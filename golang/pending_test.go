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
	"strconv"
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

// answerableQuestionLLM stands in for an OpenAI endpoint: turn 1 calls the `question`
// builtin; turn 2 (once it sees a tool result in the transcript) returns a final answer.
func answerableQuestionLLM(t *testing.T) *httptest.Server {
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
			message = map[string]any{"role": "assistant", "content": "done"}
		} else {
			args := `{"questions":[{"question":"Pick?","options":["a","b"]}]}`
			message = map[string]any{
				"role":    "assistant",
				"content": nil,
				"tool_calls": []any{map[string]any{
					"id":       "c1",
					"type":     "function",
					"function": map[string]any{"name": "question", "arguments": args},
				}},
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": message}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
}

// TestSuspensionIsNotToolError (G2): a suspension emits a `tool` metric tagged
// Pending (IsError:false), never a tool error; AfterTool runs exactly once — on the
// resolved result, not on the suspension. Mirrors the JS "a suspension is pending,
// not a tool error" test.
func TestSuspensionIsNotToolError(t *testing.T) {
	srv := answerableQuestionLLM(t)
	defer srv.Close()

	tk, err := CreateToolkit(context.Background(), Options{}) // builtins on → `question` exists
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()

	var mu sync.Mutex
	var events []MetricEvent
	var afterToolSaw []ToolResult
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"answers": []any{"a"}}}, nil
		},
		Hooks: &Hooks{
			AfterTool: func(_ context.Context, ev AfterToolEvent) (*ToolOverride, error) {
				mu.Lock()
				afterToolSaw = append(afterToolSaw, ev.Result)
				mu.Unlock()
				return nil, nil
			},
		},
		OnMetric: func(ev MetricEvent) {
			mu.Lock()
			events = append(events, ev)
			mu.Unlock()
		},
	})

	res, err := c.Run(context.Background(), "ask", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done (waitFor resolved the suspension)", res.Status)
	}
	var suspend *MetricEvent
	for i := range events {
		if events[i].Event == "tool" && events[i].Tool == "question" && events[i].Pending {
			suspend = &events[i]
			break
		}
	}
	if suspend == nil {
		t.Fatal("no tool metric tagged Pending for the suspension")
	}
	if suspend.IsError {
		t.Fatal("a suspension is not a tool error (IsError must be false)")
	}
	if len(afterToolSaw) != 1 {
		t.Fatalf("AfterTool ran %d times, want exactly 1 (only on the resolved result)", len(afterToolSaw))
	}
	if PendingOf(afterToolSaw[0]) != nil {
		t.Fatal("AfterTool saw a suspension; it must see only the resolved result")
	}
}

// addToolkit builds a toolkit with a single `add` tool that returns a+b as a string.
func addToolkit(t *testing.T) *Toolkit {
	t.Helper()
	add := Tool{
		Name:        "add",
		Description: "add",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{"a": map[string]any{"type": "number"}, "b": map[string]any{"type": "number"}}, "required": []string{"a", "b"}},
		Source:      SourceCustom,
		Execute: func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			a, _ := args["a"].(float64)
			b, _ := args["b"].(float64)
			return ToolResult{Output: strconv.Itoa(int(a + b))}, nil
		},
	}
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{add}})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

// addLLM stands in for an OpenAI endpoint: turn 1 calls `add`; turn 2 (once it sees a
// tool result) returns a final answer.
func addLLM(t *testing.T) *httptest.Server {
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
			message = map[string]any{"role": "assistant", "content": "3"}
		} else {
			message = map[string]any{
				"role":    "assistant",
				"content": nil,
				"tool_calls": []any{map[string]any{
					"id":       "c1",
					"type":     "function",
					"function": map[string]any{"name": "add", "arguments": `{"a":1,"b":2}`},
				}},
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": message}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
}

// TestBeforeToolPendingIsPathB (path B): a BeforeTool guard raises a suspension with
// no tool code running; with a WaitFor that approves, the real tool then runs, and the
// guard-raised suspension is counted Pending (not a tool error). Mirrors the JS
// "a beforeTool guard-raised pending is honored" test.
func TestBeforeToolPendingIsPathB(t *testing.T) {
	srv := addLLM(t)
	defer srv.Close()

	var mu sync.Mutex
	var events []MetricEvent
	asked := false
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		Hooks: &Hooks{
			// guard raises a suspension with NO tool code running (path B)
			BeforeTool: func(_ context.Context, ev BeforeToolEvent) (*ToolOverride, error) {
				if ev.Name == "add" && !asked {
					r := Pending(Request{Kind: "approval", Prompt: "approve add?"})
					return &ToolOverride{Result: &r}, nil
				}
				return nil, nil
			},
		},
		WaitFor: func(req Request) (Answer, error) {
			asked = true
			return Answer{ID: req.ID, Ok: true}, nil
		},
		OnMetric: func(ev MetricEvent) {
			mu.Lock()
			events = append(events, ev)
			mu.Unlock()
		},
	})

	res, err := c.Run(context.Background(), "add them", addToolkit(t))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done (on approval the real tool ran)", res.Status)
	}
	var suspend *MetricEvent
	for i := range events {
		if events[i].Event == "tool" && events[i].Tool == "add" && events[i].Pending {
			suspend = &events[i]
			break
		}
	}
	if suspend == nil {
		t.Fatal("no tool metric tagged Pending for the guard-raised suspension")
	}
	if suspend.IsError {
		t.Fatal("a guard-raised suspension is not a tool error (IsError must be false)")
	}
	ran := false
	for _, tcl := range res.ToolCalls {
		if tcl.Name == "add" && tcl.Output == "3" {
			ran = true
		}
	}
	if !ran {
		t.Fatalf("the real tool did not run after approval; toolCalls = %+v", res.ToolCalls)
	}
}

// twoQuestionsLLM stands in for an OpenAI endpoint that emits TWO `question` tool_calls
// in one turn — used to drive concurrent suspensions (§10, G3).
func twoQuestionsLLM(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		message := map[string]any{
			"role":    "assistant",
			"content": nil,
			"tool_calls": []any{
				map[string]any{"id": "c1", "type": "function", "function": map[string]any{"name": "question", "arguments": `{"questions":[{"question":"First?"}]}`}},
				map[string]any{"id": "c2", "type": "function", "function": map[string]any{"name": "question", "arguments": `{"questions":[{"question":"Second?"}]}`}},
			},
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": message}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
}

// TestConcurrentSuspensionsSurfaceFirst (G3): two concurrent suspensions with no
// WaitFor surface the FIRST in call order, deterministically; the second's placeholder
// result never enters the transcript. Mirrors the JS "two concurrent suspensions
// surface the first, not the last" test.
func TestConcurrentSuspensionsSurfaceFirst(t *testing.T) {
	srv := twoQuestionsLLM(t)
	defer srv.Close()

	tk, err := CreateToolkit(context.Background(), Options{}) // builtins on, no WaitFor
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	res, err := c.Run(context.Background(), "ask two", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "pending" {
		t.Fatalf("status = %q, want pending", res.Status)
	}
	if res.Pending == nil {
		t.Fatal("pending request is nil")
	}
	if want := "First?"; res.Pending.Prompt != want {
		t.Fatalf("pending.prompt = %q, want %q (the FIRST suspension in call order)", res.Pending.Prompt, want)
	}
	for _, m := range res.Messages {
		if mm, ok := m.(map[string]any); ok && mm["tool_call_id"] == "c2" {
			t.Fatal("the second concurrent suspension polluted the transcript (c2 present)")
		}
	}
}

// twoQuestionsStreamLLM is the streaming (SSE) counterpart of twoQuestionsLLM: it
// streams a single assistant turn with two `question` tool_calls (c1 "First?", c2
// "Second?") over text/event-stream, then a terminal usage delta and [DONE].
func twoQuestionsStreamLLM(t *testing.T) *httptest.Server {
	t.Helper()
	write := func(w http.ResponseWriter, v any) {
		b, _ := json.Marshal(v)
		_, _ = w.Write([]byte("data: " + string(b) + "\n\n"))
	}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("content-type", "text/event-stream")
		write(w, map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{
			map[string]any{"index": 0, "id": "c1", "type": "function", "function": map[string]any{"name": "question", "arguments": `{"questions":[{"question":"First?"}]}`}},
			map[string]any{"index": 1, "id": "c2", "type": "function", "function": map[string]any{"name": "question", "arguments": `{"questions":[{"question":"Second?"}]}`}},
		}}}}})
		write(w, map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}}}, "usage": map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
		_, _ = w.Write([]byte("data: [DONE]\n\n"))
	}))
}

// TestConcurrentSuspensionsSurfaceFirstStreaming (G3, streaming): two concurrent
// suspensions with no WaitFor surface the FIRST in call order over Stream(...), and the
// second's placeholder result never enters the transcript. The streaming counterpart of
// TestConcurrentSuspensionsSurfaceFirst; mirrors js streamOpenAI's halt-on-first.
func TestConcurrentSuspensionsSurfaceFirstStreaming(t *testing.T) {
	srv := twoQuestionsStreamLLM(t)
	defer srv.Close()

	tk, err := CreateToolkit(context.Background(), Options{}) // builtins on, no WaitFor
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	ch, err := c.Stream(context.Background(), "ask two", tk)
	if err != nil {
		t.Fatalf("stream: %v", err)
	}

	var res RunResult
	sawSecondResult := false
	for ev := range ch {
		switch ev.Type {
		case "done":
			if ev.Result != nil {
				res = *ev.Result
			}
		case "tool_result":
			if ev.ID == "c2" {
				sawSecondResult = true
			}
		case "error":
			t.Fatalf("stream error: %v", ev.Err)
		}
	}

	if res.Status != "pending" {
		t.Fatalf("status = %q, want pending", res.Status)
	}
	if res.Pending == nil {
		t.Fatal("pending request is nil")
	}
	if want := "First?"; res.Pending.Prompt != want {
		t.Fatalf("pending.prompt = %q, want %q (the FIRST suspension in call order)", res.Pending.Prompt, want)
	}
	if sawSecondResult {
		t.Fatal("the second concurrent suspension emitted a tool_result event (c2) — later suspensions must not surface")
	}
	for _, m := range res.Messages {
		if mm, ok := m.(map[string]any); ok && mm["tool_call_id"] == "c2" {
			t.Fatal("the second concurrent suspension polluted the transcript (c2 present)")
		}
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
