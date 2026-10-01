// What the listing GIFs are drawn from: timed events of what a live server really sent the bots. Nothing is invented:
// blocks, entities, windows, slots, chat, hearts, explosions, inventories and anvil prices are the ones the players'
// clients received, at the times they arrived. Scenes (gifs/scenes/*.mjs) drive the bots; this module records.

import { mkdirSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { Vec3, command, describe, flatten, gamerule, nbt, sleep, titleOf } from '../lib/bots.mjs'

const require = createRequire(new URL('../package.json', import.meta.url))
const OUT = new URL('../run/gifs/', import.meta.url)
mkdirSync(OUT, { recursive: true })

let t0 = Date.now()
let events = []
export const emit = (type, data = {}) => events.push({ t: Date.now() - t0, type, ...data })
export const caption = (title, detail = '') => emit('caption', { title, detail })

export function begin () {
  t0 = Date.now()
  events = []
}

export function save (scene, extra) {
  const file = new URL(`${scene}.json`, OUT)
  writeFileSync(file, JSON.stringify({ scene, duration: Date.now() - t0, events, ...extra }))
  console.log(`saved ${file.pathname} (${events.length} events, ${((Date.now() - t0) / 1000).toFixed(1)} s)`)
}

export function motd (bot, component) {
  if (component == null) return null
  try {
    const ChatMessage = require('prismarine-chat')(bot.registry)
    let json = component
    if (typeof json === 'object' && 'type' in json && 'value' in json) json = nbt.simplify(json)
    if (typeof json === 'string') {
      try { json = JSON.parse(json) } catch { return json }
    }
    return new ChatMessage(json).toMotd()
  } catch {
    return null
  }
}

export function chatOf (bot, name) {
  bot.on('message', (msg, position) => {
    if (position !== 'game_info') emit('chat', { bot: name, text: msg.toMotd() })
  })
}

/** A box of the world as the camera's client sees it, and every change to it from now on. */
export function watchWorld (camera, min, max) {
  const inside = (p) => p.x >= min.x && p.x <= max.x && p.y >= min.y && p.y <= max.y && p.z >= min.z && p.z <= max.z
  const blocks = []
  for (let x = min.x; x <= max.x; x++) {
    for (let y = min.y; y <= max.y; y++) {
      for (let z = min.z; z <= max.z; z++) {
        const b = camera.blockAt(new Vec3(x, y, z))
        if (b && b.name !== 'air' && b.name !== 'void_air' && b.name !== 'cave_air') blocks.push([x, y, z, b.name])
      }
    }
  }
  camera.on('blockUpdate', (old, now) => {
    if (now && inside(now.position) && old?.name !== now.name) emit('block', { x: now.position.x, y: now.position.y, z: now.position.z, name: now.name })
  })
  return blocks
}

const KINDS = new Set(['cow', 'player', 'item', 'tnt', 'experience_orb', 'minecart', 'leash_knot', 'creeper', 'villager', 'husk', 'zombie', 'mannequin', 'falling_block', 'iron_golem'])

/** A mob's custom name as plain text, when it has one. */
function customNameOf (bot, entity) {
  const raw = entity.metadata?.[2]
  if (raw == null) return undefined
  const text = motd(bot, raw) ?? flatten(raw)
  return text ? text.replace(/§./g, '') : undefined
}

/** Entities in the box, sampled every tick; spawns, moves, names, leashes, hearts, deaths and explosions. */
export function watchEntities (camera, min, max) {
  const seen = new Map()
  const inside = (p) => p.x >= min.x - 2 && p.x <= max.x + 2 && p.z >= min.z - 2 && p.z <= max.z + 2 && p.y >= min.y - 8 && p.y <= max.y + 16
  const timer = setInterval(() => {
    const present = new Set()
    for (const e of Object.values(camera.entities)) {
      if (!e.position || !KINDS.has(e.name) || e === camera.entity || !inside(e.position)) continue
      present.add(e.id)
      const state = {
        id: e.id,
        kind: e.name,
        x: +e.position.x.toFixed(3),
        y: +e.position.y.toFixed(3),
        z: +e.position.z.toFixed(3),
        yaw: +(e.yaw ?? 0).toFixed(3),
        baby: e.name === 'cow' ? e.metadata?.[16] === true : undefined,
        name: e.name === 'player' ? e.username : undefined,
        label: e.name === 'cow' ? customNameOf(camera, e) : undefined,
        item: e.name === 'item' ? droppedName(e) : undefined
      }
      const last = seen.get(e.id)
      if (!last || Math.abs(last.x - state.x) > 0.01 || Math.abs(last.y - state.y) > 0.01 || Math.abs(last.z - state.z) > 0.01 ||
          Math.abs(last.yaw - state.yaw) > 0.05 || last.baby !== state.baby || last.item !== state.item || last.label !== state.label) {
        emit('entity', state)
        seen.set(e.id, state)
      }
    }
    for (const id of [...seen.keys()]) {
      if (!present.has(id)) {
        emit('gone', { id })
        seen.delete(id)
      }
    }
  }, 50)
  camera._client.on('entity_status', (packet) => {
    if (packet.entityStatus === 18) emit('hearts', { id: packet.entityId })
    if (packet.entityStatus === 3 || packet.entityStatus === 60) emit('poof', { id: packet.entityId })
  })
  camera._client.on('explosion', (packet) => {
    const center = packet.center ?? packet
    emit('explode', { x: center.x, y: center.y, z: center.z })
  })
  // A leash: the mob and what holds it (a fence knot or a player); -1 when it comes off.
  camera._client.on('attach_entity', (packet) => emit('leash', { id: packet.entityId, holder: packet.vehicleId }))
  return () => clearInterval(timer)
}

/** Leashes as they are and as they change, from before a scene ties them; replayLeashes puts them into the recording. */
export function trackLeashes (bot) {
  const leashes = new Map()
  bot._client.on('attach_entity', (packet) => {
    if (packet.vehicleId > 0) leashes.set(packet.entityId, packet.vehicleId)
    else leashes.delete(packet.entityId)
  })
  return leashes
}

export function replayLeashes (leashes) {
  for (const [id, holder] of leashes) emit('leash', { id, holder })
}

function droppedName (entity) {
  try {
    return entity.getDroppedItem()?.name ?? null
  } catch {
    return null
  }
}

/** Windows, slot changes and anvil prices for menu scenes. */
export function watchWindows (bot, name) {
  bot.on('windowOpen', (window) => {
    const slots = []
    for (let i = 0; i < window.inventoryEnd; i++) slots.push(item(bot, window.slots[i]))
    emit('window', { bot: name, id: window.id, title: titleOf(window), kind: window.type, size: window.inventoryStart, slots })
    window.on('updateSlot', (slot, _old, it) => emit('slot', { bot: name, id: window.id, slot, item: item(bot, it) }))
  })
  bot.on('windowClose', () => emit('close', { bot: name }))
  // Window property 0 of an anvil is the level cost the server asks for.
  bot._client.on('craft_progress_bar', (packet) => {
    if (packet.property === 0) emit('cost', { bot: name, id: packet.windowId, value: packet.value })
  })
}

export function item (bot, it) {
  if (!it) return null
  const d = describe(it, bot)
  const components = it.components ?? []
  const has = (type) => components.some((c) => c.type === type)
  const attributes = components.find((c) => c.type === 'attribute_modifiers')
  const amounts = JSON.stringify(attributes?.data ?? {}).match(/"(?:amount|value)":(-?[\d.]+)/g)?.map((m) => Number(m.split(':')[1])) ?? []
  const potion = components.find((c) => c.type === 'potion_contents')
  const damage = components.find((c) => c.type === 'damage')
  return {
    name: it.name,
    count: it.count,
    title: motd(bot, it.customName),
    enchantments: d?.enchantments ?? null,
    unbreakable: has('unbreakable'),
    maxStack: d?.maxStackSize ?? null,
    damageBonus: amounts.find((a) => a >= 100) ?? null,
    potionEffects: JSON.stringify(potion?.data ?? {}).match(/"amplifier":(\d+)/)?.[1] ?? null,
    entityData: has('entity_data'),
    repairCost: d?.repairCost ?? null,
    damage: typeof damage?.data === 'number' ? damage.data : null,
    glint: Boolean(d?.enchantments && Object.keys(d.enchantments).length) || it.name === 'enchanted_book'
  }
}

/** A player's inventory in slot order, with what the item carries, recorded whenever it changes. */
export function watchInventory (bot, name) {
  let last = ''
  const timer = setInterval(() => {
    const items = bot.inventory.items().map((i) => item(bot, i))
    const key = JSON.stringify(items)
    if (key !== last) {
      last = key
      emit('inventory', { bot: name, items })
    }
  }, 100)
  return () => clearInterval(timer)
}

/** A player's experience level, recorded whenever it changes. */
export function watchLevel (bot, name) {
  let last = null
  const timer = setInterval(() => {
    const level = bot.experience?.level ?? 0
    if (level !== last) {
      last = level
      emit('level', { bot: name, level })
    }
  }, 100)
  return () => clearInterval(timer)
}

export async function hover (bot, name, slot, ms) {
  emit('hover', { bot: name, slot })
  await sleep(ms)
}

export async function type (bot, name, text, typingMs = 700) {
  emit('type', { bot: name, text, ms: typingMs })
  await sleep(typingMs + 150)
  emit('send', { bot: name, text })
  bot.chat(text)
}

export async function walkTo (bot, target, stopWithin = 0.6, timeoutMs = 8000, until = () => false) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline && !until()) {
    const pos = bot.entity.position
    const dx = target.x - pos.x
    const dz = target.z - pos.z
    if (Math.hypot(dx, dz) <= stopWithin) break
    await bot.lookAt(new Vec3(target.x, pos.y + 1.6, target.z), true)
    bot.setControlState('forward', true)
    await sleep(60)
  }
  bot.setControlState('forward', false)
  await sleep(150)
}

/** A one-block-high fence around a rectangle. (A "hollow" fill one block high fills every block, being all boundary.) */
export async function fenceRing (op, x0, z0, x1, z1, y) {
  for (const [ax, az, bx, bz] of [[x0, z0, x1, z0], [x0, z1, x1, z1], [x0, z0, x0, z1], [x1, z0, x1, z1]]) {
    await command(op, `/minecraft:fill ${ax} ${y} ${az} ${bx} ${y} ${bz} minecraft:oak_fence`)
  }
}

/** Quiet, bright and empty: no daylight cycle, weather, advancements, mob spawning or leftover entities. */
export async function prepareWorld (op) {
  await gamerule(op, 'sendCommandFeedback', false)
  await gamerule(op, 'doDaylightCycle', false)
  await command(op, '/minecraft:time set 6000')
  await gamerule(op, 'doImmediateRespawn', true)
  await gamerule(op, 'randomTickSpeed', 0)
  await command(op, '/minecraft:weather clear')
  await gamerule(op, 'announceAdvancements', false)
  await gamerule(op, 'doMobSpawning', false)
  await command(op, '/minecraft:kill @e[type=!player]')
  await sleep(600)
}
