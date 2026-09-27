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
- **D8 State carries the role.** `State(role, data)` returns the data's fields at the top level
  plus `role`; a non-object value goes under `data`. The wire has no role field (ADR 0035 D7).
- **D9 Each question names the state field it judges.** Live, a vague moderation question with
  the role in state blurred to 0.55; naming `message_received` gave 0.96 / 0.02 and 0.02 / 0.79.
  The role never goes into question instructions (variant E, rejected).
- **D10 `Answer.Value()`.** One number per answer: the noul probability, the score value, the
  choice's confidence; `Answer.Choice()` returns the picked option. No type assertions.
- **D11 `Policy{Rules, Default, Bands, SkipUncertain}`.** No-rule-fired is declared: `Default`
  names an action, `""` escalates with a §10 `input` Request (reason `no rule fired`).
  `SkipUncertain` skips uncertain rules instead of escalating on the first one.
- **D12 `Tape` record/replay.** Record live decisions keyed by call name; replay offline through
  the `static` style. A miss is an error naming the key. Hermetic tests replay real answers.

## Risks

- Cut-points tuned on one backend do not transfer; `Decision.calibrated` stays visible
  on every answer.
- `sure` is advisory; a confident wrong answer passes a gate.
