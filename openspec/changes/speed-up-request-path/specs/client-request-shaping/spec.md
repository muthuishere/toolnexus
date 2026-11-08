## ADDED Requirements

### Requirement: Default LLM transport reuses connections

When the host supplies no HTTP transport, the default transport SHALL reuse a persistent connection
to the same scheme, host and port across LLM requests. A reused connection the server closed while
idle SHALL be replaced before the request is sent. A request that fails after it was sent SHALL NOT
be re-sent. HTTP error statuses SHALL be
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

#### Scenario: Drop after the request was sent

- **WHEN** the server reads a request on a reused connection and closes it without answering
- **THEN** the error surfaces and the request is not sent again
