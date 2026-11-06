package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// render turns the whole request into one prompt: tools, transcript, and the reply contract.
func render(req tn.InProcessRequest) string {
	var b strings.Builder
	b.WriteString("You are the model inside a tool-calling loop. Tools you may call:\n")
	for _, t := range req.Tools {
		f, _ := t.(map[string]any)["function"].(map[string]any)
		params, _ := json.Marshal(f["parameters"])
		fmt.Fprintf(&b, "- %v: %v args=%s\n", f["name"], f["description"], params)
	}
	b.WriteString("\nConversation:\n")
	called := map[string]any{} // tool_call_id -> tool name, so results read "[tool add] 5"
	for _, raw := range req.Messages {
		m, _ := raw.(map[string]any)
		calls, _ := m["tool_calls"].([]any)
		switch {
		case m["role"] == "tool":
			name := called[fmt.Sprint(m["tool_call_id"])]
			fmt.Fprintf(&b, "[tool %v] %v\n", name, m["content"])
		case len(calls) > 0:
			fns := []any{}
			for _, c := range calls {
				cm, _ := c.(map[string]any)
				fn, _ := cm["function"].(map[string]any)
				called[fmt.Sprint(cm["id"])] = fn["name"]
				fns = append(fns, fn)
			}
			j, _ := json.Marshal(fns)
			fmt.Fprintf(&b, "[assistant called] %s\n", j)
		default:
			fmt.Fprintf(&b, "[%v] %v\n", m["role"], m["content"])
		}
	}
	b.WriteString("\nReply with ONLY one JSON object: {\"tool_calls\":[{\"name\":\"...\",\"arguments\":{...}}]} to call tools,\n" +
		"or {\"content\":\"...\"} with the final answer. No prose, no code fences.\n")
	return b.String()
}

// parse pulls the first {...} out of the reply, tolerating fences or chatter around it.
func parse(out string) tn.InProcessResponse {
	var r struct {
		ToolCalls []struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		} `json:"tool_calls"`
		Content *string `json:"content"`
	}
	i, j := strings.Index(out, "{"), strings.LastIndex(out, "}")
	if i >= 0 && j > i && json.Unmarshal([]byte(out[i:j+1]), &r) == nil {
		if len(r.ToolCalls) > 0 {
			calls := make([]tn.InProcessToolCall, 0, len(r.ToolCalls))
			for _, c := range r.ToolCalls {
				calls = append(calls, tn.InProcessToolCall{Name: c.Name, Arguments: c.Arguments})
			}
			return tn.InProcessResponse{ToolCalls: calls}
		}
		if r.Content != nil {
			return tn.InProcessResponse{Content: *r.Content}
		}
	}
	return tn.InProcessResponse{Content: strings.TrimSpace(out)} // not our contract: treat it as the answer
}

// OneShotClient runs one process per turn: prompt on stdin, reply on stdout.
func OneShotClient(command []string, timeout time.Duration) *tn.Client {
	return tn.CreateInProcessClient(tn.InProcessOptions{
		Model: command[0],
		GenerateStream: func(ctx context.Context, req tn.InProcessRequest, _ func(tn.StreamEvent)) (tn.InProcessResponse, error) {
			ctx, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()
			cmd := exec.CommandContext(ctx, command[0], command[1:]...)
			cmd.Stdin = strings.NewReader(render(req))
			var stderr strings.Builder
			cmd.Stderr = &stderr
			out, err := cmd.Output()
			if err != nil {
				return tn.InProcessResponse{}, fmt.Errorf("%s: %w: %s", command[0], err, stderr.String())
			}
			return parse(string(out)), nil
		},
	})
}
