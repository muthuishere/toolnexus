## ADDED Requirements

### Requirement: Retry count is caller-controlled and zero is expressible

The client SHALL allow a caller to request that a failed LLM call is **not** retried, and
SHALL make exactly one backend invocation when that is requested. The historical default
(an unspecified retry count meaning two retries, three attempts total) SHALL be unchanged
for every existing caller.

Each port expresses "no retries" in its own idiom — an explicit `0` where the port can
distinguish it from an unset field, or `-1` where the field is a bare integer whose zero
value already means the default. The spelling is a per-port alias; the observable
behaviour is identical.

#### Scenario: Zero retries makes exactly one attempt

- **WHEN** a client is configured for no retries and the backend fails with a retryable
  error such as HTTP 429
- **THEN** the backend is invoked exactly once and the error is returned to the caller

#### Scenario: The default is unchanged

- **WHEN** a client is configured without specifying a retry count and the backend fails
  with a retryable error
- **THEN** the backend is invoked three times in total (the initial attempt plus two retries)

#### Scenario: No negative or unbounded backoff

- **WHEN** a client is configured for no retries
- **THEN** no backoff delay is computed or awaited, and the retry loop terminates after the
  single attempt

#### Scenario: An in-process model does not retry by default

- **WHEN** an in-process client is created without an explicit error classifier and its
  generate function fails
- **THEN** the failure is final and the generate function is invoked exactly once, because
  there is no wire and therefore no transient failure to ride out
