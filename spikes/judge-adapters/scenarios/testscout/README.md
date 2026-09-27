# testscout — write tests that matter, not tests for coverage

```
 testdata/shop ─▶ 1 Understand ─▶ 2 Triage ─▶ 3 Gate ──keep──▶ 4 Write ─▶ 5 Verify ─▶ accepted / rejected
                  (code: cover)   (classifier) (rules)  │        (LLM /     (code: run + cover delta,
                                                        │         recorded)  classifier: asserts behaviour?)
                                                        ├─skip──▶ reason printed
                                                        └─unsure▶ §10 input Request ("needs a human")
```

Code counts; the classifier only judges. Coverage numbers come from `go test -coverprofile`
and `go tool cover -func` — never from the model.

- `main.go` — the five stages, one function each, plus the questions and gate rules.
- `support.go` — plumbing: go tooling, offline (static recordings) vs live mode, printing.
- `testdata/shop/` — the target: a coupon package with the checkout-500 trailing-space bug.
- `testdata/recorded/` — recorded classifier answers (`answers.json`) and recorded test drafts.

## Run

```sh
go run .                 # offline, hermetic
go test -race .          # pins the decisions
SCOUT_LIVE=1 go run .    # live: needs OPENROUTER_API_KEY in the env (read by name only); SCOUT_MODEL optional
```

## Sample output (offline)

```
FUNC          COV%  TRIAGE       TEST       WHY
Code         100.0  skip                    already covered
String         0.0  skip                    value=0.3 noticed=0.35
percentOf      0.0  needs_input             needs a human: Classifier is unsure about "value" (uncertain score answer). Decide rule 0 (skip).
ApplyCoupon    0.0  keep         accepted   value=2.9 noticed=0.93; coverage 7.1% -> 100.0%, FAILS on current code: caught a real bug
RoundCents     0.0  keep         rejected   value=2.7 noticed=0.81; coverage 7.1% -> 14.3%, coverage-only: asserts nothing
```

The accepted test *fails* on the shipped code — that is the point: it caught the trailing-space
bug. `TestGoodTestPassesOnFixedCode` applies the one-line fix and shows it goes green.
