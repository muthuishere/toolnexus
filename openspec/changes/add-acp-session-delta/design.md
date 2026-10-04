# Design — ACP session delta

This pins the text that moves into `SPEC.md §8 "ACP model source"` when the code lands (task 1).
It is written here first, not in `SPEC.md`, so the merged `SPEC.md` keeps describing what the seven
ports actually do until they do this.

## Client state

Per ACP client (one warm process, one current session):

- `sessionId` — current session, or none.
- `sentTools` — the `tools` array of the last opening prompt.
- `sentMessages` — every request message the current session has been given (opening messages,
  then each continuation's new messages), in order.
- `lastReply` — the assistant message the last `generate` returned (content, or tool calls), or none.

All of it is discarded — so the next turn opens a fresh session — when a turn fails for any reason
(agent error, timeout, unparseable transport), and on `close`.

## Deciding opening vs continuation

Given request `R` (`messages`, `tools`):

1. No current session, or `lastReply` is none → **open**.
2. `R.tools` is not structurally equal to `sentTools` → **open**.
3. `R.messages` does not begin with `sentMessages` (element-wise structural equality) → **open**.
4. Let `rest` = `R.messages` after that prefix. Its first element must be an `assistant` message
   **equivalent** to `lastReply`; otherwise → **open**. Equivalent means: for a content reply, equal
   `content`; for a tool-call reply, the same tool calls in order by `name` and by structurally
   equal **decoded** `arguments` (ids are ignored — the in-process layer assigns `call_<i>` when the
   agent gave none).
5. Let `new` = `rest` without that first element. If `new` is empty → **open** (a retry of the same
   request must not be answered from a session that already saw it).
6. Otherwise → **continue** with `new`; append `rest` (the reply and `new`) to `sentMessages`.

"Structurally equal" is JSON-value equality (object key order irrelevant). It never crosses the
wire, so how a port computes it — deep compare or a hash of a canonical encoding — is the port's
choice and not a parity surface.

## Opening a session

`session/new` (absolute `cwd`, `mcpServers: []`) → `session/set_mode` if `mode` is set → one
`session/set_config_option` per `config` entry, in the host's order (a boolean value adds
`"type": "boolean"`) → the opening prompt. A failure in any step fails that `generate` with the
agent's error, unwrapped. The first session is opened by `loadAcp` itself, so a bad `config` id
fails at load, before any turn. The latest `session/new` result's `configOptions` (raw, possibly
absent) is readable on the client so a host can discover valid ids.

## Prompt texts (byte-pinned preambles)

**Opening:** `PREAMBLE + "\nREQUEST:\n" + JSON{messages, tools}` — `PREAMBLE` exactly as pinned by
`add-acp-tool-calling`, the JSON rules unchanged. **No supersedes line** (removed).

**Continuation:** `CONTINUATION + "\nNEW MESSAGES:\n" + JSON{messages: new}` where `CONTINUATION`
is these three lines, each terminated by `\n`:

```
Continue the same conversation. NEW MESSAGES below are appended to it in the same OpenAI chat-completions format; the system prompt and tools are unchanged.
Reply with exactly one JSON object and nothing else: no prose, no markdown fences.
{"content": "<answer>"} for the final answer, or {"tool_calls": [...]} in the format given at the start, never both.
```

The reply parser is unchanged.

## What a host sees

Nothing changes in the API beyond `config` and the readable `configOptions`. A host whose
`beforeLLM` hook rewrites the system prompt or the tool list every turn (e.g. a timestamp in the
system prompt, or tool-relevance trimming) gets a fresh session every turn — correct, and no worse
than today, but none of the saving. The cookbook must say so.

## Examples, not adapters

Each port's ACP example selects the agent from `TOOLNEXUS_ACP_CMD`, defaulting to `opencode acp`,
and documents two alternatives verbatim: codex (`npx -y @zed-industries/codex-acp`) and devin
(`devin acp`). Model and other options are shown as `config`, with a comment that ids come from the
agent's `configOptions`. Exact commands and package names must be checked against the installed
agents when the examples are run (task list), not trusted from this document.

## Reversal of ADR 0031's state decision

ADR 0031 rejected delta mode because a client-owned transcript can desynchronise from
`ConversationStore`. Here the client owns no transcript the host relies on — only a record used to
*verify* that the host's next request extends what the session already holds. A mismatch can cost
a fresh session; it cannot produce an answer to the wrong conversation. ADR 0036 records this.

## Alternatives rejected

- **Keep full re-sends, add `session/new` per `run()`.** Fixes cross-run staleness, not the
  per-turn quadratic growth inside one run — the case that matters for a tool loop.
- **Send tools once, but always resend the full conversation.** Still quadratic in history.
- **A per-agent `model` option.** Agents spell model selection differently; a pass-through of ACP's
  own config options covers every agent that implements the spec without the library learning any.
