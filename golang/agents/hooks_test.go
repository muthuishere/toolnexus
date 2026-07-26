// Shared fixture: examples/agent-hooks/fixture.json (scenarios H1-H6).
// §7D "The §8 seams on an agent run": Hooks/OnMetric forwarded verbatim into the
// client the runtime builds, resolved def-over-runtime (replace, never merge),
// so a §7F compactor reaches a long-lived agent. Unset stays byte-identical.
package agents

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

// recordingLLM captures the exact transcript each turn sent, keyed by model.
type recordingLLM struct {
	mu   sync.Mutex
	sent map[string][][]map[string]any
}

func newRecordingLLM() *recordingLLM {
	return &recordingLLM{sent: map[string][][]map[string]any{}}
}

func (r *recordingLLM) RoundTrip(req *http.Request) (*http.Response, error) {
	p := parseReq(req)
	r.mu.Lock()
	r.sent[p.model] = append(r.sent[p.model], p.messages)
	r.mu.Unlock()
	return textResp("ok"), nil
}

func (r *recordingLLM) last(model string) []map[string]any {
	r.mu.Lock()
	defer r.mu.Unlock()
	runs := r.sent[model]
	if len(runs) == 0 {
		return nil
	}
	return runs[len(runs)-1]
}

func hooksReg(defs ...Def) map[string]Def {
	m := map[string]Def{}
	for _, d := range defs {
		m[d.Name] = d
	}
	return m
}

// seedTranscript stuffs a handle's stored transcript with a long history so the
// next turn is over budget.
func seedTranscript(t *testing.T, store tn.ConversationStore, id string, pairs int) {
	t.Helper()
	msgs := []any{map[string]any{"role": "system", "content": "SOUL-VERBATIM"}}
	for i := 0; i < pairs; i++ {
		msgs = append(msgs,
			map[string]any{"role": "user", "content": strings.Repeat("q", 200)},
			map[string]any{"role": "assistant", "content": strings.Repeat("a", 200)},
		)
	}
	if err := store.Save(id, msgs); err != nil {
		t.Fatalf("seed: %v", err)
	}
}

// 1. A beforeLLM hook set on Options runs inside an agent turn.
func TestAgentHooks_RuntimeHooksRunInAgentTurn(t *testing.T) {
	var saw int
	mock := newRecordingLLM()
	rt := NewRuntime(Options{
		Transport: mock,
		Registry:  hooksReg(Def{Name: "a", Does: "x", Soul: "soul-a", Model: "m-a"}),
		Hooks: &tn.Hooks{BeforeLLM: func(ctx context.Context, e tn.BeforeLLMEvent) (*tn.LLMOverride, error) {
			saw++
			msgs := append([]any{}, e.Messages...)
			msgs = append(msgs, map[string]any{"role": "system", "content": "INJECTED-BY-HOOK"})
			return &tn.LLMOverride{Messages: msgs}, nil
		}},
	})
	defer rt.Close(rt.Root, nil)

	h, err := rt.Spawn(rt.Root, "a", nil)
	if err != nil {
		t.Fatal(err)
	}
	if r := rt.RunTurn(h, "hello"); r.IsError {
		t.Fatal(r.Text)
	}
	if saw == 0 {
		t.Fatal("beforeLLM never ran inside the agent turn")
	}
	sent := mock.last("m-a")
	found := false
	for _, m := range sent {
		if c, _ := m["content"].(string); c == "INJECTED-BY-HOOK" {
			found = true
		}
	}
	if !found {
		t.Fatalf("hook rewrite did not reach the wire; sent=%v", sent)
	}
}

// 2. OnMetric set on Options receives the §8 semantic events.
func TestAgentHooks_RuntimeOnMetricReceivesEvents(t *testing.T) {
	var mu sync.Mutex
	var events []string
	rt := NewRuntime(Options{
		Transport: newRecordingLLM(),
		Registry:  hooksReg(Def{Name: "a", Does: "x", Model: "m-a"}),
		OnMetric: func(ev tn.MetricEvent) {
			mu.Lock()
			events = append(events, ev.Event)
			mu.Unlock()
		},
	})
	defer rt.Close(rt.Root, nil)

	h, _ := rt.Spawn(rt.Root, "a", nil)
	if r := rt.RunTurn(h, "hi"); r.IsError {
		t.Fatal(r.Text)
	}
	mu.Lock()
	got := strings.Join(events, ",")
	mu.Unlock()
	if !strings.Contains(got, "llm") || !strings.Contains(got, "run") {
		t.Fatalf("expected llm and run events, got %q", got)
	}
}

// 3. Def-level replaces runtime-level, per agent, independently per field.
func TestAgentHooks_DefHooksReplaceRuntimeHooks(t *testing.T) {
	var mu sync.Mutex
	fired := map[string]int{}
	mark := func(tag string) *tn.Hooks {
		return &tn.Hooks{BeforeLLM: func(ctx context.Context, e tn.BeforeLLMEvent) (*tn.LLMOverride, error) {
			mu.Lock()
			fired[tag]++
			mu.Unlock()
			return nil, nil
		}}
	}
	var metricTags []string
	rt := NewRuntime(Options{
		Transport: newRecordingLLM(),
		Hooks:     mark("runtime"),
		OnMetric: func(ev tn.MetricEvent) {
			mu.Lock()
			metricTags = append(metricTags, "runtime")
			mu.Unlock()
		},
		Registry: hooksReg(
			// A overrides hooks but NOT OnMetric — the two resolve independently.
			Def{Name: "a", Does: "x", Model: "m-a", Hooks: mark("defA")},
			Def{Name: "b", Does: "x", Model: "m-b"},
		),
	})
	defer rt.Close(rt.Root, nil)

	ha, _ := rt.Spawn(rt.Root, "a", nil)
	hb, _ := rt.Spawn(rt.Root, "b", nil)
	if r := rt.RunTurn(ha, "hi"); r.IsError {
		t.Fatal(r.Text)
	}
	if r := rt.RunTurn(hb, "hi"); r.IsError {
		t.Fatal(r.Text)
	}

	mu.Lock()
	defer mu.Unlock()
	if fired["defA"] != 1 {
		t.Fatalf("agent A should run its own hook once, got %d", fired["defA"])
	}
	if fired["runtime"] != 1 {
		t.Fatalf("runtime hook should run ONLY for B (not merged into A), got %d", fired["runtime"])
	}
	if len(metricTags) < 2 {
		t.Fatalf("A inherits the runtime OnMetric even though it overrode Hooks; got %v", metricTags)
	}
}

// 4. THE POINT: a real §7F compactor attached per-agent compacts an agent turn.
func TestAgentHooks_CompactorAttachedToAgentRun(t *testing.T) {
	mock := newRecordingLLM()
	store := tn.NewInMemoryConversationStore()
	compactor := Compactor(CompactorOptions{
		MaxTokens: 200,
		KeepTail:  80,
		Summarize: func(older []any) (string, error) { return "SUMMARY-OF-HEAD", nil },
	})
	rt := NewRuntime(Options{
		Transport: mock,
		Store:     store,
		Registry: hooksReg(Def{
			Name: "long", Does: "x", Soul: "SOUL-VERBATIM", Model: "m-a",
			Hooks: &tn.Hooks{BeforeLLM: compactor},
		}),
	})
	defer rt.Close(rt.Root, nil)

	h, _ := rt.Spawn(rt.Root, "long", nil)
	seedTranscript(t, store, h.ID, 40)
	pre, _ := store.Get(h.ID)
	if len(pre) != 81 {
		t.Fatalf("seed did not land on the handle's conversation id: pre=%d want 81", len(pre))
	}
	if r := rt.RunTurn(h, "next question"); r.IsError {
		t.Fatal(r.Text)
	}

	sent := mock.last("m-a")
	stored, _ := store.Get(h.ID)
	if len(sent) >= len(pre) {
		t.Fatalf("compaction did not shrink the transcript: pre=%d sent=%d", len(pre), len(sent))
	}
	if c, _ := sent[0]["content"].(string); c != "SOUL-VERBATIM" {
		t.Fatalf("leading system prompt not preserved verbatim, got %q", c)
	}
	blob, _ := json.Marshal(sent)
	if !strings.Contains(string(blob), "SUMMARY-OF-HEAD") {
		t.Fatal("summary system message missing from the compacted transcript")
	}
	t.Logf("pre-run stored=%d → wire=%d → post-run stored=%d", len(pre), len(sent), len(stored))
}

// 5. Unset is byte-identical. goldenUnsetRun was RECORDED ON MAIN, before this
// capability existed (captured from a git worktree of main, not from this branch)
// — so it checks the run against PRE-change output, not against itself.
const goldenUnsetRun = `{"result":{"status":"done","text":"ok","tokens":40,"turns":1},"stored":[{"content":"soul-a","role":"system"},{"content":"hello","role":"user"},{"content":"ok","role":"assistant"}],"trace":["root/a.1: spawned (depth 1, tokens Infinity)","root/a.1: idle→running (wake)","root/a.1: running→idle (done, turns=1, tokens=40)"],"wire":[{"content":"soul-a","role":"system"},{"content":"hello","role":"user"}]}`

func TestAgentHooks_UnsetIsByteIdenticalToPreChange(t *testing.T) {
	mock := newRecordingLLM()
	store := tn.NewInMemoryConversationStore()
	rt := NewRuntime(Options{
		Transport: mock,
		Store:     store,
		Registry:  hooksReg(Def{Name: "a", Does: "x", Soul: "soul-a", Model: "m-a"}),
	})
	defer rt.Close(rt.Root, nil)

	h, _ := rt.Spawn(rt.Root, "a", nil)
	r := rt.RunTurn(h, "hello")
	stored, _ := store.Get(h.ID)
	b, _ := json.Marshal(map[string]any{
		"wire":   mock.last("m-a"),
		"stored": stored,
		"trace":  rt.Trace(),
		"result": map[string]any{"text": r.Text, "status": r.Status, "turns": r.Turns, "tokens": r.TotalTokens},
	})
	if string(b) != goldenUnsetRun {
		t.Fatalf("unset run drifted from the pre-change recording\n got: %s\nwant: %s", b, goldenUnsetRun)
	}
}

// 6. §10 interaction: a turn that compacts and THEN suspends. The runtime rewinds
// the stored transcript to the pre-turn checkpoint — so does the compaction survive?
func TestAgentHooks_CompactionThenPendingRewind(t *testing.T) {
	store := tn.NewInMemoryConversationStore()
	compactor := Compactor(CompactorOptions{
		MaxTokens: 200, KeepTail: 80,
		Summarize: func(older []any) (string, error) { return "SUMMARY-OF-HEAD", nil },
	})
	reg := testRegistry()
	d := reg["asker"]
	d.Hooks = &tn.Hooks{BeforeLLM: compactor}
	reg["asker"] = d

	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: reg, Store: store})
	defer rt.Close(rt.Root, nil)

	h, _ := rt.Spawn(rt.Root, "asker", nil)
	seedTranscript(t, store, h.ID, 40)
	pre, _ := store.Get(h.ID)

	r := rt.RunTurn(h, "get the secret")
	post, _ := store.Get(h.ID)
	t.Logf("status=%q  pre=%d  post=%d", r.Status, len(pre), len(post))

	if r.Status != "pending" {
		t.Skipf("fixture did not suspend (status %q) — rewind path not exercised", r.Status)
	}
	if len(post) != len(pre) {
		t.Fatalf("SPEC GAP: a compacted turn that suspends left the store at %d, not the %d-message pre-turn checkpoint", len(post), len(pre))
	}
}
