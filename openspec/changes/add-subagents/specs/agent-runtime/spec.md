# agent-runtime — spec delta

## ADDED Requirements

### Requirement: Handle state machine pins transitions, never scheduling
A live agent SHALL be represented by a Handle with state `idle | running | suspended |
closed` and an inbox held as agent state (not a runtime/language mailbox). Transitions
SHALL be exactly: `idle → running` (wake), `running → idle` (result), `running →
suspended` (§10 pending), `running → closed` / `idle → closed` / `suspended → closed`
(close), and `suspended → running` ONLY via the Answer to the pending Request.
Scheduling, thread placement, and concurrency level are explicitly unobservable;
conformance is judged on transition traces against shared fixtures.

#### Scenario: Suspended is not idle
- **WHEN** a suspended handle receives posts or wakes that are not the Answer to its
  pending Request
- **THEN** the items buffer in the inbox, no transition occurs, and only the matching
  Answer moves the handle out of `suspended`

#### Scenario: Two resume shapes, one authority rule
- **WHEN** an inline interpreter (waitFor) answers a suspension
- **THEN** the handle traces `suspended→running` (the Run never ended)
- **WHEN** a durable pending is later resumed by an Answer routed to the runtime
- **THEN** the handle transits `suspended→idle` (Answer accepted, checkpoint restored)
  then `idle→running` (the replay wake) — the Answer remains the ONLY thing that moves
  a handle out of `suspended` in both shapes

#### Scenario: Answer routing is deepest-suspended
- **WHEN** an Answer arrives for a durable pending whose `data.path` names a relaying
  ancestor's subtree
- **THEN** the runtime routes it to the deepest suspended handle within that subtree
  (`data.path` identifies the suspended SUBTREE — each relaying level stamps its own
  path, parent-prefixed — not necessarily the leaf itself)

#### Scenario: Transition trace parity
- **WHEN** the same fixture runs against any two ports on the virtual clock
- **THEN** the sequence of state transitions per handle id is identical; fixture
  transition entries may use the full `state→state` form or the `→state` suffix form —
  conformance matches by suffix, and the two forms are equivalent

#### Scenario: The §10 append rule is scoped to the bare client
- **WHEN** a bare client run halts durably
- **THEN** the halted tool's placeholder result is appended to its transcript (§10)
- **WHEN** the SAME halt happens under an agent-runtime handle
- **THEN** the runtime's rewind-to-checkpoint governs the PERSISTED transcript (the
  placeholder never survives in the store) — the two rules compose, they do not
  conflict

### Requirement: Six host verbs with deterministic ids
The runtime SHALL expose exactly `spawn, post, wake, wait, interrupt, close` as the
host surface, plus read-only `list`/`inspect` views. `spawn` SHALL assign
parent-scoped deterministic ids (`root/coordinator.1/explore.2`) — never random ids —
and return the handle to the spawner alone (handles are capabilities: post/wake what
you hold, wait only on handles you spawned).

#### Scenario: Deterministic child ids
- **WHEN** a parent spawns two children of definition `explore`
- **THEN** their ids are `<parent-id>/explore.1` and `<parent-id>/explore.2`

#### Scenario: Wait is spawner-only
- **WHEN** a holder of a granted sibling handle attempts to wait on it
- **THEN** the runtime refuses (waiting is reserved to the spawner)

### Requirement: Two delivery rails
Solicited results (a tool call's own result) SHALL return into the live turn.
Unsolicited input (posts, timer ticks, background results, inbound events) SHALL land
in the inbox and enter ONLY as a fresh turn at idle. A wake SHALL drain the whole
inbox as one coalesced context block for one turn: timer ticks deduplicate to a single
entry with a count, and every drained item is rendered with provenance
(`from` handle path or `external`, channel); items from non-ancestor or external
senders are rendered inside an explicitly untrusted block.

#### Scenario: Batch-per-turn drain
- **WHEN** three messages and three timer ticks are posted to an idle handle and it is
  woken once
- **THEN** exactly one turn runs, receiving three message items plus one coalesced
  tick entry

#### Scenario: Nothing unsolicited enters a live turn
- **WHEN** a message is posted while the handle is running
- **THEN** the current turn's input is unchanged and the item is delivered in the next
  drain

### Requirement: Transactional drain
Inbox items SHALL be consumed only by a completed turn. If the turn is aborted
(interrupt or forced close), the items drained into it SHALL be restored to the front
of the inbox.

#### Scenario: Interrupt restores drained items
- **WHEN** a handle with one inbox item is woken and then interrupted mid-turn
- **THEN** after the abort the handle is `idle` and the inbox again contains that item

### Requirement: Interrupt aborts the turn, not the agent
`interrupt` SHALL abort the in-flight Run via the port's cancellation contract and
move the handle to `idle` with inbox intact (including restored drained items). It
SHALL NOT close the handle. Interrupting a `suspended` handle SHALL cancel its pending
Request and move it to `idle` (the operator escape hatch). The abort surfaces to
waiters as a uniform error result with an interrupted status — never an exception.

#### Scenario: Agent survives interrupt
- **WHEN** a running handle is interrupted
- **THEN** waiters receive an error result marked interrupted, the handle is `idle`,
  and a subsequent wake runs normally

### Requirement: Per-port cancellation contract
Each port SHALL document and implement a cancellation seam for the in-flight Run:
JS `AbortSignal`, Go `context` cancellation, C# `CancellationToken` (interrupt
classified by token state, not exception type), Elixir Run-process termination,
Python and Java a cooperative cancel (minimum guarantee: between attempts; stronger
transport-level abort where the HTTP stack allows). Only abort *latency* may differ
across ports; the observable outcome (idle + restored inbox + interrupted result) is
identical.

#### Scenario: Cancellation yields the same observable state everywhere
- **WHEN** the interrupt fixture runs on any port
- **THEN** the resulting transition trace and final inbox contents match the reference

### Requirement: Hierarchical budgets with live-ancestor enforcement
`Budget{maxTurns, maxTokens, maxToolCalls, maxWallMs, maxChildren, maxConcurrent,
maxDepth}` SHALL be carveable at spawn (`effective = min(own, parent remaining)`), and
enforcement SHALL walk the LIVE ancestor chain before each turn and each spawn (carve
alone misses sibling spend). Usage SHALL roll up to every ancestor (the roll-up is the
ledger). Between turn boundaries, budget reads are eventually consistent. Monetary
budgets are excluded from the library.

#### Scenario: Sibling spend exhausts the shared pool
- **WHEN** a parent pool of 100 tokens has two children that each consume 80
- **THEN** the second child's next turn is refused with an incomplete status because
  an ancestor pool is exhausted, even though the child's own carve has remainder

#### Scenario: Depth and child caps at spawn
- **WHEN** a spawn would exceed `maxDepth` or `maxChildren`
- **THEN** spawn returns a uniform error result naming the limit

### Requirement: Limit stops are loud
A run that stops because of any limit (budget pool, maxTurns without a final answer)
SHALL surface `status: "incomplete"` with the limit named — never a silent `"done"`,
never a crash; partial work and the transcript are preserved. An optional
`onBudget(info) → "stop" | "extend" | "suspend"` hook MAY extend the pool or route
through §10 as an approval Request.

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

### Requirement: Three backpressure gates, always loud
(1) The inbox SHALL be bounded; a post to a full inbox is rejected synchronously to
the sender (never silently dropped). (2) Wake admission SHALL be atomic with the verb
(admission check + slot take + state transition as one step; a dequeue transfers the
slot); at most `maxConcurrent` children of a parent run simultaneously, queued wakes
firing FIFO. (3) A global turn gate (`maxConcurrentTurns`) SHALL wrap ONLY the LLM
HTTP call — never the whole Run — and SHALL release when the acquirer dies, not only
via the acquirer's cleanup path.

#### Scenario: Full inbox rejects loudly
- **WHEN** a post arrives at an inbox at capacity
- **THEN** the sender receives an explicit inbox-full error and the inbox is unchanged

#### Scenario: Turn gate cannot deadlock the tree
- **WHEN** `maxConcurrentTurns` is 1 and a parent's turn delegates to a child
- **THEN** the child's LLM call proceeds after the parent's call completes (the gate
  is held only across HTTP calls) and the run finishes

#### Scenario: Gate slot survives a killed Run
- **WHEN** a Run holding the turn-gate slot is hard-aborted
- **THEN** the slot is released and a queued acquirer proceeds

### Requirement: Wait resolves with the next result or the last result
`wait(handle, timeout?)` SHALL resolve with the next completed result, or immediately
with the last result when the handle is already settled (idle with a recorded result,
suspended with a pending, or closed). Registration order relative to completion SHALL
NOT be observable. An expired timeout yields an explicit timeout error while the child
continues running.

#### Scenario: Wait after completion
- **WHEN** a child completes before the parent calls wait
- **THEN** wait resolves immediately with that result

### Requirement: Lifecycle callbacks and graceful shutdown
Agent definitions MAY declare `onSpawn` (once, before the first turn — the
session-start injection point) and `onClose(reason: closed | interrupted | budget |
error)` (before the final checkpoint). `close` SHALL cascade leaf-first (children
before parent), allow a running turn to finish bounded by `shutdownMs` before
escalating to abort, run `onClose`, notify waiters, and leave the handle's final state
queryable — close is not loss; posts after close are rejected loudly. Stop-all =
close(root); handle→handle blocking calls flow strictly rootward, parent→child
interaction is non-blocking, and downward traversal (close/list/resume) runs from
outside the tree.

#### Scenario: Leaf-first cascade
- **WHEN** close(root) runs over a parent → child → grandchild chain
- **THEN** onClose fires grandchild, then child, then parent, and every handle ends
  `closed`

#### Scenario: Post after close
- **WHEN** a message is posted to a closed handle
- **THEN** the sender receives an explicit closed error

### Requirement: Runtime owns cross-cutting infrastructure
The runtime SHALL own: one ConversationStore for all handles (conversation id = the
handle's deterministic id, so child transcripts genuinely survive turns and durable
resume reads real history); an injectable clock used for every timer, timeout, and
deadline (fixtures run on a virtual clock); and the handle table itself (ids, parent
links, states) so a restarted process can rebuild the tree. Wherever prose or traces
are composed from the registry, iteration SHALL be sorted by agent name.

#### Scenario: Transcript survives turns
- **WHEN** a handle runs two turns and is then inspected via its conversation id
- **THEN** the stored transcript contains both turns' messages

#### Scenario: Durable pending rewinds the stored transcript to the pre-turn checkpoint
- **WHEN** a Run returns status pending (the shipped clients append the halted tool's
  placeholder result to the persisted transcript per §10)
- **THEN** the runtime restores that handle's stored transcript to its pre-turn
  snapshot, so the resumed replay re-invokes the delegating tool and idempotency comes
  from task-key reattachment — a resumed parent that reads the placeholder as a
  resolved result and skips re-invocation is non-conformant

#### Scenario: Virtual-clock determinism
- **WHEN** a timing fixture (heartbeat, wait timeout, shutdownMs) runs on the virtual
  clock in two ports
- **THEN** the transition traces are identical
