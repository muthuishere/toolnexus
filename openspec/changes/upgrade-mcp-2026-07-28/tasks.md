## 1. Contract

- [x] 1.1 Rewrite SPEC.md §2 (MCP source, 2026-07-28 + frozen fallback) and §7C (MCP server), add §2A (authorization)
- [ ] 1.2 Resolve design open questions (resource-on-refresh default, tools/list caching, stdio concurrency)
- [x] 1.3 Shared conformance fixtures in `examples/` (fixture server tools, MRTR greet, skills, auth cases)
- [ ] 1.4 Cross-port interop runner: every port's client against every port's server
- [x] 1.5 `openspec validate upgrade-mcp-2026-07-28`

## 2. Reference (golang)

- [ ] 2.1 Promote spike client (stdio + HTTP, discover, MRTR, no-hidden-retry) into `golang/`, behind the existing MCP source
- [ ] 2.2 Frozen fallback to the current path on discover failure; remove `mark3labs/mcp-go` only after fallback is in-house or kept isolated
- [ ] 2.3 OAuth client (auto + §10 pause/resume, CIMD/pre-registered, refresh, step-up, provider tolerance)
- [ ] 2.4 `serve` MCP profile at 2026-07-28 + ServerAuth + DevAuthServer
- [ ] 2.5 Skills extension both sides; lazy tool search
- [ ] 2.6 Gated live suites: Keycloak (docker compose) and GitHub

## 3. Port parity (each: client, fallback, OAuth client, server + auth, skills ext, tool search, conformance green)

- [ ] 3.1 js
- [ ] 3.2 python
- [ ] 3.3 java
- [ ] 3.4 csharp
- [ ] 3.5 rust
- [ ] 3.6 elixir (coverage gate ≥ 95%)
- [ ] 3.7 clojure (plain .cljc, all hosts)

## 4. Verify

- [ ] 4.1 Every port's suite green in CI, hermetic
- [ ] 4.2 Interop matrix 8×8 green
- [ ] 4.3 Keycloak live suite green for every port (local)
- [ ] 4.4 GitHub live login + tool call for every port (local, owner-run)

## 5. Docs and release notes

- [ ] 5.1 Cookbook "MCP: zero to authenticated" per language
- [ ] 5.2 Authentication guide (both modes, token store, step-up, principal, verify hook, dev AS) + tested provider notes (Keycloak, GitHub)
- [ ] 5.3 Spec support table (supported / not / deprecated, and old-server behavior)
- [ ] 5.4 CHANGELOG `## Unreleased` entry incl. BREAKING server change and what is not done (Tasks, roots/sampling/logging, DCR)
