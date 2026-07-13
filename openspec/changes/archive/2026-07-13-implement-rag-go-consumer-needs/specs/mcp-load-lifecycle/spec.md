## ADDED Requirements

### Requirement: MCP load honors the caller's cancellation and deadline

The MCP load SHALL honor the caller's cancellation/deadline through connect, initialize, and list,
and the SSE-fallback start SHALL be bounded by the server `timeout` (which it currently lacks). A
context-aware entry point SHALL exist; the existing no-context entry point keeps its signature and
delegates with a background context. Parent-context cancellation/deadline aborts the whole load and
returns the context error; a per-server timeout **within** budget marks only that server `failed`
and the build continues (per-server failure isolation unchanged).

#### Scenario: Parent cancellation aborts the load promptly

- **WHEN** the caller's context is cancelled mid-load against a server that accepts but never responds
- **THEN** the load returns promptly (well under the default per-server timeout) with the context error, not after the full timeout

#### Scenario: SSE fallback start is bounded

- **WHEN** a server forces the SSE fallback and then hangs
- **THEN** the load completes within the server `timeout` with that server marked `failed`, instead of hanging forever

#### Scenario: No-context entry point is unchanged

- **WHEN** the load runs against the shared `examples/` config with no context supplied
- **THEN** behavior is identical to before this change

### Requirement: Per-server tool allowlist

A server config SHALL accept a `Tools` map (server tool name → bool) selecting which of that server's
tools are exposed, with semantics identical to the builtins and skills filters: nil/empty ⇒ all; ≥1
`true` ⇒ allowlist; only-`false` ⇒ drop-list; unknown names ignored and warned once. Keys are the
server's ORIGINAL (pre-sanitize/prefix) tool names. The filter is applied to the listed tool
definitions before conversion/prefixing.

#### Scenario: Allowlist exposes only named tools

- **WHEN** a server exposes tools `a`, `b`, `c` and its config sets `Tools` to `{a:true, b:true}`
- **THEN** the toolkit exposes only that server's `a` and `b` (prefixed), not `c`

#### Scenario: Empty and nil mean all tools

- **WHEN** a server's `Tools` is nil or an empty map
- **THEN** all of that server's tools are exposed (clearing the field never disables the server)

#### Scenario: Unknown allowlisted name is ignored

- **WHEN** `Tools` names a tool the server does not expose
- **THEN** the other named tools load, no error is raised, and the unmatched name is warned once

### Requirement: List-only MCP inventory

A list-only operation SHALL connect to every enabled server in a config, list its tool definitions,
and disconnect before returning — building no toolkit and leaving nothing running. It SHALL return,
per server, the full **unfiltered** tool definitions under their original names, plus a per-server
status (`connected` | `disabled` | `failed`). Failure isolation matches the normal load.

#### Scenario: Inventory returns original names and per-server status

- **WHEN** the inventory runs over a config with one reachable server (tools `a`, `b`) and one unreachable server
- **THEN** it returns the reachable server's `a` and `b` under their original names with a `connected` status and the unreachable server with a `failed` status, and no error for the whole call

#### Scenario: Inventory leaves nothing running

- **WHEN** the inventory returns
- **THEN** every connection it opened has been closed (no leaked child process or client)
