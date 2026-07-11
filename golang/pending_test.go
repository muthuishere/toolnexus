// §10 Suspension — Pending / WaitFor conformance test. Mirrors js/examples/pending.ts.
//
// A tool can't finish in one shot: it needs an out-of-band, async resolution (a human logs
// in, approves, types a value). It returns AuthRequired(...) / Pending(...); the client calls
// the host's WaitFor(request) -> Answer, then RETRIES the tool with ctx.Answer. Login is just
// the kind:"authorization" case — no auth subsystem, just data + one function.
//
// This drives the REAL client loop against a tiny stubbed "LLM" (httptest, no network, no key)
// so the mechanism actually fires. It exercises both host postures:
//
//	A) WaitFor provided → the engine resolves + retries transparently; the model gets the answer.
//	B) no WaitFor       → Run halts with { Status:"pending", Pending:request } to resume later.
package toolnexus

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// balanceToolkit builds a toolkit with a get_balance tool that needs authorization the first
// time, then succeeds. authed is flipped out-of-band (by WaitFor, simulating a human login).
func balanceToolkit(t *testing.T, authed *bool) *Toolkit {
	t.Helper()
	balance := Tool{
		Name:        "get_balance",
		Description: "Return the account balance. Requires login first.",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}, "additionalProperties": false},
		Source:      SourceCustom,
		Execute: func(_ map[string]any, ctx *ToolContext) (ToolResult, error) {
			if !*authed {
				return AuthRequired("https://example.com/login?token=abc", "Log in to view your balance"), nil
			}
			id := ""
			if ctx != nil && ctx.Answer != nil {
				id = ctx.Answer.ID
			}
			return ToolResult{Output: "balance: 67,417 (resolved via answer " + id + ")"}, nil
		},
	}
	tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{balance}})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

// stubLLM returns an httptest server standing in for an OpenAI endpoint: turn 1 calls
// get_balance; turn 2 (once it sees a tool result in the transcript) returns a final answer.
func stubLLM(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []map[string]any `json:"messages"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		sawToolResult := false
		for _, m := range body.Messages {
			if m["role"] == "tool" {
				sawToolResult = true
			}
		}
		var message map[string]any
		if sawToolResult {
			message = map[string]any{"role": "assistant", "content": "Done — your balance is 67,417."}
		} else {
			message = map[string]any{
				"role":    "assistant",
				"content": nil,
				"tool_calls": []any{map[string]any{
					"id":       "c1",
					"type":     "function",
					"function": map[string]any{"name": "get_balance", "arguments": "{}"},
				}},
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": message}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
}

// TestPendingWaitForResolves is posture A: WaitFor provided → resolve + retry transparently.
func TestPendingWaitForResolves(t *testing.T) {
	srv := stubLLM(t)
	defer srv.Close()

	authed := false
	var mu sync.Mutex
	var seen []string
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		// WaitFor is the ONLY behavior the host supplies. Here we simulate the human completing
		// login. In real life: open a browser, OR text the link to a channel, OR forward over A2A.
		WaitFor: func(req Request) (Answer, error) {
			mu.Lock()
			seen = append(seen, req.URL)
			mu.Unlock()
			authed = true // the world changed out-of-band
			return Answer{ID: req.ID, Ok: true}, nil
		},
	})

	res, err := c.Run(context.Background(), "what is my balance?", balanceToolkit(t, &authed))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done", res.Status)
	}
	if len(seen) == 0 || !strings.Contains(seen[0], "example.com/login") {
		t.Fatalf("link delivered to host = %v, want it to contain example.com/login", seen)
	}
	if len(res.ToolCalls) == 0 || !strings.Contains(res.ToolCalls[0].Output, "67,417") {
		t.Fatalf("tool call output = %+v, want it to contain 67,417", res.ToolCalls)
	}
	if !strings.Contains(res.Text, "67,417") {
		t.Fatalf("final answer = %q, want it to contain the balance", res.Text)
	}
}

// TestPendingNoWaitForHalts is posture B: no WaitFor → durable halt with the request to resume.
func TestPendingNoWaitForHalts(t *testing.T) {
	srv := stubLLM(t)
	defer srv.Close()

	authed := false
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})

	res, err := c.Run(context.Background(), "what is my balance?", balanceToolkit(t, &authed))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "pending" {
		t.Fatalf("status = %q, want pending", res.Status)
	}
	if res.Pending == nil {
		t.Fatal("pending request is nil")
	}
	if res.Pending.Kind != "authorization" {
		t.Fatalf("pending.kind = %q, want authorization", res.Pending.Kind)
	}
	if !strings.Contains(res.Pending.URL, "example.com/login") {
		t.Fatalf("pending.url = %q, want it to contain the login URL", res.Pending.URL)
	}
}

// questionLLM stands in for an OpenAI endpoint that always calls the `question` builtin
// with a fixed set of questions — used to drive the §10 suspension through the client loop.
func questionLLM(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		args := `{"questions":[{"question":"Pick a color?","options":["red","green"]}]}`
		message := map[string]any{
			"role":    "assistant",
			"content": nil,
			"tool_calls": []any{map[string]any{
				"id":       "c1",
				"type":     "function",
				"function": map[string]any{"name": "question", "arguments": args},
			}},
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": message}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
}

// TestQuestionNoWaitForHalts: the model calls the `question` builtin; with no WaitFor the loop
// halts durably (§10) with Status:"pending" carrying the kind:"question" Request. Mirrors the
// JS "a question suspension with no waitFor halts the run" test.
func TestQuestionNoWaitForHalts(t *testing.T) {
	srv := questionLLM(t)
	defer srv.Close()

	tk, err := CreateToolkit(context.Background(), Options{}) // builtins on → `question` exists
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"}) // no WaitFor
	res, err := c.Run(context.Background(), "ask me", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "pending" {
		t.Fatalf("status = %q, want pending (no WaitFor ⇒ the run halts, does not loop forever)", res.Status)
	}
	if res.Pending == nil {
		t.Fatal("pending request is nil")
	}
	if res.Pending.Kind != "question" {
		t.Fatalf("pending.kind = %q, want question", res.Pending.Kind)
	}
	if want := "Pick a color? (options: red, green)"; res.Pending.Prompt != want {
		t.Fatalf("pending.prompt = %q, want %q", res.Pending.Prompt, want)
	}
}

// TestPendingWireKeys pins the byte-identical JSON keys for Request/Answer across ports.
func TestPendingWireKeys(t *testing.T) {
	reqJSON, _ := json.Marshal(Request{ID: "r1", Kind: "input", Prompt: "p", URL: "u", Data: map[string]any{"k": "v"}, ExpiresAt: "2026-01-01T00:00:00Z"})
	if got := string(reqJSON); got != `{"id":"r1","kind":"input","prompt":"p","url":"u","data":{"k":"v"},"expiresAt":"2026-01-01T00:00:00Z"}` {
		t.Fatalf("Request JSON = %s", got)
	}
	ansJSON, _ := json.Marshal(Answer{ID: "r1", Ok: true, Data: map[string]any{"k": "v"}})
	if got := string(ansJSON); got != `{"id":"r1","ok":true,"data":{"k":"v"}}` {
		t.Fatalf("Answer JSON = %s", got)
	}
}
