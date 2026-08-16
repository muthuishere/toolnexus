package agents

// Loop — a live execution of an Agent, and the completion gate that stops it
// claiming `done` too early. A PROTOTYPE layer over the shipped §8 client:
// nothing here changes existing behaviour.
//
// The placement law this encodes:
//
//	Spec (the harness) answers "MAY it?"      — capability, ceilings.  Per problem.
//	Run                answers "with WHAT?"   — model for this call.   Per call.
//	Loop               answers "DID it?"      — status, turns.         Observed.
//	none of them       answers "is it RIGHT?" — a tool, skill or agent.
//
// So Loop takes no options: it is read, not configured.

import (
	"context"
	"fmt"
	"strings"

	tn "github.com/muthuishere/toolnexus/golang"
)

// guardedHooks compiles a Spec's guardrails into one BeforeTool with
// FIRST-DENY-WINS, composed ahead of any hook the Spec already set.
func guardedHooks(sp Spec) *tn.Hooks {
	if len(sp.Guardrails) == 0 {
		return sp.Hooks
	}
	prior := func(context.Context, tn.BeforeToolEvent) (*tn.ToolOverride, error) { return nil, nil }
	merged := tn.Hooks{}
	if sp.Hooks != nil {
		merged = *sp.Hooks
		if sp.Hooks.BeforeTool != nil {
			prior = sp.Hooks.BeforeTool
		}
	}
	rails := sp.Guardrails
	merged.BeforeTool = func(ctx context.Context, ev tn.BeforeToolEvent) (*tn.ToolOverride, error) {
		for _, g := range rails {
			if v := g(ev); v != "" && v != "allow" {
				return &tn.ToolOverride{Result: &tn.ToolResult{
					Output: "denied: " + v, IsError: true}}, nil
			}
		}
		return prior(ctx, ev)
	}
	return &merged
}

// Loop is a live execution of an Agent. Its only verbs are Run and Resume.
type Loop struct {
	agent   *Agent
	client  *tn.Client
	toolkit *tn.Toolkit
	history []any
	calls   []tn.ToolCall // accumulated across attempts — see Run
	turns   int
	status  string
}

// RunOpts is what varies PER CALL. Model is here, not on the Spec's default and
// not on the Loop, so the same conversation may change model between turns.
type RunOpts struct {
	// Model overrides the agent's model for this call only. "" ⇒ the agent's.
	Model string
}

// Outcome is what a Run reports. Status reuses the SHIPPED vocabulary — no new
// status strings are minted (SPEC.md pins TaskStatus identical across ports).
type Outcome struct {
	Text      string
	Status    string // done | incomplete | pending | error
	StoppedBy string // named whenever Status != "done" — never a silent stop
	Attempts  int
	Turns     int
	Result    tn.RunResult
}

// Loop opens a live execution for an agent against a client and toolkit.
func (a *Agent) Loop(c *tn.Client, tk *tn.Toolkit) *Loop {
	return &Loop{agent: a, client: c, toolkit: tk, status: "idle"}
}

// Status is observed, never set by the caller.
func (l *Loop) Status() string { return l.status }

// Turns is the model round trips this loop has spent.
func (l *Loop) Turns() int { return l.turns }

// Run executes one request. When the agent's Spec declares a Completion, the
// gate runs exactly where the loop would otherwise have returned `done`.
func (l *Loop) Run(ctx context.Context, prompt string, opts RunOpts) (Outcome, error) {
	comp := l.agent.Spec.Completion
	maxAttempts := 1
	if comp != nil {
		if comp.MaxAttempts < 1 {
			return Outcome{}, fmt.Errorf("toolnexus: Completion.MaxAttempts must be >= 1")
		}
		if comp.Verify == nil {
			return Outcome{}, fmt.Errorf("toolnexus: Completion.Verify is required")
		}
		maxAttempts = comp.MaxAttempts
	}

	l.status = "running"
	var last tn.RunResult
	var lastReason string
	for n := 1; n <= maxAttempts; n++ {
		p := prompt
		if n > 1 {
			p = "Your work did not verify: " + lastReason + ". Fix it and finish."
		}
		r, err := l.client.RunWithHistory(ctx, p, l.toolkit, l.history)
		if err != nil {
			l.status = "error"
			return Outcome{Status: "error", StoppedBy: err.Error(), Attempts: n, Turns: l.turns}, err
		}
		last = r
		l.turns += r.Turns
		l.history = r.Messages
		// The gate judges the LOOP's accumulated work, not one attempt. Without
		// this, an agent escapes the gate by simply not re-declaring its plan on
		// the retry: the fresh run carries no todowrite, the verifier sees "no
		// plan", and passes. Found by prototyping; see loop_test.go.
		l.calls = append(l.calls, r.ToolCalls...)
		r.ToolCalls = l.calls

		// Rule 2: a suspension or a limit stop already carries its own reason.
		// The gate never re-judges those, so it can never override a budget stop
		// or turn a `pending` into an `incomplete`.
		if r.Status != "" && r.Status != "done" {
			l.status = r.Status
			return Outcome{Text: r.Text, Status: r.Status, StoppedBy: "run reported " + r.Status,
				Attempts: n, Turns: l.turns, Result: r}, nil
		}
		if comp == nil {
			l.status = "idle"
			return Outcome{Text: r.Text, Status: "done", Attempts: n, Turns: l.turns, Result: r}, nil
		}
		ok, reason := comp.Verify(r)
		if ok {
			l.status = "idle"
			return Outcome{Text: r.Text, Status: "done", Attempts: n, Turns: l.turns, Result: r}, nil
		}
		lastReason = reason
		l.history = append(r.Messages, map[string]any{
			"role": "user", "content": "verification failed: " + reason})
	}
	l.status = "incomplete"
	return Outcome{
		Text: last.Text, Status: "incomplete",
		StoppedBy: fmt.Sprintf("completion.verify failed %d×: %s", maxAttempts, lastReason),
		Attempts:  maxAttempts, Turns: l.turns, Result: last,
	}, nil
}

// AllTodosDone is the built-in completion verifier. It reads the SHIPPED
// `todowrite` builtin's result metadata and requires every item to be checked.
//
// Structural, not domain: it counts unchecked boxes and never learns what a todo
// means, so the loop stays domain-blind. No plan declared ⇒ nothing to verify ⇒
// pass, so the gate never punishes an agent that does not use the builtin.
func AllTodosDone(r tn.RunResult) (bool, string) {
	for i := len(r.ToolCalls) - 1; i >= 0; i-- {
		if r.ToolCalls[i].Name != "todowrite" {
			continue
		}
		raw, ok := r.ToolCalls[i].Metadata["todos"].([]any)
		if !ok {
			return true, ""
		}
		var open []string
		for _, it := range raw {
			m, _ := it.(map[string]any)
			if done, _ := m["completed"].(bool); !done {
				txt, _ := m["text"].(string)
				open = append(open, txt)
			}
		}
		if len(open) > 0 {
			return false, fmt.Sprintf("%d item(s) still open: %s", len(open), strings.Join(open, "; "))
		}
		return true, ""
	}
	return true, ""
}
