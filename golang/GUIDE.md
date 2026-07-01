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

Your app now has every MCP server tool, a `skill` tool, and the **10 built-in
tools** (`bash`, `read`, `write`, `edit`, `grep`, `glob`, `webfetch`,
`question`, `apply_patch`, `todowrite`) — on by default. Turn the whole built-in
source off with `Builtins: false` in `Options`, or drop individual builtins with a
per-tool map, e.g. `Builtins: toolnexus.BuiltinsConfig{Tools: map[string]bool{"bash": false}}`
(all-on baseline; unknown names ignored; whole-source-off still wins). Reach for
these on locked-down hosts, since `bash`/`write`/`edit`/`apply_patch` run commands
and mutate the filesystem.

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

**Memory across turns** — `Run` is stateless; `Ask(ctx, prompt, tk, id)` remembers a conversation
by `id` through a `ConversationStore` (in-memory by default). A non-empty `id` loads → runs →
saves that id's transcript; an empty `id` is identical to `Run`. Implement `ConversationStore`
(`Get(id) ([]any, error)` / `Save(id, messages) error`) and pass it as `ClientOptions.Store` to
persist across processes.
```go
r1, _ := client.Ask(ctx, "My name is Muthu.", tk, "user-42")
r2, _ := client.Ask(ctx, "What's my name?", tk, "user-42") // recalls turn 1 → "Muthu"
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
toolnexus tools --config mcp.json --skills ./skills   # lists resolved tools, incl. the 10 builtins
```

## 6. A2A agents (call remote agents / be one)

Point at a remote agent's card and its skills become tools; serve your own toolkit so
other A2A peers can call it. Minimal subset of real A2A — JSON-RPC 2.0, card at
`/.well-known/agent-card.json`, `SendMessage` → poll `GetTask`; no streaming/auth in v1.

```go
// outbound: each remote skill → a tool named <agent>_<skill> (source "a2a")
tk, _ := toolnexus.CreateToolkit(ctx, toolnexus.Options{
    Agents: []toolnexus.Agent{{Card: "https://peer.example.com/.well-known/agent-card.json"}},
})
_, _ = tk.AddAgent(ctx, "https://other.example.com/.well-known/agent-card.json", nil) // or at runtime

// inbound: serve this toolkit as an agent (card built from SKILL.md skills, never raw tools)
handle, _ := tk.Serve("127.0.0.1:0", toolnexus.ServeOptions{
    Client: client, // a *toolnexus.Client from CreateClient
    A2A:    &toolnexus.A2AConfig{Name: "my-agent", Store: "memory"}, // "file:<dir>" / custom TaskStore too
})
defer handle.Stop()
```

A served message's A2A `contextId` keys the conversation via `Client.Ask`, so a peer's successive
turns are remembered through the client's `ConversationStore` (no `contextId` ⇒ a stateless `Run`).
The `Store` above is the separate, pluggable *TaskStore* for served-task persistence.

See [README.md](README.md#a2a-agents-agent-to-agent) for the full option set.

## Notes

- Build the toolkit **once** at startup, reuse across requests, `defer tk.Close()`.
- Secrets: use `${ENV_VAR}` in MCP/HTTP headers — read at call time, never logged.
- A broken MCP server is marked `failed` (`tk.McpStatus()`); it never takes the app down.
