# agent-pipeline

A lazy, composable agent-pipeline algebra layered over the shipped agent loop (§8) and §10
suspend/resume. Every combinator returns a pipeline; a pipeline is a `Tool` (`source:"custom"`);
nothing runs until a terminal trigger.

## ADDED Requirements

### Requirement: Pipeline is a lazy composable Tool

The system SHALL provide compose combinators (`pipe`, `map`, `filter`, `reduce`, `branch`, `vote`,
`validate`, `verify`, `retry`, `orchestrate`) that each take pipelines and return a pipeline. A
pipeline SHALL be a `Tool` with `source:"custom"` and SHALL NOT execute any stage until a terminal
trigger is invoked. Composition SHALL be closed: the result of any combinator SHALL be usable as an
input to any other combinator.

#### Scenario: Building a pipeline runs nothing

- **WHEN** a caller composes `pipe(a, b, c)` (or any combinator) without invoking a terminal trigger
- **THEN** no stage's `execute` is called, no model request is made, and the result is a `Tool` with `source:"custom"` that can be passed to another combinator

#### Scenario: A terminal trigger fires the loop

- **WHEN** a caller invokes `run(input)` on a composed pipeline
- **THEN** the pipeline executes its stages through the shipped `client.run` loop and returns a `RunResult`

### Requirement: Config carries resumability

An `AgentPipeline` SHALL be built with an `AgentPipelineConfig` bundling `client` (the loop), `ask`
(the §10 `waitFor` resolver), `checkpoints` (a `ConversationStore`), and `guard` (a
`Hooks.beforeTool` policy). Only the root pipeline SHALL require a config; sub-pipelines SHALL
inherit it via context. Suspend/resume SHALL be a property of the config: the pipeline persists to
and reloads from `config.checkpoints`.

#### Scenario: Sub-pipeline inherits the root config

- **WHEN** a root pipeline built with a config runs a sub-pipeline that was composed without its own config
- **THEN** the sub-pipeline uses the root's `ask`, `checkpoints`, and `guard` for suspension, persistence, and policy

### Requirement: ask suspends and resumes via the config

An `ask` stage SHALL produce a §10 `Pending` (it SHALL NOT be a special-cased primitive). When
`config.ask` (a `waitFor`) is present, the pipeline SHALL resolve the suspension inline and resume
the stage with `ctx.answer`. When it is absent, the terminal `run` SHALL halt and return
`{status:"pending", pending:request}` for durable resume, and a later `resume(runId, answer)` SHALL
continue at the exact suspended stage.

#### Scenario: Durable resume continues at the suspended stage

- **WHEN** a pipeline suspends at an `ask` stage with no `waitFor`, is persisted, and `resume(runId, answer)` is later called with a valid answer
- **THEN** the pipeline reloads its checkpoint and resumes execution at that stage's cursor, not from the beginning

#### Scenario: Partial resume of a parallel map

- **WHEN** a `map` fans out N branches and one branch suspends while others complete
- **THEN** the completed branches are checkpointed and only the suspended branch waits, and the incoming answer resumes just that branch

### Requirement: Every resume passes the staleness gate

On every resume, the system SHALL apply, in order: expiry (a `request.expiresAt` in the past SHALL
reject the answer and re-`ask`), freshness (preconditions the paused work depended on SHALL be
re-evaluated; changed upstream state SHALL trigger recompute of the stale stage), and idempotency
(side-effecting stages SHALL carry a key so a replayed stage does not double-execute).

#### Scenario: A late answer is rejected

- **WHEN** an `Answer` arrives for a `Request` whose `expiresAt` has passed
- **THEN** the answer is not applied and the stage re-issues a fresh `Ask` instead of proceeding

### Requirement: Safety defaults

`retry` and `loop` SHALL default to a circuit-breaker keyed on `hash(tool_name + args)` that breaks
when the same call repeats, rather than relying solely on a static iteration cap. These defaults
SHALL be active without explicit configuration.

#### Scenario: Runaway loop is broken

- **WHEN** a stage under `retry`/`loop` invokes the same tool with identical args repeatedly
- **THEN** the circuit-breaker halts the repetition and surfaces an error result instead of continuing to spend tokens

### Requirement: Triggers activate the same pipeline

The system SHALL provide terminal triggers `run(input)`, `loop(until)`, `schedule(cron)`,
`on(event)`, and `serve(addr)` that all drive the same pipeline through `client.run`. `on`/`serve`
SHALL reuse the §7B inbound edge so a pipeline is reachable as an A2A agent.

#### Scenario: Same pipeline served as an A2A agent

- **WHEN** `serve(addr)` is invoked on a composed pipeline
- **THEN** the pipeline is exposed at the §7B A2A inbound endpoint and an inbound task drives the same composition that `run` would
