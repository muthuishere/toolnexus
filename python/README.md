# toolnexus (Python)

Provider-agnostic toolkit that gives **any LLM** the two dynamic capabilities
opencode has:

1. **Dynamic MCP servers** — read an MCP config file, connect to every server
   (local stdio + remote streamable-HTTP), and expose each server tool as a
   uniform `Tool`.
2. **Dynamic agent skills** — read a skills folder (`**/SKILL.md`) and expose a
   single `skill` tool that loads a skill's instructions + resources on demand
   (progressive disclosure).

Built on the official MCP Python SDK (the `mcp` package). This is the Python
sibling of the `js/` reference implementation; the contract is shared
(`../SPEC.md`).

## Install

```bash
uv venv
uv pip install -e .
```

(Or with stdlib venv: `python -m venv .venv && .venv/bin/pip install -e .`.)

## Quickstart

The MCP SDK is async, so the toolkit is async. Manage its lifetime with
`async with`:

```python
import asyncio
from toolnexus import create_toolkit


async def main():
    async with await create_toolkit(
        mcp_config="../examples/mcp.json",
        skills_dir="../examples/skills",
    ) as tk:
        print(tk.mcp_status())                       # {"everything": "connected", ...}
        print([t.name for t in tk.tools()])          # mcp tools + "skill"
        print(tk.skills_prompt())                    # ## Available Skills ...

        tools = tk.to_openai()                        # or to_anthropic() / to_gemini()

        # ... call your LLM with `tools` + skills_prompt() as system text ...
        # model returns a tool_call { name, arguments }

        res = await tk.execute(name, arguments)       # routes to the right tool
        print(res.output)                             # feed back to the model


asyncio.run(main())
```

`create_toolkit(...)` is an async factory. The returned `Toolkit` is also an
async context manager; if you do not use `async with`, call `await tk.close()`
yourself to disconnect every MCP client.

## API

| Python                       | JS / SPEC equivalent |
|------------------------------|----------------------|
| `await create_toolkit(...)`  | `createToolkit(...)` |
| `tk.tools()`                 | `tk.tools()`         |
| `tk.get(name)`               | `tk.get(name)`       |
| `await tk.execute(name, args, ctx=None)` | `tk.execute(...)` |
| `tk.skills_prompt()`         | `tk.skillsPrompt()`  |
| `tk.mcp_status()`            | `tk.mcpStatus()`     |
| `tk.to_openai()` / `to_anthropic()` / `to_gemini()` | `toOpenAI()` etc. |
| `await tk.close()`           | `tk.close()`         |

A uniform `Tool` has `name`, `description`, `input_schema`, `source`
(`"mcp" | "skill" | "custom"`), and an async `execute(args, ctx=None)` returning
a `ToolResult(output, is_error, metadata)`.

## Examples

- `examples/basic.py` — connect to the `everything` MCP server, list tools,
  print the skills catalog, and load the `hello-world` skill (progressive
  disclosure). Requires `npx` on PATH (for `@modelcontextprotocol/server-everything`).
- `examples/openrouter_test.py` — a real OpenRouter tool-calling round trip.
  Reads `OPENROUTER_API_KEY` from the environment (never hardcode it).
