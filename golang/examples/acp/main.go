// ACP (Agent Client Protocol) as the model behind the client loop — issue #96,
// ADR 0031 / ADR 0036 (openspec/changes/add-acp-tool-calling,
// openspec/changes/add-acp-session-delta).
//
// This example spawns a real ACP agent CLI, registers one trivial local tool,
// and drives it through the ordinary toolnexus tool-calling loop for two turns
// of ONE conversation. The agent is only the model: it is handed the tool
// schemas and replies with tool calls, which toolnexus executes. Turn 1 opens
// an ACP session with the system prompt + tools; turn 2 sends only the new
// messages on that same session.
//
// It is NOT hermetic and is NOT run by CI: it needs the agent CLI installed
// and already logged in on this machine. toolnexus has nothing specific to
// any agent — the command, its arguments and any bypass flags are yours:
//
//	go run ./examples/acp                                             # opencode (default)
//	TOOLNEXUS_ACP_CMD="npx -y @zed-industries/codex-acp" go run ./examples/acp   # codex
//	TOOLNEXUS_ACP_CMD="devin acp" go run ./examples/acp               # devin
//
// Pick a model with TOOLNEXUS_ACP_MODEL. It is passed straight through as the
// ACP session config option "model"; valid values are whatever the agent
// advertises, which this example prints (ACPClient.ConfigOptions). Not every
// agent exposes a "model" option — if yours rejects it, LoadACP fails with
// the agent's own error.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/muthuishere/toolnexus/golang"
)

// clockArgs is the reflected input schema for the native `clock` tool.
type clockArgs struct{}

func main() {
	acpCmd := os.Getenv("TOOLNEXUS_ACP_CMD")
	if acpCmd == "" {
		acpCmd = "opencode acp"
	}
	parts := strings.Fields(acpCmd)
	command, args := parts[0], parts[1:]

	var config []toolnexus.ACPConfig
	if model := os.Getenv("TOOLNEXUS_ACP_MODEL"); model != "" {
		config = append(config, toolnexus.ACPConfig{ID: "model", Value: model})
	}

	_, thisFile, _, _ := runtime.Caller(0)
	root := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "examples")

	ctx := context.Background()
	// MCP servers and skills stay toolnexus tools — the agent sees only their
	// schemas, and every call runs through this loop, never the agent's own
	// MCP client.
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
		McpConfig: filepath.Join(root, "mcp.json"),
		SkillsDir: []string{filepath.Join(root, "skills")},
	})
	if err != nil {
		log.Fatal(err)
	}
	defer tk.Close()

	tk.Register(toolnexus.NativeToolReflect("clock",
		"Return the current UTC time.",
		func(_ context.Context, _ clockArgs) (string, error) {
			return time.Now().UTC().Format(time.RFC3339), nil
		},
	))

	wd, err := os.Getwd()
	if err != nil {
		log.Fatal(err)
	}

	fmt.Printf("Spawning ACP agent: %s %v\n", command, args)
	acp, err := toolnexus.LoadACP(ctx, toolnexus.ACPOptions{
		Command: command,
		Args:    args,
		Cwd:     wd,
		Config:  config,
	})
	if err != nil {
		log.Fatal("acp connect failed (is the CLI installed + logged in?): ", err)
	}
	defer acp.Close()
	printConfigOptions(acp.ConfigOptions())

	agent := toolnexus.CreateInProcessClient(toolnexus.InProcessOptions{
		Model:        acpCmd,
		Generate:     acp.Generate,
		SystemPrompt: "You are a precise agent. Use tools to compute and fetch facts.",
	})

	turns := []string{
		"What time is it right now? Use the clock tool.",
		"What did the clock tool just return, verbatim?",
	}

	// Ask with a conversation id keeps the transcript, so turn 2 extends
	// turn 1 and goes out as a continuation on the same ACP session.
	for i, prompt := range turns {
		start := time.Now()
		res, err := agent.Ask(ctx, prompt, tk, "acp-example")
		elapsed := time.Since(start)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Printf("\n--- turn %d (%s) ---\n", i+1, elapsed)
		fmt.Println("prompt:", prompt)
		if len(res.ToolCalls) > 0 {
			names := make([]string, 0, len(res.ToolCalls))
			for _, c := range res.ToolCalls {
				names = append(names, c.Name)
			}
			fmt.Println("tool calls:", names)
		}
		fmt.Println("answer:", strings.TrimSpace(res.Text))
	}

	fmt.Println("\nGo ACP example OK —", len(turns), "turns on one warm agent process")
}

// printConfigOptions lists what the agent lets a client set — the ids and
// values ACPOptions.Config accepts. Printed raw-ish: toolnexus does not
// interpret them.
func printConfigOptions(raw json.RawMessage) {
	var opts []struct {
		ID           string `json:"id"`
		Category     string `json:"category"`
		CurrentValue any    `json:"currentValue"`
		Options      []struct {
			Value string `json:"value"`
		} `json:"options"`
	}
	if len(raw) == 0 || json.Unmarshal(raw, &opts) != nil || len(opts) == 0 {
		fmt.Println("agent advertises no session config options")
		return
	}
	for _, o := range opts {
		values := make([]string, 0, len(o.Options))
		for _, v := range o.Options {
			if v.Value != "" { // grouped options nest their values one level down
				values = append(values, v.Value)
			}
		}
		fmt.Printf("config option %q (category %q) = %v; values: %v\n", o.ID, o.Category, o.CurrentValue, values)
	}
}
