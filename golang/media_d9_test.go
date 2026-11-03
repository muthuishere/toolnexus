package toolnexus

import (
	"context"
	"testing"
)

// D9 (SPEC §7G.12): a media tool result whose modelParts is [] (here: a video,
// which never reaches the model) is sent as the plain output string in every
// message style the Go client supports (openai, anthropic; Go has no gemini
// client style), run and stream — never an empty content array, never a placeholder.
func TestMediaNoImagePartsSendsPlainOutputEveryStyle(t *testing.T) {
	const want = "video ready: https://x/v.mp4"
	for _, e := range parityRunEntries {
		t.Run(e.name, func(t *testing.T) {
			calls := []routeCall{}
			media := MustCreateMedia(&routeClient{name: "f", calls: &calls, verbs: map[string]map[string]any{
				"video": {"outcome": map[string]any{"result": map[string]any{"status": "completed", "files": []any{
					map[string]any{"type": "file", "mimeType": "video/mp4", "url": "https://x/v.mp4"}}}}}}})
			var vt Tool
			for _, tl := range media.Tools() {
				if tl.Name == toolNameFor("video") {
					vt = tl
				}
			}
			if parts := toolPartsForModel(mustExec(t, vt)); len(parts) != 0 {
				t.Fatalf("modelParts = %+v, want []", parts)
			}
			vt.Name = "deploy" // the name parityLLM calls, with {} args
			inner := vt.Execute
			vt.Execute = func(_ map[string]any, tc *ToolContext) (ToolResult, error) {
				return inner(map[string]any{"prompt": "x"}, tc)
			}
			vt.InputSchema = map[string]any{"type": "object", "properties": map[string]any{}}
			tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
			if err != nil {
				t.Fatal(err)
			}
			defer tk.Close()
			tk.Register(vt)
			llm := newParityLLM(t)
			c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: e.style, Model: "m", APIKey: "k", MaxTurns: 4})
			if e.stream {
				ch, err := c.Stream(context.Background(), "go", tk)
				if err != nil {
					t.Fatal(err)
				}
				for range ch {
				}
			} else if _, err := c.Run(context.Background(), "go", tk); err != nil {
				t.Fatal(err)
			}
			llm.mu.Lock()
			defer llm.mu.Unlock()
			if len(llm.bodies) < 2 {
				t.Fatalf("%d LLM requests", len(llm.bodies))
			}
			var got any
			found := 0
			for _, m := range llm.bodies[1]["messages"].([]any) {
				mm := m.(map[string]any)
				if e.style == StyleOpenAI && mm["role"] == "tool" {
					got, found = mm["content"], found+1
				}
				if e.style == StyleAnthropic {
					if blocks, ok := mm["content"].([]any); ok {
						for _, b := range blocks {
							if bm, _ := b.(map[string]any); bm["type"] == "tool_result" {
								got, found = bm["content"], found+1
							}
						}
					}
				}
			}
			if found != 1 || got != want {
				t.Fatalf("tool-result content = %#v (found %d), want the plain string %q", got, found, want)
			}
			if e.style == StyleOpenAI { // no synthetic relocation message either
				for _, m := range llm.bodies[1]["messages"].([]any) {
					if _, isList := m.(map[string]any)["content"].([]any); isList {
						t.Fatalf("unexpected list content: %s", mediaJSON(m))
					}
				}
			}
		})
	}
}

func mustExec(t *testing.T, tl Tool) ToolResult {
	t.Helper()
	r, err := tl.Execute(map[string]any{"prompt": "x"}, &ToolContext{Ctx: context.Background()})
	if err != nil || r.IsError {
		t.Fatalf("execute: %v %+v", err, r)
	}
	return r
}
