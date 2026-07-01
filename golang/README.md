# toolnexus (Go)

[![Go Reference](https://pkg.go.dev/badge/github.com/muthuishere/toolnexus/golang.svg)](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `Run()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework (no builders, advisors, runnables, config to wade through),
> not a toy that falls over the moment you need streaming or a retry. Everything a real agent
> needs — the loop, hooks, streaming, retries, memory, A2A — and nothing it doesn't. Idiomatic
> Go: struct options, exported functions, `context.Context`, and a `defer tk.Close()`.

The Go port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Java, and C#**. Built on
[`github.com/mark3labs/mcp-go`](https://github.com/mark3labs/mcp-go). Requires Go 1.23+.

> **Embedding toolnexus in an existing Go app?** See **[GUIDE.md](GUIDE.md)** — a step-by-step
> on making a running Go service MCP- and skills-enabled.

## Install

```sh
go get github.com/muthuishere/toolnexus/golang
```

Import it (the package is `toolnexus`):

```go
import "github.com/muthuishere/toolnexus/golang"
```

Or install the **CLI** for an instant agent from the terminal — no code:

```sh
go install github.com/muthuishere/toolnexus/golang/cmd/toolnexus@latest

toolnexus run   --config mcp.json --skills ./skills \
  --base-url https://openrouter.ai/api/v1 --style openai --model openai/gpt-4o-mini
toolnexus tools --config mcp.json --skills ./skills   # list resolved tools (incl. the 10 builtins)
```

## An agent in 3 steps

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
		// APIKey defaults to $OPENROUTER_API_KEY / $OPENAI_API_KEY / $ANTHROPIC_API_KEY
	})

	// 3. run — skills are injected into the system prompt, tools are called for you, looped to an answer
	res, err := agent.Run(ctx, "List the Go files in this directory and count them.", tk)
	if err != nil {
		panic(err)
	}
	fmt.Println(res.Text)
}
```

`RunResult` also carries `Messages` (the full transcript), `ToolCalls`, `ToolCallCount`, `Turns`,
`Usage` (aggregated tokens), and `Model`.

**The API key is read from the environment** — set `OPENROUTER_API_KEY` (or `OPENAI_API_KEY` /
`ANTHROPIC_API_KEY`). The value is never logged or printed. To pass it explicitly, set
`ClientOptions.APIKey`.

## With MCP servers + skills

The point of a toolkit is aggregating real tools. Add an `mcp.json` and a `skills/` folder:

```go
tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
	McpConfig: "./mcp.json",         // path, raw JSON bytes, an McpConfig, or a parsed map
	SkillsDir: []string{"./skills"}, // one or more skill roots
})
defer tk.Close()

fmt.Println(tk.McpStatus())    // map[server]connected|failed|disabled
fmt.Println(tk.SkillsPrompt()) // markdown skill catalog for the system prompt
```

An MCP config's top-level key may be `mcpServers` (Claude style), `servers`, or `mcp`. Type is
inferred from `command` (local stdio) or `url` (remote streamable-HTTP/SSE) when omitted:

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
      "headers": { "Authorization": "Bearer ${API_TOKEN}" }
    }
  }
}
```

A bad server logs a warning and is marked `failed` (see `tk.McpStatus()`); it never breaks the
toolkit. Default per-server timeout is 30s. Remote `headers` expand `${ENV_VAR}` at call time and
are never logged.

**Skills** are directories with a `SKILL.md` (YAML frontmatter + body). The toolkit exposes one
`skill` tool: calling it with `{"name": "..."}` returns a progressive-disclosure block — the skill
body, a `file://` base directory, and up to 10 sampled sibling files. `SkillsPrompt()` opens with a
preamble telling the model to use the `skill` tool, and `Client.Run` injects it into the system
prompt for you.

## Conversations & memory

`Run` is stateless — every call starts fresh. `Ask` is the stateful counterpart: pass a
conversation **id** and the client remembers that conversation across calls through a
`ConversationStore`.

```go
func (c *Client) Ask(ctx context.Context, prompt string, tk *Toolkit, id string) (RunResult, error)
```

With a **non-empty `id`** the client loads that id's transcript from the store, runs the loop with
it as history, saves the updated `RunResult.Messages` back under `id`, and returns the result —
so the next `Ask` with the same id continues the conversation (store `Get` → `Run` → `Save`). With
an **empty `id`** it is a stateless one-shot, identical to `Run`, and the store is never touched.

```go
agent := toolnexus.CreateClient(toolnexus.ClientOptions{
	BaseURL: "https://openrouter.ai/api/v1",
	Style:   "openai",
	Model:   "openai/gpt-4o-mini",
	// Store: myStore, // defaults to an in-memory store, per-client, process lifetime
})

// Turn 1 — remembered under "user-42"
r1, _ := agent.Ask(ctx, "My name is Muthu. Remember it.", tk, "user-42")
fmt.Println(r1.Text)

// Turn 2 — same id ⇒ the client recalls turn 1
r2, _ := agent.Ask(ctx, "What's my name?", tk, "user-42")
fmt.Println(r2.Text) // "Your name is Muthu."

// Empty id ⇒ stateless (identical to Run) — no memory of "Muthu"
r3, _ := agent.Ask(ctx, "What's my name?", tk, "")
fmt.Println(r3.Text)
```

By default conversations live in an in-memory store for the client's lifetime. To persist across
processes (file / DB / Redis), implement the two-method `ConversationStore` interface and pass it
as `ClientOptions.Store`:

```go
type ConversationStore interface {
	Get(id string) ([]any, error)   // stored transcript for id, or (nil, nil) when none
	Save(id string, messages []any) error
}
```

`NewInMemoryConversationStore()` returns the built-in default (safe for concurrent use; copies on
get/save). The same store also backs **A2A `Serve`**: an inbound message's A2A `contextId` keys
the conversation via `Ask`, so a remote peer's successive turns are remembered — see
[A2A agents](#a2a-agents-agent-to-agent) below.

> Prefer an explicit object? `agent.Conversation(tk)` returns a stateful `*Conversation`; each
> `conv.Send(ctx, prompt)` continues the same in-memory transcript, and `conv.Reset()` clears it.

## Add your own tools

Any Go function or HTTP endpoint becomes a first-class tool alongside MCP and skills.

**Native tool** — a Go function. Derive the schema from a struct via `NativeToolReflect`, or supply
a raw schema with `NativeTool`:

```go
type AddIn struct {
	A float64 `json:"a"`
	B float64 `json:"b"`
}

tk.Register(
	// schema inferred from AddIn's json tags (a field is required unless it has ",omitempty")
	toolnexus.NativeToolReflect("add", "Add two numbers",
		func(ctx context.Context, in AddIn) (string, error) {
			return fmt.Sprintf("%v", in.A+in.B), nil
		}),

	// or a plain map[string]any handler with an explicit schema
	toolnexus.NativeTool("ping", "Reply pong", nil,
		func(ctx context.Context, args map[string]any) (string, error) {
			return "pong", nil
		}),
)
```

A native `fn` returning a `string` becomes `ToolResult{Output: ...}`; returning an `error` becomes
`ToolResult{Output: err.Error(), IsError: true}`.

**HTTP tool** — declare a remote endpoint. `{placeholder}`s in the URL are filled from args,
`${ENV_VAR}` in headers expands at call time (never logged):

```go
tk.Register(toolnexus.HTTPTool(toolnexus.HTTPToolOptions{
	Name:        "create_ticket",
	Description: "Open a support ticket",
	Method:      "POST",
	URL:         "https://api.acme.com/tickets",
	Headers:     map[string]string{"Authorization": "Bearer ${ACME_TOKEN}"},
	InputSchema: toolnexus.JSONSchema{
		"type":       "object",
		"properties": toolnexus.JSONSchema{"title": toolnexus.JSONSchema{"type": "string"}},
		"required":   []string{"title"},
	},
	// Query: []string{...}, Body: "json"|"form"|"raw", Timeout: ms, ResultMode: "text"|"json"|"status+text"
}))
```

You can also seed tools at construction time with `Options.ExtraTools`. `Register` (and
`ExtraTools`) are first-name-wins: a duplicate name is skipped with a warning.

## Built-in tools

toolnexus ships **10 built-in tools** so an agent can act with zero wiring — names + input schemas
match [opencode](https://github.com/anomalyco/opencode):

`bash` · `read` · `write` · `edit` · `grep` · `glob` · `webfetch` · `question` · `apply_patch` ·
`todowrite`

They appear in the tool schema (`ToOpenAI()` / `ToAnthropic()` / `ToGemini()`), like MCP tools —
not the system prompt. **On by default.** Because `bash` / `write` / `edit` / `apply_patch` run
commands and mutate the filesystem, one toggle turns the whole source off on locked-down hosts, or
a per-tool map drops individual builtins on the all-on baseline:

```go
// whole source off (also accepts BuiltinsConfig{Disabled: ...} / {Enabled: ...})
tk, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{Builtins: false})

// per-tool: drop bash, keep the other nine (unknown names ignored; whole-source-off still wins)
tk2, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{
	Builtins: toolnexus.BuiltinsConfig{Tools: map[string]bool{"bash": false}},
})
```

A config file's top-level `builtins` key is honored the same way. A host `ExtraTools` entry with
the same name shadows a builtin.

## A2A agents (agent-to-agent)

Call **remote A2A agents** (each of their skills becomes a tool) and **serve your own toolkit** as
an agent other A2A peers can call. A genuine, minimal subset of real A2A — JSON-RPC 2.0, an Agent
Card at `/.well-known/agent-card.json`, `SendMessage` → poll `GetTask` — so a toolnexus agent
interoperates with real A2A peers. No streaming / push / auth in v1.

**Outbound — call a remote agent.** Each advertised skill becomes a tool named `<agent>_<skill>`
(`source: "a2a"`):

```go
tk, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{
	Agents: []toolnexus.Agent{
		{Card: "https://researcher.example.com/.well-known/agent-card.json"},
	},
})

// or add one at runtime (a bare card URL, or an Agent value):
_, _ = tk.AddAgent(ctx, "https://writer.example.com/.well-known/agent-card.json", nil)
```

`Agent{Card, Headers, Timeout, PollEvery}` — `Headers` support `${ENV}` expansion (never logged);
`Timeout` / `PollEvery` are milliseconds (300000 / 1000 defaults). A config file can also carry an
`agents` block (mirrors `mcpServers`). A failing agent is isolated — logged, contributes no tools,
never fatal.

**Inbound — serve your toolkit as an agent.** Opt in with the `A2A` profile; the Agent Card is
built from your **SKILL.md skills** (never raw tools), and each inbound task is fulfilled through
the client's run loop:

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
		Store: "memory", // "memory" (default) | "file:<dir>" | a custom TaskStore
	},
	// OnTask: func(ev toolnexus.OnTaskEvent) { ... }, // per-task terminal-state callback
})
fmt.Println(handle.URL) // GET /.well-known/agent-card.json ; POST / (SendMessage / GetTask)
defer handle.Stop()
```

An inbound message's A2A **`contextId` keys the conversation** via `Client.Ask` — so a peer's
successive turns are remembered through the client's `ConversationStore` (no `contextId` ⇒ a
stateless `Run`). Served-task persistence is a separate pluggable `TaskStore` — in-memory default,
`"file:<dir>"`, or your own.

## Bring your own loop

Don't want the client loop? Take just the schema adapters and the executor. Emit tool schema in
any provider's format, and route the model's tool calls back through `tk.Execute`:

```go
tools := tk.ToOpenAI() // or tk.ToAnthropic() / tk.ToGemini() — []any, ready to marshal

// when the model returns a tool call {name, args}:
res, _ := tk.Execute(ctx, name, args)
fmt.Println(res.Output, res.IsError) // feed res.Output back into your messages, loop
```

`tk.Execute` routes to the right tool regardless of source (MCP, skill, native, HTTP, builtin,
A2A) and never panics across the boundary — a failure comes back as `ToolResult{IsError: true}`.

**Streaming** the built-in loop instead? `agent.Stream(ctx, prompt, tk)` returns a
`<-chan StreamEvent` — `range` over it for `text` deltas, `tool_call` / `tool_result`, `usage`,
and a terminal `done` or `error` event.

## API at a glance

| Call | What it does |
|------|--------------|
| `CreateToolkit(ctx, Options{McpConfig, SkillsDir, ExtraTools, Builtins, Agents}) (*Toolkit, error)` | Aggregate MCP + skills + built-ins + A2A + your tools into one toolkit |
| `CreateClient(ClientOptions{BaseURL, Style, Model, APIKey, ..., Store}) *Client` | Build the unified LLM client (OpenAI- / Anthropic-style) |
| `client.Run(ctx, prompt, tk) (RunResult, error)` | Stateless one-shot agent loop |
| `client.Ask(ctx, prompt, tk, id) (RunResult, error)` | Stateful: non-empty `id` remembers via the store; empty `id` = `Run` |
| `client.Stream(ctx, prompt, tk) (<-chan StreamEvent, error)` | Same loop, streamed as events |
| `client.Conversation(tk) *Conversation` | Explicit stateful transcript (`.Send`, `.Reset`) |
| `tk.Register(tools ...Tool) *Toolkit` | Add native / HTTP / custom tools (first-name-wins) |
| `tk.AddAgent(ctx, cardURLorAgent, *Agent) (*Toolkit, error)` | Register a remote A2A agent's skills as tools |
| `tk.Serve(addr, ServeOptions{Client, A2A, OnTask}) (*ServeHandle, error)` | Serve the toolkit as an A2A agent (remembers turns by `contextId`) |
| `tk.ToOpenAI() / ToAnthropic() / ToGemini() []any` | Emit tool schema for your own loop |
| `tk.Execute(ctx, name, args) (ToolResult, error)` | Run one tool by name (any source) |
| `tk.SkillsPrompt() string` · `tk.McpStatus() map[string]McpStatus` · `tk.Close()` | Skill catalog · server status · disconnect MCP |
| `NativeTool` · `NativeToolReflect[T]` · `HTTPTool` | Build tools from functions / endpoints |
| `ConversationStore` · `NewInMemoryConversationStore()` | Conversation memory provider (implement for file/db) |

## Examples

```sh
# Connects to the `everything` MCP server (spawns npx), lists tools, prints the
# skill catalog and loads the hello-world skill.
go run ./examples/basic

# Live OpenRouter tool-calling round trip (reads OPENROUTER_API_KEY from env).
OPENROUTER_API_KEY=... go run ./examples/openrouter
```

## Links

- [GUIDE.md](GUIDE.md) — embed toolnexus in an existing Go app
- [SPEC.md](../SPEC.md) — the shared cross-language conformance contract
- [Root README](../README.md) — the pitch + the other four ports (JS · Python · Java · C#)
- [Go package reference](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang)
- Built on [`github.com/mark3labs/mcp-go`](https://github.com/mark3labs/mcp-go)
