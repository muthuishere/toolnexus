# client-observability Specification

## Purpose
TBD - created by archiving change add-streaming-memory-and-observability. Update Purpose after archive.
## Requirements
### Requirement: Streaming supports conversation memory by id

`stream(prompt, { toolkit, id? })` SHALL, when given an `id`, load that id's transcript from the
client's `ConversationStore` as history before streaming, and save the updated `RunResult.messages`
back under `id` when the terminal `done` event is emitted. Without an `id` it SHALL remain stateless.
The event sequence (`text`/`tool_call`/`tool_result`/`usage`/`done`) SHALL be unchanged.

#### Scenario: Streamed turns remember by id

- **WHEN** `stream` is consumed twice with the same `id`
- **THEN** the second stream's run receives the first stream's transcript as history
- **AND** the updated transcript is saved to the store on the `done` event

### Requirement: ask streams via an on_text callback without changing its return type

`ask(prompt, { toolkit, id?, on_text? })` SHALL, when `on_text` is provided, invoke it with each
assistant text delta as the loop streams, and SHALL still return the final `RunResult`. Omitting
`on_text` SHALL be non-streaming. The return type SHALL be `RunResult` regardless of `on_text`.

#### Scenario: on_text streams while ask still returns a RunResult

- **WHEN** `ask` is called with an `on_text` callback
- **THEN** `on_text` is invoked with text deltas during the run
- **AND** `ask` returns the complete final `RunResult`

### Requirement: on_metric emits semantic events

`createClient({ ..., on_metric })` SHALL invoke `on_metric` with readable semantic event records —
not counter/histogram primitives — at each significant point: `{ event: "llm", model, status, ms,
prompt_tokens, completion_tokens }` per LLM call, `{ event: "tool", tool, source, is_error, ms }` per
tool call, and `{ event: "run", model, turns, tool_calls, total_tokens, ms, error }` per run. When
`on_metric` is unset there SHALL be no measurable overhead.

#### Scenario: llm, tool, and run events fire

- **WHEN** a run makes an LLM call that triggers a tool call and completes
- **THEN** `on_metric` receives an `"llm"` event, a `"tool"` event, and a final `"run"` event with the
  aggregated fields

### Requirement: client.metrics() renders Prometheus text with no third-party dependency

`client.metrics()` SHALL return the Prometheus text exposition format of cumulative counters and
histograms accumulated from the same internal instrumentation, using no third-party dependency in any
port. It SHALL expose at least `toolnexus_llm_requests_total{model,status}`,
`toolnexus_llm_tokens_total{type}`, `toolnexus_llm_request_duration_seconds{model}` (histogram),
`toolnexus_tool_calls_total{tool,source,is_error}`, `toolnexus_tool_duration_seconds{tool}` (histogram),
and `toolnexus_run_errors_total{model}`. Label cardinality SHALL be bounded (no conversation `id`).

#### Scenario: metrics render after activity

- **WHEN** the client has run at least one request and `client.metrics()` is called
- **THEN** it returns valid Prometheus text including the request/token/tool counters with their labels

### Requirement: on_metric events are identical on the agent-runtime path

When an `on_metric` sink reaches a client through the `§7D` agent runtime (runtime-wide or on an
agent definition) rather than through direct client construction, the sink SHALL receive the same
semantic events, with the same names and the same fields, that `§8` already specifies. The agent
runtime SHALL NOT add, rename, drop, buffer, reorder, or aggregate events on this path.

When no sink is set at either level, there SHALL be no measurable overhead, matching the
existing guarantee for an unset sink on a directly constructed client.

#### Scenario: The same turn emits the same events on both paths

- **WHEN** the same scripted turn runs once on a directly constructed client with `on_metric` and
  once on an agent handle whose runtime supplies the same sink
- **THEN** both sinks receive the same sequence of `"llm"`, `"tool"` and `"run"` events with
  equal fields (timing values excepted)

#### Scenario: Per-agent sinks attribute events to their agent

- **WHEN** each agent definition in a runtime supplies its own `on_metric` sink
- **THEN** each sink receives only the events produced by that agent's turns

#### Scenario: elixir keeps its shared registry alongside the sink

- **WHEN** an `elixir` runtime supplies `on_metric` and an agent runs a turn
- **THEN** the event is recorded into the runtime-wide `MetricsRegistry` and then passed to the
  sink, exactly as a directly constructed `Toolnexus.Client` relates the two

