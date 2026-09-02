# toolnexus (Elixir)

Your LLM, with MCP tools and agent skills built in — in 3 lines, on the BEAM.

`toolnexus` unifies every tool source an agent needs — **MCP servers** (stdio +
streamable-HTTP), **agent skills** (`SKILL.md` folders), your own functions, HTTP
endpoints, ten built-in shell/file tools, and remote **A2A agents** — behind one
uniform `Tool`, emits the schema in **OpenAI / Anthropic / Gemini** formats, and
ships a unified client with a built-in tool-calling loop (hooks, parallel tool
calls, retries, conversation memory, suspension/resume, metrics).

It is the Elixir port of [toolnexus](https://github.com/muthuishere/toolnexus),
**byte-identical in behavior** with the JS, Python, Go, Java, and C# ports — same
config files, same outputs, same wire formats. The MCP client is implemented
in-house on OTP (supervised connections, no third-party MCP SDK), which is also
why this port ships the **full** elicitation bridge (form *and* URL mode).

## Install

```elixir
def deps do
  [{:toolnexus, "~> 0.17"}]
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

- `mcp.json` is the standard Claude-desktop-style config (`mcpServers` /
  `servers` / `mcp` top-level keys all accepted).
- `skills/` is a folder of `**/SKILL.md` files with YAML frontmatter —
  loaded on demand through the single `skill` tool (progressive disclosure).
- Remote MCP `headers` values expand `${ENV_VAR}` at call time and are never
  logged.

## Images, PDFs and audio

`run/4` takes a string **or** a list of content parts, in that same first
position, so your text/image ordering reaches the model unchanged:

```elixir
alias Toolnexus.ContentPart

Client.run(client, [ContentPart.text("What broke?"), ContentPart.image!("shot.png")], toolkit)
```

`image!/2`, `file!/2` and `audio!/2` take what you already hold: a path, a
`data:` URL, an `https:` URL, `{:bytes, binary}` or **iodata** (a proper or
improper iolist) with an explicit `:mime_type`, a `File.Stream` — which knows
its own path, so it carries a mime type the way a path does — or any other
**`Enumerable`** yielding binary chunks. Everything normalises at construction:
a part holds bytes plus a `mime_type`, never a filesystem path and never an
unread stream, so a saved transcript replays without the file. A stream is
consumed **eagerly** and is not closed on your behalf. Mime types come from a
fixed extension table — never sniffed — so every port agrees. The non-raising
`image/2` etc. return `{:ok, part} | {:error, exception}`.

A tool can answer with parts too (`%ToolResult{output: "screenshot, 8x8 png",
parts: [part]}`), and MCP servers returning images, audio, embedded resources or
resource links no longer have that content silently dropped.

## Why the BEAM port

Long-running agents want supervision. Every MCP connection is a supervised
process; a crashed stdio server is isolated (status `"failed"`) without taking
your toolkit down; parallel tool calls ride `Task.async_stream`. Same contract
as the other five ports, native OTP underneath.

## Sub-agents & teams

An **Agent is a Tool**: a system prompt × a scoped toolkit view × the client loop. One agent
delegates to another **in-process** via one `task` tool — isolated context, one result back,
tokens rolled up, hierarchical budgets, durable suspension (`SPEC.md §7D`). Each handle is a
GenServer with an inbox-as-state; `interrupt` kills only the in-flight Run, never the agent.

```elixir
alias Toolnexus.Agents

explore = Agents.agent("explore", does: "read-only research", uses: %{tools: [lookup]})

coder =
  Agents.agent("coder",
    does: "implements changes",
    soul_file: "AGENTS.md",
    team: [explore],                     # team = the task tool's only targets; no team ⇒ no task tool
    budget: %{max_tokens: 10_000}
  )

r = Agents.run(coder, [llm: %{base_url: "https://openrouter.ai/api/v1", style: "openai", model: "openai/gpt-4o-mini"}], "fix the failing test")
IO.puts("#{r.status} #{r.text} #{r.total_tokens}")
```

Full guide: [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/).

## Docs

Full documentation (all six languages, one site):
<https://muthuishere.github.io/toolnexus/>
