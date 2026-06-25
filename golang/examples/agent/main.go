// Full agent example: MCP tools + skills + a native tool + an HTTP tool, driven
// by the unified client host loop. Mirrors js/examples/agent.ts.
//
//	go run ./examples/agent                 # lists tools, skips live loop without a key
//	OPENROUTER_API_KEY=... go run ./examples/agent
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	"github.com/muthuishere/toolnexus/golang"
)

// addArgs is the reflected input schema for the native `add` tool.
type addArgs struct {
	A float64 `json:"a"`
	B float64 `json:"b"`
}

func main() {
	_, thisFile, _, _ := runtime.Caller(0)
	root := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "examples")

	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
		McpConfig: filepath.Join(root, "mcp.json"),
		SkillsDir: []string{filepath.Join(root, "skills")},
	})
	if err != nil {
		log.Fatal(err)
	}
	defer tk.Close()

	// a native (annotation) tool — schema derived from the struct via json tags
	tk.Register(toolnexus.NativeToolReflect("add",
		"Add two numbers and return the sum.",
		func(_ context.Context, in addArgs) (string, error) {
			return strconv.FormatFloat(in.A+in.B, 'f', -1, 64), nil
		},
	))

	// an HTTP tool (public test API, no auth)
	tk.Register(toolnexus.HTTPTool(toolnexus.HTTPToolOptions{
		Name:        "get_post",
		Description: "Fetch a placeholder blog post by id from jsonplaceholder.",
		Method:      "GET",
		URL:         "https://jsonplaceholder.typicode.com/posts/{id}",
		InputSchema: toolnexus.JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"id": map[string]any{"type": "number", "description": "post id 1-100"},
			},
			"required":             []string{"id"},
			"additionalProperties": false,
		},
	}))

	fmt.Print("Tools: [")
	for i, t := range tk.Tools() {
		if i > 0 {
			fmt.Print(" ")
		}
		fmt.Printf("%s (%s)", t.Name, t.Source)
	}
	fmt.Println("]")

	key := os.Getenv("OPENROUTER_API_KEY")
	if key == "" {
		fmt.Println("\n(no OPENROUTER_API_KEY — skipping live loop)")
		return
	}

	model := os.Getenv("OPENROUTER_MODEL")
	if model == "" {
		model = "openai/gpt-4o-mini"
	}
	agent := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL:      "https://openrouter.ai/api/v1",
		Style:        toolnexus.StyleOpenAI,
		Model:        model,
		APIKey:       key,
		SystemPrompt: "You are a precise agent. Use tools to compute and fetch facts.",
	})

	res, err := agent.Run(ctx,
		"What is 21 + 21? Use the add tool. Then fetch post id 1 and tell me its title.", tk)
	if err != nil {
		log.Fatal(err)
	}

	names := make([]string, 0, len(res.ToolCalls))
	for _, c := range res.ToolCalls {
		names = append(names, c.Name)
	}
	fmt.Println("\nTool calls:", names)
	fmt.Println("\nFINAL:\n" + res.Text)

	if !strings.Contains(res.Text, "42") {
		log.Fatal("expected 42 in answer")
	}
	fmt.Println("\nGo agent loop (native + http + mcp + skills) OK")
}
