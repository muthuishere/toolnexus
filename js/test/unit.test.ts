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
  listSkills,
  defineTool,
  httpTool,
  toOpenAI,
  toAnthropic,
  toGemini,
  createToolkit,
  createClient,
  pending,
  elicitationToRequest,
  answerToElicitResult,
  type ConversationStore,
  createBuiltinTools,
  builtinsEnabled,
  agent,
  parseAgentsConfig,
  SKILLS_PROMPT_PREAMBLE,
  InMemoryTaskStore,
  InMemoryConversationStore,
  FileTaskStore,
  resolveStore,
  buildAgentCard,
  buildMcpServer,
  loadMcp,
  listMcpTools,
  exposedMcpTools,
} from "../dist/index.js"
import { Client as MCPClient } from "@modelcontextprotocol/sdk/client/index.js"
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js"
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js"

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

// --- extend-skill-source: S1–S5 (SPEC.md §3) ---

test("S4: on-disk hello-world skill block is byte-identical (regression baseline)", async () => {
  // The exact bytes the fs path must never move. If this fails, S1/S4 perturbed
  // the on-disk branch — the whole point of the parity contract.
  const dir = path.join(SKILLS_DIR, "hello-world")
  const base = new URL(`file://${dir}`).href
  const src = loadSkills(SKILLS_DIR)
  const res = await src.tool.execute({ name: "hello-world" })
  const content = src.skills["hello-world"].content.trim()
  const expected = [
    `<skill_content name="hello-world">`,
    `# Skill: hello-world`,
    "",
    content,
    "",
    `Base directory for this skill: ${base}`,
    "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
    "Note: file list is sampled.",
    "",
    "<skill_files>",
    `<file>${path.join(dir, "scripts", "greet.sh")}</file>`,
    "</skill_files>",
    "</skill_content>",
  ].join("\n")
  assert.equal(res.output, expected)
})

test("S1: skills supplied as data (no dir), logical base + resources", async () => {
  const src = loadSkills({
    skills: [{ name: "x", description: "d", content: "body", resources: ["scripts/foo.sh"] }],
  })
  assert.ok(src.skills["x"], "data skill discovered")
  assert.match(src.prompt(), /\*\*x\*\*: d/)
  const res = await src.tool.execute({ name: "x" })
  assert.equal(res.isError, false)
  assert.match(res.output, /Base directory for this skill: skill:\/\/x\//)
  assert.match(res.output, /<file>scripts\/foo\.sh<\/file>/)
  assert.doesNotMatch(res.output, /file:\/\//, "no absolute host path leaks")
})

test("S1: instruction-only data skill has no <skill_files>", async () => {
  const src = loadSkills({ skills: [{ name: "y", description: "d", content: "just text" }] })
  const res = await src.tool.execute({ name: "y" })
  assert.doesNotMatch(res.output, /<skill_files>/)
  assert.match(res.output, /just text/)
})

test("S1: provider + data compose; provider failure is isolated (toolkit)", async () => {
  const tk = await createToolkit({
    skillsDir: SKILLS_DIR,
    skillProvider: () => {
      throw new Error("boom")
    },
  })
  // Provider threw, but the directory skill still loaded.
  assert.match(tk.skillsPrompt(), /hello-world/)
})

test("S1: dir + data first-wins dedupe (dir wins)", async () => {
  const src = loadSkills({
    dirs: SKILLS_DIR,
    skills: [{ name: "hello-world", description: "shadow", content: "shadow body" }],
  })
  const res = await src.tool.execute({ name: "hello-world" })
  // dir came first → its file:// output wins, the data shadow is dropped
  assert.match(res.output, /file:\/\//)
  assert.doesNotMatch(res.output, /shadow body/)
})

test("S2: allowlist exposes only enabled skills", async () => {
  const defs = [
    { name: "a", description: "A", content: "a" },
    { name: "b", description: "B", content: "b" },
    { name: "c", description: "C", content: "c" },
  ]
  const src = loadSkills({ skills: defs, filter: { a: true, b: true } })
  assert.deepEqual(Object.keys(src.skills).sort(), ["a", "b"])
  const miss = await src.tool.execute({ name: "c" })
  assert.equal(miss.isError, true)
})

test("S2: drop-list removes named skills; unknown ignored; nil/empty ⇒ all", async () => {
  const defs = [
    { name: "a", description: "A", content: "a" },
    { name: "b", description: "B", content: "b" },
    { name: "c", description: "C", content: "c" },
  ]
  assert.deepEqual(Object.keys(loadSkills({ skills: defs, filter: { c: false } }).skills).sort(), ["a", "b"])
  assert.deepEqual(Object.keys(loadSkills({ skills: defs, filter: { a: true, nope: true } }).skills).sort(), ["a"])
  assert.deepEqual(Object.keys(loadSkills({ skills: defs, filter: {} }).skills).sort(), ["a", "b", "c"])
  assert.deepEqual(Object.keys(loadSkills({ skills: defs }).skills).sort(), ["a", "b", "c"])
})

test("S3: listSkills reports parsed skills + typed skip reasons", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "tn-skills-"))
  const mk = (name: string, body: string) => {
    const d = path.join(root, name)
    fs.mkdirSync(d, { recursive: true })
    fs.writeFileSync(path.join(d, "SKILL.md"), body)
  }
  mk("good", "---\nname: good\ndescription: ok\n---\nbody")
  mk("noname", "---\ndescription: no name here\n---\nbody")
  mk("bad", "---\nname: [unclosed\n---\nbody")
  const inv = listSkills({
    dirs: root,
    skills: [{ name: "good", description: "dup", content: "dup" }], // duplicate of dir's good
  })
  assert.deepEqual(inv.skills.map((s) => s.name), ["good"])
  const reasons = inv.skipped.map((s) => s.reason).sort()
  assert.deepEqual(reasons, ["duplicate-name", "malformed-frontmatter", "missing-name"])
  fs.rmSync(root, { recursive: true, force: true })
})

test("S5: sample cap — n caps, -1 omits the block, 0 is default", async () => {
  const defs = [{ name: "z", content: "z", resources: ["one", "two", "three"] }]
  const capped = await loadSkills({ skills: defs, sampleLimit: 2 }).tool.execute({ name: "z" })
  assert.match(capped.output, /<file>one<\/file>\n<file>two<\/file>/)
  assert.doesNotMatch(capped.output, /three/)
  const off = await loadSkills({ skills: defs, sampleLimit: -1 }).tool.execute({ name: "z" })
  assert.doesNotMatch(off.output, /<skill_files>/)
  assert.doesNotMatch(off.output, /Note: file list is sampled/)
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

test("resilience: onError 'fail' surfaces a normally-retryable 429 without retrying", async () => {
  let hits = 0
  const server = http.createServer((req, res) => { hits++; res.writeHead(429); res.end("slow down") })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  const client = createClient({
    baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k",
    retries: 5, retryBaseMs: 5, onError: () => "fail",
  })
  await assert.rejects(() => client.run("hi", { toolkit: tk }), /LLM 429/)
  assert.equal(hits, 1, "onError:fail skipped all retries")
  await tk.close(); server.close()
})

test("resilience: onError 'retry' retries a normally-terminal 400 within budget", async () => {
  let hits = 0
  const server = http.createServer((req, res) => { hits++; res.writeHead(400); res.end("bad") })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  const client = createClient({
    baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k",
    retries: 2, retryBaseMs: 5, onError: () => "retry",
  })
  await assert.rejects(() => client.run("hi", { toolkit: tk }), /LLM 400/)
  assert.equal(hits, 3, "onError:retry retried the 400 up to the budget (1 + 2 retries)")
  await tk.close(); server.close()
})

test("resilience: default (no onError) is unchanged — 429 retried, 400 failed", async () => {
  let a = 0
  const s1 = http.createServer((req, res) => { a++; if (a < 2) { res.writeHead(429); res.end("x") } else { res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify({ choices: [{ message: { content: "ok" } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })) } })
  await new Promise<void>((r) => s1.listen(0, r))
  const p1 = (s1.address() as any).port
  const tk = await createToolkit({ builtins: false })
  const c1 = createClient({ baseUrl: `http://127.0.0.1:${p1}`, style: "openai", model: "x", apiKey: "k", retries: 3, retryBaseMs: 5 })
  assert.equal((await c1.run("hi", { toolkit: tk })).text, "ok")
  assert.equal(a, 2, "429 retried by default")

  let b = 0
  const s2 = http.createServer((req, res) => { b++; res.writeHead(400); res.end("bad") })
  await new Promise<void>((r) => s2.listen(0, r))
  const p2 = (s2.address() as any).port
  const c2 = createClient({ baseUrl: `http://127.0.0.1:${p2}`, style: "openai", model: "x", apiKey: "k", retries: 3, retryBaseMs: 5 })
  await assert.rejects(() => c2.run("hi", { toolkit: tk }), /LLM 400/)
  assert.equal(b, 1, "400 failed immediately by default")
  await tk.close(); s1.close(); s2.close()
})

test("client: ask remembers by id via the conversation store", async () => {
  // mock LLM whose reply is the number of messages it received → proves history is loaded
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      const msg = JSON.parse(body)
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({
        choices: [{ message: { content: String(msg.messages.length) } }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }))
    })
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k" })

  const solo = await client.ask("hi", { toolkit: tk })
  assert.equal(solo.text, "1", "no id ⇒ stateless one-shot (just the user turn)")

  const a = await client.ask("first", { toolkit: tk, id: "c1" })
  assert.equal(a.text, "1", "first turn: 1 message")
  const b = await client.ask("second", { toolkit: tk, id: "c1" })
  assert.equal(b.text, "3", "same id remembers: user+assistant+user = 3")

  const c = await client.ask("other", { toolkit: tk, id: "c2" })
  assert.equal(c.text, "1", "a different id is an independent conversation")

  await tk.close()
  server.close()
})

test("client: custom ConversationStore provider is used (get/save)", async () => {
  const calls: string[] = []
  const backing = new Map<string, any[]>()
  const store: ConversationStore = {
    async get(id) { calls.push(`get:${id}`); return backing.get(id) },
    async save(id, messages) { calls.push(`save:${id}`); backing.set(id, messages) },
  }
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ choices: [{ message: { content: "ok" } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k", store })
  await client.ask("hi", { toolkit: tk, id: "u1" })
  assert.deepEqual(calls, ["get:u1", "save:u1"], "custom store: get then save")
  assert.ok(backing.has("u1"), "custom store persisted the transcript")
  await tk.close()
  server.close()
})

test("a2a serve: remembers a peer's turns by contextId (ask + store)", async () => {
  // mock LLM whose reply is the number of messages it received → proves memory
  const llm = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      const msg = JSON.parse(body)
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ choices: [{ message: { content: String(msg.messages.length) } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
    })
  })
  await new Promise<void>((r) => llm.listen(0, r))
  const llmPort = (llm.address() as any).port
  // no skills ⇒ no system-prompt message, so counts are exactly the turns
  const tk = await createToolkit({ builtins: false })
  const client = createClient({ baseUrl: `http://127.0.0.1:${llmPort}`, style: "openai", model: "x", apiKey: "k" })
  const handle = await tk.serve("127.0.0.1:0", { client, a2a: { name: "mem-desk" } })

  const rpc = async (method: string, params: any) => {
    const r = await fetch(handle.url + "/", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }) })
    return ((await r.json()) as any).result
  }
  const send = async (text: string, contextId: string) => {
    const task = await rpc("SendMessage", { message: { role: "user", contextId, parts: [{ kind: "text", text }] } })
    for (let i = 0; i < 100; i++) {
      const t = await rpc("GetTask", { id: task.id })
      if (t.status.state === "completed" || t.status.state === "failed") return t
      await new Promise((r) => setTimeout(r, 10))
    }
    throw new Error("timeout")
  }

  const t1 = await send("first", "ctxA")
  assert.equal(t1.artifacts[0].parts[0].text, "1", "first served turn: 1 message")
  const t2 = await send("second", "ctxA")
  assert.equal(t2.artifacts[0].parts[0].text, "3", "same contextId remembers: user+assistant+user = 3")
  const t3 = await send("other", "ctxB")
  assert.equal(t3.artifacts[0].parts[0].text, "1", "a different contextId is independent")

  await handle.stop()
  await tk.close()
  llm.close()
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

test("client: stream({ id }) remembers the transcript across streams", async () => {
  // mock streaming LLM whose text = number of messages it received → proves history is loaded
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      const msg = JSON.parse(body)
      const n = String(msg.messages.length)
      res.writeHead(200, { "content-type": "text/event-stream" })
      res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: n } }] })}\n\n`)
      res.write(`data: ${JSON.stringify({ choices: [{ delta: {} }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })}\n\n`)
      res.write("data: [DONE]\n\n")
      res.end()
    })
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false }) // no skills ⇒ no system message; count = turns
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k" })

  const consume = async (prompt: string, id?: string) => {
    let text = ""
    let done: any
    for await (const ev of client.stream(prompt, { toolkit: tk, id })) {
      if (ev.type === "text") text += ev.delta
      if (ev.type === "done") done = ev.result
    }
    return { text, done }
  }

  const solo = await consume("x")
  assert.equal(solo.text, "1", "no id ⇒ stateless (just the user turn)")

  const first = await consume("first", "s1")
  assert.equal(first.text, "1", "first stream: 1 message")
  const second = await consume("second", "s1")
  assert.equal(second.text, "3", "same id remembers: user+assistant+user = 3")
  assert.equal(second.done.messages.length, 4, "transcript saved on done (now 4 messages)")

  const other = await consume("other", "s2")
  assert.equal(other.text, "1", "a different id is independent")

  await tk.close()
  server.close()
})

test("client: ask({ on_text }) streams deltas AND returns the full RunResult", async () => {
  // one server: SSE when stream:true, plain JSON otherwise
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      const msg = JSON.parse(body)
      if (msg.stream) {
        res.writeHead(200, { "content-type": "text/event-stream" })
        res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: "Hel" } }] })}\n\n`)
        res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: "lo" } }] })}\n\n`)
        res.write(`data: ${JSON.stringify({ choices: [{ delta: {} }], usage: { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 } })}\n\n`)
        res.write("data: [DONE]\n\n")
        res.end()
      } else {
        res.writeHead(200, { "content-type": "application/json" })
        res.end(JSON.stringify({ choices: [{ message: { content: "Hello" } }], usage: { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 } }))
      }
    })
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k" })

  const deltas: string[] = []
  const streamed = await client.ask("hi", { toolkit: tk, on_text: (d) => deltas.push(d) })
  assert.deepEqual(deltas, ["Hel", "lo"], "on_text receives each text delta")
  assert.equal(streamed.text, "Hello", "ask still returns the full RunResult")
  assert.equal(streamed.usage.totalTokens, 5)

  const plain = await client.ask("hi", { toolkit: tk })
  assert.equal(plain.text, "Hello", "without on_text: non-streaming path still works")

  await tk.close()
  server.close()
})

test("client: onMetric fires llm, tool, and a final aggregated run event", async () => {
  // first request (no tool result yet) → a tool call; second → final text
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      const msg = JSON.parse(body)
      const hasToolResult = msg.messages.some((m: any) => m.role === "tool")
      res.writeHead(200, { "content-type": "application/json" })
      if (!hasToolResult) {
        res.end(JSON.stringify({ choices: [{ message: { content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "add", arguments: JSON.stringify({ a: 2, b: 3 }) } }] } }], usage: { prompt_tokens: 5, completion_tokens: 4, total_tokens: 9 } }))
      } else {
        res.end(JSON.stringify({ choices: [{ message: { content: "5" } }], usage: { prompt_tokens: 6, completion_tokens: 1, total_tokens: 7 } }))
      }
    })
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  tk.register(defineTool({ name: "add", description: "add", inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] }, run: ({ a, b }) => `${(a as number) + (b as number)}` }))
  const events: any[] = []
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "gpt-x", apiKey: "k", onMetric: (ev) => events.push(ev) })
  await client.run("add them", { toolkit: tk })

  const llm = events.find((e) => e.event === "llm")
  assert.ok(llm, "an llm event fired")
  assert.equal(llm.model, "gpt-x")
  assert.equal(llm.status, "ok")
  assert.equal(typeof llm.ms, "number")

  const tool = events.find((e) => e.event === "tool")
  assert.ok(tool, "a tool event fired")
  assert.equal(tool.tool, "add")
  assert.equal(tool.source, "native")
  assert.equal(tool.isError, false)

  const run = events[events.length - 1]
  assert.equal(run.event, "run", "run event is last")
  assert.equal(run.model, "gpt-x")
  assert.equal(run.toolCalls, 1, "one tool call aggregated")
  assert.equal(run.turns, 2, "two LLM round trips")
  assert.equal(run.totalTokens, 16, "9 + 7 summed")
  assert.equal(events.filter((e) => e.event === "llm").length, 2, "one llm event per call")

  await tk.close()
  server.close()
})

test("client: metrics() renders well-formed Prometheus text after a run", async () => {
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ choices: [{ message: { content: "ok" } }], usage: { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 } }))
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "gpt-x", apiKey: "k" })

  const before = client.metrics()
  assert.match(before, /# TYPE toolnexus_llm_requests_total counter/, "empty-but-valid before activity")

  await client.run("hi", { toolkit: tk })
  const text = client.metrics()

  // counters present with labels
  assert.match(text, /toolnexus_llm_requests_total\{model="gpt-x",status="ok"\} 1/)
  assert.match(text, /toolnexus_llm_tokens_total\{type="prompt"\} 3/)
  assert.match(text, /toolnexus_llm_tokens_total\{type="completion"\} 2/)
  // histogram: HELP/TYPE + _bucket / _sum / _count
  assert.match(text, /# TYPE toolnexus_llm_request_duration_seconds histogram/)
  assert.match(text, /toolnexus_llm_request_duration_seconds_bucket\{model="gpt-x",le="0.05"\} \d+/)
  assert.match(text, /toolnexus_llm_request_duration_seconds_bucket\{model="gpt-x",le="\+Inf"\} \d+/)
  assert.match(text, /toolnexus_llm_request_duration_seconds_sum\{model="gpt-x"\} /)
  assert.match(text, /toolnexus_llm_request_duration_seconds_count\{model="gpt-x"\} 1/)
  // every line is either a comment or a metric sample
  for (const line of text.split("\n").filter(Boolean)) {
    assert.ok(/^#/.test(line) || /^[a-zA-Z_][a-zA-Z0-9_]*(\{[^}]*\})? -?[0-9.eE+]+$/.test(line), `well-formed line: ${line}`)
  }

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

test("builtin question: suspends via a kind:\"question\" Request, resolves to the answer", async () => {
  const questions = [
    { question: "Pick a color?", header: "Choice", options: ["red", "green"], multiple: false },
    { question: "Confirm?" },
  ]
  // A — first call suspends (§10), it does NOT answer immediately.
  const q = await tool("question").execute({ questions })
  const req = (q.metadata as any)?.pending
  assert.ok(req, "returns a suspension carrying metadata.pending")
  assert.equal(q.isError, true, "a suspension is not a usable answer")
  assert.equal(req.kind, "question")
  assert.equal(req.prompt, "Pick a color? (options: red, green)\nConfirm?", "byte-exact rendered prompt")
  assert.deepEqual(req.data.questions, questions, "structured questions ride in data.questions")

  // B — re-executed after waitFor resolved: the answer is forwarded verbatim.
  const answered = await tool("question").execute({ questions }, { answer: { id: req.id, ok: true, data: { answers: ["red"] } } })
  assert.equal(answered.isError, false)
  assert.deepEqual(JSON.parse(answered.output), { answers: ["red"] })
  // ok-but-empty resolution → "{}" (agnostic passthrough).
  const empty = await tool("question").execute({ questions }, { answer: { id: req.id, ok: true } })
  assert.equal(JSON.parse(empty.output) && Object.keys(JSON.parse(empty.output)).length, 0)
})

test("builtin todowrite: structured round-trip", async () => {
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

test("client: a question suspension with no waitFor halts the run (status pending)", async () => {
  // Model calls the `question` builtin; with no waitFor configured the loop halts durably (§10).
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ choices: [{ message: { content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "question", arguments: JSON.stringify({ questions: [{ question: "Pick a color?", options: ["red", "green"] }] }) } }] } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({}) // builtins on → `question` exists
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k" }) // no waitFor
  try {
    const res = await client.run("ask me", { toolkit: tk })
    assert.equal(res.status, "pending", "no waitFor ⇒ the run halts pending, does not loop forever")
    assert.equal(res.pending?.kind, "question")
    assert.equal(res.pending?.prompt, "Pick a color? (options: red, green)")
  } finally {
    await tk.close()
    server.close()
  }
})

test("client: a suspension is pending, not a tool error; afterTool sees only the resolved result (G2)", async () => {
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      const msg = JSON.parse(body)
      const hasTool = msg.messages.some((m: any) => m.role === "tool")
      res.writeHead(200, { "content-type": "application/json" })
      if (!hasTool) res.end(JSON.stringify({ choices: [{ message: { content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "question", arguments: JSON.stringify({ questions: [{ question: "Pick?", options: ["a", "b"] }] }) } }] } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
      else res.end(JSON.stringify({ choices: [{ message: { content: "done" } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
    })
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({})
  const events: any[] = []
  const afterToolSaw: any[] = []
  const client = createClient({
    baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k",
    waitFor: async (r) => ({ id: r.id, ok: true, data: { answers: ["a"] } }),
    hooks: { afterTool: ({ result }) => { afterToolSaw.push(result) } },
    onMetric: (ev) => events.push(ev),
  })
  try {
    const res = await client.run("ask", { toolkit: tk })
    assert.equal(res.status, "done", "waitFor resolved the suspension and the run finished")
    const toolEvents = events.filter((e) => e.event === "tool" && e.tool === "question")
    const suspend = toolEvents.find((e) => e.pending)
    assert.ok(suspend, "the suspension emitted a tool event tagged pending")
    assert.equal(suspend.isError, false, "a suspension is not a tool error")
    assert.equal(afterToolSaw.length, 1, "afterTool ran exactly once — not on the suspension")
    assert.ok(!(afterToolSaw[0].metadata as any)?.pending, "afterTool saw the resolved result, never the suspension")
  } finally {
    await tk.close()
    server.close()
  }
})

test("client: a beforeTool guard-raised pending is honored, counted pending not error (path B)", async () => {
  const server = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      const msg = JSON.parse(body)
      const hasTool = msg.messages.some((m: any) => m.role === "tool")
      res.writeHead(200, { "content-type": "application/json" })
      if (!hasTool) res.end(JSON.stringify({ choices: [{ message: { content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "add", arguments: JSON.stringify({ a: 1, b: 2 }) } }] } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
      else res.end(JSON.stringify({ choices: [{ message: { content: "3" } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
    })
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({ builtins: false })
  tk.register(defineTool({ name: "add", description: "add", inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] }, run: ({ a, b }) => `${(a as number) + (b as number)}` }))
  const events: any[] = []
  let asked = false
  const client = createClient({
    baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k",
    // guard raises a suspension with NO tool code running (path B)
    hooks: { beforeTool: ({ name }) => (name === "add" && !asked ? { result: pending({ kind: "approval", prompt: "approve add?" }) } : undefined) },
    waitFor: async (r) => { asked = true; return { id: r.id, ok: true } },
    onMetric: (ev) => events.push(ev),
  })
  try {
    const res = await client.run("add them", { toolkit: tk })
    assert.equal(res.status, "done", "on approval the real tool ran and the run finished")
    const toolEvents = events.filter((e) => e.event === "tool" && e.tool === "add")
    const suspend = toolEvents.find((e) => e.pending)
    assert.ok(suspend, "the guard-raised pending emitted a tool event tagged pending")
    assert.equal(suspend.isError, false, "a guard-raised suspension is not a tool error")
    assert.ok(res.toolCalls.some((t) => t.name === "add" && t.output === "3"), "the real tool ran after approval")
  } finally {
    await tk.close()
    server.close()
  }
})

test("client: two concurrent suspensions with no waitFor surface the first, not the last (G3)", async () => {
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ choices: [{ message: { content: null, tool_calls: [
      { id: "c1", type: "function", function: { name: "question", arguments: JSON.stringify({ questions: [{ question: "First?" }] }) } },
      { id: "c2", type: "function", function: { name: "question", arguments: JSON.stringify({ questions: [{ question: "Second?" }] }) } },
    ] } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({}) // no waitFor
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "x", apiKey: "k" })
  try {
    const res = await client.run("ask two", { toolkit: tk })
    assert.equal(res.status, "pending")
    assert.equal(res.pending?.prompt, "First?", "the FIRST suspension in call order is surfaced, deterministically")
    const c2msg = res.messages.find((m: any) => m.tool_call_id === "c2")
    assert.ok(!c2msg, "the second concurrent suspension does not pollute the transcript")
  } finally {
    await tk.close()
    server.close()
  }
})

test("client: G3 also holds on the anthropic non-streaming path (first suspension, no leak)", async () => {
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ content: [
      { type: "tool_use", id: "t1", name: "question", input: { questions: [{ question: "First?" }] } },
      { type: "tool_use", id: "t2", name: "question", input: { questions: [{ question: "Second?" }] } },
    ], stop_reason: "tool_use", usage: { input_tokens: 10, output_tokens: 5 } }))
  })
  await new Promise<void>((r) => server.listen(0, r))
  const port = (server.address() as any).port
  const tk = await createToolkit({}) // no waitFor
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "anthropic", model: "claude-x", apiKey: "k" })
  try {
    const res = await client.run("ask two", { toolkit: tk })
    assert.equal(res.status, "pending")
    assert.equal(res.pending?.prompt, "First?", "first tool_use in order is surfaced")
    const userMsg = res.messages.filter((m: any) => m.role === "user" && Array.isArray(m.content)).at(-1)
    const ids = (userMsg?.content ?? []).map((b: any) => b.tool_use_id)
    assert.ok(ids.includes("t1") && !ids.includes("t2"), "only the first tool_result is recorded, no second-suspension leak")
  } finally {
    await tk.close()
    server.close()
  }
})

test("mcp elicitation ⇄ §10 mapping (form→input, url→authorization, accept/decline/cancel)", () => {
  const schema = { type: "object", properties: { name: { type: "string" } }, required: ["name"] }
  // form mode → kind:"input" carrying the schema in data.schema
  const req = elicitationToRequest({ message: "Your name?", requestedSchema: schema } as any)
  assert.equal(req.kind, "input")
  assert.equal(req.prompt, "Your name?")
  assert.deepEqual(req.data?.schema, schema)
  assert.equal(req.url, undefined)
  // URL mode → kind:"authorization" carrying the url, no schema
  const ureq = elicitationToRequest({ mode: "url", message: "Log in", url: "https://x/auth" } as any)
  assert.equal(ureq.kind, "authorization")
  assert.equal(ureq.url, "https://x/auth")
  assert.equal(ureq.data, undefined)
  // Answer → ElicitResult
  assert.deepEqual(answerToElicitResult({ id: "1", ok: true, data: { name: "Ada" } }), { action: "accept", content: { name: "Ada" } })
  assert.deepEqual(answerToElicitResult({ id: "1", ok: true }), { action: "accept", content: {} })
  assert.deepEqual(answerToElicitResult({ id: "1", ok: false, reason: "declined" }), { action: "decline" })
  assert.deepEqual(answerToElicitResult({ id: "1", ok: false }), { action: "cancel" })
  assert.deepEqual(answerToElicitResult({ id: "1", ok: false, reason: "expired" }), { action: "cancel" })
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

test("frontmatter: YAML folded (>) and literal (|) block scalar descriptions", () => {
  const root = tmp()
  const folded = path.join(root, "folded")
  fs.mkdirSync(folded, { recursive: true })
  fs.writeFileSync(
    path.join(folded, "SKILL.md"),
    "---\nname: folded-skill\ndescription: >\n  Runs a repo-aware expert huddle. Trigger when the user says\n  \"start a huddle\" or \"war room\".\n---\nbody\n",
  )
  const literal = path.join(root, "literal")
  fs.mkdirSync(literal, { recursive: true })
  fs.writeFileSync(
    path.join(literal, "SKILL.md"),
    "---\nname: literal-skill\ndescription: |\n  line one\n  line two\n---\nbody\n",
  )

  const src = loadSkills(root)
  // folded: newlines collapse to spaces — NOT the literal ">"
  assert.equal(
    src.skills["folded-skill"].description,
    'Runs a repo-aware expert huddle. Trigger when the user says "start a huddle" or "war room".',
  )
  assert.notEqual(src.skills["folded-skill"].description, ">")
  // literal: newlines preserved
  assert.equal(src.skills["literal-skill"].description, "line one\nline two")
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

test("a2a inbound: a suspended run surfaces input-required, not a false completed (G1)", async () => {
  // Model calls `question`; with no waitFor the run halts pending — serve must NOT report completed.
  const { server: llm, baseUrl } = await startMockLLM((_req, res) => {
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ choices: [{ message: { content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "question", arguments: JSON.stringify({ questions: [{ question: "Pick a color?", options: ["red", "green"] }] }) } }] } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
  })
  const client = createClient({ baseUrl, style: "openai", model: "x", apiKey: "k" }) // no waitFor
  const tk = await createToolkit({}) // builtins on → `question` exists
  const events: any[] = []
  const srv = await tk.serve("127.0.0.1:0", { client, a2a: { name: "ask-desk" }, onTask: (ev) => events.push(ev) })
  const endpoint = srv.url + "/"
  try {
    const sent = await rpc(endpoint, "SendMessage", textMessage("go"))
    const id = sent.result.id
    let task: any
    for (let i = 0; i < 100; i++) {
      const env = await rpc(endpoint, "GetTask", { id })
      const state = env.result?.status?.state
      if (state && state !== "submitted" && state !== "working") { task = env.result; break }
      await new Promise((r) => setTimeout(r, 10))
    }
    assert.equal(task.status.state, "input-required", "suspended run → input-required, never completed")
    assert.match(task.status.message.parts[0].text, /Pick a color\? \(options: red, green\)/, "prompt carried in the status message")
    assert.ok(!task.artifacts || task.artifacts.length === 0, "no artifact passing the prompt off as a real answer")
    assert.equal(events.at(-1)?.state, "input-required", "onTask surfaced input-required")
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

// ---------------------------------------------------------------------------
// MCP inbound (§7C) — serve a toolkit as an MCP server.
// ---------------------------------------------------------------------------

const echoTool = defineTool({
  name: "echo",
  description: "echo back text",
  inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"], additionalProperties: false },
  run: (args: any) => String(args.text ?? ""),
})
const boomTool = defineTool({
  name: "boom",
  description: "always throws",
  run: () => {
    throw new Error("kaboom")
  },
})

/** Connect an in-process MCP client to a built server via a linked transport pair. */
async function connectInProc(server: any): Promise<any> {
  const [clientT, serverT] = InMemoryTransport.createLinkedPair()
  await server.connect(serverT)
  const client = new MCPClient({ name: "test-client", version: "1.0.0" })
  await client.connect(clientT)
  return client
}

test("mcp inbound: exposedMcpTools filters by name, omit ⇒ all", () => {
  const tools = [echoTool, boomTool]
  assert.deepEqual(exposedMcpTools(tools).map((t) => t.name), ["echo", "boom"])
  assert.deepEqual(exposedMcpTools(tools, { tools: ["echo"] }).map((t) => t.name), ["echo"])
  // unknown names in the filter are ignored, not an error
  assert.deepEqual(exposedMcpTools(tools, { tools: ["echo", "nope"] }).map((t) => t.name), ["echo"])
})

test("mcp inbound: initialize reports serverInfo from the profile", async () => {
  const server = buildMcpServer([echoTool], { name: "gateway", version: "2.0.0" })
  const client = await connectInProc(server)
  try {
    const info = client.getServerVersion()
    assert.equal(info?.name, "gateway")
    assert.equal(info?.version, "2.0.0")
  } finally {
    await client.close()
  }
})

test("mcp inbound: tools/list advertises unified tools with inputSchema=parameters", async () => {
  const server = buildMcpServer([echoTool, boomTool])
  const client = await connectInProc(server)
  try {
    const { tools } = await client.listTools()
    assert.deepEqual(tools.map((t: any) => t.name).sort(), ["boom", "echo"])
    const echo = tools.find((t: any) => t.name === "echo")
    assert.deepEqual(echo.inputSchema, echoTool.inputSchema)
  } finally {
    await client.close()
  }
})

test("mcp inbound: mcp.tools narrows the advertised surface", async () => {
  const server = buildMcpServer(exposedMcpTools([echoTool, boomTool], { tools: ["echo"] }), { tools: ["echo"] })
  const client = await connectInProc(server)
  try {
    const { tools } = await client.listTools()
    assert.deepEqual(tools.map((t: any) => t.name), ["echo"])
  } finally {
    await client.close()
  }
})

test("mcp inbound: tools/call dispatches to execute → text content, isError false", async () => {
  const calls: any[] = []
  const server = buildMcpServer([echoTool], undefined, (ev) => calls.push(ev))
  const client = await connectInProc(server)
  try {
    const res: any = await client.callTool({ name: "echo", arguments: { text: "hi" } })
    assert.equal(res.isError, false)
    assert.equal(res.content[0].type, "text")
    assert.equal(res.content[0].text, "hi")
    assert.equal(calls.length, 1)
    assert.equal(calls[0].name, "echo")
    assert.equal(calls[0].source, "native")
    assert.equal(calls[0].isError, false)
  } finally {
    await client.close()
  }
})

test("mcp inbound: erroring tool → isError result, server survives", async () => {
  const server = buildMcpServer([echoTool, boomTool])
  const client = await connectInProc(server)
  try {
    const bad: any = await client.callTool({ name: "boom", arguments: {} })
    assert.equal(bad.isError, true)
    assert.match(bad.content[0].text, /kaboom/)
    // server keeps serving
    const ok: any = await client.callTool({ name: "echo", arguments: { text: "still up" } })
    assert.equal(ok.isError, false)
    assert.equal(ok.content[0].text, "still up")
  } finally {
    await client.close()
  }
})

test("mcp inbound: streamable-HTTP /mcp round-trip via serve()", async () => {
  const tk = await createToolkit({ builtins: false, extraTools: [echoTool] })
  const srv = await tk.serve("127.0.0.1:0", { mcp: { name: "http-gw" } })
  const client = new MCPClient({ name: "http-test", version: "1.0.0" })
  try {
    await client.connect(new StreamableHTTPClientTransport(new URL(srv.url + "/mcp")))
    const info = client.getServerVersion()
    assert.equal(info?.name, "http-gw")
    const { tools } = await client.listTools()
    assert.ok(tools.some((t: any) => t.name === "echo"))
    const res: any = await client.callTool({ name: "echo", arguments: { text: "over http" } })
    assert.equal(res.content[0].text, "over http")
  } finally {
    await client.close()
    await srv.stop()
    await tk.close()
  }
})

test("mcp inbound: absent profile ⇒ no /mcp surface", async () => {
  const tk = await createToolkit({ builtins: false, extraTools: [echoTool] })
  const srv = await tk.serve("127.0.0.1:0", { mcp: undefined, a2a: { name: "only-a2a" } })
  try {
    const res = await fetch(srv.url + "/mcp", {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} }),
    })
    // A2A POST handler treats it as an unknown JSON-RPC method (not an MCP endpoint).
    const body: any = await res.json()
    assert.ok(body.error, "expected a JSON-RPC error, not an MCP tools/list result")
    assert.equal(body.result, undefined)
  } finally {
    await srv.stop()
    await tk.close()
  }
})

test("mcp inbound: top-level mcpServer config block is picked up by serve", async () => {
  const tk = await createToolkit({ mcpConfig: { mcpServer: { name: "from-config" } }, builtins: false, extraTools: [echoTool] })
  // serve() with no inline mcp falls back to the config block
  const srv = await tk.serve("127.0.0.1:0", {})
  const client = new MCPClient({ name: "cfg-test", version: "1.0.0" })
  try {
    await client.connect(new StreamableHTTPClientTransport(new URL(srv.url + "/mcp")))
    assert.equal(client.getServerVersion()?.name, "from-config")
  } finally {
    await client.close()
    await srv.stop()
    await tk.close()
  }
})

// --- implement-rag-go-consumer-needs: Cluster A (gaps 1,2,5) + gap 4 (§8) ---

// Capturing OpenAI-style server: records the last decoded request body, replies non-stream.
function captureOpenAI() {
  let lastBody = null
  const server = http.createServer((req, res) => {
    let b = ""
    req.on("data", (c) => (b += c))
    req.on("end", () => {
      lastBody = JSON.parse(b)
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ choices: [{ message: { content: "ok" } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }))
    })
  })
  return { server, body: () => lastBody }
}

test("gap1: requestParams merges into openai + anthropic bodies (caller wins)", async () => {
  const cap = captureOpenAI()
  await new Promise((r) => cap.server.listen(0, r))
  const port = cap.server.address().port
  const tk = await createToolkit({ builtins: false })

  const oa = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "m", apiKey: "k",
    requestParams: { temperature: 0.42, chat_template_kwargs: { enable_thinking: false } } })
  await oa.run("hi", { toolkit: tk })
  assert.equal(cap.body().temperature, 0.42)
  assert.deepEqual(cap.body().chat_template_kwargs, { enable_thinking: false })

  const an = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "anthropic", model: "m", apiKey: "k",
    requestParams: { max_tokens: 999 } })
  // anthropic path posts to /v1/messages; our server accepts any path
  await an.run("hi", { toolkit: tk }).catch(() => {})
  assert.equal(cap.body().max_tokens, 999, "requestParams max_tokens overrides the 4096 default")

  await tk.close(); cap.server.close()
})

test("gap1: bodyTransform runs last; forbidden keys stripped from requestParams", async () => {
  const cap = captureOpenAI()
  await new Promise((r) => cap.server.listen(0, r))
  const port = cap.server.address().port
  const tk = await createToolkit({ builtins: false })
  const c = createClient({
    baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "m", apiKey: "k",
    requestParams: { temperature: 0.5, messages: [{ role: "x" }], tools: [1], stream: true }, // forbidden ones ignored
    bodyTransform: (b) => { b.injected = "yes"; delete b.temperature; return b },
  })
  await c.run("hi", { toolkit: tk })
  const body = cap.body()
  assert.equal(body.injected, "yes", "bodyTransform output is sent")
  assert.equal("temperature" in body, false, "bodyTransform ran after merge and dropped the key")
  assert.equal(Array.isArray(body.messages) && body.messages.length >= 1 && body.messages[0].role === "user", true, "forbidden messages override ignored")
  assert.equal(body.stream, undefined, "forbidden stream override ignored (non-stream call)")
  await tk.close(); cap.server.close()
})

test("gap2: injectable fetch receives the LLM call", async () => {
  let seen = 0
  const myFetch = async (_url, _init) => {
    seen++
    return new Response(JSON.stringify({ choices: [{ message: { content: "ok" } }], usage: {} }), { status: 200, headers: { "content-type": "application/json" } })
  }
  const tk = await createToolkit({ builtins: false })
  const c = createClient({ baseUrl: "http://never.invalid", style: "openai", model: "m", apiKey: "k", fetch: myFetch })
  const res = await c.run("hi", { toolkit: tk })
  assert.equal(res.text, "ok")
  assert.ok(seen >= 1, "custom fetch was used (no real network hit)")
  await tk.close()
})

test("gap5: empty toolkit omits tools/tool_choice; non-empty keeps them", async () => {
  const cap = captureOpenAI()
  await new Promise((r) => cap.server.listen(0, r))
  const port = cap.server.address().port

  const empty = await createToolkit({ builtins: false })
  const c1 = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "m", apiKey: "k" })
  await c1.run("hi", { toolkit: empty })
  assert.equal("tools" in cap.body(), false, "no tools key when empty")
  assert.equal("tool_choice" in cap.body(), false, "no tool_choice key when empty")
  await empty.close()

  const one = await createToolkit({ builtins: false })
  one.register(defineTool({ name: "t", description: "d", run: () => "x" }))
  const c2 = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "m", apiKey: "k" })
  await c2.run("hi", { toolkit: one })
  assert.equal(Array.isArray(cap.body().tools), true, "tools present when non-empty")
  assert.equal(cap.body().tool_choice, "auto")
  await one.close(); cap.server.close()
})

test("gap5: anthropic empty toolkit omits tools", async () => {
  const cap = captureOpenAI()
  await new Promise((r) => cap.server.listen(0, r))
  const port = cap.server.address().port
  const tk = await createToolkit({ builtins: false })
  const c = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "anthropic", model: "m", apiKey: "k" })
  await c.run("hi", { toolkit: tk }).catch(() => {})
  assert.equal("tools" in cap.body(), false, "anthropic: no tools key when empty")
  await tk.close(); cap.server.close()
})

test("gap4: conversationStore() — identity + default reflects saved turns", async () => {
  const custom = new InMemoryConversationStore()
  const c1 = createClient({ baseUrl: "http://x", style: "openai", model: "m", store: custom })
  assert.equal(c1.conversationStore(), custom, "returns the supplied instance")

  const cap = captureOpenAI()
  await new Promise((r) => cap.server.listen(0, r))
  const port = cap.server.address().port
  const tk = await createToolkit({ builtins: false })
  const c2 = createClient({ baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "m", apiKey: "k" })
  assert.ok(c2.conversationStore(), "default store is non-nil")
  await c2.ask("hi", { toolkit: tk, id: "conv1" })
  const saved = await c2.conversationStore().get("conv1")
  assert.ok(saved && saved.length >= 2, "default store holds the saved transcript")
  await tk.close(); cap.server.close()
})

// --- implement-rag-go-consumer-needs: Cluster B (gaps 3,6,7 — MCP load) ---
// Spins a real MCP server over stdio as a child process (hermetic, no network).

function writeStdioServer(bodyJs) {
  // Written into js/ so node resolves ./dist + node_modules.
  const p = path.resolve(fileURLToPath(import.meta.url), "../../._mcpsrv_" + Math.random().toString(36).slice(2) + ".mjs")
  fs.writeFileSync(p, bodyJs)
  return p
}
const ABC_SERVER = `
import { buildMcpServer, defineTool } from "./dist/index.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
const tools = ["a","b","c"].map((n) => defineTool({ name: n, description: n, run: () => n }))
const server = buildMcpServer(tools, { name: "srv" })
await server.connect(new StdioServerTransport())
`
const HANG_LIST_SERVER = `
import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js"
const server = new Server({ name: "hang", version: "0" }, { capabilities: { tools: {} } })
server.setRequestHandler(ListToolsRequestSchema, () => new Promise(() => {})) // never resolves
await server.connect(new StdioServerTransport())
`

test("gap7: per-server tools allowlist filters MCP tools", async () => {
  const script = writeStdioServer(ABC_SERVER)
  try {
    const mcp = await loadMcp({ srv: { command: ["node", script], tools: { a: true, b: true } } })
    const names = mcp.tools.map((t) => t.name).sort()
    assert.deepEqual(names, ["srv_a", "srv_b"], "allowlist keeps only a,b (prefixed)")
    assert.equal(mcp.status.srv, "connected")
    await mcp.close()

    const drop = await loadMcp({ srv: { command: ["node", script], tools: { c: false } } })
    assert.deepEqual(drop.tools.map((t) => t.name).sort(), ["srv_a", "srv_b"], "drop-list removes c")
    await drop.close()

    const all = await loadMcp({ srv: { command: ["node", script] } })
    assert.deepEqual(all.tools.map((t) => t.name).sort(), ["srv_a", "srv_b", "srv_c"], "nil filter = all")
    await all.close()
  } finally {
    fs.rmSync(script, { force: true })
  }
})

test("gap6: listMcpTools returns unfiltered original names + status, disconnects", async () => {
  const script = writeStdioServer(ABC_SERVER)
  try {
    const inv = await listMcpTools({
      good: { command: ["node", script], tools: { a: true } }, // filter is IGNORED by inventory
      bad: { command: ["node", "/no/such/script/xyz.mjs"] },
    })
    assert.deepEqual(inv.tools.good.map((t) => t.name).sort(), ["a", "b", "c"], "inventory is unfiltered, original names")
    assert.equal(inv.status.good, "connected")
    assert.equal(inv.status.bad, "failed")
  } finally {
    fs.rmSync(script, { force: true })
  }
})

test("gap3: hanging list is bounded by the server timeout (server failed, not hung)", async () => {
  const script = writeStdioServer(HANG_LIST_SERVER)
  try {
    const t0 = Date.now()
    const mcp = await loadMcp({ hang: { command: ["node", script], timeout: 400 } })
    const dt = Date.now() - t0
    assert.equal(mcp.status.hang, "failed", "hanging server is isolated as failed")
    assert.ok(dt < 3000, `bounded by timeout, took ${dt}ms`)
    await mcp.close()
  } finally {
    fs.rmSync(script, { force: true })
  }
})

test("gap3: parent signal aborts the whole load promptly", async () => {
  const script = writeStdioServer(HANG_LIST_SERVER)
  const ctrl = new AbortController()
  setTimeout(() => ctrl.abort(), 150)
  try {
    await assert.rejects(
      loadMcp({ hang: { command: ["node", script], timeout: 60000 } }, { signal: ctrl.signal }),
      "parent-signal abort rejects the load rather than waiting the full timeout",
    )
  } finally {
    fs.rmSync(script, { force: true })
  }
})
