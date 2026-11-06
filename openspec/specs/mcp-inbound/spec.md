# mcp-inbound Specification

## Purpose
TBD - created by archiving change add-mcp-inbound. Update Purpose after archive.

## Requirements

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

### Requirement: Embeddable MCP handler with per-request authentication

`toolkit.mcpHandler(cfg)` SHALL return the port's native HTTP handler serving MCP over streamable HTTP
(protocol `2025-06-18`, also `2025-03-26`) without opening a listener. When `cfg.authenticate` is set it
SHALL run on every HTTP request before JSON-RPC parsing; an error SHALL produce HTTP 401 carrying a
`WWW-Authenticate` header from `cfg.wwwAuthenticate` when that returns a non-empty value. The returned
identity SHALL be opaque and reachable by every hook and tool through the call context.

#### Scenario: Missing credentials

- **WHEN** a request arrives without a token `authenticate` accepts
- **THEN** the response is HTTP 401 with the caller-supplied `WWW-Authenticate` header
- **AND** no JSON-RPC method is dispatched

#### Scenario: Identity reaches the tool

- **WHEN** an authenticated caller calls a tool
- **THEN** the tool reads the identity `authenticate` returned from its context

### Requirement: Per-request tool list with list_changed

When `cfg.listTools` is set, `tools/list` and the `tools/call` lookup SHALL both use its result for the
current request; tools need not be registered in the toolkit. A `tools/call` for a name not in that list
SHALL return JSON-RPC error `-32602` "tool not found: <name>" and SHALL NOT execute any tool. The server
SHALL advertise `tools.listChanged:true` and `notifyToolsChanged()` SHALL send
`notifications/tools/list_changed` on every open GET stream. `Tool.annotations` SHALL be sent verbatim;
absent, annotations SHALL be derived from `Tool.hints` when set, else omitted.

#### Scenario: Two callers see different tools

- **WHEN** `listTools` returns `[search, admin_reset]` for alice and `[search]` for bob
- **THEN** each caller's `tools/list` shows exactly its own list
- **AND** bob calling `admin_reset` gets `-32602` and `admin_reset` never runs

#### Scenario: Tools changed

- **WHEN** a client holds a GET stream and the host calls `notifyToolsChanged()`
- **THEN** the client receives `notifications/tools/list_changed`

### Requirement: BeforeCall gate with elicitation

`cfg.beforeCall(ctx, tool, rawArgs)` SHALL decide each `tools/call`: `allow` runs the tool; `deny` returns
the decision's `result` when set, else an isError result with its message; `confirm` SHALL send
`elicitation/create` on the same POST's event stream when the session's client declared
`capabilities.elicitation`, running the tool only on `accept`, and SHALL otherwise return the decision's
`result` when set, else an isError result "confirmation required: <message>".

#### Scenario: Client can elicit

- **WHEN** a destructive tool is gated with `confirm` and the client declared elicitation and accepts
- **THEN** the tool runs and its result is returned
- **AND** a client that declines gets an isError "declined" and the tool does not run

#### Scenario: Client cannot elicit

- **WHEN** the same call comes from a client without the elicitation capability
- **THEN** the caller receives the host's shaped `result` (or "confirmation required: …") and the tool does not run

### Requirement: Metering, server info, resources, prompts and sessions

`cfg.onCall` SHALL fire once per `tools/call` with name, source, duration, isError, the execute error,
result size in bytes, the gate decision, identity and session id. `cfg.serverInfo(ctx)` SHALL be resolved
at `initialize` with the identity and the HTTP request on the context. Resources and prompts hooks SHALL be
served when set; when `listResources`/`listPrompts` is absent the capability SHALL NOT be advertised.
`cfg.sessionIds` SHALL replace the default in-memory session ids (generate at initialize, validate per
request with 404 on false, terminate on DELETE); `cfg.stateless` SHALL issue and check no session id.

#### Scenario: Branded initialize

- **WHEN** `serverInfo` derives a name from the Host header and identity
- **THEN** `initialize` returns that name, version and instructions

#### Scenario: Host session ids

- **WHEN** a host supplies `sessionIds`
- **THEN** initialize uses its `generate`, every later request is checked with its `validate`, and an unknown id gets HTTP 404

### Requirement: Structured tool results and output schema

`ToolResult` SHALL accept an optional `structured` value and `Tool` an optional `outputSchema`. Both MCP
serve surfaces SHALL emit `structured` as `structuredContent` beside the text block (the JSON text of
`structured` when `output` is empty) and `outputSchema` in `tools/list`. When both are absent the wire
output SHALL be byte-identical to before.

#### Scenario: Structured result

- **WHEN** a tool with an `outputSchema` returns `structured:{count:3}` and no output text
- **THEN** `tools/list` carries that `outputSchema`
- **AND** the call result carries `structuredContent:{count:3}` and a text block `{"count":3}`

#### Scenario: Absent stays identical

- **WHEN** a tool sets neither field
- **THEN** no `structuredContent`, `_meta` or `outputSchema` key appears on the wire

### Requirement: _meta passthrough

A request's `params._meta` SHALL be readable by every hook and tool through the call context, and a
`ToolResult.meta` SHALL leave as the result's `_meta`.

#### Scenario: Request and result metadata

- **WHEN** a `tools/call` carries `_meta:{clientCaps:{…}}` and the tool returns `meta:{usage:{units:2}}`
- **THEN** `beforeCall` and the tool both read `clientCaps` from the context
- **AND** the result carries `_meta:{usage:{units:2}}`

### Requirement: Honest per-handler protocol versions

`cfg.protocolVersions` SHALL set the versions a handler accepts, newest first, default `["2025-06-18"]`,
filtered to the versions the port implements (`2025-06-18`, `2025-11-25`). For a request carrying
`MCP-Protocol-Version: 2025-11-25`, invalid tool arguments SHALL be an `isError` tool result rather than
`-32602`. `2025-03-26` and `2026-07-28` SHALL NOT be accepted.

#### Scenario: Opt-in 2025-11-25

- **WHEN** a handler lists `["2026-07-28","2025-11-25","2025-06-18"]` and a client initializes with `2026-07-28`
- **THEN** the handler answers `2025-11-25`
- **AND** a `MCP-Protocol-Version: 2025-03-26` header gets HTTP 400

#### Scenario: Default handler

- **WHEN** a handler has no `protocolVersions` and a client asks for `2025-11-25`
- **THEN** `initialize` answers `2025-06-18`

### Requirement: Stateless session id and server title

In stateless mode a non-empty `statelessSessionId` SHALL be returned as `Mcp-Session-Id` on `initialize`
and never validated. A non-empty `title` (static or from `serverInfo`) SHALL be sent as `serverInfo.title`;
an empty one SHALL be omitted.

#### Scenario: Fixed stateless id

- **WHEN** a stateless handler sets `statelessSessionId:"stateless"` and `title:"Records API"`
- **THEN** `initialize` returns header `Mcp-Session-Id: stateless` and `serverInfo.title:"Records API"`
- **AND** later requests with any or no session id are served

### Requirement: Opt-in MCP 2026-07-28 wire on the embeddable handler

`mcpHandler` SHALL serve the stateless MCP 2026-07-28 wire of SPEC §7C.2 when `protocolVersions` lists
`"2026-07-28"`, and SHALL be byte-identical to §7C.1 when it does not. It SHALL validate the
`MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers before authentication, answering `-32020` for a
mismatch and `-32022` for an unsupported version, and every result SHALL carry `resultType` and
`_meta["io.modelcontextprotocol/serverInfo"]`.

#### Scenario: Discover then list

- **WHEN** a 2026 client sends `server/discover` then `tools/list` to a handler configured with `["2026-07-28"]`
- **THEN** discover lists `supportedVersions:["2026-07-28"]` and the tools come back sorted by name with `ttlMs`

#### Scenario: Not enabled

- **WHEN** the handler uses the default versions and a 2026 client sends `server/discover`
- **THEN** the response is HTTP 400, which the client treats as its fallback trigger

### Requirement: MRTR for confirm and tool suspension

On the 2026 wire a `confirm` decision for a client that declares elicitation SHALL return `input_required` with
input request `confirm` and a `requestState`; the retry with an accepting response SHALL run the tool once. A tool
returning a §10 pending request SHALL return `input_required` keyed by the request id, and the retry's response
SHALL reach the tool as its `Answer`. `beforeCall` SHALL run on every round.

#### Scenario: Confirm accepted

- **WHEN** `confirm_run` is called, answered `accept` with `confirm:true` and the echoed `requestState`
- **THEN** the second response is the tool's result and the tool executed exactly once

#### Scenario: Forged confirmed state

- **WHEN** a request for a denied tool carries a `requestState` claiming `confirmed`
- **THEN** the tool is still denied

### Requirement: Skills extension server

When `listSkills` is set the handler SHALL advertise `io.modelcontextprotocol/skills` and answer `skills/list`,
`skills/get` and `resources/read` of `skill://` URIs with sha256 digests, rejecting URIs that escape the skill with
`-32602`.

#### Scenario: Encoded traversal

- **WHEN** `resources/read` names `skill://hello-world/%2e%2e/secret`
- **THEN** the handler answers `-32602` and reads nothing

### Requirement: Server authorization helper and dev authorization server

`MCPServerAuth` SHALL provide an `authenticate` hook that answers a missing token with 401, an invalid token with
401 `error="invalid_token"` and a missing scope with 403 `error="insufficient_scope"` naming the full needed set,
plus an RFC 9728 metadata handler. Each port's test suite SHALL use an in-process development authorization server
supporting pre-registered and Client ID Metadata Document clients with PKCE S256; it SHALL live in test/interop code
and SHALL NOT be exported as library API.

#### Scenario: Dev authorization server is not library surface

- **WHEN** a consumer imports the published toolnexus package of any port
- **THEN** no dev authorization server symbol (e.g. `StartMCPDevAuthServer`, `start_mcp_dev_auth_server`) is available

#### Scenario: Step-up

- **WHEN** a token without `mcp:admin` calls a tool that needs it on the 2026 wire
- **THEN** the handler answers 403 with `scope="mcp mcp:admin", error="insufficient_scope"` and the tool does not run

#### Scenario: Client interop

- **WHEN** the toolnexus 2026 client loads a handler protected by `MCPServerAuth` and the dev authorization server
- **THEN** it discovers the metadata, logs in with a pre-registered client id or a CIMD URL, and calls a tool
