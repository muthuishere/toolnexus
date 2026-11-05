## 1. Contract

- [x] 1.1 SPEC.md §7C.1 versions bullet + new §7C.2 (routing, validation, results, MRTR, skills, auth, dev AS)
- [x] 1.2 ADR 0040
- [x] 1.3 Shared conformance: `conformance/mcp_handler/cases.json` `mcp2026_*` scenarios + harness README
- [x] 1.4 `openspec validate add-mcp-2026-server-handler`

## 2. Reference (golang)

- [x] 2.1 2026 wire on MCPHandler, `ListSkills`, `LoadMCPSkills`, `MCPAuthError`
- [x] 2.2 `MCPServerAuth` (PRM, challenges, RS256/JWKS, `Verify`) + dev AS (test-only: `internal/devauth`)
- [x] 2.3 Conformance harness + unit tests
- [x] 2.4 Interop: `interop/mcp2026/run.sh` — PR #142's Go client against this server (MRTR, confirm, skills, tool search, OAuth pre-registered + CIMD + step-up, fallback)

## 3. Port parity (handler wire + MCPServerAuth + dev AS + loadMcpSkills + conformance + tests + README)

- [x] 3.1 js
- [x] 3.2 python
- [x] 3.3 java
- [x] 3.4 csharp
- [x] 3.5 rust
- [x] 3.6 elixir (coverage gate ≥ 95%)
- [x] 3.7 clojure (plain .cljc, JVM + cljgo)

## 4. Verify

- [ ] 4.1 Every port's suite green locally and in CI
- [x] 4.2 Go interop green locally
- [ ] 4.3 Cross-port interop matrix (every port's 2026 client × every port's server) — needs #142 merged; not in this change
- [ ] 4.4 Keycloak live suite against the server (owner, local)

## 5. Docs

- [x] 5.1 CHANGELOG `## Unreleased` entry, incl. what is not done
- [x] 5.2 Per-port README section
- [ ] 5.3 site API pages for the new symbols (not in this change)

## 6. Dev authorization server is test-only

- [x] 6.1 Move the dev AS out of library source into each port's test tree (go, js, python, java, csharp, elixir, clojure, rust); drop every export; interop server uses the Go internal copy
- [x] 6.2 SPEC §7C.2, ADR 0040, this change, CHANGELOG and port READMEs say test/interop-only
