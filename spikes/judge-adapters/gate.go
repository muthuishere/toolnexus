// Package main is a spike: a Layer-1 "confidence-gated escalation" adapter over
// tn.Classifier — what the library might ship so callers stop hand-rolling
// decide gates on raw numbers.
package main

import (
	"context"
	"fmt"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Bands split a probability/confidence into no / uncertain / yes.
// Both cut points are EXCLUSIVE for the confident side: exactly Low or exactly
// High is uncertain (matches wfnexus judge.Bands and the TypeSafe cookbook).
type Bands struct{ Low, High float64 }

var DefaultBands = Bands{Low: 0.30, High: 0.70}

func (b Bands) Noul(p float64) string {
	switch {
	case p < b.Low:
		return "no"
	case p > b.High:
		return "yes"
	}
	return "uncertain"
}

// ChoiceSure: confidence strictly above High and not near-uniform.
func (b Bands) ChoiceSure(a tn.ChoiceAnswer) bool { return !a.NearUniform && a.Confidence > b.High }

// Rule is one gate. Exactly one of Below / AtLeast / Is applies (same shape as
// wfnexus workflow.DecideGate so the YAML maps 1:1).
type Rule struct {
	Question string
	Below    *float64
	AtLeast  *float64
	Is       string
	Action   string // e.g. "fail", "skip_to", "needs_input"
	Target   string // skip_to target
}

// Outcome of a gate pass. Exactly one of: an Action fired, Escalated with a
// §10 Request, or neither (fall through: run the step).
type Outcome struct {
	Action    string
	Target    string
	Rule      *Rule
	Answers   map[string]tn.DecisionAnswer
	Escalated bool
	Request   *tn.Request
}

// Gate evaluates once and applies rules in order. A rule whose answer is in the
// uncertain band (or missing / wrong type) ESCALATES instead of being decided:
// the first such rule wins, exactly as the first firing rule wins.
func Gate(ctx context.Context, c *tn.Classifier, state any, qs map[string]tn.Question, rules []Rule, b Bands) (Outcome, error) {
	d, err := c.Evaluate(ctx, state, qs)
	if err != nil {
		return Outcome{}, err
	}
	return Apply(d, rules, b), nil
}

// Apply is the pure half of Gate (no I/O), for callers that already hold a Decision.
func Apply(d tn.Decision, rules []Rule, b Bands) Outcome {
	out := Outcome{Answers: d.Answers}
	for i := range rules {
		r := &rules[i]
		fired, reason := check(d, r, b)
		if reason != "" {
			out.Escalated, out.Action, out.Rule = true, "needs_input", r
			out.Request = &tn.Request{
				ID:     fmt.Sprintf("gate:%d:%s", i, r.Question),
				Kind:   "input",
				Prompt: fmt.Sprintf("Classifier is unsure about %q (%s). Decide rule %d (%s).", r.Question, reason, i, r.Action),
				Data:   map[string]any{"question": r.Question, "reason": reason, "answers": d.Answers, "rule": *r},
			}
			return out
		}
		if fired {
			out.Action, out.Target, out.Rule = r.Action, r.Target, r
			return out
		}
	}
	return out
}

// check returns (fired, escalateReason). A non-empty reason means "ask a human".
func check(d tn.Decision, r *Rule, b Bands) (bool, string) {
	a, ok := d.Answers[r.Question]
	if !ok {
		return false, "missing answer"
	}
	switch {
	case r.Below != nil || r.AtLeast != nil:
		var v float64
		switch x := a.(type) {
		case tn.NoulAnswer:
			if b.Noul(x.Noul) == "uncertain" {
				return false, fmt.Sprintf("noul %.2f in uncertain band [%.2f,%.2f]", x.Noul, b.Low, b.High)
			}
			v = x.Noul
		case tn.ScoreAnswer:
			if x.Confidence <= b.High {
				return false, fmt.Sprintf("score confidence %.2f <= %.2f", x.Confidence, b.High)
			}
			v = x.Score
		default:
			return false, "numeric rule on " + a.AnswerType() + " answer"
		}
		if r.Below != nil {
			return v < *r.Below, ""
		}
		return v >= *r.AtLeast, ""
	case r.Is != "":
		x, ok := a.(tn.ChoiceAnswer)
		if !ok {
			return false, "is-rule on " + a.AnswerType() + " answer"
		}
		if !b.ChoiceSure(x) {
			return false, fmt.Sprintf("choice %q confidence %.2f nearUniform=%v", x.Choice, x.Confidence, x.NearUniform)
		}
		return x.Choice == r.Is, ""
	}
	return false, "rule has no condition"
}
