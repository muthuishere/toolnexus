// Live tests against a real Keycloak (../golang/keycloak/docker-compose.yml). Skipped unless
// KEYCLOAK=1, so the default run stays hermetic.
import { test, type TestContext } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import * as mcp from "../src/index.ts"
import { KeycloakBrowser } from "../examples/09-keycloak/headless.ts"
import { decodeSeg } from "../src/serverauth.ts"

const skip = process.env.KEYCLOAK !== "1" && "set KEYCLOAK=1 with the keycloak compose stack running"
const kcIssuer = "http://localhost:8180/realms/toolnexus"
const kcMCP = "http://127.0.0.1:7199/mcp" // the realm's audience mapper pins aud to this

/** Records every OAuth request (URL + form), never headers. */
function recorder() {
  const reqs: { line: string; form: URLSearchParams }[] = []
  const fn: mcp.HttpFn = async (r) => {
    const res = await mcp.defaultHttp({ ...r, timeoutMs: 15_000 })
    reqs.push({ line: `${r.method} ${r.url} -> ${res.status}`, form: new URLSearchParams(r.method === "POST" ? r.body ?? "" : "") })
    return res
  }
  return { fn, reqs, tokenForms: () => reqs.filter((x) => x.line.includes("/protocol/openid-connect/token")).map((x) => x.form) }
}

async function kcEnv(t: TestContext, mod?: (o: mcp.OAuth) => void) {
  const srv = mcp.fixtureServer().tool("whoami", "who is calling", (_a, ctx) => ctx.principal!.subject + " " + ctx.principal!.scopes.join(","))
  srv.auth = new mcp.ServerAuth({ issuer: kcIssuer, audience: kcMCP, scopes: { "*": ["mcp:tools"], greet: ["mcp:greet"] } })
  const ts = await srv.listen(7199)
  t.after(() => ts.close())
  const rec = recorder()
  const browser = new KeycloakBrowser("alice", "alice-test-pw")
  const store = new mcp.MemoryTokenStore()
  const o = new mcp.OAuth({ http: rec.fn, allowHttp: true, clientIds: { [kcIssuer]: "toolnexus-mcp" }, store, authorizer: new mcp.LoopbackAuthorizer({ open: browser.open }) })
  mod?.(o)
  return { rec, browser, store, client: () => new mcp.Client(kcMCP, { auth: o }), o }
}

test("keycloak: end to end (RFC 8414 path-insert, iss, resource on code exchange)", { skip }, async (t) => {
  const e = await kcEnv(t)
  const r = await e.client().callTool("whoami", {})
  assert.match(mcp.text(r), /mcp:tools/)
  const asmd = e.rec.reqs.find((x) => x.line.includes("/.well-known/") && x.line.endsWith("-> 200") && !x.line.includes("oauth-protected-resource"))
  assert.ok(asmd?.line.includes("/.well-known/oauth-authorization-server/realms/toolnexus"), asmd?.line)
  assert.equal(new URL(e.browser.callbacks[0]).searchParams.get("iss"), kcIssuer)
  const tf = e.rec.tokenForms()
  assert.equal(tf.length, 1)
  assert.equal(tf[0].get("resource"), kcMCP)
  assert.ok(tf[0].get("code_verifier"))
  const tok = e.store.load(kcMCP)!
  const c = decodeSeg(tok.accessToken.split(".")[1])
  t.diagnostic(`aud=${JSON.stringify(c.aud)} scope=${c.scope} azp=${c.azp}`)
})

test("keycloak: the 8 checks through a real AS", { skip }, async (t) => {
  const e = await kcEnv(t)
  const lines: string[] = []
  assert.ok(await mcp.runChecks(() => e.client(), (l) => lines.push(l)), lines.join("\n"))
  t.diagnostic(`login forms submitted: ${e.browser.forms}, callbacks: ${e.browser.callbacks.length}`)
})

for (const omit of [false, true]) {
  test(`keycloak: refresh (omitRefreshResource=${omit})`, { skip }, async (t) => {
    const e = await kcEnv(t, (o) => (o.omitRefreshResource = omit))
    const c = e.client()
    await c.callTool("echo", { text: "a" })
    const old = e.store.load(kcMCP)!
    e.store.save(kcMCP, new mcp.Token({ ...old, expiry: Date.now() - 60_000 }))
    assert.equal(mcp.text(await c.callTool("echo", { text: "b" })), "b")
    const last = e.rec.tokenForms().at(-1)!
    assert.equal(last.get("grant_type"), "refresh_token")
    assert.equal(last.has("resource"), !omit)
    assert.notEqual(e.store.load(kcMCP)!.accessToken, old.accessToken)
    assert.equal(e.browser.forms, 1)
  })
}

test("keycloak: step-up to mcp:greet (SSO, no second form)", { skip }, async (t) => {
  const e = await kcEnv(t)
  const c = e.client()
  await c.callTool("echo", { text: "a" })
  const r = await c.callTool("greet", {}, () => ({ action: "accept", content: { name: "Alice" } }))
  assert.equal(mcp.text(r), "Hello, Alice!")
  assert.equal(e.browser.callbacks.length, 2)
  assert.ok(e.store.load(kcMCP)!.scopes.includes("mcp:greet"))
})

test("keycloak: pause / resume", { skip }, async (t) => {
  const e = await kcEnv(t, (o) => (o.authorizer = new mcp.PauseAuthorizer({ redirect: "http://127.0.0.1:9/callback" })))
  const c = e.client()
  let p: mcp.Pending | undefined
  await c.callTool("echo", { text: "after login" }).catch((err) => (p = mcp.pendingOf(err)))
  assert.ok(p)
  const cb = await e.browser.login(p.url)
  assert.equal(mcp.text(await c.auth!.resume(p.id, cb)), "after login")
})

test("keycloak: a token for another audience is rejected", { skip }, async (t) => {
  const e = await kcEnv(t)
  await e.client().callTool("echo", { text: "a" })
  const tok = e.store.load(kcMCP)!.accessToken
  const other = mcp.fixtureServer()
  other.auth = new mcp.ServerAuth({ issuer: kcIssuer, audience: "http://127.0.0.1:7200/mcp", scopes: { "*": ["mcp:tools"] } })
  const ts = await other.listen(7200)
  t.after(() => ts.close())
  const call = (url: string) => new mcp.Client(url, { headers: { Authorization: "Bearer " + tok } }).call("tools/list")
  await assert.rejects(call("http://127.0.0.1:7200/mcp"), /HTTP 401/)
  assert.equal((await call(kcMCP)).resultType, "complete")
})

test("keycloak: CIMD client", { skip }, async (t) => {
  let docUrl = process.env.KEYCLOAK_CIMD_URL ?? ""
  if (!docUrl) {
    docUrl = "http://host.docker.internal:7198/client.json"
    const doc = http.createServer((_q, s) => s.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
      client_id: docUrl, client_name: "toolnexus spike", redirect_uris: ["http://127.0.0.1/callback"],
      grant_types: ["authorization_code", "refresh_token"], response_types: ["code"], token_endpoint_auth_method: "none",
    })))
    await new Promise<void>((r) => doc.listen(7198, "0.0.0.0", r))
    t.after(() => mcp.closeServer(doc))
  }
  const e = await kcEnv(t, (o) => { o.clientIds = {}; o.clientMetadataUrl = docUrl })
  assert.equal(mcp.text(await e.client().callTool("echo", { text: "cimd" })), "cimd")
  t.diagnostic(`consent screens accepted: ${e.browser.consents}`)
})
