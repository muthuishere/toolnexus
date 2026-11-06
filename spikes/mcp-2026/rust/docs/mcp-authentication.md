# MCP authentication

> **Rust port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/rust` (crate `mcp2026`; reference: `spikes/mcp-2026/golang`). Every code block is
> copied verbatim from a program under `examples/` that `tests/examples.rs` runs and checks; `tests/docs.rs`
> fails if a block here stops matching its source.

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
7. Expired → `refresh_token` grant. `403 insufficient_scope` → step-up with the union of scopes.

A call is re-sent **only** after a `401`, or a `403 insufficient_scope` with `WWW-Authenticate`: the server
rejected it before running the tool. A lost connection, timeout or 5xx is returned as an error and never
retried, because the tool may already have run.

## Who the client is: no DCR

The spec deprecates Dynamic Client Registration and toolnexus does not implement it. A client identifies itself
in one of two ways:

| your authorization server supports | set | example |
|---|---|---|
| Client ID Metadata Documents (AS metadata has `client_id_metadata_document_supported: true`) | `client_metadata_url: "https://app.example/client.json".into()` (an HTTPS URL you host) | Keycloak 26.8 with the `cimd` feature + client policy |
| no CIMD (most providers today) | **register an app** with the AS once and set `client_id` (+ `client_secret` for a confidential client) | GitHub OAuth App, Entra app registration, Keycloak client |
| several ASes | `client_ids: HashMap<issuer, client_id>`; each id is used only with its issuer | |

If neither is set, the call fails with *register an app with <issuer> and set OAuth.client_id*. A pre-registered
id wins over CIMD.

## Mode 1: automatic (CLI, desktop)

`Authorizer::default()` (Loopback, `open: None`) opens the system browser and listens on
`http://127.0.0.1:<random>/callback` (RFC 8252 §7.3: the AS must accept any port on a loopback redirect). The
call waits until the user has logged in, then returns the tool's result. In the example the in-process dev server
stands in for the browser.

<!-- from: examples/06-oauth-zero-config/main.rs -->
```rust
    let store = MemoryTokenStore::new(); // implement mcp::TokenStore to persist tokens (keyed by resource)

    // The user code. A real app leaves authorizer at its default: Loopback opens the system
    // browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
    let c = Client::new(format!("{}/mcp", ts.url)).with_auth(OAuth {
        client_id: "my-app".into(),
        store: store.clone(),
        authorizer: Authorizer::loopback(dev.approver()),
        ..Default::default()
    });

    let res = c
        .call_tool("echo", json!({"text": "hello after login"}), None)
        .await?;
    println!("echo: {}", mcp::text(&res));

    // Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
    // re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
    let answer = mcp::responder(|_, _| Ok(obj!({"action": "accept", "content": {"name": "Ada"}})));
    let res = c.call_tool("greet", json!({}), answer).await?;
    println!("greet: {}", mcp::text(&res));
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

`store` takes any `Arc<dyn TokenStore>` (`load/save/delete` keyed by resource URL); the default is in-memory.
Share one to avoid logging in again:

<!-- from: examples/06-oauth-zero-config/main.rs -->
```rust
    // A second client sharing the token store needs no new login.
    let c2 = Client::new(format!("{}/mcp", ts.url)).with_auth(OAuth {
        client_id: "my-app".into(),
        store: store.clone(),
        authorizer: Authorizer::loopback(dev.approver()),
        ..Default::default()
    });
    c2.call_tool("echo", json!({"text": "again"}), None).await?;
    let (logins, _) = dev.stats();
    println!("browser logins: {logins}"); // 1 login + 1 step-up

    let tok = store.load(&format!("{}/mcp", ts.url)).expect("token");
    println!("token stored for this issuer: {}", tok.issuer == dev.issuer); // {:?} of a Token is redacted
```

After a restart the first request goes out without a token, to learn the issuer from the `401`; only then is
the stored token sent. A `Token` debug-prints as `Token{issuer=…, redacted}`. Encrypting the store is up to you.

## Mode 2: pause and resume (web apps, bots, job queues)

A server must never block a request on a login. `Authorizer::pause(redirect)` makes the call return
`Err(Error::Pending(p))` with `{id, kind: "authorization", prompt, url, expires_at}`, the shape of toolnexus
SPEC §10 `Request`. Send the user to `url`; when the AS redirects back, `oauth().resume(id, callback)` returns the
**original call's** result. A pending authorization expires after 10 minutes.

### As one handler

<!-- from: examples/07-oauth-web-pause-resume/main.rs -->
```rust
    let c = Client::new(&mcp_url).with_auth(OAuth {
        client_id: "my-web-app".into(),
        authorizer: Authorizer::pause(format!("{app}/tool")),
        ..Default::default()
    });

    // (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
    // with ?code&state, web_handler resumes, and the tool's text is written to the response.
    let cc = c.clone();
    let tool = c.oauth().web_handler(move |r| {
        let c = cc.clone();
        async move {
            c.call_tool(
                "echo",
                json!({"text": r.query_param("text").unwrap_or_default()}),
                None,
            )
            .await
        }
    });
    let _web = HttpServer::serve_on(ln, tool, None);
```

### By hand

<!-- from: examples/07-oauth-web-pause-resume/main.rs -->
```rust
    let c2 = Client::new(&mcp_url).with_auth(OAuth {
        client_id: "my-web-app".into(),
        authorizer: Authorizer::pause("http://127.0.0.1/callback"),
        ..Default::default()
    });
    let r = c2
        .call_tool("echo", json!({"text": "resumed by hand"}), None)
        .await;
    let Some(p) = mcp::pending_of(&r) else {
        panic!("expected a pending authorization, got {r:?}")
    };
    println!(
        "(b) paused: {} - {} - has URL: {}",
        p.kind,
        !p.prompt.is_empty(),
        !p.url.is_empty()
    );

    let callback_url = approve_and_capture_callback(&p.url).await; // the user logs in; you receive the callback URL
    let res = c2.oauth().resume(&p.id, &callback_url).await?;
    println!("(b) resumed: {}", mcp::text(&res));
```

```text
(a) web_handler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand
```

The paused request is sent for the first time on `resume` (the `401` came before dispatch). A pause inside an
MRTR round resumes that same round without asking the user again.

## Client options (`OAuth { .., ..Default::default() }`)

| field | default | meaning |
|---|---|---|
| `authorizer` | `Authorizer::Loopback { open: None }` | automatic, or `Authorizer::pause(redirect)` |
| `client_id` / `client_secret` | | pre-registered client; a secret makes it confidential |
| `token_auth_method` | `client_secret_basic` if a secret, else `none` | or `client_secret_post` |
| `client_ids` | | issuer → client id |
| `client_metadata_url` | | CIMD client id |
| `store` | in-memory | `Arc<dyn TokenStore>` |
| `omit_refresh_resource` | false | drop `resource` on refresh (Entra, see Providers) |
| `http` | 30 s timeout client | for metadata and token calls |
| `allow_http` | false | plain-HTTP AS for local dev; the spec requires HTTPS |

## Protecting your own server

One line: the issuer. The server then serves PRM at `/.well-known/oauth-protected-resource/mcp`, answers `401`
with `WWW-Authenticate`, `403 insufficient_scope` with the full scope set for the operation, and verifies RS256
JWTs against the issuer's discovered JWKS (signature, `iss`, `aud` = this server's URL, `exp`).

<!-- from: examples/08-serve-protected/main.rs -->
```rust
    let mut srv = Server::new()
        .tool("whoami", "Who is calling", |ctx, _| {
            let p = ctx.principal.clone().unwrap_or_default(); // subject, scopes, claims of the verified token
            Ok(json!(format!(
                "hello {} ({})",
                p.subject,
                p.scopes.join(" ")
            )))
        })
        .tool("delete_all", "Dangerous admin tool", |_, _| {
            Ok(json!("deleted"))
        });
    srv.auth = Some(
        ServerAuth::new(&dev.issuer) // the whole required config
            .with_scopes(&[
                ("*", &["mcp:read"]),           // every request
                ("delete_all", &["mcp:admin"]), // additionally required for this tool
            ]),
    );
    let ts = HttpServer::start(srv).await; // production: HttpServer::bind(addr, srv)
```

`scopes["*"]` is required on every request and is advertised as `scopes_supported`; `scopes[tool]` is also
required for that `tools/call`. Scopes match exactly (no hierarchy). `audience` defaults to `scheme://host/mcp`
of the request; set it when a proxy changes the host, or when your AS pins the audience.

The handler reads the caller from `ctx.principal`: `subject`, `scopes`, `claims`.

```text
no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
```

### Verify hook: opaque tokens, API keys, introspection

`verify` replaces the JWT verifier entirely; a JWT does not bypass it.

<!-- from: examples/08-serve-protected/main.rs -->
```rust
    let keys: HashMap<String, Principal> = HashMap::from([(
        // e.g. a lookup in your DB, or an RFC 7662 introspection call
        "svc-key-123".to_string(),
        Principal {
            subject: "billing-service".into(),
            scopes: vec!["mcp:read".into()],
            ..Default::default()
        },
    )]);
    let mut srv2 = Server::new().tool("whoami", "Who is calling", |ctx, _| {
        Ok(json!(format!(
            "hello {}",
            ctx.principal
                .as_ref()
                .map(|p| p.subject.as_str())
                .unwrap_or_default()
        )))
    });
    srv2.auth = Some(ServerAuth {
        verify: Some(Arc::new(move |token: &str| {
            keys.get(token)
                .cloned()
                .ok_or_else(|| "unknown key".to_string())
        })), // -> 401 invalid_token
        ..ServerAuth::new(&dev.issuer).with_scopes(&[("*", &["mcp:read"])]) // issuer still advertised in the metadata
    });
    let ts2 = HttpServer::start(srv2).await;

    // A service caller with a static key: no OAuth, just an Authorization header on every request.
    let mut svc = Client::new(format!("{}/mcp", ts2.url));
    svc.header_override = vec![("Authorization".into(), "Bearer svc-key-123".into())];
    let res = svc.call_tool("whoami", Value::Null, None).await?;
    println!("verify hook: {}", mcp::text(&res));

    let mut bad = Client::new(format!("{}/mcp", ts2.url));
    bad.header_override = vec![("Authorization".into(), "Bearer wrong".into())];
    let r = bad.call_tool("whoami", Value::Null, None).await;
    println!("wrong key rejected: {}", r.is_err());
```

## DevAuthServer (dev and tests only)

`DevAuthServer::start().await` starts an OAuth 2.1 AS on `https://127.0.0.1:<port>/dev` that **approves everyone
as `dev-user`** with no login. It implements RFC 8414 metadata, JWKS, code + PKCE S256, `resource`, `iss`,
refresh-token rotation, pre-registered (`register_client`, `set_secret`) and CIMD clients. Its TLS certificate is
trusted by the default client in the same process, so `ServerAuth::new(&dev.issuer)` needs no HTTP wiring.
`dev.approver()` is a headless browser for `Authorizer::loopback(..)`. **Never use it in production.**

<!-- from: examples/06-oauth-zero-config/main.rs -->
```rust
    let dev = DevAuthServer::start().await; // DEV/TEST ONLY: auto-approves everyone as "dev-user"
    dev.register_client("my-app", &["http://127.0.0.1/callback"]); // the AS admin registered "my-app"

    let mut srv = mcp::fixture_server();
    srv.auth = Some(
        ServerAuth::new(&dev.issuer)
            .with_scopes(&[("*", &["mcp:read"]), ("greet", &["mcp:greet"])]),
    ); // greet needs more
    let ts = HttpServer::start(srv).await;
```
