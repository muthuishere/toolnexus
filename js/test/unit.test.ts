/**
 * Unit/integration tests (no network, no LLM). Run:
 *   npm run build && node --experimental-strip-types --test test/unit.test.ts
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import os from "node:os"
import fs from "node:fs"
import { fileURLToPath } from "node:url"
import path from "node:path"
import {
  sanitize,
  parseMcpConfig,
  expandEnvHeaders,
  loadSkills,
  defineTool,
  httpTool,
  toOpenAI,
  toAnthropic,
  toGemini,
  createToolkit,
  createClient,
  createBuiltinTools,
  builtinsEnabled,
  agent,
  parseAgentsConfig,
  SKILLS_PROMPT_PREAMBLE,
  InMemoryTaskStore,
  FileTaskStore,
  resolveStore,
  buildAgentCard,
} from "../dist/index.js"

const SKILLS_DIR = path.resolve(fileURLToPath(import.meta.url), "../../../examples/skills")

const BUILTIN_NAMES = [
  "bash", "read", "write", "edit", "grep", "glob",
  "webfetch", "question", "apply_patch", "todowrite",
]

/** Fresh temp dir per test; caller cleans up. */
function tmp(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), "tn-builtin-"))
}
function tool(name: string) {
  return createBuiltinTools().find((t) => t.name === name)!
}

test("sanitize replaces non [a-zA-Z0-9_-]", () => {
  assert.equal(sanitize("a b/c.d:e"), "a_b_c_d_e")
  assert.equal(sanitize("keep-_OK1"), "keep-_OK1")
})

test("parseMcpConfig accepts mcpServers/servers/mcp/raw", () => {
  const s = { foo: { command: ["x"] } }
  assert.deepEqual(parseMcpConfig({ mcpServers: s }), s)
  assert.deepEqual(parseMcpConfig({ servers: s }), s)
  assert.deepEqual(parseMcpConfig({ mcp: s }), s)
  assert.deepEqual(parseMcpConfig(s), s)
})

test("expandEnvHeaders expands ${ENV} from process.env", () => {
  process.env.TN_TEST_TOKEN = "secret123"
  const out = expandEnvHeaders({ Authorization: "Bearer ${TN_TEST_TOKEN}", X: "plain" })
  assert.equal(out!.Authorization, "Bearer secret123")
  assert.equal(out!.X, "plain")
  assert.equal(expandEnvHeaders(undefined), undefined)
})

test("skills: discovery, prompt, and byte-exact skill block", async () => {
  const src = loadSkills(SKILLS_DIR)
  assert.ok(src.skills["hello-world"], "hello-world discovered")
  assert.match(src.prompt(), /## Available Skills/)
  assert.match(src.prompt(), /\*\*hello-world\*\*/)

  const res = await src.tool.execute({ name: "hello-world" })
  assert.equal(res.isError, false)
  assert.match(res.output, /^<skill_content name="hello-world">/)
  assert.match(res.output, /# Skill: hello-world/)
  assert.match(res.output, /Base directory for this skill: file:\/\//)
  assert.match(res.output, /<skill_files>/)
  assert.match(res.output, /<\/skill_content>$/)

  const miss = await src.tool.execute({ name: "nope" })
  assert.equal(miss.isError, true)
  assert.match(miss.output, /not found/)
})

test("defineTool: string, ToolResult, and thrown error", async () => {
  const s = defineTool({ name: "s", description: "", run: () => "hi" })
  assert.deepEqual(await s.execute({}), { output: "hi", isError: false })

  const r = defineTool({ name: "r", description: "", run: () => ({ output: "x", isError: true }) })
  assert.deepEqual(await r.execute({}), { output: "x", isError: true })

  const e = defineTool({ name: "e", description: "", run: () => { throw new Error("boom") } })
  const er = await e.execute({})
  assert.equal(er.isError, true)
  assert.match(er.output, /boom/)
  assert.equal(s.source, "native")
})

test("httpTool: placeholder, env header, GET query, non-2xx", async () => {
  const seen: { url?: string; auth?: string } = {}
  const server = http.createServer((req, res) => {
    seen.url = req.url
    seen.auth = req.headers.authorization
    if (req.url?.startsWith("/posts/5")) {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ id: 5, title: "hello" }))
    } else {
      res.writeHead(404)
      res.end("nope")
    }
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  process.env.TN_HTTP_TOKEN = "tok"

  const ok = httpTool({
    name: "get_post", description: "", method: "GET",
    url: `http://127.0.0.1:${port}/posts/{id}`,
    headers: { Authorization: "Bearer ${TN_HTTP_TOKEN}" },
    inputSchema: { type: "object", properties: { id: { type: "number" }, q: { type: "string" } }, required: ["id"] },
  })
  const r1 = await ok.execute({ id: 5, q: "x" })
  assert.equal(r1.isError, false)
  assert.match(r1.output, /hello/)
  assert.equal(seen.auth, "Bearer tok", "env header expanded")
  assert.match(seen.url!, /\/posts\/5\?q=x/, "placeholder + querystring")
  assert.equal(ok.source, "http")

  const bad = httpTool({ name: "b", description: "", method: "GET", url: `http://127.0.0.1:${port}/posts/{id}`,
    inputSchema: { type: "object", properties: { id: { type: "number" } }, required: ["id"] } })
  const r2 = await bad.execute({ id: 99 })
  assert.equal(r2.isError, true)
  assert.match(r2.output, /^HTTP 404:/)
  server.close()
})

test("adapters produce the documented shapes", () => {
  const t = defineTool({ name: "t", description: "d", inputSchema: { type: "object", properties: {} }, run: () => "" })
  assert.deepEqual(toOpenAI([t])[0], { type: "function", function: { name: "t", description: "d", parameters: t.inputSchema } })
  assert.deepEqual(toAnthropic([t])[0], { name: "t", description: "d", input_schema: t.inputSchema })
  const g = toGemini([t])
  assert.equal(g[0].functionDeclarations[0].name, "t")
})

test("toolkit: register, get, execute, duplicate-name keeps first", async () => {
  const tk = await createToolkit({ skillsDir: SKILLS_DIR })
  tk.register(defineTool({ name: "add", description: "", run: ({ a, b }) => `${(a as number) + (b as number)}` }))
  tk.register(defineTool({ name: "add", description: "dup", run: () => "SHOULD_NOT_WIN" }))
  assert.ok(tk.get("add"))
  assert.ok(tk.get("skill"))
  const r = await tk.execute("add", { a: 2, b: 3 })
  assert.equal(r.output, "5")
  const miss = await tk.execute("ghost", {})
  assert.equal(miss.isError, true)
  await tk.close()
})

test("client: retries on 503 then succeeds (backoff)", async () => {
  let hits = 0
  const server = http.createServer((req, res) => {
    hits++
    if (hits < 3) { res.writeHead(503); res.end("busy"); return }
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ choices: [{ message: { content: "ok" } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({})
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k", retries: 3, retryBaseMs: 5 })
  const res = await client.run("hi", { toolkit: tk })
  assert.equal(res.text, "ok")
  assert.equal(hits, 3, "two 503s retried, third succeeded")
  await tk.close()
  server.close()
})

test("client: anthropic style — tool_use loop, headers, usage (mock)", async () => {
  let firstReq: any = {}
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      const msg = JSON.parse(body)
      const hasToolResult = msg.messages.some((m: any) => Array.isArray(m.content) && m.content.some((b: any) => b.type === "tool_result"))
      if (!hasToolResult) {
        firstReq = { url: req.url, apiKey: req.headers["x-api-key"], version: req.headers["anthropic-version"], tools: msg.tools }
        res.writeHead(200, { "content-type": "application/json" })
        res.end(JSON.stringify({ content: [{ type: "tool_use", id: "t1", name: "add", input: { a: 21, b: 21 } }], stop_reason: "tool_use", usage: { input_tokens: 10, output_tokens: 5 } }))
      } else {
        res.writeHead(200, { "content-type": "application/json" })
        res.end(JSON.stringify({ content: [{ type: "text", text: "The sum is 42." }], stop_reason: "end_turn", usage: { input_tokens: 8, output_tokens: 6 } }))
      }
    })
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  tk.register(defineTool({ name: "add", description: "add", inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] }, run: ({ a, b }) => `${(a as number) + (b as number)}` }))
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "anthropic", model: "claude-x", apiKey: "k" })
  const res = await client.run("add them", { toolkit: tk })

  assert.equal(firstReq.url, "/v1/messages", "appends /v1/messages")
  assert.equal(firstReq.apiKey, "k")
  assert.equal(firstReq.version, "2023-06-01")
  assert.equal(firstReq.tools[0].name, "add", "anthropic tool shape (name/input_schema)")
  assert.match(res.text, /42/)
  assert.equal(res.toolCalls[0].name, "add")
  assert.equal(res.toolCalls[0].output, "42")
  assert.equal(res.turns, 2)
  assert.equal(res.usage.totalTokens, 29, "input+output summed across both turns")
  await tk.close()
  server.close()
})

test("client: run-level timeout aborts", async () => {
  const server = http.createServer((req, res) => {
    setTimeout(() => { res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify({ choices: [{ message: { content: "late" } }] })) }, 300)
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({})
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k", retries: 0, timeoutMs: 40 })
  await assert.rejects(() => client.run("hi", { toolkit: tk }), /timeout|abort/i)
  await tk.close()
  server.close()
})

// ---------------------------------------------------------------------------
// builtin tools
// ---------------------------------------------------------------------------

test("builtinsEnabled: default ON, boolean + object precedence (MCP semantics)", () => {
  assert.equal(builtinsEnabled(undefined), true)
  assert.equal(builtinsEnabled(true), true)
  assert.equal(builtinsEnabled(false), false)
  assert.equal(builtinsEnabled({}), true)
  assert.equal(builtinsEnabled({ enabled: false }), false)
  assert.equal(builtinsEnabled({ disabled: true }), false)
  // disabled:true wins regardless of enabled
  assert.equal(builtinsEnabled({ enabled: true, disabled: true }), false)
})

test("toolkit: default assembly includes all 10 builtins, each source:builtin", async () => {
  const tk = await createToolkit({})
  for (const name of BUILTIN_NAMES) {
    const t = tk.get(name)
    assert.ok(t, `builtin ${name} present`)
    assert.equal(t!.source, "builtin", `${name} source`)
  }
  // and they appear in the provider schema arrays
  const names = tk.toOpenAI().map((f: any) => f.function.name)
  for (const name of BUILTIN_NAMES) assert.ok(names.includes(name), `${name} in toOpenAI`)
  await tk.close()
})

test("toolkit: builtins toggle off (false / {disabled} / {enabled:false}) removes all 10", async () => {
  for (const off of [false, { disabled: true }, { enabled: false }]) {
    const extra = defineTool({ name: "myextra", description: "", run: () => "x" })
    const tk = await createToolkit({ skillsDir: SKILLS_DIR, extraTools: [extra], builtins: off as any })
    for (const name of BUILTIN_NAMES) assert.equal(tk.get(name), undefined, `${name} absent when off=${JSON.stringify(off)}`)
    // skill + extras unaffected
    assert.ok(tk.get("skill"), "skill tool unaffected")
    assert.ok(tk.get("myextra"), "extra tool unaffected")
    await tk.close()
  }
})

test("toolkit: builtins.tools map disables named tools only, unknown ignored", async () => {
  const tk = await createToolkit({ builtins: { tools: { bash: false, write: false, nope: false } } })
  assert.equal(tk.get("bash"), undefined, "bash disabled")
  assert.equal(tk.get("write"), undefined, "write disabled")
  // the other nine remain
  for (const name of BUILTIN_NAMES.filter((n) => n !== "bash" && n !== "write")) {
    assert.ok(tk.get(name), `${name} still present`)
  }
  await tk.close()
})

test("toolkit: whole-source-off overrides the per-tool map", async () => {
  const tk = await createToolkit({ builtins: { disabled: true, tools: { read: true } } })
  for (const name of BUILTIN_NAMES) assert.equal(tk.get(name), undefined, `${name} absent (source off wins)`)
  await tk.close()
})

test("toolkit: extraTool named read shadows the builtin (host override)", async () => {
  const mine = defineTool({ name: "read", description: "host read", run: () => "HOST_READ" })
  const tk = await createToolkit({ extraTools: [mine] })
  const r = tk.get("read")!
  assert.equal(r.source, "native", "host tool wins the collision")
  const out = await tk.execute("read", {})
  assert.equal(out.output, "HOST_READ")
  await tk.close()
})

test("builtin read/write/edit: round-trip, replaceAll, error cases", async () => {
  const dir = tmp()
  try {
    const file = path.join(dir, "note.txt")
    const w = await tool("write").execute({ path: file, content: "alpha\nbeta\nalpha\n" })
    assert.equal(w.isError, false)
    assert.match(w.output, /Wrote \d+ bytes/)

    const r = await tool("read").execute({ path: file })
    assert.equal(r.output, "alpha\nbeta\nalpha\n")

    // offset/limit window (1-based)
    const win = await tool("read").execute({ path: file, offset: 2, limit: 1 })
    assert.equal(win.output, "beta")

    // edit non-unique without replaceAll => error, no write
    const dup = await tool("edit").execute({ path: file, oldString: "alpha", newString: "X" })
    assert.equal(dup.isError, true)
    assert.match(dup.output, /not unique/)
    assert.equal(fs.readFileSync(file, "utf8"), "alpha\nbeta\nalpha\n", "no partial write")

    // edit not-found => error
    const nf = await tool("edit").execute({ path: file, oldString: "zzz", newString: "X" })
    assert.equal(nf.isError, true)
    assert.match(nf.output, /not found/)

    // edit replaceAll
    const ra = await tool("edit").execute({ path: file, oldString: "alpha", newString: "OMEGA", replaceAll: true })
    assert.equal(ra.isError, false)
    assert.equal(fs.readFileSync(file, "utf8"), "OMEGA\nbeta\nOMEGA\n")

    // edit single unique replace
    const one = await tool("edit").execute({ path: file, oldString: "beta", newString: "gamma" })
    assert.equal(one.isError, false)
    assert.equal(fs.readFileSync(file, "utf8"), "OMEGA\ngamma\nOMEGA\n")

    // read missing => error
    const miss = await tool("read").execute({ path: path.join(dir, "nope.txt") })
    assert.equal(miss.isError, true)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("builtin grep + glob find expected files (stable counts)", async () => {
  const dir = tmp()
  try {
    fs.writeFileSync(path.join(dir, "a.txt"), "needle here\nplain\n")
    fs.writeFileSync(path.join(dir, "b.txt"), "no match\nneedle again\n")
    fs.mkdirSync(path.join(dir, "sub"))
    fs.writeFileSync(path.join(dir, "sub", "c.md"), "needle in md\n")

    const g = await tool("grep").execute({ pattern: "needle", path: dir })
    assert.equal(g.isError, false)
    assert.equal((g.metadata as any).count, 3, "three needle lines across files")

    const gInc = await tool("grep").execute({ pattern: "needle", path: dir, include: "*.txt" })
    assert.equal((gInc.metadata as any).count, 2, "include filters to .txt")

    const gl = await tool("glob").execute({ pattern: "*.txt", path: dir })
    assert.equal((gl.metadata as any).count, 2, "two .txt files")
    assert.deepEqual(gl.output.split("\n").sort(), ["a.txt", "b.txt"])

    const glMd = await tool("glob").execute({ pattern: "*.md", path: dir })
    assert.equal((glMd.metadata as any).count, 1)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("builtin bash: success output + non-zero exit is error", async () => {
  const okr = await tool("bash").execute({ command: "printf 'hello-bash'" })
  assert.equal(okr.isError, false)
  assert.match(okr.output, /hello-bash/)

  const bad = await tool("bash").execute({ command: "exit 3" })
  assert.equal(bad.isError, true)
  assert.match(bad.output, /code 3/)
  assert.equal((bad.metadata as any).exitCode, 3)

  // workdir honored
  const dir = tmp()
  try {
    const cwd = await tool("bash").execute({ command: "pwd", workdir: dir })
    assert.equal(cwd.isError, false)
    assert.ok(fs.realpathSync(dir) === fs.realpathSync(cwd.output.trim()) || cwd.output.includes(path.basename(dir)))
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("builtin webfetch (localhost)", async () => {
  const server = http.createServer((req, res) => {
    if (req.url === "/ok") {
      res.writeHead(200, { "content-type": "text/plain" })
      res.end("fetched-body-content")
    } else {
      res.writeHead(500)
      res.end("boom")
    }
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port

  const okr = await tool("webfetch").execute({ url: `http://127.0.0.1:${port}/ok`, format: "text" })
  assert.equal(okr.isError, false)
  assert.match(okr.output, /fetched-body-content/)
  assert.equal((okr.metadata as any).status, 200)

  const bad = await tool("webfetch").execute({ url: `http://127.0.0.1:${port}/err` })
  assert.equal(bad.isError, true)
  assert.match(bad.output, /^HTTP 500/)

  server.close()
})

test("builtin apply_patch: add + update + delete round-trip; non-match aborts atomically", async () => {
  const dir = tmp()
  try {
    const toDelete = path.join(dir, "gone.txt")
    const toUpdate = path.join(dir, "keep.txt")
    const toAdd = path.join(dir, "new.txt")
    fs.writeFileSync(toDelete, "bye\n")
    fs.writeFileSync(toUpdate, "line one\nline two\nline three\n")

    const patch = [
      "*** Begin Patch",
      `*** Add File: ${toAdd}`,
      "+created line 1",
      "+created line 2",
      `*** Update File: ${toUpdate}`,
      "@@",
      " line one",
      "-line two",
      "+line TWO changed",
      " line three",
      `*** Delete File: ${toDelete}`,
      "*** End Patch",
      "",
    ].join("\n")

    const res = await tool("apply_patch").execute({ patchText: patch })
    assert.equal(res.isError, false, res.output)
    assert.equal(fs.readFileSync(toAdd, "utf8"), "created line 1\ncreated line 2")
    assert.equal(fs.readFileSync(toUpdate, "utf8"), "line one\nline TWO changed\nline three\n")
    assert.equal(fs.existsSync(toDelete), false, "deleted")

    // non-matching hunk => error with no partial write
    const addTwo = path.join(dir, "should-not-exist.txt")
    const badPatch = [
      "*** Begin Patch",
      `*** Add File: ${addTwo}`,
      "+content",
      `*** Update File: ${toUpdate}`,
      "@@",
      "-DOES NOT MATCH",
      "+whatever",
      "*** End Patch",
    ].join("\n")
    const bad = await tool("apply_patch").execute({ patchText: badPatch })
    assert.equal(bad.isError, true)
    assert.match(bad.output, /does not match/)
    assert.equal(fs.existsSync(addTwo), false, "no partial write when a later hunk fails")
    assert.equal(fs.readFileSync(toUpdate, "utf8"), "line one\nline TWO changed\nline three\n", "update untouched")
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("builtin question + todowrite: structured round-trip", async () => {
  const questions = [{ question: "Pick one?", header: "Choice", options: ["a", "b"], multiple: false }]
  const q = await tool("question").execute({ questions })
  assert.equal(q.isError, false)
  assert.deepEqual(JSON.parse(q.output), questions)
  assert.deepEqual((q.metadata as any).questions, questions)

  const todos = [
    { id: "1", text: "write code", completed: true },
    { id: "2", text: "ship it", completed: false },
  ]
  const t = await tool("todowrite").execute({ todos })
  assert.equal(t.isError, false)
  assert.deepEqual((t.metadata as any).todos, todos)
  assert.match(t.output, /\[x\] write code/)
  assert.match(t.output, /\[ \] ship it/)
})

test("skillsPrompt preamble present with skills, absent without", async () => {
  const withSkills = loadSkills(SKILLS_DIR)
  const p = withSkills.prompt()
  assert.ok(p.startsWith(SKILLS_PROMPT_PREAMBLE), "starts with the exact preamble")
  assert.equal(
    p,
    SKILLS_PROMPT_PREAMBLE + "\n\n## Available Skills\n" +
      Object.values(withSkills.skills)
        .filter((s) => s.description !== undefined)
        .sort((a, b) => a.name.localeCompare(b.name))
        .map((s) => `- **${s.name}**: ${s.description}`)
        .join("\n"),
  )

  const noSkills = loadSkills(tmp())
  assert.equal(noSkills.prompt(), "No skills are currently available.")
  assert.ok(!noSkills.prompt().startsWith(SKILLS_PROMPT_PREAMBLE))
})

// ---------------------------------------------------------------------------
// A2A (outbound) — in-process fake A2A agent, no external network.
// ---------------------------------------------------------------------------

/**
 * Stand up a tiny fake A2A agent on an ephemeral port:
 *   GET /.well-known/agent-card.json → card { name:"reviewer", url→self,
 *       skills:[review, plan, fail] }
 *   POST /  (JSON-RPC) SendMessage → Task {state:"submitted"}; GetTask →
 *       "working" once, then terminal. Behavior keyed on the message text:
 *       "fail" → failed; "slow" → stays "working"; else → completed(REVIEWED).
 */
async function startA2AStub() {
  const tasks = new Map<string, { polls: number; mode: "completed" | "failed" | "slow" }>()
  const seen = { auth: undefined as string | undefined, sendMessageCalls: 0, getTaskCalls: 0 }
  let counter = 0

  const server = http.createServer((req, res) => {
    const port = (server.address() as any).port
    if (req.method === "GET" && req.url === "/.well-known/agent-card.json") {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(
        JSON.stringify({
          name: "reviewer",
          description: "a code reviewer",
          version: "1.0.0",
          protocolVersion: "0.3.0",
          capabilities: { streaming: false, pushNotifications: false },
          defaultInputModes: ["text/plain"],
          defaultOutputModes: ["text/plain"],
          url: `http://127.0.0.1:${port}/`,
          skills: [
            { id: "review", name: "Review", description: "Review some code" },
            { id: "plan", name: "Plan", description: "Plan a task" },
            { id: "fail", name: "Fail", description: "Always fails" },
          ],
        }),
      )
      return
    }
    if (req.method === "POST") {
      let body = ""
      req.on("data", (c) => (body += c))
      req.on("end", () => {
        seen.auth = req.headers.authorization
        const rpc = JSON.parse(body)
        const send = (result: unknown) => {
          res.writeHead(200, { "content-type": "application/json" })
          res.end(JSON.stringify({ jsonrpc: "2.0", id: rpc.id, result }))
        }
        if (rpc.method === "SendMessage") {
          seen.sendMessageCalls++
          const text = (rpc.params.message.parts as any[]).map((p) => p.text).join("")
          const mode = text.includes("fail") ? "failed" : text.includes("slow") ? "slow" : "completed"
          const id = `t${++counter}`
          tasks.set(id, { polls: 0, mode })
          send({ id, status: { state: "submitted" } })
        } else if (rpc.method === "GetTask") {
          seen.getTaskCalls++
          const id = rpc.params.id
          const t = tasks.get(id)!
          t.polls++
          if (t.mode === "slow" || t.polls < 2) {
            send({ id, status: { state: "working" } })
          } else if (t.mode === "failed") {
            send({
              id,
              status: { state: "failed", message: { role: "agent", parts: [{ kind: "text", text: "boom" }] } },
            })
          } else {
            send({
              id,
              status: { state: "completed" },
              artifacts: [{ parts: [{ kind: "text", text: "REVIEWED" }] }],
            })
          }
        } else {
          res.writeHead(200, { "content-type": "application/json" })
          res.end(JSON.stringify({ jsonrpc: "2.0", id: rpc.id, error: { code: -32601, message: "unknown method" } }))
        }
      })
      return
    }
    res.writeHead(404)
    res.end("nope")
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  return { server, port, cardUrl: `http://127.0.0.1:${port}/.well-known/agent-card.json`, seen }
}

test("a2a: agent skills become source:a2a tools; success round-trip + env header", async () => {
  const { server, cardUrl, seen } = await startA2AStub()
  process.env.TN_A2A_TOKEN = "a2a-secret"
  try {
    const tk = await createToolkit({
      builtins: false,
      agents: [agent({ card: cardUrl, pollEvery: 5, headers: { Authorization: "Bearer ${TN_A2A_TOKEN}" } })],
    })
    // skills → tools, source:"a2a"
    const review = tk.get("reviewer_review")!
    assert.ok(review, "reviewer_review present")
    assert.equal(review.source, "a2a")
    assert.ok(tk.get("reviewer_plan"), "reviewer_plan present")
    // in the provider schema
    const names = tk.toOpenAI().map((f: any) => f.function.name)
    assert.ok(names.includes("reviewer_review") && names.includes("reviewer_plan"))

    // execute → SendMessage once → poll GetTask → completed → "REVIEWED"
    const r = await tk.execute("reviewer_review", { task: "please review" })
    assert.equal(r.isError, false)
    assert.equal(r.output, "REVIEWED")
    assert.equal(seen.sendMessageCalls, 1, "SendMessage called exactly once")
    assert.ok(seen.getTaskCalls >= 2, "polled GetTask until completed")
    assert.equal(seen.auth, "Bearer a2a-secret", "env header expanded at call time")
    assert.equal((r.metadata as any).state, "completed")
    assert.equal((r.metadata as any).agent, "reviewer")
    assert.ok((r.metadata as any).taskId)
    await tk.close()
  } finally {
    server.close()
  }
})

test("a2a: failed task maps to isError", async () => {
  const { server, cardUrl } = await startA2AStub()
  try {
    const tk = await createToolkit({ builtins: false, agents: [agent({ card: cardUrl, pollEvery: 5 })] })
    const r = await tk.execute("reviewer_fail", { task: "please fail" })
    assert.equal(r.isError, true)
    assert.match(r.output, /failed/)
    assert.match(r.output, /boom/, "carries the status message text")
    assert.equal((r.metadata as any).state, "failed")
    await tk.close()
  } finally {
    server.close()
  }
})

test("a2a: ctx cancel mid-poll stops further GetTask calls", async () => {
  const { server, cardUrl, seen } = await startA2AStub()
  try {
    const tk = await createToolkit({ builtins: false, agents: [agent({ card: cardUrl, pollEvery: 10 })] })
    const ctrl = new AbortController()
    const p = tk.execute("reviewer_review", { task: "slow one" }, { signal: ctrl.signal })
    setTimeout(() => ctrl.abort(), 35)
    const r = await p
    assert.equal(r.isError, true)
    assert.equal((r.metadata as any).state, "canceled")
    const afterAbort = seen.getTaskCalls
    await new Promise((res) => setTimeout(res, 60))
    assert.equal(seen.getTaskCalls, afterAbort, "no GetTask calls after abort")
    await tk.close()
  } finally {
    server.close()
  }
})

test("a2a: config agents block — enabled:false skipped, enabled resolves", async () => {
  const { server, cardUrl } = await startA2AStub()
  try {
    const off = await createToolkit({
      builtins: false,
      mcpConfig: { agents: { rev: { card: cardUrl, disabled: true } } },
    })
    assert.equal(off.get("reviewer_review"), undefined, "disabled agent skipped")
    await off.close()

    const on = await createToolkit({
      builtins: false,
      mcpConfig: { agents: { rev: { card: cardUrl, pollEvery: 5 } } },
    })
    assert.ok(on.get("reviewer_review"), "enabled agent resolved from config")
    await on.close()
  } finally {
    server.close()
  }
})

test("a2a: addAgent() registers tools at runtime", async () => {
  const { server, cardUrl } = await startA2AStub()
  try {
    const tk = await createToolkit({ builtins: false })
    assert.equal(tk.get("reviewer_review"), undefined, "absent before addAgent")
    await tk.addAgent(agent({ card: cardUrl, pollEvery: 5 }))
    assert.ok(tk.get("reviewer_review"), "present after addAgent")
    assert.ok(tk.get("reviewer_plan"))
    // bare card URL form also works
    await tk.close()
  } finally {
    server.close()
  }
})

test("a2a: parseAgentsConfig honors enabled/disabled precedence", () => {
  const parsed = parseAgentsConfig({
    a: { card: "http://x/1" },
    b: { card: "http://x/2", disabled: true },
    c: { card: "http://x/3", enabled: false },
    d: { card: "http://x/4", enabled: true, disabled: true },
  })
  assert.equal(parsed.length, 1)
  assert.equal(parsed[0].card, "http://x/1")
})

test("skill discovery follows symlinked skill directories (opencode parity)", () => {
  // Layout: root/ has a real skill (direct/) and a symlink (linked/) → an
  // out-of-tree skill dir. The walker must discover both.
  const root = tmp()
  const direct = path.join(root, "direct")
  fs.mkdirSync(direct, { recursive: true })
  fs.writeFileSync(path.join(direct, "SKILL.md"), "---\nname: direct-skill\ndescription: d\n---\nbody\n")

  const external = tmp()
  const target = path.join(external, "linked-target")
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(path.join(target, "SKILL.md"), "---\nname: linked-skill\ndescription: l\n---\nbody\n")
  fs.symlinkSync(target, path.join(root, "linked")) // symlinked skill directory

  const src = loadSkills(root)
  assert.ok(src.skills["direct-skill"], "real skill discovered")
  assert.ok(src.skills["linked-skill"], "symlinked skill directory discovered")
})

// ---------------------------------------------------------------------------
// A2A (inbound) — serve a toolkit as an A2A agent; real localhost http.
// ---------------------------------------------------------------------------

/** A mock OpenAI-style LLM endpoint. `handler(req,res,body)` writes the reply. */
async function startMockLLM(handler: (req: any, res: any, body: string) => void) {
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => handler(req, res, body))
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  return { server, baseUrl: `http://127.0.0.1:${port}` }
}

/** Canned assistant completion with no tool calls. */
function cannedCompletion(text: string) {
  return (_req: any, res: any) => {
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ choices: [{ message: { content: text } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
  }
}

/** POST one JSON-RPC 2.0 request to a served endpoint and return the envelope. */
async function rpc(endpoint: string, method: string, params: unknown): Promise<any> {
  const res = await fetch(endpoint, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: "req-1", method, params }),
  })
  return res.json()
}

const TERMINAL_STATES = new Set(["completed", "failed", "canceled"])

async function pollUntilTerminal(endpoint: string, id: string): Promise<any> {
  for (let i = 0; i < 100; i++) {
    const env = await rpc(endpoint, "GetTask", { id })
    const state = env.result?.status?.state
    if (TERMINAL_STATES.has(state)) return env.result
    await new Promise((r) => setTimeout(r, 10))
  }
  throw new Error("task never reached a terminal state")
}

function textMessage(text: string) {
  return { message: { role: "user", parts: [{ kind: "text", text }] } }
}

test("a2a inbound: round-trip — serve → outbound agent → client.run → artifact", async () => {
  const { server: llm, baseUrl } = await startMockLLM(cannedCompletion("TRANSCRIBED"))
  const client = createClient({ baseUrl, style: "openai", model: "x", apiKey: "k" })
  const tk = await createToolkit({ skillsDir: SKILLS_DIR, builtins: false })
  const srv = await tk.serve("127.0.0.1:0", { client, a2a: { name: "video-desk", skills: ["hello-world"] } })
  // Caller side: the OUTBOUND agent consumes the served card and exposes its
  // hello-world skill as a tool.
  const caller = await createToolkit({
    builtins: false,
    agents: [agent({ card: srv.url + "/.well-known/agent-card.json", pollEvery: 10 })],
  })
  try {
    const tool = caller.get("video-desk_hello-world")
    assert.ok(tool, "outbound tool built from the served skill")
    assert.equal(tool!.source, "a2a")

    const r = await caller.execute("video-desk_hello-world", { task: "do it" })
    assert.equal(r.isError, false)
    assert.equal(r.output, "TRANSCRIBED", "submit→poll→client.run→artifact round-trips end to end")
    assert.equal((r.metadata as any).state, "completed")
  } finally {
    await caller.close()
    await srv.stop()
    await tk.close()
    llm.close()
  }
})

test("a2a inbound: card advertises skills (not raw tools), streaming false", async () => {
  const { server: llm, baseUrl } = await startMockLLM(cannedCompletion("ok"))
  const client = createClient({ baseUrl, style: "openai", model: "x", apiKey: "k" })
  // builtins ON so bash/read exist as tools — they must NOT leak into the card.
  const tk = await createToolkit({ skillsDir: SKILLS_DIR })
  const srv = await tk.serve("127.0.0.1:0", { client, a2a: { name: "video-desk" } })
  try {
    const card = await (await fetch(srv.url + "/.well-known/agent-card.json")).json()
    const ids = (card.skills as any[]).map((s) => s.id)
    assert.ok(ids.includes("hello-world"), "SKILL.md skill advertised")
    assert.ok(!ids.includes("bash") && !ids.includes("read"), "raw builtin tools stay private")
    assert.equal(card.capabilities.streaming, false)
    assert.equal(card.name, "video-desk")
    assert.equal(card.url, srv.url + "/", "card.url is the JSON-RPC endpoint")
    assert.deepEqual(card.defaultInputModes, ["text"])
  } finally {
    await srv.stop()
    await tk.close()
    llm.close()
  }
})

test("a2a inbound: profile is opt-in — no a2a ⇒ card 404", async () => {
  const { server: llm, baseUrl } = await startMockLLM(cannedCompletion("ok"))
  const client = createClient({ baseUrl, style: "openai", model: "x", apiKey: "k" })
  const tk = await createToolkit({ skillsDir: SKILLS_DIR, builtins: false })
  const srv = await tk.serve("127.0.0.1:0", { client })
  try {
    const res = await fetch(srv.url + "/.well-known/agent-card.json")
    assert.equal(res.status, 404, "no A2A routes mounted without an a2a profile")
  } finally {
    await srv.stop()
    await tk.close()
    llm.close()
  }
})

test("a2a inbound: fulfilment error ⇒ failed task; server keeps serving", async () => {
  const { server: llm, baseUrl } = await startMockLLM((_req, res) => {
    res.writeHead(500)
    res.end("model down")
  })
  const client = createClient({ baseUrl, style: "openai", model: "x", apiKey: "k", retries: 0 })
  const tk = await createToolkit({ skillsDir: SKILLS_DIR, builtins: false })
  const events: any[] = []
  const srv = await tk.serve("127.0.0.1:0", { client, a2a: { name: "video-desk" }, onTask: (ev) => { events.push(ev) } })
  const endpoint = srv.url + "/"
  try {
    const sent = await rpc(endpoint, "SendMessage", textMessage("go"))
    assert.equal(sent.result.status.state, "submitted", "SendMessage returns submitted immediately")
    const id = sent.result.id
    const task = await pollUntilTerminal(endpoint, id)
    assert.equal(task.status.state, "failed")

    // server survives a fulfilment error and still answers the card.
    const card = await fetch(srv.url + "/.well-known/agent-card.json")
    assert.equal(card.status, 200)

    assert.equal(events.at(-1).state, "failed", "onTask surfaced the failed outcome")
  } finally {
    await srv.stop()
    await tk.close()
    llm.close()
  }
})

test("a2a inbound: host-supplied TaskStore is used (save/get routed through it)", async () => {
  class StubStore {
    saved: string[] = []
    gotten: string[] = []
    private map = new Map<string, any>()
    async save(t: any) { this.saved.push(t.status.state); this.map.set(t.id, t) }
    async get(id: string) { this.gotten.push(id); return this.map.get(id) }
  }
  const { server: llm, baseUrl } = await startMockLLM(cannedCompletion("DONE"))
  const client = createClient({ baseUrl, style: "openai", model: "x", apiKey: "k" })
  const tk = await createToolkit({ skillsDir: SKILLS_DIR, builtins: false })
  const store = new StubStore()
  const srv = await tk.serve("127.0.0.1:0", { client, a2a: { name: "video-desk", store } })
  const endpoint = srv.url + "/"
  try {
    const sent = await rpc(endpoint, "SendMessage", textMessage("go"))
    const id = sent.result.id
    const task = await pollUntilTerminal(endpoint, id)
    assert.equal(task.status.state, "completed")
    assert.deepEqual(store.saved, ["submitted", "working", "completed"], "all writes went through the host store")
    assert.ok(store.gotten.length >= 1, "GetTask read through the host store")
    // GetTask on an unknown id ⇒ JSON-RPC error.
    const miss = await rpc(endpoint, "GetTask", { id: "nope" })
    assert.equal(miss.error.code, -32001)
    // Unknown method ⇒ -32601.
    const bad = await rpc(endpoint, "Frobnicate", {})
    assert.equal(bad.error.code, -32601)
  } finally {
    await srv.stop()
    await tk.close()
    llm.close()
  }
})

test("a2a inbound: FileTaskStore round-trips on disk; resolveStore maps selectors", async () => {
  const dir = tmp()
  try {
    const store = new FileTaskStore(dir)
    await store.save({ id: "abc-123", status: { state: "completed" }, artifacts: [{ parts: [{ kind: "text", text: "hi" }] }] })
    const got = await store.get("abc-123")
    assert.equal(got!.status.state, "completed")
    assert.equal((got!.artifacts as any)[0].parts[0].text, "hi")
    assert.equal(await store.get("missing"), undefined)
    assert.ok(fs.existsSync(path.join(dir, "abc-123.json")), "one JSON file per task id")

    // resolveStore selectors.
    assert.ok(resolveStore() instanceof InMemoryTaskStore)
    assert.ok(resolveStore("memory") instanceof InMemoryTaskStore)
    assert.ok(resolveStore(`file:${dir}`) instanceof FileTaskStore)
    assert.equal(resolveStore(store), store, "an object is used as-is")
    assert.throws(() => resolveStore("redis:whatever"), /Unknown A2A store/)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("a2a inbound: buildAgentCard filters to a2a.skills and defaults sensibly", () => {
  const skills = [
    { name: "alpha", description: "A", location: "/x/alpha/SKILL.md", content: "" },
    { name: "beta", description: "B", location: "/x/beta/SKILL.md", content: "" },
  ]
  const filtered = buildAgentCard({ name: "n", skills: ["alpha"] }, skills as any, "http://h/")
  assert.deepEqual(filtered.skills!.map((s: any) => s.id), ["alpha"])
  const all = buildAgentCard({}, skills as any, "http://h/")
  assert.deepEqual(all.skills!.map((s: any) => s.id).sort(), ["alpha", "beta"])
  assert.equal(all.name, "toolnexus-agent")
  assert.equal(all.capabilities!.streaming, false)
  assert.equal(all.protocolVersion, "0.3.0")
})

test("a2a inbound: top-level a2a config block is picked up by serve", async () => {
  const { server: llm, baseUrl } = await startMockLLM(cannedCompletion("ok"))
  const client = createClient({ baseUrl, style: "openai", model: "x", apiKey: "k" })
  const tk = await createToolkit({ mcpConfig: { a2a: { name: "from-config" } }, skillsDir: SKILLS_DIR, builtins: false })
  const srv = await tk.serve("127.0.0.1:0", { client }) // no inline a2a → falls back to config
  try {
    const card = await (await fetch(srv.url + "/.well-known/agent-card.json")).json()
    assert.equal(card.name, "from-config")
  } finally {
    await srv.stop()
    await tk.close()
    llm.close()
  }
})
