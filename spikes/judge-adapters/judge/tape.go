package judge

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"sync"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Tape records live classifier decisions BY NAME and replays them offline.
//
// StyleStatic matches the EXACT canonical state+questions, so a recording
// breaks the moment a prompt or a state field changes and the error only says
// "no recorded decision". A Tape keys each call by a caller-chosen name carried
// on the context (WithKey), so a replay survives cosmetic state changes and a
// miss names the call that has no recording.
type Tape struct {
	mu        sync.Mutex
	Decisions map[string]json.RawMessage `json:"decisions"`
	Calls     []Call                     `json:"-"` // this run's calls, in order
}

// Call is one classifier call's cost, measured at the seam.
type Call struct {
	Key      string
	Ms       int64
	In, Out  int
	Model    string
	Replayed bool
}

type keyCtx struct{}

// WithKey names the next Evaluate for the tape ("triage", "plan", "verify:X").
func WithKey(ctx context.Context, key string) context.Context {
	return context.WithValue(ctx, keyCtx{}, key)
}

func keyOf(ctx context.Context) string { s, _ := ctx.Value(keyCtx{}).(string); return s }

// LoadTape reads a tape file; a missing file is an error (offline needs one).
func LoadTape(path string) (*Tape, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	t := &Tape{}
	return t, json.Unmarshal(b, t)
}

// Save writes the tape with sorted keys (stable diffs).
func (t *Tape) Save(path string) error {
	b, err := json.MarshalIndent(t, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

// Recording wraps a live classifier: every call is forwarded, timed and taped.
func (t *Tape) Recording(live *tn.Classifier) (*tn.Classifier, error) {
	return tn.CreateClassifier(tn.ClassifierOptions{Style: tn.StyleCustom,
		Evaluate: func(ctx context.Context, state any, qs map[string]tn.Question) (tn.Decision, error) {
			start := time.Now()
			d, err := live.Evaluate(ctx, state, qs)
			if err != nil {
				return d, err
			}
			raw, err := encode(d)
			if err != nil {
				return d, err
			}
			t.log(ctx, d, time.Since(start), false, raw)
			return d, nil
		}})
}

// Replayer answers from the tape by key; a miss names the key.
func (t *Tape) Replayer() (*tn.Classifier, error) {
	return tn.CreateClassifier(tn.ClassifierOptions{Style: tn.StyleCustom,
		Evaluate: func(ctx context.Context, _ any, _ map[string]tn.Question) (tn.Decision, error) {
			k := keyOf(ctx)
			t.mu.Lock()
			raw, ok := t.Decisions[k]
			t.mu.Unlock()
			if !ok {
				return tn.Decision{}, fmt.Errorf("tape: no recording for key %q (re-run with --record)", k)
			}
			var d tn.Decision
			if err := json.Unmarshal(raw, &d); err != nil {
				return d, err
			}
			t.log(ctx, d, 0, true, nil)
			return d, nil
		}})
}

func (t *Tape) log(ctx context.Context, d tn.Decision, el time.Duration, replayed bool, raw json.RawMessage) {
	t.mu.Lock()
	defer t.mu.Unlock()
	k := keyOf(ctx)
	if raw != nil {
		if t.Decisions == nil {
			t.Decisions = map[string]json.RawMessage{}
		}
		t.Decisions[k] = raw
	}
	t.Calls = append(t.Calls, Call{k, el.Milliseconds(), d.Usage.InputTokens, d.Usage.OutputTokens, d.Model, replayed})
}

// encode writes a Decision back in wire shape (answers carry their "type").
func encode(d tn.Decision) (json.RawMessage, error) {
	ans := map[string]map[string]any{}
	keys := make([]string, 0, len(d.Answers))
	for k := range d.Answers {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		b, _ := json.Marshal(d.Answers[k])
		m := map[string]any{}
		if err := json.Unmarshal(b, &m); err != nil {
			return nil, err
		}
		m["type"] = d.Answers[k].AnswerType()
		ans[k] = m
	}
	return json.Marshal(map[string]any{"model": d.Model, "answers": ans, "usage": d.Usage, "calibrated": d.Calibrated})
}
