// ACP (Agent Client Protocol) as the model behind the client loop — issue #96,
// ADR 0025 (openspec/changes/add-acp-model-source).
//
// HONEST HEADER: the "warm session" win this example prints is the ACP
// agent CLI's process-startup cost amortised across turns, NOT a
// protocol-level speedup — session/prompt itself is not faster than any
// other wire. See ADR 0025's measurements.
//
// This example spawns a real ACP agent CLI (devin or opencode), registers
// one trivial local tool, and drives it through the ordinary toolnexus
// tool-calling loop for two turns — proving that MCP, skills and native
// tools work unchanged when the model behind the loop is an ACP agent
// instead of an HTTP LLM. Requires the agent CLI installed and authenticated
// on PATH; it is NOT hermetic and is NOT run by CI.
//
//	go run ./examples/acp                       # spawns `devin acp` (default)
//	TOOLNEXUS_ACP_CMD="opencode acp" go run ./examples/acp   # or opencode instead
package main

import (
	"context"
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
	// Agent command is selectable: TOOLNEXUS_ACP_CMD (default "devin acp").
	// Both devin and opencode speak ACP live — `devin acp` and `opencode acp`
	// both answer `initialize` with protocolVersion 1.
	acpCmd := os.Getenv("TOOLNEXUS_ACP_CMD")
	if acpCmd == "" {
		acpCmd = "devin acp"
	}
	parts := strings.Fields(acpCmd)
	command, args := parts[0], parts[1:]

	_, thisFile, _, _ := runtime.Caller(0)
	root := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "examples")

	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
		McpConfig: filepath.Join(root, "mcp.json"),
		SkillsDir: []string{filepath.Join(root, "skills")},
	})
	if err != nil {
		log.Fatal(err)
	}
	defer tk.Close()

	// a trivial native tool — proves tool-calling works unchanged through ACP
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
	})
	if err != nil {
		log.Fatal("acp connect failed (is the CLI installed + authenticated?): ", err)
	}
	defer acp.Close()

	agent := toolnexus.CreateInProcessClient(toolnexus.InProcessOptions{
		Model:        command,
		Generate:     acp.Generate,
		SystemPrompt: "You are a precise agent. Use tools to compute and fetch facts.",
	})

	turns := []string{
		"What time is it right now? Use the clock tool.",
		"What did the clock tool just return, verbatim?",
	}

	// Two turns on the SAME warm ACP session/process — this is the whole
	// point: the process-startup cost was paid once by LoadACP, not per turn.
	for i, prompt := range turns {
		start := time.Now()
		res, err := agent.Run(ctx, prompt, tk)
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

	fmt.Println("\nGo ACP example OK — warm session across", len(turns), "turns")
}
