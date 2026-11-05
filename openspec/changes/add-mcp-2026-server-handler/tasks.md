## 1. Contract

- [x] 1.1 SPEC.md §7C.1 versions bullet + new §7C.2 (routing, validation, results, MRTR, skills, auth, dev AS)
- [x] 1.2 ADR 0040
- [x] 1.3 Shared conformance: `conformance/mcp_handler/cases.json` `mcp2026_*` scenarios + harness README
- [x] 1.4 `openspec validate add-mcp-2026-server-handler`

## 2. Reference (golang)

- [x] 2.1 2026 wire on MCPHandler, `ListSkills`, `LoadMCPSkills`, `MCPAuthError`
- [x] 2.2 `MCPServerAuth` (PRM, challenges, RS256/JWKS, `Verify`) + `StartMCPDevAuthServer`
- [x] 2.3 Conformance harness + unit tests
- [x] 2.4 Interop: `interop/mcp2026/run.sh` — PR #142's Go client against this server (MRTR, confirm, skills, tool search, OAuth pre-registered + CIMD + step-up, fallback)

## 3. Port parity (handler wire + MCPServerAuth + dev AS + loadMcpSkills + conformance + tests + README)

- [ ] 3.1 js
- [ ] 3.2 python
- [ ] 3.3 java
- [ ] 3.4 csharp
- [ ] 3.5 rust
- [ ] 3.6 elixir (coverage gate ≥ 95%)
- [ ] 3.7 clojure (plain .cljc, JVM + cljgo)

## 4. Verify

- [ ] 4.1 Every port's suite green locally and in CI
- [x] 4.2 Go interop green locally
- [ ] 4.3 Cross-port interop matrix (every port's 2026 client × every port's server) — needs #142 merged; not in this change
- [ ] 4.4 Keycloak live suite against the server (owner, local)

## 5. Docs

- [x] 5.1 CHANGELOG `## Unreleased` entry, incl. what is not done
- [ ] 5.2 Per-port README section (golang done; others with 3.x)
- [ ] 5.3 site API pages for the new symbols (not in this change)
