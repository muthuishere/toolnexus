# subagents — spec delta

## ADDED Requirements

### Requirement: An Agent is a Tool
An Agent SHALL be defined as (identity/system prompt × a filtered toolkit view × the
shipped client loop) and SHALL be invocable as a uniform `Tool`: name, `does`
description, a prompt-taking input schema, and an execute that runs the agent's loop
and returns only its final text plus metadata `{agent, turns, totalTokens}`. The
`asTool()` bridge SHALL make any agent usable in the classic API's `extraTools`;
serving an agent reuses §7B unchanged. The classic toolkit/client API is untouched by
this capability.

#### Scenario: Agent inside the classic API
- **WHEN** an agent's tool form is passed as an extraTool to a classic client run and
  the model calls it
- **THEN** the agent's own loop executes in isolation and the classic run receives one
  tool result carrying the agent's final text and metadata

### Requirement: The task builtin delegates with context isolation
The model surface SHALL be a `task` tool (input: `agent`, `prompt`) composed from
spawn→wake→wait→close. The child runs on a fresh transcript; only its final text
returns as the tool output; the parent's transcript gains exactly one tool message per
task call regardless of the child's tool usage; child usage rolls up into the parent's
usage. Parallel task calls in one turn execute concurrently (§8 parallel tool calls).
Team tools beyond `task` (spawn/send/wait as model tools) are opt-in per agent
definition and default off.

#### Scenario: Fan-out with isolation
- **WHEN** a parent's turn emits two task calls that each perform multiple tool calls
- **THEN** both children run concurrently, the parent transcript gains exactly two
  tool messages, and parent usage includes both children's tokens

#### Scenario: Unknown agent
- **WHEN** task names an agent not in the caller's team scope
- **THEN** the result is a uniform error listing the available team agent names

### Requirement: Team scoping bounds delegation
An agent definition MAY declare a team (list of agent definitions). The task tool's
description SHALL advertise only the team's agents (sorted by name, composed from
their `does` descriptions), and task calls outside the team SHALL be refused. Children
do not receive the task tool unless their own definition declares a team (recursion is
opt-in, never default). A registry SHALL be the transitive closure of the entry
agent's team graph — unreachable agents are not present.

#### Scenario: Registry is the reachable graph
- **WHEN** an entry agent with team [explore] is prepared while an unrelated agent
  definition exists in scope
- **THEN** the runtime registry contains exactly the entry agent and explore

### Requirement: Suspension escalates by nearest interpreter
A suspending child agent SHALL present to its parent exactly as a suspending tool
(§10 unchanged — no new pending type): if an ancestor (nearest first) holds waitFor
authority, the child suspends and that ancestor's waitFor resolves it inline; with no
interpreter anywhere the pending propagates hop-by-hop (each level's task result
carries the child's Request) until the root run returns `status:"pending"`. Every
level parked this way consumes zero tokens. Level-skipping is prohibited — each
intermediate agent's authority applies in order.

#### Scenario: Parent authority answers the child
- **WHEN** a child's tool suspends with an approval Request and the parent's
  definition holds waitFor
- **THEN** the child transitions suspended→running on the parent's Answer and the
  parent's transcript shows only the task result

#### Scenario: No interpreter goes durable
- **WHEN** no ancestor holds waitFor
- **THEN** the root run returns pending carrying the leaf's Request, and both levels
  rest suspended

### Requirement: Durable resume reattaches by task key
On resume of a durable pending, the Answer SHALL route to the deepest suspended
handle, which resumes from its checkpoint (prior turns and usage preserved — never a
restart). The cascade upward re-runs each parent; a re-invoked task call SHALL
reattach to the existing child for the same (agent, prompt) task key — returning its
recorded result if settled, its pending if still suspended, or awaiting it if running
— and SHALL NOT spawn a duplicate child. (The §10 transcript is not guaranteed to
carry the halted tool's result across ports; reattachment is therefore the required
idempotency mechanism, not transcript inspection or a completion cache.)

#### Scenario: Cascade without re-execution
- **WHEN** the resume Answer completes the leaf and the parent's re-run re-invokes the
  same task
- **THEN** the task reattaches to the finished child, the child's usage continues from
  its checkpoint (grows, never resets), and no second child id appears in the trace

### Requirement: Failures cross handle boundaries as results
A failed child Run (retries exhausted, abort, crash) SHALL surface to its waiter as a
uniform error result — never an exception across the handle boundary. Mechanical
retry/backoff (§8) runs at the level where the failure occurred; what escalates is the
result, for the parent's model to judge. The root alone may throw to the host.

#### Scenario: Child failure is a tool result
- **WHEN** a child exhausts its LLM retries during a task
- **THEN** the parent's turn continues with an isError tool result describing the
  failure and the parent model chooses the next step

### Requirement: The agent() surface
Ports SHALL ship an `agent(name, spec)` constructor (idiomatic per language) with
spec fields: `does` (required routing description), `uses` (toolkit view), `soul`
(inline) or a soul file path, `team`, `budget`, `model` (default: inherit), optional
`waitFor`, `onSpawn`, `onClose`. `run(prompt)` executes one-shot against a runtime;
the soul SHALL reach the child's system prompt.

#### Scenario: Twelve-line coding agent
- **WHEN** an agent with a soul file and a one-agent team is run with a prompt whose
  answer requires delegation
- **THEN** the run completes with the team member's contribution in the final text and
  the soul content present in the child's system prompt
