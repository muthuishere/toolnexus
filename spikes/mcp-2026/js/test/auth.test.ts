// Port of auth_test.go: client OAuth (auto + pause modes) and server-side auth.
import { test } from "node:test"
import assert from "node:assert/strict"
import * as mcp from "../src/index.ts"
import { claims, expireToken, newEnv, pending, rawCall, rejects } from "./helpers.ts"

test("pause / resume", async (t) => {
  const e = await newEnv(t)
  const c = e.pauseClient()
  const p = await pending(c.callTool("echo", { text: "after login" }))
  assert.ok(p.kind === "authorization" && p.id && p.url.startsWith(e.as.issuer + "/authorize?") && p.prompt, JSON.stringify(p))
  assert.match(p.expiresAt, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/)
  const cb = await e.visit(p.url)
  const q = e.as.lastAuthorizeQuery
  assert.equal(q.get("resource"), e.url)
  assert.equal(q.get("code_challenge_method"), "S256")
  assert.equal(q.get("scope"), "mcp:read")
  assert.equal(mcp.text(await c.auth!.resume(p.id, cb)), "after login")
  await assert.rejects(c.auth!.resume(p.id, cb)) // second resume of the same ID must fail
  assert.equal(mcp.text(await c.callTool("echo", { text: "again" })), "again") // reuses the token
  assert.equal(e.as.authorizations, 1)
})

test("auto mode: principal reaches the handler", async (t) => {
  const e = await newEnv(t)
  assert.equal(mcp.text(await e.autoClient().callTool("whoami", {})), "dev-user mcp:read")
})

test("iss mismatch rejected, nothing stored", async (t) => {
  const e = await newEnv(t)
  e.as.issOverride = "https://evil.example"
  const c = e.autoClient()
  await rejects(c.callTool("echo", { text: "x" }), /iss mismatch/)
  assert.equal(await c.auth!.store.load(e.url), undefined)
})

test("state mismatch rejected", async (t) => {
  const e = await newEnv(t)
  const c = e.pauseClient()
  const p = await pending(c.callTool("echo", { text: "x" }))
  const cb = (await e.visit(p.url)).replace(/state=[^&]*/, "state=forged")
  await rejects(c.auth!.resume(p.id, cb), /state mismatch/)
})

test("PKCE verified by the AS", async (t) => {
  const e = await newEnv(t)
  const c = e.pauseClient()
  const p = await pending(c.callTool("echo", { text: "x" }))
  const cb = await e.visit(p.url)
  c.auth!.flows.get(p.id)!.verifier = "not-the-verifier"
  await rejects(c.auth!.resume(p.id, cb), /invalid_grant/)
})

test("refresh on expiry (rotation)", async (t) => {
  const e = await newEnv(t)
  const c = e.autoClient()
  await c.callTool("echo", { text: "1" })
  const old = (await c.auth!.store.load(e.url))!.accessToken
  await expireToken(c, e.url)
  assert.equal(mcp.text(await c.callTool("echo", { text: "2" })), "2")
  const tok = (await c.auth!.store.load(e.url))!
  assert.deepEqual([e.as.refreshes, e.as.authorizations, tok.accessToken !== old], [1, 1, true])
})

test("refresh is single-flight across concurrent callers", async (t) => {
  const e = await newEnv(t)
  const c = e.autoClient()
  await c.callTool("echo", { text: "1" })
  await expireToken(c, e.url)
  const rs = await Promise.all(Array.from({ length: 10 }, (_, i) => c.callTool("echo", { text: `c${i}` })))
  rs.forEach((r, i) => assert.equal(mcp.text(r), `c${i}`))
  assert.deepEqual([e.as.refreshes, e.as.authorizations], [1, 1])
})

test("cold login herd: N concurrent 401s -> one authorization", async (t) => {
  const e = await newEnv(t)
  const c = e.autoClient()
  const rs = await Promise.all(Array.from({ length: 8 }, (_, i) => c.callTool("echo", { text: `h${i}` })))
  rs.forEach((r, i) => assert.equal(mcp.text(r), `h${i}`))
  assert.equal(e.as.authorizations, 1)
})

test("insufficient_scope step-up with the union of scopes", async (t) => {
  const e = await newEnv(t)
  const c = e.autoClient()
  await c.callTool("echo", { text: "1" })
  const r = await c.callTool("greet", {}, () => ({ action: "accept", content: { name: "scoped" } }))
  assert.equal(mcp.text(r), "Hello, scoped!")
  const sc = (e.as.lastAuthorizeQuery.get("scope") ?? "").split(" ")
  assert.equal(e.as.authorizations, 2)
  assert.deepEqual(sc.sort(), ["mcp:greet", "mcp:read"])
  await c.callTool("echo", { text: "still" })
  assert.equal(e.as.authorizations, 2)
})

test("PRM well-known fallback; no client identity is a clear error", async (t) => {
  const e = await newEnv(t)
  const m = await new mcp.OAuth().fetchPRM(e.url, "")
  assert.equal(m.resource, e.url)
  const c = new mcp.Client(e.url, { auth: new mcp.OAuth({ authorizer: new mcp.PauseAuthorizer({ redirect: "http://127.0.0.1:9/callback" }) }) })
  await rejects(c.call("tools/list"), new RegExp("register an app with " + e.as.issuer.replace(/[.]/g, "\\.")))
})

test("AS metadata discovery; a non-https AS is refused", async (t) => {
  const e = await newEnv(t)
  const md = await mcp.discoverAS(mcp.defaultHttp, e.as.issuer)
  assert.equal(md.issuer, e.as.issuer)
  const s = mcp.fixtureServer()
  s.auth = new mcp.ServerAuth({ issuer: "http://127.0.0.1:1/plain" })
  const ts = await s.listen()
  t.after(() => ts.close())
  await rejects(new mcp.Client(ts.url, { auth: new mcp.OAuth({ clientId: "x" }) }).call("tools/list"), /must use https/)
})

test("parseChallenge", () => {
  assert.deepEqual(mcp.parseChallenge('Bearer resource_metadata="https://a/b", scope="x y", error=insufficient_scope'),
    { resource_metadata: "https://a/b", scope: "x y", error: "insufficient_scope" })
  assert.deepEqual(mcp.parseChallenge('Bearer realm="a\\"b"'), { realm: 'a"b' })
})

test("Token prints redacted", () => {
  const t = new mcp.Token({ accessToken: "secret-at", refreshToken: "secret-rt", issuer: "https://as" })
  assert.equal(String(t), "Token{issuer=https://as, redacted}")
  assert.ok(!JSON.stringify({ t }).includes("secret"))
})

// --- server-side ---

test("server: JWT via JWKS and principal", async (t) => {
  const e = await newEnv(t)
  const r = await rawCall(e, claims(e), "whoami")
  assert.equal(r.status, 200)
  assert.ok(r.body.includes("alice mcp:read"), r.body)
})

test("server rejects missing / wrong aud / expired / wrong iss / tampered", async (t) => {
  const e = await newEnv(t)
  const cases: Record<string, string> = {
    missing: "",
    "wrong audience": claims(e, (c) => (c.aud = "https://other.example/mcp")),
    expired: claims(e, (c) => (c.exp = Math.floor(Date.now() / 1000) - 60)),
    "wrong issuer": claims(e, (c) => (c.iss = "https://evil.example")),
    tampered: claims(e) + "x",
    "alg none": claims(e).split(".").slice(0, 2).join(".").replace(/^[^.]+/, Buffer.from('{"alg":"none","kid":"dev"}').toString("base64url")) + ".",
  }
  for (const [name, tok] of Object.entries(cases)) {
    const r = await rawCall(e, tok, "echo")
    assert.equal(r.status, 401, name)
    assert.ok(r.www.includes(`resource_metadata="${e.url.replace("/mcp", "")}/.well-known/oauth-protected-resource/mcp"`), `${name}: ${r.www}`)
    if (tok) assert.ok(r.www.includes('error="invalid_token"'), name)
    assert.ok(!r.body.includes(tok || "\u0000"), "never echo the token")
  }
})

test("server: per-tool scope -> 403 with the full scope set", async (t) => {
  const e = await newEnv(t)
  let r = await rawCall(e, claims(e), "greet")
  assert.equal(r.status, 403)
  assert.ok(r.www.includes('error="insufficient_scope"') && r.www.includes('scope="mcp:read mcp:greet"'), r.www)
  r = await rawCall(e, claims(e, (c) => (c.scope = "mcp:read mcp:greet")), "greet")
  assert.equal(r.status, 200)
})

test("server: custom verify replaces JWT", async (t) => {
  const e = await newEnv(t)
  e.server.auth!.verify = (tok) => {
    if (tok !== "opaque-ok") throw new Error("nope")
    return { subject: "svc", scopes: ["mcp:read"] }
  }
  const r = await rawCall(e, "opaque-ok", "whoami")
  assert.equal(r.status, 200)
  assert.ok(r.body.includes("svc mcp:read"))
  assert.equal((await rawCall(e, claims(e), "whoami")).status, 401) // a JWT does not bypass verify
})

test("server: PRM at both well-known URIs", async (t) => {
  const e = await newEnv(t)
  for (const p of ["/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource"]) {
    const m = await mcp.getJSON(mcp.defaultHttp, e.url.replace("/mcp", p))
    assert.deepEqual(m, { resource: e.url, authorization_servers: [e.as.issuer], scopes_supported: ["mcp:read"], bearer_methods_supported: ["header"] })
  }
})

test("a failing browser opener surfaces its error (no 5-minute wait)", async (t) => {
  const e = await newEnv(t)
  const c = e.autoClient()
  c.auth!.authorizer = new mcp.LoopbackAuthorizer({ open: () => { throw new Error("no browser") } })
  const started = Date.now()
  await rejects(c.callTool("echo", { text: "x" }), /no browser/)
  assert.ok(Date.now() - started < 5000)
})

test("webHandler: pause -> 302 to AS -> callback resumes the original call", async (t) => {
  const e = await newEnv(t)
  const http = await import("node:http")
  const srv = http.createServer()
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
  t.after(() => mcp.closeServer(srv))
  const app = `http://127.0.0.1:${(srv.address() as { port: number }).port}`
  e.as.clients.web = [app + "/tool"]
  const c = new mcp.Client(e.url, { auth: new mcp.OAuth({ clientId: "web", authorizer: new mcp.PauseAuthorizer({ redirect: app + "/tool" }) }) })
  srv.on("request", c.auth!.webHandler(() => c.callTool("echo", { text: "via web" })))
  const page = await mcp.browse(app + "/tool")
  assert.deepEqual([page.status, page.body], [200, "via web"])
  const bogus = await mcp.defaultHttp({ method: "GET", url: app + "/tool?state=nope&code=x" })
  assert.equal(bogus.status, 502)
})
