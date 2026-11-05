# toolnexus (Elixir)

Your LLM, with MCP tools and agent skills built in — in 3 lines, on the BEAM.

`toolnexus` unifies every tool source an agent needs — **MCP servers** (stdio + streamable-HTTP),
**agent skills** (`SKILL.md` folders), your own functions, HTTP endpoints, ten built-in shell/file
tools, and remote **A2A agents** — behind one uniform `Tool`, emits the schema in **OpenAI /
Anthropic / Gemini** formats, and ships a unified client with a built-in tool-calling loop.

The Elixir port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, Java, C#, Clojure and Rust**. The MCP client is
implemented in-house on OTP (supervised connections, no third-party MCP SDK), which is also why
this port ships the **full** elicitation bridge (form *and* URL mode).

## Install

```elixir
def deps do
  [{:toolnexus, "~> 0.24"}]
end
```

## Zero to agent

```elixir
{:ok, toolkit} = Toolnexus.create_toolkit(mcp_config: "mcp.json", skills_dir: ["skills"])
client = Toolnexus.Client.create(base_url: System.get_env("OPENAI_BASE_URL"),
                                 style: "openai", model: "gpt-4.1",
                                 api_key: System.get_env("OPENAI_API_KEY"))
result = Toolnexus.Client.run(client, "What tools do you have? Use one.", toolkit)
IO.puts(result.text)
```

- `mcp.json` is the standard Claude-desktop-style config (`mcpServers` / `servers` / `mcp` keys all accepted).
- `skills/` is a folder of `**/SKILL.md` files, loaded on demand through one `skill` tool.
- Remote MCP `headers` values expand `${ENV_VAR}` at call time and are never logged.

## API key by host (BREAKING in 0.22.0)

With no `api_key`, the client reads only `api_key_env` (the **name** of an env var) or the env
var that belongs to the `base_url` host, over https: `api.openai.com` → `OPENAI_API_KEY`,
`api.anthropic.com` → `ANTHROPIC_API_KEY`, `openrouter.ai` → `OPENROUTER_API_KEY`. There is no
cross-provider fallback any more, so **a local or custom `base_url` must set `api_key` or
`api_key_env`**, or the run raises before any request:

```elixir
Toolnexus.Client.create(base_url: "http://localhost:11434/v1", model: "llama3", api_key: "unused")
Toolnexus.Client.create(base_url: "https://gateway.example/v1", model: "m", api_key_env: "GATEWAY_KEY")
```

## Prompt cache and tool tiers

```elixir
client =
  Toolnexus.Client.create(
    base_url: "https://api.anthropic.com", style: "anthropic", model: "claude-sonnet-4-5",
    prompt_cache: %{long_ttl: false},                    # cache breakpoints (anthropic) / prompt_cache_key (openai)
    tool_tiers: %{core: ["read", "grep"], deferred: []}  # other tools load via tool_search / call_tool
  )

result = Toolnexus.Client.run(client, "fix the failing test", toolkit)
result.usage[:cache_read_tokens]           # present when the provider reports cache hits
Toolnexus.Client.tool_tier_plan(client, toolkit)   # %{"read" => :core, "bash" => :listed, ...}
Toolnexus.Client.reset_tool_tiers(client, toolkit) # rebuild the frozen plan at a session start
```

The tools array never changes mid-session, so the cached prefix holds even when tools are added
later. Runnable: `mix run examples/prompt_cache_tiers.exs` (hermetic, no key needed).

## Host additions (0.22.0)

- `Toolnexus.Mcp.load(cfg, on_server: fn %{name, status, tools, error} -> ... end)` — use each
  MCP server as soon as it is ready; `Toolnexus.Mcp.resource_tools/1` lists and reads MCP resources.
- `wait_for_ctx: fn ctx, request -> answer end` — `ctx.signal` is the turn's pid, so a pending
  question ends with the turn.
- Tool `hints` (`%Toolnexus.ToolHints{read_only, destructive, open_world}`) on builtins, MCP
  annotations and every `before_tool` event — a permission gate is a `before_tool` hook.
- Opt-in builtins: `background: Toolnexus.Builtin.BackgroundShells.new()` (`bash_output`,
  `kill_shell`), `web_search: fn query, limit -> ... end`, `notebook_edit: true`; `grep`
  `context` / `before` / `after`; truncation in `read` / `grep` metadata.
- Compactor `on_compact` (counts, summary, `folded` messages); `Toolnexus.Agents.as_tool_with/2`
  and runtime `:wait_for`; `ctx.call_id` on every tool call; `Toolnexus.Client.list_models/1`.

## Simple judgments

`Toolnexus.Judge` is a thin layer over any `Toolnexus.Classifier` (SPEC §8B); the wire is unchanged.

```elixir
import Toolnexus.Judge

st = state("You are Donkey Kong, you want to win.", %{message_received: "jump off the stage now"})

{:ok, answers} =
  ask(classifier, st, [
    noul(:is_appropriate, "Does message_received contain harmful language or topics?"),
    noul(:does_this_help, "Does message_received help Donkey Kong win?")
  ])

answers["is_appropriate"].band            #=> :yes | :no | :uncertain (cut-points 0.30 / 0.70, exclusive)
Toolnexus.Judge.Answer.value(answers["does_this_help"])

{:ok, outcome} =
  gate(classifier, st, questions, [%{question: "fixable", below: 0.3, action: "fail"}])
# outcome.escalated => a §10 `input` Request instead of a guess
```

The role lives in the state, and each question names the state field it judges. Also: `Policy`
(`default`, `skip_uncertain`), `Tape` (record / replay by call name), `Judge.static/4` (one-line
static classifier), and `Classifier.evaluate_batch/4` (many states, state order, fail-closed, 16 in flight).

Batteries (SPEC §8B): `Toolnexus.Judge.ToolGuard`, `ToolRelevance`, `SkillRelevance`,
`ToolResultFilter`, `IsComplete`, `AgentRouter`, `ContentGuard`, `ModelRouter` — each a typed
verdict from `check`/`select`/`filter`/`pick`, and `as_hook(battery, next)` where a hook seam
exists. A `before_llm` hook may also return `%{model: "id"}` to override the model for that turn.

## Why the BEAM port

Long-running agents want supervision. Every MCP connection is a supervised process; a crashed
stdio server is isolated (status `"failed"`) without taking your toolkit down; parallel tool calls
ride `Task.async_stream`. Same contract as the other six ports, native OTP underneath.

## Media generation

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```elixir
alias Toolnexus.Media

{:ok, style} = Media.load_style("examples/media-styles/a2e.json")
media = Media.new(style, on_progress: &IO.inspect/1)
{:ok, img} = Media.image(media, %{prompt: "a red fox in fresh snow", width: 1024, height: 768})
{:ok, vid} = Media.video(media, %{prompt: "the fox blinks", image: hd(img.files).url, seconds: 5})

{:ok, tk} = Toolnexus.create_toolkit(media: media)    # generate_image, …
res = Toolnexus.Client.run(client, "Draw a fox, then animate it.", tk)
for f <- res.files, do: IO.puts("#{f.from} #{f.mime_type} #{f[:url]}")
```

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | **[Elixir](https://muthuishere.github.io/toolnexus/api/elixir/)** |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).

## CLI client — an installed agent CLI as the model (SPEC §8C)

```elixir
{:ok, cli} = Toolnexus.Cli.create_client(style: :copilot, command: "copilot", toolkit: tk)
result = Toolnexus.Cli.run(cli, "What is my badge number?", tk)
Toolnexus.Cli.transport(cli)   # "auto" until the first call, then "acp" or "cli"
Toolnexus.Cli.close(cli)
```

- `:style` is a closed atom set (`Toolnexus.Cli.styles/0`): `:claude :codex :copilot :opencode :devin :gemini :goose :custom`. Strings are refused. A user style comes from `Toolnexus.Cli.load_style/1` (`:style_def`).
- `:command` is **required**, and nothing is defaulted: `cli: Command is required (path or name of the CLI binary)`.
- `:transport` is `:auto` (the default), `:acp` or `:cli`. Auto uses ACP and falls back to the one-shot CLI only when the agent fails to start or to answer `initialize`; `fallback_reason/1` says why.
- **ACP**: the toolkit is served as MCP on `127.0.0.1:<ephemeral>/mcp` behind a per-session bearer token. `skill` is served as `load_skill`, and `:before_tool`/`:after_tool` hooks run at the server boundary. Permissions are decided per `toolCallId`: our tools are allowed and everything else is rejected.
- **One-shot**: a fixed preamble plus the OpenAI-shaped request. The reply is parsed tolerantly and tool calls run in the toolnexus loop. Streaming over ACP is incremental: text deltas, `thought` events and the agent's own `tool_call`/`tool_result` (tagged `via: "acp"`, reported, never run by the loop) arrive as the agent sends them. One-shot streaming is non-incremental: the loop's tool events, the whole answer as one text event, then done. Errors are pinned (`cli: exit <code>: …`, `cli: empty reply`, `cli: no output file`, `cli: timed out after <ms>ms`, `cli: command not found: <bin>`). The default call timeout is 300000 ms.
- `usage` is zero: the CLI owns the billing.
