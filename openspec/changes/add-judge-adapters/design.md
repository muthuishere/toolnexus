# Design — add-judge-adapters

Evidence: ADR 0035 and `spikes/judge-adapters/` (Go original + six ports + `shared/`).

## Decisions

- **D1 Layer, not a new client.** `ask` / `gate` are functions over any `Classifier`
  (every style: systemone, llm, custom, static). No new options, no wire change.
- **D2 Ordered list in, map on the wire.** The list is the caller's shape; the §8B map is the
  wire. Order is preserved only for the caller (rules, reporting); keys are never sent (§8B).
- **D3 `band` for noul, `sure` for choice/score.** A noul probability has three honest
  readings. A choice/score confidence has two: sure or not. Inventing a "no" confidence was
  the one naming defect every spike reported.
- **D4 Exclusive cut-points.** `p < low` → no; `p > high` → yes; else uncertain. Exactly
  on a cut-point is uncertain (matches wfnexus `judge.Bands`). Choice is sure iff
  `confidence > high AND NOT nearUniform`; score is sure iff `confidence > high`.
- **D5 Escalation is a §10 Request.** `kind: "input"`, `data: {question, reason, answers}`.
  A host routes it through `waitFor` or its own queue (wfnexus: `needs_input`). The gate
  never authorises; it only declines to decide.
- **D6 Missing answer escalates.** Today every port errors; a gate turns it into a question
  for a human, because failing a step on a missing field is the wrong default.
- **D7 Idiom per port.** `is` is `is_` in Python; Go bands are variadic; Elixir takes
  `bands:` in opts; Clojure is plain data. Same behaviour, native shape.

## Risks

- Cut-points tuned on one backend do not transfer; `Decision.calibrated` stays visible
  on every answer.
- `sure` is advisory; a confident wrong answer passes a gate.
