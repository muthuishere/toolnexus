# Stream a `retry` event and honour `x-should-retry` (issue #189)

## Why

Karya (go-agent-cli) streams turns to a terminal. When the provider answers `429`/`529`/`5xx`
the client silently backs off — sometimes for a server-dictated `Retry-After` of many seconds —
and the user sees a frozen spinner. Claude Code and other agent CLIs show "Retrying in Ns ·
attempt A/M". The host cannot build that today: the retry decision and the wait are internal to
the loop. Separately, Anthropic/OpenAI send `x-should-retry: true|false`; the official SDKs obey it
and toolnexus ignores it, so we retry what the server says is terminal and give up on what it says
is transient.

## What changes

- `stream()` emits `{type:"retry", attempt, max, delayMs, status?, reason}` immediately before each
  retry wait. `attempt` is 1-based, `max` = `retries`, `delayMs` = the actual wait (Retry-After
  honoured), `reason` = `"HTTP <status>"` or `"network"`.
- `x-should-retry: true|false` (case-insensitive name and value) on a non-ok response overrides the
  default `retryable` classification before `onError` runs, in `run`, `ask` and `stream`.
- Non-streaming output is otherwise byte-identical. Absent header ⇒ unchanged classification.
- **BREAKING (Rust only)**: `StreamEvent` gains a `Retry` variant; exhaustive matches need an arm.
  Other ports' event unions/classes gain a member, which is additive in their idiom.

## Impact

`SPEC.md` § Streaming + § Resilience; the LLM fetch/retry loop and stream event type in all eight
ports.
