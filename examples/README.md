# Shared fixtures

Every port runs against these. Outputs must match (SPEC.md §0).

| File | What |
|---|---|
| `mcp.json` | Example MCP config (one local server, one disabled remote). |
| `skills/` | Shared skill fixtures (`hello-world` and others). |
| `mcp-progress-server.mjs` | Dependency-free stdio MCP server for the idle-timeout contract (SPEC.md §2). Run with plain `node`. |

## `mcp-progress-server.mjs`

One tool, `slow`, with args `{totalMs, intervalMs, wrongToken?}`. It sleeps `totalMs`, sending
`notifications/progress` every `intervalMs` (0 = never) for the call's `progressToken`. With
`wrongToken: true` it reports under a token that matches nothing. It returns text JSON
`{"progressTokenReceived": bool, "notificationsSent": n}`.

Every port asserts the same things, with a per-server `timeout` of 300 ms:

1. `totalMs: 1200, intervalMs: 100` succeeds, and `progressTokenReceived` is `true`.
2. `totalMs: 1200, intervalMs: 0` times out.
3. `totalMs: 1200, intervalMs: 100, wrongToken: true` times out.
4. Caller cancellation ends the call promptly and is not reported as a timeout, in ports that
   have a per-call cancellation path. Elixir and clojure don't (SPEC.md §2 known gaps).

Tests skip when `node` is not on PATH.
