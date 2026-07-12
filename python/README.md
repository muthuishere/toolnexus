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

## Quick start — a working agent in 5 lines

No `mcp.json`, no skills folder. The **10 built-in tools** (`bash`, `read`, `grep`, `webfetch`, …)
are on by default, so the model can actually *do* things right away:

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

```sh
export OPENROUTER_API_KEY=...      # or OPENAI_API_KEY / ANTHROPIC_API_KEY
```

`create_client` reads the key from `OPENROUTER_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`
(no `api_key=` needed).

## With MCP servers + skills

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

The `Toolkit` is also an async context manager (`async with await create_toolkit(...) as tk:`)
if you'd rather not call `close()` yourself.

## Conversations / memory

`run()` is stateless — each call starts fresh. For a multi-turn thread that *remembers*, use
`ask(prompt, tk, id=...)`. Give it an `id` and the client's **`ConversationStore`** does the work:
load that thread's transcript → run → save the updated transcript. The next `ask` with the same
`id` continues where it left off. Call `ask` **without** an `id` and it's a stateless one-shot —
identical to `run`.

```python
agent = create_client(base_url="https://openrouter.ai/api/v1", style="openai",
                      model="openai/gpt-4o-mini")

await agent.ask("I trade NIFTY.", tk, id="trader-42")
res = await agent.ask("What do I trade?", tk, id="trader-42")
print(res.text)   # -> "NIFTY" — the second turn remembers the first
```

Every client has a store — by default an in-memory `InMemoryConversationStore` that lives as long
as the client. To persist across processes (a file, a DB, Redis), pass your own to `create_client`:

```python
from toolnexus import create_client, ConversationStore

class FileStore:                                  # implements ConversationStore
    async def get(self, id):                      # -> list[messages] | None
        ...
    async def save(self, id, messages):           # persist the updated transcript
        ...

agent = create_client(base_url=..., style="openai", model=..., store=FileStore())
```

`ConversationStore` is just two async methods — `get(id)` and `save(id, messages)`. The A2A
`serve` side uses the same store: an inbound peer's turns are keyed by their A2A `contextId`, so a
served agent remembers a caller across tasks (see [A2A agents](#a2a-agents-agent-to-agent)).

**Streaming with memory.** The `id` also works while streaming. Pass `on_text` (a sync- or
async-callable) to `ask` to stream text deltas as they arrive — `ask` still returns the final
`RunResult` — or iterate `stream()` directly. With an `id`, the thread is loaded before the stream
and saved on the terminal `done` event.

```python
# block-style: stream deltas, still get the RunResult back — remembered under `id`
res = await agent.ask("Draft a reply.", tk, id="trader-42",
                      on_text=lambda delta: print(delta, end="", flush=True))

# async iterator: consume text + tool events; `id` makes it stateful (load before, save on done)
async for ev in agent.stream("And summarise it.", tk, id="trader-42"):
    if ev["type"] == "text":
        print(ev["delta"], end="", flush=True)
    elif ev["type"] == "done":
        print("\n", ev["result"].usage)
```

## Observability / metrics

Zero-dependency, two outputs from one internal instrumentation — both opt-in, no cost when unused.

**`on_metric` — a semantic event feed.** Pass it to `create_client` and it receives a readable,
snake_case dict at each significant point: `{"event": "llm", "model", "status", "ms",
"prompt_tokens", "completion_tokens"}` per model call, `{"event": "tool", "tool", "source",
"is_error", "ms"}` per tool call, and a terminal `{"event": "run", "model", "turns", "tool_calls",
"total_tokens", "ms", "error"?}` per `run`/`ask`. Forward it anywhere (statsd, logs, OpenTelemetry).

```python
agent = create_client(
    base_url=..., style="openai", model=...,
    on_metric=lambda ev: print("[metric]", ev["event"], ev),
)
```

**`agent.metrics()` — built-in Prometheus text.** The same events feed a tiny in-memory registry
that renders the Prometheus text exposition format (no third-party dep). Mount it at `GET /metrics`:

```python
from http.server import BaseHTTPRequestHandler, HTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/metrics":
            body = agent.metrics().encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

HTTPServer(("", 9090), Handler).serve_forever()
```

Series: `toolnexus_llm_requests_total{model,status}`, `toolnexus_llm_tokens_total{type}`,
`toolnexus_tool_calls_total{tool,source,is_error}`, `toolnexus_run_errors_total{model}`, plus the
`toolnexus_llm_request_duration_seconds` and `toolnexus_tool_duration_seconds` histograms. The
rendered text is byte-identical across all five ports; OTLP push is a planned future companion.

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

`serve(addr, *, client, a2a=None, on_task=None)` fulfils each inbound task through the client: a
message carrying an A2A `contextId` goes through `client.ask(..., id=contextId)`, so a peer's turns
are **remembered** across tasks via the client's `ConversationStore`; without a `contextId` it's a
stateless `client.run`. Task persistence is a separate pluggable `TaskStore` (in-memory default,
`"file:<dir>"`, or your own).

## Serve as an MCP server (be a gateway)

The inbound mirror of A2A: expose your **whole toolkit as an MCP server** so any MCP client — an IDE,
another agent, a remote host — can call its tools. Point toolnexus at N MCP servers + skills + your
own functions, then re-expose the union as **one** MCP server. Unlike A2A, the MCP client *is* the LLM
host, so each `tools/call` dispatches straight to the tool's `execute` — no client, no tasks, no store.

```python
# streamable-HTTP — an embeddable MCP server mounted at POST /mcp, beside any A2A routes:
srv = await tk.serve(
    "127.0.0.1:0",
    mcp={"name": "my-gateway"},   # optional "tools": ["echo"] subset; omit ⇒ every toolkit tool
    on_call=lambda ev: print(ev["name"], ev["ms"], ev["is_error"]),
)
print(srv.url + "/mcp")   # connect any MCP client here
await srv.stop()
```

`tools/list` advertises every toolkit tool (name **verbatim**, `inputSchema` = the tool's parameters);
`mcp["tools"]` narrows the surface. The `mcp` profile can also live in the config file as a top-level
**`mcpServer`** block (singular — distinct from the client-side `mcpServers`). (Transport is
streamable-HTTP; a stdio transport for local clients like Claude Desktop is a planned follow-up.)

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
| **Native tools** | `define_tool(fn)` — a function becomes a tool (usable directly or as a decorator) |
| **HTTP / REST** | `http_tool(...)` — an endpoint becomes a tool, `${ENV}` headers |

All four appear as one uniform `Tool` in `tk.tools()`, with `source` in `"mcp" | "skill" | "custom"`.

## API

| Python | Description |
|--------|-------------|
| `await create_toolkit(...)` | async factory → `Toolkit` |
| `create_client(..., store=?, on_metric=?)` | the unified host loop; `store` is the `ConversationStore` (default in-memory); `on_metric` is the metric-event sink |
| `await agent.run(prompt, tk)` | one stateless agent loop → `RunResult(text, messages, tool_calls, usage, …)` |
| `await agent.ask(prompt, tk, *, id=None, on_text=None)` | with `id`: remembers the thread via `store` (get → run → save); without: one-shot (= `run`); `on_text` streams text deltas |
| `agent.stream(prompt, tk, *, id=None)` | streaming variant — async-iterate text/tool/usage/done events; `id` ⇒ stateful |
| `agent.metrics()` | Prometheus text exposition of cumulative metrics — mount at `GET /metrics` |
| `ConversationStore` / `InMemoryConversationStore` | `async get(id)` / `async save(id, messages)` — implement for file/db; in-memory default |
| `tk.tools()` / `tk.get(name)` | the uniform tools |
| `await tk.execute(name, args, ctx=None)` | run a tool → `ToolResult` |
| `tk.skills_prompt()` | system-prompt skill catalog |
| `tk.mcp_status()` | per-server connection status |
| `tk.to_openai()` / `to_anthropic()` / `to_gemini()` | provider tool schemas |
| `tk.register(*tools)` | add native/http/custom tools |
| `await tk.serve(addr, client=…, a2a=…)` | serve the toolkit as an A2A agent |
| `await tk.close()` | disconnect MCP servers |

## More

Full docs, the other four language ports, the shared behavior spec, and runnable examples:
**https://github.com/muthuishere/toolnexus**

MIT licensed.
