// NYR ChunkHopper on a live server, played by three mineflayer clients: NyrOp (staff, watching), Kai (the owner) and Luna.
// Kai places a Chunk Hopper in chunk 2,2. Mob drops and cactus drops in that chunk never appear on the ground; what Kai
// throws, /give hands him, Luna's death drops and a block Kai mines stay on the ground. Kai sets a cactus filter in the
// menu, a barrage of raw window clicks (every mode a hacked client can send) never hands him a menu icon, the hopper
// sells into his balance, Luna can neither break nor open it, Kai breaks it with a pickaxe (one Chunk Hopper item back,
// the contents once) and places it again, and it keeps its owner, filter and contents over a clean restart. Then the
// server is hard-killed seconds after a sale: whatever the saved world still had of that sale is taken out again, so the
// balance and the hopper together show each item paid for at most once; a sale before a clean restart is paid once.
// Counted by the server where the server can count (Folia's /clear answers with the number of players, so there the
// client's own inventory is read).

import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { Vec3, command, connectWhenUp, gamerule, quitAll, rawClick, sleep, titleOf, waitFor } from '../lib/bots.mjs'
import { GUARDIAN, JAVA21, RUN, TESTBED } from '../lib/servers.mjs'

export const jarName = 'NYR-ChunkHopper'
export const displayName = 'NYR ChunkHopper'

// ------------------------------------------------------------------ the economy each server gets

/** Vault, EssentialsX and VaultUnlocked, which this repository does not ship: NYR_EXTRA_PLUGINS, default run/plugins-extra. */
const EXTRA_PLUGINS = process.env.NYR_EXTRA_PLUGINS ?? join(RUN, 'plugins-extra')
const GRADLE_FILES = join(homedir(), '.gradle/caches/modules-2/files-2.1')
const VAULT_API = join(GRADLE_FILES, 'com.github.MilkBowl/VaultAPI/1.7/e0f66f509bfb44a49a7dd76be4f7d13e3f8e079c/VaultAPI-1.7.jar')
const PAPER_API = join(GRADLE_FILES, 'io.papermc.paper/paper-api/1.21.1-R0.1-SNAPSHOT/6f1e11de8e85bb7d49bbf9f23f456ed547d8e28c/paper-api-1.21.1-R0.1-SNAPSHOT.jar')
const ECON_OUT = join(GUARDIAN, 'chunkhopper/build/testbed-econ')
const ECON_JAR = join(ECON_OUT, 'NyrTestbedEconomy.jar')

/** The first jar (not sources) Gradle cached for group:artifact, any version: javac only needs the class symbols. */
function cachedJar (group, artifact) {
  const base = join(GRADLE_FILES, group, artifact)
  for (const version of existsSync(base) ? readdirSync(base) : []) {
    for (const hash of readdirSync(join(base, version))) {
      const jar = readdirSync(join(base, version, hash)).find((f) => f.endsWith('.jar') && !f.includes('sources'))
      if (jar) return join(base, version, hash, jar)
    }
  }
  throw new Error(`no ${group}:${artifact} jar in the Gradle cache`)
}

/**
 * The testbed's own Vault economy (testbed/econ; EssentialsX does not run on Folia), compiled from its source into the
 * chunkhopper module's build directory. Its balances live in memory: a server restart resets them.
 */
function testbedEconomy () {
  if (existsSync(ECON_JAR)) return ECON_JAR
  const source = join(TESTBED, 'econ')
  const classes = join(ECON_OUT, 'classes')
  mkdirSync(classes, { recursive: true })
  const dir = join(source, 'src/com/nyr/testbed/economy')
  const files = readdirSync(dir).filter((f) => f.endsWith('.java')).map((f) => join(dir, f))
  const bin = dirname(JAVA21)
  const classPath = [VAULT_API, PAPER_API, cachedJar('net.kyori', 'adventure-api'), cachedJar('net.kyori', 'adventure-key'),
    cachedJar('net.kyori', 'examination-api'), cachedJar('net.md-5', 'bungeecord-chat')]
  execFileSync(join(bin, 'javac.exe'), ['--release', '21', '-nowarn', '-cp', classPath.join(';'), '-d', classes, ...files], { stdio: 'pipe' })
  copyFileSync(join(source, 'plugin.yml'), join(classes, 'plugin.yml'))
  execFileSync(join(bin, 'jar.exe'), ['--create', '--file', ECON_JAR, '-C', classes, '.'], { stdio: 'pipe' })
  return ECON_JAR
}

/** Vault and EssentialsX where they run; VaultUnlocked and the in-memory economy on Folia. */
export function extraJars (target) {
  if (target.folia) return [join(EXTRA_PLUGINS, 'VaultUnlocked-2.20.1.jar'), testbedEconomy()]
  return [join(EXTRA_PLUGINS, 'Vault.jar'), join(EXTRA_PLUGINS, 'EssentialsX-2.22.0.jar')]
}

/**
 * EssentialsX answers /balance only with essentials.balance, and the test server has no permissions plugin: Bukkit's
 * permissions.yml gives every player a parent node that carries it.
 */
export function files (target) {
  if (target.folia) return {}
  return {
    'permissions.yml': 'nyrtestbed.players:\n  description: Every test player may read their own EssentialsX balance.\n  default: true\n' +
      '  children:\n    essentials.balance: true\n'
  }
}

// ------------------------------------------------------------------ helpers

const plain = (line) => String(line ?? '').replace(/§./g, '')
const CX = 2
const CZ = 2
const inChunk = (pos, cx = CX, cz = CZ) => Math.floor(pos.x / 16) === cx && Math.floor(pos.z / 16) === cz
const itemName = (entity) => {
  try {
    return entity.getDroppedItem()?.name ?? null
  } catch {
    return null
  }
}

function setConfig (dir, edit) {
  const file = join(dir, 'plugins/NYR-ChunkHopper/config.yml')
  const before = readFileSync(file, 'utf8')
  const after = edit(before)
  if (before === after) throw new Error('config edit changed nothing')
  writeFileSync(file, after)
}

/**
 * How many of an item a player holds. The server counts where it can (clear with a count of 0 only counts); Folia's
 * /clear answers with the number of players it reached, so there the player's own client is read once it settles.
 */
async function count (op, bot, item, folia) {
  if (folia) {
    let last = -1
    for (let i = 0; i < 12; i++) {
      await sleep(250)
      const now = bot.inventory.items().filter((it) => it.name === item).reduce((n, it) => n + it.count, 0)
      if (now === last) return now
      last = now
    }
    return last
  }
  const reply = await command(op, `/minecraft:clear ${bot.username} minecraft:${item} 0`, /Found \d+|No items were found|No player was found/i, 8_000)
  const found = /Found (\d+)/.exec(plain(reply))
  if (found) return Number(found[1])
  if (/No items were found/i.test(plain(reply))) return 0
  throw new Error(`could not count ${item} on ${bot.username}: ${plain(reply)}`)
}

/** Every item entity a client sees appear in a chunk from now on. */
function watchItems (bot, cx = CX, cz = CZ) {
  const seen = []
  const onSpawn = (entity) => {
    if (entity.name === 'item' && entity.position && inChunk(entity.position, cx, cz)) {
      seen.push(entity)
    }
  }
  bot.on('entitySpawn', onSpawn)
  return {
    seen,
    names: () => seen.map((e) => itemName(e) ?? '?'),
    stop: () => bot.removeListener('entitySpawn', onSpawn)
  }
}

/** /chunkhopper info for the hopper at {@code at}, read by the bot looking at it. */
async function info (bot, at) {
  await bot.lookAt(at.offset(0.5, 0.5, 0.5), true)
  await sleep(350)
  const from = bot.lines.length
  bot.chat('/chunkhopper info')
  await waitFor(() => bot.lines.slice(from).map(plain).some((l) => /Holds: |Look at a Chunk Hopper|belongs to|switched off/.test(l)), 8_000,
    `${bot.username}: /chunkhopper info`).catch(() => {})
  await sleep(150)
  const lines = bot.lines.slice(from).map(plain)
  const field = (name) => {
    const line = lines.find((l) => l.includes(`${name}: `))
    return line ? line.slice(line.indexOf(`${name}: `) + name.length + 2).trim() : null
  }
  const holds = {}
  for (const part of (field('Holds') ?? '').split(',')) {
    const match = /^\s*(\d+) (\S+)/.exec(part)
    if (match) holds[match[2]] = (holds[match[2]] ?? 0) + Number(match[1])
  }
  const counters = field('Collected') ?? ''
  return {
    lines,
    owner: field('Owner'),
    filter: field('Filter'),
    sell: field('Auto-sell'),
    collected: Number(/^(\d+)/.exec(counters)?.[1] ?? NaN),
    holds,
    text: lines.join(' | ')
  }
}

/** Every line of /chunkhopper status, joined (the last line is the economy's). */
async function statusText (op) {
  const from = op.lines.length
  op.chat('/chunkhopper status')
  await waitFor(() => op.lines.slice(from).map(plain).some((l) => /Economy: /.test(l)), 8_000, '/chunkhopper status').catch(() => {})
  await sleep(100)
  return op.lines.slice(from).map(plain).join(' | ')
}

async function balance (bot) {
  const from = bot.lines.length
  bot.chat('/balance')
  const line = await waitFor(() => bot.lines.slice(from).map(plain).find((l) => /Balance:/i.test(l)), 8_000, `${bot.username}: /balance`)
  return Math.round(Number(line.split(/Balance:/i)[1].replace(/[^0-9.]/g, '')) * 100)
}

/** Waits for a window to open after {@code act}; null when none opens. */
async function opened (bot, act, timeoutMs = 6_000) {
  if (bot.currentWindow) {
    bot.closeWindow(bot.currentWindow)
    await sleep(300)
  }
  const window = new Promise((resolve) => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', onOpen); resolve(null) }, timeoutMs)
    const onOpen = (w) => { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', onOpen)
  })
  await act()
  const result = await window
  await sleep(300)
  return result
}

/**
 * Sneaking. mineflayer 4.39.0 sends it through player_input only from 1.21.6, but servers read it there from 1.21.2 on
 * (the old entity_action id 0 now means "leave bed"): between the two the packet is written here.
 */
function sneak (bot, on) {
  bot.setControlState('sneak', on)
  // mineflayer 4.39 sends sneaking as player_input from 1.21.3 on (minecraft-data's newPlayerInputPacket), but servers
  // before 1.21.6 still take it from entity_action (press and release shift key, ids 0 and 1): Paper 1.21.3 and 1.21.5
  // only store player_input's flags. From 1.21.6 on entity_action has no shift action and player_input is the one read.
  if (bot.supportFeature('newPlayerInputPacket') && !bot.supportFeature('entityActionUsesStringMapper')) {
    bot._client.write('entity_action', { entityId: bot.entity.id, actionId: on ? 0 : 1, jumpBoost: 0 })
  }
}

async function emptyHand (bot) {
  bot.setQuickBarSlot(8)
  await sleep(150)
  if (bot.heldItem) {
    // slot 8 is kept free on purpose; move whatever landed there
    await bot.moveSlotItem(36 + 8, bot.inventory.firstEmptyInventorySlot() ?? 9).catch(() => {})
    await sleep(200)
  }
}

async function holdItem (bot, name) {
  const item = await waitFor(() => bot.inventory.items().find((i) => i.name === name), 6_000, `${bot.username} to have ${name}`)
  await bot.equip(item, 'hand')
  await sleep(250)
}

/** Shift + right-click with an empty hand: the filter menu, or null. */
async function openMenu (bot, block) {
  await emptyHand(bot)
  await bot.lookAt(block.position.offset(0.5, 0.5, 0.5), true)
  sneak(bot, true)
  await sleep(300)
  const window = await opened(bot, () => bot.activateBlock(block).catch(() => {}), 5_000)
  sneak(bot, false)
  await sleep(150)
  return window
}

/** Places the held Chunk Hopper on top of the block below {@code at}; the chat lines it caused. */
async function placeAt (bot, at) {
  const from = bot.lines.length
  await holdItem(bot, 'hopper')
  await bot.lookAt(at.offset(0.5, 0, 0.5), true)
  await bot.placeBlock(bot.blockAt(at.offset(0, -1, 0)), new Vec3(0, 1, 0)).catch(() => {})
  await sleep(1200)
  return bot.lines.slice(from).map(plain)
}

/** The window slot of the first stack of {@code name} in the bot's own part of the window, or -1. */
function ownSlot (window, name) {
  for (let slot = window.inventoryStart; slot < window.inventoryEnd; slot++) {
    if (window.slots[slot]?.name === name) return slot
  }
  return -1
}

/** An item's components (1.20.5 and later) as text, for searching. */
function describeComponents (item) {
  try {
    return JSON.stringify(item?.components ?? item?.nbt ?? '', (key, value) => typeof value === 'bigint' ? String(value) : value)
  } catch {
    return ''
  }
}

const isChunkHopperItem = (item) => item?.name === 'hopper' && describeComponents(item).includes('nyrchunkhopper')

// ------------------------------------------------------------------ the scenario

export async function run ({ target, dir, check, restart, say }) {
  const port = target.port
  const folia = Boolean(target.folia)
  let op = await connectWhenUp(port, 'NyrOp')
  let kai = await connectWhenUp(port, 'Kai')
  let luna = await connectWhenUp(port, 'Luna')
  const reconnect = async () => {
    quitAll([op, kai, luna])
    await sleep(600)
    op = await connectWhenUp(port, 'NyrOp')
    kai = await connectWhenUp(port, 'Kai')
    luna = await connectWhenUp(port, 'Luna')
    await sleep(1000)
    await command(op, '/minecraft:gamemode creative NyrOp')
  }
  try {
    // The start-up line, read from the console log itself.
    const console1 = existsSync(join(dir, 'console-1.log')) ? readFileSync(join(dir, 'console-1.log'), 'utf8') : ''
    const startLine = /\[NYR-ChunkHopper\] NYR ChunkHopper \S+ on \S+ \S+/.exec(console1)?.[0]
    check('the plugin starts and says so in the console', Boolean(startLine) && !/Error occurred while enabling NYR-ChunkHopper/.test(console1), startLine ?? 'no start-up line')
    await gamerule(op, 'sendCommandFeedback', true)
    await gamerule(op, 'doMobSpawning', false)
    await gamerule(op, 'doImmediateRespawn', true)
    await gamerule(op, 'keepInventory', false)
    await gamerule(op, 'randomTickSpeed', 0)
    await gamerule(op, 'doDaylightCycle', false)
    await command(op, '/minecraft:time set day')
    await command(op, '/minecraft:gamemode creative NyrOp')
    const y = Math.floor(kai.entity.position.y)
    const H = new Vec3(40, y, 40)
    const B = new Vec3(88, y, 40)
    const C = new Vec3(120, y, 40)
    const cactusAt = new Vec3(44, y, 35)
    // Sales wait until auto-sell is switched on in the menu: a hopper placed now does not sell by itself.
    setConfig(dir, (text) => text.replace('default-on: true', 'default-on: false'))
    await command(op, '/chunkhopper reload', /Reloaded/)
    // On Folia a command changes blocks and entities only in its sender's region: NyrOp works from beside the chunk.
    await command(op, `/minecraft:tp NyrOp 40.5 ${y} 47.5`)
    await sleep(1500)
    await command(op, `/minecraft:kill @e[type=!minecraft:player,x=40,y=${y},z=40,distance=..48]`)
    await command(op, `/minecraft:fill 32 ${y} 32 47 ${y + 3} 47 minecraft:air`)
    await command(op, `/minecraft:fill 32 ${y - 1} 32 47 ${y - 1} 47 minecraft:grass_block`)
    await command(op, `/minecraft:setblock ${cactusAt.x} ${y - 1} ${cactusAt.z} minecraft:sand`)

    /** A cactus in chunk 2,2 breaks when a block is set beside it: its item drops with no player involved. */
    const breakCactus = async () => {
      await command(op, `/minecraft:setblock ${cactusAt.x} ${y} ${cactusAt.z} minecraft:cactus`)
      await sleep(300)
      await command(op, `/minecraft:setblock ${cactusAt.x + 1} ${y} ${cactusAt.z} minecraft:stone`)
      await sleep(1200)
      await command(op, `/minecraft:setblock ${cactusAt.x + 1} ${y} ${cactusAt.z} minecraft:air`)
      await sleep(300)
    }
    /** Three cows in the chunk, killed: beef (one to three each) and maybe leather drop, from the mob, not from a player. */
    const killCows = async () => {
      for (let i = 0; i < 3; i++) await command(op, `/minecraft:summon minecraft:cow ${34.5 + i} ${y} 35.5`)
      await sleep(1200)
      await command(op, `/minecraft:kill @e[type=minecraft:cow,x=36,y=${y},z=36,distance=..6]`)
      await sleep(1500)
    }
    /** Switches auto-sell with the menu's button; the button's item afterwards. */
    const toggleSell = async () => {
      const menu = await openMenu(kai, kai.blockAt(H))
      if (!menu) return null
      await kai.clickWindow(13, 0, 0)
      await sleep(700)
      const toggle = kai.currentWindow?.slots?.[13]?.name
      kai.closeWindow(kai.currentWindow ?? menu)
      await sleep(300)
      return toggle
    }
    /** Waits for Kai's next "sold" line; {items, cents} or null. */
    const nextSale = async (from, timeoutMs = 15_000) => {
      const line = await waitFor(() => kai.lines.slice(from).map(plain).find((l) => /\+.*from your Chunk Hopper \(\d+ items\)/.test(l)), timeoutMs, 'the sold message')
        .catch(() => null)
      if (!line) return null
      return { line, items: Number(/\((\d+) items\)/.exec(line)[1]), cents: Math.round(Number((/\+([^ ]+) from/.exec(line)?.[1] ?? '').replace(/[^0-9.]/g, '')) * 100) }
    }

    // Kit, handed out at spawn (outside the hopper's chunk), with NyrOp beside the players (one Folia region).
    await command(op, '/minecraft:tp Kai 8.5 ' + y + ' 8.5')
    await command(op, '/minecraft:tp Luna 10.5 ' + y + ' 8.5')
    await command(op, '/minecraft:tp NyrOp 9.5 ' + y + ' 12.5')
    await sleep(1500)
    await command(op, '/minecraft:clear Kai')
    await command(op, '/minecraft:clear Luna')
    for (const [item, n] of [['diamond_pickaxe', 1], ['iron_shovel', 1], ['stick', 6], ['cobblestone', 7]]) {
      await command(op, `/minecraft:give Kai minecraft:${item} ${n}`)
    }
    await command(op, '/minecraft:give Luna minecraft:gold_ingot 5')
    await command(op, '/minecraft:give Luna minecraft:diamond_pickaxe 1')
    await sleep(600)

    // 1. The item.
    const gave = await command(op, '/chunkhopper give Kai 2', /Gave|No online|Usage/)
    check('/chunkhopper give hands out Chunk Hoppers', /Gave 2 Chunk Hopper\(s\) to Kai/.test(plain(gave)), plain(gave))
    const received = await waitFor(() => kai.inventory.items().find(isChunkHopperItem), 6_000, 'Kai to hold Chunk Hoppers').catch(() => null)
    const hoppersHeld = await count(op, kai, 'hopper', folia)
    check('Kai holds two Chunk Hopper items', received?.count === 2 && hoppersHeld === 2, `client ${received?.count}, counted ${hoppersHeld}`)
    const components = describeComponents(received)
    check('the item shimmers (enchantment glint)', components.includes('enchantment_glint_override'), components.slice(0, 300))
    const recipe = plain(await command(op, '/minecraft:recipe give Kai nyrchunkhopper:chunk_hopper', /Unlocked|Unknown|Couldn|No player|recipe/i, 8_000)
      .catch((error) => String(error?.message ?? error)))
    check('the server knows the Chunk Hopper recipe', /Unlocked 1 recipe/i.test(recipe), recipe)

    // 2. Placing: one per chunk.
    await command(op, `/minecraft:tp NyrOp 40.5 ${y} 47.5`)
    await command(op, `/minecraft:tp Kai 40.5 ${y} 42.5`)
    await command(op, `/minecraft:tp Luna 37.5 ${y} 43.5`)
    await sleep(1500)
    let lines = await placeAt(kai, H)
    check('Kai places a Chunk Hopper', kai.blockAt(H)?.name === 'hopper' && lines.some((l) => /Chunk Hopper placed/.test(l)), `${kai.blockAt(H)?.name}; ${lines.join(' | ')}`)
    let seen = await info(kai, H)
    check('/chunkhopper info shows Kai as the owner', seen.owner === 'Kai' && seen.filter === 'everything', seen.text)

    const second = new Vec3(44, y, 44)
    lines = await placeAt(kai, second)
    check('a second Chunk Hopper in the same chunk is refused and kept',
      kai.blockAt(second)?.name === 'air' && lines.some((l) => /already has a Chunk Hopper \(at 40, /.test(l)) && (await count(op, kai, 'hopper', folia)) === 1,
      `${kai.blockAt(second)?.name}; ${lines.join(' | ')}`)

    // 3. Collecting, with an empty filter.
    let watch = watchItems(op)
    await killCows()
    watch.stop()
    seen = await info(kai, H)
    const beefCollected = seen.holds.beef ?? 0
    check('killed cows drop no item in the chunk: their beef goes straight into the hopper',
      watch.seen.length === 0 && beefCollected >= 3, `items seen on the ground: ${watch.names().join(',') || 'none'}; ${seen.text}`)
    watch = watchItems(op)
    await breakCactus()
    watch.stop()
    seen = await info(kai, H)
    const cactusBefore = seen.holds.cactus ?? 0
    check('a cactus that breaks off drops into the hopper, not onto the ground', watch.seen.length === 0 && cactusBefore >= 1,
      `ground: ${watch.names().join(',') || 'none'}; ${seen.text}`)

    // 4. What players throw, get or lose stays theirs.
    watch = watchItems(op)
    await holdItem(kai, 'stick')
    await kai.lookAt(new Vec3(40.5, y + 0.5, 45.5), true)
    await kai.tossStack(kai.heldItem).catch(() => {})
    await sleep(1500)
    watch.stop()
    seen = await info(kai, H)
    check('sticks Kai throws lie on the ground, not in the hopper', watch.names().includes('stick') && !seen.holds.stick,
      `ground: ${watch.names().join(',')}; ${seen.text}`)
    await command(op, `/minecraft:kill @e[type=minecraft:item,x=40,y=${y},z=40,distance=..20]`)

    const kaiCactus = await count(op, kai, 'cactus', folia)
    await command(op, '/minecraft:give Kai minecraft:cactus 8')
    await sleep(1500)
    seen = await info(kai, H)
    const kaiCactusGiven = await count(op, kai, 'cactus', folia)
    check('/give in the chunk: Kai gets 8 cactus and the hopper gets no copy', kaiCactusGiven === kaiCactus + 8 && (seen.holds.cactus ?? 0) === cactusBefore,
      `Kai cactus ${kaiCactus} -> ${kaiCactusGiven}; hopper ${seen.text}`)

    await command(op, `/minecraft:tp Luna 36.5 ${y} 44.5`)
    await sleep(1000)
    watch = watchItems(op)
    await command(op, '/minecraft:kill Luna')
    await sleep(2000)
    watch.stop()
    seen = await info(kai, H)
    check("Luna's death drops lie on the ground, not in the hopper", watch.names().includes('gold_ingot') && !seen.holds.gold_ingot,
      `ground: ${watch.names().join(',')}; ${seen.text}`)
    await command(op, `/minecraft:kill @e[type=minecraft:item,x=40,y=${y},z=40,distance=..20]`)

    const mined = new Vec3(36, y - 1, 42)
    await command(op, `/minecraft:tp Kai 37.5 ${y} 42.5`)
    await sleep(800)
    await holdItem(kai, 'iron_shovel')
    watch = watchItems(op)
    await kai.dig(kai.blockAt(mined)).catch(() => {})
    await sleep(1500)
    watch.stop()
    seen = await info(kai, H)
    const status = await statusText(op)
    check('a block Kai mines drops for Kai (collect.player-mined: false)', watch.names().includes('dirt') && !seen.holds.dirt,
      `ground: ${watch.names().join(',')}; ${seen.text}`)
    // Which event names a mined drop first: BlockDropItemEvent (lists the items before they spawn) or, if ItemSpawnEvent
    // came first, the broken block's cell. Either keeps the drop for the player; the counts say which one did.
    const order = /player-mined (\d+) \(by BlockDropItemEvent (\d+), by break position (\d+)\)/.exec(status)
    const listed = /BlockDropItemEvent items: (\d+) \| already spawned when it fired: (\d+)/.exec(status)
    check('the mined drop is recognised as mined', order && Number(order[1]) >= 1, `${order?.[0]}; ${listed?.[0]}`)
    say(`event order: player-mined drops recognised by BlockDropItemEvent ${order?.[2]}, by break position ${order?.[3]}; ` +
      `BlockDropItemEvent listed ${listed?.[1]} item(s), ${listed?.[2]} already spawned when it fired`)
    await command(op, `/minecraft:setblock ${mined.x} ${mined.y} ${mined.z} minecraft:grass_block`)
    await command(op, `/minecraft:kill @e[type=minecraft:item,x=40,y=${y},z=40,distance=..20]`)
    await command(op, `/minecraft:tp Kai 40.5 ${y} 42.5`)
    await sleep(800)

    // 5. The filter menu.
    let menu = await openMenu(kai, kai.blockAt(H))
    check('shift + right-click with an empty hand opens the filter menu', menu && /Chunk Hopper/.test(titleOf(menu)) && menu.inventoryStart === 18,
      menu ? `${titleOf(menu)} (${menu.type}, ${menu.inventoryStart} slots)` : 'no window')
    if (menu) {
      const cactusSlot = ownSlot(menu, 'cactus')
      await kai.clickWindow(cactusSlot, 0, 0)
      await sleep(300)
      await kai.clickWindow(0, 0, 0)
      await sleep(600)
      const icon = kai.currentWindow?.slots?.[0]
      await kai.clickWindow(cactusSlot, 0, 0)
      await sleep(300)
      check('clicking a filter slot with cactus on the cursor shows cactus there', icon?.name === 'cactus', JSON.stringify(icon?.name ?? null))
      kai.closeWindow(kai.currentWindow ?? menu)
      await sleep(400)
    }
    seen = await info(kai, H)
    const kaiCactusKept = await count(op, kai, 'cactus', folia)
    check('the server has the cactus filter, and Kai kept his cactus', seen.filter === 'cactus' && kaiCactusKept === kaiCactus + 8, `Kai cactus ${kaiCactusKept}; ${seen.text}`)

    watch = watchItems(op)
    await killCows()
    watch.stop()
    seen = await info(kai, H)
    check('with the cactus filter, cow drops fall to the ground', watch.names().includes('beef') && (seen.holds.beef ?? 0) === beefCollected,
      `ground: ${watch.names().join(',')}; ${seen.text}`)
    await command(op, `/minecraft:kill @e[type=minecraft:item,x=40,y=${y},z=40,distance=..20]`)
    watch = watchItems(op)
    await breakCactus()
    watch.stop()
    seen = await info(kai, H)
    const cactusNow = seen.holds.cactus ?? 0
    check('cactus still goes into the hopper', watch.seen.length === 0 && cactusNow > cactusBefore, `ground: ${watch.names().join(',') || 'none'}; ${seen.text}`)

    // 6. Raw window clicks of every mode on the menu never hand Kai an icon.
    menu = await openMenu(kai, kai.blockAt(H))
    let barrage = 0
    if (menu) {
      const click = async (slot, mode, button) => {
        rawClick(kai, slot, { mode, mouseButton: button, window: menu })
        barrage++
        await sleep(60)
      }
      for (const slot of [0, 1, 9, 10, 12, 13, 17]) {
        await click(slot, 1, 0) // shift-click out
        await click(slot, 1, 1)
        for (const button of [0, 4, 8, 40]) await click(slot, 2, button) // number keys and the off-hand swap
        await click(slot, 3, 2) // clone (creative only)
        await click(slot, 4, 0) // drop one
        await click(slot, 4, 1) // drop the stack
        await click(slot, 6, 0) // double-click collect
      }
      for (const slot of [9, 10, 11, 12, 14, 15, 16]) {
        await click(slot, 0, 0) // pick up an info or filler icon
        await click(slot, 0, 1)
      }
      // With cactus on the cursor: drag across the menu, and double-click in Kai's own inventory to collect cactus.
      const own = ownSlot(menu, 'cactus')
      await click(own, 0, 0)
      for (const [slot, button] of [[-999, 0], [0, 1], [2, 1], [-999, 2], [-999, 4], [3, 5], [-999, 6], [-999, 8], [4, 9], [-999, 10]]) {
        await click(slot, 5, button)
      }
      await click(own + 1 < menu.inventoryEnd ? own + 1 : own - 1, 6, 0)
      await click(own, 0, 0)
      await click(-999, 0, 0) // outside with whatever is left on the cursor
      await sleep(800)
      kai.closeWindow(kai.currentWindow ?? menu)
      await sleep(800)
    }
    let icons = 0
    for (const item of ['book', 'barrier', 'gray_stained_glass_pane', 'lime_dye', 'gray_dye']) icons += await count(op, kai, item, folia)
    const kaiCactusAfter = await count(op, kai, 'cactus', folia)
    const statusAfter = await statusText(op)
    const removed = Number(/filter icons removed from players: (\d+)/.exec(statusAfter)?.[1] ?? -1)
    seen = await info(kai, H)
    check(`${barrage} raw clicks of every mode never hand Kai a menu icon`, menu && barrage >= 90 && icons === 0 && removed === 0 &&
      seen.filter === 'cactus' && (seen.holds.cactus ?? 0) === cactusNow && kaiCactusAfter === kaiCactus + 8,
      `icons on Kai ${icons}, removed by the safety net ${removed}, Kai cactus ${kaiCactusAfter}; ${seen.text}`)
    await sleep(1500)
    await command(op, `/minecraft:kill @e[type=minecraft:item,x=40,y=${y},z=40,distance=..20]`)

    // 7. Luna may neither open nor break it.
    await command(op, '/minecraft:give Luna minecraft:diamond_pickaxe 1')
    await command(op, `/minecraft:tp Luna 38.5 ${y} 41.5`)
    await sleep(1200)
    let from = luna.lines.length
    const lunaMenu = await openMenu(luna, luna.blockAt(H))
    check("Luna cannot open Kai's filter", !lunaMenu && luna.lines.slice(from).map(plain).some((l) => /belongs to Kai/.test(l)),
      lunaMenu ? titleOf(lunaMenu) : luna.lines.slice(from).map(plain).join(' | '))
    from = luna.lines.length
    await holdItem(luna, 'diamond_pickaxe')
    await luna.dig(luna.blockAt(H)).catch(() => {})
    await sleep(1200)
    check("Luna cannot break Kai's Chunk Hopper", op.blockAt(H)?.name === 'hopper' && luna.lines.slice(from).map(plain).some((l) => /belongs to Kai/.test(l)),
      `${op.blockAt(H)?.name}; ${luna.lines.slice(from).map(plain).join(' | ')}`)
    await command(op, `/minecraft:tp Luna 10.5 ${y} 8.5`)

    // 8. Reload keeps the hopper; then selling into Kai's balance.
    // Kai takes the beef and leather out through the hopper's own window (it is still a hopper), so it holds only cactus.
    const hopperWindow = await opened(kai, async () => {
      await emptyHand(kai)
      await kai.lookAt(H.offset(0.5, 0.5, 0.5), true)
      await kai.activateBlock(kai.blockAt(H)).catch(() => {})
    })
    if (hopperWindow) {
      for (let slot = 0; slot < 5; slot++) {
        const item = hopperWindow.slots[slot]
        if (item && item.name !== 'cactus') await kai.clickWindow(slot, 0, 1).catch(() => {})
        await sleep(200)
      }
      kai.closeWindow(hopperWindow)
      await sleep(500)
    }
    seen = await info(kai, H)
    const cactusForSale = seen.holds.cactus ?? 0
    check('a plain right-click opens it as a hopper, and Kai takes the beef out', hopperWindow && Object.keys(seen.holds).join() === 'cactus' && cactusForSale > 0,
      `${hopperWindow?.type}; ${seen.text}`)

    setConfig(dir, (text) => text.replace('interval-seconds: 10', 'interval-seconds: 3'))
    await command(op, '/chunkhopper reload', /Reloaded/)
    await sleep(1500)
    seen = await info(kai, H)
    check('after /chunkhopper reload the hopper keeps its owner, filter and contents', seen.owner === 'Kai' && seen.filter === 'cactus' &&
      (seen.holds.cactus ?? 0) === cactusForSale && /off/.test(seen.sell ?? ''), seen.text)

    const statusNow = await statusText(op)
    const essentials = /EssentialsX worth, then config/.test(statusNow)
    const unit = essentials ? 10 : 2
    const before = await balance(kai)
    from = kai.lines.length
    let toggle = await toggleSell()
    check('the menu switches auto-sell on', toggle === 'lime_dye', toggle)
    let sale = await nextSale(from)
    await sleep(500)
    const after = await balance(kai)
    check(`the hopper sells its ${cactusForSale} cactus and Kai's balance rises by exactly the sale`, sale?.items === cactusForSale &&
      after - before === sale?.cents && sale?.cents === cactusForSale * unit * 100,
      `${sale?.line}; balance ${before} -> ${after} cents; price ${unit} (${essentials ? 'EssentialsX' : 'config.yml'})`)
    seen = await info(kai, H)
    check('the sold cactus left the hopper', !seen.holds.cactus, seen.text)
    toggle = await toggleSell()
    seen = await info(kai, H)
    check('and switches it off again', toggle === 'gray_dye' && /off/.test(seen.sell ?? ''), seen.text)

    // 9. Breaking with a pickaxe: one Chunk Hopper item comes back, and the contents drop exactly once.
    const hopperWindow2 = await opened(kai, async () => {
      await emptyHand(kai)
      await kai.lookAt(H.offset(0.5, 0.5, 0.5), true)
      await kai.activateBlock(kai.blockAt(H)).catch(() => {})
    })
    if (hopperWindow2) {
      const slot = ownSlot(hopperWindow2, 'cobblestone')
      if (slot >= 0) await kai.clickWindow(slot, 0, 1).catch(() => {})
      await sleep(500)
      kai.closeWindow(hopperWindow2)
      await sleep(400)
    }
    seen = await info(kai, H)
    const cobbleInHopper = seen.holds.cobblestone ?? 0
    const kaiCobble = await count(op, kai, 'cobblestone', folia)
    const kaiHoppers = await count(op, kai, 'hopper', folia)
    await holdItem(kai, 'diamond_pickaxe')
    watch = watchItems(op)
    await kai.dig(kai.blockAt(H)).catch(() => {})
    await sleep(700)
    await command(op, `/minecraft:tp Kai ${H.x + 0.5} ${y} ${H.z + 0.5}`)
    await sleep(2500)
    watch.stop()
    const hopperDrops = watch.seen.filter((e) => itemName(e) === 'hopper').length
    const cobbleDrops = watch.seen.filter((e) => itemName(e) === 'cobblestone').length
    const nowCobble = await count(op, kai, 'cobblestone', folia)
    const nowHoppers = await count(op, kai, 'hopper', folia)
    const back = kai.inventory.items().find(isChunkHopperItem)
    check('Kai breaks it with a pickaxe and gets exactly one Chunk Hopper item back', op.blockAt(H)?.name === 'air' && hopperDrops === 1 &&
      nowHoppers === kaiHoppers + 1 && back?.count === nowHoppers, `hopper items dropped ${hopperDrops}, Kai hoppers ${kaiHoppers} -> ${nowHoppers}, client ${back?.count}`)
    const breakStatus = await statusText(op)
    const breakEvent = /Chunk Hopper breaks: (\d+) listing (\d+) items/.exec(breakStatus)
    say(`the Chunk Hopper's break: BlockDropItemEvent listed ${breakEvent?.[2]} item(s) for ${breakEvent?.[1]} break(s)`)
    check(`its contents (${cobbleInHopper} cobblestone) drop exactly once`, cobbleInHopper === 7 && nowCobble === kaiCobble + cobbleInHopper && cobbleDrops === 1,
      `cobblestone entities ${cobbleDrops}; Kai cobblestone ${kaiCobble} -> ${nowCobble}; its BlockDropItemEvent listed ${breakEvent?.[2]} item(s)`)
    await command(op, `/minecraft:tp Kai 40.5 ${y} 42.5`)
    await sleep(800)
    lines = await placeAt(kai, H)
    seen = await info(kai, H)
    check('placed again, it is a Chunk Hopper again', lines.some((l) => /Chunk Hopper placed/.test(l)) && seen.owner === 'Kai', seen.text)

    // Cactus filter and some cactus in it, for the restarts.
    menu = await openMenu(kai, kai.blockAt(H))
    if (menu) {
      const cactusSlot = ownSlot(menu, 'cactus')
      await kai.clickWindow(cactusSlot, 0, 0)
      await sleep(300)
      await kai.clickWindow(0, 0, 0)
      await sleep(400)
      await kai.clickWindow(cactusSlot, 0, 0)
      await sleep(300)
      kai.closeWindow(kai.currentWindow ?? menu)
    }
    await breakCactus()
    await breakCactus()
    const kept = await info(kai, H)

    // 10. A clean restart.
    await restart({ hard: false })
    await reconnect()
    await command(op, `/minecraft:tp Kai 40.5 ${y} 42.5`)
    await command(op, `/minecraft:tp NyrOp 40.5 ${y} 47.5`)
    await sleep(2500)
    seen = await info(kai, H)
    check('after a clean restart the hopper keeps its owner, filter and contents', seen.owner === 'Kai' && seen.filter === 'cactus' &&
      (seen.holds.cactus ?? 0) === (kept.holds.cactus ?? 0) && (kept.holds.cactus ?? 0) >= 1 && seen.collected >= kept.collected, `${kept.text} => ${seen.text}`)
    await breakCactus()
    const afterClean = await info(kai, H)
    check('after a clean restart it collects at once', (afterClean.holds.cactus ?? 0) > (seen.holds.cactus ?? 0), afterClean.text)

    // 11. A hard kill seconds after a sale. The saved world is the one before the sale (and before a Chunk Hopper placed
    //     meanwhile in chunk 5,2): each item must end up paid for at most once, and hoppers.yml must follow the world.
    //     Folia answers no /save-all: there the saved world is the one of the clean stop above.
    const saved = await command(op, '/minecraft:save-all flush', /Saved the game/i, 20_000).catch(() => null)
    await sleep(1000)
    await command(op, `/minecraft:tp Kai ${B.x + 0.5} ${y} ${B.z + 2.5}`)
    await sleep(1500)
    await placeAt(kai, B)
    const listedBefore = plain(await command(kai, '/chunkhopper list', /owns \d+ Chunk Hopper/))
    await command(op, `/minecraft:tp Kai 40.5 ${y} 42.5`)
    await sleep(1500)
    const balanceBeforeSale = await balance(kai)
    from = kai.lines.length
    toggle = await toggleSell()
    sale = await nextSale(from)
    const balanceSold = await balance(kai)
    const soldHere = await info(kai, H)
    check('a sale is paid, then the server is killed before it saves', toggle === 'lime_dye' && sale && sale.items >= 1 && !soldHere.holds.cactus &&
      balanceSold - balanceBeforeSale === sale.cents, `${saved ? 'saved first' : 'no /save-all here'}; ${sale?.line}; balance ${balanceBeforeSale} -> ${balanceSold}`)
    await sleep(3000) // the economy writes its balance files
    await restart({ hard: true })
    await reconnect()
    await command(op, `/minecraft:tp Kai ${B.x + 0.5} ${y} ${B.z + 2.5}`)
    await sleep(3000)
    const listedAfter = plain(await command(kai, '/chunkhopper list', /owns \d+ Chunk Hopper/))
    const bLines = kai.lines.slice(-6).map(plain)
    const bStands = kai.blockAt(B)?.name === 'hopper'
    const bListed = bLines.some((l) => new RegExp(` ${B.x} ${y} ${B.z}$`).test(l))
    check('after a hard kill /chunkhopper list follows the world', bStands === bListed && /owns [12] /.test(listedAfter) && /owns 2 /.test(listedBefore),
      `before: ${listedBefore}; after: ${listedAfter}; chunk 5,2 ${bStands ? 'kept its Chunk Hopper' : 'was rolled back'}`)
    await command(op, `/minecraft:tp Kai 40.5 ${y} 42.5`)
    await command(op, `/minecraft:tp NyrOp 40.5 ${y} 47.5`)
    await sleep(2500)
    seen = await info(kai, H)
    const balanceAfterKill = await balance(kai)
    const crashStatus = await statusText(op)
    const undone = /crash rollbacks undone: (\d+) sale\(s\), (\d+) item\(s\)/.exec(crashStatus)
    const paidItems = Math.round((balanceAfterKill - balanceBeforeSale) / (unit * 100))
    check('after a hard kill each sold item is paid for at most once (balance and hopper together)', sale && seen.owner === 'Kai' && seen.filter === 'cactus' &&
      !seen.holds.cactus && paidItems + (seen.holds.cactus ?? 0) <= sale.items,
      `sold ${sale?.items}; paid for after the kill ${paidItems}${folia ? ' (the in-memory test economy forgets balances on a kill)' : ''}; ` +
      `cactus in the hopper ${seen.holds.cactus ?? 0}; ${undone?.[0] ?? crashStatus}`)
    say(`crash after a sale: ${undone && Number(undone[1]) > 0 ? `the world came back without the sale and ${undone[2]} item(s) were taken out again` : 'the saved world already had the sale'}; balance ${balanceBeforeSale} -> ${balanceSold} -> ${balanceAfterKill}`)
    if (target.bots && !target.spigot) {
      // Paper, Purpur and Folia save chunks only at the autosave: here the world must have come back without the sale.
      check('the crash rolled the sale back and it was taken out again as the chunk loaded', undone && Number(undone[1]) >= 1 && Number(undone[2]) >= 1,
        crashStatus)
    }

    // 12. A sale before a clean restart is paid once, and nothing is taken out again.
    if (!/on/.test(seen.sell ?? '')) await toggleSell()
    from = kai.lines.length
    await breakCactus()
    await breakCactus()
    sale = await nextSale(from)
    const balanceBeforeClean = await balance(kai)
    await sleep(2000)
    await restart({ hard: false })
    await reconnect()
    await command(op, `/minecraft:tp Kai 40.5 ${y} 42.5`)
    await command(op, `/minecraft:tp NyrOp 40.5 ${y} 47.5`)
    await sleep(2500)
    seen = await info(kai, H)
    const balanceAfterClean = await balance(kai)
    const cleanStatus = await statusText(op)
    check('a sale before a clean restart is paid once and nothing is taken out again', sale && sale.items >= 1 && !seen.holds.cactus &&
      (folia || balanceAfterClean === balanceBeforeClean) && /crash rollbacks undone: 0 sale/.test(cleanStatus),
      `${sale?.line}; balance ${balanceBeforeClean} -> ${balanceAfterClean}${folia ? ' (the in-memory test economy forgets balances on a restart)' : ''}; ` +
      `${/crash rollbacks undone: [^|]*/.exec(cleanStatus)?.[0]}; ${seen.text}`)
    await toggleSell()

    // 13. The per-player limit: Kai at his limit may not place another; without a limit he may.
    await command(op, '/chunkhopper give Kai 1', /Gave/)
    const ownedNow = Number(/owns (\d+) /.exec(plain(await command(kai, '/chunkhopper list', /owns \d+ Chunk Hopper/)))?.[1] ?? 0)
    setConfig(dir, (text) => text.replace('per-player: 0', `per-player: ${ownedNow}`))
    await command(op, '/chunkhopper reload', /Reloaded/)
    await command(op, `/minecraft:tp Kai ${C.x + 0.5} ${y} ${C.z + 2.5}`)
    await sleep(1500)
    lines = await placeAt(kai, C)
    check(`limits.per-player ${ownedNow}: Kai (owning ${ownedNow}) may not place another`, kai.blockAt(C)?.name !== 'hopper' && lines.some((l) => /your limit/.test(l)),
      lines.join(' | '))
    setConfig(dir, (text) => text.replace(`per-player: ${ownedNow}`, 'per-player: 0'))
    await command(op, '/chunkhopper reload', /Reloaded/)
    from = kai.lines.length
    lines = await placeAt(kai, C)
    const owns = plain((await command(kai, '/chunkhopper list', /owns \d+ Chunk Hopper/)) ?? '')
    await sleep(500)
    const listLines = kai.lines.slice(from).map(plain)
    check('without the limit it is placed, and /chunkhopper list shows it', new RegExp(`owns ${ownedNow + 1} `).test(owns) &&
      listLines.some((l) => new RegExp(` ${H.x} ${y} ${H.z}$`).test(l)) && listLines.some((l) => new RegExp(` ${C.x} ${y} ${C.z}$`).test(l)), listLines.join(' | '))

    // 14. Explosion-proof, and it still feeds a chest below it. Kai waits out the blast well away from it.
    await command(op, `/minecraft:tp Kai 40.5 ${y} 70.5`)
    await command(op, `/minecraft:tp NyrOp 40.5 ${y} 47.5`)
    await sleep(1500)
    await breakCactus()
    await breakCactus()
    await command(op, `/minecraft:summon minecraft:tnt ${H.x + 1.5} ${y} ${H.z + 0.5} {fuse:20}`)
    await sleep(3500)
    await command(op, `/minecraft:tp Kai 40.5 ${y} 43.5`)
    await sleep(1500)
    seen = await info(kai, H)
    check('TNT beside it leaves the Chunk Hopper standing', op.blockAt(H)?.name === 'hopper' && seen.owner === 'Kai', `${op.blockAt(H)?.name}; ${seen.text}`)
    await command(op, `/minecraft:setblock ${H.x} ${y - 1} ${H.z} minecraft:chest`)
    const inHopper = seen.holds.cactus ?? 0
    await sleep(4000)
    seen = await info(kai, H)
    check('it still feeds a chest below it, like any hopper', inHopper >= 1 && (seen.holds.cactus ?? 0) < inHopper, `cactus in the hopper ${inHopper} -> ${seen.holds.cactus ?? 0}`)

    const finalStatus = await statusText(op)
    say(`status: ${finalStatus}`)
    const ground = /Left on the ground: (.*?) \| BlockDropItemEvent/.exec(finalStatus)?.[1]
    // Counts start again with each server start: this is the run since the last restart.
    check('/chunkhopper status counts what it collected and left alone', /Collected since start: [1-9]/.test(finalStatus) &&
      /filter icons removed from players: 0/.test(finalStatus), `${/Collected since start: [^|]*\| sold: [^|]*/.exec(finalStatus)?.[0]}; ${ground}`)
  } finally {
    quitAll([op, kai, luna])
    await sleep(500)
  }
}
