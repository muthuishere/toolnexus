// Live matrix for the ACP-primary path: every installed agent gets the user's
// own toolkit (an MCP server from mcp.json, a skill, a native tool) and must
// return three unguessable tokens that only those tools hold.
//
//	go build -o /tmp/x . && /tmp/x acp [agent ...]      -> acp-results.json
//	/tmp/x fixture-mcp                                  (stdio MCP fixture)
//	/tmp/x bridge <url>                                 (stdio -> HTTP MCP)
//	/tmp/x cli-matrix [style ...]                       (fallback path, same 3 checks)
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
	tn "github.com/muthuishere/toolnexus/golang"
)

// subcommand dispatches the non-default modes; false = run the old CLI live run.
func subcommand() bool {
	if len(os.Args) < 2 {
		return false
	}
	switch os.Args[1] {
	case "fixture-mcp":
		s := server.NewMCPServer("vault", "0.0.1", server.WithToolCapabilities(false))
		s.AddTool(mcp.NewTool("get_vault_code", mcp.WithDescription("Return the current vault access code. The code exists only here.")),
			func(context.Context, mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				return mcp.NewToolResultText("vault code: " + os.Getenv("FIXTURE_TOKEN")), nil
			})
		if err := server.ServeStdio(s); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	case "bridge":
		if err := RunBridge(os.Args[2], os.Stdin, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	case "acp":
		acpMatrix(os.Args[2:])
	case "cli-matrix":
		cliMatrix(os.Args[2:])
	default:
		return false
	}
	return true
}

// agents known to speak ACP on this machine (2026-11-04). npx adapters are not
// installed globally; npx caches them.
var acpAgents = map[string]ACPAgent{
	"claude":   {Command: "npx", Args: []string{"-y", "@agentclientprotocol/claude-agent-acp@0.100.0"}},
	"codex":    {Command: "npx", Args: []string{"-y", "@zed-industries/codex-acp@0.16.0"}, Mode: "read-only", Model: "gpt-6.1-sol"},
	"copilot":  {Command: "copilot", Args: []string{"--acp"}},
	"devin":    {Command: "devin", Args: []string{"acp"}, ProjectMCPFile: ".mcp.json"},
	"opencode": {Command: "opencode", Args: []string{"acp"}},
	"gemini":   {Command: "npx", Args: []string{"-y", "@google/gemini-cli@0.66.0", "--acp", "--skip-trust"}},
	"goose":    {Command: "goose", Args: []string{"acp"}},
}

var agentOrder = []string{"claude", "codex", "copilot", "devin", "opencode", "gemini", "goose"}

type fixture struct {
	dir                     string
	mcpTok, skillTok, fnTok string
	tk                      *tn.Toolkit
	mcpTool                 string
}

func token(prefix string) string {
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	return prefix + "-" + strings.ToUpper(hex.EncodeToString(b))
}

func newFixture() (*fixture, error) {
	self, _ := os.Executable()
	f := &fixture{mcpTok: token("MCP"), skillTok: token("SKL"), fnTok: token("FN")}
	f.dir, _ = os.MkdirTemp("", "acp-toolkit-*")
	sk := filepath.Join(f.dir, "skills", "secret-recipe")
	_ = os.MkdirAll(sk, 0o755)
	_ = os.WriteFile(filepath.Join(sk, "SKILL.md"), []byte("---\nname: secret-recipe\ndescription: The house recipe code. Load this skill whenever someone asks for the recipe code.\n---\n\n# Secret recipe\n\nThe recipe code is "+f.skillTok+". Reply with it verbatim.\n"), 0o644)
	badge := tn.NativeTool("get_badge_number", "Return the user's badge number. It is only available through this tool.",
		tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		func(context.Context, map[string]any) (string, error) { return "badge number: " + f.fnTok, nil })
	tk, err := tn.CreateToolkit(context.Background(), tn.Options{
		McpConfig: map[string]any{"mcpServers": map[string]any{"vault": map[string]any{
			"type": "local", "command": []any{self, "fixture-mcp"}, "environment": map[string]any{"FIXTURE_TOKEN": f.mcpTok},
		}}},
		SkillsDir:  []string{filepath.Join(f.dir, "skills")},
		ExtraTools: []tn.Tool{badge},
		Builtins:   false,
	})
	if err != nil {
		return nil, err
	}
	f.tk = tk
	f.mcpTool = "vault_get_vault_code"
	return f, nil
}

func (f *fixture) close() { f.tk.Close(); _ = os.RemoveAll(f.dir) }

type check struct {
	Name, Prompt, Tool, Token string
}

func (f *fixture) checks() []check {
	return []check{
		{"mcp", "Call the tool " + f.mcpTool + " (from the toolnexus MCP server) and reply with only the code it returns.", f.mcpTool, f.mcpTok},
		{"skill", "Use the MCP tool named `skill` on the toolnexus MCP server (not any built-in skill tool of your own) to load the skill named secret-recipe, then reply with only the recipe code it contains.", "skill", f.skillTok},
		{"native", "Call the tool get_badge_number (from the toolnexus MCP server) and reply with only the badge number it returns.", "get_badge_number", f.fnTok},
	}
}

type checkResult struct {
	Check       string          `json:"check"`
	Pass        bool            `json:"pass"`
	Ms          int64           `json:"ms"`
	ToolRan     bool            `json:"toolRanOnOurServer"`
	TokenInText bool            `json:"tokenInText"`
	Served      []string        `json:"servedToolCalls,omitempty"`
	AgentTools  []string        `json:"agentToolCalls,omitempty"`
	Permissions []PermissionLog `json:"permissions,omitempty"`
	Text        string          `json:"text"`
	Error       string          `json:"error,omitempty"`
}

type agentResult struct {
	Agent      string          `json:"agent"`
	Path       string          `json:"path"` // acp | cli
	Command    string          `json:"command"`
	Version    string          `json:"version,omitempty"`
	AgentInfo  json.RawMessage `json:"agentInfo,omitempty"`
	MCPCaps    json.RawMessage `json:"mcpCapabilities,omitempty"`
	MCPEntry   map[string]any  `json:"mcpServersEntry,omitempty"`
	OpenMs     int64           `json:"openMs"`
	Checks     []checkResult   `json:"checks"`
	Error      string          `json:"error,omitempty"`
	StderrTail string          `json:"stderrTail,omitempty"`
	SessionNew json.RawMessage `json:"sessionNew,omitempty"`
}

func acpMatrix(names []string) {
	if len(names) == 0 {
		names = agentOrder
	}
	forceStdio := os.Getenv("FORCE_STDIO") == "1"
	var all []agentResult
	for _, n := range names {
		a := acpAgents[n]
		r := agentResult{Agent: n, Path: "acp", Command: strings.Join(append([]string{a.Command}, a.Args...), " ")}
		if _, err := exec.LookPath(a.Command); err != nil {
			r.Error = "not installed"
			fmt.Printf("%-9s not installed\n", n)
			all = append(all, r)
			continue
		}
		r.Version = versionOf(n)
		f, err := newFixture()
		if err != nil {
			r.Error = err.Error()
			all = append(all, r)
			continue
		}
		start := time.Now()
		ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
		s, err := OpenToolkitSession(ctx, f.tk, a, ToolkitSessionOptions{ForceStdio: forceStdio})
		cancel()
		r.OpenMs = time.Since(start).Milliseconds()
		if err != nil {
			r.Error = err.Error()
			fmt.Printf("%-9s OPEN FAIL %s\n", n, trunc(r.Error, 300))
			all = append(all, r)
			f.close()
			continue
		}
		r.AgentInfo, r.MCPCaps, r.MCPEntry = s.AgentInfo, s.MCPCaps, redactURL(s.MCPEntry)
		for _, c := range f.checks() {
			t0 := time.Now()
			tr, err := s.Prompt(context.Background(), c.Prompt)
			cr := checkResult{Check: c.Name, Ms: time.Since(t0).Milliseconds(), Served: tr.ServedTools,
				AgentTools: tr.AgentTools, Permissions: tr.Permissions, Text: trunc(tr.Text, 300)}
			if err != nil {
				cr.Error = trunc(err.Error(), 400)
			}
			for _, sv := range tr.ServedTools {
				if sv == c.Tool {
					cr.ToolRan = true
				}
			}
			cr.TokenInText = strings.Contains(tr.Text, c.Token)
			cr.Pass = cr.ToolRan && cr.TokenInText
			fmt.Printf("%-9s %-6s pass=%v ran=%v token=%v %dms served=%v perms=%d err=%s\n", n, c.Name, cr.Pass, cr.ToolRan, cr.TokenInText, cr.Ms, cr.Served, len(cr.Permissions), cr.Error)
			r.Checks = append(r.Checks, cr)
		}
		r.StderrTail = trunc(tail(s.Stderr.String(), 300), 300)
		r.SessionNew = s.SessionNew
		if p := os.Getenv("STDERR_FILE"); p != "" {
			_ = os.WriteFile(p, s.Stderr.Bytes(), 0o600)
		}
		_ = s.Close()
		f.close()
		all = append(all, r)
	}
	out := os.Getenv("RESULTS")
	if out == "" {
		out = "acp-results.json"
	}
	b, _ := json.MarshalIndent(all, "", "  ")
	_ = os.WriteFile(out, append(b, '\n'), 0o644)
}

// redactURL keeps the shape but not the ephemeral port.
func redactURL(e map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range e {
		out[k] = v
	}
	if u, ok := out["url"].(string); ok {
		out["url"] = strings.SplitN(u, ":", 3)[0] + "://127.0.0.1:<port>/mcp"
	}
	if a, ok := out["args"].([]string); ok && len(a) > 0 {
		out["command"] = "<this binary>"
		out["args"] = []string{"bridge", "http://127.0.0.1:<port>/mcp"}
	}
	return out
}

func versionOf(n string) string {
	bin := map[string][]string{"claude": {"claude", "--version"}, "codex": {"codex", "--version"},
		"copilot": {"copilot", "--version"}, "devin": {"devin", "--version"}, "opencode": {"opencode", "--version"}}[n]
	if bin == nil {
		return acpAgents[n].Args[1]
	}
	out, err := exec.Command(bin[0], bin[1:]...).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(strings.SplitN(string(out), "\n", 2)[0])
}

// cliMatrix runs the same three checks through the CLI-client fallback, where
// the toolnexus loop (not the CLI) executes the tools.
func cliMatrix(names []string) {
	var all []agentResult
	for _, n := range names {
		r := agentResult{Agent: n, Path: "cli", Command: n, Version: versionOf(n)}
		f, err := newFixture()
		if err != nil {
			r.Error = err.Error()
			all = append(all, r)
			continue
		}
		for _, c := range f.checks() {
			o := CliOptions{Style: styleByName(n), Command: n, TimeoutMs: 300000}
			o.MaxTurns = 5
			cl, err := CreateCliClient(o, nil)
			if err != nil {
				r.Error = err.Error()
				break
			}
			t0 := time.Now()
			res, err := cl.Run(context.Background(), cliPrompt(c.Prompt), f.tk)
			cr := checkResult{Check: c.Name, Ms: time.Since(t0).Milliseconds(), Text: trunc(res.Text, 300)}
			if err != nil {
				cr.Error = trunc(err.Error(), 400)
			}
			for _, tc := range res.ToolCalls {
				cr.Served = append(cr.Served, tc.Name)
				if tc.Name == c.Tool {
					cr.ToolRan = true
				}
			}
			cr.TokenInText = strings.Contains(res.Text, c.Token)
			cr.Pass = cr.ToolRan && cr.TokenInText
			fmt.Printf("%-9s cli %-6s pass=%v ran=%v token=%v %dms err=%s\n", n, c.Name, cr.Pass, cr.ToolRan, cr.TokenInText, cr.Ms, cr.Error)
			r.Checks = append(r.Checks, cr)
		}
		f.close()
		all = append(all, r)
	}
	out := os.Getenv("RESULTS")
	if out == "" {
		out = "cli-matrix-results.json"
	}
	b, _ := json.MarshalIndent(all, "", "  ")
	_ = os.WriteFile(out, append(b, '\n'), 0o644)
}

func cliPrompt(p string) string {
	p = strings.ReplaceAll(p, " (from the toolnexus MCP server)", "")
	return strings.ReplaceAll(p, "the MCP tool named `skill` on the toolnexus MCP server (not any built-in skill tool of your own)", "the `skill` tool")
}
