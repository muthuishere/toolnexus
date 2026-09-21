## ADDED Requirements

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
