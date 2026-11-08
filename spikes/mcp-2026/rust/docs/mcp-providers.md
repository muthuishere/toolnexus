# MCP authentication: provider notes

> **Rust port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/rust` (crate `mcp2026`; reference: `spikes/mcp-2026/golang`). Every code block is
> copied verbatim from a program under `examples/` that `tests/examples.rs` runs and checks; `tests/docs.rs`
> fails if a block here stops matching its source.

| provider | client identity | tested (Rust port) | status |
|---|---|---|---|
| Keycloak 26.8.1 | pre-registered public client | live, docker compose (`KEYCLOAK=1 cargo test --test keycloak`) | works |
| GitHub hosted MCP (`api.githubcopilot.com/mcp/`) | OAuth App (client id + secret); no DCR, no CIMD | live run reached GitHub's consent page | needs a human click once (see below) |
| Microsoft Entra ID | app registration | **UNTESTED** (simulated only) | see note |

## Keycloak

Runnable: `../golang/keycloak/docker-compose.yml` + `realm-toolnexus.json` (realm `toolnexus`, client
`toolnexus-mcp`, throwaway user `alice`), example `examples/09-keycloak`.

```sh
(cd ../golang/keycloak && docker compose up -d)
KEYCLOAK=1 cargo run --example 09-keycloak                    # logs in through your browser
KEYCLOAK=1 cargo test --test keycloak --test examples         # the live suite, headless
```

<!-- from: examples/09-keycloak/main.rs -->
```rust
    let mut srv = mcp::fixture_server().tool("whoami", "Who is calling", |ctx, _| {
        let p = ctx.principal.clone().unwrap_or_default();
        // subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
        let azp = p
            .claims
            .get("azp")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        Ok(json!(format!(
            "client={azp} scopes={} subject-set={}",
            p.scopes.join(" "),
            !p.subject.is_empty()
        )))
    });
    srv.auth = Some(ServerAuth {
        audience: RESOURCE.into(), // = the audience mapper's included.custom.audience
        ..ServerAuth::new(ISSUER).with_scopes(&[("*", &["mcp:tools"]), ("greet", &["mcp:greet"])])
    });
    let _server = headless::bind_retry(MCP_ADDR, srv).await;

    // ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
    let authorizer = if std::env::var("HEADLESS_LOGIN").as_deref() == Ok("1") {
        Authorizer::loopback(headless::login("alice", "alice-test-pw")) // throwaway local test user
    } else {
        Authorizer::default() // system browser
    };
    let c = Client::new(RESOURCE).with_auth(OAuth {
        client_ids: [(ISSUER.to_string(), "toolnexus-mcp".to_string())].into(), // client id bound to THIS issuer
        allow_http: true, // dev Keycloak is plain HTTP; never in production
        authorizer,
        ..Default::default()
    });

    let res = c.call_tool("whoami", Value::Null, None).await?;
    println!("whoami: {}", mcp::text(&res));

    // greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
    let answer =
        mcp::responder(|_, _| Ok(obj!({"action": "accept", "content": {"name": "Alice"}})));
    let res = c.call_tool("greet", json!({}), answer).await?;
    println!("greet: {}", mcp::text(&res));
```

```text
whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true
greet: Hello, Alice!
```

The realm's audience mapper pins `aud = http://127.0.0.1:7199/mcp`, so every port's Keycloak suite needs that
address; the Rust tests serialise on it and retry the bind for up to 3 minutes. Setup checklist (client, scopes,
audience mapper, `basic` scope, CIMD policy) is in the Go spike's `docs/mcp-providers.md` — it is about Keycloak,
not the language.

## GitHub

GitHub's hosted MCP server speaks 2026-07-28. Its AS supports neither DCR nor CIMD, so register an **OAuth App**
(callback URL `http://127.0.0.1/callback`; GitHub ignores the port on loopback) and pass its id + secret.

<!-- from: examples/10-github/main.rs -->
```rust
    let c = Client::new("https://api.githubcopilot.com/mcp/").with_auth(OAuth {
        client_id: id,
        client_secret: secret, // GitHub OAuth Apps are confidential clients (client_secret_basic)
        authorizer: login,
        ..Default::default()
    });

    let tools = c.tools().await?;
    println!("tools listed: {}", !tools.is_empty());
```

```sh
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- cargo run --example 10-github
```

The client handles GitHub's form-encoded token response (it sends `Accept: application/json` and also parses
`application/x-www-form-urlencoded`), `serverInfo` in result `_meta`, and a missing `isError`.

## Microsoft Entra ID — UNTESTED

Known from python-sdk issue #2578: Entra rejects `resource` on the `refresh_token` grant, though the MCP spec says
it MUST be sent. Set `omit_refresh_resource: true` for Entra; authorize and code exchange still send `resource`.
Only the simulation (`DevAuthServer::set_reject_refresh_resource`, `tests/retry.rs refresh_resource_configurable`)
covers this.
