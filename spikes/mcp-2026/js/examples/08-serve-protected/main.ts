// Example 08 — serve MCP tools behind OAuth: one issuer line, per-tool scopes, the caller's
// identity in the handler, and a custom verify hook for opaque tokens.
//
// Run: node examples/08-serve-protected/main.ts   (hermetic: everything runs on 127.0.0.1)
//
// With server.auth set the server: serves Protected Resource Metadata at
// /.well-known/oauth-protected-resource/mcp, answers 401 + WWW-Authenticate without a valid
// token, 403 insufficient_scope when a tool needs more, and verifies RS256 JWTs against the
// issuer's JWKS (signature, iss, aud = this server's URL, exp).
import * as mcp from "../../src/index.ts"

const as = await mcp.DevAuthServer.start() // DEV/TEST ONLY. Production: your Keycloak / Entra / Auth0 issuer.
as.clients["my-app"] = ["http://127.0.0.1/callback"]

// ---- (1) the server: issuer one-liner + per-tool scope + principal ----
const srv = new mcp.Server()
  .tool("whoami", "Who is calling", (_args, ctx) => {
    const p = ctx.principal! // subject, scopes, claims of the verified token
    return `hello ${p.subject} (${p.scopes.join(" ")})`
  })
  .tool("delete_all", "Dangerous admin tool", () => "deleted")
srv.auth = new mcp.ServerAuth({
  issuer: as.issuer, // the whole required config
  scopes: {
    "*": ["mcp:read"], // every request
    delete_all: ["mcp:admin"], // additionally required for this tool
  },
})
const ts = await srv.listen() // production: http.createServer(srv.listener).listen(port)
// ---- end server ----

// No token: 401 with WWW-Authenticate pointing at the resource metadata.
const resp = await fetch(ts.url, {
  method: "POST",
  headers: { "Content-Type": "application/json", "MCP-Protocol-Version": "2026-07-28", "Mcp-Method": "tools/list" },
  body: `{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}`,
})
await resp.body?.cancel()
console.log("no token:", resp.status, "| WWW-Authenticate has resource_metadata:",
  (resp.headers.get("WWW-Authenticate") ?? "").includes("resource_metadata="))

// The metadata a client discovers (RFC 9728): which AS to use and which scopes exist.
const prm = (await (await fetch(ts.url.replace("/mcp", "/.well-known/oauth-protected-resource/mcp"))).json()) as { authorization_servers: string[]; scopes_supported: string[] }
console.log("metadata: issuer ok:", prm.authorization_servers[0] === as.issuer, `| scopes: [${prm.scopes_supported.join(" ")}]`)

// A logged-in client (see example 06): whoami gets the principal; delete_all steps up.
const c = new mcp.Client(ts.url, {
  auth: new mcp.OAuth({ clientId: "my-app", authorizer: new mcp.LoopbackAuthorizer({ open: as.approve }) }),
})
let res = await c.callTool("whoami")
console.log("whoami:", mcp.text(res))
res = await c.callTool("delete_all")
console.log("delete_all after step-up:", mcp.text(res))
res = await c.callTool("whoami")
console.log("whoami:", mcp.text(res))

// ---- (2) custom verify: opaque API keys / token introspection instead of JWT ----
const keys: Record<string, mcp.Principal> = { // e.g. a lookup in your DB, or an RFC 7662 introspection call
  "svc-key-123": { subject: "billing-service", scopes: ["mcp:read"] },
}
const srv2 = new mcp.Server().tool("whoami", "Who is calling", (_args, ctx) => "hello " + ctx.principal!.subject)
srv2.auth = new mcp.ServerAuth({
  issuer: as.issuer, // still advertised in the resource metadata
  scopes: { "*": ["mcp:read"] },
  verify: (token) => {
    const p = keys[token]
    if (!p) throw new Error("unknown key") // -> 401 invalid_token
    return p
  },
})
const ts2 = await srv2.listen()

// A service caller with a static key: a plain client with an extra header.
const svc = new mcp.Client(ts2.url, { headers: { Authorization: "Bearer svc-key-123" } })
res = await svc.callTool("whoami")
console.log("verify hook:", mcp.text(res))

const bad = new mcp.Client(ts2.url, { headers: { Authorization: "Bearer wrong" } })
const rejected = await bad.callTool("whoami").then(() => false, () => true)
console.log("wrong key rejected:", rejected)

await ts.close()
await ts2.close()
await as.close()
