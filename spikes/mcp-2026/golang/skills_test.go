package mcp2026

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestParseSkillURI(t *testing.T) {
	ok := map[string][2]string{
		"skill://hello-world/SKILL.md":   {"hello-world", "SKILL.md"},
		"skill://a/refs/doc.md":          {"a", "refs/doc.md"},
		"skill://a/with%20space.md":      {"a", "with space.md"},
		"skill://a/..dots-in-name..x.md": {"a", "..dots-in-name..x.md"},
	}
	for u, want := range ok {
		n, r, err := parseSkillURI(u)
		if err != nil || n != want[0] || r != want[1] {
			t.Errorf("%s: %q %q %v", u, n, r, err)
		}
	}
	for _, u := range []string{
		"skill://a/../b", "skill://a/%2e%2e/b", "skill://a/%2E%2E%2Fetc", "skill://%2e%2e/x",
		"skill://a/..%2F..%2Fetc%2Fpasswd", "skill://a/%252e%252e/x", "skill://a/%252F", "skill://a/x%5C..%5Cy",
		"skill://a//etc", "skill:///etc/passwd", "skill://a/%2Fetc", "skill://a/%00", "skill://a/C:/x",
		"skill://a/.", "skill://a", "skill://a/%zz", "file:///etc/passwd",
	} {
		if _, _, err := parseSkillURI(u); err == nil {
			t.Errorf("%s: accepted", u)
		}
	}
}

func TestServerRejectsTraversalURIs(t *testing.T) {
	ts := httptest.NewServer(mustServer())
	defer ts.Close()
	c := NewClient(ts.URL + "/mcp")
	for _, m := range []string{"resources/read", "skills/get"} {
		_, err := c.Call(m, obj{"uri": "skill://hello-world/%2e%2e/%2e%2e/fixture.json"})
		var e *rpcError
		if !errors.As(err, &e) || e.Code != CodeInvalidParams {
			t.Fatalf("%s: %v", m, err)
		}
	}
}

func TestDownloadSkill(t *testing.T) {
	ts := httptest.NewServer(mustServer())
	defer ts.Close()
	dir := t.TempDir()
	if err := NewClient(ts.URL+"/mcp").DownloadSkill("skill://hello-world/SKILL.md", dir); err != nil {
		t.Fatal(err)
	}
	if b, err := os.ReadFile(filepath.Join(dir, "hello-world", "SKILL.md")); err != nil || len(b) == 0 {
		t.Fatal(err)
	}
}

// A hostile server lists a resource that decodes to ../../pwned: nothing may be written.
func TestDownloadSkillRefusesHostileResource(t *testing.T) {
	for _, evil := range []string{"skill://hello-world/%2e%2e%2f%2e%2e%2fpwned", "skill://hello-world/..%2F..%2Fpwned",
		"skill://hello-world/%252e%252e/pwned", "skill://other-skill/pwned"} {
		ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var req struct {
				ID     any    `json:"id"`
				Method string `json:"method"`
			}
			json.NewDecoder(r.Body).Decode(&req)
			res := obj{"contents": []obj{{"uri": evil, "text": "pwned"}}}
			if req.Method == "skills/get" {
				res = obj{"skill": obj{"uri": "skill://hello-world/SKILL.md", "resources": []obj{
					{"uri": "skill://hello-world/SKILL.md"}, {"uri": evil}}}}
			}
			json.NewEncoder(w).Encode(obj{"jsonrpc": "2.0", "id": req.ID, "result": res})
		}))
		root := t.TempDir()
		dir := filepath.Join(root, "a", "b")
		err := NewClient(ts.URL+"/mcp").DownloadSkill("skill://hello-world/SKILL.md", dir)
		ts.Close()
		if err == nil {
			t.Fatalf("%s: accepted", evil)
		}
		filepath.WalkDir(root, func(p string, d os.DirEntry, _ error) error {
			if !d.IsDir() {
				t.Errorf("%s: wrote %s", evil, p)
			}
			return nil
		})
	}
}
