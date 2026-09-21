## Why

Four corners of the classifier and client contract are either unpinned or already drifted.
None of them fails loudly, which is why they are grouped: each produces a green build and a
valid-looking request.

1. **`decisions` is required in every port and gated in none.** The `static` backend's recorded
   corpus is the backend **CI runs on** — no network, no credential, the only backend a test may
   assert a number against (`SPEC.md §8B`). It exists in all seven ports, it is absent from
   `conformance/options_manifest.json`, and `§8B` names the `"static"` style without ever naming
   the field that feeds it. The one option the whole test strategy rests on is the one option
   parity does not check.

2. **An absent `calibrated` decodes as `true` in all seven ports, and nothing says so.** Seven
   ports agree by inspection, not by contract. A port that later defaulted it to `false` would
   flip every threshold a host has tuned, on a field the host never set, and no gate would
   object.

3. **Clojure's `choice-over` stringifies option ids with `str`, not their name.** A keyword key
   reaches the wire as `":billing"`, sigil included — schema-valid, HTTP 200, a well-formed
   distribution, and option ids that differ from every other port's. Elixir's `to_string(:billing)`
   is `"billing"`, so the two ports already disagree for the idiomatic key type of each language,
   and the caller's own `(:choice answer)` comparison misses.

4. **Clojure's client backoff disagrees with six ports.** `:retry-base-ms` defaults to `250` where
   the other six default to `500`, and the backoff omits the `+ jitter[0,100)ms` term the other
   six apply. Jitter is what stops a fleet that failed together from retrying together, so its
   absence is a load-shape difference, not a cosmetic one. Reported in
   `add-classifier-retry-base-ms` and deliberately left there; this change closes it.

## What Changes

- **`decisions` becomes a named, gated option.** A row in `SPEC.md §8B`'s `ClassifierOptions`
  table and a **core**-tier row in `conformance/options_manifest.json` (16 → 17 classifier
  options). No port changes — every port already has it; the gate simply stops being blind.
- **`calibrated`'s default is written down.** `§8B` states that absent and `null` decode as
  `true` and only the literal `false` is `false`, and each port gains a test that pins all four
  cases. No port behaviour changes.
- **`§8B` pins option-id coercion** for `choiceOver`-style constructors: a non-string key travels
  as its **plain name**, never its host's printed form. `clojure/` is brought to it; `elixir/`
  already satisfied it and gains the test that says so.
- **Clojure's client backoff is unified**: default `500`, wait `base * 2^attempt + jitter[0,100)ms`.

## Breaking

Two Clojure-only behaviour changes, both toward the other six ports:

- A `choice-over` roster keyed by keywords now sends `billing` where it sent `":billing"`. A host
  that keyed a downstream branch on the sigil form must drop the colon. A host that already
  passed strings — which the port's own docs told them to do — sees no change.
- A Clojure client that never set `:retry-base-ms` now waits ~500ms before its first retry instead
  of ~250ms, plus up to 99ms of jitter. A host that set the option explicitly sees no change but
  the jitter.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `typed-decisions`: one option becomes gated, one decode default becomes contract, one
  constructor's key coercion becomes contract.
- `resilience-policy`: the client's retry backoff shape becomes a stated cross-port requirement
  rather than six ports quietly agreeing.
