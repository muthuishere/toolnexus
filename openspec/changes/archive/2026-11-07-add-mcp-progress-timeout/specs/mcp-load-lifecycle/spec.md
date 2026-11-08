## ADDED Requirements

### Requirement: MCP tool calls are bounded by an idle timeout, not a total deadline

Every `tools/call` SHALL carry a client-generated progress token, permitting the server to emit
`notifications/progress` for that call. The per-server `timeout` SHALL bound the time since the
**last** progress notification for the in-flight call, rather than the total duration of the call.
Each matching notification SHALL reset the remaining budget to the full `timeout`.

A notification SHALL reset the budget only when its `progressToken` matches the in-flight call's
token; notifications carrying an unknown or non-matching token SHALL be ignored for timeout purposes.

A call for which the server emits no progress SHALL behave exactly as before this change: it fails
after `timeout` with the port's existing MCP timeout error, and the resulting `ToolResult` shape is
unchanged.

This requirement governs `tools/call` only. The load phases (connect, `initialize`, `tools/list`)
keep the per-phase budget defined by "MCP load honors the caller's cancellation and deadline" and are
unaffected.

Caller cancellation SHALL continue to abort an in-flight call promptly regardless of progress
activity, so a server that emits progress indefinitely remains interruptible via the caller's
cancellation/deadline and the client's run-level timeout.

The mechanism is deliberately unspecified — ports differ (SDK progress hook, SDK progress callback,
explicit token plus notification handler, or an in-house timer reset). Conformance is judged only on
the observable outcomes below, verified against the shared progress fixture.

#### Scenario: A reporting server outlives the timeout

- **WHEN** a tool call runs for longer than the per-server `timeout` while the server emits progress notifications at an interval shorter than `timeout`
- **THEN** the call completes successfully and returns the server's result, instead of failing with a timeout

#### Scenario: A silent server still times out

- **WHEN** a tool call runs against a server that emits no progress and does not respond
- **THEN** the call fails after `timeout` with the port's MCP timeout error, identically to before this change

#### Scenario: Progress stops mid-call

- **WHEN** a server emits progress for a period and then goes silent while the call is still in flight
- **THEN** the call fails after `timeout` measured from the last progress notification, not from the start of the call

#### Scenario: The server receives a progress token

- **WHEN** any `tools/call` is issued
- **THEN** the request carries a progress token, and a server that inspects the request observes it as present

#### Scenario: An unrelated token does not extend the call

- **WHEN** a progress notification arrives carrying a token that does not match the in-flight call
- **THEN** the in-flight call's remaining budget is unchanged and it still times out after `timeout` of silence

#### Scenario: Cancellation still wins over progress

- **WHEN** the caller cancels (via context/signal) a call against a server that is actively emitting progress
- **THEN** the call aborts promptly with the cancellation error rather than continuing until the server finishes
