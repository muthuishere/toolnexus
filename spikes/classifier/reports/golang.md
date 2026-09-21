# Spike report — Go (`spikes/classifier/golang/`)

Standalone module `toolnexus.spike/classifier` (own `go.mod`, `replace` onto `../../../golang`
for read-only use of `tn.BeforeToolEvent` / `agents.Guardrail`). No new dependency; net/http only.
Nothing in `golang/` was modified.

## 1. Verdict

**Feasible.** All four gate items pass, plus the optional live call. Go's `encoding/json` gives
the canonical form for free; the two unions cost ~40 lines total and are the only real work.

## 2. The four gate items

All from `spikes/classifier/golang/`: `go vet ./... && go test ./...` — clean, 11/11 pass.

| # | item | result | proving test |
|---|---|---|---|
| 1 | byte-exact request | **PASS** — 514 bytes, sha256 `d5fa5c11…e85daf` matches `fixture/request.sha256` | `TestGate1CanonicalRequestIsByteExact` (+ `…ScoreArrayOrderIsPreserved`, `…NoulCriteriaAbsentVsPresent`, `…KeysSortRecursively`) |
| 2 | parse | **PASS** — `is_refund_request.noul`=0.98, `department.choice`="shipping" + probabilities {billing .39, shipping .61, technical 0}, conf .41; `urgency.score`=1.21, legend in level order | `TestGate2Parse`, `TestGate2FloatRoundTrip` |
| 3 | one judge, wired | **PASS** — `static` backend over `fixture/guard-*.json`: `git status --short` ⇒ `""` (allow), `shutil.rmtree('/')` ⇒ `"denied by judge: risk=2.97 (destructive or irreversible)"`, `rm -rf ./build` ⇒ `"needs approval: risk=2.25 (…)"`. Returns the shipped `agents.Guardrail` type verbatim | `TestGate3JudgeAsGuardrail`, `TestGate3VerdictEvidence`, `TestGate3FailPosture` |
| 4 | invariant | **PASS** — judge composed after a denying rail cannot flip it; a judge that denies still denies when second | `TestGate4JudgeCannotWidenAnEarlierDenial` |

Gate 3 is stronger than asked: the `static` backend is keyed by the **canonical request bytes**,
so a fixture hit is itself a byte-exactness assertion on all three guard requests.

The invariant holds **by construction, not by discipline**: `AsGuardrail` returns `string`, and
the only "allow" value is `""`, which first-deny-wins skips. There is no value a judge can return
that widens an earlier denial — a judge cannot express "allow" at all.

## 3. LOC (non-comment, non-blank)

| unit | lines |
|---|---|
| `Classifier` — question types + canonical marshal + `Decision`/answers decode | 177 |
| `Classifier` — wire (Options, New, Evaluate, static + systemone POST) | 94 |
| **`classifier.go` total** | **271** |
| `judge.go` — Judge, Verdict, 4 rules (bands/one/topK/atLeast), `AsGuardrail`, `Compile` | 142 |
| `spike_test.go` | 257 |

`Bands` itself is 18 lines. A port that only needed bands would be ~200 lines total.

## 4. The union problem (the finding that matters)

**`criteria`'s three shapes: solved with an interface returning `map[string]any`, not with
`MarshalJSON` and not with `json.RawMessage`.**

```
type Question interface { wire() map[string]any }
Noul{Instructions, True, False}   // both empty ⇒ criteria key never added
Choice{Instructions, Criteria map[string]any}  // any, because the wire allows a nil description
Score{Instructions, Criteria []string}         // []any on the wire; order preserved
```

Three concrete structs, one unexported method. Cost: **~35 lines.** Why the alternatives lose:

- **Struct tags + `omitempty`** — rejected. Field order would be declaration order, so canonical
  sorting would be a *comment convention* ("keep these alphabetical") that any future field
  breaks silently. Also `omitempty` cannot distinguish "criteria absent" from an empty object.
- **Custom `MarshalJSON` per type** — works, but each would have to hand-order its own keys.
  Going through `map[string]any` deletes that whole class of bug.
- **`json.RawMessage`** — only useful if you are passing bytes through. Here we *construct*, so
  it buys nothing and costs the type safety.

**The discriminated `answers` map: one `UnmarshalJSON` on `Decision`, a two-pass decode.**
`map[string]json.RawMessage` → peek `{"type"}` → re-decode into one of three structs → store as
`Answer` (a marker interface, `AnswerType() string`). Cost: **~45 lines**, all mechanical, and
one custom unmarshaller for the whole map rather than one per answer type.

The residual Go tax is at the **read** site: `Answer` is an interface, so a caller would otherwise
type-assert. Three typed accessors (`d.Noul(key)`, `d.Choice(key)`, `d.Score(key)`) returning
`(T, error)` hide that; a wrong-type read is an error, never a panic or a silent zero. **12 lines
for all three.** This is the one piece of API surface a port cannot skip — without it the union
leaks to every user.

Total union cost in Go: **~92 lines.** Not a risk. No generics needed; Go 1.23 is not a constraint.

## 5. Canonical JSON

**Free. `encoding/json` sorts map keys recursively in ASCII (byte) order, and never touches slice
order** — which is exactly the spec's "sort keys recursively, never sort arrays". The whole
canonicaliser is 8 lines. The one non-default: `json.Encoder` with `SetEscapeHTML(false)` — Go
escapes `<` `>` `&` to `<` etc. by default, which would corrupt any instruction containing
them. (The fixture has none, so this would pass CI and break on a real user's prompt. Named
because the other six ports have the same trap in reverse — most do *not* escape by default.)
`Encoder.Encode` always appends `\n`; trimmed.

**Float formatting: exact, no drift.** Go marshals `float64` with shortest-round-trip formatting.
`1.21` → `1.21`, the `technical: 0` probability → `0` (not `0.0`, not `0e+00`), and the awkward
`cost: 0.000016716` → `0.000016716`. Pinned by `TestGate2FloatRoundTrip` against a literal string.
One caveat for the other ports, not hit here: Go switches to `e` notation below 1e-6 / above 1e21,
so a probability of `1e-7` would emit `1e-07`. Probabilities and scores cannot reach that range,
but a `cost` echo could — do not re-emit floats you parsed unless you must.

## 6. Friction

- **No blocker.** No SDK, no dependency, stdlib only.
- The `Guardrail` seam is `func(ev tn.BeforeToolEvent) string` — **synchronous, no ctx**. The
  judge does a 70–500 ms network call inside it, so today it must use `context.Background()`.
  ADR 0020 D4 already flags "`Guardrail` gains an async/ctx form"; this spike confirms it is
  required, not cosmetic — without ctx a judge cannot honour the run deadline or be cancelled.
- The `ask` band has nowhere to go today. The spike renders it as a deny with an "needs approval"
  reason (plus an `onAsk` callback seam). The §10 `Pending` route in D4 is unbuilt work.
- **`state` shape is the security posture and must stay the host's.** `Judge.On` maps
  `BeforeToolEvent` → state; nothing else leaves. Keeping this a required field (not defaulted to
  "the whole event") is the difference between a judge and a data exfiltration path.
- Cross-port: the risk is NOT the union, it is **canonicalisation**. Go, Python (`sort_keys=True`)
  and Elixir are cheap; JS `JSON.stringify` needs a hand-rolled recursive sort; Java/C# need an
  ordered-map or a custom writer; every port needs the escaping and float-format checks above.
  Budget the shared conformance fixture, not the types.

## 7. Live call

Ran (optional gate 5) — `OPENROUTER_API_KEY` present in the environment. 3 calls to
`https://openrouter.ai/api/v1/systemone`, model `typesafe/jev-1.13`:

- latency **851 ms / 352 ms / 416 ms** (first call includes TLS handshake)
- `model` echoed `typesafe/jev-1.13-20260917`, 3 answers, all three shapes decoded by the same
  `UnmarshalJSON` that reads the recorded fixture — **the live shape matched the fixture exactly.**

`go test -run TestLiveOptional -v -count=3 ./...`. The key is read by name at call time from the
env, never printed, never written to a file.
