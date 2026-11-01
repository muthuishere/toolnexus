## ADDED Requirements

### Requirement: ACP client parity
The Rust port SHALL provide an ACP client that spawns an agent over stdio JSON-RPC, performs `initialize` and `session/new`, sends `session/prompt`, streams `session/update` notifications to the host, and answers `session/request_permission` through a host callback whose default selects the first allow option, matching `golang/acp.go`.

#### Scenario: Prompt with streamed updates
- **WHEN** a scripted fake agent emits two `session/update` chunks and then completes the prompt
- **THEN** the host receives both updates in order and the prompt result carries the agent's stop reason

#### Scenario: Permission request
- **WHEN** the fake agent sends `session/request_permission` with options reject_once and allow_once and no callback is set
- **THEN** the client answers with allow_once

### Requirement: Byte-identical skills
The Rust port's `skill` tool output and `skillsPrompt()` SHALL be byte-identical to the other ports for `examples/skills/`.

#### Scenario: hello-world skill
- **WHEN** the `skill` tool is called with `hello-world` against `examples/skills/`
- **THEN** the output bytes equal the shared expected output

### Requirement: MCP client conformance
The Rust port SHALL parse MCP config per SPEC §0 items 2–3 and execute MCP tools per item 4, over stdio and streamable HTTP, isolating a failing server.

#### Scenario: Shared fixture
- **WHEN** the port loads `examples/mcp.json`'s local server
- **THEN** tool names are `sanitize(server)_sanitize(tool)` and a call returns the same output as the Go port
