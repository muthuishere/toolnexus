/**
 * Media generation (SPEC §7G) — the E1 example contract, identical in every port.
 * Run after `npm run build`:  npm run example:media
 *
 * Clients, in order, each added only when its CLI is on PATH or its key is set:
 *   GrokMediaClient (grok CLI), CodexMediaClient (codex CLI), ElevenLabsMediaClient (ELEVENLABS_API_KEY),
 *   then paid fallbacks OpenAIMediaClient (OPENAI_API_KEY), FalMediaClient (FAL_API_KEY), A2EMediaClient (A2E_API_KEY).
 * Steps: (1) image, (2) speech through ElevenLabs (voice from MEDIA_VOICE, else its default),
 * (3) tool face: list media.tools() and call generate_image, (4) Grok video only with MEDIA_VIDEO=1,
 * (5) paid video/talk only with MEDIA_PAID=1. Paid image clients keep their smallest/lowest-quality
 * defaults. Exits non-zero if any attempted step errors; exits 0 with a hint when no client is
 * available. Keys are read from the environment and never printed.
 */
import fs from "node:fs"
import path from "node:path"
import {
  createMedia, GrokMediaClient, CodexMediaClient, ElevenLabsMediaClient, OpenAIMediaClient, FalMediaClient, A2EMediaClient,
  type MediaClient, type MediaFile, type MediaResult, type MediaUsage,
} from "../dist/index.js"

const onPath = (cmd: string) =>
  (process.env.PATH ?? "").split(path.delimiter).some((d) => {
    try { return d !== "" && fs.statSync(path.join(d, cmd)).isFile() } catch { return false }
  })
const hasKey = (k: string) => !!process.env[k]

const grok = onPath("grok") ? new GrokMediaClient() : undefined
const codex = onPath("codex") ? new CodexMediaClient() : undefined
const eleven = hasKey("ELEVENLABS_API_KEY") ? new ElevenLabsMediaClient() : undefined
const openai = hasKey("OPENAI_API_KEY") ? new OpenAIMediaClient() : undefined
const fal = hasKey("FAL_API_KEY") ? new FalMediaClient() : undefined
const a2e = hasKey("A2E_API_KEY") ? new A2EMediaClient() : undefined
const clients = [grok, codex, eleven, openai, fal, a2e].filter((c): c is NonNullable<typeof c> => c !== undefined) as MediaClient[]

if (clients.length === 0) {
  console.log("no media client available: install and log in to the grok or codex CLI, or set one of " +
    "ELEVENLABS_API_KEY, OPENAI_API_KEY, FAL_API_KEY, A2E_API_KEY (no network call made)")
  process.exit(0)
}
console.log("clients:", clients.map((c) => c.name).join(", "))

let failed = false
const sizeOf = (f: MediaFile): string => {
  if (f.path) { try { return String(fs.statSync(f.path).size) } catch { return "?" } }
  if (f.data !== undefined) return String(Buffer.from(f.data, "base64").byteLength)
  return "?"
}
const usageText = (u?: MediaUsage) => (u ? ` usage=${JSON.stringify(u)}` : "")
function report(client: string, verb: string, files: MediaFile[], usage?: MediaUsage) {
  for (const f of files) console.log(`  ${client} ${verb}: ${f.path ?? f.url ?? "(inline)"} ${f.mimeType} ${sizeOf(f)} bytes${usageText(usage)}`)
}
async function step(name: string, run: () => Promise<{ client: string; verb: string; r: MediaResult }>) {
  console.log(`${name}:`)
  try {
    const { client, verb, r } = await run()
    report(client, verb, r.files, r.usage)
    return r
  } catch (e) {
    failed = true
    console.log(`  ${name} failed: ${e instanceof Error ? e.message : String(e)}`)
    return undefined
  }
}
const servedBy = (list: MediaClient[], verb: string) => list.find((c) => c.supports(verb))?.name ?? "?"

const media = createMedia(clients)
const PROMPT = "a red apple on a white table"

// 1. Image — the router tries each image client in order, falling through on `unavailable`.
let image: MediaResult | undefined
if (media.supports("image")) {
  // Progress events name the client serving the call.
  let client = "?"
  image = await step("image", async () => {
    const r = await media.image({ prompt: PROMPT, progress: (p) => { client = p.client } })
    return { client, verb: "image", r }
  })
} else console.log("image: skipped (no image client)")

// 2. Speech through ElevenLabs.
if (eleven) {
  const voice = process.env.MEDIA_VOICE
  await step("speech", async () => ({
    client: eleven.name, verb: "speech",
    r: await createMedia(eleven).speech({ text: "Hello from toolnexus.", ...(voice ? { voice } : {}) }),
  }))
} else console.log("speech: skipped (set ELEVENLABS_API_KEY)")

// 3. Tool face — the same router as plain Tools an LLM would call.
const tools = media.tools()
console.log("tools:", tools.map((t) => t.name).join(", "))
const gen = tools.find((t) => t.name === "generate_image")
if (gen) {
  console.log("tool generate_image:")
  const res = await gen.execute({ prompt: PROMPT })
  if (res.isError) {
    failed = true
    console.log(`  generate_image failed: ${res.output}`)
  } else {
    const md = res.metadata ?? {}
    for (const line of res.output.replace(/^[a-z]+ ready: /, "").split("\n")) {
      const p = res.parts?.[0] as { mimeType?: string } | undefined
      let size = "?"
      try { size = String(fs.statSync(line).size) } catch { /* a URL */ }
      console.log(`  ${md.client} ${md.verb}: ${line} ${p?.mimeType ?? "?"} ${size} bytes${usageText(md.usage as MediaUsage | undefined)}`)
    }
  }
}

// 4. Grok video — opt-in.
if (process.env.MEDIA_VIDEO === "1") {
  if (grok) await step("video (grok)", async () => ({ client: grok.name, verb: "video", r: await createMedia(grok).video({ prompt: "a red apple slowly rotating" }) }))
  else console.log("video (grok): skipped (grok CLI not on PATH)")
}

// 5. Paid video / talk — opt-in.
if (process.env.MEDIA_PAID === "1") {
  const paid = [openai, fal, a2e].filter((c): c is NonNullable<typeof c> => c !== undefined) as MediaClient[]
  const pv = paid.filter((c) => c.supports("video"))
  if (pv.length) await step("video (paid)", async () => ({ client: servedBy(pv, "video"), verb: "video", r: await createMedia(pv).video({ prompt: "a red apple slowly rotating" }) }))
  else console.log("video (paid): skipped (set OPENAI_API_KEY, FAL_API_KEY or A2E_API_KEY)")
  const face = image?.files[0]?.path ?? image?.files[0]?.url
  if (a2e && face) {
    await step("talk (a2e)", async () => ({ client: a2e.name, verb: "talk", r: await createMedia(a2e).talk({ image: face, text: "Hello from toolnexus." }) }))
  } else console.log("talk (a2e): skipped (needs A2E_API_KEY and an image from step 1)")
}

process.exit(failed ? 1 : 0)
