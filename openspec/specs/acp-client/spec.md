# acp-client Specification

## Purpose
TBD - created by archiving change add-acp-client-seam. Update Purpose after archive.

## Requirements

### Requirement: Streaming generate on the in-process client
An in-process client SHALL accept a streaming generate `generateStream(request, emit)` and, when streaming, SHALL yield each emitted non-empty text event as a text delta at the moment it is emitted, pass every other emitted event straight to the consumer without executing it, and finish with `done`. A client with only a plain `generate` SHALL still refuse to stream.

#### Scenario: Deltas arrive as emitted
- **WHEN** a streaming generate emits `al`, waits until the consumer has seen it, then emits `pha` and returns `alpha`
- **THEN** the stream yields text deltas `al`, `pha` and `done` with text `alpha`, without deadlocking

#### Scenario: Agent events are reported, not executed
- **WHEN** the generate emits a `tool_call` event with `via:"acp"`
- **THEN** the consumer receives it and the loop does not execute any tool

### Requirement: ACP options are passed through untouched
`loadACP` SHALL send `mcpServers` as `session/new` `mcpServers` verbatim and in order (empty list when absent), `sessionMeta` as `session/new` `_meta` and `clientCapsMeta` as `initialize` `clientCapabilities._meta` only when set, and SHALL pass `model` through via `session/set_config_option`, falling back to `session/set_model`.

#### Scenario: Verbatim mcpServers
- **WHEN** `mcpServers` holds an http entry with an unknown field and a stdio entry
- **THEN** the agent receives both entries deep-equal and in the same order

#### Scenario: Nothing set
- **WHEN** none of the passthrough options is set
- **THEN** `mcpServers` is `[]` and neither `_meta` key is present

#### Scenario: Model fallback
- **WHEN** the agent rejects `session/set_config_option`
- **THEN** `session/set_model {modelId}` is sent with the same value

### Requirement: ACP client robustness
The ACP client SHALL declare `terminal:false`, fail a pending request with `agent exited before replying to <method>` as soon as the agent's stdout closes, and answer an unimplemented agent→client request with JSON-RPC error `-32601`.

#### Scenario: Agent exits mid-turn
- **WHEN** the agent exits without replying to `session/prompt`
- **THEN** the generate fails promptly with `agent exited before replying to session/prompt`

#### Scenario: Unknown agent request
- **WHEN** the agent sends a `fs/read_text_file` request during a turn
- **THEN** it receives error code `-32601` and the turn completes

### Requirement: ACP streaming
The ACP client's streaming generate SHALL emit each `agent_message_chunk` as a text event when it is read, `agent_thought_chunk` as a `thought` event with `via:"acp"`, and agent tool calls/results as `tool_call`/`tool_result` with `via:"acp"`.

#### Scenario: Drip
- **WHEN** the agent sends three chunks 200 ms apart
- **THEN** the stream yields three text deltas and the first arrives at least 300 ms before `done`

### Requirement: Toolkit as an MCP server for an agent
`toolkit.asMcpServer(config?)` SHALL serve the toolkit with the §7C mapping on `http://127.0.0.1:<ephemeral>/mcp`, return a fresh 64-hex-character bearer token, reject requests without that token with 401 and `WWW-Authenticate: Bearer`, answer non-POST with 405 and other paths with 404, and stop on `close()`.

#### Scenario: Authorized tools/list
- **WHEN** an MCP client connects with `Authorization: Bearer <token>`
- **THEN** `tools/list` returns the toolkit tool names verbatim, including `skill`

#### Scenario: Missing token
- **WHEN** a request carries no token
- **THEN** the response is 401 with `WWW-Authenticate: Bearer`

### Requirement: No vendor surface
The library SHALL NOT expose vendor-specific constructors, enums, style data, prompt preambles, permission allow-lists or model lists for agent CLIs.

#### Scenario: Public API
- **WHEN** the public API of any port is listed
- **THEN** it contains no `createCliClient`, `CliStyles` or vendor-named symbol
