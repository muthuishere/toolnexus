# ADR 0031 — ACP: the warm session is the feature, and it breaks the stateless request

- **Status:** **Accepted — REVISED 2026-09-22 after a spike, and SHIPPED in all seven
  ports.** The mechanism survived intact. **The headline number did not, and is corrected
  below: the 15s→1.6s figure is `devin`'s process-startup cost, not a protocol-level
  speedup.** A spike measured the same warm-session path against a fake server with no
  startup cost and found ~6x on a ~5ms call — real, and nothing like the table implies.
  The feature is still right for the agents people actually run; the justification is
  narrower than the draft claimed.
- **Date:** 2026-09-21
- **Driver:** issue #96. A working `devin acp` client exists in a consumer's tree today.
- **Related:** ADR 0030 (the seam it plugs into), ADR 0032 (the one-shot sibling).

## Context

ACP (Agent Client Protocol) is to *agents* what MCP is to *tools*: JSON-RPC 2.0,
one object per line, over a child process's stdin/stdout. `devin acp`, Gemini CLI and
Zed's agents already speak it, so one client reaches all of them.

The reporter's measurements, on macOS against `devin` (SWE-1.6 Slow), are the whole
argument:

| call | latency |
|---|---|
| `devin -p`, 17-byte prompt | 15.3s |
| `devin -p`, 12 KB prompt | 14.3s |
| ACP `session/prompt` #1 | 17.2s |
| ACP `session/prompt` #3 | **1.6s** |

Two facts fall out, and they are stronger than "ACP is nicer". **Prompt size is free**
(12 KB costs the same as 17 bytes), so the entire per-turn cost is process startup.
And a warm session amortises it away — ~15s/turn to ~7.4s/turn over a real 6-turn loop.

### The part that is actually hard

toolnexus assembles a **complete** request every turn: the full message array, every
time. An ACP session is **stateful**: it already has the transcript. Sending the whole
thing each turn makes the session accumulate near-duplicate histories, and the
reporter observed the agent *answering a stale copy*. Their mitigation is an explicit
"this supersedes everything earlier" line — a prompt-level hack holding a protocol-level
mismatch together.

The alternative is to send only the **delta**, which is faster still and makes the
client responsible for conversation state — i.e. a second, shadow copy of the thing
`ConversationStore` already owns, which can desynchronise from it.

**This is the decision. Everything else in an ACP client is mechanical:** `initialize`
/ `session/new` / `session/set_mode` / `session/prompt`; demultiplexing by JSON-RPC id
because `session/update` notifications interleave with responses; accumulating **only**
`agent_message_chunk` (thoughts and tool narration must be dropped or they wrap prose
around structured output); answering `session/request_permission` with the first
`allow`-kind option **or the turn hangs to timeout even in bypass mode**; process
lifetime independent of any one turn's context; idempotent close; one session per lane
because concurrent prompts interleave into one transcript.

## Correction to the previous draft

**The latency table measures `devin`, and only `devin`.** Against a fake ACP server over
real pipes (`spikes/acp/SPIKE.md`) the warm-session gain is ~6x on a ~5ms call — pure
process-spawn overhead. Nobody should cite this ADR as evidence that ACP is faster than
alternatives in general. What the table actually proves is narrower and still worth
shipping: **for a CLI whose startup dominates the turn, a warm session removes that cost
from every turn after the first.**

**The spike also found something no fake could produce.** One live call against the
installed `devin` revealed that real `devin acp` requires an **absolute `cwd`** and an
`mcpServers` array on `session/new`, or it rejects with `-32602`. The hermetic fake
accepted anything. This is the argument for taking the reporter's live-tested
implementation over a from-scratch one, and it is now pinned by a test in every port.

## Decision

Ship ACP as a **`Generate` source** — `LoadACP(...) -> acp.Generate`, handed to
`CreateInProcessClient` — so the loop, skills, MCP, adapters and sub-agents are
untouched. This is deliberately the *smallest* framing: ACP is a model source, not a
new tool source and not a new client.

On the state question, the proposed default is **full request every turn plus an
explicit supersedes marker**, with delta-mode as an opt-in — stateless-by-default
matches every other toolnexus model source, and a shadow transcript that can drift
from `ConversationStore` is a worse failure than a wasted token.

That default is exactly what the spike must try to break.

## Gate — how it resolved

1. **Held.** The stale answer reproduced deterministically — a stateful session answering
   an earlier near-duplicate — and the supersedes marker fixes it. Noted honestly in the
   spike: verified against one scripted staleness shape, not proof it generalises to every
   real agent.
2. **Held.** An unanswered `session/request_permission` hangs the turn *forever*, not until
   an error; answered with the first allow-kind option it completes in under a millisecond.
3. **Held.** Unfiltered thought and tool-narration chunks break JSON parsing outright.
4. **FALSIFIED as a general claim** — see the correction above. The number is `devin`'s
   startup, and the ADR now says so.
5. **Held.** No new `SPEC.md` §0 surface: ACP fits entirely inside the existing generate
   seam. That made it a Go-*shaped* change, which is **not** the same as a Go-only one —
   see below.

## The scope correction that mattered more than the gate

The spike concluded "Go-local, no SPEC §0 change", and inferred from that it could ship in
Go alone. **That inference was wrong and was overruled.** An untouched conformance contract
says nothing about the parity obligation: a capability in one port and not the other six is
drift by definition, which is the single bug this repo exists to prevent. A follow-up audit
(`spikes/portability/SPIKE.md`) found every port already ships newline-JSON-over-pipes
machinery for MCP local stdio (SPEC §2), so ACP needed **no new dependency anywhere**.

Shipped in all seven ports, each with hermetic tests over real pipes.

## Consequences

- Python's in-process seam is forced-synchronous (`client.py` rejects an awaitable). Solved
  with a background reader thread and a queue so `generate` stays synchronous — **the seam
  was widened nowhere**, and nothing async crosses it.
- Delta mode (send only the new turn) remains unshipped and opt-in if ever added. The
  shadow transcript it requires can desynchronise from `ConversationStore`, which is a
  worse failure than a wasted token.
- Change: `openspec/changes/add-acp-model-source/`.
