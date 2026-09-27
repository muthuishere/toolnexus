package toolnexus

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// TestEvaluateBatch_OrderPreserved: results come back in the SAME order as
// states, even though calls run concurrently and answer at different speeds.
func TestEvaluateBatch_OrderPreserved(t *testing.T) {
	c, err := CreateClassifier(ClassifierOptions{
		Style: StyleCustom,
		Evaluate: func(_ context.Context, state any, questions map[string]Question) (Decision, error) {
			n := state.(int)
			return Decision{
				Model:   "test",
				Answers: map[string]DecisionAnswer{"score": NoulAnswer{Noul: float64(n)}},
			}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	qs := map[string]Question{"score": NoulQuestion{Instructions: "n?"}}
	states := make([]any, 50)
	for i := range states {
		states[i] = i
	}
	decisions, err := c.EvaluateBatch(context.Background(), states, qs)
	if err != nil {
		t.Fatalf("EvaluateBatch: %v", err)
	}
	if len(decisions) != len(states) {
		t.Fatalf("got %d decisions, want %d", len(decisions), len(states))
	}
	for i, d := range decisions {
		a, err := d.Noul("score")
		if err != nil {
			t.Fatalf("state %d: Noul: %v", i, err)
		}
		if int(a.Noul) != i {
			t.Fatalf("state %d: got score %v, want %d (order not preserved)", i, a.Noul, i)
		}
	}
}

// TestEvaluateBatch_FailsClosedOnAnyError: one bad state fails the whole
// batch, naming its index — no partial/unverified result is ever returned.
func TestEvaluateBatch_FailsClosedOnAnyError(t *testing.T) {
	boom := errors.New("boom")
	c, err := CreateClassifier(ClassifierOptions{
		Style: StyleCustom,
		Evaluate: func(_ context.Context, state any, _ map[string]Question) (Decision, error) {
			if state.(int) == 3 {
				return Decision{}, boom
			}
			return Decision{Model: "test"}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	qs := map[string]Question{"score": NoulQuestion{Instructions: "n?"}}
	states := make([]any, 10)
	for i := range states {
		states[i] = i
	}
	_, err = c.EvaluateBatch(context.Background(), states, qs)
	if err == nil {
		t.Fatal("want an error, got nil")
	}
	if !errors.Is(err, boom) {
		t.Fatalf("error does not wrap the underlying failure: %v", err)
	}
	if got, want := err.Error(), "state 3:"; !containsStr(got, want) {
		t.Fatalf("error %q does not name the failing state index (%q)", got, want)
	}
}

// TestEvaluateBatch_LowestIndexWins: states 2 and 0 both fail, state 0 last;
// the error names state 0.
func TestEvaluateBatch_LowestIndexWins(t *testing.T) {
	c, err := CreateClassifier(ClassifierOptions{
		Style: StyleCustom,
		Evaluate: func(_ context.Context, state any, _ map[string]Question) (Decision, error) {
			switch state.(int) {
			case 0:
				time.Sleep(20 * time.Millisecond)
				return Decision{}, errors.New("boom 0")
			case 2:
				return Decision{}, errors.New("boom 2")
			}
			return Decision{Model: "test"}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = c.EvaluateBatch(context.Background(), []any{0, 1, 2}, map[string]Question{"q": NoulQuestion{Instructions: "?"}})
	if err == nil || !strings.HasPrefix(err.Error(), "state 0:") {
		t.Fatalf("error should name state 0: %v", err)
	}
}

// TestEvaluateBatch_EmptyStates: an empty batch is a caller mistake, not a
// silent no-op — same posture as Evaluate's "no questions" guard.
func TestEvaluateBatch_EmptyStates(t *testing.T) {
	calls := 0
	c, err := CreateClassifier(ClassifierOptions{
		Style:    StyleCustom,
		Evaluate: func(context.Context, any, map[string]Question) (Decision, error) { calls++; return Decision{}, nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.EvaluateBatch(context.Background(), nil, map[string]Question{"q": NoulQuestion{Instructions: "?"}}); err == nil {
		t.Fatal("want an error for an empty states slice, got nil")
	}
	if calls != 0 {
		t.Fatalf("empty batch sent %d requests", calls)
	}
}

// TestEvaluateBatch_BoundedConcurrency: EvaluateBatch never has more than
// DefaultClassifierBatchConcurrency Evaluate calls in flight at once, so it
// cannot itself become a thundering herd against a backend with no native
// batch call.
func TestEvaluateBatch_BoundedConcurrency(t *testing.T) {
	var inFlight, maxInFlight int64
	release := make(chan struct{})
	c, err := CreateClassifier(ClassifierOptions{
		Style: StyleCustom,
		Evaluate: func(ctx context.Context, _ any, _ map[string]Question) (Decision, error) {
			n := atomic.AddInt64(&inFlight, 1)
			for {
				old := atomic.LoadInt64(&maxInFlight)
				if n <= old || atomic.CompareAndSwapInt64(&maxInFlight, old, n) {
					break
				}
			}
			<-release
			atomic.AddInt64(&inFlight, -1)
			return Decision{Model: "test"}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	states := make([]any, DefaultClassifierBatchConcurrency*4)
	for i := range states {
		states[i] = i
	}
	done := make(chan struct{})
	go func() {
		if _, err := c.EvaluateBatch(context.Background(), states, map[string]Question{"q": NoulQuestion{Instructions: "?"}}); err != nil {
			t.Errorf("EvaluateBatch: %v", err)
		}
		close(done)
	}()
	close(release)
	<-done
	if got := atomic.LoadInt64(&maxInFlight); got > int64(DefaultClassifierBatchConcurrency) {
		t.Fatalf("max concurrent Evaluate calls = %d, want <= %d", got, DefaultClassifierBatchConcurrency)
	}
}

func containsStr(s, sub string) bool {
	return len(s) >= len(sub) && (func() bool {
		for i := 0; i+len(sub) <= len(s); i++ {
			if s[i:i+len(sub)] == sub {
				return true
			}
		}
		return false
	})()
}
