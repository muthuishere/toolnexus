## ADDED Requirements

### Requirement: Governance option is opt-in and zero-cost when unset
`Toolkit.Options` SHALL accept an optional `governance` option (idiomatic name per language:
e.g. `governance` in JS/Python, `Governance` in Go/Java/C#) carrying an optional `shield`
callback and an optional `log` sink. When `governance` is omitted, `Toolkit.execute()` SHALL
behave identically to a toolkit constructed without this option (no extra allocation, no file
I/O, no timing overhead beyond a single nil/None check).

#### Scenario: No governance option set
- **WHEN** a `Toolkit` is created without a `governance` option and `execute()` is called
- **THEN** the call runs exactly as today — no shield check, no log entry, no receipt data

### Requirement: Shield-check can deny a call before it runs
When a `shield` callback is configured, `Toolkit.execute()` SHALL invoke it synchronously before
running the underlying tool, passing the call's name, source (`mcp`|`native`|`http`|`builtin`|
`a2a`), args, and context. The callback SHALL return a verdict of `{allow: bool, reason?:
string, guaranteed: bool}`. When `allow` is `false`, `execute()` SHALL NOT invoke the underlying
tool and SHALL instead return a `ToolResult` with `isError: true` and the shield's `reason` in
the output text.

#### Scenario: Shield denies a call
- **WHEN** a configured `shield` callback returns `{allow: false, reason: "violates constraint
  X", guaranteed: true}` for a given tool call
- **THEN** `execute()` returns an error `ToolResult` containing "violates constraint X" and the
  underlying tool implementation is never invoked

#### Scenario: Shield allows a call
- **WHEN** a configured `shield` callback returns `{allow: true, guaranteed: true}`
- **THEN** `execute()` proceeds to run the underlying tool exactly as it would with no shield
  configured, and the tool's real result is returned

### Requirement: Every execute() call is appended to a hash-chained provenance log
Every `execute()` call SHALL append exactly one event to the log sink when a `log` sink is
configured — whether the call was allowed, denied by the shield, or erroring in the underlying
tool. Each event SHALL contain: `ts` (ISO-8601 timestamp), `tool` (name), `source`, `argsDigest` (a
sha256 hex digest of the canonicalized args — never the raw args), `verdict` (`allow` or
`deny`), `deniedReason` (present only when `verdict` is `deny`), `durationMs`, `prev` (the
previous event's `hash`, or 64 zero characters for the first event), and `hash` = sha256 of
`prev` concatenated with the canonical JSON of the event object excluding `hash`. Canonical JSON
SHALL use a fixed key order (as listed above) with no extra whitespace, identically across all
5 language ports, so the same input event produces the same `hash` in every port.

#### Scenario: Successful call is logged
- **WHEN** `execute()` runs a tool to completion with a `log` sink configured
- **THEN** exactly one event is appended with `verdict: "allow"` and `hash` computed from
  `prev` + the canonical event JSON

#### Scenario: Denied call is logged
- **WHEN** a `shield` callback denies a call with a `log` sink configured
- **THEN** exactly one event is appended with `verdict: "deny"` and a non-empty `deniedReason`

#### Scenario: Chain integrity is verifiable
- **WHEN** a log of N events is recomputed by replaying `prev`/`hash` from the first event
- **THEN** every recomputed `hash` matches the stored `hash`, and any single mutated field in
  any event causes a mismatch at or after that event

### Requirement: A receipt is a verifiable rollup of a log range, shaped for Autonomy Receipt interop
Toolnexus SHALL expose a receipt-rollup function that takes a log (or log range) and an
`operator` string and produces a receipt object: `{schema: "toolnexus-receipt/v0", operator,
period: {from, to}, totals: {calls, allowed, denied, distinct_tools}, integrity: {algo:
"sha256-chain", root, provenance, verify}, events: [...]}`, where `root` is the `hash` of the
last event in the range and `events` is the same event list described above. This shape SHALL
be a structural superset-compatible sibling of the `autonomy-receipt/v0-spike` schema (same
`integrity` shape and chained-event pattern) so a downstream consumer familiar with one can
read the other's integrity section without translation.

#### Scenario: Rollup over an empty range
- **WHEN** a receipt is requested for a log range with zero events
- **THEN** `totals` are all zero and `integrity.root` is the 64-zero-character sentinel (no
  events to anchor to)

#### Scenario: Rollup matches manual chain verification
- **WHEN** a receipt is produced for a non-empty range
- **THEN** recomputing the hash chain from `events[]` alone (without any other log access)
  reproduces `integrity.root` exactly
