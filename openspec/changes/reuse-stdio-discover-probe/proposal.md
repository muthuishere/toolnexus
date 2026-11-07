# Reuse the stdio discover probe child for the frozen path

## Why

SPEC §2.2a today: over stdio, when `server/discover` gets any error from an old (pre-2026) server,
the client **terminates the probe child and spawns a second process** for the frozen path (§2.F).
Every pre-2026 stdio server — today the majority — therefore pays two process starts per load.
Measured (perf-notes-losses.md / perf-notes-regression.md): Java JFR shows two spawns 152 ms apart,
C# 35–55 ms, Clojure ~20 ms. A JVM/Node/Python server spawn is the single largest fixed cost of
`loadMcp` against an old server.

An old server that answers `server/discover` with a JSON-RPC error has not been harmed: it is a
live process waiting for `initialize`. Killing it buys nothing.

## What changes

- **stdio, fast JSON-RPC error reply to `server/discover`** ⇒ the **same child** continues into
  `initialize` on the frozen path (§2.F). Exactly one process start.
- **stdio, discover silence** (no reply within `min(timeout, 5 s)`) ⇒ unchanged: the probe child is
  terminated and the frozen path spawns its own (a server that ignored one request may be wedged).
- **stdio, child dies** before replying ⇒ unchanged: load failure (§2.2a).
- **stdio, a 2xx-shaped result without `2026-07-28`** in `supportedVersions` ⇒ also reuses the child
  (it replied, it is alive, it speaks JSON-RPC).
- Discover-first is unchanged; the 2026 path is unchanged; HTTP is unchanged (no process).
- Where a port hands the frozen path to an MCP SDK, the SDK's stdio transport adopts the already-open
  pipes of the probe child (or a minimal adapter transport over them).

## Impact

- `SPEC.md §2.2a` amended.
- All eight ports: stdio probe + frozen-path handoff.
- New shared fixture `examples/mcp-2026/legacy-stdio/server.mjs` (counts process starts) and a test in
  every port: `-32601` ⇒ exactly one start; silence ⇒ two.
- No API change, no option change, no tool output change.
