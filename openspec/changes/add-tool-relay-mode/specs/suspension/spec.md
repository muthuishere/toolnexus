## ADDED Requirements

### Requirement: Request carries relayed tool calls under kind tool_call
The `Request` wire shape SHALL reserve `kind: "tool_call"` for a relayed
(declaration-only) tool call, and SHALL carry the relayed calls under `data.calls` — an
array whose entries each carry the provider's tool-call `id`, the tool `name`, and the
parsed `input`. When more than one relay tool is called in a single assistant turn, the
single surfaced suspension SHALL carry **all** of that turn's relay calls in `data.calls`,
in tool-call order. This preserves the existing first-in-order halt rule unchanged while
matching the array shape a caller must fill. The keys SHALL be byte-identical across all
six ports, like the rest of the §10 wire shape.

#### Scenario: A single relayed call is carried structurally
- **WHEN** the model calls one relay tool and the suspension is surfaced
- **THEN** the `Request` has `kind == "tool_call"` and `data.calls` is a one-entry array
  carrying that call's id, name and input

#### Scenario: Three relayed calls in one turn all ride the one surfaced request
- **WHEN** the model calls three relay tools in one assistant turn and no `waitFor` is
  configured
- **THEN** the run halts on the first suspension in tool-call order as before, and its
  `Request.data.calls` carries all three calls in tool-call order — none is lost

#### Scenario: Relay call ordering is deterministic
- **WHEN** the same three-relay-call turn is run repeatedly
- **THEN** `data.calls` is in tool-call order every time, independent of which handler
  completed first

### Requirement: A durable host can resume a suspended run by supplying an Answer
The client SHALL provide an answer-carrying resume entry point (`RunWithAnswer` /
`Ask(..., answer)` — idiomatic naming per port) so a host that did not configure a
`waitFor` can resume a run it previously received as `status: "pending"`, possibly in a
different process. The resume SHALL apply the supplied `Answer` to the suspension it
echoes by id, and SHALL fill **every** `tool_result` slot left outstanding on the halted
assistant turn — not only the halted call's — so the resulting transcript has one
`tool_result` per `tool_use` and is replayable to the provider. Resuming with an `Answer`
whose id matches no outstanding suspension SHALL fail with an error rather than silently
continuing. This SHALL be identical across all six ports, on both the streaming and
non-streaming loops.

#### Scenario: A durable host resumes a single relayed call
- **WHEN** a host receives `status == "pending"` with a relay `Request`, executes the
  call itself, and calls the resume entry point with a matching `Answer`
- **THEN** the run continues from the halt, the host's output is the `tool_result` for
  that call, and the run completes

#### Scenario: Resume fills every outstanding tool_result slot
- **WHEN** a turn had three relay calls, halted durably, and the host resumes with the
  outputs for all three
- **THEN** the resumed transcript carries one `tool_result` per `tool_use` of that turn,
  and the provider is sent a balanced, replayable transcript

#### Scenario: Resuming across a process boundary works
- **WHEN** the pending `Request` and the transcript are persisted, the process restarts,
  and a new client resumes with the `Answer` and the stored transcript
- **THEN** the run continues correctly, because `Request` and `Answer` are plain
  serializable data

#### Scenario: An Answer that matches no outstanding suspension is rejected
- **WHEN** the resume entry point is called with an `Answer` whose id does not echo any
  outstanding suspension
- **THEN** it fails with an error and does not start a run

### Requirement: A relay suspension inherits the not-a-tool-error rule
A relay suspension SHALL be treated exactly as any other §10 suspension for error
accounting: the `tool` observability event for the suspended relay call SHALL carry
`isError: false` and `pending: true`, and the `afterTool` hook's failure path SHALL NOT
run on the suspension. Relaying is normal operation for a translating host and SHALL NOT
move error-rate metrics or trip circuit-breakers. There SHALL be no second suspension
mechanism introduced for relay.

#### Scenario: A relayed call is not counted as a tool error
- **WHEN** a relay tool suspends and the loop emits the `tool` observability event
- **THEN** the event carries `isError == false` and `pending == true`, and the error-rate
  counter is not incremented

#### Scenario: A resolved relay call is not an error
- **WHEN** a relay suspension is resolved with the host's successful output
- **THEN** the recorded tool call is not marked as an error, and `afterTool` runs exactly
  once on the resolved result
