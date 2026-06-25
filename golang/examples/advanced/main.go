// Verify parallel tool calling (many calls in one assistant turn) and chained
// tool calling (a later call depends on an earlier, opaque result, across turns).
// Mirrors js/examples/advanced.ts.
//
//	go run ./examples/advanced                 # builds + skips the live section
//	OPENROUTER_API_KEY=... go run ./examples/advanced
//
// How we inspect RunResult.Messages: it is a []any holding the provider message
// objects the client appended. For the OpenAI style each element is a
// map[string]any; an assistant turn that issued tool calls carries a "tool_calls"
// key whose value is a []any of call objects (each a map[string]any with a
// "function" map). We type-assert to map[string]any and read msg["role"] /
// msg["tool_calls"] to count calls per assistant turn. (A JSON round-trip would
// work too, but the appended objects are already plain maps, so direct assertion
// is enough and allocation-free.)
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strconv"

	"github.com/muthuishere/toolnexus/golang"
)

// addArgs is the reflected input schema for the native `add` tool.
type addArgs struct {
	A float64 `json:"a"`
	B float64 `json:"b"`
}

// asMap coerces a message element to map[string]any, falling back to a JSON
// round-trip for non-map provider objects.
func asMap(v any) map[string]any {
	if m, ok := v.(map[string]any); ok {
		return m
	}
	raw, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	var m map[string]any
	if json.Unmarshal(raw, &m) != nil {
		return nil
	}
	return m
}

// toolCallsLen returns the number of tool calls on an OpenAI-style assistant
// message, or 0 if it has none.
func toolCallsLen(msg map[string]any) int {
	if msg["role"] != "assistant" {
		return 0
	}
	calls, ok := msg["tool_calls"].([]any)
	if !ok {
		return 0
	}
	return len(calls)
}

// maxParallel is the largest number of tool calls the model emitted in a single
// assistant turn.
func maxParallel(messages []any) int {
	m := 0
	for _, raw := range messages {
		msg := asMap(raw)
		if msg == nil {
			continue
		}
		if n := toolCallsLen(msg); n > m {
			m = n
		}
	}
	return m
}

// toolTurns counts assistant turns that issued tool calls (chain depth).
func toolTurns(messages []any) int {
	n := 0
	for _, raw := range messages {
		msg := asMap(raw)
		if msg == nil {
			continue
		}
		if toolCallsLen(msg) > 0 {
			n++
		}
	}
	return n
}

func main() {
	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{})
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
		Description: "Fetch a placeholder blog post by id.",
		Method:      "GET",
		URL:         "https://jsonplaceholder.typicode.com/posts/{id}",
		InputSchema: toolnexus.JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"id": map[string]any{"type": "number"},
			},
			"required":             []string{"id"},
			"additionalProperties": false,
		},
	}))

	// opaque: the model CANNOT guess this value, so it must call it first and wait
	tk.Register(toolnexus.NativeTool("todays_post_id",
		"Returns the server-chosen blog post id to read today. Cannot be guessed; you must call it.",
		nil,
		func(_ context.Context, _ map[string]any) (string, error) {
			return "3", nil
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
		BaseURL:      "https://openrouter.ai/api/v1",
		Style:        toolnexus.StyleOpenAI,
		Model:        model,
		APIKey:       key,
		SystemPrompt: "You are a precise agent. Prefer issuing independent tool calls together in one step.",
	})

	callDescs := func(calls []toolnexus.ToolCall) []string {
		out := make([]string, 0, len(calls))
		for _, c := range calls {
			args, _ := json.Marshal(c.Args)
			out = append(out, fmt.Sprintf("%s(%s)", c.Name, string(args)))
		}
		return out
	}

	// ---- A) PARALLEL: two independent adds, ideally in one turn ----
	a, err := agent.Run(ctx,
		"In a single step, call the add tool twice: add 2 and 3, and add 100 and 200. Then report both sums.",
		tk)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("A) parallel — tool calls:", callDescs(a.ToolCalls))
	fmt.Printf("A) max calls in one turn: %d | answer: %.80s\n", maxParallel(a.Messages), oneLine(a.Text))

	// ---- B) CHAIN: second call depends on an OPAQUE first result (forces a 2nd turn) ----
	b, err := agent.Run(ctx,
		"Call todays_post_id to find which post to read, then use get_post to fetch that exact id and tell me its title.",
		tk)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("\nB) chain — tool calls:", callDescs(b.ToolCalls))
	fmt.Println("B) tool-calling turns (chain depth):", toolTurns(b.Messages))
	fmt.Printf("B) answer: %.100s\n", oneLine(b.Text))

	// ---- assertions ----
	parallelOK := maxParallel(a.Messages) >= 2
	calledAdd := countCalls(a.ToolCalls, "add") >= 2
	// true chain: todays_post_id THEN get_post(id=3) across >=2 turns (id=3 is unguessable)
	chainOK := toolTurns(b.Messages) >= 2 &&
		hasCall(b.ToolCalls, "todays_post_id") &&
		hasGetPostID(b.ToolCalls, 3)

	if parallelOK {
		fmt.Printf("\nparallel: OK multiple calls in one turn | ran %d adds\n", countCalls(a.ToolCalls, "add"))
	} else {
		fmt.Printf("\nparallel: WARN model serialized | ran %d adds\n", countCalls(a.ToolCalls, "add"))
	}
	if chainOK {
		fmt.Println("chain:    OK get_post(id=3) used the opaque result across turns")
	} else {
		fmt.Println("chain:    FAIL chain not observed")
	}

	if !calledAdd || !parallelOK || !chainOK {
		log.Fatal("expected >=2 parallel adds AND a real cross-turn chain (todays_post_id -> get_post(id=3))")
	}
	fmt.Println("\nparallel + chained tool calling verified")
}

func oneLine(s string) string {
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r == '\n' || r == '\r' {
			r = ' '
		}
		out = append(out, r)
	}
	return string(out)
}

func countCalls(calls []toolnexus.ToolCall, name string) int {
	n := 0
	for _, c := range calls {
		if c.Name == name {
			n++
		}
	}
	return n
}

func hasCall(calls []toolnexus.ToolCall, name string) bool {
	return countCalls(calls, name) > 0
}

// hasGetPostID reports whether get_post was called with the given id (args["id"]
// arrives as a JSON number => float64).
func hasGetPostID(calls []toolnexus.ToolCall, id float64) bool {
	for _, c := range calls {
		if c.Name != "get_post" {
			continue
		}
		switch v := c.Args["id"].(type) {
		case float64:
			if v == id {
				return true
			}
		case json.Number:
			if f, err := v.Float64(); err == nil && f == id {
				return true
			}
		case string:
			if f, err := strconv.ParseFloat(v, 64); err == nil && f == id {
				return true
			}
		}
	}
	return false
}
