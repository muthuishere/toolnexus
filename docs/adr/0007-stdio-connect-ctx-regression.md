# ADR 0007 — v0.9.0 stdio MCP connect kills the subprocess on return (regression)

**Status:** RESOLVED in golang/v0.9.3 (2026-07-14) — stdio `Start` now uses
`context.Background()` as proposed below; consumer rag_go removed its skip gate and
the stdio integration test runs unconditionally on v0.9.3.
**Severity:** High for stdio (local) MCP servers; **remote http/sse unaffected**
**Introduced by:** ADR-0001 Gap 3 (ctx-aware `LoadMcp`) — the fix that threaded a
deadline through the connect path.

## Context

rag_go (the ADR-0001 consumer) adopted v0.9.0 and its two **stdio** MCP
integration tests + the stdio `ListMcpTools` path began failing with
`transport error: transport closed` at `listTools`, immediately after a
*successful* connect. v0.6.0 works; v0.8.0/v0.9.0 fail; same `mcp-go v0.48.0`.
Reproduced minimally (spawn a local stdio MCP echo server → `CreateToolkit` →
list tools). The **remote** http/sse path is unaffected — verified live against
the production remote HRMS MCP server (glm-5.2 tool call → result → grounded
answer), so all production MCP (Cobra/HRMS, all remote) is safe.

## Root cause

`connectServer` (`golang/mcp.go:419-424`) for the **stdio** branch:

```go
stdioTransport := transport.NewStdioWithOptions(cfg.Command[0], env, cfg.Command[1:], opts...)
startCtx, cancel := context.WithTimeout(ctx, timeout)
defer cancel()                              // <-- fires when connectServer returns
if err := stdioTransport.Start(startCtx); err != nil { ... }
client := mcpclient.NewClient(stdioTransport, ...)
if err := initClient(ctx, client, timeout); err != nil { ... }
return client, nil                          // <-- defer cancel() runs here
```

For stdio, `transport.Start(startCtx)` **spawns the child process and ties its
lifetime to `startCtx`**. When `connectServer` returns, `defer cancel()`
cancels `startCtx`, which terminates the subprocess. Every subsequent call on
the returned client (`listTools`, `callTool`) then hits a dead transport →
`transport closed`.

The http/sse branches (`mcp.go:453`, `:476`) use the same
`WithTimeout(ctx, timeout)` + cancel, but for http/sse `Start()` only performs
the connection handshake — the persistent connection is not bound to the start
context — so they survive the cancel. Only stdio couples the process to the
start ctx.

Note the irony: the ADR-0001 Gap 3 change (make the load path ctx-aware, whose
real motivation was the unbounded **SSE** `Start`) is correct for http/sse but
over-applied to stdio, where binding the process to a short-lived timeout ctx
is a lifetime bug.

## Decision (proposed fix)

Decouple the stdio subprocess lifetime from the connect-time timeout. Match the
v0.6.0 behavior (process lives until `client.Close()`), while keeping the Gap 3
SSE-hang fix intact:

```go
// stdio: the process must outlive connectServer — do NOT bind it to a ctx
// cancelled on return. Start on a background context; the client owns the
// process and closes it via client.Close().
if err := stdioTransport.Start(context.Background()); err != nil { ... }
```

Keep a startup deadline, if desired, without owning the process lifetime — e.g.
enforce the timeout around `initClient`/`listTools` (which already take their
own `WithTimeout`), not around the process-spawning `Start`. The http/sse
branches stay as-is (their timeout is correct and is the actual Gap 3 fix).

## Consequences

- Restores stdio MCP servers (local `npx …`/binary tools) on the Go port.
- No change to remote http/sse behavior; the SSE-hang protection from Gap 3 is
  preserved.
- **Cross-language parity:** verify the same lifetime coupling did not land in
  the js/python/java/csharp ports when Gap 3 was applied there. Only Go is
  confirmed affected; the others are **unverified**.
- rag_go currently gates 3 stdio tests behind `skipIfStdioMCPRegressed`
  (`RAG_GO_TOOLNEXUS_STDIO_FIXED=1` re-enables them); those flip back to
  always-on once a fixed tag ships.

## Acceptance test

A regression test in `golang/`: spawn the repo's own stdio echo MCP server via
`CreateToolkit`, then `Execute` a tool — must succeed (today: `transport
closed`). Assert the child process is still alive after `CreateToolkit`
returns.
