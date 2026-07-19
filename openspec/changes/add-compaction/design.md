# Design — add-compaction

## Context

js spike `js/spike/compaction.ts` + `compaction-demo.ts` (11/11) is the behavioral
reference. The decisive finding: the shipped loop applies `beforeLLM`'s returned messages by
**replacing the working transcript** (`messages = ov.messages`, `js/src/client.ts`), and that
array flows into `RunResult.messages` and the ConversationStore. So compaction is a pure
function wired through an existing seam — not a loop change. Design provenance:
`docs/references/agent-fundamentals-audit-2026-07-17.md` (copilot 3-tier + BudgetExceeded;
codex cache-prefix scope; openclaw tool-pair-safe split + pre-compact memory flush).

## Goals / Non-Goals

**Goals:**
- Keep a long-lived agent under its context budget with one opt-in helper.
- Zero core loop change; byte-identical when unused.
- One correctness invariant that ports cannot get wrong: tool-pair safety.

**Non-Goals:**
- Background/foreground tiering; a provider `BudgetExceeded` typed error; exact tokenizers;
  semantic recall of summarized turns. (All deferrable; the helper is the 80%.)

## Decisions

1. **`beforeLLM` helper, not a loop feature.** Matches the "composition, not a framework"
   principle and makes 6-port parity a pure-function port. The helper returns `{ messages }`
   only when it compacts; otherwise nothing (the loop leaves the transcript untouched).
2. **Tail begins at a user turn.** The retained tail is chosen by scanning back to a `user`
   message that fits `keepTail`. A user turn is always a clean boundary, so no `tool` result
   is ever orphaned from its `tool_calls`. Fallback: if no user boundary fits, keep back to
   the last user turn (safety over size). This is the single invariant the conformance
   fixture pins hardest.
3. **Leading system message preserved verbatim.** Identity/soul/skills are never summarized —
   only the conversational body between the system prompt and the tail is.
4. **Summary is pluggable and may be an LLM call.** `summarize(older) → string`. The library
   does not pick a model or make a call on the host's behalf by default — the host supplies
   the function (it can call a cheap model, or be deterministic). Token counting is likewise
   pluggable; default `chars/4`.
5. **`flushToMemory` composes with §7E.** When set, a system reminder is injected before the
   summary so the model can persist durable facts via the `memory` tool before the head is
   dropped. Off by default.
6. **Estimator, not tokenizer.** The default `chars/4` estimate is deliberately approximate;
   exactness is the host's call via `countTokens`. The fixture uses the default so the
   boundary math is byte-identical across ports.

## Risks / Trade-offs

- **A summary loses detail** — inherent; mitigated by `keepTail` (recent turns verbatim) and
  `flushToMemory` (durable facts persisted before summarizing). The host tunes the budget.
- **The estimator is approximate** — a transcript can momentarily exceed the true window if
  `chars/4` under-counts; documented, and `countTokens` lets a host pin a real tokenizer.
- **Summarize-mid-run changes what the model sees** — intentional; the spike's C6 proves the
  loop continues correctly across a compaction, and the tail keeps the immediate working
  context. A mock/host whose control flow counts raw tool messages must count its own calls,
  not the (legitimately compacted) transcript — noted for port test authors.
- **Interaction with §10/§7D** — none: compaction runs at `beforeLLM` on an ordinary run; a
  suspended/durable transcript is rewound by the runtime independently. The helper never
  touches pending state.
