// NYR ChunkHopper listing scenes, all in chunk 2,2 of a flat Paper 1.21.11 world: a cow pen and a row of cactus whose drops
// pile up on a server without the plugin; the same farm with one Chunk Hopper, which takes every drop and sells it; and
// the filter menu set to cactus, after which beef and leather stay on the ground. The cows die by /kill and the cactus by
// /fill ... destroy (block drops, as a farm's cactus breaking off makes them); everything drawn afterwards is what the
// server sent the bots.

import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { Vec3, command, connectWhenUp, quitAll, rawClick, sleep, sneak, waitFor } from '../../lib/bots.mjs'
import { TARGETS } from '../../lib/servers.mjs'
import { extraJars, files } from '../../scenarios/chunkhopper.mjs'
import { begin, caption, emit, fenceRing, hover, prepareWorld, save, type, watchEntities, watchWindows, watchWorld } from '../recorder.mjs'

const PAPER = TARGETS.find((t) => t.name === 'paper-1.21.11')
const WITH_PLUGIN = { plugins: ['chunkhopper'], extraJars: extraJars(PAPER), files: files(PAPER) }
export const setup = {
  'chunkhopper-ground': { plugins: [] },
  'chunkhopper-vacuum': WITH_PLUGIN,
  'chunkhopper-filter': WITH_PLUGIN
}

const CACTUS = [34, 36, 38, 40, 42, 44] // x of each cactus, at z 34
const PEN = { x0: 35, z0: 37, x1: 45, z1: 45 } // the fence ring
const COWS = [[37, 39], [40, 39], [43, 39], [36.5, 41.5], [39.5, 41.5], [42.5, 41.5], [38, 43.5], [42, 43.5]]
const HOPPER = { x: 45, z: 35 } // in front of the pen, where the camera sees it
const KAI = { x: 48.5, z: 37.5 } // just outside the chunk, so Kai never picks up a drop, and beside the hopper in the picture
const plain = (text) => String(text ?? '').replace(/§./g, '')

async function farm (op, y) {
  await command(op, `/minecraft:fill 29 ${y} 29 51 ${y + 5} 51 minecraft:air`)
  for (const x of CACTUS) await command(op, `/minecraft:setblock ${x} ${y - 1} 34 minecraft:sand`)
  await fenceRing(op, PEN.x0, PEN.z0, PEN.x1, PEN.z1, y)
  await restock(op, y)
}

/** New cows in the pen and new cactus on the sand. */
async function restock (op, y) {
  for (const x of CACTUS) await command(op, `/minecraft:setblock ${x} ${y} 34 minecraft:cactus`)
  for (const [x, z] of COWS) await command(op, `/minecraft:summon minecraft:cow ${x} ${y} ${z}`)
}

/** One round of the farm: every cow in the pen dies and every cactus breaks off, dropping what the game drops. */
async function harvest (op, y) {
  await command(op, `/minecraft:kill @e[type=minecraft:cow,x=${PEN.x0},y=${y - 1},z=${PEN.z0},dx=${PEN.x1 - PEN.x0},dy=4,dz=${PEN.z1 - PEN.z0}]`)
  await command(op, `/minecraft:fill ${CACTUS[0]} ${y} 34 ${CACTUS[CACTUS.length - 1]} ${y} 34 minecraft:air destroy`)
}

const itemsInChunk = (bot) => Object.values(bot.entities).filter((e) => e.name === 'item' && e.position &&
  Math.floor(e.position.x / 16) === 2 && Math.floor(e.position.z / 16) === 2).length

async function world (port, withKai) {
  const op = await connectWhenUp(port, 'NyrOp')
  const kai = withKai ? await connectWhenUp(port, 'Kai') : null
  await prepareWorld(op)
  const y = Math.floor(op.entity.position.y)
  await command(op, '/minecraft:gamemode spectator NyrOp')
  await command(op, `/minecraft:tp NyrOp 40 ${y + 14} 60`)
  await farm(op, y)
  if (kai) {
    await command(op, '/minecraft:clear Kai')
    await command(op, `/minecraft:tp Kai ${KAI.x} ${y} ${KAI.z} 90 20`)
  }
  await sleep(2500)
  const min = { x: 31, y: y - 1, z: 31 }
  const max = { x: 49, y: y + 2, z: 48 }
  return { op, kai, y, min, max }
}

/** Kai's chat, except /chunkhopper info: its answers become the hopper's contents on screen instead of chat lines. */
function kaiChat (kai) {
  const last = { collected: 0, earned: '$0', holds: 'nothing' }
  kai.on('message', (msg, position) => {
    if (position === 'game_info') return
    const text = msg.toMotd()
    const line = plain(text)
    const holds = /Holds: (.*)$/.exec(line)
    const counters = /Collected: (\d+) \| Earned: (.*)$/.exec(line)
    if (holds) {
      last.holds = holds[1].trim()
      emit('holds', { text: last.holds })
    } else if (counters) {
      last.collected = Number(counters[1])
      last.earned = counters[2].trim()
      emit('counters', { collected: last.collected, earned: last.earned })
    } else if (!/Chunk Hopper at |^\s*- (Owner|Filter|Auto-sell):/.test(line)) {
      emit('chat', { bot: 'Kai', text })
    }
  })
  return last
}

/** Kai looks at the Chunk Hopper and asks what it holds (answered into 'holds' events by kaiChat). */
async function ask (kai, y) {
  await kai.lookAt(new Vec3(HOPPER.x + 0.5, y + 0.5, HOPPER.z + 0.5), true)
  kai.chat('/chunkhopper info')
  await sleep(350)
}

async function place (op, kai, y) {
  await command(op, '/chunkhopper give Kai 1', /Gave/)
  const item = await waitFor(() => kai.inventory.items().find((i) => i.name === 'hopper'), 6000, 'Kai to hold the Chunk Hopper')
  await kai.equip(item, 'hand')
  await kai.lookAt(new Vec3(HOPPER.x + 0.5, y, HOPPER.z + 0.5), true)
  await kai.placeBlock(kai.blockAt(new Vec3(HOPPER.x, y - 1, HOPPER.z)), new Vec3(0, 1, 0)).catch(() => {})
  await waitFor(() => kai.blockAt(new Vec3(HOPPER.x, y, HOPPER.z))?.name === 'hopper', 5000, 'the Chunk Hopper to stand')
}

async function ground ({ port }) {
  const { op, y, min, max } = await world(port, false)
  begin()
  const blocks = watchWorld(op, min, max)
  const stop = watchEntities(op, min, max)
  caption('Paper 1.21.11, no plugin', 'A cow pen and a row of cactus in one chunk')
  await sleep(1800)
  for (let round = 1; round <= 4; round++) {
    await harvest(op, y)
    if (round === 1) caption('Every drop lands on the ground', 'Cows killed, cactus broken off')
    await sleep(1400)
    await restock(op, y)
    await sleep(600)
  }
  await sleep(1500)
  const count = itemsInChunk(op)
  caption(`${count} item entities on the ground`, 'In one chunk, after four rounds of the farm')
  await sleep(3200)
  stop()
  save('chunkhopper-ground', { region: { min, max }, blocks, ground: y - 1, chunk: [2, 2], counted: count })
  quitAll([op])
}

async function vacuum ({ port }) {
  const { op, kai, y, min, max } = await world(port, true)
  begin()
  const blocks = watchWorld(op, min, max)
  const stop = watchEntities(op, min, max)
  const hopper = kaiChat(kai)
  caption('One Chunk Hopper for the chunk', 'Paper 1.21.11 with NYR ChunkHopper, Vault and EssentialsX')
  await sleep(1200)
  await place(op, kai, y)
  await sleep(1200)
  await ask(kai, y)
  for (let round = 1; round <= 4; round++) {
    await harvest(op, y)
    if (round === 1) caption('The same farm, nothing on the ground', 'Each drop goes into the hopper the moment it drops')
    for (let i = 0; i < 3; i++) await ask(kai, y)
    await restock(op, y)
    await ask(kai, y)
  }
  // Auto-sell runs every 10 seconds: wait for Kai's sale line.
  const from = kai.lines.length
  await waitFor(() => kai.lines.slice(from).some((l) => /from your Chunk Hopper/.test(plain(l))), 15_000, 'a sale').catch(() => {})
  caption('Auto-sell pays Kai through Vault', 'Every 10 seconds, what the hopper holds is sold')
  await ask(kai, y)
  await sleep(1500)
  await type(kai, 'Kai', '/balance')
  await sleep(1800)
  await ask(kai, y)
  caption(`${itemsInChunk(op)} items on the ground`, `The hopper collected ${hopper.collected} items and earned ${hopper.earned}`)
  await sleep(3200)
  stop()
  save('chunkhopper-vacuum', { region: { min, max }, blocks, ground: y - 1, chunk: [2, 2], hopper: { x: HOPPER.x, y, z: HOPPER.z } })
  quitAll([op, kai])
}

async function filter ({ port, dir }) {
  // This scene is about the filter: auto-sell starts off, so the hopper keeps what it collects.
  const config = join(dir, 'plugins/NYR-ChunkHopper/config.yml')
  const text = readFileSync(config, 'utf8')
  if (!/default-on: true/.test(text)) throw new Error('config.yml has no auto-sell default-on: true')
  writeFileSync(config, text.replace('default-on: true', 'default-on: false'))
  const { op, kai, y, min, max } = await world(port, true)
  await command(op, '/chunkhopper reload', /Reloaded/)
  await place(op, kai, y)
  await command(op, '/minecraft:give Kai minecraft:cactus 16')
  kai.setQuickBarSlot(8)
  await sleep(1500)

  begin()
  const blocks = watchWorld(op, min, max)
  const stop = watchEntities(op, min, max)
  kaiChat(kai)
  watchWindows(kai, 'Kai')
  caption('The filter: shift + right-click', 'With an empty hand, on your own Chunk Hopper')
  await ask(kai, y)
  await sleep(1200)
  await kai.lookAt(new Vec3(HOPPER.x + 0.5, y + 0.5, HOPPER.z + 0.5), true)
  sneak(kai, true)
  await sleep(300)
  const opened = new Promise((resolve) => kai.once('windowOpen', resolve))
  await kai.activateBlock(kai.blockAt(new Vec3(HOPPER.x, y, HOPPER.z)))
  const window = await Promise.race([opened, sleep(5000).then(() => null)])
  sneak(kai, false)
  if (!window) throw new Error('the filter menu did not open')
  await sleep(1400)
  caption('Shift-click cactus to filter it', 'The menu shows icons; Kai keeps his cactus')
  const cactus = window.slots.findIndex((it, i) => i >= window.inventoryStart && it?.name === 'cactus')
  await hover(kai, 'Kai', cactus, 900)
  rawClick(kai, cactus, { mode: 1, window }) // shift-click, as the client sends it; the server answers with the slots
  await sleep(1200)
  await hover(kai, 'Kai', 0, 1400)
  kai.closeWindow(window)
  await sleep(800)
  caption('Now only cactus goes in', 'Beef and leather stay on the ground')
  for (let round = 1; round <= 3; round++) {
    await harvest(op, y)
    for (let i = 0; i < 3; i++) await ask(kai, y)
    await restock(op, y)
    await ask(kai, y)
  }
  await sleep(800)
  await ask(kai, y)
  const count = itemsInChunk(op)
  caption(`Cactus in the hopper, ${count} other drops on the ground`, 'Filter: cactus. Everything else is left alone')
  await sleep(3200)
  stop()
  save('chunkhopper-filter', { region: { min, max }, blocks, ground: y - 1, chunk: [2, 2], hopper: { x: HOPPER.x, y, z: HOPPER.z } })
  quitAll([op, kai])
}

export default { 'chunkhopper-ground': ground, 'chunkhopper-vacuum': vacuum, 'chunkhopper-filter': filter }
