# ADR 0040 — The MCP 2026-07-28 server lives on the embeddable handler, opt-in

- Status: **PROPOSED** (2026-11-05)
- Scope: all eight ports, Go reference
- Change: `openspec/changes/add-mcp-2026-server-handler`
- Related: ADR 0039 (embeddable handler, PR #140), change `upgrade-mcp-2026-07-28` (client, PR #142)

## Context

#142 shipped the 2026-07-28 client and wrote the server contract against `serve(addr, {mcp})` with a one-line
`auth` block, deferring the build to "a follow-up on #140's MCPHandler". #140 meanwhile made the handler the place
hosts mount MCP, with every policy as a plain hook, and refused 2026-07-28 until its wire existed.

## Decision

1. The 2026 wire is a **version the handler accepts**, opt-in through `protocolVersions`. No new handler type, no
   default change, no breaking change; both wires can share one mount (routing pinned in SPEC §7C.2).
2. The 2026 wire keeps every §7C.1 hook. MRTR replaces elicitation: confirm and §10 tool suspensions become
   `input_required` rounds; `requestState` is unsigned (`beforeCall` re-runs every round).
3. Server auth is a **helper that produces the hook** (`MCPServerAuth.authenticate` + `metadata()`), not handler
   configuration. The handler only learns `MCPAuthError {status, challenge}`. Tenancy, token issuance and storage
   stay the host's.
4. Skills come from a `listSkills` hook (data), with `loadMcpSkills(dir)` for the common case.
5. Every port ships a dev authorization server over plain HTTP (no process-wide trust of a dev certificate).
6. `serve(addr, {mcp})` is **not** switched to 2026 here. #142 marks that switch BREAKING; it needs an owner call.

## Consequences

- A toolnexus client and a toolnexus server now meet on 2026-07-28 (proven by `interop/mcp2026/run.sh`).
- Per-tool scopes need the 2026 headers; on the legacy wire only `"*"` scopes are enforced.
- SPEC §2A.2 / §7C (the `serve`-shaped target from #142) and §7C.2 describe the same wire; when #142 and this
  change both merge, §7C should point `serve` at §7C.2 once the owner decides the `serve` switch.
