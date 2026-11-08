# add-round-boundary-hook

## Why

Karya (go-agent-cli, issue #192) needs a seam at each loop **round boundary** — after a round's
tool results are in the transcript and before the next request — that can append a message: todo
reminders ("you still have 3 open todos"), sub-agent completion notices, budget warnings.

`beforeLLM` does not cover it:

1. It cannot tell a round boundary from the first call; every host re-derives "did tools just run".
2. Its only write is **replace the whole transcript** — appending one reminder means copying and
   re-shaping the per-style message list (OpenAI vs Anthropic content blocks).
3. It never fires after the model **stops**: the loop has already returned, so "you still have open
   todos, keep going" is impossible — the defining Karya case.

## What Changes

- New optional hook `afterRound({ turn, final, text, messages })` → `{ inject?: string[] }` in
  `ClientOptions.hooks`, all eight ports, run + stream, every style.
- Non-final round: fires after all tool results are appended; injected strings become user messages.
- Final round (no tool call) with a turn remaining: a non-empty `inject` appends the messages and
  **continues** the loop; empty ⇒ the run ends exactly as today.
- Not fired on pending halts, errors, `translate`, or an ACP model source. A failing hook
  propagates like `beforeLLM`.
- §7D agent runs forward it like every other hook (verbatim).
- Absent ⇒ byte-identical.

## Impact

SPEC.md §8 Hooks; capability `client-observability`; all eight clients; CHANGELOG.
