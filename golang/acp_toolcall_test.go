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

// acpSplitPrompt classifies a rendered prompt as "opening" (REQUEST JSON with
// messages + tools) or "continuation" (NEW MESSAGES JSON) and decodes its body.
func acpSplitPrompt(prompt string) (kind string, body map[string]any, ok bool) {
	for _, k := range []struct{ kind, marker string }{
		{"opening", "\nREQUEST:\n"},
		{"continuation", "\nNEW MESSAGES:\n"},
	} {
		if i := strings.Index(prompt, k.marker); i >= 0 {
			if json.Unmarshal([]byte(prompt[i+len(k.marker):]), &body) != nil {
				return "", nil, false
			}
			return k.kind, body, true
		}
	}
	return "", nil, false
}

// acpCountSessions returns the number of session/new calls and the session
// id each prompt was sent on, in order.
func acpCountSessions(t *testing.T, outFile string) (news int, promptSessions []string) {
	t.Helper()
	for _, ev := range readACPEvents(t, outFile) {
		switch ev.Type {
		case "session/new":
			news++
		case "session/prompt":
			sid, _ := ev.Data["sessionId"].(string)
			promptSessions = append(promptSessions, sid)
		}
	}
	return news, promptSessions
}

// acpHandleToolLoopForTest is a scripted tool-calling model: with no tool
// result in the request it asks for add(2,3) — wrapped in prose and fences,
// with object arguments, to exercise the tolerant parser — and once a tool
// result is present it answers from it.
func acpHandleToolLoopForTest(sendNotification func(string, any), reply func(json.RawMessage, any), emit func(acpEvent), id json.RawMessage, sessionID, prompt string) {
	kind, req, ok := acpSplitPrompt(prompt)
	answer := "unparseable prompt"
	if ok {
		emit(acpEvent{Type: "request", Data: map[string]any{"kind": kind, "body": req}})
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

	var kinds []string
	var bodies []map[string]any
	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "request" {
			kinds = append(kinds, ev.Data["kind"].(string))
			bodies = append(bodies, ev.Data["body"].(map[string]any))
		}
	}
	if len(bodies) != 2 || kinds[0] != "opening" || kinds[1] != "continuation" {
		t.Fatalf("expected an opening prompt then a continuation, got %v", kinds)
	}
	// Turn 1: the tool schema reached the agent, OpenAI-shaped.
	tools, _ := bodies[0]["tools"].([]any)
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
	// Turn 2: ONLY the tool result — no tools, no system, no history, not the
	// agent's own tool-call message (it already has that).
	if _, hasTools := bodies[1]["tools"]; hasTools {
		t.Fatal("continuation must not resend tools")
	}
	msgs := bodies[1]["messages"].([]any)
	if len(msgs) != 1 {
		t.Fatalf("continuation must carry only the tool result, got %v", msgs)
	}
	if m := msgs[0].(map[string]any); m["role"] != "tool" || m["tool_call_id"] != "c1" || m["content"] != "5" {
		t.Fatalf("continuation message = %v", m)
	}
}

func TestACP_PromptShape(t *testing.T) {
	msgs := acpNormalizeList([]any{
		map[string]any{"role": "system", "content": "be terse"},
		map[string]any{"role": "user", "content": "a <b> & c"},
	})
	p := renderACPOpening(msgs, acpNormalizeList(nil))
	if !strings.HasPrefix(p, acpPreamble+"\nREQUEST:\n{\"messages\":") {
		t.Fatalf("opening prompt shape wrong: %q", p)
	}
	if strings.Contains(p, "SUPERSEDES") {
		t.Fatal("the supersedes marker is gone (add-acp-session-delta)")
	}
	if strings.Contains(p, "\\u003c") {
		t.Fatal("REQUEST JSON is HTML-escaped")
	}
	kind, body, ok := acpSplitPrompt(p)
	if !ok || kind != "opening" {
		t.Fatalf("opening prompt does not split: %q", p)
	}
	if tools, isArr := body["tools"].([]any); !isArr || len(tools) != 0 {
		t.Fatalf("absent tools must render as [], got %v", body["tools"])
	}

	c := renderACPContinuation(msgs[1:])
	if c != acpContinuation+"\nNEW MESSAGES:\n{\"messages\":[{\"content\":\"a <b> & c\",\"role\":\"user\"}]}" {
		t.Fatalf("continuation prompt shape wrong: %q", c)
	}
}

// The preambles are byte-pinned by SPEC.md §8; read them back from the spec
// so the constants cannot drift from the contract every port is held to.
func TestACP_PreambleMatchesSpec(t *testing.T) {
	spec, err := os.ReadFile(filepath.Join("..", "SPEC.md"))
	if err != nil {
		t.Skipf("SPEC.md not reachable: %v", err)
	}
	block := func(lead string) string {
		s := string(spec)
		i := strings.Index(s, lead)
		if i < 0 {
			t.Fatalf("SPEC.md has no block introduced by %q", lead)
		}
		s = s[i:]
		start := strings.Index(s, "```\n") + len("```\n")
		end := strings.Index(s[start:], "```")
		return s[start : start+end]
	}
	if got := block("`PREAMBLE` is these seven lines"); got != acpPreamble {
		t.Fatalf("acpPreamble differs from SPEC.md:\nspec: %q\ncode: %q", got, acpPreamble)
	}
	if got := block("`CONTINUATION` is these three lines"); got != acpContinuation {
		t.Fatalf("acpContinuation differs from SPEC.md:\nspec: %q\ncode: %q", got, acpContinuation)
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

// --- session delta: every case that must open a fresh session --------------

// acpLoopTurn runs one Generate and appends the assistant message the
// in-process layer would build, so conv grows exactly like a real loop's.
func acpLoopTurn(t *testing.T, c *ACPClient, conv []any, tools []any) []any {
	t.Helper()
	resp, err := c.Generate(InProcessRequest{Messages: conv, Tools: tools})
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	return append(conv, map[string]any{"role": "assistant", "content": resp.Content})
}

func acpUser(text string) map[string]any { return map[string]any{"role": "user", "content": text} }

var acpToolA = []any{map[string]any{"type": "function", "function": map[string]any{"name": "a", "parameters": map[string]any{"type": "object"}}}}
var acpToolB = []any{map[string]any{"type": "function", "function": map[string]any{"name": "b", "parameters": map[string]any{"type": "object"}}}}

func TestACP_SessionResets(t *testing.T) {
	cases := []struct {
		name string
		// turn2 builds the second request from the first conversation.
		turn2     func(conv []any) ([]any, []any)
		wantFresh bool
	}{
		{"appended user message continues", func(conv []any) ([]any, []any) {
			return append(conv, acpUser("next")), acpToolA
		}, false},
		{"changed tools reset", func(conv []any) ([]any, []any) {
			return append(conv, acpUser("next")), acpToolB
		}, true},
		{"edited history resets", func(conv []any) ([]any, []any) {
			edited := append([]any{acpUser("rewritten first turn")}, conv[1:]...)
			return append(edited, acpUser("next")), acpToolA
		}, true},
		{"compacted history resets", func(conv []any) ([]any, []any) {
			return []any{acpUser("summary of earlier turns"), acpUser("next")}, acpToolA
		}, true},
		{"rewritten agent reply resets", func(conv []any) ([]any, []any) {
			out := append([]any{}, conv[:len(conv)-1]...)
			out = append(out, map[string]any{"role": "assistant", "content": "something the agent never said"})
			return append(out, acpUser("next")), acpToolA
		}, true},
		{"retry of the same request resets", func(conv []any) ([]any, []any) {
			return conv[:len(conv)-1], acpToolA
		}, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			outFile := filepath.Join(t.TempDir(), "events.ndjson")
			c, err := LoadACP(context.Background(), acpTestOptions(t, "warm", outFile))
			if err != nil {
				t.Fatalf("LoadACP: %v", err)
			}
			conv := acpLoopTurn(t, c, []any{acpUser("first")}, acpToolA)
			msgs, tools := tc.turn2(conv)
			if _, err := c.Generate(InProcessRequest{Messages: msgs, Tools: tools}); err != nil {
				t.Fatalf("Generate #2: %v", err)
			}
			c.Close()

			news, sessions := acpCountSessions(t, outFile)
			fresh := news == 2 && sessions[0] != sessions[1]
			if fresh != tc.wantFresh {
				t.Fatalf("fresh session = %v (session/new=%d, prompt sessions %v), want %v", fresh, news, sessions, tc.wantFresh)
			}
			var texts []string
			for _, ev := range readACPEvents(t, outFile) {
				if ev.Type == "session/prompt" {
					texts = append(texts, ev.Data["text"].(string))
				}
			}
			kind, _, _ := acpSplitPrompt(texts[1])
			if want := map[bool]string{true: "opening", false: "continuation"}[tc.wantFresh]; kind != want {
				t.Fatalf("turn 2 prompt kind = %q, want %q", kind, want)
			}
		})
	}
}

func TestACP_FailedTurnDiscardsSessionState(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	c, err := LoadACP(context.Background(), acpTestOptions(t, "warm", outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	conv := acpLoopTurn(t, c, []any{acpUser("first")}, nil)
	if _, err := c.Generate(InProcessRequest{Messages: append(conv, acpUser("FAIL-THIS-TURN"))}); err == nil {
		t.Fatal("expected the scripted failure")
	}
	// The same conversation again: after a failure nothing about the session
	// can be trusted, so this must open a fresh one.
	if _, err := c.Generate(InProcessRequest{Messages: append(conv, acpUser("try again"))}); err != nil {
		t.Fatalf("Generate after failure: %v", err)
	}
	c.Close()
	news, sessions := acpCountSessions(t, outFile)
	if news != 2 || sessions[len(sessions)-1] == sessions[0] {
		t.Fatalf("expected a fresh session after the failed turn: session/new=%d, prompt sessions %v", news, sessions)
	}
}

func TestACP_ConfigAppliedAtLoadAndOnReset(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	opts := acpTestOptions(t, "warm", outFile)
	opts.Config = []ACPConfig{{ID: "model", Value: "m-2"}, {ID: "fast", Value: true}}
	c, err := LoadACP(context.Background(), opts)
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	if !strings.Contains(string(c.ConfigOptions()), `"currentValue":"m-2"`) {
		t.Fatalf("ConfigOptions must reflect the agent's latest answer, got %s", c.ConfigOptions())
	}
	acpLoopTurn(t, c, []any{acpUser("one")}, nil)
	acpLoopTurn(t, c, []any{acpUser("an unrelated conversation")}, nil) // resets
	c.Close()

	var sets []map[string]any
	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "session/set_config_option" {
			sets = append(sets, ev.Data)
		}
	}
	if len(sets) != 4 {
		t.Fatalf("expected 2 options x 2 sessions = 4 set_config_option calls, got %d: %v", len(sets), sets)
	}
	if sets[0]["configId"] != "model" || sets[0]["value"] != "m-2" || sets[0]["sessionId"] != "sess-1" {
		t.Fatalf("first set = %v", sets[0])
	}
	if sets[1]["value"] != true || sets[1]["type"] != "boolean" {
		t.Fatalf("a boolean option must carry type:boolean, got %v", sets[1])
	}
	if sets[2]["sessionId"] != "sess-2" || sets[2]["configId"] != "model" {
		t.Fatalf("config must be re-applied on the fresh session, got %v", sets[2])
	}
}

func TestACP_RejectedConfigFailsLoad(t *testing.T) {
	opts := acpTestOptions(t, "warm", "")
	opts.Config = []ACPConfig{{ID: "bad", Value: "x"}}
	c, err := LoadACP(context.Background(), opts)
	if err == nil {
		c.Close()
		t.Fatal("expected LoadACP to fail on a rejected config option")
	}
	if !strings.Contains(err.Error(), "unknown config option: bad") {
		t.Fatalf("the agent's own error must surface, got %v", err)
	}
}

func TestACP_ConfigOptionsReadableWithoutConfig(t *testing.T) {
	c, err := LoadACP(context.Background(), acpTestOptions(t, "warm", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()
	var opts []map[string]any
	if err := json.Unmarshal(c.ConfigOptions(), &opts); err != nil || len(opts) != 1 || opts[0]["id"] != "model" {
		t.Fatalf("ConfigOptions = %s (%v)", c.ConfigOptions(), err)
	}
}

func TestACP_ToolCallReplyMatching(t *testing.T) {
	reply := InProcessResponse{ToolCalls: []InProcessToolCall{{ID: "c1", Name: "add", Arguments: map[string]any{"a": float64(2)}}}}
	built := map[string]any{"role": "assistant", "tool_calls": []any{map[string]any{
		"id": "call_0", "type": "function",
		"function": map[string]any{"name": "add", "arguments": `{"a":2}`},
	}}}
	if !acpReplyMatches(built, reply) {
		t.Fatal("same name + decoded arguments must match, ids ignored")
	}
	other := map[string]any{"role": "assistant", "tool_calls": []any{map[string]any{
		"function": map[string]any{"name": "add", "arguments": `{"a":3}`},
	}}}
	if acpReplyMatches(other, reply) {
		t.Fatal("different arguments must not match")
	}
	if acpReplyMatches(map[string]any{"role": "user", "content": "x"}, InProcessResponse{Content: "x"}) {
		t.Fatal("a non-assistant message never matches")
	}
}

// Conversation memory through the real in-process client (Ask with an id):
// the second Ask extends the stored transcript, so it must continue the same
// ACP session rather than reset — the case the token saving is for.
func TestACP_AskWithMemoryContinuesSession(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	acp, err := LoadACP(context.Background(), acpTestOptions(t, "warm", outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	tk := bareToolkit(t)
	defer tk.Close()
	tk.Register(addTool(t))

	c := CreateInProcessClient(InProcessOptions{Model: "acp", Generate: acp.Generate, SystemPrompt: "Be terse."})
	for _, q := range []string{"first question", "second question", "third question"} {
		if _, err := c.Ask(context.Background(), q, tk, "conv-1"); err != nil {
			t.Fatalf("Ask(%q): %v", q, err)
		}
	}
	acp.Close()

	news, _ := acpCountSessions(t, outFile)
	var kinds []string
	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "session/prompt" {
			k, _, _ := acpSplitPrompt(ev.Data["text"].(string))
			kinds = append(kinds, k)
		}
	}
	if news != 1 || len(kinds) != 3 || kinds[1] != "continuation" || kinds[2] != "continuation" {
		t.Fatalf("Ask with memory must continue one session: session/new=%d, prompt kinds %v", news, kinds)
	}
}
