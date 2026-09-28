package judge

import (
	"context"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Policy is Gate with a DECLARED fall-through. Apply returns Action "" when no
// rule fires, and every caller then had to invent what that means. Here it is
// configured: Default names the action, and Default "" escalates as a §10
// input Request ("no rule fired") instead of silently deciding nothing.
type Policy struct {
	Rules   []Rule
	Default string
	Bands   Bands // zero value -> DefaultBands
	// SkipUncertain: an uncertain answer SKIPS its rule instead of escalating.
	// Apply's first-match escalates on the first unsure rule, so one unsure
	// score blocks every later, confident rule. With SkipUncertain, escalation
	// comes only from Default "" — "nothing confident decided".
	SkipUncertain bool
}

// Decide applies the rules, then the declared default.
func (p Policy) Decide(a Answers) Outcome {
	rules := p.Rules
	if p.SkipUncertain {
		rules = nil
		for _, r := range p.Rules {
			if x, ok := a[r.Question]; ok && x.Band != Uncertain {
				rules = append(rules, r)
			}
		}
	}
	o := Apply(a, rules)
	if o.Action != "" || o.Escalated {
		return o
	}
	if p.Default != "" {
		o.Action = p.Default
		return o
	}
	raw := map[string]tn.DecisionAnswer{}
	for k, v := range a {
		raw[k] = v.DecisionAnswer
	}
	o.Escalated, o.Action = true, "needs_input"
	o.Request = &tn.Request{ID: "gate:default", Kind: "input",
		Prompt: "No rule fired: the answers are confident but in between. Decide.",
		Data:   map[string]any{"reason": "no rule fired", "answers": raw}}
	return o
}

// Gate asks once and decides.
func (p Policy) Gate(ctx context.Context, c *tn.Classifier, state any, qs []Q) (Outcome, error) {
	b := p.Bands
	if b == (Bands{}) {
		b = DefaultBands
	}
	a, err := Ask(ctx, c, state, qs, b)
	if err != nil {
		return Outcome{}, err
	}
	return p.Decide(a), nil
}
