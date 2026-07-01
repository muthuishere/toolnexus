// Built-in tool tests (no network beyond httptest, no LLM). Mirrors the builtin
// section of js/test/unit.test.ts. Run: go test -race ./...
package toolnexus

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var builtinNames = []string{
	"bash", "read", "write", "edit", "grep", "glob",
	"webfetch", "question", "apply_patch", "todowrite",
}

func boolPtr(b bool) *bool { return &b }

// builtinTool returns a fresh builtin tool by name.
func builtinTool(t *testing.T, name string) Tool {
	t.Helper()
	for _, tool := range CreateBuiltinTools() {
		if tool.Name == name {
			return tool
		}
	}
	t.Fatalf("builtin %q not found", name)
	return Tool{}
}

func TestBuiltinsEnabled(t *testing.T) {
	cases := []struct {
		name string
		cfg  any
		want bool
	}{
		{"nil default on", nil, true},
		{"bool true", true, true},
		{"bool false", false, false},
		{"empty config", BuiltinsConfig{}, true},
		{"enabled false", BuiltinsConfig{Enabled: boolPtr(false)}, false},
		{"disabled true", BuiltinsConfig{Disabled: boolPtr(true)}, false},
		// disabled:true wins regardless of enabled
		{"disabled wins", BuiltinsConfig{Enabled: boolPtr(true), Disabled: boolPtr(true)}, false},
		// map form (parsed config object's top-level builtins key)
		{"map disabled", map[string]any{"disabled": true}, false},
		{"map enabled false", map[string]any{"enabled": false}, false},
		{"map empty", map[string]any{}, true},
	}
	for _, c := range cases {
		if got := BuiltinsEnabled(c.cfg); got != c.want {
			t.Errorf("%s: BuiltinsEnabled(%v) = %v, want %v", c.name, c.cfg, got, c.want)
		}
	}
}

func TestToolkitDefaultBuiltins(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	for _, name := range builtinNames {
		tool, ok := tk.Get(name)
		if !ok {
			t.Fatalf("builtin %q not present in default toolkit", name)
		}
		if tool.Source != SourceBuiltin {
			t.Fatalf("builtin %q source = %q, want builtin", name, tool.Source)
		}
	}

	// They appear in the provider schema arrays.
	openai := tk.ToOpenAI()
	names := map[string]bool{}
	for _, f := range openai {
		b, _ := json.Marshal(f)
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		fn, _ := m["function"].(map[string]any)
		if n, _ := fn["name"].(string); n != "" {
			names[n] = true
		}
	}
	for _, name := range builtinNames {
		if !names[name] {
			t.Fatalf("builtin %q missing from ToOpenAI schema", name)
		}
	}
}

func TestToolkitBuiltinsToggleOff(t *testing.T) {
	offs := []any{false, BuiltinsConfig{Disabled: boolPtr(true)}, BuiltinsConfig{Enabled: boolPtr(false)}}
	for _, off := range offs {
		extra := NativeTool("myextra", "", nil, func(context.Context, map[string]any) (string, error) {
			return "x", nil
		})
		tk, err := CreateToolkit(context.Background(), Options{
			SkillsDir:  []string{"../examples/skills"},
			ExtraTools: []Tool{extra},
			Builtins:   off,
		})
		if err != nil {
			t.Fatalf("CreateToolkit: %v", err)
		}
		for _, name := range builtinNames {
			if _, ok := tk.Get(name); ok {
				t.Fatalf("off=%v: builtin %q should be absent", off, name)
			}
		}
		if _, ok := tk.Get("skill"); !ok {
			t.Fatalf("off=%v: skill tool should be unaffected", off)
		}
		if _, ok := tk.Get("myextra"); !ok {
			t.Fatalf("off=%v: extra tool should be unaffected", off)
		}
		tk.Close()
	}
}

func TestToolkitBuiltinsToolMapDisablesNamed(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{
		Builtins: BuiltinsConfig{Tools: map[string]bool{"bash": false, "write": false, "nope": false}},
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	if _, ok := tk.Get("bash"); ok {
		t.Fatalf("bash should be disabled by the tools map")
	}
	if _, ok := tk.Get("write"); ok {
		t.Fatalf("write should be disabled by the tools map")
	}
	// The other eight remain (unknown "nope" is ignored).
	for _, name := range builtinNames {
		if name == "bash" || name == "write" {
			continue
		}
		if _, ok := tk.Get(name); !ok {
			t.Fatalf("builtin %q should still be present", name)
		}
	}
}

func TestToolkitBuiltinsToolMapSourceOffWins(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{
		Builtins: BuiltinsConfig{Disabled: boolPtr(true), Tools: map[string]bool{"read": true}},
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	for _, name := range builtinNames {
		if _, ok := tk.Get(name); ok {
			t.Fatalf("builtin %q should be absent (source off wins over the per-tool map)", name)
		}
	}
}

func TestToolkitExtraShadowsBuiltin(t *testing.T) {
	mine := NativeTool("read", "host read", nil, func(context.Context, map[string]any) (string, error) {
		return "HOST_READ", nil
	})
	tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{mine}})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	r, ok := tk.Get("read")
	if !ok {
		t.Fatalf("read tool missing")
	}
	if r.Source != SourceNative {
		t.Fatalf("read source = %q, want native (host tool wins collision)", r.Source)
	}
	out, err := tk.Execute(context.Background(), "read", map[string]any{})
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if out.Output != "HOST_READ" {
		t.Fatalf("read output = %q, want HOST_READ", out.Output)
	}
}

func TestBuiltinReadWriteEdit(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "note.txt")

	w, _ := builtinTool(t, "write").Execute(map[string]any{"path": file, "content": "alpha\nbeta\nalpha\n"}, nil)
	if w.IsError {
		t.Fatalf("write error: %s", w.Output)
	}
	if !strings.Contains(w.Output, "Wrote ") || !strings.Contains(w.Output, "bytes") {
		t.Fatalf("write output = %q", w.Output)
	}

	r, _ := builtinTool(t, "read").Execute(map[string]any{"path": file}, nil)
	if r.Output != "alpha\nbeta\nalpha\n" {
		t.Fatalf("read output = %q", r.Output)
	}

	// offset/limit window (1-based)
	win, _ := builtinTool(t, "read").Execute(map[string]any{"path": file, "offset": 2, "limit": 1}, nil)
	if win.Output != "beta" {
		t.Fatalf("read window = %q, want beta", win.Output)
	}

	// edit non-unique without replaceAll => error, no write
	dup, _ := builtinTool(t, "edit").Execute(map[string]any{"path": file, "oldString": "alpha", "newString": "X"}, nil)
	if !dup.IsError || !strings.Contains(dup.Output, "not unique") {
		t.Fatalf("edit non-unique = %+v, want IsError 'not unique'", dup)
	}
	if got, _ := os.ReadFile(file); string(got) != "alpha\nbeta\nalpha\n" {
		t.Fatalf("edit non-unique wrote partial: %q", string(got))
	}

	// edit not-found => error
	nf, _ := builtinTool(t, "edit").Execute(map[string]any{"path": file, "oldString": "zzz", "newString": "X"}, nil)
	if !nf.IsError || !strings.Contains(nf.Output, "not found") {
		t.Fatalf("edit not-found = %+v, want IsError 'not found'", nf)
	}

	// edit replaceAll
	ra, _ := builtinTool(t, "edit").Execute(map[string]any{"path": file, "oldString": "alpha", "newString": "OMEGA", "replaceAll": true}, nil)
	if ra.IsError {
		t.Fatalf("edit replaceAll error: %s", ra.Output)
	}
	if got, _ := os.ReadFile(file); string(got) != "OMEGA\nbeta\nOMEGA\n" {
		t.Fatalf("after replaceAll = %q", string(got))
	}

	// edit single unique replace
	one, _ := builtinTool(t, "edit").Execute(map[string]any{"path": file, "oldString": "beta", "newString": "gamma"}, nil)
	if one.IsError {
		t.Fatalf("edit single error: %s", one.Output)
	}
	if got, _ := os.ReadFile(file); string(got) != "OMEGA\ngamma\nOMEGA\n" {
		t.Fatalf("after single edit = %q", string(got))
	}

	// read missing => error
	miss, _ := builtinTool(t, "read").Execute(map[string]any{"path": filepath.Join(dir, "nope.txt")}, nil)
	if !miss.IsError {
		t.Fatalf("read missing should be IsError")
	}
}

func TestBuiltinGrepGlob(t *testing.T) {
	dir := t.TempDir()
	mustWrite(t, filepath.Join(dir, "a.txt"), "needle here\nplain\n")
	mustWrite(t, filepath.Join(dir, "b.txt"), "no match\nneedle again\n")
	if err := os.Mkdir(filepath.Join(dir, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	mustWrite(t, filepath.Join(dir, "sub", "c.md"), "needle in md\n")

	g, _ := builtinTool(t, "grep").Execute(map[string]any{"pattern": "needle", "path": dir}, nil)
	if g.IsError {
		t.Fatalf("grep error: %s", g.Output)
	}
	if c, _ := g.Metadata["count"].(int); c != 3 {
		t.Fatalf("grep count = %v, want 3", g.Metadata["count"])
	}

	gInc, _ := builtinTool(t, "grep").Execute(map[string]any{"pattern": "needle", "path": dir, "include": "*.txt"}, nil)
	if c, _ := gInc.Metadata["count"].(int); c != 2 {
		t.Fatalf("grep include count = %v, want 2", gInc.Metadata["count"])
	}

	gl, _ := builtinTool(t, "glob").Execute(map[string]any{"pattern": "*.txt", "path": dir}, nil)
	if c, _ := gl.Metadata["count"].(int); c != 2 {
		t.Fatalf("glob count = %v, want 2", gl.Metadata["count"])
	}
	got := strings.Split(gl.Output, "\n")
	if len(got) != 2 || got[0] != "a.txt" || got[1] != "b.txt" {
		t.Fatalf("glob output = %v, want [a.txt b.txt]", got)
	}

	glMd, _ := builtinTool(t, "glob").Execute(map[string]any{"pattern": "*.md", "path": dir}, nil)
	if c, _ := glMd.Metadata["count"].(int); c != 1 {
		t.Fatalf("glob *.md count = %v, want 1", glMd.Metadata["count"])
	}
}

func TestBuiltinBash(t *testing.T) {
	okr, _ := builtinTool(t, "bash").Execute(map[string]any{"command": "printf 'hello-bash'"}, nil)
	if okr.IsError {
		t.Fatalf("bash error: %s", okr.Output)
	}
	if !strings.Contains(okr.Output, "hello-bash") {
		t.Fatalf("bash output = %q", okr.Output)
	}

	bad, _ := builtinTool(t, "bash").Execute(map[string]any{"command": "exit 3"}, nil)
	if !bad.IsError || !strings.Contains(bad.Output, "code 3") {
		t.Fatalf("bash exit3 = %+v, want IsError 'code 3'", bad)
	}
	if code, _ := bad.Metadata["exitCode"].(int); code != 3 {
		t.Fatalf("bash exitCode = %v, want 3", bad.Metadata["exitCode"])
	}

	// workdir honored
	dir := t.TempDir()
	cwd, _ := builtinTool(t, "bash").Execute(map[string]any{"command": "pwd", "workdir": dir}, nil)
	if cwd.IsError {
		t.Fatalf("bash pwd error: %s", cwd.Output)
	}
	if !strings.Contains(cwd.Output, filepath.Base(dir)) {
		t.Fatalf("bash workdir output = %q, want to contain %q", cwd.Output, filepath.Base(dir))
	}
}

func TestBuiltinWebfetch(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/ok" {
			w.Header().Set("Content-Type", "text/plain")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("fetched-body-content"))
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("boom"))
	}))
	defer srv.Close()

	okr, _ := builtinTool(t, "webfetch").Execute(map[string]any{"url": srv.URL + "/ok", "format": "text"}, nil)
	if okr.IsError {
		t.Fatalf("webfetch error: %s", okr.Output)
	}
	if !strings.Contains(okr.Output, "fetched-body-content") {
		t.Fatalf("webfetch output = %q", okr.Output)
	}
	if s, _ := okr.Metadata["status"].(int); s != 200 {
		t.Fatalf("webfetch status = %v, want 200", okr.Metadata["status"])
	}

	bad, _ := builtinTool(t, "webfetch").Execute(map[string]any{"url": srv.URL + "/err"}, nil)
	if !bad.IsError || !strings.HasPrefix(bad.Output, "HTTP 500") {
		t.Fatalf("webfetch 500 = %+v, want IsError 'HTTP 500'", bad)
	}
}

func TestBuiltinApplyPatch(t *testing.T) {
	dir := t.TempDir()
	toDelete := filepath.Join(dir, "gone.txt")
	toUpdate := filepath.Join(dir, "keep.txt")
	toAdd := filepath.Join(dir, "new.txt")
	mustWrite(t, toDelete, "bye\n")
	mustWrite(t, toUpdate, "line one\nline two\nline three\n")

	patch := strings.Join([]string{
		"*** Begin Patch",
		"*** Add File: " + toAdd,
		"+created line 1",
		"+created line 2",
		"*** Update File: " + toUpdate,
		"@@",
		" line one",
		"-line two",
		"+line TWO changed",
		" line three",
		"*** Delete File: " + toDelete,
		"*** End Patch",
		"",
	}, "\n")

	res, _ := builtinTool(t, "apply_patch").Execute(map[string]any{"patchText": patch}, nil)
	if res.IsError {
		t.Fatalf("apply_patch error: %s", res.Output)
	}
	if got, _ := os.ReadFile(toAdd); string(got) != "created line 1\ncreated line 2" {
		t.Fatalf("added file = %q", string(got))
	}
	if got, _ := os.ReadFile(toUpdate); string(got) != "line one\nline TWO changed\nline three\n" {
		t.Fatalf("updated file = %q", string(got))
	}
	if fileExists(toDelete) {
		t.Fatalf("delete file still exists")
	}

	// non-matching hunk => error, no partial write
	addTwo := filepath.Join(dir, "should-not-exist.txt")
	badPatch := strings.Join([]string{
		"*** Begin Patch",
		"*** Add File: " + addTwo,
		"+content",
		"*** Update File: " + toUpdate,
		"@@",
		"-DOES NOT MATCH",
		"+whatever",
		"*** End Patch",
	}, "\n")
	bad, _ := builtinTool(t, "apply_patch").Execute(map[string]any{"patchText": badPatch}, nil)
	if !bad.IsError || !strings.Contains(bad.Output, "does not match") {
		t.Fatalf("apply_patch non-match = %+v, want IsError 'does not match'", bad)
	}
	if fileExists(addTwo) {
		t.Fatalf("partial write: %s should not exist", addTwo)
	}
	if got, _ := os.ReadFile(toUpdate); string(got) != "line one\nline TWO changed\nline three\n" {
		t.Fatalf("update file touched after abort: %q", string(got))
	}
}

func TestBuiltinQuestionAndTodowrite(t *testing.T) {
	questions := []any{
		map[string]any{"question": "Pick one?", "header": "Choice", "options": []any{"a", "b"}, "multiple": false},
	}
	q, _ := builtinTool(t, "question").Execute(map[string]any{"questions": questions}, nil)
	if q.IsError {
		t.Fatalf("question error: %s", q.Output)
	}
	var parsed []any
	if err := json.Unmarshal([]byte(q.Output), &parsed); err != nil {
		t.Fatalf("question output not JSON: %v", err)
	}
	if len(parsed) != 1 {
		t.Fatalf("question parsed len = %d, want 1", len(parsed))
	}
	if q.Metadata["questions"] == nil {
		t.Fatalf("question metadata.questions missing")
	}

	todos := []any{
		map[string]any{"id": "1", "text": "write code", "completed": true},
		map[string]any{"id": "2", "text": "ship it", "completed": false},
	}
	tw, _ := builtinTool(t, "todowrite").Execute(map[string]any{"todos": todos}, nil)
	if tw.IsError {
		t.Fatalf("todowrite error: %s", tw.Output)
	}
	if !strings.Contains(tw.Output, "[x] write code") || !strings.Contains(tw.Output, "[ ] ship it") {
		t.Fatalf("todowrite output = %q", tw.Output)
	}
	if tw.Metadata["todos"] == nil {
		t.Fatalf("todowrite metadata.todos missing")
	}
}

func TestSkillsPromptPreamble(t *testing.T) {
	withSkills := LoadSkills("../examples/skills")
	p := withSkills.Prompt()
	if !strings.HasPrefix(p, SkillsPromptPreamble) {
		t.Fatalf("prompt does not start with preamble; got:\n%s", p)
	}
	if !strings.Contains(p, SkillsPromptPreamble+"\n\n## Available Skills\n") {
		t.Fatalf("prompt preamble/list join wrong; got:\n%s", p)
	}

	noSkills := LoadSkills(t.TempDir())
	np := noSkills.Prompt()
	if np != "No skills are currently available." {
		t.Fatalf("empty prompt = %q, want 'No skills are currently available.'", np)
	}
	if strings.HasPrefix(np, SkillsPromptPreamble) {
		t.Fatalf("empty prompt should not carry preamble")
	}
}

func mustWrite(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// Assert the exact preamble string byte-for-byte (parity with JS).
func TestSkillsPreambleExactBytes(t *testing.T) {
	want := "Skills provide specialized instructions and workflows for specific tasks.\n" +
		"Use the skill tool to load a skill when a task matches its description."
	if SkillsPromptPreamble != want {
		t.Fatalf("SkillsPromptPreamble mismatch:\n got: %q\nwant: %q", SkillsPromptPreamble, want)
	}
}

// TestSkillDiscoveryFollowsSymlinkedDirs mirrors the JS unit test
// "skill discovery follows symlinked skill directories (opencode parity)".
func TestSkillDiscoveryFollowsSymlinkedDirs(t *testing.T) {
	// Layout: root/ has a real skill (direct/) and a symlink (linked/) → an
	// out-of-tree skill dir. The walker must discover both.
	root := t.TempDir()
	direct := filepath.Join(root, "direct")
	if err := os.MkdirAll(direct, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(direct, "SKILL.md"),
		[]byte("---\nname: direct-skill\ndescription: d\n---\nbody\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	external := t.TempDir()
	target := filepath.Join(external, "linked-target")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "SKILL.md"),
		[]byte("---\nname: linked-skill\ndescription: l\n---\nbody\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, filepath.Join(root, "linked")); err != nil {
		t.Fatal(err)
	}

	src := LoadSkills(root)
	if _, ok := src.Skills["direct-skill"]; !ok {
		t.Fatal("real skill not discovered")
	}
	if _, ok := src.Skills["linked-skill"]; !ok {
		t.Fatal("symlinked skill directory not discovered")
	}
}
