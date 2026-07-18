// Level-1 surface + bridge tests (S10–S13): New/Run with team wiring and soul
// files, transitive-closure registries, the persona-from-directory and
// heartbeat patterns expressed in USERLAND over the six verbs (agent-home stays
// a future change — no library API for it here), and AsTool inside the classic
// createToolkit/createClient API.
package agents

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

// S10 — the 12-line coding agent: agent() + team wiring + soul file.
func TestLevel1CodingAgentWithSoulFile(t *testing.T) {
	soulPath := filepath.Join(t.TempDir(), "AGENTS.md")
	if err := os.WriteFile(soulPath, []byte("You are the CODER. Fix things surgically."), 0o644); err != nil {
		t.Fatal(err)
	}
	explore := New("explore", Spec{Does: "read-only research", Tools: []tn.Tool{lookupTool}, Model: "m-explore"})
	coder := New("coder", Spec{
		Does:     "implements changes",
		SoulFile: soulPath,
		Team:     []*Agent{explore},
		Model:    "m-coder",
		Budget:   &Budget{MaxTokens: 10_000},
	})
	r, _ := coder.Run(Options{Transport: mockUX{}}, "fix the failing test")
	if r.Status != "done" || !strings.Contains(r.Text, "bug at line 42") {
		t.Errorf("coding agent should complete via its team, got %q (%s)", r.Status, r.Text)
	}
	if !strings.Contains(r.Text, "[soul:loaded]") {
		t.Errorf("the soul FILE should reach the child's system prompt, got %q", r.Text)
	}
}

// S11 — team scoping: the registry is the transitive closure of the entry
// agent's team graph; task targets are exactly the declared team.
func TestTeamScopingRegistryClosure(t *testing.T) {
	_ = New("stranger", Spec{Does: "should be unreachable", Model: "m-explore", Tools: []tn.Tool{lookupTool}})
	explore := New("explore", Spec{Does: "read-only research", Tools: []tn.Tool{lookupTool}, Model: "m-explore"})
	coder := New("coder", Spec{Does: "implements", Team: []*Agent{explore}, Model: "m-coder"})

	reg := coder.Registry()
	keys := make([]string, 0, len(reg))
	for k := range reg {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	if got := strings.Join(keys, ","); got != "coder,explore" {
		t.Errorf("registry should contain only the reachable graph, got %s", got)
	}
	tj, _ := json.Marshal(reg["coder"].Team)
	if string(tj) != `["explore"]` {
		t.Errorf("coder's task targets should be exactly its team, got %s", tj)
	}
	if len(reg["explore"].Team) != 0 {
		t.Error("explore declared no team — recursion stays opt-in")
	}
}

// S12 — the persona/heartbeat pattern in USERLAND: bootstrap files rendered
// into the soul as "## <file>" sections, timer ticks posted to the inbox
// (coalescing on the unsolicited rail), quiet beats staying silent, clean stop.
func TestPersonaHeartbeatUserlandPattern(t *testing.T) {
	dir := t.TempDir()
	files := map[string]string{
		"SOUL.md":      "You are Mia. Warm, brief.",
		"USER.md":      "The user is Muthu.",
		"MEMORY.md":    "- Likes green tea.",
		"HEARTBEAT.md": "On heartbeat: if it is watering day, remind to water the plants.",
	}
	for name, body := range files {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	// Userland bootstrap (openclaw order) — the directory IS the agent.
	var sections []string
	for _, f := range []string{"AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md"} {
		if b, err := os.ReadFile(filepath.Join(dir, f)); err == nil {
			sections = append(sections, "## "+f+"\n\n"+strings.TrimSpace(string(b)))
		}
	}
	mia := New("mia", Spec{Does: "persona agent from " + dir, Soul: strings.Join(sections, "\n\n"), Model: "m-mia"})

	direct, _ := mia.Run(Options{Transport: mockUX{}}, "hello")
	if direct.Text != "soul-sections:[SOUL.md,USER.md,MEMORY.md]" {
		t.Errorf("bootstrap sections should be injected as ## sections, got %q", direct.Text)
	}

	// Heartbeat loop over the verbs: post a tick, wake at idle, mute HEARTBEAT_OK.
	rt := NewRuntime(Options{Transport: mockUX{}, Registry: mia.Registry()})
	h, err := rt.Spawn(rt.Root, "mia", nil)
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var reports []string
	const heartbeatPrompt = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK."
	for i := 0; i < 3; i++ {
		rt.Post(h, InboxItem{From: "clock", Channel: "timer", Text: "tick"})
		if rt.StateOf(h) == StateIdle {
			r := rt.RunTurn(h, heartbeatPrompt)
			if !r.IsError && !strings.Contains(r.Text, "HEARTBEAT_OK") {
				mu.Lock()
				reports = append(reports, r.Text)
				mu.Unlock()
			}
		}
	}
	beats := 0
	for _, l := range rt.Trace() {
		if strings.Contains(l, "idle→running") {
			beats++
		}
	}
	if beats < 2 {
		t.Errorf("heartbeat should wake the agent repeatedly, got %d turns", beats)
	}
	mu.Lock()
	for _, rep := range reports {
		if !strings.Contains(rep, "water") {
			t.Errorf("quiet beats must stay SILENT (HEARTBEAT_OK muted), leaked %q", rep)
		}
	}
	mu.Unlock()
	rt.Close(rt.Root, nil)
	if got := rt.StateOf(h); got != StateClosed {
		t.Errorf("agent should close cleanly on stop, is %s", got)
	}
}

// S13 — the bridge: an Agent IS a Tool inside the classic API.
func TestAsToolInsideClassicAPI(t *testing.T) {
	explore := New("explore", Spec{Does: "read-only research", Tools: []tn.Tool{lookupTool}, Model: "m-explore"})
	toolkit, err := tn.CreateToolkit(nil, tn.Options{
		Builtins:   false,
		ExtraTools: []tn.Tool{explore.AsTool(Options{Transport: mockUX{}})},
	})
	if err != nil {
		t.Fatal(err)
	}
	client := tn.CreateClient(tn.ClientOptions{
		BaseURL:    "http://mock.local",
		Style:      tn.StyleOpenAI,
		Model:      "m-old-api",
		APIKey:     "test",
		HTTPClient: &http.Client{Transport: mockUX{}},
	})
	r, err := client.Run(nil, "summarize", toolkit)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(r.Text, "old-api summary:") || !strings.Contains(r.Text, "bug at line 42") {
		t.Errorf("the classic API should call the agent like any tool, got %q", r.Text)
	}
	if len(r.ToolCalls) == 0 {
		t.Fatal("expected the agent tool call in the transcript")
	}
	meta := r.ToolCalls[0].Metadata
	if agent, _ := meta["agent"].(string); agent != "explore" {
		t.Errorf("agent metadata should surface through the tool result, got %v", meta)
	}
	if _, ok := meta["turns"]; !ok {
		t.Errorf("metadata should carry turns/totalTokens, got %v", meta)
	}
}

// Level-1 durable path: a run with no interpreter parks pending and the
// RETURNED runtime resumes it (Agent.Run keeps the runtime alive on pending).
func TestLevel1DurableRunResume(t *testing.T) {
	asker := New("asker", Spec{Does: "needs approvals", Tools: []tn.Tool{checkSecretTool}, Model: "m-asker"})
	boss := New("boss", Spec{Does: "delegates, no interpreter", Team: []*Agent{asker}, Model: "m-approver-parent"})
	r, rt := boss.Run(Options{Transport: &mockLLM{}}, "do the secret thing")
	if r.Status != "pending" || r.Pending == nil {
		t.Fatalf("no interpreter anywhere should park durably, got %q", r.Status)
	}
	if err := rt.Resume(tn.Answer{ID: r.Pending.ID, Ok: true}); err != nil {
		t.Fatal(err)
	}
	if !traceHas(rt, "task replay → REATTACH") {
		t.Error("resume through the returned runtime should reattach")
	}
}

// The task tool's description advertises the TEAM sorted by name, composed
// from each agent's Does — and only the team.
func TestTaskDescriptionSortedTeam(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h, _ := rt.Spawn(rt.Root, "coordinator", nil)
	tool := rt.taskTool(h, []string{"explore", "asker"})
	want := "asker: needs approvals; explore: read-only research"
	if !strings.HasSuffix(tool.Description, want) {
		t.Errorf("task description should list the team sorted by name, got %q", tool.Description)
	}
	// Sanity: the Level-1 spec surface stays wired to the same tool name.
	if tool.Name != "task" {
		t.Errorf("model surface tool must be named task, got %q", tool.Name)
	}
}
