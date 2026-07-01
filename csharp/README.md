# toolnexus

[![NuGet](https://img.shields.io/nuget/v/Toolnexus?logo=nuget&label=NuGet)](https://www.nuget.org/packages/Toolnexus)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `RunAsync()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework (no builders, no config to wade through), not a toy that falls
> over the moment you need streaming or a retry. Everything a real agent needs — the loop, hooks,
> streaming, retries, memory — and nothing it doesn't.

The C#/.NET port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, and Java**. Built on the official
`ModelContextProtocol` SDK. Targets .NET 10.

## Install

```sh
dotnet add package Toolnexus
```

## An agent in 3 steps

```csharp
using Toolnexus;

// 1. tools from an mcp.json + a skills/ folder
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options()
    .WithMcpConfig("./mcp.json")
    .WithSkillsDir("./skills"));

// 2. point at any OpenAI- or Anthropic-style endpoint
var agent = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = "https://openrouter.ai/api/v1",
    Style   = "openai",                 // or "anthropic"
    Model   = "openai/gpt-4o-mini",
    ApiKey  = Environment.GetEnvironmentVariable("OPENROUTER_API_KEY"),
});

// 3. run — skills injected into the system prompt, tools called for you, looped to an answer
var res = await agent.RunAsync("Refund order 1234 for the customer.", tk);
Console.WriteLine(res.Text);
```

The loop runs call → execute tools → feed back → repeat, with hooks, streaming, retries/backoff,
and conversation memory available. `res` carries `Text`, `ToolCalls`, usage, turns, and model.

## Add your own tools

```csharp
using Toolnexus;

// a method → a tool (attribute-based)
public sealed class MathTools
{
    [ToolMethod("add", "Add two numbers")]
    public string Add([Param("a")] double a, [Param("b")] double b) => (a + b).ToString();
}

tk.Register(Tools.FromObject(new MathTools()).ToArray());

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

You can also pass `ExtraTools` / `AnnotatedObjects` straight into `Toolkit.Options`.

## Bring your own loop

```csharp
var tools  = tk.ToOpenAI();      // or tk.ToAnthropic() / tk.ToGemini()
var system = tk.SkillsPrompt();  // skills catalog for your system prompt
// when the model returns a tool call (name, arguments):
var res = await tk.ExecuteAsync(name, args);   // -> ToolResult(Output, IsError, Metadata)
```

## The four sources

| Source | How |
|--------|-----|
| **MCP servers** | an `mcp.json` (`mcpServers`/`servers`/`mcp`); local stdio + remote streamable-HTTP, `Headers` for auth |
| **Agent skills** | a folder of `<name>/SKILL.md`; a `skill` tool loads each on demand + a system-prompt catalog |
| **Native tools** | `[ToolMethod]`/`[Param]` on a class (`Tools.FromObject`), or `NativeTool.Of(...)` |
| **HTTP / REST** | `HttpTool.Of(...)` — an endpoint becomes a tool, `${ENV}` headers |

All four appear as one uniform `ITool` in `tk.Tools()`.

## Built-in tools

A fifth source ships **10 built-in tools** — `bash`, `read`, `write`, `edit`, `grep`, `glob`,
`webfetch`, `question`, `apply_patch`, `todowrite` (names + input schemas match
opencode) — so an agent can act with zero wiring. They appear in the tool schema
(`ToOpenAI()`/`ToAnthropic()`/`ToGemini()`), like MCP tools — not the system prompt.

**On by default.** One global toggle turns the whole source off, or a per-tool `tools` map
disables individual builtins on the all-on baseline:

```csharp
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options()
    .WithMcpConfig("./mcp.json")
    .WithBuiltins(false));   // also accepts a Dictionary with "disabled"/"enabled"

// per-tool: drop bash, keep the other nine (unknown names ignored; whole-source-off still wins)
await using var tk2 = await Toolkit.CreateAsync(new Toolkit.Options()
    .WithMcpConfig("./mcp.json")
    .WithBuiltins(new Dictionary<string, object?>
    {
        ["tools"] = new Dictionary<string, object?> { ["bash"] = false },
    }));
```

`bash`/`write`/`edit`/`apply_patch` run commands and mutate the filesystem — these switches are
the off-switch for locked-down hosts.

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
    BaseUrl = "https://openrouter.ai/api/v1", Style = "openai", Model = "openai/gpt-4o-mini",
});

var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
{
    Client = agent,
    A2A = new A2AConfig
    {
        Name = "research-agent",
        Description = "Answers research questions.",
        // Skills = new List<string> { "hello-world" }, // subset of skills to advertise; null ⇒ all
        Store = "memory",                                // "memory" (default) | "file:<dir>" | a custom ITaskStore
    },
});
Console.WriteLine(handle.Url);   // GET /.well-known/agent-card.json ; POST / (SendMessage / GetTask)
await handle.StopAsync();
```

`ServeAsync(addr, ServeOptions)` fulfils each inbound `SendMessage` task via `client.Run`. Task
persistence is a pluggable `ITaskStore` — in-memory default, `"file:<dir>"`, or your own.

## API

| Member | Description |
|--------|-------------|
| `Toolkit.CreateAsync(opts)` | async factory → `Toolkit` (`await using`) |
| `LlmClient.Create(opts)` | the unified host loop (`RunAsync` / `StreamAsync`) |
| `tk.Tools()` | the uniform tools |
| `tk.ExecuteAsync(name, args, ctx?)` | run a tool → `ToolResult` |
| `tk.SkillsPrompt()` | system-prompt skill catalog |
| `tk.ToOpenAI()` / `ToAnthropic()` / `ToGemini()` | provider tool schemas |
| `tk.Register(params ITool[])` | add native/http/custom tools |

## More

Full docs, the other four language ports, the shared behavior spec, and runnable examples:
**https://github.com/muthuishere/toolnexus**

MIT licensed.
