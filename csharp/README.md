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
