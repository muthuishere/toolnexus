# Delta for resilience-policy

## ADDED Requirements

### Requirement: Host-configurable LLM-failure classification

The client SHALL accept an optional `onError` callback (idiomatic name per port) that
classifies each LLM-call failure into one of three tiers: `retry`, `fail`, or `suspend`.
The callback receives failure context: the error, the HTTP `status` when present, the
zero-based `attempt` number, and whether the status is in the retryable set
(`429`/`500`/`502`/`503`/`504` + network errors). When `onError` is absent, the client SHALL
behave byte-identically to today: a retryable failure within the `retries` budget is retried
with exponential backoff honoring `Retry-After`; every other failure fails.

#### Scenario: Absent callback preserves today's behavior

- **WHEN** no `onError` is configured and the LLM returns `429` twice then `200`
- **THEN** the client retries with backoff and succeeds, identical to the pre-change behavior

#### Scenario: Host forces fail on a normally-retryable status

- **WHEN** `onError` returns `fail` for a `429`
- **THEN** the client surfaces the error immediately without consuming remaining retries

#### Scenario: Host forces retry on a normally-terminal status

- **WHEN** `onError` returns `retry` for a `500`-class error the default would still retry, or
  for a `400` it would not
- **THEN** the client re-issues the call, bounded by the `retries` budget, before failing

### Requirement: The suspend tier routes an LLM failure through §10 suspension

When `onError` returns `suspend` for a failure, the client SHALL emit a §10 `Request` with
`kind: "error"`, a human-readable `prompt`, and `data` carrying `{ status?, attempt, retryable }`.
With a `waitFor` host slot the client SHALL call `waitFor(request)`; on `answer.ok == true` it
SHALL re-issue the failed LLM request and resume the loop, and on `answer.ok == false` it SHALL
surface the original error. Without a `waitFor` slot, `run` SHALL NOT hang — it SHALL return
`{ status: "pending", pending: request }`, making the failure durably resumable via the
`ConversationStore` exactly as a tool suspension is.

#### Scenario: Out-of-credits suspends and resumes on retry

- **WHEN** the LLM returns `402`, `onError` returns `suspend`, and a `waitFor` returns
  `Answer{ ok: true }` (credits topped up)
- **THEN** the client re-issues the LLM request and the run completes normally

#### Scenario: Suspend without waitFor returns pending, does not hang

- **WHEN** the LLM returns `402`, `onError` returns `suspend`, and no `waitFor` is configured
- **THEN** `run` returns `{ status: "pending", pending: <Request kind:"error"> }` promptly, and
  the transcript in the store is sufficient to resume later

#### Scenario: Declined suspension surfaces the original error

- **WHEN** a suspended failure's `waitFor` returns `Answer{ ok: false }`
- **THEN** the client surfaces the original LLM error (not a generic one), never a hang

### Requirement: Suspension may originate from the LLM call

The §10 suspension contract SHALL permit a suspension to originate from the LLM call itself,
not only from a tool result. The resume action for an LLM-originated suspension SHALL be
re-issuing the failed LLM request. `RunResult.status` values, the `Request`/`Answer` wire
shapes, and the streaming `{ type: "pending", request }` event SHALL be unchanged; only the
origin and resume action differ.

#### Scenario: Streaming emits pending for an LLM-originated suspension

- **WHEN** a streamed run hits a `suspend`-classified LLM failure with no `waitFor`
- **THEN** the stream emits `{ type: "pending", request }` with `request.kind == "error"` and
  ends, matching the tool-suspension streaming shape

### Requirement: Resilience conformance matrix

Each port SHALL carry a resilience test matrix asserting the chosen tier fires for each failure
class: `402`/`401` (suspend when the host opts in, else fail), `429` (retry within budget, then
the host's tier), persistent `500` (retries exhausted, then the host's tier), and network-down
(retry, then the host's tier), plus the absent-`onError` default-parity case. The tests SHALL be
hermetic (local stubs, no live LLM) and SHALL assert bounded time (no hang) and no process crash.

#### Scenario: Matrix runs hermetically per port

- **WHEN** a port's test suite runs
- **THEN** the resilience matrix exercises each failure class against a local stub and asserts
  the tier outcome, bounded completion, and no crash — with no network or live LLM
