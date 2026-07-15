// Resilience + streaming tests for the unified client. Hermetic (httptest, no
// real LLM). Mirrors js/test resilience tests. Run with -race.
package toolnexus

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"net/http"
	"net/http/httptest"
)

func decodeBody(r *http.Request, v any) error {
	return json.NewDecoder(r.Body).Decode(v)
}

func asMap(v any) map[string]any {
	m, _ := v.(map[string]any)
	return m
}

// TestClientRetriesOn503 returns 503 twice then a valid OpenAI JSON answer and
// asserts the client retried and ultimately succeeded.
func TestClientRetriesOn503(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}

	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt32(&calls, 1)
		if n <= 2 {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"error":"try later"}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"hello"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}`))
	}))
	defer srv.Close()

	// RetryBaseMs tiny so the test is fast; default Retries (2) is enough for 2 failures.
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1})
	res, err := c.Run(context.Background(), "hi", tk)
	if err != nil {
		t.Fatalf("run after retries: %v", err)
	}
	if res.Text != "hello" {
		t.Fatalf("text = %q, want hello", res.Text)
	}
	if got := atomic.LoadInt32(&calls); got != 3 {
		t.Fatalf("server calls = %d, want 3 (2 failures + 1 success)", got)
	}
}

// TestClientRetryExhausted returns 503 more times than Retries allows and
// asserts the final error surfaces (no infinite retry).
func TestClientRetryExhausted(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte("down"))
	}))
	defer srv.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", Retries: 1, RetryBaseMs: 1})
	_, err = c.Run(context.Background(), "hi", tk)
	if err == nil {
		t.Fatal("expected error after retries exhausted")
	}
	if got := atomic.LoadInt32(&calls); got != 2 {
		t.Fatalf("server calls = %d, want 2 (1 try + 1 retry)", got)
	}
}

// TestClientTimeout uses a slow server and a small TimeoutMs and asserts Run
// returns a context/timeout error.
func TestClientTimeout(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done(): // client hung up (timeout)
		case <-time.After(2 * time.Second):
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"late"}}]}`))
	}))
	defer srv.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", TimeoutMs: 80})
	start := time.Now()
	_, err = c.Run(context.Background(), "hi", tk)
	if err == nil {
		t.Fatal("expected timeout error, got nil")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("Run took %v, expected to abort well under 1s", elapsed)
	}
}

// TestClientCancelNotRetried cancels ctx mid-flight and asserts the request is
// aborted (context error) and NOT retried.
func TestClientCancelNotRetried(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		// Block until the client hangs up (cancellation) or a safety timeout, so
		// the handler always returns and srv.Close() does not hang.
		select {
		case <-r.Context().Done():
		case <-time.After(2 * time.Second):
		}
	}))
	defer srv.Close()

	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(50 * time.Millisecond); cancel() }()
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1})
	_, err = c.Run(ctx, "hi", tk)
	if err == nil {
		t.Fatal("expected cancellation error")
	}
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("server calls = %d, want 1 (cancellation must not retry)", got)
	}
}

// TestStreamOpenAI drives the streaming OpenAI path against a mock SSE server
// that streams text deltas, a tool call, then a final answer. Verifies the
// channel emits text/tool_call/tool_result/usage/done in order and the final
// RunResult is complete. Run with -race for the streaming goroutine.
func TestStreamOpenAI(t *testing.T) {
	echo := NativeTool("echo", "echo n",
		JSONSchema{"type": "object", "properties": map[string]any{"n": map[string]any{"type": "number"}}},
		func(_ context.Context, args map[string]any) (string, error) {
			return strconv.FormatFloat(args["n"].(float64), 'f', -1, 64), nil
		})
	tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{echo}})
	if err != nil {
		t.Fatal(err)
	}

	var turn int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		fl, _ := w.(http.Flusher)
		write := func(s string) {
			_, _ = w.Write([]byte(s))
			if fl != nil {
				fl.Flush()
			}
		}
		if atomic.AddInt32(&turn, 1) == 1 {
			// Turn 1: a streamed tool call (assembled across two arg chunks).
			write(`data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c0","function":{"name":"echo","arguments":"{\"n\":"}}]}}]}` + "\n\n")
			write(`data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"42}"}}]}}]}` + "\n\n")
			write(`data: {"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}` + "\n\n")
			write("data: [DONE]\n\n")
			return
		}
		// Turn 2: streamed text deltas, then usage.
		write(`data: {"choices":[{"delta":{"content":"The "}}]}` + "\n\n")
		write(`data: {"choices":[{"delta":{"content":"answer is 42."}}]}` + "\n\n")
		write(`data: {"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}` + "\n\n")
		write("data: [DONE]\n\n")
	}))
	defer srv.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	ch, err := c.Stream(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("stream: %v", err)
	}

	var text strings.Builder
	var toolCalls, toolResults, textDeltas int
	var done *RunResult
	for ev := range ch {
		switch ev.Type {
		case "text":
			textDeltas++
			text.WriteString(ev.Delta)
		case "tool_call":
			toolCalls++
			if ev.Name != "echo" {
				t.Fatalf("tool_call name = %q, want echo", ev.Name)
			}
			if n, _ := ev.Args["n"].(float64); n != 42 {
				t.Fatalf("tool_call arg n = %v, want 42", ev.Args["n"])
			}
		case "tool_result":
			toolResults++
			if ev.Output != "42" {
				t.Fatalf("tool_result output = %q, want 42", ev.Output)
			}
		case "done":
			done = ev.Result
		case "error":
			t.Fatalf("unexpected error event: %v", ev.Err)
		}
	}

	if toolCalls != 1 || toolResults != 1 {
		t.Fatalf("toolCalls=%d toolResults=%d, want 1/1", toolCalls, toolResults)
	}
	if textDeltas < 2 {
		t.Fatalf("textDeltas = %d, want >= 2", textDeltas)
	}
	if got := text.String(); got != "The answer is 42." {
		t.Fatalf("assembled text = %q", got)
	}
	if done == nil {
		t.Fatal("no done event")
	}
	if done.Text != "The answer is 42." {
		t.Fatalf("done.Text = %q", done.Text)
	}
	if done.ToolCallCount != 1 || done.Turns != 2 {
		t.Fatalf("done.ToolCallCount=%d done.Turns=%d, want 1/2", done.ToolCallCount, done.Turns)
	}
	if done.Usage.TotalTokens != 14 {
		t.Fatalf("done.Usage.TotalTokens = %d, want 14", done.Usage.TotalTokens)
	}
}

// TestRunWithHistory checks that prior transcript is continued (system not
// re-added) and that RunResult.Messages carries the full transcript.
func TestRunWithHistory(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}

	var lastMessages []any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []any `json:"messages"`
		}
		_ = decodeBody(r, &body)
		lastMessages = body.Messages
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`))
	}))
	defer srv.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", SystemPrompt: "SYS"})

	// Turn 1 (no history): system + user.
	r1, err := c.RunWithHistory(context.Background(), "first", tk, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(lastMessages) != 2 || asMap(lastMessages[0])["role"] != "system" {
		t.Fatalf("turn1 messages = %v, want [system,user]", lastMessages)
	}

	// Turn 2 (continue r1.Messages): no second system message, history grows.
	_, err = c.RunWithHistory(context.Background(), "second", tk, r1.Messages)
	if err != nil {
		t.Fatal(err)
	}
	systemCount := 0
	for _, m := range lastMessages {
		if asMap(m)["role"] == "system" {
			systemCount++
		}
	}
	if systemCount != 1 {
		t.Fatalf("system messages on turn2 = %d, want exactly 1 (not re-added)", systemCount)
	}
	// system + user(first) + assistant(ok) + user(second)
	if len(lastMessages) != 4 {
		t.Fatalf("turn2 message count = %d, want 4", len(lastMessages))
	}
}

// TestConversationMemory checks Conversation retains and resets transcript.
func TestConversationMemory(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"ack"}}]}`))
	}))
	defer srv.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	conv := c.Conversation(tk)
	if _, err := conv.Send(context.Background(), "one"); err != nil {
		t.Fatal(err)
	}
	after1 := len(conv.Messages)
	if _, err := conv.Send(context.Background(), "two"); err != nil {
		t.Fatal(err)
	}
	if len(conv.Messages) <= after1 {
		t.Fatalf("transcript did not grow: %d then %d", after1, len(conv.Messages))
	}
	conv.Reset()
	if len(conv.Messages) != 0 {
		t.Fatalf("after Reset, messages = %d, want 0", len(conv.Messages))
	}
}

// TestOnErrorFailOn429 asserts a host OnError returning TierFail on a retryable
// 429 surfaces immediately with a single request (no retry).
func TestOnErrorFailOn429(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte(`{"error":"slow down"}`))
	}))
	defer srv.Close()

	var seen ErrorInfo
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1,
		OnError: func(info ErrorInfo) Tier { seen = info; return TierFail },
	})
	_, err = c.Run(context.Background(), "hi", tk)
	if err == nil {
		t.Fatal("expected error when OnError returns TierFail on 429")
	}
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("server calls = %d, want 1 (TierFail ⇒ no retry)", got)
	}
	if seen.Status != 429 || !seen.Retryable || seen.Attempt != 0 || seen.Err != nil {
		t.Fatalf("ErrorInfo = %+v, want {Status:429 Retryable:true Attempt:0 Err:nil}", seen)
	}
}

// TestOnErrorRetryOn400 asserts a host OnError returning TierRetry on a
// normally-non-retryable 400 retries to the budget (1 try + Retries).
func TestOnErrorRetryOn400(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"bad"}`))
	}))
	defer srv.Close()

	var sawRetryable bool = true
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", Retries: 3, RetryBaseMs: 1,
		OnError: func(info ErrorInfo) Tier {
			if info.Status == 400 && info.Retryable {
				sawRetryable = false // 400 must NOT be in the default retryable set
			}
			return TierRetry
		},
	})
	_, err = c.Run(context.Background(), "hi", tk)
	if err == nil {
		t.Fatal("expected error after retry budget exhausted on 400")
	}
	if got := atomic.LoadInt32(&calls); got != 4 {
		t.Fatalf("server calls = %d, want 4 (1 try + 3 retries)", got)
	}
	if !sawRetryable {
		t.Fatal("400 was reported Retryable:true, want false (not in default set)")
	}
}

// TestDefaultClassifierRetries429 asserts the default (nil OnError) behavior is
// unchanged: a retryable 429 followed by 200 succeeds.
func TestDefaultClassifierRetries429(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&calls, 1) == 1 {
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"error":"retry"}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"hello"}}]}`))
	}))
	defer srv.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1})
	res, err := c.Run(context.Background(), "hi", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Text != "hello" {
		t.Fatalf("text = %q, want hello", res.Text)
	}
	if got := atomic.LoadInt32(&calls); got != 2 {
		t.Fatalf("server calls = %d, want 2 (1 retry)", got)
	}
}

// TestDefaultClassifierFailsOn400 asserts the default (nil OnError) behavior is
// unchanged: a non-retryable 400 fails immediately with a single request.
func TestDefaultClassifierFailsOn400(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"bad"}`))
	}))
	defer srv.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", Retries: 3, RetryBaseMs: 1})
	_, err = c.Run(context.Background(), "hi", tk)
	if err == nil {
		t.Fatal("expected error on 400")
	}
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("server calls = %d, want 1 (400 not retryable)", got)
	}
}
