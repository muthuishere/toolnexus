// Scripted in-process mock LLM (openai style, keyed by body.model) — the
// hermetic transport behind every runtime test. Ports the shared
// examples/subagent-*/fixture.json mockLLM scripts: zero network, zero cost.
package agents

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

func openaiResp(message map[string]any) *http.Response {
	msg := map[string]any{"role": "assistant"}
	for k, v := range message {
		msg[k] = v
	}
	body := map[string]any{
		"choices": []any{map[string]any{"message": msg}},
		"usage":   map[string]any{"prompt_tokens": 30, "completion_tokens": 10, "total_tokens": 40},
	}
	b, _ := json.Marshal(body)
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(bytes.NewReader(b)),
	}
}

func textResp(content string) *http.Response {
	return openaiResp(map[string]any{"content": content})
}

func toolCallPart(name string, args map[string]any, id string) map[string]any {
	aj, _ := json.Marshal(args)
	return map[string]any{
		"id":       id,
		"type":     "function",
		"function": map[string]any{"name": name, "arguments": string(aj)},
	}
}

func callResp(calls ...map[string]any) *http.Response {
	raw := make([]any, len(calls))
	for i, c := range calls {
		raw[i] = c
	}
	return openaiResp(map[string]any{"content": nil, "tool_calls": raw})
}

type parsedReq struct {
	model    string
	messages []map[string]any
	toolMsgs []string // contents of role=="tool" messages, in order
	system   string
	last     string // content of the last message
}

func parseReq(req *http.Request) parsedReq {
	b, _ := io.ReadAll(req.Body)
	var body struct {
		Model    string           `json:"model"`
		Messages []map[string]any `json:"messages"`
	}
	_ = json.Unmarshal(b, &body)
	p := parsedReq{model: body.Model, messages: body.Messages}
	for _, m := range body.Messages {
		content, _ := m["content"].(string)
		switch m["role"] {
		case "tool":
			p.toolMsgs = append(p.toolMsgs, content)
		case "system":
			p.system = content
		}
	}
	if n := len(body.Messages); n > 0 {
		p.last, _ = body.Messages[n-1]["content"].(string)
	}
	return p
}

var itemLineRe = regexp.MustCompile(`(?m)^\d+\.`)

// mockLLM scripts the fixture models. slowGate (when non-nil) parks m-slow
// until released or the request context dies — the interrupt/turn-gate probe.
type mockLLM struct {
	slowGate chan struct{}
}

func (m *mockLLM) RoundTrip(req *http.Request) (*http.Response, error) {
	p := parseReq(req)
	// A little latency so turn concurrency is observable.
	time.Sleep(5 * time.Millisecond)
	switch p.model {
	case "m-coordinator":
		if len(p.toolMsgs) == 0 {
			return callResp(
				toolCallPart("task", map[string]any{"agent": "explore", "prompt": "find A"}, "c1"),
				toolCallPart("task", map[string]any{"agent": "explore", "prompt": "find B"}, "c2"),
			), nil
		}
		return textResp("synthesis: " + strings.Join(p.toolMsgs, " + ")), nil
	case "m-explore":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCallPart("lookup", map[string]any{"q": "x"}, "e1")), nil
		}
		return textResp("found:" + p.toolMsgs[0]), nil
	case "m-approver-parent":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCallPart("task", map[string]any{"agent": "asker", "prompt": "get the secret"}, "p1")), nil
		}
		return textResp("parent-final: " + p.toolMsgs[len(p.toolMsgs)-1]), nil
	case "m-asker":
		for _, msg := range p.toolMsgs {
			if strings.Contains(msg, "secret-token") {
				return textResp("asker-done: secret-token"), nil
			}
		}
		return callResp(toolCallPart("check_secret", map[string]any{}, "a1")), nil
	case "m-peer":
		items := len(itemLineRe.FindAllString(p.last, -1))
		return textResp(fmt.Sprintf("processed %d items", items)), nil
	case "m-count":
		return textResp(fmt.Sprintf("msgs:%d", len(p.messages))), nil
	case "m-rogue":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCallPart("task", map[string]any{"agent": "stranger", "prompt": "hi"}, "r1")), nil
		}
		return textResp("rogue-saw: " + p.toolMsgs[0]), nil
	case "m-loop": // never finishes: always another tool call → maxTurns/incomplete
		return callResp(toolCallPart("lookup", map[string]any{"q": "again"}, fmt.Sprintf("l%d", len(p.toolMsgs)))), nil
	case "m-slow":
		select {
		case <-m.slowGate:
			return textResp("slow-done"), nil
		case <-req.Context().Done():
			return nil, req.Context().Err()
		}
	default:
		return textResp("ok"), nil
	}
}

// mockUX scripts the Level-1/bridge models (S10–S13).
type mockUX struct{}

func (mockUX) RoundTrip(req *http.Request) (*http.Response, error) {
	p := parseReq(req)
	time.Sleep(2 * time.Millisecond)
	switch p.model {
	case "m-coder":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCallPart("task", map[string]any{"agent": "explore", "prompt": "find the bug"}, "t1")), nil
		}
		soul := "missing"
		if strings.Contains(p.system, "You are the CODER") {
			soul = "loaded"
		}
		return textResp(fmt.Sprintf("fixed using: %s [soul:%s]", p.toolMsgs[0], soul)), nil
	case "m-explore":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCallPart("lookup", map[string]any{"q": "bug"}, "e1")), nil
		}
		return textResp(fmt.Sprintf("bug at line 42 (%s)", p.toolMsgs[0])), nil
	case "m-mia":
		if strings.Contains(p.last, "Heartbeat") {
			hasTicks := strings.Contains(p.last, "channel=timer")
			if strings.Contains(p.system, "water the plants") && hasTicks {
				return textResp("Reminder: water the plants!"), nil
			}
			return textResp("HEARTBEAT_OK"), nil
		}
		var found []string
		for _, f := range []string{"SOUL.md", "USER.md", "MEMORY.md"} {
			if strings.Contains(p.system, "## "+f) {
				found = append(found, f)
			}
		}
		return textResp("soul-sections:[" + strings.Join(found, ",") + "]"), nil
	case "m-old-api":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCallPart("explore", map[string]any{"prompt": "scan the repo"}, "b1")), nil
		}
		return textResp("old-api summary: " + p.toolMsgs[0]), nil
	default:
		return textResp("ok"), nil
	}
}

// ---- shared scoped tools (from the fixtures) ----

var lookupTool = tn.Tool{
	Name:        "lookup",
	Description: "look something up",
	InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{"q": map[string]any{"type": "string"}}},
	Source:      tn.SourceCustom,
	Execute: func(args map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
		return tn.ToolResult{Output: fmt.Sprintf("data(%v)", args["q"])}, nil
	},
}

var checkSecretTool = tn.Tool{
	Name:        "check_secret",
	Description: "needs human approval",
	InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
	Source:      tn.SourceCustom,
	Execute: func(_ map[string]any, tctx *tn.ToolContext) (tn.ToolResult, error) {
		if tctx != nil && tctx.Answer != nil && tctx.Answer.Ok {
			return tn.ToolResult{Output: "secret-token"}, nil
		}
		return tn.Pending(tn.Request{Kind: "approval", Prompt: "approve secret access?"}), nil
	},
}

// testRegistry mirrors the shared fixture registries (team wiring included).
func testRegistry() map[string]Def {
	return map[string]Def{
		"coordinator":    {Name: "coordinator", Does: "splits and delegates", Soul: "coordinate", Model: "m-coordinator", Team: []string{"explore"}},
		"explore":        {Name: "explore", Does: "read-only research", Soul: "explore", Model: "m-explore", Tools: []tn.Tool{lookupTool}},
		"approverParent": {Name: "approverParent", Does: "delegates, holds approval authority", Model: "m-approver-parent", Team: []string{"asker"}},
		"asker":          {Name: "asker", Does: "needs approvals", Model: "m-asker", Tools: []tn.Tool{checkSecretTool}},
		"peer":           {Name: "peer", Does: "team member", Model: "m-peer"},
		"counter":        {Name: "counter", Does: "reports transcript length", Soul: "count", Model: "m-count"},
		"rogue":          {Name: "rogue", Does: "tasks outside its team", Model: "m-rogue", Team: []string{"explore"}},
		"looper":         {Name: "looper", Does: "never finishes", Model: "m-loop", Tools: []tn.Tool{lookupTool}},
		"slow":           {Name: "slow", Does: "slow worker", Model: "m-slow"},
	}
}

// traceHas reports whether any trace line contains sub.
func traceHas(rt *Runtime, sub string) bool {
	for _, l := range rt.Trace() {
		if strings.Contains(l, sub) {
			return true
		}
	}
	return false
}

// awaitState polls until the handle reaches want (bounded), for tests that need
// to catch a mid-turn state.
func awaitState(rt *Runtime, h *Handle, want State) bool {
	for i := 0; i < 400; i++ {
		if rt.StateOf(h) == want {
			return true
		}
		time.Sleep(2 * time.Millisecond)
	}
	return false
}
