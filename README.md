# toolnexus

[![CI](https://github.com/muthuishere/toolnexus/actions/workflows/ci.yml/badge.svg)](https://github.com/muthuishere/toolnexus/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/toolnexus?logo=npm&label=npm)](https://www.npmjs.com/package/toolnexus)
[![PyPI](https://img.shields.io/pypi/v/toolnexus?logo=pypi&logoColor=white&label=PyPI)](https://pypi.org/project/toolnexus/)
[![license](https://img.shields.io/badge/license-MIT-green)](LICENSE)

### Your LLM, with MCP tools and agent skills built in — in 3 lines, in 5 languages.

Point toolnexus at an `mcp.json` and a `skills/` folder and you get a **working agent**: the
tool-calling loop, skills injection, and four unified tool sources — all included. Vendor-neutral,
byte-identical across **JavaScript · Python · Go · Java · C#**.

> **Right-sized.** Not a framework — no builders, advisors, runnables, or config to wade through.
> Not a toy that falls over the moment you need streaming or a retry. Exactly what a real agent
> needs — MCP, skills, native + HTTP tools, the loop, hooks, streaming, retries, memory — and
> nothing it doesn't.

```sh
npm i toolnexus                                   # JS / TypeScript
pip install toolnexus                             # Python
go get github.com/muthuishere/toolnexus/golang    # Go
dotnet add package Toolnexus                       # C#   (Java via Maven Central — coming)
```

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

## From zero to agent in 3 steps

No framework, no glue. Two files and one call — and your LLM now has **MCP tools and
agent skills built in**, something no other library hands you as a drop-in.

**1. Add an MCP config file** — `mcp.json`:
```jsonc
{ "mcpServers": {
    "fs":   { "command": ["npx","-y","@modelcontextprotocol/server-filesystem","/data"] },
    "acme": { "type": "remote", "url": "https://api.acme.com/mcp",
              "headers": { "Authorization": "Bearer ${ACME_TOKEN}" } }
} }
```

**2. Add a skills folder** — `skills/process-refund/SKILL.md`:
```markdown
---
name: process-refund
description: Use when a customer asks for a refund. Walks the policy + steps.
---
# Refund workflow
1. Verify the order …
```

**3. Call any LLM — MCP + skills are already in it:**
```ts
const tk    = await createToolkit({ mcpConfig: "./mcp.json", skillsDir: "./skills" })
const agent = createClient({ baseUrl: "https://openrouter.ai/api/v1", style: "openai", model: "openai/gpt-4o-mini" })

const { text } = await agent.run("Refund order 1234 for the customer.", { toolkit: tk })
// the model already sees every MCP server tool + a `skill` tool, and the skills catalog
// is injected into its system prompt — it loads `process-refund` and calls the fs/acme tools itself.
```

That's the whole thing. Bring your own loop instead? Use `tk.toOpenAI()` /
`toAnthropic()` / `toGemini()` for the schema and `tk.execute(name, args)` to run a call.
Same three steps in Python, Go, Java, and C#.

## Why toolnexus

The individual pieces — MCP, agent skills (`SKILL.md`), native tools, HTTP tools — each landed in
the big frameworks during 2026: **Spring AI**, **LangChain** (Deep Agents) and **Google ADK** now
do most of them. What none of them combine is **all four sources behind one interface,
byte-identical across five languages, vendor-neutral, in a small à-la-carte library:**

- **Five languages, one behavior** — JS · Python · Go · Java · C#, pinned by a shared
  [SPEC.md](SPEC.md) so they stay byte-compatible (the skill-loader output is byte-for-byte).
  First-class **Go and C#** — where Spring AI (Java-only) and LangChain (no Go/C#) don't reach.
- **Vendor-neutral** — a plain base URL + `openai`/`anthropic` style; not tied to one provider
  (unlike Gemini-centric ADK).
- **A library, not a platform** — à la carte: use just the MCP host, or add skills / native /
  HTTP / the host loop as you like. No runtime, no orchestration server.
- **All four sources unified** — MCP servers, agent skills, native functions, and HTTP/REST as
  one `Tool` registry, for any model.

Each language builds on the most popular MCP SDK for that ecosystem — nothing is reimplemented
from scratch:

| Lang   | Dir         | MCP SDK                                          |
|--------|-------------|--------------------------------------------------|
| JS/TS  | [`js/`](js/)         | `@modelcontextprotocol/sdk` (same as opencode)  |
| Python | [`python/`](python/) | `mcp` (modelcontextprotocol/python-sdk)         |
| Go     | [`golang/`](golang/) | `github.com/mark3labs/mcp-go`                   |
| Java   | [`java/`](java/)     | `io.modelcontextprotocol.sdk:mcp` (official)    |
| C#     | [`csharp/`](csharp/) | `ModelContextProtocol` (official)               |

The language-independent behavior is pinned in **[SPEC.md](SPEC.md)** so all five stay
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

[`js/`](js/) · [`python/`](python/) · [`golang/`](golang/) · [`java/`](java/) · [`csharp/`](csharp/) — quickstarts and API.
Embedding in a Go app? See [`golang/GUIDE.md`](golang/GUIDE.md).
[`examples/`](examples/) holds the shared `mcp.json` + sample skill used by every
implementation's examples and tests.

## Status

- ✅ MCP servers (stdio + streamable-HTTP / SSE)
- ✅ Agent skills (SKILL.md discovery + progressive-disclosure `skill` tool)
- ✅ Native/decorator tools + HTTP/REST tools
- ✅ Unified LLM client (OpenAI- and Anthropic-style endpoints) + Go CLI
- ✅ OpenAI / Anthropic / Gemini schema adapters
- ✅ Verified with live OpenRouter tool-calling round trips (every port)
- ✅ Published: **npm** + **PyPI** + **Go module** (Java → Maven Central, C# → NuGet: coming)
- ⏳ OpenAPI bulk import + MCP OAuth — follow-ups (pass a bearer token via `headers` for now)

## Tests

Each port has a hermetic suite (no network, no LLM — local HTTP servers for the HTTP
tool, the shared `examples/` fixtures for skills) covering config parsing, `${ENV}`
header expansion, the byte-exact skill block, native + HTTP tools, the provider
adapters, and toolkit routing.

```sh
cd js     && npm test                 # node:test
cd python && uv run pytest -q         # pytest
cd golang && go test ./...            # go test
cd java   && ./gradlew test           # JUnit 5
cd csharp && dotnet test              # xUnit — 24 tests
```

The end-to-end agent loop (MCP + skills + native + HTTP through the host loop) is
additionally verified live against OpenRouter per language via the `examples/agent.*`
runners (need `OPENROUTER_API_KEY`).

## License

MIT
