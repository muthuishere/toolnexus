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
directory, and up to 10 sampled sibling files.

## Examples

```sh
# Connects to the `everything` MCP server (spawns npx), lists tools, prints the
# skill catalog and loads the hello-world skill.
go run ./examples/basic

# Live OpenRouter tool-calling round trip (reads OPENROUTER_API_KEY from env).
OPENROUTER_API_KEY=... go run ./examples/openrouter
```

## API

- `CreateToolkit(ctx, Options{McpConfig, SkillsDir, ExtraTools}) (*Toolkit, error)`
- `Toolkit.Tools() []Tool`
- `Toolkit.Get(name) (Tool, bool)`
- `Toolkit.Execute(ctx, name, args) (ToolResult, error)`
- `Toolkit.SkillsPrompt() string`
- `Toolkit.McpStatus() map[string]McpStatus`
- `Toolkit.ToOpenAI() / ToAnthropic() / ToGemini() []any`
- `Toolkit.Close()`
