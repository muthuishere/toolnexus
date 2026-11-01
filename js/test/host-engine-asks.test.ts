// add-go-host-asks + add-prompt-cache, ported from golang/{resolve_key,host_engine_asks,
// host_builtin_extras,prompt_cache,tool_tiers}_test.go and golang/agents/{astool_ctx,compaction}_test.go.
// Hermetic: scripted fetches stand in for the LLM; MCP runs the shared examples/ fixture.
import { test } from "node:test"
import assert from "node:assert/strict"
import os from "node:os"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import {
  createClient, createToolkit, defineTool, createBuiltinTools, loadMcp, mcpHints, pending,
  InMemoryConversationStore, BackgroundShells, resolveKey, listModels, applyPromptCache, buildToolPlan,
  defaultCountTokens, toolDef, toolSearch, agents,
} from "../dist/index.js"

const PROGRESS_SERVER = path.resolve(fileURLToPath(import.meta.url), "../../../examples/mcp-progress-server.mjs")
const json = (o: unknown) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } })
const sse = (events: unknown[]) =>
  new Response(events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join("") + "data: [DONE]\n\n", { status: 200, headers: { "content-type": "text/event-stream" } })
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "tn-asks-"))
const tool = (name: string, run: (a: any, ctx?: any) => unknown, extra: Record<string, unknown> = {}) =>
  ({ ...defineTool({ name, description: `${name} tool`, inputSchema: { type: "object", properties: { x: { type: "string" } } }, run }), ...extra })

// ---- SECURITY: API key by host (resolve_key_test.go) ----
test("resolveKey: a key only goes to the host it belongs to", () => {
  const saved = { ...process.env }
  try {
    Object.assign(process.env, { OPENROUTER_API_KEY: "fake-or", OPENAI_API_KEY: "fake-oa", ANTHROPIC_API_KEY: "fake-an", TN_TEST_KEY: "fake-custom" })
    const cases: Array<[Record<string, string>, string]> = [
      [{ baseUrl: "https://api.openai.com/v1" }, "fake-oa"],
      [{ baseUrl: "https://api.anthropic.com" }, "fake-an"],
      [{ baseUrl: "https://openrouter.ai/api/v1" }, "fake-or"],
      [{ baseUrl: "https://API.OpenAI.com/v1" }, "fake-oa"],
      [{ baseUrl: "https://example.com/v1", apiKey: "fake-explicit" }, "fake-explicit"],
      [{ baseUrl: "https://api.openai.com/v1", apiKey: "fake-explicit" }, "fake-explicit"],
      [{ baseUrl: "https://example.com/v1", apiKeyEnv: "TN_TEST_KEY" }, "fake-custom"],
    ]
    for (const [o, want] of cases) assert.equal(resolveKey(o as any), want, o.baseUrl)
    for (const baseUrl of ["https://example.com/v1", "http://api.openai.com/v1", "https://api.openai.com.evil.test/", "https://evil-openrouter.ai/v1", "http://localhost:11434/v1"]) {
      assert.throws(() => resolveKey({ baseUrl }), (e: Error) => !e.message.includes("fake-"), baseUrl)
    }
    delete process.env.OPENAI_API_KEY
    assert.throws(() => resolveKey({ baseUrl: "https://api.openai.com/v1" }), /OPENAI_API_KEY/)
  } finally {
    for (const k of ["OPENROUTER_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "TN_TEST_KEY"]) {
      if (saved[k] === undefined) delete process.env[k]
      else process.env[k] = saved[k]
    }
  }
})

test("resolveKey: the client run refuses an unknown host without a key", async () => {
  const c = createClient({ baseUrl: "http://127.0.0.1:1/v1", style: "openai", model: "m", fetch: (async () => json({})) as any })
  await assert.rejects(c.run("hi"), /No API key/)
})

test("listModels: openai and anthropic (paginated), same key rules", async () => {
  let auth = "", url = ""
  const f = (async (u: string, init: any) => {
    url = u
    if (init.headers["x-api-key"]) {
      auth = "x-api-key"
      return u.includes("after_id=")
        ? json({ data: [{ id: "claude-a", display_name: "A" }], has_more: false })
        : json({ data: [{ id: "claude-b", display_name: "B" }], has_more: true, last_id: "claude-b" })
    }
    auth = init.headers.Authorization
    return json({ data: [{ id: "z/model", name: "Z", context_length: 200000 }, { id: "a/model" }] })
  }) as any
  let ms = await listModels({ baseUrl: "http://srv/api/v1", style: "openai", apiKey: "fake", fetch: f })
  assert.deepEqual(ms, [{ id: "a/model" }, { id: "z/model", name: "Z", contextLength: 200000 }])
  assert.equal(auth, "Bearer fake")
  assert.equal(url, "http://srv/api/v1/models")
  ms = await listModels({ baseUrl: "http://srv", style: "anthropic", apiKey: "fake", fetch: f })
  assert.deepEqual(ms.map((m) => m.id), ["claude-a", "claude-b"])
  assert.equal(ms[1].name, "B")
  assert.equal(auth, "x-api-key")
  await assert.rejects(listModels({ baseUrl: "http://srv", style: "openai", fetch: f }), /No API key/)
})

// ---- waitFor with the turn's signal ----
function balanceKit(state: { authed: boolean }) {
  return createToolkit({
    builtins: false,
    extraTools: [tool("get_balance", () => (state.authed ? "balance: 42" : pending({ kind: "authorization", prompt: "log in", url: "https://x/login" })))],
  })
}
function balanceFetch() {
  let n = 0
  return (async () => {
    n++
    return n === 1
      ? json({ choices: [{ message: { role: "assistant", content: null, tool_calls: [{ id: "b1", type: "function", function: { name: "get_balance", arguments: "{}" } }] } }] })
      : json({ choices: [{ message: { role: "assistant", content: "done" } }] })
  }) as any
}

test("waitFor: receives the turn signal and ends with the turn", async () => {
  const state = { authed: false }
  const ctrl = new AbortController()
  let seen: unknown
  const c = createClient({
    baseUrl: "http://x", style: "openai", model: "m", apiKey: "k", fetch: balanceFetch(),
    waitFor: (_req, ctx) => new Promise((_res, rej) => {
      setTimeout(() => ctrl.abort(new Error("cancelled by host")), 50)
      ctx.signal.addEventListener("abort", () => { seen = ctx.signal.reason; rej(ctx.signal.reason) }, { once: true })
    }),
  })
  const t0 = Date.now()
  await assert.rejects(c.run("balance?", { toolkit: await balanceKit(state), signal: ctrl.signal }))
  assert.ok(seen, "waitFor saw the abort")
  assert.ok(Date.now() - t0 < 2000)
})

test("waitFor: resolves as before", async () => {
  const state = { authed: false }
  const c = createClient({
    baseUrl: "http://x", style: "openai", model: "m", apiKey: "k", fetch: balanceFetch(),
    waitFor: async (req, ctx) => { assert.ok(ctx.signal instanceof AbortSignal); state.authed = true; return { id: req.id, ok: true } },
  })
  const r = await c.run("balance?", { toolkit: await balanceKit(state) })
  assert.equal(r.status, "done")
  assert.equal(r.toolCalls[0].output, "balance: 42")
})

// ---- hints ----
test("builtin hints", () => {
  const want: Record<string, boolean> = { read: true, grep: true, glob: true, webfetch: true, question: true, todowrite: true, bash: false, write: false, edit: false, apply_patch: false }
  for (const t of createBuiltinTools()) {
    assert.ok(t.name in want && t.hints, t.name)
    assert.equal(t.hints!.readOnly, want[t.name], t.name)
    assert.equal(t.hints!.destructive, !want[t.name], t.name)
  }
  const bash = createBuiltinTools().find((t) => t.name === "bash")!
  assert.equal(bash.hints!.openWorld, true)
})

test("MCP annotation hints mapping", () => {
  assert.equal(mcpHints(undefined), undefined)
  assert.equal(mcpHints({}), undefined)
  assert.deepEqual(mcpHints({ readOnlyHint: true }), { readOnly: true, destructive: false, openWorld: true })
  assert.deepEqual(mcpHints({ destructiveHint: false, openWorldHint: false }), { readOnly: false, destructive: false, openWorld: false })
  assert.deepEqual(mcpHints({ readOnlyHint: false }), { readOnly: false, destructive: true, openWorld: true })
})

test("permission gate on beforeTool denies a mutating tool, run finishes", async () => {
  const state = { authed: true }
  const t = tool("get_balance", () => "balance: 42", { hints: { readOnly: false, destructive: true, openWorld: false } })
  const tk = await createToolkit({ builtins: false, extraTools: [t] })
  let seen: any
  const c = createClient({
    baseUrl: "http://x", style: "openai", model: "m", apiKey: "k", fetch: balanceFetch(),
    hooks: { beforeTool: (ev) => { seen = ev.hints; if (!ev.hints?.readOnly) return { result: { output: "denied: plan mode allows read-only tools only", isError: true } } } },
  })
  void state
  const r = await c.run("balance?", { toolkit: tk })
  assert.equal(r.status, "done")
  assert.equal(seen?.destructive, true)
  assert.equal(r.toolCalls.length, 1)
  assert.equal(r.toolCalls[0].isError, true)
  assert.equal(r.toolCalls[0].output, "denied: plan mode allows read-only tools only")
})

// ---- callId ----
test("toolContext.callId on parallel calls, every loop", async () => {
  const seen: Record<string, string | undefined> = {}
  const mk = (name: string) => ({ name, description: "", inputSchema: { type: "object" as const }, source: "custom" as const, async execute(_a: any, ctx?: any) { seen[name] = ctx?.callId; return { output: "ok", isError: false } } })
  const tk = await createToolkit({ builtins: false, extraTools: [mk("fast"), mk("slow")] })
  for (const style of ["openai", "anthropic"] as const) {
    for (const stream of [false, true]) {
      for (const k of Object.keys(seen)) delete seen[k]
      const f = (async (_u: string, init: any) => {
        const s = JSON.parse(init.body).stream === true
        if (style === "openai") {
          return s
            ? sse([{ choices: [{ delta: { tool_calls: [{ index: 0, id: "c1", function: { name: "fast", arguments: "{}" } }, { index: 1, id: "c2", function: { name: "slow", arguments: "{}" } }] } }] }])
            : json({ choices: [{ message: { role: "assistant", content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "fast", arguments: "{}" } }, { id: "c2", type: "function", function: { name: "slow", arguments: "{}" } }] } }] })
        }
        return s
          ? sse([
              { type: "content_block_start", index: 0, content_block: { type: "tool_use", id: "c1", name: "fast" } },
              { type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "c2", name: "slow" } },
              { type: "message_delta", delta: { stop_reason: "tool_use" } },
            ])
          : json({ content: [{ type: "tool_use", id: "c1", name: "fast", input: {} }, { type: "tool_use", id: "c2", name: "slow", input: {} }], stop_reason: "tool_use" })
      }) as any
      const c = createClient({ baseUrl: "http://x", style, model: "m", apiKey: "k", maxTurns: 1, fetch: f })
      if (stream) for await (const _ of c.stream("go", { toolkit: tk })) { /* drain */ }
      else await c.run("go", { toolkit: tk })
      assert.deepEqual(seen, { fast: "c1", slow: "c2" }, `${style} stream=${stream}`)
    }
  }
})

// ---- per-server MCP readiness ----
test("loadMcp onServer: each server reported as it finishes, fast before slow", async () => {
  const tk = await createToolkit({ builtins: false })
  const order: string[] = []
  const at: Record<string, number> = {}
  const results: Record<string, any> = {}
  const t0 = Date.now()
  const src = await loadMcp(
    {
      fast: { command: ["node", PROGRESS_SERVER], timeout: 5000 },
      slow: { command: ["sh", "-c", `sleep 1; exec node ${PROGRESS_SERVER}`], timeout: 5000 },
      bad: { command: ["/nonexistent/toolnexus-no-such-binary"], timeout: 2000 },
      off: { command: ["node", PROGRESS_SERVER], enabled: false } as any,
    },
    { onServer: (r) => { order.push(r.name); at[r.name] = Date.now() - t0; results[r.name] = r; tk.register(...r.tools) } },
  )
  try {
    assert.equal(order.length, 4)
    assert.equal(results.fast.status, "connected")
    assert.ok(results.fast.tools.length > 0)
    assert.equal(results.slow.status, "connected")
    assert.equal(results.bad.status, "failed")
    assert.ok(results.bad.error instanceof Error)
    assert.equal(results.off.status, "disabled")
    assert.ok(at.fast < at.slow && at.slow - at.fast >= 500, `fast=${at.fast} slow=${at.slow}`)
    assert.ok(tk.get("fast_slow"), "fast server's tools registered via onServer")
  } finally {
    await src.close()
  }
})

test("MCP resource tools over the shared fixture", async () => {
  const src = await loadMcp({ progress: { command: ["node", PROGRESS_SERVER], timeout: 5000 } })
  try {
    const [list, read] = src.resourceTools()
    let r = await list.execute({})
    assert.equal(r.isError, false)
    assert.equal(r.output, "progress fixture://hello — hello (text/plain): A fixed text resource.")
    r = await read.execute({ server: "progress", uri: "fixture://hello" })
    assert.deepEqual([r.isError, r.output], [false, "hello resource"])
    assert.equal((await read.execute({ server: "progress", uri: "fixture://nope" })).isError, true)
    assert.equal((await read.execute({ server: "nope", uri: "x" })).isError, true)
    assert.ok(list.hints?.readOnly && read.hints?.readOnly)
  } finally {
    await src.close()
  }
})

// ---- opt-in builtins ----
const pick = (cfg: any, name: string) => {
  const t = createBuiltinTools(cfg).find((x) => x.name === name)
  assert.ok(t, `builtin ${name} present`)
  return t!
}

test("opt-in builtins are absent by default", () => {
  for (const t of createBuiltinTools({})) {
    assert.ok(!["bash_output", "kill_shell", "websearch", "notebook_edit"].includes(t.name), t.name)
    if (t.name === "bash") assert.equal("run_in_background" in (t.inputSchema.properties as object), false)
  }
  assert.equal(createBuiltinTools({}).length, 10)
})

test("background shells: a shell signalled from outside reports exit code -1, as Go", { skip: process.platform === "win32" }, async () => {
  const bg = new BackgroundShells()
  try {
    const cfg = { background: bg, killGraceMs: 100 }
    const bash = pick(cfg, "bash"), out = pick(cfg, "bash_output")
    const r = await bash.execute({ command: "kill -TERM $$; sleep 5", run_in_background: true })
    const id = r.metadata?.backgroundId as string
    const deadline = Date.now() + 5000
    for (;;) {
      const o = await out.execute({ id })
      if (o.metadata?.status === "exited") {
        assert.equal(o.metadata.exitCode, -1)
        assert.ok(o.output.includes("[status: exited with code -1]"), o.output)
        break
      }
      assert.ok(Date.now() < deadline, "never exited")
      await new Promise((res) => setTimeout(res, 50))
    }
  } finally {
    await bg.closeAll()
  }
})

test("background shells: incremental output, group kill, list", { skip: process.platform === "win32" }, async () => {
  const bg = new BackgroundShells()
  try {
    const cfg = { background: bg, killGraceMs: 100 }
    const bash = pick(cfg, "bash"), out = pick(cfg, "bash_output"), kill = pick(cfg, "kill_shell")
    let r = await bash.execute({ command: "echo one; sleep 0.2; echo two", run_in_background: true })
    const id = r.metadata?.backgroundId as string
    assert.ok(!r.isError && id, JSON.stringify(r))
    let all = ""
    const deadline = Date.now() + 5000
    for (;;) {
      const o = await out.execute({ id })
      all += o.output.split("\n[status:")[0]
      if (o.metadata?.status === "exited") { assert.equal(o.metadata.exitCode, 0); break }
      assert.ok(Date.now() < deadline, "never exited")
      await new Promise((res) => setTimeout(res, 50))
    }
    assert.equal(all.split("one").length - 1, 1, all)
    assert.equal(all.split("two").length - 1, 1, all)

    r = await bash.execute({ command: "trap '' TERM; sleep 30 & wait", run_in_background: true })
    const id2 = r.metadata?.backgroundId as string
    await new Promise((res) => setTimeout(res, 100))
    const t0 = Date.now()
    const k = await kill.execute({ id: id2 })
    assert.equal(k.isError, false)
    assert.equal(k.metadata?.killed, true)
    assert.ok(Date.now() - t0 < 2000)
    assert.equal((await out.execute({ id: id2 })).metadata?.status, "killed")
    assert.equal((await kill.execute({ id: id2 })).metadata?.killed, false)
    assert.equal((await out.execute({ id: "nope" })).isError, true)
    assert.equal(bg.list().length, 2)
  } finally {
    await bg.closeAll()
  }
})

test("websearch over a host provider", async () => {
  let q = "", n = 0
  const ws = pick({ webSearch: async (query: string, limit: number) => { q = query; n = limit; return [{ title: "A", url: "https://a.example", snippet: "sa" }, { title: "B", url: "https://b.example" }] } }, "websearch")
  assert.ok(ws.hints?.readOnly && ws.hints.openWorld)
  const r = await ws.execute({ query: "go", limit: 1 })
  assert.deepEqual([q, n, r.output], ["go", 1, "1. A\n   https://a.example\n   sa"])
})

test("notebook_edit replace / insert / delete", async () => {
  const dir = tmp()
  const p = path.join(dir, "n.ipynb")
  fs.writeFileSync(p, `{"cells":[{"cell_type":"code","id":"a","metadata":{},"source":["x = 1\\n"],"outputs":[{"output_type":"stream","text":["1"]}],"execution_count":3},{"cell_type":"markdown","id":"b","metadata":{},"source":["# T"]}],"metadata":{},"nbformat":4,"nbformat_minor":5}`)
  const ne = pick({ notebookEdit: true, baseDir: dir }, "notebook_edit")
  const load = () => JSON.parse(fs.readFileSync(p, "utf8")).cells
  let r = await ne.execute({ path: "n.ipynb", cell_id: "a", new_source: "x = 2\ny = 3" })
  assert.equal(r.isError, false, r.output)
  const c0 = load()[0]
  assert.deepEqual([c0.outputs, c0.execution_count, c0.source], [[], null, ["x = 2\n", "y = 3"]])
  r = await ne.execute({ path: "n.ipynb", cell_index: 0, edit_mode: "insert", cell_type: "markdown", new_source: "hi" })
  assert.equal(r.isError, false, r.output)
  assert.equal(load().length, 3)
  assert.equal(load()[1].cell_type, "markdown")
  r = await ne.execute({ path: "n.ipynb", cell_id: "b", edit_mode: "delete" })
  assert.equal(r.isError, false)
  assert.equal(load().length, 2)
  assert.ok(r.metadata?.files)
  assert.equal((await ne.execute({ path: "n.ipynb", cell_id: "zz" })).isError, true)
})

test("grep context lines, truncation metadata; read window metadata (text unchanged)", async () => {
  const dir = tmp()
  fs.writeFileSync(path.join(dir, "f.txt"), "a\nb\nHIT1\nc\nd\ne\nf\nHIT2\ng\n")
  const grep = pick({ baseDir: dir }, "grep")
  let r = await grep.execute({ pattern: "HIT", context: 1 })
  assert.equal(r.output, "f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g")
  r = await grep.execute({ pattern: "HIT", before: 0, after: 4 })
  assert.equal(r.output, "f.txt:3:HIT1\nf.txt-4-c\nf.txt-5-d\nf.txt-6-e\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g")
  r = await grep.execute({ pattern: "HIT", limit: 1 })
  assert.equal(r.output, "f.txt:3:HIT1")
  assert.deepEqual([r.metadata?.truncated, r.metadata?.total, r.metadata?.count], [true, 2, 1])
  r = await grep.execute({ pattern: "HIT" })
  assert.equal(r.output, "f.txt:3:HIT1\nf.txt:8:HIT2")
  assert.deepEqual(r.metadata, { count: 2 })
  const read = pick({ baseDir: dir }, "read")
  r = await read.execute({ path: "f.txt", limit: 2 })
  assert.equal(r.output, "a\nb")
  assert.deepEqual(r.metadata, { truncated: true, totalLines: 9, nextOffset: 3 })
  r = await read.execute({ path: "f.txt", offset: 8 })
  assert.equal(r.metadata, undefined)
})

// ---- compactor (agents/compaction_test.go) ----
const pad = "pad ".repeat(40), data = "data ".repeat(40)
function transcript(userTurns: number): any[] {
  const m: any[] = [{ role: "system", content: "You are Ava. SOUL." }]
  for (let i = 0; i < userTurns; i++) {
    m.push(
      { role: "user", content: `question ${i} ${pad}` },
      { role: "assistant", content: null, tool_calls: [{ id: `c${i}`, type: "function", function: { name: "lookup", arguments: "{}" } }] },
      { role: "tool", tool_call_id: `c${i}`, content: `result ${i} ${data}` },
      { role: "assistant", content: `answer ${i}` },
    )
  }
  return m
}
const summarize = (older: any[]) => `summarized ${older.length} messages`
const ev = (messages: any[]) => ({ messages, tools: [], model: "m", turn: 0 })

test("compactor onCompact: counts, summary, folded verbatim; never on a no-op", async () => {
  const got: any[] = []
  const msgs = transcript(10)
  const out = await agents.compactor({ maxTokens: 50, keepTail: 20, summarize, onCompact: (i) => { got.push(i) } })(ev(msgs))
  assert.ok(out)
  assert.equal(got.length, 1)
  const i = got[0]
  assert.equal(i.messagesBefore, msgs.length)
  assert.equal(i.messagesAfter, out!.messages.length)
  assert.equal(i.tokensBefore, agents.estimateTokens(msgs))
  assert.equal(i.tokensAfter, agents.estimateTokens(out!.messages))
  assert.ok(i.tokensAfter < i.tokensBefore && i.summary && i.summarized > 0)
  assert.equal(i.folded.length, i.summarized)
  i.folded.forEach((m: any, k: number) => assert.deepEqual(m, msgs[1 + k]))
  const none = await agents.compactor({ maxTokens: 1 << 30, summarize, onCompact: () => assert.fail("onCompact on a no-op") })(ev(msgs))
  assert.equal(none, undefined)
})

test("compactor keeps the sole user prompt of a long agentic run", async () => {
  const msgs: any[] = [{ role: "system", content: "SOUL" }, { role: "user", content: "do the task" }]
  for (let i = 0; i < 8; i++) {
    msgs.push(
      { role: "assistant", content: null, tool_calls: [{ id: `c${i}`, type: "function", function: { name: "lookup", arguments: "{}" } }] },
      { role: "tool", tool_call_id: `c${i}`, content: data },
    )
  }
  let info: any
  const out = await agents.compactor({ maxTokens: 100, keepTail: 20, summarize, onCompact: (i) => { info = i } })(ev(msgs))
  assert.equal(out?.messages.length, 3)
  assert.equal(out!.messages[1].content, "do the task")
  assert.equal(info.folded.length, msgs.length - 2)
})

test("compactor is a no-op when the result would not be smaller", async () => {
  const out = await agents.compactor({ maxTokens: 10, keepTail: 5, summarize: () => "long summary ".repeat(500), onCompact: () => assert.fail("onCompact on a no-op") })(ev(transcript(2)))
  assert.equal(out, undefined)
})

// ---- agents as tools (agents/astool_ctx_test.go) ----
test("asTool: the calling turn's signal cancels the child", async () => {
  const child = agents.agent("child", { does: "d", soul: "s" })
  const blocking = ((_u: string, init: any) => new Promise((_res, rej) => init.signal.addEventListener("abort", () => rej(init.signal.reason), { once: true }))) as any
  const t = child.asTool({ fetch: blocking } as any)
  const ctrl = new AbortController()
  setTimeout(() => ctrl.abort(new Error("turn cancelled")), 100)
  const t0 = Date.now()
  const r = await t.execute({ prompt: "go" }, { signal: ctrl.signal })
  assert.ok(Date.now() - t0 < 3000)
  assert.equal(r.isError, true)
  assert.ok(r.output.startsWith("cancelled"), r.output)
})

test("asToolWith: the child's tool calls go through the parent's gate and ask bridge", async () => {
  let ran = false
  const danger = tool("danger", () => { ran = true; return "did it" }, { hints: { readOnly: false, destructive: true, openWorld: false } })
  const child = agents.agent("child", { does: "d", soul: "s", uses: { tools: [danger] } })
  let n = 0
  const script = (async () => {
    n++
    return n === 1
      ? json({ choices: [{ message: { role: "assistant", content: null, tool_calls: [{ id: "d1", type: "function", function: { name: "danger", arguments: "{}" } }] } }] })
      : json({ choices: [{ message: { role: "assistant", content: "child done" } }] })
  }) as any
  const gated: string[] = []
  let asked = 0
  const t = child.asToolWith(() => ({
    fetch: script,
    hooks: { beforeTool: (ev: any) => { gated.push(ev.name); if (!ev.hints?.readOnly) return { result: pending({ kind: "approval", prompt: `allow ${ev.name}?` }) } } },
    waitFor: async (req: any) => { asked++; return { id: req.id, ok: true } },
  }) as any)
  const r = await t.execute({ prompt: "go" }, { signal: new AbortController().signal })
  assert.deepEqual([r.isError, r.output], [false, "child done"])
  assert.deepEqual(gated, ["danger"])
  assert.equal(asked, 1)
  assert.equal(ran, true)
})

// ---- prompt cache (prompt_cache_test.go) ----
function stripCache(v: any): any {
  if (Array.isArray(v)) return v.map(stripCache)
  if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).filter(([k]) => k !== "cache_control").map(([k, x]) => [k, stripCache(x)]))
  return v
}
const hasCache = (v: unknown) => JSON.stringify(v).includes('"cache_control"')

/** A mock anthropic server simulating a prefix cache: a request reads the longest prefix ending at a
 *  breakpoint an earlier request wrote. */
function cacheSim() {
  const written = new Set<string>()
  const sim = { reads: [] as number[], bodies: [] as any[], turn: 0 }
  const f = (async (_u: string, init: any) => {
    const body = JSON.parse(init.body)
    const segs: string[] = [], marks: boolean[] = []
    for (const v of [body.tools, body.system, ...body.messages]) { segs.push(JSON.stringify(stripCache(v) ?? null)); marks.push(hasCache(v ?? null)) }
    let read = 0
    for (let i = segs.length; i > 0; i--) if (written.has(segs.slice(0, i).join("\0"))) { read = Math.floor(segs.slice(0, i).join("").length / 4); break }
    let write = 0
    for (let i = 0; i < segs.length; i++) {
      if (!marks[i]) continue
      const key = segs.slice(0, i + 1).join("\0")
      if (!written.has(key)) { written.add(key); write = Math.floor(segs.slice(0, i + 1).join("").length / 4) - read }
    }
    sim.reads.push(read)
    sim.bodies.push(body)
    const last = body.messages[body.messages.length - 1]
    const isToolResult = Array.isArray(last.content) && last.content.some((b: any) => b.type === "tool_result")
    let content
    if (isToolResult) content = [{ type: "text", text: "ok" }]
    else {
      const n = ++sim.turn
      content = [{ type: "tool_use", id: `t${n}`, name: n === 2 ? "tool_search" : "read_notes", input: {} }]
    }
    return json({ content, stop_reason: isToolResult ? "end_turn" : "tool_use", usage: { input_tokens: 10, output_tokens: 5, cache_read_input_tokens: read, cache_creation_input_tokens: write } })
  }) as any
  return { sim, f }
}

async function runCacheSession(promptCache?: { key?: string; longTTL?: boolean }) {
  const { sim, f } = cacheSim()
  const catalog = "skill: does a thing in a stable way. ".repeat(700)
  const c = createClient({ baseUrl: "http://x", style: "anthropic", model: "m", apiKey: "k", systemPrompt: "You are Karya.\n\n" + catalog, promptCache, store: new InMemoryConversationStore(), fetch: f })
  const tk = await createToolkit({ builtins: true, extraTools: [tool("read_notes", () => "notes"), tool("tool_search", () => '{"name":"deploy","description":"Deploy.","inputSchema":{"type":"object"}}')] })
  let cacheRead = 0
  for (const p of ["first", "second", "third", "fourth"]) cacheRead += (await c.ask(p, { toolkit: tk, id: "session-1" })).usage.cacheReadTokens ?? 0
  if (promptCache) assert.ok(cacheRead > 0, "usage never reported cache-read tokens")
  return sim
}

test("prompt cache: simulated replay, hits from the 2nd request and never drop", async () => {
  const sim = await runCacheSession({ key: "session-1" })
  assert.equal(sim.reads.length, 8)
  assert.equal(sim.reads[0], 0)
  const floor = sim.reads[1]
  assert.ok(floor >= 6000, `2nd request read ${floor}`)
  for (let i = 1; i < sim.reads.length; i++) assert.ok(sim.reads[i] >= sim.reads[i - 1], `regressed: ${sim.reads}`)
  const t0 = JSON.stringify(sim.bodies[0].tools), s0 = JSON.stringify(sim.bodies[0].system)
  for (const b of sim.bodies) assert.ok(JSON.stringify(b.tools) === t0 && JSON.stringify(b.system) === s0, "prefix changed")
})

test("prompt cache: off is byte-identical (no cache_control, string system)", async () => {
  const sim = await runCacheSession(undefined)
  for (const b of sim.bodies) {
    assert.equal(hasCache(b), false)
    assert.equal(typeof b.system, "string")
  }
})

test("prompt cache: openai key + cached usage; anthropic never mutates the transcript", async () => {
  const body: any = { messages: [] }
  applyPromptCache(body, "openai", { key: "s1" })
  assert.equal(body.prompt_cache_key, "s1")
  const c = createClient({
    baseUrl: "http://x", style: "openai", model: "m", apiKey: "k",
    fetch: (async () => json({ choices: [{ message: { role: "assistant", content: "hi" } }], usage: { prompt_tokens: 100, completion_tokens: 5, prompt_tokens_details: { cached_tokens: 80 } } })) as any,
  })
  const u = (await c.run("hi")).usage
  assert.deepEqual([u.promptTokens, u.cacheReadTokens], [100, 80])
  const msg = { role: "user", content: "hi" }, t = { name: "x" }
  const b2: any = { tools: [t], system: "s", messages: [msg] }
  applyPromptCache(b2, "anthropic", { longTTL: true })
  assert.equal(typeof msg.content, "string")
  assert.equal((t as any).cache_control, undefined)
  assert.equal(JSON.stringify(b2).split('"ttl":"1h"').length - 1, 3)
})

// ---- tool tiers (tool_tiers_test.go) ----
async function tierKit(ran: string[]) {
  const mk = (name: string, description: string, hints?: any) => ({
    ...defineTool({ name, description, inputSchema: { type: "object", properties: { x: { type: "string" } } }, run: (a: any) => { ran.push(`${name}(${a.x})`); return `${name} ok` } }),
    ...(hints ? { hints } : {}),
  })
  return createToolkit({ builtins: false, extraTools: [
    mk("zeta_read", "Read zeta.\nsecond line hidden", { readOnly: true, destructive: false, openWorld: false }),
    mk("alpha_read", "Read alpha.", { readOnly: true, destructive: false, openWorld: false }),
    mk("deploy", "Deploy the service to production.", { readOnly: false, destructive: true, openWorld: false }),
    mk("lint", "Lint the code."),
  ] })
}

test("tool tiers: plan, description, late register is deferred and searchable", async () => {
  const tk = await tierKit([])
  const bodies: any[] = []
  const c = createClient({ baseUrl: "http://x", style: "openai", model: "m", apiKey: "k", toolTiers: { core: ["zeta_read", "alpha_read"], deferred: ["deploy"] },
    fetch: (async (_u: string, init: any) => { bodies.push(JSON.parse(init.body)); return json({ choices: [{ message: { role: "assistant", content: "ok" } }] }) }) as any })
  await c.run("hi", { toolkit: tk })
  const names = bodies[0].tools.map((t: any) => t.function.name)
  assert.deepEqual(names, ["alpha_read", "zeta_read", "tool_search", "call_tool"])
  const desc = bodies[0].tools[2].function.description as string
  assert.ok(desc.includes("- lint — Lint the code.") && desc.includes("(name only): deploy") && !desc.includes("Deploy the service"), desc)
  assert.ok(!desc.includes("second line hidden"))
  const plan = c.toolTierPlan(tk)!
  assert.deepEqual([plan.deploy, plan.lint, plan.alpha_read], ["deferred", "listed", "core"])
  tk.register(defineTool({ name: "late", description: "A late tool.", run: () => "late ok" }))
  await c.run("again", { toolkit: tk })
  assert.equal(JSON.stringify(bodies[1].tools), JSON.stringify(bodies[0].tools), "tools array changed after a late register")
  c.resetToolTiers(tk)
  assert.equal(c.toolTierPlan(tk)!.late, "listed")
})

test("tool tiers: budget demotes the least-used", async () => {
  const tk = await tierKit([])
  const uses: Record<string, number> = { alpha_read: 9, zeta_read: 1, lint: 5, deploy: 0 }
  const base = buildToolPlan({ core: ["alpha_read", "zeta_read"] }, tk.tools())
  const fixed = defaultCountTokens(toolDef(base.search)) + defaultCountTokens(toolDef(base.call))
  const one = defaultCountTokens(toolDef(tk.get("alpha_read")!))
  let p = buildToolPlan({ core: ["alpha_read", "zeta_read"], budgetTokens: fixed + one + 15, usageCount: (n) => uses[n] }, tk.tools())
  assert.deepEqual([p.tier.get("alpha_read"), p.tier.get("zeta_read")], ["core", "listed"])
  p = buildToolPlan({ core: ["alpha_read"], budgetTokens: 1, usageCount: (n) => uses[n] }, tk.tools())
  for (const [n, t] of p.tier) assert.equal(t, "deferred", n)
})

test("tool tiers: tool_search loads, call_tool is unwrapped before hooks, tools array stable", async () => {
  const ran: string[] = []
  const tk = await tierKit(ran)
  const arrays: string[] = []
  let step = 0
  const f = (async (_u: string, init: any) => {
    arrays.push(JSON.stringify(JSON.parse(init.body).tools))
    step++
    const content =
      step === 1 ? [{ type: "tool_use", id: "s1", name: "tool_search", input: { query: "select:deploy" } }]
      : step === 2 ? [{ type: "tool_use", id: "c1", name: "call_tool", input: { name: "deploy", arguments: { x: "prod" } } }]
      : step === 3 ? [{ type: "tool_use", id: "d1", name: "lint", input: { x: "direct" } }]
      : [{ type: "text", text: "done" }]
    return json({ content, stop_reason: "tool_use", usage: { input_tokens: 1, output_tokens: 1 } })
  }) as any
  const gated: string[] = []
  const c = createClient({ baseUrl: "http://x", style: "anthropic", model: "m", apiKey: "k", fetch: f,
    toolTiers: { core: ["alpha_read"], deferred: ["deploy"] },
    hooks: { beforeTool: (ev) => { gated.push(`${ev.name}:${ev.hints ? ev.hints.destructive : "?"}`) } } })
  const r = await c.run("ship it", { toolkit: tk })
  assert.equal(r.status, "done")
  assert.ok(r.toolCalls[0].output.includes('"name": "deploy"'), r.toolCalls[0].output)
  assert.deepEqual(ran, ["deploy(prod)", "lint(direct)"])
  assert.deepEqual(gated, ["deploy:true", "lint:?"])
  for (const a of arrays) assert.equal(a, arrays[0])
})

test("tool tiers: stream events name the real tool for call_tool", async () => {
  const ran: string[] = []
  const tk = await tierKit(ran)
  let step = 0
  const f = (async () => {
    step++
    if (step === 1) {
      return sse([
        { choices: [{ delta: { tool_calls: [{ index: 0, id: "c1", type: "function", function: { name: "call_tool", arguments: JSON.stringify({ name: "deploy", arguments: { x: "prod" } }) } }] } }] },
        { choices: [{ delta: {}, finish_reason: "tool_calls" }] },
      ])
    }
    return sse([{ choices: [{ delta: { content: "done" }, finish_reason: "stop" }] }])
  }) as any
  const c = createClient({ baseUrl: "http://x", style: "openai", model: "m", apiKey: "k", fetch: f, toolTiers: { core: ["alpha_read"] } })
  const got: string[] = []
  for await (const e of c.stream("go", { toolkit: tk })) {
    if (e.type === "tool_call") got.push(`${e.type}:${e.name}:${e.via}:${e.args.x}`)
    if (e.type === "tool_result") got.push(`${e.type}:${e.name}:${e.via}`)
  }
  assert.deepEqual(got, ["tool_call:deploy:call_tool:prod", "tool_result:deploy:call_tool"])
  assert.deepEqual(ran, ["deploy(prod)"])
})

// Same bytes as golang TestToolSearchJSONIsHTMLEscaped: sorted keys, 2-space indent, <>& escaped.
test("tool_search output escapes <, > and & exactly like Go", () => {
  const amp = defineTool({ name: "amp", description: "Use <a & b> here", inputSchema: { type: "object", properties: { x: { type: "string" } } }, run: () => "" })
  const r = toolSearch([amp], (n) => (n === "amp" ? amp : undefined), { query: "select:amp" })
  const want = "Loaded tools (call by name, or with call_tool):\n[\n  {\n    \"description\": \"Use \\u003ca \\u0026 b\\u003e here\",\n    \"inputSchema\": {\n      \"properties\": {\n        \"x\": {\n          \"type\": \"string\"\n        }\n      },\n      \"type\": \"object\"\n    },\n    \"name\": \"amp\"\n  }\n]"
  assert.equal(r.output, want)
})

test("tool_search description cuts at 120 bytes without splitting a character", () => {
  const mk = (name: string, description: string) => defineTool({ name, description, run: () => "" })
  const p = buildToolPlan({ core: [] }, [mk("a1", "a".repeat(119) + "é tail"), mk("a2", "a".repeat(117) + "€z")])
  const desc = p.search.description
  assert.ok(desc.includes("- a1 — " + "a".repeat(119) + "…\n") || desc.endsWith("- a1 — " + "a".repeat(119) + "…"), desc)
  assert.ok(desc.includes("- a2 — " + "a".repeat(117) + "€…"), desc)
})
