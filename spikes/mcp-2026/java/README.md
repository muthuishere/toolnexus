# java — in-house MCP 2026-07-28 spike (port of `../golang`)

JDK 21 only (`java.net.http`, `com.sun.net.httpserver`, `java.security`) + Jackson for JSON. No MCP SDK.
Package `io.github.muthuishere.toolnexus.mcp2026`; Gradle (wrapper copied from `../../../java`, toolchain 21).

## Commands

```sh
./run.sh server                              # listens on 127.0.0.1:${PORT:-7104}/mcp
./run.sh client http://127.0.0.1:7103/mcp    # the 8 cross-port checks against ANY port's server
./run.sh selftest                            # own client vs own in-process server
./gradlew test                               # whole suite, hermetic (127.0.0.1 only; TLS via a dev cert)
./gradlew -q runExample -Pex=01              # the 10 user-story examples (examples/NN-*/Main.java), see docs/
KEYCLOAK=1 ./gradlew test --tests '*Keycloak*' --tests '*ExamplesTest.keycloak'   # needs ../golang/keycloak up
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- env GITHUB_OAUTH=1 CHROME_AGENT_ID=toolnexus-ghoauth \
  ./gradlew --no-daemon test --tests '*ExamplesTest.github'
```

`ExamplesTest` runs examples 01-08 as separate JVMs and asserts their **exact** output — the same text the Go
examples print, byte counts included (`eager: 100 tools, 9721 bytes`; the 100-tool test measures 56,421 / 519 /
3,532 bytes, identical to Go because JSON is written with sorted keys). `DocsTest` fails if any of the 16 code
blocks in `docs/` stops matching its example.

## Status (2026-11-04)

| check | result |
|---|---|
| `./gradlew test` | 49 tests: 41 pass, 8 env-gated skips (6 Keycloak, examples 09/10) |
| interop: Java client → Go server :7103 | 8/8 PASS |
| interop: Go client → Java server :7104 | 8/8 PASS |
| Keycloak live (`KEYCLOAK=1`) | 6/6 KeycloakTest + example 09 PASS |
| GitHub live (example 10) | **not completed — needs_human**: GitHub showed "Reauthorization required: this application has made an unusually high number of requests" (7 ports authorizing the same OAuth App in parallel). Per the brief nothing was clicked. Re-run the command above once a human approves. |

## LOC (`wc -l`)

| part | files | LOC |
|---|---|---|
| server (fixture, dispatch, header rules, skills, stdio serve) | Server.java, HttpReq/HttpResp, LocalServer | 526 |
| client (HTTP + MRTR + skills download) | Client.java, RpcError, McpException, Mcp | 389 |
| stdio client transport | StdioConn.java | 120 |
| skill URI safety | SkillUri.java | 47 |
| lazy tool search | ToolSearch.java | 97 |
| client OAuth | OAuth, Token, TokenStore, MemoryTokenStore, Pending(+Exception), Authorizer, Pause/LoopbackAuthorizer | 632 |
| server auth (protected resource) | ServerAuth, Principal, ToolContext, ToolFunc | 181 |
| DevAuthServer + dev TLS (dev/test only) | DevAuthServer, DevTls | 361 |
| CLI + conformance checks | Spike, Checks | 134 |
| **library total** | 26 files | **2,487** |
| tests | 9 files | 981 |
| examples | 11 files | 684 |

Go for comparison: 2,382 non-test. Java is about the same size; it pays for fluent setters, checked-exception
plumbing and the HTTP adapter layer, and saves on error handling.

## Deviations from Go, and why

1. **API shape is Java, behaviour is Go's.** Fluent setters (`new OAuth().clientId(..).store(..)`) instead of
   struct fields; unchecked exceptions instead of `error`: `RpcError` (code/data), `PendingException` (`Pending.of(e)`
   = Go's `PendingOf`), `McpException` for everything else. `ctx context.Context` arguments are dropped
   (`resume(id, callbackUrl)`).
2. **Principal reaches tools via `ToolContext`** (`(ctx, args) -> ctx.principal()`), not `context.Context` values.
3. **Static credentials: `Client.header(name, value)`** replaces Go's custom `http.RoundTripper` (example 08) and the
   private `headerOverride` used by the checks. It is public because it is the idiomatic way to send an API key.
4. **`Server` is a `com.sun.net.httpserver.HttpHandler`**, and also a pure function `handle(HttpReq) -> HttpResp`
   so tests can drive it from a raw socket (the "connection dropped after execution" test).
5. **`LocalServer`** stands in for Go's `httptest.Server` (examples/tests need a 127.0.0.1 server in one line).
6. **Dev TLS:** the JDK cannot mint certificates through a public API, so `DevTls` runs the JDK's own `keytool` once
   per JVM to make a self-signed 127.0.0.1 cert; the shared default `HttpClient` trusts it (exact-match) on top of the
   system CAs — the same "in-process dev servers are trusted" rule as Go's `trustDevCert`.
7. **`webHandler(query -> ...)`** passes the parsed query map instead of the `*http.Request`.
8. **`DevAuthServer.get(url, follow)`** is the headless browser (Java's `HttpClient` will not follow an
   https→http redirect, which the loopback callback always is).
9. **Keycloak headless login keeps its own cookie jar**: Keycloak sets `Secure` session cookies on
   `http://localhost` and `java.net.CookieManager` refuses to return them (HTTP 400 on the login POST).
10. **KeycloakTest** ports EndToEnd, EightSteps, Refresh (both `omitRefreshResource` values), StepUp, PauseResume,
    WrongAudience. Not ported: CIMD-against-Keycloak and the resource-indicator probe, and the request-recorder
    assertions on raw token-endpoint forms (no transport hook in `java.net.http`); the same form rules are asserted
    hermetically against `DevAuthServer.lastTokenForm()`. Port 7199 is shared with other ports' suites; the test
    waits for it.
11. **Not ported: `stress_test.go`** (load, cold-login herd, refresh race, hostile inputs, fuzz). Concurrency is
    covered only by the 20-concurrent-calls stdio test and single-flight locking that mirrors Go's.
12. `GitHub` live tests other than example 10 (`TestGitHubLive` with a `gh` token, pause/resume + forced refresh) are
    not ported.

Spec ambiguities and choices are identical to Go's (see `../golang/README.md`); no new ones were hit.
