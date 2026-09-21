# Spike report — Elixir

Run: `cd spikes/classifier/elixir && mix test` (8 tests, 0.05s). Optional live call:
`mix run live.exs`. Elixir 1.20.2 / OTP 29. Deps mirror the real port exactly —
`jason`, `req`, plus a `path:` dep on `../../../elixir` so gate 4 runs against the
SHIPPED guardrail compiler rather than a copy. No new dependency was needed.

## 1. Verdict

**Feasible.** Easiest of the ports so far. The union that costs the typed languages an
interface hierarchy costs Elixir three structs going out and three function clauses
coming in. The only hand-rolled machinery is a 17-line canonical JSON encoder, and it is
needed for a non-obvious reason (§5).

## 2. The four gate items

| # | Gate | Result | Proof |
|---|------|--------|-------|
| 1 | Byte-exact request | **PASS** | `mix test` — `gate 1`: bytes == `fixture/request.json`, 514 bytes, sha256 == `fixture/request.sha256`. Plus `gate 1b` (array order preserved) and `gate 1c` (naive Jason diverges past 32 keys). |
| 2 | Parse | **PASS** | `gate 2`: `is_refund_request.noul` 0.98, `department.choice` "shipping" + probabilities map, `urgency.score` 1.21 + legend. `gate 2b` pins float/integer re-emission. |
| 3 | One judge, wired | **PASS** | `gate 3`: `git status --short` ⇒ `{:allow, ""}`, `rm -rf ./build` ⇒ `{:ask, "ask: risk 2.25 — hard to undo, …"}`, `shutil.rmtree('/')` ⇒ `{:deny, "deny: risk 2.97 — destructive or irreversible"}`. `gate 3b` additionally asserts the judge's own outbound bytes equal all three `guard-*-request.json`. |
| 4 | Invariant | **PASS** | `gate 4`: `Toolnexus.Agents.Loop.guarded_hooks([always_deny, judge_that_allows], nil)` (the real `elixir/lib/toolnexus/agents/loop.ex:147`) still returns `denied: policy: bash is off`. Reversed order also denies. |
| 5 | Live call (optional) | **PASS** | `mix run live.exs` — 581 ms, HTTP 200, `model=typesafe/jev-1.13-20260917`, all three answer shapes parsed; values identical to `fixture/response.json` apart from `department.confidence` (0.42 live vs 0.41 fixture). |

## 3. LOC

Blank lines, comments and doc strings excluded.

| Unit | LOC |
|------|-----|
| `Classifier` — 7 structs, wire form, parse, static + live backends | **93** (`lib/classifier.ex`) |
| Canonical JSON encoder | **17** (`lib/canonical.ex`) |
| `judge` — on / ask / state / rule bands / guardrail shape | **53** (`lib/judge.ex`) |
| **Total non-test** | **163** |
| Tests | 138 (`test/classifier_spike_test.exs`) |

## 4. The union problem — the finding that matters

**`criteria`'s three shapes: three structs, no tagging machinery.**
`%Noul{instructions:}` (no `criteria` field at all — absence is structural, so the
"criteria must be ABSENT not null" rule cannot be violated), `%Choice{criteria: map}`,
`%Score{criteria: list}`. Dispatch to the wire form is three one-line function clauses
on the struct pattern. Cost: **9 lines.** No sum type, no visitor, no `oneOf`, no custom
encoder per shape.

**The discriminated `answers` map: literally three lines.**

- `defp answer(%{"type" => "noul"} = a), do: …` — matching on `%{"type" => "noul"}` is
  the native form of the discriminator. Confirms the expectation: this is the cheapest
  port for the union.
- An unknown `"type"` raises `FunctionClauseError` at the parse site with the offending
  map printed. That is the right default (a new answer kind is a contract change), but
  a shipping implementation wants an explicit `defp answer(other)` clause so the error
  names the classifier rather than the module.
- Because maps are structural, `Map.new(answers, fn {k, a} -> {k, answer(a)} end)`
  builds the heterogeneous map with no type parameter anywhere — the thing Go/Java/C#
  need `any`/`Object`/`JsonElement` + a cast for.

Elixir pays a different, smaller price: **no compile-time exhaustiveness**. Dialyzer will
not tell you that a fourth question shape was added and one `wire/1` clause is missing;
the test suite has to. That is the trade the ports are making in both directions.

## 5. Canonical JSON

**Hand-rolled, and the reason is a trap worth naming for the other BEAM-adjacent ports.**

- Neither `Jason` nor OTP 27+'s built-in `JSON` module sorts keys. Both emit in Erlang
  map-iteration order.
- That order *happens* to be ASCII-sorted for maps of **≤ 32 keys**, because small maps
  are flatmaps that store keys in term order, and term order on binaries is byte-wise.
  A naive `Jason.encode!(request)` therefore **passes this fixture by accident.**
- At 33 keys the map becomes a hashmap and the order goes arbitrary. Test `gate 1c`
  pins this: `Jason.encode!/1` and the canonical encoder disagree on a 40-key map, and
  the canonical output is invariant under shuffling the insertion order. A 33-band
  `probabilities` map or a large `criteria` object would silently break conformance in
  production while every test stayed green. **Do not let any port rely on it.**
- The encoder delegates only scalar leaves to `Jason.encode!/1`, so string escaping and
  float formatting stay the library's problem. 17 lines.
- Also confirmed OTP's built-in `JSON` is available (OTP 29) and equally unsorted — the
  real port uses Jason, so the spike does too.

**Float formatting: no risk observed.** `Float.to_string(1.21) == "1.21"`; Jason uses
Erlang's shortest-round-trip float printer, so `1.21` re-emits as `1.21`, never
`1.2100000001`.

**Integer `0` is the real formatting risk, not floats.** The fixture writes
`"technical":0` and `"2":0` — integers, not `0.0`. Elixir's decoder preserves that
distinction (`is_integer/1` asserted in `gate 2b`), so re-emission is byte-stable *as
long as the struct fields stay untyped*. A port that declares `probabilities` as
`map[string]float64` (Go) or `Dictionary<string,double>` (C#) will re-emit `0.0` and
break the byte check. Elixir's looseness is an advantage here.

## 6. Friction

Low. Four notes:

1. **The ≤32-key sorting accident (§5)** is the one item that could ship broken. It
   needs an explicit >32-key test in the real suite, not just a fixture comparison.
2. **No exhaustiveness checking.** Adding a fourth question or answer shape is a
   runtime failure, not a compile error. Needs a test per shape by convention.
3. **`Req` vs the canonical bytes.** `Req.post!` must be handed `body:` (a pre-encoded
   binary), never `json:` — `json:` would re-encode through Jason and lose key ordering.
   Easy to get wrong; worth a comment at the call site in the real port.
4. **Syntax-only:** `~s(...)` sigils containing both quotes and nested parens
   (`~s(python3 -c "… rmtree('/')")`) fail to parse; use an escaped `"…"` string for
   the shell-command fixtures. Cost: two minutes.

Nothing here argues against shipping. The parts that are expensive in Go/Java/C# —
modelling the union, casting out of the heterogeneous map — are close to free, and the
part that is free elsewhere — canonical key ordering — is the one thing to hand-roll.

## 7. Live call

`mix run live.exs` against `https://openrouter.ai/api/v1/systemone`, model
`typesafe/jev-1.13`, key read from `OPENROUTER_API_KEY` at call time (never printed,
never written). **581 ms**, HTTP 200. Response shape matched `fixture/response.json`
field-for-field; `is_refund_request.noul` 0.98, `department.choice` "shipping",
`urgency.score` 1.21 with the same legend and probabilities. Only `department.confidence`
differed (0.42 vs 0.41).
