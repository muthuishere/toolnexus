// Port of retry_test.go: side effects run exactly once across auth pauses, refresh-after-401 and
// step-up; a lost or failed response is never retried (spec issue #3394).
import { test } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import * as mcp from "../src/index.ts"
import { expireToken, newEnv, pending, rejects } from "./helpers.ts"

/** Registers "tick" (base scope) and "charge" (needs mcp:charge), each counting executions. */
function counted(n: { tick: number; charge: number }) {
  return (s: mcp.Server) => {
    s.tool("tick", "counts", () => (n.tick++, "tick"))
    s.tool("charge", "counts, extra scope", () => (n.charge++, "charged"))
    if (s.auth) s.auth.scopes.charge = ["mcp:charge"]
  }
}

test("pause -> resume executes once", async (t) => {
  const n = { tick: 0, charge: 0 }
  const e = await newEnv(t, counted(n))
  const c = e.pauseClient()
  const p = await pending(c.callTool("tick", {}))
  assert.equal(n.tick, 0, "paused call must not have executed")
  const cb = await e.visit(p.url)
  assert.equal(mcp.text(await c.auth!.resume(p.id, cb)), "tick")
  await assert.rejects(c.auth!.resume(p.id, cb))
  assert.equal(n.tick, 1)
})

test("refresh after a server 401 invalid_token executes once", async (t) => {
  const n = { tick: 0, charge: 0 }
  const e = await newEnv(t, counted(n))
  const c = e.autoClient()
  await c.callTool("tick", {})
  const tok = await c.auth!.store.load(e.url)
  // not expired client-side: the server's 401 invalid_token drives the refresh
  await c.auth!.store.save(e.url, new mcp.Token({ ...tok!, accessToken: "revoked-by-server" }))
  assert.equal(mcp.text(await c.callTool("tick", {})), "tick")
  assert.deepEqual([n.tick, e.as.refreshes, e.as.authorizations], [2, 1, 1])
})

test("401 -> login -> 403 -> step-up executes once", async (t) => {
  const n = { tick: 0, charge: 0 }
  const e = await newEnv(t, counted(n))
  assert.equal(mcp.text(await e.autoClient().callTool("charge", {})), "charged")
  assert.deepEqual([n.charge, e.as.authorizations], [1, 2])
})

test("a pause in a later MRTR round resumes THAT round (user not re-asked)", async (t) => {
  const e = await newEnv(t, (s) => delete s.auth!.scopes.greet)
  const c = e.pauseClient()
  let p = await pending(c.callTool("echo", { text: "login" }))
  await c.auth!.resume(p.id, await e.visit(p.url))
  let asked = 0
  p = await pending(c.callTool("greet", {}, async () => {
    asked++
    await c.auth!.store.delete(e.url) // token lost between rounds -> round 1 gets 401
    return { action: "accept", content: { name: "once" } }
  }))
  const r = await c.auth!.resume(p.id, await e.visit(p.url))
  assert.deepEqual([mcp.text(r), asked], ["Hello, once!", 1])
})

for (const mode of ["connection dropped", "502 after execution"]) {
  test(`lost or failed response is not retried: ${mode}`, async (t) => {
    const n = { tick: 0, charge: 0 }
    const s = mcp.fixtureServer()
    counted(n)(s)
    const real = await s.listen()
    t.after(() => real.close())
    const proxy = http.createServer(async (req, res) => {
      const chunks: Buffer[] = []
      for await (const ch of req) chunks.push(ch as Buffer)
      const headers: Record<string, string> = {}
      for (const [k, v] of Object.entries(req.headers)) if (typeof v === "string" && k !== "host" && k !== "content-length") headers[k] = v
      await mcp.defaultHttp({ method: "POST", url: real.url, headers, body: Buffer.concat(chunks).toString() }) // the tool runs; its response is lost
      if (mode === "502 after execution") return void res.writeHead(502).end("bad gateway")
      req.socket.destroy()
    })
    await new Promise<void>((r) => proxy.listen(0, "127.0.0.1", r))
    t.after(() => mcp.closeServer(proxy))
    const c = new mcp.Client(`http://127.0.0.1:${(proxy.address() as { port: number }).port}/mcp`)
    await assert.rejects(c.callTool("tick", {}))
    await new Promise((r) => setTimeout(r, 50)) // a hidden retry would land here
    assert.equal(n.tick, 1)
  })
}

test("resource on refresh is configurable (Entra-like AS)", async (t) => {
  for (const omit of [false, true]) {
    const e = await newEnv(t)
    e.as.rejectRefreshResource = true
    const c = e.autoClient()
    c.auth!.omitRefreshResource = omit
    await c.callTool("echo", { text: "1" })
    assert.equal(e.as.lastAuthorizeQuery.get("resource"), e.url)
    assert.equal(e.as.lastTokenForm.get("resource"), e.url, "code exchange must always send resource")
    await expireToken(c, e.url)
    assert.equal(mcp.text(await c.callTool("echo", { text: "2" })), "2")
    const { authorizations: a, refreshes: rf } = e.as.stats()
    if (omit) assert.deepEqual([rf, a, e.as.lastTokenForm.has("resource")], [1, 1, false])
    else assert.deepEqual([rf, a], [0, 2]) // default sends resource; Entra rejects -> falls back to a new login
  }
  const e = await newEnv(t) // default AS accepts resource on refresh and it is sent
  const c = e.autoClient()
  await c.callTool("echo", { text: "1" })
  await expireToken(c, e.url)
  await c.callTool("echo", { text: "2" })
  assert.equal(e.as.lastTokenForm.get("grant_type"), "refresh_token")
  assert.equal(e.as.lastTokenForm.get("resource"), e.url)
})

test("pre-registered confidential client: basic (default) / basic / post", async (t) => {
  for (const method of ["", "client_secret_basic", "client_secret_post"] as const) {
    const e = await newEnv(t)
    e.as.noCimd = true
    e.as.clients.conf = ["http://127.0.0.1/callback"]
    e.as.secrets = { conf: "test-only-secret" }
    const c = new mcp.Client(e.url, {
      auth: new mcp.OAuth({ clientId: "conf", clientSecret: "test-only-secret", tokenAuthMethod: method, authorizer: new mcp.LoopbackAuthorizer({ open: e.follow }) }),
    })
    assert.equal(mcp.text(await c.callTool("echo", { text: "ok" })), "ok", method)
    const f = e.as.lastTokenForm
    if (method === "client_secret_post") assert.ok(f.get("client_secret"), method)
    else assert.ok(!f.has("client_secret") && !f.has("client_id"), method)
    c.auth!.clientSecret = "wrong"
    await expireToken(c, e.url)
    c.auth!.authorizer = new mcp.PauseAuthorizer({ redirect: "http://127.0.0.1:9/callback" })
    await pending(c.callTool("echo", { text: "x" })) // wrong secret fails the refresh -> new authorization
  }
})

test("no CIMD support and no client id is a clear error, before any authorization", async (t) => {
  const e = await newEnv(t)
  e.as.noCimd = true
  await rejects(e.autoClient().callTool("echo", { text: "x" }),
    /does not support Client ID Metadata Documents.*register an app with https:\/\/127\.0\.0\.1:\d+\/dev and set OAuth\.clientId/)
  assert.equal(e.as.authorizations, 0)
})

test("concurrent MRTR calls do not cross-talk", async (t) => {
  const s = await mcp.fixtureServer().listen()
  t.after(() => s.close())
  const c = new mcp.Client(s.url)
  const rs = await Promise.all(Array.from({ length: 20 }, (_, i) =>
    c.callTool("greet", {}, async () => {
      await new Promise((r) => setTimeout(r, Math.random() * 10))
      return { action: "accept", content: { name: `u${i}` } }
    })))
  rs.forEach((r, i) => assert.equal(mcp.text(r), `Hello, u${i}!`))
})
