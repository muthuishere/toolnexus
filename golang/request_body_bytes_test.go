// Pins the exact request-body bytes of the non-streaming OpenAI and Anthropic
// loops. The tools array is marshalled once per Run (toolsWire) when nothing can
// observe it between turns; these tests prove that cached path sends the same
// bytes as the per-turn path (forced by a no-op BeforeLLM hook), and pin the
// literal bytes so a later change cannot drift the wire silently.

package toolnexus

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// rawLLM records raw request bodies; turn 1 calls `deploy`, turn 2 answers.
func rawLLM(t *testing.T) (*httptest.Server, func() []string) {
	t.Helper()
	var mu sync.Mutex
	var bodies []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		bodies = append(bodies, string(b))
		n := len(bodies)
		mu.Unlock()
		w.Header().Set("content-type", "application/json")
		anth := strings.HasSuffix(r.URL.Path, "/messages")
		switch {
		case !anth && n == 1:
			io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"deploy","arguments":"{\"env\":\"prod\"}"}}]}}]}`)
		case !anth:
			io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"done"}}]}`)
		case n == 1:
			io.WriteString(w, `{"content":[{"type":"tool_use","id":"c1","name":"deploy","input":{"env":"prod"}}],"stop_reason":"tool_use"}`)
		default:
			io.WriteString(w, `{"content":[{"type":"text","text":"done"}],"stop_reason":"end_turn"}`)
		}
	}))
	t.Cleanup(srv.Close)
	return srv, func() []string { mu.Lock(); defer mu.Unlock(); return append([]string(nil), bodies...) }
}

func bodyBytesToolkit(t *testing.T) *Toolkit {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{
		Builtins: false,
		ExtraTools: []Tool{{
			// <, > and & exercise encoding/json's HTML escaping on both paths.
			Name: "deploy", Description: "deploy <svc> & report", Source: SourceCustom,
			InputSchema: JSONSchema{"type": "object", "properties": map[string]any{"env": map[string]any{"type": "string", "enum": []any{"dev", "prod"}}}, "required": []any{"env"}},
			Execute: func(a map[string]any, c *ToolContext) (ToolResult, error) {
				return ToolResult{Output: "DEPLOYED"}, nil
			}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func runBodies(t *testing.T, style ClientStyle, hooks *Hooks) []string {
	t.Helper()
	srv, bodies := rawLLM(t)
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: style, Model: "m", APIKey: "k", MaxTurns: 4, Hooks: hooks})
	r, err := c.Run(context.Background(), "go", bodyBytesToolkit(t))
	if err != nil || r.Text != "done" {
		t.Fatalf("run: %v %q", err, r.Text)
	}
	return bodies()
}

const toolSchemaJSON = `"description":"deploy \u003csvc\u003e \u0026 report",`

func TestRequestBodyBytesPinned(t *testing.T) {
	want := map[ClientStyle][]string{
		StyleOpenAI: {
			`{"messages":[{"content":"go","role":"user"}],"model":"m","tool_choice":"auto","tools":[{"type":"function","function":{"name":"deploy",` + toolSchemaJSON + `"parameters":{"properties":{"env":{"enum":["dev","prod"],"type":"string"}},"required":["env"],"type":"object"}}}]}`,
			`{"messages":[{"content":"go","role":"user"},{"content":"","role":"assistant","tool_calls":[{"function":{"arguments":"{\"env\":\"prod\"}","name":"deploy"},"id":"c1","type":"function"}]},{"content":"DEPLOYED","role":"tool","tool_call_id":"c1"}],"model":"m","tool_choice":"auto","tools":[{"type":"function","function":{"name":"deploy",` + toolSchemaJSON + `"parameters":{"properties":{"env":{"enum":["dev","prod"],"type":"string"}},"required":["env"],"type":"object"}}}]}`,
		},
	}
	noop := &Hooks{BeforeLLM: func(context.Context, BeforeLLMEvent) (*LLMOverride, error) { return nil, nil }}
	for _, style := range []ClientStyle{StyleOpenAI, StyleAnthropic} {
		cached := runBodies(t, style, nil)
		perTurn := runBodies(t, style, noop)
		if len(cached) != 2 || len(perTurn) != 2 {
			t.Fatalf("%s: requests cached=%d perTurn=%d, want 2", style, len(cached), len(perTurn))
		}
		for i := range cached {
			if cached[i] != perTurn[i] {
				t.Fatalf("%s turn %d: cached tools path changed the wire\ncached:   %s\nper-turn: %s", style, i, cached[i], perTurn[i])
			}
			if w, ok := want[style]; ok && cached[i] != w[i] {
				t.Fatalf("%s turn %d: body drifted\ngot:  %s\nwant: %s", style, i, cached[i], w[i])
			}
		}
	}
}

// BenchmarkRunTwoTurns measures one two-turn Run (tool call, then answer) against
// a local server; compare -benchmem allocs/op across changes to the request path.
func BenchmarkRunTwoTurns(b *testing.B) {
	t := &testing.T{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("content-type", "application/json")
		if !strings.Contains(string(body), `"role":"tool"`) {
			io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"deploy","arguments":"{\"env\":\"prod\"}"}}]}}]}`)
			return
		}
		io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"done"}}]}`)
	}))
	defer srv.Close()
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", MaxTurns: 4})
	tk := bodyBytesToolkit(t)
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if _, err := c.Run(context.Background(), "go", tk); err != nil {
			b.Fatal(err)
		}
	}
}
