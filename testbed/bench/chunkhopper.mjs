#!/usr/bin/env node
// NYR ChunkHopper benchmark on Paper 1.21.11: the same drop stream over (a) a 16x16 floor of vanilla hoppers and (b) one
// Chunk Hopper, plus the server idle and the stream with nothing to catch it for reference. A repeating command block
// summons one cactus item at each of 16 markers spread over chunk 2,2 every tick (320 items a second). Paper's /mspt
// and the number of item entities in the world are sampled every 5 seconds; the JSON report keeps every sample.
//
//   NYR_PORT_OFFSET=40 NYR_RUN_TAG=chb node testbed/bench/chunkhopper.mjs [--seconds 60] [--markers 16]
//
// Runs one server (bound to 127.0.0.1, offline mode, the Minecraft EULA accepted for that local server) and nothing else.

import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { parseArgs } from 'node:util'
import { GUARDIAN, TARGETS, prepare, startServer, stopServer } from '../lib/servers.mjs'
import { Vec3, command, connectWhenUp, gamerule, quitAll, sleep, waitFor } from '../lib/bots.mjs'
import { extraJars } from '../scenarios/chunkhopper.mjs'

const { values: args } = parseArgs({
  options: {
    seconds: { type: 'string', default: '60' },
    warmup: { type: 'string', default: '15' },
    markers: { type: 'string', default: '16' },
    target: { type: 'string', default: 'paper-1.21.11' }
  }
})
const SECONDS = Number(args.seconds)
const WARMUP = Number(args.warmup)
const MARKERS = Number(args.markers)
const target = TARGETS.find((t) => t.name === args.target)
if (!target) throw new Error(`no target ${args.target}`)

const libs = join(GUARDIAN, 'chunkhopper/build/libs')
const jar = readdirSync(libs).filter((f) => f.startsWith('NYR-ChunkHopper') && f.endsWith('.jar')).map((f) => join(libs, f))[0]
if (!jar) throw new Error('build the jar first: ./gradlew :chunkhopper:shadowJar')

const plain = (line) => String(line ?? '').replace(/§./g, '')
const t0 = Date.now()
const log = (text) => console.log(`${((Date.now() - t0) / 1000).toFixed(0).padStart(4)}s ${text}`)

/** Paper's /mspt: average, minimum and maximum tick time over the last 5 seconds, in milliseconds. */
async function mspt (op) {
  const from = op.lines.length
  op.chat('/mspt')
  const triple = /(\d+(?:\.\d+)?)\/(\d+(?:\.\d+)?)\/(\d+(?:\.\d+)?)/
  const line = await waitFor(() => op.lines.slice(from).map(plain).find((l) => triple.test(l)), 8_000, '/mspt')
  const [, avg, min, max] = triple.exec(line).map(Number)
  return { avg, min, max }
}

/** How many item entities exist in the world ("Test passed, count: N" before 1.21.11, "Test passed. Count: N" since). */
async function itemEntities (op) {
  const reply = plain(await command(op, '/minecraft:execute if entity @e[type=minecraft:item]', /Test (passed|failed)/i, 8_000))
  const count = /count: (\d+)/i.exec(reply)
  if (count) return Number(count[1])
  if (/Test failed/i.test(reply)) return 0
  throw new Error(`cannot read the item count from "${reply}"`)
}

/**
 * The desktop may be shared: other test servers can run on it. Counts the Minecraft servers running besides this
 * one and the machine's CPU load (percent), so every setup's numbers carry the conditions they were measured under.
 */
function machine () {
  const script = "$s = @(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match '-jar \\S*(paper|folia|spigot|purpur)\\S*\\.jar' }).Count; " +
    "$l = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average; \"$s $l\""
  try {
    const [servers, load] = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { encoding: 'utf8', timeout: 30_000 }).trim().split(/\s+/).map(Number)
    return { otherServers: Math.max(0, servers - 1), cpuLoadPercent: load }
  } catch (e) {
    return { otherServers: null, cpuLoadPercent: null, error: String(e.message ?? e).slice(0, 200) }
  }
}

/** Every line /chunkhopper status answers with (the Economy line is its last). */
async function pluginStatus (op) {
  const from = op.lines.length
  op.chat('/chunkhopper status')
  await waitFor(() => op.lines.slice(from).map(plain).find((l) => /Economy:/.test(l)), 8_000, '/chunkhopper status')
  return op.lines.slice(from).map(plain).filter((l) => l.trim())
}

async function measure (op, name, seconds, warmup, extra = async () => ({})) {
  log(`${name}: warming up ${warmup} s`)
  await sleep(warmup * 1000)
  const before = machine()
  const samples = []
  const until = Date.now() + seconds * 1000
  while (Date.now() < until) {
    await sleep(5_000)
    const tick = await mspt(op)
    const items = await itemEntities(op)
    samples.push({ at: Math.round((Date.now() - t0) / 1000), msptAvg5s: tick.avg, msptMin5s: tick.min, msptMax5s: tick.max, itemEntities: items })
    log(`${name}: mspt ${tick.avg} (max ${tick.max}), item entities ${items}`)
  }
  const avg = (key) => Math.round(samples.reduce((n, s) => n + s[key], 0) / samples.length * 100) / 100
  return {
    name,
    samples,
    msptAvg: avg('msptAvg5s'),
    msptMaxOfMax: Math.max(...samples.map((s) => s.msptMax5s)),
    itemEntitiesAvg: avg('itemEntities'),
    itemEntitiesMax: Math.max(...samples.map((s) => s.itemEntities)),
    machineAtStart: before,
    machineAtEnd: machine(),
    ...(await extra())
  }
}

const dir = prepare(target, { jars: [jar, ...extraJars(target)], runName: `${target.name}-${process.env.NYR_RUN_TAG ?? 'bench'}`, properties: { 'enable-command-block': true } })
let server = startServer(target, dir)
const report = { at: new Date().toISOString(), server: target.name, jar: jar.split(/[\\/]/).pop(), itemsPerSecond: MARKERS * 20, seconds: SECONDS, warmup: WARMUP, setups: [] }
let op
let kai
try {
  const boot = await server.ready
  log(`${target.name} up in ${(boot / 1000).toFixed(1)} s`)
  op = await connectWhenUp(target.port, 'NyrOp')
  kai = await connectWhenUp(target.port, 'Kai')
  await gamerule(op, 'sendCommandFeedback', true)
  await gamerule(op, 'doMobSpawning', false)
  await gamerule(op, 'doDaylightCycle', false)
  await gamerule(op, 'randomTickSpeed', 0)
  op.chat('/minecraft:gamerule command_block_output false')
  op.chat('/minecraft:gamerule commandBlockOutput false')
  await command(op, '/minecraft:gamemode spectator NyrOp')
  const y = Math.floor(kai.entity.position.y)
  // The stream: markers at fixed random-looking spots over chunk 2,2 (x and z 32..47), three blocks up.
  const spots = []
  let seed = 7
  const next = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648
  for (let i = 0; i < MARKERS; i++) spots.push([32.5 + Math.floor(next() * 16), y + 3, 32.5 + Math.floor(next() * 16)])
  await command(op, `/minecraft:tp NyrOp 40 ${y + 12} 60`)
  await command(op, `/minecraft:tp Kai 40.5 ${y} 50.5`)
  await command(op, `/minecraft:kill @e[type=!minecraft:player]`)
  await sleep(1000)
  for (const [x, yy, z] of spots) await command(op, `/minecraft:summon minecraft:marker ${x} ${yy} ${z} {Tags:["nyrdrop"]}`)
  const block = [36, y - 3, 52]
  const streamOn = async () => {
    // A chat line holds at most 256 characters: the command block's command is kept short.
    const cmd = 'execute as @e[tag=nyrdrop] at @s run summon item ~ ~ ~ {Item:{id:"cactus",count:1},PickupDelay:10}'
    await command(op, `/minecraft:setblock ${block[0]} ${block[1]} ${block[2]} minecraft:repeating_command_block{Command:'${cmd}',auto:1b} replace`)
    await sleep(500)
  }
  const streamOff = async () => {
    await command(op, `/minecraft:setblock ${block[0]} ${block[1]} ${block[2]} minecraft:stone replace`)
    await sleep(1500)
  }
  const clearItems = async () => {
    await command(op, '/minecraft:kill @e[type=minecraft:item]')
    await sleep(1000)
  }

  // (0) idle
  report.setups.push(await measure(op, 'idle (no stream)', Math.min(30, SECONDS), 5))

  // (c) the stream with nothing to catch it, for reference
  await streamOn()
  report.setups.push(await measure(op, 'stream, nothing catching it', SECONDS, WARMUP))
  await streamOff()
  await clearItems()

  // (a) a 16x16 floor of vanilla hoppers
  await command(op, `/minecraft:fill 32 ${y} 32 47 ${y} 47 minecraft:hopper`)
  await sleep(1000)
  await streamOn()
  report.setups.push(await measure(op, 'stream over a 16x16 floor of vanilla hoppers (256)', SECONDS, WARMUP))
  await streamOff()
  await clearItems()
  await command(op, `/minecraft:fill 32 ${y} 32 47 ${y} 47 minecraft:air`)
  await sleep(1000)
  await clearItems()

  // (b) one Chunk Hopper, selling what it collects (auto-sell on, as placed)
  await command(op, '/chunkhopper give Kai 1', /Gave/)
  await sleep(800)
  const hopperItem = await waitFor(() => kai.inventory.items().find((i) => i.name === 'hopper'), 6_000, 'Kai to hold the Chunk Hopper')
  await kai.equip(hopperItem, 'hand')
  await command(op, `/minecraft:tp Kai 40.5 ${y} 42.5`)
  await sleep(1000)
  const at = new Vec3(40, y, 40)
  await kai.lookAt(at.offset(0.5, 0, 0.5), true)
  await kai.placeBlock(kai.blockAt(at.offset(0, -1, 0)), new Vec3(0, 1, 0)).catch(() => {})
  await sleep(1000)
  if (kai.blockAt(at)?.name !== 'hopper') throw new Error('the Chunk Hopper was not placed')
  await command(op, `/minecraft:tp Kai 40.5 ${y} 50.5`)
  const statusBefore = await pluginStatus(op)
  await streamOn()
  report.setups.push(await measure(op, 'stream over one Chunk Hopper (auto-sell on)', SECONDS, WARMUP, async () => {
    return { chunkHopperStatusBefore: statusBefore, chunkHopperStatusAfter: await pluginStatus(op) }
  }))
  await streamOff()
  await sleep(11_000) // one more auto-sell interval, so the last sale is paid
  report.pluginStatus = await pluginStatus(op)
} finally {
  quitAll([op, kai].filter(Boolean))
  await sleep(500)
  if (server) report.stopExit = await stopServer(server)
  mkdirSync(join(GUARDIAN, 'testbed/reports'), { recursive: true })
  const file = join(GUARDIAN, 'testbed/reports', `bench-chunkhopper-${report.at.replace(/[:.]/g, '-')}.json`)
  report.totalSeconds = Math.round((Date.now() - t0) / 1000)
  writeFileSync(file, JSON.stringify(report, null, 2))
  console.log(`\nreport ${file}`)
  for (const setup of report.setups) {
    console.log(`${setup.name.padEnd(56)} mspt avg ${String(setup.msptAvg).padStart(6)} (worst 5 s max ${setup.msptMaxOfMax}), item entities avg ${setup.itemEntitiesAvg}, max ${setup.itemEntitiesMax}`)
  }
}
