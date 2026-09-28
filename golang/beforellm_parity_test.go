// SPEC §8 beforeLLM parity across every entry point (run / stream × openai /
// anthropic, and translate × both styles):
//   - a failing beforeLLM hook stops the call: the error propagates and NO
//     provider request is sent;
//   - a model override is transmitted for that turn only (absent ⇒ configured);
//   - the model REPORTED is the model transmitted: the turn's "llm" metric,
//     RunResult.Model and the "run" metric (= the last call's model), and
//     translate's result.Model.
//
// Hermetic: one httptest server scripts both styles, streaming and not.

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

// parityLLM: turn 1 calls the `deploy` tool, later turns answer "done". Records bodies.
type parityLLM struct {
	mu     sync.Mutex
	bodies []map[string]any
	srv    *httptest.Server
}

func newParityLLM(t *testing.T) *parityLLM {
	p := &parityLLM{}
	p.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body := map[string]any{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		p.mu.Lock()
		p.bodies = append(p.bodies, body)
		n := len(p.bodies)
		p.mu.Unlock()
		anth := strings.HasSuffix(r.URL.Path, "/messages")
		stream, _ := body["stream"].(bool)
		tool := n == 1
		sse := func(v any) { fmt.Fprintf(w, "data: %s\n\n", mustJSON(v)) }
		switch {
		case !anth && !stream:
			w.Header().Set("content-type", "application/json")
			msg := map[string]any{"role": "assistant", "content": "done"}
			if tool {
				msg = map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{map[string]any{
					"id": "c1", "type": "function", "function": map[string]any{"name": "deploy", "arguments": "{}"}}}}
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": msg}}})
		case anth && !stream:
			w.Header().Set("content-type", "application/json")
			resp := map[string]any{"content": []any{map[string]any{"type": "text", "text": "done"}}, "stop_reason": "end_turn"}
			if tool {
				resp = map[string]any{"content": []any{map[string]any{"type": "tool_use", "id": "c1", "name": "deploy", "input": map[string]any{}}}, "stop_reason": "tool_use"}
			}
			_ = json.NewEncoder(w).Encode(resp)
		case !anth && stream:
			w.Header().Set("content-type", "text/event-stream")
			if tool {
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{map[string]any{
					"index": 0, "id": "c1", "type": "function", "function": map[string]any{"name": "deploy", "arguments": "{}"}}}}}}})
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "tool_calls"}}})
			} else {
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": "done"}}}})
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "stop"}}})
			}
			fmt.Fprint(w, "data: [DONE]\n\n")
		default: // anthropic stream
			w.Header().Set("content-type", "text/event-stream")
			if tool {
				sse(map[string]any{"type": "content_block_start", "index": 0, "content_block": map[string]any{"type": "tool_use", "id": "c1", "name": "deploy"}})
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
	t.Cleanup(p.srv.Close)
	return p
}

func (p *parityLLM) models() []any {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := []any{}
	for _, b := range p.bodies {
		out = append(out, b["model"])
	}
	return out
}

type parityEntry struct {
	name   string
	style  ClientStyle
	stream bool
}

var parityRunEntries = []parityEntry{
	{"run/openai", StyleOpenAI, false},
	{"run/anthropic", StyleAnthropic, false},
	{"stream/openai", StyleOpenAI, true},
	{"stream/anthropic", StyleAnthropic, true},
}

// parityRun drives one loop and returns (result, error, metric events).
func parityRun(t *testing.T, e parityEntry, url string, hooks *Hooks) (RunResult, error, []MetricEvent) {
	t.Helper()
	var mu sync.Mutex
	var evs []MetricEvent
	c := CreateClient(ClientOptions{BaseURL: url, Style: e.style, Model: "configured", APIKey: "k", MaxTurns: 4, Hooks: hooks,
		OnMetric: func(ev MetricEvent) { mu.Lock(); evs = append(evs, ev); mu.Unlock() }})
	tk := pbToolkit(t)
	if !e.stream {
		r, err := c.Run(context.Background(), "go", tk)
		return r, err, evs
	}
	ch, err := c.Stream(context.Background(), "go", tk)
	if err != nil {
		return RunResult{}, err, evs
	}
	var res RunResult
	var serr error
	for ev := range ch {
		switch ev.Type {
		case "done":
			if ev.Result != nil {
				res = *ev.Result
			}
		case "error":
			serr = ev.Err
		}
	}
	mu.Lock()
	defer mu.Unlock()
	return res, serr, evs
}

func metricModels(evs []MetricEvent, kind string) []string {
	out := []string{}
	for _, e := range evs {
		if e.Event == kind {
			out = append(out, e.Model)
		}
	}
	return out
}

func TestBeforeLLMErrorStopsEveryLoop(t *testing.T) {
	boom := errors.New("hook boom")
	for _, e := range parityRunEntries {
		t.Run(e.name, func(t *testing.T) {
			llm := newParityLLM(t)
			_, err, evs := parityRun(t, e, llm.srv.URL, &Hooks{BeforeLLM: func(context.Context, BeforeLLMEvent) (*LLMOverride, error) {
				return nil, boom
			}})
			if !errors.Is(err, boom) {
				t.Fatalf("err = %v, want hook error", err)
			}
			if n := len(llm.models()); n != 0 {
				t.Fatalf("%d provider requests sent, want 0", n)
			}
			if got := metricModels(evs, "run"); !reflect.DeepEqual(got, []string{"configured"}) {
				t.Fatalf("run metric models = %v", got)
			}
		})
		// a later turn's failing hook: the run still reports the last call's (overridden) model.
		t.Run(e.name+"/failed-after-override", func(t *testing.T) {
			llm := newParityLLM(t)
			_, err, evs := parityRun(t, e, llm.srv.URL, &Hooks{BeforeLLM: func(_ context.Context, ev BeforeLLMEvent) (*LLMOverride, error) {
				if ev.Turn == 0 {
					return &LLMOverride{Model: "small-fast"}, nil
				}
				return nil, boom
			}})
			if !errors.Is(err, boom) || len(llm.models()) != 1 {
				t.Fatalf("err = %v, requests = %d", err, len(llm.models()))
			}
			if got := metricModels(evs, "run"); !reflect.DeepEqual(got, []string{"small-fast"}) {
				t.Fatalf("run metric models = %v, want [small-fast]", got)
			}
		})
	}
}

func TestBeforeLLMModelOverrideEveryLoop(t *testing.T) {
	for _, e := range parityRunEntries {
		t.Run(e.name+"/absent", func(t *testing.T) {
			llm := newParityLLM(t)
			r, err, evs := parityRun(t, e, llm.srv.URL, &Hooks{BeforeLLM: func(context.Context, BeforeLLMEvent) (*LLMOverride, error) {
				return &LLMOverride{}, nil
			}})
			if err != nil {
				t.Fatal(err)
			}
			if got := llm.models(); !reflect.DeepEqual(got, []any{"configured", "configured"}) {
				t.Fatalf("bodies = %v", got)
			}
			if r.Model != "configured" || !reflect.DeepEqual(metricModels(evs, "run"), []string{"configured"}) {
				t.Fatalf("reported %q / %v", r.Model, metricModels(evs, "run"))
			}
		})
		t.Run(e.name+"/first-turn-only", func(t *testing.T) {
			llm := newParityLLM(t)
			r, err, evs := parityRun(t, e, llm.srv.URL, &Hooks{BeforeLLM: func(_ context.Context, ev BeforeLLMEvent) (*LLMOverride, error) {
				if ev.Turn == 0 {
					return &LLMOverride{Model: "small-fast"}, nil
				}
				return nil, nil
			}})
			if err != nil {
				t.Fatal(err)
			}
			if got := llm.models(); !reflect.DeepEqual(got, []any{"small-fast", "configured"}) {
				t.Fatalf("bodies = %v", got)
			}
			if got := metricModels(evs, "llm"); !reflect.DeepEqual(got, []string{"small-fast", "configured"}) {
				t.Fatalf("llm metric models = %v", got)
			}
			if r.Model != "configured" || !reflect.DeepEqual(metricModels(evs, "run"), []string{"configured"}) {
				t.Fatalf("reported %q / %v", r.Model, metricModels(evs, "run"))
			}
		})
		t.Run(e.name+"/last-turn", func(t *testing.T) {
			llm := newParityLLM(t)
			r, err, evs := parityRun(t, e, llm.srv.URL, &Hooks{BeforeLLM: func(_ context.Context, ev BeforeLLMEvent) (*LLMOverride, error) {
				if ev.Turn == 1 {
					return &LLMOverride{Model: "large"}, nil
				}
				return nil, nil
			}})
			if err != nil {
				t.Fatal(err)
			}
			if got := metricModels(evs, "llm"); !reflect.DeepEqual(got, []string{"configured", "large"}) {
				t.Fatalf("llm metric models = %v", got)
			}
			if r.Model != "large" || !reflect.DeepEqual(metricModels(evs, "run"), []string{"large"}) {
				t.Fatalf("reported %q / %v, want large", r.Model, metricModels(evs, "run"))
			}
		})
	}
}

func TestTranslateBeforeLLMParity(t *testing.T) {
	req := TranslateRequest{Messages: []any{map[string]any{"role": "user", "content": "hi"}}}
	for _, st := range []ClientStyle{StyleOpenAI, StyleAnthropic} {
		mk := func(url string, h *Hooks, evs *[]MetricEvent) *Client {
			return CreateClient(ClientOptions{BaseURL: url, Style: st, Model: "configured", APIKey: "k", Hooks: h,
				OnMetric: func(ev MetricEvent) { *evs = append(*evs, ev) }})
		}
		t.Run(string(st)+"/hook-error", func(t *testing.T) {
			llm := newParityLLM(t)
			boom := errors.New("hook boom")
			var evs []MetricEvent
			_, err := mk(llm.srv.URL, &Hooks{BeforeLLM: func(context.Context, BeforeLLMEvent) (*LLMOverride, error) { return nil, boom }}, &evs).
				Translate(context.Background(), req)
			if !errors.Is(err, boom) {
				t.Fatalf("err = %v, want hook error", err)
			}
			if n := len(llm.models()); n != 0 {
				t.Fatalf("%d provider requests sent, want 0", n)
			}
		})
		for _, ov := range []string{"", "small-fast"} {
			t.Run(string(st)+"/override="+ov, func(t *testing.T) {
				llm := newParityLLM(t)
				var evs []MetricEvent
				res, err := mk(llm.srv.URL, &Hooks{BeforeLLM: func(context.Context, BeforeLLMEvent) (*LLMOverride, error) {
					return &LLMOverride{Model: ov}, nil
				}}, &evs).Translate(context.Background(), req)
				if err != nil {
					t.Fatal(err)
				}
				want := ov
				if want == "" {
					want = "configured"
				}
				if got := llm.models(); !reflect.DeepEqual(got, []any{want}) {
					t.Fatalf("bodies = %v", got)
				}
				if res.Model != want || !reflect.DeepEqual(metricModels(evs, "llm"), []string{want}) {
					t.Fatalf("reported %q / %v, want %q", res.Model, metricModels(evs, "llm"), want)
				}
			})
		}
	}
}
