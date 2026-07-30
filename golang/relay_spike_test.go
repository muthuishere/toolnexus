package toolnexus

// Spike 0002 (docs/spikes/0002-relay-mode-stress.md) — EXECUTABLE stress spike for
// ADR-0010 relay mode. Every test here runs the REAL agent loop against a mock LLM on
// UNMODIFIED library code: relay is prototyped purely as a §10 tool that returns
// Pending. Nothing in the library is changed by this file.
//
// Purpose: prove empirically (a) that relay works on the existing suspension
// primitive, (b) exactly which gaps are real, and (c) that declaring a relay tool does
// not perturb existing behavior. Delete or fold into the real suite when the change
// lands; until then these are the spike's evidence.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// ---- the relay prototype: a declaration-only tool, built only from public API ----

// spikeRelayTool is what ADR-0010's `RelayTool(name, desc, schema)` would return,
// hand-rolled from today's public surface: on first call it suspends carrying the
// call structurally; on the retry-with-answer it returns the caller's output.
func spikeRelayTool(name string) Tool {
	return Tool{
		Name:        name,
		Description: "relayed to the caller; never executed here",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      SourceCustom,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			// Retry-with-answer: the caller executed it; hand back their output.
			if tctx != nil && tctx.Answer != nil {
				a := tctx.Answer
				if !a.Ok {
					return ToolResult{Output: "relayed tool failed: " + a.Reason, IsError: true}, nil
				}
				out, _ := a.Data["output"].(string)
				isErr, _ := a.Data["isError"].(bool)
				return ToolResult{Output: out, IsError: isErr}, nil
			}
			// First call: suspend, carrying the call for the caller to execute.
			return Pending(Request{
				Kind:   "tool_call",
				Prompt: "execute " + name + " and return its output",
				Data:   map[string]any{"name": name, "input": args},
			}), nil
		},
	}
}

// spikeExecTool is an ordinary executing tool, used to prove relay does not disturb
// real tools sharing the same turn.
func spikeExecTool(name string, ran *bool, mu *sync.Mutex) Tool {
	return Tool{
		Name:        name,
		Description: "a real tool that really runs",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      SourceCustom,
		Execute: func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			mu.Lock()
			*ran = true
			mu.Unlock()
			return ToolResult{Output: "real-output"}, nil
		},
	}
}

// ---- mock LLMs ----

// spikeCall is one tool_call the mock LLM should emit.
type spikeCall struct{ id, name, args string }

// spikeScriptedLLM replays one scripted assistant turn per request, in order. A turn
// with no calls is a final text answer. It records every request body it received so a
// test can assert on what the loop actually sent back (the transcript the provider saw).
type spikeScriptedLLM struct {
	srv    *httptest.Server
	mu     sync.Mutex
	turn   int
	bodies []map[string]any
}

func newSpikeScriptedLLM(t *testing.T, turns [][]spikeCall, final string) *spikeScriptedLLM {
	t.Helper()
	m := &spikeScriptedLLM{}
	m.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		m.mu.Lock()
		m.bodies = append(m.bodies, body)
		i := m.turn
		m.turn++
		m.mu.Unlock()

		message := map[string]any{"role": "assistant"}
		if i < len(turns) {
			var tcs []any
			for _, c := range turns[i] {
				tcs = append(tcs, map[string]any{
					"id": c.id, "type": "function",
					"function": map[string]any{"name": c.name, "arguments": c.args},
				})
			}
			message["content"] = nil
			message["tool_calls"] = tcs
		} else {
			message["content"] = final
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"message": message}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
	t.Cleanup(m.srv.Close)
	return m
}

func (m *spikeScriptedLLM) requests() []map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]map[string]any, len(m.bodies))
	copy(out, m.bodies)
	return out
}

// spikeToolkit builds a builtins-off toolkit (the proxy posture) with the given tools.
func spikeToolkit(t *testing.T, tools ...Tool) *Toolkit {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: tools})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { tk.Close() })
	return tk
}

// spikeMessageCounts counts tool_use/tool_call blocks vs tool_result messages in a
// transcript, which is how the durable-halt transcript shape is asserted below.
// The OpenAI-style transcript uses {role:"assistant", tool_calls:[...]} and
// {role:"tool", tool_call_id:...} messages.
func spikeMessageCounts(messages []any) (calls, results int, resultIDs []string) {
	for _, m := range messages {
		mm, ok := m.(map[string]any)
		if !ok {
			continue
		}
		if tcs, ok := mm["tool_calls"].([]any); ok {
			calls += len(tcs)
		}
		if id, ok := mm["tool_call_id"].(string); ok {
			results++
			resultIDs = append(resultIDs, id)
		}
	}
	return
}

// ---- S1: relay works end-to-end on the in-process (WaitFor) path ----

// TestSpikeRelaySingleCallInProcess: the model calls a relay tool; the host resolves it
// out-of-band; the host's output reaches the model as a real tool_result and the run
// completes. This is the core claim of ADR-0010 — relay needs no new loop mode.
func TestSpikeRelaySingleCallInProcess(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{
		{{"c1", "lookup", `{"q":"weather"}`}},
	}, "it is sunny")

	var got Request
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			got = req
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": "sunny, 31C"}}, nil
		},
	})

	res, err := c.Run(context.Background(), "what is the weather", spikeToolkit(t, spikeRelayTool("lookup")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done", res.Status)
	}
	if res.Text != "it is sunny" {
		t.Fatalf("text = %q", res.Text)
	}
	// The caller saw the call structurally.
	if got.Kind != "tool_call" || got.Data["name"] != "lookup" {
		t.Fatalf("relay request did not carry the call: %+v", got)
	}
	if in, ok := got.Data["input"].(map[string]any); !ok || in["q"] != "weather" {
		t.Fatalf("relay request did not carry the input: %+v", got.Data["input"])
	}
	// The caller's output became the tool result the model saw.
	if len(res.ToolCalls) != 1 || res.ToolCalls[0].Output != "sunny, 31C" {
		t.Fatalf("caller output did not become the tool result: %+v", res.ToolCalls)
	}
	if res.ToolCalls[0].IsError {
		t.Fatal("a resolved relay call must not be an error")
	}
	// And it reached the provider on the next turn.
	reqs := llm.requests()
	if len(reqs) < 2 {
		t.Fatalf("expected a second provider turn, got %d", len(reqs))
	}
	raw, _ := json.Marshal(reqs[1]["messages"])
	if !strings.Contains(string(raw), "sunny, 31C") {
		t.Fatalf("the relayed output never reached the provider: %s", raw)
	}
}

// TestSpikeRelayErrorFromCaller: a relayed tool that FAILED at the caller reaches the
// model as an error tool_result — the loop continues, the model recovers.
func TestSpikeRelayErrorFromCaller(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{
		{{"c1", "lookup", `{}`}},
	}, "sorry, that failed")

	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": "boom: upstream 500", "isError": true}}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, spikeRelayTool("lookup")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done", res.Status)
	}
	if len(res.ToolCalls) != 1 || !res.ToolCalls[0].IsError {
		t.Fatalf("caller-side failure did not surface as an error tool result: %+v", res.ToolCalls)
	}
}

// ---- S2: parallel relay calls (fork F2) ----

// TestSpikeParallelRelayInProcess: with a WaitFor, THREE relay calls in one turn each
// resolve independently and none is lost (§10's documented inline behavior).
func TestSpikeParallelRelayInProcess(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{
		{"c1", "alpha", `{"n":1}`},
		{"c2", "beta", `{"n":2}`},
		{"c3", "gamma", `{"n":3}`},
	}}, "all three done")

	var mu sync.Mutex
	seen := map[string]bool{}
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			name, _ := req.Data["name"].(string)
			mu.Lock()
			seen[name] = true
			mu.Unlock()
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": "out-" + name}}, nil
		},
	})
	tk := spikeToolkit(t, spikeRelayTool("alpha"), spikeRelayTool("beta"), spikeRelayTool("gamma"))
	res, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done", res.Status)
	}
	if len(res.ToolCalls) != 3 {
		t.Fatalf("expected 3 relayed calls, got %d: %+v", len(res.ToolCalls), res.ToolCalls)
	}
	for _, n := range []string{"alpha", "beta", "gamma"} {
		if !seen[n] {
			t.Fatalf("relay call %q never surfaced to the caller", n)
		}
	}
	// Every tool_use has a matching tool_result — a replayable transcript.
	calls, results, _ := spikeMessageCounts(res.Messages)
	if calls != results {
		t.Fatalf("transcript unbalanced: %d tool_calls vs %d tool_results", calls, results)
	}
}

// TestSpikeParallelRelayDurableLosesTheRest documents fork F2 as a MEASURED fact: on
// the durable path (no WaitFor) three relay calls surface as ONE request, and the saved
// transcript is left unbalanced (3 tool_calls, 1 tool_result) — which Anthropic rejects
// on replay. This is why ADR-0010 chose F2-a (all N calls inside one Request) plus
// F1-a (a resume that fills every outstanding slot).
//
// It asserts the CURRENT behavior, so it will need updating when the change lands —
// that is the point: it pins the baseline the change must move.
func TestSpikeParallelRelayDurableLosesTheRest(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{
		{"c1", "alpha", `{"n":1}`},
		{"c2", "beta", `{"n":2}`},
		{"c3", "gamma", `{"n":3}`},
	}}, "unreachable")

	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"}) // no WaitFor
	tk := spikeToolkit(t, spikeRelayTool("alpha"), spikeRelayTool("beta"), spikeRelayTool("gamma"))
	res, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "pending" || res.Pending == nil {
		t.Fatalf("status = %q, pending = %v; want a durable halt", res.Status, res.Pending)
	}
	// GAP F2: only the first call is visible. A conforming OpenAI client needs all 3.
	if n, _ := res.Pending.Data["name"].(string); n != "alpha" {
		t.Fatalf("pending is %q, want the first-in-order call 'alpha'", n)
	}
	if _, hasAll := res.Pending.Data["calls"]; hasAll {
		t.Fatal("data.calls already exists — F2-a may already be implemented; update this spike")
	}
	// GAP F1 + the ADR's "Observation": the transcript is unbalanced and the one
	// tool_result present is a placeholder ERROR, not the caller's real output.
	calls, results, ids := spikeMessageCounts(res.Messages)
	if calls != 3 {
		t.Fatalf("expected 3 tool_calls in the transcript, got %d", calls)
	}
	if results != 1 {
		t.Fatalf("expected exactly 1 tool_result (the documented first-in-order rule), got %d (ids %v)", results, ids)
	}
	t.Logf("MEASURED BASELINE — durable relay halt: %d tool_calls vs %d tool_result (ids %v); "+
		"unbalanced transcript is not replayable to Anthropic. Pending carries only %q.",
		calls, results, ids, res.Pending.Data["name"])
	// The placeholder occupying the slot is an error, so the caller cannot supply truth.
	var placeholderIsError bool
	for _, tc := range res.ToolCalls {
		if tc.IsError {
			placeholderIsError = true
		}
	}
	if !placeholderIsError {
		t.Fatal("expected the halted call's placeholder result to be an error placeholder")
	}
}

// ---- S3: no regression — relay must not disturb anything else ----

// TestSpikeRelayMixedWithRealToolInSameTurn: a turn with one relay call and one REAL
// executing tool. The real tool must still execute. This is the highest-risk
// interaction and it must hold both with and without a WaitFor.
func TestSpikeRelayMixedWithRealToolInSameTurn(t *testing.T) {
	for _, withWaitFor := range []bool{true, false} {
		name := "durable"
		if withWaitFor {
			name = "inProcess"
		}
		t.Run(name, func(t *testing.T) {
			llm := newSpikeScriptedLLM(t, [][]spikeCall{{
				{"c1", "realtool", `{}`}, // real tool FIRST so the halt cannot pre-empt it
				{"c2", "relayed", `{}`},
			}}, "done")

			var ran bool
			var mu sync.Mutex
			opts := ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"}
			if withWaitFor {
				opts.WaitFor = func(req Request) (Answer, error) {
					return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": "relayed-out"}}, nil
				}
			}
			c := CreateClient(opts)
			tk := spikeToolkit(t, spikeExecTool("realtool", &ran, &mu), spikeRelayTool("relayed"))
			if _, err := c.Run(context.Background(), "go", tk); err != nil {
				t.Fatalf("run: %v", err)
			}
			mu.Lock()
			defer mu.Unlock()
			if !ran {
				t.Fatal("the REAL tool did not execute in a turn shared with a relay call")
			}
		})
	}
}

// TestSpikeDeclaringRelayToolIsInert: declaring a relay tool the model never calls must
// change nothing — same text, same turn count, zero tool calls. This is the
// byte-identical-when-absent rule, measured.
func TestSpikeDeclaringRelayToolIsInert(t *testing.T) {
	run := func(tools ...Tool) RunResult {
		llm := newSpikeScriptedLLM(t, nil, "plain answer")
		c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
		res, err := c.Run(context.Background(), "hello", spikeToolkit(t, tools...))
		if err != nil {
			t.Fatalf("run: %v", err)
		}
		return res
	}
	base := run()
	with := run(spikeRelayTool("never_called"))
	if base.Text != with.Text || base.Status != with.Status || base.Turns != with.Turns {
		t.Fatalf("declaring an uncalled relay tool perturbed the run:\n base=%+v\n with=%+v", base, with)
	}
	if len(with.ToolCalls) != 0 {
		t.Fatalf("an uncalled relay tool produced tool calls: %+v", with.ToolCalls)
	}
}

// TestSpikeRelayIsNotAToolError: a relay suspension must not be counted as a tool
// failure — §10's shipped rule, which relay inherits (ADR-0010 decision 3). Asserted
// via the observability metrics the loop emits.
func TestSpikeRelayIsNotAToolError(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "relayed", `{}`}}}, "done")
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": "ok"}}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, spikeRelayTool("relayed")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	for _, tc := range res.ToolCalls {
		if tc.IsError {
			t.Fatalf("a resolved relay call was recorded as an error: %+v", tc)
		}
	}
}

// ---- S4: multi-round relay ----

// TestSpikeMultiRoundRelay: relay → result → the model relays AGAIN in a later turn.
// Proves resolvePending's "never loop forever" guard is per-call and does not lock a
// relay tool out of a second, legitimate round.
func TestSpikeMultiRoundRelay(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{
		{{"c1", "step", `{"i":1}`}},
		{{"c2", "step", `{"i":2}`}},
		{{"c3", "step", `{"i":3}`}},
	}, "three rounds done")

	var mu sync.Mutex
	var n int
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			mu.Lock()
			n++
			i := n
			mu.Unlock()
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": fmt.Sprintf("round-%d", i)}}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, spikeRelayTool("step")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done", res.Status)
	}
	if n != 3 {
		t.Fatalf("relay resolved %d times, want 3 — a later round was locked out", n)
	}
	if len(res.ToolCalls) != 3 {
		t.Fatalf("expected 3 relayed calls across rounds, got %d", len(res.ToolCalls))
	}
	for i, tc := range res.ToolCalls {
		if tc.IsError {
			t.Fatalf("round %d was an error: %+v", i+1, tc)
		}
	}
}

// TestSpikeRelayDeclinedByCaller: the caller refuses to execute (Ok:false). The loop
// must continue with an error result and let the model decide — not abort the run.
func TestSpikeRelayDeclinedByCaller(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{{{"c1", "relayed", `{}`}}}, "understood")
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: false, Reason: "declined"}, nil
		},
	})
	res, err := c.Run(context.Background(), "go", spikeToolkit(t, spikeRelayTool("relayed")))
	if err != nil {
		t.Fatalf("run must not fail when the caller declines: %v", err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done (the model recovers)", res.Status)
	}
	if len(res.ToolCalls) != 1 || !res.ToolCalls[0].IsError {
		t.Fatalf("a declined relay should feed back an error result: %+v", res.ToolCalls)
	}
}

// ---- S5: memory round-trip (routsi ADR-010 item 4) ----

// TestSpikeRelayRoundTripsThroughConversationStore: relayed tool_call/tool_result pairs
// survive a ConversationStore round trip with ids intact and are replayed to the
// provider on the next Ask. This is the claim that closes routsi ADR-010's open
// question, asserted rather than asserted-by-reading.
func TestSpikeRelayRoundTripsThroughConversationStore(t *testing.T) {
	llm := newSpikeScriptedLLM(t, [][]spikeCall{
		{{"c1", "lookup", `{"q":"x"}`}},
	}, "first answer")
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": "relayed-value"}}, nil
		},
	})
	tk := spikeToolkit(t, spikeRelayTool("lookup"))

	if _, err := c.Ask(context.Background(), "first", tk, "conv-1"); err != nil {
		t.Fatalf("first ask: %v", err)
	}
	// Second turn on the same conversation id — history must replay structurally.
	if _, err := c.Ask(context.Background(), "second", tk, "conv-1"); err != nil {
		t.Fatalf("second ask: %v", err)
	}

	reqs := llm.requests()
	last, _ := json.Marshal(reqs[len(reqs)-1]["messages"])
	s := string(last)
	for _, want := range []string{`"tool_calls"`, `"c1"`, "relayed-value", `"lookup"`} {
		if !strings.Contains(s, want) {
			t.Fatalf("history did not round-trip %s structurally:\n%s", want, s)
		}
	}
}

// ---- S11: the Anthropic-style loop (closes a spike-0002 caveat) ----

// spikeAnthropicLLM replays scripted Anthropic-native turns: content blocks of
// type tool_use/text on POST /v1/messages. It records request bodies like its
// OpenAI twin so assertions can inspect the transcript the provider received.
type spikeAnthropicLLM struct {
	srv    *httptest.Server
	mu     sync.Mutex
	turn   int
	bodies []map[string]any
}

func newSpikeAnthropicLLM(t *testing.T, turns [][]spikeCall, final string) *spikeAnthropicLLM {
	t.Helper()
	m := &spikeAnthropicLLM{}
	m.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		m.mu.Lock()
		m.bodies = append(m.bodies, body)
		i := m.turn
		m.turn++
		m.mu.Unlock()

		var content []any
		stop := "end_turn"
		if i < len(turns) {
			stop = "tool_use"
			for _, c := range turns[i] {
				var in map[string]any
				_ = json.Unmarshal([]byte(c.args), &in)
				content = append(content, map[string]any{
					"type": "tool_use", "id": c.id, "name": c.name, "input": in,
				})
			}
		} else {
			content = append(content, map[string]any{"type": "text", "text": final})
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"content":     content,
			"stop_reason": stop,
			"usage":       map[string]any{"input_tokens": 1, "output_tokens": 1},
		})
	}))
	t.Cleanup(m.srv.Close)
	return m
}

func (m *spikeAnthropicLLM) requests() []map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]map[string]any, len(m.bodies))
	copy(out, m.bodies)
	return out
}

// TestSpikeRelayAnthropicStyle: relay works identically on the Anthropic-native loop —
// the path routsi actually translates TO. Closes a stated caveat of spike 0002.
func TestSpikeRelayAnthropicStyle(t *testing.T) {
	llm := newSpikeAnthropicLLM(t, [][]spikeCall{
		{{"tu_1", "lookup", `{"q":"weather"}`}},
	}, "it is sunny")

	var got Request
	c := CreateClient(ClientOptions{
		BaseURL: llm.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			got = req
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": "sunny, 31C"}}, nil
		},
	})
	res, err := c.Run(context.Background(), "weather?", spikeToolkit(t, spikeRelayTool("lookup")))
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "done" || res.Text != "it is sunny" {
		t.Fatalf("status=%q text=%q", res.Status, res.Text)
	}
	if got.Kind != "tool_call" || got.Data["name"] != "lookup" {
		t.Fatalf("relay request did not carry the call: %+v", got)
	}
	if len(res.ToolCalls) != 1 || res.ToolCalls[0].Output != "sunny, 31C" {
		t.Fatalf("caller output did not become the tool result: %+v", res.ToolCalls)
	}
	// The tool_result block must reference the ORIGINAL tool_use id, natively.
	reqs := llm.requests()
	raw, _ := json.Marshal(reqs[len(reqs)-1]["messages"])
	for _, want := range []string{`"tool_result"`, `"tu_1"`, "sunny, 31C"} {
		if !strings.Contains(string(raw), want) {
			t.Fatalf("anthropic transcript missing %s:\n%s", want, raw)
		}
	}
}

// TestSpikeRelayAnthropicDurableBaseline: the same measured F1/F2 baseline on the
// Anthropic loop — 3 tool_use blocks, 1 tool_result. Confirms the gap is in the shared
// suspension path, not an OpenAI-transcript artifact.
func TestSpikeRelayAnthropicDurableBaseline(t *testing.T) {
	llm := newSpikeAnthropicLLM(t, [][]spikeCall{{
		{"tu_1", "alpha", `{}`}, {"tu_2", "beta", `{}`}, {"tu_3", "gamma", `{}`},
	}}, "unreachable")
	c := CreateClient(ClientOptions{BaseURL: llm.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, spikeRelayTool("alpha"), spikeRelayTool("beta"), spikeRelayTool("gamma"))
	res, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Status != "pending" || res.Pending == nil {
		t.Fatalf("status=%q pending=%v; want durable halt", res.Status, res.Pending)
	}
	if n, _ := res.Pending.Data["name"].(string); n != "alpha" {
		t.Fatalf("pending is %q, want first-in-order 'alpha'", n)
	}
	// Count native blocks in the Anthropic transcript.
	uses, results := 0, 0
	for _, m := range res.Messages {
		mm, ok := m.(map[string]any)
		if !ok {
			continue
		}
		blocks, ok := mm["content"].([]any)
		if !ok {
			continue
		}
		for _, b := range blocks {
			bb, ok := b.(map[string]any)
			if !ok {
				continue
			}
			switch bb["type"] {
			case "tool_use":
				uses++
			case "tool_result":
				results++
			}
		}
	}
	if uses != 3 {
		t.Fatalf("expected 3 tool_use blocks, got %d", uses)
	}
	if results != 1 {
		t.Fatalf("expected 1 tool_result (first-in-order rule), got %d", results)
	}
	t.Logf("MEASURED BASELINE (anthropic loop) — %d tool_use vs %d tool_result: the same "+
		"unbalanced, non-replayable transcript as the OpenAI loop. Gap is in the shared "+
		"suspension path.", uses, results)
}

// ---- S12: the streaming loop (closes the other spike-0002 caveat) ----

// spikeStreamLLM streams one assistant turn of tool_calls over SSE, then usage, then
// [DONE]. Second and later requests stream a plain text answer.
func spikeStreamLLM(t *testing.T, calls []spikeCall, final string) *httptest.Server {
	t.Helper()
	var mu sync.Mutex
	var turn int
	write := func(w http.ResponseWriter, v any) {
		b, _ := json.Marshal(v)
		_, _ = w.Write([]byte("data: " + string(b) + "\n\n"))
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		i := turn
		turn++
		mu.Unlock()
		w.Header().Set("content-type", "text/event-stream")
		if i == 0 {
			var tcs []any
			for idx, c := range calls {
				tcs = append(tcs, map[string]any{
					"index": idx, "id": c.id, "type": "function",
					"function": map[string]any{"name": c.name, "arguments": c.args},
				})
			}
			write(w, map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": tcs}}}})
		} else {
			write(w, map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": final}}}})
		}
		write(w, map[string]any{
			"choices": []any{map[string]any{"delta": map[string]any{}}},
			"usage":   map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
		_, _ = w.Write([]byte("data: [DONE]\n\n"))
	}))
	t.Cleanup(srv.Close)
	return srv
}

// TestSpikeRelayStreamingInProcess: relay resolves inline on the STREAMING loop too, and
// the loop emits the `pending` stream event so a channel host can push the call in real
// time. Closes the second stated caveat of spike 0002.
func TestSpikeRelayStreamingInProcess(t *testing.T) {
	srv := spikeStreamLLM(t, []spikeCall{{"c1", "lookup", `{"q":"x"}`}}, "streamed answer")
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(req Request) (Answer, error) {
			return Answer{ID: req.ID, Ok: true, Data: map[string]any{"output": "streamed-relay-out"}}, nil
		},
	})
	ch, err := c.Stream(context.Background(), "go", spikeToolkit(t, spikeRelayTool("lookup")))
	if err != nil {
		t.Fatalf("stream: %v", err)
	}
	var sawPending bool
	var res *RunResult
	var text string
	for ev := range ch {
		switch ev.Type {
		case "pending":
			sawPending = true
			if ev.Request == nil || ev.Request.Kind != "tool_call" {
				t.Fatalf("pending event did not carry the relay request: %+v", ev.Request)
			}
		case "text":
			text += ev.Delta
		case "done":
			res = ev.Result
		case "error":
			t.Fatalf("stream error: %v", ev.Err)
		}
	}
	if !sawPending {
		t.Fatal("the streaming loop never emitted a `pending` event for the relay call")
	}
	if res == nil || res.Status != "done" {
		t.Fatalf("stream result = %+v, want status done", res)
	}
	if text != "streamed answer" {
		t.Fatalf("streamed text = %q", text)
	}
	if len(res.ToolCalls) != 1 || res.ToolCalls[0].Output != "streamed-relay-out" {
		t.Fatalf("caller output did not become the tool result: %+v", res.ToolCalls)
	}
}

// TestSpikeRelayStreamingDurableBaseline: the streaming durable halt shows the SAME
// measured baseline (3 calls in, 1 result recorded, first-in-order surfaced), so F1-a/F2-a
// must be implemented on the streaming path too — not only the non-streaming one.
func TestSpikeRelayStreamingDurableBaseline(t *testing.T) {
	srv := spikeStreamLLM(t, []spikeCall{
		{"c1", "alpha", `{}`}, {"c2", "beta", `{}`}, {"c3", "gamma", `{}`},
	}, "unreachable")
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, spikeRelayTool("alpha"), spikeRelayTool("beta"), spikeRelayTool("gamma"))
	ch, err := c.Stream(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("stream: %v", err)
	}
	var res *RunResult
	for ev := range ch {
		if ev.Type == "done" {
			res = ev.Result
		}
		if ev.Type == "error" {
			t.Fatalf("stream error: %v", ev.Err)
		}
	}
	if res == nil || res.Status != "pending" || res.Pending == nil {
		t.Fatalf("stream result = %+v, want status pending", res)
	}
	if n, _ := res.Pending.Data["name"].(string); n != "alpha" {
		t.Fatalf("pending is %q, want first-in-order 'alpha'", n)
	}
	calls, results, ids := spikeMessageCounts(res.Messages)
	if calls != 3 || results != 1 {
		t.Fatalf("streaming baseline drifted: %d tool_calls vs %d tool_results (ids %v)", calls, results, ids)
	}
	t.Logf("MEASURED BASELINE (streaming loop) — %d tool_calls vs %d tool_result (ids %v): "+
		"same gap; F1-a/F2-a must land on the streaming path too.", calls, results, ids)
}
