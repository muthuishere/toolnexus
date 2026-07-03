## ADDED Requirements

### Requirement: Serve a toolkit as an MCP server

`toolkit.serve(addr, { mcp })` SHALL expose the toolkit as a streamable-HTTP MCP server when the `mcp`
profile (typed `MCPServeConfig`) is present, built on the port's existing MCP SDK in server mode. The
server SHALL answer `initialize` with `serverInfo:{name, version}` derived from the `mcp` profile
(defaults `name:"toolnexus"`, `version:"0.1.0"`) and SHALL advertise `capabilities.tools`. It SHALL
mount at an `/mcp` endpoint on the same server as any A2A routes and return a handle with a
`stop()`/`close()` method. When the `mcp` profile is absent, no MCP surface is mounted. (A stdio
transport is intentionally out of scope for this change.)

#### Scenario: MCP profile is opt-in

- **WHEN** `serve(addr, {})` is called without an `mcp` profile
- **THEN** no `/mcp` endpoint and no MCP server are mounted
- **AND** an MCP client connecting to `/mcp` does not succeed

#### Scenario: Server advertises its identity

- **WHEN** an MCP client `initialize`s against a served toolkit whose `mcp` profile sets
  `name:"gateway"` and `version:"2.0.0"`
- **THEN** the response `serverInfo` reports `name:"gateway"` and `version:"2.0.0"`
- **AND** the server advertises tool capabilities

### Requirement: tools/list advertises the toolkit's unified tools

On `tools/list`, the server SHALL return one entry per exposed toolkit tool, across **all** sources
(`mcp` · `skill` · `native` · `http` · `builtin` · `a2a`). Each entry's `name` SHALL be the toolkit
`Tool.name` used **verbatim** (already sanitized at registration — not re-sanitized), its
`description` SHALL be `Tool.description`, and its `inputSchema` SHALL be `Tool.parameters` (a JSON
Schema object). When `mcp.tools` is set to a list of tool names, the advertised list SHALL be filtered
to exactly those names; unknown names in `mcp.tools` SHALL be ignored (not an error). When `mcp.tools`
is omitted, all toolkit tools SHALL be advertised.

#### Scenario: All sources exposed, names verbatim

- **WHEN** a served toolkit holds a native tool `echo`, a builtin `read`, and the `skill` tool
- **THEN** `tools/list` includes `echo`, `read`, and `skill` with their exact toolkit names
- **AND** each entry's `inputSchema` equals that tool's `parameters` JSON Schema

#### Scenario: mcp.tools narrows the surface

- **WHEN** the `mcp` profile sets `tools: ["echo"]`
- **THEN** `tools/list` advertises only `echo`
- **AND** tools not in the list are not advertised

### Requirement: tools/call dispatches to Tool.execute

On `tools/call` with `{ name, arguments }`, the server SHALL invoke the named toolkit tool's
`execute(arguments, ctx)` and map the returned `ToolResult` to an MCP `CallToolResult`: the result
`output` SHALL become a single `text` content part and `isError` SHALL propagate to the
`CallToolResult` `isError`. There SHALL be no client loop, no Task, and no TaskStore — the call is a
single synchronous tool invocation honoring the `Context` cancel/timeout. An `execute` error SHALL be
returned as `isError:true` with the error text and SHALL NOT crash the server. A call naming an unknown
tool SHALL return the SDK's standard unknown-tool error. A `serve`/`serveStdio` `onCall` callback, when
provided, SHALL surface each inbound call (tool name, source, ms, isError).

#### Scenario: Successful call maps ToolResult to content

- **WHEN** an MCP client calls `echo` with `{ text: "hi" }` on a served toolkit
- **THEN** the toolkit's `echo.execute` runs and the response carries its `output` as a `text` content part
- **AND** the response `isError` is `false`

#### Scenario: Tool error becomes an isError result, server survives

- **WHEN** a called tool's `execute` throws
- **THEN** the `tools/call` response has `isError:true` carrying the error text
- **AND** the server keeps serving subsequent `tools/list` and `tools/call` requests
