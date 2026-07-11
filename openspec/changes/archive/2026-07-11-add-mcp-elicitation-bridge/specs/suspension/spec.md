## ADDED Requirements

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
