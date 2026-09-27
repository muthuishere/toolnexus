package judge

import (
	"context"
	"fmt"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Q is one named question. Build with Noul / Choice / Score; pass an ordered list.
type Q struct {
	Name string
	Q    tn.Question
}

// Noul asks "how likely is this true?". Optional crit = (whenTrue, whenFalse).
func Noul(name, instr string, crit ...string) Q {
	q := tn.NoulQuestion{Instructions: instr}
	if len(crit) == 2 {
		q.Criteria = &tn.NoulCriteria{True: crit[0], False: crit[1]}
	}
	return Q{name, q}
}

// Choice picks one option id; options map id -> what picking it means.
func Choice(name, instr string, options map[string]string) Q {
	return Q{name, tn.ChoiceQuestion{Instructions: instr, Criteria: options}}
}

// Score rates on ordered levels, lowest first.
func Score(name, instr string, levels ...string) Q {
	return Q{name, tn.ScoreQuestion{Instructions: instr, Criteria: levels}}
}

// Questions turns the list into the §8B map; a repeated name is an error.
func Questions(qs ...Q) (map[string]tn.Question, error) {
	m := make(map[string]tn.Question, len(qs))
	for _, q := range qs {
		if _, dup := m[q.Name]; dup {
			return nil, fmt.Errorf("duplicate question name %q", q.Name)
		}
		m[q.Name] = q.Q
	}
	return m, nil
}

// Msg is state sugar: {"context": ctx, "message": msg} plus any extra keys.
func Msg(ctx, msg string, extra map[string]any) map[string]any {
	s := map[string]any{"context": ctx, "message": msg}
	for k, v := range extra {
		s[k] = v
	}
	return s
}

// Bands split confidence into no / uncertain / yes. Cut points are EXCLUSIVE on
// the confident side: exactly Low or exactly High is uncertain.
type Bands struct{ Low, High float64 }

var DefaultBands = Bands{Low: 0.30, High: 0.70}

const (
	Yes       = "yes"
	No        = "no"
	Uncertain = "uncertain"
)

// Band of one answer. Noul: its value against the cuts. Choice: yes only if
// confidence > High and not near-uniform. Score: yes only if confidence > High.
func (b Bands) Band(a tn.DecisionAnswer) string {
	switch x := a.(type) {
	case tn.NoulAnswer:
		if x.Noul < b.Low {
			return No
		}
		if x.Noul > b.High {
			return Yes
		}
	case tn.ChoiceAnswer:
		if !x.NearUniform && x.Confidence > b.High {
			return Yes
		}
	case tn.ScoreAnswer:
		if x.Confidence > b.High {
			return Yes
		}
	}
	return Uncertain
}

// Answer is a classifier answer plus its band.
type Answer struct {
	tn.DecisionAnswer
	Band string
}

// Answers by question name. A question the model did not answer is absent.
type Answers map[string]Answer

// Ask evaluates the questions once and bands every answer. bands is optional
// (default 0.30 / 0.70).
func Ask(ctx context.Context, c *tn.Classifier, state any, qs []Q, bands ...Bands) (Answers, error) {
	b := DefaultBands
	if len(bands) > 0 {
		b = bands[0]
	}
	m, err := Questions(qs...)
	if err != nil {
		return nil, err
	}
	d, err := c.Evaluate(ctx, state, m)
	if err != nil {
		return nil, err
	}
	out := make(Answers, len(d.Answers))
	for k, a := range d.Answers {
		out[k] = Answer{a, b.Band(a)}
	}
	return out, nil
}

// Value is the one number a rule reads, so callers never type-assert by hand:
// noul -> probability, score -> expected level, choice -> confidence.
func (a Answer) Value() float64 {
	switch x := a.DecisionAnswer.(type) {
	case tn.NoulAnswer:
		return x.Noul
	case tn.ScoreAnswer:
		return x.Score
	case tn.ChoiceAnswer:
		return x.Confidence
	}
	return 0
}

// Choice is the picked option id ("" for noul/score answers).
func (a Answer) Choice() string {
	if x, ok := a.DecisionAnswer.(tn.ChoiceAnswer); ok {
		return x.Choice
	}
	return ""
}
