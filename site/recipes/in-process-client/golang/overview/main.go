package main

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	tn "github.com/muthuishere/toolnexus/golang"
)

type addArgs struct {
	A float64 `json:"a"`
	B float64 `json:"b"`
}

func main() {
	ctx := context.Background()
	add := tn.NativeToolReflect("add", "Add two numbers.", func(_ context.Context, in addArgs) (string, error) {
		return strconv.FormatFloat(in.A+in.B, 'f', -1, 64), nil
	})
	toolkit, err := tn.CreateToolkit(ctx, tn.Options{Builtins: false, ExtraTools: []tn.Tool{add}})
	if err != nil {
		panic(err)
	}

	// GenerateStream: ctx-aware, and every emitted text delta reaches Client.Stream.
	client := tn.CreateInProcessClient(tn.InProcessOptions{
		Model: "my-model",
		GenerateStream: func(ctx context.Context, req tn.InProcessRequest, emit func(tn.StreamEvent)) (tn.InProcessResponse, error) {
			last, _ := req.Messages[len(req.Messages)-1].(map[string]any)
			if last["role"] != "tool" {
				return tn.InProcessResponse{ToolCalls: []tn.InProcessToolCall{
					{Name: "add", Arguments: map[string]any{"a": 2, "b": 3}},
				}}, nil
			}
			words := []string{"The ", "sum ", "is ", fmt.Sprint(last["content"]), "."}
			for _, w := range words {
				emit(tn.StreamEvent{Type: "text", Delta: w}) // deltas must add up to Content
			}
			return tn.InProcessResponse{Content: strings.Join(words, "")}, nil
		},
	})

	events, err := client.Stream(ctx, "What is 2 + 3?", toolkit)
	if err != nil {
		panic(err)
	}
	for ev := range events {
		switch ev.Type {
		case "text":
			fmt.Print(ev.Delta)
		case "tool_call":
			fmt.Printf("[calling %s]\n", ev.Name)
		case "error":
			panic(ev.Err)
		}
	}
	fmt.Println()
}
