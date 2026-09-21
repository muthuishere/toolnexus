## Context

`docs/adr/0020-classifier-is-to-judgments-what-tool-is-to-actions.md` decides this. It was spiked
in all seven ports *before* this proposal, which is unusual here and deliberate: ADR 0019's lesson
was that an unspiked headline justification does not survive contact. Two of ADR 0020's own claims
were falsified by the spike and are already corrected in it.

What the spike established, per `spikes/classifier/reports/99-verdict.md`:

| port | gates | `Classifier` LOC | canonical JSON | live |
|---|---|---|---|---|
| Clojure | 4/4 | 68 | native | 537 ms JVM · 1326 ms cljgo |
| Python | 4/4 | 145 | native | 468 ms |
| JS | 4/4 | 156 | 9 lines | 457 ms |
| Elixir | 4/4 | 163 | 17 lines | 581 ms |
| C# | 4/4 | 205 | 46 lines | 562 ms |
| Java | 4/4 | 264 | 74 lines | 870 ms |
| Go | 4/4 | 271 | 8 lines | 352–851 ms |

The union was never the risk: every statically-typed port modelled the three question shapes with
a closed/sealed hierarchy and zero write-side casts. The only repeated cost is the canonical
emitter, and only in three ports.

## Goals / Non-Goals

**Goals:**
- One contract for a typed judgment, in seven ports, with the wire pinned by shared fixtures.
- Vendor-neutral by construction — the contract is the three question types, not one provider.
- Hermetic CI: the `static` backend is the conformance path, no network, no credential.
- Absent ⇒ byte-identical, enforced by a test rather than asserted.

**Non-Goals:**
- The `judge` composition layer, its rules, and the adapters into `Guardrail`/`Verify`/`BeforeLLM`
  — `add-judge-adapters`. This change is the contract and the wire only.
- Exposing the classifier as a `Tool` the model can call. Paying a frontier round trip to ask a
  sub-second judge is backwards; the demand is real, so it stays a later opt-in.
- Routing between model tiers. Agent routing is a different axis and lands with the adapters;
  per-query model routing contradicts the existing routing stance and needs its own ADR.
- Using a classifier for retry-vs-fail. That is a status-code decision and already has a seam.

## Decisions

**D1 — `Classifier` is a sibling of `Client`, not a style inside it.** The System One shape has no
messages, no tool calling and no streaming; wiring it into the adapter layer would be a category
error. Same relationship as `Tool` to its sources: the contract is the noun, providers sit behind.

**D2 — Three backends plus fixtures, chosen by `style`.** The wire style covers several providers
because they share it. The `llm` style is what keeps the seam vendor-neutral and lets a host with
no System One credential run the same questions on a cheap chat model. `static` is not a
convenience — it is the *only* thing CI can assert, because the live model is non-deterministic
(σ ≈ 0.015, spread 0.05 on a 0–3 score across 12 identical calls).

**D3 — The byte-identity claim covers `questions` + `model`, never `state`.** Numbers do not
canonicalise: `-0.0` renders four different ways across our own seven runtimes, `1e-5` three. The
claim is about the structure we generate, and `state` is the caller's. Sorting is recursive over
objects and never over arrays, because a rubric's order is its numbering.

**D4 — Four fixtures, because one would pass a broken port.** The spike proved this concretely:
the base fixture contains no numbers, so one JSON library round-trips it byte-perfectly while
emitting `0.0` for integer `0`; and another port passes only by accident, because its maps iterate
in byte order up to 32 keys and arbitrarily above. So: base, hardened (unescaped `<>&`, unicode,
ASCII key order, an unsorted rubric, absent-vs-empty), **numbers**, and **>32 keys**.

**D5 — Reuse the client's error tiers and `Retry-After`; add no second retry policy.** The
observed failures are clean JSON errors with a status and a cause, which is exactly what the
existing classifier consumes. A parallel policy would be two things to keep in parity for no gain.

**D6 — Calibration travels with the decision.** The measured gap is not marginal: on one routing
job the `llm` backend was 3.3–4.4× slower, 2.5–3.4× costlier, returned no distribution, emitted
round self-reported confidence (0.95, 1.00), and on the support fixture disagreed outright. A flag
on the `Decision` is what lets the later `bands` rule refuse an uncalibrated backend.

**D7 — Enforce limits client-side, before the request.** 255 options and 2–10 rubric levels are
backend limits, but a caller finds out faster and cheaper locally, and the error can name the
offending question key. The backend's own limit error is still surfaced intact when it arrives.

## Risks / Trade-offs

**Vendor risk is real and is the reason for the shape.** The reference provider is early-access,
closed, single-vendor, with limits documented as subject to change. The mitigations are structural
rather than promissory: the contract is the three question types; the `llm` backend runs on
anything; an open-weights implementation speaks the same wire; and CI never touches the network.

**Non-determinism will surprise people.** Identical requests return slightly different numbers.
This change carries it (fixtures for conformance, calibration on the decision); the threshold
clearance rule that depends on it lands with the rules in `add-judge-adapters`.

**"Cannot hallucinate" is a claim we must not repeat.** The guarantee is schema validity, not
correctness — a decision can be confidently wrong. The spec requires the documentation to say so,
and requires that a classifier never be described as an authorisation mechanism.

**The canonical emitter is per-port work in three languages** (74, 46 and 17 lines), because their
JSON libraries cannot sort keys, or sort them but format numbers wrongly. Two ports need none. The
fixtures are what stop those three drifting apart.

**Scope discipline.** Landing the contract without adapters means this change ships something no
shipped code path consumes yet. That is deliberate — it keeps the contract reviewable on its own
and lets the adapters, which touch existing seams, be reviewed against a settled contract.
