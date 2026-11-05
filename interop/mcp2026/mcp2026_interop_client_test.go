package toolnexus

// Interop: the MCP 2026-07-28 CLIENT (change upgrade-mcp-2026-07-28, PR #142)
// against the MCPHandler 2026-07-28 SERVER (this branch). run.sh copies this
// file into a checkout of the client branch's golang/ and runs it with
// TOOLNEXUS_INTEROP_BASE pointing at golang/internal/interop/mcp2026server.

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
)

func interopBase(t *testing.T) string {
	b := os.Getenv("TOOLNEXUS_INTEROP_BASE")
	if b == "" {
		t.Skip("TOOLNEXUS_INTEROP_BASE not set (run interop/mcp2026/run.sh)")
	}
	return b
}

func interopLoad(t *testing.T, cfg map[string]any, opts McpLoadOptions) (*McpSource, McpServerResult) {
	t.Helper()
	var got McpServerResult
	opts.OnServer = func(r McpServerResult) { got = r }
	src, err := LoadMcpWithOptions(context.Background(), map[string]any{"srv": cfg}, opts)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(src.Close)
	if src.Status["srv"] != StatusConnected {
		t.Fatalf("not connected: %v %v", src.Status, got.Err)
	}
	return src, got
}

func interopTool(t *testing.T, src *McpSource, name string) Tool {
	t.Helper()
	for _, tl := range src.Tools {
		if tl.Name == name {
			return tl
		}
	}
	t.Fatalf("no tool %s (have %d)", name, len(src.Tools))
	return Tool{}
}

func interopRun(t *testing.T, tl Tool, args map[string]any) ToolResult {
	t.Helper()
	r, err := tl.Execute(args, &ToolContext{Ctx: context.Background()})
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func interopLog(t *testing.T, base string) (seen []string) {
	resp, err := http.Get(base + "/log")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var l struct{ Seen []string }
	_ = json.NewDecoder(resp.Body).Decode(&l)
	return l.Seen
}

func TestInterop2026Server(t *testing.T) {
	base := interopBase(t)
	answers := func(r Request) (Answer, error) {
		switch r.Prompt {
		case "What is your name?":
			return Answer{ID: r.ID, Ok: true, Data: map[string]any{"name": "toolnexus"}}, nil
		case "Really wipe?":
			return Answer{ID: r.ID, Ok: true, Data: map[string]any{"confirm": true}}, nil
		}
		t.Errorf("unexpected request %+v", r)
		return Answer{ID: r.ID, Ok: false}, nil
	}

	t.Run("discover, tools, MRTR input and confirm", func(t *testing.T) {
		src, _ := interopLoad(t, map[string]any{"url": base + "/open/mcp"}, McpLoadOptions{WaitFor: answers})
		names := []string{}
		for _, tl := range src.Tools {
			names = append(names, tl.Name)
		}
		if strings.Join(names, ",") != "srv_echo,srv_greet,srv_whoami,srv_wipe" {
			t.Fatalf("tools %v", names)
		}
		if r := interopRun(t, interopTool(t, src, "srv_echo"), map[string]any{"text": "hi"}); r.IsError || r.Output != `{"text":"hi"}` && r.Output != "hi" {
			t.Fatalf("echo %+v", r)
		}
		if r := interopRun(t, interopTool(t, src, "srv_greet"), nil); r.Output != "Hello, toolnexus!" {
			t.Fatalf("greet %+v", r)
		}
		if r := interopRun(t, interopTool(t, src, "srv_wipe"), nil); r.Output != "wiped" {
			t.Fatalf("wipe %+v", r)
		}
		for _, s := range interopLog(t, base) {
			if strings.HasPrefix(s, "open ") && !strings.HasPrefix(s, "open 2026-07-28 ") {
				t.Fatalf("non-2026 request on the 2026 mount: %q", s)
			}
		}
	})

	t.Run("MRTR without waitFor returns pending", func(t *testing.T) {
		src, _ := interopLoad(t, map[string]any{"url": base + "/open/mcp"}, McpLoadOptions{})
		r := interopRun(t, interopTool(t, src, "srv_greet"), nil)
		if req := PendingOf(r); req == nil || req.Kind != "input" || req.Prompt != "What is your name?" {
			t.Fatalf("pending %+v", r)
		}
	})

	t.Run("skills extension joins the skill inventory", func(t *testing.T) {
		tk, err := CreateToolkit(context.Background(), Options{McpConfig: map[string]any{"mcpServers": map[string]any{"srv": map[string]any{"url": base + "/open/mcp"}}}})
		if err != nil {
			t.Fatal(err)
		}
		defer tk.Close()
		skill, ok := tk.Get("skill")
		if !ok {
			t.Fatal("no skill tool")
		}
		r, _ := skill.Execute(map[string]any{"name": "hello-world"}, nil)
		local, _ := os.ReadFile("../examples/skills/hello-world/SKILL.md")
		_, body, _, _ := parseFrontmatter(string(local))
		if r.IsError || !strings.Contains(r.Output, strings.TrimSpace(body)) {
			t.Fatalf("remote skill body: %s", r.Output)
		}
	})

	t.Run("tool search over the served tools", func(t *testing.T) {
		src, _ := interopLoad(t, map[string]any{"url": base + "/open/mcp", "toolSearch": true, "description": "interop"}, McpLoadOptions{WaitFor: answers})
		if len(src.Tools) != 2 {
			t.Fatalf("tools %d", len(src.Tools))
		}
		r := interopRun(t, src.Tools[0], map[string]any{"query": "echo text"})
		if !strings.HasPrefix(r.Output, `[{"name":"echo"`) {
			t.Fatalf("search %s", r.Output)
		}
		if r := interopRun(t, src.Tools[1], map[string]any{"name": "greet"}); r.Output != "Hello, toolnexus!" {
			t.Fatalf("call_tool greet %+v", r)
		}
	})

	t.Run("OAuth: PRM, pre-registered clientId, PKCE, JWT, step-up", func(t *testing.T) {
		store := &MemoryTokenStore{}
		follow := func(u string) error {
			resp, err := http.Get(u)
			if err != nil {
				return err
			}
			return resp.Body.Close()
		}
		src, _ := interopLoad(t, map[string]any{"url": base + "/mcp", "auth": map[string]any{"clientId": "interop-client", "allowHttp": true}},
			McpLoadOptions{OpenURL: follow, TokenStore: store, WaitFor: answers})
		if r := interopRun(t, interopTool(t, src, "srv_echo"), map[string]any{"text": "authed"}); r.IsError {
			t.Fatalf("echo %+v", r)
		}
		// whoami needs mcp:whoami ⇒ 403 insufficient_scope ⇒ step-up ⇒ runs as dev-user.
		if r := interopRun(t, interopTool(t, src, "srv_whoami"), nil); r.Output != "dev-user" {
			t.Fatalf("whoami %+v", r)
		}
		tok, _ := store.Load(base + "/mcp")
		if tok == nil || !strings.Contains(strings.Join(tok.Scopes, " "), "mcp:whoami") {
			t.Fatalf("token scopes %+v", tok)
		}
	})

	t.Run("OAuth: Client ID Metadata Document as client_id", func(t *testing.T) {
		var docURL string
		doc := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_ = json.NewEncoder(w).Encode(map[string]any{"client_id": docURL, "client_name": "interop CIMD",
				"redirect_uris": []string{"http://127.0.0.1/callback"}})
		}))
		defer doc.Close()
		docURL = doc.URL + "/client.json"
		follow := func(u string) error {
			if !strings.Contains(u, "client_id="+url.QueryEscape(docURL)) {
				t.Errorf("authorize URL does not carry the CIMD client_id: %s", u)
			}
			resp, err := http.Get(u)
			if err != nil {
				return err
			}
			return resp.Body.Close()
		}
		src, _ := interopLoad(t, map[string]any{"url": base + "/mcp", "auth": map[string]any{"clientMetadataUrl": docURL, "allowHttp": true}},
			McpLoadOptions{OpenURL: follow})
		if r := interopRun(t, interopTool(t, src, "srv_echo"), map[string]any{"text": "cimd"}); r.IsError {
			t.Fatalf("echo %+v", r)
		}
	})

	t.Run("OAuth: no client identity is a named error", func(t *testing.T) {
		src, err := LoadMcpWithOptions(context.Background(), map[string]any{"srv": map[string]any{"url": base + "/mcp", "auth": map[string]any{"allowHttp": true}}}, McpLoadOptions{OpenURL: func(string) error { return nil }})
		if err != nil {
			t.Fatal(err)
		}
		defer src.Close()
		if src.Status["srv"] != StatusFailed {
			t.Fatalf("status %v", src.Status)
		}
	})

	t.Run("legacy-only handler: client falls back", func(t *testing.T) {
		src, _ := interopLoad(t, map[string]any{"url": base + "/legacy/mcp"}, McpLoadOptions{})
		if r := interopRun(t, interopTool(t, src, "srv_echo"), map[string]any{"text": "old"}); r.IsError {
			t.Fatalf("fallback echo %+v", r)
		}
	})
}
