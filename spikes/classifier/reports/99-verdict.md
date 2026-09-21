# Cross-language feasibility verdict — ADR 0020 `Classifier` + `judge`

Seven independent spikes, 2026-09-20. Each port implemented the same four-item gate against the
same fixtures, in its own idiom, with no shared code. Per-port detail in the sibling reports;
live-backend behaviour in `00-live-backend.md`.

## Verdict: FEASIBLE in all seven. No blockers. Proceed to `/opsx:propose add-judge`.

| port | verdict | gates | `Classifier` LOC | `judge` LOC | canonical JSON | live latency |
|---|---|---|---|---|---|---|
| Clojure | feasible | 4/4 | 68 | 25 | **native** (koine) | 537 ms JVM · 1326 ms cljgo |
| Python | feasible | 4/4 | 145 | 67 | **native** (`sort_keys`) | 468 ms |
| JS | feasible | 4/4 | 156 | 56 | 9 lines | 457 ms |
| Elixir | feasible | 4/4 | 163 | 53 | 17 lines | 581 ms |
| C# | feasible | 4/4 | 205 | 38 | 46 lines | 562 ms |
| Java | feasible | 4/4 | 264 (total) | 60 | 74 lines | 870 ms |
| Go | feasible | 4/4 | 271 | 142 | 8 lines (native sort) | 352–851 ms |

Every port ran the optional live call and matched the recorded shape with **no adaptation**.
Clojure verified byte-identical output on **three** modes (JVM, cljgo AOT, cljgo interpreted).
Python, C#, Elixir and Clojure ran gate 4 against the **real shipped** `guarded_hooks`, not a copy.

**The union was never the problem.** Every statically-typed port modelled `criteria`'s three
shapes with a closed/sealed hierarchy and *zero casts on the write side* — Java explicitly has
no `Object criteria` anywhere. The dynamic ports paid nothing at all (Clojure: "zero lines beyond
the three constructors"). The real cost is **canonical JSON**, and it is concentrated in Java (74),
C# (46) and Elixir (17): neither Jackson, System.Text.Json, Jason nor OTP's built-in `JSON` can
emit a sorted-key form with correct number formatting.

## The four findings that change the plan

### 1. `ask` already has a home — PROVEN. This deletes the ADR's biggest cost.

Java sized the `ask`→§10 route as *"an interface change in all seven ports and the biggest cost
in ADR 0020 — larger than `Classifier` itself."* It is not needed.

`golang/client.go:546-548` documents a *"guard-raised suspension — path B"* that `SPEC.md` never
states and no test exercises. Two tests (`spikes/classifier/golang/pathb_test.go`) settle it
against the **shipped** client:

```
status="done"     asked="approve deploy to prod?"          ← hook-raised pending, resolved inline by WaitFor
status="pending"  pending={"id":"r2","kind":"approval",…}  ← durable halt, no WaitFor
```

A `BeforeTool` hook returning a pending-carrying `ToolResult` suspends the run exactly like a tool
does. JS/Python/C#/Java all have the same short-circuit→`pendingOf` structure.

**Consequence:** `.asGuardrail()` compiles to a **`BeforeTool` hook**, not to a `Guardrail`, and
the three bands land as allow ⇒ pass through · deny ⇒ `isError` result · **ask ⇒ pending
Request**. Zero contract change. The undocumented behaviour should be written into `SPEC.md §10`
and given a test in all seven ports — that is a small change worth making on its own.

### 2. An async guardrail silently denies EVERY tool call — shipped, today

Reproduced verbatim from the shipped code paths:

| port | async guardrail that intends ALLOW | result |
|---|---|---|
| JS (`agents/loop.ts:73`) | `async (ev) => ""` | `denied: [object Promise]` |
| Python (`agents/loop.py:102`) | `async def rail(ev): return ""` | `denied: <coroutine object …>` + `RuntimeWarning: never awaited` |

`verdict = g(ev)` is not awaited; a Promise/coroutine is truthy and `!== "allow"`, so it takes
the deny branch. The *same function* correctly awaits the prior hook
(`return await out if inspect.isawaitable(out)`), so this is an oversight, not a design.

The declared type is synchronous, so today this is user error — but the failure is silent,
fail-closed, and produces a garbage reason. **It becomes a migration hazard the moment the
classifier work widens `Guardrail`**: any port that gets the widened type without the `await`
denies everything. If `Guardrail` is widened, the `await` must land in the same commit in all
seven ports.

Given finding 1, the cheapest path is to **not widen `Guardrail` at all** — judges ride
`BeforeTool`, which is already async everywhere.

### 3. The fixture is inadequate in two ways that let a broken port pass

- **It contains no numbers.** Java: Jackson round-trips `request.json` byte-identically, so
  gate 1 passes on Jackson — yet Jackson emits `0.0` for `0` and `1.6716E-5` for `0.000016716`.
  A port can pass the gate with an emitter that is broken for every response and every
  numeric state.
- **Elixir passes by accident at ≤32 keys.** Erlang small maps iterate in term order, which *is*
  byte order — so naive `Jason.encode!` passes this fixture and goes arbitrary at **33 keys**.
  A 33-option `probabilities` map would break conformance with every test green.

Hardened fixture added (`request-hard.json`, 640 bytes): `<>&` unescaped (Go needs
`SetEscapeHTML(false)`; C# needs `UnsafeRelaxedJsonEscaping` — its default escapes `'`), unicode
unescaped, ASCII key order across `10_alpha < Alpha < beta < café` (C# needs
`StringComparer.Ordinal`, not the culture-sensitive default), a reverse-alphabetical
`score.criteria` array that must not be sorted, and absent-vs-empty `criteria`.
**Still required:** a numbers fixture (`0`, `1.21`, `0.000016716`) and a >32-key map fixture.

### 4. Numbers do not canonicalise across languages — so `state` cannot be in the claim

Same values, five serializers:

| value | Python | Node | Go | Elixir | jq |
|---|---|---|---|---|---|
| `0.1`, `1.21`, `0` | agree | agree | agree | agree | agree |
| `-0.0` | `-0.0` | `0` | `-0` | `-0.0` | `-0.0` |
| `1e-5` | `1e-05` | `0.00001` | `0.00001` | `1.0e-5` | `0.00001` |
| `1e21` | `1e+21` | `1e+21` | `1e+21` | `1.0e21` | `1E+21` |

Simple decimals agree everywhere; anything else diverges three or four ways. Independently,
C# and Java both hit the small-magnitude exponent problem, and Elixir flagged the inverse risk —
`"technical":0` is an **integer**, and a port typing that map as `double` emits `0.0` and fails.

**Consequence:** the byte-identity conformance claim covers the **`questions` structure**
(strings and shape — which is what the claim is actually about) plus `model`. **`state` is passed
through as the host supplied it** and is explicitly outside the claim. Say so in `SPEC.md §8B`,
or the claim is false the first time a caller puts a float in their state.

## Revisions required to ADR 0020 before proposing

1. **D4 — `.asGuardrail()` targets `BeforeTool`, not `Guardrail`.** Three bands, `ask` ⇒ §10
   Pending. Open question 1 is **closed**; do not widen `Guardrail`.
2. **D2 — scope the canonical claim** to `questions` + `model`; `state` passes through verbatim.
   State the rule as "sort objects recursively, never reorder arrays".
3. **D4 — add a threshold-clearance rule.** Jev is non-deterministic (σ ≈ 0.015, spread 0.05 on
   0–3); band boundaries need ≥0.1 clearance or hysteresis. Binary `noul` was the most stable
   primitive (0.98 across every run).
4. **D3 — promote `calibrated` to a policy input.** `style:"llm"` is 3.3–4.4× slower, 2.5–3.4×
   costlier, returns no distribution, and disagreed outright on one fixture. `bands` should
   refuse an uncalibrated backend unless the policy opts in.
5. **Record that OpenRouter serves Jev today** with no TypeSafe waitlist key — early access is
   off the critical path, and CI gains an optional live-smoke path.
6. **Un-defer nothing else.** The `bash` env-inheritance and tool-idempotency gaps from the ADR's
   Context section remain out of scope and still outrank the adapters change.

## Sizing

`Classifier` is 68–271 LOC per port (median ~163) and `judge` 25–142 (median ~56). The canonical
emitter is the only genuinely repeated cost, and only in three ports. Two ports need no
canonicaliser at all. This is comfortably the smallest cross-port surface since HTTP tools —
`add-judge` (contract + fixtures, seven ports) is a realistic single change, with
`add-judge-adapters` behind it.
