# Make your Go app an MCP- & Skills-enabled agent

Add the toolnexus tool stack to an existing Go program. Everything below is a
`toolnexus.Tool` in one `Toolkit`; you either let the built-in client run the loop
or drive your own LLM with the schema adapters.

```sh
go get github.com/muthuishere/toolnexus/golang@latest
```

## 1. MCP servers + skills

Point at an `mcp.json` and a `skills/` folder (formats: see the [root README](../README.md)):

```go
tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
    McpConfig: "./mcp.json",
    SkillsDir: []string{"./skills"},
})
defer tk.Close()
```

Your app now has every MCP server tool + a `skill` tool.

## 2. Your own tools

```go
type AddIn struct{ A, B float64 `json:"a"` /* ,"b" */ }

tk.Register(
    toolnexus.NativeToolReflect("add", "Add two numbers",
        func(ctx context.Context, in AddIn) (string, error) { return fmt.Sprint(in.A + in.B), nil }),

    toolnexus.HTTPTool(toolnexus.HTTPToolOptions{
        Name: "create_ticket", Description: "Open a ticket", Method: "POST",
        URL: "https://api.acme.com/tickets",
        Headers: map[string]string{"Authorization": "Bearer ${ACME_TOKEN}"}, // ${ENV}, never logged
        InputSchema: toolnexus.JSONSchema{"type": "object",
            "properties": toolnexus.JSONSchema{"title": toolnexus.JSONSchema{"type": "string"}},
            "required": []string{"title"}},
    }),
)
```

## 3. Run it — two options

**A) Built-in loop:**
```go
client := toolnexus.CreateClient(toolnexus.ClientOptions{
    BaseURL: "https://openrouter.ai/api/v1", Style: "openai", Model: "openai/gpt-4o-mini",
    // APIKey defaults to OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY
})
out, _ := client.Run(ctx, "Refund order 1234.", tk)
fmt.Println(out.Text)
```

**B) Bring your own LLM client** — take just the schema + executor:
```go
tools := tk.ToOpenAI()            // or ToAnthropic() / ToGemini()
// when the model returns {name, args}:
res, _ := tk.Execute(ctx, name, args)   // feed res.Output back, loop
```

## 4. Just an MCP host (no skills, no loop)

Same job as the "MCP config-parser" libraries, standalone:

```go
src, _ := toolnexus.LoadMcp("./mcp.json")
defer src.Close()
for _, t := range src.Tools() { fmt.Println(t.Name()) }
```

## 5. CLI (no code)

```sh
go install github.com/muthuishere/toolnexus/golang/cmd/toolnexus@latest
toolnexus run --config mcp.json --skills ./skills --base-url https://openrouter.ai/api/v1 --style openai --model openai/gpt-4o-mini
```

## Notes

- Build the toolkit **once** at startup, reuse across requests, `defer tk.Close()`.
- Secrets: use `${ENV_VAR}` in MCP/HTTP headers — read at call time, never logged.
- A broken MCP server is marked `failed` (`tk.McpStatus()`); it never takes the app down.
