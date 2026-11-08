// Hermetic replay of conformance/reactive_compaction/cases.json (SPEC §7F.1,
// issue #194): IsContextOverflow detection, and every loop case through Run AND
// Stream in both styles against a scripted httptest provider.

package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"sync/atomic"
	"testing"
)

type rcStatusBody struct {
	Status int    `json:"status"`
	Body   string `json:"body"`
}

type rcFixture struct {
	OverflowBodies map[string]rcStatusBody `json:"overflowBodies"`
	Detection      []struct {
		Name     string `json:"name"`
		Status   int    `json:"status"`
		Body     string `json:"body"`
		Overflow bool   `json:"overflow"`
	} `json:"detection"`
	Loops []struct {
		Name     string   `json:"name"`
		Option   bool     `json:"option"`
		HookNoop bool     `json:"hookNoop"`
		Script   []string `json:"script"`
		Expect   struct {
			Text        string `json:"text"`
			Compactions *int   `json:"compactions"`
			Error       string `json:"error"`
			Status      int    `json:"status"`
			Message     string `json:"message"`
		} `json:"expect"`
		Calls int `json:"calls"`
	} `json:"loops"`
	Bad400 rcStatusBody `json:"bad400"`
}

func loadRCFixture(t *testing.T) rcFixture {
	t.Helper()
	b, err := os.ReadFile("../conformance/reactive_compaction/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var f rcFixture
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestReactiveCompactionDetection(t *testing.T) {
	f := loadRCFixture(t)
	for _, c := range f.Detection {
		if got := IsContextOverflow(c.Status, c.Body); got != c.Overflow {
			t.Errorf("%s: IsContextOverflow = %v, want %v", c.Name, got, c.Overflow)
		}
	}
}

// rcServer answers call n with script[n], in the request's style/stream shape.
func rcServer(t *testing.T, f rcFixture, style ClientStyle, script []string, calls *int32, msgCounts *[]int, mu *sync.Mutex) *httptest.Server {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body := map[string]any{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		n := int(atomic.AddInt32(calls, 1)) - 1
		msgs, _ := body["messages"].([]any)
		mu.Lock()
		*msgCounts = append(*msgCounts, len(msgs))
		mu.Unlock()
		if n >= len(script) {
			t.Errorf("unexpected call %d (script has %d)", n+1, len(script))
			w.WriteHeader(500)
			return
		}
		step := script[n]
		switch step {
		case "overflow", "bad400":
			sb := f.Bad400
			if step == "overflow" {
				sb = f.OverflowBodies[string(style)]
			}
			w.WriteHeader(sb.Status)
			_, _ = w.Write([]byte(sb.Body))
			return
		}
		tool := step == "tool"
		id := fmt.Sprintf("c%d", n)
		stream, _ := body["stream"].(bool)
		sse := func(v any) { fmt.Fprintf(w, "data: %s\n\n", mustJSON(v)) }
		switch {
		case style == StyleOpenAI && !stream:
			msg := map[string]any{"role": "assistant", "content": "done"}
			if tool {
				msg = map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{map[string]any{
					"id": id, "type": "function", "function": map[string]any{"name": "probe", "arguments": "{}"}}}}
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": msg}}})
		case style == StyleAnthropic && !stream:
			resp := map[string]any{"content": []any{map[string]any{"type": "text", "text": "done"}}, "stop_reason": "end_turn"}
			if tool {
				resp = map[string]any{"content": []any{map[string]any{"type": "tool_use", "id": id, "name": "probe", "input": map[string]any{}}}, "stop_reason": "tool_use"}
			}
			_ = json.NewEncoder(w).Encode(resp)
		case style == StyleOpenAI:
			w.Header().Set("content-type", "text/event-stream")
			if tool {
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{map[string]any{
					"index": 0, "id": id, "type": "function", "function": map[string]any{"name": "probe", "arguments": "{}"}}}}}}})
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "tool_calls"}}})
			} else {
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": "done"}}}})
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "stop"}}})
			}
			fmt.Fprint(w, "data: [DONE]\n\n")
		default:
			w.Header().Set("content-type", "text/event-stream")
			if tool {
				sse(map[string]any{"type": "content_block_start", "index": 0, "content_block": map[string]any{"type": "tool_use", "id": id, "name": "probe"}})
				sse(map[string]any{"type": "content_block_delta", "index": 0, "delta": map[string]any{"type": "input_json_delta", "partial_json": "{}"}})
				sse(map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "tool_use"}})
			} else {
				sse(map[string]any{"type": "content_block_start", "index": 0, "content_block": map[string]any{"type": "text"}})
				sse(map[string]any{"type": "content_block_delta", "index": 0, "delta": map[string]any{"type": "text_delta", "text": "done"}})
				sse(map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "end_turn"}})
			}
			sse(map[string]any{"type": "message_stop"})
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestReactiveCompactionLoops(t *testing.T) {
	f := loadRCFixture(t)
	for _, lc := range f.Loops {
		for _, style := range []ClientStyle{StyleOpenAI, StyleAnthropic} {
			for _, stream := range []bool{false, true} {
				lc, style, stream := lc, style, stream
				mode := "run"
				if stream {
					mode = "stream"
				}
				t.Run(fmt.Sprintf("%s/%s/%s", lc.Name, style, mode), func(t *testing.T) {
					var calls int32
					var mu sync.Mutex
					var msgCounts []int
					srv := rcServer(t, f, style, lc.Script, &calls, &msgCounts, &mu)

					var hookCalls, beforeCalls int32
					opts := ClientOptions{BaseURL: srv.URL, Style: style, Model: "m", APIKey: "k", MaxTurns: 20, RetryBaseMs: 1,
						Hooks: &Hooks{BeforeLLM: func(ctx context.Context, ev BeforeLLMEvent) (*LLMOverride, error) {
							atomic.AddInt32(&beforeCalls, 1)
							return nil, nil
						}}}
					if lc.Option {
						opts.CompactOnOverflow = func(ctx context.Context, ev BeforeLLMEvent) (*LLMOverride, error) {
							atomic.AddInt32(&hookCalls, 1)
							if lc.HookNoop {
								return nil, nil
							}
							return &LLMOverride{Messages: append([]any{}, ev.Messages[1:]...)}, nil
						}
					}
					c := CreateClient(opts)
					tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{NativeTool("probe", "probe", nil,
						func(ctx context.Context, a map[string]any) (string, error) { return "ok", nil })}})
					if err != nil {
						t.Fatal(err)
					}
					var history []any
					for i := 0; i < 8; i++ {
						role := "user"
						if i%2 == 1 {
							role = "assistant"
						}
						history = append(history, map[string]any{"role": role, "content": fmt.Sprintf("h%d", i)})
					}

					var res RunResult
					if !stream {
						res, err = c.RunWithHistory(context.Background(), "go", tk, history)
					} else {
						if serr := c.ConversationStore().Save("t", history); serr != nil {
							t.Fatal(serr)
						}
						ch, serr := c.StreamWithID(context.Background(), "go", tk, "t")
						if serr != nil {
							t.Fatal(serr)
						}
						for ev := range ch {
							switch ev.Type {
							case "done":
								if ev.Result != nil {
									res = *ev.Result
								}
							case "error":
								err = ev.Err
							}
						}
					}

					if got := int(atomic.LoadInt32(&calls)); got != lc.Calls {
						t.Errorf("calls = %d, want %d", got, lc.Calls)
					}
					tools := 0
					for _, s := range lc.Script {
						if s == "tool" {
							tools++
						}
					}
					// BeforeLLM runs once per TURN, never for a reactive retry.
					if got := int(atomic.LoadInt32(&beforeCalls)); got != tools+1 {
						t.Errorf("beforeLLM calls = %d, want %d (one per turn)", got, tools+1)
					}
					switch lc.Expect.Error {
					case "":
						if err != nil {
							t.Fatalf("unexpected error: %v", err)
						}
						if res.Text != lc.Expect.Text {
							t.Errorf("text = %q, want %q", res.Text, lc.Expect.Text)
						}
						if lc.Expect.Compactions != nil && int(hookCalls) != *lc.Expect.Compactions {
							t.Errorf("compactions = %d, want %d", hookCalls, *lc.Expect.Compactions)
						}
						if res.Turns != tools+1 {
							t.Errorf("turns = %d, want %d (retries do not consume turns)", res.Turns, tools+1)
						}
					case "LlmHttpError":
						var pe *ProviderError
						var coe *ContextOverflowError
						if !errors.As(err, &pe) || errors.As(err, &coe) {
							t.Fatalf("err = %T %v, want *ProviderError", err, err)
						}
						if pe.Status != lc.Expect.Status {
							t.Errorf("status = %d, want %d", pe.Status, lc.Expect.Status)
						}
					case "ContextOverflowError":
						var coe *ContextOverflowError
						if !errors.As(err, &coe) {
							t.Fatalf("err = %T %v, want *ContextOverflowError", err, err)
						}
						if coe.Compactions != *lc.Expect.Compactions {
							t.Errorf("Compactions = %d, want %d", coe.Compactions, *lc.Expect.Compactions)
						}
						if coe.Error() != lc.Expect.Message {
							t.Errorf("message = %q, want %q", coe.Error(), lc.Expect.Message)
						}
						ob := f.OverflowBodies[string(style)]
						if coe.Status != ob.Status || coe.Body != ob.Body {
							t.Errorf("status/body = %d %q, want %d %q", coe.Status, coe.Body, ob.Status, ob.Body)
						}
					default:
						t.Fatalf("unknown expected error %q", lc.Expect.Error)
					}
					// Every retry after a compaction sends strictly fewer messages.
					mu.Lock()
					defer mu.Unlock()
					for i, s := range lc.Script {
						if s == "overflow" && lc.Option && !lc.HookNoop && i+1 < len(msgCounts) && msgCounts[i+1] >= msgCounts[i] {
							t.Errorf("call %d after overflow sent %d messages, not fewer than %d", i+2, msgCounts[i+1], msgCounts[i])
						}
					}
				})
			}
		}
	}
}
