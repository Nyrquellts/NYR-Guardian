// NYR-SmartTick on a live server. A trading hall of four 1x1 glass cells, each with a farmer claiming the composter in
// front of it, and two free farmers with beds on open ground, far from spawn so the hall's chunks unload when players
// leave. Checked from the players' own clients and the server's own entity data:
//   - while the server keeps up nobody sleeps; with thresholds every reading passes (a forced "behind") exactly the four
//     cell villagers sleep through the platform's real load signal, and wake when the thresholds are put back;
//   - /smarttick sleep sleeps exactly the cell villagers; a bot trades with a sleeping one; it is restocked asleep;
//   - /smarttick wakeall wakes them all; the villager count never changes;
//   - leaving the hall wakes its sleepers before their chunks unload; a clean restart and a hard kill leave nobody asleep;
//   - after wakeall, a stop and removing the jar, the server's own entity data shows every villager awake.

import { existsSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { command, connectWhenUp, gamerule, quitAll, sleep, waitFor } from '../lib/bots.mjs'

export const jarName = 'NYR-SmartTick'
export const displayName = 'NYR SmartTick'

/**
 * An autosave every 100 ticks, so a hard kill loses only the last five seconds (Folia's save-all saves nothing). On Spigot,
 * the setting the README asks Spigot owners for: Spigot gives even villagers next to a player one "inactive" tick in four,
 * and with tick-inactive-villagers on it runs their brains there, asleep or not.
 */
export function files (target) {
  const out = { 'bukkit.yml': ['ticks-per:', '  autosave: 100', ''].join('\n') }
  if (target.spigot) {
    out['spigot.yml'] = ['world-settings:', '  default:', '    entity-activation-range:', '      tick-inactive-villagers: false', ''].join('\n')
  }
  return out
}

const plain = (text) => String(text ?? '').replace(/§./g, '')
/** The hall: 20 chunks from spawn, so no spawn chunk keeps it loaded. */
const HALL = { x: 322, z: 322 }
const CELLS = [0, 3, 6, 9].map((dx, i) => ({ tag: `st-cell${i + 1}`, x: HALL.x + dx, z: HALL.z }))
const FREE = [
  { tag: 'st-free1', x: HALL.x + 1, z: HALL.z + 8, jobX: HALL.x + 1, jobZ: HALL.z + 10, bedX: HALL.x + 3, bedZ: HALL.z + 8 },
  { tag: 'st-free2', x: HALL.x + 8, z: HALL.z + 8, jobX: HALL.x + 8, jobZ: HALL.z + 10, bedX: HALL.x + 10, bedZ: HALL.z + 8 }
]

/** Sends a command and returns every chat line that arrives within {@code waitMs}, colour codes removed. */
async function ask (bot, text, waitMs = 1200) {
  const from = bot.lines.length
  bot.chat(text)
  await sleep(waitMs)
  return bot.lines.slice(from).map(plain)
}

/** /smarttick status, parsed. */
async function status (op) {
  const text = (await ask(op, '/smarttick status', 1500)).join('\n')
  const scan = /Villagers at the last scan: (\d+) loaded, (\d+) eligible, (\d+) asleep, (\d+) put to sleep by other plugins/.exec(text)
  const totals = /Since start: (\d+) put to sleep, (\d+) woken, (\d+) restocked asleep; woken as their chunk unloaded (\d+), as it loaded (\d+), kept asleep on load (\d+)/.exec(text)
  const n = (m, i) => (m ? Number(m[i]) : NaN)
  return {
    text,
    loaded: n(scan, 1), eligible: n(scan, 2), asleep: n(scan, 3), others: n(scan, 4),
    slept: n(totals, 1), woken: n(totals, 2), restocked: n(totals, 3), unload: n(totals, 4), load: n(totals, 5), kept: n(totals, 6),
    reading: /Load: ([^\n]*)/.exec(text)?.[1] ?? '',
    mode: /Mode: ([^\n]*)/.exec(text)?.[1] ?? ''
  }
}

/** /smarttick check: each villager near the op with its state and reason, straight from the server. */
async function near (op) {
  const lines = await ask(op, '/smarttick check 24', 1500)
  return lines.map((line) => /-\s+(asleep|awake|unaware \(another plugin\))\s+(\S+) at (-?\d+) (-?\d+) (-?\d+): (.*)$/.exec(line)).filter(Boolean)
    .map((m) => ({ state: m[1], profession: m[2], x: Number(m[3]), y: Number(m[4]), z: Number(m[5]), reason: m[6] }))
}

const inCell = (v) => CELLS.some((c) => v.x === c.x && v.z === c.z)
const describe = (list) => list.map((v) => `${v.state}@${v.x},${v.z}:${v.reason}`).join(' | ')

/** The server's own saved flag, Bukkit.Aware, per tag: true awake, false asleep, null where /data does not answer. */
async function awareByData (op, tag) {
  // The key has a dot in its name, so the NBT path quotes it.
  const lines = await ask(op, `/minecraft:data get entity @e[type=minecraft:villager,tag=${tag},limit=1] "Bukkit.Aware"`, 900)
  const m = lines.map((l) => /entity data: ([01])b/.exec(l)).find(Boolean)
  return m ? m[1] === '1' : null
}

async function awareAll (op) {
  const out = {}
  for (const v of [...CELLS, ...FREE]) out[v.tag] = await awareByData(op, v.tag)
  return out
}

const villagersSeen = (bot) => Object.values(bot.entities).filter((e) => e.name === 'villager' &&
  Math.abs(e.position.x - (HALL.x + 5)) < 40 && Math.abs(e.position.z - (HALL.z + 5)) < 40).length

async function waitStatus (op, predicate, timeoutMs, what) {
  let last = null
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    last = await status(op)
    if (predicate(last)) return last
    await sleep(1500)
  }
  throw new Error(`timed out waiting for ${what}; last status: ${last?.text?.replace(/\n/g, ' | ')}`)
}

function configFile (dir) {
  return join(dir, 'plugins', 'NYR-SmartTick', 'config.yml')
}

/**
 * Renames the plugin jar (and Paper's remapped copy) out of the plugins folder once the server process has let go of it.
 * The loaded copy stays locked on Windows until the JVM exits, so a successful rename also says the server is gone.
 */
async function removeJarAfterExit (dir, timeoutMs = 120_000) {
  const plugins = join(dir, 'plugins')
  const remapped = join(plugins, '.paper-remapped')
  const candidates = [
    ...(existsSync(remapped) ? readdirSync(remapped).filter((f) => f.startsWith(jarName)).map((f) => join(remapped, f)) : []),
    ...readdirSync(plugins).filter((f) => f.startsWith(jarName) && f.endsWith('.jar')).map((f) => join(plugins, f))
  ]
  const deadline = Date.now() + timeoutMs
  for (const file of candidates) {
    for (;;) {
      try {
        renameSync(file, `${file}.removed`)
        break
      } catch (locked) {
        if (Date.now() > deadline) throw new Error(`${file} is still locked: ${locked.message}`)
        await sleep(1000)
      }
    }
  }
  return candidates.length
}

export async function run ({ target, dir, check, restart, say }) {
  const port = target.port
  let op = await connectWhenUp(port, 'NyrOp')
  let kai = await connectWhenUp(port, 'Kai')
  let y = 0
  const reconnect = async () => {
    quitAll([op, kai])
    await sleep(500)
    op = await connectWhenUp(port, 'NyrOp')
    kai = await connectWhenUp(port, 'Kai')
    await sleep(1000)
    await goToHall()
  }
  // Kai first: on Folia a command can only teleport a player in the op's own region, so the op moves last.
  const goToHall = async () => {
    await command(op, `/minecraft:tp Kai ${CELLS[0].x + 0.5} ${y} ${CELLS[0].z + 2.5}`)
    await command(op, `/minecraft:tp NyrOp ${HALL.x + 5.5} ${y} ${HALL.z + 4.5}`)
    await sleep(2500)
  }
  try {
    await gamerule(op, 'sendCommandFeedback', true)
    await gamerule(op, 'doDaylightCycle', false)
    await gamerule(op, 'doMobSpawning', false)
    await command(op, '/minecraft:time set 3000')
    y = Math.floor(op.entity.position.y)
    await goToHall()
    await command(op, `/minecraft:kill @e[type=!minecraft:player,x=${HALL.x + 5},y=${y},z=${HALL.z + 5},distance=..40]`)
    await sleep(500)

    // The hall: a glass back wall, glass side walls at feet and head height, a composter in front of each cell with air
    // above it (players trade over it) and a glass roof over the cells.
    await command(op, `/minecraft:fill ${HALL.x - 1} ${y} ${HALL.z - 1} ${HALL.x + 10} ${y + 1} ${HALL.z - 1} minecraft:glass`)
    for (const c of CELLS) {
      await command(op, `/minecraft:fill ${c.x - 1} ${y} ${c.z} ${c.x - 1} ${y + 1} ${c.z} minecraft:glass`)
      await command(op, `/minecraft:fill ${c.x + 1} ${y} ${c.z} ${c.x + 1} ${y + 1} ${c.z} minecraft:glass`)
      await command(op, `/minecraft:setblock ${c.x} ${y} ${c.z + 1} minecraft:composter`)
    }
    await command(op, `/minecraft:fill ${HALL.x - 1} ${y + 2} ${HALL.z - 1} ${HALL.x + 10} ${y + 2} ${HALL.z} minecraft:glass`)
    // Cell 4's job site gets broken below: glass over its composter keeps the gap one block high, so it cannot walk out.
    await command(op, `/minecraft:setblock ${CELLS[3].x} ${y + 1} ${CELLS[3].z + 1} minecraft:glass`)
    // A chat command may be 256 characters long at most: short ids, no defaults.
    const offers = 'Offers:{Recipes:[{buy:{id:wheat,count:20},sell:{id:emerald,count:1},maxUses:12,xp:2}]}'
    const farmer = (tag, level = 2, xp = 10) => `{VillagerData:{profession:farmer,level:${level}},Xp:${xp},Tags:[st,${tag}],${offers}}`
    // Cell 1 is one trade short of its next level; cell 4 was never traded with, so it loses its profession with its job site.
    for (const [i, c] of CELLS.entries()) {
      const nbt = i === 0 ? farmer(c.tag, 1, 9) : i === 3 ? farmer(c.tag, 1, 0) : farmer(c.tag)
      await command(op, `/minecraft:summon minecraft:villager ${c.x + 0.5} ${y} ${c.z + 0.5} ${nbt}`)
    }

    // Each cell villager claims the composter in front of it (the game's own job search), before the free ones exist.
    const claimed = await waitFor(async () => {
      const list = await near(op)
      return list.filter((v) => inCell(v) && v.reason === 'may sleep: walled in').length === 4 ? list : (await sleep(2000), null)
    }, 120_000, 'the four cell villagers to claim their composters').catch(() => null)
    check('the four cell villagers claim their job sites and read as walled in', claimed !== null, claimed ? describe(claimed) : describe(await near(op)))

    for (const f of FREE) {
      await command(op, `/minecraft:setblock ${f.jobX} ${y} ${f.jobZ} minecraft:composter`)
      await command(op, `/minecraft:setblock ${f.bedX} ${y} ${f.bedZ} minecraft:white_bed[part=foot,facing=south]`)
      await command(op, `/minecraft:setblock ${f.bedX} ${y} ${f.bedZ + 1} minecraft:white_bed[part=head,facing=south]`)
      await command(op, `/minecraft:summon minecraft:villager ${f.x + 0.5} ${y} ${f.z + 0.5} ${farmer(f.tag)}`)
    }
    const bedded = await waitFor(async () => {
      const list = await near(op)
      return list.filter((v) => !inCell(v) && /has a bed/.test(v.reason)).length === 2 ? list : (await sleep(2000), null)
    }, 120_000, 'the free villagers to claim beds').catch(() => null)
    check('the two free villagers claim beds and composters', bedded !== null, bedded ? describe(bedded) : describe(await near(op)))
    const count0 = villagersSeen(op)
    check('six villagers stand in the hall', count0 === 6, count0)

    // 1. The server keeps up: nobody sleeps.
    const idle = await waitStatus(op, (s) => s.loaded === 6, 20_000, 'a scan that saw the six villagers').catch((e) => ({ text: e.message }))
    check('while the server keeps up nobody sleeps', idle.asleep === 0 && idle.eligible === 4, idle.text)
    check('/smarttick names the load it reads', /ms per tick|TPS|region/.test(idle.reading ?? ''), `${idle.reading} | ${idle.mode}`)
    say(`load: ${idle.reading}`)

    // 2. Behind, through the platform's real load signal: thresholds every reading passes, then the defaults again.
    const file = configFile(dir)
    const original = readFileSync(file, 'utf8')
    const forced = original.replace('sleep-at: 40', 'sleep-at: 0').replace('wake-at: 35', 'wake-at: 0')
      .replace('sleep-below: 19.0', 'sleep-below: 21').replace('wake-at: 19.8', 'wake-at: 21')
    check('the thresholds can be forced in config.yml', forced !== original && (forced.match(/: 0\n/g) ?? []).length >= 2, 'config edit')
    writeFileSync(file, forced)
    const opFrom = op.lines.length
    await command(op, '/smarttick reload', /Reloaded config\.yml/)
    const behind = await waitStatus(op, (s) => s.asleep === 4, 45_000, 'the load signal to sleep the cell villagers').catch((e) => ({ text: e.message }))
    const behindNear = await near(op)
    check('behind by the load signal, exactly the four cell villagers sleep', behind.asleep === 4 &&
      behindNear.filter((v) => v.state === 'asleep').length === 4 && behindNear.filter((v) => v.state === 'asleep').every(inCell),
    `${behind.text?.replace(/\n/g, ' | ')} || ${describe(behindNear)}`)
    const alerted = op.lines.slice(opFrom).map(plain)
    check('staff are alerted that the server fell behind', alerted.some((l) => /The server is behind|Regions fell behind/.test(l)), alerted.slice(-4).join(' | '))
    writeFileSync(file, original)
    const opFrom2 = op.lines.length
    await command(op, '/smarttick reload', /Reloaded config\.yml/)
    const recovered = await waitStatus(op, (s) => s.asleep === 0 && s.loaded === 6, 60_000, 'the load signal to wake them').catch((e) => ({ text: e.message }))
    check('caught up by the load signal, they wake', recovered.asleep === 0, recovered.text?.replace(/\n/g, ' | '))
    await sleep(1500)
    const alerted2 = op.lines.slice(opFrom2).map(plain)
    check('staff are told the server caught up', alerted2.some((l) => /caught up|No region is behind/.test(l)), alerted2.slice(-4).join(' | '))

    // 3. /smarttick sleep: exactly the cell villagers.
    await command(op, '/smarttick sleep', /Every eligible villager goes to sleep/)
    const slept = await waitStatus(op, (s) => s.asleep === 4, 30_000, 'the cell villagers to sleep').catch((e) => ({ text: e.message }))
    const sleptNear = await near(op)
    check('/smarttick sleep sleeps exactly the four cell villagers', slept.asleep === 4 &&
      sleptNear.filter((v) => v.state === 'asleep').length === 4 && sleptNear.filter((v) => v.state === 'asleep').every(inCell) &&
      sleptNear.filter((v) => !inCell(v)).every((v) => v.state === 'awake'), describe(sleptNear))
    const data = await awareAll(op)
    const dataWorks = Object.values(data).every((v) => v !== null)
    if (dataWorks) {
      check('the server saved Bukkit.Aware false on the cell villagers and true on the free ones',
        CELLS.every((c) => data[c.tag] === false) && FREE.every((f) => data[f.tag] === true), JSON.stringify(data))
    } else {
      say(`/data get entity does not answer here: ${JSON.stringify(data)}; read through /smarttick check`)
    }

    // 4. Trading with a sleeping villager, outside work hours: awake for the trade, its own brain cannot restock it then.
    await command(op, '/minecraft:time set 10000')
    await command(op, '/minecraft:give Kai minecraft:wheat 64')
    await command(op, `/minecraft:tp Kai ${CELLS[0].x + 0.5} ${y} ${CELLS[0].z + 2.5}`)
    const nearestVillager = () => Object.values(kai.entities).filter((e) => e.name === 'villager' && e.position.distanceTo(kai.entity.position) < 4)
      .sort((a, b) => a.position.distanceTo(kai.entity.position) - b.position.distanceTo(kai.entity.position))[0]
    const cell1 = await waitFor(() => nearestVillager(), 15_000, 'Kai to see the cell 1 villager').catch(() => null)
    const beforeTrade = (await near(op)).find((v) => v.x === CELLS[0].x && v.z === CELLS[0].z)
    check('the villager Kai trades with is asleep', beforeTrade?.state === 'asleep', describe(beforeTrade ? [beforeTrade] : []))
    let traded = false
    let tradeDetail = ''
    try {
      await kai.lookAt(cell1.position.offset(0, 1.4, 0), true)
      const window = await Promise.race([kai.openVillager(cell1), sleep(10_000).then(() => { throw new Error('no trade window within 10 s') })])
      tradeDetail = `trades: ${window.trades.map((t) => `${t.inputItem1?.name}x${t.inputItem1?.count}->${t.outputItem?.name} uses ${t.nbTradeUses}/${t.maximumNbTradeUses}`).join(', ')}`
      const whileOpen = (await near(op)).find((v) => v.x === CELLS[0].x && v.z === CELLS[0].z)
      tradeDetail += `; while open: ${whileOpen?.state} (${whileOpen?.reason})`
      await Promise.race([kai.trade(window, 0, 1), sleep(10_000).then(() => { throw new Error('the trade did not finish within 10 s') })])
      window.close()
      traded = true
    } catch (error) {
      tradeDetail += `; ${error.message}`
      try { kai.currentWindow && kai.closeWindow(kai.currentWindow) } catch { /* closed */ }
    }
    await sleep(1000)
    const emeralds = (await ask(op, '/minecraft:clear Kai minecraft:emerald 0', 1000)).find((l) => /Found|No items/.test(l)) ?? ''
    check('a bot opens a sleeping villager\'s trades and completes a trade', traded && /Found 1 matching/.test(emeralds), `${tradeDetail}; ${emeralds}`)

    // 5. Restocked while asleep: still outside work hours it falls asleep again after the trade; back in work hours
    // SmartTick restocks it.
    const resleep = await waitFor(async () => {
      const v = (await near(op)).find((x) => x.x === CELLS[0].x && x.z === CELLS[0].z)
      return v?.state === 'asleep' ? v : (await sleep(1500), null)
    }, 40_000, 'the traded villager to sleep again').catch(() => null)
    check('after the trade the villager falls asleep again', resleep !== null, resleep ? resleep.reason : describe(await near(op)))
    const beforeRestock = await status(op)
    await command(op, '/minecraft:time set 3000')
    const restocked = await waitStatus(op, (s) => s.restocked > beforeRestock.restocked, 20_000, 'a restock while asleep').catch((e) => ({ text: e.message }))
    check('a sleeping villager next to its job site is restocked in work hours', restocked.restocked > beforeRestock.restocked, restocked.text?.replace(/\n/g, ' | '))
    let usesAfter = null
    let level = null
    try {
      let tradeList = null
      const onTrades = (packet) => { tradeList = packet }
      kai._client.on('trade_list', onTrades)
      const again = await Promise.race([kai.openVillager(cell1), sleep(10_000).then(() => { throw new Error('no trade window') })])
      kai._client.removeListener('trade_list', onTrades)
      usesAfter = again.trades[0]?.nbTradeUses
      level = tradeList?.villagerLevel ?? null
      again.close()
    } catch (error) {
      usesAfter = error.message
    }
    check('the traded offer is back to 0 uses', usesAfter === 0, `uses ${usesAfter}`)
    check('the villager traded with while it slept levelled up, as the game does', level === 2, `level ${level} (it was 1 with 9 of 10 experience)`)

    // 5b. A player breaks a sleeping villager's job site: it wakes at once, and as the game does it loses the profession it
    // never traded in; given its job site back, it takes it again and sleeps.
    const c4 = CELLS[3]
    const before4 = (await near(op)).find((v) => v.x === c4.x && v.z === c4.z)
    check('the untraded cell villager is asleep before its job site breaks', before4?.state === 'asleep', describe(before4 ? [before4] : []))
    await command(op, `/minecraft:tp Kai ${c4.x + 0.5} ${y} ${c4.z + 2.5}`)
    await sleep(1500)
    let dug = ''
    try {
      const composter = kai.blockAt(kai.entity.position.offset(0, 0, -1).floored())
      dug = composter?.name ?? 'nothing'
      await kai.lookAt(composter.position.offset(0.5, 0.5, 0.5), true)
      await Promise.race([kai.dig(composter, true), sleep(8_000).then(() => { throw new Error('digging took over 8 s') })])
    } catch (error) {
      dug += `; ${error.message}`
    }
    await sleep(400)
    const after4 = (await near(op)).find((v) => v.x === c4.x && v.z === c4.z)
    check('a player breaking a sleeping villager job site wakes it at once', after4?.state === 'awake', `dug ${dug}; ${describe(after4 ? [after4] : [])}`)
    const lost = await waitFor(async () => {
      const v = (await near(op)).find((x) => x.x === c4.x && x.z === c4.z)
      return v && /no profession/.test(v.reason) ? v : (await sleep(1500), null)
    }, 30_000, 'the villager to lose its profession').catch(() => null)
    check('awake, it loses the profession it never traded in, as the game does', lost !== null, describe(await near(op)))
    await command(op, `/minecraft:setblock ${c4.x} ${y} ${c4.z + 1} minecraft:composter`)
    await command(op, `/minecraft:tp Kai ${CELLS[0].x + 0.5} ${y} ${CELLS[0].z + 2.5}`)
    const retaken = await waitFor(async () => {
      const v = (await near(op)).find((x) => x.x === c4.x && x.z === c4.z)
      return v?.state === 'asleep' ? v : (await sleep(2000), null)
    }, 90_000, 'the villager to take its new job site and sleep').catch(() => null)
    check('given a job site again it takes it and, still in sleep mode, sleeps again', retaken !== null, describe(await near(op)))

    // 6. /smarttick wakeall.
    const wakeFrom = op.lines.length
    await command(op, '/smarttick wakeall', /Waking every sleeping villager/)
    const woke = await waitStatus(op, (s) => s.asleep === 0 && /keeping every villager awake/.test(s.mode), 30_000, 'wakeall').catch((e) => ({ text: e.message }))
    check('/smarttick wakeall wakes every villager', woke.asleep === 0 && (await near(op)).every((v) => v.state === 'awake'), woke.text?.replace(/\n/g, ' | '))
    await waitFor(() => op.lines.slice(wakeFrom).some((l) => /Every loaded villager is awake/.test(plain(l))), 20_000, 'the done message').catch(() => {})
    check('wakeall says when every villager is awake', op.lines.slice(wakeFrom).some((l) => /Every loaded villager is awake/.test(plain(l))), op.lines.slice(wakeFrom).map(plain).slice(-3).join(' | '))
    if (dataWorks) {
      const after = await awareAll(op)
      check('the server shows Bukkit.Aware true on every villager after wakeall', Object.values(after).every((v) => v === true), JSON.stringify(after))
    }
    const count1 = villagersSeen(op)
    check('the villager count never changed', count1 === count0, `${count0} -> ${count1}`)

    // 7. Leaving the hall: sleepers wake before their chunks unload.
    await command(op, '/smarttick sleep', /Every eligible villager goes to sleep/)
    await waitStatus(op, (s) => s.asleep === 4, 30_000, 'the cell villagers to sleep').catch(() => null)
    const beforeUnload = await status(op)
    await command(op, `/minecraft:tp Kai ${HALL.x + 3000} ${y} ${HALL.z + 3}`)
    await command(op, `/minecraft:tp NyrOp ${HALL.x + 3000} ${y} ${HALL.z}`)
    const unloaded = await waitStatus(op, (s) => s.loaded === 0 && s.unload >= beforeUnload.unload + beforeUnload.asleep, 60_000, 'the hall to unload')
      .catch(() => status(op))
    check('leaving the hall wakes its four sleepers as their chunks unload', beforeUnload.asleep === 4 && unloaded.unload - beforeUnload.unload === 4,
      `asleep before ${beforeUnload.asleep}; woken as their chunk unloaded ${beforeUnload.unload} -> ${unloaded.unload}; villagers loaded now ${unloaded.loaded}`)
    await goToHall()
    const back = await waitStatus(op, (s) => s.loaded === 6, 30_000, 'the hall to load again').catch(() => status(op))
    check('back in the hall all six villagers are there', back.loaded === 6 && villagersSeen(op) === 6, `${back.loaded} loaded, ${villagersSeen(op)} seen`)
    check('none of them was saved asleep: none loaded with the mark', back.kept === unloaded.kept && back.load === unloaded.load,
      `kept asleep on load ${unloaded.kept} -> ${back.kept}, woken as loaded ${unloaded.load} -> ${back.load}`)

    // 8. A clean restart with villagers asleep.
    await waitStatus(op, (s) => s.asleep === 4, 30_000, 'the cell villagers to sleep again').catch(() => null)
    await restart({ hard: false })
    await reconnect()
    const afterStop = await waitStatus(op, (s) => s.loaded === 6, 45_000, 'a scan after the restart').catch((e) => ({ text: e.message }))
    const afterStopNear = await near(op)
    check('after a clean restart nobody is asleep', afterStop.asleep === 0 && afterStopNear.length === 6 && afterStopNear.every((v) => v.state === 'awake'),
      `${afterStop.text?.replace(/\n/g, ' | ')} || ${describe(afterStopNear)}`)
    say(`after a clean stop: woken as chunks loaded ${afterStop.load} (0 means the stop itself woke them)`)
    check('the stop itself woke them: none was loaded asleep', afterStop.load === 0 && afterStop.kept === 0,
      `woken as loaded: ${afterStop.load}, kept asleep on load ${afterStop.kept}`)
    if (dataWorks) {
      const saved = await awareAll(op)
      check('after a clean restart the server shows every villager aware', Object.values(saved).every((v) => v === true), JSON.stringify(saved))
    }

    // 9. A hard kill with villagers saved asleep.
    await command(op, '/smarttick sleep', /Every eligible villager goes to sleep/)
    const beforeKill = await waitStatus(op, (s) => s.asleep === 4, 30_000, 'the cell villagers to sleep').catch(() => status(op))
    // Saved asleep: save-all where the server honours it, and the 5 s autosave this run sets (Folia's save-all saves nothing).
    await ask(op, '/minecraft:save-all flush', 4000)
    await sleep(12_000)
    await restart({ hard: true })
    await reconnect()
    // Saved asleep, each loads with the mark: woken as it loads, or kept asleep while its load (on Folia its region, busy
    // starting up) is not yet caught up, and woken once it has been for hold-seconds.
    const killedAt = Date.now()
    const loadedBack = await waitStatus(op, (s) => s.loaded === 6, 45_000, 'a scan after the hard kill').catch(() => status(op))
    check('the four villagers were saved asleep and loaded with the mark', beforeKill.asleep === 4 && loadedBack.load + loadedBack.kept === 4,
      `asleep at the kill ${beforeKill.asleep}; after it woken as their chunk loaded ${loadedBack.load}, kept asleep on load ${loadedBack.kept}`)
    const afterKill = await waitStatus(op, (s) => s.loaded === 6 && s.asleep === 0, 60_000, 'nobody asleep after the hard kill').catch(() => status(op))
    const afterKillNear = await near(op)
    check('after a hard kill nobody is left asleep', afterKill.asleep === 0 && afterKillNear.length === 6 && afterKillNear.every((v) => v.state === 'awake'),
      `all awake ${Math.round((Date.now() - killedAt) / 1000)} s after the restart; ${afterKill.text?.replace(/\n/g, ' | ')} || ${describe(afterKillNear)}`)
    if (dataWorks) {
      const saved = await awareAll(op)
      check('after a hard kill the server shows every villager aware', Object.values(saved).every((v) => v === true), JSON.stringify(saved))
    }

    // 10. What an owner does before removing the plugin: sleep, wakeall, stop, remove the jar, start without it.
    await command(op, '/smarttick sleep', /Every eligible villager goes to sleep/)
    await waitStatus(op, (s) => s.asleep === 4, 30_000, 'the cell villagers to sleep').catch(() => null)
    const doneFrom = op.lines.length
    await command(op, '/smarttick wakeall', /Waking every sleeping villager/)
    await waitFor(() => op.lines.slice(doneFrom).some((l) => /Every loaded villager is awake/.test(plain(l))), 30_000, 'wakeall to finish').catch(() => {})
    op.chat('/stop')
    const removed = await removeJarAfterExit(dir)
    await sleep(3000)
    await restart({ hard: false })
    await reconnect()
    const plugins = await ask(op, '/plugins', 1200)
    check('the server started without SmartTick', removed > 0 && !plugins.join(' ').includes('SmartTick'), plugins.join(' | '))
    const withoutPlugin = await awareAll(op)
    if (Object.values(withoutPlugin).every((v) => v !== null)) {
      check('with the plugin removed, the server shows every villager aware', Object.values(withoutPlugin).every((v) => v === true), JSON.stringify(withoutPlugin))
    } else {
      say(`without the plugin /data does not answer: ${JSON.stringify(withoutPlugin)}`)
    }
    check('with the plugin removed, all six villagers are still there', villagersSeen(op) === 6, villagersSeen(op))
  } finally {
    quitAll([op, kai])
    await sleep(500)
  }
}
