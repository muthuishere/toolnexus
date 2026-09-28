## ADDED Requirements

### Requirement: A beforeLLM override may choose the model for one turn

A `beforeLLM` hook override MAY carry `model`. When present and non-empty, that turn's request
body and `afterLLM` event SHALL carry it; the next turn starts from the configured model again.
When absent, null or empty, the configured `model` SHALL be transmitted verbatim, byte-identical
to a build without this requirement, in every client style and in streaming. The model REPORTED SHALL be
the model transmitted: the turn's `llm` metric event, `RunResult.model` and the `run` metric event
(the last model call's model), and `translate`'s `result.model`.

#### Scenario: No override keeps the configured model verbatim

- **WHEN** no hook returns a `model`
- **THEN** every request body carries the configured model unchanged

#### Scenario: An override routes only its turn

- **WHEN** `beforeLLM` returns `model: "small-fast"` on the first turn only
- **THEN** the first request carries `small-fast` and the second carries the configured model

#### Scenario: The reported model is the transmitted model

- **WHEN** `beforeLLM` returns `model: "small-fast"` on every turn of a run
- **THEN** each `llm` metric event, the `run` metric event and `RunResult.model` carry `small-fast`, and a `translate` result's `model` is `small-fast`

#### Scenario: Without an override the configured model is reported

- **WHEN** no hook returns a `model`
- **THEN** `RunResult.model` and every metric event carry the configured model

### Requirement: A failing beforeLLM hook stops the call

When a `beforeLLM` hook fails (throws, rejects, or returns an error in the port's idiom), the entry
point SHALL fail with that error and SHALL NOT send a provider request for that turn. This holds in
every loop — run, stream, the agent run — in every client style, and in the single-call
`translate`. The failure SHALL NOT be swallowed, ignored or retried. On a §7D agent, a level-1
loop run SHALL throw the hook's error, and a runtime handle turn SHALL resolve it as the §7D
boundary result (`isError: true`, `status: "error"`) — identically in all seven ports.

#### Scenario: Translate stops on a hook error

- **WHEN** `beforeLLM` fails during `translate`
- **THEN** `translate` returns that error and the provider receives no request

#### Scenario: Streaming stops on a hook error

- **WHEN** `beforeLLM` fails on the first turn of a streamed run
- **THEN** the stream ends with that error and the provider receives no request

#### Scenario: A level-1 agent loop run throws on a hook error

- **WHEN** an agent's `beforeLLM` fails with "hook boom" and the agent's level-1 loop runs a prompt
- **THEN** the loop run throws or raises that error in every port and the provider receives no request

#### Scenario: A handle turn resolves a hook error as an error result

- **WHEN** an agent's `beforeLLM` fails with "hook boom" and a runtime handle for that agent runs one turn
- **THEN** the turn resolves `isError: true`, `status: "error"`, text containing "hook boom", never an exception, and the provider receives no request
