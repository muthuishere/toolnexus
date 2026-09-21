# Jev / System One models in toolnexus — research + proposed fit (2026-09-20)

Status: research, design-stage. No spec or code changed. Feeds an ADR + OpenSpec change
(`add-classifier-client`, then `add-classifier-batteries`).

## 0. One paragraph

TypeSafe's **Jev** (early access since 2026-09-15) is not a chat model. It takes a `state`
(JSON) and a set of pre-declared typed questions — **noul** (0–1 truth), **choice** (≤255
options, full distribution), **score** (2–10 ordered levels) — and returns calibrated
answers in 70–500 ms at $0.042/MTok in, output free. Its wire shape (`POST /v1/systemone`)
has already become a de-facto standard: OpenRouter Decisions, Cloudflare Workers AI,
open-weights **Laya** (Apache-2.0, local, 7 ms/question batched), self-hosted `openjev-sglang`,
and TypeSafe's own `system-one-adapter` (emulation over any chat LLM) all speak it. That is
the fact that makes a **vendor-neutral `Classifier` client** in toolnexus defensible: one
contract, many backends, CI stays hermetic with a fake.

Everything in the wild that uses Jev inside an agent — LangChain's middleware, `jev-guard`
(tool-risk gate for 8 coding agents), `jev-belay` (done gate), TypeSafe's own
`skill_suggestion` cookbook — converges on one shape: **code owns the workflow, the model
returns typed features, thresholds live in one reviewable struct, every gate declares
fail-open or fail-closed.** That is exactly toolnexus's existing placement law
(`golang/agents/loop.go:6-14`: none of the loop's gates answers "is it RIGHT?").

## 1. The wire contract (what the port has to speak)

```
POST https://api.typesafe.ai/v1/systemone      Authorization: Bearer <key>
{ "state": <string|object|array>, "model": "jev-1.13.0",
  "questions": {
    "k1": {"type":"noul",   "instructions":"…", "criteria":{"true":"…","false":"…"}},   // criteria optional
    "k2": {"type":"choice", "instructions":"…", "criteria":{"a":"…","b":null}},         // required, ≤255
    "k3": {"type":"score",  "instructions":"…", "criteria":["lvl0","lvl1","lvl2"]} }}   // required, 2–10
→
{ "model":"jev-1.13.0",
  "answers": { "k1":{"type":"noul","noul":0.95},
               "k2":{"type":"choice","choice":"a","probabilities":{…},"confidence":0.81},
               "k3":{"type":"score","score":1.05,"legend":{…},"probabilities":{…},"confidence":0.92} },
  "usage": {"input_tokens":318,"output_tokens":34} }
```

Facts that shape the design:

| fact | consequence for toolnexus |
|---|---|
| Question keys are never sent to the model | keys can be tool names / skill names verbatim |
| Questions are independent + parallel; A is never context for B | one call per turn, N questions; batching = packing |
| 64k tokens/request, 32k state+longest question | state must be *shaped*, not the transcript |
| 250k tok/s, 1 200 req/min per account; 429/529 retry | reuse §8 resilience (`OnError` Tier) + Retry-After parity |
| No streaming, no batch endpoint, no idempotency | plain request/response; fits the injectable-transport idiom |
| noul has **no** confidence; noul vs 2-choice are *not* interchangeable (0.22 vs 0.01 on same q) | thresholds are per-question-type, user-owned |
| Aliases move silently (`jev-latest`) | pin a version once thresholds are tuned; log `response.model` |
| State is not treated as hostile | guards must exclude assistant text + tool outputs from state |
| Text only, English-primary, no fine-tune | out of scope for multimodal §1B |
| Official SDKs: Python, JS only. Go/Java/C#/Elixir/Ruby/Rust are community; **Clojure none** | do **not** depend on any SDK — raw HTTP, one POST (Elixir MCP precedent) |
| Gateway drift: Vercel renames noul→boolean, Cloudflare wraps in `input` | normalize in the adapter; canonical = TypeSafe shape |

Sources: docs.typesafe.ai/{api,models,confidence,model-jaggedness/jev-1.13}.md.

## 2. Where it fits — the seam map (Go reference, ports mirror)

The internal survey found **no per-request semantic judgment anywhere in the stack**. Every
gate is structural or host-supplied. That is good news: each consumer below plugs into an
*existing* seam and returns an *existing* type, so the loop contract barely moves.

| decision | seam today | returns | contract change |
|---|---|---|---|
| Which skill is relevant this turn | none — whole roster injected every turn (`golang/skill.go:367-384`); allowlist is static at load (`:326-357`) | `BeforeLLM` hook | **none** |
| Which tools to send this turn | computed once per run (`golang/client.go:877`); only mutation point is `ov.Tools` at 4 sites (`:902,:1177,:1479,:1741`) | `BeforeLLM` hook | **none** |
| May this tool call run | `Guardrail func(BeforeToolEvent) string`, first-deny-wins (`golang/agents/agent.go:56`, `loop.go:24-47`) | `Guardrail` | Guardrail needs a ctx/async variant (it is sync + pure today) |
| Is the run done | `Completion.Verify(RunResult) → (bool, reason)` (`agent.go:60-68`); only shipped verifier is structural `AllTodosDone` | `Verify` | **none** |
| Which model tier | route-gate is abort-only; `LLMOverride` has no `Model` (`client.go:242-254`) | `BeforeLLM` | needs `Model` on `LLMOverride` (or the `RequestParams["model"]` trick at `agents/loop.go:88-100`) |
| Accept an inbound A2A task (§7B) | none | guardrail-shaped | none |
| retry vs fail | `OnError(ErrorInfo) Tier` (`client.go:93-98`) | — | **do not use Jev here** — it cannot read an HTTP error better than code |
| budget stop/extend/suspend | `onBudget` specified `SPEC.md:887`, implemented nowhere | — | out of scope |
| compaction selection | positional (§7F); ADR 0013 wants *model-free* pruning | — | phase 3 at most |

Read before adding anything: ADR 0014 (hooks are single-slot — batteries must compose, not
occupy), ADR 0018 (`afterLLM` is observe-only), `_never-built/add-governed-execution-layer`
("not a vibes/LLM filter").

## 3. Proposed shape

### 3.1 `Classifier` — the contract (new `SPEC.md §8B`)

A sibling of `Client`, not a provider inside it.

```
Classifier.evaluate(state, questions) -> Decision
  Question = Noul{instructions, criteria?{true,false}}
           | Choice{instructions, criteria{name: desc|null}}   // ≤255
           | Score{instructions, criteria[2..10]}
  Decision = { model, answers{ key: NoulAnswer|ChoiceAnswer|ScoreAnswer }, usage }
ClassifierOptions = { baseUrl = "https://api.typesafe.ai/v1", apiKeyEnv = "TYPESAFE_API_KEY",
                      model = "jev-latest", style = "systemone" | "llm", httpClient?, timeout = 10s,
                      onError?, onMetric?, headers? }
```

- **`style: "systemone"`** — the TypeSafe wire, which also covers OpenRouter
  (`baseUrl=https://openrouter.ai/api`), openjev-sglang, Laya-over-HTTP. Cloudflare's `input`
  wrapper is a `BodyTransform`, same as §8 Gap 1.
- **`style: "llm"`** — emulation over any §8 client via structured output (what
  `typesafe-ai/system-one-adapter-python` and `openjev-sglang`'s logprob readout prove
  works). This is what keeps the seam vendor-neutral and lets a host with *no* Jev key run
  the same batteries on `deepseek/deepseek-chat` — the `classify_verify` tier that already
  exists in the fleet registry (`SPEC.md:1352`).
- Naming per port: Go `tn.Classifier` / `CreateClassifier(ClassifierOptions)`; JS `Classifier`
  / `createClassifier`; Python `Classifier` / `create_classifier`; Java/C# `Classifier` (not
  `LlmClassifier` — it is not an LLM); Elixir `Toolnexus.Classifier`; Clojure `toolnexus.classifier`.
- Idiom: nullable option, default resolved at construction, `// Mirrors js …` per field,
  `conformance/check_options_parity.py` — lands in all seven or is a declared tier gap.
- Conformance: **byte-identical request body** across ports for the same questions (keys
  sorted), `examples/classifier/` = questions fixture + recorded response; CI runs a fake
  `/v1/systemone` server — no network, no key, same as today.
- Secrets: key read from the env var *named* in `apiKeyEnv` at call time, never logged; on
  error log only type/status/request-id, never the provider message (LangChain precedent).

### 3.2 Batteries — adapters that return existing seam types (`add-classifier-batteries`)

Each is a pure function `(Classifier, Policy) → <existing type>`. Absent ⇒ byte-identical.
Every policy struct carries `thresholds` and `failOpen` explicitly.

**`SkillRelevance(c, policy) → BeforeLLM`** — TypeSafe's `skill_suggestion` cookbook, measured
on 182 skills / 488 requests: wrong loads 16.8 % → 7.3 %, needless loads 9.8 % → 4.0 %,
0.09–0.31 s/turn. Call 1: one `choice` over all skill names (criteria = description) + gate
nouls (`acts_on_user_system`, `would_follow_documented_procedure`, `prose_suffices`); mean gate
< 0.30 ⇒ say nothing. Call 2: `choice` over top-3 with 700 chars of SKILL.md + `fits::<name>`
noul each. Inject one `<skill_relevance>` line **after** the roster so the prefix cache holds;
the roster itself stays byte-identical to §3. Fail-open. State = latest user message only.
Note the ≤255 cap: past 255 skills, fall back to per-skill nouls (no cap but the 64k budget).

**`ToolGuard(c, policy) → Guardrail`** — `jev-guard`'s battery (shipped for Claude Code,
Codex, Copilot, Cursor, OpenCode…): `risk` score 0–3 (read-only → easy-undo → hard-undo →
destructive) + nouls `approval`, `user_requested`, `from_untrusted`. Reference policy:
`deny if from_untrusted ≥ .7 or risk ≥ 2.5; allow if (risk ≥ 1.5 or approval ≥ .75) and
user_requested ≥ .85; ask if risk ≥ 1.5 or approval ≥ .75; else allow`. State = tool name,
key-name-redacted args, cwd, tool description; **never** assistant text or tool outputs
(LangChain `AutoModeMiddleware` — an injected ToolMessage cannot authorize execution).
**Fail-closed** by default. The `ask` band is toolnexus's edge over every other harness: it
maps straight onto §10 `Pending` (waitFor → owner answers → resume), instead of a hook that
can only allow/deny. This is the one place the contract moves: `Guardrail` needs a
ctx-carrying/async form (today it is sync and pure).

**`Verified(c, rubric) → Completion.Verify`** — `jev-belay`'s done gate: nouls `claims_done`
(.75), `claims_verified` (.70), `verification_applies` (.50), choice `outcome`
{complete, partial, blocked, other} floor .40. State = task + closing message + counts
(`file_changes`, `checks_run`), no diffs. ~410 tokens ≈ $0.00002, ~100 ms. Fail-open; cap
re-prompts (belay uses 1/prompt, 3/session — `Completion.MaxAttempts` already exists). Zero
contract change. Complements, does not replace, the structural `AllTodosDone`; "did the tests
pass" stays a tool (`agent.go:56-58`).

**`ToolSubset(c, policy) → BeforeLLM`** — per-turn tool pruning through `ov.Tools`. One noul
per tool ("could the next step need this tool", criteria from the tool description) over a
state of the last user + last assistant turn; keep everything ≥ floor, never below `minKeep`.
Cheap, but the payoff is prompt size + tool-choice accuracy, and it interacts with §8 Gap 5
(empty tools omission) — ship after the three above, once there is a benchmark.

## 3.4 Agent routing — `AgentRouter` (the best-fitting battery of all)

The runtime already holds the exact inputs a `choice` question needs, for free: a team list
plus a one-line `does` per agent, which the `task` tool today concatenates into its description
(`golang/agents/runtime.go:1262-1270`). Those strings *are* the criteria. Four routing points,
one battery:

```
AgentRouter(c, registry|team|cards, policy) -> Router
Router.route(state) -> Route{ agent, confidence, probabilities, model }   // or "" ⇒ no agent
policy = { default?, minConfidence = .5, needsAgentFloor = .3, failOpen = true }
questions: choice `agent` over names (criteria = does) + noul `needs_agent`
          ("would prose suffice" inverted — the skill_suggestion gate) + optional noul
          `from_untrusted` when state is inbound
```

| routing point | today | with the router | contract change |
|---|---|---|---|
| **Front door** — host has a request, which agent gets `spawn`/`wake`? | host decides | `Router.route(request)` → name → host verb. LangChain's `ModelRouterMiddleware` shape: once per run, full distribution kept, fail-open to `default` | none — a function |
| **`task` tool** inside a run | the parent model picks; static `inTeam` check (`runtime.go:1283-1287`) | keep the model's pick, add a **gate** via `BeforeTool` on `task`: noul `delegation_warranted` + `agent` choice; disagreement ⇒ error result "consider <top>" (or `{args}` rewrite when confidence ≥ floor — opt-in, it overrides the model) | none — `BeforeTool` exists; team scoping (`agent.go:18-19`) still enforced after the gate |
| **A2A outbound (§7A)** — which remote agent | host picks a card | `choice` over Agent Cards' `skills[].description` — same battery, criteria from cards | none |
| **Inbound `serve` (§7B)** — which persona (§7E) answers, and should we accept at all | one toolkit per `serve` | `serve(addr, {route: Router})` → per-task persona selection + `from_untrusted`/policy guard before any turn is spent | additive option on §7B |

**Hierarchies.** The registry is the transitive closure of the entry agent's team graph
(`SPEC.md:897`), i.e. a tree. TypeSafe's *hierarchical classification* cookbook (beam search
over `choice` probabilities, level by level) maps onto it directly: route within the current
team, descend, route again. Stays under the 255-option cap and inside the security model — an
agent never sees names outside its team.

**No-LLM dispatcher.** A persona whose only job is routing needs no chat model: `Router` as
the whole turn. This is the "frontdesk sentinel" shape — a cheap always-on loop that answers
small things itself and wakes the expensive agent only on judgment — expressed as one noul
(`needs_escalation`) + one choice (which desk), ~100 ms, ~$0.00002 per event.

**Why this does not collide with `SPEC.md:1336-1340`.** That stance is about *model tiers*
(FrugalGPT: per-job-class, not per-query). Routing among *agents* is a different axis; and with
a pinned `jev-1.13.0` and the full `probabilities` logged on every route, it is auditable and
reproducible — the two properties the stance protects.

## 3.5 On the Level-1 surface — `agents.Spec`, `Agent.Run`, `Runtime`, the orchestrator recipe

The Level-1 noun is `agents.New(name, Spec{…})` (`golang/agents/agent.go:14-56`); it compiles
to the six runtime verbs, and the docs deliberately ship **no `orchestrate()`** — a
coordinator is "a whose team is the workers; the model decides the fan-out"
(`site/src/content/docs/subagents.mdx:386-405`). The classifier lands on that surface as
**values for fields that already exist**, in a `classify` package — one new field pair at most.

```go
c := tn.CreateClassifier(tn.ClassifierOptions{Model: "jev-1.13.0"})  // key from TYPESAFE_API_KEY

researcher := agents.New("researcher", agents.Spec{Does: "web + code research", Tools: tools})
writer     := agents.New("writer",     agents.Spec{Does: "drafts prose from notes"})
checker    := agents.New("checker",    agents.Spec{Does: "adversarial fact-check"})

lead := agents.New("lead", agents.Spec{
    Does: "coordinates research reports",
    Soul: "Plan, delegate in parallel where independent, have the checker verify.",
    Team: []*agents.Agent{researcher, writer, checker},

    // existing field, new value: policy on tool calls — incl. the `task` tool
    Guardrails: []agents.Guardrail{
        classify.ToolGuard(c, classify.GuardPolicy{DenyRisk: 2.5, FailClosed: true}),
        classify.TaskGuard(c, lead-team, classify.TaskPolicy{MinConfidence: .5}), // "consider researcher"
    },
    // existing field, new value: the done gate
    Completion: &agents.Completion{Verify: classify.Verified(c, rubric), MaxAttempts: 3},
    // existing field, new value: per-turn skill hint (composes with the §7F compactor)
    Hooks: &tn.Hooks{BeforeLLM: tn.ComposeBeforeLLM(classify.SkillRelevance(c, tk), agents.Compactor(…))},
})
r, _ := lead.Run(opts, "Report on the EU AI Act's impact on medical devices")
```

| Level-1 slot | battery | new field? |
|---|---|---|
| `Spec.Guardrails` (`agent.go:44-48`, compiled FIRST-DENY-WINS `loop.go:24-47`) | `ToolGuard`, `TaskGuard` (fires only on `ev.Name == "task"`; denial text names the classifier's top pick, team scoping still enforced after) | no |
| `Spec.Completion.Verify` (`agent.go:60-68`) | `Verified` | no |
| `Spec.Hooks.BeforeLLM` | `SkillRelevance`, `ToolSubset` | no — but ADR 0014's single slot means a `ComposeBeforeLLM` helper is needed so it coexists with the compactor |
| `Spec.Hooks.BeforeTool` `{args}` rewrite | `TaskRouter` — *override* the model's `agent` when confidence ≥ floor (opt-in; the guard form above is the default) | no |
| `agents.Options.Classifier` / `Spec.Classifier` | runtime-wide default the batteries fall back to; Spec replaces, never merges (the `Hooks`/`OnMetric` precedent, `agent.go:35-40`) | **the one optional addition** — lets `FromDir` personas (§7E) declare `router.json` / `guard.json` beside `SOUL.md` |
| `serve(agent, {route})` (§7B) | `AgentRouter` picks the persona per inbound task | additive option |

**Orchestrator, three grades — all userland, no new API (ADR 0016/0017):**

1. *Model-led, classifier-gated* — the recipe above: the LLM still decides fan-out, `TaskGuard`
   catches wrong targets and needless delegation before a child budget is spent.
2. *Classifier-led, model-executed* — host code routes, agents work:
   ```go
   router := classify.AgentRouter(c, lead.Registry(), classify.RoutePolicy{Default: "lead"})
   rt := agents.NewRuntime(opts)
   route := router.Route(request)               // {agent, confidence, probabilities}
   h, _ := rt.Spawn(rt.Root, route.Agent, nil); rt.Wake(h, request); r := rt.Wait(h, 0)
   ```
   A ~100 ms front door over the six verbs; the fan-out is auditable (`probabilities` logged).
3. *Classifier-only dispatcher* — a persona whose whole turn is `Route` + verbs, no chat model:
   the frontdesk/sentinel shape (noul `needs_escalation` + choice over desks).

**One contract question to settle in the ADR.** `Guardrail` returns a `string` (allow/deny).
The `ask` band of `ToolGuard` wants a §10 `Pending` (owner decides, run resumes). Either
`Guardrail` grows a `(string, *tn.Request)` form, or `ask` is expressed through
`Hooks.BeforeTool` returning a pending result — whichever the §10 contract already permits
for a hook. v1 can map `ask` → deny-with-reason and keep `Guardrail` untouched.

### 3.3 Phase 3 candidates (owner decisions, not in the first two changes)

- **`Router(c, tiers) → BeforeLLM` returning `Model`.** LangChain does this once per run,
  fail-open to a default tier. It **contradicts** `SPEC.md:1336-1340` ("deterministic,
  per-job-class … not a learned per-query router"). Half the objection is answered — a pinned
  `jev-1.13.0` with logged probabilities *is* reproducible and auditable — but it is a stance
  change and needs `Model` on `LLMOverride`. Write the ADR, then decide.
- **No-LLM dispatch.** TypeSafe's `function_calling` cookbook dispatches 54 questions in one
  request: a `__tool__` choice + one question per closed-set arg (enum → choice, bool → noul,
  `arg?` noul for optionality). For a `Toolkit` whose tools have only enum/bool args this is a
  complete agent with **no chat model at all** — a real "build agents" story for
  command/intent surfaces, and a new `Client` style rather than a battery.
- **Compaction relevance** — a noul per message is a "cheap model", ADR 0013 asked for "no
  model"; context-rot (limitation #5) argues against feeding the transcript anyway. Park.
- **`Classifier.asTool()`** — expose noul/choice/score as a `Tool` the LLM can call. Level-2,
  off by default: paying a frontier round-trip to ask a 70 ms judge is backwards, and the
  LLM's judgment of *when* to ask is the unreliable part. The MCP servers that do this
  (`jev-mcp`, `typesafe-mcp`) exist, so the demand is real; keep it opt-in.

## 4. What NOT to do

- Do not wire Jev into the §8 adapters or `Client.run` — no messages, no tool calling.
- Do not put Jev behind `OnError` — retry/fail is a status-code decision (`SPEC.md:1233-1247`).
- Do not depend on any TypeSafe SDK in any port; the wire is one POST and Clojure has no SDK.
- Do not carry a threshold from a noul to a choice or vice versa (no structural invariants).
- Do not feed the transcript as state — context rot is measured; shape the state per battery.
- Do not let a battery *occupy* the single `BeforeLLM`/`BeforeTool` slot (ADR 0014) — compose.

## 5. Risks

Early-access, waitlisted, rate limits "can change without notice", one closed vendor, a
prior-art dispute (ConvAI/Laya), self-reported evals only. Mitigation is the design itself:
`Classifier` is the contract; `style: "llm"` and Laya (open weights, same schema) are the exits.
Latency in the wild is 0.5–0.75 s via gateways vs the 70–500 ms vendor figure — budget for
that in per-turn batteries.

## 6. Sequencing

1. **ADR 0020 — typed decisions in the loop.** Contract vs providers, the placement law, the
   two contract touches (async Guardrail; `Model` on `LLMOverride` deferred), fail-open/closed.
2. **`add-classifier-client`** — `SPEC.md §8B`, `Classifier` in 7 ports, `style: systemone|llm`,
   hermetic fixture under `examples/classifier/`, options-parity check, CHANGELOG.
3. **`add-classifier-batteries`** — `SkillRelevance`, `ToolGuard` (+ async Guardrail, `ask` →
   §10 Pending), `Verified`, `AgentRouter` (front door + `task` gate; `serve` route option); each with a fixture-driven conformance test and a docs cookbook page
   ("Filter skills and gate tools with a classifier").
4. Phase 3 by ADR: `Router`, `ToolSubset` benchmark, no-LLM dispatch.

## Sources

docs.typesafe.ai (api, models, confidence, agent-skill, model-jaggedness/jev-1.13, cookbooks:
skill_suggestion / llm_guardrails / function_calling, sdk) · typesafe.ai/blog/introducing-system-one-models-and-jev ·
langchain.com/blog/building-a-harness-with-jev + langchain PR #40556 · github.com/leepokai/jev-guard ·
github.com/valentynkit/jev-belay · github.com/Anil-matcha/awesome-jev-by-typesafe ·
github.com/typesafe-ai/system-one-adapter-python · github.com/ekzhang/openjev-sglang ·
laya.convaiinnovations.com · openrouter.ai/docs/guides/community/typesafe-sdk ·
developers.cloudflare.com/ai/models/typesafe/jev · vercel.com/kb/guide/typesafe-jev-and-ai-sdk ·
pypi.org/project/jev_jsonschema · laurentkempe.com/2026/09/19/typesafe-jev-dotnet-sdk-introduction ·
elixirforum.com/t/typesafe-sdk-jev-the-first-system-one-model-from-typesafe/76700 ·
gist.github.com/pjburnhill/adf8d28efcad9df037bfdece178ef965
