#!/usr/bin/env node
// Records one listing scene on a running server: the scene (gifs/scenes/<product>.mjs) drives the bots, recorder.mjs
// saves what their clients received to run/gifs/<scene>.json for the renderers (render_world.py, render_menu.py, ...).
//
//   node capture.mjs --port 25790 --scene combattag-dummy
//
// The server must run the jars the scene needs (see record.mjs). NyrOp is op and watches as the camera.

import { readdirSync } from 'node:fs'
import { parseArgs } from 'node:util'
import { sleep } from '../lib/bots.mjs'

const { values: args } = parseArgs({ options: { port: { type: 'string', default: '25790' }, scene: { type: 'string' }, dir: { type: 'string' } } })

// Only the scene's own product file (combattag-dummy -> scenes/combattag.mjs), so another product's scene file being
// edited at the same time cannot break this recording.
const own = `${String(args.scene).split('-')[0]}.mjs`
const scenes = {}
for (const file of readdirSync(new URL('./scenes/', import.meta.url)).filter((f) => f === own)) {
  Object.assign(scenes, (await import(`./scenes/${file}`)).default)
}
if (!scenes[args.scene]) {
  console.error(`--scene ${Object.keys(scenes).join('|')}`)
  process.exit(2)
}
// dir is the server's run directory, for scenes that edit a plugin's config.yml between takes.
await scenes[args.scene]({ port: Number(args.port), dir: args.dir })
await sleep(1000)
process.exit(0)
