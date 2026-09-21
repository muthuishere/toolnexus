# ADR 0020 — `Classifier` is to judgments what `Tool` is to actions: a typed-decision seam, with Jev as the first backend

- **Status:** **Proposed — SPIKED 2026-09-20, all seven ports, gate met.** The first draft was
  argued from research and said so. It has now been built seven times against live fixtures:
  every port passes all four gate items, and the optional live call ran in each.
  **Two of the ADR's own claims did not survive** — the `ask`-band cost (deleted: the mechanism
  already ships) and the scope of the byte-identity claim (narrowed: numbers do not canonicalise
  across languages). Both are corrected below. Evidence: `spikes/classifier/reports/` —
  `99-verdict.md` (cross-language), `00-live-backend.md` (live behaviour), one report per port.
- **Date:** 2026-09-20
- **Driver:** TypeSafe's Jev (early access 2026-09-15) made a *typed, calibrated, sub-second
  judgment* a product for the first time — and its wire is already a de-facto standard
  (OpenRouter Decisions, Cloudflare Workers AI, open-weights Laya, self-hosted openjev,
  TypeSafe's own chat-LLM emulator). toolnexus has a hole exactly this shape.
- **Research:** `docs/references/jev-classifier-research-2026-09-20.md` (wire contract, limits,
  SDK matrix, every in-the-wild agent pattern, the internal seam map). This ADR decides; that
  document evidences.
- **Related:** ADR 0014 (single-slot hooks), 0016/0017 (orchestration is a host layer), 0018
  (`afterLLM` observe-only; the completion verifier), 0019 (one transport seam). `SPEC.md
  §1` (Tool), `§3` (skills), `§7D` (runtime), `§8` (client, hooks, routing), `§10` (suspension).
  `_never-built/add-governed-execution-layer`.

## Corrections after the spike

Each was falsifiable; two were falsified.

**1. The `ask` band costs nothing. The mechanism already ships.** The draft's open question 1
asked whether `Guardrail` must grow a `(verdict, *Request)` form, and the Java spike sized that
route as *"an interface change in all seven ports — the biggest cost in ADR 0020, larger than
`Classifier` itself."* Both were wrong. `golang/client.go:546-548` already documents a
*"guard-raised suspension — path B"*, unstated in `SPEC.md` and untested anywhere. Two tests
against the **shipped** client (`spikes/classifier/golang/pathb_test.go`) settle it: a
`BeforeTool` hook returning a pending-carrying `ToolResult` suspends the run exactly as a tool
does — resolved inline by `WaitFor` (`status="done"`), or halting durably with the hook's own
`Request` (`status="pending"`). JS/Python/C#/Java share the same short-circuit→`pendingOf`
structure. **So `.asGuardrail()` targets `BeforeTool`, not `Guardrail`, and `Guardrail` is not
widened at all.** Open question 1 is closed; D4 is rewritten.

**2. The byte-identity claim was too broad.** The draft claimed a byte-identical request body.
Numbers do not canonicalise across languages: `-0.0` renders four ways (`-0.0` / `0` / `-0` /
`-0.0`), `1e-5` three ways, `1e21` three ways; only simple decimals agree everywhere. Java and C#
independently hit the small-magnitude exponent problem, and Elixir the inverse (`"technical":0`
is an **integer**; a port typing it as `double` emits `0.0` and fails). **The claim now covers
`questions` + `model` only; `state` passes through verbatim as the host supplied it.**

**3. The fixture was inadequate and would have passed a broken port.** It contains no numbers, so
Jackson round-trips it byte-perfectly while emitting `0.0` for `0` and `1.6716E-5` for
`0.000016716`. Worse, Elixir passes it *by accident*: Erlang small maps iterate in term order,
which is byte order, up to 32 keys — a 33-option `probabilities` map would break conformance with
every test green. A hardened fixture is added (`<>&` unescaped, unicode, ASCII key order,
unsorted `score.criteria`, absent-vs-empty criteria); a numbers fixture and a >32-key fixture are
required before the change lands.

**4. A latent bug was found in shipped code, independent of this ADR.** An async guardrail
silently denies **every** tool call: `verdict = g(ev)` is not awaited
(`js/src/agents/loop.ts:73`, `python/src/toolnexus/agents/loop.py:102`), so a Promise/coroutine is
truthy and `!== "allow"`. Reproduced: `denied: [object Promise]` and `denied: <coroutine object
…>`. The same function correctly awaits the prior hook, so it is an oversight. The declared type
is synchronous, so today it is user error — but it is a fail-closed, silent, garbage-reason
failure, and it would become a migration hazard for any change that widens `Guardrail`.
Correction 1 means this ADR no longer needs to. **Spun out as its own fix.**

**5. Jev is non-deterministic, so thresholds need clearance.** σ ≈ 0.015, spread 0.05 on a 0–3
score across 12 concurrent identical calls. A band boundary within ~0.1 of a typical value will
flip between runs. `noul` was the most stable primitive (0.98 on every run). D4 gains a
clearance rule; conformance can only assert the `static` backend.

**6. `style:"llm"` is weaker than the draft assumed.** Same routing job: Jev 0.52 s / $0.000075
with a 64-key distribution; `gpt-4o-mini` 1.72 s / 2.5× cost; `deepseek-chat` 2.28 s / 3.4× cost —
neither returns a distribution, both emit round self-reported confidence (0.95, 1.00), and on the
support fixture the LLM backend disagreed outright (`billing` 0.95 vs Jev's `shipping` 0.61).
`calibrated` is promoted from a flag to a policy input: `bands` refuses an uncalibrated backend
unless the policy opts in.

**7. OpenRouter serves Jev today with no TypeSafe waitlist key.** Early access is off the
critical path, and CI gains an optional live-smoke path.


## Context

### The hole

toolnexus unified every **action** an LLM can take behind one `Tool`. It has no equivalent for
a **judgment**. The placement law (`golang/agents/loop.go:6-14`) is deliberate and right: the
loop never answers "is it right?". The consequence is that every judgment an agent needs is
pushed onto the user, who has exactly two speeds to make it at — code (0 ms, no semantics) or
a frontier-model turn (seconds, dollars). Our own scenario pages show what that produces:

| the docs ask the user to write | what it actually is |
|---|---|
| `const DANGEROUS = /rm\s+-rf\|git\s+push\|…/` (coding agent) | a judgment as a regex — blind to `python -c "shutil.rmtree('/')"` |
| "retry ONCE with a narrower prompt, then route to the closest other worker" (orchestrator soul) | a judgment as prose the model may or may not follow |
| "`does` is the routing signal … it is prose, not configuration" | a judgment made by the most expensive model in the tree, unmeasured |
| skills load "when relevant" | a judgment we hope the model makes; the whole roster ships every turn |
| `completion: { verify: allTodosDone }` | the only shipped verifier is structural, because no cheap semantic one existed |

The internal survey (research §2) confirms it: **there is no per-request semantic judgment
anywhere in the stack today.** Every gate is structural (`Budget`, `inTeam`, `maxTurns`) or
host-supplied (`Guardrail`, `Completion.Verify`, `beforeLLM` route-gate).

### What changed

A System One model takes a `state` and pre-declared questions of three types — **noul** (0–1
truth), **choice** (≤255 options, full distribution), **score** (2–10 ordered levels) — and
returns calibrated answers in 70–500 ms at $0.042/MTok in, output free. No text, no parsing,
structurally unable to return an out-of-schema value. The five-second-expert tier between
code and an LLM turn now exists as a product, with a shared wire.

Every agent harness that has adopted it (LangChain middleware, `jev-guard` for eight coding
agents, `jev-belay`, TypeSafe's `skill_suggestion` cookbook) converged on one shape: **code
owns the workflow, the model returns typed features, thresholds live in one reviewable place,
every gate declares fail-open or fail-closed.** That is our placement law, restated.

### The constraint the design must survive

"Intent may be interpreted. Authority must be enforced." A calibrated risk score is a *better
reading of intent*. It is not a database role that cannot delete. If this seam is ever described
as a security boundary, it is mis-described. Its legitimate contribution to the security story is
narrower and real: fewer, better-justified approval prompts (the approval-fatigue problem
Anthropic documents), and a measured route instead of a guessed one.

Two authority gaps surfaced by that test are **out of scope here and recorded so they are not
forgotten**: the `bash` builtin spawns `sh -c` with the parent's full environment
(`golang/builtin.go:331` — no `cmd.Env`), so generated code can read the same `CRM_TOKEN` the
process holds for `${ENV_VAR}` header expansion; and tools are not idempotent on the §10 retry
pass. Both are deterministic fixes and both outrank this ADR in a security ordering. They get
their own change.

## Decision

### D1. Three layers; only the core is a contract

```
judge         on / ask / rule → Verdict; .asGuardrail() .asTaskGuard() .asSkillHint() .asVerify()   (surface)
Classifier    evaluate(state, {key: Noul|Choice|Score}) → Decision                                   (SPEC §8B — core)
backends      systemone | llm | custom | static                                                       (internal)
```

Same shape as what exists: `Tool` is the contract, MCP/skill/native/HTTP/A2A are sources
behind it, `Toolkit` is what you compose. Here `Classifier` is the contract, backends sit behind
it, `judge` is what you compose. Only `Classifier` and the `Decision` shape enter `SPEC.md`.

### D2. `Classifier` — the core contract (new `SPEC.md §8B`)

```
Question = Noul   { instructions, criteria?: {true, false} }
         | Choice { instructions, criteria: {name: desc|null} }     // ≤255 keys
         | Score  { instructions, criteria: [desc, …] }              // 2..10
Decision = { model: string, answers: {key: NoulAnswer|ChoiceAnswer|ScoreAnswer}, usage, calibrated: bool }
NoulAnswer   = { noul: 0..1 }
ChoiceAnswer = { choice, probabilities: {name: p}, confidence }
ScoreAnswer  = { score: number, probabilities: {"0": p, …}, confidence }
Classifier.evaluate(state: string|object|array, questions) -> Decision
```

- Question **keys are the caller's** and are never sent to the model — so a key may be a tool,
  skill or agent name verbatim.
- Questions are **independent**: answer A is never context for answer B. Backends that cannot
  guarantee this say so (`calibrated: false` is the flag that carries both caveats — see D3).
- Canonical wire = the TypeSafe `/v1/systemone` body. **Conformance (narrowed by correction 2):**
  for the same questions, every port emits a byte-identical **`questions` + `model`** payload —
  objects sorted recursively in ASCII order, **arrays never reordered** (`score.criteria` order *is*
  the level numbering). **`state` is passed through verbatim** as the host supplied it and is outside
  the claim, because numbers do not canonicalise across languages. Pinned by fixtures at
  `examples/judge/`, which MUST include: the base case; a hardened case (`<>&` unescaped — Go needs
  `SetEscapeHTML(false)`, C# needs `UnsafeRelaxedJsonEscaping`; unicode unescaped; ASCII key order —
  C# needs `StringComparer.Ordinal`; an unsorted `score.criteria`; absent-vs-empty `criteria`);
  a **numbers** case (`0`, `1.21`, `0.000016716` — without it Jackson passes while emitting `0.0`);
  and a **>32-key** case (without it Elixir passes by accident, since Erlang small maps iterate in
  term order only up to 32 keys).

### D3. `ClientOptions` for the classifier — same idiom as the LLM client

Mirrors `ClientOptions` (§8) field-for-field where a field makes sense, so a user who has
configured one client has configured the other:

| option | default | notes |
|---|---|---|
| `style` | `"systemone"` | `"systemone" \| "llm"`; `custom` when `evaluate` is supplied; `"static"` for fixtures |
| `baseUrl` | `https://api.typesafe.ai/v1` | OpenRouter: `https://openrouter.ai/api`; openjev/Laya: your host |
| `model` | `"jev-latest"` | **pin** (`jev-1.13.0`) once thresholds are tuned; `Decision.model` echoes what answered |
| `apiKeyEnv` | `"TYPESAFE_API_KEY"` | the **name** of the env var; read at call time, never logged (secrets rule) |
| `headers` | — | custom headers; values expand `${ENV_VAR}` at call time and are **never logged** — identical to remote-MCP `headers` (§2). This is how OpenRouter's `HTTP-Referer`/`X-Title`, Cloudflare account tokens, or an internal gateway's auth land |
| `httpClient` / `transport` | default | the ADR 0019 injectable transport; scope = the classifier path only |
| `timeout` | 10 s | per request (the official SDK default) |
| `retries` / `onError` | retry 408, 429, 500–599, honour `Retry-After` | **reuses** the §8 `ErrorInfo → Tier` classifier and the Retry-After parity rule; no second retry policy |
| `requestParams` / `bodyTransform` | — | §8 Gap 1 shape; how Cloudflare's `{"input": …}` wrapper or a gateway's extra fields are applied without a proxy |
| `onMetric` | — | emits `classifier.evaluate` `MetricEvent`s (latency, tokens, model) into the same §8 sink |
| `client` | — | `style: "llm"` only: the §8 `Client` to emulate over |
| `evaluate` | — | `custom` only: the user's function; everything else is ignored |

**Backends, internal:**

- `systemone` — the wire above. Chunking under the 64k-token request budget and the
  255-option cap is the backend's job, invisible to `judge`.
- `llm` — the three question types rendered as **one JSON-schema structured-output call** on any
  §8 client (`noul` → `boolean` + `probability`, `choice` → `enum`, `score` → bounded
  `integer`); the mapping TypeSafe's `system-one-adapter` and Kiln's `jev_jsonschema` already
  use. Where the provider exposes logprobs the backend reads them; otherwise the self-reported
  number is passed through with `calibrated: false`. This backend is what makes the seam
  vendor-neutral and what a host with no Jev key runs on the fleet's existing `classify_verify`
  tier (`SPEC.md:1352`).
- `custom` — `evaluate` supplied by the host: a fine-tuned encoder, a rules engine, a cache in
  front of either.
- **`calibrated` is a policy input, not a flag** (correction 6). `systemone` sets it true;
  `llm` sets it false. A `bands` rule **refuses to run** on an uncalibrated backend unless the
  policy opts in explicitly, because thresholds tuned on Jev do not transfer: on the same routing
  job the LLM backend was 3.3–4.4× slower, 2.5–3.4× costlier, returned no distribution, emitted
  round self-reported confidence, and on one fixture disagreed outright.
- **Default `baseUrl` note:** OpenRouter (`https://openrouter.ai/api/v1`) serves this wire today
  with no TypeSafe waitlist key (verified 2026-09-20) and adds `id`, `provider` and `usage.cost`,
  which the parser ignores. That is the recommended path until TypeSafe access is general.
- `static` — a recorded `Decision` per question set. **What CI runs.** No network, no key.

### D4. `judge` — the composable surface

```
judge({ on, ask, rule, failClosed? })
  on:   state -> state'          what the judge may look at (the security posture lives here)
  ask:  {key: Question}          may use choiceOver(items) — criteria = each item's description
  rule: one | topK | atLeast | bands
judge.rule(classifier, state, items) -> Verdict { kept, evidence: {name: p|score|bucket}, model, calibrated }
```

Every toolnexus noun already has the two fields a `choice` needs — `Tool{Name, Description}`,
skill `{name, description}`, agent `Def{Name, Does}`, A2A card `skills[]{name, description}` —
so `choiceOver(items)` works on all of them without knowing which it holds. A tree is judges in
sequence (roster → top-3 → fits; team → sub-team). A tree is data, so a §7E persona directory may
carry `router.json` / `guard.json` beside `SOUL.md`.

**Adapters** are the only place a judge meets a seam, and each returns an *existing* type:

| adapter | returns | seam | contract change |
|---|---|---|---|
| `.asGuardrail(only?)` | **`Hooks.BeforeTool`** | `Spec.Hooks` / `ClientOptions.Hooks` | **none** (correction 1). allow ⇒ pass through · deny ⇒ `isError` result · **ask ⇒ pending `Request`** with `evidence` in `data`. `Guardrail` is NOT widened |
| `.asTaskGuard()` | `Hooks.BeforeTool` on `task` | same | none |
| `.asSkillHint()` | `BeforeLLM` | `Spec.Hooks` / `ClientOptions.Hooks` | none — but a `composeBeforeLLM` helper (ADR 0014) so it coexists with the compactor |
| `.asVerify()` | `Completion.Verify` | `Spec.Completion` | none |
| `.asRoute()` | a function | host code over the six verbs; `serve(addr, {route})` | additive option on §7B |

**Threshold clearance (correction 5).** Jev is non-deterministic: σ ≈ 0.015, spread 0.05 on a 0–3
score. `bands` boundaries MUST sit ≥0.1 from expected values, or carry hysteresis; the docs state
this and the rule warns when a configured boundary is inside the jitter band. `noul` is the most
reproducible primitive and is preferred for gates. Conformance asserts the `static` backend only —
no test may assert a live numeric answer.

**Invariant (from the constraint above): a judge can only move a call toward `ask`/`deny`,
never toward `allow`.** The compiled guardrail is first-deny-wins; a judge cannot widen an
earlier denial. Numeric limits (`amount >= 50`, budgets, allowlists) stay in code and are never
delegated to a judge. Documented in the adapter, tested in conformance.

### D5. Explicitly not doing

- Not a provider: no messages, no tool calling, never wired into `Client.run` or the §8 adapters.
- Not behind `OnError`: retry-vs-fail is a status-code decision (`SPEC.md:1233-1247`).
- Not a per-query **model** router (the `SPEC.md:1336-1340` stance). Routing among *agents* is
  a different axis and is in; routing among model tiers stays deterministic per job class and
  would need its own ADR plus `Model` on `LLMOverride`.
- No SDK dependency in any port. Official SDKs exist for Python and JS only; Clojure has none;
  the wire is one POST. Raw HTTP everywhere (the Elixir in-house MCP precedent).
- Not `Classifier.asTool()` in v1 — paying a frontier round-trip to ask a 70 ms judge is
  backwards; the demand is real (`jev-mcp` exists) so it stays a level-2 opt-in for later.

### D6. Naming

`Classifier` for the configure-once client (boring, correct). `judge` / `Verdict` / `on` /
`ask` / `rule` for the composable surface: *the judge looks **on** this, **asks** these, **rules**
by this.* Per port: Go `tn.Classifier` + `classify.Judge`; JS `createClassifier` + `judge`;
Python `create_classifier` + `judge`; Java/C# `Classifier` + `Judge` (not `LlmClassifier` — it is
not an LLM); Elixir `Toolnexus.Classifier` / `Toolnexus.Judge`; Clojure `toolnexus.classifier` /
`toolnexus.judge`.

### D7. Documentation — the judge gets its own pages, not a paragraph bolted onto an existing one

The docs site has three shapes (`site/astro.config.mjs`): **scenarios** (full builds), **cookbook**
(one feature, one recipe) and **harness/live** (measured proof on live models). A seam this
cross-cutting needs one of each, and shipping them is part of the change, not a follow-up.

| page | slug | what it is | ships with |
|---|---|---|---|
| **Judges — typed decisions** | `cookbook/judge` | The recipe. `Classifier` config (incl. `headers`/`${ENV}`, `apiKeyEnv`, transport), the three question types, `judge({on, ask, rule})`, the four rules, the five adapters. Seven language tabs. | `add-judge` |
| **Fast and slow — a judge and a model playing together** | `scenarios/fast-and-slow` | The full build (§D8). Why a 0.5 s typed decision and a 3 s reasoning turn are different tools, and how they compose. | `add-judge-adapters` |
| **Judges, proved on live models** | `harness/judge-live` | The measured evidence: latency/cost/accuracy tables, the non-determinism budget, `systemone` vs `llm` backends side by side. Regenerated from the spike harness, not hand-written. | `add-judge` |
| **Filter skills and gate tools** | `cookbook/judge-batteries` | `SkillRelevance`, `ToolGuard`, `Verified`, `AgentRouter` as copy-pasteable recipes with their reference thresholds. | `add-judge-adapters` |

Three edits to **existing** pages, in the same change (each is a place the docs currently teach a
hand-rolled judgment):

1. `scenarios/coding-agent` — the `DANGEROUS` regex gets a sibling tab showing the three-band
   judge, with the `shutil.rmtree` case named as what the regex misses.
2. `scenarios/research-orchestrator` — the "`does` is the routing signal" section gains
   `lintTeam` (catching overlapping remits at build time) as the way to verify the prose.
3. `scenarios/coding-agent` — **correct the stale claim** that "the agent runtime does not expose
   client-loop hooks." `Spec.Hooks` and `Spec.Guardrails` have shipped since
   `add-harness-and-loop` (`golang/agents/agent.go:32-48`).

Every page opens with the boundary sentence — *a judge interprets inside a boundary; it is never
the boundary* — before any code. A docs page that teaches a judge as a security control is a
documentation bug, and reviewers should treat it as one.

### D8. The reference build — a judge and a model playing a game, measured

The scenario pages are full builds because that is what makes an architecture legible. The judge's
build is a **game**, for three reasons: the decision rate is high enough that latency is visible,
the outcome is scored so the comparison is not a matter of taste, and the division of labour is
the whole point — the same division TypeSafe demonstrated by having Jev play Doom.

**2048, four players, one seeded board** (`spikes/classifier/game/`, Go, driving the real
`tn.Client` for the model and a `Classifier` for the judge):

| player | who decides each move | what it demonstrates |
|---|---|---|
| `heuristic` | fixed priority, no model | the honest floor — always show it |
| `judge-only` | the judge picks from described legal moves | System One alone: fast, cheap, no plan |
| `llm-only` | the big model picks every move | System Two alone: slow, expensive, thoughtful |
| `hybrid` | **the model writes the strategy in prose every N moves; the judge applies it every move** | the architecture this ADR is for |

The load-bearing detail, and the reason this is a *correct* demonstration rather than a toy:
**Jev cannot do arithmetic** (a documented limitation). So the engine computes every legal move's
consequences and renders them as prose — *"merges 2 pairs for +8 points, leaves the largest tile
(64) in the top-left CORNER, leaves 5 empty cells"* — and the judge only ever chooses between
described outcomes. Code does the arithmetic; the judge does the judgment; code applies the move.
That is `deterministic code → judge → deterministic action`, which is the placement law
(`golang/agents/loop.go:6-14`) made executable.

The hybrid is the part that only toolnexus expresses cleanly: the big model's output is **prose
that becomes the judge's `instructions`**, so a $0.01 reasoning turn is amortised over N moves of
$0.00002 reflexes. That is the same shape as a coordinator's `soul` steering its workers, and as
`SkillRelevance` applying a strategy the host wrote — the game just makes the clock visible.

The harness also doubles as the source for `harness/judge-live`: it emits per-move JSON
(`results.json`) carrying score, wall-clock, call counts and cost per player, so the docs table is
generated from a real run rather than asserted.

## Consequences

**Users get, measurably:** fewer wrong skill loads (16.8 % → 7.3 % on TypeSafe's 182-skill /
488-request benchmark); three calibrated risk bands instead of two regex bands, so fewer and
better approval prompts; routing that uses the `does` they already wrote, with the distribution
logged; a `lintTeam` that catches overlapping remits at build time; a triage hop with no chat
model (~100 ms, ~$0.00002). Cost rounds to zero against the LLM turns saved.

**Parity cost:** `Classifier` is one POST + three value types — the smallest cross-port surface
since HTTP tools. `judge` + four rules + five adapters is the larger half. Both land in all seven
or are declared tier debt in `complete-clojure-port-parity` terms; `check_options_parity.py`
covers `ClassifierOptions`.

**Differentiation:** Jev ships SDKs for two languages; LangChain's integration is Python-only
and experimental. A conformance-tested `judge` in Go, Java, C#, Elixir and Clojure is a path that
exists nowhere else.

**Risks, and the exits built in:** Jev is closed, early-access, one vendor, with rate limits
"subject to change without notice" and self-reported evals. `style: "llm"` and Laya (open
weights, same schema) are the exits; CI never touches the network. Gateway latency in the wild
is 0.5–0.75 s against the 70–500 ms vendor figure — per-turn judges budget for that. Calibration
is not correctness, and thresholds do not transfer across question types or backends —
`Decision.calibrated` and per-backend threshold tables exist to stop that silently happening.

**Docs:** the seam is introduced with the boundary sentence first — *a judge interprets inside
a boundary; it is never the boundary* — and the coding-agent page's stale claim that the runtime
exposes no hooks is corrected in the same change.

## Open questions

1. ~~**`ask` → `Pending` from a guardrail.**~~ **CLOSED by the spike** — correction 1. It rides
   `Hooks.BeforeTool`, which already suspends; `Guardrail` is not touched. The remaining work is
   to *specify* path B in `SPEC.md §10` and give it a test in all seven ports, which is a small
   change worth making on its own merits.
2. **`agents.Options.Classifier` / `Spec.Classifier`** as a runtime-wide default the adapters
   fall back to (replace-never-merge, the `Hooks`/`OnMetric` precedent) — or explicit `c`
   everywhere. Explicit is simpler; the field is what lets `FromDir` personas declare judges as
   data.
3. **LLM backend: one call or one-per-question.** One call is cheap and leaks context between
   answers; one-per-question is independent and N× the cost. Default one call, flagged.
4. **Where `judge` lives per port** — `classify` sub-package beside `agents`, or inside `agents`.
   It is used from the plain client too (`.asSkillHint()`), which argues for a sibling package.

## Gate — MET 2026-09-20

The ADR's own gate asked for one Go spike. It was run in **all seven ports**, each in its own
idiom, against shared fixtures. All four items pass everywhere; every port also ran the optional
live call. Evidence in `spikes/classifier/reports/`.

| port | gates | `Classifier` LOC | `judge` LOC | canonical JSON | live |
|---|---|---|---|---|---|
| Clojure | 4/4 | 68 | 25 | native (koine) | 537 ms JVM · 1326 ms cljgo |
| Python | 4/4 | 145 | 67 | native (`sort_keys`) | 468 ms |
| JS | 4/4 | 156 | 56 | 9 lines | 457 ms |
| Elixir | 4/4 | 163 | 53 | 17 lines | 581 ms |
| C# | 4/4 | 205 | 38 | 46 lines | 562 ms |
| Java | 4/4 | 264 | 60 | 74 lines | 870 ms |
| Go | 4/4 | 271 | 142 | 8 lines | 352–851 ms |

Clojure verified byte-identical on all three dual-host modes (JVM, cljgo AOT, cljgo interpreted).
Python, C#, Elixir and Clojure ran gate 4 against the **shipped** `guarded_hooks`, not a copy.
Gate 3 is proven live: the three-band judge denies `python3 -c "shutil.rmtree('/')"` (risk 2.97),
allows `git status --short` (0.02), and puts `rm -rf ./build` in **ask** (2.25) — where the regex
the coding-agent page ships today misses the first entirely and hard-denies the third.

**The union was never the risk.** Every statically-typed port modelled `criteria`'s three shapes
with a closed/sealed hierarchy and zero write-side casts; Java has no `Object criteria` anywhere.
The only repeated cost is the canonical emitter, concentrated in Java (74), C# (46) and Elixir
(17); two ports need none at all. Median `Classifier` is ~163 LOC, median `judge` ~56 — the
smallest cross-port surface since HTTP tools.

## Sequencing

1. **`fix-async-guardrail`** (correction 4) — independent of this ADR, ships first: either await
   the guardrail or make the sync contract enforced rather than implied. It is a silent
   deny-everything today.
2. **`spec-path-b`** (correction 1) — state hook-raised suspension in `SPEC.md §10` and test it in
   all seven ports. Small, and `add-judge-adapters` depends on it being contractual rather than
   incidental.
3. **`add-judge`** — `SPEC.md §8B`, `Classifier` in seven ports, the four fixtures from D2,
   `cookbook/judge` + `harness/judge-live` (D7).
4. **`add-judge-adapters`** — the five adapters, `lintTeam`, `scenarios/fast-and-slow` (D8),
   `cookbook/judge-batteries`, and the three existing-page edits in D7.

Still out of scope and still ahead of step 4 in a security ordering: the `bash` env-inheritance
gap and tool idempotency on the §10 retry pass (see §Context).

### Minor findings worth their own one-line fixes

- `Client.Run(ctx, prompt, nil)` panics on a nil toolkit rather than treating it as "no tools",
  although §8 Gap 5 already defines empty-tool-list behaviour.
- The coding-agent scenario page still claims the agent runtime exposes no client-loop hooks;
  `Spec.Hooks`/`Spec.Guardrails` have shipped since `add-harness-and-loop`.
