package toolnexus

// Shared test helpers for relay mode (§10, ADR-0010): scripted mock LLMs for the OpenAI,
// Anthropic-native and streaming loops, plus transcript counters.
//
// These began as spike 0002's scaffolding (docs/spikes/0002-relay-mode-stress-and-non-regression.md).
// The spike's own assertions were folded into relay_test.go once the implementation
// landed; the pre-change baseline assertions it carried are preserved in git at commit
// 0d34285, which is the reproducible evidence trail. Only the helpers remain here.

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

// spikeExecTool is an ordinary executing tool, used to prove relay does not disturb
// real tools sharing the same turn. (The hand-rolled relay prototype that used to live
// here is superseded by RelayTool in relay.go.)
func spikeExecTool(name string, ran *bool, mu *sync.Mutex) Tool {
	return Tool{
		Name:        name,
		Description: "a real tool that really runs",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      SourceCustom,
		Execute: func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			mu.Lock()
			*ran = true
			mu.Unlock()
			return ToolResult{Output: "real-output"}, nil
		},
	}
}

// ---- mock LLMs ----

// spikeCall is one tool_call the mock LLM should emit.
type spikeCall struct{ id, name, args string }

// spikeScriptedLLM replays one scripted assistant turn per request, in order. A turn
// with no calls is a final text answer. It records every request body it received so a
// test can assert on what the loop actually sent back (the transcript the provider saw).
type spikeScriptedLLM struct {
	srv    *httptest.Server
	mu     sync.Mutex
	turn   int
	bodies []map[string]any
}

func newSpikeScriptedLLM(t *testing.T, turns [][]spikeCall, final string) *spikeScriptedLLM {
	t.Helper()
	m := &spikeScriptedLLM{}
	m.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		m.mu.Lock()
		m.bodies = append(m.bodies, body)
		i := m.turn
		m.turn++
		m.mu.Unlock()

		message := map[string]any{"role": "assistant"}
		if i < len(turns) {
			var tcs []any
			for _, c := range turns[i] {
				tcs = append(tcs, map[string]any{
					"id": c.id, "type": "function",
					"function": map[string]any{"name": c.name, "arguments": c.args},
				})
			}
			message["content"] = nil
			message["tool_calls"] = tcs
		} else {
			message["content"] = final
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": message}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
	t.Cleanup(m.srv.Close)
	return m
}

func (m *spikeScriptedLLM) requests() []map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]map[string]any, len(m.bodies))
	copy(out, m.bodies)
	return out
}

// spikeToolkit builds a builtins-off toolkit (the proxy posture) with the given tools.
func spikeToolkit(t *testing.T, tools ...Tool) *Toolkit {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: tools})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { tk.Close() })
	return tk
}

// spikeMessageCounts counts tool_use/tool_call blocks vs tool_result messages in a
// transcript, which is how the durable-halt transcript shape is asserted below.
// The OpenAI-style transcript uses {role:"assistant", tool_calls:[...]} and
// {role:"tool", tool_call_id:...} messages.
func spikeMessageCounts(messages []any) (calls, results int, resultIDs []string) {
	for _, m := range messages {
		mm, ok := m.(map[string]any)
		if !ok {
			continue
		}
		if tcs, ok := mm["tool_calls"].([]any); ok {
			calls += len(tcs)
		}
		if id, ok := mm["tool_call_id"].(string); ok {
			results++
			resultIDs = append(resultIDs, id)
		}
	}
	return
}

// ---- S11: the Anthropic-style loop (closes a spike-0002 caveat) ----

// spikeAnthropicLLM replays scripted Anthropic-native turns: content blocks of
// type tool_use/text on POST /v1/messages. It records request bodies like its
// OpenAI twin so assertions can inspect the transcript the provider received.
type spikeAnthropicLLM struct {
	srv    *httptest.Server
	mu     sync.Mutex
	turn   int
	bodies []map[string]any
}

func newSpikeAnthropicLLM(t *testing.T, turns [][]spikeCall, final string) *spikeAnthropicLLM {
	t.Helper()
	m := &spikeAnthropicLLM{}
	m.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		m.mu.Lock()
		m.bodies = append(m.bodies, body)
		i := m.turn
		m.turn++
		m.mu.Unlock()

		var content []any
		stop := "end_turn"
		if i < len(turns) {
			stop = "tool_use"
			for _, c := range turns[i] {
				var in map[string]any
				_ = json.Unmarshal([]byte(c.args), &in)
				content = append(content, map[string]any{
					"type": "tool_use", "id": c.id, "name": c.name, "input": in,
				})
			}
		} else {
			content = append(content, map[string]any{"type": "text", "text": final})
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"content":     content,
			"stop_reason": stop,
			"usage":       map[string]any{"input_tokens": 1, "output_tokens": 1},
		})
	}))
	t.Cleanup(m.srv.Close)
	return m
}

func (m *spikeAnthropicLLM) requests() []map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]map[string]any, len(m.bodies))
	copy(out, m.bodies)
	return out
}

// ---- S12: the streaming loop (closes the other spike-0002 caveat) ----

// spikeStreamLLM streams one assistant turn of tool_calls over SSE, then usage, then
// [DONE]. Second and later requests stream a plain text answer.
func spikeStreamLLM(t *testing.T, calls []spikeCall, final string) *httptest.Server {
	t.Helper()
	var mu sync.Mutex
	var turn int
	write := func(w http.ResponseWriter, v any) {
		b, _ := json.Marshal(v)
		_, _ = w.Write([]byte("data: " + string(b) + "\n\n"))
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		i := turn
		turn++
		mu.Unlock()
		w.Header().Set("content-type", "text/event-stream")
		if i == 0 {
			var tcs []any
			for idx, c := range calls {
				tcs = append(tcs, map[string]any{
					"index": idx, "id": c.id, "type": "function",
					"function": map[string]any{"name": c.name, "arguments": c.args},
				})
			}
			write(w, map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": tcs}}}})
		} else {
			write(w, map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": final}}}})
		}
		write(w, map[string]any{
			"choices": []any{map[string]any{"delta": map[string]any{}}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
		_, _ = w.Write([]byte("data: [DONE]\n\n"))
	}))
	t.Cleanup(srv.Close)
	return srv
}
