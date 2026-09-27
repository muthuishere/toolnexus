# Spike: judge-adapters — confidence-gated escalation over `tn.Classifier`

**Question:** would a Layer-1 `Gate` adapter (bands + rules + §10 escalation) simplify
wfnexus's `decide:` gates and fix a real bug? **Answer: yes to both.**

## Result (hermetic, `go test -race ./...`, StyleStatic)

| row | fixable | component (conf, dist) | baseline (today) | adapter |
|---|---|---|---|---|
| unfixable | 0.12 | pricing .85 sharp | fail | fail |
| sure-pricing | 0.90 | pricing .85 sharp | skip_to fix-pricing | skip_to fix-pricing |
| **unsure-both** | **0.55** | pricing **.40** split | **skip_to (wrong)** | **needs_input** |
| noul-at-low | 0.30 | checkout .9 | fall through | needs_input |
| noul-at-high | 0.70 | checkout .9 | fall through | needs_input |
| noul-just-above-high | 0.71 | checkout .9 | fall through | fall through |
| choice-conf-at-high | 0.90 | pricing .70 | skip_to | needs_input |
| near-uniform-high-conf | 0.90 | pricing .80, flat | skip_to | needs_input |
| missing-component | 0.90 | (absent) | **step error** | needs_input |

Live (`GATE_LIVE=1 go run .`, OpenRouter systemone, key by env NAME only): shape OK —
fixable=0.83, pricing conf=1.00 → skip_to fix-pricing, not escalated.

## LOC

- Baseline gate logic in wfnexus: `decideGate`+`asFloat` 33 (decide.go:139-171) + the vals
  flattening 36 (decide.go:64-99, duplicated by judge.go:82-119, 38) → ~69 LOC in the engine that
  exist only to throw away confidence/nearUniform before gating.
- Adapter: `gate.go` 128 LOC (library side, once). wfnexus call site becomes
  `o := Apply(d, rules, bands)` + the existing action switch (engine.go:873-889) with
  `needs_input` reusing `o.Request` — ~5 lines + ~8 to map `workflow.DecideGate`→`Rule`.

## Proves / does not prove

Proves: today's engine gates on raw values with no uncertain band, so a 0.55 noul and a
0.40-confidence choice fire automatic actions; the band logic that exists (judge.Bands) is not
reachable from the engine. One pure `Apply` fixes it and yields a §10 Request carrying both answers
+ probabilities.
Does not prove: the right band values per question, score-rule semantics (escalate on
confidence ≤ High is a guess), or that live Jev is ever uncertain on this fixture (it was confident).

## Friction with the Classifier API
1. No band helper in the library; every consumer re-derives 0.30/0.70 and "exactly on the cut".
2. `NearUniform` can't fire on a 4-option choice with top p=0.40 (tolerance ±0.05 around 0.25) —
   the analyst's "pricing@0.40 near-uniform" is impossible; the real signal is `Confidence`.
3. Static setup needs hand-written wire JSON per state, keyed on state+questions+model; no way to
   build a `Decision` with derived `NearUniform` in-process.
4. A missing answer is an accessor error, so wfnexus fails the whole step instead of asking.

## Ask / Gate API (shared contract `shared/`) — the video's Donkey Kong example

Before (today's `tn.Classifier`, 16 lines):

```go
d, err := c.Evaluate(ctx, map[string]any{
	"role":             role,
	"message_received": msg,
}, map[string]tn.Question{
	"is_appropriate": tn.NoulQuestion{
		Instructions: "Does the message contain inappropriate language or topics that are considered harmful.",
	},
	"does_this_help": tn.NoulQuestion{
		Instructions: "Does this help donkey kong win?",
	},
})
a, err := d.Noul("is_appropriate")
ok := a.Noul > 0.70 // every caller re-derives cut-offs and the "exactly on the cut" rule
```

After (`ask.go`, 5 lines):

```go
d, err := Ask(ctx, c, map[string]any{"role": role, "message_received": msg}, []Q{
	Noul("is_appropriate", "Does the message contain inappropriate language or topics that are considered harmful."),
	Noul("does_this_help", "Does this help donkey kong win?"),
})
d["is_appropriate"].Band // "yes" | "no" | "uncertain"; cut-offs: Ask(..., Bands{0.2, 0.5})
```

Also: `Choice(name, instr, options)`, `Score(name, instr, levels...)`, `Msg(context, message, extra)`,
`Gate(ctx, c, state, qs, rules, bands)`. `shared_test.go` asserts every case in both shared JSON files.
