// Example 09 — a real authorization server: Keycloak 26.8.1 protecting an MCP server.
//
// Start Keycloak first (realm "toolnexus", user alice / alice-test-pw, client "toolnexus-mcp"):
//
//   cd ../golang/keycloak && docker compose up -d && cd -
//   KEYCLOAK=1 node examples/09-keycloak/main.ts                  # logs in through your system browser
//   KEYCLOAK=1 HEADLESS_LOGIN=1 node examples/09-keycloak/main.ts # scripted login (used by the test)
//
// The MCP server listens on 127.0.0.1:7199 because the realm's audience mapper puts
// aud = http://127.0.0.1:7199/mcp into tokens; ServerAuth audience must equal it.
import * as mcp from "../../src/index.ts"
import { headlessLogin } from "./headless.ts"

const issuer = "http://localhost:8180/realms/toolnexus" // exactly the `iss` Keycloak puts in tokens
const resource = "http://127.0.0.1:7199/mcp"

if (process.env.KEYCLOAK !== "1") {
  console.log("skipped: set KEYCLOAK=1 with keycloak/docker-compose.yml running")
  process.exit(0)
}

// ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
const srv = mcp.fixtureServer().tool("whoami", "Who is calling", (_args, ctx) => {
  const p = ctx.principal!
  // subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
  return `client=${p.claims?.azp} scopes=${p.scopes.join(" ")} subject-set=${p.subject !== ""}`
})
srv.auth = new mcp.ServerAuth({
  issuer,
  audience: resource, // = the audience mapper's included.custom.audience
  scopes: { "*": ["mcp:tools"], greet: ["mcp:greet"] },
})
const ts = await srv.listen(7199)

// ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
const authorizer = new mcp.LoopbackAuthorizer() // default open = system browser
if (process.env.HEADLESS_LOGIN === "1") authorizer.open = headlessLogin("alice", "alice-test-pw") // throwaway local test user
const c = new mcp.Client(resource, {
  auth: new mcp.OAuth({
    clientIds: { [issuer]: "toolnexus-mcp" }, // client id bound to THIS issuer
    allowHttp: true, // dev Keycloak is plain HTTP; never in production
    authorizer,
  }),
})

let res = await c.callTool("whoami")
console.log("whoami:", mcp.text(res))

// greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
res = await c.callTool("greet", {}, () => ({ action: "accept", content: { name: "Alice" } }))
console.log("greet:", mcp.text(res))

await ts.close()
