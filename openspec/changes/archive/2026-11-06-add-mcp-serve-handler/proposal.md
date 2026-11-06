## Why

Issue #139: a host that wants to serve MCP (e.g. a hosted, multi-customer MCP product) cannot build
on `serve(addr, {mcp})` (§7C): it owns the listener and exposes one static tool list with no
identity, no gate and no way to change the list. Owner ruling (2026-11-04): toolnexus ships a
**plain** MCP server with generic hooks; tenancy and storage are the host's (ADR 0039).

## What Changes

- New `toolkit.mcpHandler(cfg)` — an embeddable streamable-HTTP MCP handler (protocol 2025-06-18,
  also 2025-03-26) with hooks: `authenticate` (+ 401 / `wwwAuthenticate`), `identityFrom(ctx)`,
  `listTools(ctx)` (tools/call outside it ⇒ not found, never executed), `notifyToolsChanged()`,
  `beforeCall` allow/deny/confirm (elicitation when the client supports it; else the host's shaped
  `result` or a plain isError), `onCall` metering event, `serverInfo(ctx)`, resources and prompts
  hooks, `sessionIds` (generate/validate/terminate) and `stateless`.
- Core types (SPEC §1): optional `ToolResult.structured` / `meta` and `Tool.outputSchema`, emitted by
  both MCP serve surfaces as `structuredContent` / `_meta` / `outputSchema`; absent ⇒ byte-identical.
- Handler: request `_meta` on ctx, per-handler `protocolVersions` (2025-06-18 default, 2025-11-25 opt-in),
  `statelessSessionId`, `serverInfo.title`.
- New optional `Tool.annotations` (MCP tool annotations, passed through verbatim).
- SPEC §7C.1. `serve(addr, {mcp})` is unchanged.
- Go first; the other seven ports are tracked as unchecked tasks.

## Impact

- golang: `mcphandler.go`, `types.go` (`Tool.Annotations`), `mcpserve.go` (shared result mapper, no behaviour change), example `examples/mcphandler`.
- No new dependency (Go builds the transport over plain JSON-RPC; mcp-go's client is used in tests).
