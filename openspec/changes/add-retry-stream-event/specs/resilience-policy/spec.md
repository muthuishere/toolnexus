# Delta for resilience-policy

## ADDED Requirements

### Requirement: Streaming retry event

When `stream()` is about to retry a failed LLM request, the client SHALL emit, before waiting,
`{type: "retry", attempt, max, delayMs, status?, reason}`: `attempt` 1-based, `max` = `retries`,
`delayMs` = the exact wait, `status` only for HTTP failures, `reason` = `HTTP <status>` or
`network`. It SHALL NOT emit it for an unretried failure, an abort, or from `run`/`ask`.

#### Scenario: A 429 with Retry-After streams a retry event

- **WHEN** a streaming request receives `429` with `Retry-After: 0` and `retries` is 2, and the retry succeeds
- **THEN** the stream yields `{type:"retry", attempt:1, max:2, delayMs:0, status:429, reason:"HTTP 429"}` before any text of the answer, followed by the answer and `done`

#### Scenario: A network error streams a retry event without status

- **WHEN** a streaming request fails with a network error and is retried
- **THEN** the retry event has `reason:"network"` and no `status`

#### Scenario: A terminal failure streams no retry event

- **WHEN** a streaming request receives `400` and no `x-should-retry` header
- **THEN** no retry event is emitted and the stream surfaces the error

#### Scenario: run is unchanged

- **WHEN** `run` retries a `429` and succeeds
- **THEN** its `RunResult` is identical to before this change and no event is emitted

### Requirement: x-should-retry overrides the default classification

A non-ok LLM response carrying `x-should-retry` (case-insensitive name) whose trimmed value is
`true` or `false` (case-insensitive) SHALL set `retryable` to that value before `onError` runs.
Any other value or no header SHALL leave the default classification unchanged. `onError` SHALL
still decide, and `retries` SHALL still bound every retry.

#### Scenario: true makes a 400 retryable

- **WHEN** the first response is `400` with `x-should-retry: true` and the second succeeds
- **THEN** the run succeeds after one retry

#### Scenario: false makes a 503 terminal

- **WHEN** the first response is `503` with `X-Should-Retry: False`
- **THEN** the run fails with the `503` after a single attempt

#### Scenario: onError still has the final say

- **WHEN** a response carries `x-should-retry: true` and `onError` returns `fail`
- **THEN** the run fails without retrying, and `onError` saw `retryable: true`
