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

