/**
 * Unit/integration tests (no network, no LLM). Run:
 *   npm run build && node --experimental-strip-types --test test/unit.test.ts
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
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
} from "../dist/index.js"

const SKILLS_DIR = path.resolve(fileURLToPath(import.meta.url), "../../../examples/skills")

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
  const tk = await createToolkit({})
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
