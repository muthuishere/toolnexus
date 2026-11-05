## ADDED Requirements

### Requirement: Skills over MCP
When a server declares the SEP-2640 skills extension, the client SHALL list its skills with `skills/list` and load them with `skills/get` into the same `skill` tool inventory as local skills. `serve` SHALL publish the toolkit's skills through the same methods.

#### Scenario: Remote hello-world
- **WHEN** a server publishes `examples/skills/hello-world`
- **THEN** the client's `skill` tool lists and loads it with byte-identical body to the local copy

### Requirement: Skill URI containment
Skill URIs SHALL be percent-decoded once and rejected if they contain `..`, an empty or `.` segment, a backslash, NUL, `:` or remaining `%`, on both client and server.

#### Scenario: Encoded traversal
- **WHEN** a request names `skill://x/%2e%2e/secret`
- **THEN** the server answers -32602 and nothing outside the skill is read
