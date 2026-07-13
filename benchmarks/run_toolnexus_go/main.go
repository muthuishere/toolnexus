// toolnexus (Go port) benchmark runner.
//
// Same mock LLM + same shared stdio MCP server as the other runners.
//
//	cold init   = CreateToolkit (spawn MCP + discover) + CreateClient
//	per-request = client.Run(ctx, question, tk)
//
// Config via -config: "mcp" (3 MCP tools, builtins off) or "full"
// (3 MCP tools + 1 skill + 1 native tool). Prints a JSON result line.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

func main() {
	config := flag.String("config", "mcp", "mcp|full|native")
	runs := flag.Int("runs", 30, "measured runs")
	warmup := flag.Int("warmup", 3, "warmup runs")
	flag.Parse()

	repo := os.Getenv("BENCH_REPO")
	mcpPy := os.Getenv("MCP_PYTHON")
	mockURL := os.Getenv("MOCK_URL")
	if mockURL == "" {
		mockURL = "http://127.0.0.1:8900"
	}
	os.Setenv("OPENAI_API_KEY", "sk-mock-not-a-real-key")
	const question = "What's the weather in Paris and what is 2+2?"

	mcpConfig := map[string]any{
		"mcpServers": map[string]any{
			"bench-tools": map[string]any{
				"type":    "local",
				"command": []any{mcpPy, repo + "/benchmarks/mcp_server.py"},
				"enabled": true,
				"timeout": 30000,
			},
		},
	}

	ctx := context.Background()

	// ---- cold init ----
	t0 := time.Now()
	var opts toolnexus.Options
	if *config == "native" {
		// Loop-overhead measurement using NATIVE tools that the mock actually
		// calls (get_weather + add). Used because the Go port cannot currently
		// hold a live stdio-MCP session (see benchmarks/README.md "Go MCP").
		opts = toolnexus.Options{Builtins: false,
			ExtraTools: []toolnexus.Tool{weatherTool(), addTool()}}
	} else {
		opts = toolnexus.Options{McpConfig: mcpConfig, Builtins: false}
		if *config == "full" {
			opts.SkillsDir = []string{repo + "/examples/skills"}
			opts.ExtraTools = []toolnexus.Tool{multiplyTool()}
		}
	}
	tk, err := toolnexus.CreateToolkit(ctx, opts)
	if err != nil {
		fmt.Fprintln(os.Stderr, "toolkit error:", err)
		os.Exit(1)
	}
	defer tk.Close()
	client := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL: mockURL, Style: toolnexus.StyleOpenAI, Model: "mock-model",
	})
	initMs := float64(time.Since(t0).Microseconds()) / 1000.0
	toolCount := len(tk.Tools())
	if os.Getenv("BENCH_DEBUG") != "" {
		fmt.Fprintln(os.Stderr, "MCP status:", tk.McpStatus())
		for _, t := range tk.Tools() {
			fmt.Fprintln(os.Stderr, "tool:", t.Name, t.Source)
		}
	}

	// ---- warmup ----
	for i := 0; i < *warmup; i++ {
		if _, err := client.Run(ctx, question, tk); err != nil {
			fmt.Fprintln(os.Stderr, "warmup run error:", err)
			os.Exit(1)
		}
	}

	// ---- measured ----
	lat := make([]float64, 0, *runs)
	finalText := ""
	for i := 0; i < *runs; i++ {
		s := time.Now()
		res, err := client.Run(ctx, question, tk)
		if err != nil {
			fmt.Fprintln(os.Stderr, "run error:", err)
			os.Exit(1)
		}
		lat = append(lat, float64(time.Since(s).Microseconds())/1000.0)
		finalText = res.Text
	}

	out := map[string]any{
		"framework":     "toolnexus-go-" + *config,
		"language":      "go",
		"init_ms":       initMs,
		"tool_count":    toolCount,
		"latencies_ms":  lat,
		"final_text":    finalText,
	}
	b, _ := json.Marshal(out)
	fmt.Println(string(b))
}

func multiplyTool() toolnexus.Tool {
	schema := map[string]any{
		"type": "object",
		"properties": map[string]any{
			"a": map[string]any{"type": "integer"},
			"b": map[string]any{"type": "integer"},
		},
		"required": []any{"a", "b"},
	}
	return toolnexus.NativeTool("multiply", "Multiply two integers locally.", schema,
		func(ctx context.Context, args map[string]any) (string, error) {
			a, _ := args["a"].(float64)
			b, _ := args["b"].(float64)
			return fmt.Sprintf("%d", int(a)*int(b)), nil
		})
}

func weatherTool() toolnexus.Tool {
	schema := map[string]any{"type": "object",
		"properties": map[string]any{"city": map[string]any{"type": "string"}},
		"required":   []any{"city"}}
	return toolnexus.NativeTool("get_weather", "Get the current weather for a city.", schema,
		func(ctx context.Context, args map[string]any) (string, error) {
			return fmt.Sprintf("The weather in %v is Sunny, 22C.", args["city"]), nil
		})
}

func addTool() toolnexus.Tool {
	schema := map[string]any{"type": "object",
		"properties": map[string]any{"a": map[string]any{"type": "integer"},
			"b": map[string]any{"type": "integer"}}, "required": []any{"a", "b"}}
	return toolnexus.NativeTool("add", "Add two integers.", schema,
		func(ctx context.Context, args map[string]any) (string, error) {
			a, _ := args["a"].(float64)
			b, _ := args["b"].(float64)
			return fmt.Sprintf("%d", int(a)+int(b)), nil
		})
}
