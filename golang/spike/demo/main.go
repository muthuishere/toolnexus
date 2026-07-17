// SPIKE demo — the 13 acceptance scenarios (46 checks) for the agent-runtime
// substrate v2, ported from js/spike/demo.ts (S1–S9) + js/spike/demo-ux.ts
// (S10–S13). Zero network, zero cost: the LLM is a scripted in-process
// http.RoundTripper injected through the shipped client's HTTPClient seam.
//
// Run: cd golang && go run ./spike/demo
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/spike"
)

// ---------- scripted mock LLM (openai style, keyed by body.model) ----------

var slowGate chan struct{}

func openaiResp(message map[string]any) *http.Response {
	msg := map[string]any{"role": "assistant"}
	for k, v := range message {
		msg[k] = v
	}
	body := map[string]any{
		"choices": []any{map[string]any{"message": msg}},
		"usage":   map[string]any{"prompt_tokens": 30, "completion_tokens": 10, "total_tokens": 40},
	}
	b, _ := json.Marshal(body)
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(bytes.NewReader(b)),
	}
}

func textResp(content string) *http.Response {
	return openaiResp(map[string]any{"content": content})
}

func toolCall(name string, args map[string]any, id string) map[string]any {
	aj, _ := json.Marshal(args)
	return map[string]any{
		"id":       id,
		"type":     "function",
		"function": map[string]any{"name": name, "arguments": string(aj)},
	}
}

func callResp(calls ...map[string]any) *http.Response {
	raw := make([]any, len(calls))
	for i, c := range calls {
		raw[i] = c
	}
	return openaiResp(map[string]any{"content": nil, "tool_calls": raw})
}

type parsedReq struct {
	model    string
	messages []map[string]any
	toolMsgs []string // contents of role=="tool" messages, in order
	system   string
	last     string // content of the last message
}

func parseReq(req *http.Request) parsedReq {
	b, _ := io.ReadAll(req.Body)
	var body struct {
		Model    string           `json:"model"`
		Messages []map[string]any `json:"messages"`
	}
	_ = json.Unmarshal(b, &body)
	p := parsedReq{model: body.Model, messages: body.Messages}
	for _, m := range body.Messages {
		content, _ := m["content"].(string)
		switch m["role"] {
		case "tool":
			p.toolMsgs = append(p.toolMsgs, content)
		case "system":
			p.system = content
		}
	}
	if n := len(body.Messages); n > 0 {
		p.last, _ = body.Messages[n-1]["content"].(string)
	}
	return p
}

var itemLineRe = regexp.MustCompile(`(?m)^\d+\.`)

// mockRuntime scripts the S1–S9 models (mirror of demo.ts mockFetch).
type mockRuntime struct{}

func (mockRuntime) RoundTrip(req *http.Request) (*http.Response, error) {
	p := parseReq(req)
	// async-fetch-like latency so turn concurrency is observable (JS event loop
	// interleaving equivalent).
	time.Sleep(5 * time.Millisecond)
	switch p.model {
	case "m-coordinator":
		if len(p.toolMsgs) == 0 {
			return callResp(
				toolCall("task", map[string]any{"agent": "explore", "prompt": "find A"}, "c1"),
				toolCall("task", map[string]any{"agent": "explore", "prompt": "find B"}, "c2"),
			), nil
		}
		return textResp("synthesis: " + strings.Join(p.toolMsgs, " + ")), nil
	case "m-explore":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCall("lookup", map[string]any{"q": "x"}, "e1")), nil
		}
		return textResp("found:" + p.toolMsgs[0]), nil
	case "m-approver-parent":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCall("task", map[string]any{"agent": "asker", "prompt": "get the secret"}, "p1")), nil
		}
		return textResp("parent-final: " + p.toolMsgs[len(p.toolMsgs)-1]), nil
	case "m-asker":
		approved := false
		for _, m := range p.toolMsgs {
			if strings.Contains(m, "secret-token") {
				approved = true
			}
		}
		if !approved {
			return callResp(toolCall("check_secret", map[string]any{}, "a1")), nil
		}
		return textResp("asker-done: secret-token"), nil
	case "m-peer":
		items := len(itemLineRe.FindAllString(p.last, -1))
		return textResp(fmt.Sprintf("processed %d items", items)), nil
	case "m-loop": // never finishes: always another tool call → maxTurns/incomplete
		return callResp(toolCall("lookup", map[string]any{"q": "again"}, fmt.Sprintf("l%d", len(p.toolMsgs)))), nil
	case "m-slow":
		select {
		case <-slowGate:
			return textResp("slow-done"), nil
		case <-req.Context().Done():
			return nil, req.Context().Err()
		}
	default:
		return textResp("ok"), nil
	}
}

// mockUX scripts the S10–S13 models (mirror of demo-ux.ts mockFetch).
type mockUX struct{}

func (mockUX) RoundTrip(req *http.Request) (*http.Response, error) {
	p := parseReq(req)
	time.Sleep(2 * time.Millisecond)
	switch p.model {
	case "m-coder":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCall("task", map[string]any{"agent": "explore", "prompt": "find the bug"}, "t1")), nil
		}
		soul := "missing"
		if strings.Contains(p.system, "You are the CODER") {
			soul = "loaded"
		}
		return textResp(fmt.Sprintf("fixed using: %s [soul:%s]", p.toolMsgs[0], soul)), nil
	case "m-explore":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCall("lookup", map[string]any{"q": "bug"}, "e1")), nil
		}
		return textResp(fmt.Sprintf("bug at line 42 (%s)", p.toolMsgs[0])), nil
	case "m-mia":
		// persona: soul sections visible? heartbeat with nothing to do → HEARTBEAT_OK
		if strings.Contains(p.last, "Heartbeat") {
			hasTicks := strings.Contains(p.last, "channel=timer")
			if strings.Contains(p.system, "water the plants") && hasTicks {
				return textResp("Reminder: water the plants! 🌱"), nil
			}
			return textResp(spike.HeartbeatOK), nil
		}
		var found []string
		for _, f := range []string{"SOUL.md", "USER.md", "MEMORY.md"} {
			if strings.Contains(p.system, "## "+f) {
				found = append(found, f)
			}
		}
		return textResp("soul-sections:[" + strings.Join(found, ",") + "]"), nil
	case "m-old-api":
		if len(p.toolMsgs) == 0 {
			return callResp(toolCall("explore", map[string]any{"prompt": "scan the repo"}, "b1")), nil
		}
		return textResp("old-api summary: " + p.toolMsgs[0]), nil
	default:
		return textResp("ok"), nil
	}
}

// ---------- scoped tools ----------

var lookup = tn.Tool{
	Name:        "lookup",
	Description: "look something up",
	InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{"q": map[string]any{"type": "string"}}},
	Source:      tn.SourceCustom,
	Execute: func(args map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
		return tn.ToolResult{Output: fmt.Sprintf("data(%v)", args["q"])}, nil
	},
}

var checkSecret = tn.Tool{
	Name:        "check_secret",
	Description: "needs human approval",
	InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
	Source:      tn.SourceCustom,
	Execute: func(_ map[string]any, tctx *tn.ToolContext) (tn.ToolResult, error) {
		if tctx != nil && tctx.Answer != nil && tctx.Answer.Ok {
			return tn.ToolResult{Output: "secret-token"}, nil
		}
		return tn.Pending(tn.Request{Kind: "approval", Prompt: "approve secret access?"}), nil
	},
}

// ---------- registry ----------

func registry() map[string]spike.AgentDef {
	return map[string]spike.AgentDef{
		"coordinator":    {Name: "coordinator", Description: "splits and delegates", SystemPrompt: "coordinate", Model: "m-coordinator"},
		"explore":        {Name: "explore", Description: "read-only research", SystemPrompt: "explore", Model: "m-explore", Tools: []tn.Tool{lookup}},
		"approverParent": {Name: "approverParent", Description: "delegates, holds approval authority", Model: "m-approver-parent"},
		"asker":          {Name: "asker", Description: "needs approvals", Model: "m-asker", Tools: []tn.Tool{checkSecret}},
		"peer":           {Name: "peer", Description: "team member", Model: "m-peer"},
		"looper":         {Name: "looper", Description: "never finishes", Model: "m-loop", Tools: []tn.Tool{lookup}},
		"slow":           {Name: "slow", Description: "slow worker", Model: "m-slow"},
	}
}

// ---------- harness ----------

var (
	passed int
	failed int
)

func check(name string, cond bool, detail string) {
	if cond {
		passed++
		fmt.Printf("  ✅ %s\n", name)
	} else {
		failed++
		fmt.Printf("  ❌ %s %s\n", name, detail)
	}
}

func section(s string) {
	fmt.Printf("\n━━ %s\n", s)
}

func traceHas(rt *spike.AgentRuntime, sub string) bool {
	for _, l := range rt.Trace() {
		if strings.Contains(l, sub) {
			return true
		}
	}
	return false
}

func main() {
	mock := mockRuntime{}

	// S1+S2 — fan-out, isolation, parallelism, usage roll-up ------------------
	section("S1/S2 task fan-out · context isolation · parallel · usage roll-up")
	{
		rt := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: registry()})
		coord, _ := rt.Spawn(rt.Root, "coordinator", nil)
		rt.Wake(coord, "answer using two lookups")
		r := rt.Wait(coord, 0)
		check("parent reaches done", r.Status == "done", r.Text)
		check("parent got BOTH child answers", strings.Contains(r.Text, "found:data(x)") && strings.Count(r.Text, "found:") == 2, r.Text)
		check("parent ran 2 turns only (children's turns not in parent)", r.Turns == 2, fmt.Sprintf("turns=%d", r.Turns))
		check("two children spawned with deterministic ids", traceHas(rt, "/coordinator.1/explore.1:") && traceHas(rt, "/explore.2:"), "")
		check("children ran CONCURRENTLY (observed ≥2 turns in flight)", rt.MaxObservedConcurrentTurns() >= 2, fmt.Sprintf("max=%d", rt.MaxObservedConcurrentTurns()))
		// roll-up: coord usage = own 2 turns×40 + children 2×(2 turns×40) = 240
		check("usage rolls up to parent (G3 ledger)", rt.Usage(coord) == 240, fmt.Sprintf("usage=%d", rt.Usage(coord)))
		allClosed := true
		for _, h := range rt.List() {
			if h.ID != coord.ID && h.State != spike.StateClosed {
				allClosed = false
			}
		}
		check("children auto-closed after task", allClosed, "")
	}

	// S3 — suspension escalated INLINE: nearest interpreter wins --------------
	section("S3 child suspends → parent's waitFor answers (nearest interpreter)")
	{
		reg := registry()
		ap := reg["approverParent"]
		ap.WaitFor = func(req tn.Request) (tn.Answer, error) { return tn.Answer{ID: req.ID, Ok: true}, nil }
		reg["approverParent"] = ap
		rt := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: reg})
		p, _ := rt.Spawn(rt.Root, "approverParent", nil)
		rt.Wake(p, "do the secret thing")
		r := rt.Wait(p, 0)
		check("run completed (no durable pending)", r.Status == "done", r.Status)
		check("child's approval flowed through parent authority", strings.Contains(r.Text, "asker-done: secret-token"), r.Text)
		check("trace shows suspended→running round-trip", traceHas(rt, "running→suspended") && traceHas(rt, "suspended→running"), "")
		check("escalation chose an ANCESTOR (not self)", traceHas(rt, "escalate → root/approverParent.1 answers"), "")
	}

	// S4 — durable pending at root + resume cascade from checkpoint -----------
	section("S4 no interpreter anywhere → durable pending; resume() cascades from checkpoint")
	{
		rt := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: registry()})
		p, _ := rt.Spawn(rt.Root, "approverParent", nil)
		rt.Wake(p, "do the secret thing")
		r1 := rt.Wait(p, 0)
		check("root run returned status=pending", r1.Status == "pending", r1.Status)
		path := ""
		if r1.Pending != nil {
			path = strings.Join(r1.Pending.Path, "/")
		}
		check("Request carries the handle PATH to the leaf", strings.Contains(path, "approverParent.1"), path)
		allSuspended := true
		before := 0
		for _, h := range rt.List() {
			if h.State != spike.StateSuspended {
				allSuspended = false
			}
			if strings.Contains(h.ID, "asker") {
				before = h.Tokens
			}
		}
		check("both levels parked (suspended), zero tokens burning", allSuspended, "")
		_ = rt.Resume(tn.Answer{ID: r1.Pending.ID, Ok: true})
		time.Sleep(50 * time.Millisecond)
		tr := strings.Join(rt.Trace(), "\n")
		check("leaf resumed AT checkpoint (prior turns preserved)", strings.Contains(tr, "resume with Answer(ok=true) at checkpoint"), "")
		check("parent cascade REATTACHED to the finished child (no re-execution)", strings.Contains(tr, "task replay → REATTACH"), "")
		check("parent reached done after cascade", rt.StateOf(p) == spike.StateIdle, string(rt.StateOf(p)))
		after := 0
		for _, h := range rt.List() {
			if strings.Contains(h.ID, "asker") {
				after = h.Tokens
			}
		}
		check("child did not restart from scratch (usage grew, not reset)", after > before, fmt.Sprintf("before=%d after=%d", before, after))
	}

	// S5 — team peer: coalesced drain + provenance + timer dedupe -------------
	section("S5 unsolicited rail: posts coalesce into ONE turn, provenance marked, ticks dedupe")
	{
		rt := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: registry()})
		peer, _ := rt.Spawn(rt.Root, "peer", nil)
		rt.Post(peer, spike.InboxItem{From: "root/coordinator.1", Channel: "peer", Text: "update 1"})
		rt.Post(peer, spike.InboxItem{From: "external", Channel: "external", Text: "webhook payload"})
		rt.Post(peer, spike.InboxItem{From: "clock", Channel: "timer", Text: "tick"})
		rt.Post(peer, spike.InboxItem{From: "clock", Channel: "timer", Text: "tick"})
		rt.Post(peer, spike.InboxItem{From: "clock", Channel: "timer", Text: "tick"})
		rt.Wake(peer, "")
		r := rt.Wait(peer, 0)
		check("one wake = ONE turn for all items", r.Turns == 1, fmt.Sprintf("turns=%d", r.Turns))
		check("2 messages + 3 ticks coalesced to 3 items", r.Text == "processed 3 items", r.Text)
	}

	// S6 — backpressure: inbox gate, concurrency gate, global turn gate -------
	section("S6 backpressure: loud inbox reject · per-parent concurrency queue · global turn gate")
	{
		rt := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: registry(), InboxCap: 2})
		peer, _ := rt.Spawn(rt.Root, "peer", nil)
		rt.Post(peer, spike.InboxItem{From: "a", Channel: "peer", Text: "1"})
		rt.Post(peer, spike.InboxItem{From: "a", Channel: "peer", Text: "2"})
		third := rt.Post(peer, spike.InboxItem{From: "a", Channel: "peer", Text: "3"})
		check("inbox gate: third post REJECTED loudly to sender", !third.Ok && strings.Contains(third.Err, "inbox full"), third.Err)

		reg2 := registry()
		c2def := reg2["coordinator"]
		c2def.Budget = &spike.Budget{MaxConcurrent: 1}
		reg2["coordinator"] = c2def
		rt2 := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: reg2})
		c2, _ := rt2.Spawn(rt2.Root, "coordinator", nil)
		rt2.Wake(c2, "go")
		r2 := rt2.Wait(c2, 0)
		check("concurrency gate: 2nd child QUEUED then DEQUEUED, still completes", r2.Status == "done" && traceHas(rt2, "wake QUEUED") && traceHas(rt2, "DEQUEUED wake"), r2.Text)
		check("concurrency gate: never >1 child turn in flight… but parent turn allowed", rt2.MaxObservedConcurrentTurns() <= 2, fmt.Sprintf("max=%d", rt2.MaxObservedConcurrentTurns()))

		rt3 := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: registry(), MaxConcurrentTurns: 1})
		c3, _ := rt3.Spawn(rt3.Root, "coordinator", nil)
		rt3.Wake(c3, "go")
		rt3.Wait(c3, 0)
		check("global turn gate: with cap 1, max observed concurrent turns = 1", rt3.MaxObservedConcurrentTurns() == 1, fmt.Sprintf("max=%d", rt3.MaxObservedConcurrentTurns()))
	}

	// S7 — budgets: carve, exhaustion=incomplete, maxTurns visible ------------
	section("S7 budgets: hierarchical carve · exhaustion = incomplete (never crash) · maxTurns loud")
	{
		rt := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: registry()})
		c, _ := rt.Spawn(rt.Root, "coordinator", &spike.Budget{MaxTokens: 100, MaxChildren: 2})
		kid, _ := rt.Spawn(c, "explore", &spike.Budget{MaxTokens: 500})
		check("carve: child asked 500, parent pool 100 → effective 100", rt.PoolTokens(kid) == 100, fmt.Sprintf("%d", rt.PoolTokens(kid)))
		kid2, _ := rt.Spawn(c, "explore", nil)
		_, err3 := rt.Spawn(c, "explore", nil)
		check("maxChildren enforced at spawn", err3 != nil && strings.Contains(err3.Error(), "maxChildren"), fmt.Sprintf("%v", err3))
		rt.Wake(kid, "go")
		rt.Wait(kid, 0) // burns 80 tokens (2 turns×40) from both kid and parent pools
		check("roll-up drains the PARENT pool too", rt.PoolTokens(c) == 20, fmt.Sprintf("%d", rt.PoolTokens(c)))
		rt.Wake(kid2, "go")
		r2 := rt.Wait(kid2, 0)
		check("pool nearly gone: sibling still ran (20 left > 0)", r2.Status == "done", r2.Status)
		r3 := rt.RunTurn(kid2, "again")
		check("pool exhausted → status=incomplete, partial work preserved, NO crash", r3.Status == "incomplete" && strings.Contains(r3.Text, "budget exhausted"), r3.Status)

		reg4 := registry()
		lp4 := reg4["looper"]
		lp4.Budget = &spike.Budget{MaxTurns: 3}
		reg4["looper"] = lp4
		rt4 := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: reg4})
		lp, _ := rt4.Spawn(rt4.Root, "looper", nil)
		rt4.Wake(lp, "loop forever")
		rl := rt4.Wait(lp, 0)
		check("maxTurns cap is LOUD: status=incomplete (not silent done)", rl.Status == "incomplete", rl.Status)
	}

	// S8 — interrupt: aborts the TURN, not the agent --------------------------
	section("S8 interrupt = turn-abort → idle (inbox intact); NOT a kill")
	{
		slowGate = make(chan struct{})
		rt := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: registry()})
		s, _ := rt.Spawn(rt.Root, "slow", nil)
		rt.Post(s, spike.InboxItem{From: "root", Channel: "peer", Text: "for later"})
		rt.Wake(s, "work slowly")
		time.Sleep(30 * time.Millisecond)
		check("agent is mid-turn (running)", rt.StateOf(s) == spike.StateRunning, string(rt.StateOf(s)))
		done := make(chan spike.TaskResult, 1)
		go func() { done <- rt.Wait(s, 0) }()
		rt.Interrupt(s)
		r := <-done
		check("turn aborted → isError result, status=interrupted", r.IsError && r.Status == "interrupted", r.Status)
		check("agent is IDLE (alive), not closed", rt.StateOf(s) == spike.StateIdle, string(rt.StateOf(s)))
		check("inbox survived the interrupt", rt.InboxLen(s) == 1, fmt.Sprintf("inbox=%d", rt.InboxLen(s)))
		close(slowGate)
	}

	// S9 — close cascade: leaf-first; post-after-close rejected ---------------
	section("S9 stop-all: close(root) cascades leaf-first; closed handles reject posts")
	{
		rt := spike.NewAgentRuntime(spike.RuntimeOptions{Transport: mock, Registry: registry()})
		c, _ := rt.Spawn(rt.Root, "coordinator", nil)
		k1, _ := rt.Spawn(c, "peer", nil)
		k2, _ := rt.Spawn(k1, "peer", nil) // grandchild
		var closeOrder []string
		for _, h := range []*spike.Handle{c, k1, k2} {
			h.Def.OnClose = func(hh *spike.Handle, _ string) { closeOrder = append(closeOrder, hh.ID) }
		}
		rt.Close(rt.Root, nil)
		ok := len(closeOrder) == 3 && closeOrder[0] == k2.ID && closeOrder[1] == k1.ID && closeOrder[2] == c.ID
		check("leaf-first order (grandchild → child → parent)", ok, strings.Join(closeOrder, " → "))
		p := rt.Post(k1, spike.InboxItem{From: "x", Channel: "peer", Text: "late"})
		check("post after close = loud isError", !p.Ok && strings.Contains(p.Err, "closed"), p.Err)
	}

	// ---------- the UX layer (S10–S13, demo-ux.ts) ----------
	ux := mockUX{}

	// S10 — Level 1: the 12-line coding agent -------------------------------
	section("S10 Level-1 UX: agent() + team wiring + soul file")
	{
		soulPath := filepath.Join(os.TempDir(), "AGENTS-spike.md")
		_ = os.WriteFile(soulPath, []byte("You are the CODER. Fix things surgically."), 0o644)
		explore := spike.NewAgent("explore", spike.AgentSpec{Does: "read-only research", Tools: []tn.Tool{lookup}, Model: "m-explore"})
		coder := spike.NewAgent("coder", spike.AgentSpec{Does: "implements changes", SoulFile: soulPath, Team: []*spike.Agent{explore}, Model: "m-coder", Budget: &spike.Budget{MaxTokens: 10_000}})
		r, _ := coder.Run(spike.RuntimeOptions{Transport: ux}, "fix the failing test")
		check("coding agent completes via its team", r.Status == "done" && strings.Contains(r.Text, "bug at line 42"), r.Text)
		check("soul FILE reached the system prompt", strings.Contains(r.Text, "[soul:loaded]"), r.Text)
	}

	// S11 — team scoping: task cannot reach outside the declared team ---------
	section("S11 team scoping: task targets = the team, nothing else")
	{
		_ = spike.NewAgent("stranger", spike.AgentSpec{Does: "should be unreachable", Model: "m-explore", Tools: []tn.Tool{lookup}})
		explore := spike.NewAgent("explore", spike.AgentSpec{Does: "read-only research", Tools: []tn.Tool{lookup}, Model: "m-explore"})
		coder := spike.NewAgent("coder", spike.AgentSpec{Does: "implements", Team: []*spike.Agent{explore}, Model: "m-coder"})
		reg := coder.Registry()
		keys := make([]string, 0, len(reg))
		for k := range reg {
			keys = append(keys, k)
		}
		sortJoin := func(s []string) string {
			out := append([]string(nil), s...)
			for i := 1; i < len(out); i++ {
				for j := i; j > 0 && out[j] < out[j-1]; j-- {
					out[j], out[j-1] = out[j-1], out[j]
				}
			}
			return strings.Join(out, ",")
		}
		check("registry contains only the reachable graph", sortJoin(keys) == "coder,explore", sortJoin(keys))
		tj, _ := json.Marshal(reg["coder"].TaskTargets)
		check("coder's task targets are exactly its team", string(tj) == `["explore"]`, string(tj))
	}

	// S12 — Level 2: agentFromDir + heartbeat + HEARTBEAT_OK silence ----------
	section("S12 Level-2 UX: the directory IS the agent · heartbeat · silent OK")
	{
		dir, _ := os.MkdirTemp("", "mia-")
		_ = os.WriteFile(filepath.Join(dir, "SOUL.md"), []byte("You are Mia. Warm, brief."), 0o644)
		_ = os.WriteFile(filepath.Join(dir, "USER.md"), []byte("The user is Muthu."), 0o644)
		_ = os.WriteFile(filepath.Join(dir, "MEMORY.md"), []byte("- Likes green tea."), 0o644)
		_ = os.WriteFile(filepath.Join(dir, "HEARTBEAT.md"), []byte("On heartbeat: if it is watering day, remind to water the plants."), 0o644)
		mia := spike.AgentFromDir(dir, &spike.AgentSpec{Model: "m-mia"}, "")
		direct, _ := mia.Run(spike.RuntimeOptions{Transport: ux}, "hello")
		check("bootstrap files discovered + injected as ## sections (openclaw order)", direct.Text == "soul-sections:[SOUL.md,USER.md,MEMORY.md]", direct.Text)

		var mu sync.Mutex
		var reports []string
		started, err := spike.StartAgent(mia, spike.RuntimeOptions{Transport: ux}, 25, func(t string) {
			mu.Lock()
			reports = append(reports, t)
			mu.Unlock()
		})
		if err != nil {
			check("heartbeat woke the agent repeatedly", false, err.Error())
		} else {
			time.Sleep(140 * time.Millisecond)
			started.Stop()
			hbTurns := 0
			for _, l := range started.RT.Trace() {
				if strings.Contains(l, "idle→running") {
					hbTurns++
				}
			}
			check("heartbeat woke the agent repeatedly", hbTurns >= 2, fmt.Sprintf("turns=%d", hbTurns))
			mu.Lock()
			silent := true
			for _, t := range reports {
				if !strings.Contains(t, "water") {
					silent = false
				}
			}
			rj, _ := json.Marshal(reports)
			mu.Unlock()
			check("HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats)", silent, string(rj))
			check("agent closed cleanly on stop()", started.RT.StateOf(started.Handle) == spike.StateClosed, string(started.RT.StateOf(started.Handle)))
		}
	}

	// S13 — the bridge: agent.asTool() inside the OLD API ---------------------
	section("S13 bridge: an Agent IS a Tool in the classic createToolkit/createClient API")
	{
		explore := spike.NewAgent("explore", spike.AgentSpec{Does: "read-only research", Tools: []tn.Tool{lookup}, Model: "m-explore"})
		toolkit, err := tn.CreateToolkit(nil, tn.Options{Builtins: false, ExtraTools: []tn.Tool{explore.AsTool(spike.RuntimeOptions{Transport: ux})}})
		if err != nil {
			check("old API called the agent like any tool", false, err.Error())
		} else {
			client := tn.CreateClient(tn.ClientOptions{
				BaseURL:    "http://mock.local",
				Style:      tn.StyleOpenAI,
				Model:      "m-old-api",
				APIKey:     "spike",
				HTTPClient: &http.Client{Transport: ux},
			})
			r, rerr := client.Run(nil, "summarize", toolkit)
			ok := rerr == nil && strings.Contains(r.Text, "old-api summary:") && strings.Contains(r.Text, "bug at line 42")
			check("old API called the agent like any tool", ok, r.Text)
			meta := ""
			if rerr == nil && len(r.ToolCalls) > 0 && r.ToolCalls[0].Metadata != nil {
				meta, _ = r.ToolCalls[0].Metadata["agent"].(string)
			}
			check("agent metadata surfaced through the tool result", meta == "explore", meta)
		}
	}

	// ---------- summary ----------
	fmt.Printf("\n%s\n", strings.Repeat("═", 60))
	verdict := "❌"
	if failed == 0 {
		verdict = "— ALL SCENARIOS PASS ✅"
	}
	fmt.Printf("  %d passed · %d failed  %s\n", passed, failed, verdict)
	fmt.Println(strings.Repeat("═", 60))
	if failed > 0 {
		os.Exit(1)
	}
}
