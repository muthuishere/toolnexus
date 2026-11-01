## ADDED Requirements

### Requirement: Per-server MCP readiness
The MCP loader SHALL accept an `onServer` callback that is called once per configured server as soon as that server is connected, failed or found disabled, without waiting for other servers. Calls SHALL be serialized. The loader SHALL still return only after every server finishes, and parent cancellation SHALL still abort the load and close every client.

#### Scenario: Fast server is reported before a slow one
- **WHEN** one server is ready in milliseconds and another takes a second to start
- **THEN** `onServer` reports the fast server, with its tools, before the slow server finishes

#### Scenario: Failed and disabled servers are reported
- **WHEN** a server's command does not exist and another is disabled
- **THEN** `onServer` reports `failed` with an error and `disabled` respectively

### Requirement: Concurrent toolkit registration
`Toolkit.register` SHALL be safe to call while a turn reads the toolkit and while other register calls run. A tool registered mid-turn SHALL be offered from the next LLM call.

#### Scenario: Register while reading
- **WHEN** tools are registered while other goroutines list, get and execute tools
- **THEN** there is no data race and every registered tool is present afterwards
