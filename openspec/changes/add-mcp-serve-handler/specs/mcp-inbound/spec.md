## ADDED Requirements

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
