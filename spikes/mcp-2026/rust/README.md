# rust — in-house MCP 2026-07-28 spike (port of `../golang`)

No MCP SDK (no rmcp). tokio + hyper 1 (server) + reqwest/rustls (client) + serde_json; RS256 via `rsa`,
dev TLS via `rcgen`. Crate `mcp2026`, standalone (its own `[workspace]`; `rust/` is untouched).

## Commands

```sh
./run.sh server                            # listens on 127.0.0.1:${PORT:-7108}/mcp
./run.sh client http://127.0.0.1:7103/mcp  # the 8 cross-port checks against ANY port's server
./run.sh selftest                          # own client vs own in-process server
cargo test                                 # 41 tests (33 hermetic on 127.0.0.1 incl. TLS + OAuth; 8 env-gated)
cargo run --example 01-connect-http        # 10 runnable user-story examples, same numbering as Go
KEYCLOAK=1 cargo test --test keycloak --test examples   # live Keycloak (../golang/keycloak, reuse if up)
GITHUB_OAUTH=1 sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- cargo test --test examples example_github
```

`tests/examples.rs` runs 01-08 and asserts their exact output (identical text to Go's, incl. the 9721/519 byte
counts); `tests/docs.rs` fails if a code block in `docs/` stops matching its example.

## LOC (`wc -l` after `cargo fmt`, which is wider-spread than gofmt)

| part | file | LOC | Go |
|---|---|---|---|
| shared types, errors, constants | lib.rs | 138 | 26 |
| HTTP layer (hyper server, dev TLS, shared reqwest client) | http.rs | 348 | (net/http) |
| client (HTTP, `_meta`, headers, SSE, MRTR) | client.rs | 356 | 263 |
| server (validation, dispatch, skills, fixture) | server.rs | 525 | 361 |
| stdio transport (client + `serve_stdio`) | stdio.rs | 218 | 172 |
| skill URI safety + `download_skill` | skills.rs | 111 | 90 |
| lazy tool search | toolsearch.rs | 190 | 167 |
| client OAuth | auth.rs | 925 | 681 |
| server auth (protected resource, JWT/JWKS) | serverauth.rs | 268 | 217 |
| DevAuthServer (dev/test only) | devauth.rs | 434 | 252 |
| 8 checks + fixture loader / CLI | checks.rs, bin/spike.rs | 197 + 44 | 183 + 51 |
| **library + CLI total** | | **3754** | 2412 |
| tests | tests/*.rs | 1445 | 2333 (incl. 443 stress) |
| examples 01-10 | examples/ | 805 | 693 |

## Port of each Go item

Client stdio + HTTP (discover, `_meta`, the 3 headers + base64 sentinel, resultType, cache hints, MRTR resume at
the paused round, re-send only after 401 / 403 insufficient_scope with WWW-Authenticate); server (`POST /mcp`, echo,
greet MRTR, -32020/-32022 order, skills, serverInfo in `_meta`); skills both sides + traversal rejection; tool
search (same BM25, same bytes); OAuth client (PRM -> 8414/OIDC -> pre-registered / CIMD, no DCR -> PKCE S256 +
resource -> state + iss -> token with JSON/form fallback -> single-flight refresh -> step-up union,
`omit_refresh_resource`, loopback + pause/resume + `web_handler`); server auth (PRM, 401/403, RS256 via JWKS,
`verify` hook, principal in `ToolContext`); DevAuthServer with the same test knobs. Test ports: auth_test,
retry_test, skills_test, toolsearch_test, stdio_test, examples_test, docs_test, a Keycloak subset.

## Deviations from Go, and why

- **API shape**: async (`.await`) everywhere; `Client::new(url).with_auth(OAuth { .., ..Default::default() })`;
  `c.oauth().resume(id, cb)`. `Error` enum (`Rpc{code,..}` / `Pending(Pending)` / `Other`) replaces `*rpcError` +
  `*PendingError`; `pending_of(&result)` = Go `PendingOf(err)`.
- **Authorizer is an enum** (`Loopback { open: Option<Opener> }` / `Pause { redirect }`), not an interface with
  Begin/Wait sessions: the two Go implementations are the only ones the spec needs.
- **Responder and tool handlers are sync closures** (`Fn(&str,&Obj)->Result<Obj,Error>`,
  `Fn(&ToolContext,Obj)->Result<Value,String>`); the Opener is async. Principal arrives in `ToolContext`, not a ctx value.
- **Server is a builder** (`Server::new().tool(..)`, `add_tool(&mut)`), served with `HttpServer::start(srv)`;
  tests that mutate auth after serving (Go `TestServerCustomVerify`) set it before starting instead.
- **Static bearer (ex. 08)**: `Client.header_override` (pub) replaces Go's custom `http.RoundTripper`.
- **DevAuthServer knobs are methods** (`register_client`, `set_secret`, `set_no_cimd`, `stats`, ...), since it runs
  in a spawned task. Its TLS cert is one process-wide self-signed cert trusted by `http::default_client()`
  (Go: trust added per started server).
- **OAuth runtime state** is a pub opaque field (`state: AuthState`) so struct-update syntax works outside the crate.
- **stdio stderr capture**: `Client::stdio_capture(cmd, sink)` instead of setting `cmd.Stderr`. The test server is
  `spike serve-stdio` (Go: the test binary itself).
- **Keycloak tests**: a 5-test subset (end-to-end + 8 checks, refresh both modes, step-up, pause/resume, wrong
  audience). They serialise on 127.0.0.1:7199 and retry the bind for 3 min, because every port's suite needs that
  address (the realm pins `aud`). CIMD-against-Keycloak and the resource-indicator probe are not ported.
- **Not ported**: stress/fuzz tests (`stress_test.go`), `GITHUB_MCP=1` PAT test, GitHub pause/resume+refresh test.
- Example 01 prints `[2026-07-28]` built by hand so the text matches Go's `fmt` output.

## Live results (2026-11-04)

- Interop: Rust client -> Go server 8/8 PASS; Go client -> Rust server 8/8 PASS.
- Keycloak 26.8.1 (shared container): example 09 + 5 live tests PASS.
- GitHub example 10: reached PRM -> GitHub AS -> loopback, but the chrome-agent profile showed GitHub's
  **"Authorize application" consent page** (not auto-approved), so per the brief nothing was clicked: needs a human.
