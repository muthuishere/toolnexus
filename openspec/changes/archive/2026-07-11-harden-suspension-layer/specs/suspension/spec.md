## ADDED Requirements

### Requirement: A suspension is never counted as a tool error
When a tool call returns a suspension (a `ToolResult` whose `metadata.pending` is a `Request`, §10),
the client loop SHALL NOT treat it as a tool failure. Specifically: the `tool` observability event
emitted for that call SHALL carry `isError: false` and a distinct `pending: true` marker (rather than
`isError: true`), so error-rate metrics and circuit-breakers that key on tool errors do not count
suspensions; and the `afterTool` hook's failure path SHALL NOT run on the suspended result. The
resolved result produced after `waitFor` (or the durable-halt path) is unaffected and still flows
through `afterTool` exactly once. This SHALL hold whether the suspension originates from the tool's own
execution OR from a `beforeTool` hook short-circuit (a guard-raised pending), and SHALL be identical
across all five ports.

#### Scenario: A suspended tool call is not an error in metrics
- **WHEN** a tool returns a suspension and the loop emits the `tool` observability event for it
- **THEN** the event has `isError == false` and `pending == true`, and the error-rate counter is not
  incremented for that call

#### Scenario: afterTool does not run on the suspension, only on the resolved result
- **WHEN** a tool suspends, a `waitFor` resolves it, and the tool re-executes to a real result
- **THEN** the `afterTool` hook runs exactly once — on the resolved result — and not on the intermediate
  suspension

#### Scenario: A guard-raised pending is honored and is not an error
- **WHEN** a `beforeTool` hook returns `{ result: pending({ kind: "approval", prompt: ... }) }` so no
  underlying tool code runs
- **THEN** the loop routes the suspension to `waitFor` (or halts durably when none is set), and the
  `tool` event for that call carries `pending: true` / `isError: false` — never counted as a failure

### Requirement: Concurrent suspensions surface deterministically without loss
When more than one tool call in a single assistant turn suspends and no `waitFor` is configured, the
run SHALL halt on the **first** suspension in tool-call order (deterministically, independent of
scheduling) and surface it on `RunResult.pending`. The client SHALL NOT surface a non-deterministic
(last-to-resolve) request, and SHALL NOT append the other concurrent suspensions' placeholder results
to the conversation transcript as tool results — those tool calls re-suspend when the run is resumed.
When a `waitFor` IS configured, each concurrent suspension resolves independently inline and none is
lost. This SHALL be identical across all five ports.

#### Scenario: Two concurrent suspensions, no waitFor — first is surfaced, none corrupts the transcript
- **WHEN** two tool calls in one turn both suspend and no `waitFor` is configured
- **THEN** `RunResult.status == "pending"` and `RunResult.pending` is the request of the first tool call
  in order (not a scheduling-dependent one), and the second suspension's placeholder result is not
  recorded as a tool message

#### Scenario: Concurrent suspensions with a waitFor resolve independently
- **WHEN** two tool calls in one turn both suspend and a `waitFor` is configured that resolves each
- **THEN** both are resolved inline and the run continues with both real results — neither is dropped
