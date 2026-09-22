// Scripted in-process mock LLM (OpenAI wire style). Zero network, zero cost,
// no API key. It asks for `ask_human` once, then finishes by quoting verbatim
// whatever tool_result it was shown — which is the whole point of this spike:
// the final text IS the evidence of what the model was told the tool returned.
package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

type mockLLM struct{ calls int }

func (m *mockLLM) RoundTrip(req *http.Request) (*http.Response, error) {
	b, _ := io.ReadAll(req.Body)
	var body struct {
		Messages []map[string]any `json:"messages"`
	}
	_ = json.Unmarshal(b, &body)

	for i := len(body.Messages) - 1; i >= 0; i-- {
		if body.Messages[i]["role"] == "tool" {
			c, _ := body.Messages[i]["content"].(string)
			return resp(map[string]any{"content": "model saw tool_result: " + strings.TrimSpace(c)}), nil
		}
	}
	m.calls++
	args, _ := json.Marshal(map[string]any{"question": "which environment?"})
	return resp(map[string]any{
		"content": nil,
		"tool_calls": []any{map[string]any{
			"id": "call_1", "type": "function",
			"function": map[string]any{"name": "ask_human", "arguments": string(args)},
		}},
	}), nil
}

func resp(message map[string]any) *http.Response {
	msg := map[string]any{"role": "assistant"}
	for k, v := range message {
		msg[k] = v
	}
	out, _ := json.Marshal(map[string]any{
		"choices": []any{map[string]any{"message": msg}},
		"usage":   map[string]any{"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
	})
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(bytes.NewReader(out)),
	}
}
