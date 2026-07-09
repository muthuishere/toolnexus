# toolnexus

[![npm](https://img.shields.io/npm/v/toolnexus?logo=npm&label=npm)](https://www.npmjs.com/package/toolnexus)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `run()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework (no builders, advisors, runnables, config graphs), not a toy
> that falls over the moment you need streaming or a retry. Everything a real agent needs — the
> loop, hooks, streaming, retries, memory — and nothing it doesn't.

The JS/TypeScript port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same
library, byte-identical, also in **Python, Go, Java, and C#**. Built on
`@modelcontextprotocol/sdk` (the MCP SDK opencode uses).

## Install

```sh
npm install toolnexus
```

## Quick start

Built-in tools are on by default, so an empty toolkit can already act:

```ts
import { createToolkit, createClient } from "toolnexus"

const tk = await createToolkit()                        // 10 built-in tools, on by default
const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",              // any OpenAI- or Anthropic-style endpoint
  style: "openai",                                      // or "anthropic"
  model: "openai/gpt-4o-mini",
})

const { text } = await agent.run("What files are in this folder?", { toolkit: tk })
console.log(text)
```

`createClient` reads the key from `OPENROUTER_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`
(or pass `apiKey`). The loop runs call → execute tools → feed back → repeat (up to `maxTurns`,
default 10), with hooks, streaming (`agent.stream(...)`), retries/backoff, timeout, and
conversation memory available.

## With MCP + skills

Point the toolkit at an `mcp.json` and a `skills/` folder — every MCP server tool and every
`SKILL.md` becomes part of the same tool set. Skills are injected into the system prompt; a
single `skill` tool loads each on demand (progressive disclosure).

```ts
const tk = await createToolkit({
  mcpConfig: "./mcp.json",     // path or a config object; local stdio + remote streamable-HTTP
  skillsDir: "./skills",       // a folder of <name>/SKILL.md (string or string[])
})

const { text } = await agent.run("Refund order 1234 for the customer.", { toolkit: tk })
console.log(text)
await tk.close()               // disconnect MCP servers
```

## Suspension — login / approval / input (`Pending` / `waitFor`)

Some tools can't finish in one shot: they need an **out-of-band, async resolution** — a human
logs in, approves a trade, types a value. That's one idea, not one feature: *login* is just the
`kind:"authorization"` case. A tool returns **`Pending(request)`**; the client calls your
**`waitFor(request) → answer`**, then **retries the tool** with `ctx.answer`, and the model gets
the real answer. Everything crossing a boundary is plain data, so it works the same across
processes, agents, and restarts — and identically in all five language ports.

```ts
import { authRequired } from "toolnexus"

// a tool that needs login the first time:
defineTool({
  name: "get_balance", description: "…", inputSchema: { type: "object", properties: {} },
  run: (_args, ctx) =>
    authed ? `balance: ₹67,417` : authRequired("https://example.com/login", "Log in to continue"),
})

// the host supplies ONE function — how the link reaches the human is your call:
const agent = createClient({
  baseUrl, style: "openai", model,
  waitFor: async (request) => {          // interactive: open a browser + poll
    open(request.url); await pollUntilLoggedIn(); return { id: request.id, ok: true }
  },
  // headless desk: waitFor = send request.url to your channel + poll
  // another agent: waitFor = forward the request over A2A, await its answer
})
```

Omit `waitFor` for a **durable** host: `run()` doesn't hang — it returns
`{ status: "pending", pending: request }`, so you can deliver the link out-of-band (file, HTTP,
channel) and resume later, even in another process. `stream()` emits a `{ type: "pending", request }`
event so a channel can push the link in real time. Runnable demo: `examples/pending.ts`.

## Conversations / memory

`run()` is stateless — each call starts fresh. To keep a thread's history, use `ask()` with an
`id`, or a `conversation()` object.

**`ask(prompt, { toolkit, id? })`** — with an `id`, the client's `ConversationStore` remembers
the thread: it *loads* that id's transcript, *runs*, *saves* the updated transcript, and returns
the answer. The next `ask` with the same `id` continues it. Without an `id` it's a stateless
one-shot (identical to `run`).

```ts
const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",
  style: "openai",
  model: "openai/gpt-4o-mini",
  // store defaults to InMemoryConversationStore (process lifetime).
})

await agent.ask("My name is Muthu.", { toolkit: tk, id: "user-42" })
const { text } = await agent.ask("What's my name?", { toolkit: tk, id: "user-42" })
console.log(text)   // → "Your name is Muthu."
```

**Persist across processes.** A `ConversationStore` is two methods — implement it for a file, DB,
or Redis and pass it as `store`:

```ts
import type { ConversationStore } from "toolnexus"

interface ConversationStore {
  get(id: string): Promise<any[] | undefined>   // stored transcript for id, or undefined
  save(id: string, messages: any[]): Promise<void>
}

const agent = createClient({ baseUrl, style: "openai", model, store: myFileStore })
```

The default is `InMemoryConversationStore` (also exported). For a quick in-process thread without
ids, `agent.conversation({ toolkit })` returns an object whose `.send(prompt)` retains history
automatically (`.messages`, `.reset()`).

The same `store` powers inbound A2A: `toolkit.serve` keys each peer's turns by the A2A
`contextId` through `client.ask`, so a remote agent's conversation is remembered too (see below).

**Streaming with memory.** The `id` also works while streaming. Pass `on_text` to `ask` to stream
text deltas as they arrive — `ask` still returns the final `RunResult` — or iterate `stream()`
directly. With an `id`, the thread is loaded before the stream and saved on the `done` event.

```ts
// block-style: stream deltas to stdout, still get the RunResult back — remembered under `id`
const res = await agent.ask("Draft a reply.", {
  toolkit: tk,
  id: "user-42",
  on_text: (delta) => process.stdout.write(delta),
})

// iterator: consume text + tool events; `id` makes it stateful (load before, save on `done`)
for await (const ev of agent.stream("And summarise it.", { toolkit: tk, id: "user-42" })) {
  if (ev.type === "text") process.stdout.write(ev.delta)
  else if (ev.type === "done") console.log("\n", ev.result.usage)
}
```

## Observability / metrics

Zero-dependency, two outputs from one internal instrumentation — both opt-in, no cost when unused.

**`onMetric` — a semantic event feed.** Pass it to `createClient` and it receives a readable
`MetricEvent` at each significant point: `{ event: "llm", model, status, ms, promptTokens,
completionTokens }` per model call, `{ event: "tool", tool, source, isError, ms }` per tool call,
and a terminal `{ event: "run", model, turns, toolCalls, totalTokens, ms, error? }` per `run`/`ask`.
Forward it anywhere (statsd, logs, OpenTelemetry) — the library holds no opinion.

```ts
const agent = createClient({
  baseUrl, style: "openai", model,
  onMetric: (ev) => console.log("[metric]", ev.event, JSON.stringify(ev)),
})
```

**`client.metrics()` — built-in Prometheus text.** The same events feed a tiny in-memory registry
that renders the Prometheus text exposition format (no third-party dep). Mount it at `GET /metrics`:

```ts
import { createServer } from "node:http"

createServer((req, res) => {
  if (req.url === "/metrics") {
    res.setHeader("Content-Type", "text/plain; version=0.0.4")
    res.end(agent.metrics())
  } else {
    res.statusCode = 404
    res.end()
  }
}).listen(9090)
```

Series: `toolnexus_llm_requests_total{model,status}`, `toolnexus_llm_tokens_total{type}`,
`toolnexus_tool_calls_total{tool,source,is_error}`, `toolnexus_run_errors_total{model}`, plus the
`toolnexus_llm_request_duration_seconds` and `toolnexus_tool_duration_seconds` histograms. The
rendered text is byte-identical across all five ports; OTLP push is a planned future companion.

## Add your own tools

```ts
import { defineTool, httpTool } from "toolnexus"

tk.register(
  // a plain function → a tool  (run returns a string or a full ToolResult)
  defineTool({
    name: "add",
    description: "Add two numbers",
    inputSchema: {
      type: "object",
      properties: { a: { type: "number" }, b: { type: "number" } },
      required: ["a", "b"],
    },
    run: (args) => `${(args.a as number) + (args.b as number)}`,
  }),
  // a REST endpoint → a tool
  httpTool({
    name: "create_ticket",
    description: "Create a ticket",
    method: "POST",
    url: "https://api.example.com/tickets",
    headers: { Authorization: "Bearer ${API_TOKEN}" },   // ${ENV} expands from process.env, never logged
    inputSchema: {
      type: "object",
      properties: { title: { type: "string" } },
      required: ["title"],
    },
  }),
)
```

For `httpTool`: URL `{placeholders}` are filled from args; `query` arg names go to the
querystring (all args on GET); the rest become the JSON body (`body: "json" | "form" | "raw"`).
Non-2xx → `{ isError: true, output: "HTTP <status>: <body>" }`.

## Built-in tools

A fifth source ships **10 built-in tools** — `bash`, `read`, `write`, `edit`, `grep`, `glob`,
`webfetch`, `question`, `apply_patch`, `todowrite` (names + input schemas match opencode) — so an
agent can act with zero wiring. They appear in the tool schema
(`toOpenAI()`/`toAnthropic()`/`toGemini()`), like MCP tools — not the system prompt.

**On by default.** One global toggle turns the whole source off, or a per-tool `tools` map
disables individual builtins on the all-on baseline:

```ts
const tk = await createToolkit({ builtins: false })
// also accepts { disabled: true } or { enabled: false }

// per-tool: drop bash, keep the other nine (unknown names ignored; whole-source-off still wins)
const tk2 = await createToolkit({ builtins: { tools: { bash: false } } })
```

`bash`/`write`/`edit`/`apply_patch` run commands and mutate the filesystem — the toggle is the
off-switch for locked-down hosts.

## A2A agents (agent-to-agent)

Call **remote A2A agents** (each of their skills becomes a tool) and serve your own toolkit as an
agent other A2A peers can call. A genuine, minimal subset of real A2A (JSON-RPC 2.0; Agent Card at
`/.well-known/agent-card.json`; `SendMessage` → poll `GetTask`). No streaming / push / auth in v1.

**Outbound — call a remote agent.** Each advertised skill becomes a tool named `<agent>_<skill>`
(`source: "a2a"`):

```ts
import { createToolkit, agent } from "toolnexus"

const tk = await createToolkit({
  agents: [agent({ card: "https://researcher.example.com/.well-known/agent-card.json" })],
})

// or add one at runtime (an Agent descriptor or a bare card URL):
await tk.addAgent("https://writer.example.com/.well-known/agent-card.json")
```

`agent({ card, headers?, timeout?, pollEvery? })` — `headers` support `${ENV}` expansion (never
logged); `timeout` defaults to 300000ms, `pollEvery` to 1000ms. A config object can also carry an
`agents` block (mirrors `mcpServers`). A failing agent is isolated — logged, contributes no tools,
never fatal.

**Inbound — serve your toolkit as an agent.** Opt in with the `a2a` profile; the Agent Card is
built from your **SKILL.md skills** (never raw tools):

```ts
const handle = await tk.serve("127.0.0.1:0", {
  client: agent,                  // a createClient(...) instance fulfils each task
  a2a: {
    name: "research-agent",
    description: "Answers research questions.",
    // skills: ["hello-world"],   // subset of skills to advertise; omit ⇒ all
    store: "memory",              // "memory" (default) | "file:<dir>" | a custom TaskStore
  },
})
console.log(handle.url)   // GET /.well-known/agent-card.json ; POST / (SendMessage / GetTask)
await handle.stop()
```

`serve(addr, { client, a2a?, onTask? })` fulfils each inbound `SendMessage` task via the client.
Task persistence is a pluggable `TaskStore` — in-memory default, `"file:<dir>"`, or your own. And
it **remembers**: a message's A2A `contextId` keys the conversation through `client.ask`, so a
peer's turns in the same context carry history via the client's `ConversationStore`.

## Serve as an MCP server (be a gateway)

The inbound mirror of A2A: expose your **whole toolkit as an MCP server** so any MCP client — an IDE,
another agent, a remote host — can call its tools. Point toolnexus at N MCP servers + skills + your
own functions, then re-expose the union as **one** MCP server. Unlike A2A (which advertises skills and
runs the client loop), the MCP client *is* the LLM host, so each `tools/call` dispatches straight to
`Tool.execute` — no `client`, no tasks, no store.

```ts
// streamable-HTTP — an embeddable MCP server mounted at POST /mcp, beside any A2A routes:
const srv = await tk.serve("127.0.0.1:0", {
  mcp: { name: "my-gateway" /*, tools: ["echo"]  // subset; omit ⇒ every toolkit tool */ },
  onCall: (ev) => console.error(ev.name, ev.ms, ev.isError),
})
console.log(srv.url + "/mcp")   // connect any MCP client here
await srv.stop()
```

`tools/list` advertises every toolkit tool (name **verbatim**, `inputSchema` = the tool's parameters);
`mcp.tools` narrows the surface. The `mcp` profile can also live in the config file as a top-level
**`mcpServer`** block (singular — distinct from the client-side `mcpServers`). (Transport is
streamable-HTTP; a stdio transport for local clients like Claude Desktop is a planned follow-up.)

## Bring your own loop

Don't want the host loop? Use the schema adapters and execute calls yourself:

```ts
const tools  = tk.toOpenAI()       // or tk.toAnthropic() / tk.toGemini()
const system = tk.skillsPrompt()   // skills catalog for your system prompt
// when the model returns a tool call { name, arguments }:
const res = await tk.execute(name, args)   // → { output, isError, metadata }
```

## The four sources

| Source | How |
|--------|-----|
| **MCP servers** | an `mcp.json` (`mcpServers`/`servers`/`mcp`); local stdio + remote streamable-HTTP, `headers` for auth |
| **Agent skills** | a folder of `<name>/SKILL.md`; a `skill` tool loads each on demand + a system-prompt catalog |
| **Native tools** | `defineTool({...})` — a function becomes a tool |
| **HTTP / REST** | `httpTool({...})` — an endpoint becomes a tool, `${ENV}` headers |

All appear as one uniform `Tool` in `tk.tools()`. Built-ins are a fifth source; remote A2A agents
a sixth.

## API

| Member | Description |
|--------|-------------|
| `createToolkit(opts)` | async factory → `Toolkit` (`mcpConfig`, `skillsDir`, `builtins`, `agents`, `extraTools`) |
| `createClient(opts)` | the unified host loop (`baseUrl`, `style`, `model`, `store?`, `hooks?`, `maxTurns?`, `retries?`, `timeoutMs?`, `onMetric?`) |
| `client.run(prompt, { toolkit, signal?, history? })` | stateless run → `RunResult` |
| `client.ask(prompt, { toolkit, id?, on_text?, signal? })` | stateful with `id` (loads → runs → saves via `store`); one-shot without `id`; `on_text` streams text deltas |
| `client.conversation({ toolkit })` | in-process multi-turn object — `.send(prompt)`, `.messages`, `.reset()` |
| `client.stream(prompt, { toolkit, id? })` | async-iterate `StreamEvent`s (text deltas, tool calls/results, usage, done); `id` ⇒ stateful |
| `client.metrics()` | Prometheus text exposition of cumulative metrics — mount at `GET /metrics` |
| `ConversationStore` | `get(id)` / `save(id, messages)` — implement for file/db; default `InMemoryConversationStore` |
| `tk.tools()` / `tk.get(name)` | the uniform tools |
| `tk.register(...tools)` | add native/http/custom tools at runtime |
| `tk.execute(name, args, ctx?)` | run a tool → `ToolResult` |
| `tk.skillsPrompt()` | system-prompt skill catalog |
| `tk.mcpStatus()` | per-server connection status |
| `tk.toOpenAI()` / `toAnthropic()` / `toGemini()` | provider tool schemas |
| `tk.serve(addr, { client?, a2a?, onTask?, mcp?, onCall? })` | serve the toolkit over HTTP — A2A agent and/or streamable-HTTP MCP server (`/mcp`) → `ServeHandle` |
| `tk.addAgent(cardUrl \| Agent)` | fetch a remote agent's card and register its skills as tools |
| `tk.close()` | disconnect MCP servers |
| `defineTool` / `httpTool` / `agent` | build native / HTTP / A2A tools |
| `InMemoryConversationStore` · `InMemoryTaskStore` / `FileTaskStore` | default stores (memory / A2A tasks) |

## More

Full docs, the other four language ports, and the shared cross-language behavior contract
([`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md)) — plus runnable
`examples/`: **https://github.com/muthuishere/toolnexus**

MIT licensed.
