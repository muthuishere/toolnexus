/**
 * Media generation (SPEC §7G) + RunResult.files (§8).
 * Hermetic: the conformance cases are replayed against an in-process node:http server; the
 * fixtures are read from ../conformance/media (never copied).
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import http from "node:http"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { createMedia, createToolkit, createClient, loadMediaStyle, MEDIA_VERBS, type Tool, type ToolResult } from "../dist/index.js"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CONF = path.resolve(HERE, "../../conformance/media")
const read = (f: string) => JSON.parse(fs.readFileSync(path.join(CONF, f), "utf8"))
const CASES = read("cases.json").cases as any[]
const SCHEMAS = read("schemas.json")
const VERBS = read("verbs.json")

type Recorded = { method: string; path: string; headers: http.IncomingHttpHeaders; body: string }

/** Fake provider: serves script[] in order, last entry repeats; records every request. */
async function fakeServer(script: any[]) {
  const requests: Recorded[] = []
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      requests.push({ method: req.method!, path: req.url!, headers: req.headers, body })
      const entry = script[Math.min(requests.length - 1, script.length - 1)]
      res.writeHead(entry.status, { "content-type": "application/json" })
      res.end(entry.rawBody !== undefined ? entry.rawBody : JSON.stringify(entry.body))
    })
  })
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r))
  const url = `http://127.0.0.1:${(server.address() as any).port}`
  return { url, requests, reset: () => { requests.length = 0 }, close: () => new Promise((r) => server.close(r)) }
}

const withServer = (style: any, url: string) => JSON.parse(JSON.stringify(style).split("{server}").join(url))

function assertRequests(c: any, got: Recorded[]) {
  if (c.expectRequests) {
    assert.equal(got.length, c.expectRequests.length, `${c.name}: request count`)
    c.expectRequests.forEach((want: any, i: number) => {
      assert.equal(got[i].method, want.method, `${c.name}: method #${i}`)
      assert.equal(got[i].path, want.path, `${c.name}: path #${i}`)
      if (want.body !== undefined) {
        assert.deepEqual(JSON.parse(got[i].body), want.body, `${c.name}: body #${i}`)
        assert.equal(got[i].headers["content-type"], "application/json")
      } else {
        assert.equal(got[i].body, "", `${c.name}: poll has no body`)
      }
    })
  }
  for (const [name, value] of Object.entries(c.expectHeaders ?? {})) {
    for (const r of got) assert.equal(r.headers[name.toLowerCase()], value, `${c.name}: header ${name}`)
  }
}

for (const c of CASES) {
  test(`media conformance: ${c.name}`, async () => {
    const srv = await fakeServer(c.script)
    const saved: Record<string, string | undefined> = {}
    for (const [k, v] of Object.entries(c.env ?? {})) { saved[k] = process.env[k]; process.env[k] = v as string }
    try {
      const progress: string[] = []
      const media = createMedia(withServer(c.style, srv.url), { onProgress: (p) => progress.push(p.status) })

      // function face
      let result: any, error: string | undefined
      try { result = await media.generate(c.verb, c.args) } catch (e) { error = (e as Error).message }
      if (c.error !== undefined) {
        assert.equal(error, c.error, `${c.name}: error`)
      } else {
        assert.equal(error, undefined, `${c.name}: unexpected error`)
        assert.deepEqual(result, c.result, `${c.name}: result`)
      }
      assertRequests(c, srv.requests)
      if (c.expectProgress) assert.deepEqual(progress, c.expectProgress, `${c.name}: progress`)

      // tool face (fresh script position)
      if (c.tool) {
        srv.reset()
        const tool = media.tools().find((t: Tool) => t.name === c.tool.name)!
        assert.ok(tool, `${c.name}: tool ${c.tool.name}`)
        const tr: ToolResult = await tool.execute(c.args)
        const { name: _n, ...want } = c.tool
        assert.deepEqual(tr, want, `${c.name}: tool result`)
        assertRequests(c, srv.requests)
      }
    } finally {
      for (const [k, v] of Object.entries(saved)) { if (v === undefined) delete process.env[k]; else process.env[k] = v }
      await srv.close()
    }
  })
}

test("media: tool schemas match conformance/media/schemas.json (order + bytes)", () => {
  const tools = createMedia(SCHEMAS.style).tools()
  assert.deepEqual(
    tools.map((t: Tool) => ({ name: t.name, description: t.description, source: t.source, inputSchema: t.inputSchema })),
    SCHEMAS.tools,
  )
})

test("media: embedded verb table is verbs.json verbatim", () => {
  assert.deepEqual(MEDIA_VERBS, VERBS.verbs)
})

test("media: createToolkit({ media }) registers the tools; loadMediaStyle reads a file", async () => {
  const tmp = fs.mkdtempSync(path.join(fs.realpathSync(process.env.TMPDIR ?? "/tmp"), "tn-media-"))
  const f = path.join(tmp, "style.json")
  fs.writeFileSync(f, JSON.stringify(SCHEMAS.style))
  const media = createMedia(loadMediaStyle(f))
  const tk = await createToolkit({ builtins: false, media })
  assert.deepEqual(tk.tools().filter((t: Tool) => t.source === "media").map((t: Tool) => t.name), SCHEMAS.tools.map((t: any) => t.name))
})

test("media: typed verbs pass providerOptions last; first style declaring a verb wins", async () => {
  const srv = await fakeServer([{ status: 200, body: { u: "https://x/a.png" } }])
  try {
    const a = { baseUrl: srv.url, verbs: { image: { mode: "sync", kind: "image", start: { path: "/a" }, args: { prompt: "p" }, defaults: { q: 1 }, resultPath: "u" } } }
    const b = { baseUrl: srv.url, verbs: { image: { mode: "sync", kind: "image", start: { path: "/b" }, args: { prompt: "p" }, resultPath: "u" } } }
    const media = createMedia([a as any, b as any])
    const r = await media.image({ prompt: "cat", providerOptions: { q: 2, p: "over" } })
    assert.deepEqual(r, { files: [{ type: "image", mimeType: "image/png", url: "https://x/a.png" }], status: "completed" })
    assert.equal(srv.requests[0].path, "/a")
    assert.deepEqual(JSON.parse(srv.requests[0].body), { q: 2, p: "over" })
  } finally { await srv.close() }
})

test("media: AbortSignal stops the poll loop", async () => {
  const srv = await fakeServer([{ status: 200, body: { id: "j" } }, { status: 200, body: { st: "processing" } }])
  try {
    const media = createMedia({ baseUrl: srv.url, verbs: { video: { mode: "async", kind: "video", start: { path: "/v" }, args: { prompt: "p" }, idPath: "id",
      poll: { path: "/v/{id}", statusPath: "st", done: ["ok"], failed: ["x"], intervalMs: 5, timeoutMs: 60000 }, resultPath: "u" } } } as any)
    const ac = new AbortController()
    setTimeout(() => ac.abort(), 40)
    const t0 = Date.now()
    await assert.rejects(media.video({ prompt: "x", signal: ac.signal }), (e: any) => e?.name === "AbortError")
    assert.ok(Date.now() - t0 < 5000)
    // tool face never throws: cancellation becomes an error result
    const ac2 = new AbortController()
    ac2.abort()
    const tr = await media.tools()[0].execute({ prompt: "x" }, { signal: ac2.signal })
    assert.equal(tr.isError, true)
  } finally { await srv.close() }
})

// ---- RunResult.files / toolCalls[].parts (§8) ----

const PARTS = [
  { type: "text", text: "ignored" },
  { type: "image", mimeType: "image/png", url: "https://x/a.png" },
  { type: "audio", mimeType: "audio/wav", url: "https://x/s.wav" },
]
const partsTool: Tool = {
  name: "make", description: "makes", source: "custom", inputSchema: { type: "object", properties: {} },
  async execute() { return { output: "made", isError: false, parts: PARTS as any } },
}
const plainTool: Tool = {
  name: "plain", description: "plain", source: "custom", inputSchema: { type: "object", properties: {} },
  async execute() { return { output: "ok", isError: false } },
}
const json = (o: unknown) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } })
const sseRes = (s: string) => new Response(s, { status: 200, headers: { "content-type": "text/event-stream" } })
const ev = (o: unknown) => `data: ${JSON.stringify(o)}\n\n`

function scripted(style: "openai" | "anthropic") {
  let n = 0
  return (async (_u: any, init: any) => {
    const stream = JSON.parse(init.body).stream === true
    const first = n++ === 0
    if (style === "openai") {
      if (stream) {
        return sseRes(first
          ? ev({ choices: [{ index: 0, delta: { tool_calls: [
              { index: 0, id: "c1", function: { name: "make", arguments: "{}" } },
              { index: 1, id: "c2", function: { name: "plain", arguments: "{}" } }] } }] }) + "data: [DONE]\n\n"
          : ev({ choices: [{ index: 0, delta: { content: "done" }, finish_reason: "stop" }] }) + "data: [DONE]\n\n")
      }
      return json(first
        ? { choices: [{ message: { role: "assistant", content: null, tool_calls: [
            { id: "c1", type: "function", function: { name: "make", arguments: "{}" } },
            { id: "c2", type: "function", function: { name: "plain", arguments: "{}" } }] } }] }
        : { choices: [{ message: { role: "assistant", content: "done" }, finish_reason: "stop" }] })
    }
    if (stream) {
      return sseRes(first
        ? [ev({ type: "message_start", message: { usage: { input_tokens: 1, output_tokens: 0 } } }),
           ev({ type: "content_block_start", index: 0, content_block: { type: "tool_use", id: "c1", name: "make" } }),
           ev({ type: "content_block_delta", index: 0, delta: { type: "input_json_delta", partial_json: "{}" } }),
           ev({ type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "c2", name: "plain" } }),
           ev({ type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: "{}" } }),
           ev({ type: "message_delta", delta: { stop_reason: "tool_use" }, usage: { output_tokens: 1 } })].join("")
        : [ev({ type: "message_start", message: { usage: { input_tokens: 1, output_tokens: 0 } } }),
           ev({ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }),
           ev({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "done" } }),
           ev({ type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 1 } })].join(""))
    }
    return json(first
      ? { content: [{ type: "tool_use", id: "c1", name: "make", input: {} }, { type: "tool_use", id: "c2", name: "plain", input: {} }], stop_reason: "tool_use", usage: {} }
      : { content: [{ type: "text", text: "done" }], stop_reason: "end_turn", usage: {} })
  }) as unknown as typeof fetch
}

const WANT_FILES = [
  { type: "image", mimeType: "image/png", url: "https://x/a.png", from: "make", callId: "c1" },
  { type: "audio", mimeType: "audio/wav", url: "https://x/s.wav", from: "make", callId: "c1" },
]

for (const style of ["openai", "anthropic"] as const) {
  for (const mode of ["run", "stream"] as const) {
    test(`RunResult.files + toolCalls[].parts: ${style} ${mode}`, async () => {
      const tk = await createToolkit({ builtins: false, extraTools: [partsTool, plainTool] })
      const c = createClient({ baseUrl: "http://never.invalid", style, model: "m", apiKey: "k", fetch: scripted(style), retries: 0 })
      let r: any
      if (mode === "run") r = await c.run("go", { toolkit: tk })
      else for await (const e of c.stream("go", { toolkit: tk })) if (e.type === "done") r = e.result
      assert.equal(r.text, "done")
      assert.deepEqual(r.files, WANT_FILES)
      assert.deepEqual(r.toolCalls[0].parts, PARTS)
      assert.equal("parts" in r.toolCalls[1], false)
    })
  }
}

test("RunResult.files is [] when no tool produced parts", async () => {
  const c = createClient({ baseUrl: "http://never.invalid", style: "openai", model: "m", apiKey: "k", retries: 0,
    fetch: (async () => json({ choices: [{ message: { role: "assistant", content: "hi" }, finish_reason: "stop" }] })) as unknown as typeof fetch })
  const r = await c.run("go")
  assert.deepEqual(r.files, [])
})

test("media: a string error-envelope code renders as-is", async () => {
  const srv = await fakeServer([{ status: 200, body: { code: "E_QUOTA", message: "out of credits" } }])
  try {
    const media = createMedia({ baseUrl: srv.url, errorEnvelope: { codePath: "code", okValues: ["OK"], messagePath: "message" },
      verbs: { image: { mode: "sync", kind: "image", start: { path: "/s" }, args: { prompt: "p" }, resultPath: "u" } } } as any)
    await assert.rejects(media.image({ prompt: "x" }), { message: "API error E_QUOTA: out of credits" })
  } finally { await srv.close() }
})

test("media: several unsupported args ⇒ error names the first in sorted order; null args are absent", async () => {
  const media = createMedia({ baseUrl: "http://unused", verbs: { image: { mode: "sync", kind: "image", start: { path: "/s" }, args: { prompt: "p" }, resultPath: "u" } } } as any)
  await assert.rejects(media.generate("image", { seed: 1, prompt: "x", aspectRatio: "1:1", Width: 2 }), { message: 'image: argument "Width" not supported by this style' })
  await assert.rejects(media.generate("image", { zeta: 1, alpha: 2 }), { message: 'image: argument "alpha" not supported by this style' })
  await assert.rejects(media.generate("image", { prompt: "x", seed: null, zeta: 1 }), { message: 'image: argument "zeta" not supported by this style' })
})
