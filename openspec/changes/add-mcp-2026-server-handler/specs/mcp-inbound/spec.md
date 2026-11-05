## ADDED Requirements

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
plus an RFC 9728 metadata handler. Each port SHALL ship an in-process development authorization server supporting
pre-registered and Client ID Metadata Document clients with PKCE S256, documented as not for production.

#### Scenario: Step-up

- **WHEN** a token without `mcp:admin` calls a tool that needs it on the 2026 wire
- **THEN** the handler answers 403 with `scope="mcp mcp:admin", error="insufficient_scope"` and the tool does not run

#### Scenario: Client interop

- **WHEN** the toolnexus 2026 client loads a handler protected by `MCPServerAuth` and the dev authorization server
- **THEN** it discovers the metadata, logs in with a pre-registered client id or a CIMD URL, and calls a tool
