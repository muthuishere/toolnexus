# Delta for resilience-policy

## ADDED Requirements

### Requirement: Uniform `Retry-After` parsing

When a retryable LLM response carries a `Retry-After` header, the client SHALL honour it only when
the trimmed header value consists solely of ASCII digits (`1*DIGIT`, per RFC 9110 §10.2.3) and the
resulting integer is in the range `0 … 2147483647` (a signed 32-bit second count, ~68 years —
the widest range every port represents exactly). In that case the delay before the next
attempt SHALL be exactly that many seconds, including zero.

For every other header value — a fractional value, a signed value, the HTTP-date form, an
out-of-range value, an empty value, or any other unparseable text — the client SHALL fall back to
its exponential backoff with jitter, and SHALL NOT raise, sleep for a negative duration, or retry
without delay as a result of the header.

Header lookup SHALL be case-insensitive. An honoured `Retry-After` delay SHALL remain subject to
the existing `retries` budget and to the whole-run deadline where the port enforces one.

#### Scenario: An integer value is honoured exactly

- **WHEN** a retryable response carries `Retry-After: 2`
- **THEN** the client waits two seconds before the next attempt, rather than its backoff delay

#### Scenario: A zero value retries immediately

- **WHEN** a retryable response carries `Retry-After: 0`
- **THEN** the client retries without additional delay, and does not fall back to backoff

#### Scenario: A fractional value falls back to backoff

- **WHEN** a retryable response carries `Retry-After: 0.5` or `Retry-After: 5.9`
- **THEN** the client ignores the header and waits its exponential backoff delay, in every port —
  it neither honours the fraction nor truncates it to a whole number of seconds

#### Scenario: A negative value cannot cause an immediate or negative wait

- **WHEN** a retryable response carries `Retry-After: -5`
- **THEN** the client ignores the header and waits its exponential backoff delay, and no port
  retries immediately or attempts to sleep for a negative duration

#### Scenario: An out-of-range value cannot throw or stall the run

- **WHEN** a retryable response carries a digit string above `2147483647`
- **THEN** the client ignores the header and waits its exponential backoff delay, and the retry
  path raises no error

#### Scenario: The HTTP-date form is uniformly unsupported

- **WHEN** a retryable response carries `Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`
- **THEN** the client ignores the header and waits its exponential backoff delay, in every port

#### Scenario: Absent header is unchanged

- **WHEN** a retryable response carries no `Retry-After` header
- **THEN** the client waits its exponential backoff delay with jitter, exactly as before this change
