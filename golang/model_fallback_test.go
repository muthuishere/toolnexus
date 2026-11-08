// Model fallback chain (§8 Resilience FallbackModels, issue #190). Hermetic.

package toolnexus

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

// fbServer answers per model: a status for models in fail, else a valid reply
// (SSE when the request streams). It records each request's model.
func fbServer(t *testing.T, fail map[string]int) (*httptest.Server, func() []string) {
	t.Helper()
	var mu sync.Mutex
	var models []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = decodeBody(r, &body)
		m, _ := body["model"].(string)
		mu.Lock()
		models = append(models, m)
		mu.Unlock()
		if st, ok := fail[m]; ok {
			w.WriteHeader(st)
			_, _ = w.Write([]byte(`{"error":"overloaded"}`))
			return
		}
		if s, _ := body["stream"].(bool); s {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = w.Write([]byte(`data: {"choices":[{"delta":{"content":"hi"}}]}` + "\n\n" + "data: [DONE]\n\n"))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"hi"}}]}`))
	}))
	t.Cleanup(srv.Close)
	return srv, func() []string { mu.Lock(); defer mu.Unlock(); return append([]string(nil), models...) }
}

func fbClient(url string, extra func(*ClientOptions)) *Client {
	o := ClientOptions{BaseURL: url, Style: StyleOpenAI, Model: "m1", APIKey: "k", Retries: -1, RetryBaseMs: 1,
		FallbackModels: []string{"", "m1", "m2"}}
	if extra != nil {
		extra(&o)
	}
	return CreateClient(o)
}

func eqModels(t *testing.T, got []string, want ...string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("models = %v, want %v", got, want)
	}
	for i := range got {
		if got[i] != want[i] {
			t.Fatalf("models = %v, want %v", got, want)
		}
	}
}

func TestFallbackOn529Switches(t *testing.T) {
	tk, _ := CreateToolkit(context.Background(), Options{})
	srv, models := fbServer(t, map[string]int{"m1": 529})
	var afterModel string
	c := fbClient(srv.URL, func(o *ClientOptions) {
		o.Hooks = &Hooks{AfterLLM: func(_ context.Context, e AfterLLMEvent) error { afterModel = e.Model; return nil }}
	})
	res, err := c.Run(context.Background(), "x", tk)
	if err != nil {
		t.Fatal(err)
	}
	if res.Model != "m2" || afterModel != "m2" {
		t.Fatalf("RunResult.model=%q afterLLM=%q, want m2", res.Model, afterModel)
	}
	eqModels(t, models(), "m1", "m2")
}

func TestFallbackFreshBudgetAndExhaustedChain(t *testing.T) {
	tk, _ := CreateToolkit(context.Background(), Options{})
	srv, models := fbServer(t, map[string]int{"m1": 503, "m2": 503})
	c := fbClient(srv.URL, func(o *ClientOptions) { o.Retries = 1 })
	_, err := c.Run(context.Background(), "x", tk)
	pe, ok := err.(*ProviderError)
	if !ok || pe.Status != 503 {
		t.Fatalf("err = %v, want 503 ProviderError", err)
	}
	eqModels(t, models(), "m1", "m1", "m2", "m2")
}

func TestFallbackNeverOn429(t *testing.T) {
	tk, _ := CreateToolkit(context.Background(), Options{})
	srv, models := fbServer(t, map[string]int{"m1": 429})
	_, err := fbClient(srv.URL, nil).Run(context.Background(), "x", tk)
	if err == nil {
		t.Fatal("want error")
	}
	eqModels(t, models(), "m1")
}

func TestFallbackNotWhenOnErrorFails(t *testing.T) {
	tk, _ := CreateToolkit(context.Background(), Options{})
	srv, models := fbServer(t, map[string]int{"m1": 503})
	c := fbClient(srv.URL, func(o *ClientOptions) {
		o.Retries = 2
		o.OnError = func(ErrorInfo) Tier { return TierFail }
	})
	if _, err := c.Run(context.Background(), "x", tk); err == nil {
		t.Fatal("want error")
	}
	eqModels(t, models(), "m1")
}

func TestFallbackKillSwitch(t *testing.T) {
	for _, v := range []string{"1", "TRUE"} {
		t.Setenv("TOOLNEXUS_DISABLE_MODEL_FALLBACK", v)
		tk, _ := CreateToolkit(context.Background(), Options{})
		srv, models := fbServer(t, map[string]int{"m1": 529})
		if _, err := fbClient(srv.URL, nil).Run(context.Background(), "x", tk); err == nil {
			t.Fatal("want error")
		}
		eqModels(t, models(), "m1")
	}
}

func TestFallbackStreamEmitsEventBeforeText(t *testing.T) {
	tk, _ := CreateToolkit(context.Background(), Options{})
	srv, models := fbServer(t, map[string]int{"m1": 529})
	ch, err := fbClient(srv.URL, nil).Stream(context.Background(), "x", tk)
	if err != nil {
		t.Fatal(err)
	}
	var types []string
	var done *RunResult
	for ev := range ch {
		types = append(types, ev.Type)
		switch ev.Type {
		case "fallback":
			if ev.From != "m1" || ev.To != "m2" {
				t.Fatalf("fallback event = %q -> %q", ev.From, ev.To)
			}
		case "done":
			done = ev.Result
		case "error":
			t.Fatal(ev.Err)
		}
	}
	if len(types) < 2 || types[0] != "fallback" || types[1] != "text" {
		t.Fatalf("event order = %v, want fallback then text", types)
	}
	if done == nil || done.Model != "m2" {
		t.Fatalf("done = %+v, want model m2", done)
	}
	eqModels(t, models(), "m1", "m2")
}

func TestFallbackAbsentUnchanged(t *testing.T) {
	tk, _ := CreateToolkit(context.Background(), Options{})
	srv, models := fbServer(t, map[string]int{"m1": 529})
	c := fbClient(srv.URL, func(o *ClientOptions) { o.FallbackModels = nil })
	if _, err := c.Run(context.Background(), "x", tk); err == nil {
		t.Fatal("want error")
	}
	eqModels(t, models(), "m1")
}
