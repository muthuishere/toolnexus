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

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```go
style, _ := toolnexus.LoadMediaStyle("examples/media-styles/a2e.json")
media := toolnexus.CreateMedia([]toolnexus.MediaStyle{style}, toolnexus.MediaOptions{
    OnProgress: func(p toolnexus.MediaProgress) { fmt.Println(p.Verb, p.Status) },
})
img, err := media.Image(ctx, toolnexus.ImageInput{Prompt: "a red fox in fresh snow", Width: 1024, Height: 768})
vid, err := media.Video(ctx, toolnexus.VideoInput{Prompt: "the fox blinks", Image: img.Files[0].URL, Seconds: 5})

tk, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{Media: media}) // generate_image, …
res, _ := agent.Run(ctx, "Draw a fox, then animate it.", tk)
for _, f := range res.Files { fmt.Println(f.From, f.MimeType, f.URL) }
```

In the typed inputs a zero value means "not set" (`Seed: 0` is never sent). To send a real
zero, use `media.Generate(ctx, "image", map[string]any{"prompt": "…", "seed": 0}, nil)`.

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

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | [Go](https://muthuishere.github.io/toolnexus/api/go/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
