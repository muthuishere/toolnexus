# toolnexus

[![npm](https://img.shields.io/npm/v/toolnexus?logo=npm&label=npm)](https://www.npmjs.com/package/toolnexus)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `run()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework (no builders, advisors, runnables, config), not a toy that
> falls over the moment you need streaming or a retry. Everything a real agent needs — the loop,
> hooks, streaming, retries, memory — and nothing it doesn't.

The JS/TypeScript port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same
library, byte-identical, also in **Python, Go, Java, and C#**. Built on
`@modelcontextprotocol/sdk` (the MCP SDK opencode uses).

## Install

```sh
npm install toolnexus
```

## An agent in 3 steps

```ts
import { createToolkit, createClient } from "toolnexus"

// 1. tools from an mcp.json + a skills/ folder
const tk = await createToolkit({ mcpConfig: "./mcp.json", skillsDir: "./skills" })

// 2. point at any OpenAI- or Anthropic-style endpoint
const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",
  style: "openai",                  // or "anthropic"
  model: "openai/gpt-4o-mini",
})

// 3. run — skills are injected into the system prompt, tools are called for you, looped to an answer
const { text } = await agent.run("Refund order 1234 for the customer.", { toolkit: tk })
console.log(text)
await tk.close()
```

`createClient` reads the key from `OPENROUTER_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`
by default. The loop runs call → execute tools → feed back → repeat (up to `maxTurns`, default 10),
with hooks, streaming (`agent.stream(...)`), retries/backoff, and conversation memory available.

## Add your own tools

```ts
import { defineTool, httpTool } from "toolnexus"

tk.register(
  // a plain function → a tool
  defineTool({
    name: "add", description: "Add two numbers",
    inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
    run: ({ a, b }) => `${(a as number) + (b as number)}`,
  }),
  // a REST endpoint → a tool
  httpTool({
    name: "create_ticket", description: "Create a ticket", method: "POST",
    url: "https://api.example.com/tickets",
    headers: { Authorization: "Bearer ${API_TOKEN}" },   // ${ENV} expands from process.env, never logged
    inputSchema: { type: "object", properties: { title: { type: "string" } }, required: ["title"] },
  }),
)
```

URL `{placeholders}` are filled from args; `query` args go to the querystring; the rest become
the JSON body. Non-2xx → `{ isError: true, output: "HTTP <status>: <body>" }`.

## Bring your own loop

Don't want the host loop? Use the schema adapters and execute calls yourself:

```ts
const tools  = tk.toOpenAI()       // or tk.toAnthropic() / tk.toGemini()
const system = tk.skillsPrompt()   // markdown skills catalog for your system prompt
// when the model returns a tool call { name, arguments }:
const res = await tk.execute(name, args)   // -> { output, isError, metadata }
```

## The four sources

| Source | How |
|--------|-----|
| **MCP servers** | an `mcp.json` (`mcpServers`/`servers`/`mcp`); local stdio + remote streamable-HTTP, `headers` for auth |
| **Agent skills** | a folder of `<name>/SKILL.md`; a `skill` tool loads each on demand + a system-prompt catalog |
| **Native tools** | `defineTool({...})` — a function becomes a tool |
| **HTTP / REST** | `httpTool({...})` — an endpoint becomes a tool, `${ENV}` headers |

All four appear as one uniform `Tool` in `tk.tools()`. Each is à la carte — use just `loadMcp(config)`
or `loadSkills(dir)` if that's all you need.

## API

| Member | Description |
|--------|-------------|
| `createToolkit(opts)` | async factory → `Toolkit` |
| `createClient(opts)` | the unified host loop (`run` / `stream`) |
| `tk.tools()` / `tk.get(name)` | the uniform tools |
| `tk.execute(name, args, ctx?)` | run a tool → `ToolResult` |
| `tk.skillsPrompt()` | system-prompt skill catalog |
| `tk.mcpStatus()` | per-server connection status |
| `tk.toOpenAI()` / `toAnthropic()` / `toGemini()` | provider tool schemas |
| `tk.register(...tools)` | add native/http/custom tools |
| `tk.close()` | disconnect MCP servers |

## More

Full docs, the other four language ports, the shared behavior spec, and runnable examples:
**https://github.com/muthuishere/toolnexus**

MIT licensed.
