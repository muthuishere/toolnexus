# Delta for agent-harness-loop

## ADDED Requirements

### Requirement: Harness names the agent specification

The library SHALL expose a `Harness` factory (idiomatic name per port) that produces an
agent specification without changing its type. It is a naming affordance only: a
specification built through `Harness` and one built directly SHALL be indistinguishable.

#### Scenario: Harness is the specification, not a wrapper

- **WHEN** an agent is built through `Harness` and an identical agent is built directly
- **THEN** both produce the same specification and behave identically

### Requirement: A loop is a live execution and takes no configuration

The library SHALL expose a `Loop`, opened from an agent, that executes requests and
reports observed state: a `status`, a count of model round trips, and an `Outcome`. The
loop SHALL NOT accept configuration options — capability belongs to the harness and
per-call choices belong to the run options.

The `Outcome` SHALL name the reason for any status other than `done`. A loop SHALL NEVER
stop silently.

#### Scenario: The loop reports turns and status without being configured

- **WHEN** a caller opens a loop for an agent and runs one request that completes
- **THEN** the outcome status is `done`, the turn count is the model round trips spent,
  and no stop reason is set

#### Scenario: A non-done outcome always names its stop reason

- **WHEN** a run ends with any status other than `done`
- **THEN** the outcome carries a non-empty stop reason describing what stopped it

### Requirement: The model is chosen per call, not per loop

Run options SHALL accept a model override that applies to a single call and reaches the
provider request. Omitting it SHALL use the agent's own model.

#### Scenario: A per-call model override reaches the wire

- **WHEN** a caller runs a request with a model override
- **THEN** the provider request carries the overriding model, and a subsequent run without
  the override carries the agent's own model

### Requirement: A completion gate stops an agent claiming done too early

An agent specification SHALL accept an optional completion gate carrying a verifier and a
**required** maximum attempt count. When present, the gate SHALL run at the point the loop
would otherwise report `done`. When the verifier passes, the run reports `done`. When it
fails, the loop SHALL feed the failure reason back to the agent and retry, bounded by the
maximum attempts.

When the gate is absent, behavior SHALL be byte-identical to the pre-change loop.

#### Scenario: Absent gate leaves behavior unchanged

- **WHEN** an agent declares no completion gate
- **THEN** the loop behaves exactly as before this change

#### Scenario: A failing verifier blocks done and retries

- **WHEN** the verifier rejects the first attempt and accepts the second
- **THEN** the run reports `done` after two attempts, and the failure reason was given
  back to the agent between them

#### Scenario: A maximum attempt count is required

- **WHEN** a completion gate is declared without a maximum attempt count of at least one
- **THEN** the loop reports an error rather than looping unbounded

#### Scenario: An unverifiable run stops loudly

- **WHEN** the verifier rejects every attempt up to the maximum
- **THEN** the run reports status `incomplete` with a structured limit of `completion` and
  a human-readable reason, and never reports `done`

### Requirement: The gate judges accumulated work across attempts

The verifier SHALL receive the tool calls accumulated across every attempt of the loop,
not only those of the latest attempt.

#### Scenario: An agent cannot escape the gate by not re-declaring its plan

- **WHEN** the first attempt declares a plan with an open item and the retry declares no
  plan at all
- **THEN** the verifier still sees the earlier plan and the run does not report `done`

### Requirement: The gate never re-judges a run that stopped for its own reason

When a run reports any status other than `done` — a suspension, a budget stop — the gate
SHALL NOT evaluate the verifier for that run, and SHALL NOT alter that status.

#### Scenario: A suspension is not converted into a verification failure

- **WHEN** a run suspends and reports `pending`
- **THEN** the outcome remains `pending`, the verifier is not consulted, and the caller can
  still tell it owes an answer rather than a fix

#### Scenario: A budget stop mid-verification reports both reasons

- **WHEN** a verification attempt fails and a subsequent attempt is stopped by a budget
  ceiling
- **THEN** the reported reason names the budget stop **and** the last verification failure

### Requirement: The completion gate travels through delegation

A completion gate declared on an agent SHALL apply when that agent runs as a delegated
child of another agent, not only when it is driven directly.

#### Scenario: A delegated child is gated

- **WHEN** a parent delegates to a child whose specification declares a completion gate,
  and the child's verifier fails
- **THEN** the child does not report `done` to the parent

### Requirement: A built-in verifier checks declared plan state

The library SHALL provide a verifier that reads the built-in todo tool's result metadata
and passes only when every declared item is complete. It SHALL be structural: it SHALL NOT
interpret the meaning of an item. When no plan was declared, it SHALL pass.

#### Scenario: An open item blocks completion

- **WHEN** the agent's last todo declaration leaves one item incomplete
- **THEN** the verifier fails and names the open item

#### Scenario: No declared plan passes

- **WHEN** the agent never called the todo tool
- **THEN** the verifier passes, so the gate never punishes an agent for not using the builtin

### Requirement: Guardrails are policy checks composed first-deny-wins

An agent specification SHALL accept optional guardrails: callbacks evaluated before a tool
runs that either allow the call or deny it with a reason. Guardrails SHALL compose into a
single before-tool hook where the **first denial wins** and a later guardrail cannot widen
an earlier denial. Any pre-existing before-tool hook SHALL run only if every guardrail
allows. No guardrails SHALL be byte-identical to the pre-change behavior.

#### Scenario: The first denial wins

- **WHEN** two guardrails are declared and the first denies a tool call
- **THEN** the tool does not execute, the denial reason is the first guardrail's, and the
  second guardrail cannot re-allow it

#### Scenario: Guardrails run before an existing hook

- **WHEN** a guardrail allows and a before-tool hook is also declared
- **THEN** the hook runs; when the guardrail denies, the hook does not run

#### Scenario: Guardrails survive delegation

- **WHEN** an agent with guardrails is delegated to as a child
- **THEN** the guardrails still apply to that child's tool calls
