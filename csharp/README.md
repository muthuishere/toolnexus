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
byte-identical, also in **JavaScript, Python, Go, Java, Elixir, Clojure and Rust**. Built on the
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

## Simple judgments

A thin layer over `Classifier` (SPEC §8B): an ordered question list, a state, bands, and a gate
that escalates (a §10 `input` Request) instead of deciding when unsure. The wire is unchanged.

```csharp
var qs = new[] {
    Judge.Noul("is_appropriate", "Does `message_received` contain inappropriate language?"),
    Judge.Noul("does_this_help", "Does `message_received` help donkey kong win?"),
};
var state = Judge.State("You are Donkey Kong, you want to win.",
    new Dictionary<string, object?> { ["message_received"] = "jump off the stage now" });
var a = await Judge.AskAsync(classifier, state, qs);          // bands default 0.30 / 0.70
a["is_appropriate"].Band;                                      // "yes" | "no" | "uncertain"

var o = await Judge.GateAsync(classifier, state, qs, new Policy {
    Rules = new[] { Rule.Below("does_this_help", 0.3, "ignore") }, Default = "obey" });
// o.Escalated => o.Request is a §10 input Request

var ds = await classifier.EvaluateBatchAsync(states, Judge.Questions(qs)); // in order, fail-closed
```

Also: `Judge.Context(ctx, message, extra)`, `Judge.Choice` / `Judge.Score`, `answer.Value()` /
`answer.Choice()`, `Tape` (record by call name, replay offline), `Classifier.FromRecorded(...)`,
`Question.ToWire()`, public `Decision.FromJson`.

Batteries (§8B): `ToolGuardClassifier`, `ToolRelevanceClassifier`, `SkillRelevanceClassifier`,
`ToolResultFilterClassifier`, `IsCompleteClassifier`, `AgentRouterClassifier`,
`ContentGuardClassifier`, `ModelRouterClassifier` — e.g.
`new ToolGuardClassifier(classifier, new ToolGuardOptions { OnError = OnError.Closed })`, then
`CheckAsync(...)` standalone or `AsHook(next)` as a client hook. A `BeforeLLM` override may carry
`Model` for that turn only (the opt-in `ModelRouterClassifier` sets it).

## API key by host (BREAKING in 0.22.0)

With no `ApiKey`, the client reads `ApiKeyEnv` (the NAME of an env var) if set, else ONLY the env
var of the `BaseUrl` host, over https: `api.openai.com` → `OPENAI_API_KEY`, `api.anthropic.com` →
`ANTHROPIC_API_KEY`, `openrouter.ai` → `OPENROUTER_API_KEY`. A key is never sent to another host.
A local server or proxy now needs one of the two:

```csharp
var local = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = "http://localhost:11434/v1", Style = "openai", Model = "llama3.2",
    ApiKey = "YOUR_KEY_HERE",            // or: ApiKeyEnv = "MY_PROXY_KEY"
});
```

[API key by host](https://muthuishere.github.io/toolnexus/api/csharp/client/api-key/) ·
[`ListModelsAsync`](https://muthuishere.github.io/toolnexus/api/csharp/client/list-models/) uses the same rule.

## Prompt cache and tool tiers

```csharp
var agent = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = "https://api.anthropic.com", Style = "anthropic", Model = "claude-sonnet-4-5",
    PromptCache = new PromptCacheOptions { Key = "session-42" },             // cache_control / prompt_cache_key
    ToolTiers = new ToolTierOptions { Core = new[] { "read", "edit", "bash" } }, // the rest via tool_search / call_tool
});
// res.Usage.CacheReadTokens / CacheWriteTokens; agent.ToolTierPlan(tk); agent.ResetToolTiers(tk) at a new session
```

Only the core schemas are sent; the model loads the rest with `tool_search` and calls them by name
or through `call_tool` (hooks still see the real tool), so the tools array and the cached prefix
never change mid-session. Runnable: `dotnet run --project examples/Toolnexus.Examples -- cache`.
[Prompt cache](https://muthuishere.github.io/toolnexus/api/csharp/client/prompt-cache/) ·
[Tool tiers](https://muthuishere.github.io/toolnexus/api/csharp/client/tool-tiers/)

## Host additions (0.22.0)

- `Options.WaitForCtx((request, ct) => …)` — a pending question ends with the turn.
- `McpSource.LoadAsync(cfg, new McpLoadOptions { OnServer = r => … })` — each MCP server reported as it is ready.
- `McpSource.ResourceTools()` — opt-in `list_mcp_resources` / `read_mcp_resource`.
- `BeforeToolEvent.Hints` (`ReadOnly` / `Destructive` / `OpenWorld`) on every builtin and MCP tool, for permission gates.
- Opt-in builtins: `["background"] = new BackgroundShells()` (`bash_output`, `kill_shell`; call `CloseAll` on shutdown), `["webSearch"]` (your provider), `["notebookEdit"] = true`.
- `grep` `context` / `before` / `after`; `read` / `grep` report truncation in metadata.
- `Compaction.Options.OnCompact`, `Agent.AsToolWith(ctx => runtimeOptions)` with `RuntimeOptions.WaitFor`, `ToolContext.CallId`, `LlmClient.ListModelsAsync`.
- Not in C# yet: the ACP host hooks (Go only; `openspec/changes/add-go-host-asks`).

## Media generation

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```csharp
var media = Media.Create(MediaStyle.Load("examples/media-styles/a2e.json"),
    new MediaOptions { OnProgress = p => Console.WriteLine($"{p.Verb} {p.Status}") });
var img = await media.ImageAsync(new ImageInput("a red fox in fresh snow") { Width = 1024, Height = 768 });
var vid = await media.VideoAsync(new VideoInput("the fox blinks") { Image = img.Files[0].Url, Seconds = 5 });

await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Media = media }); // generate_image, …
var res = await agent.RunAsync("Draw a fox, then animate it.", tk);
foreach (var f in res.Files) Console.WriteLine($"{f.From} {f.MimeType} {f.Url}");
```

## CLI client (agent CLIs as the model)

`CliClient.Create` makes an agent CLI you already have installed (and pay for) the model behind
an ordinary client. The style is a closed enum, never a string: `CliStyles.Claude | Codex |
Copilot | Opencode | Devin | Gemini | Goose | Custom`. `Command` is required: nothing is
embedded or defaulted. SPEC §8C.

```csharp
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { SkillsDir = ["./skills"] });
using var client = CliClient.Create(new CliOptions
{
    Style = CliStyles.Opencode, Command = "opencode", Toolkit = tk,
});
var res = await client.RunAsync("What is my badge number?", tk);
Console.WriteLine($"{client.Transport} {res.Text}");   // "acp" …
LlmClient plain = client;                               // usable anywhere an LlmClient goes
```

- **Transports** `CliTransports.Auto | Acp | Cli`. Auto uses ACP when the style has an ACP launch
  (`[Command] + style.acp.args`, or `AcpCommand` verbatim, e.g. claude's
  `["npx", "-y", "@agentclientprotocol/claude-agent-acp@0.100.0"]`) and falls back to the one-shot
  CLI only when the agent fails to start or answer `initialize` (`FallbackReason` says why).
  `Transport` is `"auto"` until the first call.
- **ACP**: the toolkit is served as MCP on `127.0.0.1:<ephemeral>/mcp` behind a per-session
  bearer token, `skill` is served as `load_skill`, and `BeforeTool`/`AfterTool` run at that
  server. Only calls to served tools are allowed; the agent's own shell, edit and fetch are
  rejected. Agents without http MCP get a stdio bridge (`BridgeCommand`, default
  `toolnexus mcp-bridge` when the Go CLI is on PATH; `CliClient.RunMcpBridgeAsync` is the same
  thing as a function).
- **One-shot**: the conversation goes in under a fixed preamble, the reply is parsed as an
  OpenAI-shaped envelope, and tool calls run in the toolnexus loop.
- **Streaming** (`StreamAsync`) works on both transports. Over **ACP** it is incremental: each
  `agent_message_chunk` is a `Text` delta as it arrives, `agent_thought_chunk` a `Thought` event
  (never part of the answer), and the agent's own tool calls/results `ToolCall`/`ToolResult` events
  with `Via = "acp"`. The **one-shot** transport is not incremental: the loop's
  `ToolCall`/`ToolResult` events arrive, then the whole answer as one `Text` event, then `Done`.
  Your own in-process model can stream the same way via `InProcess.Options.GenerateStream`.
- Errors are pinned (`cli: command not found: <bin>`, `cli: exit <code>: <stderr>`,
  `cli: empty reply`, `cli: no output file`, `cli: timed out after <ms>ms`), thrown as
  `CliException`. Default call timeout 300000 ms (`CallTimeoutMs`). `Usage` is always zero.
  Styles are data: `CliClient.LoadStyle(pathOrJson)` for your own.
- `AcpClient.StartAsync(new AcpStartOptions { McpServers, Model, Stderr, OnPermission, … })`
  is the lower-level ACP model source the CLI client is built on.

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

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
