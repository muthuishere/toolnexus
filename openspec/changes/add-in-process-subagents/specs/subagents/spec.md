## ADDED Requirements

### Requirement: A sub-agent runtime accepts an in-process model

A sub-agent runtime SHALL accept a caller-supplied semantic generate function as an
alternative to wire-shaped transport or provider configuration, so that one model
implementation can serve both a top-level client and a sub-agent runtime without the caller
reimplementing the library's request/response adapter.

#### Scenario: One generate serves both a client and a sub-agent

- **WHEN** a host configures a top-level in-process client and a sub-agent runtime with the
  same generate function
- **THEN** both invoke that function for their LLM calls, and the host writes no
  request-assembly or response-parsing code of its own

#### Scenario: Conflicting model configuration is rejected

- **WHEN** a sub-agent runtime is configured with both a semantic generate and a
  wire-shaped transport or provider config
- **THEN** construction fails immediately, reporting the conflict by name, and no
  precedence rule silently selects one. The failure mechanism is each port's idiom for a
  static misconfiguration (a raised error, exception, or panic where the constructor has no
  error return); the observable behaviour is identical

#### Scenario: The turn gate applies to in-process sub-agents

- **WHEN** a sub-agent runtime configured with a semantic generate limits concurrent turns
  to one and several turns are started at once
- **THEN** no two invocations of the generate function overlap

### Requirement: The generate-backed adapter is public

Each port SHALL export the adapter that bridges a semantic generate function to that port's
model-call seam, and the port's own in-process client SHALL be implemented as a caller of
that exported adapter rather than a private duplicate.

#### Scenario: A host reuses the library's adapter

- **WHEN** a host needs to wire a semantic generate into a seam the library does not itself
  configure
- **THEN** the exported adapter is available for that purpose, and its behaviour is
  identical to the one the in-process client uses
