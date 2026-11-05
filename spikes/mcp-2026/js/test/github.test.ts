// Live OAuth against GitHub's hosted MCP server (speaks 2026-07-28). Read-only: tools/list + get_me.
// Gated: GITHUB_OAUTH=1, client id/secret injected by `sec run`. Tokens are never logged.
import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { createHash } from "node:crypto"
import http from "node:http"
import { promisify } from "node:util"
import * as mcp from "../src/index.ts"

const skip = process.env.GITHUB_OAUTH !== "1" && "set GITHUB_OAUTH=1 (run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET)"
const githubMCP = "https://api.githubcopilot.com/mcp/"
const chromeAgent = (u: string) => promisify(execFile)("chrome-agent", ["goto", u])
const creds = () => ({ clientId: process.env.GITHUB_MCP_CLIENT_ID, clientSecret: process.env.GITHUB_MCP_CLIENT_SECRET })

test("github: auto mode (loopback + chrome-agent browser)", { skip }, async (t) => {
  const c = new mcp.Client(githubMCP, { auth: new mcp.OAuth({ ...creds(), authorizer: new mcp.LoopbackAuthorizer({ open: chromeAgent }) }) })
  const r = await c.callTool("get_me", {})
  assert.equal(r.resultType, "complete")
  const l = await c.call("tools/list")
  t.diagnostic(`get_me content-items=${r.content?.length}; tools/list: ${l.tools.length} tools`)
})

test("github: pause / resume, then a forced refresh", { skip }, async (t) => {
  let deliver!: (u: string) => void
  const got = new Promise<string>((r) => (deliver = r))
  const host = http.createServer((q, s) => { deliver(`http://127.0.0.1:${port}${q.url}`); s.end("ok") })
  await new Promise<void>((r) => host.listen(0, "127.0.0.1", r))
  const port = (host.address() as { port: number }).port
  t.after(() => mcp.closeServer(host))
  const store = new mcp.MemoryTokenStore()
  const c = new mcp.Client(githubMCP, {
    auth: new mcp.OAuth({ ...creds(), store, authorizer: new mcp.PauseAuthorizer({ redirect: `http://127.0.0.1:${port}/callback` }) }),
  })
  let p: mcp.Pending | undefined
  await c.callTool("get_me", {}).catch((e) => (p = mcp.pendingOf(e)))
  assert.ok(p, "expected a pending authorization")
  await chromeAgent(p.url) // the user clicks the link
  const cb = await Promise.race([got, new Promise<string>((_, j) => setTimeout(() => j(new Error("no callback (consent page?)")), 120_000))])
  const r = await c.auth!.resume(p.id, cb)
  t.diagnostic(`resumed: get_me resultType=${r.resultType}`)
  const tok = store.load(githubMCP)
  assert.ok(tok?.refreshToken, "no refresh token stored (enable expiring user tokens on the OAuth App)")
  const before = createHash("sha256").update(tok.accessToken).digest("hex")
  store.save(githubMCP, new mcp.Token({ ...tok, expiry: Date.now() - 60_000 }))
  await c.call("tools/list")
  assert.notEqual(createHash("sha256").update(store.load(githubMCP)!.accessToken).digest("hex"), before, "access token not refreshed")
})
