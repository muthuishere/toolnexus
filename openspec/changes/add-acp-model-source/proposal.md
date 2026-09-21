# ACP: connect to a running agent the way `loadMcp` connects to a server

## Why

MCP is how toolnexus consumes *tools*. ACP (Agent Client Protocol) is the equivalent for
consuming a whole *agent*: JSON-RPC 2.0, one object per line, over a child process's
stdin/stdout. `devin acp`, Gemini CLI and Zed's agents already speak it, so one client
reaches all of them (issue #96).

The argument is a measurement, not an aesthetic. Against `devin` on macOS:

| call | latency |
|---|---|
| `devin -p`, 17-byte prompt | 15.3s |
| `devin -p`, 12 KB prompt | 14.3s |
| ACP `session/prompt` #1 | 17.2s |
| ACP `session/prompt` #3 | **1.6s** |

**Prompt size is free** — 12 KB costs the same as 17 bytes — so the entire per-turn cost is
process startup, and a warm session amortises it away. A host driving a one-shot agent CLI
pays that startup on *every* turn of the tool-calling loop. Over a real 6-turn loop the
reporter measured ~15s/turn falling to ~7.4s/turn.

**Stated honestly, because a spike checked:** against a fake ACP server with no startup cost
the warm-session gain is ~6x on a ~5ms call — pure spawn overhead. The 15s→1.6s figure is
`devin`'s real startup cost, not a protocol-level speedup. The feature is worth shipping for
the agents people actually run; it is not a general performance claim.

## The design decision

toolnexus assembles a **complete** request every turn. An ACP session is **stateful** — it
already holds the transcript. Sending the whole thing each turn makes the session accumulate
near-duplicate histories, and the reporter observed the agent *answering a stale copy*. A
spike reproduced that deterministically against a fake server over real pipes.

The alternative — sending only the delta — is faster and makes the client own conversation
state, i.e. a shadow transcript that can desynchronise from `ConversationStore`.

**Decision: full request every turn, with an explicit supersedes marker, built into the
default generate's prompt assembly rather than left to the caller.** Stateless-by-default
matches every other toolnexus model source, and a drifting shadow transcript is a worse
failure than a wasted token. Delta mode may be added later as an opt-in.

## What a spike found (`spikes/acp/SPIKE.md`)

Four traps, each reproduced, each of which costs the next implementer a day:

- **An unanswered `session/request_permission` hangs the turn forever** — even in bypass
  mode. Answering with the first `allow`-kind option completes it in under a millisecond.
- **`agent_thought_chunk` and tool narration must be dropped.** Accumulated, they wrap prose
  around structured output and break JSON parsing outright. Only `agent_message_chunk` is
  the reply.
- Responses and `session/update` notifications interleave, so the client demultiplexes by
  JSON-RPC id.
- One ACP session is one conversation: concurrent prompts interleave into a single
  transcript, so turns serialise and each lane gets its own session.

From the one live `devin` call: **real `devin acp` requires an absolute `cwd` and an
`mcpServers` array on `session/new`**, or it rejects with `-32602`. No fake produces that.

## What changes

ACP ships as a **model source** — a generate function handed to the in-process client — so
the loop, skills, MCP, adapters and sub-agents are untouched. It is deliberately the
smallest framing: not a new tool source, not a new client, no new conformance surface in
`SPEC.md §0`.

Per the prime directive it lands in **all seven ports**, not Go alone. Portability rests on
each port spawning a child process and speaking newline-delimited JSON-RPC over its pipes
without a new third-party dependency; `spikes/portability/` establishes that per port,
including both Clojure hosts.
