# toolnexus (Elixir)

Your LLM, with MCP tools and agent skills built in — in 3 lines, on the BEAM.

`toolnexus` unifies every tool source an agent needs — **MCP servers** (stdio + streamable-HTTP),
**agent skills** (`SKILL.md` folders), your own functions, HTTP endpoints, ten built-in shell/file
tools, and remote **A2A agents** — behind one uniform `Tool`, emits the schema in **OpenAI /
Anthropic / Gemini** formats, and ships a unified client with a built-in tool-calling loop.

The Elixir port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, Java, C# and Clojure**. The MCP client is
implemented in-house on OTP (supervised connections, no third-party MCP SDK), which is also why
this port ships the **full** elicitation bridge (form *and* URL mode).

## Install

```elixir
def deps do
  [{:toolnexus, "~> 0.20"}]
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

## Why the BEAM port

Long-running agents want supervision. Every MCP connection is a supervised process; a crashed
stdio server is isolated (status `"failed"`) without taking your toolkit down; parallel tool calls
ride `Task.async_stream`. Same contract as the other six ports, native OTP underneath.

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

Contract across all seven ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
