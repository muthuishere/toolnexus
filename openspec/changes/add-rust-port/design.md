# Design: add-rust-port

## D1. Async runtime: tokio
The consumer is a tokio daemon. Every I/O API is `async`. Cancellation is a
`CancellationToken` on `ToolContext`, the analogue of Go's `ctx` and JS's `AbortSignal`.
Process-group kill uses `setpgid` plus `killpg` (SIGTERM, then SIGKILL after a grace period)
on POSIX and a Job Object on Windows, the same model as `golang/builtin_unix.go` and
`builtin_windows.go`.

## D2. MCP: official `rmcp`
The client uses `rmcp` (child-process and streamable-HTTP transports). Elixir wrote its own
MCP client only because no mature SDK existed; Rust has an official one. The port owns only
the toolnexus layer on top: naming, the allowlist, isolation and the result mapping in §0
item 4.

## D3. ACP: in-house
No SDK is assumed. ACP is newline-delimited JSON-RPC 2.0 over the agent's stdio. A reader
task routes responses by id and sends `session/update` to a stream (`tokio::sync::mpsc`).
`session/request_permission` goes to a host callback (`async fn(PermissionRequest) ->
PermissionOutcome`). Test fixtures are scripted fake agents; no real CLI runs in tests.

## D4. Shape
Idiomatic Rust, not a transliteration: builders for options, `Result<T, toolnexus::Error>`
(thiserror), and `serde_json::Value` for schemas and args. `ToolResult` keeps the wire keys
(`output`, `isError`, `metadata`) under serde renames, so byte-exact outputs match.

## D5. Byte-exactness
`skill` output and `skillsPrompt()` are produced by string assembly, never a pretty-printer.
They are asserted against the same expected bytes the other ports' tests use for
`examples/skills/`.
