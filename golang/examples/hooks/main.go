// Lifecycle hooks: BeforeLLM / AfterLLM / BeforeTool / AfterTool — observe,
// mutate, and short-circuit. Mirrors js/examples/hooks.ts.
//
//	go run ./examples/hooks                  # builds + skips the live section
//	OPENROUTER_API_KEY=... go run ./examples/hooks
//
// The BeforeTool hook DENIES get_post (short-circuit), so the real get_post tool
// fn never runs. We prove that with a package-level counter incremented inside
// the get_post fn; after the run it must be 0.
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"sync"

	"github.com/muthuishere/toolnexus/golang"
)

// getPostHits counts how many times the real get_post fn actually executed. The
// BeforeTool deny hook must short-circuit it to 0. Guarded because tool calls in
// a turn run concurrently.
var (
	getPostHits int
	hitsMu      sync.Mutex
)

func main() {
	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{})
	if err != nil {
		log.Fatal(err)
	}
	defer tk.Close()

	// `add` — a plain native tool.
	tk.Register(toolnexus.NativeTool("add", "Add two numbers.",
		toolnexus.JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"a": map[string]any{"type": "number"},
				"b": map[string]any{"type": "number"},
			},
			"required": []string{"a", "b"},
		},
		func(_ context.Context, args map[string]any) (string, error) {
			a, _ := args["a"].(float64)
			b, _ := args["b"].(float64)
			return strconv.FormatFloat(a+b, 'f', -1, 64), nil
		},
	))

	// `get_post` — a native tool standing in for an HTTP fetch. Its body counts
	// real executions so we can prove the deny hook short-circuited it. It must
	// never run (the BeforeTool hook returns a Result for it).
	tk.Register(toolnexus.NativeTool("get_post", "Fetch a post by id.",
		toolnexus.JSONSchema{
			"type":       "object",
			"properties": map[string]any{"id": map[string]any{"type": "number"}},
			"required":   []string{"id"},
		},
		func(_ context.Context, args map[string]any) (string, error) {
			hitsMu.Lock()
			getPostHits++
			hitsMu.Unlock()
			return "should never reach here", nil
		},
	))

	key := os.Getenv("OPENROUTER_API_KEY")
	if key == "" {
		fmt.Println("no OPENROUTER_API_KEY — skipping live loop (build OK)")
		return
	}

	model := os.Getenv("OPENROUTER_MODEL")
	if model == "" {
		model = "openai/gpt-4o-mini"
	}

	// Hook counters. beforeTool/afterTool can fire concurrently within a turn, so
	// guard the counts.
	var (
		countMu                                                     sync.Mutex
		beforeLLM, afterLLM, beforeTool, afterTool, denied int
	)

	agent := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL:      "https://openrouter.ai/api/v1",
		Style:        toolnexus.StyleOpenAI,
		Model:        model,
		APIKey:       key,
		SystemPrompt: "You are an agent. Use tools when asked.",
		Hooks: &toolnexus.Hooks{
			BeforeLLM: func(_ context.Context, ev toolnexus.BeforeLLMEvent) (*toolnexus.LLMOverride, error) {
				countMu.Lock()
				beforeLLM++
				countMu.Unlock()
				fmt.Printf("  [beforeLLM] turn %d\n", ev.Turn)
				return nil, nil
			},
			AfterLLM: func(_ context.Context, ev toolnexus.AfterLLMEvent) error {
				countMu.Lock()
				afterLLM++
				countMu.Unlock()
				fmt.Printf("  [afterLLM] turn %d usage=%v\n", ev.Turn, ev.Response["usage"])
				return nil
			},
			BeforeTool: func(_ context.Context, ev toolnexus.BeforeToolEvent) (*toolnexus.ToolOverride, error) {
				countMu.Lock()
				beforeTool++
				countMu.Unlock()
				fmt.Printf("  [beforeTool] %s(%v)\n", ev.Name, ev.Args)
				if ev.Name == "get_post" {
					countMu.Lock()
					denied++
					countMu.Unlock()
					// SHORT-CIRCUIT: deny the tool; the real fn never runs.
					return &toolnexus.ToolOverride{Result: &toolnexus.ToolResult{
						Output:  "DENIED: get_post is blocked by policy",
						IsError: true,
					}}, nil
				}
				return nil, nil
			},
			AfterTool: func(_ context.Context, ev toolnexus.AfterToolEvent) (*toolnexus.ToolOverride, error) {
				countMu.Lock()
				afterTool++
				countMu.Unlock()
				out := ev.Result.Output
				if len(out) > 40 {
					out = out[:40]
				}
				fmt.Printf("  [afterTool] %s -> %s\n", ev.Name, out)
				return nil, nil
			},
		},
	})

	out, err := agent.Run(ctx,
		"Add 2 and 3 with the add tool, then fetch post id 1 with get_post.", tk)
	if err != nil {
		log.Fatal(err)
	}

	final := strings.ReplaceAll(out.Text, "\n", " ")
	if len(final) > 100 {
		final = final[:100]
	}
	fmt.Println("\nFINAL:", final)
	fmt.Printf("hook counts: beforeLLM=%d afterLLM=%d beforeTool=%d afterTool=%d denied=%d | get_post real hits=%d | usage=%+v\n",
		beforeLLM, afterLLM, beforeTool, afterTool, denied, getPostHits, out.Usage)

	ok := beforeLLM >= 1 && afterLLM >= 1 &&
		beforeTool >= 2 && afterTool >= 1 &&
		denied >= 1 && getPostHits == 0 // deny short-circuited the real call
	if !ok {
		log.Fatal("hooks did not all fire / short-circuit failed")
	}
	fmt.Println("\nall four hooks fired; beforeTool short-circuited get_post (0 real hits)")
}
