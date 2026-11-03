# MCP authentication

> **JS port of the Go reference spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/js` (`import * as mcp from "./src/index.ts"`; Node 22.18+, no dependencies, no MCP SDK). Every
> code block is copied verbatim from a program under `examples/` that `test/examples.test.ts` runs and checks;
> `test/docs.test.ts` fails if a block here stops matching its source.

MCP 2026-07-28 `basic/authorization` is OAuth 2.1 with the MCP server as a protected resource. The client
discovers everything from the server's `401`:

1. `WWW-Authenticate: Bearer resource_metadata=…` → Protected Resource Metadata (RFC 9728); falls back to
   `/.well-known/oauth-protected-resource/<path>`, then the root.
2. `authorization_servers[0]` → AS metadata (RFC 8414 path-insert, then OIDC path-insert, then OIDC append),
   with an `issuer` identity check.
3. Refuse the AS if it does not list PKCE `S256`.
4. Authorization code + PKCE S256, with `resource` (RFC 8707) on the authorize **and** token requests.
5. Callback: `iss` (RFC 9207) and `state` are checked before `code` or `error` is acted on.
6. The token is stored per resource and bound to the issuer; it is sent only once that issuer is confirmed.
7. Expired → `refresh_token` grant (single-flight). `403 insufficient_scope` → step-up with the union of scopes.

A call is re-sent **only** after a `401`, or a `403 insufficient_scope` with `WWW-Authenticate`: the server
rejected it before running the tool. A lost connection, timeout or 5xx is thrown and never retried, because the
tool may already have run.

## Who the client is: no DCR

The spec deprecates Dynamic Client Registration and toolnexus does not implement it. A client identifies itself
in one of two ways:

| your authorization server supports | set | example |
|---|---|---|
| Client ID Metadata Documents (AS metadata has `client_id_metadata_document_supported: true`) | `clientMetadataUrl: "https://app.example/client.json"` (an HTTPS URL you host) | Keycloak 26.8 with the `cimd` feature + client policy |
| no CIMD (most providers today) | **register an app** with the AS once and set `clientId` (+ `clientSecret` for a confidential client) | GitHub OAuth App, Entra app registration, Keycloak client |
| several ASes | `clientIds: { [issuer]: clientId }`; each id is used only with its issuer | |

If neither is set, the call fails with *register an app with <issuer> and set OAuth.clientId*. A pre-registered
id wins over CIMD.

## Mode 1: automatic (CLI, desktop)

`new LoopbackAuthorizer()` opens the system browser and listens on `http://127.0.0.1:<random>/callback`
(RFC 8252 §7.3: the AS must accept any port on a loopback redirect). The call waits until the user has
logged in, then resolves with the tool's result. In the example the in-process dev server stands in for the browser.

<!-- from: examples/06-oauth-zero-config/main.ts -->
```ts
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
```

```text
echo: hello after login
greet: Hello, Ada!
browser logins: 2
token stored for this issuer: true
```

`greet` needs `mcp:greet`, so the second call is a **step-up**: `403 insufficient_scope` → a second authorization
for `mcp:read mcp:greet` → the paused call is sent once. `browser logins: 2` is the login plus the step-up.

### Token store

`store` takes any `TokenStore` (`load/save/delete` keyed by resource URL, sync or async); default in-memory.
Share one to avoid logging in again:

<!-- from: examples/06-oauth-zero-config/main.ts -->
```ts
// A second client sharing the token store needs no new login.
const c2 = new mcp.Client(ts.url, {
  auth: new mcp.OAuth({ clientId: "my-app", store, authorizer: new mcp.LoopbackAuthorizer({ open: as.approve }) }),
})
await c2.callTool("echo", { text: "again" })
console.log("browser logins:", as.stats().authorizations) // 1 login + 1 step-up

const tok = store.load(ts.url)
console.log("token stored for this issuer:", tok?.issuer === as.issuer) // console.log(tok) is redacted
```

After a restart the first request goes out without a token, to learn the issuer from the `401`; only then is
the stored token sent. A `Token` prints (and JSON-serialises) as `Token{issuer=…, redacted}`. Encrypting the
store is up to you.

## Mode 2: pause and resume (web apps, bots, job queues)

A server must never block a request on a login. `PauseAuthorizer` makes the call reject with a `PendingError`
whose `pending` is `{id, kind: "authorization", prompt, url, expiresAt}`, the shape of toolnexus SPEC §10
`Request`. Send the user to `url`; when the AS redirects back, `resume` resolves with the **original call's** result.
A pending authorization expires after 10 minutes.

### As one node:http listener

<!-- from: examples/07-oauth-web-pause-resume/main.ts -->
```ts
// ---- the web app ----
const c = new mcp.Client(mcpTS.url, {
  auth: new mcp.OAuth({ clientId: "my-web-app", authorizer: new mcp.PauseAuthorizer({ redirect: app + "/tool" }) }),
})

// (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
// with ?code&state, webHandler resumes, and the tool's text is written to the response.
appServer.on("request", c.auth!.webHandler((req) => {
  const text = new URL(req.url!, app).searchParams.get("text") ?? ""
  return c.callTool("echo", { text })
}))
// ---- end web app ----
```

### By hand

<!-- from: examples/07-oauth-web-pause-resume/main.ts -->
```ts
// (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
const c2 = new mcp.Client(mcpTS.url, {
  auth: new mcp.OAuth({ clientId: "my-web-app", authorizer: new mcp.PauseAuthorizer({ redirect: "http://127.0.0.1/callback" }) }),
})
let p: mcp.Pending | undefined
try {
  await c2.callTool("echo", { text: "resumed by hand" })
} catch (err) {
  p = mcp.pendingOf(err) // undefined when the call failed for another reason
  if (!p) throw err
}
if (!p) throw new Error("expected a pending authorization")
console.log("(b) paused:", p.kind, "-", p.prompt !== "", "- has URL:", p.url !== "")

const callbackUrl = await as.visit(p.url) // the user logs in; you receive the callback URL
const res = await c2.auth!.resume(p.id, callbackUrl)
console.log("(b) resumed:", mcp.text(res))
```

```text
(a) WebHandler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand
```

The paused request is sent for the first time on `resume` (the `401` came before dispatch). A pause inside an
MRTR round resumes that same round without asking the user again.

## Client options (`new OAuth({...})`)

| option | default | meaning |
|---|---|---|
| `authorizer` | `new LoopbackAuthorizer()` | automatic, or `new PauseAuthorizer({ redirect })` |
| `clientId` / `clientSecret` | | pre-registered client; a secret makes it confidential |
| `tokenAuthMethod` | `client_secret_basic` if a secret, else `none` | or `client_secret_post` |
| `clientIds` | | issuer → client id |
| `clientMetadataUrl` | | CIMD client id |
| `store` | in-memory | `TokenStore` |
| `omitRefreshResource` | false | drop `resource` on refresh (Entra, see Providers) |
| `http` | node:http(s), 30 s timeout | `HttpFn` for metadata and token calls |
| `allowHttp` | false | plain-HTTP AS for local dev; the spec requires HTTPS |

## Protecting your own server

One line: the issuer. The server then serves PRM at `/.well-known/oauth-protected-resource/mcp`, answers `401`
with `WWW-Authenticate`, `403 insufficient_scope` with the full scope set for the operation, and verifies RS256
JWTs against the issuer's discovered JWKS (signature, `iss`, `aud` = this server's URL, `exp`).

<!-- from: examples/08-serve-protected/main.ts -->
```ts
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
```

`scopes["*"]` is required on every request and is advertised as `scopes_supported`; `scopes[tool]` is also
required for that `tools/call`. Scopes match exactly (no hierarchy). `audience` defaults to
`scheme://host/mcp` of the request; set it when a proxy changes the host, or when your AS pins the audience.

The handler reads the caller from its second argument: `ctx.principal` (`subject`, `scopes`, `claims`).

```text
no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
```

### Verify hook: opaque tokens, API keys, introspection

`verify` replaces the JWT verifier entirely; a JWT does not bypass it.

<!-- from: examples/08-serve-protected/main.ts -->
```ts
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
```

<!-- from: examples/08-serve-protected/main.ts -->
```ts
// A service caller with a static key: a plain client with an extra header.
const svc = new mcp.Client(ts2.url, { headers: { Authorization: "Bearer svc-key-123" } })
res = await svc.callTool("whoami")
console.log("verify hook:", mcp.text(res))

const bad = new mcp.Client(ts2.url, { headers: { Authorization: "Bearer wrong" } })
const rejected = await bad.callTool("whoami").then(() => false, () => true)
console.log("wrong key rejected:", rejected)
```

## DevAuthServer (dev and tests only)

`await mcp.DevAuthServer.start()` starts an OAuth 2.1 AS on `https://127.0.0.1:<port>/dev` that **approves everyone as
`dev-user`** with no login. It implements RFC 8414 metadata, JWKS, code + PKCE S256, `resource`, `iss`,
refresh-token rotation, pre-registered (`clients`, `secrets`) and CIMD clients. Its self-signed TLS certificate
(built in-process: node:crypto cannot create certificates, so the spike writes ~40 lines of DER) is trusted by
`defaultHttp` in the same process, so `new ServerAuth({ issuer: as.issuer })` needs no HTTP wiring.
`as.approve` is a headless browser for `new LoopbackAuthorizer({ open: as.approve })`. **Never use it in production.**

<!-- from: examples/06-oauth-zero-config/main.ts -->
```ts
// ---- demo harness: an authorization server + a protected MCP server, both in-process ----
const as = await mcp.DevAuthServer.start() // DEV/TEST ONLY: auto-approves everyone as "dev-user"
as.clients["my-app"] = ["http://127.0.0.1/callback"] // the AS admin registered "my-app"

const srv = mcp.fixtureServer()
srv.auth = new mcp.ServerAuth({ issuer: as.issuer, scopes: { "*": ["mcp:read"], greet: ["mcp:greet"] } }) // greet needs more
const ts = await srv.listen()
// ---- end harness ----
```
