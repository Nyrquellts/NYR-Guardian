// NYR SmartTick listing scenes, recorded from what a live Paper 1.21.11 server sent the bots.
//
// smarttick-awake records two GIFs from one server run, so they show the same hall: 1,500 villagers in 1x1 glass cells,
// each holding the composter in front of it, first awake (smarttick-awake.json) and then after /smarttick sleep
// (smarttick-asleep.json), each with Paper's own /mspt and SmartTick's own villager count. Every villager stands within
// 32 blocks of the op (the villager entity activation range), so the server ticks them all in full. Afterwards, off the
// recording, the scene switches to auto and logs what the load does (smarttick-auto.json).
//
// smarttick-trading: Kai trades with a sleeping villager; /smarttick check shows it asleep before and asleep again after.

import { Vec3, command, connectWhenUp, gamerule, sleep, waitFor } from '../../lib/bots.mjs'
import { begin, caption, chatOf, emit, save, type, watchEntities, watchInventory, watchWindows, watchWorld } from '../recorder.mjs'

const COLS = 25
const ROWS = 20
const FLOORS = 3
const X0 = 1000
const Z0 = 1000
const plain = (text) => String(text ?? '').replace(/§./g, '')

async function ask (bot, text, ms = 1500) {
  const from = bot.lines.length
  bot.chat(text)
  await sleep(ms)
  return bot.lines.slice(from).map(plain)
}

async function status (bot) {
  const text = (await ask(bot, '/smarttick status')).join('\n')
  const m = /Villagers at the last scan: (\d+) loaded, (\d+) eligible, (\d+) asleep/.exec(text)
  return m ? { loaded: +m[1], eligible: +m[2], asleep: +m[3] } : { loaded: NaN, eligible: NaN, asleep: NaN }
}

async function waitStatus (bot, ok, ms) {
  const deadline = Date.now() + ms
  let s = null
  while (Date.now() < deadline) {
    s = await status(bot)
    if (ok(s)) return s
    await sleep(2500)
  }
  return s
}

async function quiet (op) {
  await gamerule(op, 'sendCommandFeedback', true)
  await gamerule(op, 'doDaylightCycle', false)
  await command(op, '/minecraft:time set 6000')
  await command(op, '/minecraft:weather clear')
  await gamerule(op, 'doMobSpawning', false)
  await gamerule(op, 'randomTickSpeed', 0)
  await gamerule(op, 'announceAdvancements', false)
}

/**
 * Floors of COLS x ROWS cells: glass walls two blocks high, a composter in front of each cell with glass above it, a smooth
 * stone ceiling that is the next floor's floor; the top floor has no ceiling, so the camera looks into it.
 */
async function buildHall (op, y) {
  const x1 = X0 + 2 * COLS - 1
  const z1 = Z0 + 3 * ROWS - 1
  await command(op, `/minecraft:fill ${X0 - 1} ${y} ${Z0 - 1} ${x1} ${y + 1} ${z1} minecraft:glass`, /filled|Success/i, 20_000)
  await command(op, `/minecraft:fill ${X0} ${y} ${Z0} ${X0} ${y + 1} ${Z0} minecraft:air`, /filled|Success/i)
  await command(op, `/minecraft:setblock ${X0} ${y} ${Z0 + 1} minecraft:composter`, /Changed|Success/i)
  for (let width = 1; width < COLS;) {
    const copy = Math.min(width, COLS - width)
    await command(op, `/minecraft:clone ${X0} ${y} ${Z0} ${X0 + 2 * copy - 1} ${y + 1} ${Z0 + 2} ${X0 + 2 * width} ${y} ${Z0}`, /cloned|Success/i, 20_000)
    width += copy
  }
  for (let rows = 1; rows < ROWS;) {
    const copy = Math.min(rows, ROWS - rows)
    await command(op, `/minecraft:clone ${X0 - 1} ${y} ${Z0} ${x1} ${y + 1} ${Z0 + 3 * copy - 1} ${X0 - 1} ${y} ${Z0 + 3 * rows}`, /cloned|Success/i, 20_000)
    rows += copy
  }
  await command(op, `/minecraft:fill ${X0 - 1} ${y + 2} ${Z0 - 1} ${x1} ${y + 2} ${z1} minecraft:smooth_stone`, /filled|Success/i, 20_000)
  for (let floor = 1; floor < FLOORS; floor++) {
    await command(op, `/minecraft:clone ${X0 - 1} ${y} ${Z0 - 1} ${x1} ${y + 2} ${z1} ${X0 - 1} ${y + 3 * floor} ${Z0 - 1}`, /cloned|Success/i, 20_000)
  }
  await command(op, `/minecraft:fill ${X0 - 1} ${y + 3 * FLOORS - 1} ${Z0 - 1} ${x1} ${y + 3 * FLOORS - 1} ${z1} minecraft:air`, /filled|Success/i, 20_000)
}

async function mspt (bot, name) {
  await type(bot, name, '/mspt', 500)
  await sleep(1500)
}

async function hall ({ port }) {
  const op = await connectWhenUp(port, 'NyrOp')
  const luna = await connectWhenUp(port, 'Luna')
  await quiet(op)
  await command(op, '/minecraft:gamemode creative NyrOp')
  await command(op, '/minecraft:gamemode spectator Luna')
  const y = Math.floor(op.entity.position.y)
  const top = y + 3 * (FLOORS - 1)
  const stand = new Vec3(X0 + COLS + 0.5, top + 3, Z0 + 3 * Math.floor(ROWS / 2) + 0.5)
  await command(op, `/minecraft:tp Luna ${stand.x} ${stand.y + 12} ${stand.z}`)
  await command(op, `/minecraft:tp NyrOp ${stand.x} ${stand.y + 2} ${stand.z}`)
  await sleep(1000)
  op.creative.startFlying()
  await sleep(6000)
  await buildHall(op, y)
  const n = COLS * ROWS * FLOORS
  // held awake while they take their composters, and while "awake" is recorded
  await command(op, '/smarttick wakeall', /Waking every sleeping villager/)
  for (let i = 0; i < n; i++) {
    const floor = Math.floor(i / (COLS * ROWS))
    const cell = i % (COLS * ROWS)
    op.chat(`/minecraft:summon minecraft:villager ${X0 + 2 * (cell % COLS) + 0.5} ${y + 3 * floor} ${Z0 + 3 * Math.floor(cell / COLS) + 0.5} {VillagerData:{profession:farmer,level:2},Xp:10}`)
    if (i % 20 === 19) await sleep(250)
  }
  await command(op, `/minecraft:tp NyrOp ${stand.x} ${stand.y} ${stand.z}`)
  const claimed = await waitStatus(luna, (s) => s.loaded >= n && s.eligible >= n * 0.99, 360_000)
  console.log(`claimed: ${JSON.stringify(claimed)}`)
  await sleep(30_000)

  // The camera's box: the front corner of the top floor, its stone floor as the ground.
  const min = { x: X0 - 1, y: top - 1, z: Z0 - 1 }
  const max = { x: X0 + 21, y: top + 1, z: Z0 + 14 }
  const region = { min, max }
  chatOf(op, 'NyrOp')

  begin()
  let blocks = watchWorld(op, new Vec3(min.x, min.y, min.z), new Vec3(max.x, max.y, max.z))
  let stop = watchEntities(op, min, max)
  caption(`${n.toLocaleString('en-US')} villagers in a trading hall`, 'Paper 1.21.11: awake, every villager thinks every tick')
  await sleep(1500)
  await mspt(op, 'NyrOp')
  await type(op, 'NyrOp', '/smarttick status', 600)
  await sleep(5500)
  stop()
  save('smarttick-awake', { blocks, region, ground: top - 1, villagers: n })

  begin()
  blocks = watchWorld(op, new Vec3(min.x, min.y, min.z), new Vec3(max.x, max.y, max.z))
  stop = watchEntities(op, min, max)
  caption('/smarttick sleep', `The same ${n.toLocaleString('en-US')} villagers: brains asleep, none removed`)
  await sleep(1200)
  await type(op, 'NyrOp', '/smarttick sleep', 700)
  const slept = await waitStatus(luna, (s) => s.asleep >= claimed.eligible, 60_000)
  console.log(`asleep: ${JSON.stringify(slept)}`)
  emit('mark', { text: 'waited for the 5 s average' })
  await sleep(12_000)
  caption(`Asleep: the same ${n.toLocaleString('en-US')} villagers`, 'Paper 1.21.11, after /smarttick sleep')
  await mspt(op, 'NyrOp')
  await type(op, 'NyrOp', '/smarttick status', 600)
  await sleep(5500)
  stop()
  save('smarttick-asleep', { blocks, region, ground: top - 1, villagers: n })

  // Off the recording: back on auto, what the real load does, sampled every 5 s for two minutes.
  const samples = []
  await ask(op, '/smarttick auto')
  for (let i = 0; i < 24; i++) {
    await sleep(3500)
    const s = await status(luna)
    const m = (await ask(luna, '/mspt', 1200)).join(' ').match(/\d+(?:\.\d+)?\/\d+(?:\.\d+)?\/\d+(?:\.\d+)?/)
    samples.push({ s: i * 5, asleep: s.asleep, mspt5s: m ? Number(m[0].split('/')[0]) : null })
  }
  console.log(`auto: ${JSON.stringify(samples)}`)
  begin()
  save('smarttick-auto', { samples })
}

async function trading ({ port }) {
  const op = await connectWhenUp(port, 'NyrOp')
  const kai = await connectWhenUp(port, 'Kai')
  const luna = await connectWhenUp(port, 'Luna')
  await quiet(op)
  await command(op, '/minecraft:gamemode spectator Luna')
  const y = Math.floor(op.entity.position.y)
  const c = { x: 20, z: 20 }
  await command(op, `/minecraft:tp Kai ${c.x + 0.5} ${y} ${c.z + 2.5}`)
  await command(op, `/minecraft:tp NyrOp ${c.x + 3.5} ${y} ${c.z + 3.5}`)
  await command(op, `/minecraft:tp Luna ${c.x + 2.5} ${y + 2} ${c.z + 3.5}`)
  await sleep(2000)
  await command(op, `/minecraft:fill ${c.x - 1} ${y} ${c.z - 1} ${c.x + 1} ${y + 1} ${c.z - 1} minecraft:glass`, /filled|Success/i)
  await command(op, `/minecraft:fill ${c.x - 1} ${y} ${c.z} ${c.x - 1} ${y + 1} ${c.z} minecraft:glass`, /filled|Success/i)
  await command(op, `/minecraft:fill ${c.x + 1} ${y} ${c.z} ${c.x + 1} ${y + 1} ${c.z} minecraft:glass`, /filled|Success/i)
  await command(op, `/minecraft:setblock ${c.x} ${y} ${c.z + 1} minecraft:composter`, /Changed|Success/i)
  await command(op, `/minecraft:setblock ${c.x} ${y + 2} ${c.z} minecraft:glass`, /Changed|Success/i)
  const offers = 'Offers:{Recipes:[{buy:{id:wheat,count:20},sell:{id:emerald,count:1},maxUses:16,xp:2},{buy:{id:potato,count:26},sell:{id:emerald,count:1},maxUses:16,xp:2}]}'
  await command(op, `/minecraft:summon minecraft:villager ${c.x + 0.5} ${y} ${c.z + 0.5} {VillagerData:{profession:farmer,level:2},Xp:10,${offers}}`)
  await waitFor(async () => (await ask(op, '/smarttick check 8', 1200)).some((l) => /walled in/.test(l)) || (await sleep(1500), false), 90_000, 'the villager to take its composter')
  await command(op, '/minecraft:give Kai minecraft:wheat 40')
  await command(op, '/smarttick sleep', /goes to sleep/)
  await sleep(6000)
  const villager = await waitFor(() => Object.values(kai.entities).find((e) => e.name === 'villager'), 10_000, 'Kai to see the villager')
  await kai.lookAt(villager.position.offset(0, 1.4, 0), true)
  chatOf(op, 'NyrOp')
  chatOf(kai, 'Kai')
  watchWindows(kai, 'Kai')
  watchInventory(kai, 'Kai')
  kai._client.on('trade_list', (packet) => emit('trades', {
    bot: 'Kai',
    id: packet.windowId,
    level: packet.villagerLevel,
    offers: packet.trades.map((o) => ({
      uses: o.nbTradeUses,
      max: o.maximumNbTradeUses,
      a: o.inputItem1?.itemId ?? o.inputItem1?.type,
      ac: o.inputItem1?.itemCount ?? o.inputItem1?.count,
      out: o.outputItem?.itemId ?? o.outputItem?.type,
      oc: o.outputItem?.itemCount ?? o.outputItem?.count
    }))
  }))

  begin()
  caption('A sleeping villager still trades', 'Paper 1.21.11: /smarttick check shows it asleep')
  await sleep(1000)
  await type(op, 'NyrOp', '/smarttick check', 600)
  await sleep(3000)
  caption('Kai opens its trades', 'the window opens at once; the villager wakes for the trade')
  const window = await kai.openVillager(villager)
  await sleep(1800)
  emit('select', { bot: 'Kai', index: 0 })
  await kai.trade(window, 0, 1)
  await sleep(2500)
  window.close()
  await sleep(800)
  caption('Traded, then back to sleep', 'it stays awake 10 s after the window closes, then sleeps again')
  await type(op, 'NyrOp', '/smarttick check', 600)
  // 10 s held awake after the trade, then the next scan (every 5 s) puts it back to sleep
  await waitFor(async () => (await ask(luna, '/smarttick check 8', 1200)).some((l) => /asleep farmer/.test(l)) || (await sleep(1000), false), 40_000, 'the villager to sleep again').catch(() => {})
  await type(op, 'NyrOp', '/smarttick check', 600)
  await sleep(4000)
  save('smarttick-trading', { item_names: Object.fromEntries(kai.registry.itemsArray.filter((i) => ['wheat', 'potato', 'emerald'].includes(i.name)).map((i) => [i.id, i.name])) })
}

export default { 'smarttick-awake': hall, 'smarttick-trading': trading }

export const setup = {
  'smarttick-awake': { plugins: ['smarttick'], heap: '3G', ops: ['NyrOp', 'Luna'], properties: { 'view-distance': 8, 'simulation-distance': 8 } },
  'smarttick-trading': { plugins: ['smarttick'], ops: ['NyrOp', 'Luna'] }
}
