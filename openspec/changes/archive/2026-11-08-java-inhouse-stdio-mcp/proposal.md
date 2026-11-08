# Java: in-house stdio MCP client for pre-2026 servers

## Why

After `speed-up-request-path`, the Java port still sent every frozen-path (pre-2026) stdio request
through the official MCP Java SDK session. Each request built a Reactor `Mono` chain, hopped schedulers
on the way out and back, and built a JSON tree for every line before converting it to the result type.
LangChain4j's MCP client and our own Rust port (`rust/src/mcp/stdio.rs`) use a cheaper shape: write the
request line directly, run one reader thread, and keep a pending map keyed by JSON-RPC id.

## What changes

- **java only.** An in-house `StdioMcpClient` replaces the SDK's `McpSyncClient` for local stdio servers
  that answer `server/discover` as an old server (§2.2a). It also drives the child respawned after a
  silent discover. The caller writes its request straight to stdin and parks on its own slot. One
  platform reader thread scans stdout bytes for newlines and reads only the envelope's `id`/`method`. It
  hands a response's raw bytes to the caller, which parses them straight into the SDK's `McpSchema` result
  record, so content mapping is unchanged.
- Server→client requests are answered from their own virtual thread, never from the reader. `ping` gets
  `{}`. `elicitation/create` goes through the §10 bridge when a `waitFor` exists. `roots/list` and any
  other method get `-32601`, with the SDK's texts.
- `initialize` offers `2025-11-25` and accepts `2024-11-05` to `2025-11-25`, as the SDK did. Servers that
  speak 2026-07-28 never reach this client: after `server/discover` they stay on the in-house `Mcp2026` path.
- Remote HTTP/SSE servers and the serve side keep the official SDK (the same split as Rust).
- `ProcessStdioTransport`, the SDK transport adapter for the probe hand-off, is removed.

## Impact

- No SPEC.md contract moves. The existing suites still pass unchanged: idle timeout, cancel, probe reuse,
  elicitation and content mapping. The new `StdioMcpClientTest` covers notification floods, concurrent
  calls with progress, server requests during a call (no deadlock), child exit and malformed lines.
- One observable difference: a child respawned after a silent discover now inherits stderr, as the probe
  child already did. Before, the SDK logged that stderr through SLF4J.
