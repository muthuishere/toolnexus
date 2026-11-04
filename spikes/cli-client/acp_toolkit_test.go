package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

// TestMain doubles as the fake ACP agent (FAKE_ACP=1) and the stdio bridge
// (`<test binary> bridge <url>`), so the tests need no network and no agent.
func TestMain(m *testing.M) {
	if len(os.Args) == 3 && os.Args[1] == "bridge" {
		if err := RunBridge(os.Args[2], os.Stdin, os.Stdout); err != nil {
			os.Exit(1)
		}
		os.Exit(0)
	}
	if os.Getenv("FAKE_ACP") == "1" {
		fakeAgent()
		os.Exit(0)
	}
	os.Exit(m.Run())
}

// fakeAgent: advertises mcpCapabilities.http = FAKE_HTTP, records the
// session/new mcpServers, and on a prompt asks permission for one of our tools
// and for its own "Bash", then calls get_badge_number on the handed MCP server
// (http POST, or by spawning the stdio entry) and echoes the result.
func fakeAgent() {
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 0, 1<<16), 1<<24)
	out := json.NewEncoder(os.Stdout)
	var entry map[string]any
	id := 1000
	ask := func(title string) string {
		id++
		_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": id, "method": "session/request_permission", "params": map[string]any{
			"sessionId": "s1", "toolCall": map[string]any{"toolCallId": "t", "title": title},
			"options": []any{map[string]any{"optionId": "yes", "kind": "allow_once"}, map[string]any{"optionId": "no", "kind": "reject_once"}}}})
		for in.Scan() {
			var r struct {
				ID     int `json:"id"`
				Result struct {
					Outcome struct {
						OptionID string `json:"optionId"`
					} `json:"outcome"`
				} `json:"result"`
			}
			if json.Unmarshal(in.Bytes(), &r) == nil && r.ID == id {
				return r.Result.Outcome.OptionID
			}
		}
		return ""
	}
	for in.Scan() {
		var m struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
		}
		_ = json.Unmarshal(in.Bytes(), &m)
		switch m.Method {
		case "initialize":
			_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{"protocolVersion": 1,
				"agentCapabilities": map[string]any{"mcpCapabilities": map[string]any{"http": os.Getenv("FAKE_HTTP") == "1", "sse": false}}}})
		case "session/new":
			var p struct {
				MCPServers []map[string]any `json:"mcpServers"`
			}
			_ = json.Unmarshal(m.Params, &p)
			if len(p.MCPServers) == 1 {
				entry = p.MCPServers[0]
			}
			_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{"sessionId": "s1"}})
		case "session/prompt":
			ours := ask("mcp__toolnexus__get_badge_number")
			own := ask("Bash")
			text := fmt.Sprintf("perm ours=%s own=%s; ", ours, own) + callBadge(entry)
			_ = out.Encode(map[string]any{"jsonrpc": "2.0", "method": "session/update", "params": map[string]any{"sessionId": "s1",
				"update": map[string]any{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": text}}}})
			_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{"stopReason": "end_turn"}})
		}
	}
}

func callBadge(entry map[string]any) string {
	reqs := []string{
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"fake","version":"0"}}}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_badge_number","arguments":{}}}`,
	}
	var last []byte
	if entry["type"] == "http" {
		for _, r := range reqs {
			req, _ := http.NewRequest("POST", entry["url"].(string), strings.NewReader(r))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Accept", "application/json, text/event-stream")
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				return "http error"
			}
			last, _ = io.ReadAll(resp.Body)
			resp.Body.Close()
		}
	} else {
		var args []string
		for _, a := range entry["args"].([]any) {
			args = append(args, a.(string))
		}
		cmd := exec.Command(entry["command"].(string), args...)
		cmd.Stdin = strings.NewReader(strings.Join(reqs, "\n") + "\n")
		b, err := cmd.Output()
		if err != nil {
			return "bridge error"
		}
		lines := bytes.Split(bytes.TrimSpace(b), []byte("\n"))
		last = lines[len(lines)-1]
	}
	return "entry=" + fmt.Sprint(entry["type"]) + " reply=" + string(last)
}

func badgeToolkit(t *testing.T) *tn.Toolkit {
	t.Helper()
	badge := tn.NativeTool("get_badge_number", "badge", tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		func(context.Context, map[string]any) (string, error) { return "BADGE-77Q", nil })
	tk, err := tn.CreateToolkit(context.Background(), tn.Options{ExtraTools: []tn.Tool{badge}, Builtins: false})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(tk.Close)
	return tk
}

func openFake(t *testing.T, http bool, force bool) *ToolkitSession {
	t.Helper()
	self, _ := os.Executable()
	env := []string{"FAKE_ACP=1", "FAKE_HTTP=0"}
	if http {
		env[1] = "FAKE_HTTP=1"
	}
	s, err := OpenToolkitSession(context.Background(), badgeToolkit(t), ACPAgent{Command: self, Env: env},
		ToolkitSessionOptions{ForceStdio: force, BridgeCommand: []string{self}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func TestACPHTTPEntryWhenAgentAcceptsHTTP(t *testing.T) {
	s := openFake(t, true, false)
	if s.MCPEntry["type"] != "http" || s.MCPEntry["name"] != "toolnexus" || s.MCPEntry["url"] != s.MCPURL {
		t.Fatalf("entry = %v", s.MCPEntry)
	}
	if h, ok := s.MCPEntry["headers"].([]any); !ok || len(h) != 0 {
		t.Fatalf("headers must be an empty array: %v", s.MCPEntry["headers"])
	}
	tr, err := s.Prompt(context.Background(), "badge?")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(tr.Text, "BADGE-77Q") || len(tr.ServedTools) != 1 || tr.ServedTools[0] != "get_badge_number" {
		t.Fatalf("turn = %+v", tr)
	}
	if !strings.Contains(tr.Text, "ours=yes own=no") {
		t.Fatalf("permission hook must allow ours and reject the agent's own: %s", tr.Text)
	}
}

func TestACPStdioBridgeWhenAgentLacksHTTP(t *testing.T) {
	s := openFake(t, false, false)
	if _, has := s.MCPEntry["type"]; has || s.MCPEntry["name"] != "toolnexus" {
		t.Fatalf("stdio entry has no type field: %v", s.MCPEntry)
	}
	args := s.MCPEntry["args"].([]string)
	if args[len(args)-2] != "bridge" || args[len(args)-1] != s.MCPURL {
		t.Fatalf("args = %v", args)
	}
	tr, err := s.Prompt(context.Background(), "badge?")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(tr.Text, "entry=<nil>") || !strings.Contains(tr.Text, "BADGE-77Q") || len(tr.ServedTools) != 1 {
		t.Fatalf("turn = %+v", tr)
	}
}

func TestACPForceStdio(t *testing.T) {
	s := openFake(t, true, true)
	if _, has := s.MCPEntry["type"]; has {
		t.Fatalf("ForceStdio must send a stdio entry: %v", s.MCPEntry)
	}
}

func TestACPServerStopsWithSession(t *testing.T) {
	s := openFake(t, true, false)
	url := s.MCPURL
	_ = s.Close()
	if _, err := http.Post(url, "application/json", strings.NewReader("{}")); err == nil {
		t.Fatal("toolkit MCP server still listening after Close")
	}
}

func TestIsOursMatchesAgentSpellings(t *testing.T) {
	s := &ToolkitSession{serverName: "toolnexus", ourTools: map[string]bool{"get_badge_number": true, "skill": true}}
	for title, want := range map[string]bool{
		"mcp__toolnexus__get_badge_number": true, "toolnexus/skill": true, "get_badge_number": true,
		"skill": true, "Bash": false, "Read file": false, "toolnexus_skill": true,
	} {
		p, _ := json.Marshal(map[string]any{"toolCall": map[string]any{"title": title}})
		if got, _ := s.IsOurs(p); got != want {
			t.Errorf("IsOurs(%q) = %v, want %v", title, got, want)
		}
	}
}

func TestACPProjectMCPFileForAgentsThatIgnoreSessionServers(t *testing.T) {
	self, _ := os.Executable()
	s, err := OpenToolkitSession(context.Background(), badgeToolkit(t),
		ACPAgent{Command: self, Env: []string{"FAKE_ACP=1", "FAKE_HTTP=0"}, ProjectMCPFile: ".mcp.json"},
		ToolkitSessionOptions{BridgeCommand: []string{self}})
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(s.tempDir + "/.mcp.json")
	if err != nil {
		t.Fatal(err)
	}
	want := `{"mcpServers":{"toolnexus":{"type":"http","url":"` + s.MCPURL + `"}}}`
	if string(b) != want {
		t.Fatalf(".mcp.json = %s", b)
	}
	dir := s.tempDir
	_ = s.Close()
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatal("session temp cwd not removed on Close")
	}
}
