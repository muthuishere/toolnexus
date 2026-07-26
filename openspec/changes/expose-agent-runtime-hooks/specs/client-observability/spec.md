## ADDED Requirements

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
