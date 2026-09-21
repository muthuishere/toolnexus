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

### Requirement: The client's retry backoff is identical in every port

When the §8 client retries a failed attempt and no usable `Retry-After` header is present, every
port SHALL wait `retryBaseMs * 2^attempt + jitter` milliseconds, where `attempt` is zero-based
and `jitter` is drawn uniformly from `[0, 100)` milliseconds.

`retryBaseMs` SHALL default to `500` in every port. A usable `Retry-After` in its `delay-seconds`
form SHALL continue to win over the computed backoff, jitter included — an upstream that named a
delay is not improved by a library adding to it.

The jitter term is load-shaping, not decoration: without it a fleet of hosts that failed against
the same upstream at the same moment retries against it at the same moment, and the retry
converts one spike into several. A port that omits it therefore differs in behaviour under the
exact conditions retries exist for, not merely in timing.

The classifier path (`§8B`) deliberately adds **no** jitter and is unaffected; it shares the base,
the default and the `base * 2^attempt` shape only.

#### Scenario: An unset base backs off half a second

- **WHEN** a client with no configured retry backoff base retries its first failed attempt
- **THEN** it waits at least 500 ms before the second attempt

#### Scenario: The wait varies between identical clients

- **WHEN** several identically configured clients each retry one failed attempt
- **THEN** their waits are not all identical, because each drew its own jitter
- **AND** no wait is shorter than the un-jittered backoff, because jitter is additive

#### Scenario: Retry-After still wins

- **WHEN** a retryable response carries a usable `Retry-After` header
- **THEN** the client waits exactly that delay, with no backoff and no jitter added
