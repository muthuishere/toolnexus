// usage: go run . npx -y @agentclientprotocol/claude-agent-acp@0.100.0
package main

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

type addArgs struct {
	A float64 `json:"a"`
	B float64 `json:"b"`
}

func main() {
	ctx := context.Background()
	add := tn.NativeToolReflect("add", "Add two numbers.", func(_ context.Context, in addArgs) (string, error) {
		fmt.Printf("\n[tool] add(%g, %g)\n", in.A, in.B)
		return strconv.FormatFloat(in.A+in.B, 'f', -1, 64), nil
	})

	// 1. Serve YOUR toolkit on loopback, behind a fresh bearer token.
	toolkit, err := tn.CreateToolkit(ctx, tn.Options{Builtins: false, ExtraTools: []tn.Tool{add}})
	if err != nil {
		panic(err)
	}
	server, err := toolkit.AsMCPServer(nil)
	if err != nil {
		panic(err)
	}
	defer server.Close()

	// 2. Start the agent; MCPServers is sent to session/new exactly as written.
	agent, err := tn.LoadACP(ctx, tn.ACPOptions{
		Command: os.Args[1], Args: os.Args[2:],
		MCPServers: []map[string]any{{"type": "http", "name": "toolnexus", "url": server.URL,
			"headers": []map[string]any{{"name": "Authorization", "value": "Bearer " + server.Token}}}},
		PermissionTimeout: 5 * time.Minute, // one turn of a real agent can take minutes
		RequestTimeout:    5 * time.Minute,
	})
	if err != nil {
		panic(err)
	}
	defer agent.Close() // ends the agent's whole process group

	// 3. The agent runs the tools itself (over MCP), so the client's own toolkit is empty.
	client := tn.CreateInProcessClient(tn.InProcessOptions{Model: os.Args[1], GenerateStream: agent.GenerateStream})
	empty, err := tn.CreateToolkit(ctx, tn.Options{Builtins: false})
	if err != nil {
		panic(err)
	}
	events, err := client.Stream(ctx, "Use the add tool to add 17 and 25. Reply with just the sum.", empty)
	if err != nil {
		panic(err)
	}
	for ev := range events {
		switch {
		case ev.Type == "text":
			fmt.Print(ev.Delta) // live text as the agent writes it
		case ev.Type == "tool_call" && ev.Via == "acp":
			fmt.Printf("\n[agent tool] %s\n", ev.Name)
		case ev.Type == "done":
			fmt.Println("\nfinal:", ev.Result.Text)
		case ev.Type == "error":
			panic(ev.Err)
		}
	}
}
