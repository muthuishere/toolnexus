// usage: go run . npx -y @agentclientprotocol/claude-agent-acp@0.100.0
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

type addArgs struct {
	A float64 `json:"a"`
	B float64 `json:"b"`
}

// allowOnly is a permission policy: allow tool calls whose title names one of your tools,
// reject the rest. Return an optionId to pick it; "" answers with the first reject option.
func allowOnly(names []string) func(context.Context, json.RawMessage) (string, error) {
	return func(_ context.Context, raw json.RawMessage) (string, error) {
		var p struct {
			ToolCall struct{ Title string } `json:"toolCall"`
			Options  []struct{ OptionID, Kind string }
		}
		if err := json.Unmarshal(raw, &p); err != nil {
			return "", err
		}
		want := "reject"
		for _, n := range names {
			if strings.Contains(p.ToolCall.Title, n) {
				want = "allow"
			}
		}
		for _, o := range p.Options {
			if strings.HasPrefix(o.Kind, want) {
				return o.OptionID, nil
			}
		}
		return "", nil
	}
}

func main() {
	ctx := context.Background()
	add := tn.NativeToolReflect("add", "Add two numbers.", func(_ context.Context, in addArgs) (string, error) {
		return strconv.FormatFloat(in.A+in.B, 'f', -1, 64), nil
	})
	toolkit, err := tn.CreateToolkit(ctx, tn.Options{Builtins: false, ExtraTools: []tn.Tool{add}})
	if err != nil {
		panic(err)
	}
	server, err := toolkit.AsMCPServer(nil)
	if err != nil {
		panic(err)
	}
	defer server.Close()

	names := []string{}
	for _, t := range toolkit.Tools() {
		names = append(names, t.Name)
	}
	agent, err := tn.LoadACP(ctx, tn.ACPOptions{
		Command: os.Args[1], Args: os.Args[2:],
		MCPServers: []map[string]any{{"type": "http", "name": "toolnexus", "url": server.URL,
			"headers": []map[string]any{{"name": "Authorization", "value": "Bearer " + server.Token}}}},
		// Agent-specific knobs are opaque data: this one isolates Claude Code (see the per-agent notes).
		SessionMeta: map[string]any{"claudeCode": map[string]any{"options": map[string]any{
			"settingSources": []any{}, "strictMcpConfig": true, "plugins": []any{}, "skills": []any{}, "tools": []any{}}}},
		Model:        os.Getenv("ACP_MODEL"), // e.g. "haiku"; passed through, never validated
		OnPermission: allowOnly(names),
		OnUpdate: func(raw json.RawMessage) {
			if strings.Contains(string(raw), `"sessionUpdate":"usage_update"`) {
				fmt.Fprintln(os.Stderr, "[usage]", string(raw))
			}
		},
		PermissionTimeout: 5 * time.Minute,
		RequestTimeout:    5 * time.Minute,
	})
	if err != nil {
		panic(err)
	}
	defer agent.Close()

	client := tn.CreateInProcessClient(tn.InProcessOptions{Model: os.Args[1], GenerateStream: agent.GenerateStream})
	empty, err := tn.CreateToolkit(ctx, tn.Options{Builtins: false})
	if err != nil {
		panic(err)
	}
	result, err := client.Run(ctx, "Use the add tool to add 17 and 25. Reply with just the sum.", empty)
	if err != nil {
		panic(err)
	}
	fmt.Println(result.Text)
}
