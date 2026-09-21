// Tests for ADR 0030 / issue #95: agents.Options.InProcess, the semantic
// counterpart to Options.Transport, so a host whose model is a Go function
// doesn't have to hand-build an http.RoundTripper to reach the sub-agent
// runtime. Ported from the spike at spikes/inprocess-subagent/SPIKE.md.
package agents

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// scriptedModel is the fake model shared by BOTH the top-level client and the
// sub-agent runtime below — the reporter's actual case. It never touches
// HTTP: it is handed the assembled InProcessRequest and returns one
// InProcessResponse. It instruments concurrency so the gate test can assert
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

// TestInProcessOption_SharedGenerate_TopLevelAndSubAgent proves the reporter's
// actual case: ONE generate function serving BOTH a top-level
// tn.CreateInProcessClient AND a sub-agent runtime (via the new
// Options.InProcess field), with no copied adapter code — NewRuntime builds
// its transport by calling the SAME tn.InProcessTransport export that
// CreateInProcessClient calls.
func TestInProcessOption_SharedGenerate_TopLevelAndSubAgent(t *testing.T) {
	m := &scriptedModel{}

	// Top-level client, driven by m.generate directly.
	client := tn.CreateInProcessClient(tn.InProcessOptions{Model: "spike-model", Generate: m.generate})
	toolkit, err := tn.CreateToolkit(context.Background(), tn.Options{})
	if err != nil {
		t.Fatal(err)
	}
	topRes, err := client.Ask(context.Background(), "hello", toolkit, "top-level")
	if err != nil {
		t.Fatal(err)
	}
	if topRes.Status != "done" {
		t.Fatalf("top-level status = %q, want done (%s)", topRes.Status, topRes.Text)
	}

	// Sub-agent runtime, driven by the SAME m.generate via Options.InProcess.
	rt := NewRuntime(Options{
		InProcess: m.generate,
		Registry: map[string]Def{
			"worker": {Name: "worker", Does: "a scripted worker"},
		},
	})
	h, err := rt.Spawn(rt.Root, "worker", nil)
	if err != nil {
		t.Fatal(err)
	}
	rt.Wake(h, "do the thing")
	subRes := rt.Wait(h, 0)
	if subRes.Status != "done" {
		t.Fatalf("sub-agent status = %q, want done (%s)", subRes.Status, subRes.Text)
	}

	if got := atomic.LoadInt32(&m.calls); got != 2 {
		t.Fatalf("m.calls = %d, want 2 (one top-level Ask, one sub-agent turn)", got)
	}
}

// TestNewRuntime_InProcessAndTransportConflict proves construction-time
// validation, never precedence: setting both Options.Transport and
// Options.InProcess must error loudly (a panic, matching this constructor
// family's existing style — see tn.CreateInProcessClient).
func TestNewRuntime_InProcessAndTransportConflict(t *testing.T) {
	m := &scriptedModel{}
	defer func() {
		if recover() == nil {
			t.Fatal("NewRuntime did not panic with both Transport and InProcess set")
		}
	}()
	NewRuntime(Options{
		Transport: tn.InProcessTransport(m.generate),
		InProcess: m.generate,
	})
}

// TestNewRuntime_InProcessAndLLMConflict is the same validation for the
// InProcess/LLM pair.
func TestNewRuntime_InProcessAndLLMConflict(t *testing.T) {
	m := &scriptedModel{}
	defer func() {
		if recover() == nil {
			t.Fatal("NewRuntime did not panic with both LLM and InProcess set")
		}
	}()
	NewRuntime(Options{
		LLM:       &LLMOptions{BaseURL: "http://example.invalid"},
		InProcess: m.generate,
	})
}

// TestInProcessOption_GateHoldsAtConcurrencyOne is the falsifiable gate test
// (ADR 0030 gate item 2): MaxConcurrentTurns=1, five workers woken
// concurrently on the NEW Options.InProcess path. If the global turn gate
// (gatedTransport, runtime.go) stopped wrapping the transport built from
// Options.InProcess, scriptedModel would observe >1 in flight and this test
// fails.
func TestInProcessOption_GateHoldsAtConcurrencyOne(t *testing.T) {
	m := &scriptedModel{hold: 15 * time.Millisecond}
	rt := NewRuntime(Options{
		InProcess:          m.generate,
		MaxConcurrentTurns: 1,
		Registry: map[string]Def{
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
			"Options.InProcess transport", got, m.maxSeen)
	}
	if got := rt.MaxObservedConcurrentTurns(); got != 1 {
		t.Fatalf("rt.MaxObservedConcurrentTurns() = %d, want 1", got)
	}
	t.Logf("gate holds: calls=%d maxSeen=%d overlaps=%d rt.MaxObservedConcurrentTurns=%d",
		m.calls, m.maxSeen, m.overlaps, rt.MaxObservedConcurrentTurns())
}

// TestInProcessOption_GateControl_WouldCatchABypass is the negative control:
// same 5 concurrent workers, but MaxConcurrentTurns=5 (effectively no gate).
// Proves the assertion above is a real detector, not a tautology — with room
// to run concurrently, scriptedModel DOES observe overlap.
func TestInProcessOption_GateControl_WouldCatchABypass(t *testing.T) {
	m := &scriptedModel{hold: 15 * time.Millisecond}
	rt := NewRuntime(Options{
		InProcess:          m.generate,
		MaxConcurrentTurns: 5,
		Registry: map[string]Def{
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
