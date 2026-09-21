# Spike report — JS / TypeScript

Code: `spikes/classifier/js/`. Node v24.18.0, native TS type-stripping, `node:test`, native
`fetch`. **Zero runtime dependencies**; `typescript` + `@types/node` used only for one
type-check pass (`--no-save`, removed after).

## 1. Verdict

**Feasible.** All four gate items pass, plus the optional live call. One cast in the whole
spike, and it buys generic ergonomics, not correctness. One real integration friction: the
shipped `Guardrail` type is synchronous and a judge is a network call.

## 2. The four gate items

| # | item | result | proof |
|---|---|---|---|
| 1 | byte-exact request | **PASS** | `node --test` → *gate 1* — 514 bytes, `deepEqual` vs `fixture/request.json`, sha256 == `fixture/request.sha256` |
| 2 | parse | **PASS** | *gate 2* — `noul` 0.98, `choice` "shipping" + probabilities map, `score` 1.21 + legend; *gate 2b* — wrong type and unknown type both throw |
| 3 | one judge, wired | **PASS** | *gate 3* — static backend keyed on the canonical bytes: `git status --short` ⇒ allow (`""`), `shutil.rmtree('/')` ⇒ deny `risk 2.97 — destructive or irreversible`, `rm -rf ./build` ⇒ ask; *gate 3b* — fail-open default, fail-closed on request |
| 4 | invariant | **PASS** | *gate 4* — deny-then-judge stays denied and the judge is **never invoked** (short-circuit); judge-then-deny also denied |
| 5 | live | **PASS** | `node live.ts` — see §7 |

```
cd spikes/classifier/js && node --test          # 8/8 pass, ~62 ms
npm i --no-save typescript@5 @types/node@24 && ./node_modules/.bin/tsc --noEmit   # clean
OPENROUTER_API_KEY=… node live.ts
```
`tsconfig.json` is `strict` + `noUncheckedIndexedAccess`.

## 3. LOC (non-blank, non-comment)

| unit | LOC |
|---|---|
| `Classifier` wire + parsing (`classifier.ts`) | 106 |
| types / union / constructors (`types.ts`) | 41 |
| canonical JSON (`canonical.ts`) | **9** — total **156** |
| `judge` (on/ask/rule + `bands` + `asGuardrail`) (`judge.ts`) | **56** |
| tests (`spike.test.ts`) | 173 raw |

Of the 106 in `classifier.ts`, **51 are hand-written runtime validation** (`num`/`str`/`numMap`/
`strMap`/`parseAnswer`/`parseDecision`). That is the tax for refusing a schema dependency (zod);
it is the part that will look different in every port.

## 4. The union problem

**Questions.** `criteria`'s three shapes are *not* modelled as one field with three types. Each
union member declares its own, so the discriminant does the work:

- `noul` — `criteria?: {true, false}` (optional; absent in the fixture)
- `choice` — `criteria: Record<string,string>`
- `score` — `criteria: string[]`

Cost: **zero.** `switch (q.type)` narrows each branch to exactly one `criteria` type
(`exhaustive.ts`, `never` default; deleting a `case` is TS2345). Three constructors
(`noul()`/`choice()`/`score()`) mean a caller never writes `type` by hand and cannot pair the
wrong `criteria` with it.

**Answers.** `Record<string, Answer>`, `Answer` discriminated on `type`. Two findings:

1. **`unknown` → union with no cast, by *constructing* rather than asserting.** `parseAnswer`
   validates fields and returns a fresh object literal per branch; TS checks it against the
   union. An `isAnswer(x): x is Answer` predicate would have needed a cast inside it.
2. **Generic discriminant lookup is the one place TS gives up.** `expect(d, "urgency", "score")`
   returning `Extract<Answer, {type: T}>` does **not** typecheck: TS narrows a union against a
   string *literal*, not against a generic parameter `T`, so after `if (a.type !== type)` the
   value is still `Answer` (TS2322, reproduced). One `as Extract<…>` fixes it. Non-generic
   accessors (`asScore(d, key)` ×3) would be cast-free at the cost of three near-identical
   functions. **So: exhaustive narrowing yes, zero casts yes — except that one generic
   accessor, which is ergonomics, not safety.** Its callers still get full narrowing:
   `answerFor(d,"urgency","score").legend` compiles, `.noul` on it does not.

## 5. Canonical JSON

**Hand-rolled, 9 lines.** `JSON.stringify` preserves insertion order and has no sort option;
its `replacer` array applies one key list at every depth, so it cannot do a recursive sort
either. The recursion is: primitives → `JSON.stringify`, arrays → map **without sorting**,
objects → `Object.keys().filter(≠undefined).sort()`. `Array.prototype.sort()`'s default
comparator is UTF-16 code-unit order, which equals ASCII order for ASCII keys — no comparator
needed. `score.criteria` survives because the array branch never touches order (*gate 1b*).

**Float formatting: no risk in JS.** Number→string is ECMA-262 `Number::toString`, the shortest
decimal that round-trips to the same double — `1.21` re-emits as `1.21` (never `1.2100000001`)
and the integer-valued `0` probability as `0`, not `0.0`. Re-serialising the *whole* parsed
`response.json` reproduces `1.21`, `"technical":0`, `0.000016716` (*gate 1c*). Caveats for
other ports: JS has no int/float split, so a port holding `0` as a float must suppress `.0`;
`-0` emits as `0` here.

## 6. Friction

1. **`Guardrail` is synchronous; a judge is I/O.** `js/src/agents/loop.ts:22-27` types it
   `(ev) => string | undefined | void`, and `guardedHooks` (`:67-82`) calls `g(ev)` **without
   awaiting**. A judge returning a `Promise` would be truthy on every call ⇒ **deny everything,
   silently**. Shipping needs `Guardrail` widened to `… | Promise<string|undefined|void>` and
   one `await` in `guardedHooks`. Backwards-compatible, but it is a SPEC §8 change in all seven
   ports, and the failure mode if a port forgets is fail-closed-silently.
2. **Three outcomes vs two.** The judge returns allow/**ask**/deny; `Guardrail` has allow/deny.
   `asGuardrail` collapses ASK into a deny-with-reason. A real ASK wants the §10 suspension path
   (`waitFor`) — which is why `Verdict` is its own type here.
3. **Latency is now inside `beforeTool`.** 457 ms of network on every matched tool call. `on`
   filtering and a per-run cache are not optional extras.
4. **Node's type-stripping loader is strip-only** — parameter properties, enums, namespaces are
   `ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX` (hit on `constructor(private readonly opts)`). Irrelevant
   to the built port, but it bites spikes and examples.
5. **The 51 lines of hand-rolled validation** are the per-port cost that will not amortise.

## 7. Live call

`node live.ts` against `https://openrouter.ai/api/v1/systemone`, model `typesafe/jev-1.13`,
key read from `OPENROUTER_API_KEY` at call time.

```
bytes match fixture: true
latency 457ms  model=typesafe/jev-1.13-20260917
noul 0.98        choice shipping        score 1.23  legend {0:routine,1:elevated,2:urgent}
usage { input_tokens: 398, output_tokens: 72, cost: 0.000016716 }
```

Shape matched `parseDecision` with no changes. `score` came back **1.23** vs the fixture's
**1.21** — the wire is stable, the values are not, so no conformance test may assert an exact
live number. `input_tokens` (398) and `cost` matched the fixture exactly.
