# Design: CLI client styles

Source of truth for the reasoning: `docs/adr/0037-cli-client-styles.md` and
`spikes/cli-client/SPIKE.md`. This file records the library decisions taken on top of the spike.

## D1 — One constructor, transport decided on the first call

`createCliClient` validates everything it can at construction (command, style, argv template,
model rules). The transport is decided on the first model call, because opening an ACP session
needs a context and spawns a process:

- `transport: Auto` (default): ACP when an ACP launch exists; if the agent fails to **start** or to
  answer **initialize**, fall back to the one-shot CLI and record the reason
  (`fallbackReason()`). A failure after initialize (session/new, set model, a prompt) is an error,
  never a silent fallback.
- `transport: ACP` never falls back; `transport: CLI` never tries ACP.
- `transport()` reports `auto` until decided, then `acp` or `cli`.

The transport selector is an enum (`CliTransports.Auto | ACP | CLI`) for the same reason the style is.

## D2 — The ACP launch

`[command] + style.acp.args`, or `acpCommand` verbatim when given (adapters, e.g. claude has no ACP
mode of its own: `npx -y @agentclientprotocol/claude-agent-acp@0.100.0`). `codex` has no ACP launch
(its adapter cannot reach current account models), so it is always one-shot.

## D3 — Serving the toolkit (spike follow-ups a, b)

The client serves `options.toolkit` itself — not through `Toolkit.Serve` — on
`127.0.0.1:<ephemeral>/mcp`, reusing the §7C MCP profile's tool mapping, so it can require
`Authorization: Bearer <token>` on every request (401 otherwise). The token is 32 random bytes,
hex, new per session, carried in: the http entry's `headers`; the stdio bridge's `env`
(`TOOLNEXUS_MCP_TOKEN`); the project MCP file's `headers`. It is never logged, and diagnostic views
redact it. `Toolkit.Serve` and §7C are unchanged.

The toolkit's `skill` tool is served as `load_skill` (`CLI_SERVED_SKILL_TOOL_NAME`), with the
skills catalog (the §3 prompt) appended to its description, because the agent's own system prompt
does not carry our catalog. Every other tool keeps its name.

## D4 — Which `mcpServers` shape

`session/new` gets one entry named `toolnexus`:

| agent | entry |
|---|---|
| advertises `mcpCapabilities.http` | `{"type":"http","name","url","headers":[{name,value}]}` |
| no http, a bridge is available | `{"name","command","args":[…,url],"env":[{name,value}]}` (no `type`) |
| style has `acp.projectMcpFile` (devin) | **no** session entry; a fresh temp cwd holding the file `{"mcpServers":{"toolnexus":{"type":"http","url","headers":{…}}}}`, mode 0600, removed on close |

The bridge is `bridgeCommand` or, by default, `toolnexus mcp-bridge` when the toolnexus CLI is on
PATH. With neither, the http shape is sent anyway (the agent may still accept it).

## D5 — Permissions by toolCallId (spike follow-up c)

Each `session/request_permission` is decided once per `toolCallId` and the decision is cached:
a later request for the same id cannot flip it, and a request with no id is rejected. The identity
is the request's own title, else the first non-empty title the agent sent for that id in a
`tool_call`/`tool_call_update` (devin sends empty permission titles). It is ours only when it is
exactly one of the spellings agents use for a tool of our server — `<tool>`,
`mcp__toolnexus__<tool>`, `toolnexus/<tool>`, `<tool> (toolnexus MCP Server)`,
`Calling <tool> from toolnexus`, `<tool>(…)` — for a tool we serve. No substring matching.
Allowed ⇒ the `allow_once` option (else the first `allow*`); otherwise a `reject*` option.

Residual risk (stated, not hidden): an agent that titles its *own* tool with the bare name of a
served tool would be allowed. Renaming `skill` removes the one collision seen live.

## D6 — Hooks and observability in ACP mode

The agent executes tools, so `BeforeTool`/`AfterTool` run at the server boundary (short-circuit,
rewrite and replace all honoured; `id` and `turn` are empty/0). The client loop still runs
`BeforeLLM`/`AfterLLM` per prompt. `usage` is zero.

## D7 — The ACP model source changes (follow-up d)

`ACPOptions.MCPServers` (entries may carry both URL and command; the agent's capability picks),
`ClientCapsMeta`, `Model` (`session/set_config_option {configId:"model"}`, then
`session/set_model`; both failing is an error), `Stderr`. `clientCapabilities.terminal: false`.
The agent runs in its own process group (an `npx` launch is a tree). A pending request returns
`agent exited before replying to <method>` as soon as the agent's stdout closes instead of
waiting out a timeout — this is what makes Auto's fallback fast.

## Open (owner)

- Devin hides MCP tools behind a lookup tool; live 0/3 unless the prompt names the `toolnexus`
  server, 3/3 when it does. Should the client add that hint to the system prompt automatically for
  styles that need it (a style flag), or leave it documented?
- `gemini` one-shot args and all of `goose` remain UNVERIFIED (not installed / not live-run here).
