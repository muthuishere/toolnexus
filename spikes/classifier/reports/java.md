# Spike report — Java

## 1. Verdict

**Feasible.** Four gate items pass plus the optional live call. No new dependency; the only
hand-rolled part is a 74-line canonical JSON writer. Union modelling is cheap — sealed interface
+ records + pattern-matching `switch`, the idiom the real port already uses.

## 2. Gate

Build/run — no Gradle; JEP 458 multi-file source mode, Java 26.0.1, Jackson 2.18.2 taken off the
port's own Gradle cache:

```
cd spikes/classifier/java
CP=$(find ~/.gradle/caches -name 'jackson-databind-2.18.2.jar' -o -name 'jackson-core-2.18.2.jar' -o -name 'jackson-annotations-2.20.jar' | tr '\n' ':')
java -cp "$CP" Spike.java
```

| # | item | result | proof |
|---|---|---|---|
| 1 | byte-exact request | **PASS** | 514 bytes, sha256 `d5fa5c11…` == `fixture/request.sha256` |
| 2 | parse | **PASS** | `noul=0.98`, `choice=shipping` + probabilities `{billing:0.39, technical:0, shipping:0.61}`, `score=1.21`, legend `{0:routine,1:elevated,2:urgent}` |
| 3 | judge, wired (static backend) | **PASS** | `git status --short`→allow, `shutil.rmtree('/')`→deny, `rm -rf ./build`→ask |
| 4 | invariant | **PASS** | judge that would rule `allow`, composed after a denying guardrail → `policy: bash is off-limits` |
| 5 | live call (optional) | **PASS** | 870 ms, `typesafe/jev-1.13-20260917`, shape matched |

Gate 3 bands: `allow < 1.0 ≤ ask < 2.5 ≤ deny` on `risk` (fixture scores 0.02 / 2.25 / 2.97). The
static backend keys on the **canonical request bytes**, so gate 3 re-proves gate 1 three more times.

## 3. LOC (code lines; blanks and comment-only lines excluded)

| unit | file | LOC |
|---|---|---|
| `Classifier` — types (`Question`, `Answer`, `Decision`, `Usage`) | Classifier.java:24-108 | **61** |
| `Classifier` — wire build + response parse | Classifier.java:109-156 | **40** |
| `Classifier` — backends (`static` + `systemone` POST) | Classifier.java:157-190 | **29** |
| **canonical JSON emitter (hand-rolled)** | Canon.java | **74** |
| `Judge` (on/ask/rule/bands/One + `asGuardrail` + first-deny-wins) | Judge.java | **60** |
| spike harness (not shippable) | Spike.java | 131 |

Shippable total ≈ **264** LOC. The feasibility number — the hand-rolled serializer — is **74**,
~30 of which are string escaping every port needs once.

## 4. The union problem

`criteria`'s three shapes cost **nothing structural**. The union is the sealed hierarchy, not a
field:

```java
sealed interface Question permits Question.Noul, Question.Choice, Question.Score {
    String instructions();
    Map<String,Object> wire();
}
record Noul  (String instructions, String whenTrue, String whenFalse)   // criteria ABSENT unless both set
record Choice(String instructions, Map<String,String> criteria)         // OBJECT
record Score (String instructions, List<String> criteria)               // ARRAY, order = level number
```

- Each shape is its **own record component with its own static type** — no `Object criteria`, no
  runtime cast anywhere in the Classifier. Absent `criteria` (noul) is one `if` in `Noul.wire()`.
- Compact-constructor validation is one line each: `Choice` rejects >255 options, `Score` rejects
  outside 2..10, both at construction.
- `answers` — the heterogeneous map discriminated by `type` — is `Map<String, Answer>` over a
  second sealed interface, built with a pattern-matching `switch` on the `type` string
  (`Classifier.java:133-145`, an arrow switch with a `default -> throw`; exhaustive by
  construction). Read-out is typed: `d.noul("is_refund_request")`, `d.choice("department")`,
  `d.score("urgency")`, each throwing a named `IllegalStateException` if the key answered with a
  different type. 12 lines for all three accessors.
- Verdict: **not ceremony.** `LlmClient.MetricEvent` in the real port is exactly this shape, so
  a reviewer of this repo expects it. Nothing is generic, so no variance problem arises.

Real friction: record components cannot be optional, so `Noul(instructions)` needs a 1-line
secondary constructor; and the typed accessors must live on `Decision`, not `Answer`, because Java
cannot narrow a union at the map-get site.

## 5. Canonical JSON

**Jackson sorts keys natively and recursively** — `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`
round-trips `fixture/request.json` byte-identically (verified). Compact separators are Jackson's
default. So for the **request** direction Jackson alone would pass gate 1, because
`request.json` contains no numbers.

**It fails on numbers**, which is why `Canon.java` exists. Jackson (and `Double.toString`) emit:

| value | Jackson | canonical |
|---|---|---|
| `0` (a zero probability) | `0.0` | `0` |
| `0.000016716` (usage cost) | `1.6716E-5` | `0.000016716` |
| `1.21` | `1.21` | `1.21` ✓ |

`Canon.number` fixes it in 9 lines: integral doubles under 1e21 → `BigDecimal.valueOf(d).toBigInteger()`
(`0`); everything else → `BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()` (`0.000016716`).
`BigDecimal.valueOf` is load-bearing — it routes through the shortest round-trip `Double.toString`,
where `new BigDecimal(double)` would print 50 digits of binary noise. No precision loss: `1.21`
re-emits as `1.21`, asserted in gate 2.

> Not covered: the two ECMAScript exponent thresholds. `1e21` prints as `1000000000000000000000`
> (ECMA: `1e+21`) and `1e-7` as `0.0000001` (ECMA: `1e-7`). ~4 more lines, or a documented range
> limit — probabilities, scores and token counts never reach either threshold.

Parsing is Jackson (`Json.toMap` in the real port) — numbers land as `Double`/`Integer`, and
`((Number) o).doubleValue()` normalises. No `BigDecimal` reader config needed.

## 6. Friction — what would make this expensive in seven ports

1. **The canonical emitter is per-port work, not per-port-trivial.** Java needed 74 lines because
   no stdlib or Jackson mode produces ECMAScript numbers. Go's `encoding/json` sorts map keys but
   also emits `1e-05`; C# `System.Text.Json` sorts nothing. Budget a hand-rolled writer in most
   ports and **pin it with a shared number-formatting fixture** (0, 1.21, 0.000016716, 1e21) —
   the request fixture alone does not exercise it — a hole in the current fixture set.
2. **`ask` has no home in `Guardrail`.** The shipped `Loop.Guardrail` is `allow | deny-with-reason`
   (`java/.../Loop.java:35-37`) — two states for a three-band verdict. The spike encodes ask as a
   `"ask: …"` deny string, which is a placeholder, not a design. ADR 0020 says it becomes a §10
   `Pending`; that is an interface change to `Guardrail` in all seven ports and is the largest
   single cost in the ADR, larger than `Classifier` itself.
3. Fail-open/fail-closed must be explicit per judge — done here with one `failClosed` flag
   defaulting to closed (`Judge.rule` catches, returns `deny`). Every port must default the same
   way or the same config denies in one language and allows in another.

## 7. Live call

`POST https://openrouter.ai/api/v1/systemone`, model `typesafe/jev-1.13`, key read from
`OPENROUTER_API_KEY` at call time. **870 ms**, HTTP 200, shape matched `fixture/response.json`
exactly (`department=shipping`, `urgency=1.24` vs the fixture's `1.21`). `java.net.http.HttpClient`,
no SDK, 14 lines including the timeout.
