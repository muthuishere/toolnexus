// Example 06 — call an OAuth-protected MCP server with zero auth configuration (auto mode).
//
// Run: node examples/06-oauth-zero-config/main.ts   (hermetic: everything runs on 127.0.0.1)
//
// The client learns everything from the server's 401: Protected Resource Metadata -> the
// authorization server's metadata -> PKCE S256 login with `resource` -> token -> retry.
// You only say WHO the client is: a pre-registered clientId, or a CIMD clientMetadataUrl.
// There is no Dynamic Client Registration.
import * as mcp from "../../src/index.ts"

// ---- demo harness: an authorization server + a protected MCP server, both in-process ----
const as = await mcp.DevAuthServer.start() // DEV/TEST ONLY: auto-approves everyone as "dev-user"
as.clients["my-app"] = ["http://127.0.0.1/callback"] // the AS admin registered "my-app"

const srv = mcp.fixtureServer()
srv.auth = new mcp.ServerAuth({ issuer: as.issuer, scopes: { "*": ["mcp:read"], greet: ["mcp:greet"] } }) // greet needs more
const ts = await srv.listen()
// ---- end harness ----

const store = new mcp.MemoryTokenStore() // implement mcp.TokenStore to persist tokens (keyed by resource)

// The user code. A real app leaves authorizer unset: new LoopbackAuthorizer() opens the system
// browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
const c = new mcp.Client(ts.url, {
  auth: new mcp.OAuth({
    clientId: "my-app",
    store,
    authorizer: new mcp.LoopbackAuthorizer({ open: as.approve }),
  }),
})

let res = await c.callTool("echo", { text: "hello after login" })
console.log("echo:", mcp.text(res))

// Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
// re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
res = await c.callTool("greet", {}, () => ({ action: "accept", content: { name: "Ada" } }))
console.log("greet:", mcp.text(res))

// A second client sharing the token store needs no new login.
const c2 = new mcp.Client(ts.url, {
  auth: new mcp.OAuth({ clientId: "my-app", store, authorizer: new mcp.LoopbackAuthorizer({ open: as.approve }) }),
})
await c2.callTool("echo", { text: "again" })
console.log("browser logins:", as.stats().authorizations) // 1 login + 1 step-up

const tok = store.load(ts.url)
console.log("token stored for this issuer:", tok?.issuer === as.issuer) // console.log(tok) is redacted

await ts.close()
await as.close()
