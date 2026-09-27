# ADR 0035 — Judge adapters: patterns first, batteries on top, recipes in the docs

- **Status:** **Proposed** (2026-09-27). Pending the Go spike at `spikes/judge-adapters/`.
  Nothing below is accepted until the spike's numbers go into *Evidence*. Every "SHALL" is
  a claim that the spike can still refute.
- **Date:** 2026-09-27
- **Driver:** 0.18.0 shipped `Classifier` (SPEC §8B) in all seven ports and told users plainly
  that there are **"no adapters and no batteries"** (`CHANGELOG.md`, 0.18.0, *What is NOT done*).
  So a host that wants a judgment inside the agent loop hand-writes its questions and its hook
  glue every time. TypeSafe's published cookbooks show that those hand-written hooks come in a
  small number of recurring shapes.
- **Related:**
  - ADR 0014 (hook composition, single slot, **Proposed**, no `chain` helper shipped).
  - ADR 0020 (the `Classifier` seam).
  - ADR 0021 (the encoding carries the judgment).
  - `SPEC.md §8B` (Classifier), `§8` (hooks, right-size routing), `§10` (suspension).
  - `docs/references/jev-classifier-research-2026-09-20.md`.

---

## Context

We read three external sources:

- TypeSafe's official cookbook index (about 22 recipes).
- The community `jev-cookbook` (10 runnable examples and 4 composition patterns).
- Langfuse's write-up on using Jev as an eval judge.

Every recipe splits into two parts:

1. **A composition move**: gate on confidence, sample repeatedly, split a judgment into
   atomic questions, try something cheap before something expensive, or ask every question
   in one call.
2. **A domain**: support triage, fraud, contract clauses, DOM navigation, and so on.

The moves repeat; the domains do not. A library that ships domains becomes a vertical
product. A library that ships no moves makes every host rebuild the same five of them.

A third part is specific to toolnexus: **where in the loop the judgment goes.**

- The cookbook's "Agent Safety Guardrails" wraps a tool function in a decorator.
- The "Agent skill suggestion" recipe filters a list before the prompt is built.

We already have seams for both: `beforeTool` and `beforeLLM`. What we lack is the thin layer
that turns a `Classifier` plus a policy into a value that fits one of those seams.

---

## Decisions

### D1 — Three layers, and only the first two are library code

| layer | what | where it lives |
|---|---|---|
| **1. Patterns** | composition moves over any `Classifier` | library, all seven ports |
| **2. Batteries** | `*Classifier` values: a standalone method plus `asHook(next)` | library, all seven ports |
| **3. Recipes** | triage, fraud, moderation policy, incident routing, … | Cookbook pages built from layers 1 and 2; no code |

### D2 — Layer 1: the patterns

- **Confidence-gated escalation.**
  - `escalate(c, gate, fallback)` returns the classifier's answer when it clears `gate`.
  - Below the gate, it falls back to one of two targets:
    - **(a) another `Classifier`**, typically `style: "llm"`;
    - **(b) a §10 `Request`**: the run suspends and a human answers through `waitFor`.
  - (b) is the part the cookbooks lack. Their "escalate to a human" happens outside the loop;
    ours is a resumable suspension that already works in all ports.
  - The gate reads `confidence` for `choice` and `score`. For `noul` it reads distance from 0.5,
    because §8B says `noul` carries no confidence.
- **Self-consistency.** `consistent(c, n, agree)` asks the same questions `n` times. If fewer
  than `agree` answers match, the result is marked as disagreeing, and it composes with
  `escalate`.
- **Composite scoring.** `composite(weights)` combines several independent answers in **code**,
  with no second model call.
  - §8B already guarantees that questions are independent, which is what makes this valid.
  - The weights belong to the host; the library never picks them.
- **Cheap-first cascade.** This is `escalate` with a cheaper classifier in front of a dearer one,
  so it needs no separate primitive. Documented as a pattern, not an API.
- **Speculative fan-out.** Already native: `evaluate` takes a whole map of questions, so this
  needs no new code. Documented only.

### D3 — Layer 2: the batteries

Every battery follows the same four rules:

1. **Its constructor takes a `Classifier`**, never a vendor, URL or model (vendor-neutral, per
   the scope memo). The same battery runs on `systemone`, on `llm`, and on `static` in CI.
2. **Its standalone method** returns a typed verdict, so it is useful with no agent at all.
3. **`asHook(next)` wraps `next` and delegates to it.** It must never discard it. ADR 0014 is
   still Proposed and ships no `chain`, so the battery does its own composition, explicitly, at
   the call site. That is the stance ADR 0014 itself recommends. Once `chain` lands,
   `asHook(nil)` plus `chain(...)` is equivalent.
4. **Its default question text is part of the contract.** ADR 0021 measured that the wording is
   the product: bare ids scored 0, described options scored 17. So defaults describe
   consequences, and a host may override them.

| battery | standalone | seam | question shape |
|---|---|---|---|
| `ToolGuardClassifier` | `check(call) → allow \| ask \| deny`, plus risk | **`beforeTool`**; `ask` short-circuits with a §10 `Request` | `score`, a 0–3 risk rubric |
| `ToolRelevanceClassifier` | `select(prompt, tools) → subset` | `beforeLLM`, via an `LLMOverride` that swaps `tools` | one `noul` per tool, keyed by the tool name |
| `SkillRelevanceClassifier` | `select(prompt, skills) → subset` | `beforeLLM` | one `noul` per skill |
| `ToolResultFilterClassifier` | `filter(query, chunks) → kept` | `afterTool`, via an override `Result` | one `noul` per chunk |
| `IsCompleteClassifier` | `check(task, answer) → p` | **no seam today** (see below) | `noul`, optionally one `noul` per claim |
| `AgentRouterClassifier` | `pick(task, agents) → agent + probabilities` | subagent dispatch / A2A outbound | `choice`; hierarchical beyond 255 |
| `ContentGuardClassifier` | `check(text) → p per dimension` | `serve` inbound, `beforeLLM`, `afterLLM` (observe only) | composite `noul`s |
| `ModelRouterClassifier` | — | — | **OPEN: owner decision** (D6) |

Three of these depend on facts in the code, not on taste:

- **`ToolGuard` is a `beforeTool` battery, not a `Guardrail`.**
  - A `Guardrail` returns a verdict string synchronously (0.18.0 made anything else a loud
    error in every port), so it has only two states.
  - A guard needs three states, and "ask" is exactly the hook-raised §10 suspension that
    0.18.0 wrote into the spec (path B).
- **`IsComplete` has no loop seam.**
  - The research doc assumed a `Completion.Verify` hook on the client. Client `Hooks` has none
    (`golang/client.go:258`, four fields); `Completion.Verify` exists only in the agents layer
    (`golang/agents/agent.go:61-63`), which wfnexus already uses (`engine/step.go:170-178`).
  - `afterLLM` can observe the final turn but cannot reject it.
  - So in this change `IsComplete` ships **standalone only**. A retry-on-incomplete seam is a
    separate proposal, if the spike shows it is worth one.
- **`AgentRouter` beyond 255 agents walks a tree.**
  - This follows the cookbook's hierarchical-classification recipe: one `choice` per level.
  - The tree comes from the host, e.g. grouped by Agent Card `skills[].description`; the
    library never infers it.

### D4 — Constraints every battery inherits

- **Advisory, never authorising** (§8B, verbatim).
  - Allowlists, permission checks and numeric limits stay in code.
  - `ToolGuard`'s `deny` is a policy aid, and the docs must not call it a security control.
  - A host that needs a hard boundary puts code in front of the battery, not the other way round.
- **No numeric, counting or date questions.**
  - TypeSafe documents weak counting and numerics for jev-1.13.
  - So batteries only ask Jev to *pick among candidates that code has already enumerated*
    (ADR 0021; the cookbook's "pre-parsed value extraction").
- **Read `Decision.calibrated` before any threshold.** A gate tuned on `systemone` does not
  carry over to `llm` (§8B). A battery given an uncalibrated classifier reports that in its
  verdict rather than silently comparing.
- **Fail-open or fail-closed is a required constructor argument, with no default.**
  - A classifier error is the host's policy decision, never ours.
  - `ToolGuard` examples show fail-closed, `ToolRelevance` examples show fail-open.
- **Absent means byte-identical.** A host that constructs no battery sees no change. The
  conformance suite pins this first, as for §8B.

### D5 — Layer 3 is documentation

Each recipe becomes a Cookbook page built from D2 and D3:

- support triage;
- content moderation;
- incident routing;
- the video's "is this appropriate / does this help" game.

No `TriageClassifier` type exists. That keeps the library a tool-calling library, per the scope
memo.

### D6 — `ModelRouterClassifier` is not decided here

- A per-query model router is the cookbook's headline saving (claimed 83%).
- It also directly contradicts `SPEC.md §8`, *Right-size routing*: toolnexus implements the
  **deterministic, per-job-class** point on the cost/quality frontier, "not a learned per-query
  router", and transmits the configured `model` verbatim (conformance-tested).
- Adding the battery means amending that paragraph. **That is the owner's decision**, and this
  ADR records it as open rather than settling it by building the battery.

---

## Evidence (to be filled in by `spikes/judge-adapters/`)

The Go spike must, against the shipped `golang/` `Classifier` with `style: "static"` (hermetic)
and at least one live backend:

1. **ToolGuard end to end.** An agent run where `ToolGuard.asHook(next)` allows one call, denies
   one, and suspends one through §10, with `next` still invoked. It needs a control where `next`
   records that it ran.
2. **ToolRelevance shrink.** With N tools offered, measure how many reach the model and whether
   the task still completes. Report tokens saved and task-success before and after, not just the
   count.
3. **Escalation.** A below-gate answer reaches the fallback. Measure how often that happens on the
   live backend.
4. **A consumer rewrite.** Show that the core judgment code in an existing consumer
   (`bug-fixer-platform`) gets shorter or simpler on top of these pieces. If it does not, the
   batteries are the wrong abstraction and this ADR says so.

---

## Consequences

- **Parity bill.** Five patterns-or-batteries × seven ports, plus fixtures under
  `examples/judge-adapters/`. The fixtures carry the decision: verdict per canned `Decision`,
  hook side-effect order, and byte-identity when absent.
- **The cheap tier becomes first-class in the loop.** A loop gets per-call risk, per-turn tool
  and skill selection and trimmed tool results. Each of those costs a sub-second classifier call
  instead of a frontier-model call.
- **One new seam may follow.** If `IsComplete` proves useful, a reject-and-retry completion hook
  gets its own ADR.

## Alternatives considered

- **Ship domain batteries (triage, fraud, …).** Rejected, per D5 and the scope memo.
- **Make `ToolGuard` a `Guardrail`.** Rejected: it would lose the third state (see D3).
- **Wait for ADR 0014's `chain`.** Rejected as a blocker: `asHook(next)` is explicit
  composition, and it becomes a thin alias once `chain` exists.
- **Leave it to hosts.** That is the status quo. The spike's consumer rewrite (Evidence §4) is
  the test of whether that is actually worse.

## Sources

- TypeSafe official cookbooks and patterns — <https://systemonemodels.org/examples/cookbooks/>
- `paramjeetn/jev-cookbook` — <https://github.com/paramjeetn/jev-cookbook>
- Langfuse, *Using TypeSafe's Jev for evals* (2026-09-18) — <https://langfuse.com/blog/2026-09-18-using-typesafes-jev-for-evals>

## Evidence (adversarial check, 2026-09-27)

- **Baseline port is faithful.** `spikes/judge-adapters/baseline.go` matches `bug-fixer-platform/apps/api/internal/engine/decide.go:64-99` (choice flattens to the string only; confidence/nearUniform never reach `vals`), `decide.go:139-171` (`decideGate`; `asFloat` reduced to `float64`, which is the only type `vals` ever holds) and `engine.go:865-892` (first-match loop). The "bug" rows come from wfnexus itself, not from a bad port.
- **The bug is live in a shipped workflow.** `workflows/bug-fix.yaml:61-66` has `is_security` (noul) `at_least: 0.6 → fail`. A noul of 0.60-0.70, which is the uncertain band by `judge.DefaultBands` (`internal/judge/judge.go:46-52`), fails the run with no human asked. The engine never calls `judge.Read` or `Bands` (the only `judge.` calls in `engine/` are `Questions` and `Options`), so no code path guards against it. Correction: the band is 0.60-0.70 for this gate, not "0.51".
- **Tests:** `go vet` and `go test -race` pass. Mutating either `Bands.Noul` cut, `ChoiceSure`'s `>` or its `NearUniform` check makes a test fail. **Gap:** flipping the score-confidence comparison (`gate.go` `x.Confidence <= b.High`) still passes, because no row covers a `score` answer, and that is the type the real workflow's `fixability` gate uses.
- **Completion.Verify: both statements are true.** `golang/client.go:258` `Hooks` has four fields (BeforeLLM/AfterLLM/BeforeTool/AfterTool) and no Verify. `Completion.Verify` lives in `golang/agents/agent.go:61-63` (the agents layer), and wfnexus `engine/step.go:170-178` uses that. The ADR line "There is none in the Go port today" should say "none in client `Hooks`; it exists as `agents.Completion.Verify`".
- **Spec refs check out:** SPEC.md §8 (line 1417), §8B (1806, noul carries no confidence at 1852), §10 (2091).
