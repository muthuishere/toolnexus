# Judge batteries: eight `*Classifier` values over ask/gate, in all seven ports

## Why

`add-judge-adapters` shipped the patterns (`ask`, `gate`, `Policy`, `Tape`) and left the
batteries of ADR 0035 D3 for a follow-up. Without them every host re-writes the same hook glue
around a `Classifier`: rate a tool call before it runs, trim the tools offered to the model,
drop irrelevant tool output, screen inbound text, route a task. The owner asked for all of
them, **including `ModelRouterClassifier`**, which ADR 0035 D6 had left open because it
contradicts SPEC §8 *Right-size routing*.

Owner decision on D6 (2026-09-28): build `ModelRouterClassifier` as an **opt-in** battery.
The default stays deterministic: with no router attached the configured `model` is still
transmitted verbatim (the existing conformance test keeps passing). Only a user-attached
router picks a model per query, from a user-supplied list of model options described in prose
(ADR 0021: the option sentences carry the judgment), and it falls back to the configured model
whenever its pick is not sure.

## What changes

1. **Eight batteries** in js, python, golang, java, csharp, elixir and clojure, each built on
   `Classifier` + the §8B simple-judgment layer, each with a standalone method returning a
   typed verdict and, where a seam exists, `asHook(next)`:
   - `ToolGuardClassifier` — `check(call)` → allow | ask | deny; hook on **beforeTool**,
     `ask` raises a §10 `approval` Request (path B).
   - `ToolRelevanceClassifier` — `select(prompt, tools)`; hook on **beforeLLM** swaps `tools`.
   - `SkillRelevanceClassifier` — `select(prompt, skills)`; standalone (see design D5).
   - `ToolResultFilterClassifier` — `filter(query, chunks)`; hook on **afterTool**.
   - `IsCompleteClassifier` — `check(task, answer)`; standalone (no loop seam, ADR 0035).
   - `AgentRouterClassifier` — `pick(task, agents, fallback)`, hierarchical; standalone.
   - `ContentGuardClassifier` — `check(text)` → allow | review | block; hook on **beforeLLM**.
   - `ModelRouterClassifier` — constructed with the user's model options; `pick(prompt, fallback)`;
     hook on **beforeLLM**.
2. **§8 hooks: a `beforeLLM` override MAY carry `model`** for that turn only. Absent or empty ⇒
   the configured model, verbatim, exactly as today. This is the one loop change, and it is what
   lets a user-attached router take effect. SPEC §8 *Right-size routing* is amended accordingly.
3. **Parity by data**: `examples/judge/batteries/*.json` — recorded `static` classifier calls
   (state + questions + response) and the expected verdict per case, plus the latest-user-text
   extraction cases. Every port asserts all of them; the default question text and role are
   pinned byte-for-byte because the static corpus is keyed by the canonical request.
4. SPEC §8B gains a *Batteries* subsection and idiom rows; ADR 0035 D6 records the decision.

**Absent means byte-identical.** A host that constructs no battery and returns no `model` from
`beforeLLM` sees no change on the wire.

## Out of scope

- A retry-on-incomplete completion seam for `IsComplete` (its own ADR if wanted).
- A `beforeLLM` skill filter: skills live in the system prompt, which the anthropic style
  carries outside `messages`; `SkillRelevance` ships standalone and feeds the S2 allowlist.
- Per-battery question-text overrides beyond `role` (and `dimensions` for ContentGuard).
- `llm`-style calibration of the batteries' thresholds; thresholds are tuned per backend.
- Metric events keep reporting the configured model; only the request body and `afterLLM`
  carry a routed model.
