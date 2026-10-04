# Design — ACP tool calling

## The prompt (byte-pinned preamble, parse-pinned JSON)

One `session/prompt` per turn, one text block, assembled as:

```
<PREAMBLE>
REQUEST:
<JSON>

SUPERSEDES-ALL-PRIOR: <latest user text>
```

`<PREAMBLE>` is these exact lines, each ending in `\n` (no trailing spaces), followed by a blank
line before `REQUEST:`:

```
You are the language model behind a tool-calling client. The client executes tools; you never do.
Do not run commands, read or edit files, or use any tool of your own.
The REQUEST below is the complete conversation in OpenAI chat-completions format: "messages" holds every message so far, including earlier tool calls and their results; "tools" lists the only tools you may call.
Reply with exactly one JSON object and nothing else: no prose, no markdown fences.
To give the final answer: {"content": "<answer>"}
To call tools: {"tool_calls": [{"id": "<unique id>", "type": "function", "function": {"name": "<tool name>", "arguments": "<JSON-encoded arguments>"}}]}
Never both. Use tool results already in "messages" instead of calling the same tool again.
```

`<JSON>` is one JSON object with exactly two keys, `messages` then `tools`: the assembled request's
`messages` and `tools` arrays unmodified (`[]` when absent). Its *bytes* are not pinned — key order
inside messages and escaping differ per JSON library — only that it parses to those two arrays. It
is written compact (no indentation) and must not escape `<`, `>`, `&` as `<` etc. where the
library makes that optional (Go: `SetEscapeHTML(false)`), so a reader sees the text it was given.

`<latest user text>`: the content of the last message whose role is `user`; a string is used as
is, an array of parts is the `text` of its `type: "text"` parts joined with a single space. With no
user message, the same rendering of the last message's content; with no messages, empty.

Why keep the supersedes line when the preamble already says it: the session is still stateful, and
every port's existing tests and fake servers key off that marker (ADR 0031). It costs one line.

## The reply parser (`parseAcpReply(text) -> {content} | {toolCalls}`)

Pinned, in order:

1. `s` = `text` trimmed of whitespace. If `s` starts with ```` ``` ````, drop its first line; if
   what remains ends with ```` ``` ````, drop that; trim again.
2. Parse `s` as JSON. If that fails or is not an object, and `s` contains `{` with a `}` after it,
   parse the substring from the first `{` to the last `}`. If no object results → **content =
   `text`** (the original, untouched).
3. Unwrap: if the object has a non-empty `choices` array whose first element has an object
   `message`, use that message; else if it has an object `message`, use that.
4. If it has a `tool_calls` array: for each element that is an object, let `fn` = its `function`
   if that is an object, else the element itself. Skip it unless `fn.name` is a non-empty string.
   `arguments` = `fn.arguments`: absent or null → `{}`; a string → passed through as pre-encoded;
   anything else → passed as a structured value (the in-process layer encodes it). `id` = the
   element's `id` if a non-empty string, else omitted (the in-process layer assigns `call_<i>`).
   If at least one call survives → **toolCalls**.
5. Else if it has a `content` key: a string → **content** = it; null → `""`; anything else → its
   compact JSON encoding.
6. Else → **content = `text`** (the original): the object is not a tool-calling envelope — most
   often structured output the host asked for — so it passes through untouched.

Unknown tool names are not filtered here: the loop already answers an unknown tool with an error
tool result the model can react to, and doing it twice would hide which layer rejected it.

## Permissions

`session/request_permission` is still answered inline from the read loop, never awaited (an
unanswered request hangs the turn forever — ADR 0031). Default: the first option whose `kind`
starts with `reject`; none → `{"outcome": {"outcome": "cancelled"}}`. With `allowAgentTools` true:
the first option whose `kind` starts with `allow`, else cancelled (the 0.19.0 behaviour).

The prompt *asks* the agent not to use its own tools; the permission default *enforces* it for
every tool the agent gates behind a permission. Tools an agent runs without asking (reads, in some
agents) cannot be refused by an ACP client, which is why the builtin execution seam (ADR 0033) and
an agent's own read-only/plan mode (`mode` option) remain the actual containment.

Option spelling per port: Go `AllowAgentTools`, JS `allowAgentTools`, Python `allow_agent_tools`,
Java/C# `allowAgentTools`/`AllowAgentTools`, Elixir `:allow_agent_tools`, Clojure
`:allow-agent-tools`.

## Alternatives rejected

- **Toolkit as an MCP server in `session/new.mcpServers`.** The agent would run the loop and call
  tools itself. Tools would then execute outside the toolnexus loop: no `beforeTool` hooks, no
  suspension, no retries, no per-turn metrics, and the run's result would be one opaque reply.
  Possibly worth offering later as a *separate* mode; not the model-source contract.
- **Prompt-only, no permission change.** Leaves an agent free to `bash` on the host with the
  host's own consent — exactly the escape ADR 0033 documents.
