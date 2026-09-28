# Judge adapters: a simple `ask` / `gate` over `Classifier`

## Why

`Classifier` (SPEC §8B, 0.18.0) is correct but verbose. The video-scale request — a state
plus two yes/no questions — takes ~16 lines of nested question-type maps in Go, and every
consumer re-derives the same 0.30/0.70 cut-offs by hand. The first real consumer
(wfnexus, `bug-fixer-platform/apps/api/internal/engine/decide.go`) shows the cost: its
`decide:` gates act on the raw number with **no uncertain band**, so
`workflows/bug-fix.yaml:61-66` (`is_security at_least 0.6 → fail`) fails a run on a 0.62
reading that its own `judge` package would call uncertain.

A seven-port spike (`spikes/judge-adapters/`, ADR 0035) built the smaller API in every
port against one shared contract (`spikes/judge-adapters/shared/`); all 16 shared cases
pass in js, python, golang, java, csharp, elixir and clojure. The Donkey Kong call site
drops from 16 lines to 5 (Go).

## What changes

1. **Builders** — state from a map, plus `context` + `message` (+ extra map) sugar;
   questions as an ordered list `noul(name, …)` / `choice(name, …, options)` /
   `score(name, …, levels)`; a duplicate name is an error naming it.
2. **`ask(classifier, state, questions, bands?)`** — answers by name. `noul` answers carry
   `band ∈ {yes, no, uncertain}`; `choice` / `score` answers carry `sure: bool`. (The spike
   used one `band` word for all three; five ports reported that a choice can never be "no",
   so the fake label is dropped.)
3. **`Bands`** — `{low: 0.30, high: 0.70}` by default, overridable per call; cut-points are
   exclusive on the confident side.
4. **`gate(classifier, state, questions, rules, bands?)`** — first-match rules
   (`below` / `at_least` / `is`); an uncertain or missing answer escalates to a §10 `input`
   `Request` instead of acting.
5. **Library fixes the spikes hit** — a one-line static classifier from recorded decisions;
   a public question → wire conversion; export Python `NoulCriteria` / `Request` and C#
   `Decision.FromJson`; the Go answer type marshals flat.

**Wire unchanged.** The builders produce the existing §8B `evaluate(state, questions)`
inputs byte-identically. A host that uses none of this observes byte-identical behaviour.

## Out of scope

- **Batteries** (`ToolGuardClassifier`, `ToolRelevanceClassifier`, `SkillRelevanceClassifier`,
  `ToolResultFilterClassifier`, `IsCompleteClassifier`, `AgentRouterClassifier`,
  `ContentGuardClassifier`) — follow-up `add-judge-batteries`, per ADR 0035.
- **`ModelRouterClassifier`** — open owner decision (conflicts with SPEC §8 routing stance).
- Changing wfnexus — that is a consumer PR after release.
