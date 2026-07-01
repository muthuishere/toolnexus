# toolnexus

[![PyPI](https://img.shields.io/pypi/v/toolnexus?logo=pypi&logoColor=white&label=PyPI)](https://pypi.org/project/toolnexus/)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `run()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework (no builders, no config to wade through), not a toy that falls
> over the moment you need streaming or a retry. Everything a real agent needs — the loop, hooks,
> streaming, retries, memory — and nothing it doesn't.

The Python port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Go, Java, and C#**. Built on the official MCP Python SDK
(the `mcp` package). Python ≥ 3.11.

## Install

```sh
pip install toolnexus
```

## An agent in 3 steps

The MCP SDK is async, so the toolkit is async:

```python
import asyncio
from toolnexus import create_toolkit, create_client


async def main():
    # 1. tools from an mcp.json + a skills/ folder
    tk = await create_toolkit(mcp_config="./mcp.json", skills_dir="./skills")

    # 2. point at any OpenAI- or Anthropic-style endpoint
    agent = create_client(
        base_url="https://openrouter.ai/api/v1",
        style="openai",                # or "anthropic"
        model="openai/gpt-4o-mini",
    )

    # 3. run — skills injected, tools called for you, looped to an answer
    res = await agent.run("Refund order 1234 for the customer.", tk)
    print(res.text)
    await tk.close()


asyncio.run(main())
```

`create_client` reads the key from `OPENROUTER_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`
by default. The `Toolkit` is also an async context manager (`async with await create_toolkit(...)`)
if you'd rather not call `close()` yourself.

## Add your own tools

```python
from toolnexus import define_tool, http_tool

# a plain function → a tool (schema inferred from the signature)
def add(a: float, b: float) -> str:
    """Add two numbers and return the sum."""
    return str(a + b)

tk.register(define_tool(add, name="add"))

# a REST endpoint → a tool
tk.register(http_tool(
    name="create_ticket", description="Create a ticket", method="POST",
    url="https://api.example.com/tickets",
    headers={"Authorization": "Bearer ${API_TOKEN}"},   # ${ENV} expands from os.environ, never logged
    input_schema={"type": "object", "properties": {"title": {"type": "string"}}, "required": ["title"]},
))
```

URL `{placeholders}` are filled from args; the rest become the JSON body. Non-2xx →
`ToolResult(output="HTTP <status>: <body>", is_error=True)`.

## Bring your own loop

Don't want the host loop? Use the schema adapters and execute calls yourself:

```python
tools  = tk.to_openai()        # or tk.to_anthropic() / tk.to_gemini()
system = tk.skills_prompt()    # skills catalog for your system prompt (opens with a preamble telling the model to use the skill tool)
# when the model returns a tool call { name, arguments }:
res = await tk.execute(name, arguments)   # -> ToolResult(output, is_error, metadata)
```

## The four sources

| Source | How |
|--------|-----|
| **MCP servers** | an `mcp.json` (`mcpServers`/`servers`/`mcp`); local stdio + remote streamable-HTTP, `headers` for auth |
| **Agent skills** | a folder of `<name>/SKILL.md`; a `skill` tool loads each on demand + a system-prompt catalog |
| **Native tools** | `define_tool(fn)` / the `@tool` decorator — a function becomes a tool |
| **HTTP / REST** | `http_tool(...)` — an endpoint becomes a tool, `${ENV}` headers |

All four appear as one uniform `Tool` in `tk.tools()`, with `source` in `"mcp" | "skill" | "custom"`.

## Built-in tools

A fifth source ships **10 built-in tools** — `bash`, `read`, `write`, `edit`, `grep`, `glob`,
`webfetch`, `question`, `apply_patch`, `todowrite` (names + input schemas match
opencode) — so an agent can act with zero wiring. They appear in the tool schema
(`to_openai()`/`to_anthropic()`/`to_gemini()`), like MCP tools — not the system prompt.

**On by default.** One global toggle turns the whole source off, or a per-tool `tools` map
disables individual builtins on the all-on baseline:

```python
tk = await create_toolkit(mcp_config="./mcp.json", builtins=False)
# also accepts {"disabled": True} or {"enabled": False}

# per-tool: drop bash, keep the other nine (unknown names ignored; whole-source-off still wins)
tk2 = await create_toolkit(mcp_config="./mcp.json", builtins={"tools": {"bash": False}})
```

`bash`/`write`/`edit`/`apply_patch` run commands and mutate the filesystem — the toggle is the
off-switch for locked-down hosts.

## A2A agents (agent-to-agent)

Call **remote A2A agents** (each of their skills becomes a tool) and serve your own toolkit as an
agent other A2A peers can call. A genuine, minimal subset of real A2A (JSON-RPC 2.0; Agent Card at
`/.well-known/agent-card.json`; `SendMessage` → poll `GetTask`). No streaming / push / auth in v1.

**Outbound — call a remote agent.** Each advertised skill becomes a tool named `<agent>_<skill>`
(`source="a2a"`):

```python
from toolnexus import create_toolkit, agent

tk = await create_toolkit(
    agents=[agent("https://researcher.example.com/.well-known/agent-card.json")],
)

# or add one at runtime (an Agent or a bare card URL):
await tk.add_agent("https://writer.example.com/.well-known/agent-card.json")
```

`agent(card, *, headers=None, timeout=None, poll_every=None)` — `headers` support `${ENV}`
expansion (never logged); `timeout` / `poll_every` are milliseconds (300000 / 1000 defaults). A
config file can also carry an `agents` block. A failing agent is isolated — contributes no tools,
never fatal.

**Inbound — serve your toolkit as an agent.** The Agent Card is built from your **SKILL.md skills**
(never raw tools):

```python
from toolnexus import create_client

agent_client = create_client(base_url="https://openrouter.ai/api/v1", style="openai", model="openai/gpt-4o-mini")

handle = await tk.serve("127.0.0.1:0", client=agent_client, a2a={
    "name": "research-agent",
    "description": "Answers research questions.",
    # "skills": ["hello-world"],   # subset of skills to advertise; omit ⇒ all
    "store": "memory",             # "memory" (default) | "file:<dir>" | a custom TaskStore
})
print(handle.url)                  # GET /.well-known/agent-card.json ; POST / (SendMessage / GetTask)
await handle.stop()
```

`serve(addr, *, client, a2a=None, on_task=None)` fulfils each inbound task via `client.run`. Task
persistence is a pluggable `TaskStore` — in-memory default, `"file:<dir>"`, or your own.

## API

| Python | Description |
|--------|-------------|
| `await create_toolkit(...)` | async factory → `Toolkit` |
| `create_client(...)` | the unified host loop (`await agent.run(msg, tk)` / `agent.stream(...)`) |
| `tk.tools()` / `tk.get(name)` | the uniform tools |
| `await tk.execute(name, args, ctx=None)` | run a tool → `ToolResult` |
| `tk.skills_prompt()` | system-prompt skill catalog |
| `tk.mcp_status()` | per-server connection status |
| `tk.to_openai()` / `to_anthropic()` / `to_gemini()` | provider tool schemas |
| `tk.register(*tools)` | add native/http/custom tools |
| `await tk.close()` | disconnect MCP servers |

## More

Full docs, the other four language ports, the shared behavior spec, and runnable examples:
**https://github.com/muthuishere/toolnexus**

MIT licensed.
