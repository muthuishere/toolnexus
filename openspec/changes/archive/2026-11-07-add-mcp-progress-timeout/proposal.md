# Progress-aware MCP call timeout

## Why

A legitimately long-running MCP tool — a build, a crawl, a large query — is killed at the per-call
`timeout` (default 30000 ms) in **all seven ports**, even when the server is dutifully streaming
`notifications/progress` the whole time. The per-call timeout is currently a *total-duration*
deadline; for a tool that reports liveness it should be an *idle* timeout.

`js/` looks like it already handles this and does not. `js/src/mcp.ts:203` passes
`resetTimeoutOnProgress: true` but omits the `onprogress` handler, and the MCP TypeScript SDK
attaches a `progressToken` to the request **only** when that hook is present. Without a token the
server is never permitted to send progress, so nothing ever resets the timer — the flag has never
done anything. A spike against a real MCP stdio server (6 s tool, progress every 1 s, client
timeout 2 s) confirms it:

| client configuration | elapsed | outcome | server saw |
|---|---|---|---|
| toolnexus today (`reset:true`, no `onprogress`) | 2002 ms | FAIL `-32001 Request timed out` | `progressTokenReceived: false`, 0 notifications |
| with the `onprogress` hook added | 6010 ms | OK | `progressTokenReceived: true`, 6 notifications |
| hard deadline, no reset (the other six ports) | 2002 ms | FAIL `-32001 Request timed out` | `progressTokenReceived: false`, 0 notifications |

The other six never attempt progress at all: `python/src/toolnexus/mcp_source.py:243`
(`read_timeout_seconds`), `golang/mcp.go:302` (`context.WithTimeout`),
`java/.../McpSource.java:646` (bare `client.callTool(req)`), `csharp/src/Toolnexus/McpSource.cs`,
`elixir/lib/toolnexus/mcp/connection.ex:403` (`put_pending` single deadline), and
`clojure/src/toolnexus/mcp.cljc:326` (`await-response!` single deadline). A repo-wide grep for
`resetTimeoutOnProgress|onprogress|on_progress` matches exactly one line — the dead one.

This is the drift class the shared-fixture rule exists to catch, and today it is invisible because
no fixture exercises a slow server.

## What Changes

- Every `tools/call` SHALL request a progress token, so servers are permitted to report progress.
- The per-call `timeout` becomes an **idle** timeout: each `notifications/progress` for the in-flight
  call resets the remaining budget. A call that never reports progress behaves exactly as today.
- A shared fixture — a slow MCP server that emits progress — is added under `examples/`, so the
  behavior is verified identically in all seven ports rather than per-port.
- `SPEC.md §2` pins the observable contract, since the underlying mechanism differs per SDK
  (js `onprogress` hook, python `progress_callback`, go/java/csharp SDK progress handlers,
  elixir + clojure in-house timer reset).
- Not a breaking change: no signature moves, and a non-reporting server's behavior is byte-identical.
  It is user-visible (a call that used to fail now succeeds), so it earns a `CHANGELOG.md` entry.

## Capabilities

### New Capabilities

None. This tightens behavior already owned by an existing capability.

### Modified Capabilities

- `mcp-load-lifecycle`: gains a new requirement covering the per-call idle timeout — a tool call
  bounded by `timeout` **since the last progress notification** rather than since the call started,
  with a progress token requested on every call. The capability's existing requirements (load-phase
  cancellation/deadline, allowlist, inventory) are untouched; this change adds a requirement rather
  than modifying one, since the existing deadline requirement governs connect/initialize/list, not
  `tools/call`.

## Impact

- **Code**: the MCP tool-execute path in all seven ports — `js/src/mcp.ts`,
  `python/src/toolnexus/mcp_source.py`, `golang/mcp.go`,
  `java/src/main/java/io/github/muthuishere/toolnexus/McpSource.java`,
  `csharp/src/Toolnexus/McpSource.cs`, `elixir/lib/toolnexus/mcp/connection.ex`,
  `clojure/src/toolnexus/mcp.cljc`.
- **Contract**: `SPEC.md §2` (Behaviour), which currently describes `execute(args)` with no
  progress or idle-timeout semantics.
- **Fixtures**: a new shared slow/progress MCP server under `examples/`, plus a per-port test.
- **Dependencies**: none added. Every SDK in use already exposes a progress mechanism; the two
  in-house clients (elixir, clojure) own their timers outright.
- **Risk**: a server that streams progress forever now keeps a call alive indefinitely. Mitigated by
  the design's decision on an optional absolute cap (see `design.md`).
