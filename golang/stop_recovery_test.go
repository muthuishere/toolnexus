package toolnexus

// Hermetic replay of conformance/stop_recovery/cases.json (SPEC §8 "Stop-reason recovery"):
// every case, both styles, run and stream.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"
)

type srCall struct {
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}
type srResp struct {
	Thinking  string   `json:"thinking"`
	Text      string   `json:"text"`
	ToolCalls []srCall `json:"toolCalls"`
	Stop      string   `json:"stop"`
}
type srCase struct {
	Name         string   `json:"name"`
	StopRecovery bool     `json:"stopRecovery"`
	MaxTurns     int      `json:"maxTurns"`
	Responses    []srResp `json:"responses"`
	Expect       struct {
		Turns      int              `json:"turns"`
		Text       string           `json:"text"`
		Recoveries []string         `json:"recoveries"`
		ToolRuns   []map[string]any `json:"toolRuns"`
	} `json:"expect"`
}
type srFixture struct {
	Messages map[string]string `json:"messages"`
	Cases    []srCase          `json:"cases"`
}

func srStop(style, stop string) string {
	m := map[string][2]string{"end": {"stop", "end_turn"}, "truncated": {"length", "max_tokens"}, "tool": {"tool_calls", "tool_use"}}
	if style == "openai" {
		return m[stop][0]
	}
	return m[stop][1]
}

func srSSE(w http.ResponseWriter, events []any) {
	w.Header().Set("Content-Type", "text/event-stream")
	for _, e := range events {
		b, _ := json.Marshal(e)
		fmt.Fprintf(w, "data: %s\n\n", b)
	}
}

func srOpenAI(r srResp, stream bool) (any, []any) {
	var calls []any
	for i, c := range r.ToolCalls {
		calls = append(calls, map[string]any{"index": i, "id": fmt.Sprintf("call_%d", i), "type": "function",
			"function": map[string]any{"name": c.Name, "arguments": c.Arguments}})
	}
	finish := srStop("openai", r.Stop)
	if !stream {
		msg := map[string]any{"role": "assistant", "content": r.Text, "reasoning_content": r.Thinking}
		if len(calls) > 0 {
			msg["tool_calls"] = calls
		}
		return map[string]any{"choices": []any{map[string]any{"index": 0, "message": msg, "finish_reason": finish}},
			"usage": map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}, nil
	}
	var ev []any
	ch := func(d map[string]any, fr any) any {
		return map[string]any{"choices": []any{map[string]any{"index": 0, "delta": d, "finish_reason": fr}}}
	}
	if r.Thinking != "" {
		ev = append(ev, ch(map[string]any{"reasoning_content": r.Thinking}, nil))
	}
	if r.Text != "" {
		ev = append(ev, ch(map[string]any{"content": r.Text}, nil))
	}
	if len(calls) > 0 {
		ev = append(ev, ch(map[string]any{"tool_calls": calls}, nil))
	}
	ev = append(ev, ch(map[string]any{}, finish))
	ev = append(ev, map[string]any{"choices": []any{}, "usage": map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
	return nil, ev
}

func srAnthropic(r srResp, stream bool) (any, []any) {
	var blocks []map[string]any
	if r.Thinking != "" {
		blocks = append(blocks, map[string]any{"type": "thinking", "thinking": r.Thinking, "signature": "sig"})
	}
	if r.Text != "" {
		blocks = append(blocks, map[string]any{"type": "text", "text": r.Text})
	}
	for i, c := range r.ToolCalls {
		var in any = c.Arguments
		var obj map[string]any
		if json.Unmarshal([]byte(c.Arguments), &obj) == nil && obj != nil {
			in = obj
		}
		blocks = append(blocks, map[string]any{"type": "tool_use", "id": fmt.Sprintf("toolu_%d", i), "name": c.Name, "input": in, "raw": c.Arguments})
	}
	stop := srStop("anthropic", r.Stop)
	if !stream {
		var content []any
		for _, b := range blocks {
			delete(b, "raw")
			content = append(content, b)
		}
		return map[string]any{"content": content, "stop_reason": stop, "usage": map[string]any{"input_tokens": 1, "output_tokens": 1}}, nil
	}
	ev := []any{map[string]any{"type": "message_start", "message": map[string]any{"usage": map[string]any{"input_tokens": 1, "output_tokens": 0}}}}
	for i, b := range blocks {
		switch b["type"] {
		case "thinking":
			ev = append(ev, map[string]any{"type": "content_block_start", "index": i, "content_block": map[string]any{"type": "thinking", "thinking": ""}},
				map[string]any{"type": "content_block_delta", "index": i, "delta": map[string]any{"type": "thinking_delta", "thinking": b["thinking"]}})
		case "text":
			ev = append(ev, map[string]any{"type": "content_block_start", "index": i, "content_block": map[string]any{"type": "text", "text": ""}},
				map[string]any{"type": "content_block_delta", "index": i, "delta": map[string]any{"type": "text_delta", "text": b["text"]}})
		case "tool_use":
			ev = append(ev, map[string]any{"type": "content_block_start", "index": i, "content_block": map[string]any{"type": "tool_use", "id": b["id"], "name": b["name"], "input": map[string]any{}}},
				map[string]any{"type": "content_block_delta", "index": i, "delta": map[string]any{"type": "input_json_delta", "partial_json": b["raw"]}})
		}
		ev = append(ev, map[string]any{"type": "content_block_stop", "index": i})
	}
	ev = append(ev, map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": stop}, "usage": map[string]any{"output_tokens": 1}},
		map[string]any{"type": "message_stop"})
	return nil, ev
}

func TestStopRecoveryConformance(t *testing.T) {
	b, err := os.ReadFile("../conformance/stop_recovery/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var fx srFixture
	if err := json.Unmarshal(b, &fx); err != nil {
		t.Fatal(err)
	}
	for _, cs := range fx.Cases {
		for _, style := range []string{"openai", "anthropic"} {
			for _, stream := range []bool{false, true} {
				cs, style, stream := cs, style, stream
				t.Run(fmt.Sprintf("%s/%s/stream=%v", cs.Name, style, stream), func(t *testing.T) {
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
						resp := cs.Responses[n]
						var obj any
						var ev []any
						if style == "openai" {
							obj, ev = srOpenAI(resp, stream)
						} else {
							obj, ev = srAnthropic(resp, stream)
						}
						if stream {
							srSSE(w, ev)
							return
						}
						w.Header().Set("Content-Type", "application/json")
						_ = json.NewEncoder(w).Encode(obj)
					}))
					defer srv.Close()

					var runsMu sync.Mutex
					var runs []map[string]any
					echo := NativeTool("echo", "echo", nil, func(_ context.Context, args map[string]any) (string, error) {
						runsMu.Lock()
						runs = append(runs, args)
						runsMu.Unlock()
						return "ok", nil
					})
					tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{echo}, Builtins: false})
					if err != nil {
						t.Fatal(err)
					}
					st := StyleOpenAI
					if style == "anthropic" {
						st = StyleAnthropic
					}
					c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: st, Model: "m", APIKey: "k", Retries: -1,
						MaxTurns: cs.MaxTurns, StopRecovery: cs.StopRecovery})
					var res RunResult
					if stream {
						ch, err := c.Stream(context.Background(), "go", tk)
						if err != nil {
							t.Fatal(err)
						}
						var deltas strings.Builder
						for ev := range ch {
							switch ev.Type {
							case "text":
								deltas.WriteString(ev.Delta)
							case "done":
								res = *ev.Result
							case "error":
								t.Fatalf("stream error: %v", ev.Err)
							}
						}
						if len(cs.Expect.ToolRuns) == 0 && deltas.String() != res.Text {
							t.Fatalf("deltas %q != text %q", deltas.String(), res.Text)
						}
					} else {
						res, err = c.Run(context.Background(), "go", tk)
						if err != nil {
							t.Fatal(err)
						}
					}
					if res.Turns != cs.Expect.Turns || len(bodies) != cs.Expect.Turns {
						t.Fatalf("turns = %d (requests %d), want %d", res.Turns, len(bodies), cs.Expect.Turns)
					}
					if res.Text != cs.Expect.Text {
						t.Fatalf("text = %q, want %q", res.Text, cs.Expect.Text)
					}
					var got []string
					for _, bd := range bodies[1:] {
						msgs, _ := bd["messages"].([]any)
						last, _ := msgs[len(msgs)-1].(map[string]any)
						if last["role"] == "user" {
							if s, ok := last["content"].(string); ok {
								for k, v := range fx.Messages {
									if s == v {
										got = append(got, k)
									}
								}
							}
						}
					}
					want := cs.Expect.Recoveries
					if len(got) != len(want) || (len(want) > 0 && !reflect.DeepEqual(got, want)) {
						t.Fatalf("recoveries = %v, want %v", got, want)
					}
					if len(runs) != len(cs.Expect.ToolRuns) {
						t.Fatalf("tool runs = %v, want %v", runs, cs.Expect.ToolRuns)
					}
					for i := range runs {
						a, _ := json.Marshal(runs[i])
						e, _ := json.Marshal(cs.Expect.ToolRuns[i])
						if string(a) != string(e) {
							t.Fatalf("tool run %d = %s, want %s", i, a, e)
						}
					}
				})
			}
		}
	}
}
