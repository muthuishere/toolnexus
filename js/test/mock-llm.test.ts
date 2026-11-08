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
  MediaStyle,
  noul,
  choice,
  score,
  createInProcessClient,
  loadACP,
  httpTool,
  agent,
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

test("mock: classifier openai backend (decisions wire)", { skip }, async () => {
  process.env.MOCK_LLM_KEY ??= "mock-key"
  await checkClassifier(createClassifier({ backend: "openai", baseUrl: base, apiKeyEnv: "MOCK_LLM_KEY" }))
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
    one(await m.talk({ image: "https://x/a.png", audio: "https://x/a.wav" }), "/files/talking.mp4", "video/mp4")
    one(await m.speech({ text: "hello" }), "/files/speech.wav", "audio/wav")
    const tr = await m.transcribe({ audio: "https://x/a.wav" })
    assert.equal(tr.text, "Mock transcript of https://x/a.wav.")
    const cv = await m.cloneVoice({ name: "muthu", samples: ["https://x/a.wav"] })
    const { createHash } = await import("node:crypto")
    assert.equal(cv.text, "voice-" + createHash("sha256").update("muthu").digest("hex").slice(0, 8))
    await assert.rejects(m.image({ prompt: "please [fail]" }), (e: any) => /mock: job asked to fail/.test(e.message))
  } finally { fs.rmSync(tmp, { recursive: true, force: true }) }
})

// ---------------------------------------------------------------- round 2: mixed toolkit, in-process CLI, ACP
// TN_MOCK_KIT_DIR overrides where mock_mcp.py / fake_cli.py / fake_acp_agent.py are read from (mutation checks).
const KIT = process.env.TN_MOCK_KIT_DIR ?? path.join(REPO, "tools/mock-llm")
const SKILLS = path.join(REPO, "examples/skills")

async function startHttpMcp(legacy: boolean): Promise<{ url: string; kill: () => void }> {
  const p = spawn("python3", [path.join(KIT, "mock_mcp.py"), "--http", "--port", "0", ...(legacy ? ["--legacy"] : [])], { stdio: ["ignore", "pipe", "ignore"] })
  const url = await new Promise<string>((resolve, reject) => {
    let buf = ""
    const t = setTimeout(() => reject(new Error("mock mcp did not start")), 10000)
    p.stdout!.on("data", (c) => {
      buf += c
      const m = buf.match(/toolnexus mock mcp at (\S+)/)
      if (m) { clearTimeout(t); resolve(m[1]) }
    })
  })
  return { url, kill: () => p.kill() }
}

async function mixedToolkit() {
  const { tool, calls } = add()
  const origin = new URL(base).origin
  const httpMcp = await startHttpMcp(false)
  const legacyMcp = await startHttpMcp(true)
  const tk = await createToolkit({
    builtins: false,
    mcpConfig: {
      mcpServers: {
        aaa: { type: "stdio", command: "python3", args: [path.join(KIT, "mock_mcp.py")] },
        web: { type: "http", url: httpMcp.url },
        old: { type: "http", url: legacyMcp.url },
      },
    },
    skillsDir: SKILLS,
    agents: [agent({ card: `${origin}/.well-known/agent-card.json`, pollEvery: 20 })],
    extraTools: [
      tool,
      httpTool({ name: "http_add", description: "Add over HTTP.", method: "POST", url: `${base}/tools/add`, body: "json",
        inputSchema: { type: "object", properties: { a: { type: "integer" }, b: { type: "integer" } }, required: ["a", "b"] } }),
    ],
  } as any)
  return { tk, calls, close: async () => { await (tk as any).close?.(); httpMcp.kill(); legacyMcp.kill() } }
}

const run = async (tk: any, name: string, args: Record<string, unknown>) => {
  const t = tk.tools().find((x: any) => x.name === name)
  assert.ok(t, `tool ${name} missing; have ${tk.tools().map((x: any) => x.name)}`)
  return t.execute(args)
}

test("mock: mixed toolkit — MCP stdio/http/legacy + HTTP + A2A + native + skills", { skip }, async () => {
  const { tk, calls, close } = await mixedToolkit()
  try {
    const names = tk.tools().map((t: any) => t.name)
    for (const n of ["aaa_add", "aaa_echo", "web_add", "web_echo", "old_add", "old_echo", "skill", "mockagent_summarize", "add", "http_add"])
      assert.ok(names.includes(n), `${n} not in ${names}`)
    for (const s of ["aaa", "web", "old"]) assert.equal((await run(tk, `${s}_add`, { a: 2, b: 3 })).output, "5")
    // SPEC §0.4: structuredContent wins, JSON-stringified
    assert.deepEqual(JSON.parse((await run(tk, "web_echo", { text: "hi" })).output), { text: "hi" })
    assert.equal((await run(tk, "mockagent_summarize", { task: "x" })).output, "A2A mock done: x")
    assert.deepEqual(JSON.parse((await run(tk, "http_add", { a: 2, b: 3 })).output), { sum: 5 })
    assert.equal((await run(tk, "add", { a: 2, b: 3 })).output, "5")
    assert.deepEqual(calls, [{ a: 2, b: 3 }])
    const sk = await run(tk, "skill", { name: "hello-world" })
    assert.equal(sk.isError, false)
    assert.ok(sk.output.includes('<skill_content name="hello-world">'), sk.output)
  } finally { await close() }
})

test("mock: openai run over the mixed toolkit calls the first offered tool (an MCP add)", { skip }, async () => {
  const { tk, close } = await mixedToolkit()
  try {
    // js orders MCP tools by server connect-completion, so the first server varies; every
    // server's first tool is add(a,b), so the mock's add(1,1) is meaningful either way.
    const first = tk.tools()[0].name
    assert.match(first, /^(aaa|web|old)_add$/)
    const r = await client("openai").run("add something", { toolkit: tk })
    assert.equal(r.text, "Tool result: 2")
    assert.equal(r.toolCalls[0].name, first)
  } finally { await close() }
})

test("mock: in-process one-shot CLI (fake_cli.py) drives a native add tool", { skip }, async () => {
  const { tool, calls } = add()
  const tk = await createToolkit({ builtins: false, extraTools: [tool] })
  const render = ({ messages }: any) => {
    const called: Record<string, string> = {}
    const lines: string[] = []
    for (const m of messages) {
      if (m.role === "tool") lines.push(`[tool ${called[m.tool_call_id] ?? m.tool_call_id}] ${m.content}`)
      else if (m.tool_calls) for (const c of m.tool_calls) called[c.id] = c.function.name
      else lines.push(`[${m.role}] ${typeof m.content === "string" ? m.content : JSON.stringify(m.content)}`)
    }
    return lines.join("\n")
  }
  const c = createInProcessClient({
    model: "fake-cli",
    generate: (req: any) => {
      const r = spawnSync("python3", [path.join(KIT, "fake_cli.py")], { input: render(req), encoding: "utf8" })
      if (r.status !== 0) throw new Error(`fake_cli exited ${r.status}: ${r.stderr}`)
      const o = JSON.parse(r.stdout)
      return o.tool_calls ? { toolCalls: o.tool_calls.map((x: any) => ({ name: x.name, arguments: x.arguments })) } : { content: o.content }
    },
  })
  const r = await c.run("add 19 and 23", { toolkit: tk })
  assert.deepEqual(calls, [{ a: 19, b: 23 }])
  assert.equal(r.text, "The sum is 42.")
})

async function acpSession(onPermission?: (p: any) => string | undefined) {
  const { tool, calls } = add()
  const served = await createToolkit({ builtins: false, extraTools: [tool] })
  const server = await (served as any).asMcpServer()
  const ag = await loadACP({
    command: "python3", args: [path.join(KIT, "fake_acp_agent.py")],
    mcpServers: [{ type: "http", name: "toolnexus", url: server.url, headers: [{ name: "Authorization", value: `Bearer ${server.token}` }] }],
    ...(onPermission ? { onPermission } : {}),
  })
  return { ag, calls, close: async () => { await (ag as any).close(); await server.close() } }
}

test("mock: ACP agent uses the served toolkit after permission is approved", { skip }, async () => {
  const { ag, calls, close } = await acpSession()
  try {
    const c = createInProcessClient({ model: "fake-acp", generateStream: ag.generateStream })
    let txt = ""
    let done: any
    const acpTools: string[] = []
    for await (const ev of c.stream("add 17 and 25", { toolkit: await createToolkit({ builtins: false }) })) {
      if (ev.type === "text") txt += ev.delta
      if (ev.type === "tool_call" && ev.via === "acp") acpTools.push(ev.name)
      if (ev.type === "done") done = ev.result
    }
    assert.equal(txt, "The sum is 42.")
    assert.equal(done.text, "The sum is 42.")
    assert.deepEqual(calls, [{ a: 17, b: 25 }])
    assert.ok(acpTools.length >= 1)
  } finally { await close() }
})

test("mock: ACP permission rejected via onPermission -> Permission denied.", { skip }, async () => {
  const { ag, calls, close } = await acpSession(() => "no")
  try {
    const r = await createInProcessClient({ model: "fake-acp", generate: ag.generate }).run("add 17 and 25", { toolkit: await createToolkit({ builtins: false }) })
    assert.equal(r.text, "Permission denied.")
    assert.deepEqual(calls, [])
  } finally { await close() }
})

test("mock: ACP [wait] turn cancelled via AbortSignal sends session/cancel", { skip }, async () => {
  const { ag, close } = await acpSession()
  try {
    const ac = new AbortController()
    let txt = ""
    const p = ag.generateStream({ messages: [{ role: "user", content: "please [wait]" }], tools: [] } as any, (ev: any) => {
      if (ev.type === "text") { txt += ev.delta; if (txt.includes("Working...")) ac.abort() }
    }, { signal: ac.signal })
    await assert.rejects(p, (e: any) => e?.name === "AbortError")
    assert.equal(txt, "Working...")
    // session stays warm: a following turn still answers
    const r = await ag.generate({ messages: [{ role: "user", content: "hello" }], tools: [] } as any)
    assert.ok(typeof r.content === "string" && r.content.length > 0, JSON.stringify(r))
  } finally { await close() }
})

test("mock: whole agent offline — mock model calls generate_image on the xai-grok-cli-mock preset", { skip }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-offline-"))
  const media = createMedia(loadMediaStyle(MediaStyle.XaiGrokCliMock, { gather: { dir } }))
  const tk = await createToolkit({ builtins: false, media })
  const r = await client("openai").run("Draw a red apple.", { toolkit: tk })
  assert.equal(r.toolCalls.length, 1)
  assert.equal(r.toolCalls[0].name, "generate_image")
  assert.equal(r.toolCalls[0].isError, false)
  assert.equal(r.files.length, 1)
  assert.equal(r.files[0].mimeType, "image/png")
  assert.equal(fs.readFileSync(r.files[0].path!, "utf8"), "toolnexus mock image")
})
