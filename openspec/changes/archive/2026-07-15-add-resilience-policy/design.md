# Design: add-resilience-policy

## Context

The client loop hardcodes retry-vs-fail (`SPEC.md §8` Resilience). This change makes that one
decision host-configurable and nothing else. Suspension (§10) is untouched — it stays a
user-action pause. JS is the reference port.

## Goals / Non-Goals

**Goals**
- One optional `onError` classifier → `retry | fail`, host-owned, replacing the hardcoded rule.
- Byte-identical default when `onError` is absent.
- A hermetic resilience matrix as the conformance test in every port.
- One resilience mechanism in the repo — the pipeline's `retry` combinator delegates here.

**Non-Goals**
- No suspend tier, no LLM-originated suspension, no new `Request` kind — §10 unchanged
  (owner decision 2026-07-15: "we don't need suspend unless for user action").
- MCP failures (already isolated, §2) and tool-execution failures (already `isError`, §8) —
  out of scope, unchanged.
- No circuit breaker / goal classifier here — if wanted, it belongs to the pipeline's `retry`
  combinator, which delegates to this seam (see D4).

## Decisions

### D1 — The seam: `onError`, two tiers

A single callback, idiomatic per port:

| Port | Signature (shape) |
|------|-------------------|
| JS | `onError?: (info: ErrorInfo) => "retry" \| "fail"` |
| Python | `on_error: Optional[Callable[[ErrorInfo], Literal["retry","fail"]]]` |
| Go | `OnError func(ErrorInfo) Tier` (Tier = `TierRetry\|TierFail`) |
| Java | `Options.onError(Function<ErrorInfo, Tier>)` |
| C# | `Func<ErrorInfo, Tier>? OnError` |
| Elixir | `on_error: (ErrorInfo.t() -> :retry \| :fail)` |

`ErrorInfo` carries `{ error, status?, attempt, retryable }`. Absent ⇒ the default classifier:
`retryable && attempt < retries → retry`, else `fail`. This is exactly today's behavior
expressed as a default `onError`, so the default path is provably byte-identical.

### D2 — Where it hooks in

Inside the existing HTTP-with-retry helper (JS `fetchWithRetry`, Go `postWithRetry`, Elixir
`request/…`). On a failed attempt, replace the current hardcoded "retry-or-throw" branch with
the resolved classifier's result:

- `retry` → existing backoff path, still bounded by `retries`; `Retry-After` honored.
- `fail` → throw/return the error now (skip remaining retries).

One branch changes. No new call sites, no new types beyond `ErrorInfo`.

### D3 — No suspend tier (the simplicity decision)

A failure is not a user action, so it does not get a suspension. `fail` surfaces through the
existing error path exactly as today. This removes, versus the first draft: a new `Request`
kind, an LLM-originated suspension semantics, an answer-to-retry protocol, and a
streaming-resume rule. The whole §10 surface stays put. If a specific failure ever needs a
human (e.g. `402` → top up → resume), that is a separate opt-in change for hosts already
running §10, and it must forbid suspend-after-first-byte (a resumed stream would replay
already-emitted tokens). Deferred, not designed here.

### D4 — Resilience lives once; the pipeline delegates

`add-agent-pipeline` proposes a `retry` combinator with a circuit-breaker default. To keep one
mechanism, that combinator SHALL call this `onError` seam for the retry/fail decision rather
than classify failures itself; the circuit-breaker (loop-kill by `hash(tool+args)`) is a
distinct concern (runaway *tool* loops, not HTTP failures) and stays in the pipeline. The two
proposals must land this boundary explicitly. This change owns HTTP-failure classification; the
pipeline owns loop-shape safety.

### D5 — Naming

`onError` returning a tier reads as a policy decision (not a side-effecting handler). It is
distinct from the `before/after LLM` hooks (which intercept requests/responses, not failures)
and from the `retries` count (which bounds the `retry` tier). Three concepts, but each with a
single, non-overlapping job: `retries` = budget, `hooks` = interception, `onError` =
retry/fail policy. Documented together so the boundary is clear.

## Risks / Trade-offs

- **A third error-related option** (`retries`, `hooks`, `onError`). Mitigated by D5's explicit
  separation of duties and by keeping `onError` to two tiers — the minimum that lets a host
  override the hardcoded set.
- **Six-port parity cost** — accepted; the resilience matrix is the shared conformance gate.
  Small surface (one callback, two tiers) keeps it cheap.

## Migration

None. Absent `onError` ⇒ byte-identical. §10, hooks, retries config all unchanged.

## Open Questions

- Confirm the `add-agent-pipeline` `retry` combinator delegates to `onError` (D4) — settle
  before either change applies.
