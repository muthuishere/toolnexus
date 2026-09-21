## Why

toolnexus unified every **action** an LLM can take behind one `Tool`. It has no equivalent for a
**judgment**, so every judgment an agent needs is pushed onto the user, who has two speeds to make
it at: code (0 ms, no semantics) or a frontier-model turn (seconds, dollars). Our own docs show
the cost — the coding-agent page ships `const DANGEROUS = /rm\s+-rf|git\s+push|…/` (blind to
`python -c "shutil.rmtree('/')"`), the orchestrator page encodes retry policy as soul prose the
model may ignore, skills load "when relevant" by hope with the whole roster shipped every turn,
and the only completion verifier we ship is structural because no cheap semantic one existed.

A third tier now exists as a product. A System One model takes a state plus pre-declared typed
questions and returns calibrated answers in well under a second, at a cost that rounds to zero —
and its wire is already a de-facto standard served by several providers, including open weights
and an emulation path over any chat model. `SPEC.md` has no seam for it.

Decided in `docs/adr/0020-classifier-is-to-judgments-what-tool-is-to-actions.md`, which was
**spiked in all seven ports before this proposal**: 4/4 gate items pass in every port, median
`Classifier` ~163 LOC, the live call ran in each. Evidence in `spikes/classifier/reports/`.

## What Changes

- **New `Classifier` contract (`SPEC.md §8B`)** — a sibling of `Client`, not a provider inside it.
  One method, `evaluate(state, questions) -> Decision`, and three question types: a binary truth
  probability, a choice over a named set with its full distribution, and a score on an ordered
  rubric.
- **Backends behind that contract, chosen by `style`** — the System One wire; an `llm` emulation
  over any existing `Client` via structured output; a host-supplied `custom` function; and a
  `static` fixture backend, which is what CI runs.
- **`ClassifierOptions` mirrors `ClientOptions`** where a field makes sense — `baseUrl`, `model`,
  `apiKeyEnv`, `headers` with `${ENV_VAR}` expansion (never logged, identical to remote-MCP
  headers), injectable transport, timeout, and reuse of the existing error-tier and `Retry-After`
  rules rather than a second retry policy.
- **Calibration is carried, not assumed.** A `Decision` reports whether its probabilities are
  calibrated, because the `llm` backend's are not — measured 3.3–4.4× slower, 2.5–3.4× costlier,
  no distribution, round self-reported confidence, and on one fixture it disagreed outright.
- **Conformance on the request, narrowed honestly.** For the same questions every port emits a
  byte-identical `questions` + `model` payload; `state` passes through verbatim, because numbers
  do not canonicalise across languages (`-0.0` renders four ways).
- **Encoding health, because a schema-valid request can rank at chance.** Decided in
  `docs/adr/0021-the-encoding-carries-the-judgment.md`, measured across two games: a `choice`
  whose `criteria` values are its own keys is schema-valid, passes validation, returns a
  well-formed distribution — and ranks at chance (0 apples against 17 for the same board with
  options described). Ports **warn** on degenerate criteria naming the question id, and never
  repair; `Decision` exposes a derived `nearUniform` per choice answer, which is the only
  encoding health check available without ground truth. Both advisory, neither a correctness
  signal.
- **Not shipped here:** the `judge` composition layer and its adapters (`add-judge-adapters`).
  This change lands the contract and the wire only.

## Capabilities

### New Capabilities
- `typed-decisions`: the `Classifier` contract — the three question types, the `Decision` shape,
  the canonical request form and what it covers, backend selection, calibration reporting, and the
  secret-handling and error-classification rules the seam inherits.

### Modified Capabilities

_None._ No existing requirement changes: `Classifier` is a new, independent object, and a host
that never constructs one gets a byte-identical run.

## Impact

- **Spec**: new `SPEC.md §8B`. §8 is referenced (error tiers, `Retry-After`, injectable transport,
  metrics sink) but not modified.
- **Code**: a new `Classifier` in all seven ports — no SDK dependency in any of them, since the
  official SDKs cover two languages and Clojure has none, and the wire is one POST.
- **Fixtures**: `examples/judge/` — a base case, a hardened case (unescaped `<>&` and unicode,
  ASCII key ordering, an array whose order is meaning, absent-vs-empty fields), a **numbers** case,
  and a **>32-key** case. The last two exist because the spike proved a port can otherwise pass
  with a broken emitter: one JSON library round-trips the base fixture perfectly while emitting
  `0.0` for `0`, and another passes only by accident below 33 keys.
- **Docs**: `cookbook/judge` (the recipe, seven language tabs, plus the encoding section ADR 0021
  D4 requires — every claim carrying its measurement) and `harness/judge-live` (measured latency,
  cost and calibration, generated from a real run rather than asserted, plus the shuffle control
  of D5, which is the one cheap test that can distinguish "the judge is ranking" from "my code is
  steering").
- **Not in scope**: adapters into the existing seams, the batteries built on them, and the
  reference build — all in `add-judge-adapters`.
