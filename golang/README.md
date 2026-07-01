# toolnexus (Go)

Build an agent in a few lines. Unify **four tool sources** behind one `Tool`
interface and drive any LLM with them:

1. **MCP servers** — read an MCP config, connect to every server (local stdio +
   remote streamable-HTTP/SSE), expose each server tool.
2. **Agent skills** — read a skills folder (`**/SKILL.md`); a `skill` tool loads
   each skill's instructions + resources on demand (progressive disclosure).
3. **Native tools** — a Go function → a tool (`NativeTool` / `NativeToolReflect`,
   schema from struct tags).
4. **HTTP/REST tools** — declare an endpoint (`HTTPTool`), `${ENV}` header expansion.

Plus a **unified client** (`CreateClient` — OpenAI- or Anthropic-style endpoints)
that runs the whole tool-calling loop, and a **CLI** (`cmd/toolnexus`) for an
instant interactive agent. Adapters emit OpenAI / Anthropic / Gemini tool schema,
so you can also bring your own LLM client and just call `tk.Execute(ctx, name, args)`.

This is the Go implementation of the shared [`../SPEC.md`](../SPEC.md) contract,
built on [`github.com/mark3labs/mcp-go`](https://github.com/mark3labs/mcp-go).

> **Embedding toolnexus in your own Go app?** See **[GUIDE.md](GUIDE.md)** — a
> step-by-step on making an existing Go application MCP- and skills-enabled.

## Install

```sh
go get github.com/muthuishere/toolnexus/golang
```

Requires Go 1.23+.

## Quickstart

```go
package main

import (
	"context"
	"fmt"

	"github.com/muthuishere/toolnexus/golang"
)

func main() {
	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
		McpConfig: "./mcp.json",            // path, raw JSON bytes, McpConfig, or parsed map
		SkillsDir: []string{"./skills"},    // one or more skill roots
		// ExtraTools: []toolnexus.Tool{...},
	})
	if err != nil {
		panic(err)
	}
	defer tk.Close()

	fmt.Println(tk.McpStatus())   // map[server]connected|failed|disabled
	fmt.Println(tk.SkillsPrompt()) // markdown catalog for the system prompt

	tools := tk.ToOpenAI() // or tk.ToAnthropic() / tk.ToGemini()
	_ = tools

	// When the model returns a tool call:
	res, _ := tk.Execute(ctx, "skill", map[string]any{"name": "hello-world"})
	fmt.Println(res.Output)
}
```

## MCP config

Top-level key may be `mcpServers` (Claude style), `servers`, or `mcp`. Type is
inferred from `command` (local) or `url` (remote) when omitted.

```jsonc
{
  "mcpServers": {
    "everything": {
      "command": ["npx", "-y", "@modelcontextprotocol/server-everything"],
      "environment": { "FOO": "bar" },
      "enabled": true,
      "timeout": 30000
    },
    "remote-api": {
      "type": "remote",
      "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer ..." }
    }
  }
}
```

One bad server logs a warning and is marked `failed`; it never breaks the toolkit.
Default per-server timeout is 30s.

## Skills

Each skill is a directory containing a `SKILL.md` with YAML frontmatter:

```markdown
---
name: hello-world
description: A tiny example skill.
---

# Hello World Skill
...body...
```

The toolkit ships one `skill` tool by default. Calling it with `{"name": "..."}`
returns a progressive-disclosure block: the skill body, a `file://` base
directory, and up to 10 sampled sibling files. `SkillsPrompt()` opens with a
preamble telling the model to use the `skill` tool.

## Built-in tools

A fifth source ships **10 built-in tools** — `bash`, `read`, `write`, `edit`,
`grep`, `glob`, `webfetch`, `question`, `apply_patch`, `todowrite`
(names + input schemas match opencode) — so an agent can act with zero wiring.
They appear in the tool schema (`ToOpenAI()`/`ToAnthropic()`/`ToGemini()`), like
MCP tools — not the system prompt. **On by default.** One global toggle turns the
whole source off, or a per-tool `Tools` map disables individual builtins on the
all-on baseline:

```go
tk, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{
    McpConfig: "./mcp.json",
    Builtins:  false, // also accepts a BuiltinsConfig{Disabled: ...} / {Enabled: ...}
})

// per-tool: drop bash, keep the other nine (unknown names ignored; whole-source-off still wins)
tk2, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{
    McpConfig: "./mcp.json",
    Builtins:  toolnexus.BuiltinsConfig{Tools: map[string]bool{"bash": false}},
})
```

`bash`/`write`/`edit`/`apply_patch` run commands and mutate the filesystem — the
toggle is the off-switch for locked-down hosts. The `toolnexus tools` CLI lists
these 10 builtins by default too.

## A2A agents (agent-to-agent)

Call **remote A2A agents** (each of their skills becomes a tool) and serve your own
toolkit as an agent other A2A peers can call. A genuine, minimal subset of real A2A
(JSON-RPC 2.0; Agent Card at `/.well-known/agent-card.json`; `SendMessage` → poll
`GetTask`). No streaming / push / auth in v1.

**Outbound — call a remote agent.** Each advertised skill becomes a tool named
`<agent>_<skill>` (`source: "a2a"`):

```go
tk, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{
    Agents: []toolnexus.Agent{
        {Card: "https://researcher.example.com/.well-known/agent-card.json"},
    },
})

// or add one at runtime (a card URL, or an Agent value):
_, _ = tk.AddAgent(ctx, "https://writer.example.com/.well-known/agent-card.json", nil)
```

`Agent{Card, Headers, Timeout, PollEvery}` — `Headers` support `${ENV}` expansion (never
logged); `Timeout` / `PollEvery` are milliseconds (300000 / 1000 defaults). A config file
can also carry an `agents` block (mirrors `mcpServers`). A failing agent is isolated —
logged, contributes no tools, never fatal.

**Inbound — serve your toolkit as an agent.** Opt in with the `A2A` profile; the Agent Card
is built from your **SKILL.md skills** (never raw tools):

```go
client := toolnexus.CreateClient(toolnexus.ClientOptions{
    BaseURL: "https://openrouter.ai/api/v1", Style: "openai", Model: "openai/gpt-4o-mini",
})

handle, _ := tk.Serve("127.0.0.1:0", toolnexus.ServeOptions{
    Client: client,
    A2A: &toolnexus.A2AConfig{
        Name:        "research-agent",
        Description: "Answers research questions.",
        // Skills:   []string{"hello-world"}, // subset of skills to advertise; nil ⇒ all
        Store: "memory",                       // "memory" (default) | "file:<dir>" | a custom TaskStore
    },
})
fmt.Println(handle.URL)   // GET /.well-known/agent-card.json ; POST / (SendMessage / GetTask)
defer handle.Stop()
```

`Serve(addr, ServeOptions{Client, A2A, OnTask})` fulfils each inbound `SendMessage` task via
`Client.Run`. Task persistence is a pluggable `TaskStore` — in-memory default, `"file:<dir>"`,
or your own.

## Examples

```sh
# Connects to the `everything` MCP server (spawns npx), lists tools, prints the
# skill catalog and loads the hello-world skill.
go run ./examples/basic

# Live OpenRouter tool-calling round trip (reads OPENROUTER_API_KEY from env).
OPENROUTER_API_KEY=... go run ./examples/openrouter
```

## API

- `CreateToolkit(ctx, Options{McpConfig, SkillsDir, ExtraTools, Builtins}) (*Toolkit, error)` — `Builtins` toggles the 10 built-in tools (default on)
- `Toolkit.Tools() []Tool`
- `Toolkit.Get(name) (Tool, bool)`
- `Toolkit.Execute(ctx, name, args) (ToolResult, error)`
- `Toolkit.SkillsPrompt() string`
- `Toolkit.McpStatus() map[string]McpStatus`
- `Toolkit.ToOpenAI() / ToAnthropic() / ToGemini() []any`
- `Toolkit.AddAgent(ctx, cardURLorAgent, *Agent) (*Toolkit, error)` — register a remote A2A agent's skills as tools
- `Toolkit.Serve(addr, ServeOptions{Client, A2A, OnTask}) (*ServeHandle, error)` — serve the toolkit as an A2A agent
- `Toolkit.Close()`
