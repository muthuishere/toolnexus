package agents

import (
	"strings"
	"testing"
	"time"
)

// The background-agent recipe (SPEC §7D "Background runs"): Spawn + Wake return
// at once with the handle id, and a Wait in the background delivers the
// completion to the host's callback. No new API; this pins the recipe.
func TestBackgroundRunRecipe(t *testing.T) {
	rt := NewRuntime(Options{Transport: &mockLLM{}, Registry: testRegistry()})
	start := time.Now()
	h, err := rt.Spawn(rt.Root, "coordinator", nil)
	if err != nil {
		t.Fatal(err)
	}
	if res := rt.Wake(h, "answer using two lookups"); !res.Ok {
		t.Fatalf("wake: %s", res.Err)
	}
	id := h.ID
	if id == "" {
		t.Fatal("the handle id is available at once")
	}

	done := make(chan TaskResult, 1)
	go func() { done <- rt.Wait(h, 0) }() // onDone(id, result)

	select {
	case r := <-done:
		if r.Status != "done" || !strings.Contains(r.Text, "found:") {
			t.Fatalf("completion: %+v", r)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("no completion for %s within 5s (started %s ago)", id, time.Since(start))
	}
}
