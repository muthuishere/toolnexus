// Right-size routing conformance (spec: right-size-routing). Hermetic — a mock
// OpenAI-style endpoint, no live key, no network. Proves the two properties the
// fleet's right-size routing depends on:
//
//  1. Model faithfulness — the caller-chosen model id is the one billed, sent
//     verbatim on the wire, so a route the operator picked as "cheap"
//     (e.g. deepseek/deepseek-chat) stays cheap. This is the CEO use case
//     ("route one classify/verify job to a cheap model, get the right result")
//     pinned in CI without a live key.
//  2. Route gate — a beforeLLM hook can gate an expensive-tier route (the
//     brain-check seam): abort unless a reason is supplied, while a cheap-tier
//     route runs unimpeded.
package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// mockClassifier stands up an OpenAI-style /chat/completions that records the
// model id it was asked for and replies with a fixed classification label.
func mockClassifier(t *testing.T, gotModel *string, hit *int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*hit++
		body, _ := io.ReadAll(r.Body)
		var req struct {
			Model string `json:"model"`
		}
		_ = json.Unmarshal(body, &req)
		*gotModel = req.Model
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"NEGATIVE"}}]}`))
	}))
}

// TestRoutingModelFaithfulness — the classify job routed to the cheap tier hits
// exactly that model, and the model's answer comes back. (spec scenario:
// "A classify job routed to a cheap tier hits exactly that model")
func TestRoutingModelFaithfulness(t *testing.T) {
	var gotModel string
	var hits int
	srv := mockClassifier(t, &gotModel, &hits)
	defer srv.Close()

	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatalf("toolkit: %v", err)
	}
	defer tk.Close()

	const cheap = "deepseek/deepseek-chat"
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: cheap, APIKey: "k"})
	res, err := c.Run(context.Background(), "Classify as one word POSITIVE or NEGATIVE: 'total waste of money'.", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if gotModel != cheap {
		t.Fatalf("model faithfulness broken: endpoint was asked for %q, want %q", gotModel, cheap)
	}
	if !strings.Contains(res.Text, "NEGATIVE") {
		t.Fatalf("want the model's classification NEGATIVE, got %q", res.Text)
	}
	if res.Model != cheap {
		t.Fatalf("RunResult.Model = %q, want %q", res.Model, cheap)
	}
}

// TestRouteGateBlocksExpensiveTier — a beforeLLM gate aborts an expensive-tier
// route that carries no justification (the endpoint is never called), and the
// same gate permits a cheap-tier route (endpoint hit, answer returned). This is
// the brain-check seam: the gate stands in for `brain check "<why this model>"`.
// (spec scenarios: "Gate blocks an expensive-tier route lacking a reason" /
// "Gate permits a cheap-tier route")
func TestRouteGateBlocksExpensiveTier(t *testing.T) {
	const (
		cheap     = "deepseek/deepseek-chat"
		expensive = "anthropic/claude-opus-4"
	)

	// gate: expensive-tier models require a non-empty reason, else abort — the
	// deterministic stand-in for a `brain check ... --json` that isn't guaranteed.
	gate := func(reason string) *Hooks {
		return &Hooks{
			BeforeLLM: func(ctx context.Context, ev BeforeLLMEvent) (*LLMOverride, error) {
				if ev.Model == expensive && reason == "" {
					return nil, errors.New("route gate: expensive-tier model requires a brain-check reason")
				}
				return nil, nil
			},
		}
	}

	// expensive route, no reason -> blocked before the endpoint is touched.
	var m1 string
	var hits1 int
	srv1 := mockClassifier(t, &m1, &hits1)
	defer srv1.Close()
	tk1, _ := CreateToolkit(context.Background(), Options{})
	defer tk1.Close()
	c1 := CreateClient(ClientOptions{BaseURL: srv1.URL, Style: StyleOpenAI, Model: expensive, APIKey: "k", Hooks: gate("")})
	if _, err := c1.Run(context.Background(), "expensive job", tk1); err == nil {
		t.Fatalf("expected gate to block ungoverned expensive route, got nil error")
	}
	if hits1 != 0 {
		t.Fatalf("gate leaked: endpoint was hit %d times for a blocked expensive route", hits1)
	}

	// cheap route -> permitted, answer returned.
	var m2 string
	var hits2 int
	srv2 := mockClassifier(t, &m2, &hits2)
	defer srv2.Close()
	tk2, _ := CreateToolkit(context.Background(), Options{})
	defer tk2.Close()
	c2 := CreateClient(ClientOptions{BaseURL: srv2.URL, Style: StyleOpenAI, Model: cheap, APIKey: "k", Hooks: gate("")})
	res, err := c2.Run(context.Background(), "cheap classify job", tk2)
	if err != nil {
		t.Fatalf("cheap route should pass the gate, got %v", err)
	}
	if hits2 != 1 || m2 != cheap {
		t.Fatalf("cheap route not executed faithfully: hits=%d model=%q", hits2, m2)
	}
	if !strings.Contains(res.Text, "NEGATIVE") {
		t.Fatalf("cheap route lost its answer: %q", res.Text)
	}
}
