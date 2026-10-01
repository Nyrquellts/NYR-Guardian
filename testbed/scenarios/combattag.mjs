// NYR-CombatTagPro on a live server, played by mineflayer clients NyrOp (op, watching), Kai (logs out in combat) and Luna
// (hits Kai, then his dummy). Every check reads what a client saw or what the server answers to a vanilla command:
//
//   1. Luna hits Kai: both are told they are in combat and see the action-bar countdown fall; Kai's /spawn is refused. Kai
//      quits: Luna's client sees a dummy named Kai (a mannequin on 1.21.9+, the husk fallback elsewhere). Luna kills it with
//      one blow, everyone is told, Kai's kit lies on the ground and Luna picks it up. Kai rejoins, dies with the plugin's
//      death message and has nothing: exactly one copy of the kit exists, in Luna's inventory. Luna is credited a kill.
//   2. Kai quits in combat again and rejoins before the dummy dies: the dummy goes, Kai keeps his kit and has its health.
//   3. A dummy left alone vanishes after dummy.seconds and Kai keeps everything.
//   4. The dummy is killed, then the server is killed hard before Kai rejoins: after the restart Kai still dies on joining
//      and has nothing (the kill record was on disk before anything dropped).
//   5. In a peaceful world the dummy still stands: a mannequin on 1.21.9+, a husk on Paper 1.21.8 (which can keep it), the
//      villager stand-in on older servers.

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { Vec3, command, connectWhenUp, gamerule, quitAll, sleep, waitFor } from '../lib/bots.mjs'
import { offlineUuid } from '../lib/servers.mjs'

export const jarName = 'NYR-CombatTagPro'
export const displayName = 'NYR CombatTag Pro'

const plain = (line) => String(line ?? '').replace(/§./g, '')
const DUMMY_KINDS = ['mannequin', 'husk', 'villager', 'zombie']
const KIT = { diamond: 32, iron_ingot: 7, oak_log: 64, iron_sword: 1, iron_helmet: 1 }

/** Minecraft 1.21.9 and newer have mannequins (Paper, Folia, Purpur, Spigot). */
function hasMannequin (target) {
  const [a, b = 0, c = 0] = target.mc.split('.').map(Number)
  return a > 1 || (a === 1 && (b > 21 || (b === 21 && c >= 9)))
}

/** The dummy a client should see: in a peaceful world Paper 1.21.8 keeps the husk, older servers use the villager. */
function expectedKind (target, peaceful) {
  if (hasMannequin(target)) return 'mannequin'
  if (!peaceful) return 'husk'
  return target.mc === '1.21.8' ? 'husk' : 'villager'
}

function metadataText (entity) {
  try {
    return JSON.stringify(entity.metadata ?? [], (key, value) => (typeof value === 'bigint' ? String(value) : value))
  } catch {
    return ''
  }
}

function dummyNear (bot, spot, radius = 8) {
  return Object.values(bot.entities).find((e) => DUMMY_KINDS.includes(e.name) && e.position && e.position.distanceTo(spot) <= radius) ?? null
}

function dropped (entity) {
  try {
    return entity.getDroppedItem() ?? null
  } catch {
    return null
  }
}

/** Kit stacks lying near a point as a client sees them: name -> count. */
function groundKit (bot, spot, radius = 10) {
  const out = {}
  for (const e of Object.values(bot.entities)) {
    if (e.name !== 'item' || !e.position || e.position.distanceTo(spot) > radius) continue
    const item = dropped(e)
    if (item && KIT[item.name] !== undefined) out[item.name] = (out[item.name] ?? 0) + item.count
  }
  return out
}

function watchBars (bot) {
  bot.bars = []
  bot.on('messagestr', (line, position) => {
    if (position === 'game_info') bot.bars.push({ at: Date.now(), text: plain(line) })
  })
}

async function enter (port, name) {
  const bot = await connectWhenUp(port, name)
  watchBars(bot)
  return bot
}

/** Items of one kind a player carries, asked of the server (the client's inventory cache can lag). */
async function countOn (op, player, item) {
  const reply = await command(op, `/minecraft:clear ${player} minecraft:${item} 0`, /Found \d+ matching|No items were found|No player was found/, 8000)
  const found = /Found (\d+) matching/.exec(plain(reply))
  return found ? Number(found[1]) : 0
}

async function kitCount (op, player) {
  const out = {}
  for (const item of Object.keys(KIT)) out[item] = await countOn(op, player, item)
  return out
}

const same = (a, b) => Object.keys(KIT).every((k) => (a[k] ?? 0) === (b[k] ?? 0))
const empty = (a) => Object.keys(KIT).every((k) => (a[k] ?? 0) === 0)
const show = (a) => Object.keys(KIT).map((k) => `${k} ${a[k] ?? 0}`).join(', ')

/**
 * The kit, with the helmet worn. The player's own client puts the helmet on: Folia 1.21.11 runs /item replace without
 * doing anything (no feedback, no helmet), so the command is not used.
 */
async function giveKit (op, bot) {
  const player = bot.username
  await command(op, `/minecraft:clear ${player}`)
  await command(op, `/minecraft:give ${player} minecraft:diamond 32`)
  await command(op, `/minecraft:give ${player} minecraft:iron_ingot 7`)
  await command(op, `/minecraft:give ${player} minecraft:oak_log 64`)
  await command(op, `/minecraft:give ${player} minecraft:iron_sword 1`)
  await command(op, `/minecraft:give ${player} minecraft:iron_helmet 1`)
  const helmet = await waitFor(() => bot.inventory.items().find((i) => i.name === 'iron_helmet'), 5000, 'the helmet').catch(() => null)
  if (helmet) await bot.equip(helmet, 'head').catch(() => {})
  await waitFor(() => bot.inventory.slots[5]?.name === 'iron_helmet', 3000, 'the helmet on').catch(() => {})
  await sleep(500)
}

/** The kit a bot's own client holds, once two reads half a second apart agree. */
async function clientKit (bot) {
  const read = () => {
    const out = {}
    for (const item of bot.inventory.slots) if (item && KIT[item.name] !== undefined) out[item.name] = (out[item.name] ?? 0) + item.count
    return out
  }
  let last = read()
  for (let i = 0; i < 12; i++) {
    await sleep(500)
    const now = read()
    if (same(now, last)) return now
    last = now
  }
  return last
}

async function heal (op, player) {
  await command(op, `/minecraft:effect give ${player} minecraft:instant_health 1 10 true`)
  await sleep(400)
  await command(op, `/minecraft:effect clear ${player}`)
}

/** Luna looks at the target and swings once. */
async function strike (bot, entity) {
  await bot.lookAt(entity.position.offset(0, (entity.height ?? 1.8) * 0.8, 0), true)
  bot.attack(entity)
}

function setConfig (dir, edit) {
  const file = join(dir, 'plugins/NYR-CombatTagPro/config.yml')
  const before = readFileSync(file, 'utf8')
  const after = edit(before)
  if (before === after) throw new Error('config edit changed nothing')
  writeFileSync(file, after)
}

export async function run ({ target, dir, server, check, restart, say }) {
  const port = target.port
  // matrix.mjs checks for this line after the run as a log problem; here it is also one of the scenario's own checks.
  const startLog = readFileSync(join(dir, `console-${server.attempt}.log`), 'utf8')
  const startLine = startLog.split(/\r?\n/).find((line) => /\] NYR CombatTag Pro \S+ on \S+/.test(line)) ?? ''
  check('the plugin starts and says so in the console', startLine !== '', startLine.replace(/^\[[^\]]*\]\s*/, ''))
  let op = await enter(port, 'NyrOp')
  let kai = await enter(port, 'Kai')
  let luna = await enter(port, 'Luna')
  const kaiId = offlineUuid('Kai')
  const recordFile = join(dir, 'plugins/NYR-CombatTagPro/data/killed', `${kaiId}.yml`)
  // What a player carries, asked of the server; Folia 1.21.11's /clear answers "Found 1 matching item(s)" whatever the count
  // (it reports the players it ran for), so there the players' own clients are read instead.
  const kitOf = (name) => (target.folia ? clientKit(name === 'Kai' ? kai : luna) : kitCount(op, name))

  const setup = async () => {
    await gamerule(op, 'sendCommandFeedback', true)
    await gamerule(op, 'doImmediateRespawn', true)
    await gamerule(op, 'keepInventory', false)
    await gamerule(op, 'doMobSpawning', false)
    // no natural healing, so the health a dummy carries back is exactly what it had
    await command(op, '/minecraft:gamerule naturalRegeneration false')
    await command(op, '/minecraft:gamerule natural_health_regeneration false')
    await command(op, '/minecraft:gamemode creative NyrOp')
    await sleep(300)
  }

  /** Luna next to Kai at a spot, both in survival, Kai healed with a fresh kit, Luna with a netherite sword in hand. */
  const stage = async (x, y, z) => {
    await command(op, '/minecraft:gamemode survival Kai')
    await command(op, '/minecraft:gamemode survival Luna')
    await command(op, `/minecraft:tp NyrOp ${x} ${y + 5} ${z + 5}`)
    await command(op, `/minecraft:tp Kai ${x} ${y} ${z}`)
    await command(op, `/minecraft:tp Luna ${x + 1.5} ${y} ${z}`)
    await heal(op, 'Kai')
    await giveKit(op, kai)
    await command(op, '/minecraft:clear Luna')
    await command(op, '/minecraft:effect clear Luna')
    await command(op, '/minecraft:give Luna minecraft:netherite_sword 1')
    luna.setQuickBarSlot(0)
    await sleep(1200)
  }

  /** Luna hits Kai once; resolves with Kai's health after the hit as his client sees it. */
  const lunaHitsKai = async () => {
    const seen = await waitFor(() => luna.players.Kai?.entity, 5000, 'Luna to see Kai')
    await sleep(700)
    const before = kai.health
    await strike(luna, seen)
    await waitFor(() => kai.health < before, 5000, 'Kai to take the hit').catch(() => {})
    await sleep(300)
    return kai.health
  }

  const kaiQuits = async (spot, peaceful = false) => {
    kai.quit()
    const dummy = await waitFor(() => dummyNear(luna, spot), 8000, 'the dummy to appear').catch(() => null)
    await sleep(600)
    const kind = expectedKind(target, peaceful)
    return { dummy, kind }
  }

  const kaiJoins = async () => {
    await sleep(800)
    kai = await enter(port, 'Kai')
    await sleep(1500)
  }

  try {
    await setup()
    await command(op, '/minecraft:difficulty easy')
    const y = Math.floor(kai.entity.position.y)
    /**
     * Luna steps away from the dummy she just struck before its drops can be picked up (10 ticks after they land), but only
     * after the blow has certainly reached the server: on Spigot a teleport sent at once can arrive before the attack and
     * leave Luna out of reach.
     */
    const stepBack = async (spot) => {
      await sleep(250)
      await command(op, `/minecraft:tp Luna ${spot.x + 7} ${y} ${spot.z}`)
    }
    const lethal = async () => {
      await command(op, '/minecraft:effect give Luna minecraft:strength 30 9 true')
      await sleep(900)
    }

    // 1. Tag, countdown, refused command, the dummy, its death, the owner's death on joining.
    const spot1 = new Vec3(20.5, y, 20.5)
    await stage(spot1.x, y, spot1.z)
    const statsWork = !target.folia
    if (statsWork) await command(op, '/minecraft:scoreboard objectives add ctkills minecraft.custom:minecraft.player_kills')
    const kaiFrom = kai.lines.length
    const lunaFrom = luna.lines.length
    kai.bars.length = 0
    luna.bars.length = 0
    const kaiHealth = await lunaHitsKai()
    // The first bars say 15s (14.3 s left shows as 15); wait until one says 13s or less.
    await waitFor(() => kai.bars.some((b) => /In combat/.test(b.text) && Number(/(\d+)s/.exec(b.text)?.[1] ?? 99) <= 13) && luna.bars.length >= 2,
      5000, 'the countdown to fall').catch(() => {})
    const kaiLines = kai.lines.slice(kaiFrom).map(plain)
    const lunaLines = luna.lines.slice(lunaFrom).map(plain)
    check('Kai is told he is in combat', kaiLines.some((l) => /You are in combat for 15s/.test(l)), kaiLines.join(' | '))
    check('Luna, who hit him, is told she is in combat too', lunaLines.some((l) => /You are in combat for 15s/.test(l)), lunaLines.join(' | '))
    const secondsOf = (bar) => Number(/(\d+)s/.exec(bar.text)?.[1] ?? NaN)
    const kaiBars = kai.bars.filter((b) => /In combat/.test(b.text))
    const lunaBars = luna.bars.filter((b) => /In combat/.test(b.text))
    check('both see the combat countdown on the action bar', kaiBars.length > 0 && lunaBars.length > 0,
      `Kai: ${kaiBars.map((b) => b.text).join(' / ')} | Luna: ${lunaBars.map((b) => b.text).join(' / ')}`)
    check('the countdown falls', kaiBars.length >= 2 && secondsOf(kaiBars[kaiBars.length - 1]) < secondsOf(kaiBars[0]),
      kaiBars.map((b) => b.text).join(' / '))
    const refused = await command(kai, '/spawn', /cannot use \/spawn in combat|Unknown|unknown/, 6000).catch(() => null)
    check('Kai\'s /spawn is refused in combat', /cannot use \/spawn in combat/.test(plain(refused)), plain(refused))
    const namespaced = await command(kai, '/essentials:home', /cannot use \/home in combat|Unknown|unknown/, 6000).catch(() => null)
    check('a plugin: prefix does not get around the list', /cannot use \/home in combat/.test(plain(namespaced)), plain(namespaced))

    const quit1 = await kaiQuits(spot1)
    const dummy1 = quit1.dummy
    check(`Luna sees a ${quit1.kind} dummy named Kai where Kai stood`, dummy1 && dummy1.name === quit1.kind && metadataText(dummy1).includes('Kai'),
      dummy1 ? `${dummy1.name} at ${dummy1.position} ${metadataText(dummy1).slice(0, 200)}` : 'no dummy')
    if (dummy1) {
      // prismarine-entity equipment: 0 main hand, 1 off hand, 2 boots, 3 leggings, 4 chestplate, 5 helmet
      const worn = (dummy1.equipment ?? []).map((item) => item?.name ?? null)
      check('the dummy wears Kai\'s helmet and holds what Kai held', worn[5] === 'iron_helmet' && worn[0] === 'diamond', JSON.stringify(worn))
    }
    if (quit1.kind === 'mannequin') {
      const text = metadataText(dummy1)
      const at = text.indexOf('Logged out in combat')
      check('the mannequin says "Logged out in combat" under its name, not "NPC"', at >= 0 && !text.includes('mannequin.label'),
        at >= 0 ? text.slice(Math.max(0, at - 120), at + 40) : text.slice(0, 300))
    }
    const listed = await command(op, '/combattag dummies', /- Kai \(|none/, 6000).catch(() => null)
    const listedHealth = Number(/, ([\d.]+) health/.exec(plain(listed))?.[1] ?? NaN)
    check('the dummy has Kai\'s health', Math.abs(listedHealth - kaiHealth) < 0.6, `dummy ${plain(listed)}; Kai had ${kaiHealth}`)

    if (dummy1) {
      await lethal()
      const broadcastFrom = op.lines.length
      await strike(luna, dummy1)
      await stepBack(spot1)
      await waitFor(() => !luna.entities[dummy1.id], 6000, 'the dummy to die').catch(() => {})
      check('Luna kills the dummy', !luna.entities[dummy1.id], dummy1.id)
      await waitFor(() => Object.keys(groundKit(luna, spot1)).length >= 5, 6000, 'the kit on the ground').catch(() => {})
      await sleep(800)
      const ground = groundKit(luna, spot1)
      check('Luna sees Kai\'s kit on the ground where the dummy died', same(ground, KIT), show(ground))
      const told = op.lines.slice(broadcastFrom).map(plain)
      check('everyone is told who killed the dummy', told.some((l) => /Kai logged out in combat and their dummy was killed by Luna/.test(l)), told.join(' | '))
      check('the kill is on disk before Kai comes back', existsSync(recordFile), recordFile)
      await command(op, '/minecraft:effect clear Luna')
      for (const e of Object.values(luna.entities).filter((e) => e.name === 'item' && e.position.distanceTo(spot1) < 10)) {
        await command(op, `/minecraft:tp Luna ${e.position.x.toFixed(2)} ${Math.floor(e.position.y)} ${e.position.z.toFixed(2)}`)
        await sleep(600)
      }
      await sleep(1000)
      const deathFrom = op.lines.length
      await kaiJoins()
      const kaiJoinLines = kai.lines.map(plain)
      check('Kai is told on joining who killed his dummy', kaiJoinLines.some((l) => /Luna killed your dummy/.test(l)), kaiJoinLines.join(' | '))
      await waitFor(() => op.lines.slice(deathFrom).map(plain).some((l) => /Kai died in combat: slain by Luna while logged out/.test(l)), 10000, 'Kai\'s death').catch(() => {})
      const deathLines = op.lines.slice(deathFrom).map(plain)
      check('Kai dies on joining, with the plugin\'s death message', deathLines.some((l) => /Kai died in combat: slain by Luna while logged out/.test(l)), deathLines.join(' | '))
      await sleep(1500)
      const kaiNow = await kitOf('Kai')
      check('after respawning Kai has nothing of his kit', empty(kaiNow), show(kaiNow))
      const lunaNow = await kitOf('Luna')
      const groundNow = groundKit(op, spot1, 16)
      check('exactly one copy of the kit exists: the one Luna picked up', same(lunaNow, KIT) && empty(groundNow),
        `Luna ${show(lunaNow)}; ground ${show(groundNow)}; Kai ${show(kaiNow)}`)
      check('the record is gone once applied', !existsSync(recordFile), recordFile)
      if (statsWork) {
        const kills = await command(op, '/minecraft:scoreboard players get Luna ctkills', /Luna has \d+|Can't get value|none is set/, 6000).catch(() => null)
        check('Luna is credited with a player kill', /Luna has 1 /.test(plain(kills) + ' '), plain(kills))
      }
    }

    // 2. Kai comes back before his dummy dies: dummy gone, kit kept, its health carried.
    const spot2 = new Vec3(40.5, y, 20.5)
    await stage(spot2.x, y, spot2.z)
    await lunaHitsKai()
    const quit2 = await kaiQuits(spot2)
    if (quit2.dummy) {
      await sleep(900)
      await strike(luna, quit2.dummy)
      await sleep(1200)
      const standing = await command(op, '/combattag dummies', /- Kai \(|none/, 6000).catch(() => null)
      const dummyHealth = Number(/, ([\d.]+) health/.exec(plain(standing))?.[1] ?? NaN)
      check('Luna\'s hit hurt the standing dummy', dummyHealth > 0 && dummyHealth < 19, plain(standing))
      await kaiJoins()
      await waitFor(() => !luna.entities[quit2.dummy.id], 5000, 'the dummy to go').catch(() => {})
      check('when Kai comes back first the dummy goes', !luna.entities[quit2.dummy.id], quit2.dummy.id)
      check('Kai has the dummy\'s health, not a healed one', Math.abs(kai.health - dummyHealth) < 0.6, `Kai ${kai.health}, dummy ${dummyHealth}`)
      const kept = await kitOf('Kai')
      check('Kai keeps his kit', same(kept, KIT), show(kept))
      check('Kai is told he is in combat again', kai.lines.map(plain).some((l) => /You came back before your dummy died/.test(l)), kai.lines.map(plain).join(' | '))
      check('nothing dropped', empty(groundKit(luna, spot2)), show(groundKit(luna, spot2)))
    } else {
      check(`Luna sees a ${quit2.kind} dummy when Kai logs out again`, false, 'no dummy')
    }

    // 3. A dummy nobody kills vanishes after dummy.seconds; Kai keeps everything.
    setConfig(dir, (text) => text.replace('  seconds: 30', '  seconds: 8'))
    await command(op, '/combattag reload', /Reloaded/)
    const spot3 = new Vec3(60.5, y, 20.5)
    await stage(spot3.x, y, spot3.z)
    await lunaHitsKai()
    const quit3 = await kaiQuits(spot3)
    check('a dummy stands after logging out in combat', Boolean(quit3.dummy), quit3.dummy?.name)
    await sleep(6000)
    check('it still stands after 6 s', quit3.dummy && Boolean(luna.entities[quit3.dummy.id]), quit3.dummy?.id)
    await waitFor(() => quit3.dummy && !luna.entities[quit3.dummy.id], 6000, 'the dummy to vanish').catch(() => {})
    check('a dummy left alone vanishes after dummy.seconds (8 s)', quit3.dummy && !luna.entities[quit3.dummy.id], quit3.dummy?.id)
    const expiredFrom = op.lines.length
    await kaiJoins()
    await sleep(1000)
    const afterExpiry = await kitOf('Kai')
    check('Kai keeps his kit after his dummy survived', same(afterExpiry, KIT), show(afterExpiry))
    check('and does not die', !op.lines.slice(expiredFrom).map(plain).some((l) => /Kai died/.test(l)) && kai.health > 0, `health ${kai.health}`)
    setConfig(dir, (text) => text.replace('  seconds: 8', '  seconds: 30'))
    await command(op, '/combattag reload', /Reloaded/)

    // 4. The dummy dies, the server is killed before Kai comes back: after the restart the kill still applies.
    const spot4 = new Vec3(80.5, y, 20.5)
    await stage(spot4.x, y, spot4.z)
    await lunaHitsKai()
    const quit4 = await kaiQuits(spot4)
    if (quit4.dummy) {
      await lethal()
      await strike(luna, quit4.dummy)
      await stepBack(spot4)
      await waitFor(() => !luna.entities[quit4.dummy.id], 6000, 'the dummy to die').catch(() => {})
      await sleep(1000)
      check('before the hard kill the kill record is on disk', existsSync(recordFile), recordFile)
      quitAll([op, luna])
      await sleep(500)
      await restart({ hard: true })
      op = await enter(port, 'NyrOp')
      luna = await enter(port, 'Luna')
      await sleep(1000)
      const hardFrom = op.lines.length
      await kaiJoins()
      await waitFor(() => op.lines.slice(hardFrom).map(plain).some((l) => /Kai died in combat/.test(l)), 10000, 'Kai\'s death').catch(() => {})
      check('after a hard kill Kai still dies on joining', op.lines.slice(hardFrom).map(plain).some((l) => /Kai died in combat: slain by Luna while logged out/.test(l)),
        op.lines.slice(hardFrom).map(plain).join(' | '))
      await sleep(1500)
      const afterCrash = await kitOf('Kai')
      check('after a hard kill Kai has nothing of his kit', empty(afterCrash), show(afterCrash))
      const lunaAfter = await kitOf('Luna')
      await command(op, `/minecraft:tp NyrOp ${spot4.x} ${y + 3} ${spot4.z}`)
      await sleep(2000)
      const groundAfter = groundKit(op, spot4, 16)
      check('no more than one copy of the kit exists after the crash', Object.keys(KIT).every((k) => (afterCrash[k] ?? 0) + (lunaAfter[k] ?? 0) + (groundAfter[k] ?? 0) <= KIT[k]),
        `Kai ${show(afterCrash)}; Luna ${show(lunaAfter)}; ground ${show(groundAfter)}`)
      check('the record is gone once applied after the restart', !existsSync(recordFile), recordFile)
      await setup()
    } else {
      check('a dummy stands before the hard kill', false, 'no dummy')
    }

    // 5. Peaceful: the dummy still stands.
    await command(op, '/minecraft:difficulty peaceful')
    const spot5 = new Vec3(100.5, y, 20.5)
    await stage(spot5.x, y, spot5.z)
    await lunaHitsKai()
    const quit5 = await kaiQuits(spot5, true)
    await sleep(1500)
    const stillThere = quit5.dummy && luna.entities[quit5.dummy.id]
    check(`in a peaceful world the dummy stands: a ${quit5.kind}`, Boolean(stillThere) && quit5.dummy.name === quit5.kind && metadataText(quit5.dummy).includes('Kai'),
      quit5.dummy ? `${quit5.dummy.name}, still there: ${Boolean(stillThere)}` : 'no dummy')
    await kaiJoins()
    const peacefulKit = await kitOf('Kai')
    check('coming back in the peaceful world takes the dummy back and keeps the kit', same(peacefulKit, KIT) && (!quit5.dummy || !luna.entities[quit5.dummy.id]),
      show(peacefulKit))
    await command(op, '/minecraft:difficulty easy')

    // counts since the last (hard) start: the peaceful step's dummy, taken back
    const status = await command(op, '/combattag status', /Since start/, 6000).catch(() => null)
    check('/combattag status counts the dummies since the restart', /dummies [1-9]/.test(plain(status)) && /returned [1-9]/.test(plain(status)), plain(status))
    say(`ground y ${y}; dummy on this server: ${expectedKind(target, false)}`)
  } finally {
    quitAll([op, kai, luna])
    await sleep(500)
  }
}
