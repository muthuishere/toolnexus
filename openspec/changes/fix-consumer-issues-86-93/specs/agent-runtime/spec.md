## ADDED Requirements

### Requirement: The Loop honours the Spec it is handed

A `Loop` built from an agent SHALL apply that agent's `Spec` when it builds its client: the
agent's soul as the system prompt, the agent's compiled guardrail and hook chain, the agent's
model as the default model, and the agent's turn budget as the default turn cap.

A caller-supplied system prompt SHALL win over the agent's soul, in every port. A caller-supplied
model and turn cap SHALL likewise win; the Spec supplies defaults, never overrides.

The Spec's model SHALL apply when the caller's model is **absent** *or* equal to the sentinel
`"inherit"`. Both spellings SHALL be honoured in every port, including ports whose model field is
optional and could express absence alone — otherwise a caller who passes a real model alongside a
Spec model gets one behaviour in some ports and another elsewhere. One rule, two spellings.

A guardrail declared on a Spec SHALL deny on the Loop path exactly as it denies on the one-shot
runtime path. A denial SHALL be observable as the denied tool's executor never being entered —
not merely as text the model produced.

#### Scenario: A denied tool never executes on the Loop path

- **WHEN** an agent whose Spec denies a tool is driven through its Loop and the model calls that tool
- **THEN** the tool's executor is never entered
- **AND** the same Spec driven through the one-shot runtime path produces the same denial

#### Scenario: The soul reaches the Loop's system prompt

- **WHEN** an agent with a soul and no caller-supplied system prompt is driven through its Loop
- **THEN** the request's system message carries the soul

#### Scenario: The caller's system prompt wins over the soul

- **WHEN** an agent with a soul is driven through its Loop with a caller-supplied system prompt
- **THEN** the request's system message is the caller's prompt, in every port

#### Scenario: Spec model and turn cap are defaults

- **WHEN** an agent whose Spec names a model and a turn cap is driven through its Loop with neither supplied by the caller
- **THEN** the run uses the Spec's model and stops at the Spec's turn cap
- **AND** a caller who supplies either one overrides it

#### Scenario: The inherit sentinel means the same as an absent model

- **WHEN** a Loop is driven with the caller's model set to `"inherit"` and the agent's Spec naming a model
- **THEN** the run uses the Spec's model, exactly as it would had the caller's model been absent

#### Scenario: A real caller model still wins over the Spec

- **WHEN** a Loop is driven with a caller model that is neither absent nor `"inherit"`, and the Spec names a different model
- **THEN** the run uses the caller's model, in every port

### Requirement: A Loop names the Spec fields it cannot honour

A driver over a caller-built client and toolkit genuinely cannot honour a Spec's tool set, team,
suspension handler or metric sink. Every port SHALL expose an additive query — named per port,
e.g. `loopUnsupported(spec)` — returning the names of the Spec fields that the Loop path will
ignore for that Spec, and SHALL name the same limitation in its documentation.

The returned names SHALL be drawn from one closed vocabulary, identical in every port, exactly as
the limit strings are: `"tools"`, `"team"`, `"waitFor"`, `"onMetric"`. A port SHALL NOT return its
own language's spelling of the field, so that the returned names can be compared across ports.

This query SHALL NOT change any existing signature and SHALL NOT fail construction. A Spec
declaring none of the unhonourable fields SHALL yield an empty result, and a Loop built from it
SHALL behave byte-identically to one built before this capability existed.

#### Scenario: A Spec with a team is reported as partly unhonourable

- **WHEN** `loopUnsupported` is called with a Spec declaring a team
- **THEN** the result is exactly `["team"]`, in every port
- **AND** constructing the Loop still succeeds

#### Scenario: The returned names are one closed vocabulary

- **WHEN** `loopUnsupported` is called with a Spec declaring all four unhonourable fields
- **THEN** the returned names are drawn from `"tools"`, `"team"`, `"waitFor"`, `"onMetric"` and
  are the same strings in every port, whatever each language spells the field

#### Scenario: A Spec with nothing unhonourable reports nothing

- **WHEN** `loopUnsupported` is called with a Spec declaring only a soul, guardrails, a model and a budget
- **THEN** the result is empty

### Requirement: A task result reports the subtree's tokens and its own

`TaskResult.totalTokens` SHALL be the handle's rolled-up, cumulative subtree usage on **every**
status — the same number the runtime's own tree ledger reports — and SHALL NOT vary in meaning by
which status was returned.

`TaskResult.turns` SHALL be that handle's **own** cumulative round trips, reported identically on
**every** status. The defect being closed is that the field means one thing on three statuses and
another on the other three — nothing more.

Turns SHALL **NOT** be rolled up the ancestor chain. The runtime's rollup walks ancestors for
tokens and tool calls only; no port accumulates a child's turns into its parent today, and adding
one would be new behaviour rather than a parity fix. A port SHALL NOT implement a turns roll-up.

It follows that a parent MAY report **fewer** turns than a child it delegated to — a parent can
delegate in one turn to a child that takes five — and no requirement here says otherwise. The
"never fewer than the child" guarantee holds for tokens, which roll up, and does not hold for
turns.

A new `ownTokens` field SHALL carry that handle's own accumulated spend excluding its children,
so the per-agent figure remains reachable. There SHALL be **no** corresponding own-turns field:
with no roll-up, `turns` already **is** the own-figure, so a second field would carry the same
number under a second name.

A parent that delegated to a child SHALL never report fewer total tokens than that child.

#### Scenario: One delegation is visible in the parent's bill

- **WHEN** a coordinator delegates once to a team member and both spend tokens
- **THEN** the coordinator's `totalTokens` is at least the child's `totalTokens`
- **AND** it equals the runtime's tree ledger for that handle

#### Scenario: The meaning does not depend on the status

- **WHEN** the same handle settles once as `done` and once as `incomplete`
- **THEN** `totalTokens` is the cumulative subtree figure in both cases

#### Scenario: Turns is the same figure on every status

- **WHEN** the same handle settles once as `done` and once as `incomplete`
- **THEN** the reported `turns` is that handle's own cumulative round trips in both cases
- **AND** no status branch reports a per-run figure instead

#### Scenario: Turns are not rolled up into the parent

- **WHEN** a coordinator delegates in a single turn to a child that takes five turns
- **THEN** the coordinator's `turns` does not include the child's five
- **AND** the coordinator may report fewer turns than the child, which is not a defect

#### Scenario: There is no own-turns field

- **WHEN** a host enumerates a task result's fields
- **THEN** an own-tokens field is present and no own-turns field exists, in any port
- **AND** `turns` is itself the own-figure, because nothing rolls up into it

#### Scenario: The per-agent figure is still available

- **WHEN** a coordinator that delegated reads `ownTokens`
- **THEN** it is that handle's own spend, excluding the delegated child's

#### Scenario: A second wake never reports less than the first

- **WHEN** a handle is woken, suspends, and is resumed
- **THEN** the token figure reported after the resume is greater than or equal to the one reported at the suspension

### Requirement: The two status vocabularies are named constants, not prose

The agent runtime's task-result status set and the client's run-result status set are two
different closed vocabularies that share the field name `status`. Every port SHALL expose both
sets as first-class named constants, and the specification SHALL document them side by side as
two distinct vocabularies rather than as prose with inline literals.

What is pinned is the **values** — the seven strings and the three strings — not the name of the
type, module or holder each port declares them on. A port SHALL be free to name that holder
whatever its ecosystem requires, including to avoid a collision with a platform type already in
scope, and SHALL NOT be brought to another port's spelling on that account.

`"timeout"` belongs to the agent runtime's wait vocabulary alone and SHALL NOT be reported as a
client run-result status in any port.

This requirement pins the **values** of the two sets. It does not pin the invariant that no third
closed vocabulary may later land on a field named `status`; that gap is real, is deliberately not
closed here, and is tracked as a follow-up conformance row.

#### Scenario: Neither public status field is renamed

- **WHEN** a host upgrades across this change
- **THEN** both `status` fields keep their names and their values, and only the constants are new

#### Scenario: Both vocabularies are reachable as values

- **WHEN** a host enumerates the status values a task result and a run result may carry
- **THEN** each set is available as named constants in that port, not only as documentation

#### Scenario: The holder's name is port-local, the values are not

- **WHEN** a port declares the seven-value set on a type named to avoid a collision with a platform type already in scope
- **THEN** it conforms, because the seven values are identical
- **AND** no other port is expected to adopt that name

#### Scenario: A wait deadline is the only source of timeout

- **WHEN** a host waits on a handle with a deadline that expires while the child keeps running
- **THEN** the task result status is `"timeout"`
- **AND** no client run result in any port ever carries `"timeout"` as its status

### Requirement: Status and limit never contradict each other

A settled result's status and its `limit` SHALL agree on every construction path: a result that
stopped at a limit SHALL name that limit, and a result that did not SHALL leave `limit` empty. A
status that says a limit was reached beside an empty `limit` — or a named `limit` on a result that
completed normally — is a contradiction inside the very field a host was given to branch on, and
SHALL NOT be reachable by any path.

Each port SHALL hold this as an **invariant** test rather than a test of the one site that was
reported, driven over at least a `done` result, a budget-`incomplete` result, a `closed` result
and a wait-deadline `timeout` result, asserting each value is a member of its own closed
vocabulary — the seven-value task-status set and the nine-value limit set.

Every result-construction site SHALL use the named constants rather than string literals, so that
a rename cannot silently desync the two fields. Where a port's type system can make the
contradiction unrepresentable — by giving the internal limit accessors a return type that admits
only vocabulary members — it SHOULD do so in preference to relying on the test.

#### Scenario: A wait deadline sets both fields

- **WHEN** a handle settles because a wait deadline expired
- **THEN** the status is `"timeout"` and the `limit` is `"timeout"`, never an empty limit beside a timeout status

#### Scenario: A normal completion names no limit

- **WHEN** a run settles as `done` without reaching any limit
- **THEN** its `limit` is empty

#### Scenario: The invariant holds across every settle path

- **WHEN** results are collected from a done path, a budget-`incomplete` path, a closed path and a wait-deadline path
- **THEN** each one either names a limit from the closed limit vocabulary or leaves it empty, consistently with its status
- **AND** each status is a member of the closed task-status vocabulary

### Requirement: The vocabularies are public; the invariant predicate is not

Both closed vocabularies SHALL be **public API** in every port, since a host branching on a status
or a limit must be able to name the value rather than hard-code its spelling — which is the whole
purpose of reporting the limit at all. The holder's name remains port-local; the values do not.

Publicness SHALL be verified from **outside the module boundary**, not from a suite that enjoys
ambient access to internals — a test project granted access to internals, a test in the same
package, or a convention rather than an enforced boundary all allow a demoted constant to keep
compiling while every real consumer breaks. A port SHALL verify by reflection, by an
out-of-module test, or by the identifier's own spelling where the language encodes visibility
there.

The predicate that asserts the status/limit invariant SHALL remain **test-only** and SHALL NOT be
exported by any port. It exists so the suite can assert a rule; publishing it would oblige every
present and future port to carry an implementation of it forever. Ports SHALL copy the predicate,
not the export. A port SHOULD guard against its quiet reintroduction — for example by asserting
that the limit vocabulary exposes no public boolean-returning member.

#### Scenario: A host names a limit instead of writing a string

- **WHEN** a host branches on which limit stopped a run
- **THEN** it can reference each value as a named public constant, without a string literal

#### Scenario: Visibility is checked from outside the module

- **WHEN** the vocabularies' publicness is verified
- **THEN** it is established by reflection, by an out-of-module test, or by the identifier's own spelling
- **AND** a constant demoted to module-internal fails that check even though the port's own suite would still compile

#### Scenario: The invariant predicate is not part of the API

- **WHEN** a port's public surface is enumerated
- **THEN** no status/limit invariant predicate appears in it, in any port
- **AND** the internal pool-name mapper is likewise absent

## MODIFIED Requirements

### Requirement: Limit stops are loud
A run that stops because of any limit (budget pool, maxTurns without a final answer)
SHALL surface `status: "incomplete"` with the limit named — never a silent `"done"`,
never a crash; partial work and the transcript are preserved. An optional
`onBudget(info) → "stop" | "extend" | "suspend"` hook MAY extend the pool or route
through §10 as an approval Request.

The limit name SHALL be carried on `TaskResult` as a structured `limit` field in **every** port,
never only as prose inside the result text. It SHALL be populated from the underlying run
result's limit, and SHALL also be populated for a budget-pool stop, where the exhausted pool's
name is already known at the stop site. No port SHALL hardcode a stop reason it did not read.

Its value SHALL name **the budget field that stopped the run**, spelled exactly as the `Budget`
type spells it, and SHALL be drawn from one closed vocabulary identical in all seven ports:

    maxTurns · maxTokens · maxToolCalls · maxWallMs · maxChildren · maxConcurrent · maxDepth
    completion · timeout

and empty when the run did not stop at a limit. A port SHALL **map its internal pool or dimension
name onto this vocabulary at the boundary**; an internal spelling is an implementation detail and
SHALL NOT leak into the field. A value that is not portable defeats the field's only purpose,
which is that a host can branch on which limit stopped a run without knowing which port produced
it. The mapper itself SHALL remain internal to each port: exporting it would publish exactly the
internal names this vocabulary exists to keep out.

Three of these values — `maxChildren`, `maxConcurrent`, `maxDepth` — describe **spawn and
admission refusals**, which in several ports surface as an error from the verb and never settle a
`TaskResult` at all. This requirement pins their **spelling wherever a port reports such a stop**;
it does **NOT** require a port to report one. A port SHALL NOT invent a settle path so that these
strings can appear, and a port that only ever refuses at the verb simply never emits them.

Where a port implements this vocabulary in a type whose values can be enumerated, every value
SHALL **also** be reachable as a named public constant, so a host can name a limit rather than
write a string literal.

#### Scenario: maxTurns without answer
- **WHEN** a run reaches its turn cap still emitting tool calls
- **THEN** the result status is `"incomplete"`, not `"done"`, and `RunResult` carries
  the limit name in an optional `limit` field (e.g. `"maxTurns"`, `"maxTokens"`) — the
  spec'd home for "which limit", since RunResult has no metadata map

#### Scenario: Task-result status vocabulary is closed
- **WHEN** a handle's run settles by any path
- **THEN** its result status is exactly one of `"done" | "pending" | "incomplete" |
  "interrupted" | "closed" | "timeout" | "error"` — the same seven strings in every
  port (trace and fixture conformance depends on this vocabulary)

#### Scenario: A completion gate is not reported as a turn cap

- **WHEN** a run stops because a completion gate refused to finish, not because it ran out of turns
- **THEN** the task result's `limit` is `"completion"`, and no port's message names a turn cap

#### Scenario: A budget-pool stop names its pool

- **WHEN** a run stops because a hierarchical budget pool is exhausted
- **THEN** the task result carries the exhausted pool's limit name, not an empty `limit`

#### Scenario: A budget stop reports the canonical string, not the port's internal name

- **WHEN** a run stops on a wall-clock budget in a port whose internal pool is named differently
- **THEN** the reported `limit` is exactly `"maxWallMs"`, the `Budget` field's own spelling
- **AND** a token-budget stop reports exactly `"maxTokens"` and a tool-call stop exactly `"maxToolCalls"`, in every port

#### Scenario: The limit vocabulary is closed

- **WHEN** the values a `limit` may carry are enumerated in each port
- **THEN** they are exactly the seven `Budget` field names plus `"completion"` and `"timeout"`, and the empty value
- **AND** no port emits a spelling outside that set

#### Scenario: A host branches without matching prose

- **WHEN** a host needs to distinguish a turn-cap stop from a completion-gate stop
- **THEN** it can do so from the `limit` field alone, in every port
