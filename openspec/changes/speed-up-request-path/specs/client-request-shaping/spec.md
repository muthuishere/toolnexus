## ADDED Requirements

### Requirement: Default LLM transport reuses connections

When the host supplies no HTTP transport, the default transport SHALL reuse a persistent connection
to the same scheme, host and port across LLM requests, and SHALL retry a request once on a fresh
connection when a reused idle connection turns out to have been closed by the server. A connection
that fails on its first use SHALL surface the error without retrying. HTTP error statuses SHALL be
reported with the same status, body and raw `Retry-After` value as before. When a proxy applies
from the environment, the previous transport SHALL be used unchanged.

#### Scenario: Idle connection closed by the server

- **WHEN** the server silently closes a kept-alive connection between two LLM requests
- **THEN** the second request is sent once on a new connection and succeeds, with no error surfaced

#### Scenario: Connection reused

- **WHEN** five LLM requests go to a server that keeps connections open
- **THEN** the server accepts exactly one connection

#### Scenario: Error status keeps Retry-After

- **WHEN** the server answers 429 with `Retry-After: 7`
- **THEN** the provider error carries status 429, the body, and the raw value `7`

#### Scenario: Host-supplied transport wins

- **WHEN** the host passes its own transport or fetch
- **THEN** the default keep-alive transport is not used
