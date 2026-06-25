# toolnexus (JavaScript / TypeScript)

Dynamic MCP servers + agent skills as a uniform tool list for any LLM. Built on
`@modelcontextprotocol/sdk` (the same MCP SDK opencode uses). See the root
[SPEC.md](../SPEC.md) for the language-independent contract.

## Install

```sh
npm install toolnexus
```

## Use

```ts
import { createToolkit } from "toolnexus"

const tk = await createToolkit({
  mcpConfig: "./mcp.json",   // path or parsed object; key may be mcpServers/servers/mcp
  skillsDir: "./skills",     // folder of <name>/SKILL.md (or an array of folders)
  // extraTools: [...]        // your own custom tools, optional
})

// 1. give the model the tool schema in its native format
const tools  = tk.toOpenAI()       // or tk.toAnthropic() / tk.toGemini()
const system = tk.skillsPrompt()   // markdown catalog of skills for the system prompt

// 2. when the model returns a tool call { name, arguments }:
const res = await tk.execute(name, args)   // -> { output, isError, metadata }
// feed res.output back as the tool result, loop.

console.log(tk.mcpStatus())  // { server: "connected" | "failed" | "disabled" }
await tk.close()
```

## Run the examples

```sh
npm install && npm run build

# offline: connects to @modelcontextprotocol/server-everything via npx, lists tools, loads a skill
node --experimental-strip-types examples/basic.ts

# live: real OpenRouter tool-calling round trip (needs OPENROUTER_API_KEY)
OPENROUTER_API_KEY=... node --experimental-strip-types examples/openrouter_test.ts
```

## API

| Member | Description |
|--------|-------------|
| `createToolkit(opts)` | async factory → `Toolkit` |
| `tk.tools()` | all uniform `Tool`s (mcp + skill + extras) |
| `tk.get(name)` | one tool by name |
| `tk.execute(name, args, ctx?)` | run a tool → `ToolResult` |
| `tk.skillsPrompt()` | system-prompt skill catalog |
| `tk.mcpStatus()` | per-server connection status |
| `tk.toOpenAI()` / `toAnthropic()` / `toGemini()` | provider tool schemas |
| `tk.register(...tools)` | add native/http/custom tools at runtime |
| `tk.close()` | disconnect MCP servers |

You can also use the sources directly: `loadMcp(config)`, `loadSkills(dir)`.

## Native tools (a function → a tool)

```ts
import { defineTool } from "toolnexus"

const add = defineTool({
  name: "add", description: "Add two numbers",
  inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
  run: ({ a, b }) => `${(a as number) + (b as number)}`,   // string or ToolResult
})
tk.register(add)
```

## HTTP / REST tools (an endpoint → a tool)

```ts
import { httpTool } from "toolnexus"

tk.register(httpTool({
  name: "create_ticket", description: "Create a ticket", method: "POST",
  url: "https://api.example.com/tickets",
  headers: { Authorization: "Bearer ${API_TOKEN}" },   // ${ENV} expands from process.env, never logged
  inputSchema: { type: "object", properties: { title: { type: "string" } }, required: ["title"] },
}))
```

URL `{placeholders}` are filled from args; `query` args go to the querystring; the rest
become the JSON body. Non-2xx → `{ isError: true, output: "HTTP <status>: <body>" }`.

## Unified client (the host loop)

```ts
import { createClient } from "toolnexus"

const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",  // any OpenAI- or Anthropic-style endpoint
  style: "openai",                           // or "anthropic"
  model: "openai/gpt-4o-mini",
  // apiKey defaults to OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY
  systemPrompt: "You are a helpful agent.",
})

const { text, toolCalls } = await agent.run("do the thing", { toolkit: tk })
```

It injects `tk.skillsPrompt()` into the system message and runs the full
call → execute tools → feed back → repeat loop (up to `maxTurns`, default 10).

## Examples

- `examples/basic.ts` — toolkit + adapters + skill load (offline).
- `examples/agent.ts` — native + http tools driven by the unified client (live with `OPENROUTER_API_KEY`).
- `examples/openrouter_test.ts` — minimal live tool-calling round trip.
