## ADDED Requirements

### Requirement: Stdio tool-call timeouts unchanged by the call path

However a port schedules an MCP stdio tool call, the idle timeout SHALL fire at the configured budget
with the same error text, progress notifications SHALL reset it, cancellation SHALL end the call with
the same cancelled result, and no interrupt or cancellation state SHALL leak to the caller afterwards.

#### Scenario: Progress resets the idle budget

- **WHEN** a tool call lasts twice the idle budget but sends a progress notification every third of it
- **THEN** the call succeeds

#### Scenario: Idle budget expires

- **WHEN** a tool call sends nothing for longer than the idle budget
- **THEN** it fails with the port's existing idle-timeout message and the caller's thread is not left interrupted
