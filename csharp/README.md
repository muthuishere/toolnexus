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

## Classifier backends, images, cache and helpers

Proposed defaults, owner to confirm (`openspec/changes/add-classifier-backends`, SPEC.md §8B).

- **Backends** are data: `ClassifierBackends.Table` has `TypeSafe` (default), `OpenRouter`
  (`typesafe/jev-1.14`), `Cloudflare` (`/ai/run` envelope; `${CLOUDFLARE_ACCOUNT_ID}` in the URL
  expands at call time, and errors name the unexpanded URL), `Vercel` and `Custom` (needs `BaseUrl`
  and `Supports`). `Supports = new ClassifierSupports(Images: true)` overrides the image column.
  `WithBackend("name")` parses the wire name.
- **Images**: put a `ContentPart` image (PNG/JPEG, inline data) anywhere in the state. It is sent
  as an asset and a reference string; a state with no parts is sent verbatim. A backend without
  image support throws `ClassifierUnsupportedException` before any request; the 16 MiB / 32 MiB and
  280–1568 px limits are checked client-side. Images are never resized.
- **Value builders**: `await c.State(v).EvaluateAsync(Judge.Noul(...), ...)` fails closed on an
  invalid reply; `await c.States(a, b, c).EvaluateAsync(...)` returns one `BatchResult`
  (`Decision` or `Error`) per state, in order, at most 16 in flight.
- **Cache** (off by default): `Cache = new MemoryCache(TimeSpan.FromHours(1), 1000)` or your own
  `IClassifierCache` (async `GetAsync`/`SetAsync`). Per request, keyed on the served model, only
  valid replies stored; a hit sets `Decision.Cached` and the metric's `Cached`.
- **Helpers**: `Classifier.ValidateDecision(qs, d)` (returns the error or null),
  `Thresholds` (inclusive 0.8 / 0.2 / 0.6, next to the unchanged `Bands`),
  `Classifier.ChunkQuestions(qs, size)`, `Judge.Raw(name, type, instructions, criteria?)` for
  unmodelled question types (answers decode to `RawAnswer`).
- `classifier.evaluate` events carry `Questions`, `QuestionsHash` and `Cached`.
- `ClassifierStyle.Llm` is `[Obsolete]`: it still works unchanged for at least one minor release.

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
