# suspension Specification

## Purpose
TBD - created by archiving change harden-suspension-layer. Update Purpose after archive.
## Requirements
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

### Requirement: Streaming loops honor the first-in-order suspension halt
The streaming run path (`stream()`) SHALL apply the same concurrent-suspension rule as the
non-streaming path: when more than one tool call in a turn suspends and no `waitFor` is configured,
the stream SHALL halt on the **first** suspension in tool-call order — emitting the `pending` stream
event and a `done` event whose `RunResult.status == "pending"` carries that first request — and SHALL
NOT record the later concurrent suspensions' placeholder results in the transcript. The streaming and
non-streaming paths SHALL be consistent, and identical across all five ports.

#### Scenario: Two concurrent suspensions in a streamed turn halt on the first
- **WHEN** a streamed turn issues two tool calls that both suspend and no `waitFor` is configured
- **THEN** the stream emits a `done` event with `RunResult.status == "pending"` whose `pending` is the
  first tool call's request, and the second suspension's placeholder result is not in the transcript

### Requirement: MCP server elicitation is bridged onto the one waitFor
When toolnexus is connected to a remote MCP server (MCP-outbound, §2) and a host `waitFor` is
configured, the MCP client SHALL advertise the `elicitation` capability and register an elicitation
handler. On receiving an `elicitation/create` request during a `tools/call`, the handler SHALL
synthesize a §10 `Request` — `prompt` from the elicitation `message`, `kind:"input"` for form mode or
`kind:"authorization"` (with `url`) for URL mode, and the elicitation `requestedSchema` carried on the
Request (`data.schema`) — call the same host `waitFor(request)`, and translate the resulting `Answer`
back into an MCP `ElicitResult` (`ok=true → accept` with `answer.data` as `content`; `ok=false →
decline` or `cancel`). The handler SHALL satisfy the reverse-request **inline** (it does NOT re-execute
the tool; the in-flight `tools/call` resumes when the handler returns). When no `waitFor` is configured,
the client SHALL NOT advertise the `elicitation` capability (it degrades cleanly rather than promising
elicitation it cannot satisfy). Credentials SHALL NOT be collected via form/`input` mode — the security
pin for `kind:"question"`/`"input"` applies (credentials use `kind:"authorization"` + `url`). This
SHALL be identical across all five ports (form mode; URL mode where the port's SDK supports it).

#### Scenario: A server elicits during a tool call and the human answers
- **WHEN** a remote MCP server sends `elicitation/create` (form mode) while executing a tool toolnexus
  called, and a `waitFor` is configured
- **THEN** the bridge calls `waitFor` with a `kind:"input"` Request carrying the message and schema, and
  on `Answer{ok:true, data}` returns an `ElicitResult{action:"accept", content:data}` to the server, which
  completes the tool call

#### Scenario: Declined elicitation degrades gracefully
- **WHEN** the configured `waitFor` returns `Answer{ok:false}` for an elicitation request
- **THEN** the bridge returns `ElicitResult{action:"decline"}` (or `"cancel"` per the pinned default) and
  the remote tool call proceeds per the server's own handling — toolnexus does not crash the session

#### Scenario: No waitFor means the capability is not advertised
- **WHEN** toolnexus connects to MCP servers with no `waitFor` configured
- **THEN** the client does not advertise `capabilities.elicitation`, and a spec-compliant server does not
  send `elicitation/create`

### Requirement: Answer carries an optional decline/cancel reason
The `Answer` type SHALL gain an optional `reason` field (`"declined" | "cancelled" | "expired"`),
populated only when `ok == false`, to distinguish an explicit refusal from a dismissal/timeout. The §10
loop rule SHALL remain unchanged — it branches only on `ok` — so `reason` is advisory and non-breaking.
The MCP elicitation bridge SHALL use it to map `ok=false` onto MCP's `decline` vs `cancel`
(`reason:"declined" → decline`, otherwise `→ cancel`). `Answer` keys remain byte-identical across ports.

#### Scenario: A declined answer is distinguishable from a cancelled one
- **WHEN** a host `waitFor` returns `Answer{ok:false, reason:"declined"}`
- **THEN** consumers (including the MCP elicitation bridge) can distinguish it from
  `Answer{ok:false, reason:"cancelled"}`, while any code that only checks `ok` behaves exactly as before

### Requirement: Request may carry an optional input schema
The `Request` type SHALL support an optional restricted JSON-Schema (flat primitive properties only —
string/number/boolean/enum, matching MCP `requestedSchema`) carried as `data.schema`, describing the
shape of the expected `answer.data`. A host `waitFor` or a durable renderer MAY use it to render and
validate input generically; the MCP elicitation bridge SHALL populate it from the server's
`requestedSchema` and MAY validate `answer.data` against it before replying `accept` (downgrading to
`decline`/`cancel` on mismatch). Its absence SHALL be fully backward-compatible.

#### Scenario: A generic host renders input from the schema
- **WHEN** a `Request` carries `data.schema` describing the expected fields
- **THEN** a host `waitFor` can render a form and validate the entered `answer.data` against it before
  returning, and a Request without `data.schema` behaves exactly as today

