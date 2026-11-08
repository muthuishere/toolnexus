# mcp-load-lifecycle Specification

## Purpose
TBD - created by archiving change implement-rag-go-consumer-needs. Update Purpose after archive.
## Requirements
### Requirement: MCP load honors the caller's cancellation and deadline

The MCP load SHALL honor the caller's cancellation/deadline through connect, initialize, and list,
and the SSE-fallback start SHALL be bounded by the server `timeout` (which it currently lacks). A
context-aware entry point SHALL exist; the existing no-context entry point keeps its signature and
delegates with a background context. Parent-context cancellation/deadline aborts the whole load and
returns the context error; a per-server timeout **within** budget marks only that server `failed`
and the build continues (per-server failure isolation unchanged).

#### Scenario: Parent cancellation aborts the load promptly

- **WHEN** the caller's context is cancelled mid-load against a server that accepts but never responds
- **THEN** the load returns promptly (well under the default per-server timeout) with the context error, not after the full timeout

#### Scenario: SSE fallback start is bounded

- **WHEN** a server forces the SSE fallback and then hangs
- **THEN** the load completes within the server `timeout` with that server marked `failed`, instead of hanging forever

#### Scenario: No-context entry point is unchanged

- **WHEN** the load runs against the shared `examples/` config with no context supplied
- **THEN** behavior is identical to before this change

### Requirement: Per-server tool allowlist

A server config SHALL accept a `Tools` map (server tool name → bool) selecting which of that server's
tools are exposed, with semantics identical to the builtins and skills filters: nil/empty ⇒ all; ≥1
`true` ⇒ allowlist; only-`false` ⇒ drop-list; unknown names ignored and warned once. Keys are the
server's ORIGINAL (pre-sanitize/prefix) tool names. The filter is applied to the listed tool
definitions before conversion/prefixing.

#### Scenario: Allowlist exposes only named tools

- **WHEN** a server exposes tools `a`, `b`, `c` and its config sets `Tools` to `{a:true, b:true}`
- **THEN** the toolkit exposes only that server's `a` and `b` (prefixed), not `c`

#### Scenario: Empty and nil mean all tools

- **WHEN** a server's `Tools` is nil or an empty map
- **THEN** all of that server's tools are exposed (clearing the field never disables the server)

#### Scenario: Unknown allowlisted name is ignored

- **WHEN** `Tools` names a tool the server does not expose
- **THEN** the other named tools load, no error is raised, and the unmatched name is warned once

### Requirement: List-only MCP inventory

A list-only operation SHALL connect to every enabled server in a config, list its tool definitions,
and disconnect before returning — building no toolkit and leaving nothing running. It SHALL return,
per server, the full **unfiltered** tool definitions under their original names, plus a per-server
status (`connected` | `disabled` | `failed`). Failure isolation matches the normal load.

#### Scenario: Inventory returns original names and per-server status

- **WHEN** the inventory runs over a config with one reachable server (tools `a`, `b`) and one unreachable server
- **THEN** it returns the reachable server's `a` and `b` under their original names with a `connected` status and the unreachable server with a `failed` status, and no error for the whole call

#### Scenario: Inventory leaves nothing running

- **WHEN** the inventory returns
- **THEN** every connection it opened has been closed (no leaked child process or client)

### Requirement: MCP tool results preserve non-text content

When mapping an MCP `CallToolResult` to a `ToolResult`, a port SHALL map every non-text
`content[]` entry to a `ContentPart` on `ToolResult.parts` rather than discarding it: image
content becomes an `image` part; audio content becomes an `audio` part; a resource link becomes a
`file` part carrying its `uri` as `url`; an embedded resource carrying a blob becomes a `file`
part carrying that blob; an embedded resource carrying text SHALL be appended to `output`.
`output` SHALL continue to be the joined text parts, so a text-only MCP tool is byte-identical to
today. A non-text entry SHALL NEVER be dropped silently.

#### Scenario: A screenshot tool's image survives

- **WHEN** an MCP server returns a `CallToolResult` holding one text entry and one image entry
- **THEN** `ToolResult.output` is the text entry, and `ToolResult.parts` holds one `image` part
  carrying the server's base64 and mime type

#### Scenario: A resource link becomes a file part

- **WHEN** an MCP server returns a `resource_link` entry
- **THEN** `ToolResult.parts` holds a `file` part whose `url` is the link's `uri`

#### Scenario: A text-only MCP tool is unchanged

- **WHEN** an MCP server returns a `CallToolResult` holding only text entries
- **THEN** `ToolResult.output` is the joined text exactly as before and `parts` is absent

#### Scenario: An image-only result is not an empty string

- **WHEN** an MCP server returns a single image entry and no text
- **THEN** `ToolResult.parts` holds the image part and `output` names the returned part rather
  than being the empty string

### Requirement: Content parts are collected on every result branch

A port SHALL collect content parts regardless of which branch produces `output`. In particular a
result carrying `structuredContent` SHALL still map its `content[]` entries to parts, and a result
carrying `isError` SHALL still map them, so neither short-circuit reintroduces a silent drop.

#### Scenario: Structured content does not swallow an image

- **WHEN** an MCP server returns both `structuredContent` and an image entry
- **THEN** `output` is the serialised structured content as before
- **AND** `ToolResult.parts` still holds the image part

#### Scenario: An error result keeps its image

- **WHEN** an MCP server returns `isError:true` with a text entry and an image entry
- **THEN** `ToolResult.isError` is true, `output` is the formatted error text, and `parts` holds
  the image part

### Requirement: MCP tool calls are bounded by an idle timeout, not a total deadline

Every `tools/call` SHALL carry a client-generated progress token, permitting the server to emit
`notifications/progress` for that call. The per-server `timeout` SHALL bound the time since the
**last** progress notification for the in-flight call, rather than the total duration of the call.
Each matching notification SHALL reset the remaining budget to the full `timeout`.

A notification SHALL reset the budget only when its `progressToken` matches the in-flight call's
token; notifications carrying an unknown or non-matching token SHALL be ignored for timeout purposes.

A call for which the server emits no progress SHALL behave exactly as before this change: it fails
after `timeout` with the port's existing MCP timeout error, and the resulting `ToolResult` shape is
unchanged.

This requirement governs `tools/call` only. The load phases (connect, `initialize`, `tools/list`)
keep the per-phase budget defined by "MCP load honors the caller's cancellation and deadline" and are
unaffected.

Caller cancellation SHALL continue to abort an in-flight call promptly regardless of progress
activity, so a server that emits progress indefinitely remains interruptible via the caller's
cancellation/deadline and the client's run-level timeout.

The mechanism is deliberately unspecified — ports differ (SDK progress hook, SDK progress callback,
explicit token plus notification handler, or an in-house timer reset). Conformance is judged only on
the observable outcomes below, verified against the shared progress fixture.

#### Scenario: A reporting server outlives the timeout

- **WHEN** a tool call runs for longer than the per-server `timeout` while the server emits progress notifications at an interval shorter than `timeout`
- **THEN** the call completes successfully and returns the server's result, instead of failing with a timeout

#### Scenario: A silent server still times out

- **WHEN** a tool call runs against a server that emits no progress and does not respond
- **THEN** the call fails after `timeout` with the port's MCP timeout error, identically to before this change

#### Scenario: Progress stops mid-call

- **WHEN** a server emits progress for a period and then goes silent while the call is still in flight
- **THEN** the call fails after `timeout` measured from the last progress notification, not from the start of the call

#### Scenario: The server receives a progress token

- **WHEN** any `tools/call` is issued
- **THEN** the request carries a progress token, and a server that inspects the request observes it as present

#### Scenario: An unrelated token does not extend the call

- **WHEN** a progress notification arrives carrying a token that does not match the in-flight call
- **THEN** the in-flight call's remaining budget is unchanged and it still times out after `timeout` of silence

#### Scenario: Cancellation still wins over progress

- **WHEN** the caller cancels (via context/signal) a call against a server that is actively emitting progress
- **THEN** the call aborts promptly with the cancellation error rather than continuing until the server finishes

### Requirement: Stdio server requests never block the response reader

A port's frozen-path stdio MCP client SHALL answer server→client requests (ping, elicitation, roots and
unknown methods) without blocking the thread that reads the server's stdout. A server that sends many
requests while not reading stdin then cannot deadlock a tool call in flight.

#### Scenario: Many pings during a tool call

- **WHEN** a server sends thousands of `ping` requests during a `tools/call` and reads none of the replies until it has finished writing them
- **THEN** the client answers every ping and the tool call completes with the server's result

#### Scenario: The child exits with a call in flight

- **WHEN** the stdio server exits without answering a pending `tools/call`
- **THEN** that call and every later call on the server fail promptly with an error instead of waiting for the timeout

### Requirement: Abandoned stdio calls are cancelled on the server

When a frozen-path stdio `tools/call` is abandoned on an idle timeout or a caller cancel, the client SHALL
send `notifications/cancelled` with that request's `requestId` and a reason, and SHALL drop a later reply
to that id without error.

#### Scenario: Idle timeout

- **WHEN** a tool call sends nothing for longer than the idle budget and the server replies later
- **THEN** the server receives `notifications/cancelled` with reason `request timeout`, and the next call on that server succeeds

