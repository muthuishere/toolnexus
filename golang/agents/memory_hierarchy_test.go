// Memory hierarchy + dreaming (SPEC §7E "Memory hierarchy" / "Dreaming"),
// driven by the shared fixtures examples/memory-hierarchy/fixture.json and
// examples/dreaming/fixture.json. Hermetic: a scripted openai-style
// RoundTripper, a virtual clock, temp dirs. Mirrors js/test/memory-hierarchy.test.ts.
package agents

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

func loadJSON(t *testing.T, name string) map[string]any {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", "examples", name, "fixture.json"))
	if err != nil {
		t.Fatal(err)
	}
	var v map[string]any
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

// rawAt is the fixture's raw JSON at a key path (stored key order intact).
func rawAt(t *testing.T, name string, keys ...string) json.RawMessage {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "examples", name, "fixture.json"))
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range keys {
		var m map[string]json.RawMessage
		if err := json.Unmarshal(raw, &m); err != nil {
			t.Fatal(err)
		}
		raw = m[k]
	}
	return raw
}

func obj(v any) map[string]any { m, _ := v.(map[string]any); return m }
func arr(v any) []any          { a, _ := v.([]any); return a }
func str(v any) string         { s, _ := v.(string); return s }

func mkHome(t *testing.T, files map[string]any) string {
	t.Helper()
	dir := t.TempDir()
	for f, body := range files {
		p := filepath.Join(dir, filepath.FromSlash(f))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(str(body)), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

// snapshot is every file under dir, keyed by '/'-separated relative path.
func snapshot(t *testing.T, dir string) map[string]string {
	t.Helper()
	out := map[string]string{}
	_ = filepath.Walk(dir, func(p string, fi os.FileInfo, err error) error {
		if err != nil || fi.IsDir() {
			return err
		}
		rel, _ := filepath.Rel(dir, p)
		b, _ := os.ReadFile(p)
		out[filepath.ToSlash(rel)] = string(b)
		return nil
	})
	return out
}

func toStrMap(v any) map[string]string {
	out := map[string]string{}
	for k, x := range obj(v) {
		out[k] = str(x)
	}
	return out
}

// sameJSON compares two values by their JSON form (key order ignored).
func sameJSON(a, b any) bool {
	norm := func(v any) any {
		j, _ := json.Marshal(v)
		var out any
		_ = json.Unmarshal(j, &out)
		return out
	}
	return reflect.DeepEqual(norm(a), norm(b))
}

// stubStore is the fixtures' store: List() in fixture order, Get(id), and a
// Save that must never be called. Tool-use inputs are kept as raw JSON so the
// raw-input path (which must sort keys, §7E) is exercised too.
type stubStore struct {
	ids   []string
	msgs  map[string][]any
	reads atomic.Int32
}

func newStubStore(t *testing.T, raw json.RawMessage) *stubStore {
	t.Helper()
	s := &stubStore{msgs: map[string][]any{}}
	var conversations any
	if err := json.Unmarshal(raw, &conversations); err != nil {
		t.Fatal(err)
	}
	var typed []struct {
		ID       string `json:"id"`
		Messages []struct {
			Content json.RawMessage `json:"content"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(raw, &typed); err != nil {
		t.Fatal(err)
	}
	for ci, c := range arr(conversations) {
		id := str(obj(c)["id"])
		s.ids = append(s.ids, id)
		msgs := arr(obj(c)["messages"])
		for mi, m := range msgs {
			parts, ok := obj(m)["content"].([]any)
			if !ok {
				continue
			}
			var rawParts []struct {
				Type  string          `json:"type"`
				Input json.RawMessage `json:"input"`
			}
			_ = json.Unmarshal(typed[ci].Messages[mi].Content, &rawParts)
			for pi, p := range parts {
				if str(obj(p)["type"]) == "tool_use" {
					obj(p)["input"] = rawParts[pi].Input
				}
			}
		}
		s.msgs[id] = msgs
	}
	return s
}

func (s *stubStore) Get(id string) ([]any, error) {
	s.reads.Add(1)
	return s.msgs[id], nil
}
func (s *stubStore) Save(string, []any) error { panic("transcript search must never write") }
func (s *stubStore) List() ([]tn.ConversationInfo, error) {
	s.reads.Add(1)
	out := make([]tn.ConversationInfo, len(s.ids))
	for i, id := range s.ids {
		out[i] = tn.ConversationInfo{ID: id}
	}
	return out, nil
}

// noListStore implements only Get/Save.
type noListStore struct{}

func (noListStore) Get(string) ([]any, error) { return nil, nil }
func (noListStore) Save(string, []any) error  { return nil }

// ===========================================================================
// G1 — index cap
// ===========================================================================

func capMemory(c map[string]any) string {
	if m, ok := c["memory"].(string); ok {
		return m
	}
	g := obj(c["generate"])
	var b strings.Builder
	for i := 1; i <= int(g["count"].(float64)); i++ {
		b.WriteString(strings.ReplaceAll(str(g["line"]), "<i>", fmt.Sprint(i)) + "\n")
	}
	return b.String()
}

func capOpts(o map[string]any) ComposeSoulOptions {
	var out ComposeSoulOptions
	out.Notes, _ = o["notes"].(bool)
	switch c := o["memoryIndexCap"].(type) {
	case bool:
		if c {
			out.MemoryIndexCap = &IndexCap{}
		}
	case map[string]any:
		ic := IndexCap{}
		if v, ok := c["maxLines"].(float64); ok {
			ic.MaxLines = int(v)
		}
		if v, ok := c["maxBytes"].(float64); ok {
			ic.MaxBytes = int(v)
		}
		out.MemoryIndexCap = &ic
	}
	return out
}

func TestMemoryHierarchyG1IndexCap(t *testing.T) {
	mh := loadJSON(t, "memory-hierarchy")
	ic := obj(mh["indexCap"])
	if p := obj(ic["preset"]); int(p["maxLines"].(float64)) != MemoryIndexCapPreset.MaxLines || int(p["maxBytes"].(float64)) != MemoryIndexCapPreset.MaxBytes {
		t.Fatalf("preset drift: %v", p)
	}
	for _, cv := range arr(ic["cases"]) {
		c := obj(cv)
		t.Run(str(c["name"]), func(t *testing.T) {
			mem := capMemory(c)
			files := obj(c["files"])
			all := map[string]any{"MEMORY.md": mem}
			for k, v := range files {
				all[k] = v
			}
			dir := mkHome(t, all)
			soul, _, err := ComposeSoulWith(dir, capOpts(obj(c["options"])))
			if err != nil {
				t.Fatal(err)
			}
			e := obj(c["expect"])
			sum := sha256.Sum256([]byte(soul))
			if hex.EncodeToString(sum[:]) != str(e["soulSha256"]) {
				t.Errorf("sha256 mismatch; tail %q", soul[max(0, len(soul)-300):])
			}
			if len(soul) != int(e["soulBytes"].(float64)) {
				t.Errorf("bytes: got %d want %v", len(soul), e["soulBytes"])
			}
			if !strings.HasSuffix(soul, str(e["memorySectionTail"])) {
				t.Errorf("tail mismatch: %q", soul[max(0, len(soul)-200):])
			}
			uncapped, _ := ComposeSoul(dir)
			if (soul == uncapped) != e["equalsUncappedSoul"].(bool) {
				t.Errorf("equalsUncappedSoul != %v", e["equalsUncappedSoul"])
			}
			if b, _ := os.ReadFile(filepath.Join(dir, "MEMORY.md")); string(b) != mem {
				t.Error("MEMORY.md on disk changed")
			}
		})
	}
}

func TestMemoryHierarchyG1FromDirAndInvalid(t *testing.T) {
	mh := loadJSON(t, "memory-hierarchy")
	var c map[string]any
	for _, x := range arr(obj(mh["indexCap"])["cases"]) {
		if str(obj(x)["name"]) == "lines-cap-preset-notes-off" {
			c = obj(x)
		}
	}
	all := map[string]any{"MEMORY.md": capMemory(c)}
	for k, v := range obj(c["files"]) {
		all[k] = v
	}
	dir := mkHome(t, all)
	soul := FromDir(dir, FromDirOptions{MemoryIndexCap: &IndexCap{}}).Spec.Soul
	sum := sha256.Sum256([]byte(soul))
	if hex.EncodeToString(sum[:]) != str(obj(c["expect"])["soulSha256"]) {
		t.Error("FromDir did not thread MemoryIndexCap")
	}
	if !strings.Contains(soul, "- fact 200\n\n[MEMORY.md index truncated") || strings.Contains(soul, "- fact 201") {
		t.Error("expected first 200 lines + notice")
	}
	if _, _, err := ComposeSoulWith(dir, ComposeSoulOptions{MemoryIndexCap: &IndexCap{MaxLines: -1}}); err == nil {
		t.Error("invalid cap must error")
	}
	defer func() {
		if recover() == nil {
			t.Error("FromDir with an invalid cap must panic")
		}
	}()
	FromDir(dir, FromDirOptions{MemoryIndexCap: &IndexCap{MaxBytes: -5}})
}

// ===========================================================================
// G2 — notes tier
// ===========================================================================

func TestMemoryHierarchyG2SchemaAndDescription(t *testing.T) {
	mt := obj(loadJSON(t, "memory-hierarchy")["memoryTool"])
	off, on := obj(mt["notesOff"]), obj(mt["notesOn"])
	tl := MemoryTool("/x")
	if tl.Description != str(off["description"]) || !sameJSON(tl.InputSchema, off["inputSchema"]) {
		t.Errorf("notes-off drift: %q %v", tl.Description, tl.InputSchema)
	}
	if t2 := MemoryToolWith("/x", MemoryToolOptions{}); !sameJSON(t2.InputSchema, tl.InputSchema) || t2.Description != tl.Description {
		t.Error("notes:false must be byte-identical")
	}
	n := MemoryToolWith("/x", MemoryToolOptions{Notes: true})
	if n.Description != str(on["description"]) || !sameJSON(n.InputSchema, on["inputSchema"]) {
		t.Errorf("notes-on drift: %q %v", n.Description, n.InputSchema)
	}
	dir := mkHome(t, map[string]any{"SOUL.md": "S"})
	for _, x := range FromDir(dir, FromDirOptions{Notes: true}).Spec.Tools {
		if x.Name == "memory" && x.Description != str(on["description"]) {
			t.Error("FromDir Notes did not wire the notes-on tool")
		}
	}
}

func TestMemoryHierarchyG2Cases(t *testing.T) {
	mt := obj(loadJSON(t, "memory-hierarchy")["memoryTool"])
	for _, cv := range arr(mt["cases"]) {
		c := obj(cv)
		t.Run(str(c["name"]), func(t *testing.T) {
			dir := mkHome(t, obj(c["seed"]))
			notes, _ := c["notes"].(bool)
			tool := MemoryToolWith(dir, MemoryToolOptions{Notes: notes})
			for _, opv := range arr(c["ops"]) {
				op := obj(opv)
				r, err := tool.Execute(obj(op["args"]), nil)
				if err != nil {
					t.Fatal(err)
				}
				e := obj(op["expect"])
				if r.Output != str(e["output"]) || r.IsError != e["isError"].(bool) {
					t.Errorf("%v: got {%q,%v} want {%q,%v}", op["args"], r.Output, r.IsError, e["output"], e["isError"])
				}
			}
			if got, want := snapshot(t, dir), toStrMap(c["expectFiles"]); !reflect.DeepEqual(got, want) {
				t.Errorf("files: got %v want %v", got, want)
			}
			if must, ok := c["soulMustNotContain"].(string); ok {
				soul, _, _ := ComposeSoulWith(dir, ComposeSoulOptions{Notes: true})
				if strings.Contains(soul, must) {
					t.Error("a note leaked into the soul")
				}
			}
		})
	}
}

// ===========================================================================
// G3 — transcript search
// ===========================================================================

func TestMemoryHierarchyG3TranscriptSearch(t *testing.T) {
	ts := obj(loadJSON(t, "memory-hierarchy")["transcriptSearch"])
	convs := rawAt(t, "memory-hierarchy", "transcriptSearch", "store", "conversations")
	tl, err := tn.TranscriptSearchTool(newStubStore(t, convs), tn.TranscriptSearchOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if tl.Name != str(obj(ts["defaults"])["name"]) || tl.Description != str(ts["description"]) || !sameJSON(tl.InputSchema, ts["inputSchema"]) {
		t.Errorf("schema/description drift: %s %q %v", tl.Name, tl.Description, tl.InputSchema)
	}
	for _, cv := range arr(ts["cases"]) {
		c := obj(cv)
		t.Run(str(c["name"]), func(t *testing.T) {
			o := obj(c["options"])
			var opts tn.TranscriptSearchOptions
			if v, ok := o["maxResults"].(float64); ok {
				opts.MaxResults = int(v)
			}
			if v, ok := o["snippetChars"].(float64); ok {
				opts.SnippetChars = int(v)
			}
			store := newStubStore(t, convs)
			if b, _ := c["emptyStore"].(bool); b {
				store = newStubStore(t, json.RawMessage(`[]`))
			}
			tool, err := tn.TranscriptSearchTool(store, opts)
			if err != nil {
				t.Fatal(err)
			}
			r, _ := tool.Execute(obj(c["args"]), nil)
			e := obj(c["expect"])
			if r.Output != str(e["output"]) || r.IsError != e["isError"].(bool) {
				t.Errorf("got {%q,%v}\nwant {%q,%v}", r.Output, r.IsError, e["output"], e["isError"])
			}
		})
	}

	// Empty query never reads the store; a store without List is rejected.
	s := newStubStore(t, convs)
	st, _ := tn.TranscriptSearchTool(s, tn.TranscriptSearchOptions{})
	if r, _ := st.Execute(map[string]any{"query": ""}, nil); !r.IsError || s.reads.Load() != 0 {
		t.Error("empty query must error before reading the store")
	}
	if _, err := tn.TranscriptSearchTool(noListStore{}, tn.TranscriptSearchOptions{}); err == nil || err.Error() != str(obj(ts["storeWithoutList"])["error"]) {
		t.Errorf("store without List: %v", err)
	}
	if _, err := tn.TranscriptSearchTool(s, tn.TranscriptSearchOptions{MaxResults: -1}); err == nil {
		t.Error("maxResults < 1 must error")
	}

	// §7E: keys sorted at every depth, whether the input is a map or raw JSON.
	m := map[string]any{"role": "assistant", "content": []any{map[string]any{"type": "tool_use", "input": map[string]any{"q": "zubat", "n": 2}}}}
	if got := tn.TranscriptMessageText(m); got != `{"n":2,"q":"zubat"}` {
		t.Errorf("map input: %q", got)
	}
	rm := map[string]any{"role": "assistant", "content": []any{map[string]any{"type": "tool_use", "input": json.RawMessage(`{ "q": {"z":1,"a":[{"y":1.50,"b":2}]}, "n": 2 }`)}}}
	if got := tn.TranscriptMessageText(rm); got != `{"n":2,"q":{"a":[{"b":2,"y":1.50}],"z":1}}` {
		t.Errorf("raw input: %q", got)
	}

	// U+2028/2029 and <>& emitted as-is; a literal backslash-u2028 text stays escaped.
	ls := map[string]any{"role": "assistant", "content": []any{map[string]any{"type": "tool_use", "input": map[string]any{"a": "x\u2028y\u2029<&>é", "b": `\u2028`}}}}
	if got := tn.TranscriptMessageText(ls); got != "{\"a\":\"x\u2028y\u2029<&>é\",\"b\":\"\\\\u2028\"}" {
		t.Errorf("line separators: %q", got)
	}

	// Works over the shipped InMemoryConversationStore.
	mem := tn.NewInMemoryConversationStore()
	_ = mem.Save("only", []any{map[string]any{"role": "user", "content": "The vault code is 4417"}})
	it, err := tn.TranscriptSearchTool(mem, tn.TranscriptSearchOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if r, _ := it.Execute(map[string]any{"query": "VAULT"}, nil); r.Output != "only#0 user: The vault code is 4417" {
		t.Errorf("in-memory store: %q", r.Output)
	}
}

// ===========================================================================
// G4 — write safety
// ===========================================================================

func TestMemoryHierarchyG4ConcurrentAdds(t *testing.T) {
	ca := obj(obj(loadJSON(t, "memory-hierarchy")["writeSafety"])["concurrentAdds"])
	writers, each, want := int(ca["writers"].(float64)), int(ca["addsEach"].(float64)), int(ca["expectEntries"].(float64))
	dir := mkHome(t, nil)
	var wg sync.WaitGroup
	for w := 0; w < writers; w++ {
		tool := MemoryTool(dir) // one tool per writer, all over ONE home
		for n := 0; n < each; n++ {
			wg.Add(1)
			go func(w, n int) {
				defer wg.Done()
				if r, _ := tool.Execute(map[string]any{"action": "add", "text": fmt.Sprintf("w%d-%d", w, n)}, nil); r.IsError {
					t.Error(r.Output)
				}
			}(w, n)
		}
	}
	wg.Wait()
	b, _ := os.ReadFile(filepath.Join(dir, "MEMORY.md"))
	lines := strings.Split(strings.TrimRight(string(b), "\n"), "\n")
	seen := map[string]bool{}
	for _, l := range lines {
		seen[l] = true
	}
	if len(lines) != want || len(seen) != want {
		t.Errorf("lost writes: %d lines, %d unique, want %d", len(lines), len(seen), want)
	}
}

func TestMemoryHierarchyG4ConcurrentAddsViaSymlink(t *testing.T) {
	ca := obj(obj(loadJSON(t, "memory-hierarchy")["writeSafety"])["concurrentAddsViaSymlink"])
	writers, each, want := int(ca["writers"].(float64)), int(ca["addsEach"].(float64)), int(ca["expectEntries"].(float64))
	dir := mkHome(t, nil)
	link := filepath.Join(t.TempDir(), "home-link")
	if err := os.Symlink(dir, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	var wg sync.WaitGroup
	for w := 0; w < writers; w++ {
		home := dir
		if w == 0 {
			home = link // writer 1 over the symlink, writer 2 over the real path
		}
		tool := MemoryTool(home)
		for n := 0; n < each; n++ {
			wg.Add(1)
			go func(w, n int) {
				defer wg.Done()
				if r, _ := tool.Execute(map[string]any{"action": "add", "text": fmt.Sprintf("w%d-%d", w, n)}, nil); r.IsError {
					t.Error(r.Output)
				}
			}(w, n)
		}
	}
	wg.Wait()
	b, _ := os.ReadFile(filepath.Join(dir, str(ca["file"])))
	lines := strings.Split(strings.TrimRight(string(b), "\n"), "\n")
	seen := map[string]bool{}
	for _, l := range lines {
		seen[l] = true
	}
	if len(lines) != want || len(seen) != want {
		t.Errorf("lost writes via symlink: %d lines, %d unique, want %d", len(lines), len(seen), want)
	}
}

func TestMemoryHierarchyG2ListSkipsNonRegular(t *testing.T) {
	ls := obj(obj(loadJSON(t, "memory-hierarchy")["memoryTool"])["listSkipsNonRegular"])
	dir := mkHome(t, obj(ls["regular"]))
	for f, target := range obj(ls["symlinks"]) {
		if err := os.Symlink(str(target), filepath.Join(dir, f)); err != nil {
			t.Skipf("symlinks unavailable: %v", err)
		}
	}
	for _, d := range arr(ls["dirs"]) {
		if err := os.MkdirAll(filepath.Join(dir, str(d)), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	r, _ := MemoryToolWith(dir, MemoryToolOptions{Notes: true}).Execute(map[string]any{"action": "list"}, nil)
	if r.IsError || r.Output != str(ls["expectList"]) {
		t.Errorf("list: {%q,%v} want %q", r.Output, r.IsError, ls["expectList"])
	}
}

func TestMemoryHierarchyG4LargeFileAdd(t *testing.T) {
	lf := obj(obj(loadJSON(t, "memory-hierarchy")["writeSafety"])["largeFileAdd"])
	original := strings.Repeat(str(lf["fill"]), int(lf["bytes"].(float64)))
	dir := mkHome(t, map[string]any{"MEMORY.md": original})
	r, _ := MemoryToolWith(dir, MemoryToolOptions{Notes: true}).Execute(map[string]any{"action": "add", "text": str(lf["addText"])}, nil)
	if r.IsError {
		t.Fatal(r.Output)
	}
	b, _ := os.ReadFile(filepath.Join(dir, "MEMORY.md"))
	if string(b) != original+"\n- "+str(lf["addText"])+"\n" {
		t.Errorf("disk not original + entry (len %d)", len(b))
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 || entries[0].Name() != "MEMORY.md" {
		t.Errorf("temp file left behind: %v", entries)
	}
}

// ===========================================================================
// Dreaming
// ===========================================================================

// dreamMock implements the dreaming fixture's mockLLM scripts. The dreamer is
// recognised by its system prompt, every other call by model id.
type dreamMock struct {
	scripts map[string]any
	script  *atomic.Value // "dreamer-fix" | "dreamer-idle"
}

func newDreamMock(scripts map[string]any) dreamMock {
	v := &atomic.Value{}
	v.Store("dreamer-fix")
	return dreamMock{scripts: scripts, script: v}
}

func (m dreamMock) step(name string, n int) map[string]any {
	return obj(arr(m.scripts[name])[n])
}

func (m dreamMock) call(n int, id string) *http.Response {
	tc := obj(arr(m.step("dreamer-fix", n)["toolCalls"])[0])
	return callResp(toolCallPart(str(tc["name"]), obj(tc["args"]), id))
}

func (m dreamMock) RoundTrip(req *http.Request) (*http.Response, error) {
	p := parseReq(req)
	if strings.Contains(p.system, "You are dreaming:") {
		script := m.script.Load().(string)
		if script == "dreamer-idle" {
			return textResp(str(m.step(script, 0)["text"])), nil
		}
		switch n := len(p.toolMsgs); {
		case n <= 1:
			return m.call(n, fmt.Sprintf("d%d", n)), nil
		case n == 2:
			if strings.Contains(p.toolMsgs[0], "Mt. Moon exit is south") && strings.Contains(p.toolMsgs[1], "exit is north") {
				return m.call(2, "d2"), nil
			}
			return textResp(HeartbeatOK), nil
		case n == 3:
			return m.call(3, "d3"), nil
		default:
			return textResp(str(m.step("dreamer-fix", 4)["text"])), nil
		}
	}
	if p.model == "m-navigator" {
		if strings.Contains(p.system, "Mt. Moon exit is north") {
			return textResp("Head north out of Mt. Moon."), nil
		}
		return textResp("Head south out of Mt. Moon."), nil
	}
	return textResp(HeartbeatOK), nil
}

type dreamFx struct {
	raw   map[string]any
	home  map[string]any
	convs json.RawMessage
	exp   map[string]any
	mock  dreamMock
}

func loadDream(t *testing.T) dreamFx {
	d := loadJSON(t, "dreaming")
	return dreamFx{
		raw:   d,
		home:  obj(d["home"]),
		convs: rawAt(t, "dreaming", "store", "conversations"),
		exp:   obj(d["expect"]),
		mock:  newDreamMock(obj(obj(d["mockLLM"])["scripts"])),
	}
}

func mustDreamer(t *testing.T, dir string, o DreamerOptions) *Agent {
	t.Helper()
	d, err := Dreamer(dir, o)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func TestDreamD0Prompt(t *testing.T) {
	if DreamPrompt != str(loadJSON(t, "dreaming")["dreamPrompt"]) {
		t.Error("DreamPrompt drifted from the fixture")
	}
}

// D1 — the BUILD GATE (Mt. Moon): one dream pass fixes the wrong claim, logs it
// once, and the NEXT session answers differently. Then a second pass is silent.
func TestDreamD1MtMoonBuildGate(t *testing.T) {
	fx := loadDream(t)
	g := obj(fx.exp["D1_mtMoonBuildGate"])
	before, after := obj(g["before"]), obj(g["after"])
	next := obj(after["nextSession"])
	home := mkHome(t, fx.home)
	opts := Options{Transport: fx.mock}

	r, _ := FromDir(home, FromDirOptions{Model: str(before["model"])}).Run(opts, str(before["prompt"]))
	if r.Text != str(before["text"]) {
		t.Fatalf("before: %q", r.Text)
	}

	pass, _ := mustDreamer(t, home, DreamerOptions{Store: newStubStore(t, fx.convs)}).Run(opts, HeartbeatPrompt)
	if pass.Text != str(obj(g["pass"])["replyText"]) {
		t.Fatalf("pass reply: %+v", pass)
	}
	snap := snapshot(t, home)
	for f, body := range toStrMap(after["files"]) {
		if snap[f] != body {
			t.Errorf("%s: got %q want %q", f, snap[f], body)
		}
	}
	entries := 0
	for _, l := range strings.Split(snap["memory/log.md"], "\n") {
		if strings.HasPrefix(l, "- ") {
			entries++
		}
	}
	if entries != 1 {
		t.Errorf("log entries: %d", entries)
	}

	r2, _ := FromDir(home, FromDirOptions{Model: str(next["model"])}).Run(opts, str(next["prompt"]))
	if r2.Text != str(next["text"]) {
		t.Errorf("next session: %q", r2.Text)
	}

	// D2 (second case): the claim is already right — silent, nothing written.
	frozen := snapshot(t, home)
	again, _ := mustDreamer(t, home, DreamerOptions{Store: newStubStore(t, fx.convs)}).Run(opts, HeartbeatPrompt)
	if !strings.Contains(again.Text, HeartbeatOK) || !reflect.DeepEqual(snapshot(t, home), frozen) {
		t.Errorf("second pass not silent: %q", again.Text)
	}
}

func TestDreamD2IdleWritesNothing(t *testing.T) {
	fx := loadDream(t)
	fx.mock.script.Store("dreamer-idle")
	home := mkHome(t, fx.home)
	frozen := snapshot(t, home)
	r, _ := mustDreamer(t, home, DreamerOptions{Store: newStubStore(t, fx.convs)}).Run(Options{Transport: fx.mock}, HeartbeatPrompt)
	if !strings.Contains(r.Text, HeartbeatOK) || !reflect.DeepEqual(snapshot(t, home), frozen) {
		t.Errorf("idle pass: %q", r.Text)
	}
}

func toolNames(ts []tn.Tool) []string {
	out := make([]string, len(ts))
	for i, x := range ts {
		out[i] = x.Name
	}
	return out
}

func TestDreamD7Guard(t *testing.T) {
	fx := loadDream(t)
	e := obj(fx.exp["D7_dreamGuard"])
	home := mkHome(t, obj(e["files"]))
	mem := mustDreamer(t, home, DreamerOptions{Store: newStubStore(t, fx.convs)}).Spec.Tools[0]
	for _, raw := range arr(e["cases"]) {
		c := obj(raw)
		r, _ := mem.Execute(obj(c["args"]), nil)
		if r.Output != str(c["output"]) || r.IsError != c["isError"].(bool) {
			t.Errorf("%s: got %q/%v", str(c["name"]), r.Output, r.IsError)
		}
	}
	snap := snapshot(t, home)
	if len(snap) != len(obj(e["files"])) {
		t.Errorf("home files: %v", snap)
	}
	for f := range obj(e["files"]) {
		if _, ok := snap[f]; !ok {
			t.Errorf("missing %s", f)
		}
	}
	for f, body := range obj(e["filesAfter"]) {
		if snap[f] != str(body) {
			t.Errorf("%s: %q", f, snap[f])
		}
	}
	p := obj(e["plainToolStillWrites"])
	r, _ := MemoryToolWith(home, MemoryToolOptions{Notes: true}).Execute(obj(p["args"]), nil)
	if r.Output != str(p["output"]) || r.IsError != p["isError"].(bool) {
		t.Errorf("plain tool: %q/%v", r.Output, r.IsError)
	}
}

func TestDreamD8PromptKeepsUnmentionedClaims(t *testing.T) {
	e := obj(loadDream(t).exp["D8_promptKeepsUnmentionedClaims"])
	if !strings.Contains(DreamPrompt, str(e["dreamPromptContains"])) || strings.Contains(DreamPrompt, str(e["dreamPromptNotContains"])) {
		t.Error("DreamPrompt must remove only what the transcripts contradict")
	}
}

func TestDreamD3ToolsExactly(t *testing.T) {
	fx := loadDream(t)
	e := obj(fx.exp["D3_dreamerToolsExactly"])
	home := mkHome(t, fx.home)
	var personaTools []tn.Tool
	var want []string
	for _, n := range arr(e["personaTools"]) {
		personaTools = append(personaTools, tn.Tool{Name: str(n), Description: str(n)})
		want = append(want, str(n))
	}
	if got := toolNames(FromDir(home, FromDirOptions{Tools: personaTools}).Spec.Tools); !reflect.DeepEqual(got, append(want, "memory")) {
		t.Errorf("persona tools: %v", got)
	}
	d := mustDreamer(t, home, DreamerOptions{Store: newStubStore(t, fx.convs)})
	var dreamerWant []string
	for _, n := range arr(e["dreamerTools"]) {
		dreamerWant = append(dreamerWant, str(n))
	}
	if got := toolNames(d.Spec.Tools); !reflect.DeepEqual(got, dreamerWant) {
		t.Errorf("dreamer tools: %v", got)
	}
	if d.Spec.Tools[0].Description != MemoryNotesDescription {
		t.Error("dreamer memory tool must have notes on")
	}
}

func TestDreamD3bSoulNameAndStoreChecks(t *testing.T) {
	fx := loadDream(t)
	e := obj(fx.exp["D3b_dreamerSoul"])
	plain := mkHome(t, fx.home)
	d := mustDreamer(t, plain, DreamerOptions{Store: newStubStore(t, fx.convs)})
	if d.Spec.Soul != str(e["soulWithoutExtras"]) {
		t.Errorf("soul without extras: %q", d.Spec.Soul)
	}
	if d.Name != filepath.Base(plain)+"-dreamer" {
		t.Errorf("default name: %q", d.Name)
	}
	files := map[string]any{"DREAM.md": e["DREAM.md"]}
	for k, v := range fx.home {
		files[k] = v
	}
	withExtras := mkHome(t, files)
	if got := mustDreamer(t, withExtras, DreamerOptions{Store: newStubStore(t, fx.convs), Instructions: str(e["instructions"])}).Spec.Soul; got != str(e["soul"]) {
		t.Errorf("soul with extras: %q", got)
	}
	if _, err := Dreamer(plain, DreamerOptions{Store: noListStore{}}); err == nil || !strings.Contains(err.Error(), "list()") {
		t.Errorf("store without List: %v", err)
	}
	if _, err := Dreamer(plain, DreamerOptions{}); err == nil || err.Error() != "dreamer: store is required" {
		t.Errorf("missing store: %v", err)
	}
	func() {
		defer func() {
			if recover() == nil {
				t.Error("FromDir dream.everyMs <= 0 must panic")
			}
		}()
		FromDir(plain, FromDirOptions{Dream: &DreamOptions{EveryMs: 0, Store: newStubStore(t, fx.convs)}})
	}()
}

// waitArmedN spins (bounded) until the virtual clock has n pending After channels.
func waitArmedN(t *testing.T, c *fakeClock, n int) {
	t.Helper()
	for i := 0; i < 1000; i++ {
		if c.armed() >= n {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("virtual clock never had %d timers armed", n)
}

func TestDreamD4FromDirDreamRunsOnPersonaRuntime(t *testing.T) {
	fx := loadDream(t)
	e := obj(fx.exp["D4_fromDirDream"])
	home := mkHome(t, fx.home)
	clk := newFakeClock()
	var mu sync.Mutex
	var onBeat []string
	persona := FromDir(home, FromDirOptions{Model: "m-quiet", Dream: &DreamOptions{EveryMs: 50, Store: newStubStore(t, fx.convs)}})
	sa, err := StartAgent(persona, StartOptions{
		Options: Options{Transport: fx.mock, Clock: clk},
		EveryMs: 1000,
		OnBeat: func(s string) {
			mu.Lock()
			onBeat = append(onBeat, s)
			mu.Unlock()
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	waitArmedN(t, clk, 2) // the persona's and the dreamer's heartbeat
	clk.fire()
	want := toStrMap(obj(e["after"])["files"])
	deadline := time.Now().Add(10 * time.Second)
	for {
		snap := snapshot(t, home)
		ok := true
		for f, body := range want {
			ok = ok && snap[f] == body
		}
		if ok {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("dreamer never fixed memory: %v", snap)
		}
		time.Sleep(5 * time.Millisecond)
	}
	sa.Stop()
	mu.Lock()
	defer mu.Unlock()
	if len(onBeat) != 0 || len(sa.Beats()) != 0 {
		t.Errorf("dreamer replies reached onBeat/beats: %v %v", onBeat, sa.Beats())
	}
	found := false
	for _, l := range sa.Runtime().Trace() {
		found = found || strings.Contains(l, "-dreamer")
	}
	if !found {
		t.Error("dreamer was not spawned on the persona's runtime")
	}
}

func TestDreamD6DreamerNameCollision(t *testing.T) {
	fx := loadDream(t)
	e := obj(fx.exp["D6_dreamerNameCollision"])
	home := mkHome(t, fx.home)
	name := str(e["personaName"])
	persona := FromDir(home, FromDirOptions{Name: name, Model: "m-quiet", Dream: &DreamOptions{EveryMs: 50, Store: newStubStore(t, fx.convs)}})
	persona.Spec.Team = append(persona.Spec.Team, New(name+"-dreamer", Spec{Does: "team member"}))
	clk := newFakeClock()
	sa, err := StartAgent(persona, StartOptions{Options: Options{Transport: fx.mock, Clock: clk}, EveryMs: 1000})
	if err == nil || err.Error() != str(e["error"]) {
		t.Fatalf("got %v, want %q", err, e["error"])
	}
	if sa != nil {
		t.Error("nothing may be started on a collision")
	}
	if n := clk.armed(); n != 0 {
		t.Errorf("%d timers armed after a collision", n)
	}
}

func TestDreamD5AbsentDreamIsUnchanged(t *testing.T) {
	fx := loadDream(t)
	home := mkHome(t, fx.home)
	frozen := snapshot(t, home)
	persona := FromDir(home, FromDirOptions{Model: "m-quiet"})
	if n := len(persona.Registry()); n != 1 {
		t.Fatalf("registry size %d", n)
	}
	clk := newFakeClock()
	sa, err := StartAgent(persona, StartOptions{Options: Options{Transport: fx.mock, Clock: clk}, EveryMs: 50})
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 4; i++ {
		waitArmed(t, clk)
		clk.fire()
		<-sa.beat
	}
	sa.Stop()
	if !reflect.DeepEqual(snapshot(t, home), frozen) {
		t.Error("files changed without a dream option")
	}
	for _, l := range sa.Runtime().Trace() {
		if strings.Contains(l, "dreamer") {
			t.Errorf("a dreamer ran: %s", l)
		}
	}
}
