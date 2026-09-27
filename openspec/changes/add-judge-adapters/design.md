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
- **D7 Idiom per port.** Same behaviour, native shape. Recorded as permitted idiom (what the
  seven ports shipped): golang names the answer type `JudgeAnswer` (`Answer` is already the §10
  type) and takes bands variadically; js puts the named builders under `judge.` (`judge.noul`
  etc. — the bare `noul/choice/score` stay the §8B wire builders) and the picked-option method is
  `pick()` (a choice answer keeps §8B's `choice` string field, which a method would shadow);
  python spells the rule field `is_` and uses snake_case (`skip_uncertain`); clojure spells it
  `:at-least` and is plain data; elixir takes `bands:` in opts. Band values follow the host
  (strings; java enum `YES/NO/UNCERTAIN`; elixir atoms `:yes/:no/:uncertain`).
- **D8 State carries the role.** `State(role, data)` returns the data's fields at the top level
  plus `role`; a non-object value goes under `data`. The wire has no role field (ADR 0035 D7).
- **D9 Each question names the state field it judges.** Live, a vague moderation question with
  the role in state blurred to 0.55; naming `message_received` gave 0.96 / 0.02 and 0.02 / 0.79.
  The role never goes into question instructions (variant E, rejected).
- **D10 `Answer.Value()`.** One number per answer: the noul probability, the score value, the
  choice's confidence; `Answer.Choice()` returns the picked option. No type assertions.
- **D11 `Policy{Rules, Default, Bands, SkipUncertain}`.** No-rule-fired is declared: `Default`
  names an action, `""` escalates with a §10 `input` Request (reason `no rule fired`).
  `SkipUncertain` skips only rules whose answer is present but uncertain / not sure; a rule whose
  answer is **missing** still escalates (all seven ports).
- **D12 `Tape` record/replay.** Record live decisions keyed by a caller-given call name, replay
  them offline keyed by that call name — not by the canonical request, which is why the replaying
  classifier is a `custom`-style classifier over the tape rather than `static` (a `static` corpus
  is keyed by the request). A miss is an error naming the key and sends nothing. Hermetic tests
  replay real answers. (js and python replay a *hit* by handing the recorded entry to `static`;
  see open items.)
- **D13 `evaluateBatch` fails closed on the lowest index.** Per-state `evaluate`, ≤ 16 in flight,
  decisions in state order; when several states fail, the error names the lowest failing index
  (every port scans results in state order), so the error is deterministic under concurrency.

## Open items — where the ports disagree (not papered over)

- **O1 Tape surface.** golang: one `Tape` with `Recording(live)` / `Replayer()`, call name carried
  on `ctx` (`WithCallName`). js: `new Tape(live).classifier(name)` / `Tape.replay(entries).classifier(name)`;
  a replay hit goes through `static`, a replay miss throws when the classifier is *built*, not
  when it is evaluated. python: `Tape(live).call(name)` / `Tape(recorded=…).call(name)`; hit via
  `static`, miss is a `custom` classifier that errors on evaluate. java: `record(call)` / `replay(call)`,
  `custom`. csharp: records via `RecordAsync(call, live, state, qs)` (a method, not a wrapping
  classifier), replays via `Replay(call)`, `custom`. elixir `Tape.record/3` / `Tape.replay/2`,
  clojure `recording` / `replaying`, both `custom`. Tape file format is also per-port.
- **O2 Missing-answer reason text.** golang, java, python and clojure name the question
  (`missing answer "x"`); js, csharp and elixir say `missing answer` (the key is still on the
  Request's `data.question`).
- **O3 How a Policy is applied.** golang `Policy.Gate(ctx, c, …)` / `Policy.Decide(answers)`;
  js `decide(c, state, qs, policy)`; python `gate(c, st, qs, Policy(...))`; java
  `policy.decide(c, state, qs)`; csharp `Judge.GateAsync(…, Policy)`; elixir `gate(…, %Policy{})`;
  clojure `j/decide`. Behaviour agrees (shared gate cases); the entry point does not.
- **O4 Picked-option accessor.** `Choice()` (golang, csharp), `pick()` (js, permitted), `choice()`
  (python, java via the raw answer, elixir `Answer.choice/1`), clojure `j/picked`.
- **O5 One-line static classifier name.** `StaticClassifier(Recorded(…))` (golang),
  `staticClassifier` (js), `static_classifier` (python), `Classifier.fromRecorded` (java),
  `Classifier.FromRecorded` (csharp), `Judge.static/4` (elixir), `jev/static-classifier` (clojure).

## Risks

- Cut-points tuned on one backend do not transfer; `Decision.calibrated` stays visible
  on every answer.
- `sure` is advisory; a confident wrong answer passes a gate.
