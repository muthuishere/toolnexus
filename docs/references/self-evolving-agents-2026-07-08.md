# Reference: self-improving harness + self-evolving skills

Owner intake 2026-07-08. Two sources, digested for the nexus stack. This is the REFERENCE copy
(toolnexus); the build SPEC lives in the brain repo (`brain/docs/SPEC-self-evolving.md`).

## Where things live (the decision)

| Concern | Home | Why |
|---|---|---|
| Learning lifecycle: experiences → reflect → curate → evolving playbook → regression-gate | **brain** | brain is already the persistent, git-backed, evidence-first store with `record/recall/consolidate/check`; the new verbs extend it |
| Skill library: versioned skills, failure-driven synthesis, validate/deprecate, metrics | **brain** | same store, same git genealogy; skills are learned artifacts |
| RUNTIME use of evolved skills by agents | **toolnexus** | toolnexus builds/runs agents; it LOADS `brain playbook` + `brain skill search` output as agent context/tools |
| OS doctrine (who runs the loop, when) | deemwar-one-os `doctrine/self-improving-harness.md` | cadence + guardrails for CEO/lines/desks |

One-liner: **brain learns, toolnexus acts.** No new repo needed.

## Source 1 — Lilian Weng, "Agent Harness" (lilianweng.github.io, 2026-07-04)

A harness = everything around the model: plan/act/observe loops, tools, memory, evaluation,
permissions. Key adoptables:

- **Evolving playbook**, not a growing text blob: structured itemized entries; a *reflector*
  distills each trajectory (success AND failure) into deltas; a *curator* merges/dedups/
  supersedes.
- **Regression-gate self-edits**: any proposed harness/playbook change is checked against
  held-in cases that already worked; rejected candidates are logged, not applied.
- **Failure root-cause, verifier-grounded** — record the pattern + cause, not the surface error.
- **Guards**: evaluator OUTSIDE the loop (no self-grading), anti-diversity-collapse
  (probe poor-looking paths on paper), negative results first-class, human up the stack
  (strategy + irreversible forks only).
- Prereq: recursive improvement only works with a capable base model.

## Source 2 — arXiv 2605.23904, "SkillOpt: self-evolving skills"

Closed-loop skill lifecycle: discover → verify → curate → reuse → refine.

- **Skill = executable procedure** with id, description, preconditions, success criteria,
  cost (calls/latency), metrics (success rate, invocation count).
- **Failure-driven synthesis**: repeated failure patterns generate candidate skills.
- **Validation-gated acceptance**: candidate must beat prior version on a held-out set
  (e.g., ≥5% improvement) or it's archived.
- **Library**: versioned repo, primitives + composites (chaining), genealogy (what replaced
  what → rollback), deprecation of low-usage/degraded skills.
- **Explainability + human-in-loop**: every change stores its rationale; manual curation
  coexists with automated.

## Rollout in our OS

1. brainlib implements the new brain verbs (`reflect`, `curate`, `playbook`, `regress`,
   `skill register|validate|search|metrics|deprecate`).
2. toolnexus agents load `brain playbook`/`skill search` at spawn (runtime consumption).
3. Fleet contract: reflect+curate on every `work done/block`; desks at session end
   (crypto desk directive sent 2026-07-08); CEO retro = curator + regression pass.
