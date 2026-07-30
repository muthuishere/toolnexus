package toolnexus

// Single-turn translation tests (§11, ADR-0011). The driving case is a stateless proxy:
// OpenAI messages+tools in, one provider call, OpenAI-shaped result out, no loop and no
// tool execution. The multi-turn cases are the ones a text-flattening translator gets
// wrong, which is the bug this path exists to remove.

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// capturingUpstream records the request body it received and replies with a canned
// response, so tests assert on what the PROVIDER was actually sent.
type capturingUpstream struct {
	srv  *httptest.Server
	mu   sync.Mutex
	body map[string]any
}

func newCapturingUpstream(t *testing.T, reply map[string]any) *capturingUpstream {
	t.Helper()
	u := &capturingUpstream{}
	u.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		u.body = b
		u.mu.Unlock()
		_ = json.NewEncoder(w).Encode(reply)
	}))
	t.Cleanup(u.srv.Close)
	return u
}

func (u *capturingUpstream) sent() map[string]any {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.body
}

func (u *capturingUpstream) sentJSON() string {
	b, _ := json.Marshal(u.sent())
	return string(b)
}

// openAITools is the OpenAI `tools` array a client sends, verbatim.
func openAITools() []any {
	return []any{map[string]any{
		"type": "function",
		"function": map[string]any{
			"name":        "get_weather",
			"description": "Get the weather",
			"parameters": map[string]any{
				"type":       "object",
				"properties": map[string]any{"city": map[string]any{"type": "string"}},
				"required":   []any{"city"},
			},
		},
	}}
}

// ---- Anthropic upstream: the real translation ----

// TestTranslateToolCallToOpenAIShape: an Anthropic tool_use turn comes back as OpenAI
// tool_calls with arguments as a JSON STRING and finish_reason "tool_calls".
func TestTranslateToolCallToOpenAIShape(t *testing.T) {
	up := newCapturingUpstream(t, map[string]any{
		"content": []any{map[string]any{
			"type": "tool_use", "id": "toolu_1", "name": "get_weather",
			"input": map[string]any{"city": "Chennai"},
		}},
		"stop_reason": "tool_use",
		"usage":       map[string]any{"input_tokens": 10, "output_tokens": 5},
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})

	res, err := c.Translate(context.Background(), TranslateRequest{
		Messages: []any{map[string]any{"role": "user", "content": "weather in Chennai?"}},
		Tools:    openAITools(),
	})
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	if res.FinishReason != "tool_calls" {
		t.Fatalf("finishReason = %q, want tool_calls", res.FinishReason)
	}
	if len(res.ToolCalls) != 1 {
		t.Fatalf("want 1 tool call, got %d", len(res.ToolCalls))
	}
	tc := res.ToolCalls[0]
	if tc.ID != "toolu_1" || tc.Name != "get_weather" {
		t.Fatalf("tool call = %+v", tc)
	}
	// Arguments must be a JSON STRING (the OpenAI wire shape), not an object.
	var args map[string]any
	if err := json.Unmarshal([]byte(tc.Arguments), &args); err != nil {
		t.Fatalf("arguments is not a JSON string: %q (%v)", tc.Arguments, err)
	}
	if args["city"] != "Chennai" {
		t.Fatalf("arguments = %q", tc.Arguments)
	}
	if res.Usage.TotalTokens == 0 {
		t.Fatalf("usage not reported: %+v", res.Usage)
	}
	// The declaration reached the provider in Anthropic's native shape.
	sent := up.sentJSON()
	for _, want := range []string{`"input_schema"`, "get_weather", "Get the weather"} {
		if !strings.Contains(sent, want) {
			t.Fatalf("provider did not receive %s: %s", want, sent)
		}
	}
	if strings.Contains(sent, `"parameters"`) {
		t.Fatalf("OpenAI-shaped 'parameters' leaked to the Anthropic upstream: %s", sent)
	}
}

// TestTranslateMultiTurnToolResultSurvives is THE case a text-flattening translator gets
// wrong: an assistant turn carrying tool_calls plus a tool-role result carrying
// tool_call_id must reach Anthropic as tool_use / tool_result blocks with the id intact.
func TestTranslateMultiTurnToolResultSurvives(t *testing.T) {
	up := newCapturingUpstream(t, map[string]any{
		"content":     []any{map[string]any{"type": "text", "text": "It is 31C in Chennai."}},
		"stop_reason": "end_turn",
		"usage":       map[string]any{"input_tokens": 20, "output_tokens": 8},
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})

	// Exactly what a conforming OpenAI client resends on turn 2.
	res, err := c.Translate(context.Background(), TranslateRequest{
		Tools: openAITools(),
		Messages: []any{
			map[string]any{"role": "system", "content": "Be terse."},
			map[string]any{"role": "user", "content": "weather in Chennai?"},
			map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
				map[string]any{"id": "call_abc", "type": "function", "function": map[string]any{
					"name": "get_weather", "arguments": `{"city":"Chennai"}`,
				}},
			}},
			map[string]any{"role": "tool", "tool_call_id": "call_abc", "content": "31C, clear"},
		},
	})
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	if res.FinishReason != "stop" || res.Text != "It is 31C in Chennai." {
		t.Fatalf("result = %+v", res)
	}

	sent := up.sent()
	raw, _ := json.Marshal(sent)
	// System hoisted out of messages into Anthropic's separate field.
	if sent["system"] != "Be terse." {
		t.Fatalf("system not hoisted: %s", raw)
	}
	// Structure preserved, not flattened to text.
	for _, want := range []string{`"tool_use"`, `"tool_result"`, "call_abc", "31C, clear"} {
		if !strings.Contains(string(raw), want) {
			t.Fatalf("multi-turn structure lost %s:\n%s", want, raw)
		}
	}
	// And the tool_use's input is an OBJECT upstream, parsed back from the JSON string.
	msgs, _ := sent["messages"].([]any)
	var foundInput bool
	for _, m := range msgs {
		mm, _ := m.(map[string]any)
		blocks, _ := mm["content"].([]any)
		for _, b := range blocks {
			bb, _ := b.(map[string]any)
			if bb["type"] == "tool_use" {
				in, ok := bb["input"].(map[string]any)
				if !ok || in["city"] != "Chennai" {
					t.Fatalf("tool_use input not re-parsed to an object: %+v", bb["input"])
				}
				foundInput = true
			}
		}
	}
	if !foundInput {
		t.Fatalf("no tool_use block reached the provider:\n%s", raw)
	}
}

// TestTranslateConsecutiveToolResultsMergeIntoOneUserTurn: Anthropic wants ONE user
// message carrying all of the preceding turn's tool_result blocks.
func TestTranslateConsecutiveToolResultsMergeIntoOneUserTurn(t *testing.T) {
	up := newCapturingUpstream(t, map[string]any{
		"content": []any{map[string]any{"type": "text", "text": "done"}}, "stop_reason": "end_turn",
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
	_, err := c.Translate(context.Background(), TranslateRequest{
		Messages: []any{
			map[string]any{"role": "user", "content": "do three things"},
			map[string]any{"role": "assistant", "tool_calls": []any{
				map[string]any{"id": "a", "function": map[string]any{"name": "f", "arguments": "{}"}},
				map[string]any{"id": "b", "function": map[string]any{"name": "f", "arguments": "{}"}},
				map[string]any{"id": "c", "function": map[string]any{"name": "f", "arguments": "{}"}},
			}},
			map[string]any{"role": "tool", "tool_call_id": "a", "content": "ra"},
			map[string]any{"role": "tool", "tool_call_id": "b", "content": "rb"},
			map[string]any{"role": "tool", "tool_call_id": "c", "content": "rc"},
		},
	})
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	msgs, _ := up.sent()["messages"].([]any)
	// Find the user turn holding tool_result blocks; it must hold all three.
	var resultTurns, blocksInTurn int
	for _, m := range msgs {
		mm, _ := m.(map[string]any)
		blocks, ok := mm["content"].([]any)
		if !ok {
			continue
		}
		n := 0
		for _, b := range blocks {
			if bb, ok := b.(map[string]any); ok && bb["type"] == "tool_result" {
				n++
			}
		}
		if n > 0 {
			resultTurns++
			blocksInTurn = n
		}
	}
	if resultTurns != 1 {
		t.Fatalf("tool results spread over %d user turns, want exactly 1", resultTurns)
	}
	if blocksInTurn != 3 {
		t.Fatalf("merged user turn carries %d tool_result blocks, want 3", blocksInTurn)
	}
	// All three tool_use blocks must be in the single assistant turn.
	uses := strings.Count(up.sentJSON(), `"tool_use"`)
	if uses != 3 {
		t.Fatalf("want 3 tool_use blocks upstream, got %d", uses)
	}
}

// TestTranslateParallelToolCallsAllReturned: N tool_use blocks come back as N tool_calls —
// no truncation, which is the whole point for a conforming OpenAI client.
func TestTranslateParallelToolCallsAllReturned(t *testing.T) {
	up := newCapturingUpstream(t, map[string]any{
		"content": []any{
			map[string]any{"type": "text", "text": "calling three"},
			map[string]any{"type": "tool_use", "id": "t1", "name": "alpha", "input": map[string]any{"n": 1}},
			map[string]any{"type": "tool_use", "id": "t2", "name": "beta", "input": map[string]any{"n": 2}},
			map[string]any{"type": "tool_use", "id": "t3", "name": "gamma", "input": map[string]any{"n": 3}},
		},
		"stop_reason": "tool_use",
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
	res, err := c.Translate(context.Background(), TranslateRequest{
		Messages: []any{map[string]any{"role": "user", "content": "go"}},
		Tools:    openAITools(),
	})
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	if len(res.ToolCalls) != 3 {
		t.Fatalf("want 3 tool calls, got %d: %+v", len(res.ToolCalls), res.ToolCalls)
	}
	for i, want := range []string{"alpha", "beta", "gamma"} {
		if res.ToolCalls[i].Name != want {
			t.Fatalf("call %d = %q, want %q (provider order)", i, res.ToolCalls[i].Name, want)
		}
	}
	if res.Text != "calling three" {
		t.Fatalf("text alongside tool calls lost: %q", res.Text)
	}
	if res.FinishReason != "tool_calls" {
		t.Fatalf("finishReason = %q", res.FinishReason)
	}
	// The envelope helper renders the OpenAI array a proxy needs.
	arr := res.ToolCallsJSON()
	if len(arr) != 3 {
		t.Fatalf("ToolCallsJSON returned %d entries", len(arr))
	}
	first, _ := arr[0].(map[string]any)
	if first["type"] != "function" {
		t.Fatalf("ToolCallsJSON entry is not an OpenAI function call: %+v", first)
	}
}

// TestTranslateExecutesNothingAndKeepsNoState is the defining property: no toolkit is
// involved, so there is nothing to execute and nothing to remember.
func TestTranslateExecutesNothingAndKeepsNoState(t *testing.T) {
	var calls int
	var mu sync.Mutex
	up := newCapturingUpstream(t, map[string]any{
		"content":     []any{map[string]any{"type": "tool_use", "id": "t1", "name": "danger", "input": map[string]any{}}},
		"stop_reason": "tool_use",
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})

	// A tool by the same name exists in a toolkit the translator is never given.
	tk := spikeToolkit(t, Tool{
		Name: "danger", Description: "must not run", Source: SourceCustom,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
			mu.Lock()
			calls++
			mu.Unlock()
			return ToolResult{Output: "RAN"}, nil
		},
	})
	_ = tk // deliberately unused: Translate takes no toolkit

	for i := 0; i < 3; i++ {
		res, err := c.Translate(context.Background(), TranslateRequest{
			Messages: []any{map[string]any{"role": "user", "content": "go"}},
			Tools:    openAITools(),
		})
		if err != nil {
			t.Fatalf("translate %d: %v", i, err)
		}
		// Every call is self-contained: same input ⇒ same output, no accumulation.
		if len(res.ToolCalls) != 1 || res.ToolCalls[0].Name != "danger" {
			t.Fatalf("call %d: %+v", i, res.ToolCalls)
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if calls != 0 {
		t.Fatalf("Translate executed a tool %d times — it must NEVER execute anything", calls)
	}
	// No history accumulated upstream across the three independent calls.
	msgs, _ := up.sent()["messages"].([]any)
	if len(msgs) != 1 {
		t.Fatalf("state leaked between Translate calls: upstream saw %d messages on the last call", len(msgs))
	}
}

// TestTranslateToolChoiceMapping covers the OpenAI → Anthropic tool_choice shapes.
func TestTranslateToolChoiceMapping(t *testing.T) {
	cases := []struct {
		in   any
		want string // "" ⇒ tool_choice must be absent
	}{
		{nil, ""},
		{"auto", ""},
		{"required", `"type":"any"`},
		{"none", `"type":"none"`},
		{map[string]any{"type": "function", "function": map[string]any{"name": "get_weather"}}, `"name":"get_weather"`},
	}
	for _, tc := range cases {
		up := newCapturingUpstream(t, map[string]any{
			"content": []any{map[string]any{"type": "text", "text": "ok"}}, "stop_reason": "end_turn",
		})
		c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
		if _, err := c.Translate(context.Background(), TranslateRequest{
			Messages: []any{map[string]any{"role": "user", "content": "go"}},
			Tools:    openAITools(), ToolChoice: tc.in,
		}); err != nil {
			t.Fatalf("translate %v: %v", tc.in, err)
		}
		sent := up.sentJSON()
		_, present := up.sent()["tool_choice"]
		if tc.want == "" {
			if present {
				t.Fatalf("tool_choice %v should be omitted, got: %s", tc.in, sent)
			}
			continue
		}
		if !present || !strings.Contains(strings.ReplaceAll(sent, " ", ""), tc.want) {
			t.Fatalf("tool_choice %v did not map to %s: %s", tc.in, tc.want, sent)
		}
	}
}

// TestTranslateFinishReasonMapping: provider stop reasons map onto OpenAI finish reasons,
// and tool calls always win.
func TestTranslateFinishReasonMapping(t *testing.T) {
	for _, tc := range []struct{ stop, want string }{
		{"end_turn", "stop"},
		{"max_tokens", "length"},
		{"refusal", "content_filter"},
		{"stop_sequence", "stop"},
	} {
		up := newCapturingUpstream(t, map[string]any{
			"content": []any{map[string]any{"type": "text", "text": "x"}}, "stop_reason": tc.stop,
		})
		c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
		res, err := c.Translate(context.Background(), TranslateRequest{
			Messages: []any{map[string]any{"role": "user", "content": "go"}},
		})
		if err != nil {
			t.Fatalf("translate: %v", err)
		}
		if res.FinishReason != tc.want {
			t.Fatalf("stop_reason %q → %q, want %q", tc.stop, res.FinishReason, tc.want)
		}
	}
}

// ---- OpenAI upstream: near-passthrough ----

func TestTranslateOpenAIUpstreamPassesToolsThrough(t *testing.T) {
	up := newCapturingUpstream(t, map[string]any{
		"choices": []any{map[string]any{
			"message": map[string]any{"content": "", "tool_calls": []any{
				map[string]any{"id": "call_1", "type": "function", "function": map[string]any{
					"name": "get_weather", "arguments": `{"city":"Madurai"}`,
				}},
			}},
			"finish_reason": "tool_calls",
		}},
		"usage": map[string]any{"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7},
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k"})
	res, err := c.Translate(context.Background(), TranslateRequest{
		Messages: []any{map[string]any{"role": "user", "content": "weather?"}},
		Tools:    openAITools(),
	})
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	if res.FinishReason != "tool_calls" || len(res.ToolCalls) != 1 {
		t.Fatalf("result = %+v", res)
	}
	if res.ToolCalls[0].Arguments != `{"city":"Madurai"}` {
		t.Fatalf("arguments not passed through byte-for-byte: %q", res.ToolCalls[0].Arguments)
	}
	if res.Usage.TotalTokens != 7 {
		t.Fatalf("usage = %+v", res.Usage)
	}
	// OpenAI-shaped tools go upstream unchanged.
	if !strings.Contains(up.sentJSON(), `"parameters"`) {
		t.Fatalf("OpenAI tools were altered on an OpenAI upstream: %s", up.sentJSON())
	}
}

// TestTranslateAcceptsStructMessages: entries need not be maps — any JSON-marshalable
// value works, so a caller can hand over its own typed messages.
func TestTranslateAcceptsStructMessages(t *testing.T) {
	type msg struct {
		Role    string `json:"role"`
		Content string `json:"content"`
	}
	up := newCapturingUpstream(t, map[string]any{
		"content": []any{map[string]any{"type": "text", "text": "hi"}}, "stop_reason": "end_turn",
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
	res, err := c.Translate(context.Background(), TranslateRequest{
		Messages: []any{msg{Role: "user", Content: "hello"}},
	})
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	if res.Text != "hi" {
		t.Fatalf("text = %q", res.Text)
	}
	if !strings.Contains(up.sentJSON(), "hello") {
		t.Fatalf("struct message did not reach the provider: %s", up.sentJSON())
	}
}

// TestTranslateContentPartsArrayFlattens covers the OpenAI content-parts form.
func TestTranslateContentPartsArrayFlattens(t *testing.T) {
	up := newCapturingUpstream(t, map[string]any{
		"content": []any{map[string]any{"type": "text", "text": "ok"}}, "stop_reason": "end_turn",
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
	if _, err := c.Translate(context.Background(), TranslateRequest{
		Messages: []any{map[string]any{"role": "user", "content": []any{
			map[string]any{"type": "text", "text": "part one "},
			map[string]any{"type": "text", "text": "part two"},
		}}},
	}); err != nil {
		t.Fatalf("translate: %v", err)
	}
	if !strings.Contains(up.sentJSON(), "part one part two") {
		t.Fatalf("content parts not flattened: %s", up.sentJSON())
	}
}

// ---- generality: Translate serves toolnexus's OWN users, not only OpenAI-JSON proxies ----

// TestTranslateDeclaresAToolkitWithoutExecutingIt is the generality case in test form: a
// user with a real toolkit (MCP tools, skills, native funcs) can declare it to a provider
// and get the model's tool CALLS back to dispatch themselves — the inbound half of the
// adapters, which previously had no counterpart. Nothing in the toolkit runs.
func TestTranslateDeclaresAToolkitWithoutExecutingIt(t *testing.T) {
	var ran bool
	var mu sync.Mutex
	up := newCapturingUpstream(t, map[string]any{
		"content": []any{map[string]any{
			"type": "tool_use", "id": "tu_9", "name": "my_native_tool",
			"input": map[string]any{"x": 1},
		}},
		"stop_reason": "tool_use",
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})

	tk := spikeToolkit(t, Tool{
		Name: "my_native_tool", Description: "an ordinary executable tool", Source: SourceNative,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{"x": map[string]any{"type": "number"}}},
		Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
			mu.Lock()
			ran = true
			mu.Unlock()
			return ToolResult{Output: "SHOULD NOT RUN"}, nil
		},
	})

	res, err := c.Translate(context.Background(), TranslateRequest{
		Messages: []any{map[string]any{"role": "user", "content": "use the tool"}},
		Toolkit:  tk,
	})
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if ran {
		t.Fatal("Translate EXECUTED a toolkit tool — it must only declare them")
	}
	if len(res.ToolCalls) != 1 || res.ToolCalls[0].Name != "my_native_tool" {
		t.Fatalf("the toolkit tool's call was not handed back: %+v", res.ToolCalls)
	}
	if res.ToolCalls[0].ID != "tu_9" {
		t.Fatalf("call id not preserved: %q", res.ToolCalls[0].ID)
	}
	// Declared in the provider's native shape, via the existing adapters.
	sent := up.sentJSON()
	if !strings.Contains(sent, `"input_schema"`) || !strings.Contains(sent, "my_native_tool") {
		t.Fatalf("toolkit was not declared natively: %s", sent)
	}
}

// TestTranslateComposesToolkitAndOpenAITools: a gateway can declare its OWN toolkit
// alongside the caller's declared tools in one call.
func TestTranslateComposesToolkitAndOpenAITools(t *testing.T) {
	up := newCapturingUpstream(t, map[string]any{
		"content": []any{map[string]any{"type": "text", "text": "ok"}}, "stop_reason": "end_turn",
	})
	c := CreateClient(ClientOptions{BaseURL: up.srv.URL, Style: StyleAnthropic, Model: "stub", APIKey: "k"})
	tk := spikeToolkit(t, Tool{
		Name: "server_side_tool", Description: "gateway's own", Source: SourceNative,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Execute:     func(map[string]any, *ToolContext) (ToolResult, error) { return ToolResult{}, nil },
	})
	if _, err := c.Translate(context.Background(), TranslateRequest{
		Messages: []any{map[string]any{"role": "user", "content": "go"}},
		Toolkit:  tk,
		Tools:    openAITools(), // the caller's own declarations
	}); err != nil {
		t.Fatalf("translate: %v", err)
	}
	sent := up.sentJSON()
	for _, want := range []string{"server_side_tool", "get_weather"} {
		if !strings.Contains(sent, want) {
			t.Fatalf("composed declaration missing %s: %s", want, sent)
		}
	}
}
