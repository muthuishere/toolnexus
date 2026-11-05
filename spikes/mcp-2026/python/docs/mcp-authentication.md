# MCP authentication

> **Python port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/python` (package `mcp2026`, `import mcp2026 as mcp`); the Go spike is the reference.
> Every code block is copied verbatim from a program under `examples/` that `tests/test_examples.py` runs and
> checks; `tests/test_docs.py` fails if a block here stops matching its source.

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
7. Expired → `refresh_token` grant (one refresh at a time). `403 insufficient_scope` → step-up with the union of scopes.

A call is re-sent **only** after a `401`, or a `403 insufficient_scope` with `WWW-Authenticate`: the server
rejected it before running the tool. A lost connection, timeout or 5xx raises and is never retried, because
the tool may already have run.

## Who the client is: no DCR

The spec deprecates Dynamic Client Registration and toolnexus does not implement it. A client identifies itself
in one of two ways:

| your authorization server supports | set | example |
|---|---|---|
| Client ID Metadata Documents (AS metadata has `client_id_metadata_document_supported: true`) | `client_metadata_url="https://app.example/client.json"` (an HTTPS URL you host) | Keycloak 26.8 with the `cimd` feature + client policy |
| no CIMD (most providers today) | **register an app** with the AS once and set `client_id` (+ `client_secret` for a confidential client) | GitHub OAuth App, Entra app registration, Keycloak client |
| several ASes | `client_ids={issuer: client_id}`; each id is used only with its issuer | |

If neither is set, the call raises *register an app with <issuer> and set OAuth.client_id*. A pre-registered
id wins over CIMD.

## Mode 1: automatic (CLI, desktop)

`LoopbackAuthorizer()` opens the system browser and listens on `http://127.0.0.1:<random>/callback`
(RFC 8252 §7.3: the AS must accept any port on a loopback redirect). The call blocks until the user has
logged in, then returns the tool's result. In the example the in-process dev server stands in for the browser.

<!-- from: examples/06-oauth-zero-config/main.py -->
```python
store = mcp.MemoryTokenStore()  # implement mcp.TokenStore to persist tokens (keyed by resource)

# The user code. A real app leaves authorizer unset: LoopbackAuthorizer() opens the system
# browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
c = mcp.Client(ts.url + "/mcp")
c.auth = mcp.OAuth(
    client_id="my-app",
    store=store,
    authorizer=mcp.LoopbackAuthorizer(open=as_.approve),
)

res = c.call_tool("echo", {"text": "hello after login"})
print("echo:", mcp.text(res))

# Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
# re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
res = c.call_tool("greet", {}, lambda key, req: {"action": "accept", "content": {"name": "Ada"}})
print("greet:", mcp.text(res))
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

`store` takes any `TokenStore` (`load/save/delete` keyed by resource URL); unset means in-memory. Share one to
avoid logging in again:

<!-- from: examples/06-oauth-zero-config/main.py -->
```python
# A second client sharing the token store needs no new login.
c2 = mcp.Client(ts.url + "/mcp")
c2.auth = mcp.OAuth(client_id="my-app", store=store, authorizer=mcp.LoopbackAuthorizer(open=as_.approve))
c2.call_tool("echo", {"text": "again"})
logins, _ = as_.stats()
print("browser logins:", logins)  # 1 login + 1 step-up

tok = store.load(ts.url + "/mcp")
print("token stored for this issuer:", str(tok.issuer == as_.issuer).lower())  # print(tok) is redacted
```

After a restart the first request goes out without a token, to learn the issuer from the `401`; only then is
the stored token sent. `Token` prints as `Token{issuer=…, redacted}`. Encrypting the store is up to you.

## Mode 2: pause and resume (web apps, bots, job queues)

A server must never block a request on a login. `PauseAuthorizer` makes the call raise `PendingError` whose
`.pending` has `{id, kind: "authorization", prompt, url, expires_at}`, the shape of toolnexus SPEC §10
`Request`. Send the user to `url`; when the AS redirects back, `resume` returns the **original call's** result.
A pending authorization expires after 10 minutes.

### As one WSGI app

<!-- from: examples/07-oauth-web-pause-resume/main.py -->
```python
c = mcp.Client(mcp_ts.url + "/mcp")
c.auth = mcp.OAuth(client_id="my-web-app", authorizer=mcp.PauseAuthorizer(redirect=app + "/tool"))

# (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
# with ?code&state, web_handler resumes, and the tool's text is written to the response.
lazy.app = c.auth.web_handler(
    lambda req: c.call_tool("echo", {"text": parse_qs(req.query).get("text", [""])[0]}))
```

### By hand

<!-- from: examples/07-oauth-web-pause-resume/main.py -->
```python
c2 = mcp.Client(mcp_ts.url + "/mcp")
c2.auth = mcp.OAuth(client_id="my-web-app", authorizer=mcp.PauseAuthorizer(redirect="http://127.0.0.1/callback"))
try:
    c2.call_tool("echo", {"text": "resumed by hand"})
    raise SystemExit("expected a pending authorization")
except mcp.PendingError as e:
    p = e.pending
print("(b) paused:", p.kind, "-", str(p.prompt != "").lower(), "- has URL:", str(p.url != "").lower())

callback_url = approve_and_capture_callback(p.url)  # the user logs in; you receive the callback URL
res = c2.auth.resume(p.id, callback_url)
print("(b) resumed:", mcp.text(res))
```

```text
(a) web_handler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand
```

The paused request is sent for the first time on `resume` (the `401` came before dispatch). A pause inside an
MRTR round resumes that same round without asking the user again.

## Client options

| `OAuth(...)` keyword | default | meaning |
|---|---|---|
| `authorizer` | `LoopbackAuthorizer()` | automatic, or `PauseAuthorizer(redirect)` |
| `client_id` / `client_secret` | | pre-registered client; a secret makes it confidential |
| `token_auth_method` | `client_secret_basic` if a secret, else `none` | or `client_secret_post` |
| `client_ids` | | issuer → client id |
| `client_metadata_url` | | CIMD client id |
| `store` | in-memory | `TokenStore` |
| `omit_refresh_resource` | False | drop `resource` on refresh (Entra, see Providers) |
| `http` | 30 s timeout `HttpClient` | for metadata and token calls |
| `allow_http` | False | plain-HTTP AS for local dev; the spec requires HTTPS |

## Protecting your own server

One line: the issuer. The server then serves PRM at `/.well-known/oauth-protected-resource/mcp`, answers `401`
with `WWW-Authenticate`, `403 insufficient_scope` with the full scope set for the operation, and verifies RS256
JWTs against the issuer's discovered JWKS (signature, `iss`, `aud` = this server's URL, `exp`).

<!-- from: examples/08-serve-protected/main.py -->
```python
srv = (mcp.Server()
       .tool("whoami", "Who is calling",
             # ctx.principal: subject, scopes, claims of the verified token
             lambda args, ctx: f"hello {ctx.principal.subject} ({' '.join(ctx.principal.scopes)})")
       .tool("delete_all", "Dangerous admin tool", lambda args, ctx: "deleted"))
srv.auth = mcp.ServerAuth(
    as_.issuer,  # the whole required config
    scopes={
        "*": ["mcp:read"],  # every request
        "delete_all": ["mcp:admin"],  # additionally required for this tool
    },
)
ts = mcp.serve(srv)  # production: mount srv (a WSGI app) in any WSGI server
```

`scopes["*"]` is required on every request and is advertised as `scopes_supported`; `scopes[tool]` is also
required for that `tools/call`. Scopes match exactly (no hierarchy). `audience` defaults to
`scheme://host/mcp` of the request; set it when a proxy changes the host, or when your AS pins the audience.

The handler reads the caller from `ctx.principal`: `subject`, `scopes`, `claims`.

```text
no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
```

### Verify hook: opaque tokens, API keys, introspection

`verify` replaces the JWT verifier entirely; a JWT does not bypass it. Raise to reject (→ `401 invalid_token`).

<!-- from: examples/08-serve-protected/main.py -->
```python
keys = {  # e.g. a lookup in your DB, or an RFC 7662 introspection call
    "svc-key-123": mcp.Principal(subject="billing-service", scopes=["mcp:read"]),
}


def verify(token: str) -> mcp.Principal:
    if token in keys:
        return keys[token]
    raise PermissionError("unknown key")  # -> 401 invalid_token


srv2 = mcp.Server().tool("whoami", "Who is calling", lambda args, ctx: "hello " + ctx.principal.subject)
srv2.auth = mcp.ServerAuth(
    as_.issuer,  # still advertised in the resource metadata
    scopes={"*": ["mcp:read"]},
    verify=verify,
)
ts2 = mcp.serve(srv2)

# A service caller with a static key: an HttpClient that adds the header to every request.
svc = mcp.Client(ts2.url + "/mcp", http=mcp.HttpClient(headers={"Authorization": "Bearer svc-key-123"}))
print("verify hook:", mcp.text(svc.call_tool("whoami")))

bad = mcp.Client(ts2.url + "/mcp", http=mcp.HttpClient(headers={"Authorization": "Bearer wrong"}))
try:
    bad.call_tool("whoami")
    rejected = False
except Exception:
    rejected = True
print("wrong key rejected:", str(rejected).lower())
```

## DevAuthServer (dev and tests only)

`mcp.DevAuthServer.start()` starts an OAuth 2.1 AS on `https://127.0.0.1:<port>/dev` that **approves everyone as
`dev-user`** with no login. It implements RFC 8414 metadata, JWKS, code + PKCE S256, `resource`, `iss`,
refresh-token rotation, pre-registered (`clients`, `secrets`) and CIMD clients. Its TLS certificate is trusted
by default clients in the same process, so `ServerAuth(as_.issuer)` needs no HTTP wiring.
`as_.approve` is a headless browser for `LoopbackAuthorizer(open=as_.approve)`. **Never use it in production.**

<!-- from: examples/06-oauth-zero-config/main.py -->
```python
# ---- demo harness: an authorization server + a protected MCP server, both in-process ----
as_ = mcp.DevAuthServer.start()  # DEV/TEST ONLY: auto-approves everyone as "dev-user"
as_.clients["my-app"] = ["http://127.0.0.1/callback"]  # the AS admin registered "my-app"

srv = mcp.fixture_server()
srv.auth = mcp.ServerAuth(as_.issuer,
                          scopes={"*": ["mcp:read"], "greet": ["mcp:greet"]})  # greet needs more
ts = mcp.serve(srv)
# ---- end harness ----
```
