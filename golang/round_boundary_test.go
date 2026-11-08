// SPEC §8 AfterRound (change add-round-boundary-hook, #192). Shared fixture:
// examples/round-boundary/fixture.json (R1-R5 here; R6 in agents/). Hermetic:
// a scripted httptest server (openai run/stream, anthropic run).

package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"testing"
)

type rbReply struct {
	content string
	call    bool // the echo tool call {"text":"hi"}
}

type rbLLM struct {
	mu     sync.Mutex
	bodies []map[string]any
	srv    *httptest.Server
}

func newRBLLM(t *testing.T, replies []rbReply) *rbLLM {
	p := &rbLLM{}
	p.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body := map[string]any{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		p.mu.Lock()
		p.bodies = append(p.bodies, body)
		n := len(p.bodies)
		p.mu.Unlock()
		rp := replies[min(n-1, len(replies)-1)]
		stream, _ := body["stream"].(bool)
		sse := func(v any) { fmt.Fprintf(w, "data: %s\n\n", mustJSON(v)) }
		switch {
		case strings.HasSuffix(r.URL.Path, "/messages"):
			w.Header().Set("content-type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"content": []any{map[string]any{"type": "text", "text": rp.content}}, "stop_reason": "end_turn"})
		case !stream:
			w.Header().Set("content-type", "application/json")
			msg := map[string]any{"role": "assistant", "content": rp.content}
			if rp.call {
				msg = map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{map[string]any{
					"id": "c1", "type": "function", "function": map[string]any{"name": "echo", "arguments": `{"text":"hi"}`}}}}
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": msg}}})
		default:
			w.Header().Set("content-type", "text/event-stream")
			if rp.call {
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{map[string]any{
					"index": 0, "id": "c1", "type": "function", "function": map[string]any{"name": "echo", "arguments": `{"text":"hi"}`}}}}}}})
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "tool_calls"}}})
			} else {
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": rp.content}}}})
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "stop"}}})
			}
			fmt.Fprint(w, "data: [DONE]\n\n")
		}
	}))
	t.Cleanup(p.srv.Close)
	return p
}

func (p *rbLLM) sent() []map[string]any {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]map[string]any{}, p.bodies...)
}

func rbTail(body map[string]any, n int) []map[string]any {
	msgs, _ := body["messages"].([]any)
	out := []map[string]any{}
	for _, m := range msgs[len(msgs)-n:] {
		mm := m.(map[string]any)
		out = append(out, map[string]any{"role": mm["role"], "content": mm["content"]})
	}
	return out
}

func rbToolkit(t *testing.T) *Toolkit {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{
		Builtins: false,
		ExtraTools: []Tool{{
			Name: "echo", Description: "echo", Source: SourceCustom,
			InputSchema: JSONSchema{"type": "object", "properties": map[string]any{"text": map[string]any{"type": "string"}}},
			Execute: func(a map[string]any, c *ToolContext) (ToolResult, error) {
				return ToolResult{Output: fmt.Sprint(a["text"])}, nil
			}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

type rbCall struct {
	Turn  int
	Final bool
	Text  string
}

// rbGo drives one loop; for stream it also returns the event types seen.
func rbGo(t *testing.T, stream bool, style ClientStyle, url string, hooks *Hooks, maxTurns int) (RunResult, error, []string) {
	t.Helper()
	c := CreateClient(ClientOptions{BaseURL: url, Style: style, Model: "m", APIKey: "k", MaxTurns: maxTurns, Hooks: hooks})
	tk := rbToolkit(t)
	if !stream {
		r, err := c.Run(context.Background(), "go", tk)
		return r, err, nil
	}
	ch, err := c.Stream(context.Background(), "go", tk)
	if err != nil {
		return RunResult{}, err, nil
	}
	var res RunResult
	var serr error
	var types []string
	for ev := range ch {
		types = append(types, ev.Type)
		switch ev.Type {
		case "done":
			res = *ev.Result
		case "error":
			serr = ev.Err
		}
	}
	return res, serr, types
}

func rbRecorder(calls *[]rbCall, rule func(AfterRoundEvent) *RoundInjection) *Hooks {
	return &Hooks{AfterRound: func(_ context.Context, ev AfterRoundEvent) (*RoundInjection, error) {
		*calls = append(*calls, rbCall{ev.Turn, ev.Final, ev.Text})
		return rule(ev), nil
	}}
}

var rbModes = []struct {
	name   string
	stream bool
}{{"run", false}, {"stream", true}}

func count(xs []string, s string) int {
	n := 0
	for _, x := range xs {
		if x == s {
			n++
		}
	}
	return n
}

func TestAfterRound_R1_InjectAfterToolResults(t *testing.T) {
	for _, m := range rbModes {
		t.Run(m.name, func(t *testing.T) {
			llm := newRBLLM(t, []rbReply{{call: true}, {content: "done"}})
			var calls []rbCall
			r, err, _ := rbGo(t, m.stream, StyleOpenAI, llm.srv.URL, rbRecorder(&calls, func(ev AfterRoundEvent) *RoundInjection {
				if ev.Final {
					return nil
				}
				return &RoundInjection{Inject: []string{"todo: 2 open"}}
			}), 10)
			if err != nil {
				t.Fatal(err)
			}
			sent := llm.sent()
			if len(sent) != 2 || r.Text != "done" {
				t.Fatalf("requests=%d text=%q", len(sent), r.Text)
			}
			want := []map[string]any{{"role": "tool", "content": "hi"}, {"role": "user", "content": "todo: 2 open"}}
			if got := rbTail(sent[1], 2); !reflect.DeepEqual(got, want) {
				t.Fatalf("tail = %v", got)
			}
			if wc := []rbCall{{0, false, ""}, {1, true, "done"}}; !reflect.DeepEqual(calls, wc) {
				t.Fatalf("calls = %v", calls)
			}
		})
	}
}

func r2Rule(ev AfterRoundEvent) *RoundInjection {
	if ev.Final && ev.Turn == 0 {
		return &RoundInjection{Inject: []string{"keep going"}}
	}
	return nil
}

func TestAfterRound_R2_ContinueWhenModelStops(t *testing.T) {
	for _, m := range rbModes {
		t.Run(m.name, func(t *testing.T) {
			llm := newRBLLM(t, []rbReply{{content: "first"}, {content: "second"}})
			var calls []rbCall
			r, err, types := rbGo(t, m.stream, StyleOpenAI, llm.srv.URL, rbRecorder(&calls, r2Rule), 10)
			if err != nil {
				t.Fatal(err)
			}
			sent := llm.sent()
			if len(sent) != 2 || r.Text != "second" {
				t.Fatalf("requests=%d text=%q", len(sent), r.Text)
			}
			want := []map[string]any{{"role": "assistant", "content": "first"}, {"role": "user", "content": "keep going"}}
			if got := rbTail(sent[1], 2); !reflect.DeepEqual(got, want) {
				t.Fatalf("tail = %v", got)
			}
			if wc := []rbCall{{0, true, "first"}, {1, true, "second"}}; !reflect.DeepEqual(calls, wc) {
				t.Fatalf("calls = %v", calls)
			}
			if m.stream && (count(types, "usage") != 1 || count(types, "done") != 1) {
				t.Fatalf("stream events = %v, want one usage + one done", types)
			}
		})
	}
}

func TestAfterRound_R2_AnthropicRun(t *testing.T) {
	llm := newRBLLM(t, []rbReply{{content: "first"}, {content: "second"}})
	var calls []rbCall
	r, err, _ := rbGo(t, false, StyleAnthropic, llm.srv.URL, rbRecorder(&calls, r2Rule), 10)
	if err != nil {
		t.Fatal(err)
	}
	sent := llm.sent()
	if len(sent) != 2 || r.Text != "second" {
		t.Fatalf("requests=%d text=%q", len(sent), r.Text)
	}
	tail := rbTail(sent[1], 2)
	if tail[0]["role"] != "assistant" || !reflect.DeepEqual(tail[1], map[string]any{"role": "user", "content": "keep going"}) {
		t.Fatalf("tail = %v", tail)
	}
}

func TestAfterRound_R3_EmptyInjectionEndsNormally(t *testing.T) {
	for _, m := range rbModes {
		t.Run(m.name, func(t *testing.T) {
			llm := newRBLLM(t, []rbReply{{content: "only"}})
			var calls []rbCall
			r, err, _ := rbGo(t, m.stream, StyleOpenAI, llm.srv.URL, rbRecorder(&calls, func(AfterRoundEvent) *RoundInjection {
				return &RoundInjection{Inject: []string{""}}
			}), 10)
			if err != nil {
				t.Fatal(err)
			}
			if n := len(llm.sent()); n != 1 || r.Text != "only" || r.Status != RunStatusDone {
				t.Fatalf("requests=%d text=%q status=%q", n, r.Text, r.Status)
			}
			if wc := []rbCall{{0, true, "only"}}; !reflect.DeepEqual(calls, wc) {
				t.Fatalf("calls = %v", calls)
			}
		})
	}
}

func TestAfterRound_R4_NoContinuationPastMaxTurns(t *testing.T) {
	for _, m := range rbModes {
		t.Run(m.name, func(t *testing.T) {
			llm := newRBLLM(t, []rbReply{{content: "only"}})
			var calls []rbCall
			r, err, _ := rbGo(t, m.stream, StyleOpenAI, llm.srv.URL, rbRecorder(&calls, func(AfterRoundEvent) *RoundInjection {
				return &RoundInjection{Inject: []string{"again"}}
			}), 1)
			if err != nil {
				t.Fatal(err)
			}
			if n := len(llm.sent()); n != 1 || r.Text != "only" || len(calls) != 0 {
				t.Fatalf("requests=%d text=%q calls=%v", n, r.Text, calls)
			}
		})
	}
}

func TestAfterRound_R5_FailingHookStopsTheRun(t *testing.T) {
	boom := errors.New("round-boom")
	for _, m := range rbModes {
		t.Run(m.name, func(t *testing.T) {
			llm := newRBLLM(t, []rbReply{{call: true}, {content: "never"}})
			_, err, _ := rbGo(t, m.stream, StyleOpenAI, llm.srv.URL, &Hooks{AfterRound: func(context.Context, AfterRoundEvent) (*RoundInjection, error) {
				return nil, boom
			}}, 10)
			if !errors.Is(err, boom) {
				t.Fatalf("err = %v, want round-boom", err)
			}
			if n := len(llm.sent()); n != 1 {
				t.Fatalf("requests = %d, want 1", n)
			}
		})
	}
}

func TestAfterRound_AbsentIsByteIdentical(t *testing.T) {
	for _, m := range rbModes {
		t.Run(m.name, func(t *testing.T) {
			a := newRBLLM(t, []rbReply{{call: true}, {content: "done"}})
			b := newRBLLM(t, []rbReply{{call: true}, {content: "done"}})
			ra, err1, _ := rbGo(t, m.stream, StyleOpenAI, a.srv.URL, nil, 10)
			rb, err2, _ := rbGo(t, m.stream, StyleOpenAI, b.srv.URL, &Hooks{AfterRound: func(context.Context, AfterRoundEvent) (*RoundInjection, error) {
				return nil, nil
			}}, 10)
			if err1 != nil || err2 != nil {
				t.Fatal(err1, err2)
			}
			if !reflect.DeepEqual(a.sent(), b.sent()) || !reflect.DeepEqual(ra.Messages, rb.Messages) {
				t.Fatal("a no-op AfterRound changed the wire or the transcript")
			}
		})
	}
}
