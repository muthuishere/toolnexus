## ADDED Requirements

### Requirement: Every port arms a run-level deadline, and a timeout is never retried

A configured run timeout SHALL bound the whole run — every turn, every tool call and every
retry — in every port, not a single HTTP call. A port SHALL NOT allow a multi-turn loop to exceed
the configured deadline by looping.

A timeout SHALL NOT be retried in any port. It is a deadline the caller set, not a transient
transport fault.

A timeout SHALL be distinguishable from caller cancellation, by a typed error or by a message
naming the budget that expired and the elapsed limit. A port whose idiom is to raise SHALL raise
a **named** error type, never a bare untyped error.

#### Scenario: A slow multi-turn run stops at the deadline

- **WHEN** a run with a short deadline makes several turns, each individually faster than the deadline
- **THEN** the run stops at the deadline rather than completing

#### Scenario: A timeout is not retried

- **WHEN** a run times out with a retry budget configured
- **THEN** no further attempt is made

#### Scenario: A timeout is distinguishable from cancellation

- **WHEN** a host catches a run timeout
- **THEN** it can tell the deadline apart from a caller cancellation, by type or by a message naming the budget
- **AND** the error type is a named one, not a bare untyped error

### Requirement: A failed run still reports what it accumulated

A port whose run entry point returns a result alongside an error SHALL NOT return a zero-valued
result beside a non-nil error. On a deadline it SHALL return `status: "incomplete"` with the limit
named `"timeout"`, reusing the existing limit mechanism and introducing no new run-result status
value, and SHALL preserve the turns, usage, messages and tool calls accumulated before the
deadline.

A host that branches on the status first SHALL therefore never fall through every case on an
empty string.

#### Scenario: A deadline returns an incomplete result, not a zero value

- **WHEN** a run in a port that returns a result-and-error pair exceeds its deadline
- **THEN** the result carries `status: "incomplete"` and `limit: "timeout"`

#### Scenario: Work done before the deadline survives

- **WHEN** a run completes two turns and then exceeds its deadline
- **THEN** the returned result still reports those turns and their accumulated usage
