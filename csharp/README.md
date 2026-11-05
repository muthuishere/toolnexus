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

## Embeddable MCP server (SPEC §7C.1)

`tk.McpHandler(cfg)` returns an `McpHttpHandler` you mount on your own HTTP server, behind your own
TLS, CORS and OAuth. `ServeAsync` is unchanged. Every policy is a hook:

```csharp
var handler = tk.McpHandler(new McpHandlerConfig
{
    Authenticate = ex => VerifyBearer(ex.Header("Authorization")),      // throw => 401 (+ WwwAuthenticate)
    ListTools    = ctx => ToolsFor((User)ctx.Identity!),                 // per request; others are "tool not found"
    BeforeCall   = (ctx, tool, args) => Task.FromResult(tool.Annotations?.DestructiveHint == true
                       ? McpDecision.Confirm("Really delete?")          // elicitation on the same POST
                       : McpDecision.Allow()),
    OnCall       = ev => Log(ev.Name, ev.Decision, ev.ResultBytes),
});
// HttpListener host:  await handler.HandleAsync(httpListenerContext);
// any other server:   subclass McpHttpExchange and call handler.HandleAsync(exchange).
handler.NotifyToolsChanged(); // tools/list_changed on every open GET stream
```

Tools and hooks read the request through `McpContext` (`Identity`, `Request`, `SessionId`, `Meta` =
`params._meta`); a tool uses `McpContext.Current`. Also: `ServerInfo`, resources/prompts hooks,
pluggable `SessionIds`, `Stateless`, `ProtocolVersions` (`2025-06-18` default, `2025-11-25` opt-in).
`ToolResult.Structured`/`Meta` and `ITool.OutputSchema`/`Annotations` map to `structuredContent`,
`_meta`, `outputSchema` and `annotations`. Runnable: `dotnet run --project examples/Toolnexus.Examples -- mcphandler`.

### MCP 2026-07-28 on the same handler (SPEC §7C.2, opt-in)

List `"2026-07-28"` in `ProtocolVersions` (alone, or next to `"2025-06-18"` to serve both wires on one
mount). The 2026 wire is stateless: `server/discover`, header validation, `resultType` + `_meta`
serverInfo, and MRTR instead of elicitation — a `Confirm` decision or a §10 tool suspension
(`ToolResult.Pending`) answers `input_required`, and the client's retry reaches the tool as
`ToolContext.Answer`. Without the opt-in the handler is byte-identical to §7C.1.

```csharp
var auth = new McpServerAuth                                   // OAuth 2.1 protected resource (RS256/JWKS by default)
{
    Issuer = "https://login.example",
    Scopes = new Dictionary<string, IReadOnlyList<string>> { ["*"] = ["mcp"], ["wipe"] = ["mcp:admin"] },
};
var handler = tk.McpHandler(new McpHandlerConfig
{
    ProtocolVersions = ["2026-07-28", "2025-06-18"],
    Authenticate     = auth.AuthenticateAsync,                 // identity = McpPrincipal; 401/403 + exact challenge
    ListSkills       = _ => Task.FromResult(McpHttpHandler.LoadMcpSkills("skills")), // skills/list, skills/get, skill:// reads
});
// route GET /.well-known/oauth-protected-resource[/mcp] to auth.Metadata() (or auth.MetadataAsync(listenerCtx))
```

`McpServerAuth.Verify` replaces the JWT verifier (opaque tokens, introspection). Bring your own authorization
server: `McpServerAuth` verifies its tokens. The auto-approving dev authorization server the suite uses is test
tooling (`tests/Toolnexus.Tests/McpDevAuth.cs`), not part of the library.

## Agents via ACP

toolnexus ships no client per coding agent — three vendor-neutral pieces instead (SPEC §8C):

- `tk.AsMcpServer(cfg?)` serves the toolkit for a local agent on
  `http://127.0.0.1:<ephemeral>/mcp` behind a fresh bearer token (`Url`, `Token`, `Close()`).
- `AcpClient.StartAsync(new AcpStartOptions { Command, Arguments, McpServers, SessionMeta,
  ClientCapsMeta, Model, OnPermission, … })` drives any ACP agent; `McpServers` entries are sent
  verbatim, so you build whichever entry your agent accepts.
- `InProcess.Options.GenerateStream = acp.GenerateStreamAsync` streams the agent's answer
  (`Text` deltas, `Thought`, and its own `ToolCall`/`ToolResult` with `Via = "acp"`).

```csharp
await using var srv = tk.AsMcpServer();
using var acp = await AcpClient.StartAsync(new AcpStartOptions
{
    Command = "my-agent", Arguments = ["acp"],
    McpServers = [new Dictionary<string, object?>
    {
        ["type"] = "http", ["name"] = "toolnexus", ["url"] = srv.Url,
        ["headers"] = new[] { new Dictionary<string, object?> { ["name"] = "Authorization", ["value"] = "Bearer " + srv.Token } },
    }],
});
var client = InProcess.CreateClient(new InProcess.Options { Model = "acp", GenerateStream = acp.GenerateStreamAsync });
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

## MCP 2026-07-28 + OAuth

`McpSource.LoadAsync` / `Toolkit.CreateAsync` speak MCP **2026-07-28** first (SPEC §2): every server gets
`server/discover` before anything else, over stdio or stateless Streamable HTTP, with no MCP SDK on that
path. A server that is not a 2026-07-28 server (a JSON-RPC error, HTTP 400/404/405/5xx, or a silent stdio
child) falls back to the frozen pre-2026 path, which still runs on the `ModelContextProtocol` SDK. Your
config stays the same.

- **MRTR → §10:** `input_required` rounds go through `WaitFor`. Without a `WaitFor` the tool returns
  `{status:"pending"}`; re-running it with the `Answer` resumes that same round. A `tools/call` is never
  re-sent after a lost connection, a timeout or a 5xx.
- **Skills over MCP:** skills a server publishes through the skills extension join the one `skill` tool
  (base `skill://<name>/`).
- **Lazy tool search:** set `"toolSearch": true` (and optionally `"description"`) on a server. You then get
  `<server>_search_tools` and `<server>_call_tool` instead of every tool.
- **OAuth 2.1 (SPEC §2A):** the per-server `auth` object takes `clientId`, `clientSecret`,
  `tokenAuthMethod`, `clientIds`, `clientMetadataUrl`, `redirectUri`, `mode` (`auto`|`pause`),
  `omitRefreshResource` and `allowHttp`. The client uses a pre-registered client id or a Client ID Metadata
  Document; dynamic client registration is never used. It does PKCE S256 + `resource`, iss/state checks,
  single-flight refresh and step-up. `auto` opens the browser and listens on loopback. `pause` sends a §10
  `authorization` request; answer it with `Data = { ["callbackUrl"] = "<redirected URL>" }`.

```csharp
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
{
    McpConfig = "mcp.json",
    TokenStore = myStore,            // ITokenStore; default in-memory per load
    OpenUrl = url => OpenBrowser(url), // auto mode; default is the OS opener
    WaitFor = WaitFor,               // MRTR input + pause-mode authorization
});
```

On the frozen fallback, the bearer goes onto the SDK's HTTP transport through a delegating handler. A 401
there runs the same flow once. Not yet on the 2026 path: `list_mcp_resources` / `read_mcp_resource`
(`ResourceTools()` only sees fallback servers), progress notifications, and the server side (`serve`).
