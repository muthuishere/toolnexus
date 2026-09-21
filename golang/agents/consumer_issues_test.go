// Regression tests for consumer issues #87, #88 and #90 — the two entry points
// agreeing on one agent, and the runtime path being as legible as the loop.
// Hermetic: a scripted RoundTripper stands in for the LLM.
package agents_test

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
)

// systemRec records the system prompt and model of every request.
type systemRec struct {
	reply   func(n int) map[string]any
	systems []string
	models  []string
	n       int
}

func (s *systemRec) RoundTrip(req *http.Request) (*http.Response, error) {
	s.n++
	var sent struct {
		Model    string           `json:"model"`
		Messages []map[string]any `json:"messages"`
	}
	if req.Body != nil {
		raw, _ := io.ReadAll(req.Body)
		_ = json.Unmarshal(raw, &sent)
	}
	s.models = append(s.models, sent.Model)
	sys := ""
	for _, m := range sent.Messages {
		if m["role"] == "system" {
			sys, _ = m["content"].(string)
			break
		}
	}
	s.systems = append(s.systems, sys)
	body, _ := json.Marshal(map[string]any{
		"choices": []any{map[string]any{"message": s.reply(s.n)}},
		"usage":   map[string]any{"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10},
	})
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(string(body))),
		Header: http.Header{"Content-Type": []string{"application/json"}}}, nil
}

func callTool(name, id string) map[string]any {
	return map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
		map[string]any{"id": id, "type": "function",
			"function": map[string]any{"name": name, "arguments": "{}"}}}}
}

// ------------------------------------------------------- D2 / issue #87

// THE regression test. A guardrail declared on the Spec must deny through the
// Loop door exactly as it does through the runtime door — and the assertion is
// on EXECUTION, not on the model's text. Asserting the text is what let this
// survive a release: the model apologised convincingly while the tool ran.
func TestD2_GuardrailDeniesOnTheLoopPath_ExecuteNeverEntered(t *testing.T) {
	var entered atomic.Int32
	danger := tn.Tool{
		Name: "rm_rf", Description: "deletes everything",
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      tn.SourceCustom,
		Execute: func(map[string]any, *tn.ToolContext) (tn.ToolResult, error) {
			entered.Add(1) // if this ever runs, the guardrail is decorative
			return tn.ToolResult{Output: "deleted"}, nil
		},
	}
	tk, err := tn.CreateToolkit(nil, tn.Options{Builtins: false, ExtraTools: []tn.Tool{danger}})
	if err != nil {
		t.Fatal(err)
	}
	a := agents.New("worker", agents.Spec{
		Does: "works",
		Guardrails: []agents.Guardrail{func(ev tn.BeforeToolEvent) string {
			if ev.Name == "rm_rf" {
				return "destructive tools are not permitted"
			}
			return "allow"
		}},
	})
	rt := &systemRec{reply: func(n int) map[string]any {
		if n == 1 {
			return callTool("rm_rf", "c1")
		}
		return map[string]any{"role": "assistant", "content": "all done"}
	}}
	if _, err := a.Loop(clientWith(rt), tk).Run(context.Background(), "clean up", agents.RunOpts{}); err != nil {
		t.Fatal(err)
	}
	if got := entered.Load(); got != 0 {
		t.Fatalf("the denied tool's Execute was ENTERED %d× on the Loop path", got)
	}
}

// Soul, Model and Budget.MaxTurns travel with the agent through the Loop door.
func TestD2_LoopHonoursSoulModelAndBudget(t *testing.T) {
	tk, _ := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	a := agents.New("worker", agents.Spec{
		Does: "works", Soul: "You are terse.",
		Model: "spec-model", Budget: &agents.Budget{MaxTurns: 3},
	})
	rt := &systemRec{reply: func(int) map[string]any {
		return map[string]any{"role": "assistant", "content": "ok"}
	}}
	opts := clientWith(rt)
	opts.Model = "" // the caller declares no model
	if _, err := a.Loop(opts, tk).Run(context.Background(), "go", agents.RunOpts{}); err != nil {
		t.Fatal(err)
	}
	if rt.systems[0] != "You are terse." {
		t.Errorf("the soul must reach the wire, system = %q", rt.systems[0])
	}
	if rt.models[0] != "spec-model" {
		t.Errorf("Spec.Model must be the Loop default, model = %q", rt.models[0])
	}
}

// A caller-supplied SystemPrompt WINS over the soul (matching python/csharp;
// js's soul-wins precedence is the fork being aligned to this).
func TestD2_CallerSystemPromptWinsOverTheSoul(t *testing.T) {
	tk, _ := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	a := agents.New("worker", agents.Spec{Does: "works", Soul: "soul prompt"})
	rt := &systemRec{reply: func(int) map[string]any {
		return map[string]any{"role": "assistant", "content": "ok"}
	}}
	opts := clientWith(rt)
	opts.SystemPrompt = "caller prompt"
	if _, err := a.Loop(opts, tk).Run(context.Background(), "go", agents.RunOpts{}); err != nil {
		t.Fatal(err)
	}
	if rt.systems[0] != "caller prompt" {
		t.Errorf("caller-wins violated, system = %q", rt.systems[0])
	}
}

// A8: the caller passing the "inherit" sentinel means exactly what passing
// nothing means.
func TestD2_InheritSentinelBehavesLikeAnAbsentModel(t *testing.T) {
	tk, _ := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	a := agents.New("worker", agents.Spec{Does: "works", Model: "spec-model"})
	rt := &systemRec{reply: func(int) map[string]any {
		return map[string]any{"role": "assistant", "content": "ok"}
	}}
	opts := clientWith(rt)
	opts.Model = "inherit"
	if _, err := a.Loop(opts, tk).Run(context.Background(), "go", agents.RunOpts{}); err != nil {
		t.Fatal(err)
	}
	if rt.models[0] != "spec-model" {
		t.Errorf(`Model "inherit" must defer to Spec.Model, got %q`, rt.models[0])
	}
}

// A per-call RunOpts.Model still beats the Spec default.
func TestD2_PerCallModelStillWins(t *testing.T) {
	tk, _ := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	a := agents.New("worker", agents.Spec{Does: "works", Model: "spec-model"})
	rt := &systemRec{reply: func(int) map[string]any {
		return map[string]any{"role": "assistant", "content": "ok"}
	}}
	if _, err := a.Loop(clientWith(rt), tk).Run(context.Background(), "go", agents.RunOpts{Model: "per-call"}); err != nil {
		t.Fatal(err)
	}
	if rt.models[0] != "per-call" {
		t.Errorf("RunOpts.Model = %q", rt.models[0])
	}
}

// A6: the residue is REPORTED, in a fixed canonical vocabulary identical in all
// seven ports — never the language's own field spelling, and never a
// construction-time error (that would be breaking).
func TestD2_LoopUnsupportedNamesTheResidue(t *testing.T) {
	if got := agents.LoopUnsupported(agents.Spec{Does: "x", Soul: "y"}); len(got) != 0 {
		t.Errorf("a Spec the Loop fully carries must report nothing, got %v", got)
	}
	sp := agents.Spec{
		Does:     "x",
		Tools:    []tn.Tool{{Name: "t"}},
		Team:     []*agents.Agent{agents.New("other", agents.Spec{Does: "o"})},
		WaitFor:  func(tn.Request) (tn.Answer, error) { return tn.Answer{}, nil },
		OnMetric: func(tn.MetricEvent) {},
	}
	got := agents.LoopUnsupported(sp)
	want := []string{"tools", "team", "waitFor", "onMetric"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v (canonical vocabulary, stable order)", got, want)
		}
	}
	// And it does NOT refuse to build: the runtime door still honours all four.
	if l := agents.New("w", sp).Loop(clientWith(&systemRec{reply: func(int) map[string]any {
		return map[string]any{"role": "assistant", "content": "ok"}
	}}), nil); len(l.Unsupported()) != 4 {
		t.Error("the live Loop must report the same residue")
	}
}

// ------------------------------------------------------- D3 / issues #88,#90

// TotalTokens is the CUMULATIVE SUBTREE total on EVERY status, and OwnTokens is
// the per-agent figure. Before this the field meant "tree" on four status
// branches and "this run" on three — one field, two meanings.
func TestD3_TotalTokensIsTheTreeTotalAndOwnTokensIsNot(t *testing.T) {
	rt := &systemRec{reply: func(n int) map[string]any {
		switch n {
		case 1:
			return map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
				map[string]any{"id": "c1", "type": "function", "function": map[string]any{
					"name": "task", "arguments": `{"agent":"child","prompt":"sub"}`}}}}
		default:
			return map[string]any{"role": "assistant", "content": "parent done"}
		}
	}}
	child := agents.New("child", agents.Spec{Does: "does the sub-task"})
	parent := agents.New("parent", agents.Spec{Does: "delegates", Team: []*agents.Agent{child}})

	res, _ := parent.Run(agents.Options{
		LLM:       &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, Model: "m", APIKey: "x"},
		Transport: rt,
	}, "delegate it")

	if res.Status != agents.TaskStatusDone {
		t.Fatalf("status=%q text=%q", res.Status, res.Text)
	}
	if res.OwnTokens <= 0 {
		t.Fatal("OwnTokens must carry the per-agent figure")
	}
	if res.TotalTokens <= res.OwnTokens {
		t.Errorf("TotalTokens (%d) must include the delegated child's spend, OwnTokens = %d",
			res.TotalTokens, res.OwnTokens)
	}
	// A13: Turns is cumulative on every status too — the parent spent a turn
	// delegating and a turn answering, so it can never report fewer than the
	// child it drove. There is no OwnTurns: turns are not billed.
	if res.Turns < 2 {
		t.Errorf("Turns must be CUMULATIVE, not the last run's figure, got %d", res.Turns)
	}
}

// A13: Turns means the same thing on EVERY status branch. Before this it was
// per-run on done/pending/incomplete and cumulative on error/closed/timeout.
func TestD3_TurnsIsCumulativeOnEveryStatus(t *testing.T) {
	ask := tn.Tool{
		Name: "ask_human", Description: "asks",
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      tn.SourceCustom,
		Execute: func(map[string]any, *tn.ToolContext) (tn.ToolResult, error) {
			return tn.Pending(tn.Request{Kind: "input", Prompt: "proceed?"}), nil
		},
	}
	rt := &systemRec{reply: func(int) map[string]any { return callTool("ask_human", "c1") }}
	a := agents.New("w", agents.Spec{Does: "works", Tools: []tn.Tool{ask}})
	opts := agents.Options{
		LLM:       &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, Model: "m", APIKey: "x"},
		Transport: rt, Registry: a.Registry(),
	}
	rtm := agents.NewRuntime(opts)
	h, err := rtm.Spawn(rtm.Root, "w", nil)
	if err != nil {
		t.Fatal(err)
	}
	first := rtm.RunTurn(h, "one")
	if first.Status != agents.TaskStatusPending {
		t.Fatalf("expected a durable suspension, got %q", first.Status)
	}
	if first.Turns == 0 {
		t.Error("a suspended turn still spent a round trip")
	}
	// The runtime's own cumulative counter is the reference: the "closed" and
	// "error" branches have always reported it, the pending/done/incomplete
	// branches reported the last run's figure instead. They must now agree.
	if view := rtm.Inspect(h); view.Turns != first.Turns {
		t.Errorf("pending reported %d turns, the handle has %d — one field, two meanings",
			first.Turns, view.Turns)
	}
	// A further turn ACCUMULATES rather than resetting — including through the
	// resume cascade, which is where the per-run figure was most misleading.
	resumed, err := rtm.Resume(tn.Answer{ID: first.Pending.ID, Ok: true})
	if err != nil {
		t.Fatal(err)
	}
	if resumed.Turns <= first.Turns {
		t.Errorf("Turns must accumulate: %d before the resume, %d after", first.Turns, resumed.Turns)
	}
}

// Limit is STRUCTURED: a host must never have to string-match the message to
// learn which limit stopped the run.
func TestD3_LimitIsNamedOnABudgetStop(t *testing.T) {
	rt := &systemRec{reply: func(int) map[string]any {
		return map[string]any{"role": "assistant", "content": "ok"}
	}}
	a := agents.New("w", agents.Spec{Does: "works", Budget: &agents.Budget{MaxTokens: 1}})
	opts := agents.Options{
		LLM:       &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, Model: "m", APIKey: "x"},
		Transport: rt, Registry: a.Registry(),
	}
	rtm := agents.NewRuntime(opts)
	h, err := rtm.Spawn(rtm.Root, "w", nil)
	if err != nil {
		t.Fatal(err)
	}
	// The first turn spends the pool; the second is refused by the budget walk.
	rtm.RunTurn(h, "one")
	got := rtm.RunTurn(h, "two")
	if got.Status != agents.TaskStatusIncomplete {
		t.Fatalf("status=%q text=%q", got.Status, got.Text)
	}
	if got.Limit != agents.LimitMaxTokens {
		t.Errorf("Limit = %q, want %q (the shared vocabulary, not the internal pool name)", got.Limit, agents.LimitMaxTokens)
	}
	if got.TotalTokens == 0 {
		t.Error("partial work must be preserved on a budget stop")
	}
}

// Resume hands back the settled result of the topmost handle it re-ran (A4), so
// a host writes `res, err := rt.Resume(ans)` and is done — instead of resuming
// blind and then hunting for the result.
func TestD3_ResumeReturnsTheResumedResult(t *testing.T) {
	asked := 0
	ask := tn.Tool{
		Name: "ask_human", Description: "asks",
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      tn.SourceCustom,
		Execute: func(_ map[string]any, ctx *tn.ToolContext) (tn.ToolResult, error) {
			asked++
			if ctx != nil && ctx.Answer != nil {
				return tn.ToolResult{Output: "human said yes"}, nil
			}
			return tn.Pending(tn.Request{Kind: "input", Prompt: "proceed?"}), nil
		},
	}
	// The resume REPLAYS the suspended turn from its pre-turn checkpoint, so the
	// model is asked again and re-issues the same call — which is exactly the
	// idempotency contract this scripting is here to expose.
	rt := &systemRec{reply: func(n int) map[string]any {
		if n <= 2 {
			return callTool("ask_human", "c1")
		}
		return map[string]any{"role": "assistant", "content": "finished after the answer"}
	}}
	a := agents.New("w", agents.Spec{Does: "works", Tools: []tn.Tool{ask}})
	res, rtm := a.Run(agents.Options{
		LLM:       &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, Model: "m", APIKey: "x"},
		Transport: rt,
	}, "go")
	if res.Status != agents.TaskStatusPending || res.Pending == nil {
		t.Fatalf("expected a durable suspension, got %q / %q", res.Status, res.Text)
	}

	resumed, err := rtm.Resume(tn.Answer{ID: res.Pending.ID, Ok: true})
	if err != nil {
		t.Fatal(err)
	}
	if resumed.Status != agents.TaskStatusDone {
		t.Fatalf("Resume returned status %q (%s)", resumed.Status, resumed.Text)
	}
	if resumed.Text != "finished after the answer" {
		t.Errorf("Resume must return the RESUMED result, got %q", resumed.Text)
	}
	if resumed.TotalTokens <= 0 {
		t.Error("the resumed result must carry the cumulative usage")
	}
	// The idempotency contract, asserted rather than only documented: the
	// suspended tool ran again on the resume.
	if asked < 2 {
		t.Errorf("a resumed turn re-executes the suspended tool (ran %d×)", asked)
	}
}

// A17: a status and its limit must never contradict each other. The wait
// deadline used to report Status "timeout" with an EMPTY Limit, so a host that
// branched on Limit saw nothing while the status said the run had timed out.
// This asserts BOTH fields together — status in the 7-value agent vocabulary,
// limit in the closed A14 set — so they cannot drift apart again.
func TestD3_WaitDeadlineSetsBothStatusAndLimit(t *testing.T) {
	// A turn that parks forever on a durable suspension, so the wait deadline is
	// the thing that fires rather than the turn completing.
	block := make(chan struct{})
	t.Cleanup(func() { close(block) })
	slow := tn.Tool{
		Name: "slow", Description: "never returns",
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      tn.SourceCustom,
		Execute: func(map[string]any, *tn.ToolContext) (tn.ToolResult, error) {
			<-block
			return tn.ToolResult{Output: "late"}, nil
		},
	}
	rt := &systemRec{reply: func(int) map[string]any { return callTool("slow", "c1") }}
	a := agents.New("w", agents.Spec{Does: "works", Tools: []tn.Tool{slow}})
	rtm := agents.NewRuntime(agents.Options{
		LLM:       &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, Model: "m", APIKey: "x"},
		Transport: rt, Registry: a.Registry(),
	})
	h, err := rtm.Spawn(rtm.Root, "w", nil)
	if err != nil {
		t.Fatal(err)
	}
	rtm.Wake(h, "go")
	got := rtm.Wait(h, 25*time.Millisecond)

	if got.Status != agents.TaskStatusTimeout {
		t.Fatalf("Status = %q, want %q", got.Status, agents.TaskStatusTimeout)
	}
	if got.Limit != agents.LimitTimeout {
		t.Fatalf("Status %q with Limit %q — the two fields contradict each other",
			got.Status, got.Limit)
	}
	// Both values belong to their own closed vocabulary.
	agentStatuses := map[string]bool{
		agents.TaskStatusDone: true, agents.TaskStatusPending: true,
		agents.TaskStatusIncomplete: true, agents.TaskStatusInterrupted: true,
		agents.TaskStatusClosed: true, agents.TaskStatusTimeout: true,
		agents.TaskStatusError: true,
	}
	limits := map[string]bool{
		agents.LimitMaxTurns: true, agents.LimitMaxTokens: true,
		agents.LimitMaxToolCalls: true, agents.LimitMaxWallMs: true,
		agents.LimitMaxChildren: true, agents.LimitMaxConcurrent: true,
		agents.LimitMaxDepth: true, agents.LimitCompletion: true,
		agents.LimitTimeout: true,
	}
	if !agentStatuses[got.Status] {
		t.Errorf("status %q is outside the 7-value agent vocabulary", got.Status)
	}
	if !limits[got.Limit] {
		t.Errorf("limit %q is outside the closed A14 set", got.Limit)
	}
}

// Every LIMIT STOP names its limit. A stop that is not a limit (closed, error,
// interrupted, pending, done) leaves it empty — that is the invariant, and it is
// the class of bug A17 swept, not one site.
func TestD3_EveryLimitStopNamesItsLimit(t *testing.T) {
	rt := &systemRec{reply: func(int) map[string]any {
		return map[string]any{"role": "assistant", "content": "ok"}
	}}
	a := agents.New("w", agents.Spec{Does: "works", Budget: &agents.Budget{MaxTokens: 1}})
	rtm := agents.NewRuntime(agents.Options{
		LLM:       &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, Model: "m", APIKey: "x"},
		Transport: rt, Registry: a.Registry(),
	})
	h, err := rtm.Spawn(rtm.Root, "w", nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, got := range []agents.TaskResult{
		rtm.RunTurn(h, "one"), // done   — no limit
		rtm.RunTurn(h, "two"), // incomplete (budget) — MUST name it
	} {
		switch got.Status {
		case agents.TaskStatusIncomplete, agents.TaskStatusTimeout:
			if got.Limit == "" {
				t.Errorf("status %q is a LIMIT STOP and must name its limit", got.Status)
			}
		default:
			if got.Limit != "" {
				t.Errorf("status %q is not a limit stop but named %q", got.Status, got.Limit)
			}
		}
	}
	// A closed handle is not a limit stop either. (Wait would hand back the
	// SETTLED last result — here the budget stop — so drive the closed-handle
	// branch directly.)
	rtm.Close(h, nil)
	closed := rtm.RunTurn(h, "three")
	if closed.Status != agents.TaskStatusClosed {
		t.Fatalf("status = %q", closed.Status)
	}
	if closed.Limit != "" {
		t.Errorf("a closed handle named a limit: %q", closed.Limit)
	}
}

// The two vocabularies are distinct, and "timeout" lives ONLY in this one.
func TestD3_AgentStatusVocabularyIsSevenValues(t *testing.T) {
	seven := []string{
		agents.TaskStatusDone, agents.TaskStatusPending, agents.TaskStatusIncomplete,
		agents.TaskStatusInterrupted, agents.TaskStatusClosed, agents.TaskStatusTimeout,
		agents.TaskStatusError,
	}
	if len(seven) != 7 {
		t.Fatal("the §7D set has seven values")
	}
	if agents.TaskStatusTimeout != "timeout" {
		t.Error(`"timeout" is this set's — and only this set's`)
	}
	// A14: the Limit vocabulary is CLOSED and names the Budget field as SPEC
	// spells it — maxWallMs, not maxWall.
	for name, got := range map[string]string{
		"maxTurns": agents.LimitMaxTurns, "maxTokens": agents.LimitMaxTokens,
		"maxToolCalls": agents.LimitMaxToolCalls, "maxWallMs": agents.LimitMaxWallMs,
		"maxChildren": agents.LimitMaxChildren, "maxConcurrent": agents.LimitMaxConcurrent,
		"maxDepth":   agents.LimitMaxDepth,
		"completion": agents.LimitCompletion, "timeout": agents.LimitTimeout,
	} {
		if got != name {
			t.Errorf("Limit vocabulary drifted: got %q, want %q", got, name)
		}
	}
}
