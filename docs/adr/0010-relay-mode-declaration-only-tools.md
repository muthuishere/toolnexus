# ADR 0010 — Relay mode: declaration-only tools (surface `tool_use` instead of executing it)

**Status:** ACCEPTED (2026-07-30) — spike resolved AMBER; both forks decided by the
owner: **F1-a** (add an answer-carrying resume entry point) and **F2-a** (one
`Request` carrying all N calls in `data.calls`). Implementation proceeds through
OpenSpec across all six ports.
**Date:** 2026-07-30
**Issue:** [#37](https://github.com/muthuishere/toolnexus/issues/37)
**Evidence:** `docs/spikes/0001-relay-mode-on-the-suspension-primitive.md`
**Consumer:** routsi (`github.com/muthuishere/routsi`) ADR-010 + spike 004
**Affects:** `SPEC.md` §8 (client loop) + §10 (suspension) · all six ports

## Context

routsi uses toolnexus as its OpenAI→Anthropic/Gemini translator. It wants to relay
**client-declared** function schemas: the model emits a tool call, and the **HTTP
client — not toolnexus — executes it**. That is standard OpenAI function calling, and
it is a different thing from a builtin:

- **Builtins** — executed by toolnexus *on the proxy host* (shell, file I/O). Exposing
  these from a proxy is a security hole. `Builtins:false` stays off. Non-negotiable.
- **Client-declared tools** — the model only *emits* a call; the caller executes it.
  Nothing runs on the proxy.

Today toolnexus collapses the two: every tool is an executor, so a translator that
must not execute has to drop all tools, and an Anthropic model behind the translator
can never do function calling — while the same request through raw passthrough can.

## What the spike changed about the framing

Issue #37 proposes a new mode (`Toolkit.RelayTools`, or an `Ask` option that returns
on the first `tool_use`). The spike found that **the loop already has a
no-execute-and-return path** — the §10 suspend/resume primitive — which post-dates
the issue's reading of the code:

- A tool returns `Pending(Request{…})`; `resolvePending` (`golang/client.go:1012`)
  either halts the run with `Status:"pending"` + the `Request`, or calls the host's
  `WaitFor` and **re-executes the tool with the `Answer`**, whose result becomes the
  `tool_result` block fed back to the model.

That is relay, structurally. And it matches this repo's stated doctrine:

> "There is **no auth subsystem** — there is a suspend/resume primitive, and auth is
> a use of it." — `SPEC.md:1248`

Relay should be **a use of the same primitive**, not a second loop mode. A second
mode would fork the agent loop in six languages to gain nothing.

The spike also closed routsi ADR-010's open question: `ConversationStore` **already**
round-trips `tool_use`/`tool_result` pairs structurally (raw provider block maps,
persisted verbatim by `Ask` — `client.go:1153-1159,1217-1222,648`). No upstream work
for item 4.

## Decision

1. **Relay is a §10 tool, not a loop mode.** Add a declaration-only constructor —
   `RelayTool(name, description, schema)` — producing a `Tool` that:
   - on first execution returns a suspension whose `Request` carries the call
     structurally, and
   - on retry-with-answer returns the caller's supplied output as its `ToolResult`.

   No change to the execute-or-not branch in the loop. `Builtins:false` unchanged.

2. **Pin the relay wire shape in `SPEC.md` §10.** The suspension's `Request` gains a
   reserved `kind:"tool_call"` and a pinned `data` shape carrying the provider's
   `tool_use` id, name and input — so a caller reads structured fact, not a
   convention. `Answer.data` carries the caller's tool output (plus an error flag).
   Byte-identical keys across all six ports, like the rest of §10.

3. **A relay suspension is not a tool error** — it inherits §10's existing rule
   (`SPEC.md:1390-1394`): `isError:false`, `pending:true` on the observability event,
   no `afterTool` failure path. Relaying is normal operation for a proxy, and must not
   move error-rate metrics or trip circuit breakers.

4. **Guard against proxy-side execution by name collision.** A relay tool whose name
   collides with a builtin name is rejected at toolkit construction. This is routsi
   ADR-010 item 3, enforced upstream so it cannot be socially engineered downstream by
   a future toolkit change.

5. **Absent relay tools, behavior is byte-identical.** Same rule the resilience-policy
   and skill-extension changes followed: no relay tool declared ⇒ not one observable
   difference in any port.

## Forks — DECIDED by the owner, 2026-07-30

Both were contract-level and land in six ports, so the spike deliberately left them
open. The owner ruled **F1-a** and **F2-a** — both the recommended options, recorded
below with the alternatives they beat.

### F1 — How does a *durable* host resume a relayed call?

routsi is a proxy; it cannot hold a `WaitFor` closure across an HTTP boundary, so it
takes §10's durable path (`SPEC.md:1370-1372`). But on a durable halt the loop
**writes a placeholder error result into the transcript before returning** — the
halted-tool transcript rule (`client.go:1015` → `:1217-1222` → `:1242`;
`SPEC.md:1425-1431`), verified byte-identical across six ports on 2026-07-18. The
`tool_result` slot for that `tool_use_id` is therefore already filled with
`is_error:true`, and **there is no entry point that injects an `Answer` into a
persisted run** (`Run`/`Ask` take a prompt, not an answer).

- ✅ **F1-a (CHOSEN) — add an answer-carrying resume entry point** (`RunWithAnswer` /
  `Ask(..., answer)`). Adds API surface; leaves the shipped transcript rule untouched.
  Additive, and useful beyond relay — it is the missing half of the durable path
  generally.
- ❌ **F1-b (rejected) — relay-specific transcript rule**: leave the `tool_result` slot unfilled on
  a relay halt so the caller fills it. Smaller API, but edits a rule already verified
  identical in six ports, and produces a transcript no port currently emits.

### F2 — Do parallel tool calls survive relay?

OpenAI clients expect **every** `tool_call` in a turn. §10 deliberately surfaces only
the **first in tool-call order** and drops the rest (`client.go:1231-1241`;
`SPEC.md:1395-1399`; tested as intended at `golang/pending_test.go:453,512`). A turn
with three calls would relay one.

- ✅ **F2-a (CHOSEN) — one `Request`, N calls inside** (`data.calls` as an array).
  Preserves §10's hardened first-in-order rule exactly, and matches OpenAI's
  `tool_calls` array shape one-to-one.
- ❌ **F2-b (rejected) — relax the halt rule for relay tools** so N suspensions surface as N
  requests. Conceptually cleaner; re-opens a concurrency contract that was hardened
  on purpose.

## Observation (filed, not fixed)

On the durable-halt path the assistant message keeps **all N** `tool_use` blocks while
the following user message carries only the **first** `tool_result`
(`client.go:1231-1241`). Anthropic requires a `tool_result` per `tool_use`, so that
saved transcript is not directly replayable to the provider. It is unreachable via the
intended in-process resume (retry-with-answer never replays it) and bites only a
durable host that replays history — routsi's exact shape.

F1-a + F2-a together give relay a path out, and the implementation **must** take it:
the single first-in-order `Request` carries all N calls (`data.calls`), so the caller
returns N results, and the resume entry point must fill **every** outstanding
`tool_result` slot for that assistant turn — not just the halted tool's. That is a
requirement on this change, stated here so it cannot be missed.

The general (non-relay) case — two concurrent *auth* suspensions on a durable host —
is **not** fixed by this ADR and is not fixed inline. It remains an open defect
against §10's durable path and wants its own change.

## Alternatives

- **A new `RelayTools` field + a return-on-first-`tool_use` loop mode** (issue #37 as
  written). Rejected: forks the agent loop in six languages to re-implement what §10's
  halt path already does, and creates a second suspension mechanism — the exact thing
  `SPEC.md:1439-1441` says must not exist.
- **Keep dropping tools; tell proxies to use passthrough for tool use.** Rejected —
  proxy-managed memory and tool use then cannot coexist at all (routsi ADR-010).
- **Prompt-encoded tool emulation** (routsi ADR-011's technique). Rejected — these
  upstreams have native tool APIs.

## Consequences

- **Six-port obligation.** Per the prime directive this lands in js / python / golang /
  java / csharp / elixir, or it is not done. Streaming and non-streaming loops both.
- Sequencing for the consumer: routsi ADR-010 stays Proposed until this ships; its
  item 4 (memory round-trip) is already satisfied and can be struck.
- The declaration half is free — `ToOpenAI`/`ToAnthropic`/`ToGemini`
  (`golang/adapters.go:41,57,70`) already emit each provider's native tool shape.
- With F1/F2 decided this goes through `/opsx:propose` as a normal OpenSpec change
  with a per-language parity checklist, spec deltas, and the `SPEC.md` §10 edit in the
  same diff.
