package toolnexus

// ask #7: background shells, websearch, notebook_edit, MCP resource tools,
// grep context, read/grep truncation metadata.

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func extraTool(t *testing.T, cfg BuiltinsConfig, name string) Tool {
	t.Helper()
	for _, tl := range CreateBuiltinToolsWith(cfg) {
		if tl.Name == name {
			return tl
		}
	}
	t.Fatalf("builtin %q not present", name)
	return Tool{}
}

func TestOptInBuiltinsAbsentByDefault(t *testing.T) {
	for _, tl := range CreateBuiltinToolsWith(BuiltinsConfig{}) {
		switch tl.Name {
		case "bash_output", "kill_shell", "websearch", "notebook_edit":
			t.Fatalf("%s must be opt-in", tl.Name)
		case "bash":
			if _, ok := tl.InputSchema["properties"].(map[string]any)["run_in_background"]; ok {
				t.Fatal("bash must not offer run_in_background without Background")
			}
		}
	}
}

func TestBackgroundShellLifecycle(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("posix")
	}
	bg := NewBackgroundShells()
	defer bg.CloseAll()
	cfg := BuiltinsConfig{Background: bg, KillGraceMs: 100}
	bash, out, kill := extraTool(t, cfg, "bash"), extraTool(t, cfg, "bash_output"), extraTool(t, cfg, "kill_shell")

	// a command that finishes
	r, _ := bash.Execute(map[string]any{"command": "echo one; sleep 0.2; echo two", "run_in_background": true}, nil)
	id, _ := r.Metadata["backgroundId"].(string)
	if r.IsError || id == "" {
		t.Fatalf("start: %+v", r)
	}
	deadline := time.Now().Add(5 * time.Second)
	var all string
	for {
		o, _ := out.Execute(map[string]any{"id": id}, nil)
		all += strings.SplitN(o.Output, "\n[status:", 2)[0]
		if o.Metadata["status"] == "exited" {
			if o.Metadata["exitCode"] != 0 {
				t.Fatalf("exit: %+v", o.Metadata)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("never exited")
		}
		time.Sleep(50 * time.Millisecond)
	}
	if strings.Count(all, "one") != 1 || strings.Count(all, "two") != 1 {
		t.Fatalf("incremental output repeated or lost: %q", all)
	}

	// a stubborn command with a child, killed as a group
	r, _ = bash.Execute(map[string]any{"command": "trap '' TERM; sleep 30 & wait", "run_in_background": true}, nil)
	id2 := r.Metadata["backgroundId"].(string)
	time.Sleep(100 * time.Millisecond)
	start := time.Now()
	k, _ := kill.Execute(map[string]any{"id": id2}, nil)
	if k.IsError || k.Metadata["killed"] != true {
		t.Fatalf("kill: %+v", k)
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("kill took %v", d)
	}
	o, _ := out.Execute(map[string]any{"id": id2}, nil)
	if o.Metadata["status"] != "killed" {
		t.Fatalf("status after kill: %+v", o.Metadata)
	}
	if k2, _ := kill.Execute(map[string]any{"id": id2}, nil); k2.Metadata["killed"] != false {
		t.Fatalf("second kill: %+v", k2)
	}
	if e, _ := out.Execute(map[string]any{"id": "nope"}, nil); !e.IsError {
		t.Fatal("unknown id must be an error")
	}
	if n := len(bg.List()); n != 2 {
		t.Fatalf("List = %d", n)
	}
}

func TestWebSearchProvider(t *testing.T) {
	var gotQ string
	var gotN int
	cfg := BuiltinsConfig{WebSearch: func(_ context.Context, q string, n int) ([]WebSearchResult, error) {
		gotQ, gotN = q, n
		return []WebSearchResult{{Title: "A", URL: "https://a.example", Snippet: "sa"}, {Title: "B", URL: "https://b.example"}}, nil
	}}
	ws := extraTool(t, cfg, "websearch")
	if ws.Hints == nil || !ws.Hints.ReadOnly || !ws.Hints.OpenWorld {
		t.Fatalf("hints %+v", ws.Hints)
	}
	r, _ := ws.Execute(map[string]any{"query": "go", "limit": 1}, nil)
	if gotQ != "go" || gotN != 1 || r.Output != "1. A\n   https://a.example\n   sa" {
		t.Fatalf("q=%q n=%d out=%q", gotQ, gotN, r.Output)
	}
}

func TestNotebookEdit(t *testing.T) {
	dir := t.TempDir()
	nb := `{"cells":[{"cell_type":"code","id":"a","metadata":{},"source":["x = 1\n"],"outputs":[{"output_type":"stream","text":["1"]}],"execution_count":3},{"cell_type":"markdown","id":"b","metadata":{},"source":["# T"]}],"metadata":{},"nbformat":4,"nbformat_minor":5}`
	p := filepath.Join(dir, "n.ipynb")
	_ = os.WriteFile(p, []byte(nb), 0o644)
	ne := extraTool(t, BuiltinsConfig{NotebookEdit: true, BaseDir: dir}, "notebook_edit")
	load := func() []any {
		var m map[string]any
		raw, _ := os.ReadFile(p)
		_ = json.Unmarshal(raw, &m)
		return m["cells"].([]any)
	}
	if r, _ := ne.Execute(map[string]any{"path": "n.ipynb", "cell_id": "a", "new_source": "x = 2\ny = 3"}, nil); r.IsError {
		t.Fatal(r.Output)
	}
	c0 := load()[0].(map[string]any)
	if len(c0["outputs"].([]any)) != 0 || c0["execution_count"] != nil || len(c0["source"].([]any)) != 2 {
		t.Fatalf("replace: %v", c0)
	}
	if r, _ := ne.Execute(map[string]any{"path": "n.ipynb", "cell_index": 0, "edit_mode": "insert", "cell_type": "markdown", "new_source": "hi"}, nil); r.IsError {
		t.Fatal(r.Output)
	}
	if cells := load(); len(cells) != 3 || cells[1].(map[string]any)["cell_type"] != "markdown" {
		t.Fatalf("insert: %v", cells)
	}
	r, _ := ne.Execute(map[string]any{"path": "n.ipynb", "cell_id": "b", "edit_mode": "delete"}, nil)
	if r.IsError || len(load()) != 2 || r.Metadata["files"] == nil {
		t.Fatalf("delete: %+v", r)
	}
	if r, _ := ne.Execute(map[string]any{"path": "n.ipynb", "cell_id": "zz"}, nil); !r.IsError {
		t.Fatal("unknown cell id must be an error")
	}
}

func TestGrepContextAndTruncation(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "f.txt"), []byte("a\nb\nHIT1\nc\nd\ne\nf\nHIT2\ng\n"), 0o644)
	grep := extraTool(t, BuiltinsConfig{BaseDir: dir}, "grep")
	r, _ := grep.Execute(map[string]any{"pattern": "HIT", "context": 1}, nil)
	want := "f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"
	if r.Output != want {
		t.Fatalf("context:\n%s\nwant:\n%s", r.Output, want)
	}
	r, _ = grep.Execute(map[string]any{"pattern": "HIT", "before": 0, "after": 4}, nil)
	if r.Output != "f.txt:3:HIT1\nf.txt-4-c\nf.txt-5-d\nf.txt-6-e\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g" {
		t.Fatalf("merged groups:\n%s", r.Output)
	}
	r, _ = grep.Execute(map[string]any{"pattern": "HIT", "limit": 1}, nil)
	if r.Output != "f.txt:3:HIT1" || r.Metadata["truncated"] != true || r.Metadata["total"] != 2 {
		t.Fatalf("truncation: %q %+v", r.Output, r.Metadata)
	}
	read := extraTool(t, BuiltinsConfig{BaseDir: dir}, "read")
	r, _ = read.Execute(map[string]any{"path": "f.txt", "limit": 2}, nil)
	if r.Output != "a\nb" || r.Metadata["truncated"] != true || r.Metadata["totalLines"] != 9 || r.Metadata["nextOffset"] != 3 {
		t.Fatalf("read window: %q %+v", r.Output, r.Metadata)
	}
}

func TestMcpResourceTools(t *testing.T) {
	src, _ := progressFixture(t, 5000)
	tools := src.ResourceTools()
	list, read := tools[0], tools[1]
	r, _ := list.Execute(map[string]any{}, nil)
	if r.IsError || r.Output != "progress fixture://hello — hello (text/plain): A fixed text resource." {
		t.Fatalf("list: %q", r.Output)
	}
	r, _ = read.Execute(map[string]any{"server": "progress", "uri": "fixture://hello"}, nil)
	if r.IsError || r.Output != "hello resource" {
		t.Fatalf("read: %+v", r)
	}
	if r, _ = read.Execute(map[string]any{"server": "progress", "uri": "fixture://nope"}, nil); !r.IsError {
		t.Fatal("missing resource must be an error")
	}
	if r, _ = read.Execute(map[string]any{"server": "nope", "uri": "x"}, nil); !r.IsError {
		t.Fatal("unknown server must be an error")
	}
	if !list.Hints.ReadOnly || !read.Hints.ReadOnly {
		t.Fatal("resource tools are read-only")
	}
}
