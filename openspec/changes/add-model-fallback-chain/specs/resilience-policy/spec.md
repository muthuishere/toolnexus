## ADDED Requirements

### Requirement: Opt-in model fallback chain on overload
The client SHALL accept an optional ordered `fallbackModels` list (idiomatic name per port). For each
model call the chain SHALL be the turn's model (configured, or that turn's `beforeLLM` override)
followed by `fallbackModels`, skipping empty entries and duplicates. The retry budget SHALL be spent
on the current model first; only when it is exhausted and the final HTTP status is `503` or `529`
SHALL the call advance to the next model with a fresh retry budget. No other failure — `429`, other
statuses, network errors, aborts, or an attempt `onError` classified `"fail"` — SHALL advance the
chain. When the chain is exhausted the last model's error SHALL surface unchanged. Each call SHALL
start at its own turn model (no stickiness). Absent or empty `fallbackModels` SHALL be byte-identical
to a client without the option, and in-process/ACP model sources SHALL ignore it.

#### Scenario: Overload exhausts the budget and the next model serves
- **WHEN** `fallbackModels` is `["m2"]`, `retries` is 0, and model `m1` answers `529`
- **THEN** the client sends the same request with model `m2`, and `RunResult.model` is `m2`

#### Scenario: A rate limit does not switch models
- **WHEN** `fallbackModels` is `["m2"]` and model `m1` answers `429` on every attempt
- **THEN** the run fails with the `429` error and no request is sent with model `m2`

#### Scenario: onError fail short-circuits the chain
- **WHEN** `onError` returns `"fail"` for a `503` from `m1` and `fallbackModels` is `["m2"]`
- **THEN** the `503` error surfaces and no request is sent with model `m2`

#### Scenario: Absent option is byte-identical
- **WHEN** `fallbackModels` is unset and `m1` answers `529` beyond the budget
- **THEN** the run fails exactly as it does without this change

### Requirement: Model fallback is observable and can be disabled
When `stream` switches models it SHALL emit `{ type: "fallback", from, to }` before the next model's
request. The model that served SHALL be reported as the model transmitted: in `afterLLM`, the `llm`
metric event, `RunResult.model` and the `run` metric event. When the environment variable
`TOOLNEXUS_DISABLE_MODEL_FALLBACK` is `1` or `true` (case-insensitive), read at each model call, the
chain SHALL be ignored.

#### Scenario: Stream reports the switch
- **WHEN** a stream's model `m1` answers `503` past its budget and `fallbackModels` is `["m2"]`
- **THEN** the stream emits `{ type: "fallback", from: "m1", to: "m2" }` before any text from `m2`

#### Scenario: Kill switch disables the chain
- **WHEN** `TOOLNEXUS_DISABLE_MODEL_FALLBACK=1` and `m1` answers `529` past its budget
- **THEN** the run fails with the `529` error and no request is sent with model `m2`
