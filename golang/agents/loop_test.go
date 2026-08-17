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

// modelRec is `scripted` plus a record of the `model` each request carried, so a
// test can assert what actually reached the wire rather than what was configured.
// `reply` is called with the 1-based request number.
type modelRec struct {
	reply  func(n int) map[string]any
	models []string
	n      int
}

func (m *modelRec) RoundTrip(req *http.Request) (*http.Response, error) {
	m.n++
	var sent map[string]any
	if req.Body != nil {
		raw, _ := io.ReadAll(req.Body)
		_ = json.Unmarshal(raw, &sent)
	}
	model, _ := sent["model"].(string)
	m.models = append(m.models, model)

	body, _ := json.Marshal(map[string]any{
		"choices": []any{map[string]any{"message": m.reply(m.n)}},
		"usage":   map[string]any{"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10},
	})
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(string(body))),
		Header: http.Header{"Content-Type": []string{"application/json"}}}, nil
}


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

// todowriteTool returns the SHIPPED todowrite builtin as a Tool, so an agent Def
// can scope it in. Builtins are never added to a Def implicitly (§7D: the
// toolkit view IS the security model).
func todowriteTool(t *testing.T) tn.Tool {
	t.Helper()
	for _, tool := range todoToolkit(t).Tools() {
		if tool.Name == "todowrite" {
			return tool
		}
	}
	t.Fatal("todowrite builtin not found")
	return tn.Tool{}
}

func clientWith(rt http.RoundTripper) tn.ClientOptions {
	return tn.ClientOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI,
		Model: "m", APIKey: "x", HTTPClient: &http.Client{Transport: rt}}
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

// THE point of putting Completion on the agent: a DELEGATED child inherits its
// own gate. A host-side retry loop lives at the call site and cannot reach here.
func TestCompletionGateReachesDelegatedChild(t *testing.T) {
	rt := &scripted{replies: []map[string]any{
		callTodo([]any{todo("1", "work", false)}), text("claiming done"),
		callTodo([]any{todo("1", "work", true)}), text("really done"),
	}}
	child := agents.New("child", agents.Spec{Does: "does the work",
		Tools:      []tn.Tool{todowriteTool(t)},
		Completion: &agents.Completion{Verify: agents.AllTodosDone, MaxAttempts: 3}})
	r := agents.NewRuntime(agents.Options{
		Registry: child.Registry(), Transport: rt,
		LLM: &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, APIKey: "x", Model: "m"},
	})
	h, err := r.Spawn(r.Root, "child", nil)
	if err != nil { t.Fatal(err) }
	r.Wake(h, "do the work")
	res := r.Wait(h, 0)
	defer r.Close(h, nil)
	if res.Status != "done" {
		t.Fatalf("status=%s text=%q", res.Status, res.Text)
	}
	if rt.n < 4 {
		t.Fatalf("the gate did not re-run inside the runtime turn (consumed %d replies)", rt.n)
	}
}

// An unpassable gate inside the runtime stops the handle LOUDLY as incomplete.
func TestDelegatedGateStopsLoudly(t *testing.T) {
	// The agent declares an item it never completes. Plenty of turns available,
	// so the GATE is unambiguously the limit that fires — not maxTurns.
	rt := &scripted{replies: []map[string]any{
		callTodo([]any{todo("1", "never", false)}), text("done?"), text("done?"), text("done?"),
	}}
	child := agents.New("child", agents.Spec{Does: "works",
		Tools:      []tn.Tool{todowriteTool(t)},
		Budget:     &agents.Budget{MaxTurns: 30},
		Completion: &agents.Completion{Verify: agents.AllTodosDone, MaxAttempts: 2}})
	r := agents.NewRuntime(agents.Options{
		Registry: child.Registry(), Transport: rt,
		LLM: &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, APIKey: "x", Model: "m"},
	})
	h, _ := r.Spawn(r.Root, "child", nil)
	r.Wake(h, "go")
	res := r.Wait(h, 0)
	defer r.Close(h, nil)
	if res.Status != "incomplete" {
		t.Fatalf("status=%s, want incomplete; text=%q", res.Status, res.Text)
	}
	// KNOWN INTERACTION, found by this spike and not yet designed away: the gate's
	// retries share the handle's turn budget, and the client counts turns across
	// REPLAYED HISTORY. So each retry starts nearer the ceiling and the budget stop
	// can fire first — reporting "hit maxTurns" instead of the verification reason.
	// Both are honest, loud stops (never a silent done), which is what this asserts.
	// The open question is whether the gate should carry its own allowance, or the
	// two reasons should compose ("hit maxTurns while verifying; last failure: X").
	named := strings.Contains(res.Text, "completion.verify failed") ||
		strings.Contains(res.Text, "maxTurns")
	if !named {
		t.Fatalf("stop was not named at all: %q", res.Text)
	}
}

// SUSPENSION must survive the gate: a §10 pending is NOT a verification failure.
func TestSuspensionIsNotReJudgedByTheGate(t *testing.T) {
	asked := false
	ask := tn.Tool{Name: "ask_human", Description: "asks", Source: tn.SourceCustom,
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		Execute: func(_ map[string]any, ctx *tn.ToolContext) (tn.ToolResult, error) {
			if ctx != nil && ctx.Answer != nil {
				return tn.ToolResult{Output: "human said yes"}, nil
			}
			asked = true
			return tn.Pending(tn.Request{ID: "req-1", Kind: "question", Prompt: "Proceed?"}), nil
		}}
	callAsk := map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
		map[string]any{"id": "a1", "type": "function",
			"function": map[string]any{"name": "ask_human", "arguments": "{}"}}}}
	rt := &scripted{replies: []map[string]any{callAsk, text("finished")}}

	child := agents.New("child", agents.Spec{Does: "asks", Tools: []tn.Tool{ask},
		Completion: &agents.Completion{
			Verify:      func(tn.RunResult) (bool, string) { return false, "must never be consulted on a pending" },
			MaxAttempts: 2}})
	r := agents.NewRuntime(agents.Options{
		Registry: child.Registry(), Transport: rt,
		LLM: &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, APIKey: "x", Model: "m"},
	})
	h, _ := r.Spawn(r.Root, "child", nil)
	r.Wake(h, "go")
	res := r.Wait(h, 0)
	if !asked { t.Fatal("the suspending tool never ran") }
	if res.Status != "pending" {
		t.Fatalf("status=%s, want pending — the gate re-judged a suspension; text=%q", res.Status, res.Text)
	}
	if res.Pending == nil || res.Pending.Prompt != "Proceed?" {
		t.Fatalf("the §10 Request did not survive the gate: %+v", res.Pending)
	}
	r.Close(h, nil)
}

// Harness is a FACTORY over Spec, never a second type — so the name is
// first-class while exactly one type exists underneath and the two cannot drift.
func TestHarnessIsAFactoryNotASecondType(t *testing.T) {
	sp := agents.Spec{Does: "works", Soul: "be brief",
		Completion: &agents.Completion{Verify: agents.AllTodosDone, MaxAttempts: 2}}
	h := agents.Harness(sp)

	// Identical value, and assignable both ways — it IS Spec, so no drift is
	// possible between "harness" and "spec".
	var back agents.Spec = h
	if back.Does != sp.Does || back.Soul != sp.Soul || back.Completion != sp.Completion {
		t.Fatal("Harness did not round-trip its Spec")
	}
	// And an agent built through the harness name behaves identically.
	a := agents.New("worker", h)
	if a.Spec.Completion == nil || a.Spec.Completion.MaxAttempts != 2 {
		t.Fatal("the completion gate did not survive the harness factory")
	}
}

// RunOpts.Model must actually reach the wire — an option that silently does
// nothing is worse than no option.
func TestRunOptsModelReachesTheWire(t *testing.T) {
	rec := &modelRec{reply: func(int) map[string]any { return text("ok") }}
	tk, _ := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	a := agents.New("w", agents.Spec{Does: "w"})
	l := a.Loop(tn.ClientOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI,
		Model: "default-model", APIKey: "x", HTTPClient: &http.Client{Transport: rec}}, tk)

	if _, err := l.Run(context.Background(), "a", agents.RunOpts{}); err != nil { t.Fatal(err) }
	if _, err := l.Run(context.Background(), "b", agents.RunOpts{Model: "per-run-model"}); err != nil { t.Fatal(err) }

	if len(rec.models) != 2 || rec.models[0] != "default-model" || rec.models[1] != "per-run-model" {
		t.Fatalf("models on the wire = %v; want [default-model per-run-model]", rec.models)
	}
}

// When another limit stops a run while the gate was retrying, the caller must
// learn BOTH reasons — otherwise the budget stop masks the verification failure.
func TestBudgetStopCarriesTheVerificationReason(t *testing.T) {
	rec := &modelRec{reply: func(n int) map[string]any {
		if n == 1 { return callTodo([]any{todo("1", "never", false)}) }
		return text("claiming done")
	}}
	tw := todowriteTool(t)
	child := agents.New("child", agents.Spec{Does: "w", Tools: []tn.Tool{tw},
		Completion: &agents.Completion{Verify: agents.AllTodosDone, MaxAttempts: 5}})
	r := agents.NewRuntime(agents.Options{Registry: child.Registry(), Transport: rec,
		LLM: &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, APIKey: "x", Model: "m"}})
	h, _ := r.Spawn(r.Root, "child", nil)
	r.Wake(h, "go")
	res := r.Wait(h, 0)
	defer r.Close(h, nil)

	if res.Status == "done" { t.Fatal("silently done despite an open todo") }
	// The caller must be told the reason that ACTUALLY stopped the run. Before
	// the Limit field was threaded, the runtime hardcoded "hit maxTurns without a
	// final answer" for every incomplete — masking a completion-gate stop behind
	// a turn-cap message the run never hit.
	if strings.Contains(res.Text, "maxTurns") {
		t.Fatalf("MASKED: reported a turn-cap stop, but the completion gate is what "+
			"exhausted: %q", res.Text)
	}
	if !strings.Contains(res.Text, "completion.verify failed") || !strings.Contains(res.Text, "still open") {
		t.Fatalf("the gate's reason did not reach the caller: %q", res.Text)
	}
}
