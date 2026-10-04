package toolnexus

// ACP as a real tool-calling model (SPEC §8 "ACP model source",
// openspec/changes/add-acp-tool-calling): the OpenAI-shaped request reaches
// the agent, its JSON reply becomes tool calls the loop executes, and the
// agent's own tools are refused unless the host opts in. Uses the same
// re-invoked-test-binary fake agent as acp_test.go.

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

// acpSplitPrompt pulls the REQUEST JSON back out of a rendered prompt.
func acpSplitPrompt(prompt string) (preamble string, request map[string]any, ok bool) {
	i := strings.Index(prompt, "\nREQUEST:\n")
	j := strings.LastIndex(prompt, "\n\n"+acpSupersedesMarker+" ")
	if i < 0 || j < i {
		return "", nil, false
	}
	if err := json.Unmarshal([]byte(prompt[i+len("\nREQUEST:\n"):j]), &request); err != nil {
		return "", nil, false
	}
	return prompt[:i], request, true
}

// acpHandleToolLoopForTest is a scripted tool-calling model: with no tool
// result in the request it asks for add(2,3) — wrapped in prose and fences,
// with object arguments, to exercise the tolerant parser — and once a tool
// result is present it answers from it.
func acpHandleToolLoopForTest(sendNotification func(string, any), reply func(json.RawMessage, any), emit func(acpEvent), id json.RawMessage, sessionID, prompt string) {
	_, req, ok := acpSplitPrompt(prompt)
	answer := "unparseable prompt"
	if ok {
		emit(acpEvent{Type: "request", Data: req})
		toolResult := ""
		msgs, _ := req["messages"].([]any)
		for _, m := range msgs {
			if mm, _ := m.(map[string]any); mm["role"] == "tool" {
				toolResult, _ = mm["content"].(string)
			}
		}
		if toolResult == "" {
			answer = "Sure, calling the tool.\n```json\n" +
				`{"tool_calls":[{"id":"c1","type":"function","function":{"name":"add","arguments":{"a":2,"b":3}}}]}` +
				"\n```"
		} else {
			b, _ := json.Marshal(map[string]any{"content": "The answer is " + toolResult + "."})
			answer = string(b)
		}
	}
	sendNotification("session/update", map[string]any{
		"sessionId": sessionID,
		"update": map[string]any{
			"sessionUpdate": "agent_message_chunk",
			"content":       map[string]any{"type": "text", "text": answer},
		},
	})
	reply(id, map[string]any{"stopReason": "end_turn"})
}

func TestACP_ToolCallingLoopEndToEnd(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	acp, err := LoadACP(context.Background(), acpTestOptions(t, "toolloop", outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	tk := bareToolkit(t)
	defer tk.Close()
	tk.Register(addTool(t))

	c := CreateInProcessClient(InProcessOptions{Model: "acp", Generate: acp.Generate})
	r, err := c.Run(context.Background(), "What is 2 + 3?", tk)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	acp.Close()

	if r.Text != "The answer is 5." {
		t.Fatalf("final text = %q", r.Text)
	}
	if len(r.ToolCalls) != 1 || r.ToolCalls[0].Name != "add" || r.ToolCalls[0].Output != "5" {
		t.Fatalf("tool calls = %+v", r.ToolCalls)
	}

	var requests []map[string]any
	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "request" {
			requests = append(requests, ev.Data)
		}
	}
	if len(requests) != 2 {
		t.Fatalf("expected 2 prompts (ask, then answer), got %d", len(requests))
	}
	// Turn 1: the tool schema reached the agent, OpenAI-shaped.
	tools, _ := requests[0]["tools"].([]any)
	var sawAdd bool
	for _, tl := range tools {
		fn, _ := tl.(map[string]any)["function"].(map[string]any)
		if fn["name"] == "add" && fn["parameters"] != nil {
			sawAdd = true
		}
	}
	if !sawAdd {
		t.Fatalf("turn 1 tools did not carry add's schema: %v", tools)
	}
	// Turn 2: the assistant tool_calls message and the tool result are both there.
	var sawCall, sawResult bool
	for _, m := range requests[1]["messages"].([]any) {
		mm := m.(map[string]any)
		if mm["role"] == "assistant" && mm["tool_calls"] != nil {
			sawCall = true
		}
		if mm["role"] == "tool" && mm["tool_call_id"] == "c1" && mm["content"] == "5" {
			sawResult = true
		}
	}
	if !sawCall || !sawResult {
		t.Fatalf("turn 2 messages missing the call (%v) or its result (%v): %v", sawCall, sawResult, requests[1]["messages"])
	}
}

func TestACP_PromptShape(t *testing.T) {
	req := InProcessRequest{
		Messages: []any{
			map[string]any{"role": "system", "content": "be terse"},
			map[string]any{"role": "user", "content": []any{
				map[string]any{"type": "text", "text": "a <b> & c"},
				map[string]any{"type": "image_url"},
				map[string]any{"type": "text", "text": "d"},
			}},
		},
	}
	p := renderACPPrompt(req)
	pre, body, ok := acpSplitPrompt(p)
	if !ok {
		t.Fatalf("prompt does not split: %q", p)
	}
	if pre != acpPreamble {
		t.Fatalf("preamble drifted:\n%q", pre)
	}
	if !strings.HasSuffix(p, "\n\nSUPERSEDES-ALL-PRIOR: a <b> & c d") {
		t.Fatalf("marker line wrong: %q", p[len(p)-60:])
	}
	if strings.Contains(p, "\\u003c") {
		t.Fatal("REQUEST JSON is HTML-escaped")
	}
	if tools, isArr := body["tools"].([]any); !isArr || len(tools) != 0 {
		t.Fatalf("absent tools must render as [], got %v", body["tools"])
	}
	if !strings.Contains(p, "\nREQUEST:\n{\"messages\":") {
		t.Fatal("REQUEST JSON must lead with messages")
	}
}

// The preamble is byte-pinned by SPEC.md §8; read it back from the spec so the
// constant cannot drift from the contract every port is held to.
func TestACP_PreambleMatchesSpec(t *testing.T) {
	spec, err := os.ReadFile(filepath.Join("..", "SPEC.md"))
	if err != nil {
		t.Skipf("SPEC.md not reachable: %v", err)
	}
	s := string(spec)
	i := strings.Index(s, "`PREAMBLE` is these seven lines")
	if i < 0 {
		t.Fatal("SPEC.md has no ACP preamble block")
	}
	s = s[i:]
	start := strings.Index(s, "```\n") + len("```\n")
	end := strings.Index(s[start:], "```")
	if got := s[start : start+end]; got != acpPreamble {
		t.Fatalf("acpPreamble differs from SPEC.md:\nspec: %q\ncode: %q", got, acpPreamble)
	}
}

func TestACP_ParseReply(t *testing.T) {
	add := []InProcessToolCall{{ID: "c1", Name: "add", Arguments: map[string]any{"a": float64(2), "b": float64(3)}}}
	addStr := []InProcessToolCall{{ID: "c1", Name: "add", Arguments: `{"a":2,"b":3}`}}
	cases := []struct {
		name  string
		in    string
		calls []InProcessToolCall
		text  string
	}{
		{"plain prose passes through", "just text {not json", nil, "just text {not json"},
		{"content envelope", `{"content":"The answer is 5."}`, nil, "The answer is 5."},
		{"null content", `{"content":null}`, nil, ""},
		{"non-string content encodes", `{"content":{"x":1}}`, nil, `{"x":1}`},
		{"string arguments pre-encoded", `{"tool_calls":[{"id":"c1","type":"function","function":{"name":"add","arguments":"{\"a\":2,\"b\":3}"}}]}`, addStr, ""},
		{"object arguments", `{"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}`, add, ""},
		{"fenced", "```json\n{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]}\n```", add, ""},
		{"prose around", "Calling now: {\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]} done", add, ""},
		{"choices envelope", `{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}}]}`, add, ""},
		{"message envelope", `{"message":{"content":"hi"}}`, nil, "hi"},
		{"flat call, no id, no arguments", `{"tool_calls":[{"name":"ping"}]}`, []InProcessToolCall{{Name: "ping", Arguments: map[string]any{}}}, ""},
		{"nameless call skipped, falls to content", `{"tool_calls":[{"function":{"arguments":"{}"}}],"content":"fallback"}`, nil, "fallback"},
		{"structured output passes through", ` {"answer":true} `, nil, ` {"answer":true} `},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := parseACPReply(tc.in)
			if !reflect.DeepEqual(got.ToolCalls, tc.calls) || got.Content != tc.text {
				t.Fatalf("parseACPReply(%q) = %+v, want calls=%+v text=%q", tc.in, got, tc.calls, tc.text)
			}
		})
	}
}

func TestACP_PermissionAllowedOnOptIn(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	opts := acpTestOptions(t, "permission", outFile)
	opts.AllowAgentTools = true
	c, err := LoadACP(context.Background(), opts)
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	start := time.Now()
	if _, err := c.Generate(acpUserMessage("go")); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("permission was awaited, not answered")
	}
	c.Close()
	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "permission-answer" {
			if ev.Data["optionId"] != "allow-once" {
				t.Fatalf("opt-in must select the first allow-kind option, got %v", ev.Data["optionId"])
			}
			return
		}
	}
	t.Fatal("no permission answer observed")
}
