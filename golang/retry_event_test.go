// Retry stream event + x-should-retry override (SPEC § Resilience, issue #189).
// Hermetic: httptest only.

package toolnexus

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

const oaiStreamOK = `data: {"choices":[{"delta":{"content":"hello"}}]}` + "\n\n" + "data: [DONE]\n\n"
const anthStreamOK = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n" +
	"event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
	"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n" +
	"event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
	"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n" +
	"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

func collect(t *testing.T, c *Client) []StreamEvent {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	ch, err := c.Stream(context.Background(), "hi", tk)
	if err != nil {
		t.Fatal(err)
	}
	var evs []StreamEvent
	for e := range ch {
		evs = append(evs, e)
	}
	return evs
}

// failFirst serves fail(w) on the first request and ok afterwards.
func failFirst(fail func(w http.ResponseWriter), ok string, calls *int32) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(calls, 1) == 1 {
			fail(w)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte(ok))
	}))
}

func TestStreamRetryEvent429(t *testing.T) {
	for _, st := range []struct {
		style ClientStyle
		ok    string
	}{{StyleOpenAI, oaiStreamOK}, {StyleAnthropic, anthStreamOK}} {
		var calls int32
		srv := failFirst(func(w http.ResponseWriter) {
			w.Header().Set("Retry-After", "0")
			w.WriteHeader(429)
		}, st.ok, &calls)
		evs := collect(t, CreateClient(ClientOptions{BaseURL: srv.URL, Style: st.style, Model: "m", APIKey: "k", RetryBaseMs: 1}))
		srv.Close()
		if len(evs) < 3 || evs[0].Type != "retry" {
			t.Fatalf("%s: events = %+v, want retry first", st.style, evs)
		}
		r := evs[0]
		if r.Attempt != 1 || r.Max != 2 || r.DelayMs != 0 || r.Status != 429 || r.Reason != "HTTP 429" {
			t.Fatalf("%s: retry event = %+v", st.style, r)
		}
		var sawText bool
		for _, e := range evs[1:] {
			if e.Type == "retry" {
				t.Fatalf("%s: extra retry event", st.style)
			}
			if e.Type == "text" && e.Delta == "hello" {
				sawText = true
			}
		}
		if !sawText || evs[len(evs)-1].Type != "done" {
			t.Fatalf("%s: events = %+v, want text then done", st.style, evs)
		}
	}
}

func TestStreamRetryEventNetwork(t *testing.T) {
	var calls int32
	srv := failFirst(func(w http.ResponseWriter) {
		conn, _, err := w.(http.Hijacker).Hijack()
		if err == nil {
			conn.Close()
		}
	}, oaiStreamOK, &calls)
	defer srv.Close()
	evs := collect(t, CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1, Retries: 1}))
	r := evs[0]
	if r.Type != "retry" || r.Reason != "network" || r.Status != 0 || r.Attempt != 1 || r.Max != 1 {
		t.Fatalf("retry event = %+v", r)
	}
	if r.DelayMs < 1 || r.DelayMs > 100 { // base*2^0 + jitter[0,100)
		t.Fatalf("delayMs = %d", r.DelayMs)
	}
	if evs[len(evs)-1].Type != "done" {
		t.Fatalf("events = %+v", evs)
	}
}

func TestStream400NoRetryEvent(t *testing.T) {
	var calls int32
	srv := failFirst(func(w http.ResponseWriter) { w.WriteHeader(400) }, oaiStreamOK, &calls)
	defer srv.Close()
	evs := collect(t, CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1}))
	if len(evs) != 1 || evs[0].Type != "error" || calls != 1 {
		t.Fatalf("events = %+v calls=%d, want single error", evs, calls)
	}
}

func TestShouldRetryTrueRetries400(t *testing.T) {
	var calls int32
	srv := failFirst(func(w http.ResponseWriter) {
		w.Header().Set("x-should-retry", " true ")
		w.WriteHeader(400)
	}, `{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`, &calls)
	defer srv.Close()
	tk, _ := CreateToolkit(context.Background(), Options{})
	res, err := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1}).Run(context.Background(), "hi", tk)
	if err != nil || res.Text != "ok" || calls != 2 {
		t.Fatalf("res=%q err=%v calls=%d", res.Text, err, calls)
	}
}

func TestShouldRetryFalseMakes503Terminal(t *testing.T) {
	var calls int32
	srv := failFirst(func(w http.ResponseWriter) {
		w.Header().Set("X-Should-Retry", "False")
		w.WriteHeader(503)
	}, `{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`, &calls)
	defer srv.Close()
	tk, _ := CreateToolkit(context.Background(), Options{})
	_, err := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1}).Run(context.Background(), "hi", tk)
	if err == nil || calls != 1 {
		t.Fatalf("err=%v calls=%d, want 503 error after 1 call", err, calls)
	}
}

func TestShouldRetryOnErrorStillDecides(t *testing.T) {
	var calls int32
	srv := failFirst(func(w http.ResponseWriter) {
		w.Header().Set("x-should-retry", "true")
		w.WriteHeader(400)
	}, `{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`, &calls)
	defer srv.Close()
	var seen ErrorInfo
	tk, _ := CreateToolkit(context.Background(), Options{})
	_, err := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1,
		OnError: func(i ErrorInfo) Tier { seen = i; return TierFail }}).Run(context.Background(), "hi", tk)
	if err == nil || calls != 1 || !seen.Retryable {
		t.Fatalf("err=%v calls=%d seen=%+v", err, calls, seen)
	}
}

func TestShouldRetryHeaderParse(t *testing.T) {
	for in, want := range map[string][2]bool{"true": {true, true}, " FALSE ": {false, true}, "yes": {false, false}, "": {false, false}} {
		v, ok := shouldRetryHeader(in)
		if v != want[0] || ok != want[1] {
			t.Fatalf("%q => %v,%v", in, v, ok)
		}
	}
}

func TestRunRetry429Unchanged(t *testing.T) {
	var calls int32
	srv := failFirst(func(w http.ResponseWriter) {
		w.Header().Set("Retry-After", "0")
		w.WriteHeader(429)
	}, `{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`, &calls)
	defer srv.Close()
	tk, _ := CreateToolkit(context.Background(), Options{})
	res, err := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", RetryBaseMs: 1}).Run(context.Background(), "hi", tk)
	if err != nil || res.Text != "ok" || calls != 2 {
		t.Fatalf("res=%q err=%v calls=%d", res.Text, err, calls)
	}
}
