# golang — in-house MCP 2026-07-28 spike

Stdlib only (`net/http`, `encoding/json`, `crypto/sha256`). No MCP SDK. Go 1.23+.

## Commands

```sh
./run.sh server                          # listens on 127.0.0.1:${PORT:-7103}/mcp
./run.sh client http://127.0.0.1:7103/mcp  # run all client checks against ANY port's server
./run.sh selftest                        # own client vs own in-process server
go vet ./... && go test -race ./...      # unit tests incl. OAuth (hermetic: localhost TLS only)
```

Self-test output (all PASS): server/discover, unsupported version -> -32022,
header mismatch -> -32020, tools/list, tools/call echo, tools/call greet (MRTR), skills/list,
skills/get hello-world.

## LOC (`wc -l`, all lines, tests/CLI separated)

| part | file | LOC |
|---|---|---|
| server | server.go | 285 |
| client | client.go | 130 |
| shared constants | protocol.go | 26 |
| CLI + self-test checks | main.go | 207 |
| **client OAuth** | auth.go | 542 |
| **server auth (protected resource)** | serverauth.go | 212 |
| **DevAuthServer (dev/test only)** | devauth.go | 217 |
| tests | auth_test.go | 336 |
| client/server wiring changes | client.go (130→161), server.go (285→306) | +52 |
| **stdio client transport** | stdio.go | 133 |
| **lazy tool search** | toolsearch.go | 167 |
| **skill URI safety + DownloadSkill** | skills.go | 90 |
| tests (round 3) | retry/stdio/skills/toolsearch _test.go | 517 |

Round 3 (2026-11-02) delta: non-test 1898 → 2382 (+484), tests 867 → 1388 (+521).

Core MCP layer (client + server + shared) ≈ 440 lines.

## What it does

- Server: `server/discover` (supportedVersions, capabilities incl. `extensions["io.modelcontextprotocol/skills"]`,
  serverInfo in `_meta`, ttlMs/cacheScope); `tools/list` sorted by name with `ttlMs`/`cacheScope`/`resultType`;
  `tools/call` echo (+`structuredContent`) and greet (MRTR: `input_required` with one form
  `elicitation/create` keyed `name`; re-asks if the answer is missing/declined); `skills/list`, `skills/get`
  (`-32602` for unknown), plus `resources/read` for `skill://` files (the SEP requires the resources capability).
  Header validation: missing/mismatched `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` (base64 sentinel decoded)
  → 400 + `-32020`; unsupported `_meta` version → 400 + `-32022` with `{supported, requested}`; unknown method → 404 + `-32601`.
- Client: `_meta` (protocolVersion, clientCapabilities, clientInfo) + all headers on every request, base64
  sentinel encoding for `Mcp-Name`, accepts JSON or SSE responses, treats missing `resultType` as complete,
  `CallTool` loops MRTR rounds through a `Responder` callback (stand-in for §10 waitFor), echoing `requestState`.

## Spec ambiguities hit

1. Header vs `_meta` precedence: if `MCP-Protocol-Version` header and `_meta` disagree AND the value is unsupported,
   is it -32020 or -32022? Chose -32020 (mismatch checked first).
2. Missing `MCP-Protocol-Version` header: spec lists it as a -32020 condition but also allows treating it as
   2025-03-26. Chose -32020.
3. Where `inputResponses`/`requestState` live on retry: examples imply `params.inputResponses`; schema prose is
   not explicit in the MRTR page. Used `params`.
4. Client elicitation capability shape (`{"elicitation":{}}` vs `{"elicitation":{"form":{}}}`): sent the latter;
   server does not gate on it (MUST NOT send unsupported inputRequests — not enforced in spike).
5. `skills/get` caching attributes left open by SEP-2640; omitted. `skills/list` URI choice (`skill://<name>/SKILL.md`)
   is server-chosen, so cross-port checks rely on all ports using no prefix.
6. Whether `resultType` belongs on `server/discover`/`skills/*` results (spec says "all results"); included everywhere.
7. Frontmatter "verbatim as JSON" needs a YAML parser for real skills; spike parses flat `key: value` only —
   the one real dependency an owned implementation would need (or a small YAML subset).

## Verdict

**Own it.** The stateless 2026-07-28 revision removed the hard parts (sessions, handshake, server→client
requests, resumable SSE). The whole wire layer is ~440 lines of stdlib Go, no goroutine lifecycle to manage,
and MRTR maps cleanly onto a callback. Remaining work for production: SSE progress
notifications, `x-mcp-header` mirroring/validation, requestState integrity, real YAML frontmatter.

## OAuth (MCP 2026-07-28 `basic/authorization`)

Read from the spec, not memory: [basic/authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization),
[authorization-server-discovery](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery),
[client-registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration),
[security-considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations).
Added ≈ 970 LOC non-test (client 542 + server 212 + dev AS 217) + 52 wiring, stdlib only (RS256 via `crypto/rsa`).

### Client API (the product)

```go
c.Auth = &OAuth{
    Authorizer:        LoopbackAuthorizer{},                 // (a) auto: browser + 127.0.0.1:<random>/callback
    // Authorizer:     PauseAuthorizer{Redirect: cbURL},     // (b) pause: returns *PendingError, never blocks
    ClientIDs:         map[string]string{issuer: "my-id"},   // pre-registered, bound to that issuer
    ClientMetadataURL: "https://app.example/client.json",    // CIMD, used if AS advertises support
    Store:             myStore,                              // TokenStore; nil = in-memory
}
_, err := c.CallTool("echo", args, nil)
var pe *PendingError                       // pe.Pending = {ID, Kind:"authorization", Prompt, URL, ExpiresAt}
if errors.As(err, &pe) { /* show pe.Pending.URL; later: */ res, err = c.Auth.Resume(ctx, pe.Pending.ID, callbackURL) }
```
`Pending` mirrors SPEC §10 `Request` (id/kind/prompt/url/expiresAt); `Resume(id, callback)` is the `Answer`
and returns the **original tool call's** result. `Authorizer` is the seam a toolnexus `waitFor` plugs into.

### Server API

```go
as := StartDevAuthServer()                       // DEV/TEST ONLY: auto-approves as "dev-user"
srv.Auth = &ServerAuth{Issuer: as.Issuer, HTTP: as.Client(),
    Scopes: map[string][]string{"*": {"mcp:read"}, "greet": {"mcp:greet"}}}
// Verify: optional func(ctx, token) (Principal, error) replaces JWT/JWKS (opaque / introspection)
// tool handlers: p, _ := PrincipalFrom(ctx)   // Subject, Scopes, Claims
```
Serves PRM at `/.well-known/oauth-protected-resource[/mcp]`; 401 + `WWW-Authenticate: Bearer resource_metadata=…, scope=…`
(+`error="invalid_token"` when a token was presented); 403 `insufficient_scope` with the full scope set for the operation.
Default verifier: RS256 JWT, keys from the issuer's discovered `jwks_uri`, checks signature, `iss`, `aud` = resource, `exp`.

### Spec-mandated (section) vs our choice

| behaviour | source |
|---|---|
| PRM from `resource_metadata`, else path-inserted then root well-known | MUST — *AS Discovery › PRM Discovery Requirements* |
| PRM `resource` must equal the server URI | RFC 9728 §3.3 (spec defers to it) |
| AS metadata order (oauth-as path-insert → oidc path-insert → oidc append) + `issuer` identity check | MUST — *AS Discovery › AS Metadata Discovery* |
| Refuse if `code_challenge_methods_supported` lacks S256 | MUST — *Security › Authorization Code Protection* |
| `resource` on authorize **and** token requests; on refresh unless `OmitRefreshResource` | MUST — *Resource Parameter Implementation*; opt-out because Entra rejects it on refresh (python-sdk #2578) |
| `iss` validation table, before acting on `error` | MUST — *Authorization Response Validation* |
| `state` check | SHOULD — *Security › Open Redirection* |
| Scope: challenge `scope`, else `scopes_supported`; step-up = union with previous | SHOULD — *Scope Selection Strategy*, *Step-Up Authorization Flow* |
| Registration: pre-registered (`ClientID`/`ClientIDs`, optional `ClientSecret` + `TokenAuthMethod` basic/post) → CIMD; neither → error "register an app with <issuer> and set OAuth.ClientID" | SHOULD — *Client Registration*; **no DCR, ever** (owner decision) |
| Token only sent once the server's AS issuer is confirmed; credentials keyed by issuer | MUST — *Token Handling*, *Authorization Server Binding* |
| AS endpoints HTTPS (`AllowHTTP` escape hatch) | MUST — *Security › Communication Security* |
| Server: 401 invalid/expired, 403 insufficient_scope, audience check | MUST/SHOULD — *Token Handling*, *Scope Challenge Handling* |
| Pick `authorization_servers[0]`; ≤3 auth retries per request; 10 min pending expiry; 10 s expiry skew | ours |
| After a restart, the first request goes token-less (401) to learn the issuer before a stored token is sent | ours (safe reading of "MUST NOT send tokens other than…") |
| Resume sends the **paused request** (same MRTR round, same `inputResponses`/`requestState`) for the first time | ours — the 401/403 came before dispatch, so the tool never ran; earlier rounds are not replayed |
| Re-send only after 401 / 403 `insufficient_scope` with `WWW-Authenticate`; lost connection / timeout / 5xx / bad body is **never** retried | ours (spec issue #3394: no idempotency guarantee for `tools/call`) |
| Header validation (-32020/-32022) runs before auth | ours |
| Exact scope match on the server (no hierarchy) | ours — spec says servers MUST account for hierarchies; not modelled |

### Ambiguities

1. Initial vs step-up scope union: the 401 path says "SHOULD include alongside previously granted" — we union only on 403.
2. Which scopes a 403 should list: "scopes for the current operation" — server emits the full needed set (base + tool), not just the missing one.
3. Multiple `authorization_servers`: selection is left to RFC 9728 §7.6; first wins.
4. PKCE check for OIDC metadata lacking the field is MUST-refuse; real providers that omit it will fail (intended).
5. Loopback redirect with a random port vs CIMD `redirect_uris` exact-match: relies on AS honouring RFC 8252 §7.3 port-agnostic loopback; the spec's CIMD section says "MUST validate redirect URIs" without mentioning it.
6. `iss` comparison forbids normalization, yet the canonical-URI section says accept uppercase scheme/host — different values, but easy to confuse.

### Unverified

No real AS (Entra/Auth0/Keycloak) or real browser tested (the Entra refresh behaviour is simulated by `DevAuthServer.RejectRefreshResource`);
`private_key_jwt`, `offline_access`, token persistence encryption not done.

## Round 3 additions (client-first)

**Exactly-once side effects.** `retry_test.go` counts server-side executions: pause→Resume = 1, refresh after a
server 401 `invalid_token` = 1, 401→login→403→step-up = 1, a pause in MRTR round 1 resumes round 1 without re-asking,
and a dropped connection / 502 *after* the tool ran = 1 with an error returned (no hidden retry).

**stdio transport** (`stdio.go`): `c, _ := NewStdioClient(exec.Command("my-server")); defer c.Close()` — same `Call`/
`CallTool`/`Tools` API; one JSON-RPC message per line, responses demuxed by id (concurrent calls OK); stderr goes to
`cmd.Stderr` (nil = `os.Stderr`), never parsed; a dead server fails pending calls instead of hanging. No auth/headers over
stdio. The test binary doubles as a stdio server (`TestMain`, test-only — the product server stays HTTP only) and runs
the same 8 checks: 7 PASS, header-mismatch SKIP (no headers on stdio).

**Skill URI safety** (`skills.go`): `parseSkillURI` percent-decodes once, then rejects any leftover `%` (double
encoding), `\`, NUL, `:`, and empty/`.`/`..` segments. Server checks it before lookup (-32602);
`Client.DownloadSkill(uri, dir)` checks every resource URI (same skill, `filepath.IsLocal`) and the sha256 digest
before writing anything.

**Lazy tool search** (`toolsearch.go`): `c.ToolSearch = true; c.Description = "…"` → `c.Tools()` returns
`search_tools(query, limit?)` + `call_tool(name, arguments)`; `c.Invoke(name, args, respond)` runs them. BM25 over
name (×2, camelCase/`_` split) + description; results carry `name/description/inputSchema/server`; tools/list cached for
its `ttlMs`. 100-tool fake server: **eager 56,421 bytes of tool JSON vs lazy 519** (+3,532 per 5-hit search result).

### Round 3 spec ambiguities

7. stdio + `MCP-Protocol-Version`/`Mcp-Name`: no headers exist, so -32020 cannot occur; only `_meta` version (-32022) applies.
8. Whether a stdio client must serialise requests: nothing forbids concurrent ids on one pipe; we multiplex.
9. Skill URI grammar is not normative in SEP-2640 (no rule on percent-encoding or `..`); we reject rather than normalise.
10. `resource` on refresh is a MUST in the MCP spec but real ASes reject it — the opt-out deviates from the letter of the spec.
11. Tool search is client-side only; the spec has no server-side search method, so every search needs the full tools/list once.
