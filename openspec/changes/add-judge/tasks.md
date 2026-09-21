## 1. Pin the contract before any port moves

- [x] 1.1 `SPEC.md` — add §8B: the `Classifier` operation, the three question types with their
      limits (≤255 options, 2–10 rubric levels), and the `Decision`/answer shapes.
- [x] 1.2 `SPEC.md` §8B — the canonical request rule: objects sorted recursively in ASCII order,
      arrays never reordered, and the claim covering `questions` + `model` only with `state` passed
      through verbatim. State *why* (numbers do not canonicalise) so no one re-widens it later.
- [x] 1.3 `SPEC.md` §8B — `ClassifierOptions`, field by field, each marked against its `ClientOptions`
      counterpart: `style`, `baseUrl`, `model`, `apiKeyEnv`, `headers` (`${ENV}` expansion, never
      logged), transport, timeout, error tiers + `Retry-After` reuse, metrics sink, `client`, `evaluate`.
- [x] 1.4 `SPEC.md` §8B — calibration reporting, and the sentence that a classifier interprets and
      never authorises.
- [x] 1.5 `SPEC.md` §8B — **the encoding obligation (ADR 0021 D1)**, stated where `choice` is
      defined, as a requirement on the caller with its measurement attached: `criteria[id]` is the
      only thing that differentiates options to the model, and passing the id, an empty string, or
      a value equal to the key is schema-valid and ranks at chance.
- [x] 1.6 `SPEC.md` §8B — **degenerate-criteria detection (D2)**: the exact predicate (every value
      empty, or equal to its key, or all values identical), that it warns through the existing
      sink naming the question id, that it is one-time per question id, and that the request is
      sent **unmodified**. Detection, never repair.
- [x] 1.7 `SPEC.md` §8B — **`nearUniform` (D3)** on a choice answer: derived not transported, the
      tolerance, the boundary behaviour, and the sentence that it is advisory and not a
      correctness signal — alongside `calibrated`, which carries the same caveat.
- [x] 1.8 `conformance/options_manifest.json` — register `ClassifierOptions` so the existing
      options-parity check covers it; a field missing in a port must fail loudly, not silently.

## 2. Shared fixtures — four, because one would pass a broken port

- [x] 2.1 `examples/judge/base.json` — one question of each type; the request bytes plus a recorded
      response. Lift from `spikes/classifier/fixture/` (514 bytes, sha256 recorded).
- [x] 2.2 `examples/judge/hardened.json` — unescaped `<>&`, a quotation mark and a backslash,
      non-ASCII text and a non-ASCII key, ASCII key ordering across digit/upper/lower, a rubric in
      non-alphabetical order, and an absent-vs-empty field. Lift from `spikes/classifier/fixture/request-hard.json`.
- [x] 2.3 `examples/judge/numbers.json` — integer `0`, `1.21`, and a small magnitude such as
      `0.000016716`. **Without this a port passes with an emitter that writes `0.0` for `0`.**
- [x] 2.4 `examples/judge/wide.json` — a response whose probability map exceeds 32 keys. **Without
      this a port passes by accident where map iteration is byte-ordered only below 33 keys.**
- [x] 2.5 `examples/judge/decisions.json` — recorded decisions for the `static` backend, including
      the three guard states recorded in the spike.
- [x] 2.6 `examples/judge/degenerate.json` — a `choice` whose criteria equal their keys, plus one
      whose values are all identical. **The warning is the assertion**; the request bytes must be
      unchanged from what the same questions produce without detection.
- [x] 2.7 `examples/judge/near-uniform.json` — three distributions: clearly peaked, exactly
      uniform, and one just inside the tolerance boundary. Without the boundary case the ports
      will disagree on `nearUniform` and no test will notice.
- [x] 2.8 Each fixture carries its expected request sha256, so a port compares bytes rather than
      re-deriving what it thinks correct looks like.

## 3. Go (reference port)

- [x] 3.1 Question types as a closed set with a `wire()` projection through `map[string]any` —
      not struct tags (field order becomes a comment convention) and not `omitempty` (it cannot
      distinguish an absent field from an empty one).
- [x] 3.2 Canonical encoder: `SetEscapeHTML(false)` is **mandatory** — Go escapes `<>&` by default
      and the base fixture would not catch it.
- [x] 3.3 `Decision` decode: peek the discriminator, re-decode per type; typed accessors returning
      `(T, error)` so a wrong-type read is an error, not a panic.
- [x] 3.4 Backends: wire, `llm` over an existing `Client`, `custom`, `static`.
- [x] 3.5 Client-side limit enforcement (≤255 options, 2–10 levels) naming the offending key.
- [x] 3.6 Degenerate-criteria detection + `nearUniform`, against the two new fixtures.
- [x] 3.7 Tests: all four fixtures byte-exact; parse; limits rejected pre-flight; credential and
      expanded headers absent from every error path; absent-classifier run byte-identical.

## 4. Per-language parity — each port lands items 1–5 of the Go list plus its own trap

- [x] 4.1 **js** — hand-rolled canonicaliser (`JSON.stringify` has no recursive sort; a `replacer`
      array applies one key list at every depth). Discriminated unions narrow with no casts.
- [x] 4.2 **python** — `json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False)` is
      canonical natively; `sort_keys` is objects-only so rubric order survives. Dataclasses with a
      literal discriminant; a projection step is needed so an absent field is absent, not `null`.
- [x] 4.3 **java** — Jackson sorts keys natively but **formats numbers wrongly** (`0.0` for `0`,
      scientific for small magnitudes), so a hand-rolled emitter is required; sealed interface plus
      records, one arrow `switch` on the discriminator.
- [x] 4.4 **csharp** — System.Text.Json cannot sort keys: hand-rolled emitter, `StringComparer.Ordinal`
      (not the culture-sensitive default), and the relaxed encoder or `'` becomes `'`.
- [x] 4.5 **elixir** — neither Jason nor the built-in JSON sorts keys; they pass the base fixture
      **by accident** below 33 keys. Explicit ordered encoder required. Keep the response decoder
      from keywordising keys, or numeric legend keys are mangled.
- [x] 4.6 **clojure** — plain `.cljc`, no reader conditionals, verified on JVM and cljgo; the JSON
      library sorts natively. Pass the key function that keeps string keys as strings.
- [x] 4.7 Every port: all four canonical fixtures byte-exact against their recorded sha256.
- [x] 4.8 Every port: degenerate criteria warn once, naming the question id, and send unchanged
      bytes; `nearUniform` matches the shared fixture including the boundary case.

## 5. Docs

- [x] 5.1 `cookbook/judge` — the recipe in seven language tabs: constructing a `Classifier`, the
      three question types, reading a decision, and switching backends.
- [x] 5.2 `harness/judge-live` — latency, cost and calibration measured from a real run, with the
      `systemone` and `llm` backends side by side and the non-determinism stated as a number.
      Generated from the harness, not hand-written.
      The runner is `site/scripts/generate-judge-live.mjs`: it drives the shipped JS `Classifier`
      against the live System One wire and a chat model on the shared `examples/judge/base.json`
      fixture, and writes both `site/src/data/judge-live.json` (the raw record) and the page. One
      command, `node site/scripts/generate-judge-live.mjs`, regenerates the page; the page states
      in its own words that it is generated, when, by which command and at what cost. Latency is
      p50 **and** p95, nearest-rank. Absent `OPENROUTER_API_KEY` it exits non-zero rather than
      emitting numbers. **CI never invokes it** — no workflow and no suite references it, and the
      `static` backend remains the whole CI path.

- [x] 5.3 `cookbook/judge` — the **encoding section** (ADR 0021 D4), each claim citing its
      measurement: describe every option (undescribed options ranked at chance — 0 apples vs 17);
      use one identical sentence template across options; keep arithmetic in code and hand over
      the conclusion (numbers cost ~18%); and a clause wrong about one option in one situation
      costs far more than one uninformative everywhere (50%→100% agreement from one rewrite,
      against a uniformly-false clause that cost nothing until the cap came off).
- [x] 5.4 `harness/judge-live` — the **shuffle control** (D5): keep the returned probabilities,
      permute which option each belongs to, re-run. Published with its number (17 apples → 1) as
      the one control in this space that can fail.
- [x] 5.5 Both pages open with the boundary: a decision is advisory, calibration is not
      correctness, "cannot hallucinate" means schema-valid only, and authority is enforced in code.
- [x] 5.6 Both pages state that a threshold tuned on one backend does not transfer to another, and
      show where `calibrated` is read.
- [x] 5.7 `CHANGELOG.md` — one entry under `## Unreleased`, written from the user's side, naming
      what is **not** done (no adapters, no batteries, no model routing — tracked in
      `add-judge-adapters`).

## 6. Verify

- [x] 6.1 Run the narrowest useful suite per touched port; record which ran.
- [x] 6.2 Prove the non-breaking claim rather than asserting it: a run that constructs no
      `Classifier` is byte-identical to `main` on at least one port.
- [x] 6.3 Confirm CI needs no network and no credential — the `static` backend is the whole path.
- [x] 6.4 Confirm no test asserts a live numeric answer; the live call, if run, asserts shape only.
- [x] 6.5 `openspec validate add-judge`.

## 7. TypeSafe's first-party API, and the two defects it exposed

- [x] 7.1 Add exactly `529` to the default retryable set — `{429,500,502,503,504,529}` on the
      client path, plus the classifier's existing `408`. The set stays an exhaustive enumeration;
      no other status changes classification, and `Retry-After` is untouched.
      Ports: [x] js [x] python [x] golang [x] java [x] csharp [x] elixir [x] clojure
- [x] 7.1b Add a `retryableStatuses` option (named per port) to BOTH `ClientOptions` and
      `ClassifierOptions`: additive to the defaults, unable to subtract, deciding the default
      classification only while `onError` keeps the final say per attempt. Register it in
      `conformance/options_manifest.json` (clientOptions 18→19, classifierOptions 14→15).
      Ports: [x] js [x] python [x] golang [x] java [x] csharp [x] elixir [x] clojure
- [x] 7.2 Make an unreported cost representable in Go (`*float64`) and C# (`double?`), matching the
      five ports that already had an optional. A real `0` stays a real `0`.
      Ports: n/a js [x] n/a python [x] golang n/a java [x] csharp n/a elixir n/a clojure
- [x] 7.3 Each port's `examples/judge.*` selects its backend from the environment —
      `TYPESAFE_API_KEY`, else `OPENROUTER_API_KEY`, else the existing offline `static` replay —
      prints which one it used, and prints an absent cost as absent rather than `$0`.
      Ports: [x] js [x] python [x] golang [x] java [x] csharp [x] elixir [x] clojure
- [x] 7.4 One hermetic test per port, on the client path and the classifier path: `529` retries,
      an unlisted `520`/`501` does not, `retryableStatuses` makes `520` retry while `429` still
      retries, `422` stays terminal, and `onError` overrides a host-listed status. Plus: a
      TypeSafe-shaped `usage` block yields an absent cost, not zero.
      Ports: [x] js [x] python [x] golang [x] java [x] csharp [x] elixir [x] clojure
- [x] 7.5 `SPEC.md` §8, §8B's option table and the per-language API reference state the REAL
      enumerated set instead of the long-standing and incorrect "429/5xx/network" shorthand, and
      point at `retryableStatuses` for anything beyond it. Documentation corrected to the code.
- [x] 7.6 `judge/backends` and `cookbook/judge` document both endpoints side by side, state that
      they are **equivalent in latency** with both sets of numbers, and state that TypeSafe returns
      no cost so a cost-based budget only works through the gateway.
- [x] 7.7 `CHANGELOG.md` entry naming the source-breaking Go/C# `Usage.Cost` type change and what
      the widened rule changes beyond `529`.
- [ ] 7.8 **Not done:** `SPEC.md` §8B does not yet say which response fields are backend-specific
      (`usage.cost`, `id`, `provider` are OpenRouter's, not the wire's). Wording proposed to the
      owner rather than written, since §8B's canonical-request rules were out of scope.
