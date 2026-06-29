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
system = tk.skills_prompt()    # markdown skills catalog for your system prompt
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
