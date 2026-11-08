## MODIFIED Requirements

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

#### Scenario: Header-only 2026 request on a mixed mount

- **WHEN** a handler configured with `["2026-07-28", "2025-06-18"]` receives a POST `ping` carrying `MCP-Protocol-Version: 2026-07-28` but no `Mcp-Method` header and no `_meta` protocol version
- **THEN** the legacy §7C.1 wire serves it and answers HTTP 200 with an empty result, not a `-32020` header mismatch

#### Scenario: initialize asking for 2026-07-28

- **WHEN** a client sends `initialize` with `protocolVersion: "2026-07-28"` to a mixed mount
- **THEN** the result's `protocolVersion` is the newest listed legacy version, because 2026-07-28 has no `initialize` and `server/discover` is its handshake

