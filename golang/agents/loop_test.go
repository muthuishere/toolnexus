package agents_test

// Hermetic tests for the harness/loop/completion prototype. Zero network: a
// scripted RoundTripper stands in for the LLM, so these pin BEHAVIOUR and can
// be ported to the other six as-is.

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
)

type scripted struct{ replies []map[string]any; n int }

func (s *scripted) RoundTrip(_ *http.Request) (*http.Response, error) {
	msg := s.replies[min(s.n, len(s.replies)-1)]
	s.n++
	body, _ := json.Marshal(map[string]any{
		"choices": []any{map[string]any{"message": msg}},
		"usage":   map[string]any{"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10},
	})
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(string(body))),
		Header: http.Header{"Content-Type": []string{"application/json"}}}, nil
}
func min(a, b int) int { if a < b { return a }; return b }

func text(s string) map[string]any { return map[string]any{"role": "assistant", "content": s} }
func callTodo(todos []any) map[string]any {
	args, _ := json.Marshal(map[string]any{"todos": todos})
	return map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
		map[string]any{"id": "t1", "type": "function",
			"function": map[string]any{"name": "todowrite", "arguments": string(args)}}}}
}
func todo(id, txt string, done bool) any {
	return map[string]any{"id": id, "text": txt, "completed": done}
}

func clientWith(rt http.RoundTripper) *tn.Client {
	return tn.CreateClient(tn.ClientOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI,
		Model: "m", APIKey: "x", HTTPClient: &http.Client{Transport: rt}})
}
func todoToolkit(t *testing.T) *tn.Toolkit {
	t.Helper()
	tk, err := tn.CreateToolkit(nil, tn.Options{Builtins: tn.BuiltinsConfig{Tools: map[string]bool{
		"todowrite": true, "bash": false, "read": false, "write": false, "edit": false,
		"glob": false, "grep": false, "webfetch": false, "apply_patch": false, "question": false}}})
	if err != nil { t.Fatal(err) }
	return tk
}

// Absent option ⇒ byte-identical: no Completion means one attempt, status done.
func TestLoopWithoutCompletionIsUnchanged(t *testing.T) {
	a := agents.New("plain", agents.Spec{Does: "answers", Soul: "be brief"})
	tk, _ := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	l := a.Loop(clientWith(&scripted{replies: []map[string]any{text("hi")}}), tk)
	out, err := l.Run(context.Background(), "hello", agents.RunOpts{})
	if err != nil { t.Fatal(err) }
	if out.Status != "done" || out.Attempts != 1 || out.StoppedBy != "" {
		t.Fatalf("got status=%s attempts=%d stoppedBy=%q", out.Status, out.Attempts, out.StoppedBy)
	}
}

// The gate blocks a run with an open todo, feeds the reason back, and passes
// once the agent completes it.
func TestCompletionGateBlocksThenPasses(t *testing.T) {
	rt := &scripted{replies: []map[string]any{
		callTodo([]any{todo("1", "draft", true), todo("2", "proofread", false)}), // attempt 1: open
		text("claiming done"),
		callTodo([]any{todo("1", "draft", true), todo("2", "proofread", true)}), // attempt 2: complete
		text("really done"),
	}}
	a := agents.New("worker", agents.Spec{Does: "works",
		Completion: &agents.Completion{Verify: agents.AllTodosDone, MaxAttempts: 3}})
	out, err := a.Loop(clientWith(rt), todoToolkit(t)).Run(context.Background(), "do it", agents.RunOpts{})
	if err != nil { t.Fatal(err) }
	if out.Status != "done" { t.Fatalf("status=%s stoppedBy=%q", out.Status, out.StoppedBy) }
	if out.Attempts != 2 { t.Fatalf("expected the gate to force a 2nd attempt, got %d", out.Attempts) }
}

// A gate that can never pass stops BOUNDED and NAMED — never a silent done.
func TestCompletionGateStopsLoudly(t *testing.T) {
	rt := &scripted{replies: []map[string]any{
		callTodo([]any{todo("1", "never", false)}), text("done?"),
	}}
	a := agents.New("worker", agents.Spec{Does: "works",
		Completion: &agents.Completion{Verify: agents.AllTodosDone, MaxAttempts: 2}})
	out, err := a.Loop(clientWith(rt), todoToolkit(t)).Run(context.Background(), "do it", agents.RunOpts{})
	if err != nil { t.Fatal(err) }
	if out.Status != "incomplete" { t.Fatalf("status=%s", out.Status) }
	if out.Attempts != 2 { t.Fatalf("attempts=%d, want 2", out.Attempts) }
	if !strings.Contains(out.StoppedBy, "failed 2×") || !strings.Contains(out.StoppedBy, "never") {
		t.Fatalf("stop reason not named: %q", out.StoppedBy)
	}
}

// MaxAttempts is required — an unbounded gate is a runaway loop.
func TestCompletionRequiresBound(t *testing.T) {
	a := agents.New("w", agents.Spec{Does: "w",
		Completion: &agents.Completion{Verify: agents.AllTodosDone}})
	tk, _ := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	if _, err := a.Loop(clientWith(&scripted{replies: []map[string]any{text("x")}}), tk).
		Run(context.Background(), "go", agents.RunOpts{}); err == nil {
		t.Fatal("expected an error for MaxAttempts < 1")
	}
}

// No plan declared ⇒ nothing to verify ⇒ pass. The gate must not punish an
// agent that never used todowrite.
func TestNoPlanPasses(t *testing.T) {
	a := agents.New("w", agents.Spec{Does: "w",
		Completion: &agents.Completion{Verify: agents.AllTodosDone, MaxAttempts: 2}})
	tk, _ := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	out, err := a.Loop(clientWith(&scripted{replies: []map[string]any{text("no plan here")}}), tk).
		Run(context.Background(), "go", agents.RunOpts{})
	if err != nil { t.Fatal(err) }
	if out.Status != "done" || out.Attempts != 1 {
		t.Fatalf("status=%s attempts=%d", out.Status, out.Attempts)
	}
}

// Guardrails: first-deny-wins, and a later rail cannot widen an earlier denial.
func TestGuardrailFirstDenyWins(t *testing.T) {
	executed := false
	deploy := tn.Tool{Name: "deploy", Description: "deploys", Source: tn.SourceCustom,
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		Execute: func(map[string]any, *tn.ToolContext) (tn.ToolResult, error) {
			executed = true
			return tn.ToolResult{Output: "DEPLOYED"}, nil
		}}
	sp := agents.Spec{Does: "ops", Tools: []tn.Tool{deploy},
		Guardrails: []agents.Guardrail{
			func(tn.BeforeToolEvent) string { return "blocked by policy" },
			func(tn.BeforeToolEvent) string { return "allow" }, // must NOT widen the denial
		}}
	a := agents.New("ops", sp)
	def := a.Registry()["ops"] // guardrails must survive the registry projection (delegation)
	if def.Hooks == nil || def.Hooks.BeforeTool == nil {
		t.Fatal("guardrails did not reach the registry Def — delegation would bypass them")
	}
	ov, err := def.Hooks.BeforeTool(context.Background(), tn.BeforeToolEvent{Name: "deploy"})
	if err != nil { t.Fatal(err) }
	if ov == nil || ov.Result == nil || !ov.Result.IsError {
		t.Fatal("expected a denial short-circuit")
	}
	if !strings.Contains(ov.Result.Output, "blocked by policy") {
		t.Fatalf("wrong denial surfaced: %q", ov.Result.Output)
	}
	if executed { t.Fatal("the tool executed despite the denial") }
}
