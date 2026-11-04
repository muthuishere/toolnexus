// A CLI client end to end: your installed agent CLI is the model, and your own
// toolkit (an MCP server from mcp config, a skill, a native tool) is what it
// calls. Also the live check for SPEC §8C.
//
//	go run ./examples/cliclient claude opencode            # Auto (ACP when available)
//	go run ./examples/cliclient -transport cli claude      # force the one-shot CLI
//
// claude has no ACP mode of its own, so it is launched through the ACP adapter
// (ACPCommand). Each run makes three fresh random tokens; a check passes only
// when the toolkit itself executed the tool AND the answer contains the token.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"

	tn "github.com/muthuishere/toolnexus/golang"
)

func token(prefix string) string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return prefix + "-" + strings.ToUpper(hex.EncodeToString(b))
}

// fixtureMCP is a stdio MCP server with one tool, vault_get_code.
func fixtureMCP() {
	s := server.NewMCPServer("vault", "0.1.0")
	s.AddTool(mcp.NewTool("get_code", mcp.WithDescription("Returns the vault code.")),
		func(context.Context, mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			return mcp.NewToolResultText(os.Getenv("VAULT_CODE")), nil
		})
	_ = server.ServeStdio(s)
}

type check struct{ name, prompt, token, tool string }

var suffix string

func main() {
	if len(os.Args) > 1 && os.Args[1] == "fixture-mcp" {
		fixtureMCP()
		return
	}
	transport := flag.String("transport", "auto", "auto | acp | cli")
	model := flag.String("model", "", "model id, passed through unchanged")
	flag.StringVar(&suffix, "suffix", "", "text appended to every prompt (e.g. to name the MCP server)")
	flag.Parse()

	styles := map[string]tn.CliOptions{
		"claude":   {Style: tn.CliStyles.Claude, Command: "claude", ACPCommand: []string{"npx", "-y", "@agentclientprotocol/claude-agent-acp@0.100.0"}},
		"opencode": {Style: tn.CliStyles.Opencode, Command: "opencode"},
		"copilot":  {Style: tn.CliStyles.Copilot, Command: "copilot"},
		"devin":    {Style: tn.CliStyles.Devin, Command: "devin"},
		"codex":    {Style: tn.CliStyles.Codex, Command: "codex"},
		"gemini":   {Style: tn.CliStyles.Gemini, Command: "gemini"},
		"goose":    {Style: tn.CliStyles.Goose, Command: "goose"},
	}
	var results []map[string]any
	for _, name := range flag.Args() {
		o, ok := styles[name]
		if !ok {
			fmt.Fprintln(os.Stderr, "unknown style", name)
			os.Exit(2)
		}
		switch *transport {
		case "acp":
			o.Transport = tn.CliTransports.ACP
		case "cli":
			o.Transport = tn.CliTransports.CLI
		}
		o.Model = *model
		results = append(results, runStyle(name, o)...)
	}
	b, _ := json.MarshalIndent(results, "", "  ")
	fmt.Println(string(b))
}

func runStyle(name string, o tn.CliOptions) []map[string]any {
	ctx := context.Background()
	codes := map[string]string{"mcp": token("VAULT"), "skill": token("RECIPE"), "native": token("BADGE")}
	dir, _ := os.MkdirTemp("", "cliclient-*")
	defer os.RemoveAll(dir)
	skillDir := filepath.Join(dir, "skills", "secret-recipe")
	_ = os.MkdirAll(skillDir, 0o700)
	_ = os.WriteFile(filepath.Join(skillDir, "SKILL.md"), []byte("---\nname: secret-recipe\ndescription: The secret recipe code.\n---\nThe secret recipe code is "+codes["skill"]+".\n"), 0o600)
	self, _ := os.Executable()
	badge := tn.NativeTool("get_badge_number", "Returns the user's badge number.", nil,
		func(context.Context, map[string]any) (string, error) { return codes["native"], nil })
	tk, err := tn.CreateToolkit(ctx, tn.Options{
		McpConfig: map[string]any{"mcpServers": map[string]any{"vault": map[string]any{"type": "local", "command": []any{self, "fixture-mcp"},
			"environment": map[string]any{"VAULT_CODE": codes["mcp"]}}}},
		SkillsDir: []string{filepath.Join(dir, "skills")}, ExtraTools: []tn.Tool{badge}, Builtins: false,
	})
	if err != nil {
		return []map[string]any{{"style": name, "error": err.Error()}}
	}
	defer tk.Close()

	checks := []check{
		{"mcp", "What is the vault code? Use the vault_get_code tool.", codes["mcp"], "vault_get_code"},
		{"skill", "Load the secret-recipe skill and tell me the secret recipe code.", codes["skill"], "load_skill"},
		{"native", "What is my badge number? Use the get_badge_number tool.", codes["native"], "get_badge_number"},
	}
	var out []map[string]any
	for _, c := range checks {
		o.Toolkit = tk
		o.SystemPrompt = "You are a terse assistant. Answer in one short sentence and include any code verbatim."
		client, err := tn.CreateCliClient(o)
		if err != nil {
			out = append(out, map[string]any{"style": name, "check": c.name, "error": err.Error()})
			continue
		}
		start := time.Now()
		res, err := client.Run(ctx, strings.TrimSpace(c.prompt+" "+suffix), tk)
		row := map[string]any{"style": name, "check": c.name, "transport": client.Transport(), "ms": time.Since(start).Milliseconds()}
		ran := false
		if info, ok := client.ACPSession(); ok {
			for _, s := range info.ServedCalls {
				ran = ran || s == c.tool
			}
			row["servedCalls"] = info.ServedCalls
			row["permissions"] = info.Permissions
		}
		for _, tc := range res.ToolCalls {
			n := tc.Name
			if n == "skill" {
				n = "load_skill"
			}
			ran = ran || n == c.tool
		}
		if r := client.FallbackReason(); r != "" {
			row["fallback"] = r
		}
		if err != nil {
			row["error"] = err.Error()
		}
		row["text"] = res.Text
		row["pass"] = err == nil && ran && strings.Contains(res.Text, c.token)
		_ = client.Close()
		out = append(out, row)
		fmt.Fprintf(os.Stderr, "%s %s %s pass=%v %dms\n", name, c.name, row["transport"], row["pass"], row["ms"])
	}
	return out
}
