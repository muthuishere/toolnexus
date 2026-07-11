// Observability + streaming-memory tests (§8). Hermetic: mock LLMs via httptest,
// no network, no live model. Covers:
//   - StreamWithID(id) remembers the transcript across two consumptions
//   - AskStream(onText) forwards each text delta AND returns the full RunResult
//   - OnMetric fires llm/tool/run semantic events
//   - Metrics() renders Prometheus text BYTE-IDENTICAL to the js reference
//
// Run: go test -race .
package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func mustJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

// mockStreamCountingLLM answers streamed /chat/completions with an SSE whose text
// delta is the number of messages the request carried — so a growing count proves
// history was reloaded across StreamWithID calls with the same id.
func mockStreamCountingLLM() *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []any `json:"messages"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		w.Header().Set("content-type", "text/event-stream")
		fmt.Fprintf(w, "data: %s\n\n", mustJSON(map[string]any{
			"choices": []any{map[string]any{"delta": map[string]any{"content": fmt.Sprint(len(body.Messages))}}},
		}))
		fmt.Fprintf(w, "data: %s\n\n", mustJSON(map[string]any{
			"choices": []any{map[string]any{"delta": map[string]any{}}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		}))
		fmt.Fprint(w, "data: [DONE]\n\n")
	}))
}

// drainStream collects the terminal RunResult (or error) from a stream channel.
func drainStream(t *testing.T, ch <-chan StreamEvent) RunResult {
	t.Helper()
	var res RunResult
	for ev := range ch {
		switch ev.Type {
		case "done":
			if ev.Result != nil {
				res = *ev.Result
			}
		case "error":
			t.Fatalf("stream error: %v", ev.Err)
		}
	}
	return res
}

// TestStreamWithIDRemembers: an empty id ⇒ stateless; a repeated id remembers the
// transcript (loaded as history, saved on the terminal done event).
func TestStreamWithIDRemembers(t *testing.T) {
	llm := mockStreamCountingLLM()
	defer llm.Close()

	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	client := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "x", APIKey: "k"})
	ctx := context.Background()

	// Stateless (empty id): always sees just the single user turn.
	ch, err := client.StreamWithID(ctx, "hi", tk, "")
	if err != nil {
		t.Fatalf("StreamWithID(no id): %v", err)
	}
	if got := drainStream(t, ch).Text; got != "1" {
		t.Fatalf("no id ⇒ stateless: text = %q, want 1", got)
	}

	// First stateful turn: 1 message (user).
	ch, err = client.StreamWithID(ctx, "first", tk, "s1")
	if err != nil {
		t.Fatalf("StreamWithID(s1 #1): %v", err)
	}
	if got := drainStream(t, ch).Text; got != "1" {
		t.Fatalf("first turn: text = %q, want 1", got)
	}

	// Second stateful turn with same id: user+assistant+user = 3 messages remembered.
	ch, err = client.StreamWithID(ctx, "second", tk, "s1")
	if err != nil {
		t.Fatalf("StreamWithID(s1 #2): %v", err)
	}
	if got := drainStream(t, ch).Text; got != "3" {
		t.Fatalf("same id remembers (user+assistant+user): text = %q, want 3", got)
	}

	// A distinct id is independent.
	ch, err = client.StreamWithID(ctx, "other", tk, "s2")
	if err != nil {
		t.Fatalf("StreamWithID(s2): %v", err)
	}
	if got := drainStream(t, ch).Text; got != "1" {
		t.Fatalf("a different id is independent: text = %q, want 1", got)
	}
}

// TestAskStreamOnText: with onText the streaming path forwards each delta AND
// still returns the full RunResult; without onText the non-streaming path works.
func TestAskStreamOnText(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Stream bool `json:"stream"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.Stream {
			w.Header().Set("content-type", "text/event-stream")
			fmt.Fprintf(w, "data: %s\n\n", mustJSON(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": "Hel"}}}}))
			fmt.Fprintf(w, "data: %s\n\n", mustJSON(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": "lo"}}}}))
			fmt.Fprintf(w, "data: %s\n\n", mustJSON(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}}}, "usage": map[string]any{"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}}))
			fmt.Fprint(w, "data: [DONE]\n\n")
			return
		}
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": map[string]any{"content": "Hello"}}},
			"usage":   map[string]any{"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5},
		})
	}))
	defer server.Close()

	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	client := CreateClient(ClientOptions{BaseURL: server.URL, Style: StyleOpenAI, Model: "x", APIKey: "k"})
	ctx := context.Background()

	var deltas []string
	streamed, err := client.AskStream(ctx, "hi", tk, "", func(d string) { deltas = append(deltas, d) })
	if err != nil {
		t.Fatalf("AskStream(onText): %v", err)
	}
	if len(deltas) != 2 || deltas[0] != "Hel" || deltas[1] != "lo" {
		t.Fatalf("onText deltas = %v, want [Hel lo]", deltas)
	}
	if streamed.Text != "Hello" {
		t.Fatalf("AskStream returns full RunResult: text = %q, want Hello", streamed.Text)
	}
	if streamed.Usage.TotalTokens != 5 {
		t.Fatalf("usage.TotalTokens = %d, want 5", streamed.Usage.TotalTokens)
	}

	plain, err := client.Ask(ctx, "hi", tk, "")
	if err != nil {
		t.Fatalf("Ask(no onText): %v", err)
	}
	if plain.Text != "Hello" {
		t.Fatalf("without onText: non-streaming path text = %q, want Hello", plain.Text)
	}
}

// TestOnMetricFires: OnMetric receives one llm event per LLM call, one tool event
// per tool call (with the tool's source), and a final aggregated run event.
func TestOnMetricFires(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []map[string]any `json:"messages"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		hasToolResult := false
		for _, m := range body.Messages {
			if m["role"] == "tool" {
				hasToolResult = true
			}
		}
		w.Header().Set("content-type", "application/json")
		if !hasToolResult {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []any{map[string]any{"message": map[string]any{
					"content":    nil,
					"tool_calls": []any{map[string]any{"id": "c1", "type": "function", "function": map[string]any{"name": "add", "arguments": `{"a":2,"b":3}`}}},
				}}},
				"usage": map[string]any{"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9},
			})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": map[string]any{"content": "5"}}},
			"usage":   map[string]any{"prompt_tokens": 6, "completion_tokens": 1, "total_tokens": 7},
		})
	}))
	defer server.Close()

	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()
	tk.Register(NativeTool("add", "add", nil, func(ctx context.Context, args map[string]any) (string, error) {
		a, _ := args["a"].(float64)
		b, _ := args["b"].(float64)
		return fmt.Sprint(int(a + b)), nil
	}))

	var events []MetricEvent
	client := CreateClient(ClientOptions{BaseURL: server.URL, Style: StyleOpenAI, Model: "gpt-x", APIKey: "k", OnMetric: func(ev MetricEvent) { events = append(events, ev) }})
	if _, err := client.Run(context.Background(), "add them", tk); err != nil {
		t.Fatalf("Run: %v", err)
	}

	var llmCount int
	var sawTool, sawLLM bool
	for _, e := range events {
		switch e.Event {
		case "llm":
			sawLLM = true
			llmCount++
			if e.Model != "gpt-x" || e.Status != "ok" {
				t.Fatalf("llm event = %+v, want model gpt-x status ok", e)
			}
		case "tool":
			sawTool = true
			if e.Tool != "add" || e.Source != "native" || e.IsError {
				t.Fatalf("tool event = %+v, want tool add source native isError false", e)
			}
		}
	}
	if !sawLLM {
		t.Fatal("no llm event fired")
	}
	if !sawTool {
		t.Fatal("no tool event fired")
	}
	if llmCount != 2 {
		t.Fatalf("llm events = %d, want 2 (one per call)", llmCount)
	}

	run := events[len(events)-1]
	if run.Event != "run" {
		t.Fatalf("last event = %q, want run", run.Event)
	}
	if run.Model != "gpt-x" || run.Turns != 2 || run.ToolCalls != 1 || run.TotalTokens != 16 || run.Error != "" {
		t.Fatalf("run event = %+v, want model gpt-x turns 2 toolCalls 1 totalTokens 16 no error", run)
	}
}

// jsMetricsReference is the exact Prometheus text the js port renders for the
// fixed event set below (captured from js/dist by driving its registry). Metrics
// in fixed order; series sorted by label string; fixed buckets; le rendered
// 0.05/…/60/+Inf; sums via String(); trailing newline. BYTE-IDENTICAL contract.
const jsMetricsReference = `# HELP toolnexus_llm_requests_total Total LLM requests.
# TYPE toolnexus_llm_requests_total counter
toolnexus_llm_requests_total{model="gpt-x",status="ok"} 1
# HELP toolnexus_llm_tokens_total Total tokens, by type.
# TYPE toolnexus_llm_tokens_total counter
toolnexus_llm_tokens_total{type="completion"} 2
toolnexus_llm_tokens_total{type="prompt"} 3
# HELP toolnexus_llm_request_duration_seconds LLM request duration in seconds.
# TYPE toolnexus_llm_request_duration_seconds histogram
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.05"} 0
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.1"} 0
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.25"} 1
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.5"} 1
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="1"} 1
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="2.5"} 1
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="5"} 1
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="10"} 1
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="30"} 1
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="60"} 1
toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="+Inf"} 1
toolnexus_llm_request_duration_seconds_sum{model="gpt-x"} 0.12
toolnexus_llm_request_duration_seconds_count{model="gpt-x"} 1
# HELP toolnexus_tool_calls_total Total tool calls.
# TYPE toolnexus_tool_calls_total counter
toolnexus_tool_calls_total{tool="add",source="native",is_error="false",pending="false"} 1
# HELP toolnexus_tool_duration_seconds Tool execution duration in seconds.
# TYPE toolnexus_tool_duration_seconds histogram
toolnexus_tool_duration_seconds_bucket{tool="add",le="0.05"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="0.1"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="0.25"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="0.5"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="1"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="2.5"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="5"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="10"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="30"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="60"} 1
toolnexus_tool_duration_seconds_bucket{tool="add",le="+Inf"} 1
toolnexus_tool_duration_seconds_sum{tool="add"} 0.04
toolnexus_tool_duration_seconds_count{tool="add"} 1
# HELP toolnexus_run_errors_total Total run errors.
# TYPE toolnexus_run_errors_total counter
`

// TestMetricsByteParity feeds a fixed set of events straight into the registry
// (deterministic ms, no timing) and asserts Metrics() is byte-identical to the js
// reference. Also checks the empty-but-valid state before any activity.
func TestMetricsByteParity(t *testing.T) {
	client := CreateClient(ClientOptions{BaseURL: "http://x", Style: StyleOpenAI, Model: "gpt-x", APIKey: "k"})

	// Before any activity: only # HELP / # TYPE lines, no series samples.
	before := client.Metrics()
	for _, line := range splitNonEmpty(before) {
		if line[0] != '#' {
			t.Fatalf("before activity: unexpected sample line %q", line)
		}
	}

	client.registry.record(MetricEvent{Event: "llm", Model: "gpt-x", Status: "ok", Ms: 120, PromptTokens: 3, CompletionTokens: 2})
	client.registry.record(MetricEvent{Event: "tool", Tool: "add", Source: "native", IsError: false, Ms: 40})
	client.registry.record(MetricEvent{Event: "run", Model: "gpt-x", Turns: 2, ToolCalls: 1, TotalTokens: 16, Ms: 200})

	got := client.Metrics()
	if got != jsMetricsReference {
		t.Fatalf("Metrics() not byte-identical to js reference.\n--- got ---\n%s\n--- want ---\n%s", got, jsMetricsReference)
	}
}

func splitNonEmpty(s string) []string {
	var out []string
	for _, line := range splitLines(s) {
		if line != "" {
			out = append(out, line)
		}
	}
	return out
}

func splitLines(s string) []string {
	var out []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '\n' {
			out = append(out, s[start:i])
			start = i + 1
		}
	}
	if start < len(s) {
		out = append(out, s[start:])
	}
	return out
}
