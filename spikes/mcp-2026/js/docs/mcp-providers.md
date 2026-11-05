# MCP authentication: provider notes

> **JS port of the Go reference spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/js` (`import * as mcp from "./src/index.ts"`; Node 22.18+, no dependencies, no MCP SDK). Every
> code block is copied verbatim from a program under `examples/` that `test/examples.test.ts` runs and checks;
> `test/docs.test.ts` fails if a block here stops matching its source.

| provider | client identity | tested | status |
|---|---|---|---|
| Keycloak 26.8.1 | pre-registered public client, or CIMD (experimental) | live, docker compose | works |
| GitHub hosted MCP (`api.githubcopilot.com/mcp/`) | OAuth App (client id + secret); no DCR, no CIMD | live | works |
| Microsoft Entra ID | app registration | **UNTESTED** (simulated only) | see note |

## Keycloak

Runnable: the Go spike's `../golang/keycloak/docker-compose.yml` + `realm-toolnexus.json` (realm `toolnexus`,
client `toolnexus-mcp`, throwaway user `alice`), example `examples/09-keycloak`.

```sh
(cd ../golang/keycloak && docker compose up -d)
KEYCLOAK=1 node examples/09-keycloak/main.ts                # logs in through your browser
KEYCLOAK=1 node --test test/keycloak.test.ts test/examples.test.ts   # the live suite, headless
```

<!-- from: examples/09-keycloak/main.ts -->
```ts
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
```

```text
whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true
greet: Hello, Alice!
```

### Setup checklist

1. **Client**: public, standard flow, PKCE method `S256`, redirect URI `http://127.0.0.1/*` (Keycloak matches any
   loopback port). Set `fullScopeAllowed: false` or every token request logs a deprecation warning.
2. **Client scopes**: one per permission (`mcp:tools` default, `mcp:greet` optional). Optional scopes are granted
   only when requested, which is what step-up asks for.
3. **Audience mapper**: Keycloak does **not** derive `aud` from `resource`. Add an `oidc-audience-mapper` with
   `included.custom.audience` = your MCP server's canonical URL (here on the `mcp:tools` scope) and set
   the ServerAuth `audience` to the same value. With the `resource-indicators` feature on, a `resource` that
   matches no audience gets `invalid_target` at the **token** endpoint, not at authorize.
4. **`basic` scope**: must exist, or tokens have no `sub`. Defining `clientScopes` in a realm import replaces
   the built-in ones, so the import must re-declare `basic`.
5. **CIMD (optional, experimental)**: the `cimd` feature flag is not enough; you also need a client **policy +
   profile** (see the Go spike's providers page for the full list). Without the policy: `client_not_found`.

Other behaviour seen: AS metadata answers at the RFC 8414 path-insert URL; `iss` is sent on every redirect;
`resource` on refresh is accepted; the `scope` claim is a space-separated string.

## GitHub

GitHub's hosted MCP server speaks 2026-07-28. Its AS supports neither DCR nor CIMD, so register an **OAuth App**:
github.com → Settings → Developer settings → OAuth Apps → New, callback URL `http://127.0.0.1/callback`
(GitHub ignores the port on loopback). Enable expiring user tokens to get refresh tokens.

<!-- from: examples/10-github/main.ts -->
```ts
const login = new mcp.LoopbackAuthorizer() // system browser + http://127.0.0.1:<random>/callback
if (process.env.BROWSER === "chrome-agent") login.open = (u) => promisify(execFile)("chrome-agent", ["goto", u])

const c = new mcp.Client("https://api.githubcopilot.com/mcp/", {
  auth: new mcp.OAuth({
    clientId: id,
    clientSecret: secret, // GitHub OAuth Apps are confidential clients (client_secret_basic)
    authorizer: login,
  }),
})

const tools = await c.tools()
console.log("tools listed:", tools.length > 0)

const res = await c.callTool("get_me", {}) // read-only: who am I
console.log("get_me:", res.resultType, "| isError:", res.isError === true) // content withheld on purpose
```

```sh
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- node examples/10-github/main.ts
```

Quirks the client handles without configuration:

- **Form-encoded token response.** Without `Accept: application/json` GitHub's token endpoint answers
  `application/x-www-form-urlencoded` (RFC 6749 requires JSON). The client sends the header and also parses a
  form-encoded body.
- `serverInfo` arrives in result `_meta` (`io.modelcontextprotocol/serverInfo`), not the result body.
- `isError` is omitted on successful results; treat missing as false.

## Microsoft Entra ID — UNTESTED

Not run against a real tenant. Known from python-sdk issue #2578: Entra rejects `resource` on the
`refresh_token` grant, though the MCP spec says it MUST be sent. Set `omitRefreshResource: true` for Entra;
authorize and code exchange still send `resource`. Only the simulation
(`DevAuthServer.rejectRefreshResource`, `test/retry.test.ts`) covers this.
