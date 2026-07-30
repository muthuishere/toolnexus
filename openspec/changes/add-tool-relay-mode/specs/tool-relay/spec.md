## ADDED Requirements

### Requirement: A relay tool is declaration-only and is never executed host-side
The toolkit SHALL provide a declaration-only tool constructor (`RelayTool(name,
description, schema)` — idiomatic naming per port) that produces a normal `Tool`. When
the model calls a relay tool, the client loop SHALL NOT run any host-side work for it;
it SHALL surface the call to the host as a §10 suspension whose `Request` carries the
call structurally, and SHALL feed the host's supplied output back to the model as that
call's `tool_result`. A relay tool SHALL appear in every format adapter
(OpenAI/Anthropic/Gemini) exactly as any other tool's declaration does, so no adapter
change is required. This SHALL be identical across all six ports, on both the streaming
and non-streaming loops.

#### Scenario: The model's relay call reaches the host and nothing runs host-side
- **WHEN** a toolkit declares a relay tool `lookup` and the model emits a call to it
- **THEN** the host receives a `Request` with `kind == "tool_call"` carrying the call's
  id, name and input, and no host-side handler for `lookup` is invoked

#### Scenario: The host's output becomes the tool result the model sees
- **WHEN** the host resolves a relay suspension with an `Answer` whose `data.output` is
  the executed tool's output
- **THEN** the loop feeds that output back as the `tool_result` for the original tool-call
  id, the model continues, and the run completes with `status == "done"`

#### Scenario: A relay tool is declared to the provider like any other tool
- **WHEN** a toolkit containing a relay tool is converted with the OpenAI, Anthropic and
  Gemini adapters
- **THEN** the relay tool's name, description and input schema appear in each provider's
  native declaration shape, indistinguishable from an executing tool's declaration

#### Scenario: Relay works on the Anthropic-native loop
- **WHEN** a relay call is resolved on the Anthropic-style loop
- **THEN** the emitted `tool_result` block references the original `tool_use` id and
  carries the host's output

#### Scenario: Relay works on the streaming loop
- **WHEN** a relay call is made during a streamed run
- **THEN** the stream emits the `pending` event carrying the relay `Request` before the
  host is asked to resolve it, and the resolved output becomes the call's `tool_result`

### Requirement: A relayed tool failure reaches the model as a normal error result
The host SHALL be able to report that the relayed tool failed at the caller, and the
loop SHALL feed that back as an error `tool_result` so the model can recover. A relayed
failure SHALL NOT abort the run.

#### Scenario: The caller's tool failed
- **WHEN** the host resolves a relay suspension with `ok == true` and an error flag on
  `data` alongside the failure output
- **THEN** the loop feeds back an error `tool_result` carrying that output, the run
  continues, and the run completes with `status == "done"`

#### Scenario: The caller declines to execute the relayed call
- **WHEN** the host resolves a relay suspension with `ok == false`
- **THEN** the loop feeds back an error `tool_result`, the run does not fail with an
  error, and the model decides what to do next

### Requirement: A relay tool name colliding with a builtin is rejected
Toolkit construction SHALL fail when a relay tool's exposed name collides with a
built-in tool's name, regardless of whether builtins are currently enabled. This
prevents a future change to the builtin set from turning a declaration-only tool into a
host-executed one.

#### Scenario: Collision with a builtin name is rejected at construction
- **WHEN** a toolkit is constructed with a relay tool whose name equals a built-in tool
  name
- **THEN** construction fails with an error naming the collision, and no toolkit is
  returned

#### Scenario: Collision is rejected even when builtins are disabled
- **WHEN** the same toolkit is constructed with builtins turned off
- **THEN** construction still fails, because the guard does not depend on the current
  builtins setting

### Requirement: Declaring a relay tool does not perturb existing behavior
When no relay tool is declared, every port's observable behavior SHALL be byte-identical
to before this change. When a relay tool is declared but never called by the model, the
run's text, status, turn count and tool-call list SHALL be identical to the same run
without it. Relay tools SHALL coexist with executing tools in the same assistant turn
without preventing the executing tools from running.

#### Scenario: An uncalled relay tool is inert
- **WHEN** the same prompt is run against a toolkit with a declared-but-uncalled relay
  tool and against an otherwise identical toolkit without it
- **THEN** both runs produce the same text, the same status, the same turn count, and
  zero tool calls

#### Scenario: A real tool in the same turn as a relay call still executes
- **WHEN** one assistant turn contains a call to an executing tool and a call to a relay
  tool
- **THEN** the executing tool's handler runs, whether or not the host is configured to
  resolve suspensions inline

#### Scenario: A relay tool may be called again in a later turn
- **WHEN** the model calls the same relay tool in three successive turns and the host
  resolves each one
- **THEN** all three calls are surfaced and resolved, and none is rejected as an
  unresolved repeat
