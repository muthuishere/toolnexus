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
	opts    tn.ClientOptions // kept so a per-run Model override can rebuild the client
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

// Loop opens a live execution for an agent. It takes the CLIENT OPTIONS rather
// than a built client, because RunOpts.Model must be able to override the model
// for a single call — and the model is fixed when a client is constructed.
func (a *Agent) Loop(opts tn.ClientOptions, tk *tn.Toolkit) *Loop {
	return &Loop{agent: a, opts: opts, toolkit: tk, status: "idle"}
}

// clientFor returns the client for this call, applying a per-run Model override
// via RequestParams (`model` is NOT in the forbidden set — client.go forbids only
// messages/tools/stream — and the merge reaches the wire; spiked).
func (l *Loop) clientFor(o RunOpts) *tn.Client {
	if o.Model == "" {
		return tn.CreateClient(l.opts)
	}
	opts := l.opts
	rp := map[string]any{}
	for k, v := range opts.RequestParams {
		rp[k] = v
	}
	rp["model"] = o.Model
	opts.RequestParams = rp
	return tn.CreateClient(opts)
}

// Status is observed, never set by the caller.
func (l *Loop) Status() string { return l.status }

// Turns is the model round trips this loop has spent.
func (l *Loop) Turns() int { return l.turns }

// Run executes one request. When the agent's Spec declares a Completion, the
// gate runs exactly where the loop would otherwise have returned `done`.
func (l *Loop) Run(ctx context.Context, prompt string, opts RunOpts) (Outcome, error) {
	client := l.clientFor(opts)
	l.status = "running"

	attempts := 0
	// The gate lives in runGated, NOT here. An inline second copy is how this port
	// came to report a completion stop without setting Result.Limit, while the
	// runtime path set it — one behaviour, two implementations, one of them wrong.
	r, err := runGated(func(p string) (tn.RunResult, error) {
		attempts++
		out, err := client.RunWithHistory(ctx, p, l.toolkit, l.history)
		if err != nil {
			return out, err
		}
		l.turns += out.Turns
		l.history = out.Messages
		return out, nil
	}, prompt, l.agent.Spec.Completion)

	if err != nil {
		l.status = "error"
		return Outcome{Status: "error", StoppedBy: err.Error(), Attempts: attempts, Turns: l.turns}, err
	}

	if r.Status != "" && r.Status != "done" {
		l.status = r.Status
		stoppedBy := "run reported " + r.Status
		if r.Limit == "completion" {
			stoppedBy = r.Text
		}
		return Outcome{Text: r.Text, Status: r.Status, StoppedBy: stoppedBy,
			Attempts: attempts, Turns: l.turns, Result: r}, nil
	}

	l.status = "idle"
	return Outcome{Text: r.Text, Status: "done", Attempts: attempts, Turns: l.turns, Result: r}, nil
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

// runGated wraps a client run with the completion gate. It is the SHARED
// implementation used by both the standalone Loop and the §7D runtime turn, so
// a delegated child gets exactly the same guarantee as a directly-driven one.
//
// Rule 2 in force: a run that is `pending` (suspended on a human) or otherwise
// non-done already carries its own reason, so the gate never re-judges it. That
// keeps `pending` and `incomplete` distinct — the caller can always tell whether
// it owes an Answer or a fix.
// ask runs ONE attempt. Taking a closure rather than a client is what lets the
// standalone Loop and the runtime turn share this function instead of keeping two
// copies of the six rules — which is how the two drifted in the first place.
type ask func(prompt string) (tn.RunResult, error)

func runGated(a ask, prompt string, comp *Completion) (tn.RunResult, error) {
	if comp == nil {
		return a(prompt)
	}
	maxAttempts := comp.MaxAttempts
	if maxAttempts < 1 {
		return tn.RunResult{}, fmt.Errorf("toolnexus: Completion.MaxAttempts must be >= 1")
	}
	if comp.Verify == nil {
		return tn.RunResult{}, fmt.Errorf("toolnexus: Completion.Verify is required")
	}
	var acc []tn.ToolCall
	var last tn.RunResult
	var reason string
	for n := 1; n <= maxAttempts; n++ {
		p := prompt
		if n > 1 {
			p = "Your work did not verify: " + reason + ". Fix it and finish."
		}
		r, err := a(p)
		if err != nil {
			return r, err
		}
		// The gate judges the accumulated work, so an agent cannot escape it by
		// declining to re-declare its plan on a retry.
		acc = append(acc, r.ToolCalls...)
		r.ToolCalls = acc
		last = r
		if r.Status != "" && r.Status != "done" {
			// The run stopped for its own reason (suspension, budget). If the gate
			// was mid-retry, the caller must learn BOTH — otherwise a budget stop
			// masks the verification failure and they never see why it was looping.
			// Spiked: without this the caller reads only "hit maxTurns".
			if reason != "" && r.Status != "pending" {
				r.Text = fmt.Sprintf("%s [while verifying: attempt %d last failed: %s]",
					r.Text, n, reason)
			}
			return r, nil
		}
		ok, why := comp.Verify(r)
		if ok {
			return r, nil
		}
		reason = why
	}
	// Structured, not prose: `Limit` is how a caller (and the §7D runtime) tells
	// WHICH limit stopped the run. Text carries the human reason.
	last.Status = "incomplete"
	last.Limit = "completion"
	last.Text = fmt.Sprintf("completion.verify failed %d×: %s", maxAttempts, reason)
	return last, nil
}
