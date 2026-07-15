# resilience-policy Specification

## Purpose
TBD - created by archiving change add-resilience-policy. Update Purpose after archive.
## Requirements
### Requirement: Host-configurable retry-vs-fail classification

The client SHALL accept an optional `onError` callback (idiomatic name per port) that
classifies each LLM-call failure into one of two tiers: `retry` or `fail`. The callback
receives failure context: the error, the HTTP `status` when present, the zero-based `attempt`
number, and whether the status is in the retryable set (`429`/`500`/`502`/`503`/`504` + network
errors). When `onError` is absent, the client SHALL behave byte-identically to today: a
retryable failure within the `retries` budget is retried with exponential backoff honoring
`Retry-After`; every other failure fails. A `retry` result is always bounded by the existing
`retries` budget — the classifier cannot loop unbounded.

#### Scenario: Absent callback preserves today's behavior

- **WHEN** no `onError` is configured and the LLM returns `429` twice then `200`
- **THEN** the client retries with backoff and succeeds, identical to the pre-change behavior

#### Scenario: Absent callback fails a non-retryable status

- **WHEN** no `onError` is configured and the LLM returns `400`
- **THEN** the client fails immediately without retrying, identical to today

#### Scenario: Host forces fail on a normally-retryable status

- **WHEN** `onError` returns `fail` for a `429`
- **THEN** the client surfaces the error immediately without consuming remaining retries

#### Scenario: Host forces retry on a normally-terminal status

- **WHEN** `onError` returns `retry` for a `400`
- **THEN** the client re-issues the call, bounded by the `retries` budget, before failing

### Requirement: The classifier does not introduce a suspend tier

This capability SHALL NOT add a failure-originated suspension. Suspension (§10) remains a
user-action pause (a tool or MCP elicitation asking a human) and its contract SHALL be
unchanged by this capability — no new `Request` kind, no LLM-originated suspension, no
streaming-event change. A failure classified `fail` surfaces as an error result/exception per
the existing loop contract, never as a `Request`.

#### Scenario: A failed classification surfaces an error, not a pending

- **WHEN** `onError` returns `fail` for a `402`
- **THEN** the client surfaces the LLM error through the existing error path, and `RunResult`
  does not carry `status:"pending"` or a `Request`

### Requirement: Resilience conformance matrix

Each port SHALL carry a hermetic resilience test matrix asserting the chosen tier fires for
each failure class: `402`/`401`/`400` (fail by default; retry when the host opts in), `429`
(retry within budget by default; fail when the host opts in), persistent `500` (retries
exhausted then fail), and network-down (retry then fail), plus the absent-`onError`
default-parity case. Tests SHALL use local stubs (no live LLM), and SHALL assert bounded time
(no hang) and no process crash.

#### Scenario: Matrix runs hermetically per port

- **WHEN** a port's test suite runs
- **THEN** the resilience matrix exercises each failure class against a local stub and asserts
  the tier outcome, bounded completion, and no crash — with no network or live LLM

