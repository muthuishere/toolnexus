# Spike — `Classifier` + `judge` feasibility across all seven ports

Decides whether ADR 0020 can be built. **Not shipping code.** Nothing here is imported by any
port's source; each language gets a throwaway directory under `spikes/classifier/<lang>/`.

Read ADR `docs/adr/0020-classifier-is-to-judgments-what-tool-is-to-actions.md` for the why.

## The contract being spiked

```
Question = Noul   { instructions, criteria?: {true,false} }    // criteria ABSENT in the fixture
         | Choice { instructions, criteria: {name: desc} }      // criteria is an OBJECT
         | Score  { instructions, criteria: [desc, ...] }       // criteria is an ARRAY (order is meaning)
Classifier.evaluate(state, questions) -> Decision
Decision = { model, answers: {key: NoulAnswer|ChoiceAnswer|ScoreAnswer}, usage }
```

The point of the spike is that `criteria` is **three different JSON shapes** on one field, and
`answers` is a **heterogeneous map discriminated by `type`**. In Go/Java/C#/Clojure that is the
whole risk. Find out what it costs.

## Canonical request form (the conformance claim)

`fixture/request.json` is the byte sequence every port must emit for the fixture questions:

- keys sorted **recursively**, ASCII ordering
- **arrays are NEVER sorted** — `score.criteria` order IS the level numbering
- compact separators `,` `:` — no spaces
- UTF-8, no trailing newline
- 514 bytes, sha256 in `fixture/request.sha256`

Verified live: this exact byte sequence returns HTTP 200 from OpenRouter.

## The gate — four items, all four must pass

1. **Byte-exact request.** Build the fixture's three questions with your port's types, serialize,
   compare to `fixture/request.json` byte-for-byte. Pure function, no network.
2. **Parse.** Read `fixture/response.json` into typed values; read out
   `is_refund_request.noul` (0.98), `department.choice` ("shipping") + its `probabilities` map,
   `urgency.score` (1.21) + `legend`. Pure, no network.
3. **One judge, wired.** `judge({on, ask, rule: bands})` over the `static` backend reading
   `fixture/guard-*.json`: `git status --short` ⇒ ALLOW, `shutil.rmtree("/")` ⇒ DENY,
   `rm -rf ./build` ⇒ ASK. Emit the guardrail-shaped result your port's `Guardrail` type uses
   (`"" ⇒ allow`, reason string ⇒ deny).
4. **Invariant.** A judge composed AFTER a guardrail that already denied cannot flip it to allow.
   (First-deny-wins is existing behaviour — `golang/agents/loop.go:24-47`; just prove a judge
   respects it.)

Optional 5th, only if `OPENROUTER_API_KEY` is in the environment: one live call, print latency.

## Hard rules

- **No SDK.** Raw HTTP with the port's stdlib/standard client. Jev's official SDKs cover Python
  and JS only; Clojure has none. If your port cannot do it with what it already depends on, that
  is the finding — say so, do not add a dependency.
- **Secrets are use-only.** Read the key from the env var by name at call time. Never print it,
  never write it to a file, never put it in a comment. Use `OPENROUTER_API_KEY`.
- Base URL for the live call: `https://openrouter.ai/api/v1/systemone`, model
  `typesafe/jev-1.13`, `Authorization: Bearer <key>`.
- Stay inside `spikes/classifier/<lang>/`. Do not touch any port's real source.
- Match the port's idiom. A transliteration of Go into Java is not evidence about Java.

## What to report

Write `spikes/classifier/reports/<lang>.md`:

1. **Verdict** — feasible / feasible-with-friction / blocked, one line.
2. **The four gate items** — pass/fail each, with the command that proves it.
3. **LOC** — for `Classifier` (wire + types) and `judge` (on/ask/rule/bands) separately.
4. **The union problem** — how you modelled `criteria`'s three shapes and the discriminated
   `answers` map, and what it cost. This is the finding that matters most.
5. **Canonical JSON** — does your port sort keys natively, or did you hand-roll it? Any float
   formatting risk (e.g. `1.21` vs `1.2100000001`) when re-emitting?
6. **Friction** — anything that would make this expensive to ship in all seven.
7. **Live call** — latency + whether the shape matched, if you ran one.

Keep the report under 120 lines. Facts and numbers, no narrative.
