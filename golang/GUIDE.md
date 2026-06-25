# Make your Go application an MCP- and Skills-enabled agent

This guide shows how to take an **existing Go program** and give it the full agent
stack with `toolnexus`: MCP servers, Anthropic-style agent skills, your own
functions as tools, HTTP/REST endpoints as tools, and a tool-calling loop against
any LLM — in a few lines.

> Mental model: everything is a **Tool**. MCP server tools, skills, your Go
> functions, and remote HTTP endpoints all become the same `toolnexus.Tool`, live
> in one `Toolkit`, and are offered to any model. You either let the built-in
> client run the loop, or you drive your own LLM client with the schema adapters.

---

## 1. Install

```sh
go get github.com/muthuishere/toolnexus/golang@latest
```

```go
import toolnexus "github.com/muthuishere/toolnexus/golang"
```

Requires Go 1.23+.

---

## 2. The smallest possible upgrade — add MCP + skills

Drop an `mcp.json` and a `skills/` folder next to your app, then build a toolkit.

`mcp.json`:
```jsonc
{
  "mcpServers": {
    "fs": { "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"] },
    "company-api": { "type": "remote", "url": "https://api.acme.com/mcp",
                     "headers": { "Authorization": "Bearer ${ACME_TOKEN}" } }
  }
}
```

`skills/refunds/SKILL.md`:
```markdown
---
name: process-refund
description: Use when a customer asks for a refund. Walks the refund policy + steps.
---
# Refund workflow
1. Verify the order ...
2. ...
```

In your app:
```go
ctx := context.Background()
tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
    McpConfig: "./mcp.json",          // path, raw JSON, or a parsed map/McpConfig
    SkillsDir: []string{"./skills"},  // one or more skill roots
})
if err != nil { log.Fatal(err) }
defer tk.Close()                       // tears down MCP servers (stdio child procs too)
```

Your app now has every MCP server tool plus a `skill` tool, all in `tk.Tools()`.

---

## 2b. À la carte — just an MCP host (config parser + connector)

Don't want skills or the loop? Use only the MCP source. This is the same job the
"MCP host / config-parser" libraries do — parse `mcp.json`, connect, hand you the
tools — and toolnexus does it standalone, no other parts attached:

```go
src, err := toolnexus.LoadMcp("./mcp.json")   // path, raw JSON, McpConfig, or map
if err != nil { log.Fatal(err) }
defer src.Close()

fmt.Println(src.Status())     // map[server]connected|failed|disabled
for _, t := range src.Tools() {
    fmt.Println(t.Name(), t.Description())   // each MCP server tool, as a uniform Tool
}
res, _ := src.Tools()[0].Execute(ctx, map[string]any{ /* args */ }, toolnexus.ToolContext{})
```

(JS: `loadMcp(config)`, Python: `await load_mcp(config)` — same shape.) Skills,
native/HTTP tools, and the client are all opt-in on top; the MCP host works alone.

---

## 3. Add your own functions as tools (annotation-style)

Use struct-tag reflection — Go's equivalent of an annotation — so you never
hand-write JSON Schema:

```go
type AddInput struct {
    A float64 `json:"a" jsonschema:"description=first number"`
    B float64 `json:"b" jsonschema:"description=second number"`
}

tk.Register(
    toolnexus.NativeToolReflect("add", "Add two numbers",
        func(ctx context.Context, in AddInput) (string, error) {
            return fmt.Sprintf("%v", in.A+in.B), nil
        }),
)
```

Prefer to control the schema yourself? Use the explicit form:

```go
tk.Register(toolnexus.NativeTool("now", "Current server time",
    toolnexus.JSONSchema{"type": "object", "properties": toolnexus.JSONSchema{}},
    func(ctx context.Context, args map[string]any) (string, error) {
        return time.Now().Format(time.RFC3339), nil
    }))
```

A returned `error` becomes `ToolResult{IsError: true}` automatically.

---

## 4. Wrap a REST API as a tool

```go
tk.Register(toolnexus.HTTPTool(toolnexus.HTTPToolOptions{
    Name: "create_ticket", Description: "Open a support ticket",
    Method: "POST", URL: "https://api.acme.com/tickets",
    Headers: map[string]string{"Authorization": "Bearer ${ACME_TOKEN}"}, // ${ENV} expands; never logged
    InputSchema: toolnexus.JSONSchema{
        "type": "object",
        "properties": toolnexus.JSONSchema{"title": toolnexus.JSONSchema{"type": "string"}},
        "required": []string{"title"},
    },
}))
```

`{placeholder}` segments in the URL are filled from args; for `GET` the remaining
args become the querystring, otherwise they become the JSON body.

---

## 5. Two ways to actually run the agent

### A) Let toolnexus run the loop (simplest)

```go
client := toolnexus.CreateClient(toolnexus.ClientOptions{
    BaseURL: "https://openrouter.ai/api/v1", // any OpenAI- or Anthropic-style endpoint
    Style:   "openai",                        // or "anthropic"
    Model:   "openai/gpt-4o-mini",
    // APIKey defaults to OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY
    SystemPrompt: "You are Acme's support agent.",
})

out, err := client.Run(ctx, "Refund order 1234 for the customer.", tk)
if err != nil { log.Fatal(err) }
fmt.Println(out.Text)        // final answer; out.ToolCalls lists what ran
```

The client injects `tk.SkillsPrompt()` into the system message, calls the model,
runs any tool calls via `tk.Execute`, feeds results back, and repeats up to
`MaxTurns` (default 10).

### B) Keep your own LLM client, just borrow the tools

If you already call an LLM SDK, take only the schema + execute:

```go
tools := tk.ToOpenAI()             // or tk.ToAnthropic() / tk.ToGemini()
// ... send `tools` to your model; when it returns a tool call {name, args}:
res, _ := tk.Execute(ctx, name, args)
// feed res.Output back to the model as the tool result, loop.
```

---

## 6. Putting it in a real service (an `/agent` HTTP endpoint)

```go
func main() {
    ctx := context.Background()
    tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
        McpConfig: "./mcp.json",
        SkillsDir: []string{"./skills"},
    })
    if err != nil { log.Fatal(err) }
    defer tk.Close()

    tk.Register( /* your NativeToolReflect + HTTPTool tools */ )

    client := toolnexus.CreateClient(toolnexus.ClientOptions{
        BaseURL: os.Getenv("LLM_BASE_URL"), Style: "openai",
        Model: os.Getenv("LLM_MODEL"), SystemPrompt: "You are our in-app agent.",
    })

    http.HandleFunc("/agent", func(w http.ResponseWriter, r *http.Request) {
        var body struct{ Prompt string `json:"prompt"` }
        json.NewDecoder(r.Body).Decode(&body)
        out, err := client.Run(r.Context(), body.Prompt, tk)
        if err != nil { http.Error(w, err.Error(), 500); return }
        json.NewEncoder(w).Encode(map[string]any{"answer": out.Text, "tools": out.ToolCalls})
    })

    log.Fatal(http.ListenAndServe(":8080", nil))
}
```

Build the toolkit **once** at startup (MCP servers connect then) and reuse it
across requests; `Run` is safe to call per request. Call `tk.Close()` on shutdown.

---

## 7. Or skip code entirely — the CLI

The same stack ships as a binary if you just want an interactive agent:

```sh
go install github.com/muthuishere/toolnexus/golang/cmd/toolnexus@latest

toolnexus run --config mcp.json --skills ./skills \
  --base-url https://openrouter.ai/api/v1 --style openai --model openai/gpt-4o-mini
# > continuous REPL: you type, it uses MCP/skills/your tools, it answers, loop

toolnexus tools --config mcp.json --skills ./skills   # list resolved tools and exit
```

---

## 8. Production notes

- **Failures are isolated.** A broken MCP server is marked `failed` (see
  `tk.McpStatus()`) and the rest of the toolkit still works — one bad server never
  takes the app down.
- **Secrets stay in the environment.** Use `${ENV_VAR}` in MCP/HTTP headers; the
  value is read at call time from `os.Getenv` and is never logged.
- **Timeouts.** Per-server `timeout` (ms) in `mcp.json` (default 30000); pass a
  `context.Context` with a deadline to `Run`/`Execute` to bound a whole turn.
- **Lifecycle.** One toolkit per process; `defer tk.Close()`.
- **Cross-language parity.** This Go API mirrors the JS/Python/Java ports exactly
  (pinned by [`../SPEC.md`](../SPEC.md) §0), so a polyglot team gets identical
  behavior everywhere.
```
