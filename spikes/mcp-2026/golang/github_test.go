package main

// Live interop against GitHub's hosted MCP server (speaks 2026-07-28).
// Gated: GITHUB_MCP=1 GH_TOKEN=$(gh auth token) go test -run GitHub -v .
// Read-only: discover, tools/list, and the get_me tool. The token is use-only, never logged.

import (
	"fmt"
	"os"
	"os/exec"
	"testing"
)

const githubMCP = "https://api.githubcopilot.com/mcp/"

func TestGitHubLive(t *testing.T) {
	if os.Getenv("GITHUB_MCP") != "1" || os.Getenv("GH_TOKEN") == "" {
		t.Skip("set GITHUB_MCP=1 and GH_TOKEN")
	}
	c := NewClient(githubMCP)
	c.headerOverride = map[string]string{"Authorization": "Bearer " + os.Getenv("GH_TOKEN")}

	d, err := c.Call("server/discover", obj{})
	if err != nil {
		t.Fatalf("server/discover: %v", err)
	}
	t.Logf("discover: serverInfo=%v supportedVersions=%v resultType=%v", d["serverInfo"], d["supportedVersions"], d["resultType"])

	l, err := c.Call("tools/list", obj{})
	if err != nil {
		t.Fatalf("tools/list: %v", err)
	}
	tools, _ := l["tools"].([]any)
	t.Logf("tools/list: %d tools, ttlMs=%v cacheScope=%v nextCursor=%v", len(tools), l["ttlMs"], l["cacheScope"], l["nextCursor"])
	if len(tools) == 0 {
		t.Fatal("no tools")
	}

	r, err := c.CallTool("get_me", obj{}, nil)
	if err != nil {
		t.Fatalf("get_me: %v", err)
	}
	for k, v := range r {
		s := fmt.Sprint(v)
		if len(s) > 60 {
			s = s[:60]
		}
		if k == "structuredContent" {
			s = "<redacted>"
		}
		t.Logf("  key %s: %T %s", k, v, s)
	}
	t.Logf("get_me: resultType=%v isError=%v content-items=%d", r["resultType"], r["isError"], len(anyList(r["content"])))
}

// Full OAuth against GitHub: PRM -> GitHub AS -> pre-registered OAuth App (GitHub has no CIMD/DCR)
// -> PKCE login in the chrome-agent browser -> loopback callback -> tools.
// Gated: GITHUB_OAUTH=1, client id/secret injected by `sec run`.
func TestGitHubOAuthLive(t *testing.T) {
	if os.Getenv("GITHUB_OAUTH") != "1" {
		t.Skip("set GITHUB_OAUTH=1 (run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET)")
	}
	c := NewClient(githubMCP)
	c.Auth = &OAuth{
		ClientID:     os.Getenv("GITHUB_MCP_CLIENT_ID"),
		ClientSecret: os.Getenv("GITHUB_MCP_CLIENT_SECRET"),
		Authorizer: LoopbackAuthorizer{Open: func(u string) error {
			// the user's browser = the chrome-agent profile; open the GitHub consent page there
			return exec.Command("chrome-agent", "goto", u).Run()
		}},
	}
	r, err := c.CallTool("get_me", obj{}, nil)
	if err != nil {
		t.Fatalf("get_me via OAuth: %v", err)
	}
	t.Logf("get_me via OAuth: resultType=%v content-items=%d", r["resultType"], len(anyList(r["content"])))
	l, err := c.Call("tools/list", obj{})
	if err != nil {
		t.Fatalf("tools/list: %v", err)
	}
	t.Logf("tools/list via OAuth: %d tools", len(anyList(l["tools"])))
}

func anyList(v any) []any { s, _ := v.([]any); return s }
