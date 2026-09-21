## ADDED Requirements

### Requirement: The classifier's retry backoff base is host-configurable

`ClassifierOptions` SHALL expose a retry backoff base, spelled idiomatically per port
(`retryBaseMs` / `retry_base_ms` / `RetryBaseMs` / `:retry-base-ms`), carrying the same
meaning, the same units (milliseconds) and the same default (`500`) as the §8
`ClientOptions.retryBaseMs` it mirrors.

When the classifier retries a failed attempt and no usable `Retry-After` header is present, it
SHALL wait `base * 2^attempt` milliseconds, where `base` is the configured value, `attempt` is
zero-based, and no jitter is added. A `Retry-After` header in its `delay-seconds` form SHALL
continue to win over the computed backoff, unchanged.

A value that is absent, zero or negative SHALL be treated as unset, yielding the `500` default,
so a host that does not set the option observes the exact timing it observes today.

This option SHALL be registered in `conformance/options_manifest.json` under
`classifierOptions`, so a port that lacks it fails the parity check rather than drifting
silently.

#### Scenario: An unset base keeps today's timing

- **WHEN** a classifier is constructed without a retry backoff base and its first attempt fails retryably
- **THEN** it waits 500 ms before the second attempt, and 1000 ms before the third

#### Scenario: A short base makes the retry path fast

- **WHEN** a classifier is constructed with a retry backoff base of 1 ms and two attempts fail retryably
- **THEN** the third attempt is made after roughly 1 ms and 2 ms, not 500 ms and 1000 ms
- **AND** the number of attempts, the emitted request bytes and the returned decision are unchanged

#### Scenario: Retry-After still wins

- **WHEN** a retryable response carries `Retry-After: 0` and a retry backoff base of 5000 ms is configured
- **THEN** the classifier retries immediately rather than waiting 5000 ms

#### Scenario: Every port carries the option

- **WHEN** the options-parity checker runs over the seven ports
- **THEN** the classifier retry backoff base is present in every port held to the full tier
