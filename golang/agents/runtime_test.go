// Runtime substrate tests — the §7D acceptance scenarios (S1–S9) against the
// shared examples/subagent-*/fixture.json expectations, plus the shipped-only
// obligations (runtime-wide store, gate release on acquirer death, wait
// timeout, wall/tool-call budgets, virtual clock). Hermetic: scripted
// RoundTripper, zero network.
package agents

import (
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// S1/S2 — examples/subagent-fanout: parallel task fan-out, context isolation,
// deterministic ids, concurrency, usage roll-up, auto-close.
func TestTaskFanoutIsolationAndRollup(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	coord, err := rt.Spawn(rt.Root, "coordinator", nil)
	if err != nil {
		t.Fatal(err)
	}
	rt.Wake(coord, "answer using two lookups")
	r := rt.Wait(coord, 0)

	if r.Status != "done" {
		t.Fatalf("parent status = %q (%s), want done", r.Status, r.Text)
	}
	if !strings.Contains(r.Text, "found:data(x)") || strings.Count(r.Text, "found:") != 2 {
		t.Errorf("parent should carry BOTH child answers, got %q", r.Text)
	}
	if r.Turns != 2 {
		t.Errorf("parent turns = %d, want 2 (children's turns stay out of the parent)", r.Turns)
	}
	if !traceHas(rt, "/coordinator.1/explore.1:") || !traceHas(rt, "/explore.2:") {
		t.Error("children should spawn with deterministic parent-scoped ids")
	}
	if got := rt.MaxObservedConcurrentTurns(); got < 2 {
		t.Errorf("children should run concurrently (observed %d turns in flight, want >=2)", got)
	}
	// Roll-up ledger: coord 2 turns×40 + 2 children × 2 turns×40 = 240.
	if got := rt.UsageTokens(coord); got != 240 {
		t.Errorf("parent rolled-up usage = %d, want 240", got)
	}
	for _, h := range rt.List() {
		if h.ID != coord.ID && h.State != StateClosed {
			t.Errorf("child %s should be auto-closed after task, is %s", h.ID, h.State)
		}
	}
}

// S3 — examples/subagent-escalation: the child's suspension is answered INLINE
// by the nearest ancestor interpreter (§10 unchanged).
func TestEscalationNearestInterpreter(t *testing.T) {
	reg := testRegistry()
	ap := reg["approverParent"]
	ap.WaitFor = func(req tn.Request) (tn.Answer, error) { return tn.Answer{ID: req.ID, Ok: true}, nil }
	reg["approverParent"] = ap
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: reg})
	p, _ := rt.Spawn(rt.Root, "approverParent", nil)
	rt.Wake(p, "do the secret thing")
	r := rt.Wait(p, 0)

	if r.Status != "done" {
		t.Fatalf("status = %q, want done (no durable pending)", r.Status)
	}
	if !strings.Contains(r.Text, "asker-done: secret-token") {
		t.Errorf("child approval should flow through parent authority, got %q", r.Text)
	}
	if !traceHas(rt, "running→suspended") || !traceHas(rt, "suspended→running") {
		t.Error("trace should show the suspended→running round-trip")
	}
	if !traceHas(rt, "escalate → root/approverParent.1 answers") {
		t.Error("escalation should choose the ANCESTOR interpreter, not self")
	}
}

// S4 — examples/subagent-durable-resume: no interpreter anywhere → durable
// pending at the root carrying data.path; Resume routes to the deepest
// suspended handle, resumes at its checkpoint, and the parent cascade
// REATTACHES by task key — no duplicate child, usage grows.
func TestDurableResumeCascadeReattaches(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	p, _ := rt.Spawn(rt.Root, "approverParent", nil)
	rt.Wake(p, "do the secret thing")
	r1 := rt.Wait(p, 0)

	if r1.Status != "pending" {
		t.Fatalf("root run status = %q, want pending", r1.Status)
	}
	if r1.Pending == nil || r1.Pending.Kind != "approval" {
		t.Fatalf("pending request missing or wrong kind: %+v", r1.Pending)
	}
	segs, _ := r1.Pending.Data["path"].([]string)
	path := strings.Join(segs, "/")
	if !strings.Contains(path, "approverParent.1") {
		t.Errorf("Request.data.path should identify the suspended subtree, got %q", path)
	}
	before := 0
	for _, h := range rt.List() {
		if h.State != StateSuspended {
			t.Errorf("%s should be parked suspended, is %s", h.ID, h.State)
		}
		if strings.Contains(h.ID, "asker") {
			before = h.Tokens
		}
	}

	if err := rt.Resume(tn.Answer{ID: r1.Pending.ID, Ok: true}); err != nil {
		t.Fatal(err)
	}
	tr := strings.Join(rt.Trace(), "\n")
	if !strings.Contains(tr, "resume with Answer(ok=true) at checkpoint") {
		t.Error("leaf should resume AT its checkpoint")
	}
	if !strings.Contains(tr, "task replay → REATTACH") {
		t.Error("the parent cascade should REATTACH to the finished child, not re-execute")
	}
	if got := rt.StateOf(p); got != StateIdle {
		t.Errorf("parent state after cascade = %s, want idle", got)
	}
	after, askers := 0, 0
	for _, h := range rt.List() {
		if strings.Contains(h.ID, "asker") {
			after = h.Tokens
			askers++
		}
	}
	if askers != 1 {
		t.Errorf("resume must not spawn a duplicate child (found %d asker handles)", askers)
	}
	if after <= before {
		t.Errorf("child usage should GROW across resume (before=%d after=%d), never reset", before, after)
	}
	if last := rt.Wait(p, 0); !strings.Contains(last.Text, "asker-done: secret-token") {
		t.Errorf("parent final should carry the child's real result, got %q", last.Text)
	}
}

// S5 — examples/subagent-lifecycle coalescedDrain: one wake drains the WHOLE
// inbox as one turn; timer ticks dedupe; provenance rendered.
func TestUnsolicitedRailCoalescedDrain(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	peer, _ := rt.Spawn(rt.Root, "peer", nil)
	rt.Post(peer, InboxItem{From: "root/coordinator.1", Channel: "peer", Text: "update 1"})
	rt.Post(peer, InboxItem{From: "external", Channel: "external", Text: "webhook payload"})
	for i := 0; i < 3; i++ {
		rt.Post(peer, InboxItem{From: "clock", Channel: "timer", Text: "tick"})
	}
	rt.Wake(peer, "")
	r := rt.Wait(peer, 0)

	if r.Turns != 1 {
		t.Errorf("one wake should be ONE turn for all items, got %d turns", r.Turns)
	}
	if r.Text != "processed 3 items" {
		t.Errorf("2 messages + 3 coalesced ticks should render as 3 items, got %q", r.Text)
	}
}

// S6 — examples/subagent-lifecycle gates: loud inbox reject, per-parent
// concurrency queue with slot-transfer dequeue, global turn gate.
func TestBackpressureGates(t *testing.T) {
	t.Run("inboxGate", func(t *testing.T) {
		rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry(), InboxCap: 2})
		peer, _ := rt.Spawn(rt.Root, "peer", nil)
		rt.Post(peer, InboxItem{From: "a", Channel: "peer", Text: "1"})
		rt.Post(peer, InboxItem{From: "a", Channel: "peer", Text: "2"})
		third := rt.Post(peer, InboxItem{From: "a", Channel: "peer", Text: "3"})
		if third.Ok || !strings.Contains(third.Err, "inbox full") {
			t.Errorf("third post should reject LOUDLY to the sender, got %+v", third)
		}
		if got := rt.InboxLen(peer); got != 2 {
			t.Errorf("inbox should be unchanged by the rejected post, len=%d", got)
		}
	})

	t.Run("concurrencyGate", func(t *testing.T) {
		reg := testRegistry()
		def := reg["coordinator"]
		def.Budget = &Budget{MaxConcurrent: 1}
		reg["coordinator"] = def
		rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: reg})
		c, _ := rt.Spawn(rt.Root, "coordinator", nil)
		rt.Wake(c, "go")
		r := rt.Wait(c, 0)
		if r.Status != "done" {
			t.Fatalf("run should still complete via slot transfer, got %q (%s)", r.Status, r.Text)
		}
		if !traceHas(rt, "wake QUEUED") || !traceHas(rt, "DEQUEUED wake") {
			t.Error("second child should QUEUE then DEQUEUE via slot transfer")
		}
		if got := rt.MaxObservedConcurrentTurns(); got > 2 {
			t.Errorf("never more than parent turn + 1 child turn in flight, observed %d", got)
		}
	})

	t.Run("turnGate", func(t *testing.T) {
		rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry(), MaxConcurrentTurns: 1})
		c, _ := rt.Spawn(rt.Root, "coordinator", nil)
		rt.Wake(c, "go")
		r := rt.Wait(c, 0)
		if r.Status != "done" {
			t.Fatalf("gate wraps ONLY the LLM HTTP call — a delegating parent must not deadlock, got %q", r.Status)
		}
		if got := rt.MaxObservedConcurrentTurns(); got != 1 {
			t.Errorf("with cap 1, max observed concurrent turns = %d, want 1", got)
		}
	})
}

// S7 — examples/subagent-budgets: hierarchical carve, live ancestor
// enforcement, maxChildren at spawn, loud incomplete on exhaustion + maxTurns.
func TestHierarchicalBudgets(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	c, _ := rt.Spawn(rt.Root, "coordinator", &Budget{MaxTokens: 100, MaxChildren: 2})
	kid, _ := rt.Spawn(c, "explore", &Budget{MaxTokens: 500})
	if got := rt.PoolTokens(kid); got != 100 {
		t.Errorf("carve: child asked 500, parent pool 100 → effective %d, want 100", got)
	}
	kid2, _ := rt.Spawn(c, "explore", nil)
	if _, err := rt.Spawn(c, "explore", nil); err == nil || !strings.Contains(err.Error(), "maxChildren") {
		t.Errorf("third spawn should refuse naming maxChildren, got %v", err)
	}
	rt.Wake(kid, "go")
	rt.Wait(kid, 0) // 2 turns × 40 tokens, rolled up
	if got := rt.PoolTokens(c); got != 20 {
		t.Errorf("roll-up should drain the PARENT pool too, got %d, want 20", got)
	}
	rt.Wake(kid2, "go")
	if r := rt.Wait(kid2, 0); r.Status != "done" {
		t.Errorf("pool 20 > 0 at admission — the live walk should still allow, got %q", r.Status)
	}
	r3 := rt.RunTurn(kid2, "again")
	if r3.Status != "incomplete" || !strings.Contains(r3.Text, "budget exhausted") {
		t.Errorf("exhausted ancestor pool → LOUD incomplete, got %q (%s)", r3.Status, r3.Text)
	}

	// maxTurns without a final answer is equally loud.
	reg := testRegistry()
	lp := reg["looper"]
	lp.Budget = &Budget{MaxTurns: 3}
	reg["looper"] = lp
	rt4 := NewRuntime(Options{Transport: &mockLLM{}, Registry: reg})
	h, _ := rt4.Spawn(rt4.Root, "looper", nil)
	rt4.Wake(h, "loop forever")
	if r := rt4.Wait(h, 0); r.Status != "incomplete" {
		t.Errorf("maxTurns cap must be LOUD incomplete, not a silent done, got %q", r.Status)
	}
}

// S8 — interrupt aborts the TURN, not the agent: idle afterwards, inbox intact
// including the transactionally restored drained item, waiters get a uniform
// interrupted result, and a later wake runs normally.
func TestInterruptIsTurnAbortWithDrainRestore(t *testing.T) {
	mock := &mockLLM{slowGate: make(chan struct{})}
	rt := NewRuntime(Options{Transport: mock, Registry: testRegistry()})
	s, _ := rt.Spawn(rt.Root, "slow", nil)
	rt.Post(s, InboxItem{From: "root", Channel: "peer", Text: "for later"})
	rt.Wake(s, "work slowly")
	if !awaitState(rt, s, StateRunning) {
		t.Fatal("agent never reached running")
	}
	done := make(chan TaskResult, 1)
	go func() { done <- rt.Wait(s, 0) }()
	time.Sleep(10 * time.Millisecond) // let the waiter register
	rt.Interrupt(s)
	r := <-done
	if !r.IsError || r.Status != "interrupted" {
		t.Errorf("waiter should get a uniform interrupted error result, got %+v", r)
	}
	if got := rt.StateOf(s); got != StateIdle {
		t.Errorf("agent should be IDLE (alive) after interrupt, is %s", got)
	}
	if got := rt.InboxLen(s); got != 1 {
		t.Errorf("transactional drain should restore the item, inbox=%d want 1", got)
	}
	// The agent survives: release the gate and wake again.
	close(mock.slowGate)
	rt.Wake(s, "work again")
	if r := rt.Wait(s, 0); r.Status != "done" {
		t.Errorf("a subsequent wake should run normally, got %q", r.Status)
	}
}

// S9 — close(root) cascades LEAF-FIRST; closed handles reject posts loudly.
func TestCloseCascadeLeafFirst(t *testing.T) {
	var mu sync.Mutex
	var order []string
	reg := testRegistry()
	onClose := func(h *Handle, _ string) {
		mu.Lock()
		order = append(order, h.ID)
		mu.Unlock()
	}
	for _, name := range []string{"coordinator", "peer"} {
		def := reg[name]
		def.OnClose = onClose
		reg[name] = def
	}
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: reg})
	c, _ := rt.Spawn(rt.Root, "coordinator", nil)
	k1, _ := rt.Spawn(c, "peer", nil)
	k2, _ := rt.Spawn(k1, "peer", nil)

	rt.Close(rt.Root, nil)
	mu.Lock()
	got := strings.Join(order, " → ")
	mu.Unlock()
	want := k2.ID + " → " + k1.ID + " → " + c.ID
	if got != want {
		t.Errorf("onClose order = %s, want leaf-first %s", got, want)
	}
	for _, h := range rt.List() {
		if h.State != StateClosed {
			t.Errorf("%s should be closed, is %s", h.ID, h.State)
		}
	}
	if p := rt.Post(k1, InboxItem{From: "x", Channel: "peer", Text: "late"}); p.Ok || !strings.Contains(p.Err, "closed") {
		t.Errorf("post after close should reject loudly, got %+v", p)
	}
}

// Runtime-wide ConversationStore: the handle's transcript genuinely survives
// turns (conversation id = handle id) — the spike's fresh-client-per-turn
// honesty item, fixed and proven.
func TestTranscriptSurvivesTurns(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h, _ := rt.Spawn(rt.Root, "counter", nil)
	r1 := rt.RunTurn(h, "one")
	if r1.Text != "msgs:2" { // system + user
		t.Fatalf("first turn transcript = %q, want msgs:2", r1.Text)
	}
	r2 := rt.RunTurn(h, "two")
	if r2.Text != "msgs:4" { // system + user + assistant + user — history GREW
		t.Errorf("second turn should continue the stored transcript, got %q, want msgs:4", r2.Text)
	}
}

// Transcript rewind on pending (cross-port pin, found by the Java port): the
// shipped client appends the halted tool's placeholder result to its transcript
// on a durable pending (§10). If that transcript were committed, the cascading
// parent's re-run would see the placeholder as a RESOLVED tool result and never
// re-invoke task — breaking reattachment. The runtime therefore commits ONLY
// completed turns: on pending, the stored transcript stays at the pre-turn
// checkpoint, and resume replays the whole turn (idempotent via the task key).
func TestTranscriptRewindsOnPending(t *testing.T) {
	store := tn.NewInMemoryConversationStore()
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry(), Store: store})
	p, _ := rt.Spawn(rt.Root, "approverParent", nil)
	rt.Wake(p, "do the secret thing")
	r := rt.Wait(p, 0)
	if r.Status != "pending" {
		t.Fatalf("setup: want pending, got %q", r.Status)
	}
	for _, row := range rt.List() {
		if msgs, _ := store.Get(row.ID); len(msgs) != 0 {
			t.Errorf("suspended %s must stay at its pre-turn checkpoint (no halted placeholder persisted), stored %d messages", row.ID, len(msgs))
		}
	}
	if err := rt.Resume(tn.Answer{ID: r.Pending.ID, Ok: true}); err != nil {
		t.Fatal(err)
	}
	if !traceHas(rt, "task replay → REATTACH") {
		t.Error("resume must reattach — the replayed parent turn re-invokes task")
	}
	if msgs, _ := store.Get(p.ID); len(msgs) == 0 {
		t.Error("the completed resume turn should commit the parent transcript")
	}
}

// Forced close is an abort: drained inbox items are restored (the spec delta
// says "interrupt or forced close"), and the final state stays queryable.
func TestForcedCloseRestoresDrainedItems(t *testing.T) {
	mock := &mockLLM{slowGate: make(chan struct{})}
	defer close(mock.slowGate)
	rt := NewRuntime(Options{Transport: mock, Registry: testRegistry()})
	s, _ := rt.Spawn(rt.Root, "slow", nil)
	rt.Post(s, InboxItem{From: "root", Channel: "peer", Text: "for later"})
	rt.Wake(s, "work slowly") // drains the item into the turn
	if !awaitState(rt, s, StateRunning) {
		t.Fatal("slow never reached running")
	}
	rt.Close(s, &CloseOptions{Force: true})
	if got := rt.StateOf(s); got != StateClosed {
		t.Errorf("force-closed handle should end closed, is %s", got)
	}
	if got := rt.InboxLen(s); got != 1 {
		t.Errorf("forced close must restore the drained item (transactional drain), inbox=%d want 1", got)
	}
}

// Graceful close ESCALATES: after the Shutdown grace a still-running turn is
// interrupted, so close always terminates — and the abort settles first
// (drained items restored, state never resurrected past closed).
func TestGracefulCloseEscalatesAfterShutdown(t *testing.T) {
	mock := &mockLLM{slowGate: make(chan struct{})}
	defer close(mock.slowGate)
	rt := NewRuntime(Options{Transport: mock, Registry: testRegistry(), Shutdown: 20 * time.Millisecond})
	s, _ := rt.Spawn(rt.Root, "slow", nil)
	rt.Post(s, InboxItem{From: "root", Channel: "peer", Text: "for later"})
	rt.Wake(s, "work slowly")
	if !awaitState(rt, s, StateRunning) {
		t.Fatal("slow never reached running")
	}
	done := make(chan struct{})
	go func() { rt.Close(s, nil); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("graceful close never escalated — still waiting on the stuck turn")
	}
	if got := rt.StateOf(s); got != StateClosed {
		t.Errorf("handle should end closed (never resurrected to idle), is %s", got)
	}
	if got := rt.InboxLen(s); got != 1 {
		t.Errorf("escalated close must restore the drained item, inbox=%d want 1", got)
	}
}

// A custom store passed via Options.Store is the one the runtime writes.
func TestRuntimeUsesInjectedStore(t *testing.T) {
	store := tn.NewInMemoryConversationStore()
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry(), Store: store})
	h, _ := rt.Spawn(rt.Root, "peer", nil)
	rt.Wake(h, "hello")
	rt.Wait(h, 0)
	msgs, _ := store.Get(h.ID)
	if len(msgs) == 0 {
		t.Errorf("the injected store should hold the transcript under the handle id %q", h.ID)
	}
}

// Turn gate releases on acquirer death: a hard-aborted Run holding the slot
// frees it for a queued acquirer (never only via the acquirer's cleanup path).
func TestTurnGateReleasesOnAcquirerDeath(t *testing.T) {
	mock := &mockLLM{slowGate: make(chan struct{})}
	defer close(mock.slowGate)
	rt := NewRuntime(Options{Transport: mock, Registry: testRegistry(), MaxConcurrentTurns: 1})
	s, _ := rt.Spawn(rt.Root, "slow", nil)
	p, _ := rt.Spawn(rt.Root, "peer", nil)
	rt.Wake(s, "hold the slot")
	if !awaitState(rt, s, StateRunning) {
		t.Fatal("slow never reached running")
	}
	time.Sleep(20 * time.Millisecond) // slot is now held inside the HTTP call
	rt.Wake(p, "queued behind the slot")
	time.Sleep(20 * time.Millisecond)
	rt.Interrupt(s) // kill the acquirer mid-flight
	if r := rt.Wait(p, 0); r.Status != "done" || r.Text != "processed 0 items" {
		t.Errorf("queued acquirer should proceed after the holder died, got %q (%s)", r.Status, r.Text)
	}
}

// Wait timeout is an explicit error; the child keeps running and can still be
// awaited to completion.
func TestWaitTimeoutChildKeepsRunning(t *testing.T) {
	mock := &mockLLM{slowGate: make(chan struct{})}
	rt := NewRuntime(Options{Transport: mock, Registry: testRegistry()})
	s, _ := rt.Spawn(rt.Root, "slow", nil)
	rt.Wake(s, "take your time")
	if !awaitState(rt, s, StateRunning) {
		t.Fatal("slow never reached running")
	}
	r := rt.Wait(s, 30*time.Millisecond)
	if !r.IsError || r.Status != "timeout" || !strings.Contains(r.Text, "wait timeout") {
		t.Errorf("expired wait should be an explicit timeout error, got %+v", r)
	}
	if got := rt.StateOf(s); got != StateRunning {
		t.Errorf("child should keep running through a wait timeout, is %s", got)
	}
	close(mock.slowGate)
	if r := rt.Wait(s, 0); r.Status != "done" || r.Text != "slow-done" {
		t.Errorf("child should still complete, got %q (%s)", r.Status, r.Text)
	}
}

// Wait resolves immediately with the LAST result on a settled handle.
func TestWaitSettledFastPath(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h, _ := rt.Spawn(rt.Root, "peer", nil)
	rt.Wake(h, "go")
	first := rt.Wait(h, 0)
	again := rt.Wait(h, 10*time.Millisecond) // settled: answers immediately
	if again != first {
		t.Errorf("wait on a settled handle should return the last result, got %+v vs %+v", again, first)
	}
}

// MaxWall (§7D maxWallMs) is enforced on the live ancestor walk, on the
// runtime Clock.
func TestWallClockBudget(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h, _ := rt.Spawn(rt.Root, "peer", &Budget{MaxWall: time.Nanosecond})
	time.Sleep(time.Millisecond)
	r := rt.RunTurn(h, "too late")
	if r.Status != "incomplete" || !strings.Contains(r.Text, "wallMs") {
		t.Errorf("wall-clock exhaustion should be a loud incomplete naming wallMs, got %q (%s)", r.Status, r.Text)
	}
}

// MaxToolCalls pools tool calls exactly like tokens.
func TestToolCallBudget(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h, _ := rt.Spawn(rt.Root, "explore", &Budget{MaxToolCalls: 1})
	if r := rt.RunTurn(h, "go"); r.Status != "done" {
		t.Fatalf("first run (one lookup) should pass, got %q", r.Status)
	}
	r := rt.RunTurn(h, "go again")
	if r.Status != "incomplete" || !strings.Contains(r.Text, "toolCalls") {
		t.Errorf("tool-call pool exhaustion should be a loud incomplete naming toolCalls, got %q (%s)", r.Status, r.Text)
	}
}

// MaxDepth refuses at spawn, naming the limit.
func TestMaxDepthAtSpawn(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h := rt.Root
	var err error
	for i := 0; i < 3; i++ {
		h, err = rt.Spawn(h, "peer", nil)
		if err != nil {
			t.Fatalf("spawn %d within depth should pass: %v", i, err)
		}
	}
	if _, err = rt.Spawn(h, "peer", nil); err == nil || !strings.Contains(err.Error(), "maxDepth") {
		t.Errorf("spawn beyond maxDepth should refuse naming the limit, got %v", err)
	}
}

// A task call outside the caller's team is refused with the team names listed.
func TestTaskOutsideTeamRefused(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h, _ := rt.Spawn(rt.Root, "rogue", nil)
	rt.Wake(h, "delegate to a stranger")
	r := rt.Wait(h, 0)
	if !strings.Contains(r.Text, "not in this agent's team") || !strings.Contains(r.Text, "explore") {
		t.Errorf("out-of-team task should error listing the team names, got %q", r.Text)
	}
}

// A def without a team gets NO task tool — recursion/delegation is opt-in.
func TestNoTeamMeansNoTaskTool(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h, _ := rt.Spawn(rt.Root, "peer", nil) // peer declares no team
	rt.Wake(h, "hello")
	rt.Wait(h, 0)
	if traceHas(rt, "task replay") || len(rt.List()) != 1 {
		t.Error("a teamless agent must not be able to delegate")
	}
}

// The virtual clock drives Wait timeouts deterministically.
func TestVirtualClockWaitTimeout(t *testing.T) {
	mock := &mockLLM{slowGate: make(chan struct{})}
	defer close(mock.slowGate)
	clk := newFakeClock()
	rt := NewRuntime(Options{Transport: mock, Registry: testRegistry(), Clock: clk})
	s, _ := rt.Spawn(rt.Root, "slow", nil)
	rt.Wake(s, "hold")
	if !awaitState(rt, s, StateRunning) {
		t.Fatal("slow never reached running")
	}
	done := make(chan TaskResult, 1)
	go func() { done <- rt.Wait(s, time.Hour) }()
	select {
	case r := <-done:
		t.Fatalf("wait resolved before the virtual clock fired: %+v", r)
	case <-time.After(30 * time.Millisecond):
	}
	clk.fire() // one virtual hour elapses
	r := <-done
	if r.Status != "timeout" {
		t.Errorf("virtual-clock timeout should yield the timeout error, got %+v", r)
	}
}

// fakeClock is a minimal virtual clock: After channels fire only on fire().
type fakeClock struct {
	mu  sync.Mutex
	now time.Time
	chs []chan time.Time
}

func newFakeClock() *fakeClock { return &fakeClock{now: time.Unix(0, 0)} }

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) After(d time.Duration) <-chan time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	ch := make(chan time.Time, 1)
	c.chs = append(c.chs, ch)
	return ch
}

func (c *fakeClock) fire() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(time.Hour)
	for _, ch := range c.chs {
		select {
		case ch <- c.now:
		default:
		}
	}
	c.chs = nil
}

// Interrupting a SUSPENDED handle cancels its pending Request → idle (the
// operator escape hatch).
func TestInterruptSuspendedCancelsPending(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	p, _ := rt.Spawn(rt.Root, "approverParent", nil)
	rt.Wake(p, "do the secret thing")
	r := rt.Wait(p, 0)
	if r.Status != "pending" {
		t.Fatalf("setup: want pending, got %q", r.Status)
	}
	rt.Interrupt(p)
	if got := rt.StateOf(p); got != StateIdle {
		t.Errorf("interrupted suspended handle should be idle, is %s", got)
	}
	if !traceHas(rt, "interrupt cancelled pending") {
		t.Error("trace should record the cancelled pending")
	}
	if v := rt.Inspect(p); v.PendingKind != "" {
		t.Errorf("pending should be cleared, still %q", v.PendingKind)
	}
}

func TestSpawnUnknownAgentListsRegistrySorted(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: map[string]Def{
		"zeta": {Name: "zeta", Does: "z", Model: "m"},
		"alpha": {Name: "alpha", Does: "a", Model: "m"},
	}})
	_, err := rt.Spawn(rt.Root, "nope", nil)
	if err == nil || !strings.Contains(err.Error(), "alpha, zeta") {
		t.Errorf("unknown-agent error should list the registry sorted by name, got %v", err)
	}
}

func TestPostWhileRunningEntersNextDrain(t *testing.T) {
	mock := &mockLLM{slowGate: make(chan struct{})}
	rt := NewRuntime(Options{Transport: mock, Registry: testRegistry()})
	s, _ := rt.Spawn(rt.Root, "slow", nil)
	rt.Wake(s, "busy")
	if !awaitState(rt, s, StateRunning) {
		t.Fatal("slow never reached running")
	}
	rt.Post(s, InboxItem{From: "x", Channel: "peer", Text: "mid-turn item"})
	close(mock.slowGate)
	rt.Wait(s, 0)
	if got := rt.InboxLen(s); got != 1 {
		t.Errorf("nothing unsolicited enters a LIVE turn — item waits for the next drain, inbox=%d", got)
	}
}

func TestListAndInspectViews(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	h, _ := rt.Spawn(rt.Root, "peer", nil)
	rt.Wake(h, "go")
	rt.Wait(h, 0)
	rows := rt.List()
	if len(rows) != 1 || rows[0].ID != h.ID || rows[0].State != StateIdle {
		t.Errorf("List should show the one idle handle, got %+v", rows)
	}
	v := rt.Inspect(h)
	if v.Turns != 1 || v.Tokens != 40 {
		t.Errorf("Inspect should expose turns/tokens, got %+v", v)
	}
	_ = fmt.Sprintf("%v", v)
}
