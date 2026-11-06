## 1. Contract

- [x] 1.1 Rewrite SPEC.md §2 (MCP source, 2026-07-28 + frozen fallback) and §7C (MCP server), add §2A (authorization)
- [x] 1.2 Resolve design open questions (SPEC §2.2a: resource sent on refresh unless `omitRefreshResource`; tools/list cached only by tool search, per `ttlMs`; stdio concurrency allowed, demuxed by id; fallback trigger incl. 5xx and stdio silence)
- [x] 1.3 Shared conformance fixtures in `examples/` (fixture server tools, MRTR greet, skills, auth cases)
- [x] 1.4 Cross-port interop runner on the shipped code: every port's client against every port's server (needs 2.4/3.x server)
- [x] 1.5 `openspec validate upgrade-mcp-2026-07-28`
- [x] 1.6 Pin the library surface (SPEC §2A.1a: `tokenStore`, `openUrl`, pause answer `data.callbackUrl`, error texts, request ids, tool-search names/output/filters)

## 2. Reference (golang)

- [x] 2.1 Promote spike client (stdio + HTTP, discover, MRTR, no-hidden-retry) into `golang/`, behind the existing MCP source
- [x] 2.2 Frozen fallback to the current path on discover failure (`mark3labs/mcp-go` kept, isolated to the fallback; removal deferred)
- [x] 2.3 OAuth client (auto + §10 pause/resume, CIMD/pre-registered, refresh, step-up, provider tolerance)
- [x] 2.4 `serve` MCP profile at 2026-07-28 + ServerAuth + DevAuthServer — follow-up on PR #140's MCPHandler
- [x] 2.5a Skills extension client; lazy tool search
- [x] 2.5b Skills extension server — with 2.4
- [ ] 2.6 Gated live suites: Keycloak (docker compose) and GitHub, against the library (spike passed both)

## 3. Port parity — client (discover, fallback, MRTR, OAuth client, skills ext client, tool search, hermetic tests)

- [x] 3.1 js
- [x] 3.2 python
- [x] 3.3 java
- [x] 3.4 csharp
- [x] 3.5 rust
- [x] 3.6 elixir (coverage gate ≥ 95%)
- [x] 3.7 clojure (plain .cljc, all hosts)

## 3S. Port parity — server (2026 `serve` profile + auth + dev AS + skills ext server), after 2.4

- [x] 3S.1 js  - [x] 3S.2 python  - [x] 3S.3 java  - [x] 3S.4 csharp
- [x] 3S.5 rust  - [x] 3S.6 elixir  - [x] 3S.7 clojure

## 4. Verify

- [ ] 4.1 Every port's suite green in CI, hermetic
- [x] 4.2 Interop matrix 8×8 green (after 3S)
- [x] 4.3 Keycloak live suite green for every port (local; interop/mcp2026/keycloak.sh, login + step-up)
- [ ] 4.4 GitHub live login + tool call for every port (local, owner-run)

## 5. Docs and release notes

- [x] 5.1 Cookbook "MCP: hosted servers and OAuth" (client side, per language)
- [ ] 5.2 Authentication guide server half (serve auth, principal, verify hook, dev AS) — with 2.4
- [x] 5.3 Spec support table (supported / not / deprecated, and old-server behavior) — in the cookbook page
- [x] 5.4 CHANGELOG `## Unreleased` entry incl. what is not done (server, BREAKING when it lands, Tasks, roots/sampling/logging, DCR)
