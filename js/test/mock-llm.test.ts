/**
 * Integration suite against the shared toolnexus mock model (tools/mock-llm/mock_llm.py).
 * Real HTTP, no key, no cost. Uses env TN_MOCK_LLM_URL when set; otherwise starts the mock
 * as a subprocess on a free port. Skips (not fails) when python3 is unavailable.
 */
import { test, before, after } from "node:test"
import assert from "node:assert/strict"
import { spawn, spawnSync, type ChildProcess } from "node:child_process"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import {
  attach,
  text as textPart,
  createClient,
  createToolkit,
  defineTool,
  createClassifier,
  createMedia,
  loadMediaStyle,
  noul,
  choice,
  score,
  type ClientStyle,
} from "../dist/index.js"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, "../..")
const MOCK = path.join(REPO, "tools/mock-llm/mock_llm.py")
const FIXTURE = path.join(REPO, "examples/media/fixture.png")
const FIXTURE_SHA12 = fs.readFileSync(FIXTURE + ".sha256", "utf8").trim().slice(0, 12)
const DEFAULT_TEXT = "This is a reply from the toolnexus mock model."

const hasPython = !process.env.TN_MOCK_LLM_URL && spawnSync("python3", ["--version"]).status !== 0 ? false : true
const skip = hasPython ? false : "python3 not available: mock model integration suite skipped"

let base = process.env.TN_MOCK_LLM_URL?.replace(/\/$/, "") ?? ""
let proc: ChildProcess | undefined

before(async () => {
  if (skip || base) return
  proc = spawn("python3", [MOCK, "--port", "0", "--quiet"], { stdio: ["ignore", "pipe", "inherit"] })
  base = await new Promise<string>((resolve, reject) => {
    let buf = ""
    const t = setTimeout(() => reject(new Error("mock did not start in 10s")), 10000)
    proc!.stdout!.on("data", (c) => {
      buf += c
      const m = buf.match(/toolnexus mock model at (\S+)/)
      if (m) { clearTimeout(t); resolve(m[1].replace(/\/$/, "")) }
    })
    proc!.on("exit", (code) => { clearTimeout(t); reject(new Error(`mock exited ${code}`)) })
  })
})

after(() => { proc?.kill() })

const add = () => {
  const calls: any[] = []
  const tool = defineTool({
    name: "add",
    description: "Add two integers.",
    inputSchema: { type: "object", properties: { a: { type: "integer" }, b: { type: "integer" } }, required: ["a", "b"] },
    run: (args: any) => { calls.push(args); return String(args.a + args.b) },
  })
  return { tool, calls }
}

const client = (style: ClientStyle, extra: Record<string, unknown> = {}) =>
  createClient({ baseUrl: base, style, model: "mock-model", apiKey: "mock-key", ...extra })

const mockGet = async (p: string) => (await fetch(`${base}${p}`)).json()
const mockPost = async (p: string, body: unknown = {}) =>
  fetch(`${base}${p}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })

for (const style of ["openai", "anthropic"] as const) {
  test(`mock: ${style} run executes the native tool with schema-built args`, { skip }, async () => {
    const { tool, calls } = add()
    const tk = await createToolkit({ builtins: false, extraTools: [tool] })
    const r = await client(style).run("add 1 and 1", { toolkit: tk })
    assert.deepEqual(calls, [{ a: 1, b: 1 }])
    assert.equal(r.text, "Tool result: 2")
  })

  test(`mock: ${style} streaming text deltas concatenate to the default reply`, { skip }, async () => {
    let txt = ""
    let done: any
    for await (const ev of client(style).stream("hello")) {
      if (ev.type === "text") txt += ev.delta
      if (ev.type === "done") done = ev.result
    }
    assert.equal(txt, DEFAULT_TEXT)
    assert.equal(done?.text, DEFAULT_TEXT)
  })

  test(`mock: ${style} image attachment arrives byte-intact`, { skip }, async () => {
    const img = await attach(FIXTURE)
    const r = await client(style).run([textPart("what is this?"), img])
    assert.ok(r.text.includes(`image/png 82 bytes sha256=${FIXTURE_SHA12}`), r.text)
    assert.ok(r.text.startsWith("Received 1 image(s)"), r.text)
  })
}

test("mock: one queued 429 (Retry-After 0) is retried and succeeds", { skip }, async () => {
  await mockPost("/_mock/reset")
  const q = await mockPost("/_mock/faults", { path: "/chat/completions", status: 429, times: 1, retryAfter: "0" })
  assert.ok(q.ok)
  const r = await client("openai", { retryBaseMs: 1 }).run("hi")
  assert.equal(r.text, DEFAULT_TEXT)
  const counts: any = await mockGet("/_mock/requests")
  const n = Object.entries(counts.counts ?? counts).filter(([k]) => k.endsWith("/chat/completions")).reduce((s, [, v]) => s + Number(v), 0)
  assert.equal(n, 2, JSON.stringify(counts))
})

const QS = {
  urgent: noul("Is this urgent?"),
  team: choice("Which team owns this?", { billing: "Billing issues", infra: "Infrastructure", sales: "Sales" }),
  sev: score("How severe is it?", ["none", "low", "medium", "high"]),
}

async function checkClassifier(c: any) {
  const state = "The payment page returns 500 for every customer."
  const d = await c.evaluate(state, QS)
  assert.equal(d.noul("urgent").noul, 0.95)
  const ch = d.choice("team")
  assert.ok(["billing", "infra", "sales"].includes(ch.choice), ch.choice)
  assert.equal(ch.probabilities[ch.choice], 0.7)
  const sum = Object.values(ch.probabilities as Record<string, number>).reduce((s, v) => s + v, 0)
  assert.ok(Math.abs(sum - 1) < 1e-9, `sum ${sum}`)
  const sc = d.score("sev")
  assert.ok(sc.score >= 0 && sc.score <= 3, `score ${sc.score}`)
  const d2 = await c.evaluate(state, QS)
  assert.equal(d2.choice("team").choice, ch.choice)
  assert.equal(d2.score("sev").score, sc.score)
}

test("mock: classifier custom backend (systemone wire)", { skip }, async () => {
  process.env.MOCK_LLM_KEY ??= "mock-key"
  await checkClassifier(createClassifier({ backend: "custom", baseUrl: base, model: "jev-mock", apiKeyEnv: "MOCK_LLM_KEY", supports: { images: false } }))
})

test("mock: classifier cloudflare backend", { skip }, async () => {
  process.env.MOCK_LLM_KEY ??= "mock-key"
  const origin = new URL(base).origin
  await checkClassifier(createClassifier({ backend: "cloudflare", baseUrl: `${origin}/v1/accounts/acc/ai/run`, apiKeyEnv: "MOCK_LLM_KEY", supports: { images: false } }))
})

test("mock: media style drives all six verbs + an async failure", { skip }, async () => {
  process.env.MOCK_LLM_KEY ??= "mock-key"
  const style = await mockGet("/_mock/media-style.json")
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "tn-mock-media-"))
  const f = path.join(tmp, "style.json")
  fs.writeFileSync(f, JSON.stringify(style))
  try {
    const m = createMedia(loadMediaStyle(f))
    const one = (r: any, suffix: string, mime: string) => {
      assert.equal(r.files.length, 1, JSON.stringify(r))
      assert.ok(r.files[0].url.endsWith(suffix), r.files[0].url)
      assert.equal(r.files[0].mimeType, mime)
    }
    one(await m.image({ prompt: "a cat" }), "/files/image.png", "image/png")
    one(await m.video({ prompt: "a cat running" }), "/files/video.mp4", "video/mp4")
    one(await m.talking({ image: "https://x/a.png", audio: "https://x/a.wav" }), "/files/talking.mp4", "video/mp4")
    one(await m.speech({ text: "hello" }), "/files/speech.wav", "audio/wav")
    const tr = await m.transcribe({ audio: "https://x/a.wav" })
    assert.equal(tr.text, "Mock transcript of https://x/a.wav.")
    const cv = await m.cloneVoice({ name: "muthu", samples: ["https://x/a.wav"] })
    const { createHash } = await import("node:crypto")
    assert.equal(cv.text, "voice-" + createHash("sha256").update("muthu").digest("hex").slice(0, 8))
    await assert.rejects(m.image({ prompt: "please [fail]" }), (e: any) => /mock: job asked to fail/.test(e.message))
  } finally { fs.rmSync(tmp, { recursive: true, force: true }) }
})
