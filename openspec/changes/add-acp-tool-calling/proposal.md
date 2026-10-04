# ACP tool calling: the agent becomes a real tool-calling model

## Why

`add-acp-model-source` (shipped 0.19.0) made an ACP agent the `generate` behind an in-process
client, and claimed the tool-calling loop, MCP tools and skills "work through it unchanged". They
do not. Every port's ACP `generate` flattens `messages` to `role: text` lines, **ignores
`request.tools`**, and only ever returns `content`. The agent is never told which tools exist, so
it either answers from its own built-in tools (which run on the host, outside every toolnexus
hook, outside any sandbox) or makes the answer up. A loop whose model cannot ask for a tool is not
a tool-calling loop.

What the host wants is the in-process contract, honoured: the ACP agent receives the same
OpenAI-shaped request an HTTP model would — the conversation including earlier tool calls and
their results, plus the tool schemas — and replies with an OpenAI-shaped assistant message:
either `content` or `tool_calls`. toolnexus then executes the calls itself, through the toolkit,
so hooks, metrics, suspension, and (later) the builtin execution seam all apply.

That last point is the one that matters for a sandboxing host (`docs/adr/0033-the-builtin-execution-seam.md`,
the wfnexus case): an agent CLI that runs `bash` on its own has already escaped any seam
toolnexus might put around its builtins. So the agent must not execute tools of its own.

## What changes

- **Prompt.** Each turn sends one `session/prompt` whose text is a fixed, byte-pinned
  instruction preamble, then a `REQUEST:` JSON object `{"messages": [...], "tools": [...]}` (the
  assembled OpenAI-shaped request, unmodified), then the existing supersedes marker line.
- **Reply.** The accumulated `agent_message_chunk` text is parsed by one pinned algorithm into
  either `toolCalls` or `content`. It tolerates markdown fences, prose around the JSON, a full
  `choices[0].message` envelope, and `arguments` as either a JSON string or an object. A reply
  that is not a tool-calling envelope — plain text, or a JSON object the host asked for as
  structured output — passes through untouched as `content`.
- **Agent-native tools are refused by default.** `session/request_permission` is answered with
  the first `reject`-kind option (or `cancelled`), still immediately — never awaited. A new
  `allowAgentTools` option restores the 0.19.0 behaviour (first `allow`-kind option). This
  supersedes the "answered with the first permitting option" scenario of `add-acp-model-source`.
- **Local only.** The agent is a child process on the host. Remote agents, ACP `authenticate`
  flows (browser / terminal login) and exposing them through a host endpoint are out of scope and
  are a later change; an agent that needs login must be authenticated locally first.

Lands in all seven ports. `SPEC.md §8 In-process models` gains an **ACP** subsection that pins the
preamble bytes and the reply-parsing algorithm, because those are what make the ports
substitutable.

## Not in scope

- ACP `authenticate` / `auth/login`, remote transports, endpoint pass-through of login (later).
- Passing the toolkit to the agent as an MCP server (`session/new.mcpServers`) — the opposite
  design, in which the agent runs the loop; rejected here because tools would then execute
  outside the toolnexus loop's hooks and resilience.
- Delta mode (send only the new turn) — unchanged from ADR 0031: not offered.
- Streaming — the in-process path still refuses streaming.
