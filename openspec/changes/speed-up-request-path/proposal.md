# Speed up the per-request path and cold init

## Why

The 2026-11-08 quiet benchmark left us behind in Go, Java, Rust and C# (init), and slower than we
need to be in Python and JS. A no-framework floor (same spawn, same two LLM POSTs, same two MCP
calls) showed most of our per-request cost was **waiting**, not computing: new TCP connections per
LLM call, thread hand-offs on every stdio line, per-call OS proxy lookups, and SDK code compiled or
loaded only after the MCP child was ready.

## What changes

- **Default LLM transport keeps connections alive** (python, js). Python reuses one HTTP/1.1
  connection per worker thread and host, retrying once on a connection the server closed while idle.
  JS uses a `fetch`-shaped `node:http`/`node:https` transport with keep-alive agents on Node only.
  Both fall back to the previous transport when a proxy env var applies; JS also when the caller
  passes `fetch`, has replaced `globalThis.fetch`, or runs on Bun, Deno or a browser.
- **Proxy resolution cached per scheme and host** (python, csharp). The OS lookup ran on every POST.
- **No thread hop per MCP stdio line** (python: event-loop pipe I/O on POSIX asyncio; java: tool calls
  run on the caller thread under one shared watchdog; java/LlmClient default client runs callbacks inline).
- **Cold init overlaps the MCP child's startup** (java: spawn first, lazy HTTP clients, SDK prewarm;
  csharp: SDK warm-up against an in-memory server; js: JSON-schema validator built on first use).
- **java uses one JSON library.** `jackson-databind` 2.x is dropped; Jackson 3 (already required by the
  MCP SDK) is configured to produce byte-identical output.
- **rust**: our own stdio MCP client replaces rmcp for stdio servers; runs move onto a tokio worker.
- golang: no change (measured within ~8 µs of the floor). elixir, clojure: out of scope.

## Impact

- No SPEC.md byte-level contract moves: tool names, skill output, adapter JSON and MCP behaviour are
  unchanged (pinned by Java's new byte-parity test and the existing conformance jobs).
- User-visible: a system proxy changed while the process runs is not picked up (python, csharp);
  java no longer brings `jackson-databind` 2.x transitively.
