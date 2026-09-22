// Scripted in-process mock LLM (OpenAI wire style), keyed by body.model.
// Zero network, zero cost, no API key — the whole spike runs offline.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// perModelTokens lets each agent have a DISTINCT, legible price so the
// arithmetic in the output is checkable by eye.
var perModelTokens = map[string]int{
	"m-parent": 100,
	"m-child":  400,
	"m-loop":   70,
	"m-sus":    50,
}

type mockLLM struct{}

func (m *mockLLM) RoundTrip(req *http.Request) (*http.Response, error) {
	b, _ := io.ReadAll(req.Body)
	var body struct {
		Model    string           `json:"model"`
		Messages []map[string]any `json:"messages"`
	}
	_ = json.Unmarshal(b, &body)

	// Tool results this turn can see, in order, as "name: output".
	var seen []string
	for i, msg := range body.Messages {
		if msg["role"] != "tool" {
			continue
		}
		c, _ := msg["content"].(string)
		seen = append(seen, nameOfToolMsg(body.Messages, i)+": "+strings.TrimSpace(c))
	}
	countOf := func(tool string) int {
		n := 0
		for _, s := range seen {
			if strings.HasPrefix(s, tool+":") {
				n++
			}
		}
		return n
	}

	tok := perModelTokens[body.Model]
	if tok == 0 {
		tok = 10
	}

	switch body.Model {
	case "m-parent":
		// One delegation, then report.
		if countOf("task") == 0 {
			return call(tok, "task", map[string]any{"agent": "explore", "prompt": "survey the repo"}), nil
		}
		return text(tok, "coordinator done; the explorer reported back"), nil

	case "m-child":
		return text(tok, "explorer done"), nil

	case "m-loop":
		// Never finishes: always another tool call, so maxTurns must stop it.
		return call(tok, "noop", map[string]any{}), nil

	case "m-sus":
		// A three-step plan: stage -> get approval -> commit.
		//   counter("stage")  ->  approve  ->  counter("commit")  ->  done
		// It reads ONLY the transcript it is given, like a real model. So if
		// the transcript is rewound, it honestly redoes the steps it cannot see.
		approved := false
		for _, s := range seen {
			if strings.HasPrefix(s, "approve:") && strings.Contains(s, "APPROVED") {
				approved = true
			}
		}
		n := countOf("counter")
		if !approved {
			if n < 1 {
				return call(tok, "counter", map[string]any{"step": "stage"}), nil
			}
			return call(tok, "approve", map[string]any{}), nil
		}
		if n < 2 {
			return call(tok, "counter", map[string]any{"step": "commit"}), nil
		}
		return text(tok, "staged, approved and committed"), nil
	}
	return text(tok, "ok"), nil
}

// nameOfToolMsg resolves a role=="tool" message back to its tool name by
// matching tool_call_id against the preceding assistant tool_calls.
func nameOfToolMsg(msgs []map[string]any, idx int) string {
	id, _ := msgs[idx]["tool_call_id"].(string)
	for i := idx - 1; i >= 0; i-- {
		calls, ok := msgs[i]["tool_calls"].([]any)
		if !ok {
			continue
		}
		for _, raw := range calls {
			c, _ := raw.(map[string]any)
			if cid, _ := c["id"].(string); cid == id {
				fn, _ := c["function"].(map[string]any)
				name, _ := fn["name"].(string)
				return name
			}
		}
	}
	return "?"
}

var callSeq int

func call(tok int, name string, args map[string]any) *http.Response {
	callSeq++
	aj, _ := json.Marshal(args)
	return resp(tok, map[string]any{
		"content": nil,
		"tool_calls": []any{map[string]any{
			"id": fmt.Sprintf("c%d", callSeq), "type": "function",
			"function": map[string]any{"name": name, "arguments": string(aj)},
		}},
	})
}

func text(tok int, content string) *http.Response {
	return resp(tok, map[string]any{"content": content})
}

func resp(tok int, message map[string]any) *http.Response {
	msg := map[string]any{"role": "assistant"}
	for k, v := range message {
		msg[k] = v
	}
	out, _ := json.Marshal(map[string]any{
		"choices": []any{map[string]any{"message": msg}},
		"usage": map[string]any{
			"prompt_tokens": tok / 2, "completion_tokens": tok - tok/2, "total_tokens": tok,
		},
	})
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(bytes.NewReader(out)),
	}
}
