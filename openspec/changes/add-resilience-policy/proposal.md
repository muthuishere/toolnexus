# Proposal: add-resilience-policy

## Why

Real deployments fail in the middle: the LLM endpoint returns `402` (out of credits),
`401` (expired key), `429` past the retry budget, or the network drops. Today the client
loop has exactly two responses to an LLM-call failure — **retry** (transient `429`/`5xx`/
network, bounded backoff) or **raise/return an error** (everything else). That is fine for a
script and wrong for a resident agent: "out of credits" should not kill a long conversation
the user can rescue by topping up; an expired token should be recoverable by rotating it, not
by losing the run.

toolnexus already owns the primitive for exactly this: **§10 suspension**. A tool can return
`Pending(request)`; the host's `waitFor` resolves it out-of-band; the run resumes from the
persisted transcript — across restarts, via the `ConversationStore`. Human-in-the-loop and
MCP elicitation already ride it. **LLM-call failures do not — yet.** This change routes them
onto the same primitive, so a failure becomes a third, host-chosen option beside retry and
fail: **suspend and resume**. No new subsystem — one classifier plus the suspension that
already exists. Simplicity is the point: the host learns one mechanism (`waitFor`) for
questions *and* failures.

## What Changes

A host-configurable **failure policy** on the client, with three tiers per failure class:

- **retry** — re-issue the call now (transient errors; today's default within budget).
- **fail** — surface immediately, skip remaining retries (programmer errors: `400`, unknown
  tool, malformed config — already the default for these).
- **suspend** — emit a §10 `Request` (`kind: "error"`) describing the failure; the host's
  `waitFor` decides: `ok:true` ⇒ retry the call now (they fixed it), `ok:false` ⇒ abort with
  the error. **Without `waitFor`, `run` returns `{status:"pending", pending:request}`** — the
  failure is durably resumable exactly like a human-in-the-loop suspension.

The seam is one optional callback:

```
onError(info) -> "retry" | "fail" | "suspend"      // info: { error, status?, attempt, retryable }
```

- **Absent ⇒ byte-identical to today** — retryable-within-budget → retry, else fail.
- **Present ⇒ the host classifies per failure** — e.g. `on 402 → suspend and ping me`,
  `on 401 → suspend so I can rotate the key`, `on a flaky 500 → retry twice then fail`.

This extends §10 in one precise way: **a suspension may originate from the LLM call**, not only
a tool. The resume action for an LLM-originated suspension is "re-issue the failed LLM request";
`RunResult.status:"pending"` and the `Request`/`Answer` wire shapes are unchanged. Streaming
emits the same `{type:"pending", request}` it already emits for tool suspensions.

Scope is the **LLM path only**. MCP failures already isolate (a bad server → status `failed`,
never fatal, §2) and tool-execution failures already become `isError` and let the loop
continue (§1/§8) — both already resilient, both unchanged.

## Capabilities

### New Capabilities
- `resilience-policy`: host-configurable classification of LLM-call failures into
  retry / fail / suspend, with the suspend tier routed through the existing §10 suspension
  primitive (resumable via `waitFor` + `ConversationStore`).

### Modified Capabilities
<!-- The client-loop and suspension capability specs gain requirements; the cross-language
     contract moves in SPEC.md §8 (Resilience) and §10 (suspension may originate from the LLM
     call). Recorded as spec deltas here + SPEC.md edits in this change. -->

## Impact

- `SPEC.md §8` (Resilience) — the `onError` classifier + the three tiers; default unchanged.
- `SPEC.md §10` — "a suspension may originate from the LLM call; resume re-issues it"; new
  `Request` `kind: "error"` with `data: { status?, attempt, retryable }`.
- All six ports — `onError` (idiomatic name/return per port), the classifier default, the
  suspend→§10 path in both the non-streaming and streaming loops. Byte-identical default.
- Shared fixture `examples/` — none required (failures are stubbed per-port), but a
  **resilience conformance matrix** (402/401/429/500/network + default parity) is the test.
- No breaking change: callers passing no `onError` get today's behavior byte-for-byte.
