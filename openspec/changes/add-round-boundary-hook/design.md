# Design — add-round-boundary-hook

## Decisions

- **D1 — a new hook, not a `beforeLLM` extension.** See proposal: `beforeLLM` cannot continue a
  stopped loop and only replaces. A separate hook keeps `beforeLLM` semantics untouched.
- **D2 — inject strings, appended as user messages.** `{role:"user", content:"…"}` is valid in the
  OpenAI and Anthropic message shapes alike, so the hook is style-blind and byte-identical across
  ports. Hosts needing richer edits still have `beforeLLM`.
- **D3 — continue-on-stop is pinned in.** On a final round with a turn remaining, a non-empty
  injection continues the loop. Bounded by `maxTurns`, so a hook that always injects cannot loop
  forever; the final-round call is skipped when no turn remains, so the run never ends on a
  dangling user message after its answer.
- **D4 — streaming.** A continued final round emits no `usage`/`done`; its text deltas were already
  streamed. The single terminal `done` is unchanged.
- **D5 — not on halts/errors/translate/ACP.** Pending halts already end the round; `translate` has
  no rounds; ACP agents own their loop.
- **D6 — failure = beforeLLM failure.** Thrown/returned error propagates; no further request.

## Event

| field | meaning |
|---|---|
| `turn` | 0-based round index (as `beforeLLM`) |
| `final` | `true` when the model answered with no tool call |
| `text` | that answer's text when `final`, else `""` |
| `messages` | the working transcript (read-only by contract) |
