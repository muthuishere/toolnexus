# csharp — in-house MCP 2026-07-28 spike (port of `../golang`)

.NET 10, no MCP SDK. The only dependency is the ASP.NET Core shared framework (`FrameworkReference
Microsoft.AspNetCore.App`, Kestrel), for the HTTP server side. Everything else is BCL: `System.Text.Json.Nodes`,
`HttpClient`, `RSA` (RS256), `SHA256`, `Process`.

## Commands

```sh
./run.sh server                            # listens on 127.0.0.1:${PORT:-7105}/mcp
./run.sh client http://127.0.0.1:7103/mcp  # the 8 cross-port checks against ANY port's server
./run.sh selftest                          # own client vs own in-process server
dotnet test tests/Mcp2026.Tests            # 81 tests, hermetic (127.0.0.1 only; builds examples + CLI too)
dotnet run --project examples/01-connect-http   # the 10 user-story examples, see docs/
KEYCLOAK=1 dotnet test tests/Mcp2026.Tests      # + live Keycloak (../golang/keycloak compose)
GITHUB_OAUTH=1 sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- dotnet test tests/Mcp2026.Tests --filter GitHub
```

Layout: `src/Mcp2026` (library, namespace `Mcp2026`), `src/Spike` (CLI), `examples/NN-*` (one program per user
story, same numbering/names as Go), `tests/Mcp2026.Tests` (xunit), `docs/` (the 6 pages, C# code blocks copied
from the examples; `DocsTests` fails on drift). Examples 01-08 print **byte-identical** output to Go's.

## LOC (`wc -l`, non-test)

| part | file | LOC |
|---|---|---|
| server (fixture tools, code tools, skills, header validation) | Server.cs | 334 |
| client (HTTP, `_meta`, headers, SSE, MRTR, retry rule) | Client.cs | 155 |
| shared constants + JSON helpers | Protocol.cs | 87 |
| Kestrel host + dev TLS trust | HttpHost.cs | 97 |
| stdio client + test stdio server | Stdio.cs | 129 |
| skill URI safety + DownloadSkill | Skills.cs | 75 |
| lazy tool search | ToolSearch.cs | 150 |
| **client OAuth** | OAuth.cs | 481 |
| **server auth** | ServerAuth.cs | 139 |
| **DevAuthServer (dev/test only)** | DevAuthServer.cs | 235 |
| conformance checks | Checks.cs | 93 |
| CLI | Program.cs | 26 |
| **total library + CLI** | | **2001** (Go: 2382) |
| tests | tests/*.cs | 945 |
| examples | examples/*/*.cs | 556 |

## Deviations from Go, and why

1. **Async + exceptions.** Every call is `…Async`; errors are exceptions: `McpRpcException` (JSON-RPC error, `.Code`),
   `McpException` (transport/protocol/auth), `PendingAuthorizationException` (`.Pending`) instead of `*PendingError`.
   `Mcp.PendingOf(ex)` mirrors `PendingOf(err)`.
2. **JSON is `JsonObject`** (`System.Text.Json.Nodes`), not `map[string]any`; nodes are deep-cloned at every
   boundary (a node can have one parent). Serialization uses the relaxed encoder so byte sizes match Go (9721 / 519).
3. **Principal reaches handlers as `ToolContext.Principal`** (an explicit parameter) instead of `PrincipalFrom(ctx)`.
   Handlers are `(ToolContext, JsonObject) => object?` (sync) or `ToolFunc` (async).
4. **`WebHandler` returns an ASP.NET Core `RequestDelegate`**, not `http.Handler`; `Server.HandleAsync(HttpContext)`
   is the server entry point (`HttpHost` wraps Kestrel as Go's `httptest.Server`).
5. **Manual redirect following.** .NET's `HttpClient` refuses https→http redirects, and every loopback callback is
   one, so `DevAuthServer.ApproveAsync/BrowseAsync` follow redirects by hand (Go's `as.Client()` just follows).
6. **Keycloak cookies.** Keycloak marks cookies `Secure` on `http://localhost`; `CookieContainer` will not send them
   over http (Go's jar does), so the headless-login harness keeps its own minimal jar. Harness only.
7. **The stdio test server is the CLI** (`spike stdio-fixture` / `stdio-exit`, test-only verbs) instead of the Go
   test binary re-executing itself. Example 02 re-executes itself like Go.
8. **DevTrust:** dev certificates are trusted by a validation callback on the shared `HttpClient` handler
   (Go swaps the process transport's root pool). Same effect: zero HTTP wiring in dev.
9. Not ported: Go's stress / fuzz suites and the Keycloak CIMD + resource-indicator probes (the CIMD flow itself is
   covered hermetically against the DevAuthServer).

Spec ambiguities and choices are exactly the Go spike's (see `../golang/README.md`); no new ones surfaced.

## Verified (2026-11-04)

- `dotnet test`: 81/81 pass (hermetic); with `KEYCLOAK=1`: 81/81 incl. 6 live Keycloak tests + example 09.
- Interop: C# client → Go server (7103) 8/8 PASS; Go client → C# server (7105) 8/8 PASS.
- **GitHub live (example 10): NOT verified.** The chrome-agent browser stops on GitHub's *"Reauthorization required —
  unusually high number of requests"* consent page (the same OAuth App was being authorized by 7 parallel ports).
  Per the rules nobody clicks it: needs a human to approve once, then re-run the GitHub command above.
