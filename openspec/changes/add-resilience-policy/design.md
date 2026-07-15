# Design: add-resilience-policy

## Context

The client loop already retries transient LLM failures and fails the rest (`SPEC.md §8`
Resilience). §10 suspension already lets a tool suspend and the host resume via `waitFor`,
durably through the `ConversationStore`. This change connects the two: an LLM-call failure
the host wants to recover from becomes a suspension, not a dead run. JS is the reference port.

## Goals / Non-Goals

**Goals**
- One optional `onError` classifier → `retry | fail | suspend`, host-owned.
- The `suspend` tier reuses §10 verbatim (same `Request`/`Answer`, same `status:"pending"`,
  same streaming event) — no new suspension machinery.
- Byte-identical default when `onError` is absent.
- A hermetic resilience matrix as the conformance test in every port.

**Non-Goals**
- MCP failures (already isolated → `failed`, §2) and tool-execution failures (already
  `isError`, loop continues) — out of scope, unchanged.
- No built-in credit/auth handling — the host decides what "recovered" means. toolnexus
  bridges the *requirement* onto §10, it does not negotiate credentials (same stance as the
  MCP authorization bridge).
- No automatic circuit breaker or goal classifier — a classifier is a host concern.

## Decisions

### D1 — The seam: `onError`, not a config zoo

A single callback, idiomatic per port, keeps the surface minimal:

| Port | Signature (shape) |
|------|-------------------|
| JS | `onError?: (info: ErrorInfo) => "retry" \| "fail" \| "suspend"` |
| Python | `on_error: Optional[Callable[[ErrorInfo], Literal["retry","fail","suspend"]]]` |
| Go | `OnError func(ErrorInfo) Tier` (Tier = `TierRetry\|TierFail\|TierSuspend`) |
| Java | `Options.onError(Function<ErrorInfo, Tier>)` |
| C# | `Func<ErrorInfo, Tier>? OnError` |
| Elixir | `on_error: (ErrorInfo.t() -> :retry \| :fail \| :suspend)` |

`ErrorInfo` carries `{ error, status?, attempt, retryable }`. No enum ceremony beyond the
three tiers. Absent ⇒ the default classifier: `retryable && attempt < retries → retry`, else
`fail`. This is exactly today's behavior expressed as a default `onError`, so the default path
is provably byte-identical.

### D2 — Where it hooks in

Inside the existing HTTP-with-retry helper (JS `fetchWithRetry`, Go `postWithRetry`, Elixir
`request/…`). On a failed attempt, instead of the current "retry-or-throw" branch, call the
resolved classifier:

- `retry` → existing backoff path (bounded by `retries`; `Retry-After` honored).
- `fail` → throw/return the error now (skip remaining retries).
- `suspend` → build the `Request` and hand control to the §10 path (D3).

The default classifier makes the retry/fail branches identical to today; only `suspend` is new
behavior, and only when the host asks for it.

### D3 — The suspend path reuses §10 exactly

Build `Request{ id, kind:"error", prompt:"LLM call failed: <status/err>", data:{status?, attempt, retryable} }`.
Then:

- **`waitFor` present** — call it (same call site as tool suspension). `answer.ok == true` →
  loop back and re-issue the LLM request (a "retry" the host authorized). `answer.ok == false`
  → surface the *original* error (carry it, don't synthesize). A second suspension on the retry
  is bounded the same way tool re-suspension is (feed back / return pending), no infinite loop.
- **`waitFor` absent** — return `RunResult{ status:"pending", pending:request, messages:<transcript so far> }`.
  Because the transcript up to the failed call is already in `messages`/the store, a later
  `run(prompt, { history })` / `ask(id)` resumes and re-issues the call — identical to resuming
  a tool suspension.

The resume action differs from a tool suspension (re-issue the LLM call vs re-execute the tool),
but the wire contract (`Request`/`Answer`/`status`/streaming event) does not move. That is the
one-line §10 extension: **a suspension's origin may be the LLM call.**

### D4 — Answer semantics kept trivial

`ok:true` = "I fixed it, try again"; `ok:false` = "give up, return the error". No `action`
vocabulary in `answer.data` in v1 — the two outcomes cover top-up-credits, rotate-key, and
wait-then-retry (the host simply resolves `ok:true` whenever it is ready, possibly much later
via the durable path). If a richer action set is ever needed it is additive under `data`.

### D5 — Streaming parity

The streaming loops emit the existing `{ type:"pending", request }` for a `suspend`-classified
failure with no `waitFor`, then end — the same event tool suspension already emits, so stream
consumers need no new case. With `waitFor`, the stream continues after an authorized retry.

## Risks / Trade-offs

- **Re-issuing an LLM call after a long suspension** replays the same transcript — correct and
  idempotent for chat-completions (no side effect on the provider beyond a new billable call).
  Documented.
- **Host classifier that always returns `retry`** could loop; bounded by `retries`, after which
  the default fails. The classifier cannot exceed the budget silently — the budget still caps
  `retry`.
- **Six-port parity cost** — accepted; the resilience matrix is the shared conformance gate.

## Migration

None. Absent `onError` ⇒ byte-identical. Existing suspension/`waitFor`/store code is reused, not
changed.

## Open Questions

- None blocking. A richer `answer.data.action` set is deferred (D4) until a consumer needs it.
