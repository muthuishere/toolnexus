# ADR 0022 — Cost is always reported: derive it from tokens when the backend will not

- **Status:** **Proposed — 2026-09-21.** The hole is measured, not argued: the numbers below come
  from identical live requests to two backends on the same wire.
- **Date:** 2026-09-21
- **Driver:** Whether a caller gets a cost back currently depends on which backend they happened
  to configure. A spend counter written against one silently stops counting against another.
- **Evidence:** live A/B on the same three questions, 2026-09-21 — OpenRouter returns
  `usage.cost`; TypeSafe's own API returns `input_tokens` and `output_tokens` and nothing else
  (https://docs.typesafe.ai/api). Response keys observed: `model,answers,usage` (TypeSafe) against
  `model,answers,usage,id,provider` (OpenRouter). Independently re-confirmed while landing the
  TypeSafe backend.
- **Related:** ADR 0020 (the `Classifier` seam), ADR 0021 (what a caller owes the encoding),
  `SPEC.md` §8 (client usage) and §8B (classifier usage).

## Context

Three backends serve the same wire and disagree about cost:

| backend | `usage.input_tokens` | `usage.output_tokens` | `usage.cost` |
|---|---|---|---|
| OpenRouter gateway | yes | yes | **yes** |
| TypeSafe first-party | yes | yes | **no** |
| self-hosted / open weights | yes | yes | no — there is no price to report |

**Tokens are always there. Cost is the thing that comes and goes.** That is not an accident:
tokens are what the model consumed, a fact of the request; cost is a commercial property of the
model, which an origin serving its own API has no reason to compute per call.

Three consequences, in ascending order of how badly they fail:

1. A budget or spend counter written against the gateway stops counting the day the same code is
   pointed at the first-party API. Nothing raises.
2. Go and C# could not represent "no cost supplied" and reported **`0.00`, which reads as free
   rather than unknown**. (Fixed in `add-judge` — Go is now `*float64`, C# `double?` — which
   makes absence expressible but leaves it absent.)
3. The same hole exists on the §8 `Client` path, where providers also vary. This is not a
   `Classifier` quirk, and fixing it only there would leave the bigger surface broken.

## Decision

**D1 — Cost is always reported when a price is known.** A backend-supplied cost passes through
unchanged. When none is supplied, cost is computed as tokens times the model's price. A caller
gets the same field from every backend, which is the entire point.

**D2 — Provenance is carried, not inferred.** Every cost states whether it was `reported` by the
backend or `derived` from tokens. A derived cost is an estimate of list price: it does not know
about a caller's discounts, free tier, cache pricing or rounding, so it must never be presented as
a bill. This is the discipline `calibrated` and `nearUniform` already carry — the number travels
with the limits of what it means.

**D3 — An unknown price stays absent. It never becomes zero.** If no price is known for a model,
cost is absent, and absent is distinguishable from zero in every port. A silent zero is the
failure this ADR exists to remove; re-introducing it as the fallback would be perverse.

**D4 — One shared price table, not seven.** Model to price per million input and output tokens,
in a single file every port reads. Seven copies of a price is precisely the drift this repo
exists to prevent, and a price that disagrees across languages is worse than no price at all.
Each entry carries the date it was taken and where from, so a reader can judge its staleness.

**D5 — A host can override or supply prices.** Published prices change and a release cycle is the
wrong latency for that. A host supplies its own table for its own models, or corrects a stale
entry, without waiting on us.

**D6 — This change reports; it does not enforce.** No budgets, no caps, no refusing a call. A
number that may be an estimate must not quietly become an enforcement mechanism. Acting on cost
is a separate decision with a separate failure mode.

## Consequences

- A cost assertion becomes portable across backends, which it is not today.
- The number gets *less* authoritative on average, since some of it is now estimated — which is
  why D2 is load-bearing. Uniformity without provenance would be a downgrade disguised as a
  feature: every caller would get a number and some would trust the wrong ones.
- The price table is a maintenance surface that did not exist before, and it will go stale. D4's
  dated entries and D5's override are what keep that honest rather than silently wrong.
- Builds on `add-judge`'s absent-cost fix; D3 is unimplementable without it.

## Open questions

1. Where does the shared table live so all seven ports read it without a build step in any of
   them? `examples/` is the established shared-and-authoritative location, but it currently holds
   conformance fixtures with recorded hashes, and a price file is not a conformance fixture.
2. Should a derived cost appear on the §8 `Client` path in the same change, or should that follow?
   The hole is the same; the blast radius is not.

## Gate

1. Identical requests to a cost-reporting and a non-cost-reporting backend both yield a cost, each
   correctly marked `reported` or `derived`. ☐
2. A model absent from the table yields an absent cost, not zero, in all seven ports. ☐
3. The price table is read from one file by all seven ports, proven by changing a price in that
   file and seeing every port move. ☐
4. Documentation states that a derived cost is list price and not a bill. ☐
