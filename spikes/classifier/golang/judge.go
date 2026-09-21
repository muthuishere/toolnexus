package classifier

import (
	"context"
	"fmt"
	"sort"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
)

// Verdict is what a rule produces. Kept is the surviving item(s) for topK/one;
// for bands it is the single band name ("allow" | "ask" | "deny").
type Verdict struct {
	Kept       []string
	Evidence   map[string]float64
	Model      string
	Calibrated bool
	Reason     string
}

// Band is one ordered risk band: everything with score <= Max is this band.
// The LAST band's Max is ignored (it is the catch-all).
type Band struct {
	Max  float64
	Name string
}

// Rule turns a Decision into a Verdict.
type Rule func(Decision) (Verdict, error)

// Bands rules on ONE score answer against ordered bands.
func Bands(key string, bands []Band) Rule {
	return func(d Decision) (Verdict, error) {
		a, err := d.Score(key)
		if err != nil {
			return Verdict{}, err
		}
		name := bands[len(bands)-1].Name
		for _, b := range bands[:len(bands)-1] {
			if a.Score <= b.Max {
				name = b.Name
				break
			}
		}
		ev := map[string]float64{key: a.Score, key + ".confidence": a.Confidence}
		return Verdict{Kept: []string{name}, Evidence: ev, Model: d.Model, Calibrated: d.Calibrated,
			Reason: fmt.Sprintf("%s=%g (%s)", key, a.Score, levelAt(a, a.Score))}, nil
	}
}

func levelAt(a ScoreAnswer, s float64) string {
	i := int(s + 0.5)
	if v, ok := a.Legend[fmt.Sprint(i)]; ok {
		return v
	}
	return "?"
}

// One rules on a choice answer: the argmax option.
func One(key string) Rule {
	return func(d Decision) (Verdict, error) {
		a, err := d.Choice(key)
		if err != nil {
			return Verdict{}, err
		}
		return Verdict{Kept: []string{a.Choice}, Evidence: a.Probabilities,
			Model: d.Model, Calibrated: d.Calibrated}, nil
	}
}

// TopK rules on a choice answer: the k highest-probability options, ties by name.
func TopK(key string, k int) Rule {
	return func(d Decision) (Verdict, error) {
		a, err := d.Choice(key)
		if err != nil {
			return Verdict{}, err
		}
		names := make([]string, 0, len(a.Probabilities))
		for n := range a.Probabilities {
			names = append(names, n)
		}
		sort.Slice(names, func(i, j int) bool {
			if a.Probabilities[names[i]] != a.Probabilities[names[j]] {
				return a.Probabilities[names[i]] > a.Probabilities[names[j]]
			}
			return names[i] < names[j]
		})
		if k < len(names) {
			names = names[:k]
		}
		return Verdict{Kept: names, Evidence: a.Probabilities, Model: d.Model, Calibrated: d.Calibrated}, nil
	}
}

// AtLeast rules on a noul answer against a threshold.
func AtLeast(key string, p float64) Rule {
	return func(d Decision) (Verdict, error) {
		a, err := d.Noul(key)
		if err != nil {
			return Verdict{}, err
		}
		name := "no"
		if a.Noul >= p {
			name = "yes"
		}
		return Verdict{Kept: []string{name}, Evidence: map[string]float64{key: a.Noul},
			Model: d.Model, Calibrated: d.Calibrated}, nil
	}
}

// Judge is the composable surface: look ON this, ASK these, RULE by this.
type Judge struct {
	// On is what the judge may see — the security posture lives here.
	On func(tn.BeforeToolEvent) any
	// Ask are the pre-declared questions.
	Ask map[string]Question
	// Rule turns the Decision into a Verdict.
	Rule Rule
	// FailClosed decides what a backend error means. false ⇒ allow on error.
	FailClosed bool
}

// Rules runs the judge once.
func (j Judge) Rules(ctx context.Context, c *Classifier, ev tn.BeforeToolEvent) (Verdict, error) {
	d, err := c.Evaluate(ctx, j.On(ev), j.Ask)
	if err != nil {
		return Verdict{}, err
	}
	return j.Rule(d)
}

// AsGuardrail adapts a judge to the SHIPPED agents.Guardrail type — "" allows,
// a reason denies. It can only move a call toward ask/deny: it never returns a
// value that widens an earlier denial (the compiled rail is first-deny-wins and
// this adapter has no "allow" override to hand it).
func (j Judge) AsGuardrail(c *Classifier, onAsk func(tn.BeforeToolEvent, Verdict) string) agents.Guardrail {
	return func(ev tn.BeforeToolEvent) string {
		v, err := j.Rules(context.Background(), c, ev)
		if err != nil {
			if j.FailClosed {
				return "judge unavailable: " + err.Error()
			}
			return "" // fail-open, declared
		}
		switch band(v) {
		case "allow":
			return ""
		case "ask":
			if onAsk != nil {
				return onAsk(ev, v)
			}
			return "needs approval: " + v.Reason
		default:
			return "denied by judge: " + v.Reason
		}
	}
}

func band(v Verdict) string {
	if len(v.Kept) == 0 {
		return "deny"
	}
	return v.Kept[0]
}

// Compile is the spike's copy of golang/agents/loop.go:24-47 first-deny-wins,
// so gate 4 can be proven without reaching into the port's unexported helper.
func Compile(rails ...agents.Guardrail) agents.Guardrail {
	return func(ev tn.BeforeToolEvent) string {
		for _, g := range rails {
			if v := g(ev); v != "" && v != "allow" {
				return v
			}
		}
		return ""
	}
}
