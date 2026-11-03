# clojure — in-house MCP 2026-07-28 spike

Port of the Go reference (`../golang`), same behaviour, Clojure shape. No MCP SDK. JVM host only:
`java.net.http` (client), `com.sun.net.httpserver` (server), `java.security` (RS256, SHA-256), plus
**koine** for JSON — the same single third-party dependency the repo's `clojure/` port uses. Plain `.cljc`,
no reader conditionals; the JVM interop is confined to `mcp2026.wire`, so a cljgo host would need only that
namespace re-done.

## Commands

```sh
./run.sh server                            # listens on 127.0.0.1:${PORT:-7107}/mcp
./run.sh client http://127.0.0.1:7103/mcp  # the 8 cross-port checks against ANY port's server
./run.sh selftest                          # own client vs own in-process server
./run.sh test                              # full suite (hermetic; Keycloak + GitHub gated)
./run.sh example 01-connect-http           # the 10 runnable user stories under examples/, see docs/
KEYCLOAK=1 ./run.sh test                   # + live Keycloak (../golang/keycloak, docker compose up -d)
GITHUB_OAUTH=1 CHROME_AGENT_ID=toolnexus-ghoauth \
  sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ./run.sh test   # + live GitHub (example 10)
```

## Layout and LOC (`wc -l`, all lines)

| part | file | LOC |
|---|---|---|
| client + server + stdio + skills + tool search + wire constants | `src/mcp2026/core.cljc` | 566 |
| client OAuth (PRM, AS discovery, PKCE, iss/state, refresh, step-up, pause/resume, web handler) | `src/mcp2026/auth.cljc` | 356 |
| server auth (PRM well-known, 401/403, RS256 JWT via JWKS, verify hook) | `src/mcp2026/serverauth.cljc` | 85 |
| DevAuthServer (dev/test only) | `src/mcp2026/devauth.cljc` | 159 |
| host plumbing: HTTP client/server, TLS trust, JSON, crypto, URL codecs | `src/mcp2026/wire.cljc` | 306 |
| CLI + the 8 conformance checks | `src/mcp2026/checks.cljc` | 80 |
| **non-test total** | | **1,552** (Go: 2,382) |
| tests | `test/mcp2026/*.cljc` | 727 |
| examples (10 programs + Keycloak headless-login harness) | `examples/*/` | 512 |
| docs (6 pages) | `docs/*.md` | 553 |

## API in one screen

```clojure
(require '[mcp2026.core :as mcp] '[mcp2026.auth :as auth])
(def c (mcp/client "https://mcp.example/mcp"
                   {:auth (auth/oauth {:client-id "my-app"})        ; optional; loopback browser login
                    :tool-search true :description "what it is for"})) ; optional; 2 meta-tools
(mcp/call c "server/discover")                      ; any method -> result map (string keys)
(mcp/call-tool c "greet" {} (fn [key req] {"action" "accept" "content" {"name" "Ada"}})) ; MRTR responder
(mcp/tools c) (mcp/invoke c "search_tools" {"query" "…"}) (mcp/download-skill c uri dir)
(mcp/stdio-client ["my-server"] {:env {…} :stderr :inherit})  ; same API; (mcp/close c)

(-> (mcp/fixture-server)                            ; or (mcp/server) / (mcp/load-server path)
    (mcp/tool "whoami" "Who" (fn [{p :principal} args] (:subject p)))
    (assoc :auth {:issuer "https://as.example" :scopes {"*" ["mcp:read"]}})
    (mcp/serve! {:port 7107}))
```

Errors are exceptions: `(mcp/rpc-code e)` for a JSON-RPC error, `(mcp/pending-of e)` for a paused authorization
(`{:id :kind "authorization" :prompt :url :expires-at}`), then `(auth/resume oauth id callback-url)`.

## Verified

- `./run.sh test`: 44 tests, 0 failures — 170 assertions hermetic (auth 16, retry/exactly-once 8, skills 4,
  tool search 2, stdio 3, examples 01-08 exact output, docs-drift 1; Keycloak/GitHub skip), 197 with
  `KEYCLOAK=1`. The tool-search sizes are byte-identical to
  Go's (56,421 eager / 519 lazy / 3,532 per 5-hit result; example 05 prints the same 9,721 / 519), because
  koine's JSON sorts keys like `encoding/json`.
- **Interop with Go, both ways**: this client → Go server on :7103: 8/8 PASS; Go client → this server on :7107:
  8/8 PASS.
- **Keycloak 26.8.1 live** (`KEYCLOAK=1`): end-to-end, 8 checks behind auth, refresh with and without
  `resource`, step-up, pause/resume, wrong-audience rejection, CIMD, and example 09: all PASS.
- **GitHub live** (example 10, `GITHUB_OAUTH=1`): see the report; run via `sec` + chrome-agent.

## Deviations from Go, and why

| Go | Clojure | why |
|---|---|---|
| `*Client` struct with mutable fields | `(mcp/client url opts)` returns a map (atoms only for id counter + tool cache) | idiomatic; `(assoc c :version …)` makes a variant |
| `error` returns, `*PendingError`, `*rpcError` | `ex-info` with `:mcp/pending` / `:mcp/rpc-error` in ex-data | idiomatic; `pending-of` / `rpc-code` read them |
| `http.Client` / `RoundTripper` | an HTTP client is a fn of a request map (`wire/request` default) | a static bearer or a traffic recorder is a 1-line wrapper |
| `Server.Tool` mutates; `Server.Auth` field | `tool` returns a new server value; `:auth` is a plain map | values; JWKS cache lives in the handler closure |
| `ToolFunc(ctx, args)` + `PrincipalFrom(ctx)` | `(fn [ctx args])`, principal at `(:principal ctx)` | no context.Context on the JVM |
| `ServerAuth.Verify(ctx, tok) (Principal, error)` | `:verify (fn [token] principal-or-nil)` | nil or throw = 401 `invalid_token` |
| `Authorizer` interface | an authorizer is `(fn [] session)`; `pause-authorizer` / `loopback-authorizer` | same seam, data-shaped |
| `httptest.NewTLSServer` cert for `DevAuthServer` | self-signed 127.0.0.1 cert made once per JVM with the JDK's `keytool`; trusted by a process-wide trust manager | the JDK has no public API to mint an X.509 cert |
| `examples/NN/main.go` built + run | `examples/NN/main.cljc` run as scripts (`clojure.main file`) in fresh JVMs | same numbering and names; output asserted exactly |
| example 07 label `(a) WebHandler:` | `(a) web-handler:` | names the Clojure fn |
| `stress_test.go`, `FuzzServerHandler`, `github_test.go` extras | not ported | spike scope: the GitHub path is covered by example 10; no load/fuzz numbers for this port |

Keycloak headless-login quirk (JVM only): Keycloak sets its session cookies `Secure`, and `java.net.CookieManager`
withholds `Secure` cookies over `http://localhost` (Go's cookiejar sends them), so the harness
(`examples/09-keycloak/headless.cljc`) keeps a tiny per-host jar. Real browsers are unaffected.

## Not done / untested

Same gaps as Go (`x-mcp-header`, SSE progress — the SSE body is buffered, `requestState` integrity, pagination,
real YAML frontmatter, `private_key_jwt`, encrypted token store, Entra live). Not ported: load/stress tests and
the fuzz target. cljgo host not attempted (`wire.cljc` is the only JVM-bound namespace).
