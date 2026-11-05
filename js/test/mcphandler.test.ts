/**
 * toolkit.mcpHandler (SPEC §7C.1, ADR 0039): the embeddable MCP server, exercised with the
 * @modelcontextprotocol/sdk CLIENT over real streamable HTTP, plus raw HTTP protocol checks.
 * Mirrors golang/mcphandler_test.go test for test. Hermetic: loopback only.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import { createHash } from "node:crypto"
import path from "node:path"
import { fileURLToPath } from "node:url"
import type { AddressInfo } from "node:net"
import { Client as MCPClient } from "@modelcontextprotocol/sdk/client/index.js"
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js"
import { ElicitRequestSchema, ToolListChangedNotificationSchema } from "@modelcontextprotocol/sdk/types.js"
import {
  createToolkit,
  identityFrom,
  requestFrom,
  mcpRequestMetaFrom,
  mcpWireTool,
  toolResultToMcp,
  buildMcpServer,
  loadMcpSkills,
  McpServerAuth,
  startMcpDevAuthServer,
  type McpDevAuthServer,
  type McpPrincipal,
  type Tool,
  type ToolResult,
  type McpHandler,
  type McpCallEvent,
  type McpSessionIds,
  type McpHandlerConfig,
} from "../dist/index.js"

type User = { name: string }

function testTool(name: string, ran?: { n: number }): Tool {
  return {
    name,
    description: name + " tool",
    source: "native",
    inputSchema: { type: "object", properties: { id: { type: "string" } } },
    async execute(_args, ctx) {
      if (ran) ran.n++
      const u = identityFrom(ctx) as User | undefined
      return { output: name + ":" + (u?.name ?? ""), isError: false }
    },
  }
}

function bearerAuth(req: http.IncomingMessage): User {
  const tok = (req.headers.authorization ?? "").replace(/^Bearer /, "")
  if (tok === "alice" || tok === "bob") return { name: tok }
  throw new Error("bad token")
}

async function newTK() {
  return createToolkit({ builtins: false })
}

async function listen(h: McpHandler): Promise<{ url: string; close: () => Promise<void> }> {
  const srv = http.createServer(h)
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
  const url = `http://127.0.0.1:${(srv.address() as AddressInfo).port}/`
  return {
    url,
    close: () =>
      new Promise<void>((r) => {
        srv.closeAllConnections()
        srv.close(() => r())
      }),
  }
}

type Elicit = { action: "accept" | "decline" | "cancel"; asked: number }

async function dial(url: string, token: string, elicit?: Elicit): Promise<MCPClient> {
  const c = new MCPClient({ name: "test", version: "1" }, { capabilities: elicit ? { elicitation: {} } : {} })
  if (elicit) {
    c.setRequestHandler(ElicitRequestSchema, async () => {
      elicit.asked++
      return { action: elicit.action, content: { confirm: elicit.action === "accept" } }
    })
  }
  const headers: Record<string, string> = token ? { Authorization: "Bearer " + token } : {}
  await c.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers } }))
  return c
}

const text = (r: any) => r?.content?.[0]?.text ?? ""
const names = async (c: MCPClient) => (await c.listTools()).tools.map((t) => t.name)

async function rawPost(url: string, sid: string, body: string, hdr: Record<string, string> = {}) {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json, text/event-stream",
    ...hdr,
  }
  if (sid) headers["Mcp-Session-Id"] = sid
  return fetch(url, { method: "POST", headers, body })
}

test("mcpHandler: auth rejects with 401 and WWW-Authenticate", async () => {
  const tk = await newTK()
  const s = await listen(
    tk.mcpHandler({
      authenticate: bearerAuth,
      wwwAuthenticate: () => `Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource"`,
    }),
  )
  try {
    const r = await fetch(s.url, { method: "POST", headers: { "Content-Type": "application/json" }, body: `{"jsonrpc":"2.0","id":1,"method":"ping"}` })
    await r.text()
    assert.equal(r.status, 401)
    assert.match(r.headers.get("www-authenticate") ?? "", /resource_metadata=/)
  } finally {
    await s.close()
  }
})

test("mcpHandler: per-identity tools and a not-listed name never executes", async () => {
  const ranAdmin = { n: 0 }
  const tk = await newTK()
  const s = await listen(
    tk.mcpHandler({
      authenticate: bearerAuth,
      listTools: (ctx) => {
        const tools = [testTool("search")]
        if ((identityFrom(ctx) as User).name === "alice") tools.push(testTool("admin_reset", ranAdmin))
        return tools
      },
    }),
  )
  const alice = await dial(s.url, "alice")
  const bob = await dial(s.url, "bob")
  try {
    assert.deepEqual(await names(alice), ["search", "admin_reset"])
    assert.deepEqual(await names(bob), ["search"])
    assert.equal(text(await bob.callTool({ name: "search" })), "search:bob")
    await assert.rejects(bob.callTool({ name: "admin_reset" }), /tool not found/)
    assert.equal(ranAdmin.n, 0)
    assert.equal(text(await alice.callTool({ name: "admin_reset" })), "admin_reset:alice")
  } finally {
    await alice.close()
    await bob.close()
    await s.close()
  }
})

async function gatedHandler(fallback?: ToolResult) {
  const ran = { n: 0 }
  const del = testTool("delete_record", ran)
  del.annotations = { title: "Delete", destructiveHint: true, readOnlyHint: false, idempotentHint: true, openWorldHint: false }
  const tk = await newTK()
  const h = tk.mcpHandler({
    authenticate: bearerAuth,
    listTools: () => [del, testTool("read")],
    beforeCall: (_ctx, tool, args) => {
      const raw = JSON.stringify(args)
      if (tool.annotations?.destructiveHint) {
        if (raw.includes(`"nonce-ok"`)) return { kind: "allow" }
        return { kind: "confirm", message: "Delete the record?", result: fallback }
      }
      if (raw.includes(`"forbidden"`)) return { kind: "deny", message: "not allowed" }
      return {}
    },
  })
  return { h, ran }
}

test("mcpHandler: confirm via elicitation (accept runs, decline does not)", async () => {
  const { h, ran } = await gatedHandler()
  const s = await listen(h)
  const yes: Elicit = { action: "accept", asked: 0 }
  const no: Elicit = { action: "decline", asked: 0 }
  const c = await dial(s.url, "alice", yes)
  const c2 = await dial(s.url, "alice", no)
  try {
    let r: any = await c.callTool({ name: "delete_record", arguments: { id: "1" } })
    assert.ok(!r.isError)
    assert.equal(text(r), "delete_record:alice")
    assert.equal(yes.asked, 1)
    assert.equal(ran.n, 1)
    r = await c2.callTool({ name: "delete_record", arguments: { id: "1" } })
    assert.equal(r.isError, true)
    assert.equal(text(r), "declined")
    assert.equal(ran.n, 1)
  } finally {
    await c.close()
    await c2.close()
    await s.close()
  }
})

test("mcpHandler: raw elicitation round trip on the POST's SSE response", async () => {
  const { h, ran } = await gatedHandler()
  const s = await listen(h)
  try {
    const init = await rawPost(
      s.url,
      "",
      `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"elicitation":{}}}}`,
      { Authorization: "Bearer alice" },
    )
    const sid = init.headers.get("mcp-session-id")!
    await init.text()
    const hdr = { Authorization: "Bearer alice", "MCP-Protocol-Version": "2025-06-18" }
    const call = await rawPost(s.url, sid, `{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"delete_record","arguments":{"id":"1"}}}`, hdr)
    assert.equal(call.headers.get("content-type"), "text/event-stream")
    const reader = call.body!.getReader()
    const dec = new TextDecoder()
    let buf = ""
    const events: any[] = []
    const nextEvent = async () => {
      while (!buf.includes("\n\n")) {
        const { value, done } = await reader.read()
        if (done) throw new Error("stream ended")
        buf += dec.decode(value)
      }
      const i = buf.indexOf("\n\n")
      const block = buf.slice(0, i)
      buf = buf.slice(i + 2)
      const data = block.split("\n").find((l) => l.startsWith("data: "))!.slice(6)
      events.push(JSON.parse(data))
      return events.at(-1)
    }
    const ask = await nextEvent()
    assert.equal(ask.method, "elicitation/create")
    assert.equal(ask.params.message, "Delete the record?")
    assert.deepEqual(ask.params.requestedSchema.properties.confirm.type, "boolean")
    const ans = await rawPost(s.url, sid, JSON.stringify({ jsonrpc: "2.0", id: ask.id, result: { action: "accept", content: { confirm: true } } }), hdr)
    assert.equal(ans.status, 202)
    const done = await nextEvent()
    assert.equal(done.id, 2)
    assert.equal(done.result.content[0].text, "delete_record:alice")
    assert.equal(ran.n, 1)
  } finally {
    await s.close()
  }
})

test("mcpHandler: confirm timeout ⇒ confirmation failed", async () => {
  const tk = await newTK()
  const s = await listen(
    tk.mcpHandler({
      listTools: () => [testTool("x")],
      beforeCall: () => ({ kind: "confirm", message: "ok?" }),
      confirmTimeout: 30,
    }),
  )
  try {
    const init = await rawPost(s.url, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{"elicitation":{}}}}`)
    const sid = init.headers.get("mcp-session-id")!
    await init.text()
    const b = await (await rawPost(s.url, sid, `{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"x"}}`)).text()
    assert.match(b, /confirmation failed: context deadline exceeded/)
  } finally {
    await s.close()
  }
})

test("mcpHandler: confirm without elicitation (plain, then host-shaped nonce re-call)", async () => {
  const g1 = await gatedHandler()
  const s = await listen(g1.h)
  const g2 = await gatedHandler({ output: `re-call with {"nonce":"nonce-ok"}`, isError: false })
  const s2 = await listen(g2.h)
  const c = await dial(s.url, "alice")
  const c2 = await dial(s2.url, "alice")
  try {
    let r: any = await c.callTool({ name: "delete_record", arguments: { id: "1" } })
    assert.equal(r.isError, true)
    assert.equal(text(r), "confirmation required: Delete the record?")
    assert.equal(g1.ran.n, 0)
    r = await c2.callTool({ name: "delete_record", arguments: { id: "1" } })
    assert.ok(!r.isError)
    assert.match(text(r), /nonce-ok/)
    assert.equal(g2.ran.n, 0)
    r = await c2.callTool({ name: "delete_record", arguments: { id: "1", nonce: "nonce-ok" } })
    assert.equal(text(r), "delete_record:alice")
    assert.equal(g2.ran.n, 1)
  } finally {
    await c.close()
    await c2.close()
    await s.close()
    await s2.close()
  }
})

test("mcpHandler: deny and annotations", async () => {
  const { h } = await gatedHandler()
  const s = await listen(h)
  const c = await dial(s.url, "alice")
  try {
    const r: any = await c.callTool({ name: "read", arguments: { id: "forbidden" } })
    assert.equal(r.isError, true)
    assert.equal(text(r), "not allowed")
    const { tools } = await c.listTools()
    assert.deepEqual(tools[0]!.annotations, {
      title: "Delete",
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: true,
      openWorldHint: false,
    })
    assert.equal(tools[1]!.annotations, undefined)
  } finally {
    await c.close()
    await s.close()
  }
})

test("mcpHandler: onCall event (throws isolated)", async () => {
  const evs: McpCallEvent[] = []
  const boom: Tool = {
    name: "boom",
    description: "",
    source: "native",
    inputSchema: { type: "object" },
    async execute() {
      await new Promise((r) => setTimeout(r, 5))
      throw new Error("kaput")
    },
  }
  const tk = await newTK()
  const s = await listen(
    tk.mcpHandler({
      authenticate: bearerAuth,
      listTools: () => [boom],
      onCall: (ev) => {
        evs.push(ev)
        throw new Error("isolated")
      },
    }),
  )
  const c = await dial(s.url, "bob")
  try {
    const r: any = await c.callTool({ name: "boom" })
    assert.equal(r.isError, true)
    assert.equal(text(r), "kaput")
    assert.equal(evs.length, 1)
    const ev = evs[0]!
    assert.equal(ev.name, "boom")
    assert.equal(ev.source, "native")
    assert.equal(ev.isError, true)
    assert.ok(ev.err instanceof Error)
    assert.ok(ev.resultBytes > 0)
    assert.ok(ev.ms >= 4)
    assert.equal((ev.identity as User).name, "bob")
    assert.ok(ev.sessionId !== "")
    assert.equal(ev.decision, "allow")
  } finally {
    await c.close()
    await s.close()
  }
})

test("mcpHandler: notifyToolsChanged reaches a GET stream", async () => {
  const tk = await newTK()
  const h = tk.mcpHandler({ listTools: () => [testTool("a")] })
  const s = await listen(h)
  const c = new MCPClient({ name: "t", version: "1" })
  let got = false
  c.setNotificationHandler(ToolListChangedNotificationSchema, async () => {
    got = true
  })
  await c.connect(new StreamableHTTPClientTransport(new URL(s.url)))
  try {
    const deadline = Date.now() + 5000
    while (!got && Date.now() < deadline) {
      h.notifyToolsChanged()
      await new Promise((r) => setTimeout(r, 50))
    }
    assert.ok(got, "no list_changed received")
  } finally {
    await c.close()
    await s.close()
  }
})

test("mcpHandler: serverInfo hook, resources and prompts", async () => {
  const tk = await newTK()
  const s = await listen(
    tk.mcpHandler({
      authenticate: bearerAuth,
      serverInfo: (ctx) => ({
        name: "srv-" + requestFrom(ctx)!.headers.host!.slice(0, 9) + "-" + (identityFrom(ctx) as User).name,
        version: "9.9.9",
        instructions: "be careful",
      }),
      listResources: () => [{ uri: "docs://api", name: "api", mimeType: "text/markdown" }],
      readResource: (ctx, uri) => [{ uri, mimeType: "text/markdown", text: "# API for " + (identityFrom(ctx) as User).name }],
      listPrompts: () => [{ name: "greet", arguments: [{ name: "who", required: true }] }],
      getPrompt: (_ctx, _name, args) => ({ messages: [{ role: "user", text: "hello " + args.who }] }),
    }),
  )
  const c = await dial(s.url, "alice")
  try {
    assert.deepEqual(c.getServerVersion(), { name: "srv-127.0.0.1-alice", version: "9.9.9" })
    assert.equal(c.getInstructions(), "be careful")
    const caps = c.getServerCapabilities()!
    assert.ok(caps.resources && caps.prompts)
    const rl = await c.listResources()
    assert.equal(rl.resources[0]!.uri, "docs://api")
    const rd: any = await c.readResource({ uri: "docs://api" })
    assert.equal(rd.contents[0].text, "# API for alice")
    const pr: any = await c.getPrompt({ name: "greet", arguments: { who: "world" } })
    assert.equal(pr.messages[0].content.text, "hello world")
    const pl = await c.listPrompts()
    assert.equal(pl.prompts[0]!.arguments![0]!.name, "who")
    assert.equal(pl.prompts[0]!.arguments![0]!.required, true)
  } finally {
    await c.close()
    await s.close()
  }
})

test("mcpHandler: protocol conformance (streamable HTTP 2025-06-18)", async () => {
  const tk = await newTK()
  const s = await listen(tk.mcpHandler({ listTools: () => [] }))
  const u = s.url
  try {
    let r = await rawPost(u, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}`)
    const sid = r.headers.get("mcp-session-id") ?? ""
    let b = await r.text()
    assert.equal(r.status, 200)
    assert.ok(sid)
    assert.ok(b.includes(`"protocolVersion":"2025-06-18"`) && b.includes(`"listChanged":true`), b)
    b = await (await rawPost(u, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}`)).text()
    assert.ok(b.includes(`"protocolVersion":"2025-06-18"`))
    const v = { "MCP-Protocol-Version": "2025-06-18" }
    assert.equal((await rawPost(u, sid, `{"jsonrpc":"2.0","method":"notifications/initialized"}`, v)).status, 202)
    assert.equal((await rawPost(u, "", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, v)).status, 400)
    assert.equal((await rawPost(u, "nope", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, v)).status, 404)
    assert.equal((await rawPost(u, sid, `{"jsonrpc":"2.0","id":2,"method":"ping"}`, { "MCP-Protocol-Version": "2099-01-01" })).status, 400)
    assert.match(await (await rawPost(u, sid, `[{"jsonrpc":"2.0","id":3,"method":"ping"}]`, v)).text(), /-32600/)
    assert.ok((await (await rawPost(u, sid, `{"jsonrpc":"2.0","id":4,"method":"ping"}`, v)).text()).includes(`"result":{}`))
    assert.match(await (await rawPost(u, sid, `{"jsonrpc":"2.0","id":5,"method":"resources/list"}`, v)).text(), /-32601/)
    r = await fetch(u, { method: "POST", headers: { "Content-Type": "text/plain" }, body: "{}" })
    assert.equal(r.status, 415)
    assert.equal((await fetch(u)).status, 405)
    r = await fetch(u, { method: "PUT" })
    assert.equal(r.status, 405)
    assert.equal(r.headers.get("allow"), "GET, POST, DELETE")
    assert.equal((await fetch(u, { method: "DELETE", headers: { "Mcp-Session-Id": sid } })).status, 200)
    assert.equal((await rawPost(u, sid, `{"jsonrpc":"2.0","id":6,"method":"ping"}`, v)).status, 404)
    // body cap ⇒ 413
    const small = await listen(tk.mcpHandler({ stateless: true, maxBodyBytes: 16 }))
    assert.equal((await rawPost(small.url, "", `{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}`)).status, 413)
    await small.close()
  } finally {
    await s.close()
  }
})

test("mcpHandler: custom sessionIds and stateless", async () => {
  const counts = { gen: 0, val: 0, term: 0 }
  const ids: McpSessionIds = {
    generate: () => (counts.gen++, "host-session-1"),
    validate: (_ctx, id) => (counts.val++, id === "host-session-1"),
    terminate: () => void counts.term++,
  }
  const tk = await newTK()
  const s = await listen(tk.mcpHandler({ sessionIds: ids, listTools: () => [testTool("a")] }))
  const c = await dial(s.url, "")
  try {
    assert.equal(text(await c.callTool({ name: "a" })), "a:")
    assert.equal(counts.gen, 1)
    assert.ok(counts.val > 0)
  } finally {
    await c.close()
    await s.close()
  }
  const s2 = await listen(tk.mcpHandler({ stateless: true, listTools: () => [testTool("a")] }))
  try {
    const r = await rawPost(s2.url, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`)
    await r.text()
    assert.equal(r.headers.get("mcp-session-id"), null)
    assert.match(await (await rawPost(s2.url, "", `{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"a"}}`)).text(), /a:/)
    assert.equal((await fetch(s2.url, { method: "DELETE" })).status, 405)
  } finally {
    await s2.close()
  }
})

test("mcpHandler: defaults to the toolkit's tools", async () => {
  const tk = await newTK()
  tk.register(testTool("registered"))
  const s = await listen(tk.mcpHandler())
  const c = await dial(s.url, "")
  try {
    assert.ok((await names(c)).includes("registered"))
  } finally {
    await c.close()
    await s.close()
  }
})

test("mcpHandler: structuredContent and outputSchema", async () => {
  const st: Tool = {
    name: "stats",
    description: "",
    source: "native",
    inputSchema: { type: "object" },
    outputSchema: { type: "object", properties: { count: { type: "integer" } } },
    async execute() {
      return { output: "", isError: false, structured: { count: 3 } }
    },
  }
  const plain = testTool("plain")
  const tk = await newTK()
  const s = await listen(tk.mcpHandler({ listTools: () => [st, plain] }))
  const c = await dial(s.url, "")
  try {
    const lt = await c.listTools()
    assert.equal((lt.tools[0]!.outputSchema as any).type, "object")
    const r: any = await c.callTool({ name: "stats" })
    assert.equal(JSON.stringify(r.structuredContent), `{"count":3}`)
    assert.equal(text(r), `{"count":3}`)
    const raw = JSON.stringify(toolResultToMcp({ output: "x", isError: false }, true))
    assert.ok(!raw.includes("structuredContent") && !raw.includes("_meta") && !raw.includes("isError"), raw)
    assert.ok(!("outputSchema" in mcpWireTool(plain)))
  } finally {
    await c.close()
    await s.close()
  }
})

test("mcpHandler: request _meta reaches hooks and tools; result meta leaves as _meta", async () => {
  let seen: any
  let seenHook: any
  const tool: Tool = {
    name: "m",
    description: "",
    source: "native",
    inputSchema: { type: "object" },
    async execute(_a, ctx) {
      seen = mcpRequestMetaFrom(ctx)
      return { output: "ok", isError: false, meta: { usage: { units: 2 } } }
    },
  }
  const tk = await newTK()
  const s = await listen(
    tk.mcpHandler({
      stateless: true,
      listTools: () => [tool],
      beforeCall: (ctx) => {
        seenHook = mcpRequestMetaFrom(ctx)
      },
    }),
  )
  try {
    const b = await (await rawPost(s.url, "", `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"m","_meta":{"clientCaps":{"elicitation":{}}}}}`)).text()
    assert.ok(b.includes(`"_meta":{"usage":{"units":2}}`), b)
    assert.ok(seen && "clientCaps" in seen)
    assert.ok(seenHook && "clientCaps" in seenHook)
  } finally {
    await s.close()
  }
})

test("mcpHandler: protocolVersions per handler", async () => {
  const initBody = (v: string) => `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"${v}"}}`
  const tk = await newTK()
  const def = await listen(tk.mcpHandler({ stateless: true }))
  const newer = await listen(
    tk.mcpHandler({
      stateless: true,
      protocolVersions: ["2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26"],
      listTools: () => [testTool("a")],
    }),
  )
  try {
    assert.ok((await (await rawPost(def.url, "", initBody("2025-11-25"))).text()).includes(`"protocolVersion":"2025-06-18"`))
    assert.equal((await rawPost(def.url, "", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, { "MCP-Protocol-Version": "2025-11-25" })).status, 400)
    assert.ok((await (await rawPost(newer.url, "", initBody("2025-11-25"))).text()).includes(`"protocolVersion":"2025-11-25"`))
    assert.ok((await (await rawPost(newer.url, "", initBody("2026-07-28"))).text()).includes(`"protocolVersion":"2025-11-25"`))
    assert.equal((await rawPost(newer.url, "", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, { "MCP-Protocol-Version": "2025-03-26" })).status, 400)
    const bad = `{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"a","arguments":[1]}}`
    let b = await (await rawPost(newer.url, "", bad, { "MCP-Protocol-Version": "2025-11-25" })).text()
    assert.ok(b.includes(`"isError":true`) && !b.includes("-32602"), b)
    b = await (await rawPost(newer.url, "", bad, { "MCP-Protocol-Version": "2025-06-18" })).text()
    assert.ok(b.includes("-32602"), b)
  } finally {
    await def.close()
    await newer.close()
  }
})

test("mcpHandler: statelessSessionId and title", async () => {
  const tk = await newTK()
  const s = await listen(tk.mcpHandler({ stateless: true, statelessSessionId: "stateless", title: "Records API" }))
  const s2 = await listen(tk.mcpHandler({ stateless: true }))
  try {
    const r = await rawPost(s.url, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`)
    const b = await r.text()
    assert.equal(r.headers.get("mcp-session-id"), "stateless")
    assert.ok(b.includes(`"title":"Records API"`))
    assert.ok((await (await rawPost(s.url, "whatever", `{"jsonrpc":"2.0","id":2,"method":"ping"}`)).text()).includes(`"result":{}`))
    assert.ok(!(await (await rawPost(s2.url, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}`)).text()).includes(`"title"`))
  } finally {
    await s.close()
    await s2.close()
  }
})

test("serve (§7C): structured/meta/outputSchema mapped; absent ⇒ byte-identical", async () => {
  // absent: the exact pre-§7C.1 shapes
  assert.equal(
    JSON.stringify(toolResultToMcp({ output: "hi", isError: false })),
    JSON.stringify({ content: [{ type: "text", text: "hi" }], isError: false }),
  )
  assert.equal(
    JSON.stringify(toolResultToMcp({ output: "hi", isError: false, structured: { a: 1 }, meta: { k: "v" } })),
    `{"content":[{"type":"text","text":"hi"}],"isError":false,"structuredContent":{"a":1},"_meta":{"k":"v"}}`,
  )
  // through the real §7C SDK server
  const tools: Tool[] = [
    { name: "x", description: "", source: "native", inputSchema: { type: "object" }, execute: async () => ({ output: "x", isError: false }) },
    {
      name: "y",
      description: "",
      source: "native",
      inputSchema: { type: "object" },
      outputSchema: { type: "object" },
      execute: async () => ({ output: "", isError: false, structured: { a: 1 }, meta: { k: "v" } }),
    },
  ]
  const tk = await createToolkit({ builtins: false, extraTools: tools })
  const srv = await tk.serve("127.0.0.1:0", { mcp: {} })
  const c = new MCPClient({ name: "t", version: "1" })
  try {
    await c.connect(new StreamableHTTPClientTransport(new URL(srv.url + "/mcp")))
    const lt = await c.listTools()
    assert.ok(!("outputSchema" in lt.tools.find((t) => t.name === "x")!))
    assert.deepEqual(lt.tools.find((t) => t.name === "y")!.outputSchema, { type: "object" })
    assert.deepEqual(await c.callTool({ name: "x" }), { content: [{ type: "text", text: "x" }], isError: false })
    const y: any = await c.callTool({ name: "y" })
    assert.deepEqual(y.structuredContent, { a: 1 })
    assert.deepEqual(y._meta, { k: "v" })
    assert.equal(y.content[0].text, `{"a":1}`)
  } finally {
    await c.close()
    await srv.stop()
    await tk.close()
  }
  void buildMcpServer
})

// Keep the config type exercised (compile-time only).
const _cfg: McpHandlerConfig = {}
void _cfg

// ---- MCP 2026-07-28 (SPEC §7C.2) — mirrors golang/mcphandler2026_test.go ----

const V26 = "2026-07-28"

/** One 2026-07-28 request (headers + _meta stamped). */
async function post26(url: string, token: string, method: string, params: Record<string, any> = {}) {
  params = { ...params, _meta: { ...(params._meta ?? {}), "io.modelcontextprotocol/protocolVersion": V26 } }
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "MCP-Protocol-Version": V26,
    "Mcp-Method": method,
  }
  if (typeof params.name === "string") headers["Mcp-Name"] = params.name
  if (token) headers.Authorization = "Bearer " + token
  const r = await fetch(url, { method: "POST", headers, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }) })
  const raw = await r.text()
  let body: any
  try {
    body = JSON.parse(raw)
  } catch {
    body = undefined
  }
  return { status: r.status, headers: r.headers, body }
}

function whoTool(): Tool {
  return {
    name: "who",
    description: "who",
    inputSchema: { type: "object" },
    source: "native",
    async execute(_a, ctx) {
      return { output: (identityFrom(ctx) as McpPrincipal | undefined)?.subject ?? "", isError: false }
    },
  }
}

/** Mounts the handler at /mcp and the PRM listener under /.well-known/. */
async function listenMux(h: McpHandler, meta?: http.RequestListener) {
  const srv = http.createServer((req, res) => {
    if (meta && (req.url ?? "").startsWith("/.well-known/")) return meta(req, res)
    if ((req.url ?? "").split("?")[0] === "/mcp") return h(req, res)
    res.writeHead(404).end()
  })
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
  const base = `http://127.0.0.1:${(srv.address() as AddressInfo).port}`
  return {
    base,
    close: () =>
      new Promise<void>((r) => {
        srv.closeAllConnections()
        srv.close(() => r())
      }),
  }
}

test("2026: default JWT verifier against the dev AS", async () => {
  const as = await startMcpDevAuthServer()
  const tk = await newTK()
  tk.register(whoTool())
  const auth = new McpServerAuth({ issuer: as.issuer, scopes: { "*": ["mcp"], who: ["mcp:who"] } })
  const s = await listenMux(tk.mcpHandler({ protocolVersions: [V26], authenticate: auth.authenticate }), auth.metadata())
  try {
    const u = s.base + "/mcp"
    const now = Math.floor(Date.now() / 1000)
    const mint = (over: Record<string, unknown> = {}) =>
      as.mint({ iss: as.issuer, sub: "dev-user", aud: u, scope: "mcp mcp:who", exp: now + 60, ...over })
    const ok = await post26(u, mint(), "tools/call", { name: "who", arguments: {} })
    assert.equal(ok.status, 200)
    assert.equal(ok.body.result.content[0].text, "dev-user")
    const good = mint()
    const bad: Record<string, string> = {
      "wrong audience": mint({ aud: "https://other.example/mcp" }),
      "aud array miss": mint({ aud: ["x"] }),
      expired: mint({ exp: now - 1 }),
      "wrong issuer": mint({ iss: "https://evil.example" }),
      tampered: good.slice(0, 20) + (good[20] === "x" ? "y" : "x") + good.slice(21),
      "not a jwt": "opaque",
    }
    for (const [name, tok] of Object.entries(bad)) {
      const r = await post26(u, tok, "server/discover")
      const www = r.headers.get("www-authenticate") ?? ""
      assert.equal(r.status, 401, name)
      assert.ok(www.includes('error="invalid_token"'), name + ": " + www)
      assert.ok(!www.includes(tok), name + ": token leaked")
      assert.deepEqual(r.body, { error: "unauthorized" })
    }
    const up = await post26(u, mint({ scope: "mcp" }), "tools/call", { name: "who", arguments: {} })
    assert.equal(up.status, 403)
    assert.equal(
      up.headers.get("www-authenticate"),
      `Bearer resource_metadata="${s.base}/.well-known/oauth-protected-resource/mcp", scope="mcp mcp:who", error="insufficient_scope"`,
    )
    const prm = await (await fetch(s.base + "/.well-known/oauth-protected-resource/mcp")).json()
    assert.deepEqual(prm, {
      resource: u,
      authorization_servers: [as.issuer],
      scopes_supported: ["mcp"],
      bearer_methods_supported: ["header"],
    })
  } finally {
    await s.close()
    await as.close()
    await tk.close()
  }
})

/** Authorization code + PKCE against the dev AS, no browser. */
async function pkceLogin(as: McpDevAuthServer, clientId: string, redirect: string, resource: string, scope: string) {
  const verifier = "v-" + "a".repeat(50)
  const challenge = createHash("sha256").update(verifier).digest("base64url")
  const q = new URLSearchParams({
    response_type: "code",
    client_id: clientId,
    redirect_uri: redirect,
    state: "s1",
    code_challenge: challenge,
    code_challenge_method: "S256",
    resource,
    scope,
  })
  const r = await fetch(as.issuer + "/authorize?" + q, { redirect: "manual" })
  assert.equal(r.status, 302)
  const cb = new URL(r.headers.get("location")!).searchParams
  assert.equal(cb.get("iss"), as.issuer)
  assert.equal(cb.get("state"), "s1")
  assert.ok(cb.get("code"))
  const tr = await fetch(as.issuer + "/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code: cb.get("code")!,
      code_verifier: verifier,
      client_id: clientId,
      redirect_uri: redirect,
      resource,
    }),
  })
  const tok = await tr.json()
  assert.equal(tr.status, 200, JSON.stringify(tok))
  return tok
}

test("2026: dev AS — pre-registered and CIMD clients, refresh, unknown client, counter", async () => {
  let docUrl = ""
  const cimd = http.createServer((_req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" })
    res.end(JSON.stringify({ client_id: docUrl, client_name: "CIMD app", redirect_uris: ["http://127.0.0.1/cb"] }))
  })
  await new Promise<void>((r) => cimd.listen(0, "127.0.0.1", r))
  docUrl = `http://127.0.0.1:${(cimd.address() as AddressInfo).port}/client.json`
  const as = await startMcpDevAuthServer({ clients: { app: ["http://127.0.0.1/cb"] } })
  const tk = await newTK()
  tk.register(whoTool())
  const auth = new McpServerAuth({ issuer: as.issuer, scopes: { "*": ["mcp"] } })
  const s = await listenMux(tk.mcpHandler({ protocolVersions: [V26], authenticate: auth.authenticate }))
  try {
    const u = s.base + "/mcp"
    for (const cid of ["app", docUrl]) {
      const tok = await pkceLogin(as, cid, "http://127.0.0.1:5555/cb", u, "mcp")
      const r = await post26(u, tok.access_token, "tools/call", { name: "who", arguments: {} })
      assert.equal(r.status, 200, cid)
      assert.equal(r.body.result.content[0].text, "dev-user")
      const rr = await fetch(as.issuer + "/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: tok.refresh_token, client_id: cid, resource: u }),
      })
      assert.equal(rr.status, 200, "refresh " + cid)
      const rt = await rr.json()
      assert.notEqual(rt.refresh_token, tok.refresh_token, "refresh rotates")
      const again = await post26(u, rt.access_token, "tools/call", { name: "who", arguments: {} })
      assert.equal(again.status, 200)
    }
    const unknown = await fetch(as.issuer + "/authorize?client_id=nobody&redirect_uri=http://127.0.0.1/cb", { redirect: "manual" })
    assert.equal(unknown.status, 400)
    await unknown.text()
    assert.equal(as.authorizations(), 3)
  } finally {
    await s.close()
    await as.close()
    await tk.close()
    cimd.closeAllConnections()
    await new Promise<void>((r) => cimd.close(() => r()))
  }
})

test("2026: onCall fires (throws isolated), prompts envelope, discover capabilities", async () => {
  const events: McpCallEvent[] = []
  const tk = await newTK()
  tk.register(whoTool())
  const s = await listen(
    tk.mcpHandler({
      protocolVersions: [V26],
      onCall: (ev) => {
        events.push(ev)
        throw new Error("isolated")
      },
      listPrompts: () => [{ name: "p", description: "d" }],
      getPrompt: (_ctx, name) => ({ messages: [{ role: "user", text: "hi " + name }] }),
    }),
  )
  try {
    const r = await post26(s.url, "", "tools/call", { name: "who", arguments: {} })
    assert.equal(r.status, 200)
    assert.equal(events.length, 1)
    assert.equal(events[0]!.name, "who")
    const d = await post26(s.url, "", "server/discover")
    const caps = d.body.result.capabilities
    assert.ok(caps.prompts)
    assert.equal(caps.extensions, undefined)
    assert.equal(caps.resources, undefined)
    const g = await post26(s.url, "", "prompts/get", { name: "p" })
    assert.equal(g.body.result.messages[0].content.text, "hi p")
    assert.equal(g.body.result.resultType, "complete")
    const get = await fetch(s.url, { headers: { Accept: "text/event-stream" } })
    assert.equal(get.status, 405)
    assert.equal(get.headers.get("allow"), "POST")
    await get.text()
  } finally {
    await s.close()
    await tk.close()
  }
})

test("2026: loadMcpSkills on examples/skills", () => {
  const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../examples/skills")
  const skills = loadMcpSkills(dir)
  assert.equal(skills.length, 1)
  assert.equal(skills[0]!.name, "hello-world")
  assert.deepEqual(
    skills[0]!.files.map((f) => f.path),
    ["SKILL.md", "scripts/greet.sh"],
  )
  assert.equal(typeof skills[0]!.frontmatter?.description, "string")
})
