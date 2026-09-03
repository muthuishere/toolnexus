## MODIFIED Requirements

### Requirement: tools/call dispatches to Tool.execute

On `tools/call` with `{ name, arguments }`, the server SHALL invoke the named toolkit tool's
`execute(arguments, ctx)` and map the returned `ToolResult` to an MCP `CallToolResult`: the result
`output` SHALL become a `text` content part and `isError` SHALL propagate to the
`CallToolResult` `isError`. When the `ToolResult` carries `parts`, each non-text part SHALL be
emitted as the corresponding MCP content block — `image` as image content, `audio` as audio
content, `file` as an embedded resource — appended after the text part, in order. There SHALL be
no client loop, no Task, and no TaskStore — the call is a
single synchronous tool invocation honoring the `Context` cancel/timeout. An `execute` error SHALL be
returned as `isError:true` with the error text and SHALL NOT crash the server. A call naming an unknown
tool SHALL return the SDK's standard unknown-tool error. A `serve`/`serveStdio` `onCall` callback, when
provided, SHALL surface each inbound call (tool name, source, ms, isError).

#### Scenario: Successful call maps ToolResult to content

- **WHEN** an MCP client calls `echo` with `{ text: "hi" }` on a served toolkit
- **THEN** the toolkit's `echo.execute` runs and the response carries its `output` as a `text` content part
- **AND** the response `isError` is `false`

#### Scenario: A tool's image part becomes an MCP image block

- **WHEN** a served tool returns `output` plus one `image` part
- **THEN** the `CallToolResult` content holds the text part first and the image content block
  after it

#### Scenario: Tool error becomes an isError result, server survives

- **WHEN** a called tool's `execute` throws
- **THEN** the `tools/call` response has `isError:true` carrying the error text
- **AND** the server keeps serving subsequent `tools/list` and `tools/call` requests
