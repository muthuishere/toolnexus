// Streaming: live text deltas + tool_call / tool_result / usage / done events.
// Mirrors js/examples/streaming.ts.
//
//	OPENROUTER_API_KEY=... go run ./examples/streaming
package main

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/muthuishere/toolnexus/golang"
)

func main() {
	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{})
	if err != nil {
		fmt.Println("toolkit error:", err)
		os.Exit(1)
	}
	defer tk.Close()

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

	key := os.Getenv("OPENROUTER_API_KEY")
	if key == "" {
		fmt.Println("no OPENROUTER_API_KEY — skipping")
		return
	}
	model := os.Getenv("OPENROUTER_MODEL")
	if model == "" {
		model = "openai/gpt-4o-mini"
	}

	agent := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL: "https://openrouter.ai/api/v1",
		Style:   toolnexus.StyleOpenAI,
		Model:   model,
		APIKey:  key,
	})

	textDeltas, toolCallEv, toolResultEv := 0, 0, 0
	var streamedText strings.Builder
	var done *toolnexus.RunResult

	fmt.Print("stream: ")
	ch, err := agent.Stream(ctx, "Use the add tool to compute 21 + 21, then say the result in one short sentence.", tk)
	if err != nil {
		fmt.Println("\nstream error:", err)
		os.Exit(1)
	}
	for ev := range ch {
		switch ev.Type {
		case "text":
			textDeltas++
			streamedText.WriteString(ev.Delta)
			fmt.Print(ev.Delta)
		case "tool_call":
			toolCallEv++
			fmt.Printf("\n[tool_call] %s(%v)\n", ev.Name, ev.Args)
		case "tool_result":
			toolResultEv++
			fmt.Printf("[tool_result] %s -> %s\n", ev.Name, ev.Output)
		case "usage":
			fmt.Printf("[usage] %+v\n", *ev.Usage)
		case "done":
			done = ev.Result
		case "error":
			fmt.Println("\nstream error:", ev.Err)
			os.Exit(1)
		}
	}

	if done == nil {
		fmt.Println("\nno done event")
		os.Exit(1)
	}
	fmt.Printf("\n\nsummary: textDeltas=%d toolCall=%d toolResult=%d | done.toolCallCount=%d turns=%d usage=%+v\n",
		textDeltas, toolCallEv, toolResultEv, done.ToolCallCount, done.Turns, done.Usage)

	ok := textDeltas > 1 && toolCallEv >= 1 && toolResultEv >= 1 &&
		done.Usage.TotalTokens > 0 && strings.Contains(done.Text, "42")
	if !ok {
		fmt.Println("streaming did not produce deltas + tool events + final usage/answer")
		os.Exit(1)
	}
	fmt.Println("streaming verified: incremental text deltas, tool_call/tool_result events, final done with usage")
}
