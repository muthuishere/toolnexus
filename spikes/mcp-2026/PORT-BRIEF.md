# Porting the MCP 2026-07-28 spike to <lang>

**Reference = `spikes/mcp-2026/golang/`.** Read its README.md, docs/, examples/, and every *_test.go
before writing code. The OpenSpec change `openspec/changes/upgrade-mcp-2026-07-28/` is the contract.
Same behavior, idiomatic shape (match the style of the repo's `<lang>/` port, but do NOT edit it).

## Rules
- Code ONLY in `spikes/mcp-2026/<lang>/`. 7 agents are working in sibling dirs in parallel. Do not
  touch other dirs, do not commit, do not push.
- No MCP SDK. Language stdlib + minimal deps (HTTP server, JSON, JWT/JWKS crypto if the stdlib lacks it).
- Default HTTP port for your server: js 7101, python 7102, java 7104, csharp 7105, elixir 7106,
  clojure 7107, rust 7108 (Go is 7103). Read `PORT` env.
- Hermetic tests: localhost only. Keycloak + GitHub suites are env-gated.
- Never print tokens or secrets. GitHub creds only via
  `sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- <cmd>`; redact `gh*_` strings in output.
  The OAuth App is already authorized in the chrome-agent browser (`CHROME_AGENT_ID=toolnexus-ghoauth`,
  open the authorize URL with `chrome-agent goto <url>`); it auto-completes. If a consent page appears,
  stop and report needs_human — never click.

## Must have (same as Go)
1. Client stdio + HTTP: server/discover, `_meta`, MCP-Protocol-Version/Mcp-Method/Mcp-Name headers,
   resultType, cache hints, MRTR input_required (resume at the paused round), never re-send a
   tools/call except after 401 / 403 insufficient_scope with WWW-Authenticate.
2. Server HTTP (`POST /mcp`): fixture tools echo + greet(MRTR), -32022 / -32020 rules, skills extension,
   serverInfo also in result `_meta` (`io.modelcontextprotocol/serverInfo`).
3. Skills extension both sides + percent-decoded URI traversal rejection.
4. Lazy tool search (`search_tools` / `call_tool`, per-server description).
5. OAuth client: PRM → AS metadata (8414 then OIDC) → pre-registered clientId or CIMD (no DCR) →
   PKCE S256 + resource → state + iss check → token (Accept: application/json; form-encoded fallback) →
   refresh (single-flight) → step-up on insufficient_scope; OmitRefreshResource option;
   auto (loopback 127.0.0.1:random/callback + opener) and pause/resume modes.
6. Server auth: `{issuer, audience?, scopes per tool, verify?}`, PRM well-known, 401/403
   WWW-Authenticate, JWT via JWKS, principal visible in the tool handler; a dev auth server.
7. The 10 examples (same numbering/names as Go `examples/`) as runnable programs, plus tests that run
   01–08 and assert output; 09 Keycloak + 10 GitHub gated.
8. `docs/` — the same 6 pages as Go's docs/, with this language's code blocks taken from the tested
   examples, plus a test that fails if a doc snippet drifts from its example.
9. `run.sh` with `server`, `client <url>`, `selftest` like Go.

## Proof required before you report
- Full suite green (language's normal test runner).
- **Interop both ways with Go:** start Go's server (`cd ../golang && PORT=7103 ./run.sh server`) and run
  your `./run.sh client http://127.0.0.1:7103/mcp` → all PASS; start your server and run
  `cd ../golang && ./run.sh client http://127.0.0.1:<yourport>/mcp` → all PASS. Kill servers after.
- Keycloak gated suite: `cd ../golang/keycloak && docker compose up -d` — ANOTHER agent may also be
  using it: do NOT run `down`; if it's already up, reuse it. Run your suite against it.
- GitHub gated example 10 live.
- README.md: commands, LOC, deviations from Go and why.

Report < 250 words: per-item PASS/FAIL, LOC, interop results, anything untested.
