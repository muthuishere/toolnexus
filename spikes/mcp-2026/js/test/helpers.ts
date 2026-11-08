// Shared test harness: a dev AS + a protected MCP server (port of auth_test.go's env).
import assert from "node:assert/strict"
import type { TestContext } from "node:test"
import * as mcp from "../src/index.ts"

export interface Env {
  as: mcp.DevAuthServer
  server: mcp.Server
  url: string // canonical resource URI
  cimdUrl: string
  follow: (u: string) => Promise<void> // test "browser": follows redirects (auto mode lands on the loopback listener)
  visit: (u: string) => Promise<string> // AS redirect Location without following it (pause mode)
  autoClient: () => mcp.Client
  pauseClient: () => mcp.Client
}

/** Starts a dev AS + protected MCP server; cfg runs on the server before it starts serving. */
export async function newEnv(t: TestContext, ...cfg: ((s: mcp.Server) => void)[]): Promise<Env> {
  const as = await mcp.DevAuthServer.start()
  t.after(() => as.close())
  as.documents["client.json"] = {
    client_id: as.documentUrl("client.json"), client_name: "toolnexus spike", redirect_uris: ["http://127.0.0.1/callback"],
    grant_types: ["authorization_code", "refresh_token"], response_types: ["code"], token_endpoint_auth_method: "none",
  }
  const server = mcp.fixtureServer()
  server.auth = new mcp.ServerAuth({ issuer: as.issuer, scopes: { "*": ["mcp:read"], greet: ["mcp:greet"] } })
  server.tool("whoami", "who is calling", (_a, ctx) => {
    if (!ctx.principal) throw new Error("no principal")
    return ctx.principal.subject + " " + ctx.principal.scopes.join(",")
  })
  for (const f of cfg) f(server)
  const ts = await server.listen()
  t.after(() => ts.close())
  const e: Env = {
    as, server, url: ts.url, cimdUrl: as.documentUrl("client.json"),
    follow: as.approve,
    visit: (u) => as.visit(u),
    autoClient: () => new mcp.Client(e.url, {
      auth: new mcp.OAuth({ clientMetadataUrl: e.cimdUrl, authorizer: new mcp.LoopbackAuthorizer({ open: e.follow }) }),
    }),
    pauseClient: () => {
      as.clients["spike-prereg"] = ["http://127.0.0.1:9/callback"]
      return new mcp.Client(e.url, {
        auth: new mcp.OAuth({ clientIds: { [as.issuer]: "spike-prereg" }, authorizer: new mcp.PauseAuthorizer({ redirect: "http://127.0.0.1:9/callback" }) }),
      })
    },
  }
  return e
}

/** The Pending a call threw (fails the test otherwise). */
export async function pending(p: Promise<unknown>): Promise<mcp.Pending> {
  try {
    await p
  } catch (e) {
    const pd = mcp.pendingOf(e)
    if (pd) return pd
    assert.fail(`want PendingError, got ${(e as Error)?.message ?? e}`)
  }
  assert.fail("want PendingError, got success")
}

export async function rejects(p: Promise<unknown>, re: RegExp): Promise<void> {
  await assert.rejects(p, (e: Error) => re.test(e.message))
}

export async function expireToken(c: mcp.Client, url: string): Promise<void> {
  const tok = await c.auth!.store.load(url)
  await c.auth!.store.save(url, new mcp.Token({ ...tok!, expiry: Date.now() - 60_000 }))
}

/** A raw tools/call (server-side tests): returns status, WWW-Authenticate and body. */
export async function rawCall(e: Env, token: string, tool: string): Promise<{ status: number; www: string; body: string }> {
  const h: Record<string, string> = {
    "Content-Type": "application/json", "MCP-Protocol-Version": mcp.PROTOCOL_VERSION, "Mcp-Method": "tools/call", "Mcp-Name": tool,
  }
  if (token) h.Authorization = "Bearer " + token
  const r = await mcp.defaultHttp({
    method: "POST", url: e.url, headers: h,
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: tool, arguments: { text: "x" }, _meta: { [mcp.META_VERSION]: mcp.PROTOCOL_VERSION } } }),
  })
  return { status: r.status, www: r.headers["www-authenticate"] ?? "", body: r.body }
}

export function claims(e: Env, mut?: (c: Record<string, unknown>) => void): string {
  const c: Record<string, unknown> = { iss: e.as.issuer, sub: "alice", aud: e.url, scope: "mcp:read", exp: Math.floor(Date.now() / 1000) + 3600 }
  mut?.(c)
  return e.as.mint(c)
}

export const quiet = () => {}
