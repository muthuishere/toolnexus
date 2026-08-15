## Context

`SPEC.md §2` defines the MCP tool `execute(args)` with no progress or liveness semantics, and every
port therefore treats the per-server `timeout` (default 30000 ms) as a total-duration deadline on
`tools/call`. MCP servers can report liveness via `notifications/progress`, but the protocol only
permits them to do so when the client attaches a `progressToken` to the request — which no port does.

`js/` is a special case worth naming: it *appears* to handle this. `js/src/mcp.ts:203` passes
`resetTimeoutOnProgress: true`, but the TypeScript SDK attaches the token only when an `onprogress`
handler is supplied, so the option has never had an effect. A spike (`proposal.md`) proves it — the
server reports `progressTokenReceived: false` and sends zero notifications.

The mechanism differs per SDK, which is the central constraint: this change must produce
**identical observable behavior** across seven ports built on six different client implementations.

| port | mechanism |
|---|---|
| js | `onprogress` hook on `callTool` (the SDK then attaches the token and honors `resetTimeoutOnProgress`) |
| python | `progress_callback=` on `session.call_tool`; `read_timeout_seconds` stays a hard deadline, so the reset is ours to implement |
| golang | set `req.Params.Meta.ProgressToken` (`mcp/types.go:167`) + route `client.OnNotification` (`client/client.go:127`); `context.WithTimeout` cannot be extended, so the deadline construct must change |
| java | official SDK progress handler on the client |
| csharp | `IProgress<ProgressNotificationValue>` on `CallToolAsync` |
| elixir | in-house — reset the pending timer in `connection.ex:403` `put_pending` |
| clojure | in-house — extend the deadline in `mcp.cljc:326` `await-response!` |

## Goals / Non-Goals

**Goals:**

- A `tools/call` whose server streams progress is bounded by time **since the last progress
  notification**, not since the call started.
- Every `tools/call` requests a progress token, so servers are permitted to report.
- A server that reports no progress behaves byte-identically to today.
- The contract is pinned in `SPEC.md §2` and verified by one shared `examples/` fixture, not by seven
  independently-written per-port tests.

**Non-Goals:**

- Surfacing progress to the caller (no `onProgress` callback on `ToolContext`, no streaming of
  progress into the client loop). This change is about liveness only; a progress *feed* is a separate,
  larger surface and would move §1 `Context`.
- Changing connect / `initialize` / `tools/list` bounding. `mcp-load-lifecycle`'s existing per-phase
  budget stays exactly as specified.
- Progress on inbound `serve` (§7C) — this is the client side only.
- MCP cancellation (`notifications/cancelled`) — related, but its own change.

## Decisions

### D1 — The timeout becomes an idle timeout, not a longer one

Reset the remaining budget to the full `timeout` on each `notifications/progress` matching the
in-flight call's token. A call is abandoned after `timeout` of **silence**.

*Alternative rejected:* raising the default timeout. It trades one arbitrary number for a larger
arbitrary number and still kills a genuinely long tool, while making a hung server take longer to
detect. Idle-timeout semantics strictly dominates: it detects a hang *faster* in wall-clock terms
than a raised ceiling would, and never kills a live one.

### D2 — No absolute cap in v1

A server that streams progress forever keeps the call alive indefinitely. We accept this, because the
existing escape hatches already cover it and are strictly better-targeted:
`ctx.signal`/`ctx` cancellation (§1 `Context`) and the client's run-level `timeoutMs` (§8 Resilience)
both already abort an in-flight tool call. Adding a third, MCP-specific ceiling would be a fourth
number to reason about with no case the other two miss.

*Alternative considered:* a `maxTotalMs` per server. Deferred — it can be added later without
breaking anyone, whereas removing it could not. Recorded in Open Questions.

### D3 — Progress requested unconditionally, not behind a config flag

Requesting a token is free when the server does not report, and a flag would mean the fixture proves
behavior only in one of two configurations. Uniform behavior is the point of the parity contract.

*Alternative rejected:* opt-in per server. It preserves the bug by default, which is what we are
fixing.

### D4 — The token is a client-generated opaque per-call identity

Each `tools/call` gets a fresh unique token; a notification resets the timer only when its
`progressToken` matches the in-flight call. Ports MUST NOT reset on unmatched or unknown tokens, so
one chatty tool cannot hold another's call open. Ports whose SDK owns token generation (js, python,
java, csharp) inherit this for free; go, elixir, and clojure generate it explicitly.

### D5 — Conformance is fixture-based, since the mechanism is not portable

`SPEC.md` pins the *observable* contract (call survives past `timeout` while progress flows; fails
after `timeout` of silence) and explicitly does NOT pin the mechanism. The shared fixture is the
conformance instrument: a slow MCP server that emits progress at a configurable interval and reports
back whether it received a token. Each port asserts the same two outcomes against it.

The fixture must be runnable by all seven suites hermetically. It is a Node script under `examples/`
— consistent with the existing shared `examples/mcp.json`, which already spawns
`@modelcontextprotocol/server-filesystem` via `npx`, so a Node-based fixture server adds no new class
of test dependency.

### D6 — Go must stop using `context.WithTimeout` for the call

A `context.WithTimeout` deadline cannot be extended. Replace it with a cancellable context driven by a
resettable timer, cancelled on idle expiry, so parent-context cancellation still propagates
(preserving `mcp-load-lifecycle`'s cancellation requirement). Python needs the equivalent: since
`read_timeout_seconds` is a hard deadline, the idle budget is enforced by our own watchdog with
`read_timeout_seconds` left as the outer bound or removed in favor of it.

## Risks / Trade-offs

- **A malicious or buggy server holds a call open forever** → `ctx` cancellation and the §8 run-level
  `timeoutMs` both already abort it (D2). Documented in `SPEC.md §2` so the escape hatch is findable.
- **Seven implementations, six mechanisms, silent drift** → this is the exact failure mode the repo
  exists to prevent; mitigated by D5's single shared fixture with identical assertions, and by the
  per-language parity checklist in `tasks.md`. A port that cannot pass stays an unchecked task rather
  than a silent omission.
- **An SDK does not expose progress at the needed granularity** (java/csharp verified only from API
  surface, not run) → if a port cannot implement the reset, it keeps today's hard deadline, the gap is
  named in `CHANGELOG.md` and left unchecked in `tasks.md`. Parity debt stated beats parity assumed.
- **Go/python deadline rework touches cancellation paths** → `mcp-load-lifecycle`'s cancellation
  scenarios are existing tests; they must still pass unchanged, which is an explicit task.
- **Timer churn on a chatty server** (progress every few ms) → reset is O(1); the in-house ports
  should reset a deadline value rather than re-arm a timer per notification where that is cheaper.

## Migration Plan

Additive and non-breaking; no data or config migration. A server that reports no progress is
unaffected. Rollback is reverting the change — no persisted state, no wire-format change beyond an
optional `_meta.progressToken` that spec-compliant servers already ignore when unused.

## Open Questions

1. **Absolute cap** — should a per-server `maxTotalMs` land now rather than later (D2)? Deferring is
   the reversible choice, so the default is defer unless the owner wants the ceiling.
2. **Java/C# mechanism** — verified from API surface only. If either SDK's progress handler cannot be
   scoped to a single in-flight call (D4), that port needs a correlation layer or stays on the hard
   deadline.
3. **Should `timeout` be renamed** in docs to `idleTimeout` to match its new meaning? It would be
   clearer but is a config-surface change across seven ports and every example; the recommendation is
   to keep the key and document the semantics.
