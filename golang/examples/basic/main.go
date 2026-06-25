// Minimal end-to-end example. Mirrors js/examples/basic.ts.
//
//	go run ./examples/basic
//
// Uses the shared sample fixtures in ../../../examples/.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"path/filepath"
	"runtime"

	"github.com/muthuishere/toolnexus/golang"
)

func main() {
	// Resolve the shared fixtures relative to this source file.
	_, thisFile, _, _ := runtime.Caller(0)
	root := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "examples")
	mcpConfig := filepath.Join(root, "mcp.json")
	skillsDir := filepath.Join(root, "skills")

	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
		McpConfig: mcpConfig,
		SkillsDir: []string{skillsDir},
	})
	if err != nil {
		log.Fatal(err)
	}
	defer tk.Close()

	fmt.Printf("MCP status: %v\n", tk.McpStatus())

	fmt.Print("Tools: [")
	for i, t := range tk.Tools() {
		if i > 0 {
			fmt.Print(" ")
		}
		fmt.Printf("%s (%s)", t.Name, t.Source)
	}
	fmt.Println("]")

	fmt.Println("\nSystem-prompt skill catalog:")
	fmt.Println(tk.SkillsPrompt())

	fmt.Println("\nOpenAI tool schema (first 2):")
	openai := tk.ToOpenAI()
	if len(openai) > 2 {
		openai = openai[:2]
	}
	b, _ := json.MarshalIndent(openai, "", "  ")
	fmt.Println(string(b))

	// Load a skill (progressive disclosure) — works with no MCP servers running.
	res, err := tk.Execute(ctx, "skill", map[string]any{"name": "hello-world"})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("\nskill(hello-world) ->")
	fmt.Println(res.Output)
}
