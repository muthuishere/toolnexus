package toolnexus

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"
	"testing"
)

// CreateInProcessClient — a model in this process, with no wire configuration.
// openspec/changes/add-in-process-client. Mirrored in all seven ports.

func addTool(t *testing.T) Tool {
	t.Helper()
	return NativeTool("add", "Add two numbers.",
		JSONSchema{"type": "object", "properties": map[string]any{
			"a": map[string]any{"type": "number"}, "b": map[string]any{"type": "number"}},
			"required": []string{"a", "b"}},
		func(_ context.Context, args map[string]any) (string, error) {
			a, _ := args["a"].(float64)
			b, _ := args["b"].(float64)
			return strconv.FormatFloat(a+b, 'f', -1, 64), nil
		})
}

func bareToolkit(t *testing.T) *Toolkit {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func TestInProcessNoWireConfiguration(t *testing.T) {
	tk := bareToolkit(t)
	defer tk.Close()
	// No BaseURL. No APIKey. No Style. That is the whole point.
	c := CreateInProcessClient(InProcessOptions{
		Model: "my-local",
		Generate: func(InProcessRequest) (InProcessResponse, error) {
			return InProcessResponse{Content: "hello from in-process"}, nil
		},
	})
	r, err := c.Run(context.Background(), "hi", tk)
	if err != nil || r.Text != "hello from in-process" || r.Status != "done" {
		t.Fatalf("got %+v err=%v", r, err)
	}
}

func TestInProcessGenerateSeesTheAssembledRequest(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	tk.Register(addTool(t))

	var seen InProcessRequest
	c := CreateInProcessClient(InProcessOptions{
		Model: "my-local", SystemPrompt: "You are terse.",
		Generate: func(req InProcessRequest) (InProcessResponse, error) {
			seen = req
			return InProcessResponse{Content: "ok"}, nil
		},
	})
	if _, err := c.Run(context.Background(), "What is 2 + 3?", tk); err != nil {
		t.Fatal(err)
	}
	if seen.Model != "my-local" {
		t.Fatalf("model = %q", seen.Model)
	}
	if len(seen.Tools) == 0 {
		t.Fatal("tool schemas were not offered")
	}
	blob, _ := json.Marshal(seen.Messages)
	if !strings.Contains(string(blob), "terse") || !strings.Contains(string(blob), "2 + 3") {
		t.Fatalf("messages missing the system prompt or the user turn: %s", blob)
	}
}

func TestInProcessToolCallsLoopBack(t *testing.T) {
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	defer tk.Close()
	tk.Register(addTool(t))

	n := 0
	c := CreateInProcessClient(InProcessOptions{Model: "m",
		Generate: func(req InProcessRequest) (InProcessResponse, error) {
			n++
			if n == 1 {
				return InProcessResponse{ToolCalls: []InProcessToolCall{
					{Name: "add", Arguments: map[string]any{"a": 2, "b": 3}}}}, nil
			}
			return InProcessResponse{Content: "the answer is 5"}, nil
		}})
	r, err := c.Run(context.Background(), "What is 2 + 3?", tk)
	if err != nil {
		t.Fatal(err)
	}
	if len(r.ToolCalls) != 1 || r.ToolCalls[0].Name != "add" || r.ToolCalls[0].Output != "5" {
		t.Fatalf("tool calls = %+v", r.ToolCalls)
	}
}

func TestInProcessArgumentsStructuredOrPreEncoded(t *testing.T) {
	for _, args := range []any{map[string]any{"a": 2, "b": 3}, `{"a":2,"b":3}`} {
		tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
		tk.Register(addTool(t))
		n := 0
		c := CreateInProcessClient(InProcessOptions{Model: "m",
			Generate: func(InProcessRequest) (InProcessResponse, error) {
				n++
				if n == 1 {
					return InProcessResponse{ToolCalls: []InProcessToolCall{{Name: "add", Arguments: args}}}, nil
				}
				return InProcessResponse{Content: "done"}, nil
			}})
		r, err := c.Run(context.Background(), "go", tk)
		if err != nil || len(r.ToolCalls) != 1 || r.ToolCalls[0].Output != "5" {
			t.Fatalf("args %v -> %+v err=%v", args, r.ToolCalls, err)
		}
		tk.Close()
	}
}

func TestInProcessUsageIsOptional(t *testing.T) {
	tk := bareToolkit(t)
	defer tk.Close()

	bare := CreateInProcessClient(InProcessOptions{Model: "m",
		Generate: func(InProcessRequest) (InProcessResponse, error) {
			return InProcessResponse{Content: "x"}, nil
		}})
	r, _ := bare.Run(context.Background(), "hi", tk)
	if r.Usage.TotalTokens != 0 {
		t.Fatalf("absent usage should be zero, got %d", r.Usage.TotalTokens)
	}

	counted := CreateInProcessClient(InProcessOptions{Model: "m",
		Generate: func(InProcessRequest) (InProcessResponse, error) {
			return InProcessResponse{Content: "x",
				Usage: &InProcessUsage{PromptTokens: 11, CompletionTokens: 4}}, nil
		}})
	r2, _ := counted.Run(context.Background(), "hi", tk)
	if r2.Usage.PromptTokens != 11 || r2.Usage.TotalTokens != 15 {
		t.Fatalf("usage = %+v; total should be derived", r2.Usage)
	}
}

func TestInProcessStreamingIsRefusedLoudly(t *testing.T) {
	tk := bareToolkit(t)
	defer tk.Close()
	c := CreateInProcessClient(InProcessOptions{Model: "m",
		Generate: func(InProcessRequest) (InProcessResponse, error) {
			return InProcessResponse{Content: "x"}, nil
		}})
	ch, err := c.Stream(context.Background(), "hi", tk)
	if err != nil {
		if !strings.Contains(err.Error(), "does not support streaming") {
			t.Fatalf("wrong error: %v", err)
		}
		return
	}
	// If the call itself did not fail, the error must arrive on the channel — and no
	// content delta may precede it, or we faked a stream.
	for ev := range ch {
		if ev.Type == "text" {
			t.Fatal("emitted a text delta instead of refusing")
		}
		if ev.Err != nil && strings.Contains(ev.Err.Error(), "does not support streaming") {
			return
		}
	}
	t.Fatal("streaming was neither refused nor errored")
}
