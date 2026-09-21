package toolnexus

// SPEC §10 path B, durable half. TestBeforeToolPendingIsPathB covers a hook-raised
// suspension resolved INLINE by WaitFor, and TestPendingNoWaitForHalts covers a
// TOOL-raised one halting durably. The remaining corner is the one a policy gate
// actually ships on: hook-raised with NO WaitFor must halt carrying the HOOK's own
// Request, so an approval can be answered in another process.

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

// a scripted LLM: turn 1 asks for the tool, turn 2 answers in text.
type pbScripted struct{ turn int }

func (s *pbScripted) RoundTrip(r *http.Request) (*http.Response, error) {
	s.turn++
	var body string
	if s.turn == 1 {
		body = `{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
		  {"id":"c1","type":"function","function":{"name":"deploy","arguments":"{\"env\":\"prod\"}"}}]}}]}`
	} else {
		body = `{"choices":[{"message":{"role":"assistant","content":"done"}}]}`
	}
	return &http.Response{StatusCode: 200, Body: pbNopCloser{strings.NewReader(body)},
		Header: http.Header{"Content-Type": []string{"application/json"}}}, nil
}

type pbNopCloser struct{ *strings.Reader }

func (pbNopCloser) Close() error { return nil }

func pbClient(t *testing.T, hooks *Hooks, waitFor func(Request) (Answer, error)) *Client {
	t.Helper()
	return CreateClient(ClientOptions{
		BaseURL: "http://x/v1", Style: "openai", Model: "m", APIKey: "k",
		HTTPClient: &http.Client{Transport: &pbScripted{}},
		Hooks:      hooks, WaitFor: waitFor, MaxTurns: 4,
	})
}

func pbToolkit(t *testing.T) *Toolkit {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{
		Builtins: false,
		ExtraTools: []Tool{{
			Name: "deploy", Description: "deploy", Source: SourceCustom,
			InputSchema: JSONSchema{"type": "object"},
			Execute: func(a map[string]any, c *ToolContext) (ToolResult, error) {
				return ToolResult{Output: "DEPLOYED"}, nil
			}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

// B: no WaitFor ⇒ the run must halt durably with the hook's Request.
func TestBeforeToolPendingHaltsDurablyWithoutWaitFor(t *testing.T) {
	hooks := &Hooks{BeforeTool: func(ctx context.Context, ev BeforeToolEvent) (*ToolOverride, error) {
		if ev.Name != "deploy" {
			return nil, nil
		}
		req := Request{ID: "r2", Kind: "approval", Prompt: "approve?"}
		return &ToolOverride{Result: &ToolResult{Metadata: map[string]any{"pending": req}}}, nil
	}}
	r, err := pbClient(t, hooks, nil).Run(context.Background(), "ship it", pbToolkit(t))
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	b, _ := json.Marshal(r.Pending)
	t.Logf("status=%q pending=%s", r.Status, b)
	if r.Status != "pending" || r.Pending == nil || r.Pending.ID != "r2" {
		t.Fatalf("want durable halt carrying the hook's Request, got status=%q pending=%s", r.Status, b)
	}
}
