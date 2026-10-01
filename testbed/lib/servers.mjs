// Live Minecraft servers for the NYR Guardian plugins, started straight from their jars. Every server binds 127.0.0.1 in offline mode and
// runs with -Dcom.mojang.eula.agree=true, which accepts the Minecraft EULA (https://aka.ms/MinecraftEULA) for that local
// server: run these only with that agreement.
//
// Server jars live in NYR_SERVER_RUN/downloads (default testbed/run/downloads; fetch-versions.sh fetches Paper builds from
// PaperMC and checks them). Each run directory is seeded with the Mojang cache, libraries and patched jar of an earlier run
// of the same server where one exists (SERVER_RUN and SEED_RUN below), so a server downloads nothing twice.

import { spawn } from 'node:child_process'
import { copyFileSync, cpSync, createWriteStream, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { homedir } from 'node:os'

const HERE = dirname(fileURLToPath(import.meta.url))
export const TESTBED = join(HERE, '..')
export const RUN = join(TESTBED, 'run')
export const GUARDIAN = join(TESTBED, '..')

export const JAVA21 = process.env.NYR_JAVA21 ?? join(homedir(), '.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin/java.exe')
export const JAVA25 = process.env.NYR_JAVA25 ?? 'C:/Program Files/Java/jdk-25/bin/java.exe'
export const SERVER_RUN = process.env.NYR_SERVER_RUN ?? RUN
/**
 * Run directories of another copy of this testbed, one per server name, to seed from: their Mojang jar, libraries and
 * patched jar. A fresh run directory with none of that makes Paper download the Mojang jar again.
 */
export const SEED_RUN = process.env.NYR_SEED_RUN ?? RUN

const downloads = (name) => join(SERVER_RUN, 'downloads', name)
/** A Paper build run-paper already cached for this Minecraft version, or null. */
const runPaperJar = (version) => {
  const dir = join(homedir(), '.gradle/caches/run-task-jars/paper/jars', version)
  const jar = existsSync(dir) ? readdirSync(dir).find((f) => f.endsWith('.jar')) : null
  return jar ? join(dir, jar) : null
}
/** Jars fetch-versions.sh downloaded from PaperMC: this testbed's own run/downloads first, then the shared server run's. */
const fetched = (name) => [join(TESTBED, 'run', 'downloads', name), downloads(name)].find((path) => existsSync(path)) ?? downloads(name)

/**
 * NYR_PORT_OFFSET moves every port, so two copies of this testbed (two checkouts, two plugins being tested at once) never
 * bind the same one.
 */
const OFFSET = Number(process.env.NYR_PORT_OFFSET ?? 0)

/** bots: false where mineflayer cannot join (26.2): those targets boot, enable and get their logs checked only. */
export const TARGETS = [
  { name: 'paper-1.21.11', port: 25711 + OFFSET, java: JAVA21, jar: runPaperJar('1.21.11') ?? fetched('paper-1.21.11.jar'), seed: 'matrix/paper-1.21.11', bots: true, mc: '1.21.11' },
  { name: 'paper-1.20.6', port: 25712 + OFFSET, java: JAVA21, jar: downloads('paper-1.20.6.jar'), seed: 'matrix/paper-1.20.6', bots: true, mc: '1.20.6' },
  { name: 'paper-26.1.2', port: 25713 + OFFSET, java: JAVA25, jar: downloads('paper-26.1.2.jar'), seed: 'matrix/paper-26.1.2', bots: true, mc: '26.1.2' },
  // minecraft-data 3.116.0, the newest on npm on 2026-09-15, lists 26.2 (protocol 776) but carries no protocol for it
  { name: 'paper-26.2', port: 25714 + OFFSET, java: JAVA25, jar: downloads('paper-26.2.jar'), seed: 'matrix/paper-26.2', bots: false, mc: '26.2' },
  { name: 'purpur-1.21.11', port: 25715 + OFFSET, java: JAVA21, jar: downloads('purpur-1.21.11.jar'), seed: 'matrix/purpur-1.21.11', bots: true, mc: '1.21.11' },
  { name: 'folia-1.21.11', port: 25716 + OFFSET, java: JAVA21, jar: downloads('folia-1.21.11.jar'), seed: 'matrix/folia-1.21.11', bots: true, mc: '1.21.11', folia: true },
  { name: 'spigot-1.21.11', port: 25717 + OFFSET, java: JAVA21, jar: downloads('spigot-1.21.11.jar'), seed: null, bots: true, mc: '1.21.11', spigot: true },
  // the 1.21 versions in between, one per BuiltByBit version box (1.21, 1.21.2, 1.21.4, 1.21.5, 1.21.8)
  { name: 'paper-1.21.1', port: 25718 + OFFSET, java: JAVA21, jar: runPaperJar('1.21.1') ?? fetched('paper-1.21.1.jar'), seed: 'matrix/paper-1.21.1', bots: true, mc: '1.21.1' },
  { name: 'paper-1.21.3', port: 25719 + OFFSET, java: JAVA21, jar: fetched('paper-1.21.3.jar'), seed: null, bots: true, mc: '1.21.3' },
  { name: 'paper-1.21.4', port: 25720 + OFFSET, java: JAVA21, jar: fetched('paper-1.21.4.jar'), seed: null, bots: true, mc: '1.21.4' },
  { name: 'paper-1.21.5', port: 25721 + OFFSET, java: JAVA21, jar: fetched('paper-1.21.5.jar'), seed: null, bots: true, mc: '1.21.5' },
  { name: 'paper-1.21.8', port: 25722 + OFFSET, java: JAVA21, jar: fetched('paper-1.21.8.jar'), seed: null, bots: true, mc: '1.21.8' }
]

export const BOT_NAMES = ['NyrOp', 'Kai', 'Luna', 'Mira']

export function offlineUuid (name) {
  const md5 = createHash('md5').update(`OfflinePlayer:${name}`).digest()
  md5[6] = (md5[6] & 0x0f) | 0x30
  md5[8] = (md5[8] & 0x3f) | 0x80
  const hex = md5.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** NYR_RUN_TAG keeps two runs of one target apart (their own world, plugins and logs): run/<target>-<tag>. */
const RUN_TAG = process.env.NYR_RUN_TAG ? `-${process.env.NYR_RUN_TAG}` : ''

/**
 * A fresh run directory: new worlds, plugins and logs; the server's own cache kept. Only NyrOp is op, so the other bots
 * play as ordinary players and get no permission they were not given. The cache, libraries and patched jar come from a
 * seed run of the same server where one exists (SERVER_RUN, SEED_RUN), so no server downloads anything again.
 */
export function prepare (target, { jars, ops = ['NyrOp'], properties = {}, runName = target.name + RUN_TAG, files = {} }) {
  const dir = join(RUN, runName)
  mkdirSync(dir, { recursive: true })
  for (const stale of ['world', 'world_nether', 'world_the_end', 'plugins', 'logs', 'usercache.json', 'crash-reports']) {
    rmSync(join(dir, stale), { recursive: true, force: true })
  }
  for (const f of readdirSync(dir).filter((f) => /^console-\d+\.log$/.test(f))) rmSync(join(dir, f))
  const seeds = [target.seed ? join(SERVER_RUN, target.seed) : null, join(SEED_RUN, target.name)].filter(Boolean)
  for (const part of ['cache', 'libraries', 'versions', 'bundler']) {
    const from = seeds.map((seed) => join(seed, part)).find((path) => existsSync(path))
    if (from && !existsSync(join(dir, part))) cpSync(from, join(dir, part), { recursive: true })
  }
  const props = {
    'server-ip': '127.0.0.1',
    'server-port': target.port,
    'online-mode': false,
    'enforce-secure-profile': false,
    motd: `NYR ${runName}`,
    'level-type': 'minecraft\\:flat',
    'generate-structures': false,
    'spawn-protection': 0,
    'spawn-monsters': false,
    difficulty: 'peaceful',
    'max-players': 12,
    'view-distance': 4,
    'simulation-distance': 4,
    'enable-rcon': false,
    'enable-query': false,
    ...properties
  }
  writeFileSync(join(dir, 'server.properties'), Object.entries(props).map(([k, v]) => `${k}=${v}`).join('\n') + '\n')
  writeFileSync(join(dir, 'ops.json'), JSON.stringify(ops.map((name) => ({ uuid: offlineUuid(name), name, level: 4, bypassesPlayerLimit: false })), null, 2))
  mkdirSync(join(dir, 'plugins'), { recursive: true })
  for (const jar of jars) copyFileSync(jar, join(dir, 'plugins', jar.split(/[\\/]/).pop()))
  for (const [relative, content] of Object.entries(files)) {
    mkdirSync(dirname(join(dir, relative)), { recursive: true })
    writeFileSync(join(dir, relative), content)
  }
  return dir
}

/** Starts the server with stdin ignored (with an open stdin pipe Paper 1.20.6 hangs at "Closing Thread Pool" on /stop). */
export function startServer (target, dir, { heap = '1200M', bootTimeoutMs = 360_000, extraJvm = [] } = {}) {
  const attempt = readdirSync(dir).filter((f) => /^console-\d+\.log$/.test(f)).length + 1
  const out = createWriteStream(join(dir, `console-${attempt}.log`))
  const proc = spawn(target.java, ['-Xms256M', `-Xmx${heap}`, '-Dcom.mojang.eula.agree=true', ...extraJvm, '-jar', target.jar, '--nogui'],
    { cwd: dir, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
  proc.stdout.pipe(out)
  proc.stderr.pipe(out)
  const started = Date.now()
  let tail = ''
  const listeners = new Set()
  proc.stdout.on('data', (chunk) => {
    tail = (tail + chunk).slice(-16_000)
    for (const listener of listeners) listener(String(chunk))
  })
  const exited = new Promise((resolve) => proc.once('exit', (code) => resolve(code)))
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`no "Done" within ${bootTimeoutMs / 1000} s`)), bootTimeoutMs)
    const check = () => {
      if (/Done \(\d/.test(tail)) {
        clearTimeout(timer)
        listeners.delete(check)
        resolve(Date.now() - started)
      }
    }
    listeners.add(check)
    proc.once('exit', (code) => {
      clearTimeout(timer)
      reject(new Error(`the server exited with ${code} before "Done" (see console-${attempt}.log)`))
    })
  })
  /** Resolves with the first console line matching the pattern after this call. */
  const waitForLine = (pattern, timeoutMs = 30_000) => new Promise((resolve, reject) => {
    let buffer = ''
    const timer = setTimeout(() => {
      listeners.delete(watch)
      reject(new Error(`no console line matching ${pattern} within ${timeoutMs} ms`))
    }, timeoutMs)
    const watch = (chunk) => {
      buffer += chunk
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop()
      for (const line of lines) {
        if (pattern.test(line)) {
          clearTimeout(timer)
          listeners.delete(watch)
          resolve(line)
          return
        }
      }
    }
    listeners.add(watch)
  })
  return { proc, ready, exited, attempt, waitForLine, dir }
}

export async function stopServer (server, timeoutMs = 90_000) {
  if (server.proc.exitCode !== null) return server.proc.exitCode
  server.proc.kill()
  const code = await Promise.race([server.exited, new Promise((resolve) => setTimeout(() => resolve('timeout'), timeoutMs))])
  if (code === 'timeout') {
    hardKill(server)
    return server.exited
  }
  return code
}

/** TerminateProcess on the JVM: no shutdown hooks, no world save. */
export function hardKill (server) {
  try {
    process.kill(server.proc.pid, 'SIGKILL')
  } catch { /* already gone */ }
}

/** Every NYR Guardian plugin WARN/ERROR/exception line from this directory's console logs, plus load failures. */
export const PLUGIN_NAMES = 'CombatTagPro|ChunkHopper|SmartTick|DupeSentry'
export const DISPLAY_NAMES = 'CombatTag Pro|ChunkHopper|SmartTick|DupeSentry'
// String.raw: in a plain template literal \S, \] and \. lose their backslash and the patterns stop matching
const OWN = new RegExp(String.raw`NYR-(${PLUGIN_NAMES})|com\.nyr\.guardian|nyr(${PLUGIN_NAMES.toLowerCase()})\.`, 'i')
const START = new RegExp(String.raw`\] NYR (${DISPLAY_NAMES}) \S+ on `)
const LOAD_FAILURE = new RegExp(String.raw`Could not load 'plugins[\\/]NYR-(${PLUGIN_NAMES})|Error occurred while enabling NYR-(${PLUGIN_NAMES})|UnsupportedClassVersionError|NoSuchMethodError|NoClassDefFoundError|IncompatibleClassChangeError`)

export function scanLogs (dir, { allow = [] } = {}) {
  const problems = []
  const facts = []
  for (const f of readdirSync(dir).filter((f) => /^console-\d+\.log$/.test(f)).sort()) {
    const lines = readFileSync(join(dir, f), 'utf8').split(/\r?\n/)
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]
      const own = OWN.test(line)
      if (own && START.test(line)) facts.push(line.replace(/^\[[^\]]*\]\s*/, ''))
      const bad = /(WARN|ERROR|SEVERE)\]|Exception|Error:|Caused by/.test(line)
      if (own && bad && !allow.some((re) => re.test(line))) problems.push(`${f}:${i + 1}: ${line.slice(0, 500)}`)
      if (LOAD_FAILURE.test(line) && !problems.some((p) => p.endsWith(line.slice(0, 500)))) {
        problems.push(`${f}:${i + 1}: ${line.slice(0, 500)}`)
      }
    }
  }
  return { problems, facts }
}
