import { test } from "node:test"
import assert from "node:assert/strict"
import * as mcp from "../src/index.ts"
import { newEnv, quiet } from "./helpers.ts"

test("8 checks, unprotected", async (t) => {
  const s = await mcp.fixtureServer().listen()
  t.after(() => s.close())
  assert.ok(await mcp.runChecks(s.url, quiet))
})

test("8 checks, protected, auto mode", async (t) => {
  const e = await newEnv(t)
  const lines: string[] = []
  assert.ok(await mcp.runChecks(e.autoClient, (l) => lines.push(l)), lines.join("\n"))
})

test("server: -32020 / -32022 / 404 / 405 / parse error / notification / 413", async (t) => {
  const s = await mcp.fixtureServer().listen()
  t.after(() => s.close())
  const post = (headers: Record<string, string>, body: string) => mcp.defaultHttp({ method: "POST", url: s.url, headers: { "Content-Type": "application/json", ...headers }, body })
  const ok = { "MCP-Protocol-Version": mcp.PROTOCOL_VERSION, "Mcp-Method": "tools/list" }
  const body = (method: string, v = mcp.PROTOCOL_VERSION) => JSON.stringify({ jsonrpc: "2.0", id: 7, method, params: { _meta: { [mcp.META_VERSION]: v } } })
  let r = await post({ "Mcp-Method": "tools/list" }, body("tools/list"))
  assert.equal(r.status, 400)
  assert.equal(JSON.parse(r.body).error.code, -32020)
  r = await post({ ...ok, "MCP-Protocol-Version": "1900-01-01" }, body("tools/list", "1900-01-01"))
  assert.deepEqual(JSON.parse(r.body).error, { code: -32022, message: "Unsupported protocol version", data: { supported: [mcp.PROTOCOL_VERSION], requested: "1900-01-01" } })
  r = await post({ ...ok, "Mcp-Method": "nope/x" }, body("nope/x"))
  assert.equal(r.status, 404)
  assert.equal(JSON.parse(r.body).error.code, -32601)
  r = await post(ok, "{not json")
  assert.equal(JSON.parse(r.body).error.code, -32700)
  r = await post(ok, JSON.stringify({ jsonrpc: "2.0", method: "notifications/x" }))
  assert.equal(r.status, 202)
  r = await post(ok, "x".repeat(mcp.MAX_BODY_BYTES + 10))
  assert.equal(r.status, 413)
  r = await mcp.defaultHttp({ method: "GET", url: s.url })
  assert.equal(r.status, 405)
  // base64 sentinel in Mcp-Name decodes to the body value
  const c = new mcp.Client(s.url)
  c.headerOverride = { "Mcp-Name": "=?base64?" + Buffer.from("echo").toString("base64") + "?=" }
  assert.equal(mcp.text(await c.callTool("echo", { text: "sentinel" })), "sentinel")
  assert.equal(mcp.encodeHeader("héllo"), "=?base64?aMOpbGxv?=")
  assert.equal(mcp.encodeHeader(" pad"), "=?base64?IHBhZA==?=")
  assert.equal(mcp.encodeHeader("plain_name"), "plain_name")
})

test("serverInfo in result _meta; missing resultType = complete; SSE final message", async (t) => {
  const s = await mcp.fixtureServer().listen()
  t.after(() => s.close())
  const r = await new mcp.Client(s.url).call("server/discover")
  assert.deepEqual(r._meta[mcp.META_SERVER_INFO], { name: "toolnexus-spike", version: "0.0.1" })
  assert.equal(r.ttlMs, 3600000)
  const fake: mcp.HttpFn = async () => ({
    status: 200, headers: { "content-type": "text/event-stream" },
    body: 'event: message\ndata: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}\n\ndata: {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}\n\n',
  })
  const sse = await new mcp.Client("http://x/mcp", { http: fake }).call("tools/list")
  assert.equal(sse.resultType, "complete")
})
