package toolnexus

// Relay mode tests (§10, ADR-0010). The scenario names track the spec deltas in
// openspec/changes/add-tool-relay-mode/specs/. Test helpers (scripted mock LLMs,
// spikeToolkit) are shared with relay_spike_test.go.

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"
)

func relayTool(name string) Tool { return RelayTool(name, "relayed to the caller", nil) }

// ---- tool-relay: declaration-only, never executed host-side ----

// TestRelayCallReachesHostAndNothingRunsLocally: the model's relay call surfaces as a
// kind:"tool_call" Request carrying id/name/input/arguments, and the host's output
// becomes the tool_result the model sees.
func TestRelayCallReachesHostAndNothingRunsLocally(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{"q":"weather"}`}}}, "it is sunny")
	var got Request
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			got = req
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{RelayOutputKey: "sunny, 31C"}}, nil
		},
	})
	res, err := c.Run(context.Background(), "weather?", spikeToolkit(t, relayTool("lookup")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" || res.Text != "it is sunny" {
		t.Fatalf("status=%q text=%q", res.Status, res.Text)
	}
	if !IsRelayRequest(&got) {
		t.Fatalf("request kind = %q, want %q", got.Kind, RelayKind)
	}
	calls := RelayCallsOf(&got)
	if len(calls) != 1 {
		t.Fatalf("want 1 relayed call, got %d", len(calls))
	}
	if calls[0].ID != "c1" || calls[0].Name != "lookup" {
		t.Fatalf("call id/name = %q/%q, want c1/lookup", calls[0].ID, calls[0].Name)
	}
	if calls[0].Input["q"] != "weather" {
		t.Fatalf("call input = %+v", calls[0].Input)
	}
	if !strings.Contains(calls[0].Arguments, `"weather"`) {
		t.Fatalf("raw arguments not carried: %q", calls[0].Arguments)
	}
	if len(res.ToolCalls) != 1 || res.ToolCalls[0].Output != "sunny, 31C" || res.ToolCalls[0].IsError {
		t.Fatalf("host output did not become the tool result: %+v", res.ToolCalls)
	}
}

// TestRelayToolIsDeclaredToEveryAdapter: a relay tool appears in each provider's native
// declaration shape, indistinguishable from an executing tool.
func TestRelayToolIsDeclaredToEveryAdapter(t *testing.T) {
	tools := []Tool{RelayTool("lookup", "look something up", JSONSchema{
		"type": "object", "properties": map[string]any{"q": map[string]any{"type": "string"}},
	})}
	for name, got := range map[string][]any{
		"openai":    ToOpenAI(tools),
		"anthropic": ToAnthropic(tools),
		"gemini":    ToGemini(tools),
	} {
		raw, _ := json.Marshal(got)
		for _, want := range []string{"lookup", "look something up"} {
			if !strings.Contains(string(raw), want) {
				t.Fatalf("%s adapter dropped %q: %s", name, want, raw)
			}
		}
	}
}

// TestRelayOnAnthropicLoop: the tool_result block references the original tool_use id.
func TestRelayOnAnthropicLoop(t *testing.T) {
	llm := newSpikeAnthropicLLM(t, [][]spikeCall{{{"tu_1", "lookup", `{"q":"x"}`}}}, "done")
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			if calls := RelayCallsOf(&req); len(calls) != 1 || calls[0].ID != "tu_1" {
				t.Fatalf("relay call id not stamped from the tool_use block: %+v", calls)
			}
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{RelayOutputKey: "anthropic-out"}}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, relayTool("lookup")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q", res.Status)
	}
	raw, _ := json.Marshal(llm.requests()[1]["messages"])
	for _, want := range []string{`"tool_result"`, `"tu_1"`, "anthropic-out"} {
		if !strings.Contains(string(raw), want) {
			t.Fatalf("anthropic transcript missing %s: %s", want, raw)
		}
	}
}

// TestRelayOnStreamingLoop: the stream emits `pending` carrying the relay Request before
// the host resolves it, then resolves inline.
func TestRelayOnStreamingLoop(t *testing.T) {
	srv := spikeStreamLLM(t, []spikeCall{{"c1", "lookup", `{}`}}, "streamed")
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{RelayOutputKey: "stream-out"}}, nil
		},
	})
	ch, err := c.Stream(context.Background(), "go", spikeToolkit(t, relayTool("lookup")))
	if err != nil {
		t.Fatalf("stream: %v", err)
	}
	var sawPending bool
	var res *RunResult
	for ev := range ch {
		switch ev.Type {
		case "pending":
			sawPending = true
			if !IsRelayRequest(ev.Request) {
				t.Fatalf("pending event kind = %q", ev.Request.Kind)
			}
			if calls := RelayCallsOf(ev.Request); len(calls) != 1 || calls[0].ID != "c1" {
				t.Fatalf("streaming relay call not stamped: %+v", calls)
			}
		case "done":
			res = ev.Result
		case "error":
			t.Fatalf("stream error: %v", ev.Err)
		}
	}
	if !sawPending {
		t.Fatal("no pending event emitted for the relay call")
	}
	if res == nil || res.Status != "done" || len(res.ToolCalls) != 1 || res.ToolCalls[0].Output != "stream-out" {
		t.Fatalf("stream result = %+v", res)
	}
}

// ---- tool-relay: caller-side failure and decline ----

func TestRelayCallerToolFailedIsAnErrorResult(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{}`}}}, "recovered")
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{
				RelayOutputKey: "upstream 500", RelayIsErrorKey: true,
			}}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, relayTool("lookup")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done", res.Status)
	}
	if len(res.ToolCalls) != 1 || !res.ToolCalls[0].IsError {
		t.Fatalf("caller-side failure did not surface as an error result: %+v", res.ToolCalls)
	}
}

func TestRelayDeclinedByCallerContinues(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{}`}}}, "understood")
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: false, Reason: "declined"}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, relayTool("lookup")))
	if err != nil {
		t.Fatalf("a declined relay must not fail the run: %v", err)
	}
	if res.Status != "done" || len(res.ToolCalls) != 1 || !res.ToolCalls[0].IsError {
		t.Fatalf("declined relay = %+v (status %q)", res.ToolCalls, res.Status)
	}
}

// ---- suspension: all N relay calls ride the one surfaced Request (F2-a) ----

// TestThreeRelayCallsAllRideTheSurfacedRequest is the F2-a requirement: the run still
// halts first-in-order, but the surfaced Request carries the whole turn.
func TestThreeRelayCallsAllRideTheSurfacedRequest(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{
		{"c1", "alpha", `{"n":1}`}, {"c2", "beta", `{"n":2}`}, {"c3", "gamma", `{"n":3}`},
	}}, "unreachable")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, relayTool("alpha"), relayTool("beta"), relayTool("gamma"))
	res, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "pending" || res.Pending == nil {
		t.Fatalf("status=%q pending=%v, want a durable halt", res.Status, res.Pending)
	}
	calls := RelayCallsOf(res.Pending)
	if len(calls) != 3 {
		t.Fatalf("surfaced request carries %d calls, want all 3: %+v", len(calls), calls)
	}
	for i, want := range []struct{ id, name string }{{"c1", "alpha"}, {"c2", "beta"}, {"c3", "gamma"}} {
		if calls[i].ID != want.id || calls[i].Name != want.name {
			t.Fatalf("call %d = %q/%q, want %q/%q (tool-call order)", i, calls[i].ID, calls[i].Name, want.id, want.name)
		}
	}
}

// TestRelayCallOrderIsDeterministic: repeated runs give the same order, independent of
// which handler finished first.
func TestRelayCallOrderIsDeterministic(t *testing.T) {
	for i := 0; i < 20; i++ {
		llm := newSpikeScriptedLLM(t, [][]spikeCall{{
			{"c1", "alpha", `{}`}, {"c2", "beta", `{}`}, {"c3", "gamma", `{}`},
		}}, "x")
		c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
		tk := spikeToolkit(t, relayTool("alpha"), relayTool("beta"), relayTool("gamma"))
		res, err := c.Run(context.Background(), "go", tk)
		if err != nil {
			t.Fatalf("run %d: %v", i, err)
		}
		var names []string
		for _, rc := range RelayCallsOf(res.Pending) {
			names = append(names, rc.Name)
		}
		if strings.Join(names, ",") != "alpha,beta,gamma" {
			t.Fatalf("iteration %d: order = %v, want alpha,beta,gamma", i, names)
		}
	}
}

// ---- suspension: durable resume (F1-a / D4) ----

// TestResumeFillsEveryOutstandingToolResultSlot is the D4 requirement and routsi's
// binding constraint: after resume, one tool_result per tool_use, and the halt's
// placeholder error is REPLACED, not sitting alongside.
func TestResumeFillsEveryOutstandingToolResultSlot(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{
		{"c1", "alpha", `{}`}, {"c2", "beta", `{}`}, {"c3", "gamma", `{}`},
	}}, "all three answered")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, relayTool("alpha"), relayTool("beta"), relayTool("gamma"))

	halted, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if halted.Status != "pending" {
		t.Fatalf("status = %q", halted.Status)
	}

	// The host executes all three itself and resumes.
	resumed, err := c.RunWithAnswer(context.Background(), tk, halted.Messages, *halted.Pending,
		RelayAnswer(halted.Pending.ID, []RelayResult{
			{ID: "c1", Output: "out-alpha"},
			{ID: "c2", Output: "out-beta"},
			{ID: "c3", Output: "out-gamma"},
		}))
	if err != nil {
		t.Fatalf("resume: %v", err)
	}
	if resumed.Status != "done" || resumed.Text != "all three answered" {
		t.Fatalf("resumed status=%q text=%q", resumed.Status, resumed.Text)
	}
	calls, results, ids := spikeMessageCounts(resumed.Messages)
	if calls != results {
		t.Fatalf("transcript unbalanced after resume: %d tool_calls vs %d tool_results (ids %v)", calls, results, ids)
	}
	if len(ids) != 3 {
		t.Fatalf("want a tool_result per call, got ids %v", ids)
	}
	// The placeholder must be gone, and every real output present.
	raw, _ := json.Marshal(resumed.Messages)
	for _, want := range []string{"out-alpha", "out-beta", "out-gamma"} {
		if !strings.Contains(string(raw), want) {
			t.Fatalf("resumed transcript missing %s", want)
		}
	}
	if strings.Contains(string(raw), "relay tool call alpha to the caller") {
		t.Fatal("the halt placeholder survived the resume — it must be REPLACED, not appended alongside")
	}
	// And the provider saw a balanced transcript on the resumed turn.
	reqs := llm.requests()
	sent, _ := json.Marshal(reqs[len(reqs)-1]["messages"])
	for _, want := range []string{"out-alpha", "out-beta", "out-gamma"} {
		if !strings.Contains(string(sent), want) {
			t.Fatalf("provider did not receive %s: %s", want, sent)
		}
	}
}

// TestResumeSingleCallShorthand: a one-call relay resumes from the data.output shorthand,
// with no results array.
func TestResumeSingleCallShorthand(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{}`}}}, "answered")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, relayTool("lookup"))
	halted, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	resumed, err := c.RunWithAnswer(context.Background(), tk, halted.Messages, *halted.Pending,
		Answer{ID: halted.Pending.ID, Ok: true, Data: map[string]any{RelayOutputKey: "single-out"}})
	if err != nil {
		t.Fatalf("resume: %v", err)
	}
	if resumed.Status != "done" {
		t.Fatalf("status = %q", resumed.Status)
	}
	raw, _ := json.Marshal(resumed.Messages)
	if !strings.Contains(string(raw), "single-out") {
		t.Fatalf("shorthand output missing: %s", raw)
	}
}

// TestResumeOnAnthropicTranscript: resume rebuilds native tool_result blocks.
func TestResumeOnAnthropicTranscript(t *testing.T) {
	llm := newSpikeAnthropicLLM(t, [][]spikeCall{{
		{"tu_1", "alpha", `{}`}, {"tu_2", "beta", `{}`},
	}}, "both answered")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, relayTool("alpha"), relayTool("beta"))
	halted, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if halted.Status != "pending" {
		t.Fatalf("status = %q", halted.Status)
	}
	resumed, err := c.RunWithAnswer(context.Background(), tk, halted.Messages, *halted.Pending,
		RelayAnswer(halted.Pending.ID, []RelayResult{
			{ID: "tu_1", Output: "out-1"}, {ID: "tu_2", Output: "out-2"},
		}))
	if err != nil {
		t.Fatalf("resume: %v", err)
	}
	if resumed.Status != "done" {
		t.Fatalf("status = %q", resumed.Status)
	}
	// Count native blocks: one tool_result per tool_use.
	uses, results := 0, 0
	for _, m := range resumed.Messages {
		mm, ok := m.(map[string]any)
		if !ok {
			continue
		}
		blocks, ok := mm["content"].([]any)
		if !ok {
			continue
		}
		for _, b := range blocks {
			if bb, ok := b.(map[string]any); ok {
				switch bb["type"] {
				case "tool_use":
					uses++
				case "tool_result":
					results++
				}
			}
		}
	}
	if uses != results || uses == 0 {
		t.Fatalf("anthropic transcript unbalanced after resume: %d tool_use vs %d tool_result", uses, results)
	}
}

// TestResumeAcrossAProcessBoundary: the request and transcript are plain serializable
// data, so a fresh client with only the persisted JSON can resume.
func TestResumeAcrossAProcessBoundary(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{}`}}}, "resumed elsewhere")
	tk := spikeToolkit(t, relayTool("lookup"))
	first := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	halted, err := first.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}

	// Round-trip everything through JSON, as a durable host would.
	blob, err := json.Marshal(map[string]any{"messages": halted.Messages, "pending": halted.Pending})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var restored struct {
		Messages []any   `json:"messages"`
		Pending  Request `json:"pending"`
	}
	if err := json.Unmarshal(blob, &restored); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if calls := RelayCallsOf(&restored.Pending); len(calls) != 1 || calls[0].ID != "c1" {
		t.Fatalf("relay calls did not survive serialization: %+v", calls)
	}

	// A brand-new client resumes it.
	second := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	resumed, err := second.RunWithAnswer(context.Background(), tk, restored.Messages, restored.Pending,
		RelayAnswer(restored.Pending.ID, []RelayResult{{ID: "c1", Output: "cross-process"}}))
	if err != nil {
		t.Fatalf("resume in a new client: %v", err)
	}
	if resumed.Status != "done" || resumed.Text != "resumed elsewhere" {
		t.Fatalf("resumed = %+v", resumed)
	}
}

// TestResumeRejectsAMismatchedAnswer: a stale or misrouted answer must error, not
// silently continue.
func TestResumeRejectsAMismatchedAnswer(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{}`}}}, "x")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, relayTool("lookup"))
	halted, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	_, err = c.RunWithAnswer(context.Background(), tk, halted.Messages, *halted.Pending,
		RelayAnswer("pnd-not-the-one", []RelayResult{{ID: "c1", Output: "x"}}))
	if err == nil {
		t.Fatal("resuming with a mismatched answer id must fail")
	}
	if !strings.Contains(err.Error(), "does not echo") {
		t.Fatalf("unhelpful error: %v", err)
	}
}

// TestResumeWithoutATranscriptFails guards the other degenerate input.
func TestResumeWithoutATranscriptFails(t *testing.T) {
	c := CreateClient(ClientOptions{BaseURL: "http://127.0.0.1:1", Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, relayTool("lookup"))
	if _, err := c.RunWithAnswer(context.Background(), tk, nil, Request{ID: "p1", Kind: RelayKind}, Answer{ID: "p1", Ok: true}); err == nil {
		t.Fatal("resuming with no transcript must fail")
	}
}

// TestAskWithAnswerRoundTripsThroughTheStore: the stateful resume loads and saves.
func TestAskWithAnswerRoundTripsThroughTheStore(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{}`}}}, "stored answer")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, relayTool("lookup"))

	halted, err := c.Ask(context.Background(), "go", tk, "conv-relay")
	if err != nil {
		t.Fatalf("ask: %v", err)
	}
	if halted.Status != "pending" {
		t.Fatalf("status = %q", halted.Status)
	}
	resumed, err := c.AskWithAnswer(context.Background(), tk, "conv-relay", *halted.Pending,
		RelayAnswer(halted.Pending.ID, []RelayResult{{ID: "c1", Output: "stored-out"}}))
	if err != nil {
		t.Fatalf("askWithAnswer: %v", err)
	}
	if resumed.Status != "done" || resumed.Text != "stored answer" {
		t.Fatalf("resumed = %+v", resumed)
	}
	// The saved transcript is the resumed one.
	saved, err := c.store.Get("conv-relay")
	if err != nil {
		t.Fatalf("store get: %v", err)
	}
	raw, _ := json.Marshal(saved)
	if !strings.Contains(string(raw), "stored-out") {
		t.Fatalf("resumed transcript was not saved: %s", raw)
	}
	if _, err := c.AskWithAnswer(context.Background(), tk, "", Request{}, Answer{}); err == nil {
		t.Fatal("AskWithAnswer with an empty id must fail")
	}
}

// TestResumeSuppliesErrorResultsForMissingOutputs: a call the host did not answer gets an
// explicit error result, never an absent slot (a provider rejects an unanswered call).
func TestResumeSuppliesErrorResultsForMissingOutputs(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{
		{"c1", "alpha", `{}`}, {"c2", "beta", `{}`},
	}}, "partial")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, relayTool("alpha"), relayTool("beta"))
	halted, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	resumed, err := c.RunWithAnswer(context.Background(), tk, halted.Messages, *halted.Pending,
		RelayAnswer(halted.Pending.ID, []RelayResult{{ID: "c1", Output: "only-alpha"}}))
	if err != nil {
		t.Fatalf("resume: %v", err)
	}
	calls, results, _ := spikeMessageCounts(resumed.Messages)
	if calls != results {
		t.Fatalf("a missing host result left an unanswered call: %d vs %d", calls, results)
	}
	raw, _ := json.Marshal(resumed.Messages)
	if !strings.Contains(string(raw), "no result supplied on resume for beta") {
		t.Fatalf("expected an explicit error result for the unanswered call: %s", raw)
	}
}

// ---- collision guard (D5) ----

func TestRelayNameCollidingWithABuiltinIsRejected(t *testing.T) {
	for _, builtins := range []any{true, false} {
		_, err := CreateToolkit(context.Background(), Options{
			Builtins:   builtins,
			ExtraTools: []Tool{relayTool("bash")},
		})
		if err == nil {
			t.Fatalf("builtins=%v: a relay tool named 'bash' must be rejected", builtins)
		}
		if !strings.Contains(err.Error(), "collides with a built-in") {
			t.Fatalf("builtins=%v: unhelpful error: %v", builtins, err)
		}
	}
}

func TestNonRelayToolMayShadowABuiltin(t *testing.T) {
	// The guard is relay-specific: an ordinary ExtraTools override is a documented
	// feature (host tools outrank builtins) and must keep working.
	tk, err := CreateToolkit(context.Background(), Options{
		Builtins:   true,
		ExtraTools: []Tool{{Name: "bash", Description: "mine", Source: SourceCustom, Execute: func(map[string]any, *ToolContext) (ToolResult, error) { return ToolResult{Output: "mine"}, nil }}},
	})
	if err != nil {
		t.Fatalf("a non-relay override must still be allowed: %v", err)
	}
	defer tk.Close()
}

// ---- no-perturbation (the byte-identical-when-absent rule) ----

func TestUncalledRelayToolIsInert(t *testing.T) {
	run := func(tools ...Tool) RunResult {
		llm := newSpikeScriptedLLM(t, nil, "plain answer")
		c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
		res, err := c.Run(context.Background(), "hello", spikeToolkit(t, tools...))
		if err != nil {
			t.Fatalf("run: %v", err)
		}
		return res
	}
	base, with := run(), run(relayTool("never_called"))
	if base.Text != with.Text || base.Status != with.Status || base.Turns != with.Turns || len(with.ToolCalls) != 0 {
		t.Fatalf("an uncalled relay tool perturbed the run:\n base=%+v\n with=%+v", base, with)
	}
}

func TestRealToolStillRunsAlongsideARelayCall(t *testing.T) {
	for _, withWaitFor := range []bool{true, false} {
		llm := newSpikeScriptedLLM(t, [][]spikeCall{{
			{"c1", "realtool", `{}`}, {"c2", "relayed", `{}`},
		}}, "done")
		var ran bool
		var mu = new(sync.Mutex)
		opts := ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"}
		if withWaitFor {
			opts.WaitFor = func(req Request) (Answer, error) {
				return Answer{ID: req.ID, Ok: true, Data: map[string]any{RelayOutputKey: "relayed"}}, nil
			}
		}
		c := CreateClient(opts)
		tk := spikeToolkit(t, spikeExecTool("realtool", &ran, mu), relayTool("relayed"))
		if _, err := c.Run(context.Background(), "go", tk); err != nil {
			t.Fatalf("waitFor=%v: run: %v", withWaitFor, err)
		}
		mu.Lock()
		got := ran
		mu.Unlock()
		if !got {
			t.Fatalf("waitFor=%v: the real tool did not run alongside a relay call", withWaitFor)
		}
	}
}

func TestRelaySuspensionIsNotATooError(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "relayed", `{}`}}}, "done")
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{RelayOutputKey: "ok"}}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, relayTool("relayed")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	for _, tc := range res.ToolCalls {
		if tc.IsError {
			t.Fatalf("a resolved relay call was recorded as an error: %+v", tc)
		}
	}
}

func TestRelayMayBeCalledAgainInALaterTurn(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{
		{{"c1", "step", `{"i":1}`}}, {{"c2", "step", `{"i":2}`}}, {{"c3", "step", `{"i":3}`}},
	}, "three rounds")
	var n int
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			n++
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{RelayOutputKey: "round"}}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, relayTool("step")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" || n != 3 || len(res.ToolCalls) != 3 {
		t.Fatalf("multi-round relay: status=%q resolutions=%d calls=%d", res.Status, n, len(res.ToolCalls))
	}
}

// TestRelayAndAuthSuspensionInTheSameTurn covers the design's flagged risk: two different
// suspension kinds raised in one turn. The auth call is first in order, so it is the one
// surfaced — and it must NOT be turned into a relay request by the merge.
func TestRelayAndAuthSuspensionInTheSameTurn(t *testing.T) {
	authTool := Tool{
		Name: "needs_login", Description: "auth", Source: SourceCustom,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
			return AuthRequired("https://example.test/login", "log in first"), nil
		},
	}
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{
		{"c1", "needs_login", `{}`}, {"c2", "relayed", `{}`},
	}}, "x")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, authTool, relayTool("relayed"))
	res, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "pending" || res.Pending == nil {
		t.Fatalf("status = %q", res.Status)
	}
	if res.Pending.Kind != "authorization" {
		t.Fatalf("surfaced kind = %q, want the first-in-order authorization suspension", res.Pending.Kind)
	}
	if RelayCallsOf(res.Pending) != nil {
		t.Fatal("the auth request was contaminated with relay calls by the merge")
	}
}

// TestRelayRoundTripsThroughConversationStore: relayed tool_call/tool_result pairs survive
// a store round trip with ids intact and replay to the provider on the next Ask. This is
// the claim that closes routsi ADR-010 item 4 (no upstream memory work needed).
func TestRelayRoundTripsThroughConversationStore(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{"q":"x"}`}}}, "first answer")
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{RelayOutputKey: "relayed-value"}}, nil
		},
	})
	tk := spikeToolkit(t, relayTool("lookup"))
	if _, err := c.Ask(context.Background(), "first", tk, "conv-1"); err != nil {
		t.Fatalf("first ask: %v", err)
	}
	if _, err := c.Ask(context.Background(), "second", tk, "conv-1"); err != nil {
		t.Fatalf("second ask: %v", err)
	}
	reqs := llm.requests()
	last, _ := json.Marshal(reqs[len(reqs)-1]["messages"])
	for _, want := range []string{`"tool_calls"`, `"c1"`, "relayed-value", `"lookup"`} {
		if !strings.Contains(string(last), want) {
			t.Fatalf("history did not round-trip %s structurally:\n%s", want, last)
		}
	}
}

// TestParkedWaitForAcrossTurns answers routsi's Q1/Q2/Q5 by measurement rather than by
// reading: a host may PARK its WaitFor across an external boundary (an HTTP turn) and
// release it later from another goroutine. Three relay calls in one turn each get their
// OWN concurrent WaitFor callback, so a proxy can learn N, answer all N out-of-band, and
// let the loop resume with real tool_result blocks.
//
// This is the "hooks/trampoline" route working on the in-process path with no durable
// resume involved. It is supported provided the client sets no run deadline (TimeoutMs);
// nothing else in the loop assumes WaitFor returns promptly, and no lock is held across it.
func TestParkedWaitForAcrossTurns(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{
		{"c1", "alpha", `{}`}, {"c2", "beta", `{}`}, {"c3", "gamma", `{}`},
	}}, "all parked calls answered")

	type parked struct {
		req   Request
		reply chan Answer
	}
	arrivals := make(chan parked, 3)

	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		// No TimeoutMs: a run deadline is the ONE thing that breaks parking.
		WaitFor: func(req Request) (Answer, error) {
			p := parked{req: req, reply: make(chan Answer, 1)}
			arrivals <- p         // hand the call to the "HTTP turn"
			return <-p.reply, nil // park until the client comes back
		},
	})

	// The "proxy": collect all three parked calls (this is the N it would emit in one
	// OpenAI response), then answer them all on the next "turn".
	done := make(chan RunResult, 1)
	go func() {
		res, err := c.Run(context.Background(), "go", spikeToolkit(t,
			relayTool("alpha"), relayTool("beta"), relayTool("gamma")))
		if err != nil {
			t.Errorf("parked run: %v", err)
		}
		done <- res
	}()

	var collected []parked
	for len(collected) < 3 {
		select {
		case p := <-arrivals:
			collected = append(collected, p)
		case <-time.After(5 * time.Second):
			t.Fatalf("only %d of 3 relay calls surfaced a WaitFor callback — parallel calls "+
				"do NOT each get their own callback", len(collected))
		}
	}
	// Each callback carried exactly its own call — proof they are per-tool, not coalesced.
	names := map[string]bool{}
	for _, p := range collected {
		calls := RelayCallsOf(&p.req)
		if len(calls) != 1 {
			t.Fatalf("an in-process callback carried %d calls, want exactly its own 1", len(calls))
		}
		names[calls[0].Name] = true
	}
	for _, want := range []string{"alpha", "beta", "gamma"} {
		if !names[want] {
			t.Fatalf("no WaitFor callback for %q", want)
		}
	}
	// Now the "next HTTP turn" returns the client's results.
	for _, p := range collected {
		calls := RelayCallsOf(&p.req)
		p.reply <- Answer{ID: p.req.ID, Ok: true, Data: map[string]any{
			RelayOutputKey: "out-" + calls[0].Name,
		}}
	}

	select {
	case res := <-done:
		if res.Status != "done" || res.Text != "all parked calls answered" {
			t.Fatalf("parked run result: status=%q text=%q", res.Status, res.Text)
		}
		if len(res.ToolCalls) != 3 {
			t.Fatalf("want 3 resolved calls, got %d", len(res.ToolCalls))
		}
		calls, results, _ := spikeMessageCounts(res.Messages)
		if calls != results {
			t.Fatalf("parked resume left an unbalanced transcript: %d vs %d", calls, results)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the run never resumed after the parked answers were delivered")
	}
}

// TestRunDeadlineBreaksParking pins what a run deadline actually does to a parked
// WaitFor — measured, because the intuitive answer is wrong. WaitFor is a plain blocking
// call with no ctx of its own, so the deadline does NOT interrupt the wait; the park runs
// to completion. The run then dies at the next context-aware step (the follow-up LLM
// call). Net effect for a proxy: TimeoutMs does not cap the park, but any park outliving
// it turns the whole run into an error AFTER the host has already done its work — the
// worst of both. So a parking host must leave TimeoutMs unset.
func TestRunDeadlineBreaksParking(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "alpha", `{}`}}}, "x")
	const park = 400 * time.Millisecond
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		TimeoutMs: 100, // deadline shorter than the park
		WaitFor: func(req Request) (Answer, error) {
			<-time.After(park)
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{RelayOutputKey: "late"}}, nil
		},
	})
	start := time.Now()
	_, err := c.Run(context.Background(), "go", spikeToolkit(t, relayTool("alpha")))
	elapsed := time.Since(start)

	if err == nil {
		t.Fatal("a park outliving TimeoutMs must not silently succeed")
	}
	// The wait was NOT cut short — proof the deadline does not interrupt WaitFor itself.
	if elapsed < park {
		t.Fatalf("the run aborted after %s, before the %s park finished — WaitFor now IS "+
			"deadline-interruptible; update the guidance to routsi", elapsed, park)
	}
	t.Logf("MEASURED: TimeoutMs=100ms did NOT interrupt a %s park (ran %s), but the run then "+
		"failed at the next context-aware step: %v", park, elapsed, err)
}

// TestToolkitNeedNotBeTheSameInstanceOnResume answers routsi's Q4: resume resolves relay
// tools BY NAME, so a proxy that rebuilds its toolkit per HTTP request from the client's
// declared tools can resume with a freshly constructed toolkit.
func TestToolkitNeedNotBeTheSameInstanceOnResume(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "lookup", `{}`}}}, "resumed on a new toolkit")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})

	halted, err := c.Run(context.Background(), "go", spikeToolkit(t, relayTool("lookup")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	// A DIFFERENT toolkit instance, same relay tool name — as a per-request proxy builds.
	fresh := spikeToolkit(t, relayTool("lookup"))
	resumed, err := c.RunWithAnswer(context.Background(), fresh, halted.Messages, *halted.Pending,
		RelayAnswer(halted.Pending.ID, []RelayResult{{ID: "c1", Output: "fresh-toolkit"}}))
	if err != nil {
		t.Fatalf("resume on a fresh toolkit: %v", err)
	}
	if resumed.Status != "done" {
		t.Fatalf("status = %q", resumed.Status)
	}
}
