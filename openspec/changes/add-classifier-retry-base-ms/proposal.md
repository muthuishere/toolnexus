## Why

`ClassifierOptions` is specified (`SPEC.md §8B`) to mirror `ClientOptions` "field-for-field
wherever a field makes sense". It does not. `ClientOptions.retryBaseMs` exists in all seven
ports; the classifier's retry backoff base is a hardcoded `500` in **every** port, and only
`js/` exposes a `retryBaseMs` on `ClassifierOptions` — one port with an option six do not have
is exactly the drift `conformance/options_manifest.json` exists to catch, and the manifest
has no row for it, so the checker cannot see it.

It has a concrete cost. A test that exercises the classifier's retry path must pay real wall
time: two retries at the hardcoded base is `500 + 1000 = 1.5 s` of sleep per test, and the only
way around it today is to fake a `Retry-After: 0` header or inject a transport — a workaround
that tests the header parser rather than the backoff. A suite that sleeps is a suite people
learn to skip.

Related but **out of scope**, recorded here so it is not lost: `clojure/`'s *client* defaults
`:retry-base-ms` to **250** where the other six default to 500, and its client backoff omits
the `+ jitter[0,100)ms` term the other six apply. That is a second, genuine parity gap in
default behaviour; changing it would change what a Clojure host observes today, so it is
reported rather than quietly unified here.

## What Changes

- **`ClassifierOptions` gains `retryBaseMs`** (idiomatic name per port), default `500` — the base
  of the classifier's exponential backoff, `base * 2^attempt`. It is the same option, the same
  default and the same semantics as `ClientOptions.retryBaseMs`, on the seam the spec already
  says mirrors it. `js/` already has it; the other six ports gain it.
- **The classifier's backoff formula does not change.** It stays `base * 2^attempt` with no
  jitter in all seven ports (this was already identical everywhere), and `Retry-After` still
  wins over backoff when present. Absent the option, every port sleeps exactly what it sleeps
  today.
- **A manifest row**, so the parity checker gates it: `classifierOptions` moves from 15 options
  to 16.
- **Docs** — the `SPEC.md §8B` `ClassifierOptions` table, and the per-language API reference.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `typed-decisions`: the `ClassifierOptions` surface gains one option. No request, no decision
  and no default timing changes.
