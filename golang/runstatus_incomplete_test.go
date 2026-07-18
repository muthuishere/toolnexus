// §8 addendum: RunResult.Status gains "incomplete" — a run stopped by the turn
// cap while the model was still emitting tool calls is LOUD, never a silent
// "done" (QG5; SPEC §7D limit stops).
package toolnexus

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

// loopingTransport scripts a model that ALWAYS asks for another tool call.
type loopingTransport struct{ calls int }

func (l *loopingTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	l.calls++
	body := map[string]any{
		"choices": []any{map[string]any{"message": map[string]any{
			"role":    "assistant",
			"content": nil,
			"tool_calls": []any{map[string]any{
				"id":   "c1",
				"type": "function",
				"function": map[string]any{"name": "noop", "arguments": "{}"},
			}},
		}}},
		"usage": map[string]any{"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10},
	}
	b, _ := json.Marshal(body)
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(bytes.NewReader(b)),
	}, nil
}

func incompleteToolkit(t *testing.T) *Toolkit {
	t.Helper()
	tk, err := CreateToolkit(nil, Options{Builtins: false, ExtraTools: []Tool{{
		Name:        "noop",
		Description: "does nothing",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      SourceCustom,
		Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
			return ToolResult{Output: "ok"}, nil
		},
	}}})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func TestRunStatusIncompleteAtMaxTurns(t *testing.T) {
	client := CreateClient(ClientOptions{
		BaseURL:    "http://mock.local",
		Style:      StyleOpenAI,
		Model:      "m",
		APIKey:     "test",
		MaxTurns:   2,
		HTTPClient: &http.Client{Transport: &loopingTransport{}},
	})
	r, err := client.Run(nil, "loop forever", incompleteToolkit(t))
	if err != nil {
		t.Fatal(err)
	}
	if r.Status != "incomplete" {
		t.Errorf("maxTurns while still tool-calling must be status incomplete, got %q", r.Status)
	}
	if r.Limit != "maxTurns" {
		t.Errorf("the limit must be NAMED on the result, got %q", r.Limit)
	}
	if r.Turns != 2 || r.ToolCallCount != 2 {
		t.Errorf("partial work should be preserved (turns=%d toolCalls=%d)", r.Turns, r.ToolCallCount)
	}
	if r.Pending != nil {
		t.Error("incomplete is a limit stop, not a suspension — no Pending")
	}
}

// loopingSSETransport is loopingTransport for the streaming path: every call
// streams one assembled tool call, never a final answer.
type loopingSSETransport struct{}

func (loopingSSETransport) RoundTrip(*http.Request) (*http.Response, error) {
	body := strings.Join([]string{
		`data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"noop","arguments":"{}"}}]}}]}`,
		`data: {"usage":{"prompt_tokens":5,"completion_tokens":5,"total_tokens":10}}`,
		`data: [DONE]`,
		``,
	}, "\n\n")
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"text/event-stream"}},
		Body:       io.NopCloser(strings.NewReader(body)),
	}, nil
}

func TestStreamStatusIncompleteAtMaxTurns(t *testing.T) {
	client := CreateClient(ClientOptions{
		BaseURL:    "http://mock.local",
		Style:      StyleOpenAI,
		Model:      "m",
		APIKey:     "test",
		MaxTurns:   1,
		HTTPClient: &http.Client{Transport: loopingSSETransport{}},
	})
	ch, err := client.Stream(nil, "loop forever", incompleteToolkit(t))
	if err != nil {
		t.Fatal(err)
	}
	var final *RunResult
	for ev := range ch {
		if ev.Type == "error" {
			t.Fatal(ev.Err)
		}
		if ev.Type == "done" {
			final = ev.Result
		}
	}
	if final == nil || final.Status != "incomplete" {
		status := "<none>"
		if final != nil {
			status = final.Status
		}
		t.Errorf("streaming maxTurns stop must also be incomplete, got %s", status)
	}
}

// A run that ends with a normal final answer stays "done" — the new status
// never leaks into completed runs.
func TestRunStatusDoneUnchangedOnFinalAnswer(t *testing.T) {
	done := func(req *http.Request) *http.Response {
		body := map[string]any{
			"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": "final"}}},
			"usage":   map[string]any{"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10},
		}
		b, _ := json.Marshal(body)
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": []string{"application/json"}}, Body: io.NopCloser(bytes.NewReader(b))}
	}
	client := CreateClient(ClientOptions{
		BaseURL:  "http://mock.local",
		Style:    StyleOpenAI,
		Model:    "m",
		APIKey:   "test",
		MaxTurns: 2,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			return done(req), nil
		})},
	})
	r, err := client.Run(nil, "just answer", incompleteToolkit(t))
	if err != nil {
		t.Fatal(err)
	}
	if r.Status != "done" || !strings.Contains(r.Text, "final") {
		t.Errorf("a normal completion stays done, got %q (%s)", r.Status, r.Text)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }
