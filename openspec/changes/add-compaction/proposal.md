# Proposal — add-compaction

## Why

A long-lived agent — especially a persona (just shipped in `add-agent-home`) — grows its
transcript until it overflows the model's context window. The fundamentals audit called this
the "persona killer" ("dies within days"). toolnexus has no context lifecycle today: it
raw-appends until the provider errors. This adds **compaction**: summarize the older
transcript, keep a recent tail, stay under budget. A js spike proves it rides the **existing
`beforeLLM` seam** as a pure `messages → messages` helper — **no core loop change** — passing
11/11 including an end-to-end run through the shipped client.

## What Changes

- **`compactor(options)` helper** (per port, in the client/agents surface): returns a
  `beforeLLM` hook. Below `maxTokens` it is a **no-op** (byte-identical to today); above it,
  it replaces the older messages with a summary and keeps a recent tail.
- **Tool-pair safety**: the retained tail always begins at a **user turn**, so a `tool`
  message is never orphaned from its `tool_calls` (the one correctness invariant).
- **System-prompt preservation**: a leading `system` message (identity/soul/skills) is kept
  verbatim, never summarized.
- **Pluggable summary**: `summarize(older) → string`, which MAY call an LLM (the host's
  choice of model); a token estimator is overridable (default chars/4).
- **`flushToMemory` option**: injects a pre-compact system reminder telling the model to
  persist anything worth keeping via the §7E `memory` tool before the head is summarized
  (openclaw's "flush notes before compacting") — composes the two persona features.
- **SPEC.md gains §7F** (context compaction: the beforeLLM contract, tool-pair rule,
  no-op-below-budget guarantee).

**Explicitly OUT of scope**: automatic background/foreground compaction *tiers* (copilot's
3-tier ladder) — the helper is synchronous and host-wired; a typed `BudgetExceeded` provider
error path; token-exact tokenizers (the estimator is pluggable, default heuristic); semantic
retrieval of summarized content.

## Capabilities

### New Capabilities
- `context-compaction`: the `compactor` helper, its tool-pair-safe summarization contract,
  the no-op-below-budget guarantee, and the memory-flush composition.

## Impact

- **Code**: a small `compactor`/`estimateTokens` surface per port (js/python/golang/java/
  csharp/elixir), built on the shipped `beforeLLM` hook — extracted from
  `spike-compaction` (`js/spike/compaction.ts`), rewritten to shipped quality. No client
  loop changes.
- **SPEC.md**: new §7F.
- **Fixtures**: `examples/compaction/` — a transcript with tool groups + expected
  compacted output (tail boundary, summary insertion, tool-pair safety) as the §0
  conformance artifact.
- **Docs**: a compaction section on the persona-agents page (or its own page) + a "keep a
  persona alive for weeks" recipe wiring `compactor` + `flushToMemory` + the memory builtin.
- **No behavior change for existing users**: entirely additive and opt-in — a client with no
  `compactor` hook behaves byte-identically to today.
