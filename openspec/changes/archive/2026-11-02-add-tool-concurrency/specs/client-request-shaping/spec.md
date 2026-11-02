## ADDED Requirements

### Requirement: Concurrency-safe tool scheduling
The client SHALL run the tool calls of one model response in emitted order as consecutive runs: consecutive concurrency-safe calls SHALL run concurrently, at most `maxToolConcurrency` at once, and each unsafe call SHALL run alone after every earlier call has finished and before any later call starts. Results, `RunResult.toolCalls` and the transcript SHALL keep emitted order. This SHALL hold for run and stream, both styles, the resume paths and agent runs.

#### Scenario: Two writes serialize
- **WHEN** one response carries two calls to tools without the readOnly hint
- **THEN** the second starts only after the first has finished, and peak overlap is 1

#### Scenario: Reads overlap
- **WHEN** one response carries six calls to readOnly tools and no cap is configured
- **THEN** all six run at once

#### Scenario: Mixed runs
- **WHEN** a response carries read, read, write, read, read
- **THEN** both leading reads finish before the write starts, neither trailing read starts before the write ends, and results are in emitted order

### Requirement: Concurrency safety rule
A call SHALL be concurrency-safe when the tool's own `concurrencySafe(args)` predicate says so; without a predicate, only when the tool's hints say readOnly. A tool without hints SHALL be unsafe. With tool tiers, `tool_search` SHALL be safe and `call_tool` SHALL take the safety of the tool it names. Relay tools and the agent runtime's `task` tool SHALL be safe.

#### Scenario: Builtins
- **WHEN** safety is asked of the builtins
- **THEN** read, grep, glob and webfetch are safe and bash, write, edit and apply_patch are unsafe

#### Scenario: Predicate wins per input
- **WHEN** a readOnly tool carries a predicate that is true only for `{dry: true}`
- **THEN** a call with `{}` is unsafe and a call with `{dry: true}` is safe

### Requirement: Tool concurrency cap
`maxToolConcurrency` SHALL resolve as: a positive option value; else `TOOLNEXUS_MAX_TOOL_CONCURRENCY` when it parses as a positive integer; else 10.

#### Scenario: Cap honoured
- **WHEN** six safe calls run with maxToolConcurrency 2
- **THEN** peak overlap is exactly 2

#### Scenario: Env override and fallback
- **WHEN** the env var is 3 and no option is set
- **THEN** peak overlap of six safe calls is 3; with option 4 it is 4; with env 0, -2, abc or empty it is the default
