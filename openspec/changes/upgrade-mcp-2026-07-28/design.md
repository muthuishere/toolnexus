## Context

Today every port has an MCP client (stdio + HTTP, tools, resources, elicitation → §10, progress timeout) and an
MCP server (`serve /mcp`, stateless HTTP, tools only). Five ports wrap an SDK; elixir and clojure are in-house
(declaring 2025-06-18 and 2024-11-05). No port has OAuth. The 2026-07-28 spec removes the handshake, sessions and
server→client requests, which is what makes an in-house implementation small. The Go spike
(`spikes/mcp-2026/golang`, ~2.4k LOC + 1.4k LOC tests) is the reference implementation and was verified live
against Keycloak 26.8.1 (docker compose, CIMD included) and GitHub's hosted MCP server (OAuth App, pause/resume,
forced refresh).

## Goals / Non-Goals

**Goals:** one owned MCP layer per port at 2026-07-28; OAuth that needs zero config when the AS supports CIMD and
one `clientId` when it does not; server protection in one line; SEP-2640 skills; lazy tool search; byte-identical
behavior across the 8 ports, proven by a shared conformance suite and a cross-port interop matrix.

**Non-Goals:** roots, sampling, logging (deprecated); DCR (owner decision); MCP server over stdio; Tasks
extension (follow-up change); new features on the frozen old-era path; a production authorization server.

## Decisions

1. **Own the layer, no SDK.** SDKs that speak 2026-07-28 are beta/preview; the Go spike shows the layer is small,
   and owning it gives one behavior across 8 ports. Alternative (wrap betas) rejected: 5 different bugs and
   release cadences, and elixir/clojure/rust would still be in-house.
2. **Fallback = the current code, frozen.** On `server/discover` failure the client uses today's path. It is
   already tested in every port; rewriting it buys nothing. It is deleted when the spec's removal window passes.
3. **OAuth flows through §10.** Pause mode is a `Request{kind:"authorization"}` carrying the URL, so hosts reuse
   the code they already wrote for URL elicitation. Automatic mode is the default for CLI/desktop.
4. **No DCR; CIMD or pre-registered.** Deprecated by the spec; GitHub supports neither DCR nor CIMD, Keycloak
   supports CIMD behind a flag, so a configured `clientId` is the universal fallback.
5. **Provider tolerance is built in, not configured:** `Accept: application/json` + form-encoded fallback
   (GitHub), one refresh for concurrent callers, `omitRefreshResource` per server (Entra).
6. **Retry only provable pre-dispatch failures** (401, 403 insufficient_scope). Answers spec issue #3394.
7. **`serverInfo` read from either the result or result `_meta`** (`io.modelcontextprotocol/serverInfo`, as
   GitHub sends it); our server sends it in `_meta`.
8. **Error precedence:** header/body mismatch (-32020) is checked before version support (-32022); a missing
   version header is -32020.

## Risks / Trade-offs

- [Spec still moving; SEP-2640 just final] → pin the version string in one place per port; conformance fixtures
  are the contract, so a spec change is one fixture edit + 8 ports.
- [8× implementation cost] → Go spike is the reference; port in order js, python, java, csharp, rust, elixir,
  clojure; shared conformance suite gates each.
- [JWT/JWKS crypto per port] → use the platform crypto where available; one small vetted dependency otherwise.
- [Loopback redirect with random port rejected by some AS] → pause mode with a host `redirectUri` is the escape.
- [Exact scope matching vs spec hierarchy wording] → exact match now, documented; revisit if a provider needs it.
- [Old MCP clients can no longer call our server] → called out as BREAKING in the CHANGELOG.

## Migration Plan

Ship in one release across all ports. `mcp.json` stays valid (new optional keys: `auth`, `description`,
`toolSearch`). Hosts that handled §10 `authorization` for URL elicitation get OAuth pauses for free.
Rollback = previous release; no persisted format changes besides the optional token store.

## Open Questions

- Default for `resource` on refresh: spec MUST (current) vs omit (Entra works out of the box).
- Cache `tools/list` per `ttlMs` for tool search, or always re-list.
- Concurrency over a single stdio pipe: allowed in the spike; confirm against reference servers.
