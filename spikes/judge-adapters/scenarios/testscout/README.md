# testscout — write tests that matter, not tests for coverage

```
testdata/shop ─▶ 1 Understand ─▶ 2 Triage ─────▶ 3 Plan ────────▶ 4 Gate ───keep──▶ 5 Write ─▶ 6 Verify ─▶ accepted
                 (code: cover)   (clf, ONE call  (clf, ONE call   (policy)   │       (LLM via    (code: shipped / fixed /   rejected
                                  for all funcs:  choice per func:           │        toolnexus    mutants; clf: asserts? needs_input
                                  value+noticed)  bug report in state)       │        client)      named?)
                                                                             ├─skip─▶ reason printed
                                                                             └─unsure▶ re-ask 2 more VIEWS ─agree─▶ decided
                                                                                                          └split─▶ §10 needs_input
```

Code counts, the classifier judges, the LLM writes. Coverage and mutants come from
`go test -coverprofile`/`go tool cover` and a small AST mutator, never from a model.

- `main.go` — the six stages, one small function each, plus the questions and policies.
- `support.go` — go tooling, fixed-code + mutation check, live/replay modes, cost table.
- `testdata/shop/` — 7 funcs: getter, `String`, `percentOf` (the trailing-space bug), `ApplyCoupon`,
  `RoundCents`, cart `Total`, `ValidateLine` (user-facing errors).
- `testdata/recorded/` — `tape.json` (live classifier decisions, keyed by call name) and the live
  LLM drafts. Written by `--record`, replayed by `go test`.

## Run

```sh
zsh -ic 'go run .'            # LIVE (default): TypeSafe systemone jev-latest (TYPESAFE_API_KEY)
                              #   + writer on OpenRouter openai/gpt-4.1-mini (OPENROUTER_API_KEY); SCOUT_MODEL overrides
zsh -ic 'go run . --record'   # live, and rewrite testdata/recorded from this run
go run . --offline            # replay the tape, no network
go test -race .               # hermetic: replays the recorded LIVE answers
```

Keys are read by the library by env-var NAME; this program never reads or prints them.

## Real live sample (run 2, 2026-09-27, `jev-1.13.0`)

```
FUNC          COV%  TRIAGE      PLAN       TEST      WHY
Code         100.0  skip                             already covered
String         0.0  skip        happy_path           value=0.1(yes) noticed=0.66
percentOf      0.0  needs_input regression           value=2.0(uncertain) noticed=0.79; re-asked [needs_input needs_input keep] -> split, ask a human
ApplyCoupon    0.0  keep        regression accepted  value=3.0(yes) noticed=0.86; cov 16.0->64.0%, mutants killed 4/6, FAILS on shipped code, passes on fix: caught the bug, asserts=0.82 named=0.86
RoundCents     0.0  needs_input happy_path           value=1.4(uncertain) noticed=0.83; re-asked [needs_input needs_input needs_input] -> split, ask a human
Total         75.0  needs_input happy_path           value=1.4(uncertain) noticed=0.83; re-asked [needs_input needs_input needs_input] -> split, ask a human
ValidateLine   0.0  keep        happy_path needs_input value=2.6(uncertain) noticed=0.72; re-asked [needs_input keep keep] -> agreed; cov 16.0->32.0%, mutants killed 4/6, asserts=0.68 named=0.66

STAGE       CALLS  WALL_MS   IN_TOK  OUT_TOK
gate            8     2614     4064      324
plan            1      302     1747      302
triage          1      373     1700      220
verify          2      658     1741       92
write(llm)      2     7601     1390      784
```

Classifier: 12 calls, ~3.9 s wall, ~9.3k in / ~0.9k out tokens for the whole run (TypeSafe
reports no cost, so none is printed — absent is not $0). One batched triage call for six
functions cost ~370 ms; the self-consistency re-asks are the expensive stage (8 calls, 2.6 s).
The LLM writer is ~3.8 s per draft and dominates wall time.

## What surprised us (honest)

- **Run 1: the LLM pinned the bug as correct.** Asked for a "regression" test, gpt-4.1-mini wrote
  `"SAVE10 "` → `expectError: true`. It passes on shipped code and fails on the fix. The code check
  (`fails on fixed code → rejected`) caught it; the classifier would not have. The prompt now says
  "assert the CORRECT behaviour even where the code is buggy", and run 2 is accepted.
- **Encoding moved `noticed` a lot.** With a bare noul ("a bug here would be noticed by a customer")
  ValidateLine — which returns the shopper's error text — scored 0.23. Adding true/false criteria
  ("wrong price, wrong or missing error message, failed checkout" vs "only other code sees it")
  moved it to 0.72. ADR 0021 again: the sentence is the product.
- **Plan picked `happy_path` for ValidateLine** (0.63), not `invalid`, even though the function
  exists only to produce error messages. `regression` for ApplyCoupon and percentOf was right.
- **Score confidence is low for anything mid-rubric.** Every value between ~1.3 and ~2.6 came back
  uncertain; in this tape confidence tracked closeness to a whole level (0.15→0.85, 2.28→0.28, 2.05→0.05,
  2.85→0.85). So the first-match gate escalated almost everything — see friction 5.
- **Self-consistency = views, not samples.** Jev is near-deterministic (runs 1 and 2 differ by ≤0.1),
  so re-asking the same state is pointless. The gate re-asks with two different states (the func
  alone; func + bug report). ValidateLine agreed (keep), percentOf split (bug report flips it to keep),
  RoundCents/Total stayed unsure in every view → human.
- **Batching dilutes.** In the batched triage call, per-func answers were less confident than when
  the same function was asked alone (ValidateLine value confidence 0.56 batched vs 0.83/0.91 alone).
  Speculative fan-out is cheap, but not free in sharpness.
- No backend-native multi-state batch exists (`EvaluateBatch` fans out); batching here is "many
  keyed questions over one shared state" in a single `Evaluate`.

## Friction fixed in the spike `judge` package (not the library)

1. `Answer.Value()` / `Answer.Choice()` — no more `.DecisionAnswer.(tn.ScoreAnswer).Score`.
2. `judge.Policy{Default}` — "no rule fired" is declared: an action, or `""` = §10 escalation.
3. `judge.Tape` — record live by call NAME (`WithKey(ctx, "plan")`), replay offline; a miss names
   the key. Replaces rebuilding exact states for `StyleStatic`.
4. No empty toolkit: `client.Run(ctx, prompt, nil)` works (Toolkit methods are nil-safe) — undocumented.
5. (new) `Policy.SkipUncertain` — with first-match, one unsure rule blocked every later confident
   rule; now uncertain rules are skipped and only "nothing confident decided" escalates.
