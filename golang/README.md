# toolnexus (Go)

[![Go Reference](https://pkg.go.dev/badge/github.com/muthuishere/toolnexus/golang.svg)](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `Run()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework, not a toy that falls over the moment you need streaming or a
> retry. Idiomatic Go: struct options, exported functions, `context.Context`, `defer tk.Close()`.

The Go port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Java, C#, Elixir, Clojure and Rust**. Built on
[`mark3labs/mcp-go`](https://github.com/mark3labs/mcp-go). Requires Go 1.23+.

## Install

```sh
go get github.com/muthuishere/toolnexus/golang      # import path: "github.com/muthuishere/toolnexus/golang", package `toolnexus`
```

Or install the **CLI** for an instant agent from the terminal — no code:

```sh
go install github.com/muthuishere/toolnexus/golang/cmd/toolnexus@latest

toolnexus run   --config mcp.json --skills ./skills \
  --base-url https://openrouter.ai/api/v1 --style openai --model openai/gpt-4o-mini
toolnexus tools --config mcp.json --skills ./skills   # list resolved tools (incl. the 10 builtins)
```

## Quick start

```go
package main

import (
	"context"
	"fmt"

	"github.com/muthuishere/toolnexus/golang"
)

func main() {
	ctx := context.Background()

	// 1. a toolkit — the 10 built-in tools are on by default (no MCP / skills needed to start)
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{})
	if err != nil {
		panic(err)
	}
	defer tk.Close()

	// 2. point at any OpenAI- or Anthropic-style endpoint
	agent := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL: "https://openrouter.ai/api/v1",
		Style:   "openai", // or "anthropic"
		Model:   "openai/gpt-4o-mini",
		// no APIKey: openrouter.ai reads $OPENROUTER_API_KEY (its own host's var only).
		// A local or custom BaseURL needs APIKey or APIKeyEnv — see below.
	})

	// 3. run — skills injected into the system prompt, tools called for you, looped to an answer
	res, err := agent.Run(ctx, "List the Go files in this directory and count them.", tk)
	if err != nil {
		panic(err)
	}
	fmt.Println(res.Text)
}
```

**Embedding toolnexus in an existing Go service?** [`GUIDE.md`](GUIDE.md) is a step-by-step on
making a running app MCP- and skills-enabled.

## Simple judgments

A thin layer over any `Classifier` (SPEC §8B); the wire request is unchanged.

```go
a, err := tn.Ask(ctx, c,
	tn.State("You are Donkey Kong, you want to win.", map[string]any{"message_received": msg}),
	[]tn.JudgeQuestion{
		tn.Noul("is_appropriate", "Does `message_received` contain harmful language?"),
		tn.Noul("does_this_help", "Does `message_received` help donkey kong win?"),
	})
a["is_appropriate"].Band // "yes" | "no" | "uncertain" (default bands 0.30 / 0.70, exclusive)

o, err := tn.Policy{Rules: rules, Default: "continue"}.Gate(ctx, c, state, qs)
// o.Escalated => o.Request is a §10 input Request: the gate declines to decide
```

`Choice` / `Score` build the other question kinds; `Tape` records live decisions by call name
(`WithCallName`) and replays them offline; `StaticClassifier(Recorded(...))` is a one-line test
double; `EvaluateBatch` asks the same questions of many states.

## API key by host (breaking in 0.22.0)

A key is only sent to the host it belongs to. `APIKey` set ⇒ only it; else `APIKeyEnv` (the **name**
of an env var); else only the env var of the `BaseURL` host, over https — `api.openai.com` →
`OPENAI_API_KEY`, `api.anthropic.com` → `ANTHROPIC_API_KEY`, `openrouter.ai` → `OPENROUTER_API_KEY`.
Any other host (a local llama.cpp/vLLM server, a proxy, plain http) without `APIKey`/`APIKeyEnv`
fails with `no API key`. Before 0.22.0 the first of those three env vars was sent to any `BaseURL`;
a host that relied on that must now set one:

```go
client := toolnexus.CreateClient(toolnexus.ClientOptions{
	BaseURL: "http://localhost:8080/v1", Style: "openai", Model: "local",
	APIKeyEnv: "LOCAL_LLM_KEY", // the NAME of the env var, never the value
})
```

## Prompt cache and tool tiers

```go
client := toolnexus.CreateClient(toolnexus.ClientOptions{
	BaseURL: "https://api.anthropic.com", Style: "anthropic", Model: "claude-sonnet-4-5",
	PromptCache: &toolnexus.PromptCacheOptions{Key: "session-42"}, // anthropic breakpoints / openai prompt_cache_key
	ToolTiers:   &toolnexus.ToolTierOptions{Core: []string{"read", "grep", "bash"}},
})
res, _ := client.Run(ctx, "fix the failing test", tk)
_ = res.Usage.CacheReadTokens // + CacheWriteTokens; under anthropic context = Prompt+CacheRead+CacheWrite
client.ToolTierPlan(tk)       // name -> core | listed | deferred, frozen until client.ResetToolTiers(tk)
```

`ToolTiers` sends only the core schemas plus `tool_search` (the model loads the rest; schemas arrive
as a tool result, so the tools array and the cached prefix never change) and `call_tool` (unwrapped
before hooks, so gates see the real tool). Runnable: `go run ./examples/promptcache`.

## Host hooks (0.22.0)

- `LoadMcpWithOptions(ctx, cfg, McpLoadOptions{OnServer: ...})` — each MCP server as soon as it is
  ready; `Toolkit.Register` is safe mid-turn.
- `ClientOptions.WaitForCtx(ctx, req)` — a pending question ends with its turn.
- `Tool.Hints{ReadOnly, Destructive, OpenWorld}` on every builtin and MCP tool, passed to
  `BeforeTool` — allow / deny / ask (`Pending`) gates.
- Opt-in builtins: `BuiltinsConfig{Background: NewBackgroundShells(), WebSearch: fn, NotebookEdit: true}`
  (`bash_output`, `kill_shell`, `websearch`, `notebook_edit`); `McpSource.ResourceTools()`.
- `grep` `context`/`before`/`after`; `read`/`grep` report truncation in metadata.
- `agents.CompactorOptions.OnCompact`, `Agent.AsToolWith` + runtime `Options.WaitFor`,
  `ToolContext.CallID`, `ListModels(ctx, opts)`.
- ACP (Go only): `ACPOptions.OnPermission`, `ACPOptions.OnUpdate`, `ACPClient.GenerateCtx`
  (cancel sends `session/cancel`), and `ACPClient.GenerateStream` as
  `InProcessOptions.GenerateStream` to make `Client.Stream` work over an ACP agent.

Each has a runnable page in the [Go API reference](https://muthuishere.github.io/toolnexus/api/go/).

## Media generation

Generate images, video, speech and music (plus transcription, voice cloning and talking-head
video) through **named media clients**: API clients `NewA2EMediaClient`, `NewElevenLabsMediaClient`,
`NewOpenAIMediaClient`, `NewOpenRouterMediaClient`, `NewFalMediaClient`, and CLI clients
`NewGrokMediaClient`, `NewCodexMediaClient`. `CreateMedia` takes them in priority order: each verb
goes to the first client that supports it, and moves on to the next **only** when that client is
unavailable (no key, no credits, CLI missing). Keys are read from the client's env var
(`FAL_API_KEY`, `OPENAI_API_KEY`, …) at call time and never logged. SPEC §7G; guide:
<https://muthuishere.github.io/toolnexus/media/>.

```go
cfg := toolnexus.MediaConfig{Gather: toolnexus.MediaGather{Mode: "download", Dir: "out"}}
media, err := toolnexus.CreateMedia(
	toolnexus.NewFalMediaClient(cfg),        // image, video (FAL_API_KEY)
	toolnexus.NewGrokMediaClient(cfg),       // fallback when fal is unavailable (grok CLI; prompt only)
	toolnexus.NewElevenLabsMediaClient(cfg), // speech, music, transcribe, cloneVoice
	toolnexus.NewA2EMediaClient(cfg),        // talk
)
if err != nil {
	log.Fatal(err)
}
img, err := media.Image(ctx, toolnexus.ImageInput{Prompt: "a red fox in fresh snow"})
if errors.Is(err, toolnexus.ErrMediaUnavailable) {
	log.Fatal("every image client is unavailable: ", err)
}
vid, _ := media.Video(ctx, toolnexus.VideoInput{Prompt: "the fox blinks", Image: img.Files[0].Path, Seconds: 5})
voice, _ := media.Speech(ctx, toolnexus.SpeechInput{Text: "Hello from the fox."})
talk, _ := media.Talk(ctx, toolnexus.TalkInput{Image: img.Files[0].Path, Audio: voice.Files[0].Path})
fmt.Println(vid.Files[0].Path, talk.Files[0].Path)
```

The typed verbs are `Image`, `Video`, `Speech`, `Music`, `Transcribe` (→ `MediaResult.Text`),
`CloneVoice` (voice id in `Text`) and `Talk`. A zero field means "not set"; to send a real zero use
`media.Generate(ctx, "image", map[string]any{"prompt": "…", "seed": 0}, toolnexus.MediaCall{})`.

**Config** — every built-in client takes one `MediaConfig{Model, Models, Key, URL, Command, Loop,
Gather, HTTPClient}`. `Gather.Mode` is `"url"` (keep provider URLs), `"download"` (default; save under
`Dir`, default `<os temp>/toolnexus-media`, capped by `MaxBytes`) or `"inline"` (base64 `Data`).
`Loop{IntervalMs, TimeoutMs, OnProgress}` bounds async jobs.

**As agent tools** — pass the router to the toolkit and each supported verb becomes a tool
(`generate_image`, `generate_video`, `generate_speech`, …). Whatever they produce lands in
`RunResult.Files`; only image parts are shown to the model.

```go
tk, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{Media: media})
res, _ := agent.Run(ctx, "Draw a fox, then animate it.", tk)
for _, f := range res.Files { fmt.Println(f.From, f.Type, f.MimeType, f.URL) }
```

**Your own provider** — embed `toolnexus.UnsupportedMedia`, implement `Name`, `Supports` and the
verbs you serve; return `toolnexus.MediaUnavailable("…")` to let the router fall through.

```go
type stock struct{ toolnexus.UnsupportedMedia }

func (stock) Name() string              { return "stock" }
func (stock) Supports(verb string) bool { return verb == "image" }
func (stock) Image(ctx context.Context, args map[string]any, call toolnexus.MediaCall) (toolnexus.MediaResult, error) {
	return toolnexus.MediaResult{Status: "completed", Files: []toolnexus.MediaFile{
		{Type: "image", MimeType: "image/png", URL: "https://example.com/fox.png"}}}, nil
}
```

Advanced: `LoadMediaStyle` + `NewStyleMediaClient` drive any HTTP provider from a JSON style.
Run the example: `go run ./examples/media`. It uses, in order and only when present, the `grok`
and `codex` CLIs, `ELEVENLABS_API_KEY`, then `OPENAI_API_KEY` / `FAL_API_KEY` / `A2E_API_KEY`:
an image, ElevenLabs speech (`MEDIA_VOICE` or the default voice), and the `generate_image` tool;
Grok video only with `MEDIA_VIDEO=1`, paid video/talk only with `MEDIA_PAID=1`. It prints client,
verb, file, mime, size and provider-reported `usage`, exits 1 if a step fails, and exits 0 with a
hint when no client is available. A client that cannot take a call's args is skipped like an
unavailable one; saved files carry their `Path` into `RunResult.Files`.

## MCP 2026-07-28 + OAuth

Every MCP server is asked `server/discover` first. A server that speaks protocol
`2026-07-28` is used through toolnexus's own client: stateless HTTP (JSON or SSE
replies) or line-delimited stdio. There is no `initialize` and no session. Older
servers fall back to the previous `mark3labs/mcp-go` path, which is frozen. The
config format, tool names and result mapping are the same on both paths.

```json
{
  "mcpServers": {
    "github": {
      "url": "https://example.com/mcp",
      "auth": { "clientId": "YOUR_CLIENT_ID", "mode": "auto" },
      "toolSearch": true,
      "description": "GitHub issues and pull requests"
    }
  }
}
```

- **OAuth** (`auth`): a 401 starts the flow: protected-resource metadata, then authorization-server metadata, then PKCE S256 with `resource`. The client identity is a pre-registered `clientId`/`clientIds`, or a client ID metadata document (`clientMetadataUrl`). Dynamic client registration is never used. In `auto` mode the browser opens and the callback lands on a loopback listener. In `pause` mode the client sends a §10 `authorization` request; the host answers with `Answer{Ok: true, Data: {"callbackUrl": ...}}`. Tokens are refreshed single-flight and step up on `insufficient_scope`. Pass `Options.TokenStore` (default: in-memory) and `Options.OpenURL` (default: the OS opener). Both are also on `McpLoadOptions`. Tokens never appear in logs or errors.
- **Input requests** (`input_required`) go through `WaitFor`. Without a `WaitFor`, the run returns `{status:"pending"}`, and resuming continues the same round.
- **Tool search** (`toolSearch: true`) exposes `<server>_search_tools` and `<server>_call_tool` in place of every server tool.
- **Skills**: skills from a server's skills extension join the same `skill` tool.
- **Fallback and OAuth**: the bearer token is attached to the SDK transport for each request (`WithHTTPHeaderFunc`). A 401 from that path runs the same OAuth flow once.

## MCP server you mount, on 2026-07-28 (opt-in)

`tk.MCPHandler(cfg)` is an `http.Handler` for your own mux (SPEC §7C.1). Add `"2026-07-28"` to
`ProtocolVersions` to serve the stateless 2026-07-28 wire (SPEC §7C.2), optionally with skills and OAuth:

```go
auth := &toolnexus.MCPServerAuth{Issuer: "https://login.example", Scopes: map[string][]string{"*": {"mcp"}}}
mux.Handle("/mcp", tk.MCPHandler(toolnexus.MCPHandlerConfig{
	ProtocolVersions: []string{toolnexus.MCPProtocol2026, "2025-06-18"}, // both wires on one mount
	Authenticate:     auth.Authenticate,                                  // IdentityFrom(ctx).(toolnexus.MCPPrincipal)
	ListSkills:       func(context.Context) ([]toolnexus.MCPSkill, error) { return toolnexus.LoadMCPSkills("skills") },
}))
mux.Handle("/.well-known/oauth-protected-resource/", auth.Metadata())
mux.Handle("/.well-known/oauth-protected-resource", auth.Metadata())
```

Bring your own authorization server: `MCPServerAuth` verifies its tokens. The auto-approving dev
authorization server the suite uses is test tooling (`internal/devauth`), not part of the library.

## Agents via ACP

toolnexus ships no per-agent client. Three vendor-neutral pieces compose instead (SPEC §8C):
`tk.AsMCPServer(nil)` serves your toolkit on `127.0.0.1` behind a bearer token, `LoadACP` drives any
ACP agent (its `MCPServers` entries are sent verbatim — hand it that URL and token), and
`CreateInProcessClient(InProcessOptions{GenerateStream: acp.GenerateStream})` makes it an ordinary
streaming client. Launch flags, logins and permission policy are yours to choose.

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Dreaming](https://muthuishere.github.io/toolnexus/dreaming/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | [Go](https://muthuishere.github.io/toolnexus/api/go/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
