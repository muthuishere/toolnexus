package toolnexus

// Issue #235 / SPEC §8.x: an empty anthropic text block beside tool_use must never be
// stored in the assistant message, or the next request is rejected with a 400.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

func TestAnthropicEmptyTextBlockIsNotStored(t *testing.T) {
	for _, stream := range []bool{false, true} {
		t.Run(fmt.Sprintf("stream=%v", stream), func(t *testing.T) {
			var mu sync.Mutex
			var bodies []map[string]any
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				raw, _ := io.ReadAll(r.Body)
				var body map[string]any
				_ = json.Unmarshal(raw, &body)
				mu.Lock()
				n := len(bodies)
				bodies = append(bodies, body)
				mu.Unlock()
				if n == 0 {
					if !stream {
						w.Header().Set("Content-Type", "application/json")
						_ = json.NewEncoder(w).Encode(map[string]any{
							"content": []any{
								map[string]any{"type": "text", "text": ""},
								map[string]any{"type": "tool_use", "id": "toolu_0", "name": "echo", "input": map[string]any{}},
							},
							"stop_reason": "tool_use", "usage": map[string]any{"input_tokens": 1, "output_tokens": 1}})
						return
					}
					srSSE(w, []any{
						map[string]any{"type": "message_start", "message": map[string]any{"usage": map[string]any{"input_tokens": 1}}},
						map[string]any{"type": "content_block_start", "index": 0, "content_block": map[string]any{"type": "text", "text": ""}},
						map[string]any{"type": "content_block_stop", "index": 0},
						map[string]any{"type": "content_block_start", "index": 1, "content_block": map[string]any{"type": "tool_use", "id": "toolu_0", "name": "echo", "input": map[string]any{}}},
						map[string]any{"type": "content_block_delta", "index": 1, "delta": map[string]any{"type": "input_json_delta", "partial_json": "{}"}},
						map[string]any{"type": "content_block_stop", "index": 1},
						map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "tool_use"}, "usage": map[string]any{"output_tokens": 1}},
						map[string]any{"type": "message_stop"},
					})
					return
				}
				_, ev := srAnthropic(srResp{Text: "done", Stop: "end"}, true)
				if stream {
					srSSE(w, ev)
					return
				}
				obj, _ := srAnthropic(srResp{Text: "done", Stop: "end"}, false)
				w.Header().Set("Content-Type", "application/json")
				_ = json.NewEncoder(w).Encode(obj)
			}))
			defer srv.Close()

			echo := NativeTool("echo", "echo", nil, func(context.Context, map[string]any) (string, error) { return "ok", nil })
			tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{echo}, Builtins: false})
			if err != nil {
				t.Fatal(err)
			}
			c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleAnthropic, Model: "m", APIKey: "k", Retries: -1})
			var res RunResult
			if stream {
				ch, err := c.Stream(context.Background(), "go", tk)
				if err != nil {
					t.Fatal(err)
				}
				for ev := range ch {
					if ev.Type == "done" {
						res = *ev.Result
					} else if ev.Type == "error" {
						t.Fatal(ev.Err)
					}
				}
			} else if res, err = c.Run(context.Background(), "go", tk); err != nil {
				t.Fatal(err)
			}
			if res.Text != "done" || len(bodies) != 2 {
				t.Fatalf("text %q, requests %d", res.Text, len(bodies))
			}
			msgs := bodies[1]["messages"].([]any)
			for _, m := range msgs {
				content, _ := m.(map[string]any)["content"].([]any)
				for _, b := range content {
					blk := b.(map[string]any)
					if blk["type"] == "text" && blk["text"] == "" {
						t.Fatalf("empty text block sent: %v", msgs)
					}
				}
			}
		})
	}
}
