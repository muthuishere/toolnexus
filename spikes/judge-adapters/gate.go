// Package main is a spike: a Layer-1 "confidence-gated escalation" adapter over
// tn.Classifier — Ask (banded answers) + Gate (rules over them, §10 escalation).
package main

import (
	"context"
	"fmt"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Rule is one gate. Exactly one of Below / AtLeast / Is applies (same shape as
// wfnexus workflow.DecideGate so the YAML maps 1:1).
type Rule struct {
	Question string
	Below    *float64
	AtLeast  *float64
	Is       string
	Action   string // e.g. "fail", "skip_to"
	Target   string // skip_to target
}

// Outcome: an Action fired, or Escalated with a §10 Request, or neither (fall through).
type Outcome struct {
	Action    string
	Target    string
	Rule      *Rule
	Answers   Answers
	Escalated bool
	Request   *tn.Request
}

// Gate asks once and applies rules first-match. A rule whose answer is missing
// or uncertain ESCALATES (needs_input) instead of being decided.
func Gate(ctx context.Context, c *tn.Classifier, state any, qs []Q, rules []Rule, b Bands) (Outcome, error) {
	a, err := Ask(ctx, c, state, qs, b)
	if err != nil {
		return Outcome{}, err
	}
	return Apply(a, rules), nil
}

// Apply is the pure half of Gate.
func Apply(a Answers, rules []Rule) Outcome {
	out := Outcome{Answers: a}
	for i := range rules {
		r := &rules[i]
		fired, reason := check(a, r)
		if reason != "" {
			raw := map[string]tn.DecisionAnswer{}
			for k, v := range a {
				raw[k] = v.DecisionAnswer
			}
			out.Escalated, out.Action, out.Rule = true, "needs_input", r
			out.Request = &tn.Request{
				ID:     fmt.Sprintf("gate:%d:%s", i, r.Question),
				Kind:   "input",
				Prompt: fmt.Sprintf("Classifier is unsure about %q (%s). Decide rule %d (%s).", r.Question, reason, i, r.Action),
				Data:   map[string]any{"question": r.Question, "reason": reason, "answers": raw, "rule": *r},
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
func check(as Answers, r *Rule) (bool, string) {
	a, ok := as[r.Question]
	if !ok {
		return false, "missing answer"
	}
	if a.Band == Uncertain {
		return false, "uncertain " + a.AnswerType() + " answer"
	}
	switch x := a.DecisionAnswer.(type) {
	case tn.NoulAnswer, tn.ScoreAnswer:
		v := 0.0
		if n, ok := x.(tn.NoulAnswer); ok {
			v = n.Noul
		} else {
			v = x.(tn.ScoreAnswer).Score
		}
		switch {
		case r.Below != nil:
			return v < *r.Below, ""
		case r.AtLeast != nil:
			return v >= *r.AtLeast, ""
		}
	case tn.ChoiceAnswer:
		if r.Is != "" {
			return x.Choice == r.Is, ""
		}
	}
	return false, fmt.Sprintf("rule does not fit %s answer", a.AnswerType())
}
