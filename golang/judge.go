package toolnexus

// Simple judgments (SPEC §8B "Simple judgments — ask / gate", change
// add-judge-adapters): builders, Ask, Gate, Policy and Tape over any
// Classifier. The wire is unchanged — the builders produce exactly the
// Evaluate inputs a host would write by hand.

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"sync"
)

// JudgeQuestion is one named question. Build with Noul / Choice / Score and
// pass an ORDERED list; a repeated name is an error.
type JudgeQuestion struct {
	Name     string
	Question Question
}

// Noul asks "how likely is this true?". Optional crit = (whenTrue, whenFalse).
func Noul(name, instructions string, crit ...string) JudgeQuestion {
	q := NoulQuestion{Instructions: instructions}
	if len(crit) == 2 {
		q.Criteria = &NoulCriteria{True: crit[0], False: crit[1]}
	}
	return JudgeQuestion{name, q}
}

// Choice picks one option id; options maps id -> what picking it means.
func Choice(name, instructions string, options map[string]string) JudgeQuestion {
	return JudgeQuestion{name, ChoiceQuestion{Instructions: instructions, Criteria: options}}
}

// Score rates on ordered levels, lowest first.
func Score(name, instructions string, levels ...string) JudgeQuestion {
	return JudgeQuestion{name, ScoreQuestion{Instructions: instructions, Criteria: levels}}
}

// Questions turns the ordered list into the §8B map; a repeated name is an
// error naming the key, raised before any request.
func Questions(qs ...JudgeQuestion) (map[string]Question, error) {
	m := make(map[string]Question, len(qs))
	for _, q := range qs {
		if _, dup := m[q.Name]; dup {
			return nil, fmt.Errorf("duplicate question name %q", q.Name)
		}
		m[q.Name] = q.Question
	}
	return m, nil
}

// QuestionWire is the public question -> §8B wire conversion (the exact map
// the request carries for one question).
func QuestionWire(q Question) map[string]any { return q.wire() }

// State puts role next to data's fields at the top level. data may be a map
// or any JSON-serialisable struct; a non-object value goes under "data". The
// role is never copied into question instructions (ADR 0035 D7).
func State(role string, data any) map[string]any {
	s := map[string]any{}
	if b, err := json.Marshal(data); err == nil {
		var m map[string]any
		if json.Unmarshal(b, &m) == nil && m != nil {
			s = m
		} else if data != nil {
			s["data"] = data
		}
	}
	s["role"] = role
	return s
}

// MessageState is state sugar: {"context": ctx, "message": msg} plus extra.
func MessageState(ctx, message string, extra map[string]any) map[string]any {
	s := map[string]any{"context": ctx, "message": message}
	for k, v := range extra {
		s[k] = v
	}
	return s
}

// Bands split confidence into no / uncertain / yes. Cut-points are EXCLUSIVE
// on the confident side: exactly Low or exactly High is uncertain.
type Bands struct {
	Low  float64 `json:"low"`
	High float64 `json:"high"`
}

// DefaultBands is 0.30 / 0.70.
var DefaultBands = Bands{Low: 0.30, High: 0.70}

const (
	BandYes       = "yes"
	BandNo        = "no"
	BandUncertain = "uncertain"
)

// Band of one answer. Noul: value against the cuts. Choice: yes only if
// confidence > High and not near-uniform. Score: yes only if confidence > High.
func (b Bands) Band(a DecisionAnswer) string {
	switch x := a.(type) {
	case NoulAnswer:
		if x.Noul < b.Low {
			return BandNo
		}
		if x.Noul > b.High {
			return BandYes
		}
	case ChoiceAnswer:
		if !x.NearUniform && x.Confidence > b.High {
			return BandYes
		}
	case ScoreAnswer:
		if x.Confidence > b.High {
			return BandYes
		}
	}
	return BandUncertain
}

func pickBands(bands []Bands) Bands {
	if len(bands) > 0 && bands[0] != (Bands{}) {
		return bands[0]
	}
	return DefaultBands
}

// JudgeAnswer is a classifier answer plus its band. Named JudgeAnswer because
// §10 owns Answer. Sure is meaningful for choice/score (the §8B "sure" bool);
// Band carries yes|no|uncertain for noul (and yes|uncertain for the others).
type JudgeAnswer struct {
	DecisionAnswer
	Band string
	Sure bool
}

// Value is the one number: noul probability, score value, choice confidence.
func (a JudgeAnswer) Value() float64 {
	switch x := a.DecisionAnswer.(type) {
	case NoulAnswer:
		return x.Noul
	case ScoreAnswer:
		return x.Score
	case ChoiceAnswer:
		return x.Confidence
	}
	return 0
}

// Choice is the picked option id ("" for noul/score answers).
func (a JudgeAnswer) Choice() string {
	if x, ok := a.DecisionAnswer.(ChoiceAnswer); ok {
		return x.Choice
	}
	return ""
}

// MarshalJSON writes the answer FLAT: its wire fields plus "type", then
// "band" (noul) or "sure" (choice/score) — never a nested DecisionAnswer.
func (a JudgeAnswer) MarshalJSON() ([]byte, error) {
	m, err := answerWire(a.DecisionAnswer)
	if err != nil {
		return nil, err
	}
	if _, ok := a.DecisionAnswer.(NoulAnswer); ok {
		m["band"] = a.Band
	} else {
		m["sure"] = a.Sure
	}
	return json.Marshal(m)
}

// answerWire is one answer in wire shape (fields + "type").
func answerWire(a DecisionAnswer) (map[string]any, error) {
	if a == nil {
		return nil, fmt.Errorf("judge: nil answer")
	}
	b, err := json.Marshal(a)
	if err != nil {
		return nil, err
	}
	m := map[string]any{}
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, err
	}
	m["type"] = a.AnswerType()
	return m, nil
}

// Answers by question name. A question the model did not answer is absent.
type Answers map[string]JudgeAnswer

func bandAll(d Decision, b Bands) Answers {
	out := make(Answers, len(d.Answers))
	for k, a := range d.Answers {
		band := b.Band(a)
		out[k] = JudgeAnswer{a, band, band == BandYes}
	}
	return out
}

// Ask evaluates the questions once and bands every answer. bands is optional
// (default 0.30 / 0.70).
func Ask(ctx context.Context, c *Classifier, state any, qs []JudgeQuestion, bands ...Bands) (Answers, error) {
	m, err := Questions(qs...)
	if err != nil {
		return nil, err
	}
	d, err := c.Evaluate(ctx, state, m)
	if err != nil {
		return nil, err
	}
	return bandAll(d, pickBands(bands)), nil
}

// Rule is one gate. Exactly one of Below / AtLeast / Is applies.
type Rule struct {
	Question string   `json:"question"`
	Below    *float64 `json:"below,omitempty"`
	AtLeast  *float64 `json:"at_least,omitempty"`
	Is       string   `json:"is,omitempty"`
	Action   string   `json:"action"`
	Target   string   `json:"target,omitempty"`
}

// Outcome: an Action fired, or Escalated with a §10 input Request, or neither
// (Action "" — fall through).
type Outcome struct {
	Action    string
	Target    string
	Rule      *Rule
	Answers   Answers
	Escalated bool
	Request   *Request
}

// Gate asks once and applies rules first-match. A rule whose answer is
// missing, uncertain or not sure ESCALATES (needs_input) instead of deciding.
func Gate(ctx context.Context, c *Classifier, state any, qs []JudgeQuestion, rules []Rule, bands ...Bands) (Outcome, error) {
	a, err := Ask(ctx, c, state, qs, bands...)
	if err != nil {
		return Outcome{}, err
	}
	return ApplyRules(a, rules), nil
}

// ApplyRules is the pure half of Gate.
func ApplyRules(a Answers, rules []Rule) Outcome {
	out := Outcome{Answers: a}
	for i := range rules {
		r := &rules[i]
		fired, reason := checkRule(a, r)
		if reason != "" {
			out.Escalated, out.Action, out.Rule = true, "needs_input", r
			out.Request = &Request{
				ID:     fmt.Sprintf("gate:%d:%s", i, r.Question),
				Kind:   "input",
				Prompt: fmt.Sprintf("Classifier is unsure about %q (%s). Decide rule %d (%s).", r.Question, reason, i, r.Action),
				Data:   map[string]any{"question": r.Question, "reason": reason, "answers": rawAnswers(a), "rule": *r},
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

func rawAnswers(a Answers) map[string]DecisionAnswer {
	raw := make(map[string]DecisionAnswer, len(a))
	for k, v := range a {
		raw[k] = v.DecisionAnswer
	}
	return raw
}

// checkRule returns (fired, escalateReason); a non-empty reason means ask a human.
func checkRule(as Answers, r *Rule) (bool, string) {
	a, ok := as[r.Question]
	if !ok {
		return false, fmt.Sprintf("missing answer %q", r.Question)
	}
	if a.Band == BandUncertain {
		return false, "uncertain " + a.AnswerType() + " answer"
	}
	switch a.DecisionAnswer.(type) {
	case NoulAnswer, ScoreAnswer:
		v := a.Value()
		switch {
		case r.Below != nil:
			return v < *r.Below, ""
		case r.AtLeast != nil:
			return v >= *r.AtLeast, ""
		}
	case ChoiceAnswer:
		if r.Is != "" {
			return a.Choice() == r.Is, ""
		}
	}
	return false, fmt.Sprintf("rule does not fit %s answer", a.AnswerType())
}

// Policy is Gate with a DECLARED fall-through. Default names the action when
// no rule fires; Default "" escalates with reason "no rule fired". With
// SkipUncertain an uncertain answer skips its rule instead of escalating.
type Policy struct {
	Rules         []Rule
	Default       string
	Bands         Bands // zero value -> DefaultBands
	SkipUncertain bool
}

// Decide applies the rules, then the declared default.
func (p Policy) Decide(a Answers) Outcome {
	rules := p.Rules
	if p.SkipUncertain {
		rules = nil
		for _, r := range p.Rules {
			if x, ok := a[r.Question]; ok && x.Band == BandUncertain {
				continue
			}
			rules = append(rules, r)
		}
	}
	o := ApplyRules(a, rules)
	if o.Action != "" || o.Escalated {
		return o
	}
	if p.Default != "" {
		o.Action = p.Default
		return o
	}
	o.Escalated, o.Action = true, "needs_input"
	o.Request = &Request{ID: "gate:default", Kind: "input",
		Prompt: "No rule fired: the answers are confident but in between. Decide.",
		Data:   map[string]any{"reason": "no rule fired", "answers": rawAnswers(a)}}
	return o
}

// Gate asks once and decides.
func (p Policy) Gate(ctx context.Context, c *Classifier, state any, qs []JudgeQuestion) (Outcome, error) {
	a, err := Ask(ctx, c, state, qs, p.Bands)
	if err != nil {
		return Outcome{}, err
	}
	return p.Decide(a), nil
}

// Recorded builds one static-corpus entry. response is the backend body: a
// []byte / json.RawMessage verbatim, or any value marshalled to JSON
// (e.g. map[string]any{"answers": ...}).
func Recorded(state any, questions map[string]Question, response any) RecordedDecision {
	var raw []byte
	switch r := response.(type) {
	case []byte:
		raw = r
	case json.RawMessage:
		raw = r
	default:
		raw, _ = json.Marshal(r)
	}
	return RecordedDecision{State: state, Questions: questions, Response: raw}
}

// StaticClassifier is the one-line static classifier over recorded decisions.
func StaticClassifier(decisions ...RecordedDecision) (*Classifier, error) {
	return CreateClassifier(ClassifierOptions{Style: StyleStatic, Decisions: decisions})
}

// Tape records live decisions BY CALL NAME and replays them offline. The call
// name rides on the context (WithCallName); a replay miss names the key.
type Tape struct {
	mu        sync.Mutex
	Decisions map[string]json.RawMessage `json:"decisions"`
}

type callNameKey struct{}

// WithCallName names the next Evaluate for a Tape ("triage", "plan").
func WithCallName(ctx context.Context, name string) context.Context {
	return context.WithValue(ctx, callNameKey{}, name)
}

func callName(ctx context.Context) string { s, _ := ctx.Value(callNameKey{}).(string); return s }

// LoadTape reads a tape file.
func LoadTape(path string) (*Tape, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	t := &Tape{}
	return t, json.Unmarshal(b, t)
}

// Save writes the tape (sorted keys, stable diffs).
func (t *Tape) Save(path string) error {
	t.mu.Lock()
	b, err := json.MarshalIndent(t, "", "  ")
	t.mu.Unlock()
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

// Recording wraps a live classifier: every call is forwarded and taped.
func (t *Tape) Recording(live *Classifier) (*Classifier, error) {
	return CreateClassifier(ClassifierOptions{Style: StyleCustom,
		Evaluate: func(ctx context.Context, state any, qs map[string]Question) (Decision, error) {
			d, err := live.Evaluate(ctx, state, qs)
			if err != nil {
				return d, err
			}
			raw, err := encodeDecision(d)
			if err != nil {
				return d, err
			}
			t.mu.Lock()
			if t.Decisions == nil {
				t.Decisions = map[string]json.RawMessage{}
			}
			t.Decisions[callName(ctx)] = raw
			t.mu.Unlock()
			return d, nil
		}})
}

// Replayer answers from the tape by call name, with no network.
func (t *Tape) Replayer() (*Classifier, error) {
	return CreateClassifier(ClassifierOptions{Style: StyleCustom,
		Evaluate: func(ctx context.Context, _ any, _ map[string]Question) (Decision, error) {
			k := callName(ctx)
			t.mu.Lock()
			raw, ok := t.Decisions[k]
			t.mu.Unlock()
			if !ok {
				return Decision{}, fmt.Errorf("tape: no recording for call %q", k)
			}
			var d Decision
			return d, json.Unmarshal(raw, &d)
		}})
}

// encodeDecision writes a Decision back in wire shape.
func encodeDecision(d Decision) (json.RawMessage, error) {
	keys := make([]string, 0, len(d.Answers))
	for k := range d.Answers {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	ans := make(map[string]any, len(keys))
	for _, k := range keys {
		m, err := answerWire(d.Answers[k])
		if err != nil {
			return nil, err
		}
		ans[k] = m
	}
	return json.Marshal(map[string]any{"model": d.Model, "answers": ans, "usage": d.Usage, "calibrated": d.Calibrated})
}
