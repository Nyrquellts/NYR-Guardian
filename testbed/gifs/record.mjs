#!/usr/bin/env node
// Starts a local server with the NYR Guardian jars a scene asks for, records the listing scene on it (capture.mjs), and
// stops it. Each scene gets a fresh world.
//
//   node record.mjs --scenes combattag-dummy,combattag-reconnect
//
// A scene module (gifs/scenes/<product>.mjs) exports its scenes as default { name: async ({ port, dir }) => ... } and may
// export setup = { name: { plugins: ['combattag'], extraJars: [...], files: {...}, ops: [...], target: 'paper-1.21.11' } }.
// plugins: [] records a "before" scene on a server without any NYR plugin, so it shows what the game itself does there.
// The server binds 127.0.0.1 in offline mode and accepts the Minecraft EULA for itself: run this only with that agreement.

import { spawn } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { GUARDIAN, TARGETS, prepare, startServer, stopServer } from '../lib/servers.mjs'
import { connectWhenUp, sleep } from '../lib/bots.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const { values: args } = parseArgs({ options: { scenes: { type: 'string' }, target: { type: 'string', default: 'paper-1.21.11' } } })
if (!args.scenes) {
  console.error('--scenes a,b,c')
  process.exit(2)
}

// Only the product files these scenes belong to (combattag-dummy -> scenes/combattag.mjs).
const own = new Set(args.scenes.split(',').map((scene) => `${scene.split('-')[0]}.mjs`))
const setups = {}
for (const file of readdirSync(join(HERE, 'scenes')).filter((f) => own.has(f))) {
  Object.assign(setups, (await import(`./scenes/${file}`)).setup ?? {})
}

const OFFSET = Number(process.env.NYR_PORT_OFFSET ?? 0)
const jarOf = (id) => {
  const libs = join(GUARDIAN, id, 'build/libs')
  const jar = existsSync(libs) ? readdirSync(libs).find((f) => f.endsWith('.jar')) : null
  if (!jar) throw new Error(`no jar for ${id}: ./gradlew :${id}:shadowJar`)
  return join(libs, jar)
}

for (const scene of args.scenes.split(',')) {
  const setup = setups[scene] ?? {}
  const base = TARGETS.find((t) => t.name === (setup.target ?? args.target))
  if (!base) throw new Error(`no target ${setup.target ?? args.target}`)
  const target = { ...base, port: 25790 + OFFSET }
  const plugins = setup.plugins ?? []
  const jars = [...plugins.map(jarOf), ...(setup.extraJars ?? [])]
  const dir = prepare(target, {
    jars,
    files: setup.files ?? {},
    ops: setup.ops ?? ['NyrOp'],
    runName: `gif-${target.name}${process.env.NYR_RUN_TAG ? `-${process.env.NYR_RUN_TAG}` : ''}`,
    properties: { 'spawn-monsters': false, 'view-distance': 6, 'simulation-distance': 6, motd: 'NYR GIFs', ...(setup.properties ?? {}) }
  })
  const server = startServer(target, dir, setup.heap ? { heap: setup.heap } : {})
  await server.ready
  console.log(`server up for ${scene} (${plugins.length ? plugins.join(', ') : 'no NYR plugin'})`)
  const code = await new Promise((resolve) => {
    const child = spawn(process.execPath, [join(HERE, 'capture.mjs'), '--port', String(target.port), '--scene', scene, '--dir', dir], { stdio: 'inherit', windowsHide: true })
    child.on('exit', resolve)
  })
  console.log(`${scene}: capture exited with ${code}`)
  try {
    const op = await connectWhenUp(target.port, 'NyrOp', 20_000)
    op.chat('/stop')
    await Promise.race([server.exited, sleep(60_000)])
  } catch { /* stopped below */ }
  await stopServer(server)
  if (code !== 0) process.exitCode = 1
}
