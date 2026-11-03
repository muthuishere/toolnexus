/**
 * Media generation (SPEC §7G) against the shared a2e.ai preset — both faces: the typed verb
 * (`media.image`) and the tool face (`media.tools()`, invoked directly). Run after `npm run build`:
 *   A2E_API_KEY=... npm run example:media
 *
 * Without A2E_API_KEY it prints a hint and exits 0, so hermetic runs never touch the network.
 * The key is read by the preset's ${A2E_API_KEY} header and is never printed.
 */
import { createMedia, loadMediaStyle } from "../dist/index.js"

if (!process.env.A2E_API_KEY) {
  console.log("set A2E_API_KEY to run the media example (skipping: no network call made)")
  process.exit(0)
}

const PRESET = new URL("../../examples/media-styles/a2e.json", import.meta.url).pathname
const media = createMedia(loadMediaStyle(PRESET), {
  onProgress: (p) => console.log(`  ..${p.verb} ${p.status}`),
})

// 1. Typed verb.
const img = await media.image({ prompt: "a red fox in fresh snow, watercolor", width: 768, height: 768 })
console.log("image status:", img.status)
for (const f of img.files) console.log("  file:", f.type, f.mimeType, f.url)

// 2. Tool face — the same engine as plain Tools an LLM would call.
const tools = media.tools()
for (const t of tools) console.log("tool:", t.name)
const speech = tools.find((t) => t.name === "generate_speech")!
const res = await speech.execute({ text: "Hello from toolnexus." })
console.log("speech output:", res.output, "isError:", res.isError ?? false)
for (const p of res.parts ?? []) console.log("  part:", JSON.stringify(p))
