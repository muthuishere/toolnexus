# ACP: send what changed, not everything, on every turn

**Status: proposed — spec only. No port implements this yet.** The tool-calling half
(`add-acp-tool-calling`) is implemented in all seven ports; this change is its follow-up and is
written ahead of the code on purpose, so implementation can be driven from a machine with real ACP
agents installed.

## Why

`add-acp-tool-calling` sends, on **every** turn, the instruction preamble, the system prompt, every
tool schema and the whole conversation — into an ACP session that **keeps every prompt it has ever
received**. The agent's context therefore grows quadratically with the number of turns. For a
focused agent of ~50 tools (≈7.5k tokens of schemas) plus a ≈1k-token system prompt, a 10-turn tool
loop with ≈1k tokens of new material per turn puts roughly:

| | tokens into the session over the run |
|---|---|
| today — everything, every turn | ≈ 140k (10 × 8.5k fixed + 1k+2k+…+10k history) |
| this change — fixed part once, then only what is new | ≈ 18k (8.5k + 10 × 1k) |

(Estimates for illustration, not measurements.) That is the user's subscription quota and the
agent's context window, both spent on repeats.

The parts that repeat are exactly the parts that never change within a conversation: the tool set
is generic and fixed for the agent, and the system prompt is fixed. Only the conversation grows.
ADR 0031 chose "full request every turn" because sending only the delta means the client must know
what the session already holds — a shadow transcript that could drift from `ConversationStore`.
This change removes that risk instead of living with it: the client keeps only a fingerprint of
what it sent, and **any** mismatch opens a fresh session rather than guessing.

## What changes

1. **Opening prompt** (the first prompt of a session): preamble + system + tools + messages so far
   — today's format, minus the supersedes marker.
2. **Continuation prompt** (every later prompt in the same session): a short pinned preamble plus
   only the messages appended since the last prompt, excluding the agent's own reply (it already
   has it).
3. **Fingerprint check.** If the next request is not exactly "the conversation already sent, plus
   our last reply, plus new messages", with identical tools, the client opens a fresh session
   (`session/new` on the same warm process) and sends an opening prompt. Triggers: tools changed,
   system prompt changed, history compacted or edited, a different conversation, a retry of the
   same request, or any error on the previous turn.
4. **The supersedes marker is removed.** It existed only because full re-sends piled near-duplicate
   copies into one session; continuation prompts never duplicate, and a fresh session has no stale
   copy to answer from.
5. **`config` — a pass-through, not an abstraction.** An ordered map of ACP session config option
   ids to values (e.g. `{ model: "openai/gpt-5", thought_level: "high" }`), applied with
   `session/set_config_option` after every `session/new`, after `mode`. toolnexus does not know or
   validate ids — the agent advertises them (`configOptions` in its `session/new` response, exposed
   raw on the client) and rejects bad ones; that rejection is surfaced as-is.
6. **No per-agent adapters.** The host supplies the command, arguments and any bypass flags. The
   library ships **examples** for three agents only — opencode (the documented default), codex and
   devin — and nothing in the library is specific to any of them.

## Not in scope

- MCP passthrough of the toolkit to the agent. Deliberate, and now a requirement: MCP servers and
  skills stay toolnexus tools executed by our loop (`mcpServers: []`), so hooks, approvals and
  metrics keep applying — handing the agent our MCP config would cede that control.
- ACP `authenticate`, remote agents, streaming — unchanged from `add-acp-tool-calling`.
- Closing abandoned sessions: ACP v1 has no stable session-close; a reset abandons the old session
  inside the still-running agent process. A host that resets constantly can `close` and reload.
