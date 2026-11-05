## Why

PR #142 (`upgrade-mcp-2026-07-28`) shipped the MCP 2026-07-28 **client** in all eight ports and deferred the
server half (its tasks 2.4, 2.5b, 3S, 5.2) to "a follow-up on PR #140's MCPHandler". Until it lands, a toolnexus
client talking to a toolnexus server always takes the frozen pre-2026 fallback, and nothing in the repo serves
the 2026 wire, its OAuth metadata, or SEP-2640 skills.

## What Changes

- `mcpHandler` accepts `"2026-07-28"` in `protocolVersions` (**opt-in**, never a default): the stateless wire —
  `server/discover`, header validation (`-32020` / `-32022`), `resultType` + `_meta` serverInfo on every result,
  sorted `tools/list` with cache hints, MRTR (`input_required` + `requestState`) for the confirm gate and for §10
  tool suspensions. Legacy and 2026 wires can share one mount.
- New `listSkills(ctx)` hook + `loadMcpSkills(dir)` helper: SEP-2640 `skills/list`, `skills/get`,
  `resources/read` of `skill://` URIs with URI containment.
- `MCPServerAuth`: the `authenticate` hook (bearer, default RS256/JWKS verifier or a `verify` hook, per-tool
  scopes, exact 401/403 challenges) and the RFC 9728 metadata handler. `MCPAuthError` lets any `authenticate`
  hook choose 401/403 and the challenge.
- `startMcpDevAuthServer`: in-process dev authorization server (pre-registered + CIMD clients, PKCE, refresh) —
  development and tests only. No DCR.
- Shared conformance (`conformance/mcp_handler/cases.json`, `mcp2026_*`) and an interop runner
  (`interop/mcp2026/run.sh`): PR #142's Go client against this Go server.
- Not changed: `serve(addr, {mcp})` stays on its pre-2026 profile (§7C.F); hooks stay plain (tenancy, storage and
  token issuance remain the host's).

## Capabilities

### Modified Capabilities
- `mcp-inbound`: the embeddable handler gains the opt-in 2026-07-28 wire, skills extension and server auth helpers.

## Impact

- SPEC.md §7C.1 (versions bullet) and new §7C.2; ADR 0040.
- All eight ports: handler, auth helper, dev AS, conformance harness, tests, README section.
- No new runtime dependency is intended (platform crypto for RS256).
