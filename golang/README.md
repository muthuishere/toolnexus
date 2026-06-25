# toolnexus (Go)

Give any LLM the same two dynamic capabilities opencode has:

1. **Dynamic MCP servers** — read an MCP config, connect to every server
   (local stdio + remote streamable-HTTP/SSE), and expose each server tool as a
   uniform `Tool`.
2. **Dynamic agent skills** — read a skills folder (`**/SKILL.md`) and expose a
   single `skill` tool that loads a skill's instructions + resources on demand
   (progressive disclosure).

The output is a single **Toolkit** of uniform `Tool`s plus adapters that emit the
tool schema in OpenAI / Anthropic / Gemini formats. Wire the schema into your LLM
call; when the model asks for a tool, call `toolkit.Execute(ctx, name, args)`.

This is the Go implementation of the shared [`../SPEC.md`](../SPEC.md) contract,
built on [`github.com/mark3labs/mcp-go`](https://github.com/mark3labs/mcp-go).

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
