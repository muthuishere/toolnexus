# toolnexus

**Build an agent in a few lines.** Bring any LLM endpoint, point at an `mcp.json` and a
`skills/` folder, and you get a working agent — system prompt, skills, tool-calling loop,
all included. A small, **vendor-neutral** library in **JavaScript / TypeScript, Python, Go, and Java**.

The insight (borrowed from [opencode](https://github.com/anomalyco/opencode)): MCP server
tools, agent skills, your own functions, and remote HTTP endpoints are all *the same thing*
to an LLM — a named, described, schema'd callable. toolnexus unifies **four tool sources**
behind one `Tool` interface and drives **any** model with them.

```
   SOURCES                         TOOLKIT                         ANY LLM
 ┌────────────────┐
 │ MCP servers    │──┐
 │  (mcp.json)    │  │      ┌─────────────────────────┐      ┌──────────────┐
 ├────────────────┤  │      │  uniform Tool[] registry │─────▶│ OpenAI-style │
 │ Agent skills   │  ├─────▶│  • tools() / execute()   │      ├──────────────┤
 │  (SKILL.md)    │  │      │  • skillsPrompt()        │─────▶│ Anthropic    │
 ├────────────────┤  │      │  • toOpenAI/Anthropic/   │      ├──────────────┤
 │ Native fns     │  │      │    Gemini()              │─────▶│ Gemini       │
 │  (@tool)       │  │      └────────────┬────────────┘      └──────────────┘
 ├────────────────┤  │                   ▼
 │ HTTP / OpenAPI │──┘      ┌─────────────────────────────────┐
 │  (url+headers) │         │ UNIFIED CLIENT (host loop):      │
 └────────────────┘         │ baseURL + style + model → run()  │
                            │ inject skills → call → exec → … │
                            └─────────────────────────────────┘
```

## Why toolnexus (and not a framework)

The giants each cover *part* of this surface; none covers all of it in our lane:

| | MCP from config | Agent Skills (SKILL.md) | native/decorator tools | HTTP/OpenAPI→tools | unified any-LLM client | languages |
|---|:--:|:--:|:--:|:--:|:--:|---|
| **Spring AI** | ✅ | ❌ | ✅ | ❌ | ✅ | Java only |
| **LangChain** | ✅ | ❌ | ✅ | ~ | ✅ | Py, JS (no Go) |
| **Google ADK** | ✅ | ✅ | ✅ | ✅ | ✅ | Py/Java/Go/JS — Gemini-centric |
| **toolnexus** | ✅ | ✅ | ✅ | ✅ | ✅ | **JS + Py + Go + Java, vendor-neutral** |

toolnexus is a **focused library**, not a platform: lightweight, vendor-neutral, with real
parity across JS, Python, and Go (including first-class Go — where LangChain and Spring AI
can't reach). Each language builds on the most popular MCP SDK for that ecosystem — nothing
is reimplemented from scratch:

| Lang   | Dir         | MCP SDK                                          |
|--------|-------------|--------------------------------------------------|
| JS/TS  | [`js/`](js/)         | `@modelcontextprotocol/sdk` (same as opencode)  |
| Python | [`python/`](python/) | `mcp` (modelcontextprotocol/python-sdk)         |
| Go     | [`golang/`](golang/) | `github.com/mark3labs/mcp-go`                   |
| Java   | [`java/`](java/)     | `io.modelcontextprotocol.sdk:mcp` (official)    |

The language-independent behavior is pinned in **[SPEC.md](SPEC.md)** so all three stay
byte-compatible (especially the skill loader output).

## Quick start (JS) — a full agent

```sh
cd js && npm install && npm run build
```

```ts
import { createToolkit, defineTool, httpTool, createClient } from "toolnexus"

const tk = await createToolkit({ mcpConfig: "./mcp.json", skillsDir: "./skills" })

// add your own tools — a native function and a REST endpoint
tk.register(
  defineTool({ name: "add", description: "Add two numbers",
    inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
    run: ({ a, b }) => `${a + b}` }),
  httpTool({ name: "get_post", description: "Fetch a post", method: "GET",
    url: "https://jsonplaceholder.typicode.com/posts/{id}",
    inputSchema: { type: "object", properties: { id: { type: "number" } }, required: ["id"] } }),
)

// the host loop: plain URL + style → working agent
const agent = createClient({ baseUrl: "https://openrouter.ai/api/v1", style: "openai", model: "openai/gpt-4o-mini" })
const { text } = await agent.run("What is 21+21? Then fetch post 1's title.", { toolkit: tk })
console.log(text)
await tk.close()
```

Prefer to own the loop? Use the schema adapters and call `tk.execute(name, args)` yourself.

## Go CLI — an instant agent from the terminal

```sh
cd golang && go build -o toolnexus ./cmd/toolnexus
./toolnexus run --config ../examples/mcp.json --skills ../examples/skills \
  --base-url https://openrouter.ai/api/v1 --style openai --model openai/gpt-4o-mini
# > you: ...     (continuous REPL agent loop)
./toolnexus tools --config ../examples/mcp.json --skills ../examples/skills   # list resolved tools
```

## The four sources

1. **MCP servers** — an `mcp.json` (Claude-desktop superset; top-level `mcpServers`/`servers`/`mcp`).
   Local stdio + remote streamable-HTTP/SSE, with `headers` for auth.
2. **Agent skills** — a folder of `<name>/SKILL.md` (YAML frontmatter); a `skill` tool loads
   each on demand (progressive disclosure) + a system-prompt catalog. Same format as Claude/opencode.
3. **Native tools** — a plain function → a tool (`defineTool` / `@tool` decorator; schema inferred).
4. **HTTP/REST tools** — declare an endpoint (`httpTool`); `${ENV}` header expansion; OpenAPI import (best-effort).

All four show up as the same uniform `Tool`, in one registry, for any model.

**À la carte.** Each source is usable on its own. Want *only* an MCP host — parse
`mcp.json`, connect, get the tools, like the MCP-only libraries do? Use just
`loadMcp` / `load_mcp` / `LoadMcp` (no skills, no loop). The skills, native/HTTP
tools, and host loop are all opt-in on top.

## Per-language docs

[`js/`](js/) · [`python/`](python/) · [`golang/`](golang/) · [`java/`](java/) — quickstarts and API.
Embedding in a Go app? See [`golang/GUIDE.md`](golang/GUIDE.md).
[`examples/`](examples/) holds the shared `mcp.json` + sample skill used by every
implementation's examples and tests.

## Status

- ✅ MCP servers (stdio + streamable-HTTP / SSE)
- ✅ Agent skills (SKILL.md discovery + progressive-disclosure `skill` tool)
- ✅ Native/decorator tools + HTTP/REST tools
- ✅ Unified LLM client (OpenAI- and Anthropic-style endpoints) + Go CLI
- ✅ OpenAI / Anthropic / Gemini schema adapters
- ✅ Verified with live OpenRouter tool-calling round trips
- ⏳ OpenAPI bulk import + MCP OAuth — follow-ups (pass a bearer token via `headers` for now)

## License

MIT
