# toolnexus

[![NuGet](https://img.shields.io/nuget/v/Toolnexus?logo=nuget&label=NuGet)](https://www.nuget.org/packages/Toolnexus)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Give a toolkit some tools — built-ins, an `mcp.json`, a
`skills/` folder, your own C# methods, HTTP endpoints, other agents — point a client at any LLM,
call `RunAsync()`, and the tool-calling loop runs to an answer. Every source is unified behind one
`ITool` interface and emitted in **OpenAI / Anthropic / Gemini** schema.

> **Right-sized.** Not a framework (no builders to learn, no config to wade through), not a toy
> that falls over the moment you need streaming or a retry. Everything a real agent needs — the
> loop, hooks, streaming, retries, conversation memory — and nothing it doesn't.

The C#/.NET port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, and Java**. Built on the official
`ModelContextProtocol` SDK. Targets .NET 10.

## Install

```sh
dotnet add package Toolnexus
```

## Quick start — an agent in 3 steps

```csharp
using Toolnexus;

// 1. a toolkit — the 10 built-in tools (bash/read/write/edit/grep/glob/…) are on by default
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());

// 2. a client — point at any OpenAI- or Anthropic-style endpoint
var agent = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = "https://openrouter.ai/api/v1",
    Style   = "openai",                       // or "anthropic"
    Model   = "anthropic/claude-3.5-sonnet",
    ApiKey  = Environment.GetEnvironmentVariable("OPENROUTER_API_KEY"),
});

// 3. run — tools called for you, looped to a final answer
var res = await agent.RunAsync("List the files in the current folder, then summarise the README.", tk);
Console.WriteLine(res.Text);
```

The loop runs call → execute tools → feed results back → repeat, with hooks, streaming, and
retries/backoff available. `res` is a `RunResult` carrying `Text`, `Messages`, `ToolCalls`
(+ `ToolCallCount`), `Turns`, `Usage`, and `Model`.

> **API key.** `ApiKey` is optional — when unset the client reads `OPENROUTER_API_KEY`, then
> `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` from the environment. Keys are use-only; never bake one
> into code.

## With MCP servers + agent skills

Point the toolkit at an `mcp.json` (local stdio **and** remote streamable-HTTP servers) and a
folder of `<name>/SKILL.md` skills. Every MCP tool and the `skill` tool join the same tool set.

```csharp
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options()
    .WithMcpConfig("./mcp.json")     // path, raw JSON string, or a parsed config dict
    .WithSkillsDir("./skills"));     // one or more skill roots

var res = await agent.RunAsync("Refund order 1234 for the customer.", tk);
```

Skills are injected into the system prompt as a catalog; the `skill` tool loads a skill's full
instructions + resources **on demand** (progressive disclosure). A failing MCP server is isolated —
it contributes no tools and never breaks the toolkit.

## Conversations / memory

`RunAsync` is stateless — each call starts fresh. For a remembered, multi-turn conversation use
**`AskAsync`** with a stable `id`: the client loads that id's transcript from its conversation
store, runs, saves the updated transcript, and returns the answer — so the next `AskAsync` with the
same id continues where it left off.

```csharp
var user = "user-42";

var a = await agent.AskAsync("My name is Muthu and I love Go.", tk, user);
var b = await agent.AskAsync("What's my name and favourite language?", tk, user);
Console.WriteLine(b.Text);   // "Your name is Muthu and you love Go."

// same overload, no id  →  stateless one-shot (identical to RunAsync)
var once = await agent.AskAsync("Unrelated question.", tk);
```

```csharp
public async Task<RunResult> AskAsync(
    string prompt, Toolkit toolkit, string? id = null,
    Action<string>? onText = null, CancellationToken cancellationToken = default);
```

- **Non-null `id`** — remembers via the store: `store.GetAsync(id)` → `RunAsync(prompt, toolkit, history)` → `store.SaveAsync(id, result.Messages)`.
- **Null `id`** — stateless, exactly equivalent to `RunAsync`.
- **`onText`** — a block-style streaming sink: when set, the streaming loop runs and each assistant text delta is forwarded as it arrives; `AskAsync` still returns the final `RunResult`.

### Where transcripts live

The store comes from the `Store` option on `LlmClient.Options`, defaulting to
`InMemoryConversationStore` (kept for the client's lifetime). Implement `IConversationStore` to
persist across processes (file, database, Redis):

```csharp
public interface IConversationStore
{
    Task<List<object?>?> GetAsync(string id);        // stored transcript, or null
    Task SaveAsync(string id, List<object?> messages); // persist the updated transcript
}

var agent = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = "https://openrouter.ai/api/v1",
    Model   = "anthropic/claude-3.5-sonnet",
    Store   = new MyFileConversationStore("./conversations"),  // your IConversationStore
});
```

The same store powers **inbound A2A** (below): when a peer calls your served agent, its A2A
`contextId` becomes the conversation id, so a peer's turns are remembered through this store.

### Streaming with memory

The `id` also works while streaming. Pass `onText` to `AskAsync` to stream text deltas as they
arrive — `AskAsync` still returns the final `RunResult` — or use `StreamAsync(prompt, tk, onEvent,
id)` for the full event stream. With an `id`, the thread is loaded before the stream and saved once
the run terminates.

```csharp
// block-style: stream deltas to the console, still get the RunResult — remembered under "user-42"
var r = await agent.AskAsync("Draft a reply.", tk, "user-42",
    onText: delta => Console.Write(delta));

// event stream with memory: id ⇒ load before, save at the end
await agent.StreamAsync("And summarise it.", tk, ev =>
{
    if (ev.Type == LlmClient.StreamKind.Text) Console.Write(ev.Delta);
    else if (ev.Type == LlmClient.StreamKind.Done) Console.WriteLine($"\n{ev.Result!.Usage}");
}, "user-42");
```

## Observability / metrics

Zero-dependency, two outputs from one internal instrumentation — both opt-in, no cost when unused.

**`OnMetric` — a semantic event feed.** Set `OnMetric` on `LlmClient.Options` and it receives a
readable `MetricEvent` (a record) at each significant point: `Event == "llm"` (`Model`, `Status`,
`Ms`, `PromptTokens`, `CompletionTokens`) per model call, `Event == "tool"` (`Tool`, `Source`,
`IsError`, `Ms`) per tool call, and a terminal `Event == "run"` (`Model`, `Turns`, `ToolCalls`,
`TotalTokens`, `Ms`, `Error`) per run/ask. Forward it anywhere (statsd, logs, OpenTelemetry).

```csharp
var agent = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = baseUrl, Style = "openai", Model = model,
    OnMetric = ev => Console.WriteLine($"[metric] {ev.Event} {ev}"),
});
```

**`agent.Metrics()` — built-in Prometheus text.** The same events feed a tiny in-memory registry
that renders the Prometheus text exposition format (no third-party dep). Mount it at `GET /metrics`:

```csharp
// ASP.NET Core minimal API
app.MapGet("/metrics", () =>
    Results.Text(agent.Metrics(), "text/plain; version=0.0.4"));
```

Series: `toolnexus_llm_requests_total{model,status}`, `toolnexus_llm_tokens_total{type}`,
`toolnexus_tool_calls_total{tool,source,is_error}`, `toolnexus_run_errors_total{model}`, plus the
`toolnexus_llm_request_duration_seconds` and `toolnexus_tool_duration_seconds` histograms. The
rendered text is byte-identical across all five ports; OTLP push is a planned future companion.

## Add your own tools

```csharp
using Toolnexus;

// a C# method → a tool (attribute-based)
public sealed class MathTools
{
    [ToolMethod("add", "Add two numbers")]
    public string Add([Param("a")] double a, [Param("b")] double b) => (a + b).ToString();
}

tk.Register(Tools.FromObject(new MathTools()).ToArray());

// a plain function → a tool (no class needed)
tk.Register(NativeTool.Of("upper", "Uppercase a string",
    new Dictionary<string, object?>
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["s"] = new Dictionary<string, object?> { ["type"] = "string" } },
        ["required"] = new List<object?> { "s" },
    },
    args => (args.TryGetValue("s", out var s) ? s?.ToString() ?? "" : "").ToUpperInvariant()));

// a REST endpoint → a tool
tk.Register(HttpTool.Of(new HttpTool.Options
{
    Name = "create_ticket", Description = "Create a ticket", Method = "POST",
    Url = "https://api.example.com/tickets",
    Headers = new Dictionary<string, string> { ["Authorization"] = "Bearer ${API_TOKEN}" }, // ${ENV} expands, never logged
    InputSchema = new Dictionary<string, object?>
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["title"] = new Dictionary<string, object?> { ["type"] = "string" } },
        ["required"] = new List<object?> { "title" },
    },
}));
```

`Register(params ITool[])` is chainable and first-name-wins (a duplicate name is warned and
dropped). You can also pass `ExtraTools` / `AnnotatedObjects` straight into `Toolkit.Options`.

### The tool sources at a glance

| Source | How |
|--------|-----|
| **Built-in** | 10 tools, on by default — see below |
| **MCP servers** | an `mcp.json` (`mcpServers`/`servers`/`mcp`); local stdio + remote streamable-HTTP, `Headers` for auth |
| **Agent skills** | a folder of `<name>/SKILL.md`; a `skill` tool loads each on demand + a system-prompt catalog |
| **Native tools** | `[ToolMethod]`/`[Param]` on a class (`Tools.FromObject`), or `NativeTool.Of(...)` |
| **HTTP / REST** | `HttpTool.Of(...)` — an endpoint becomes a tool, `${ENV}` headers |
| **A2A agents** | remote agents whose skills become tools (below) |

All of them appear as one uniform `ITool` in `tk.Tools()`.

## Built-in tools

toolnexus ships **10 built-in tools** — `bash`, `read`, `write`, `edit`, `grep`, `glob`,
`webfetch`, `question`, `apply_patch`, `todowrite` (names + input schemas match
[opencode](https://github.com/anomalyco/opencode)) — so an agent can act with zero wiring. They
appear in the tool schema (`ToOpenAI()`/`ToAnthropic()`/`ToGemini()`), like MCP tools — not the
system prompt.

**On by default.** `WithBuiltins(false)` turns the whole source off; a per-tool map disables
individual builtins on the all-on baseline:

```csharp
// whole source off (for a locked-down host)
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options()
    .WithBuiltins(false));   // also accepts { ["disabled"] = true } / { ["enabled"] = false }

// per-tool: drop bash, keep the other nine (unknown names ignored; whole-source-off still wins)
await using var tk2 = await Toolkit.CreateAsync(new Toolkit.Options()
    .WithBuiltins(new Dictionary<string, object?>
    {
        ["tools"] = new Dictionary<string, object?> { ["bash"] = false },
    }));
```

`bash`/`write`/`edit`/`apply_patch` run commands and mutate the filesystem — these switches are the
off-switch for locked-down hosts.

## A2A agents (agent-to-agent)

Call **remote A2A agents** (each of their skills becomes a tool) and serve your own toolkit as an
agent other A2A peers can call. A genuine, minimal subset of real A2A (JSON-RPC 2.0; Agent Card at
`/.well-known/agent-card.json`; `SendMessage` → poll `GetTask`). No streaming / push / auth in v1.

**Outbound — call a remote agent.** Each advertised skill becomes a tool named `<agent>_<skill>`
(source `"a2a"`):

```csharp
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options()
    .WithAgents(new Agent { Card = "https://researcher.example.com/.well-known/agent-card.json" }));

// or add one at runtime (an Agent, or a bare card URL):
await tk.AddAgentAsync("https://writer.example.com/.well-known/agent-card.json");
```

`new Agent { Card, Headers?, Timeout?, PollEvery? }` — `Headers` support `${ENV}` expansion (never
logged); `Timeout` / `PollEvery` are milliseconds (300000 / 1000 defaults). A config file can also
carry an `agents` block (mirrors `mcpServers`). A failing agent is isolated — contributes no tools,
never fatal.

**Inbound — serve your toolkit as an agent.** The Agent Card is built from your **SKILL.md skills**
(never raw tools):

```csharp
var agent = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = "https://openrouter.ai/api/v1", Style = "openai", Model = "anthropic/claude-3.5-sonnet",
});

var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
{
    Client = agent,
    A2A = new A2AConfig
    {
        Name = "research-agent",
        Description = "Answers research questions.",
        // Skills = new List<string> { "hello-world" }, // subset of skills to advertise; null ⇒ all
        Store = "memory",                                // task store: "memory" | "file:<dir>" | a custom ITaskStore
    },
});
Console.WriteLine(handle.Url);   // GET /.well-known/agent-card.json ; POST / (SendMessage / GetTask)
await handle.StopAsync();
```

Each inbound `SendMessage` is fulfilled by the client. A message's A2A `contextId` keys the
conversation via `client.AskAsync`, so a peer's turns are **remembered** through the client's
`IConversationStore` (a message with no `contextId` is a stateless run). Task lifecycle persistence
is a separate, pluggable `ITaskStore` — in-memory default, `"file:<dir>"`, or your own.

## Serve as an MCP server (be a gateway)

The inbound mirror of A2A: expose your **whole toolkit as an MCP server** so any MCP client — an IDE,
another agent, a remote host — can call its tools. Point toolnexus at N MCP servers + skills + your
own functions, then re-expose the union as **one** MCP server. Unlike A2A, the MCP client *is* the LLM
host, so each `tools/call` dispatches straight to the tool's `ExecuteAsync` — no client, no tasks, no store.

```csharp
// streamable-HTTP — an embeddable MCP server mounted at POST /mcp, beside any A2A routes:
var srv = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
{
    Mcp = new MCPServeConfig { Name = "my-gateway" },   // optional Tools = ["echo"] subset; null ⇒ all
    OnCall = ev => { Console.Error.WriteLine($"{ev.Name} {ev.Ms} {ev.IsError}"); return Task.CompletedTask; },
});
Console.WriteLine(srv.Url + "/mcp");   // connect any MCP client here
await srv.StopAsync();
```

`tools/list` advertises every toolkit tool (name **verbatim**, `inputSchema` = the tool's parameters);
`Mcp.Tools` narrows the surface. The profile can also live in the config file as a top-level
**`mcpServer`** block (singular — distinct from the client-side `mcpServers`). (Transport is
streamable-HTTP; a stdio transport for local clients like Claude Desktop is a planned follow-up.)

## Bring your own loop

Don't want the built-in loop? Emit the schema for your provider, run your own calls, and let the
toolkit execute the tool the model picks:

```csharp
var tools  = tk.ToOpenAI();      // or tk.ToAnthropic() / tk.ToGemini()
var system = tk.SkillsPrompt();  // skills catalog for your system prompt

// when the model returns a tool call (name, arguments):
var res = await tk.ExecuteAsync(name, args);   // -> ToolResult(Output, IsError, Metadata)
```

## Sub-agents & teams

An **Agent is a Tool**: a system prompt × a scoped toolkit view × the client loop. One agent
delegates to another **in-process** via one `task` tool — isolated context, one result back,
tokens rolled up, hierarchical budgets, durable suspension (`SPEC.md §7D`). Lives in the
`Toolnexus.Agents` namespace (never the A2A `Agent`):

```csharp
using Toolnexus.Agents;

var explore = new Agent("explore", new AgentSpec
{
    Does = "read-only research",
    Uses = new List<ITool> { lookup },
});
var coder = new Agent("coder", new AgentSpec
{
    Does = "implements changes",
    SoulFile = "./AGENTS.md",
    Team = new List<Agent> { explore },   // team = the task tool's only targets; no team ⇒ no task tool
    Budget = new Budget { MaxTokens = 10_000 },
});

var r = await coder.RunAsync(new RuntimeOptions
{
    BaseUrl = "https://openrouter.ai/api/v1",
    Style = "openai",
    Model = "openai/gpt-4o-mini",
}, "fix the failing test");
Console.WriteLine($"{r.Status} {r.Text} {r.TotalTokens}");
```

Full guide: [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/).

## API

| Member | Description |
|--------|-------------|
| `Toolkit.CreateAsync(opts)` | async factory → `Toolkit` (`await using`) |
| `LlmClient.Create(opts)` | the unified host loop |
| `agent.RunAsync(prompt, tk, history?, ct?)` | stateless run → `RunResult` |
| `agent.AskAsync(prompt, tk, id?, onText?, ct?)` | remembered run when `id` is set (via `IConversationStore`); stateless when `id` is null; `onText` streams text deltas |
| `agent.StreamAsync(prompt, tk, onEvent, id?, ct?)` | the loop with streaming events; `id` ⇒ stateful (load before, save at end) |
| `agent.Metrics()` | Prometheus text exposition of cumulative metrics — mount at `GET /metrics` |
| `tk.ServeAsync(addr, serveOpts)` | serve the toolkit as an A2A agent → `ServeHandle` |
| `tk.Register(params ITool[])` | add native/http/custom tools (chainable) |
| `tk.AddAgentAsync(agent \| cardUrl)` | register a remote A2A agent's skills at runtime |
| `tk.Tools()` / `tk.Get(name)` | the uniform tools |
| `tk.ExecuteAsync(name, args, ctx?)` | run a tool → `ToolResult` |
| `tk.SkillsPrompt()` | system-prompt skill catalog |
| `tk.ToOpenAI()` / `ToAnthropic()` / `ToGemini()` | provider tool schemas |
| `IConversationStore` | `GetAsync(id)` / `SaveAsync(id, messages)` — implement for file/db memory |

## More

Full docs, the other four language ports, the shared behavior spec, and runnable examples:
**https://github.com/muthuishere/toolnexus**

MIT licensed.
</content>
</invoke>
