## ADDED Requirements

### Requirement: Local command accepts string plus args

A local MCP server's `command` SHALL be accepted as either a non-empty string or a non-empty array
of strings, with an optional `args` array of strings appended, producing argv
`[command] ++ args` or `command ++ args`. The argv SHALL be spawned without shell interpretation.
An empty command, a non-string array element, or a non-array/non-string `args` SHALL fail the parse
with an error naming the server.

#### Scenario: Claude command string plus args

- **WHEN** the config is `{"mcpServers":{"x":{"command":"node","args":["server.mjs","a b"]}}}`
- **THEN** server `x` is local with argv `["node","server.mjs","a b"]`

#### Scenario: Argv array unchanged

- **WHEN** the config is `{"mcp":{"x":{"command":["node","server.mjs"]}}}`
- **THEN** server `x` has argv `["node","server.mjs"]`

#### Scenario: String without args

- **WHEN** `command` is `"node"` and `args` is absent
- **THEN** argv is `["node"]`

#### Scenario: Invalid command is a named error

- **WHEN** `command` is `""`, `[]`, or `["node", 1]`, or `args` is `"x"`
- **THEN** parsing fails with an error that names the server
