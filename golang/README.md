# toolnexus (Go)

[![Go Reference](https://pkg.go.dev/badge/github.com/muthuishere/toolnexus/golang.svg)](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `Run()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework, not a toy that falls over the moment you need streaming or a
> retry. Idiomatic Go: struct options, exported functions, `context.Context`, `defer tk.Close()`.

The Go port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Java, C#, Elixir and Clojure**. Built on
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
		// APIKey defaults to $OPENROUTER_API_KEY / $OPENAI_API_KEY / $ANTHROPIC_API_KEY
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

Contract across all seven ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
