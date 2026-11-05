## ADDED Requirements

### Requirement: Client speaks 2026-07-28 first
The MCP client SHALL call `server/discover` before any other request and, when the server lists `2026-07-28`, SHALL send every request with `_meta` keys `io.modelcontextprotocol/protocolVersion`, `clientCapabilities` and `clientInfo`, and over HTTP the headers `MCP-Protocol-Version`, `Mcp-Method` and (for tools/call, prompts/get, resources/read) `Mcp-Name`. The same client API SHALL work over stdio and Streamable HTTP.

#### Scenario: Discover then call over HTTP
- **WHEN** a client loads an HTTP server that lists 2026-07-28 in `server/discover`
- **THEN** every later request carries the three `_meta` keys and matching headers, and no `initialize` is sent

#### Scenario: Same steps over stdio
- **WHEN** the server is a stdio command
- **THEN** discover, tools/list and tools/call behave identically, with stderr captured and never parsed

### Requirement: Frozen fallback for older servers
When `server/discover` fails with method-not-found or an unsupported-version error, the client SHALL fall back to the existing pre-2026 path unchanged. Features introduced by this change SHALL NOT be available on that path, except OAuth.

#### Scenario: Old server keeps working
- **WHEN** a 2025-11-25 server is configured
- **THEN** its tools load and run exactly as before this change

### Requirement: MRTR input requests route through suspension
A `tools/call` result with `resultType: "input_required"` SHALL be surfaced through SPEC §10 (`waitFor`): form elicitations as `input`, URL elicitations as `authorization`. The client SHALL retry the same call with `inputResponses` and the server's `requestState`, resuming at the paused round without re-asking earlier rounds.

#### Scenario: Greet asks for a name
- **WHEN** `greet` returns input_required for `name` and the host answers "toolnexus"
- **THEN** the retried call returns "Hello, toolnexus!" and the server ran the tool body once

### Requirement: No hidden re-execution
The client SHALL NOT re-send a `tools/call` after a lost connection, timeout, 5xx or unreadable response. It SHALL re-send only after a 401, or a 403 `insufficient_scope`, carrying `WWW-Authenticate`.

#### Scenario: Response lost after the tool ran
- **WHEN** the connection drops after the server executed the tool
- **THEN** the caller gets an error and the server's execution count stays 1

### Requirement: Server speaks 2026-07-28 only
`serve` with the MCP profile SHALL implement `server/discover`, return `resultType` on every result, list tools in deterministic order with `ttlMs` and `cacheScope`, reject an unsupported `_meta` version with -32022, and reject header/body mismatch with -32020. It SHALL keep no per-client session state and SHALL NOT offer a stdio transport.

#### Scenario: Wrong version
- **WHEN** a request carries protocolVersion 2025-11-25
- **THEN** the server answers -32022 listing its supported versions
