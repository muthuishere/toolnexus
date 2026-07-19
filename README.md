# toolnexus

[![CI](https://github.com/muthuishere/toolnexus/actions/workflows/ci.yml/badge.svg)](https://github.com/muthuishere/toolnexus/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/toolnexus?logo=npm&label=npm)](https://www.npmjs.com/package/toolnexus)
[![PyPI](https://img.shields.io/pypi/v/toolnexus?logo=pypi&logoColor=white&label=PyPI)](https://pypi.org/project/toolnexus/)
[![NuGet](https://img.shields.io/nuget/v/Toolnexus?logo=nuget&label=NuGet)](https://www.nuget.org/packages/Toolnexus)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.muthuishere/toolnexus?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus)
[![Hex.pm](https://img.shields.io/hexpm/v/toolnexus?logo=elixir&label=Hex)](https://hex.pm/packages/toolnexus)
[![Go Reference](https://pkg.go.dev/badge/github.com/muthuishere/toolnexus/golang.svg)](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang)
[![license](https://img.shields.io/badge/license-MIT-green)](LICENSE)

### Your LLM, with MCP tools and agent skills built in — in 3 lines, in 6 languages.

Point toolnexus at an `mcp.json` and a `skills/` folder and you get a **working agent**: the
tool-calling loop, skills injection, six unified tool sources, and conversation memory — all
included. Vendor-neutral, byte-identical across **JavaScript · Python · Go · Java · C# · Elixir**.

> **Right-sized.** Not a framework — no builders, advisors, runnables, or config to wade through.
> Not a toy that falls over the moment you need streaming or a retry. Exactly what a real agent
> needs — MCP, skills, native + HTTP + built-in tools, remote A2A agents, in-process sub-agents,
> the loop, hooks, streaming, retries, memory — and nothing it doesn't.

```sh
npm i toolnexus                                   # JS / TypeScript
pip install toolnexus                             # Python
go get github.com/muthuishere/toolnexus/golang    # Go
dotnet add package Toolnexus                       # C#
{:toolnexus, "~> 0.9"}                             # Elixir (mix.exs deps)
# Java (Maven): io.github.muthuishere:toolnexus:0.9.5
```

The insight (borrowed from [opencode](https://github.com/anomalyco/opencode)): MCP server
tools, agent skills, your own functions, remote HTTP endpoints, the built-in shell/file
tools, remote A2A agents, and in-process **sub-agents** are all *the same thing* to an LLM — a
named, described, schema'd callable. toolnexus unifies **every tool source** behind one `Tool`
interface and drives **any** model with them.

```
   SOURCES                          TOOLKIT                          ANY LLM
 ┌──────────────────┐
 │ MCP servers      │──┐
 │  (mcp.json)      │  │     ┌──────────────────────────┐      ┌──────────────┐
 ├──────────────────┤  │     │  uniform Tool[] registry  │─────▶│ OpenAI-style │
 │ Agent skills     │  │     │  • tools() / execute()    │      ├──────────────┤
 │  (SKILL.md)      │  ├────▶│  • skillsPrompt()         │─────▶│ Anthropic    │
 ├──────────────────┤  │     │  • toOpenAI/Anthropic/    │      ├──────────────┤
 │ Native fns       │  │     │    Gemini()               │─────▶│ Gemini       │
 │  (defineTool)    │  │     └────────────┬─────────────┘      └──────────────┘
 ├──────────────────┤  │                  ▼
 │ HTTP / OpenAPI   │  │     ┌───────────────────────────────────┐
 │  (url+headers)   │  ├────▶│ UNIFIED CLIENT (host loop):        │
 ├──────────────────┤  │     │ baseURL + style + model → run()    │
 │ Built-in tools   │──┘     │ inject skills → call → exec → …    │
 │  (10, on by dflt)│        │ + memory: ask() / ConversationStore│
 └──────────────────┘        └───────────────────────────────────┘
        + remote A2A agents (each skill → a tool) · or serve your toolkit as an A2A agent
        + in-process sub-agents (agent() + team → one task tool; SPEC §7D)
```

## From zero to agent in 3 steps

No framework, no glue. Two files and one call — and your LLM now has **MCP tools, agent skills,
and 10 built-in shell/file tools built in**, something no other library hands you as a drop-in.

**1. Add an MCP config file** — `mcp.json`:
```jsonc
{
  "mcpServers": {
    "fs": {
      "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"]
    },
    "acme": {
      "type": "remote",
      "url": "https://api.acme.com/mcp",
      "headers": { "Authorization": "Bearer ${ACME_TOKEN}" }
    }
  }
}
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

**3. Call any LLM — MCP + skills + built-ins are already in it:**
```ts
const tk = await createToolkit({ mcpConfig: "./mcp.json", skillsDir: "./skills" })

const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",
  style: "openai",
  model: "openai/gpt-4o-mini",
})

const { text } = await agent.run("Refund order 1234 for the customer.", { toolkit: tk })
// The model sees every MCP server tool, a `skill` tool, and the built-in toolset — the skills
// catalog is injected into its system prompt. It loads `process-refund` and calls tools itself.
```

That's the whole thing. `createToolkit()` alone (no config) still gives you a working agent —
the 10 built-in tools are on by default. Bring your own loop instead? Use `tk.toOpenAI()` /
`toAnthropic()` / `toGemini()` for the schema and `tk.execute(name, args)` to run a call.
The same three steps work in Python, Go, Java, C#, and Elixir.

## Why toolnexus

The individual pieces — MCP, agent skills (`SKILL.md`), native tools, HTTP tools — each landed in
the big frameworks during 2026: **Spring AI**, **LangChain** (Deep Agents) and **Google ADK** now
do most of them. What none of them combine is **every tool source behind one interface,
byte-identical across six languages, vendor-neutral, in a small à-la-carte library:**

- **Six languages, one behavior** — JS · Python · Go · Java · C# · Elixir, pinned by a shared
  [SPEC.md](SPEC.md) so they stay byte-compatible (the skill-loader output is byte-for-byte).
  First-class **Go and C#** — where Spring AI (Java-only) and LangChain (no Go/C#) don't reach.
- **Vendor-neutral** — a plain base URL + `openai`/`anthropic` style; not tied to one provider
  (unlike Gemini-centric ADK).
- **A library, not a platform** — à la carte: use just the MCP host, or add skills / native /
  HTTP / built-ins / A2A / the host loop as you like. No runtime, no orchestration server.
- **Everything unified** — MCP servers, agent skills, native functions, HTTP/REST, built-in
  tools, and remote A2A agents as one `Tool` registry, for any model.

Each language builds on the most popular MCP SDK for that ecosystem — nothing is reimplemented
from scratch:

| Lang   | Dir         | MCP SDK                                          |
|--------|-------------|--------------------------------------------------|
| JS/TS  | [`js/`](js/)         | `@modelcontextprotocol/sdk` (same as opencode)  |
| Python | [`python/`](python/) | `mcp` (modelcontextprotocol/python-sdk)         |
| Go     | [`golang/`](golang/) | `github.com/mark3labs/mcp-go`                   |
| Java   | [`java/`](java/)     | `io.modelcontextprotocol.sdk:mcp` (official)    |
| C#     | [`csharp/`](csharp/) | `ModelContextProtocol` (official)               |
| Elixir | [`elixir/`](elixir/) | in-house MCP client (OTP-supervised, no SDK)    |

The language-independent behavior is pinned in **[SPEC.md](SPEC.md)** so all six stay
byte-compatible (especially the skill loader output).

## Six tool sources, one interface

Everything below surfaces as the same uniform `Tool` — one registry, any model.

| # | Source | Declare with | What you get |
|---|--------|--------------|--------------|
| 1 | **MCP servers** | `mcp.json` | Claude-desktop superset (`mcpServers`/`servers`/`mcp`); local stdio + remote streamable-HTTP/SSE; `${ENV}` header auth; one bad server is isolated, never fatal. |
| 2 | **Agent skills** | `skills/**/SKILL.md` | One `skill` tool loads each on demand (progressive disclosure) + a system-prompt catalog. Same format as Claude/opencode. |
| 3 | **Native functions** | `defineTool` | A plain function → a tool; schema inferred from type hints / struct tags. |
| 4 | **HTTP / REST** | `httpTool` | Declare an endpoint; `{ph}` URL substitution, `${ENV}` header expansion; OpenAPI import (best-effort). |
| 5 | **Built-in tools** | on by default | 10 opencode shell/file tools so an agent can *act* with zero wiring (see below). |
| 6 | **Sub-agents** | `agent()` + `team` | An in-process agent (prompt × scoped toolkit × loop) delegated to via one `task` tool — isolated context, tokens rolled up, hierarchical budgets, durable suspension ([SPEC §7D](SPEC.md), [docs](https://muthuishere.github.io/toolnexus/subagents/)). |

(A **7th**, remote **A2A** agents, joins the same registry over HTTP — outbound as tools, or serve your toolkit *as* an agent; see below.)

Registering your own native + HTTP tools is one call:

```ts
const tk = await createToolkit({ mcpConfig: "./mcp.json", skillsDir: "./skills" })

tk.register(
  defineTool({
    name: "add",
    description: "Add two numbers",
    inputSchema: {
      type: "object",
      properties: { a: { type: "number" }, b: { type: "number" } },
      required: ["a", "b"],
    },
    run: ({ a, b }) => `${a + b}`,
  }),
  httpTool({
    name: "get_post",
    description: "Fetch a post",
    method: "GET",
    url: "https://jsonplaceholder.typicode.com/posts/{id}",
    inputSchema: {
      type: "object",
      properties: { id: { type: "number" } },
      required: ["id"],
    },
  }),
)
```

**À la carte.** Each source is usable on its own. Want *only* an MCP host — parse `mcp.json`,
connect, get the tools, like the MCP-only libraries do? Use just `loadMcp` / `load_mcp` /
`LoadMcp` (no skills, no loop). Everything else is opt-in on top.

### Built-in tools (on by default)

toolnexus ships opencode's default toolset — **10 built-in tools** (`bash`, `read`, `write`,
`edit`, `grep`, `glob`, `webfetch`, `question`, `apply_patch`, `todowrite`, with names + input
schemas matching opencode) so an agent can act with zero wiring. They surface in the tool schema
(`toOpenAI`/`toAnthropic`/`toGemini`) like MCP tools — *not* injected into the system prompt.

The source is **on by default** with two levels of control:

- **Global toggle** — `createToolkit({ builtins: false })` / `create_toolkit(builtins=False)` /
  `Options{ Builtins: false }` / `.builtins(false)` turns the whole source off.
- **Per-tool map** — `builtins: { tools: { bash: false } }` drops individual tools on the all-on
  baseline (other tools stay on, unknown names ignored; a whole-source-off still wins).

Because `bash`/`write`/`edit`/`apply_patch` run commands and mutate the filesystem, these switches
are the off-switch for locked-down hosts.

## A2A agents — call remote agents, or be one

Beyond the five local sources: **agent-to-agent**. Point the toolkit at a remote **A2A agent** and
each of its skills becomes a tool (named `<agent>_<skill>`, source `"a2a"`) — an agent is just
another tool source. The same toolkit can **serve itself** as an A2A agent, so other agents
(toolnexus or not) can call it. It's a genuine, minimal subset of real A2A (verified against
`a2a-python`): JSON-RPC 2.0, the Agent Card at `/.well-known/agent-card.json`, `SendMessage` → poll
`GetTask`. No streaming / push / auth in v1.

```ts
// outbound: a remote agent's skills become tools
const tk = await createToolkit({
  agents: [agent({ card: "https://peer.example.com/.well-known/agent-card.json" })],
})
await tk.addAgent("https://other.example.com/.well-known/agent-card.json") // or at runtime

// inbound: serve this toolkit as an agent — the card is built from your SKILL.md skills, not raw tools
const llm = createClient({
  baseUrl: "https://openrouter.ai/api/v1",
  style: "openai",
  model: "openai/gpt-4o-mini",
})
const handle = await tk.serve("127.0.0.1:0", {
  client: llm,
  a2a: { name: "my-agent", store: "memory" },
})
```

Both directions exist in all six ports (`agent(...)` / `Agent{...}`, an `agents` config block, and
`serve` / `ServeAsync`). Served tasks persist through a pluggable **TaskStore** (in-memory default,
`"file:<dir>"`, or your own). See each port's README for the full option set.

## Sub-agents — delegate in-process

The same axiom, locally: **an Agent is a Tool**. Define agents declaratively — a routing
description, a scoped toolkit view, an identity file, a **team** — and one agent delegates to
another through a built-in `task` tool: the child runs on a fresh transcript with only its own
tools, the parent gets back exactly one result, and the child's tokens roll up into the parent's
usage. Parallel `task` calls in one turn run concurrently. (`SPEC.md §7D`.)

```ts
import { agents } from "toolnexus"   // its own namespace — distinct from the A2A agent()

const explore = agents.agent("explore", {
  does: "read-only research",
  uses: { tools: [lookup] },          // least privilege: this child sees ONLY these tools
})
const coder = agents.agent("coder", {
  does: "implements changes",
  soulFile: "./AGENTS.md",            // identity → system prompt
  team: [explore],                    // listing agents here IS the wiring; no team ⇒ no task tool
  budget: { maxTokens: 10_000 },      // hierarchical, live-enforced across the whole tree
})

const r = await coder.run("fix the failing test", {
  llm: { baseUrl: "https://openrouter.ai/api/v1", style: "openai", model: "openai/gpt-4o-mini" },
})
r.status                              // "done" — a budget stop is a LOUD "incomplete", never silent
coder.asTool()                        // …or drop any agent into the classic API's extraTools
```

A suspending child escalates like a suspending tool (the §10 human-in-the-loop layer, verbatim):
no interpreter up the chain ⇒ the run returns `status: "pending"` with the tree parked at zero
token burn, and `runtime.resume(answer)` continues from the checkpoint — re-run parents reattach
to their existing children instead of spawning duplicates. Available in all six ports (an
`agents` namespace per language). See the [sub-agents docs](https://muthuishere.github.io/toolnexus/subagents/).

## Persona agents — a long-lived assistant

Sub-agents are **workers** (spawn, delegate, done). A **persona** is the other archetype — a
long-lived assistant whose identity lives in **files**, whose **memory** it can edit, and which
acts on a **heartbeat** without being prompted. All three ride the same runtime: `fromDir(dir)`
composes bootstrap files (`SOUL/USER/HEARTBEAT/MEMORY.md`) into a frozen soul and wires a
file-backed `memory` tool; `startAgent` gives it a clock. (`SPEC.md §7E`.)

```ts
import { agents } from "toolnexus"

const ava = agents.fromDir("./personas/ava")   // the directory IS the agent
const r = await ava.run("What's on my plate today?", { llm })
// memory writes land on disk and load at the START of the next session — a frozen snapshot,
// so the prompt cache stays warm (the write does NOT mutate the live prompt).

const started = agents.startAgent(ava, { llm },
  { everyMs: 60_000, onBeat: (text) => notifyMe(text) })  // HEARTBEAT_OK beats stay silent
```

Runnable JS/Python/Go entrypoints (`examples/persona.*`) drive a real "Ava" against OpenRouter.
See the [persona-agents docs](https://muthuishere.github.io/toolnexus/persona-agents/) — including
a **"when to use which surface"** guide (`agent()` vs `fromDir` vs the raw verbs) and the
dream/consolidation + channel-assistant recipes.

## Serve as an MCP server — be a gateway

The other inbound edge: expose your **whole toolkit as an MCP server**, so any MCP client (Claude
Desktop, an IDE, another agent) can call its tools. Aggregate N MCP servers + skills + your own
functions behind one toolkit, then re-expose the **union** as one MCP server — a universal MCP
gateway. Unlike A2A (which advertises skills and runs the client loop), the MCP client *is* the LLM
host, so each `tools/call` dispatches straight to `Tool.execute` — no client, no tasks, no store.

```ts
// streamable-HTTP — an embeddable MCP server at POST /mcp, beside any A2A routes:
const srv = await tk.serve("127.0.0.1:0", { mcp: { name: "my-gateway" } })   // connect at srv.url + "/mcp"
```

All six ports ship the streamable-HTTP MCP server (the `/mcp` endpoint on `serve`), built on each
port's existing MCP SDK in server mode. `tools/list` advertises every tool (name verbatim,
`inputSchema` = the tool's parameters). A stdio transport (for local clients like Claude Desktop) is a
planned follow-up. See `SPEC.md §7C`.

## Conversations & memory

The host loop remembers a thread for you. `ask(prompt, { toolkit, id })` loads that id's transcript
from a **ConversationStore**, runs the loop with it as history, and saves the updated transcript back
— so the next `ask` with the same id continues the conversation. No id ⇒ a stateless one-shot
(identical to `run`).

```ts
const agent = createClient({ baseUrl, style: "openai", model })   // in-memory store by default
await agent.ask("Book me a flight to Berlin.", { toolkit: tk, id: "user-42" })
await agent.ask("Actually, make it Munich.",   { toolkit: tk, id: "user-42" })  // same thread — remembered
await agent.ask("What is 21 + 21?",            { toolkit: tk })                 // no id → one-shot
```

- **Pluggable store, two methods** — `get(id) → messages` and `save(id, messages)`. The default is
  in-memory (per-client, process lifetime); pass `createClient({ ..., store })` with your own
  file / db / redis implementation to persist across processes.
- **Served A2A agents remember too** — inbound `serve` fulfils each `SendMessage` via
  `ask(text, { id: contextId })`, so a peer's turns are remembered by A2A `contextId` through the
  same store; a message with no `contextId` is a one-shot.
- The low-level `run(prompt, { toolkit, history })` primitive and a stateful
  `client.conversation({ toolkit })` wrapper are still there when you'd rather own the transcript.
- **Streaming with memory** — the same `id` works on the streaming paths. Pass `on_text` to `ask`
  to stream assistant text deltas while `ask` still returns the final result, or use
  `stream(prompt, { toolkit, id })` to iterate events (`text` / `tool_call` / `tool_result` /
  `usage` / `done`); with an `id` the thread is loaded before and saved on the `done` event.

Available in all six ports (a `ConversationStore` interface + in-memory default + `ask`).

## Observability — metric events + built-in Prometheus

Zero-dependency, two outputs from one internal instrumentation — both opt-in, no cost when unused.

- **`on_metric` event feed** — `createClient({ ..., on_metric })` (idiomatic name per port) receives a
  readable semantic record at each significant point: one `{ event: "llm" }` per model call, one
  `{ event: "tool" }` per tool call, one terminal `{ event: "run" }` per `run`/`ask` (with tokens,
  turns, timings, error). Forward it to statsd, logs, or OpenTelemetry — the library holds no opinion.
- **`client.metrics()` → Prometheus text** — the same events feed a tiny in-memory registry that
  renders the Prometheus text exposition format (no third-party dep). Mount it at `GET /metrics`:
  `toolnexus_llm_requests_total`, `toolnexus_llm_tokens_total`, `toolnexus_tool_calls_total`, plus
  the `toolnexus_llm_request_duration_seconds` / `toolnexus_tool_duration_seconds` histograms. The
  rendered text is byte-identical across all six ports; OTLP push is a planned future companion.

## Go CLI — an instant agent from the terminal

```sh
cd golang && go build -o toolnexus ./cmd/toolnexus
./toolnexus run --config ../examples/mcp.json --skills ../examples/skills \
  --base-url https://openrouter.ai/api/v1 --style openai --model openai/gpt-4o-mini
# > you: ...     (continuous REPL agent loop)
./toolnexus tools --config ../examples/mcp.json --skills ../examples/skills   # list resolved tools
```

## Per-language docs

Full docs site (all six languages): **<https://muthuishere.github.io/toolnexus/>**.
Per port: [`js/`](js/) · [`python/`](python/) · [`golang/`](golang/) · [`java/`](java/) · [`csharp/`](csharp/) · [`elixir/`](elixir/) — quickstarts and API.
Embedding in a Go app? See [`golang/GUIDE.md`](golang/GUIDE.md).
[`examples/`](examples/) holds the shared `mcp.json` + sample skill used by every
implementation's examples and tests. The cross-language contract lives in [SPEC.md](SPEC.md).

## Status

- ✅ MCP servers (stdio + streamable-HTTP / SSE)
- ✅ Agent skills (SKILL.md discovery + progressive-disclosure `skill` tool)
- ✅ Native/decorator tools + HTTP/REST tools
- ✅ Built-in tools (10 opencode tools; on by default, whole-source toggle + per-tool map)
- ✅ A2A agents — outbound (call remote agents) + inbound (`serve` your toolkit as an agent); all six ports
- ✅ MCP server (inbound) — expose the toolkit as a streamable-HTTP MCP server (`/mcp` on `serve`); all six ports
- ✅ Conversation memory (`ask` + pluggable `ConversationStore`; A2A serve remembers by `contextId`)
- ✅ Streaming with memory (`stream`/`ask` take an `id`; `ask` gains an `on_text` delta callback)
- ✅ Observability — `on_metric` event feed + zero-dep `client.metrics()` Prometheus text
- ✅ Unified LLM client (OpenAI- and Anthropic-style endpoints) + Go CLI
- ✅ OpenAI / Anthropic / Gemini schema adapters
- ✅ Verified with live OpenRouter tool-calling round trips (every port)
- ✅ Published on all six registries: **npm** · **PyPI** · **Go module** · **NuGet** · **Maven Central** · **Hex**
- ⏳ OpenAPI bulk import + MCP OAuth — follow-ups (pass a bearer token via `headers` for now)

## Tests

Each port has a hermetic suite (no network, no LLM — local HTTP servers for the HTTP
tool, the shared `examples/` fixtures for skills) covering config parsing, `${ENV}`
header expansion, the byte-exact skill block, native + HTTP + built-in tools, A2A, the
provider adapters, and toolkit routing.

```sh
cd js     && npm test                 # node:test
cd python && uv run pytest -q         # pytest
cd golang && go test ./...            # go test
cd java   && ./gradlew test           # JUnit 5
cd csharp && dotnet test              # xUnit
cd elixir && mix test                 # ExUnit
```

The end-to-end agent loop (MCP + skills + native + HTTP through the host loop) is
additionally verified live against OpenRouter per language via the `examples/agent.*`
runners (need `OPENROUTER_API_KEY`).

## License

MIT
