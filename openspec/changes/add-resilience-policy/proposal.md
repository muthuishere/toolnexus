# Proposal: add-resilience-policy

## Why

Real deployments fail in the middle: the LLM endpoint returns `402` (out of credits),
`401` (expired key), `429` past the retry budget, or the network drops. Today the client
loop hardcodes its response — retry the transient set (`429`/`5xx`/network) with bounded
backoff, fail everything else. The host cannot say "also retry my provider's flaky `400`",
or "fail fast on `429`, don't burn my budget", or "this failure is fatal, stop now". The
retry-vs-fail decision belongs to the host; the library should not own it.

This change makes that one decision host-configurable, and **nothing more**. It deliberately
does *not* add a failure "suspend/resume" subsystem: suspension (§10) stays exactly what it is
— a **user-action** pause (a human logs in, approves, answers). A failure is not a user action,
so it does not get its own suspension path in this change. (If a specific failure ever genuinely
needs a human — top up credits, re-authenticate — that is a small, opt-in phase-2 follow-on for
hosts *already* running §10; see Deferred below. It is not core, and it is not this change.)

Scope is the **LLM path only**. MCP failures already isolate (a bad server → status `failed`,
never fatal, §2) and tool-execution failures already become `isError` and let the loop continue
(§1/§8) — both already resilient, both unchanged.

## What Changes

One optional callback that classifies each LLM-call failure into **two** tiers:

```
onError(info) -> "retry" | "fail"        // info: { error, status?, attempt, retryable }
```

- **retry** — re-issue the call (bounded by the existing `retries` budget + backoff + `Retry-After`).
- **fail** — surface the error now, skipping any remaining retries.

Behavior:

- **Absent ⇒ byte-identical to today** — the default classifier is exactly today's rule
  (`retryable && attempt < retries → retry`, else `fail`), expressed as a default `onError`.
- **Present ⇒ the host owns the retry/fail decision per failure** — widen the retryable set,
  narrow it, or fail fast.

No new `Request` kind, no new suspension origin, no streaming change — because there is no
suspend tier. The wire contract does not move at all; this is a client-options addition plus
one branch in the retry helper.

### Reconciliation with `add-agent-pipeline` (required before apply)

The `add-agent-pipeline` proposal already specs a `retry` combinator with a circuit-breaker
default. To avoid two resilience mechanisms (the exact drift this repo exists to prevent),
**resilience lives once, here at the client**, and the pipeline's `retry`/circuit-breaker
combinator SHALL delegate to this `onError` seam rather than reimplement classification. This
change and the pipeline change must agree on that boundary before either lands.

## Capabilities

### New Capabilities
- `resilience-policy`: host-configurable classification of an LLM-call failure into retry or
  fail, replacing the hardcoded retry-vs-fail decision, with the default preserving today's
  behavior byte-for-byte.

### Modified Capabilities
<!-- The client-loop capability gains a requirement; SPEC.md §8 (Resilience) gains the onError
     seam. §10 is NOT touched — suspension stays user-action only. -->

## Impact

- `SPEC.md §8` (Resilience) — the `onError` classifier + two tiers; default unchanged. **§10 is
  not touched.**
- All six ports — `onError` (idiomatic name/return per port) + the classifier default. One
  branch in the retry helper. Byte-identical default.
- Shared fixture `examples/` — none; failures are stubbed per-port.
- No breaking change: callers passing no `onError` get today's behavior byte-for-byte.

## Deferred (explicitly not in this change)

- **Failure-as-user-action suspension.** If a `402`/`401` should pause for a human to top up or
  re-auth and then resume, that is a §10 suspension originating from the LLM call — genuinely
  useful, but *only* for hosts already running §10 with a `ConversationStore` + resume harness.
  It carries a real subtlety (a mid-stream failure that resumes would replay already-emitted
  tokens, so suspend-after-first-byte must be forbidden or the stream reset). Because it is an
  advanced, opt-in tail — not the common path — it is deferred to its own follow-on change and
  spec'd there, not bolted onto this one. Keeping it out is what keeps this change simple.
