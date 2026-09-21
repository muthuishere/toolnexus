# Spike report — C# / .NET 10

Spike code: `spikes/classifier/csharp/` (`Spike/` lib + `Spike.Tests/` xunit). It project-references
`csharp/src/Toolnexus/Toolnexus.csproj` read-only, so gates 3–4 run against the REAL `Guardrail`
delegate and the real `LoopSupport.GuardedHooks` — nothing in the port was modified. No new package
(`System.Text.Json` + `HttpClient` are stdlib).

## 1. Verdict

**Feasible.** No blockers. The union costs ~75 lines of ordinary records + one read-side
`JsonConverter`; the canonicaliser is 46 lines and is the part that repeats in Java.

## 2. Gate results

| # | Item | Result | Proof |
|---|---|---|---|
| 1 | Byte-exact request | **PASS** | `Gate1_RequestIsByteExact` — 514 bytes, sha256 == `fixture/request.sha256`. Plus `Gate1_GuardRequestsAreByteExact` (3 cases) matching `guard-{allow,ask,deny}-request.json` byte-for-byte. |
| 2 | Parse | **PASS** | `Gate2_ParsesTypedAnswers` — `noul` 0.98, `choice` "shipping" + probabilities {billing .39, shipping .61, technical 0}, `score` 1.21 + legend. |
| 3 | One judge, wired | **PASS** | `Gate3_JudgeBandsTheCommand` (allow/ask/deny) + `Gate3_AsGuardrailEmitsGuardrailShape` — `git status --short` ⇒ `""`, `shutil.rmtree('/')` ⇒ deny reason, `rm -rf ./build` ⇒ ask (v1 fallback: deny-with-reason). |
| 4 | Invariant | **PASS** | `Gate4_JudgeCannotFlipAnEarlierDenial` — `[denyAll, judge.AsGuardrail()]` through the port's own `GuardedHooks`; result is `denied: policy: bash is off` although the judge alone allows that command. |

```
cd spikes/classifier/csharp && dotnet build Spike.Tests/Spike.Tests.csproj
cd spikes/classifier/csharp && dotnet test  Spike.Tests/Spike.Tests.csproj
# Passed! - Failed: 0, Passed: 12, Skipped: 0, Total: 12, Duration: 867 ms
```

The `static` backend is keyed on the **canonical request bytes**, so any body drift misses the
fixture and fails loudly instead of silently returning a stale decision.

## 3. LOC (non-blank, non-comment)

| Piece | File | LOC |
|---|---|---|
| Question types (the union) | `Questions.cs` | 37 |
| Answer types + parse (discriminated map) | `Answers.cs` | 70 |
| **Canonical JSON (counted separately — repeats in Java)** | `CanonicalJson.cs` | **46** |
| Classifier (options + wire + static backend + HTTP) | `Classifier.cs` | 52 |
| `judge` (on/ask/bands/verdict + `AsGuardrail`) | `Judge.cs` | 38 |
| Classifier total (types + canonical + wire) | | **205** |
| Tests | `GateTests.cs` | 188 |

## 4. The union problem

**`criteria`'s three shapes: a closed record hierarchy + `ToWire()`, no `JsonConverter` at all.**
`abstract record Question` with `Noul` / `Choice` / `Score`; each owns a *differently typed*
`Criteria` (`(string,string)?` / `IReadOnlyDictionary<string,string?>` / `IReadOnlyList<string>`)
and projects itself to `Dictionary<string,object?>`. Absent criteria is `null` and is simply not
added to the dictionary. Cost: 37 lines, zero converter, and the shape is checked at compile time —
you cannot construct a `Score` with an object criteria. Alternatives rejected:

- **Polymorphic `JsonConverter<Question>`** — the serializer must go through the canonicaliser
  anyway (keys must sort), so a converter would be a second, parallel write path for no gain.
  STJ's built-in `[JsonDerivedType]` polymorphism also writes the discriminator **first**, not in
  sorted position, which is wrong here outright.
- **`JsonElement`/`JsonNode` for criteria** — kills the compile-time shape guarantee, which is the
  only thing this seam is selling.

A dictionary-projection step (`ToWire`) is the honest cost of "typed API, canonical wire", and it is
9 lines of it. It doubles as the place `null` descriptions (`{name: desc|null}`) stay legal.

**The `answers` map: `abstract record Answer` + one read-only `JsonConverter<Answer>`** dispatching
on `type` (26 lines). `Dictionary<string, Answer>` is exactly the discriminated map; callers do
`Assert.IsType<ChoiceAnswer>` / C# pattern matching (`a switch { NoulAnswer n => …, ScoreAnswer s => … }`)
with exhaustive-ish switches. `Write` throws — answers are inbound only, which halves the converter.
Total union cost, both halves: **107 lines**.

## 5. Canonical JSON

**Hand-rolled — System.Text.Json does not sort object keys on write, and has no option to.**
`CanonicalJson` is 46 lines: a recursive `Utf8JsonWriter` walk that sorts `IReadOnlyDictionary`
keys with `StringComparer.Ordinal` and leaves every `IEnumerable` in source order (`score.criteria`
order is the level numbering). Two traps, both hit:

1. **Encoder.** The default `JavaScriptEncoder` escapes `'` as `'`, and the fixture contains
   `"user's own request"` raw. `JavaScriptEncoder.UnsafeRelaxedJsonEscaping` is required — the same
   one `csharp/src/Toolnexus/Json.cs` already uses, so the port idiom is right by accident.
   The deny fixture's embedded `\"` is handled correctly either way.
2. **Case ordering.** `StringComparer.Ordinal` (ASCII), NOT the default `string.CompareTo`
   (culture-sensitive, and orders `"B"` before `"a"` differently). Easy to get wrong silently.

**Number formatting.** STJ's default `double` writer is shortest-roundtrip and matches the fixture
exactly where it matters: `1.21` → `1.21`, `0.0` → `0` (not `0.0`), `0.98` → `0.98`. Verified by
`Gate2_DoubleRoundTripIsByteStable`. **One real risk:** small magnitudes go scientific —
`0.000016716` re-emits as `1.6716E-05`, so a re-emitted `usage.cost` (or any probability below
~1e-4) would NOT be byte-identical to the fixture. Irrelevant for the request path (no numbers) but
it must not be assumed away if a `Decision` is ever re-serialized for a cache or a transcript. The
request path is safe because the canonical body contains only strings.

## 6. Friction

- **`Guardrail` is synchronous** (`Agents/Loop.cs:9`, `delegate string? Guardrail(BeforeToolEvent)`).
  A network-backed judge must `.GetAwaiter().GetResult()` inside the adapter — sync-over-async on
  the hot path, exactly the async/ctx form ADR 0020 already flags. In C# this is the sharpest edge
  of the whole spike: one line of code, and a deadlock class in any sync-context host.
- **`ask` has nowhere to go** today, so it collapses to deny-with-reason (ADR OQ1). Visible in the
  test as `Assert.Contains("ask", …)` on a *denial* string, which is the smell.
- **The canonicaliser is per-port work.** Neither STJ (C#) nor Jackson (Java, unless
  `ORDER_MAP_ENTRIES_BY_KEYS` + a custom `Include`) sorts by default; Go's `encoding/json` does sort
  map keys, JS `JSON.stringify` does not. Budget ~45 lines × the non-sorting ports, and pin it with
  the fixture in every one, not just in Go.
- **`required` init-only records need C# 11+** — fine on net10.0, no issue for the real port.
- Everything else was free: no dependency added, `dotnet build`/`dotnet test` clean, and the real
  port's `GuardedHooks` accepted the adapter with no change at all.

## 7. Live call

Ran (`OPENROUTER_API_KEY` present): `POST https://openrouter.ai/api/v1/systemone`,
model `typesafe/jev-1.13`, the byte-exact 514-byte body.

- **HTTP 200, 562 ms** (single cold call, includes TLS handshake — well inside the 0.5–0.75 s
  gateway figure in the ADR, well above the 70–500 ms vendor figure).
- Shape matched the recorded fixture exactly: `model=typesafe/jev-1.13-20260917`, answers
  `department` / `is_refund_request` / `urgency`, all three deserialized into the typed records with
  no schema surprises.
