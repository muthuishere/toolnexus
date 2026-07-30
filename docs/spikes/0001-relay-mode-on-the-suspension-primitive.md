# Spike 0001 — can relay mode (issue #37) be built on the §10 suspension primitive?

**Status:** RESOLVED — source-verified against `golang/` on branch `cljc`, 2026-07-30.
**Verdict:** **AMBER.** Two of the four questions come back clean and remove work
from the estimate; two surface genuine design forks that need a decision before any
six-port implementation. Not "all good" — see [Forks](#the-two-forks).
**Feeds:** toolnexus ADR-0010 · consumer: routsi ADR-010 + routsi spike 004.

## Question

Issue #37 asks for a *declaration-only* (relay) tool: the model emits `tool_use`,
toolnexus **does not execute it**, the caller gets `id`/`name`/`input` and feeds a
`tool_result` back on the next turn. routsi needs this to proxy standard OpenAI
function calling through toolnexus's OpenAI→Anthropic/Gemini translator.

The spike's real question is not *"is relay possible"* but **"is relay a new loop
mode, or is it the §10 suspend/resume primitive wearing a different hat?"** — because
this repo's doctrine is explicitly one primitive, not a subsystem per use case:

> "There is **no auth subsystem** — there is a suspend/resume primitive, and auth is
> a use of it." — `SPEC.md:1248`

If relay is a use of §10, the change is a constructor plus wire-shape pinning. If it
is a second loop mode, it is a fork of the agent loop in six languages.

## Findings

### Q1 — Does the loop hard-execute every `tool_use`? · **YES, but §10 already forks it**

The execution site issue #37 cites is real: `golang/client.go:1172` ("Execute all
tool_use blocks in this turn concurrently"), and the streaming twin continues while
`stopReason == "tool_use"` (`client.go:1763`).

But the loop **already has a no-execute-and-return path** — the §10 suspension layer,
which post-dates the issue's reading of the code:

- A tool returns `Pending(Request{...})` (`types.go:80-100`) instead of a result.
- `PendingOf` detects it (`types.go:109`) and `resolvePending` (`client.go:1012`)
  branches:
  - **no `WaitFor` configured** → `client.go:1013-1016` returns the suspension
    upward; the run halts and `pendingRun` returns `RunResult{Status:"pending",
    Pending:*Request}` (`client.go:1242-1244`, fields at `client.go:277-282`).
  - **`WaitFor` configured** → `client.go:1017-1025` calls the host, then
    **re-executes the same tool with the `Answer`** (`executeWithAnswer`), and that
    result becomes the `tool_result` block fed to the model.

That second bullet **is relay**, structurally. A declaration-only tool is a tool
whose `Execute` returns `Pending`, and whose retry-with-answer path returns
`ToolResult{Output: answer.Data["output"]}`. The model's call surfaces to the host;
nothing executes proxy-side; the host's result reaches the model as a proper
`tool_result` block. No new loop mode needed **for the in-process host shape**.

### Q2 — Does `ConversationStore` round-trip `tool_use`/`tool_result` structurally? · **YES — clean**

This was routsi ADR-010's open question ("does it need upstream work?"). It does not.

- `ConversationStore` stores `[]any` of raw provider message maps
  (`client.go:1917-1922`).
- The loop appends the assistant turn with **native block structure** —
  `{"type":"tool_use","id","name","input"}` (`client.go:1153-1159`) — and the tool
  results as `{"type":"tool_result","tool_use_id","content","is_error"}`
  (`client.go:1217-1222`).
- `Ask` persists `res.Messages` verbatim (`client.go:648`).

So multi-turn tool use survives the transcript today, with ids intact. **routsi
ADR-010 item 4 needs no upstream change.**

### Q3 — Can a *durable* (stateless-HTTP) host resume a relayed call? · **NO — fork 1**

routsi is a proxy: it cannot hold a `WaitFor` closure across an HTTP boundary, so it
is a **durable** host (`SPEC.md:1370-1372` — "omit `waitFor`, take the
`status:"pending"` result, … later call `run` again, possibly in another process").

On that path the loop **writes a placeholder error result into the transcript before
returning**: `resolvePending` yields `ToolResult{Output: request.Prompt, IsError:
true}` (`client.go:1015`), which is assembled into the `tool_result` block
(`client.go:1217-1222`) and appended (`client.go:1242`). This is deliberate and
specified — `SPEC.md:1425-1431`, the "halted-tool transcript rule", verified across
all six ports on 2026-07-18.

For auth/approval that is correct: resume re-invokes the tool via
retry-with-answer, so the placeholder is never replayed as truth. **For relay it is
wrong**: the caller's real `tool_result` has nowhere to go — the slot for that
`tool_use_id` is already filled with `is_error:true, content:"<prompt>"`, and there
is **no entry point that injects an `Answer` into a persisted run**. `Run`/`Ask` take
a prompt, not an answer.

⇒ Durable relay needs either (a) an answer-carrying resume entry point, or (b) a
relay-specific transcript rule that leaves the `tool_result` slot empty on halt. Both
touch §10's shipped, six-port-verified contract.

### Q4 — Do parallel tool calls survive relay? · **NO — fork 2**

OpenAI function calling relays **every** `tool_call` in a turn. §10 deliberately
surfaces only the **first in tool-call order** and drops the rest, which re-suspend
on resume — `client.go:1231-1241` breaks the `blocks` loop at the first halt;
specified at `SPEC.md:1395-1399`; tested as intended behavior at
`golang/pending_test.go:453` and `:512` (non-streaming + streaming).

Consequence for a relay caller: a turn where the model emits three tool calls
surfaces one. A conforming OpenAI client sees a truncated `tool_calls` array.

**Secondary observation (not a relay-only issue).** On that durable-halt path the
assistant message retains **all N** `tool_use` blocks while the following user
message carries only the first `tool_result`. Anthropic requires a `tool_result` for
every `tool_use` in the preceding turn, so that saved transcript is not directly
replayable to the provider. It is unreachable via the intended in-process resume
(retry-with-answer never replays it) and only bites a durable host that replays
history — i.e. exactly routsi's shape. Filed here as an observation for ADR-0010,
**not fixed inline**.

## The two forks

Both are contract-level and affect all six ports, so they are owner decisions, not
implementer's discretion:

- **F1 — durable resume shape.** Add an answer-carrying resume entry point
  (`RunWithAnswer`/`Ask(..., answer)`), or make the relay halt leave the
  `tool_result` slot unfilled? The first adds API surface but leaves §10's transcript
  rule untouched; the second changes a rule verified byte-identical across six ports.
- **F2 — parallel relay.** Keep §10's first-in-order halt and carry **all** of the
  turn's relay calls inside one `Request` (e.g. `Data["calls"]`), or relax the
  halt rule for relay tools so N suspensions surface as N requests? The first
  preserves the shipped rule exactly and matches OpenAI's array shape; the second
  is conceptually cleaner but re-opens the hardened concurrency contract.

## What the spike removes from the estimate

- The declaration translation is already written: `ToOpenAI`/`ToAnthropic`/`ToGemini`
  (`golang/adapters.go:41,57,70`).
- Memory round-trip needs no work (Q2).
- `Builtins:false` already separates builtins from client-declared tools
  (`builtin.go` `CreateBuiltinTools`), so the proxy's security posture is unchanged.
- No second agent loop is needed — relay is a use of §10 (Q1).

## Success criteria (unchanged, still to run live)

A model's tool call reaches the caller as structured `id`/`name`/`input` with zero
proxy-side execution, and a synthetic `tool_result` fed back continues the
conversation correctly — including a turn with two parallel calls, and across a
process boundary.

## Method note

Source-verified only; no live LLM round trip was run (this repo's suites are
hermetic by design). Every claim above cites `file:line` on branch `cljc` and was
read, not inferred. The two forks are stated as forks precisely because the source
does not decide them.
