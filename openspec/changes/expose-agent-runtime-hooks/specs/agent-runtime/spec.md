## ADDED Requirements

### Requirement: The agent runtime forwards the §8 hooks and metric sink into the client it builds

The `§7D` agent runtime SHALL accept two optional configuration values — `hooks` (the `§8`
lifecycle callbacks) and `onMetric` (the `§8` semantic observability sink) — and SHALL assign the
resolved value of each into the corresponding field of the `ClientOptions` it constructs for a
handle's turn. Each port SHALL use the name its client already uses for that field (`hooks` /
`onMetric` in `js`, `hooks` / `on_metric` in `python` and `elixir`, `Hooks` / `OnMetric` in
`golang` and `csharp`, `hooks(...)` / `onMetric(...)` on the `java` builder).

The runtime SHALL forward both values **verbatim**: it SHALL NOT compose, wrap, reorder, default,
or otherwise interpret either value, and SHALL NOT read them for its own purposes. All `§8`
semantics for hooks and metric events therefore hold identically whether the client was
constructed directly by a caller or by the agent runtime.

Neither value SHALL be a route to alter any option the runtime owns. Supplying `hooks` or
`onMetric` SHALL NOT allow a caller to change the handle's system prompt (its composed soul), its
`§10` escalating `waitFor`, its HTTP seam (which the global turn gate wraps), or the
runtime-wide `ConversationStore`.

#### Scenario: A beforeLLM hook supplied to the runtime runs inside an agent turn

- **WHEN** a runtime is configured with `hooks` carrying a `beforeLLM` that rewrites the working
  transcript, and an agent handle runs a turn
- **THEN** the rewritten transcript is the one sent to the LLM for that turn

#### Scenario: A metric sink supplied to the runtime receives the run's events

- **WHEN** a runtime is configured with `onMetric` and an agent handle completes a turn that makes
  an LLM call and one tool call
- **THEN** the sink receives the `"llm"`, `"tool"` and terminal `"run"` events for that turn, with
  the same shape `§8` specifies for a directly constructed client

#### Scenario: Unset is byte-identical to today

- **WHEN** a fixture agent run executes with neither `hooks` nor `onMetric` configured at any level
- **THEN** its transition trace, stored transcript, and result are byte-identical to the same
  fixture run before this capability existed

#### Scenario: Runtime-owned options are not overridable through the seam

- **WHEN** a caller supplies `hooks` or `onMetric` to the runtime
- **THEN** the handle's system prompt, `waitFor`, HTTP seam and conversation store remain exactly
  those the runtime composed, and no configuration value is exposed that could replace them

### Requirement: Per-agent hooks and metric sink override the runtime-wide values

An agent definition SHALL additionally accept optional `hooks` and `onMetric` values. For a given
handle, the runtime SHALL resolve each independently: the definition's value when set, otherwise
the runtime's value, otherwise none. Resolution SHALL be **replacement, never merging** — when a
definition sets `hooks`, the runtime-wide `hooks` SHALL NOT also run for that agent, and the same
holds for `onMetric`. This is what lets a caller vary a `§7F` compaction budget per agent and
attribute metric events to the agent that produced them.

#### Scenario: A definition's hooks replace the runtime's for that agent only

- **WHEN** a runtime sets a runtime-wide `beforeLLM`, agent A's definition sets its own
  `beforeLLM`, and agent B's definition sets none
- **THEN** agent A's turns run only A's definition hook and agent B's turns run only the
  runtime-wide hook

#### Scenario: The two values resolve independently

- **WHEN** a definition sets `hooks` but not `onMetric`, and the runtime sets both
- **THEN** that agent's turns use the definition's `hooks` and the runtime's `onMetric`

#### Scenario: Neither level set means no hook

- **WHEN** neither the definition nor the runtime sets `hooks`
- **THEN** the client is constructed with no hooks and the turn is byte-identical to a run with
  the capability absent
