// Agent-home conformance (SPEC §7E), H1–H7, against the shared fixture
// examples/persona-agent/fixture.json. Hermetic: a scripted openai-style
// RoundTripper (homeMock) implements the fixture's four models — zero network,
// zero cost — exactly as the add-subagents runtime tests do. The persona is
// REAL: a soul from files, a memory tool that persists to disk, and a heartbeat
// that only speaks when HEARTBEAT.md is due.
package agents

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- the shared fixture ----------------------------------------------------

type personaFixture struct {
	BootstrapDir  map[string]string `json:"bootstrapDir"`
	BootstrapOrder []string         `json:"bootstrapOrder"`
	MaxFileBytes  int               `json:"maxFileBytes"`
}

func loadFixture(t *testing.T) personaFixture {
	t.Helper()
	// golang/agents → repo root → examples/persona-agent/fixture.json
	p := filepath.Join("..", "..", "examples", "persona-agent", "fixture.json")
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	var f personaFixture
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatalf("parse fixture: %v", err)
	}
	return f
}

// writeBootstrap materializes a bootstrap dir from a file map.
func writeBootstrap(t *testing.T, files map[string]string) string {
	t.Helper()
	dir := t.TempDir()
	for name, body := range files {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

// ---- scripted mock LLM (fixture models) ------------------------------------

// homeRecorder captures the `last` user message of each heartbeat call — the
// coalesce assertion inspects what actually reached one turn.
type homeRecorder struct {
	mu     sync.Mutex
	inputs []string
}

func (r *homeRecorder) record(s string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.inputs = append(r.inputs, s)
}

func (r *homeRecorder) saw(sub string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, s := range r.inputs {
		if strings.Contains(s, sub) {
			return true
		}
	}
	return false
}

// homeMock implements the fixture.json mockLLM scripts. Reuses the openai-style
// helpers (parseReq/textResp/callResp/toolCallPart) from mock_llm_test.go. The
// optional gate parks m-heartbeat until released (or the request context dies) so
// a turn can be held genuinely in-flight; the optional rec captures inputs.
type homeMock struct {
	gate chan struct{}
	rec  *homeRecorder
}

func (m homeMock) RoundTrip(req *http.Request) (*http.Response, error) {
	p := parseReq(req)
	if m.rec != nil {
		m.rec.record(p.last)
	}
	if m.gate != nil && p.model == "m-heartbeat" {
		select {
		case <-m.gate:
		case <-req.Context().Done():
			return nil, req.Context().Err()
		}
	}
	switch p.model {
	case "m-echo-soul": // reports which bootstrap sections it can see, in order
		var found []string
		for _, f := range BootstrapOrder {
			if strings.Contains(p.system, "## "+f) {
				found = append(found, f)
			}
		}
		return textResp("sections:[" + strings.Join(found, ",") + "]"), nil
	case "m-remember": // writes a memory then confirms
		if len(p.toolMsgs) == 0 {
			return callResp(toolCallPart("memory",
				map[string]any{"action": "add", "target": "user", "text": "Prefers dark roast"}, "w1")), nil
		}
		return textResp("saved: " + p.toolMsgs[0]), nil
	case "m-recall": // proves the next-session snapshot carries prior USER.md content
		if strings.Contains(p.system, "Prefers dark roast") {
			return textResp("I recall: dark roast"), nil
		}
		return textResp("no memory"), nil
	case "m-heartbeat": // speaks only when the HEARTBEAT.md rule is due
		if strings.Contains(p.system, "remind about the 3pm sync") && strings.Contains(p.last, "Heartbeat") {
			return textResp("Reminder: 3pm sync 🔔"), nil
		}
		return textResp(HeartbeatOK), nil
	default:
		return textResp("ok"), nil
	}
}

// armed reports how many After channels are currently registered on the virtual
// clock (the re-arm sync point for a deterministic heartbeat test).
func (c *fakeClock) armed() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.chs)
}

// waitArmed spins (bounded) until the virtual clock has a pending After channel.
func waitArmed(t *testing.T, c *fakeClock) {
	t.Helper()
	for i := 0; i < 500; i++ {
		if c.armed() > 0 {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatal("heartbeat never armed the virtual clock")
}

// falseP is a *bool pointing at false (memory opt-out).
func falseP() *bool { b := false; return &b }

// ---- H1: the directory IS the agent ----------------------------------------

func TestHomeH1BootstrapDiscoveryAndInjection(t *testing.T) {
	fx := loadFixture(t)
	dir := writeBootstrap(t, fx.BootstrapDir)

	soul, found := ComposeSoul(dir)

	// H1.1 — discovers only present files, in canonical order.
	if got := strings.Join(found, ","); got != "SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md" {
		t.Errorf("H1.1 discovery order: got %q", got)
	}
	// H1.2 — injected as ## sections, SOUL before USER before HEARTBEAT before MEMORY.
	iS, iU := strings.Index(soul, "## SOUL.md"), strings.Index(soul, "## USER.md")
	iH, iM := strings.Index(soul, "## HEARTBEAT.md"), strings.Index(soul, "## MEMORY.md")
	if !(iS < iU && iU < iH && iH < iM) {
		t.Errorf("H1.2 section ordering wrong: SOUL=%d USER=%d HEARTBEAT=%d MEMORY=%d", iS, iU, iH, iM)
	}
	// H1.3 — the composed soul reaches the child's system prompt.
	ava := FromDir(dir, FromDirOptions{Model: "m-echo-soul"})
	r, _ := ava.Run(Options{Transport: homeMock{}}, "who are you?")
	if r.Text != "sections:[SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md]" {
		t.Errorf("H1.3 soul did not reach child system prompt: %q", r.Text)
	}
}

// ---- H2: per-file byte cap -------------------------------------------------

func TestHomeH2PerFileByteCap(t *testing.T) {
	dir := writeBootstrap(t, map[string]string{"SOUL.md": strings.Repeat("x", 3*1024*1024)})
	soul, _ := ComposeSoul(dir)
	// H2.1 — a >2 MB file is truncated with a notice; the on-disk file is untouched.
	if !strings.Contains(soul, "[truncated: exceeds 2 MB bootstrap cap]") || len(soul) > 2*1024*1024+4096 {
		t.Errorf("H2.1 over-cap file not truncated (len=%d)", len(soul))
	}
	onDisk, _ := os.ReadFile(filepath.Join(dir, "SOUL.md"))
	if len(onDisk) != 3*1024*1024 {
		t.Errorf("H2.1 on-disk file was mutated (len=%d)", len(onDisk))
	}
}

// ---- H3: the memory tool persists to disk ----------------------------------

func TestHomeH3MemoryToolPersists(t *testing.T) {
	dir := writeBootstrap(t, map[string]string{"MEMORY.md": "- Likes green tea."})
	tool := MemoryTool(dir)
	read := func(name string) string {
		b, _ := os.ReadFile(filepath.Join(dir, name))
		return string(b)
	}

	// H3.1 — add appends an entry.
	if _, _ = tool.Execute(map[string]any{"action": "add", "text": "Likes hiking"}, nil); !strings.Contains(read("MEMORY.md"), "- Likes hiking") {
		t.Error("H3.1 add did not append")
	}
	// H3.2 — replace swaps a substring.
	if _, _ = tool.Execute(map[string]any{"action": "replace", "text": "green tea", "with": "oolong"}, nil); !strings.Contains(read("MEMORY.md"), "oolong") {
		t.Error("H3.2 replace did not swap")
	}
	// H3.3 — remove deletes an entry.
	if _, _ = tool.Execute(map[string]any{"action": "remove", "text": "- Likes hiking\n"}, nil); strings.Contains(read("MEMORY.md"), "hiking") {
		t.Error("H3.3 remove did not delete")
	}
	// H3.4 — replace of a missing substring is a loud isError; file unchanged.
	before := read("MEMORY.md")
	miss, _ := tool.Execute(map[string]any{"action": "replace", "text": "nonexistent", "with": "x"}, nil)
	if !miss.IsError || read("MEMORY.md") != before {
		t.Errorf("H3.4 missing substring not a loud error (isError=%v)", miss.IsError)
	}
	// H3.5 — target=user writes USER.md, not MEMORY.md.
	uw, _ := tool.Execute(map[string]any{"action": "add", "target": "user", "text": "Speaks Tamil"}, nil)
	if uw.IsError || !strings.Contains(read("USER.md"), "Speaks Tamil") {
		t.Error("H3.5 target=user did not write USER.md")
	}
}

// ---- H4: frozen-snapshot — a mid-session write never changes the live prompt

func TestHomeH4FrozenSnapshot(t *testing.T) {
	dir := writeBootstrap(t, map[string]string{"USER.md": "The user is new."})

	// A first run writes a memory via the tool.
	ava := FromDir(dir, FromDirOptions{Model: "m-remember"})
	ava.Run(Options{Transport: homeMock{}}, "note my coffee preference")

	// H4.1 — the write landed on disk.
	onDisk, _ := os.ReadFile(filepath.Join(dir, "USER.md"))
	if !strings.Contains(string(onDisk), "Prefers dark roast") {
		t.Errorf("H4.1 memory write did not persist: %q", string(onDisk))
	}
	// H4.2 — a SECOND, fresh FromDir re-reads the file → the snapshot carries it.
	ava2 := FromDir(dir, FromDirOptions{Model: "m-recall"})
	r2, _ := ava2.Run(Options{Transport: homeMock{}}, "what do you know about me?")
	if r2.Text != "I recall: dark roast" {
		t.Errorf("H4.2 next-session snapshot missing memory: %q", r2.Text)
	}
}

// ---- H5: heartbeat — silent OK, speaks only when due, ticks coalesce --------

func TestHomeH5Heartbeat(t *testing.T) {
	// H5.1 — a due beat surfaces to onBeat (via the real StartAgent + virtual clock).
	{
		dir := writeBootstrap(t, map[string]string{
			"SOUL.md":      "You are Ava.",
			"HEARTBEAT.md": "If it is time, remind about the 3pm sync.",
		})
		clk := newFakeClock()
		ava := FromDir(dir, FromDirOptions{Model: "m-heartbeat"})
		sa, err := StartAgent(ava, StartOptions{
			Options: Options{Transport: homeMock{}, Clock: clk},
			EveryMs: 60_000,
		})
		if err != nil {
			t.Fatal(err)
		}
		waitArmed(t, clk)
		clk.fire()
		<-sa.beat
		beats := sa.Beats()
		sa.Stop()
		if len(beats) < 1 || !strings.Contains(beats[0], "3pm sync") {
			t.Errorf("H5.1 due beat did not surface: %v", beats)
		}
	}

	// H5.2 — a HEARTBEAT_OK reply stays silent (no report).
	{
		dir := writeBootstrap(t, map[string]string{
			"SOUL.md":      "You are Ava.",
			"HEARTBEAT.md": "Nothing scheduled today.",
		})
		clk := newFakeClock()
		ava := FromDir(dir, FromDirOptions{Model: "m-heartbeat"})
		sa, err := StartAgent(ava, StartOptions{
			Options: Options{Transport: homeMock{}, Clock: clk},
			EveryMs: 60_000,
		})
		if err != nil {
			t.Fatal(err)
		}
		waitArmed(t, clk)
		clk.fire()
		<-sa.beat
		beats := sa.Beats()
		sa.Stop()
		if len(beats) != 0 {
			t.Errorf("H5.2 HEARTBEAT_OK beat was not silent: %v", beats)
		}
	}

	// H5.3 — ticks that arrive WHILE a beat's turn is in-flight coalesce into ONE
	// subsequent turn, not one turn per tick. The mock gate holds the first turn
	// genuinely running while three more ticks are posted; on release they drain
	// together, and the recorder proves a single turn saw "tick (x3 coalesced)".
	{
		dir := writeBootstrap(t, map[string]string{
			"SOUL.md":      "You are Ava.",
			"HEARTBEAT.md": "If it is time, remind about the 3pm sync.",
		})
		gate := make(chan struct{})
		rec := &homeRecorder{}
		mock := homeMock{gate: gate, rec: rec}
		ava := FromDir(dir, FromDirOptions{Model: "m-heartbeat"})
		rt := NewRuntime(Options{Transport: mock, Registry: ava.Registry()})
		h, err := rt.Spawn(rt.Root, ava.Name, nil)
		if err != nil {
			t.Fatal(err)
		}
		const hb = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK."

		// Turn 1: one tick, then wake — the turn parks in the gated mock.
		rt.Post(h, InboxItem{From: "clock", Channel: "timer", Text: "tick"})
		rt.Wake(h, hb)
		if !awaitState(rt, h, StateRunning) {
			t.Fatal("H5.3 first turn never entered running")
		}
		// Three more intervals elapse while that turn is still in-flight.
		for i := 0; i < 3; i++ {
			rt.Post(h, InboxItem{From: "clock", Channel: "timer", Text: "tick"})
		}
		close(gate) // release turn 1 (and let turn 2 run freely)
		r1 := rt.Wait(h, 0)
		if !strings.Contains(r1.Text, "3pm sync") {
			t.Errorf("H5.3 first beat did not surface: %q", r1.Text)
		}
		// Turn 2: a single wake drains the three queued ticks as ONE coalesced turn.
		rt.Wake(h, hb)
		r2 := rt.Wait(h, 0)
		rt.Close(rt.Root, nil)
		if r2.Turns != 1 {
			t.Errorf("H5.3 queued ticks did not coalesce to one turn (turns=%d)", r2.Turns)
		}
		if !rec.saw("tick (x3 coalesced)") {
			t.Errorf("H5.3 three in-flight ticks did not coalesce into one turn's input; saw %v", rec.inputs)
		}
	}
}

// ---- H6: memory opt-out ----------------------------------------------------

func TestHomeH6MemoryOptOut(t *testing.T) {
	dir := writeBootstrap(t, map[string]string{"SOUL.md": "Read-only Ava."})
	ava := FromDir(dir, FromDirOptions{Memory: falseP()})
	for _, tl := range ava.Spec.Tools {
		if tl.Name == "memory" {
			t.Fatal("H6 memory tool present despite Memory=false")
		}
	}
}

// ---- H7: dream/consolidation is composition, not new surface ---------------

func TestHomeH7DreamIsComposition(t *testing.T) {
	// A "dream" agent is just FromDir + the memory tool + a HEARTBEAT.md that says
	// "fold notes into MEMORY.md" — StartAgent + MemoryTool composed, no new API.
	dir := writeBootstrap(t, map[string]string{
		"SOUL.md":      "Nightly consolidator.",
		"HEARTBEAT.md": "Consolidate: merge duplicate notes into MEMORY.md.",
	})
	dream := FromDir(dir, FromDirOptions{Model: "m-echo-soul"})
	has := false
	for _, tl := range dream.Spec.Tools {
		if tl.Name == "memory" {
			has = true
		}
	}
	if !has {
		t.Error("H7 dream agent should be fromDir + the memory tool (composition)")
	}
}
