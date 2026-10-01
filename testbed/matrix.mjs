#!/usr/bin/env node
// Runs every NYR Guardian plugin's built jar on every testbed server at once, plays each plugin's live scenario against it with
// mineflayer clients, then stops the server cleanly and checks its logs. One JSON report per run.
//
//   node matrix.mjs                                   every target, every plugin
//   node matrix.mjs --only paper-1.21.11 --plugins combattag
//   node matrix.mjs --waves 4                         at most this many servers at once (default 4)
//   node matrix.mjs --fresh                           every plugin installed, each scenario on its own fresh server
//
// Needs the plugin jars (./gradlew assemble) and the server jars described in lib/servers.mjs. Every server binds
// 127.0.0.1 in offline mode and accepts the Minecraft EULA for itself: run this only with that agreement.

import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { parseArgs } from 'node:util'
import { GUARDIAN, TARGETS, hardKill, prepare, scanLogs, startServer, stopServer } from './lib/servers.mjs'
import { connectWhenUp, sleep } from './lib/bots.mjs'

const { values: args } = parseArgs({
  options: {
    only: { type: 'string' },
    plugins: { type: 'string' },
    waves: { type: 'string', default: '4' },
    keep: { type: 'boolean', default: false },
    fresh: { type: 'boolean', default: false }
  }
})
const RUN_TAG = process.env.NYR_RUN_TAG ? `-${process.env.NYR_RUN_TAG}` : ''

const ALL_PLUGINS = ['combattag', 'chunkhopper', 'smarttick', 'dupesentry'].filter((id) => existsSync(join(GUARDIAN, 'testbed/scenarios', `${id}.mjs`)))
const selectedPlugins = args.plugins ? args.plugins.split(',') : ALL_PLUGINS
const scenarios = []
for (const id of selectedPlugins) {
  const module = await import(`./scenarios/${id}.mjs`)
  const libs = join(GUARDIAN, id, 'build/libs')
  const jar = existsSync(libs) ? readdirSync(libs).filter((f) => f.startsWith(module.jarName) && f.endsWith('.jar')).map((f) => join(libs, f))[0] : null
  if (!jar) throw new Error(`no ${module.jarName} jar in ${libs}: build it first (./gradlew :${id}:shadowJar)`)
  scenarios.push({ id, module, jar })
}

const t0 = Date.now()
const stamp = () => `${((Date.now() - t0) / 1000).toFixed(0).padStart(4)}s`
const say = (target, text) => console.log(`${stamp()} ${target.name.padEnd(16)} ${text}`)

/** Stops a server the way an admin does, /stop from an op, and waits for the JVM to exit; kills it if it hangs. */
async function cleanStop (target, server) {
  if (server.proc.exitCode !== null) return server.proc.exitCode
  if (target.bots) {
    try {
      const op = await connectWhenUp(target.port, 'NyrOp', 30_000)
      op.chat('/stop')
      const code = await Promise.race([server.exited, sleep(90_000).then(() => 'timeout')])
      if (code !== 'timeout') return code
    } catch { /* fall through to a console-less stop */ }
  }
  return stopServer(server)
}

/**
 * One server run: a fresh run directory with these jars and files, the given scenarios played on it in order, a clean
 * stop, and its logs checked for every plugin jar it carried. Results go into summary.
 */
async function runSession (target, summary, played, { jars, files, runName }) {
  const dir = prepare(target, runName ? { jars, files, runName } : { jars, files })
  let server = null
  try {
    server = startServer(target, dir)
    const ms = await server.ready
    summary.boots.push(ms)
    say(target, `up in ${(ms / 1000).toFixed(1)} s${runName ? ` (${runName})` : ''}`)
    for (const { id, module } of played) {
      const result = { plugin: id, checks: [], error: null, skipped: null }
      summary.plugins.push(result)
      if (!target.bots || (module.supports && !module.supports(target))) {
        result.skipped = !target.bots ? 'no client library joins this version: boot and log checks only' : 'not supported here'
        continue
      }
      const check = (name, ok, detail = '') => {
        result.checks.push({ name, ok: Boolean(ok), detail: String(detail).slice(0, 400) })
        if (!ok) say(target, `  FAIL ${id}: ${name} (${String(detail).slice(0, 200)})`)
      }
      const started = Date.now()
      /** Stops the server (cleanly with /stop, or hard with a process kill) and starts it again on the same world. */
      const restart = async ({ hard = false } = {}) => {
        if (hard) {
          hardKill(server)
          await server.exited
        } else {
          await cleanStop(target, server)
        }
        server = startServer(target, dir)
        const ms = await server.ready
        summary.boots.push(ms)
        say(target, `  ${id}: restarted after a ${hard ? 'hard kill' : 'clean stop'} in ${(ms / 1000).toFixed(1)} s`)
      }
      try {
        await module.run({ target, dir, server, restart, check, say: (text) => say(target, `  ${id}: ${text}`) })
      } catch (error) {
        result.error = String(error?.stack ?? error).slice(0, 1500)
        say(target, `  ERROR ${id}: ${String(error?.message ?? error).slice(0, 300)}`)
      }
      const failed = result.checks.filter((c) => !c.ok).length
      say(target, `${id}: ${result.checks.length - failed}/${result.checks.length} checks passed in ${((Date.now() - started) / 1000).toFixed(0)} s${result.error ? ', ERROR' : ''}`)
    }
  } catch (error) {
    summary.error = [summary.error, String(error?.message ?? error)].filter(Boolean).join('; ')
    say(target, `ERROR: ${summary.error}`)
  } finally {
    if (server) {
      summary.stopExit = await cleanStop(target, server)
    }
  }
  const logs = scanLogs(dir)
  summary.logProblems.push(...logs.problems)
  summary.facts.push(...logs.facts)
  // Every plugin whose jar was on the server must have said it started, on this server, in this run.
  for (const { module, jar } of scenarios) {
    if (jars.includes(jar) && module.displayName && !logs.facts.some((line) => line.includes(`${module.displayName} `))) {
      summary.logProblems.push(`no start-up line from ${module.displayName}${runName ? ` in ${runName}` : ''}`)
    }
  }
  say(target, `done: ${logs.problems.length} log problem(s)${summary.error ? ', ERROR' : ''}`)
}

async function runTarget (target) {
  const summary = { target: target.name, boots: [], plugins: [], logProblems: [], facts: [], error: null }
  if (!target.jar || !existsSync(target.jar)) {
    summary.error = `no server jar at ${target.jar}`
    say(target, `SKIPPED: ${summary.error}`)
    return summary
  }
  const pluginJars = scenarios.map((s) => s.jar)
  if (args.fresh) {
    // Every plugin installed and running each time, but each scenario on its own fresh world with only its own extra jars
    // (an economy for ChunkHopper) and files, so one scenario's settings (a 5 s autosave) cannot change another's test.
    for (const scenario of scenarios) {
      const jars = [...pluginJars, ...(scenario.module.extraJars?.(target) ?? [])]
      const files = scenario.module.files?.(target) ?? {}
      await runSession(target, summary, [scenario], { jars, files, runName: `${target.name}${RUN_TAG}-${scenario.id}` })
    }
    return summary
  }
  // A scenario may need other plugins on the server (an economy for ChunkHopper) and files written before the first start.
  const extra = [...new Set(scenarios.flatMap((s) => s.module.extraJars?.(target) ?? []))]
  const files = Object.assign({}, ...scenarios.map((s) => s.module.files?.(target) ?? {}))
  await runSession(target, summary, scenarios, { jars: [...pluginJars, ...extra], files })
  return summary
}

const selected = TARGETS.filter((t) => !args.only || args.only.split(',').includes(t.name))
const perWave = Math.max(1, Number(args.waves))
const summaries = []
for (let i = 0; i < selected.length; i += perWave) {
  const group = selected.slice(i, i + perWave)
  console.log(`${stamp()} wave: ${group.map((t) => t.name).join(', ')}`)
  summaries.push(...await Promise.all(group.map(runTarget)))
}

const report = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), jars: scenarios.map((s) => s.jar), summaries }
mkdirSync(join(GUARDIAN, 'testbed/reports'), { recursive: true })
const file = join(GUARDIAN, 'testbed/reports', `matrix-${report.at.replace(/[:.]/g, '-')}.json`)
writeFileSync(file, JSON.stringify(report, null, 2))
console.log(`\nmatrix finished in ${report.seconds} s; report ${file}`)
let bad = false
for (const s of summaries) {
  const parts = s.plugins.map((f) => {
    if (f.skipped) return `${f.plugin} boot-only`
    const failed = f.checks.filter((c) => !c.ok).length
    if (failed || f.error) bad = true
    return `${f.plugin} ${f.checks.length - failed}/${f.checks.length}${f.error ? ' ERROR' : ''}`
  })
  if (s.error || s.logProblems.length) bad = true
  console.log(`${s.target.padEnd(16)} ${s.error ? `ERROR ${s.error}; ` : ''}${parts.join(', ')}; log problems ${s.logProblems.length}; boot ${s.boots.map((b) => (b / 1000).toFixed(1)).join('/')} s`)
}
process.exit(bad ? 1 : 0)
