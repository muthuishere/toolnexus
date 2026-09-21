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
byte-identical, also in **JavaScript, Go, Java, C#, Elixir and Clojure**. Built on the official
MCP Python SDK (the `mcp` package). Python ≥ 3.11.

## Install

```sh
pip install toolnexus
```

## Quick start

The **10 built-in tools** (`bash`, `read`, `grep`, `webfetch`, …) are on by default, so the model
can act with no `mcp.json` and no skills folder:

```python
import asyncio
from toolnexus import create_toolkit, create_client

async def main():
    tk = await create_toolkit()                          # built-in tools, on by default
    agent = create_client(
        base_url="https://openrouter.ai/api/v1", style="openai",
        model="deepseek/deepseek-chat",                  # any OpenRouter/OpenAI/Anthropic model
    )
    res = await agent.run("List the files here, then count them.", tk)
    print(res.text)
    await tk.close()

asyncio.run(main())
```

Add real tool sources by pointing at them:

```python
tk = await create_toolkit(mcp_config="mcp.json", skills_dir=["skills"])
```

- `mcp.json` is the standard Claude-desktop-style config (`mcpServers` / `servers` / `mcp` keys all accepted).
- `skills/` is a folder of `**/SKILL.md` files, loaded on demand through one `skill` tool.
- Remote MCP `headers` values expand `${ENV_VAR}` at call time and are never logged.

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | [Python](https://muthuishere.github.io/toolnexus/api/python/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all seven ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
