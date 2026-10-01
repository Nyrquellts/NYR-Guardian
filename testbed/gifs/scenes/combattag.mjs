// NYR CombatTag Pro listing scenes on Paper 1.21.11, played by Kai (logs out mid-fight) and Luna (hits him), watched by
// NyrOp as the camera. Every event is what a client received: positions, swings and hits (animation, damage_event), the
// action bar (game_info chat), the dummy's name and description (its entity data), chat lines and the death screen
// (death_combat_event on Kai's own client).
//
//   combattag-logger     no plugin: Kai quits mid-fight and nothing happens
//   combattag-dummy      the plugin: countdown, Kai quits, his mannequin dummy is killed, his kit drops
//   combattag-reconnect  the plugin: Kai rejoins after his dummy was killed, dies, respawns with nothing

import { Vec3, command, connectWhenUp, gamerule, mineflayer, quitAll, sleep, waitFor } from '../../lib/bots.mjs'
import { begin, caption, chatOf, emit, motd, prepareWorld, save, walkTo, watchEntities, watchInventory, watchWorld } from '../recorder.mjs'

const X0 = 40
const Z0 = 40

export const setup = {
  'combattag-logger': { plugins: [] },
  'combattag-dummy': { plugins: ['combattag'] },
  'combattag-reconnect': { plugins: ['combattag'] }
}

/** Swings, hits and deaths as the camera's client received them. */
function watchCombat (camera) {
  camera._client.on('animation', (p) => { if (p.animation === 0) emit('swing', { id: p.entityId }) })
  camera._client.on('damage_event', (p) => emit('hurt', { id: p.entityId }))
}

/** A mannequin's name and the line under it, from the entity data the camera received. */
function watchDummies (camera) {
  const seen = new Set()
  const timer = setInterval(() => {
    for (const e of Object.values(camera.entities)) {
      if (e.name !== 'mannequin' || seen.has(e.id) || e.metadata?.[2] == null) continue
      seen.add(e.id)
      emit('dummy', { id: e.id, name: motd(camera, e.metadata[2]), description: motd(camera, e.metadata[19]) })
    }
  }, 50)
  return () => clearInterval(timer)
}

function watchBar (bot, name) {
  bot.on('message', (msg, position) => {
    if (position === 'game_info') emit('actionbar', { bot: name, text: msg.toMotd() })
  })
}

async function arena (port) {
  const op = await connectWhenUp(port, 'NyrOp')
  const kai = await connectWhenUp(port, 'Kai')
  const luna = await connectWhenUp(port, 'Luna')
  await prepareWorld(op)
  const y = Math.floor(kai.entity.position.y)
  await command(op, `/minecraft:fill ${X0 - 8} ${y} ${Z0 - 8} ${X0 + 10} ${y + 4} ${Z0 + 8} minecraft:air`)
  await command(op, '/minecraft:gamemode spectator NyrOp')
  await command(op, `/minecraft:tp NyrOp ${X0 + 1} ${y + 12} ${Z0 + 12}`)
  // Luna stands west of Kai, so her hits knock him (and his dummy) east, across the recorded box
  const region = { min: { x: X0 - 2, y: y - 1, z: Z0 - 2 }, max: { x: X0 + 7, y: y + 2, z: Z0 + 2 } }
  return { op, kai, luna, y, region }
}

async function stage (op, y) {
  await command(op, `/minecraft:tp Kai ${X0 + 1.5} ${y} ${Z0 + 0.5} 90 0`)
  await command(op, `/minecraft:tp Luna ${X0 - 0.5} ${y} ${Z0 + 0.5} -90 0`)
  await command(op, '/minecraft:clear Kai')
  await command(op, '/minecraft:clear Luna')
  await command(op, '/minecraft:give Kai minecraft:iron_sword 1')
  await command(op, '/minecraft:give Kai minecraft:diamond 32')
  await command(op, '/minecraft:give Kai minecraft:golden_apple 5')
  await command(op, '/minecraft:item replace entity Kai armor.head with minecraft:iron_helmet')
  await command(op, '/minecraft:item replace entity Kai armor.chest with minecraft:iron_chestplate')
  await command(op, '/minecraft:give Luna minecraft:netherite_sword 1')
  await command(op, '/minecraft:effect give Kai minecraft:instant_health 1 10 true')
  await sleep(1500)
}

/** Luna turns to her target and swings, as a player's click does. */
async function strike (luna, entity) {
  await luna.lookAt(entity.position.offset(0, 1.4, 0), true)
  luna.attack(entity)
}

/** The standing dummy as a client sees it; one playing its death animation (health 0) no longer counts. */
const dummyOf = (bot) => Object.values(bot.entities).find((e) => (e.name === 'mannequin' || e.name === 'husk') && !(e.metadata?.[9] <= 0)) ?? null

/**
 * Luna hits the dummy at the pace of her sword until it dies, following it when a hit knocks it out of reach; after the
 * killing blow she steps back, so the drops stay on the ground (they can be picked up half a second after they land).
 */
async function killDummy (luna, gapMs = 800) {
  for (let i = 0; i < 12; i++) {
    const dummy = dummyOf(luna)
    if (!dummy) break
    if (dummy.position.distanceTo(luna.entity.position) > 2.6) await walkTo(luna, dummy.position, 1.8, 3000)
    await strike(luna, dummy)
    const dead = await waitFor(() => !dummyOf(luna), gapMs, 'the dummy to die').then(() => true, () => false)
    if (dead) break
  }
  luna.setControlState('back', true)
  await sleep(450)
  luna.setControlState('back', false)
  return !dummyOf(luna)
}

async function logger ({ port }) {
  const { op, kai, luna, y, region } = await arena(port)
  await stage(op, y)
  begin()
  const blocks = watchWorld(op, region.min, region.max)
  const stop = watchEntities(op, region.min, region.max)
  watchCombat(op)
  const stopKai = watchInventory(kai, 'Kai')
  chatOf(luna, 'Luna')
  caption('Paper 1.21.11, no plugin', 'Luna and Kai are fighting')
  await sleep(900)
  await strike(luna, luna.players.Kai.entity)
  await sleep(900)
  await strike(luna, luna.players.Kai.entity)
  await sleep(700)
  caption('Kai logs out mid-fight', 'One click on Disconnect, and he is gone')
  await sleep(900)
  kai.quit()
  await waitFor(() => luna.lines.some((l) => /Kai left the game/.test(l)), 8000, 'the quit').catch(() => {})
  await sleep(900)
  caption('Nothing happens', 'Kai keeps his whole kit; Luna gets nothing')
  await sleep(3600)
  stop()
  stopKai()
  save('combattag-logger', { region, blocks, ground: y - 1, hotbars: ['Kai'], server: 'Paper 1.21.11, no plugin' })
  quitAll([op, luna])
}

async function dummy ({ port }) {
  const { op, kai, luna, y, region } = await arena(port)
  await stage(op, y)
  begin()
  const blocks = watchWorld(op, region.min, region.max)
  const stop = watchEntities(op, region.min, region.max)
  const stopDummies = watchDummies(op)
  watchCombat(op)
  watchBar(kai, 'Kai')
  watchBar(luna, 'Luna')
  const stopKai = watchInventory(kai, 'Kai')
  chatOf(luna, 'Luna')
  caption('A hit puts both players in combat', 'The countdown is on their action bar')
  await sleep(900)
  await strike(luna, luna.players.Kai.entity)
  await sleep(3200)
  caption('Kai logs out mid-fight', 'A dummy with his name stays where he stood')
  await sleep(700)
  kai.quit()
  await waitFor(() => dummyOf(luna), 8000, 'the dummy').catch(() => {})
  await sleep(1800)
  caption('Luna kills the dummy', 'It stands in for Kai, with his health')
  await sleep(500)
  await killDummy(luna)
  await waitFor(() => luna.lines.some((l) => /dummy was killed/.test(l)), 6000, 'the broadcast').catch(() => {})
  await sleep(1200)
  caption('Kai’s kit drops where it fell', 'The kill is saved to disk before anything drops')
  await sleep(3600)
  stop()
  stopDummies()
  stopKai()
  save('combattag-dummy', { region, blocks, ground: y - 1, hotbars: ['Kai'] })
  quitAll([op, luna])
}

async function reconnect ({ port }) {
  const { op, kai, luna, y, region } = await arena(port)
  // Kai's own client shows the death screen and waits on it, as a player does
  await gamerule(op, 'doImmediateRespawn', false)
  await command(op, `/minecraft:spawnpoint Kai ${X0 + 2} ${y} ${Z0 - 1}`)
  await stage(op, y)
  // before the recording: Luna hits Kai, Kai logs out, Luna kills his dummy and picks up his kit
  await strike(luna, luna.players.Kai.entity)
  await sleep(1200)
  kai.quit()
  await waitFor(() => dummyOf(luna), 8000, 'the dummy')
  await command(op, '/minecraft:effect give Luna minecraft:strength 20 9 true')
  await sleep(600)
  await killDummy(luna)
  await command(op, '/minecraft:effect clear Luna')
  await sleep(1200)
  for (const e of Object.values(op.entities).filter((e) => e.name === 'item')) {
    await command(op, `/minecraft:tp Luna ${e.position.x.toFixed(2)} ${y} ${e.position.z.toFixed(2)}`)
    await sleep(500)
  }
  await command(op, `/minecraft:tp Luna ${X0 + 4.5} ${y} ${Z0 + 0.5} 90 0`)
  // Luna's own combat from the setup would end mid-scene with a chat line that belongs to another story
  await command(op, '/combattag untag Luna')
  await sleep(1500)

  begin()
  const blocks = watchWorld(op, region.min, region.max)
  const stop = watchEntities(op, region.min, region.max)
  const stopLuna = watchInventory(luna, 'Luna')
  chatOf(luna, 'Luna')
  caption('Kai logs back in', 'His dummy was killed while he was away')
  await sleep(1200)
  // Kai's own client, left on its death screen until he presses Respawn (mineflayer would respawn at once)
  const again = mineflayer.createBot({ host: '127.0.0.1', port, username: 'Kai', auth: 'offline', hideErrors: true, respawn: false })
  let died = false
  again._client.on('death_combat_event', (p) => {
    died = true
    emit('death_screen', { bot: 'Kai', text: motd(again, p.message) ?? '', score: again.entity?.metadata?.[18] ?? null })
  })
  chatOf(again, 'Kai')
  await waitFor(() => again.inventory, 15_000, 'Kai to log in')
  const stopKai = watchInventory(again, 'Kai')
  await waitFor(() => died, 15_000, 'Kai to die on joining')
  caption('He dies as he joins', 'Nothing of his kit is left on him to drop')
  await sleep(3600)
  // what the Respawn button sends (mineflayer's respawn() does nothing when it never saw Kai alive: he died on arrival)
  emit('respawn', { bot: 'Kai' })
  again._client.write('client_command', { actionId: 0 })
  await waitFor(() => again.health > 0, 5000, 'Kai to respawn').catch(() => console.log(`no respawn: health ${again.health}`))
  await sleep(1000)
  caption('Respawned with nothing', 'His kit exists once: Luna has it')
  await sleep(3600)
  stop()
  stopKai()
  stopLuna()
  save('combattag-reconnect', { region, blocks, ground: y - 1, hotbars: ['Kai', 'Luna'] })
  quitAll([op, luna, again])
}

export default {
  'combattag-logger': logger,
  'combattag-dummy': dummy,
  'combattag-reconnect': reconnect
}
