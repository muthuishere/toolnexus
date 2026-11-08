/**
 * MCP 2026-07-28 client (SPEC §2, §2A, §2.7, §2.8) behind loadMcp / Toolkit. Hermetic: every server
 * (2026 fixture over HTTP + stdio, a dev authorization server, an old-era SDK server) is in-process
 * or a local child, on 127.0.0.1. Expectations follow examples/mcp-2026/expected.json.
 */
import { test, type TestContext } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import { Readable } from "node:stream"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js"
import { loadMcp, listMcpTools, Toolkit, buildMcpServer, defineTool, MemoryTokenStore, Token, type Request, type Answer, type Tool } from "../dist/index.js"
import { fixtureServer, closeServer, type Server } from "./mcp2026/server.ts"
import { ServerAuth } from "./mcp2026/serverauth.ts"
import { DevAuthServer } from "./mcp2026/devauth.ts"

const here = path.dirname(fileURLToPath(import.meta.url))
const expected = JSON.parse(fs.readFileSync(path.join(here, "..", "..", "examples", "mcp-2026", "expected.json"), "utf8"))
const stdioScript = path.join(here, "mcp2026", "stdio-server.ts")
const stdioCmd = (...extra: string[]) => ["node", "--experimental-strip-types", "--no-warnings", stdioScript, ...extra]

interface Seen { method: string; headers: http.IncomingHttpHeaders }

/** Starts s behind a recorder of every request's method + headers. */
async function serve(t: TestContext, s: Server, wrap?: (req: http.IncomingMessage, res: http.ServerResponse, body: string, next: () => void) => void) {
  const seen: Seen[] = []
  const hs = http.createServer((req, res) => {
    const chunks: Buffer[] = []
    req.on("data", (c) => chunks.push(c))
    req.on("end", () => {
      const body = Buffer.concat(chunks).toString("utf8")
      let method = ""
      try {
        method = JSON.parse(body).method ?? ""
      } catch {}
      seen.push({ method, headers: req.headers })
      // re-feed the body to the fixture listener
      const replay = Object.assign(Readable.from([Buffer.from(body)]), { headers: req.headers, method: req.method, url: req.url, socket: req.socket })
      const next = () => s.listener(replay as any, res)
      if (wrap) wrap(req, res, body, next)
      else next()
    })
  })
  await new Promise<void>((r) => hs.listen(0, "127.0.0.1", () => r()))
  t.after(() => closeServer(hs))
  const url = `http://127.0.0.1:${(hs.address() as { port: number }).port}/mcp`
  return { url, seen }
}

const byName = (tools: Tool[], n: string) => {
  const t = tools.find((x) => x.name === n)
  assert.ok(t, `tool ${n} in ${tools.map((x) => x.name).join(",")}`)
  return t!
}

// ---------------------------------------------------------------------------
// 1. discover first, headers, tools, MRTR
// ---------------------------------------------------------------------------

test("mcp2026 http: discover first, no initialize, headers + _meta, tools/list naming + result mapping", async (t) => {
  const s = fixtureServer()
  s.tool("héllo wörld", "non-ASCII tool name", () => "ok")
  const { url, seen } = await serve(t, s)
  const src = await loadMcp({ fx: { url } })
  t.after(() => src.close())
  assert.equal(src.status.fx, "connected")
  assert.deepEqual(src.tools.map((x) => x.name).sort(), ["fx_echo", "fx_greet", "fx_h_llo_w_rld"])
  assert.equal(seen[0].method, "server/discover")
  assert.ok(!seen.some((x) => x.method === "initialize"), "no initialize on the 2026 path")
  const echo = byName(src.tools, "fx_echo")
  assert.equal(echo.description, "Echo the given text back.")
  assert.equal(echo.inputSchema.additionalProperties, false)
  const step = expected.steps.find((x: any) => x.id === "tools/call echo")
  const r = await echo.execute(step.request.params.arguments)
  assert.deepEqual(JSON.parse(r.output), step.expect.structuredContent)
  assert.equal(r.isError, step.expect.isError)
  const call = seen.find((x) => x.method === "tools/call")!
  assert.equal(call.headers["mcp-protocol-version"], expected.protocolVersion)
  assert.equal(call.headers["mcp-method"], "tools/call")
  assert.equal(call.headers["mcp-name"], "echo")
  assert.equal((await byName(src.tools, "fx_h_llo_w_rld").execute({})).output, "ok")
  const odd = seen.filter((x) => x.method === "tools/call").at(-1)!
  assert.equal(odd.headers["mcp-name"], "=?base64?" + Buffer.from("héllo wörld").toString("base64") + "?=")
})

test("mcp2026 http: SSE (text/event-stream) replies are accepted", async (t) => {
  const s = fixtureServer()
  const { url } = await serve(t, s, (_req, res, _body, next) => {
    const end = res.end.bind(res)
    const writeHead = res.writeHead.bind(res)
    ;(res as any).writeHead = (st: number, h: any) => writeHead(st, { ...h, "Content-Type": "text/event-stream" })
    ;(res as any).end = (chunk: any) => end(`event: message\ndata: ${String(chunk).trim()}\n\n`)
    next()
  })
  const src = await loadMcp({ fx: { url } })
  t.after(() => src.close())
  assert.equal((await byName(src.tools, "fx_echo").execute({ text: "sse" })).output, '{"text":"sse"}')
})

test("mcp2026 MRTR: input_required → waitFor (kind input + data.schema) → same tools/call re-sent once", async (t) => {
  const step = expected.steps.find((x: any) => x.inputRequest)
  const s = fixtureServer()
  const { url, seen } = await serve(t, s)
  const asked: Request[] = []
  const src = await loadMcp({ fx: { url } }, {
    waitFor: async (req) => (asked.push(req), { id: req.id, ok: true, data: step.answer.content }),
  })
  t.after(() => src.close())
  const r = await byName(src.tools, "fx_greet").execute({})
  assert.equal(asked.length, step.expect.asked)
  assert.equal(asked[0].kind, "input")
  assert.equal(asked[0].prompt, step.inputRequest.message)
  assert.ok((asked[0].data as any).schema.properties.name)
  assert.equal(JSON.parse(r.output).greeting, step.expect.text)
  assert.equal(seen.filter((x) => x.method === "tools/call").length, 2) // round 1 + the answered re-send
})

test("mcp2026 MRTR without waitFor: {pending} then resume via ctx.answer (not re-asked); decline maps to decline", async (t) => {
  const { url, seen } = await serve(t, fixtureServer())
  const src = await loadMcp({ fx: { url } })
  t.after(() => src.close())
  const greet = byName(src.tools, "fx_greet")
  const p = await greet.execute({})
  const req = (p.metadata as any)?.pending as Request
  assert.ok(req && req.kind === "input" && req.prompt === "What is your name?", JSON.stringify(p))
  assert.equal(p.isError, true)
  const r = await greet.execute({}, { answer: { id: req.id, ok: true, data: { name: "toolnexus" } } })
  assert.equal(JSON.parse(r.output).greeting, "Hello, toolnexus!")
  assert.equal(seen.filter((x) => x.method === "tools/call").length, 2)
  // declined: the fixture asks again ⇒ a fresh pending, never a hang
  const p2 = await greet.execute({})
  const r2 = await greet.execute({}, { answer: { id: ((p2.metadata as any).pending as Request).id, ok: false, reason: "declined" } })
  assert.ok((r2.metadata as any).pending, "server re-asks after decline")
})

test("mcp2026 via the client loop: pending halts the run, resumed answer completes it", async (t) => {
  const { url } = await serve(t, fixtureServer())
  const tk = await Toolkit.create({ mcpConfig: { fx: { url } }, builtins: false })
  t.after(() => tk.close())
  const r1 = await tk.execute("fx_greet", {})
  const req = (r1.metadata as any).pending as Request
  const r2 = await tk.execute("fx_greet", {}, { answer: { id: req.id, ok: true, data: { name: "loop" } } })
  assert.equal(JSON.parse(r2.output).greeting, "Hello, loop!")
})

// ---------------------------------------------------------------------------
// stdio
// ---------------------------------------------------------------------------

test("mcp2026 stdio: discover first, concurrent calls demuxed by id, MRTR", async (t) => {
  const src = await loadMcp({ fx: { command: stdioCmd("--slow-echo") } }, {
    waitFor: async (req) => ({ id: req.id, ok: true, data: { name: "stdio" } }),
  })
  t.after(() => src.close())
  assert.equal(src.status.fx, "connected")
  const [a, b, c] = await Promise.all([
    byName(src.tools, "fx_slow").execute({}),
    byName(src.tools, "fx_echo").execute({ text: "one" }),
    byName(src.tools, "fx_echo").execute({ text: "two" }),
  ])
  assert.deepEqual([a.output, b.output, c.output], ["slow", '{"text":"one"}', '{"text":"two"}'])
  assert.equal(JSON.parse((await byName(src.tools, "fx_greet").execute({})).output).greeting, "Hello, stdio!")
})

test("mcp2026 stdio: a dead child fails the pending call instead of hanging", async (t) => {
  const src = await loadMcp({ fx: { command: stdioCmd("--die-on-call") } })
  t.after(() => src.close())
  const r = await byName(src.tools, "fx_die").execute({})
  assert.equal(r.isError, true)
  assert.match(r.output, /closed/)
})

// ---------------------------------------------------------------------------
// 2. frozen fallback
// ---------------------------------------------------------------------------

/** An old-era (pre-2026) Streamable HTTP server built on the MCP SDK, stateless. */
async function oldServer(t: TestContext, guard?: (req: http.IncomingMessage, res: http.ServerResponse) => Promise<boolean>) {
  const echo = defineTool({ name: "echo", description: "echo", inputSchema: { type: "object", properties: { text: { type: "string" } } }, run: async (a: any) => String(a.text) })
  const methods: string[] = []
  const auths: string[] = []
  const hs = http.createServer(async (req, res) => {
    if (guard && !(await guard(req, res))) return
    auths.push(String(req.headers.authorization ?? ""))
    const chunks: Buffer[] = []
    for await (const c of req) chunks.push(c as Buffer)
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : undefined
    if (body?.method) methods.push(body.method)
    const server = buildMcpServer([echo])
    const tr = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined })
    res.on("close", () => { void tr.close(); void server.close() })
    await server.connect(tr)
    await tr.handleRequest(req, res, body)
  })
  await new Promise<void>((r) => hs.listen(0, "127.0.0.1", () => r()))
  t.after(() => closeServer(hs))
  return { url: `http://127.0.0.1:${(hs.address() as { port: number }).port}/mcp`, methods, auths }
}

test("mcp2026 fallback: an old-era HTTP server gets the frozen SDK path (discover, then initialize)", async (t) => {
  const old = await oldServer(t)
  const src = await loadMcp({ old: { url: old.url } })
  t.after(() => src.close())
  assert.equal(src.status.old, "connected")
  assert.equal(old.methods[0], "server/discover")
  assert.ok(old.methods.includes("initialize"))
  assert.equal((await byName(src.tools, "old_echo").execute({ text: "legacy" })).output, "legacy")
})

test("mcp2026 discover 5xx tries the frozen fallback; connection refused is a load failure", async (t) => {
  const old = await oldServer(t)
  let discovers = 0
  const front = http.createServer((req, res) => {
    const chunks: Buffer[] = []
    req.on("data", (c) => chunks.push(c))
    req.on("end", () => {
      const body = Buffer.concat(chunks).toString("utf8")
      if (body.includes('"server/discover"')) return void (discovers++, res.writeHead(503).end())
      const u = new URL(old.url)
      const p = http.request({ host: u.hostname, port: u.port, path: req.url, method: req.method, headers: req.headers }, (r) => {
        res.writeHead(r.statusCode ?? 502, r.headers)
        r.pipe(res)
      })
      // The client may hang up mid-proxy; without handlers the reset is an uncaught exception.
      p.on("error", () => res.destroy())
      req.on("error", () => p.destroy())
      p.end(body)
    })
  })
  await new Promise<void>((r) => front.listen(0, "127.0.0.1", () => r()))
  t.after(() => closeServer(front))
  const src = await loadMcp({
    five: { url: `http://127.0.0.1:${(front.address() as { port: number }).port}/mcp`, timeout: 2000 },
    refused: { url: "http://127.0.0.1:1/mcp", timeout: 2000 },
  })
  t.after(() => src.close())
  assert.deepEqual(src.status, { five: "connected", refused: "failed" })
  assert.equal(discovers, 1)
  assert.ok(old.methods.includes("initialize"))
})

// ---------------------------------------------------------------------------
// 2.6 no hidden re-execution
// ---------------------------------------------------------------------------

test("mcp2026 no hidden retry: 502 or a dropped connection after the tool ran ⇒ caller error, ran once", async (t) => {
  for (const mode of ["502", "drop"]) {
    const s = fixtureServer()
    let runs = 0
    s.tool("tick", "counts", () => (runs++, "tick"))
    const { url } = await serve(t, s, (_req, res, body, next) => {
      if (JSON.parse(body).method !== "tools/call") return next()
      ;(res as any).writeHead = () => res
      ;(res as any).end = () => {
        if (mode === "502") {
          http.ServerResponse.prototype.writeHead.call(res, 502)
          http.ServerResponse.prototype.end.call(res, "bad gateway")
        } else res.socket?.destroy()
        return res
      }
      next()
    })
    const src = await loadMcp({ fx: { url } })
    const r = await byName(src.tools, "fx_tick").execute({})
    await src.close()
    assert.equal(r.isError, true, mode)
    assert.equal(runs, 1, mode)
  }
})

// ---------------------------------------------------------------------------
// 4. skills extension
// ---------------------------------------------------------------------------

test("mcp2026 skills: remote skills join the skill tool; body byte-identical to the local copy", async (t) => {
  const { url } = await serve(t, fixtureServer())
  const remote = await Toolkit.create({ mcpConfig: { fx: { url } }, builtins: false })
  t.after(() => remote.close())
  const local = await Toolkit.create({ skillsDir: path.join(here, "..", "..", "examples", "skills"), builtins: false })
  t.after(() => local.close())
  const r = await remote.execute("skill", { name: "hello-world" })
  const l = await local.execute("skill", { name: "hello-world" })
  assert.equal(r.isError, false, r.output)
  const body = (s: string) => s.split("\n\nBase directory")[0]
  assert.equal(body(r.output), body(l.output))
  assert.match(r.output, /Base directory for this skill: skill:\/\/hello-world\//)
  assert.match(r.output, /scripts\/greet\.sh/)
})

test("mcp2026 skills: a hostile resource URI is refused (skill skipped, server still connected)", async (t) => {
  for (const evil of expected.skillUriRejected as string[]) {
    const s = fixtureServer()
    const { url } = await serve(t, s, (_req, res, body, next) => {
      const m = JSON.parse(body)
      if (m.method !== "skills/get") return next()
      res.writeHead(200, { "Content-Type": "application/json" })
      res.end(JSON.stringify({ jsonrpc: "2.0", id: m.id, result: { skill: { uri: "skill://hello-world/SKILL.md", resources: [{ uri: evil }] } } }))
    })
    const src = await loadMcp({ fx: { url } })
    assert.equal(src.status.fx, "connected", evil)
    assert.equal(src.skills.length, 0, evil)
    await src.close()
  }
})

// ---------------------------------------------------------------------------
// 5. tool search
// ---------------------------------------------------------------------------

test("mcp2026 tool search: two tools, byte-exact descriptions, BM25 hits, call_tool, unknown name", async (t) => {
  const { url } = await serve(t, fixtureServer())
  const src = await loadMcp({ fx: { url, toolSearch: true, description: "the fixture server" } })
  t.after(() => src.close())
  assert.deepEqual(src.tools.map((x) => x.name), ["fx_search_tools", "fx_call_tool"])
  const [search, call] = src.tools
  assert.equal(search.description, "Search the tools of the fixture server by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.")
  assert.deepEqual(search.inputSchema, { type: "object", properties: { query: { type: "string" }, limit: { type: "integer" } }, required: ["query"] })
  assert.equal(call.description, "Run a tool found with search_tools.")
  const hits = JSON.parse((await search.execute({ query: "echo text" })).output)
  assert.deepEqual(Object.keys(hits[0]), expected.toolSearch.hitKeys)
  assert.equal(hits[0].name, "echo")
  assert.equal(hits[0].server, "the fixture server")
  assert.equal((await call.execute({ name: "echo", arguments: { text: "found" } })).output, '{"text":"found"}')
  assert.deepEqual(await call.execute({ name: "nope" }), { output: 'unknown tool "nope" on fx', isError: true, metadata: { server: "fx" } })
  const p = await call.execute({ name: "greet", arguments: {} })
  const req = (p.metadata as any).pending as Request
  assert.equal(JSON.parse((await call.execute({ name: "greet" }, { answer: { id: req.id, ok: true, data: { name: "ts" } } })).output).greeting, "Hello, ts!")
  const noDesc = await loadMcp({ fx: { url, toolSearch: true } })
  assert.match(noDesc.tools[0].description, /^Search the tools of fx by keywords/)
  await noDesc.close()
})

test("mcp2026 tool search: tools filter and disableTools apply before search_tools / call_tool", async (t) => {
  const s = fixtureServer()
  s.tool("tick", "tick counter", () => "tick")
  const { url } = await serve(t, s)
  for (const [cfg, opts] of [[{ tools: { greet: false, tick: false } }, {}], [{}, { disableTools: ["fx_greet", "fx_tick"] }]] as const) {
    const tk = await Toolkit.create({ mcpConfig: { fx: { url, toolSearch: true, ...cfg } }, builtins: false, ...opts })
    const hits = JSON.parse((await tk.execute("fx_search_tools", { query: "greet tick echo user name counter", limit: 10 })).output)
    assert.deepEqual(hits.map((h: any) => h.name), ["echo"])
    for (const n of ["greet", "tick"]) {
      const r = await tk.execute("fx_call_tool", { name: n, arguments: {} })
      assert.deepEqual([r.output, r.isError], [`unknown tool "${n}" on fx`, true])
    }
    await tk.close()
  }
})

test("mcp2026 listMcpTools uses the 2026 path too", async (t) => {
  const { url } = await serve(t, fixtureServer())
  const inv = await listMcpTools({ fx: { url } })
  assert.equal(inv.status.fx, "connected")
  assert.deepEqual(inv.tools.fx.map((x) => x.name), expected.steps.find((x: any) => x.id === "tools/list").expect.toolNames)
})

// ---------------------------------------------------------------------------
// 3. OAuth (§2A.1, §2A.1a)
// ---------------------------------------------------------------------------

async function authEnv(t: TestContext, cfg?: (s: Server, as: DevAuthServer) => void) {
  const as = await DevAuthServer.start()
  t.after(() => as.close())
  as.documents["client.json"] = {
    client_id: as.documentUrl("client.json"), client_name: "toolnexus test", redirect_uris: ["http://127.0.0.1/callback"],
    grant_types: ["authorization_code", "refresh_token"], response_types: ["code"], token_endpoint_auth_method: "none",
  }
  as.clients["prereg"] = ["http://127.0.0.1:9/callback"]
  const s = fixtureServer()
  s.auth = new ServerAuth({ issuer: as.issuer, scopes: expected.auth.server.scopes })
  s.tool("whoami", "who is calling", (_a, ctx) => ctx.principal!.subject + " " + ctx.principal!.scopes.join(","))
  cfg?.(s, as)
  const { url, seen } = await serve(t, s)
  const cimd = as.documentUrl("client.json")
  const visit = (u: string) => as.visit(u)
  return { as, s, url, seen, cimd, visit, open: as.approve }
}

const paused = (redirect = "http://127.0.0.1:9/callback") => ({ mode: "pause" as const, clientId: "prereg", redirectUri: redirect })

test("mcp2026 oauth auto: PRM → AS → CIMD → PKCE + resource → token; principal reaches the tool", async (t) => {
  const e = await authEnv(t)
  const store = new MemoryTokenStore()
  const src = await loadMcp({ fx: { url: e.url, auth: { clientMetadataUrl: e.cimd } } }, { openUrl: e.open, tokenStore: store })
  t.after(() => src.close())
  assert.equal(src.status.fx, "connected")
  assert.equal((await byName(src.tools, "fx_whoami").execute({})).output, "dev-user mcp:read")
  const q = e.as.lastAuthorizeQuery
  assert.equal(q.get("resource"), e.url)
  assert.equal(q.get("code_challenge_method"), "S256")
  const tok = store.load(e.url)!
  assert.equal(tok.issuer, e.as.issuer)
  assert.ok(!String(tok).includes(tok.accessToken) && !JSON.stringify(tok).includes(tok.accessToken), "token never printable")
})

test("mcp2026 oauth pause with waitFor: authorization Request answered with callbackUrl", async (t) => {
  const e = await authEnv(t)
  const asked: Request[] = []
  const src = await loadMcp({ fx: { url: e.url, auth: paused() } }, {
    waitFor: async (req) => {
      asked.push(req)
      return { id: req.id, ok: true, data: { callbackUrl: await e.visit(req.url!) } }
    },
  })
  t.after(() => src.close())
  assert.equal(src.status.fx, "connected")
  const c = expected.auth.cases.find((x: any) => x.id === "pause then resume").expect
  assert.equal(asked[0].kind, c.pendingKind)
  assert.ok(asked[0].id.startsWith(c.pendingIdPrefix))
  assert.ok(asked[0].prompt.startsWith(c.promptPrefix))
  assert.match(asked[0].expiresAt!, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/)
  assert.equal((await byName(src.tools, "fx_echo").execute({ text: "x" })).output, '{"text":"x"}')
  assert.equal(e.as.authorizations, 1)
})

test("mcp2026 oauth pause: no waitFor at load ⇒ failed 'authorization pending'; denied answer ⇒ failed", async (t) => {
  const e = await authEnv(t)
  const errs: string[] = []
  await loadMcp({ fx: { url: e.url, auth: paused() } }, { onServer: (r) => void (r.error && errs.push(r.error.message)) })
  await loadMcp({ fx: { url: e.url, auth: paused() } }, {
    waitFor: async (req) => ({ id: req.id, ok: false, reason: "declined" }),
    onServer: (r) => void (r.error && errs.push(r.error.message)),
  })
  assert.deepEqual(errs, ["authorization pending", "authorization denied: declined"])
})

test("mcp2026 oauth pause during a tool call: {pending} without waitFor, resume runs the paused round once", async (t) => {
  let ticks = 0
  const e = await authEnv(t, (s) => s.tool("tick", "counts", () => (ticks++, "tick")))
  const store = new MemoryTokenStore()
  const pre = await loadMcp({ fx: { url: e.url, auth: paused() } }, {
    tokenStore: store, waitFor: async (req) => ({ id: req.id, ok: true, data: { callbackUrl: await e.visit(req.url!) } }),
  })
  await pre.close()
  const src = await loadMcp({ fx: { url: e.url, auth: paused() } }, { tokenStore: store })
  t.after(() => src.close())
  assert.equal(src.status.fx, "connected")
  store.delete(e.url) // token lost ⇒ the next call is rejected before dispatch
  const tick = byName(src.tools, "fx_tick")
  const p = await tick.execute({})
  const req = (p.metadata as any).pending as Request
  assert.equal(req.kind, "authorization")
  assert.equal(ticks, 0)
  const r = await tick.execute({}, { answer: { id: req.id, ok: true, data: { callbackUrl: await e.visit(req.url!) } } })
  assert.equal(r.output, "tick")
  assert.equal(ticks, 1)
  // unknown id after it was consumed: it is no longer a pending of ours ⇒ a fresh call
  assert.equal((await tick.execute({})).output, "tick")
})

test("mcp2026 oauth pause in MRTR round 1 resumes THAT round (not re-asked)", async (t) => {
  const e = await authEnv(t, (s) => delete s.auth!.scopes.greet)
  const store = new MemoryTokenStore()
  let asked = 0
  const visitAnswer = async (req: Request): Promise<Answer> => ({ id: req.id, ok: true, data: { callbackUrl: await e.visit(req.url!) } })
  const src = await loadMcp({ fx: { url: e.url, auth: paused() } }, {
    tokenStore: store,
    waitFor: async (req) => {
      if (req.kind === "authorization") return visitAnswer(req)
      asked++
      store.delete(e.url) // token lost between rounds ⇒ the answered round gets 401
      return { id: req.id, ok: true, data: { name: "once" } }
    },
  })
  t.after(() => src.close())
  const r = await byName(src.tools, "fx_greet").execute({})
  assert.equal(JSON.parse(r.output).greeting, "Hello, once!")
  assert.equal(asked, 1)
  assert.equal(e.as.authorizations, 2)
})

test("mcp2026 oauth errors: iss mismatch, state mismatch, no S256, no client identity (no DCR)", async (t) => {
  const failOf = async (cfg: any, opts: any = {}) => {
    let err = ""
    await loadMcp(cfg, { ...opts, onServer: (r: any) => void (err = r.error?.message ?? "") })
    return err
  }
  const e = await authEnv(t)
  const store = new MemoryTokenStore()
  e.as.issOverride = "https://evil.example"
  assert.equal(await failOf({ fx: { url: e.url, auth: { clientMetadataUrl: e.cimd } } }, { openUrl: e.open, tokenStore: store }), "authorization response iss mismatch (possible mix-up attack)")
  assert.equal(store.load(e.url), undefined)
  e.as.issOverride = ""
  const forged = await failOf({ fx: { url: e.url, auth: paused() } }, {
    waitFor: async (req: Request) => ({ id: req.id, ok: true, data: { callbackUrl: (await e.visit(req.url!)).replace(/state=[^&]*/, "state=forged") } }),
  })
  assert.equal(forged, "authorization response state mismatch")
  e.as.noCimd = true
  const noId = await failOf({ fx: { url: e.url } }, { openUrl: e.open })
  for (const s of expected.auth.cases.find((x: any) => x.id === "no CIMD and no clientId").expect.errorContains) assert.ok(noId.includes(s), noId)
  assert.ok(noId.startsWith(`no client identity for ${e.as.issuer}: `), noId)
  assert.equal(noId, `no client identity for ${e.as.issuer}: it does not support Client ID Metadata Documents and no client id is configured — register an app with ${e.as.issuer} and set auth.clientId (dynamic client registration is not supported)`)
})

test("mcp2026 oauth: AS without PKCE S256 is refused", async (t) => {
  const e = await authEnv(t)
  // Serve AS metadata without S256 by fronting the PRM with a fake issuer that lacks it.
  const fake = http.createServer((req, res) => {
    const base = `http://127.0.0.1:${(fake.address() as any).port}`
    if (req.url === "/.well-known/oauth-authorization-server") return void res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ issuer: base, authorization_endpoint: base + "/a", token_endpoint: base + "/t", code_challenge_methods_supported: ["plain"] }))
    res.writeHead(404).end()
  })
  await new Promise<void>((r) => fake.listen(0, "127.0.0.1", () => r()))
  t.after(() => closeServer(fake))
  const issuer = `http://127.0.0.1:${(fake.address() as any).port}`
  const s = fixtureServer()
  s.auth = new ServerAuth({ issuer })
  const { url } = await serve(t, s)
  let err = ""
  await loadMcp({ fx: { url, auth: { clientId: "x", allowHttp: true } } }, { openUrl: e.open, onServer: (r) => void (err = r.error?.message ?? "") })
  assert.equal(err, expected.auth.cases.find((x: any) => x.id === "AS without S256").expect.error)
})

test("mcp2026 oauth: refresh on expiry is single-flight across concurrent calls; omitRefreshResource", async (t) => {
  for (const omit of [false, true]) {
    let ticks = 0
    const e = await authEnv(t, (s, as) => {
      s.tool("tick", "counts", () => (ticks++, "tick"))
      as.rejectRefreshResource = omit
    })
    const store = new MemoryTokenStore()
    const src = await loadMcp({ fx: { url: e.url, auth: { clientMetadataUrl: e.cimd, omitRefreshResource: omit } } }, { openUrl: e.open, tokenStore: store })
    const tok = store.load(e.url)!
    store.save(e.url, new Token({ ...tok, expiresAt: Date.now() - 60_000 }))
    const rs = await Promise.all(Array.from({ length: 20 }, () => byName(src.tools, "fx_tick").execute({})))
    await src.close()
    assert.ok(rs.every((r) => r.output === "tick"), JSON.stringify(rs.find((r) => r.output !== "tick")))
    assert.equal(e.as.refreshes, 1, `omit=${omit}`)
    assert.equal(e.as.authorizations, 1)
    assert.equal(e.as.lastTokenForm.has("resource"), !omit)
    assert.equal(ticks, 20)
  }
})

test("mcp2026 oauth: step-up on 403 insufficient_scope re-authorizes with the union, tool runs once", async (t) => {
  let charges = 0
  const e = await authEnv(t, (s) => {
    s.tool("charge", "needs mcp:charge", () => (charges++, "charged"))
    s.auth!.scopes.charge = ["mcp:charge"]
  })
  const src = await loadMcp({ fx: { url: e.url, auth: { clientMetadataUrl: e.cimd } } }, { openUrl: e.open })
  t.after(() => src.close())
  assert.equal((await byName(src.tools, "fx_charge").execute({})).output, "charged")
  assert.equal(charges, 1)
  assert.equal(e.as.authorizations, 2)
  assert.deepEqual(e.as.lastAuthorizeQuery.get("scope")!.split(" ").sort(), ["mcp:charge", "mcp:read"])
})

test("mcp2026 oauth + frozen fallback: the SDK transport carries the bearer", async (t) => {
  const e = await authEnv(t)
  const sa = new ServerAuth({ issuer: e.as.issuer, scopes: { "*": ["mcp:read"] } })
  const old = await oldServer(t, async (req, res) => {
    const p = new URL(req.url ?? "/", "http://x").pathname
    if (req.method === "GET" && sa.serveMetadata(req, res, p)) return false
    return !!(await sa.check(req, res, "initialize", {}))
  })
  const src = await loadMcp({ old: { url: old.url, auth: { clientMetadataUrl: e.cimd } } }, { openUrl: e.open })
  t.after(() => src.close())
  assert.equal(src.status.old, "connected")
  assert.ok(old.methods.includes("initialize"))
  assert.ok(old.auths.length > 0 && old.auths.every((a) => a.startsWith("Bearer ")))
  assert.equal((await byName(src.tools, "old_echo").execute({ text: "authed legacy" })).output, "authed legacy")
  assert.equal(e.as.authorizations, 1)
})

test("mcp2026 oauth: tokens never appear in errors or logs", async (t) => {
  const e = await authEnv(t)
  const logs: string[] = []
  const warn = console.warn
  console.warn = (...a: unknown[]) => void logs.push(a.map(String).join(" "))
  t.after(() => void (console.warn = warn))
  const store = new MemoryTokenStore()
  const src = await loadMcp({ fx: { url: e.url, auth: { clientMetadataUrl: e.cimd } } }, { openUrl: e.open, tokenStore: store })
  const tok = store.load(e.url)!
  e.as.issOverride = "https://evil.example"
  store.delete(e.url)
  const r = await byName(src.tools, "fx_echo").execute({ text: "x" })
  await src.close()
  assert.equal(r.isError, true)
  for (const s of [r.output, ...logs]) {
    assert.ok(!s.includes(tok.accessToken), s)
    if (tok.refreshToken) assert.ok(!s.includes(tok.refreshToken), s)
  }
})

// §2.2a (change reuse-stdio-discover-probe): a fast discover error reuses the probe child; only
// discover silence kills it and respawns.
for (const [mode, starts] of [["error", 1], ["silent", 2]] as const) {
  test(`stdio legacy server, discover ${mode} ⇒ ${starts} spawn(s), echo works`, async () => {
    const dir = fs.mkdtempSync(path.join((await import("node:os")).tmpdir(), "tn-spawn-"))
    const countFile = path.join(dir, "count")
    const script = path.join(here, "..", "..", "examples", "mcp-2026", "legacy-stdio", "server.mjs")
    const src = await loadMcp({ legacy: { command: ["node", script], env: { SPAWN_COUNT_FILE: countFile, DISCOVER_MODE: mode }, timeout: 1500 } })
    try {
      assert.equal(src.status.legacy, "connected")
      const echo = src.tools.find((t) => t.name === "legacy_echo")!
      const r = await echo.execute({ text: "hi" }, {} as any)
      assert.match(JSON.stringify(r), /echo:hi/)
      const lines = fs.readFileSync(countFile, "utf8").trim().split("\n")
      assert.equal(lines.length, starts)
    } finally {
      await src.close()
      fs.rmSync(dir, { recursive: true, force: true })
    }
  })
}
