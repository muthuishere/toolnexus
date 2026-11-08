# MCP authentication

> **Java port of the spike — the Go spike is the reference (openspec `upgrade-mcp-2026-07-28`).** The API below is
> `spikes/mcp-2026/java` (package `io.github.muthuishere.toolnexus.mcp2026`). Every code block is copied verbatim
> from a program under `examples/` that `ExamplesTest` runs and checks; `DocsTest` fails if a block here stops
> matching its source.

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
7. Expired → `refresh_token` grant (one at a time). `403 insufficient_scope` → step-up with the union of scopes.

A call is re-sent **only** after a `401`, or a `403 insufficient_scope` with `WWW-Authenticate`: the server
rejected it before running the tool. A lost connection, timeout or 5xx is thrown and never retried, because the
tool may already have run.

## Who the client is: no DCR

The spec deprecates Dynamic Client Registration and toolnexus does not implement it. A client identifies itself
in one of two ways:

| your authorization server supports | set | example |
|---|---|---|
| Client ID Metadata Documents (AS metadata has `client_id_metadata_document_supported: true`) | `.clientMetadataUrl("https://app.example/client.json")` (an HTTPS URL you host) | Keycloak 26.8 with the `cimd` feature + client policy |
| no CIMD (most providers today) | **register an app** with the AS once and set `.clientId(…)` (+ `.clientSecret(…)` for a confidential client) | GitHub OAuth App, Entra app registration, Keycloak client |
| several ASes | `.clientIds(Map.of(issuer, clientId))`; each id is used only with its issuer | |

If neither is set, the call fails with *register an app with <issuer> and set OAuth.ClientID*. A pre-registered
id wins over CIMD.

## Mode 1: automatic (CLI, desktop)

`new LoopbackAuthorizer()` opens the system browser and listens on `http://127.0.0.1:<random>/callback`
(RFC 8252 §7.3: the AS must accept any port on a loopback redirect). The call blocks until the user has
logged in, then returns the tool's result. In the example the in-process dev server stands in for the browser.

<!-- from: examples/06-oauth-zero-config/Main.java -->
```java
                MemoryTokenStore store = new MemoryTokenStore(); // implement TokenStore to persist tokens (keyed by resource)

                // The user code. A real app leaves the authorizer unset: LoopbackAuthorizer opens the system
                // browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
                Client c = new Client(ts.url() + "/mcp").auth(new OAuth()
                        .clientId("my-app")
                        .store(store)
                        .authorizer(new LoopbackAuthorizer(as::approve)));

                Map<String, Object> res = c.callTool("echo", Mcp.obj("text", "hello after login"), null);
                System.out.println("echo: " + Mcp.text(res));

                // Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
                // re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
                res = c.callTool("greet", Mcp.obj(), (key, req) -> Mcp.obj("action", "accept", "content", Mcp.obj("name", "Ada")));
                System.out.println("greet: " + Mcp.text(res));
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

`.store(…)` takes any `TokenStore` (`load/save/delete` keyed by resource URL); the default is in-memory. Share
one to avoid logging in again:

<!-- from: examples/06-oauth-zero-config/Main.java -->
```java
                // A second client sharing the token store needs no new login.
                Client c2 = new Client(ts.url() + "/mcp")
                        .auth(new OAuth().clientId("my-app").store(store).authorizer(new LoopbackAuthorizer(as::approve)));
                c2.callTool("echo", Mcp.obj("text", "again"), null);
```

After a restart the first request goes out without a token, to learn the issuer from the `401`; only then is
the stored token sent. `Token` prints as `Token{issuer=…, redacted}`. Encrypting the store is up to you.

## Mode 2: pause and resume (web apps, bots, job queues)

A server must never block a request on a login. `PauseAuthorizer` makes the call throw `PendingException`
whose `pending` has `{id, kind: "authorization", prompt, url, expiresAt}`, the shape of toolnexus SPEC §10
`Request` (`Pending.of(e)` finds it in any exception). Send the user to `url`; when the AS redirects back,
`resume` returns the **original call's** result. A pending authorization expires after 10 minutes.

### As one HttpHandler

<!-- from: examples/07-oauth-web-pause-resume/Main.java -->
```java
            Client c = new Client(mcpTS.url() + "/mcp")
                    .auth(new OAuth().clientId("my-web-app").authorizer(new PauseAuthorizer(app + "/tool")));

            // (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
            // with ?code&state, webHandler resumes, and the tool's text is written to the response.
            web.createContext("/tool", c.auth().webHandler(query ->
                    c.callTool("echo", Mcp.obj("text", query.get("text")), null)));
```

### By hand

<!-- from: examples/07-oauth-web-pause-resume/Main.java -->
```java
            // (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
            Client c2 = new Client(mcpTS.url() + "/mcp")
                    .auth(new OAuth().clientId("my-web-app").authorizer(new PauseAuthorizer("http://127.0.0.1/callback")));
            Pending p = null;
            try {
                c2.callTool("echo", Mcp.obj("text", "resumed by hand"), null);
            } catch (RuntimeException e) {
                p = Pending.of(e); // null when the call failed for another reason
            }
            if (p == null) throw new IllegalStateException("expected a pending authorization");
            System.out.println("(b) paused: " + p.kind() + " - " + !p.prompt().isEmpty() + " - has URL: " + !p.url().isEmpty());

            // The user logs in; you receive the callback URL (here: the AS redirect, not followed).
            String callbackUrl = as.get(p.url(), false).headers().firstValue("Location").orElseThrow();
            Map<String, Object> res = c2.auth().resume(p.id(), callbackUrl);
            System.out.println("(b) resumed: " + Mcp.text(res));
```

```text
(a) WebHandler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand
```

The paused request is sent for the first time on `resume` (the `401` came before dispatch). A pause inside an
MRTR round resumes that same round without asking the user again.

## Client options (`new OAuth()…`)

| setter | default | meaning |
|---|---|---|
| `authorizer` | `new LoopbackAuthorizer()` | automatic, or `new PauseAuthorizer(redirect)` |
| `clientId` / `clientSecret` | | pre-registered client; a secret makes it confidential |
| `tokenAuthMethod` | `client_secret_basic` if a secret, else `none` | or `client_secret_post` |
| `clientIds` | | issuer → client id |
| `clientMetadataUrl` | | CIMD client id |
| `store` | in-memory | `TokenStore` |
| `omitRefreshResource` | false | drop `resource` on refresh (Entra, see Providers) |
| `http` | shared 30 s-per-request `HttpClient` | for metadata and token calls |
| `allowHttp` | false | plain-HTTP AS for local dev; the spec requires HTTPS |

## Protecting your own server

One line: the issuer. The server then serves PRM at `/.well-known/oauth-protected-resource/mcp`, answers `401`
with `WWW-Authenticate`, `403 insufficient_scope` with the full scope set for the operation, and verifies RS256
JWTs against the issuer's discovered JWKS (signature, `iss`, `aud` = this server's URL, `exp`).

<!-- from: examples/08-serve-protected/Main.java -->
```java
            Server srv = new Server()
                    .tool("whoami", "Who is calling", (ctx, a) -> {
                        Principal p = ctx.principal().orElseThrow(); // subject, scopes, claims of the verified token
                        return "hello " + p.subject() + " (" + String.join(" ", p.scopes()) + ")";
                    })
                    .tool("delete_all", "Dangerous admin tool", (ctx, a) -> "deleted");
            srv.auth(new ServerAuth(as.issuer()) // the whole required config
                    .scopes(Map.of(
                            "*", List.of("mcp:read"),           // every request
                            "delete_all", List.of("mcp:admin")))); // additionally required for this tool
            LocalServer ts = LocalServer.start(srv); // production: any JDK HttpServer, srv is an HttpHandler
            // ---- end server ----
```

`scopes["*"]` is required on every request and is advertised as `scopes_supported`; `scopes[tool]` is also
required for that `tools/call`. Scopes match exactly (no hierarchy). `audience` defaults to
`scheme://host/mcp` of the request; set it when a proxy changes the host, or when your AS pins the audience.

The handler reads the caller with `ctx.principal()`: `subject`, `scopes`, `claims`.

```text
no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
```

### Verify hook: opaque tokens, API keys, introspection

`verify` replaces the JWT verifier entirely; a JWT does not bypass it. Throw to reject (→ `401 invalid_token`).

<!-- from: examples/08-serve-protected/Main.java -->
```java
            Map<String, Principal> keys = Map.of( // e.g. a lookup in your DB, or an RFC 7662 introspection call
                    "svc-key-123", new Principal("billing-service", List.of("mcp:read")));
            Server srv2 = new Server().tool("whoami", "Who is calling",
                    (ctx, a) -> "hello " + ctx.principal().orElseThrow().subject());
            srv2.auth(new ServerAuth(as.issuer()) // still advertised in the resource metadata
                    .scopes(Map.of("*", List.of("mcp:read")))
                    .verify(token -> {
                        Principal p = keys.get(token);
                        if (p == null) throw new SecurityException("unknown key"); // -> 401 invalid_token
                        return p;
                    }));
            LocalServer ts2 = LocalServer.start(srv2);
```

A caller with a static key needs no OAuth, just a header on every request:

<!-- from: examples/08-serve-protected/Main.java -->
```java
            // A service caller with a static key: a header on every request, no OAuth.
            Client svc = new Client(ts2.url() + "/mcp").header("Authorization", "Bearer svc-key-123");
            System.out.println("verify hook: " + Mcp.text(svc.callTool("whoami", null, null)));
```

## DevAuthServer (dev and tests only)

`DevAuthServer.start()` starts an OAuth 2.1 AS on `https://127.0.0.1:<port>/dev` that **approves everyone as
`dev-user`** with no login. It implements RFC 8414 metadata, JWKS, code + PKCE S256, `resource`, `iss`,
refresh-token rotation, pre-registered (`clients`, `secrets`) and CIMD clients. Its TLS certificate (one
self-signed 127.0.0.1 certificate per JVM, made with the JDK's `keytool`) is trusted by default clients in the same
process, so `new ServerAuth(as.issuer())` needs no HTTP wiring. `as::approve` is a headless browser for
`new LoopbackAuthorizer(as::approve)`. **Never use it in production.**

<!-- from: examples/06-oauth-zero-config/Main.java -->
```java
        try (DevAuthServer as = DevAuthServer.start()) { // DEV/TEST ONLY: auto-approves everyone as "dev-user"
            as.clients.put("my-app", List.of("http://127.0.0.1/callback")); // the AS admin registered "my-app"
```
