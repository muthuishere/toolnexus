package mcp2026

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// hundredToolServer: 10 services x 10 verbs, realistic descriptions + schemas.
func hundredToolServer(t *testing.T) *httptest.Server {
	t.Helper()
	services := [][2]string{{"github", "issue"}, {"slack", "message"}, {"jira", "ticket"}, {"gmail", "email"},
		{"calendar", "event"}, {"drive", "file"}, {"stripe", "invoice"}, {"postgres", "row"}, {"s3", "object"}, {"notion", "page"}}
	verbs := []string{"create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment"}
	var tools []obj
	for _, s := range services {
		for _, v := range verbs {
			tools = append(tools, obj{
				"name":        fmt.Sprintf("%s_%s_%s", s[0], v, s[1]),
				"description": fmt.Sprintf("%s a %s in %s. Requires the workspace to be connected; returns the %s as JSON with its id, timestamps and owner.", v, s[1], s[0], s[1]),
				"inputSchema": obj{"type": "object", "properties": obj{
					"id":      obj{"type": "string", "description": "The " + s[1] + " id (omit when creating)."},
					"query":   obj{"type": "string", "description": "Free-text filter applied server-side."},
					"body":    obj{"type": "object", "description": "Fields of the " + s[1] + " to write."},
					"options": obj{"type": "object", "properties": obj{"dryRun": obj{"type": "boolean"}, "limit": obj{"type": "integer"}}},
				}, "required": []string{"query"}},
			})
		}
	}
	dir := t.TempDir()
	b, _ := json.Marshal(obj{"server": obj{"name": "big", "version": "1"}, "tools": tools, "skillsDir": "."})
	os.WriteFile(filepath.Join(dir, "f.json"), b, 0o644)
	s, err := LoadServer(filepath.Join(dir, "f.json"))
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(s)
	t.Cleanup(ts.Close)
	return ts
}

func TestToolSearchRankingAndSize(t *testing.T) {
	ts := hundredToolServer(t)
	eager := NewClient(ts.URL + "/mcp")
	lazy := NewClient(ts.URL + "/mcp")
	lazy.ToolSearch, lazy.Description = true, "SaaS workspace (GitHub, Slack, Jira, ...)"
	all, err := eager.Tools()
	if err != nil || len(all) != 100 {
		t.Fatalf("%d %v", len(all), err)
	}
	meta, _ := lazy.Tools()
	for q, want := range map[string]string{"create slack message": "slack_create_message",
		"search stripe invoices": "stripe_search_invoice", "archive a notion page": "notion_archive_page", "s3 export": "s3_export_object"} {
		hits, err := lazy.SearchTools(q, 5)
		if err != nil || len(hits) == 0 || hits[0]["name"] != want || hits[0]["server"] != lazy.Description {
			t.Errorf("%q: top=%v err=%v", q, hits, err)
		}
	}
	r, _ := lazy.Invoke("search_tools", obj{"query": "create slack message"}, nil)
	eb, lb, rb := len(mustJSON(all)), len(mustJSON(meta)), len(mustJSON(r["content"]))
	t.Logf("tool JSON bytes: eager(100 tools)=%d  lazy(2 meta-tools)=%d  + one search_tools result(5 hits)=%d", eb, lb, rb)
	if lb*10 > eb {
		t.Fatalf("lazy not smaller: %d vs %d", lb, eb)
	}
}

func TestToolSearchInvokeCallsFoundTool(t *testing.T) {
	ts := httptest.NewServer(mustServer())
	defer ts.Close()
	c := NewClient(ts.URL + "/mcp")
	c.ToolSearch = true
	hits, _ := c.SearchTools("echo text back", 3)
	if len(hits) == 0 || hits[0]["name"] != "echo" {
		t.Fatalf("%v", hits)
	}
	if r, err := c.Invoke("call_tool", obj{"name": "echo", "arguments": obj{"text": "found"}}, nil); err != nil || Text(r) != "found" {
		t.Fatalf("%v %v", r, err)
	}
	if _, err := c.Invoke("echo", obj{}, nil); err == nil {
		t.Fatal("direct tool names are hidden in ToolSearch mode")
	}
}
