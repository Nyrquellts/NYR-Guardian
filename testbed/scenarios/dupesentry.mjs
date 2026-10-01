// NYR-DupeSentry on a live server. Each documented dupe is built block by block with commands, run once with DupeSentry's
// guards switched off in config.yml and once with them on, and what exists afterwards is counted by the server itself
// (/execute if block, /execute if entity with item counts, /fill ... replace in the End), never from a client's cache:
//   - TNT duper: a piston pushes a TNT that is powered without having been told (a lever on the block above it); a torch
//     beside it breaks off in the middle of the push and its block update lights the TNT the piston is still moving;
//   - carpet duper: a carpet in a slime push stands on a pumpkin the same push destroys, so it breaks off while moving;
//   - rail duper: the same with a rail, which breaks off when the torch beside it breaks off;
//   - gravity block through an end portal: a falling sand block on the ground inside an end portal;
//   - tripwire hook duplicator: a hook on a door whose string, when tripped, opens that door through dust (the string set
//     in its tripped state, and an item landing on it at three spots: dust updates its neighbours in an order that depends
//     on its coordinates, so the item-tripped rig duplicates at some spots and not at others).
// On Paper, Purpur and Folia the server's own fixes block the first four; there the scenario also restarts the server with
// Paper's unsupported-settings turned on and runs everything again. Then vanilla parity (pushes, portals and chests that
// are no dupe behave as before) and stale container screens (a chest broken or a teleport while a player has it open, an
// item held on the cursor at quit).

import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { Vec3, command, connectWhenUp, gamerule, quitAll, sleep, waitFor } from '../lib/bots.mjs'

export const jarName = 'NYR-DupeSentry'
export const displayName = 'NYR DupeSentry'

const plain = (text) => String(text ?? '').replace(/§./g, '')
const REPLY = /Test passed|Test failed|No entity was found|Killed|Successfully|No blocks were|Could not|Unknown|Incorrect|Changed the block|Found \d+|No items were found|Summoned|Teleported|Set the|Added|Removed|marked|already|Gave|Expected|Invalid|Chunk|forceload|nothing changed|That position|game mode/i

const DUPES = ['tnt', 'carpet', 'rail', 'portal', 'hook', 'hookTrip']
const NAMES = {
  tnt: 'TNT duper',
  carpet: 'carpet duper',
  rail: 'rail duper',
  portal: 'sand through an end portal',
  hook: 'tripwire hook duplicator (string tripped)',
  hookTrip: 'tripwire hook duplicator (item on the string, 3 spots)'
}
const GUARD_OF = { tnt: 'piston-dupes', carpet: 'piston-dupes', rail: 'piston-dupes', portal: 'portal-gravity', hook: 'tripwire-hooks', hookTrip: 'tripwire-hooks' }
/** Spots where the item-tripped hook rig duplicated on Spigot 1.21.11 in the lab (it did not at x 110 or 126, z 60). */
const HOOK_TRIP_SPOTS = [[110, 20], [126, 20], [150, 84]]

/** Sends a command and resolves with its reply line (or a TIMEOUT line): the server's answer, not the client's view. */
async function q (op, text, timeoutMs = 6000) {
  try {
    return plain(await command(op, text, REPLY, timeoutMs))
  } catch (error) {
    return 'TIMEOUT ' + String(error.message).slice(0, 160)
  }
}

/** Every chat line arriving within {@code waitMs} after a command. */
async function ask (bot, text, waitMs = 1200) {
  const from = bot.lines.length
  bot.chat(text)
  await sleep(waitMs)
  return bot.lines.slice(from).map(plain)
}

const sb = (op, x, y, z, block, mode = '') => q(op, `/minecraft:setblock ${x} ${y} ${z} ${block}${mode ? ' ' + mode : ''}`)

function inDim (op, dim) {
  return dim && !op.folia ? `in ${dim} ` : ''
}

async function blockIs (op, x, y, z, id, dim = '') {
  return /Test passed/.test(await q(op, `/minecraft:execute ${inDim(op, dim)}if block ${x} ${y} ${z} ${id}`))
}

async function countEntities (op, selector, dim = '') {
  const reply = await q(op, `/minecraft:execute ${inDim(op, dim)}if entity @e[${selector}]`)
  const m = /count: (\d+)/.exec(reply)
  return m ? Number(m[1]) : (/Test passed/.test(reply) ? 1 : 0)
}

const box = (x, y, z, d) => `x=${x - d},y=${y - d},z=${z - d},dx=${2 * d},dy=${2 * d},dz=${2 * d}`

/**
 * How many of an item lie in a box, counting stacks: items dropped in one spot merge, so the entity count alone would
 * miss a second copy. Stacks of 2 to 16 are counted by exact count (every server answers /execute if entity; Folia's
 * /clear and /data replies carry no counts); an entity with no count tag holds one.
 */
async function amount (op, id, x, y, z, d = 5, dim = '') {
  const where = `type=minecraft:item,${box(x, y, z, d)}`
  const entities = await countEntities(op, `${where},nbt={Item:{id:"${id}"}}`, dim)
  if (entities === 0) return 0
  let total = 0
  let stacks = 0
  for (let k = 2; k <= 16 && stacks < entities; k++) {
    const n = await countEntities(op, `${where},nbt={Item:{id:"${id}",count:${k}}}`, dim)
    total += k * n
    stacks += n
  }
  return total + (entities - stacks)
}

/** Whether a player's saved inventory list holds a stack of exactly this item and count (the cursor is not in it). */
async function holds (op, player, id, count = null) {
  const item = count === null ? `{id:"${id}"}` : `{id:"${id}",count:${count}}`
  return /Test passed/.test(await q(op, `/minecraft:execute if data entity ${player} Inventory[${item}]`))
}

async function clearSite (op, x, y, z, r = 7) {
  await q(op, `/minecraft:fill ${x - r} ${y - 2} ${z - r} ${x + r} ${y + 5} ${z + r} minecraft:air`)
  await q(op, `/minecraft:kill @e[type=!minecraft:player,${box(x, y, z, r + 2)}]`)
}

/** Every counted thing about the End's arrival platform; on Folia the op walks there, as commands cannot reach across. */
async function endSand (op, back) {
  let blocks
  let items
  if (op.folia) {
    await q(op, '/minecraft:execute in minecraft:the_end run tp NyrOp 100.5 52 3.5')
    await sleep(2500)
    blocks = Number(/filled (\d+)/.exec(await q(op, '/minecraft:fill 95 44 -5 105 60 5 minecraft:air replace minecraft:sand'))?.[1] ?? 0)
    items = await amount(op, 'minecraft:sand', 100, 50, 0, 12)
    await q(op, `/minecraft:kill @e[type=minecraft:item,${box(100, 50, 0, 14)}]`)
    await q(op, `/minecraft:execute in minecraft:overworld run tp NyrOp ${back.x} ${back.y} ${back.z}`)
    await sleep(2500)
  } else {
    blocks = Number(/filled (\d+)/.exec(await q(op, '/minecraft:execute in minecraft:the_end run fill 95 44 -5 105 60 5 minecraft:air replace minecraft:sand'))?.[1] ?? 0)
    items = await amount(op, 'minecraft:sand', 100, 50, 0, 12, 'minecraft:the_end')
    await q(op, `/minecraft:execute in minecraft:the_end run kill @e[type=minecraft:item,${box(100, 50, 0, 14)}]`)
  }
  return { blocks, items }
}

async function goTo (op, x, y, z) {
  await q(op, `/minecraft:tp NyrOp ${x} ${y} ${z}`)
  await sleep(1200)
}

// --- the dupes, each at its own spot -------------------------------------------------------------------------------------

/** Carpet on a pumpkin: the slime's push destroys the pumpkin, then the carpet breaks off while the piston still moves it. */
async function carpet (op, x, y, z) {
  await goTo(op, x + 2, y + 2, z - 5)
  await clearSite(op, x, y, z)
  await sb(op, x, y, z, 'minecraft:piston[facing=east]')
  await sb(op, x + 1, y, z, 'minecraft:slime_block')
  await sb(op, x + 1, y + 1, z, 'minecraft:stone')
  await sb(op, x + 2, y, z, 'minecraft:pumpkin')
  await sb(op, x + 2, y + 1, z, 'minecraft:white_carpet')
  await sb(op, x + 3, y, z, 'minecraft:stone')
  await sleep(300)
  const ready = await blockIs(op, x + 2, y + 1, z, 'minecraft:white_carpet')
  await sb(op, x - 1, y, z, 'minecraft:redstone_block')
  await sleep(900)
  const out = {
    ready,
    moved: await blockIs(op, x + 3, y + 1, z, 'minecraft:white_carpet'),
    left: await blockIs(op, x + 2, y + 1, z, 'minecraft:white_carpet'),
    items: await amount(op, 'minecraft:white_carpet', x + 2, y + 1, z)
  }
  out.total = Number(out.moved) + Number(out.left) + out.items
  await clearSite(op, x, y, z)
  return out
}

/** The rail version: the rail breaks off when the torch beside it breaks off, which happens mid-push. */
async function rail (op, x, y, z) {
  await goTo(op, x + 2, y + 2, z - 5)
  await clearSite(op, x, y, z)
  await sb(op, x, y, z, 'minecraft:piston[facing=east]')
  await sb(op, x + 1, y, z, 'minecraft:slime_block')
  await sb(op, x + 1, y + 1, z, 'minecraft:stone')
  await sb(op, x + 2, y, z, 'minecraft:pumpkin')
  await sb(op, x + 2, y + 1, z, 'minecraft:rail[shape=east_west]')
  await sb(op, x + 1, y, z + 1, 'minecraft:stone')
  await sb(op, x + 2, y, z + 1, 'minecraft:pumpkin')
  await sb(op, x + 2, y + 1, z + 1, 'minecraft:torch')
  await sb(op, x + 3, y, z, 'minecraft:stone')
  await sleep(300)
  const ready = await blockIs(op, x + 2, y + 1, z, 'minecraft:rail')
  await sb(op, x - 1, y, z, 'minecraft:redstone_block')
  await sleep(900)
  const out = {
    ready,
    moved: await blockIs(op, x + 3, y + 1, z, 'minecraft:rail'),
    left: await blockIs(op, x + 2, y + 1, z, 'minecraft:rail'),
    items: await amount(op, 'minecraft:rail', x + 2, y + 1, z)
  }
  out.total = Number(out.moved) + Number(out.left) + out.items
  await clearSite(op, x, y, z)
  return out
}

/**
 * TNT pushed by a slime line. The lever on the stone above the TNT powers that stone without telling the TNT (its block
 * updates reach only the lever's own neighbours), so the TNT is powered but unlit until the torch beside it breaks off in
 * the middle of the push. Placed by commands; in a survival duper the same moment comes from the duper's own clock.
 */
async function tnt (op, x, y, z, { popper = true, lever = true } = {}) {
  await goTo(op, x + 2, y + 2, z - 5)
  await clearSite(op, x, y, z)
  await sb(op, x, y, z, 'minecraft:piston[facing=east]')
  await sb(op, x + 1, y, z, 'minecraft:slime_block')
  await sb(op, x + 1, y + 1, z, 'minecraft:stone')
  await sb(op, x + 2, y + 1, z, 'minecraft:tnt')
  if (popper) {
    await sb(op, x + 1, y, z + 1, 'minecraft:stone')
    await sb(op, x + 2, y, z + 1, 'minecraft:pumpkin')
    await sb(op, x + 2, y + 1, z + 1, 'minecraft:torch')
  }
  await sb(op, x + 2, y + 2, z, 'minecraft:stone')
  await sleep(200)
  if (lever) await sb(op, x + 2, y + 3, z, 'minecraft:lever[face=floor,facing=east,powered=true]')
  await sleep(300)
  const ready = await blockIs(op, x + 2, y + 1, z, 'minecraft:tnt') && await countEntities(op, `type=minecraft:tnt,${box(x + 2, y, z, 6)}`) === 0
  await sb(op, x - 1, y, z, 'minecraft:redstone_block')
  await sleep(700)
  const out = {
    ready,
    primed: await countEntities(op, `type=minecraft:tnt,${box(x + 2, y, z, 6)}`),
    moved: await blockIs(op, x + 3, y + 1, z, 'minecraft:tnt'),
    left: await blockIs(op, x + 2, y + 1, z, 'minecraft:tnt')
  }
  await q(op, `/minecraft:kill @e[type=minecraft:tnt,${box(x + 2, y, z, 8)}]`)
  out.total = out.primed + Number(out.moved) + Number(out.left)
  await clearSite(op, x, y, z)
  return out
}

/** A falling sand block on the ground inside an end portal: it is in the portal and landing in the same tick. */
async function portal (op, x, y, z) {
  const back = { x, y: y + 2, z: z - 5 }
  await goTo(op, back.x, back.y, back.z)
  await clearSite(op, x, y, z, 4)
  if (!op.folia) await q(op, '/minecraft:execute in minecraft:the_end run forceload add 96 -4 104 4')
  await endSand(op, back)
  await sb(op, x, y - 1, z, 'minecraft:stone')
  await sb(op, x, y, z, 'minecraft:end_portal')
  await sleep(300)
  await q(op, `/minecraft:summon minecraft:falling_block ${x + 0.5} ${y} ${z + 0.5} {BlockState:{Name:"minecraft:sand"},Time:1}`)
  await sleep(3000)
  const here = await amount(op, 'minecraft:sand', x, y, z, 4)
  const there = await endSand(op, back)
  const out = { here, endBlocks: there.blocks, endItems: there.items }
  out.total = here + there.blocks + there.items
  await clearSite(op, x, y, z, 4)
  return out
}

/**
 * The west hook hangs on the east face of a closed door; the east hook's own power runs back through dust to the block
 * beside that door. An item landing on the string trips it: the game powers the east hook first, the dust opens the door,
 * the west hook breaks off and drops, and the game then sets the west hook again.
 */
async function hook (op, x, y, z, trigger = 'string') {
  await goTo(op, x + 2, y + 2, z - 5)
  await clearSite(op, x + 2, y, z)
  for (let i = 0; i <= 4; i++) await sb(op, x + i, y - 1, z, 'minecraft:stone')
  for (let i = 0; i <= 4; i++) await sb(op, x + i, y, z + 1, 'minecraft:stone')
  await sb(op, x, y, z, 'minecraft:oak_door[facing=west,half=lower,open=false,hinge=left,powered=false]')
  await sb(op, x, y + 1, z, 'minecraft:oak_door[facing=west,half=upper,open=false,hinge=left,powered=false]')
  await sb(op, x + 4, y, z, 'minecraft:stone')
  await sb(op, x + 1, y, z, 'minecraft:tripwire_hook[facing=east]')
  await sb(op, x + 3, y, z, 'minecraft:tripwire_hook[facing=west]')
  await sb(op, x + 4, y + 1, z, 'minecraft:redstone_wire')
  for (let i = 4; i >= 0; i--) await sb(op, x + i, y + 1, z + 1, 'minecraft:redstone_wire')
  let ready
  if (trigger === 'item') {
    await sb(op, x + 2, y, z, 'minecraft:tripwire[attached=true,east=true,west=true]')
    await sleep(700)
    ready = await blockIs(op, x + 1, y, z, 'minecraft:tripwire_hook') && await blockIs(op, x, y, z, 'minecraft:oak_door[open=false]')
    await q(op, `/minecraft:summon minecraft:item ${x + 2.5} ${y + 0.2} ${z + 0.5} {Item:{id:"minecraft:cobblestone",count:1},PickupDelay:32767}`)
  } else {
    // The string placed already tripped: the game runs the same hook update an entity on the string starts.
    await sleep(300)
    ready = await blockIs(op, x + 1, y, z, 'minecraft:tripwire_hook') && await blockIs(op, x, y, z, 'minecraft:oak_door[open=false]')
    await sb(op, x + 2, y, z, 'minecraft:tripwire[attached=true,powered=true,east=true,west=true]')
  }
  await sleep(1500)
  const out = {
    ready,
    standing: await blockIs(op, x + 1, y, z, 'minecraft:tripwire_hook'),
    items: await amount(op, 'minecraft:tripwire_hook', x + 1, y, z, 5)
  }
  out.total = Number(out.standing) + out.items
  await clearSite(op, x + 2, y, z)
  return out
}

/** The item-tripped rig at each of the three spots; it counts as duplicated when any spot duplicated. */
async function hookTrip (op, y) {
  const spots = []
  for (const [x, z] of HOOK_TRIP_SPOTS) spots.push(await hook(op, x, y, z, 'item'))
  return { ready: spots.every((s) => s.ready), spots: spots.map((s) => s.total), total: Math.max(...spots.map((s) => s.total)), atLeastOne: Math.min(...spots.map((s) => s.total)) }
}

const BUILD = { tnt, carpet, rail, portal, hook }

/** Every dupe once, each at its own spot for this pass. */
async function allDupes (op, y, pass, say) {
  const out = {}
  for (const [i, dupe] of DUPES.entries()) {
    const x = 30 + i * 24
    const z = 60 + pass * 24
    out[dupe] = dupe === 'hookTrip' ? await hookTrip(op, y) : await BUILD[dupe](op, x, y, z)
    say(`${pass}: ${dupe} ${JSON.stringify(out[dupe])}`)
  }
  return out
}

const duped = (r) => r.total >= 2

// --- DupeSentry's own view ---------------------------------------------------------------------------------------------

async function status (op) {
  const lines = await ask(op, '/dupesentry status', 1500)
  const guards = {}
  for (const line of lines) {
    const m = /- (piston-dupes|portal-gravity|tripwire-hooks|container-desync): (.*?) \((\d+) (?:stopped|screens closed) since start\)/.exec(line)
    if (m) guards[m[1]] = { state: m[2], count: Number(m[3]) }
  }
  return { lines, guards }
}

function configFile (dir) {
  return join(dir, 'plugins', 'NYR-DupeSentry', 'config.yml')
}

/** Sets one boolean in a guard's section of config.yml, as a server owner would. */
function setGuard (dir, guard, key, value) {
  const file = configFile(dir)
  const text = readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
  const start = text.indexOf(`\n  ${guard}:\n`)
  if (start < 0) throw new Error(`config.yml has no ${guard} section`)
  const re = new RegExp(`\\n    ${key}: (true|false)\\n`)
  const rest = text.slice(start + 1)
  const m = re.exec(rest)
  if (!m) throw new Error(`the ${guard} section has no ${key}`)
  const at = start + 1 + m.index
  writeFileSync(file, text.slice(0, at) + `\n    ${key}: ${value}\n` + text.slice(at + m[0].length))
}

async function guardsOn (op, dir, on) {
  for (const guard of ['piston-dupes', 'portal-gravity', 'tripwire-hooks']) setGuard(dir, guard, 'enabled', on)
  const reply = await ask(op, '/dupesentry reload', 1200)
  return reply.some((line) => /Reloaded config\.yml/.test(line))
}

function paperGlobal (dir) {
  return join(dir, 'config', 'paper-global.yml')
}

/** Turns Paper's unsupported-settings for these dupes on (the server must restart to use them); returns the original text. */
function unsafePaper (dir) {
  const file = paperGlobal(dir)
  const original = readFileSync(file, 'utf8')
  let text = original
  for (const key of ['allow-piston-duplication', 'allow-unsafe-end-portal-teleportation', 'skip-tripwire-hook-placement-validation']) {
    text = text.replace(new RegExp(`(\\n  ${key}: )false`), '$1true')
  }
  writeFileSync(file, text)
  return original
}

// --- vanilla parity: what is no dupe works as before ---------------------------------------------------------------------

async function parity (op, y, check, say) {
  const z = 60 + 5 * 24
  // A plain TNT push: nothing lights it, it is simply moved.
  const plainPush = await tnt(op, 30, y, z, { popper: false, lever: false })
  check('parity: a piston pushes a plain TNT block and it arrives unlit', plainPush.moved && plainPush.primed === 0 && !plainPush.left, JSON.stringify(plainPush))

  // TNT pushed next to a redstone block is lit where it arrives, two ticks after the push (TNT cannons, bombers).
  {
    const [x, zz] = [54, z]
    await goTo(op, x + 2, y + 2, zz - 5)
    await clearSite(op, x, y, zz)
    await sb(op, x, y, zz, 'minecraft:piston[facing=east]')
    await sb(op, x + 1, y, zz, 'minecraft:tnt')
    await sb(op, x + 2, y + 1, zz, 'minecraft:redstone_block')
    await sleep(300)
    await sb(op, x - 1, y, zz, 'minecraft:redstone_block')
    await sleep(700)
    const primed = await countEntities(op, `type=minecraft:tnt,${box(x + 2, y, zz, 6)}`)
    await q(op, `/minecraft:kill @e[type=minecraft:tnt,${box(x + 2, y, zz, 8)}]`)
    check('parity: TNT pushed onto a powered spot is lit where it arrives (TNT cannons keep working)', primed === 1, `primed ${primed}`)
    await clearSite(op, x, y, zz)
  }

  // A carpet and a rail riding a slime push (flying machines, carpet on moving floors) move with it and drop nothing.
  {
    const [x, zz] = [78, z]
    await goTo(op, x + 2, y + 2, zz - 5)
    await clearSite(op, x, y, zz)
    await sb(op, x, y, zz, 'minecraft:sticky_piston[facing=east]')
    await sb(op, x + 1, y, zz, 'minecraft:slime_block')
    await sb(op, x + 1, y + 1, zz, 'minecraft:white_carpet')
    await sb(op, x + 1, y, zz + 1, 'minecraft:slime_block')
    await sb(op, x + 1, y + 1, zz + 1, 'minecraft:rail[shape=east_west]')
    await sb(op, x + 1, y - 1, zz + 1, 'minecraft:oak_planks')
    await sleep(300)
    await sb(op, x - 1, y, zz, 'minecraft:redstone_block')
    await sleep(900)
    const out = {
      carpet: await blockIs(op, x + 2, y + 1, zz, 'minecraft:white_carpet'),
      rail: await blockIs(op, x + 2, y + 1, zz + 1, 'minecraft:rail'),
      planks: await blockIs(op, x + 2, y - 1, zz + 1, 'minecraft:oak_planks'),
      carpetItems: await amount(op, 'minecraft:white_carpet', x + 1, y + 1, zz),
      railItems: await amount(op, 'minecraft:rail', x + 1, y + 1, zz + 1)
    }
    check('parity: a slime push carries a carpet, a rail and the block under the slime along, dropping nothing',
      out.carpet && out.rail && out.planks && out.carpetItems === 0 && out.railItems === 0, JSON.stringify(out))
    await sb(op, x - 1, y, zz, 'minecraft:air')
    await sleep(900)
    const back = {
      carpet: await blockIs(op, x + 1, y + 1, zz, 'minecraft:white_carpet'),
      rail: await blockIs(op, x + 1, y + 1, zz + 1, 'minecraft:rail'),
      items: await amount(op, 'minecraft:white_carpet', x + 1, y + 1, zz) + await amount(op, 'minecraft:rail', x + 1, y + 1, zz + 1)
    }
    check('parity: the sticky piston pulls them all back, still dropping nothing', back.carpet && back.rail && back.items === 0, JSON.stringify(back))
    await clearSite(op, x, y, zz)
  }

  // A carpet on a block that a sticky piston pulls away from under it breaks off once, as always (it is not pulled).
  {
    const [x, zz] = [102, z]
    await goTo(op, x + 2, y + 2, zz - 5)
    await clearSite(op, x, y, zz)
    await sb(op, x, y, zz, 'minecraft:sticky_piston[facing=east]')
    await sb(op, x + 1, y, zz, 'minecraft:stone')
    await sleep(300)
    await sb(op, x - 1, y, zz, 'minecraft:redstone_block')
    await sleep(900)
    await sb(op, x + 2, y + 1, zz, 'minecraft:white_carpet')
    await sleep(300)
    const ready = await blockIs(op, x + 2, y, zz, 'minecraft:stone') && await blockIs(op, x + 2, y + 1, zz, 'minecraft:white_carpet')
    await sb(op, x - 1, y, zz, 'minecraft:air')
    await sleep(900)
    const items = await amount(op, 'minecraft:white_carpet', x + 2, y + 1, zz)
    const standing = await blockIs(op, x + 2, y + 1, zz, 'minecraft:white_carpet')
    const pulled = await blockIs(op, x + 1, y, zz, 'minecraft:stone')
    check('parity: a carpet whose block a sticky piston pulls away drops once, as always (it was never pushed)',
      ready && pulled && items === 1 && !standing, `ready ${ready}, stone pulled ${pulled}, carpet items ${items}, carpet standing ${standing}`)
    await clearSite(op, x, y, zz)
  }

  // A piston breaking a pumpkin (pumpkin and melon farms) drops it, as always.
  {
    const [x, zz] = [150, z]
    await goTo(op, x + 2, y + 2, zz - 5)
    await clearSite(op, x, y, zz)
    await sb(op, x, y, zz, 'minecraft:piston[facing=east]')
    await sb(op, x + 1, y, zz, 'minecraft:pumpkin')
    await sleep(300)
    await sb(op, x - 1, y, zz, 'minecraft:redstone_block')
    await sleep(900)
    const pumpkins = await amount(op, 'minecraft:pumpkin', x + 1, y, zz)
    check('parity: a pumpkin a piston breaks drops as always (pumpkin and melon farms)', pumpkins === 1, `pumpkin items ${pumpkins}`)
    await clearSite(op, x, y, zz)
  }

  // Items still use end portals; only falling blocks are kept out.
  {
    const [x, zz] = [126, z]
    const back = { x, y: y + 2, z: zz - 5 }
    await goTo(op, back.x, back.y, back.z)
    await clearSite(op, x, y, zz, 4)
    await endSand(op, back)
    await sb(op, x, y - 1, zz, 'minecraft:stone')
    await sb(op, x, y, zz, 'minecraft:end_portal')
    await q(op, `/minecraft:summon minecraft:item ${x + 0.5} ${y + 0.3} ${zz + 0.5} {Item:{id:"minecraft:sand",count:3}}`)
    await sleep(2500)
    const here = await amount(op, 'minecraft:sand', x, y, zz, 4)
    const there = await endSand(op, back)
    check('parity: a sand item thrown into an end portal arrives in the End', here === 0 && there.items === 3, `here ${here}, End items ${there.items}, End blocks ${there.blocks}`)
    await clearSite(op, x, y, zz, 4)
  }
  say('parity done')
}

// --- stale container screens -------------------------------------------------------------------------------------------

async function openChestAt (kai, x, y, z) {
  const block = kai.blockAt(new Vec3(x, y, z))
  if (!block) throw new Error(`Kai cannot see the chest at ${x} ${y} ${z}`)
  const window = await kai.openContainer(block)
  await sleep(400)
  return window
}

async function containers (op, kai, dir, buildY, check, say, guardOn, baseline = null) {
  const z = 60 + 6 * 24
  const x = 30
  const y = buildY - 4
  const label = guardOn ? 'with DupeSentry' : 'without the container guard'
  setGuard(dir, 'container-desync', 'enabled', guardOn)
  setGuard(dir, 'container-desync', 'cursor-item', guardOn)
  await ask(op, '/dupesentry reload', 1000)
  await goTo(op, x + 0.5, y, z + 2.5)
  await clearSite(op, x, y, z, 5)
  await q(op, `/minecraft:gamemode survival Kai`)
  await q(op, `/minecraft:tp Kai ${x + 0.5} ${y} ${z - 2.5}`)
  await sleep(1500)
  const before = (await status(op)).guards['container-desync']?.count ?? NaN

  // 1. A chest broken while Kai has it open.
  await sb(op, x, y, z, 'minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:5}]}')
  await sleep(500)
  await openChestAt(kai, x, y, z)
  const opened = Boolean(kai.currentWindow)
  // Broken by a player (a command's /setblock is no block break and fires nothing a plugin can see).
  await op.dig(op.blockAt(new Vec3(x, y, z)), true)
  const closedAfterBreak = await waitFor(() => !kai.currentWindow, 3000, 'the chest screen to close').then(() => true).catch(() => false)
  const dropped = await amount(op, 'minecraft:diamond', x, y, z, 3)
  const afterBreak = (await status(op)).guards['container-desync']?.count ?? NaN
  check(`containers ${label}: a chest broken while a player has it open closes his screen and drops its items once`,
    opened && closedAfterBreak && dropped === 5, `opened ${opened}, closed ${closedAfterBreak}, diamonds dropped ${dropped}`)
  check(`containers ${label}: ${guardOn ? 'DupeSentry closed that screen itself (counted)' : 'nothing counted while the guard is off'}`,
    guardOn ? afterBreak === before + 1 : afterBreak === before, `count ${before} -> ${afterBreak}`)
  await q(op, `/minecraft:kill @e[type=minecraft:item,${box(x, y, z, 4)}]`)

  // 2. A teleport more than 8 blocks away while the chest is open.
  await sb(op, x, y, z, 'minecraft:chest[facing=north]')
  await sleep(500)
  await openChestAt(kai, x, y, z)
  const openedAgain = Boolean(kai.currentWindow)
  await q(op, `/minecraft:tp Kai ${x + 20.5} ${y} ${z - 1.5}`)
  const closedAfterTp = await waitFor(() => !kai.currentWindow, 3000, 'the chest screen to close').then(() => true).catch(() => false)
  const afterTp = (await status(op)).guards['container-desync']?.count ?? NaN
  check(`containers ${label}: a teleport 20 blocks away closes the chest screen`, openedAgain && closedAfterTp, `opened ${openedAgain}, closed ${closedAfterTp}`)
  if (!op.folia) {
    check(`containers ${label}: ${guardOn ? 'DupeSentry closed it at the teleport (counted)' : 'nothing counted while the guard is off'}`,
      guardOn ? afterTp === afterBreak + 1 : afterTp === afterBreak, `count ${afterBreak} -> ${afterTp}`)
  } else {
    say(`Folia: /tp fired no PlayerTeleportEvent the guard could see; the game's own reach check closed the screen (count ${afterBreak} -> ${afterTp})`)
  }

  // 3. A chest opened and closed normally keeps working: Kai takes the diamonds out.
  await q(op, `/minecraft:tp Kai ${x + 0.5} ${y} ${z - 2.5}`)
  await sleep(1200)
  await sb(op, x, y, z, 'minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:emerald",count:7}]}')
  await sleep(500)
  await q(op, '/minecraft:clear Kai')
  const window = await openChestAt(kai, x, y, z)
  // Pick the stack up and put it into the first slot of Kai's own inventory (the slot after the chest's 27).
  await kai.clickWindow(0, 0, 0)
  await sleep(300)
  await kai.clickWindow(window.inventoryStart ?? 27, 0, 0)
  await sleep(500)
  kai.closeWindow(window)
  await sleep(500)
  const took = await holds(op, 'Kai', 'minecraft:emerald', 7)
  const afterNormal = (await status(op)).guards['container-desync']?.count ?? NaN
  if (baseline) {
    // Compared with the same steps without the guard: on 1.20.6 the test client's clicks move nothing either way.
    check(`containers ${label}: a chest opened, emptied and closed normally ends as it does without DupeSentry, nothing counted`,
      took === baseline.took && afterNormal === afterTp, `Kai holds the 7 emeralds: ${took} (without the guard: ${baseline.took}); count ${afterTp} -> ${afterNormal}`)
  } else {
    check(`containers ${label}: a chest opened and closed normally counts nothing`, afterNormal === afterTp,
      `Kai holds the 7 emeralds: ${took}; count ${afterTp} -> ${afterNormal}`)
  }
  await clearSite(op, x, y, z, 5)
  return { before, afterBreak, afterTp, afterNormal, took }
}

/** An item held on the cursor when a player quits: into the inventory before the save (guard on), or wherever the server puts it. */
async function cursorAtQuit (op, port, buildY, check, say, guardOn) {
  const x = 40
  const y = buildY - 4
  const z = 60 + 6 * 24
  await goTo(op, x, y + 3, z - 4)
  let kai = await connectWhenUp(port, 'Kai')
  await q(op, `/minecraft:tp Kai ${x + 0.5} ${y} ${z + 0.5}`)
  await q(op, '/minecraft:clear Kai')
  await q(op, `/minecraft:kill @e[type=minecraft:item,${box(x, y, z, 6)}]`)
  await q(op, '/minecraft:give Kai minecraft:gold_ingot 9')
  await sleep(1200)
  // /give also drops a fake copy that nobody can pick up and that vanishes; none may be counted.
  await q(op, `/minecraft:kill @e[type=minecraft:item,${box(x, y, z, 8)}]`)
  const slot = kai.inventory.slots.findIndex((item) => item?.name === 'gold_ingot')
  if (slot < 0) throw new Error('Kai never got the gold')
  await kai.clickWindow(slot, 0, 0)
  await sleep(600)
  // The saved inventory list does not have the cursor: gold that left every slot after the click is on the cursor.
  const onCursor = !(await holds(op, 'Kai', 'minecraft:gold_ingot'))
  kai.quit()
  await sleep(2000)
  const onGround = await amount(op, 'minecraft:gold_ingot', x, y, z, 8)
  kai = await connectWhenUp(port, 'Kai')
  await sleep(1200)
  const held = await holds(op, 'Kai', 'minecraft:gold_ingot', 9) ? 9 : (await holds(op, 'Kai', 'minecraft:gold_ingot') ? -1 : 0)
  const label = guardOn ? 'with DupeSentry' : 'without cursor-item'
  if (guardOn) {
    check(`cursor ${label}: gold held on the cursor at quit is in the inventory after rejoining, and not on the ground`,
      onCursor && held === 9 && onGround === 0, `on cursor ${onCursor}, held ${held}, on the ground ${onGround}`)
  } else {
    // What the server does by itself is recorded; a dupe (more than 9) would fail.
    check(`cursor ${label}: the server itself never makes more of an item held on the cursor at quit`,
      onCursor && held + onGround <= 9, `on cursor ${onCursor}, held after rejoining ${held}, on the ground ${onGround}`)
  }
  await q(op, `/minecraft:kill @e[type=minecraft:item,${box(x, y, z, 8)}]`)
  say(`cursor ${label}: on cursor ${onCursor}, held after rejoining ${held}, on the ground ${onGround}`)
  return kai
}

// --- the scenario ------------------------------------------------------------------------------------------------------

function paperBased (target) {
  return !target.spigot
}

async function connectAll (port) {
  const op = await connectWhenUp(port, 'NyrOp')
  const kai = await connectWhenUp(port, 'Kai')
  return { op, kai }
}

async function setUp (op, target) {
  op.folia = Boolean(target.folia)
  await gamerule(op, 'sendCommandFeedback', true)
  await gamerule(op, 'doDaylightCycle', false)
  await gamerule(op, 'doMobSpawning', false)
  await gamerule(op, 'announceAdvancements', false)
  await gamerule(op, 'doWeatherCycle', false)
  await q(op, '/minecraft:gamemode creative NyrOp')
}

/**
 * One server setting (the target's defaults, or Paper with unsupported-settings on): every dupe without DupeSentry's guards
 * and with them. Checks that each dupe does what this server is known to do without DupeSentry, that DupeSentry's own
 * status agrees with that, and that nothing dupes with DupeSentry.
 */
async function settingRound (op, dir, y, target, setting, passBase, expectDupes, check, say) {
  const shown = await status(op)
  await guardsOn(op, dir, false)
  const off = await status(op)
  check(`${setting}: /dupesentry status shows the three dupe guards off after they are switched off in config.yml`,
    ['piston-dupes', 'portal-gravity', 'tripwire-hooks'].every((g) => /off in config\.yml/.test(off.guards[g]?.state ?? '')), JSON.stringify(off.guards))
  const without = await allDupes(op, y, passBase, say)
  await guardsOn(op, dir, true)
  const on = await status(op)
  const withIt = await allDupes(op, y, passBase + 1, say)
  const after = await status(op)

  for (const dupe of DUPES) {
    const w = without[dupe]
    const d = withIt[dupe]
    const expected = expectDupes[dupe]
    const guardState = on.guards[GUARD_OF[dupe]]?.state ?? '?'
    const guardNeeded = /^on/.test(guardState)
    check(`${setting}: the ${NAMES[dupe]} was built as intended both times`, w.ready !== false && d.ready !== false, `without ${JSON.stringify(w)}; with ${JSON.stringify(d)}`)
    if (expected !== undefined) {
      check(`${setting}: without DupeSentry the ${NAMES[dupe]} ${expected ? 'duplicates' : 'is blocked by the server itself'}`,
        duped(w) === expected, JSON.stringify(w))
    }
    // Idle means the server blocks it, so it must not duplicate; a dupe that works here needs the guard on. A guard that is
    // on where this rig does not duplicate (no Paper fix, but the rig does not work on that version) is recorded, not failed.
    check(`${setting}: DupeSentry's status for ${GUARD_OF[dupe]} agrees with the ${NAMES[dupe]} (${duped(w) ? 'it duplicates here: guard on' : guardNeeded ? 'no fix here, but this rig does not duplicate: guard on' : 'the server blocks it: guard idle'})`,
      guardNeeded || !duped(w), `status "${guardState}", without DupeSentry ${JSON.stringify(w)}`)
    check(`${setting}: with DupeSentry the ${NAMES[dupe]} does not duplicate`, !duped(d) && (d.atLeastOne ?? d.total) >= 1, JSON.stringify(d))
  }
  const stoppedBefore = (g) => on.guards[g]?.count ?? NaN
  const stoppedAfter = (g) => after.guards[g]?.count ?? NaN
  for (const guard of ['piston-dupes', 'portal-gravity', 'tripwire-hooks']) {
    const needed = /^on/.test(on.guards[guard]?.state ?? '')
    const worked = DUPES.some((dupe) => GUARD_OF[dupe] === guard && duped(without[dupe]))
    const what = worked ? 'counted what it stopped' : needed ? 'had nothing to stop (no rig of it duplicated here)' : 'stopped nothing, as the server blocks it'
    check(`${setting}: ${guard} ${what}`,
      worked ? stoppedAfter(guard) > stoppedBefore(guard) : stoppedAfter(guard) === stoppedBefore(guard),
      `${stoppedBefore(guard)} -> ${stoppedAfter(guard)}; ${on.guards[guard]?.state}`)
  }
  return { shown, without, with: withIt }
}

export async function run ({ target, dir, check, restart, say }) {
  const port = target.port
  let { op, kai } = await connectAll(port)
  let original = null
  try {
    await setUp(op, target)
    const y = Math.floor(op.entity.position.y) + 4
    say(`building at y ${y}`)

    const start = await status(op)
    check('/dupesentry status lists all four guards', ['piston-dupes', 'portal-gravity', 'tripwire-hooks', 'container-desync'].every((g) => start.guards[g]), start.lines.join(' | '))
    if (!paperBased(target)) {
      check(`${target.name}: every guard is on (Spigot has none of these fixes)`, Object.values(start.guards).every((g) => /^on/.test(g.state)), JSON.stringify(start.guards))
    } else {
      check(`${target.name}: the piston and portal guards are idle because the server blocks those dupes itself`,
        /blocks this dupe itself/.test(start.guards['piston-dupes']?.state) && /blocks this dupe itself/.test(start.guards['portal-gravity']?.state), JSON.stringify(start.guards))
    }

    // Round 1: the server as installed.
    const expectDefault = paperBased(target)
      ? { tnt: false, carpet: false, rail: false, portal: false }
      : { tnt: true, carpet: true, rail: true, portal: true, hook: true }
    await settingRound(op, dir, y, target, `${target.name} as installed`, 0, expectDefault, check, say)

    await parity(op, y, check, say)
    const plainChests = await containers(op, kai, dir, y, check, say, false)
    await containers(op, kai, dir, y, check, say, true, plainChests)
    quitAll([kai])
    await sleep(500)
    setGuard(dir, 'container-desync', 'cursor-item', false)
    await ask(op, '/dupesentry reload', 1000)
    kai = await cursorAtQuit(op, port, y, check, say, false)
    setGuard(dir, 'container-desync', 'cursor-item', true)
    await ask(op, '/dupesentry reload', 1000)
    quitAll([kai])
    await sleep(500)
    kai = await cursorAtQuit(op, port, y, check, say, true)

    // Round 2: Paper with the unsupported-settings for these dupes turned on.
    if (paperBased(target) && existsSync(paperGlobal(dir))) {
      const hasHookSetting = readFileSync(paperGlobal(dir), 'utf8').includes('skip-tripwire-hook-placement-validation:')
      original = unsafePaper(dir)
      quitAll([op, kai])
      await restart({ hard: false })
      ;({ op, kai } = await connectAll(port))
      await setUp(op, target)
      const unsafe = await status(op)
      check(`${target.name} with unsupported-settings on: the piston, portal and tripwire guards are on`,
        ['piston-dupes', 'portal-gravity', 'tripwire-hooks'].every((g) => /^on/.test(unsafe.guards[g]?.state ?? '')), JSON.stringify(unsafe.guards))
      const unsafeExpect = { tnt: true, carpet: true, rail: true }
      if (!target.folia) unsafeExpect.portal = true
      // The hook rigs get no expectation here: on Paper 26.1.2 with the validation skipped they did not duplicate. What they did
      // is recorded; the status check still fails if one duplicates while the guard is idle.
      void hasHookSetting
      await settingRound(op, dir, y, target, `${target.name} with unsupported-settings on`, 2, unsafeExpect, check, say)
    }
  } finally {
    if (original !== null) writeFileSync(paperGlobal(dir), original)
    quitAll([op, kai])
  }
}
