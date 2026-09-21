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
byte-identical, also in **JavaScript, Python, Go, Java, Elixir and Clojure**. Built on the
official `ModelContextProtocol` SDK. Targets .NET 10.

## Install

```sh
dotnet add package Toolnexus
```

## Quick start

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

Add real tool sources by pointing at them:

```csharp
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
{
    McpConfig = "mcp.json",
    SkillsDir = new[] { "skills" },
});
```

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | [C#](https://muthuishere.github.io/toolnexus/api/csharp/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all seven ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
