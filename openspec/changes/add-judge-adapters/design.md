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

## Open items — resolved

- **O1 Tape surface — RESOLVED (semantics converged, spelling is idiom).** Majority behaviour
  (golang, python, java, csharp, elixir, clojure) is pinned in SPEC §8B: obtaining a replaying
  classifier never fails; a miss is reported on **evaluate** as
  `tape: no recorded decision for call "<name>"` and sends nothing. js previously threw when the
  replaying classifier was *built*; it now returns a `custom` classifier that fails on evaluate.
  golang, python and clojure miss messages were aligned to the shared text. API spellings stay
  per-port (idiom table in SPEC §8B); the on-disk tape format is explicitly out of contract.
- **O2 Missing-answer reason — RESOLVED (rule restored).** The reason is exactly
  `missing answer "<key>"` in every port. js, csharp and elixir said `missing answer`
  (elixir actually emitted `missing answer: "<key>"`); python used `repr` (single quotes). All four
  fixed, each with a test pinning the exact string; csharp's skipUncertain check now tests
  presence of the answer instead of comparing the reason text.
- **O3 Policy entry point — RESOLVED as idiom.** Overloading `gate` (python, csharp, elixir),
  a `decide` function/method (js, java, clojure) and a method on `Policy` (golang) are each the
  natural shape of their language; behaviour is pinned by the shared gate cases. Recorded in the
  SPEC §8B mapping table; no rename.
- **O4 Picked-option accessor — RESOLVED as idiom.** `choice()` / `Choice()` in five ports; js
  `pick()` (the §8B `choice` field would be shadowed) and clojure `j/picked` (`j/choice` is the
  builder) are sanctioned in the table.
- **O5 Static one-liner — RESOLVED as idiom.** Each port's existing helper is recorded in the table;
  no rename.

- **O6 Uncertain-answer reason — RESOLVED.** Seven ports had six wordings (`noul answer is
  uncertain` js/clojure, `uncertain noul answer` go, `noul answer is in the uncertain band` java,
  `fixable=0.5 uncertain` python, three value-bearing variants in csharp, `uncertain: "fixable"`
  elixir); only two named the key and none agreed on a value format. Pinned: exactly
  `uncertain answer "<key>"`, parallel to O2's `missing answer "<key>"`; no value or confidence in
  the reason, since `data.answers` already carries them and a formatted float is a parity trap.
- **O7 Escalation shape — RESOLVED.** All seven already used `gate:<i>:<key>` / `gate:default`;
  now pinned. golang's `SkipUncertain` pre-filtered the rule list, so `<i>` was the filtered index;
  it now skips inside the loop. golang omitted and elixir nil'd `data.question` on `gate:default`;
  both now `""`. The prompt stays free text.
- **O8 Check order and misfit rules — RESOLVED (owner asleep; sensible call).** Per rule: missing,
  then uncertain, then fit. A rule that does not fit its answer's type escalates (reason
  port-specific) and is never skipped. java and python checked fit first; csharp and elixir let
  `skipUncertain` skip a misfit; js and elixir evaluated a misfit silently to false. Uncertain-first
  was the majority and the only order in which `skipUncertain` means what its name says.
- **Parity by data.** `gate-cases.json` now pins `question` / `reason` / `requestId` on every
  escalation, `wantAnswers` (value, band/sure, choice) on every case, Policy cases
  (`policy: {default, skipUncertain}`) and per-case `rules`; `state-cases.json` pins `State(role,
  data)`. Every port asserts all of it; each port was mutation-checked (see `parity-matrix.md`).

## Risks

- Cut-points tuned on one backend do not transfer; `Decision.calibrated` stays visible
  on every answer.
- `sure` is advisory; a confident wrong answer passes a gate.
