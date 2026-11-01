// add-host-engine-needs + add-mcp-progress-timeout tests: §2 command/args
// normalization, FileConversationStore (§8 Conversation memory), and the MCP
// idle timeout against the shared examples/mcp-progress-server.mjs fixture.
package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- command + args ----

func TestCommandArgsNormalization(t *testing.T) {
	cases := []struct {
		name string
		json string
		want []string
	}{
		{"mcpServers string+args", `{"mcpServers":{"s":{"command":"npx","args":["-y","pkg"]}}}`, []string{"npx", "-y", "pkg"}},
		{"servers string+args", `{"servers":{"s":{"command":"npx","args":["-y","pkg"]}}}`, []string{"npx", "-y", "pkg"}},
		{"flat array form", `{"s":{"command":["node","server.js"]}}`, []string{"node", "server.js"}},
		{"array + args appended", `{"s":{"command":["node"],"args":["a.js"]}}`, []string{"node", "a.js"}},
		{"string without args", `{"s":{"command":"my-server"}}`, []string{"my-server"}},
		{"args with spaces", `{"s":{"command":"run","args":["hello world","--x=a b"]}}`, []string{"run", "hello world", "--x=a b"}},
		{"unicode path", `{"s":{"command":"/opt/тест/サーバー","args":["ü"]}}`, []string{"/opt/тест/サーバー", "ü"}},
		{"remote ignores args", `{"s":{"url":"http://x","args":7}}`, nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			cfg, err := ParseMcpConfig([]byte(c.json))
			if err != nil {
				t.Fatal(err)
			}
			if got := cfg["s"].Command; !reflect.DeepEqual(got, c.want) {
				t.Fatalf("argv = %q, want %q", got, c.want)
			}
		})
	}
}

func TestCommandArgsInvalid(t *testing.T) {
	cases := map[string]string{
		"empty string":       `{"s":{"command":""}}`,
		"empty array":        `{"s":{"command":[]}}`,
		"non-string element": `{"s":{"command":["node",1]}}`,
		"command number":     `{"s":{"command":42}}`,
		"args not array":     `{"s":{"command":"x","args":"-y"}}`,
		"args non-string":    `{"s":{"command":"x","args":["a",true]}}`,
	}
	for name, js := range cases {
		t.Run(name, func(t *testing.T) {
			_, err := ParseMcpConfig([]byte(js))
			if err == nil || !strings.Contains(err.Error(), `"s"`) {
				t.Fatalf("want error naming server, got %v", err)
			}
			field := "command"
			if strings.HasPrefix(name, "args") {
				field = "args"
			}
			if !strings.Contains(err.Error(), "invalid "+field) {
				t.Fatalf("want error naming %s, got %v", field, err)
			}
		})
	}
}

// ---- FileConversationStore ----

func TestFileStoreRequiresRoot(t *testing.T) {
	if _, err := NewFileConversationStore(""); err == nil {
		t.Fatal("empty root must error")
	}
}

func TestFileStoreRoundtripAcrossInstances(t *testing.T) {
	root := filepath.Join(t.TempDir(), "convs")
	a, _ := NewFileConversationStore(root)
	msgs := []any{map[string]any{"role": "user", "content": "hi ✓"}}
	if err := a.Save("c1", msgs); err != nil {
		t.Fatal(err)
	}
	b, _ := NewFileConversationStore(root)
	got, err := b.Get("c1")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, msgs) {
		t.Fatalf("got %v", got)
	}
	if none, err := b.Get("missing"); err != nil || none != nil {
		t.Fatalf("missing: %v %v", none, err)
	}
	data, _ := os.ReadFile(filepath.Join(root, "c1.json"))
	var body map[string]any
	_ = json.Unmarshal(data, &body)
	if body["version"] != float64(1) {
		t.Fatalf("body %s", data)
	}
	if st, _ := os.Stat(root); st.Mode().Perm() != 0o700 {
		t.Fatalf("root mode %v", st.Mode().Perm())
	}
}

func TestFileStoreRejectsTraversalIDs(t *testing.T) {
	s, _ := NewFileConversationStore(t.TempDir())
	for _, id := range []string{"", ".", "..", "../x", "a/b", `a\b`, "x y", strings.Repeat("a", 129)} {
		if _, err := s.Get(id); err == nil {
			t.Errorf("Get(%q) should error", id)
		}
		if err := s.Save(id, nil); err == nil {
			t.Errorf("Save(%q) should error", id)
		}
		if err := s.Delete(id); err == nil {
			t.Errorf("Delete(%q) should error", id)
		}
	}
}

func TestFileStoreCorruptAndUnknownVersion(t *testing.T) {
	root := t.TempDir()
	s, _ := NewFileConversationStore(root)
	_ = os.WriteFile(filepath.Join(root, "bad.json"), []byte("{not json"), 0o600)
	if _, err := s.Get("bad"); err == nil {
		t.Fatal("corrupt must error")
	}
	_ = os.WriteFile(filepath.Join(root, "v2.json"), []byte(`{"version":2,"messages":[]}`), 0o600)
	if _, err := s.Get("v2"); err == nil {
		t.Fatal("unknown version must error")
	}
}

func TestFileStoreListOrderAndDelete(t *testing.T) {
	root := t.TempDir()
	s, _ := NewFileConversationStore(root)
	for _, id := range []string{"b", "a", "old", "new"} {
		if err := s.Save(id, []any{}); err != nil {
			t.Fatal(err)
		}
	}
	base := time.Now().Add(-time.Hour)
	_ = os.Chtimes(filepath.Join(root, "old.json"), base, base)
	_ = os.Chtimes(filepath.Join(root, "a.json"), base.Add(time.Minute), base.Add(time.Minute))
	_ = os.Chtimes(filepath.Join(root, "b.json"), base.Add(time.Minute), base.Add(time.Minute))
	_ = os.WriteFile(filepath.Join(root, ".x.abc.tmp"), []byte("x"), 0o600)
	_ = os.WriteFile(filepath.Join(root, "bad name.json"), []byte("{}"), 0o600)
	list, err := s.List()
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, e := range list {
		ids = append(ids, e.ID)
	}
	if want := []string{"new", "a", "b", "old"}; !reflect.DeepEqual(ids, want) {
		t.Fatalf("order %v want %v", ids, want)
	}
	if err := s.Delete("a"); err != nil {
		t.Fatal(err)
	}
	if err := s.Delete("a"); err != nil {
		t.Fatalf("delete must be idempotent: %v", err)
	}
	if got, _ := s.Get("a"); got != nil {
		t.Fatal("deleted id still present")
	}
}

func TestFileStoreConcurrentSavesSameID(t *testing.T) {
	root := t.TempDir()
	s, _ := NewFileConversationStore(root)
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			msgs := make([]any, i+1)
			for j := range msgs {
				msgs[j] = fmt.Sprintf("m%d-%d", i, j)
			}
			if err := s.Save("same", msgs); err != nil {
				t.Error(err)
			}
		}(i)
	}
	wg.Wait()
	if _, err := s.Get("same"); err != nil {
		t.Fatalf("file not valid after concurrent saves: %v", err)
	}
	entries, _ := os.ReadDir(root)
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".tmp") {
			t.Fatalf("temp file left: %s", e.Name())
		}
	}
	if len(entries) != 1 {
		t.Fatalf("want exactly one file, got %d", len(entries))
	}
}

func TestInMemoryStoreListDelete(t *testing.T) {
	s := NewInMemoryConversationStore()
	_ = s.Save("x", []any{1})
	time.Sleep(2 * time.Millisecond)
	_ = s.Save("y", []any{2})
	list, _ := s.List()
	if len(list) != 2 || list[0].ID != "y" || list[1].ID != "x" {
		t.Fatalf("list %v", list)
	}
	_ = s.Delete("x")
	if err := s.Delete("x"); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.Get("x"); got != nil {
		t.Fatal("not deleted")
	}
}

// ---- MCP idle timeout (shared fixture) ----

func progressFixture(t *testing.T, timeoutMs int) (*McpSource, Tool) {
	t.Helper()
	if _, err := exec.LookPath("node"); err != nil {
		t.Skip("node not on PATH")
	}
	fixture, _ := filepath.Abs("../examples/mcp-progress-server.mjs")
	cfg := McpConfig{"progress": ServerConfig{Command: []string{"node", fixture}, Timeout: timeoutMs}}
	src, err := LoadMcp(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(src.Close)
	for _, tl := range src.Tools {
		if tl.Name == "progress_slow" {
			return src, tl
		}
	}
	t.Fatalf("progress_slow not loaded (status %v)", src.Status)
	return nil, Tool{}
}

func TestMcpIdleTimeoutProgressKeepsCallAlive(t *testing.T) {
	_, tl := progressFixture(t, 300)
	res, err := tl.Execute(map[string]any{"totalMs": 1200, "intervalMs": 100}, nil)
	if err != nil || res.IsError {
		t.Fatalf("call failed: %v %+v", err, res)
	}
	var out struct {
		ProgressTokenReceived bool `json:"progressTokenReceived"`
		NotificationsSent     int  `json:"notificationsSent"`
	}
	if err := json.Unmarshal([]byte(res.Output), &out); err != nil {
		t.Fatal(err)
	}
	if !out.ProgressTokenReceived || out.NotificationsSent == 0 {
		t.Fatalf("got %+v", out)
	}
}

func TestMcpIdleTimeoutSilenceTimesOut(t *testing.T) {
	_, tl := progressFixture(t, 300)
	start := time.Now()
	res, _ := tl.Execute(map[string]any{"totalMs": 1200, "intervalMs": 0}, nil)
	if !res.IsError || !strings.Contains(res.Output, "timed out") {
		t.Fatalf("want idle timeout, got %+v", res)
	}
	if el := time.Since(start); el > 1000*time.Millisecond {
		t.Fatalf("timed out too late: %v", el)
	}
}

func TestMcpIdleTimeoutWrongTokenDoesNotReset(t *testing.T) {
	_, tl := progressFixture(t, 300)
	res, _ := tl.Execute(map[string]any{"totalMs": 1200, "intervalMs": 100, "wrongToken": true}, nil)
	if !res.IsError || !strings.Contains(res.Output, "timed out") {
		t.Fatalf("unmatched token must not reset: %+v", res)
	}
}

func TestMcpIdleTimeoutCancellationWins(t *testing.T) {
	_, tl := progressFixture(t, 300)
	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(150*time.Millisecond, cancel)
	start := time.Now()
	res, _ := tl.Execute(map[string]any{"totalMs": 3000, "intervalMs": 50}, &ToolContext{Ctx: ctx})
	if !res.IsError {
		t.Fatalf("cancelled call must fail: %+v", res)
	}
	if el := time.Since(start); el > 1000*time.Millisecond {
		t.Fatalf("cancel not prompt: %v", el)
	}
	if strings.Contains(res.Output, "timed out") {
		t.Fatalf("cancel misreported as idle timeout: %s", res.Output)
	}
}

func TestFileStoreSyncModes(t *testing.T) {
	if _, err := NewFileConversationStoreWith(t.TempDir(), FileStoreOptions{Sync: "bogus"}); err == nil {
		t.Fatal("unknown sync mode must be an error")
	}
	for _, m := range []FileStoreSync{"", SyncFull, SyncFsync, SyncNone} {
		root := t.TempDir()
		s, err := NewFileConversationStoreWith(root, FileStoreOptions{Sync: m})
		if err != nil {
			t.Fatalf("mode %q: %v", m, err)
		}
		msgs := []any{map[string]any{"role": "user", "content": "hi"}}
		if err := s.Save("c1", msgs); err != nil {
			t.Fatalf("mode %q save: %v", m, err)
		}
		s2, _ := NewFileConversationStore(root)
		got, err := s2.Get("c1")
		if err != nil || len(got) != 1 {
			t.Fatalf("mode %q roundtrip: %v %v", m, got, err)
		}
		ents, _ := os.ReadDir(root)
		if len(ents) != 1 {
			t.Fatalf("mode %q left temp files: %v", m, ents)
		}
	}
}

func BenchmarkFileStoreSave200KB(b *testing.B) {
	payload := strings.Repeat("x", 200*1024)
	msgs := []any{map[string]any{"role": "user", "content": payload}}
	for _, m := range []FileStoreSync{SyncFull, SyncFsync, SyncNone} {
		b.Run(string(m), func(b *testing.B) {
			s, _ := NewFileConversationStoreWith(b.TempDir(), FileStoreOptions{Sync: m})
			for i := 0; i < b.N; i++ {
				if err := s.Save("c1", msgs); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}
