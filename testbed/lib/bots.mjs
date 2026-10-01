// Mineflayer clients for the NYR plugins' live tests: connect, run commands, wait for chat, click windows the way a client can,
// and read items (components included) back from the client.

import { createRequire } from 'node:module'

const require = createRequire(new URL('../package.json', import.meta.url))
export const mineflayer = require('mineflayer')
export const prismarineItem = require('prismarine-item')
export const nbt = require('prismarine-nbt')
export const Vec3 = require('vec3').Vec3

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * minecraft-data describes attribute_modifiers (Minecraft 1.21.6 and later) as a list of modifiers followed by one display
 * field, but the game writes a display field inside every modifier. An item with two modifiers (a netherite sword's
 * default attack damage and speed) then misreads the rest of the packet, and the client drops a whole window without a
 * word. Spigot sends such default modifiers explicitly; Paper leaves them out. Corrected in memory, before any client
 * compiles its protocol, and only where the definition still has that shape.
 */
function fixAttributeModifierLayout () {
  const minecraftData = require('minecraft-data')
  for (const version of ['1.21.6', '1.21.7', '1.21.8', '1.21.9', '1.21.10', '1.21.11']) {
    let types
    try {
      types = minecraftData(version)?.protocol?.types
    } catch {
      continue
    }
    const data = Array.isArray(types?.SlotComponent) ? types.SlotComponent[1].find((field) => field.name === 'data')?.type : null
    const layout = data?.[0] === 'switch' ? data[1].fields?.attribute_modifiers : null
    if (!layout || layout[0] !== 'container' || layout[1].length !== 2) continue
    const [list, display] = layout[1]
    const entry = list?.name === 'attributes' ? list.type?.[1]?.type : null
    if (display?.name !== 'display' || entry?.[0] !== 'container' || entry[1].some((field) => field.name === 'display')) continue
    entry[1].push(display)
    layout[1] = [list]
  }
}
fixAttributeModifierLayout()

export function connect (port, username, { timeoutMs = 60_000, version } = {}) {
  return new Promise((resolve, reject) => {
    // NYR_BOT_ERRORS=1 prints what the client could not read (a packet that fails to parse is otherwise dropped silently).
    const verbose = Boolean(process.env.NYR_BOT_ERRORS)
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(port), username, auth: 'offline', version, hideErrors: !verbose })
    if (verbose) {
      bot._client.on('error', (error) => console.log(`[${username} client error] ${String(error?.stack ?? error).slice(0, 1500)}`))
    }
    const timer = setTimeout(() => {
      bot.end()
      reject(new Error(`${username} did not spawn within ${timeoutMs / 1000} s${bot.lastError ? ` (${bot.lastError})` : ''}`))
    }, timeoutMs)
    bot.lines = []
    bot.on('messagestr', (line, position) => {
      if (position !== 'game_info') bot.lines.push(line)
    })
    bot.on('error', (error) => { bot.lastError = String(error) })
    bot.on('kicked', (reason) => { bot.kickReason = typeof reason === 'string' ? reason : JSON.stringify(reason) })
    bot.once('spawn', () => {
      clearTimeout(timer)
      resolve(bot)
    })
    bot.once('end', () => { bot.ended = true })
  })
}

export async function connectWhenUp (port, username, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    try {
      return await connect(port, username)
    } catch (error) {
      if (Date.now() > deadline) throw error
      await sleep(3_000)
    }
  }
}

export async function waitFor (predicate, timeoutMs, what) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const value = await predicate()
    if (value) return value
    if (Date.now() > deadline) throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}`)
    await sleep(50)
  }
}

/** Sends a chat command and resolves with the first new chat line matching {@code pattern}. */
export async function command (bot, text, pattern, timeoutMs = 10_000) {
  const from = bot.lines.length
  bot.chat(text)
  if (!pattern) {
    await sleep(150)
    return null
  }
  return waitFor(() => bot.lines.slice(from).find((line) => pattern.test(line)), timeoutMs,
    `a reply to "${text}" matching ${pattern} (got: ${bot.lines.slice(from).slice(-5).join(' | ') || 'nothing'})`)
}

/**
 * Sets a gamerule under both of its names: Minecraft 1.21.11 renamed every gamerule to snake_case (keepInventory became
 * keep_inventory, doMobSpawning spawn_mobs), and a server answers the name it does not know with an error nobody sees.
 */
const GAMERULES = {
  sendCommandFeedback: 'send_command_feedback',
  announceAdvancements: 'show_advancement_messages',
  doMobSpawning: 'spawn_mobs',
  doDaylightCycle: 'advance_time',
  doImmediateRespawn: 'immediate_respawn',
  keepInventory: 'keep_inventory',
  naturalRegeneration: 'natural_health_regeneration',
  randomTickSpeed: 'random_tick_speed',
  doWeatherCycle: 'advance_weather'
}

export async function gamerule (bot, name, value) {
  bot.chat(`/minecraft:gamerule ${name} ${value}`)
  await sleep(120)
  if (GAMERULES[name]) {
    bot.chat(`/minecraft:gamerule ${GAMERULES[name]} ${value}`)
    await sleep(120)
  }
}

/**
 * Starts or stops sneaking so the server sees it on every version. On 1.21.3 to 1.21.5 mineflayer 4.39.0 sends sneaking
 * only in player_input, while those Paper servers set Player#isSneaking from entity_action's press and release shift
 * key (ids 0 and 1): a shift + right-click was an ordinary right-click there (found live by the ChunkHopper scenario).
 * From 1.21.6 entity_action has no shift action and player_input is what the server reads.
 */
export function sneak (bot, on) {
  bot.setControlState('sneak', on)
  if (bot.supportFeature('newPlayerInputPacket') && !bot.supportFeature('entityActionUsesStringMapper')) {
    bot._client.write('entity_action', { entityId: bot.entity.id, actionId: on ? 0 : 1, jumpBoost: 0 })
  }
}

export function linesSince (bot, from) {
  return bot.lines.slice(from)
}

/** Window titles arrive as JSON text before 1.20.3 and as NBT after. */
export function titleOf (window) {
  try {
    let t = typeof window.title === 'string' ? JSON.parse(window.title) : window.title
    if (t && typeof t === 'object' && 'type' in t && 'value' in t) t = nbt.simplify(t)
    return flatten(t)
  } catch {
    return String(window.title)
  }
}

export function flatten (component) {
  if (component == null) return ''
  if (typeof component === 'string') return component
  if (Array.isArray(component)) return component.map(flatten).join('')
  if (component.translate && !component.text) return component.translate
  let text = component.text ?? component[''] ?? ''
  if (component.extra) text += component.extra.map(flatten).join('')
  return text
}

/** A window click exactly as a modified client can send it: any slot, any mode, no client-side checks. */
export function rawClick (bot, slot, { mouseButton = 0, mode = 0, window = bot.currentWindow ?? bot.inventory } = {}) {
  const Item = prismarineItem(bot.registry)
  bot._client.write('window_click', {
    windowId: window.id,
    stateId: window.stateId ?? -1,
    slot,
    mouseButton,
    mode,
    changedSlots: [],
    cursorItem: Item.toNotch(null)
  })
}

/** The fields tests compare: name, count, enchantments and a few components, from the client's own view of the item. */
export function describe (item, bot = null) {
  if (!item) return null
  const components = item.components ?? []
  const component = (type) => components.find((c) => c.type === type)
  const out = { name: item.name, count: item.count }
  const enchants = component('enchantments') ?? component('stored_enchantments')
  if (enchants?.data?.enchantments) {
    out.enchantments = Object.fromEntries(enchants.data.enchantments.map((e) => [enchantmentName(bot, e.id), e.level]))
  }
  const repair = component('repair_cost')
  if (repair) out.repairCost = repair.data
  if (component('unbreakable')) out.unbreakable = true
  const maxStack = component('max_stack_size')
  if (maxStack) out.maxStackSize = maxStack.data
  const attributes = component('attribute_modifiers')
  if (attributes) out.attributeModifiers = attributes.data?.modifiers?.length ?? attributes.data?.attributes?.length ?? 1
  return out
}

function enchantmentName (bot, id) {
  const known = bot?.registry?.enchantments?.[id]
  return known?.name ?? String(id)
}

export function quitAll (bots) {
  for (const bot of bots) {
    try {
      if (bot && !bot.ended) bot.quit()
    } catch { /* already gone */ }
  }
}
