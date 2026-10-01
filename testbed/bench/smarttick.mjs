#!/usr/bin/env node
// NYR SmartTick benchmark on Paper 1.21.11: N villagers in 1x1 glass cells, each holding the composter in front of it, and
// Paper's own /mspt with them awake (/smarttick wakeall), asleep (/smarttick sleep) and awake again. One server, one op.
// Writes testbed/reports/bench-smarttick-<time>.json.
//
//   NYR_PORT_OFFSET=60 NYR_RUN_TAG=stb node testbed/bench/smarttick.mjs --counts 200,500,1000,2000
//
// The hall is stacked in floors of 20 x 20 cells (40 x 60 blocks) with the op standing on top of its middle, so every
// villager is inside the entity activation range of a player (32 blocks for villagers on a default server): the server ticks
// them in full, which is where Mob#setAware pauses the brain. For the largest count the op then stands 96 blocks away, still
// within simulation distance: the server ticks the villagers as inactive, and with tick-inactive-villagers on (the default)
// it runs their brains there whether they sleep or not. Both are measured and written down.
//
// The machine is a desktop that runs other work at the same time: every number is what /mspt printed during this run, next
// to the machine it ran on and how many other Java processes were running.

import { execFileSync } from 'node:child_process'
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import { join } from 'node:path'
import { parseArgs } from 'node:util'
import { GUARDIAN, TARGETS, prepare, startServer, stopServer } from '../lib/servers.mjs'
import { command, connectWhenUp, gamerule, sleep } from '../lib/bots.mjs'

const { values: args } = parseArgs({
  options: {
    counts: { type: 'string', default: '200,500,1000,2000' },
    heap: { type: 'string', default: '2G' },
    measure: { type: 'string', default: '60' },
    far: { type: 'boolean', default: true }
  }
})
const COUNTS = args.counts.split(',').map(Number).sort((a, b) => a - b)
const MEASURE_SECONDS = Number(args.measure)
const COLS = 20
const ROWS = 20
const PER_FLOOR = COLS * ROWS
const FLOORS = Math.ceil(COUNTS[COUNTS.length - 1] / PER_FLOOR)
const X0 = 1000
const Z0 = 1000
const FAR = 96
const plain = (text) => String(text ?? '').replace(/§./g, '')
const t0 = Date.now()
const log = (text) => console.log(`${((Date.now() - t0) / 1000).toFixed(0).padStart(5)}s ${text}`)

const target = TARGETS.find((t) => t.name === 'paper-1.21.11')
const libs = join(GUARDIAN, 'smarttick/build/libs')
const jar = readdirSync(libs).filter((f) => f.startsWith('NYR-SmartTick') && f.endsWith('.jar')).map((f) => join(libs, f))[0]
if (!jar) throw new Error('build the jar first: ./gradlew :smarttick:shadowJar')

async function ask (bot, text, waitMs = 1200) {
  const from = bot.lines.length
  bot.chat(text)
  await sleep(waitMs)
  return bot.lines.slice(from).map(plain)
}

/** Paper's /mspt: average, min and max milliseconds per tick over the last 5 s, 10 s and 1 min. */
async function mspt (op) {
  const lines = await ask(op, '/mspt', 1500)
  const numbers = lines.join(' ').match(/\d+(?:\.\d+)?\/\d+(?:\.\d+)?\/\d+(?:\.\d+)?/g) ?? []
  const parse = (s) => {
    const [avg, min, max] = s.split('/').map(Number)
    return { avg, min, max }
  }
  return { s5: numbers[0] && parse(numbers[0]), s10: numbers[1] && parse(numbers[1]), m1: numbers[2] && parse(numbers[2]) }
}

async function status (op) {
  const text = (await ask(op, '/smarttick status', 1500)).join('\n')
  const scan = /Villagers at the last scan: (\d+) loaded, (\d+) eligible, (\d+) asleep/.exec(text)
  return { text, loaded: Number(scan?.[1] ?? NaN), eligible: Number(scan?.[2] ?? NaN), asleep: Number(scan?.[3] ?? NaN) }
}

async function waitStatus (op, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    last = await status(op)
    if (predicate(last)) return last
    await sleep(3000)
  }
  return last
}

/** Samples /mspt every 10 s for the measuring window; the last sample's 1 min figure covers that window. */
async function measure (op, label) {
  const samples = []
  const until = Date.now() + MEASURE_SECONDS * 1000
  while (Date.now() < until) {
    await sleep(Math.min(10_000, Math.max(0, until - Date.now())))
    const m = await mspt(op)
    samples.push({ at: Math.round((Date.now() - t0) / 1000), s10: m.s10, m1: m.m1 })
  }
  const last = samples[samples.length - 1]
  const tens = samples.map((s) => s.s10?.avg).filter((v) => Number.isFinite(v)).sort((a, b) => a - b)
  const result = { label, oneMinuteAvg: last?.m1?.avg, oneMinuteMax: last?.m1?.max, tenSecondAvgs: tens, tenSecondMedian: tens[Math.floor(tens.length / 2)], samples }
  log(`  ${label}: /mspt 1 min avg ${result.oneMinuteAvg} ms (max ${result.oneMinuteMax}), 10 s averages ${tens.join(', ')}`)
  return result
}

function otherJava () {
  try {
    const out = execFileSync('tasklist', ['/FI', 'IMAGENAME eq java.exe', '/FO', 'CSV', '/NH'], { encoding: 'utf8' })
    return out.split(/\r?\n/).filter((l) => l.includes('java.exe')).length - 1
  } catch {
    return null
  }
}

function summary (awake, asleep, awakeAgain) {
  const awakeAvg = awakeAgain ? (awake.oneMinuteAvg + awakeAgain.oneMinuteAvg) / 2 : awake.oneMinuteAvg
  return {
    awakeMspt: awake.oneMinuteAvg, asleepMspt: asleep.oneMinuteAvg, awakeAgainMspt: awakeAgain?.oneMinuteAvg ?? null,
    savedMsPerTick: Math.round((awakeAvg - asleep.oneMinuteAvg) * 100) / 100,
    savedPercent: Math.round(((awakeAvg - asleep.oneMinuteAvg) / awakeAvg) * 1000) / 10
  }
}

const report = {
  at: new Date().toISOString(),
  what: 'Paper /mspt with N villagers in 1x1 cells with composters, awake (/smarttick wakeall), asleep (/smarttick sleep), awake again',
  server: target.name,
  heap: args.heap,
  measureSeconds: MEASURE_SECONDS,
  hall: { cellsPerFloor: PER_FLOOR, floors: FLOORS, footprintBlocks: `${2 * COLS} x ${3 * ROWS}`, opStandsOn: 'the top of the middle of the hall' },
  jar,
  machine: {
    cpu: os.cpus()[0]?.model, threads: os.cpus().length, memoryGiB: Math.round(os.totalmem() / 2 ** 30), freeMemoryGiBAtStart: Math.round(os.freemem() / 2 ** 30),
    os: `${os.type()} ${os.release()}`, otherJavaProcessesAtStart: otherJava(),
    caveat: 'A desktop running other work (other test servers and builds) during the benchmark; numbers are one run on this machine, not a controlled lab.'
  },
  runs: []
}
const file = join(GUARDIAN, 'testbed/reports', `bench-smarttick-${report.at.replace(/[:.]/g, '-')}.json`)
const save = () => {
  mkdirSync(join(GUARDIAN, 'testbed/reports'), { recursive: true })
  writeFileSync(file, JSON.stringify(report, null, 2))
}

const dir = prepare(target, {
  jars: [jar],
  runName: `${target.name}-${process.env.NYR_RUN_TAG ?? 'stb'}`,
  properties: { 'view-distance': 8, 'simulation-distance': 8, 'max-players': 4 }
})
const server = startServer(target, dir, { heap: args.heap })
let op = null
try {
  log(`server up in ${((await server.ready) / 1000).toFixed(1)} s`)
  try {
    const spigotYml = readFileSync(join(dir, 'spigot.yml'), 'utf8')
    report.serverSettings = {
      villagerActivationRange: Number(/villagers:\s*(\d+)/.exec(spigotYml)?.[1] ?? NaN),
      tickInactiveVillagers: /tick-inactive-villagers:\s*(\w+)/.exec(spigotYml)?.[1] ?? null
    }
  } catch { /* no spigot.yml */ }
  op = await connectWhenUp(target.port, 'NyrOp')
  await gamerule(op, 'sendCommandFeedback', true)
  await gamerule(op, 'doDaylightCycle', false)
  await gamerule(op, 'doMobSpawning', false)
  await gamerule(op, 'randomTickSpeed', 0)
  await command(op, '/minecraft:time set 3000')
  await command(op, '/minecraft:gamemode creative NyrOp')
  const y = Math.floor(op.entity.position.y)
  const top = y + 3 * FLOORS
  const middle = { x: X0 + COLS + 1.5, z: Z0 + Math.floor(3 * ROWS / 2) + 0.5 }
  await command(op, `/minecraft:tp NyrOp ${middle.x} ${top + 12} ${middle.z}`)
  await sleep(1000)
  op.creative.startFlying()
  await sleep(8000)

  // One floor: glass two blocks high with a glass ceiling, one cell carved (air for the villager, its composter in front with
  // glass above), cloned along the row, the row cloned along the floor, the floor cloned upwards. Every villager has glass on
  // three sides, above its head and above its composter.
  await command(op, `/minecraft:fill ${X0 - 1} ${y} ${Z0 - 1} ${X0 + 2 * COLS - 1} ${y + 2} ${Z0 + 3 * ROWS - 1} minecraft:glass`, /filled|Successfully/i, 20_000)
  await command(op, `/minecraft:fill ${X0} ${y} ${Z0} ${X0} ${y + 1} ${Z0} minecraft:air`)
  await command(op, `/minecraft:setblock ${X0} ${y} ${Z0 + 1} minecraft:composter`)
  for (let width = 1; width < COLS;) {
    const copy = Math.min(width, COLS - width)
    await command(op, `/minecraft:clone ${X0} ${y} ${Z0} ${X0 + 2 * copy - 1} ${y + 1} ${Z0 + 2} ${X0 + 2 * width} ${y} ${Z0}`, /cloned|Successfully/i, 20_000)
    width += copy
  }
  for (let rows = 1; rows < ROWS;) {
    const copy = Math.min(rows, ROWS - rows)
    await command(op, `/minecraft:clone ${X0 - 1} ${y} ${Z0} ${X0 + 2 * COLS - 1} ${y + 1} ${Z0 + 3 * copy - 1} ${X0 - 1} ${y} ${Z0 + 3 * rows}`, /cloned|Successfully/i, 20_000)
    rows += copy
  }
  for (let floor = 1; floor < FLOORS; floor++) {
    await command(op, `/minecraft:clone ${X0 - 1} ${y} ${Z0 - 1} ${X0 + 2 * COLS - 1} ${y + 2} ${Z0 + 3 * ROWS - 1} ${X0 - 1} ${y + 3 * floor} ${Z0 - 1}`, /cloned|Successfully/i, 20_000)
  }
  log(`hall of ${FLOORS} floors x ${PER_FLOOR} cells built at ${X0},${y},${Z0}`)
  const stand = async () => {
    await command(op, `/minecraft:tp NyrOp ${middle.x} ${top + 1} ${middle.z}`)
    op.creative.stopFlying()
    await sleep(3000)
  }
  await stand()
  log(`op stands at ${op.entity.position.floored()}`)
  report.idle = await measure(op, 'no villagers')
  save()

  let summoned = 0
  for (const n of COUNTS) {
    log(`--- ${n} villagers`)
    // Held awake while they take their job sites and while "awake" is measured: on auto, a server that falls behind with
    // them awake would put them to sleep in the middle of the awake measurement.
    await command(op, '/smarttick wakeall', /Waking every sleeping villager/)
    for (let i = summoned; i < n; i++) {
      const floor = Math.floor(i / PER_FLOOR)
      const cell = i % PER_FLOOR
      const x = X0 + 2 * (cell % COLS) + 0.5
      const z = Z0 + 3 * Math.floor(cell / COLS) + 0.5
      op.chat(`/minecraft:summon minecraft:villager ${x} ${y + 3 * floor} ${z} {VillagerData:{profession:farmer,level:2},Xp:10}`)
      if (i % 20 === 19) await sleep(250)
    }
    summoned = Math.max(summoned, n)
    const run = { villagers: n }
    report.runs.push(run)
    // The game assigns job sites itself; SmartTick counts a villager eligible once it holds its composter.
    const claimed = await waitStatus(op, (s) => s.loaded >= n && s.eligible >= n * 0.98, 300_000)
    run.claimed = { loaded: claimed?.loaded, eligible: claimed?.eligible }
    log(`  ${claimed?.eligible}/${n} eligible (${claimed?.loaded} loaded)`)
    // Settle: the job-site search right after summoning is not what an established trading hall costs.
    await sleep(30_000)
    run.awake = await measure(op, 'awake')
    await command(op, '/smarttick sleep', /goes to sleep/)
    const asleep = await waitStatus(op, (s) => s.asleep >= (claimed?.eligible ?? n), 120_000)
    run.asleepCount = asleep?.asleep
    await sleep(15_000)
    run.asleep = await measure(op, `asleep (${asleep?.asleep})`)
    await command(op, '/smarttick wakeall', /Waking every sleeping villager/)
    const woke = await waitStatus(op, (s) => s.asleep === 0, 120_000)
    run.awakeAgainLoaded = woke?.loaded
    await sleep(15_000)
    run.awakeAgain = await measure(op, 'awake again')
    run.summary = summary(run.awake, run.asleep, run.awakeAgain)
    log(`  summary ${JSON.stringify(run.summary)}`)
    report.machine.otherJavaProcessesAtEnd = otherJava()
    save()
  }

  if (args.far) {
    // The same hall, with the op 96 blocks away: in simulation distance, out of the activation range.
    const n = COUNTS[COUNTS.length - 1]
    log(`--- ${n} villagers, op ${FAR} blocks away`)
    op.creative.startFlying()
    await command(op, `/minecraft:tp NyrOp ${middle.x + FAR} ${top + 12} ${middle.z}`)
    await sleep(20_000)
    const far = { villagers: n, opDistanceBlocks: FAR }
    report.far = far
    far.awake = await measure(op, 'far, awake')
    await command(op, '/smarttick sleep', /goes to sleep/)
    far.asleepCount = (await waitStatus(op, (s) => s.asleep >= n * 0.98, 120_000))?.asleep
    await sleep(15_000)
    far.asleep = await measure(op, `far, asleep (${far.asleepCount})`)
    await command(op, '/smarttick wakeall', /Waking every sleeping villager/)
    await waitStatus(op, (s) => s.asleep === 0, 120_000)
    await sleep(15_000)
    far.awakeAgain = await measure(op, 'far, awake again')
    far.summary = summary(far.awake, far.asleep, far.awakeAgain)
    log(`  summary ${JSON.stringify(far.summary)}`)
    save()
  }
} catch (error) {
  report.error = String(error?.stack ?? error).slice(0, 2000)
  log(`ERROR ${error?.message ?? error}`)
} finally {
  report.machine.otherJavaProcessesAtEnd = otherJava()
  try { op?.chat('/stop') } catch { /* gone */ }
  const code = await Promise.race([server.exited, sleep(90_000).then(() => 'timeout')])
  if (code === 'timeout') await stopServer(server)
  save()
  log(`report ${file}`)
}
