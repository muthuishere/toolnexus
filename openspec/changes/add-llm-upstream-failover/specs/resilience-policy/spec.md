## ADDED Requirements

### Requirement: Ordered fallback upstreams beneath the retry budget

The client SHALL accept an optional ordered list of fallback upstreams (idiomatic name per port). An
upstream SHALL be able to override the base URL, the model, the provider style, the credentials, and
the injected HTTP transport; any field it does not specify SHALL inherit from the client's primary
configuration. The client's existing configuration SHALL remain the primary upstream and SHALL be
tried first.

Failover SHALL be an outer tier around the existing retry budget: the `retries` budget SHALL be spent
on the current upstream before the client advances to the next, and the budget SHALL reset for each
upstream. Total attempts for one call SHALL therefore be bounded by `(1 + retries) × upstreams`.

The client SHALL advance to the next upstream only on failures the existing classification treats as
retryable (`429`, `5xx`, network errors) — the same set that governs retry. A failure outside that
set SHALL fail immediately as it does today, without advancing.

When no fallback upstreams are configured, behaviour SHALL be byte-identical to before this change:
the same request bodies, the same retry sequence, the same errors.

#### Scenario: Absent option changes nothing

- **WHEN** no fallback upstreams are configured and the LLM returns `429` twice then `200`
- **THEN** the client retries against the single upstream and succeeds, identically to before this change

#### Scenario: Retry is exhausted before advancing

- **WHEN** the primary upstream returns `503` and one fallback is configured with `retries` set to 2
- **THEN** the client attempts the primary three times before issuing any request to the fallback

#### Scenario: A dead primary fails over to a healthy backup

- **WHEN** the primary upstream refuses connections and a healthy fallback with a different base URL and model is configured
- **THEN** the call succeeds, and the fallback receives a request carrying the fallback's own model

#### Scenario: A non-retryable failure does not advance

- **WHEN** the primary upstream returns `400` and a fallback is configured
- **THEN** the client fails immediately and sends no request to the fallback

#### Scenario: Every upstream fails

- **WHEN** the primary and all fallbacks exhaust their budgets
- **THEN** the client surfaces an error reporting that all upstreams failed, identifying each upstream tried

### Requirement: A failed upstream is excluded for the rest of the request

An upstream that has exhausted its retry budget during a call SHALL be excluded from the remainder of
that call, so no chain revisits an upstream already known to have failed. The client MAY additionally
deprioritise a recently failed upstream for subsequent calls using a process-local cooldown.

The cooldown SHALL be local to the process and SHALL NOT be presented as shared or fleet-wide health
state. The client SHALL NOT implement traffic distribution, weighting, or health-based load balancing.

#### Scenario: No upstream is retried twice within a call

- **WHEN** a call fails over through three upstreams and the last also fails
- **THEN** each upstream was attempted during exactly one budget window and none was revisited

### Requirement: A committed stream never fails over

When a streaming call fails before its first token has been emitted to the caller, the client SHALL
fail over exactly as a non-streaming call does. When a streaming call fails after at least one token
has been emitted, the response SHALL be treated as committed: the client SHALL surface the error and
SHALL NOT attempt any further upstream or retry for that call.

#### Scenario: Pre-token stream failure fails over

- **WHEN** a streaming call's upstream fails before emitting any token and a healthy fallback is configured
- **THEN** the call fails over and the caller receives a single uninterrupted stream from the fallback

#### Scenario: Mid-stream failure surfaces the error

- **WHEN** a streaming call's upstream fails after emitting tokens to the caller
- **THEN** the caller receives the error, and no additional text from any other upstream is emitted

### Requirement: Results and metrics report the serving upstream

`RunResult` SHALL report the upstream that actually served the call, not the configured primary, and
the `on_metric` events SHALL label usage with the serving upstream's model. Usage across a call that
spanned multiple upstreams SHALL be accumulated, with per-upstream attribution available in the
metric events.

The `onError` classification context SHALL identify the upstream a failure came from, so a host can
classify per upstream. A classification of `fail` SHALL short-circuit both tiers — it SHALL prevent
retry and SHALL prevent advancing to any further upstream.

#### Scenario: The backup's model is reported

- **WHEN** a call fails over and is served by a fallback using a different model
- **THEN** `RunResult` reports the fallback's model, not the primary's

#### Scenario: onError fail prevents failover

- **WHEN** `onError` returns `fail` for a `503` and fallbacks are configured
- **THEN** the client surfaces the error immediately and sends no request to any fallback

#### Scenario: Usage spans upstreams

- **WHEN** a call consumes tokens on a failing primary and then completes on a fallback
- **THEN** the reported usage includes both, and the metric events attribute each portion to the upstream that produced it

### Requirement: Each upstream is gated and billed as the operator configured it

An upstream's model SHALL be transmitted to that upstream verbatim, exactly as model faithfulness
requires of the primary. Failover SHALL NOT alias, rewrite, or silently default a model; it selects
among upstreams the operator configured explicitly and in order.

The `beforeLLM` route-gate SHALL run again for each upstream attempted, receiving that upstream's
model. A gate that aborts the call SHALL prevent the request to that upstream. An operator who gates
an expensive tier SHALL therefore remain gated during an outage, rather than having the gate bypassed
by failover.

#### Scenario: The fallback's model is sent verbatim

- **WHEN** a call fails over to an upstream configured with a different model
- **THEN** the fallback endpoint receives exactly that model id, unaliased

#### Scenario: The route-gate runs for the fallback

- **WHEN** a call fails over to an upstream whose model the `beforeLLM` route-gate rejects
- **THEN** the gate aborts the call and no request is sent to that upstream

#### Scenario: A gated expensive tier is not reached by failover

- **WHEN** the primary is a cheap model that fails, the fallback is an expensive model, and the route-gate permits the cheap tier but aborts the expensive one
- **THEN** the run fails without billing the expensive tier

### Requirement: Cross-style fallbacks are rejected until transcripts are canonical

A fallback upstream whose provider style differs from the primary's SHALL be rejected when the client
is constructed, with an error naming the unmet dependency, until conversation history is held in the
provider-neutral canonical form. The client SHALL NOT forward an in-flight transcript rendered in one
provider's dialect to a provider of the other style.

#### Scenario: A differing-style fallback fails at construction

- **WHEN** a client configured with `style:"openai"` is given a fallback upstream with `style:"anthropic"`
- **THEN** construction fails with an error naming the dependency, rather than failing later during an outage
