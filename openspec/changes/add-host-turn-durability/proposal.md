# Host turn durability: save interrupted turns, kill grace, edit diffs

## Why
go-agent-cli's real server integration (core/server/engine.go) hit three engine gaps:
1. `ask`/streaming-with-id save the transcript only on success. A cancelled or failed turn loses the
   user prompt and every finished tool call, so `--continue` cannot mark the interrupted turn.
2. The builtin kill grace is a fixed 2000 ms. go-agent-cli's cancel bar is 200 ms, and e2e cancel
   measures about 2.0 s.
3. `edit` / `write` / `apply_patch` return only text. A host cannot render a patch block.

## What Changes
- On error or cancellation, stateful runs (`ask`, streaming with an id) save the partial transcript:
  the user prompt plus every finished message. A tool call with no result gets a synthetic
  `isError` result `"interrupted"`, so the pairs stay valid. The error is still returned.
- Builtin option `killGraceMs` (default 2000): SIGTERM-to-SIGKILL wait on POSIX.
- `edit`, `write`, `apply_patch` results carry `metadata.files` and `metadata.diff`; the stream
  `tool_result` event carries the result's `metadata`.

Follow-ups, not in scope: a toolkit register lock, per-server MCP readiness, and a ctx on waitFor.
go-agent-cli has workarounds for all three.
