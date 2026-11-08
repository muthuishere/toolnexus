## ADDED Requirements

### Requirement: Change mode and model on a running ACP client
The ACP client SHALL offer `setMode(mode)` and `setModel(model)` on a running client without restarting the agent or opening a new session. `setMode` SHALL send `session/set_mode {sessionId, modeId}`. `setModel` SHALL send `session/set_config_option {sessionId, configId:"model", value}` and, if the agent rejects it, `session/set_model {sessionId, modelId}`, failing only when both fail. Values SHALL pass through unvalidated. An agent rejection SHALL fail only that call and leave the client usable.

#### Scenario: Live switch keeps the session
- **WHEN** a loaded client calls `setMode("plan")` then `setModel("m-2")` then generates
- **THEN** the agent records `session/set_mode {modeId:"plan"}` and `session/set_config_option {configId:"model", value:"m-2"}` with the original sessionId, `initialize` and `session/new` exactly once, and the turn answers

#### Scenario: Model fallback on a running client
- **WHEN** the agent rejects `session/set_config_option` and `setModel("m-2")` is called
- **THEN** `session/set_model {modelId:"m-2"}` is sent and the call succeeds

#### Scenario: Agent rejects the mode
- **WHEN** the agent answers `session/set_mode` with an error
- **THEN** `setMode` fails with that error and a following generate still answers
