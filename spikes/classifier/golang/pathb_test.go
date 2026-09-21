package classifier_test

// PATH B — can a BeforeTool hook raise a §10 suspension, the way a TOOL can?
// If yes, a judge's `ask` band already has a home and needs NO contract change.
// golang/client.go:546-548 claims it ("or a guard-raised suspension — path B")
// but SPEC.md never states it and no test exercises it. Settle it.

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

// a scripted LLM: turn 1 asks for the tool, turn 2 answers in text.
type scripted struct{ turn int }

func (s *scripted) RoundTrip(r *http.Request) (*http.Response, error) {
	s.turn++
	var body string
	if s.turn == 1 {
		body = `{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
		  {"id":"c1","type":"function","function":{"name":"deploy","arguments":"{\"env\":\"prod\"}"}}]}}]}`
	} else {
		body = `{"choices":[{"message":{"role":"assistant","content":"done"}}]}`
	}
	return &http.Response{StatusCode: 200, Body: nopCloser{strings.NewReader(body)},
		Header: http.Header{"Content-Type": []string{"application/json"}}}, nil
}

type nopCloser struct{ *strings.Reader }

func (nopCloser) Close() error { return nil }

func newClient(t *testing.T, hooks *tn.Hooks, waitFor func(tn.Request) (tn.Answer, error)) *tn.Client {
	t.Helper()
	return tn.CreateClient(tn.ClientOptions{
		BaseURL: "http://x/v1", Style: "openai", Model: "m", APIKey: "k",
		HTTPClient: &http.Client{Transport: &scripted{}},
		Hooks:      hooks, WaitFor: waitFor, MaxTurns: 4,
	})
}

func toolkit(t *testing.T) *tn.Toolkit {
	t.Helper()
	tk, err := tn.CreateToolkit(context.Background(), tn.Options{
		Builtins: false,
		ExtraTools: []tn.Tool{{
			Name: "deploy", Description: "deploy", Source: tn.SourceCustom,
			InputSchema: tn.JSONSchema{"type": "object"},
			Execute: func(a map[string]any, c *tn.ToolContext) (tn.ToolResult, error) {
				return tn.ToolResult{Output: "DEPLOYED"}, nil
			}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}


// A: the hook raises a suspension; a WaitFor answers it inline.
func TestPathB_HookRaisedSuspension_ResolvedInline(t *testing.T) {
	asked := ""
	hooks := &tn.Hooks{BeforeTool: func(ctx context.Context, ev tn.BeforeToolEvent) (*tn.ToolOverride, error) {
		if ev.Name != "deploy" {
			return nil, nil
		}
		req := tn.Request{ID: "r1", Kind: "approval", Prompt: "approve deploy to prod?"}
		return &tn.ToolOverride{Result: &tn.ToolResult{Metadata: map[string]any{"pending": req}}}, nil
	}}
	r, err := newClient(t, hooks, func(req tn.Request) (tn.Answer, error) {
		asked = req.Prompt
		return tn.Answer{ID: req.ID, Ok: true}, nil
	}).Run(context.Background(), "ship it", toolkit(t))
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	t.Logf("status=%q asked=%q", r.Status, asked)
	if asked != "approve deploy to prod?" {
		t.Fatalf("WaitFor was never reached from a HOOK-raised pending; asked=%q", asked)
	}
	if r.Status != "done" {
		t.Fatalf("want done after inline answer, got %q", r.Status)
	}
}

// B: no WaitFor ⇒ the run must halt durably with the hook's Request.
func TestPathB_HookRaisedSuspension_DurableHalt(t *testing.T) {
	hooks := &tn.Hooks{BeforeTool: func(ctx context.Context, ev tn.BeforeToolEvent) (*tn.ToolOverride, error) {
		if ev.Name != "deploy" {
			return nil, nil
		}
		req := tn.Request{ID: "r2", Kind: "approval", Prompt: "approve?"}
		return &tn.ToolOverride{Result: &tn.ToolResult{Metadata: map[string]any{"pending": req}}}, nil
	}}
	r, err := newClient(t, hooks, nil).Run(context.Background(), "ship it", toolkit(t))
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	b, _ := json.Marshal(r.Pending)
	t.Logf("status=%q pending=%s", r.Status, b)
	if r.Status != "pending" || r.Pending == nil || r.Pending.ID != "r2" {
		t.Fatalf("want durable halt carrying the hook's Request, got status=%q pending=%s", r.Status, b)
	}
}
