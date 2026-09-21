// Spike for issue #95 / ADR 0024: does ONE semantic `Generate` function serve
// both a top-level CreateInProcessClient AND a sub-agent runtime, with no
// copied round-tripper code, while the global turn gate still holds?
//
// This file only compiles against golang/inprocess.go as patched by
// ../inprocess-shape1.patch (adds the exported `toolnexus.InProcessTransport`
// function used below — see SPIKE.md). It is left in the repo unpatched, so
// this file will NOT build until the patch is (re)applied; that is documented
// in SPIKE.md along with the verbatim output captured while it was applied.
package inprocesssubagent

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/muthuishere/toolnexus/golang/agents"

	tn "github.com/muthuishere/toolnexus/golang"
)

// scriptedModel is the fake model used by BOTH the top-level client and the
// sub-agent runtime below. It never touches HTTP: it is handed the assembled
// request (messages/tools/model) and returns one assistant message — exactly
// the InProcessRequest/InProcessResponse shape `tn.CreateInProcessClient`
// already uses. It also instruments concurrency so the gate test can assert
// on real overlap, not just a trust-me counter.
type scriptedModel struct {
	inFlight int32
	maxSeen  int32
	overlaps int32 // count of calls that observed >1 in flight
	calls    int32
	hold     time.Duration
}

func (m *scriptedModel) generate(req tn.InProcessRequest) (tn.InProcessResponse, error) {
	n := atomic.AddInt32(&m.inFlight, 1)
	defer atomic.AddInt32(&m.inFlight, -1)
	atomic.AddInt32(&m.calls, 1)
	for {
		cur := atomic.LoadInt32(&m.maxSeen)
		if n <= cur || atomic.CompareAndSwapInt32(&m.maxSeen, cur, n) {
			break
		}
	}
	if n > 1 {
		atomic.AddInt32(&m.overlaps, 1)
	}
	if m.hold > 0 {
		time.Sleep(m.hold) // widen the window so a real bypass WOULD overlap
	}
	return tn.InProcessResponse{Content: fmt.Sprintf("ok model=%s turn-done", req.Model)}, nil
}

// TestSharedGenerate_TopLevelClient proves the top-level half of the reporter's
// case: tn.CreateInProcessClient driven by scriptedModel.generate, no HTTP.
func TestSharedGenerate_TopLevelClient(t *testing.T) {
	m := &scriptedModel{}
	client := tn.CreateInProcessClient(tn.InProcessOptions{
		Model:    "spike-model",
		Generate: m.generate,
	})
	toolkit, err := tn.CreateToolkit(context.Background(), tn.Options{})
	if err != nil {
		t.Fatal(err)
	}
	res, err := client.Ask(context.Background(), "hello", toolkit, "top-level-spike")
	if err != nil {
		t.Fatal(err)
	}
	if res.Status != "done" {
		t.Fatalf("status = %q, want done (%s)", res.Status, res.Text)
	}
	t.Logf("top-level client result: %+v", res)
}

// TestSharedGenerate_SubAgentRuntime proves the sub-agent half: the SAME
// scriptedModel.generate wired into agents.Options.Transport via
// toolnexus.InProcessTransport — the exported round tripper — not a
// re-implementation of it.
func TestSharedGenerate_SubAgentRuntime(t *testing.T) {
	m := &scriptedModel{}
	rt := agents.NewRuntime(agents.Options{
		Transport: tn.InProcessTransport(m.generate), // <- the shared seam
		Registry: map[string]agents.Def{
			"worker": {Name: "worker", Does: "a scripted worker"},
		},
	})
	h, err := rt.Spawn(rt.Root, "worker", nil)
	if err != nil {
		t.Fatal(err)
	}
	rt.Wake(h, "do the thing")
	r := rt.Wait(h, 0)
	if r.Status != "done" {
		t.Fatalf("status = %q, want done (%s)", r.Status, r.Text)
	}
	t.Logf("sub-agent result: %+v, calls=%d", r, m.calls)
}

// TestGateHolds_ConcurrencyOne is the falsifiable gate test (ADR 0024 gate
// item 2): MaxConcurrentTurns=1, five workers woken concurrently. If
// gatedTransport (runtime.go:872) stopped wrapping the Transport this spike
// wires in, scriptedModel would observe >1 in flight and this test fails.
func TestGateHolds_ConcurrencyOne(t *testing.T) {
	m := &scriptedModel{hold: 15 * time.Millisecond}
	rt := agents.NewRuntime(agents.Options{
		Transport:          tn.InProcessTransport(m.generate),
		MaxConcurrentTurns: 1,
		Registry: map[string]agents.Def{
			"worker": {Name: "worker", Does: "a scripted worker"},
		},
	})
	const n = 5
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			h, err := rt.Spawn(rt.Root, "worker", nil)
			if err != nil {
				t.Error(err)
				return
			}
			rt.Wake(h, fmt.Sprintf("job %d", i))
			r := rt.Wait(h, 0)
			if r.Status != "done" {
				t.Errorf("worker %d status = %q, want done", i, r.Status)
			}
		}(i)
	}
	wg.Wait()

	if got := atomic.LoadInt32(&m.overlaps); got != 0 {
		t.Fatalf("gate FALSIFIED: %d call(s) observed >1 in flight (maxSeen=%d) "+
			"with MaxConcurrentTurns=1 — the global turn gate did not wrap the "+
			"in-process Transport", got, m.maxSeen)
	}
	if got := rt.MaxObservedConcurrentTurns(); got != 1 {
		t.Fatalf("rt.MaxObservedConcurrentTurns() = %d, want 1", got)
	}
	t.Logf("gate holds: calls=%d maxSeen=%d overlaps=%d rt.MaxObservedConcurrentTurns=%d",
		m.calls, m.maxSeen, m.overlaps, rt.MaxObservedConcurrentTurns())
}

// TestGateControl_WouldCatchABypass is the NEGATIVE control for the test
// above: same 5 concurrent workers, but MaxConcurrentTurns=5 (effectively no
// gate). This proves the assertion in TestGateHolds is a real detector, not a
// tautology that would pass no matter what — with room to run concurrently,
// scriptedModel DOES observe overlap.
func TestGateControl_WouldCatchABypass(t *testing.T) {
	m := &scriptedModel{hold: 15 * time.Millisecond}
	rt := agents.NewRuntime(agents.Options{
		Transport:          tn.InProcessTransport(m.generate),
		MaxConcurrentTurns: 5,
		Registry: map[string]agents.Def{
			"worker": {Name: "worker", Does: "a scripted worker"},
		},
	})
	const n = 5
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			h, err := rt.Spawn(rt.Root, "worker", nil)
			if err != nil {
				t.Error(err)
				return
			}
			rt.Wake(h, fmt.Sprintf("job %d", i))
			rt.Wait(h, 0)
		}(i)
	}
	wg.Wait()

	if got := atomic.LoadInt32(&m.overlaps); got == 0 {
		t.Fatalf("control INVALID: expected overlap with MaxConcurrentTurns=5 and a "+
			"15ms hold, got 0 overlaps (maxSeen=%d) — the detector proves nothing", m.maxSeen)
	}
	t.Logf("control confirms detector is live: calls=%d maxSeen=%d overlaps=%d",
		m.calls, m.maxSeen, m.overlaps)
}
