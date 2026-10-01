// NYR DupeSentry listing scenes, all on Spigot 1.21.11 (Paper, Purpur and Folia block these dupes themselves):
//   dupesentry-spigot   no plugin: the TNT duper from testbed/scenarios/dupesentry.mjs lights the TNT the piston moves: 2
//   dupesentry-blocked  NYR DupeSentry: the same rig, the TNT is only moved: 1, and the staff alert the plugin sent
//   dupesentry-vanilla  NYR DupeSentry: a carpet and a rail ride a slime push out and back, nothing drops
// Kai (op, far outside the picture) builds and triggers the rigs and asks the server for the counts; NyrOp is the camera,
// a spectator, and its chat is what the GIF shows (minus the "[Kai: ...]" echoes of Kai's commands that ops receive).

import { Vec3, command, connectWhenUp, gamerule, sleep } from '../../lib/bots.mjs'
import { begin, caption, emit, prepareWorld, save, watchEntities, watchWorld } from '../recorder.mjs'

const RIG = { x: 8, z: 8 }
const q = async (bot, text) => {
  try {
    return String(await command(bot, text, /Test passed|Test failed|Changed the block|Could not|Killed|No entity|Set own|Set Kai|Set NyrOp|game mode|Teleported|Successfully|No blocks/i, 6000))
  } catch (error) {
    return 'TIMEOUT'
  }
}
const sb = (bot, x, y, z, block) => q(bot, `/minecraft:setblock ${x} ${y} ${z} ${block}`)
const box = (x, y, z, d) => `x=${x - d},y=${y - d},z=${z - d},dx=${2 * d},dy=${2 * d},dz=${2 * d}`

async function start (port) {
  const camera = await connectWhenUp(port, 'NyrOp')
  const kai = await connectWhenUp(port, 'Kai')
  await prepareWorld(kai)
  await gamerule(kai, 'sendCommandFeedback', true)
  const y0 = Math.floor(camera.entity.position.y)
  await q(kai, `/minecraft:fill ${RIG.x - 6} ${y0} ${RIG.z - 6} ${RIG.x + 10} ${y0 + 6} ${RIG.z + 8} minecraft:air`)
  await q(kai, `/minecraft:fill ${RIG.x - 6} ${y0 - 1} ${RIG.z - 6} ${RIG.x + 10} ${y0 - 1} ${RIG.z + 8} minecraft:grass_block`)
  await q(kai, '/minecraft:gamemode spectator NyrOp')
  await q(kai, `/minecraft:tp NyrOp ${RIG.x + 2.5} ${y0 + 6} ${RIG.z + 9.5}`)
  await q(kai, `/minecraft:tp Kai ${RIG.x + 40.5} ${y0} ${RIG.z + 0.5}`)
  await sleep(1500)
  return { camera, kai, y0 }
}

/** NyrOp's chat as recorded: every line but the echoes of Kai's commands. */
function chatWithoutEchoes (camera) {
  camera.on('message', (msg, position) => {
    const text = msg.toMotd()
    if (position === 'game_info' || /^\[Kai: /.test(text.replace(/§./g, ''))) return
    emit('chat', { bot: 'NyrOp', text })
  })
}

/** The TNT duper rig of the live scenario, on the ground: obsidian under the slime, so the slime does not grab the ground. */
async function tntDuper (kai, y0) {
  const { x, z } = RIG
  const y = y0
  await sb(kai, x + 1, y - 1, z, 'minecraft:obsidian')
  await sb(kai, x, y, z, 'minecraft:piston[facing=east]')
  await sb(kai, x + 1, y, z, 'minecraft:slime_block')
  await sb(kai, x + 1, y + 1, z, 'minecraft:stone')
  await sb(kai, x + 2, y + 1, z, 'minecraft:tnt')
  await sb(kai, x + 1, y, z + 1, 'minecraft:stone')
  await sb(kai, x + 2, y, z + 1, 'minecraft:pumpkin')
  await sb(kai, x + 2, y + 1, z + 1, 'minecraft:torch')
  await sb(kai, x + 2, y + 2, z, 'minecraft:stone')
  await sleep(200)
  // powers the stone above the TNT without telling the TNT: powered, not lit, until something updates it
  await sb(kai, x + 2, y + 3, z, 'minecraft:lever[face=floor,facing=east,powered=true]')
  await sleep(500)
}

async function countTnt (kai, y0) {
  const { x, z } = RIG
  const reply = await q(kai, `/minecraft:execute if entity @e[type=minecraft:tnt,${box(x + 2, y0, z, 6)}]`)
  const lit = Number(/count: (\d+)/.exec(reply)?.[1] ?? (/Test passed/.test(reply) ? 1 : 0))
  let blocks = 0
  for (let dx = -1; dx <= 5; dx++) {
    if (/Test passed/.test(await q(kai, `/minecraft:execute if block ${x + dx} ${y0 + 1} ${z} minecraft:tnt`))) blocks++
  }
  return { lit, blocks }
}

function region (y0) {
  return { min: { x: RIG.x - 2, y: y0 - 1, z: RIG.z - 2 }, max: { x: RIG.x + 5, y: y0 + 4, z: RIG.z + 3 } }
}

async function dupeScene (port, scene, withPlugin) {
  const { camera, kai, y0 } = await start(port)
  await tntDuper(kai, y0)
  const r = region(y0)
  begin()
  const blocks = watchWorld(camera, new Vec3(r.min.x, r.min.y, r.min.z), new Vec3(r.max.x, r.max.y, r.max.z))
  const stop = watchEntities(camera, new Vec3(r.min.x, r.min.y, r.min.z), new Vec3(r.max.x, r.max.y, r.max.z))
  chatWithoutEchoes(camera)
  const server = withPlugin ? 'Spigot 1.21.11 with NYR DupeSentry' : 'Spigot 1.21.11, no plugin'
  caption(withPlugin ? 'The same TNT duper, with DupeSentry' : 'A TNT duper on Spigot, no plugin',
    withPlugin ? 'Spigot 1.21.11 with NYR DupeSentry: the same rig, one TNT.' : 'Spigot 1.21.11 without any plugin: one TNT, powered but not lit.')
  await sleep(3000)
  const before = await countTnt(kai, y0)
  caption('The piston fires', 'Mid-push the torch breaks off; its block update reaches the TNT.')
  await sb(kai, RIG.x - 1, y0, RIG.z, 'minecraft:redstone_block')
  await sleep(1100)
  const after = await countTnt(kai, y0)
  const total = after.lit + after.blocks
  caption(`${before.lit + before.blocks} TNT in, ${total} out`, withPlugin
    ? 'DupeSentry kept the pushed TNT unlit: the piston just moved it.'
    : 'Lit mid-push, yet the piston still moved the TNT block: now there are two.')
  emit('count', { before, after })
  await sleep(1900)
  stop()
  save(scene, { region: r, ground: y0 - 1, blocks, counts: { before, after } })
  await q(kai, '/minecraft:kill @e[type=minecraft:tnt]')
  console.log(`${scene}: before ${JSON.stringify(before)}, after ${JSON.stringify(after)}`)
}

export default {
  'dupesentry-spigot': ({ port }) => dupeScene(port, 'dupesentry-spigot', false),
  'dupesentry-blocked': ({ port }) => dupeScene(port, 'dupesentry-blocked', true),

  /** A carpet and a rail riding a slime push out and back (moving floors, flying machines): they move, nothing drops. */
  'dupesentry-vanilla': async ({ port }) => {
    const { camera, kai, y0 } = await start(port)
    const { x, z } = RIG
    // obsidian under the slimes where they start and where they are pushed: slime would grab grass and could not pull it back
    for (const dx of [1, 2]) for (const dz of [0, 1]) await sb(kai, x + dx, y0 - 1, z + dz, 'minecraft:obsidian')
    await sb(kai, x, y0, z, 'minecraft:sticky_piston[facing=east]')
    await sb(kai, x + 1, y0, z, 'minecraft:slime_block')
    await sb(kai, x + 1, y0, z + 1, 'minecraft:slime_block')
    await sb(kai, x + 1, y0 + 1, z, 'minecraft:white_carpet')
    await sb(kai, x + 1, y0 + 1, z + 1, 'minecraft:rail[shape=east_west]')
    await sleep(500)
    const r = region(y0)
    const dropped = async () => {
      let n = 0
      for (const id of ['white_carpet', 'rail']) {
        const reply = await q(kai, `/minecraft:execute if entity @e[type=minecraft:item,${box(x + 2, y0, z, 6)},nbt={Item:{id:"minecraft:${id}"}}]`)
        n += Number(/count: (\d+)/.exec(reply)?.[1] ?? (/Test passed/.test(reply) ? 1 : 0))
      }
      return n
    }
    begin()
    const blocks = watchWorld(camera, new Vec3(r.min.x, r.min.y, r.min.z), new Vec3(r.max.x, r.max.y, r.max.z))
    const stop = watchEntities(camera, new Vec3(r.min.x, r.min.y, r.min.z), new Vec3(r.max.x, r.max.y, r.max.z))
    chatWithoutEchoes(camera)
    caption('Slime contraptions keep working', 'Spigot 1.21.11 with NYR DupeSentry: a carpet and a rail on a slime push.')
    await sleep(2800)
    caption('The piston pushes the slime', 'The carpet and the rail ride on top, as on a moving floor.')
    await sb(kai, x - 1, y0, z, 'minecraft:redstone_block')
    await sleep(1000)
    const out = await dropped()
    caption('They ride along', `${out} carpets or rails dropped (server count): the piston just moved them.`)
    await sleep(2400)
    caption('The sticky piston pulls it back', 'Slime, carpet and rail come back together.')
    await sb(kai, x - 1, y0, z, 'minecraft:air')
    await sleep(1000)
    const back = await dropped()
    caption('Nothing dropped, nothing stopped', `${back} carpets or rails dropped (server count), and no staff alert.`)
    emit('drops', { out, back })
    await sleep(2000)
    stop()
    save('dupesentry-vanilla', { region: r, ground: y0 - 1, blocks, counts: { out, back } })
    console.log(`dupesentry-vanilla: dropped after the push ${out}, after the pull ${back}`)
  }
}

export const setup = {
  'dupesentry-spigot': { plugins: [], target: 'spigot-1.21.11', ops: ['NyrOp', 'Kai'] },
  'dupesentry-blocked': { plugins: ['dupesentry'], target: 'spigot-1.21.11', ops: ['NyrOp', 'Kai'] },
  'dupesentry-vanilla': { plugins: ['dupesentry'], target: 'spigot-1.21.11', ops: ['NyrOp', 'Kai'] }
}
