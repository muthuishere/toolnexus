# Host engine needs: Claude-shaped MCP command, file conversation store

## Why

go-agent-cli (a per-folder agent server built on the Go port) reads the MCP configs that Claude Code
writes and keeps one transcript file per session in the working folder. Two gaps block it, both
verified by probe on v0.21.0:

1. **`ParseMcpConfig` rejects Claude's `command` + `args` shape.** Claude writes
   `{"command":"node","args":["server.mjs"]}`; every port types `command` as an argv array, so the
   Go port fails the WHOLE config with `cannot unmarshal string into ... command of type []string`.
   Only the opencode argv-array form works.
2. **There is no durable conversation store.** `ConversationStore` is `get`/`save`, and only the
   in-memory store ships. A host that resumes sessions must write its own, and nothing can list or
   delete a conversation.

## What Changes

- **MCP config:** a local server's `command` MAY be a string or a non-empty string array; an
  optional `args` string array is appended. argv = `[command] ++ args` or `command ++ args`. No shell
  interpretation. Invalid shapes fail the parse with an error naming the server and field.
- **`FileConversationStore(root)`:** one JSON file per id under a caller-given root, atomic
  replacement on save, id validation (no traversal), plus `list()` and `delete(id)`. The in-memory
  store gains `list`/`delete` too. `ConversationStore` itself stays two methods (non-breaking).
- Ships with `add-mcp-progress-timeout` (idle MCP timeout, #63) in the same branch.

Not in scope: Windows `npx` → `npx.cmd` resolution (exec lookup stays per-port, unchanged); a
context-window field (tracked as a gap); config discovery/precedence (host's job).
