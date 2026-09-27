package main

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

type jq struct {
	Kind, Name, Instructions string
	Options                  map[string]string
	Levels                   []string
}

func (q jq) build() Q {
	switch q.Kind {
	case "choice":
		return Choice(q.Name, q.Instructions, q.Options)
	case "score":
		return Score(q.Name, q.Instructions, q.Levels...)
	}
	return Noul(q.Name, q.Instructions)
}

// wire renders the §8B question map as the shared JSON shape.
func wire(m map[string]tn.Question) any {
	out := map[string]any{}
	for k, q := range m {
		w := map[string]any{}
		switch x := q.(type) {
		case tn.NoulQuestion:
			w["type"], w["instructions"] = "noul", x.Instructions
			if x.Criteria != nil {
				w["criteria"] = map[string]any{"true": x.Criteria.True, "false": x.Criteria.False}
			}
		case tn.ChoiceQuestion:
			w["type"], w["instructions"], w["criteria"] = "choice", x.Instructions, x.Criteria
		case tn.ScoreQuestion:
			w["type"], w["instructions"], w["criteria"] = "score", x.Instructions, x.Criteria
		}
		out[k] = w
	}
	return norm(out)
}

func norm(v any) any { b, _ := json.Marshal(v); var o any; _ = json.Unmarshal(b, &o); return o }

func load(t *testing.T, p string, v any) {
	b, err := os.ReadFile(p)
	if err == nil {
		err = json.Unmarshal(b, v)
	}
	if err != nil {
		t.Fatal(err)
	}
}

func TestSharedStateCases(t *testing.T) {
	var f struct {
		Cases []struct {
			Name    string
			State   map[string]any
			Context *struct {
				Context, Message string
				Extra            map[string]any
			}
			Questions []jq
			WantState map[string]any
			WantQs    map[string]any `json:"wantQuestions"`
			WantError string
		}
	}
	load(t, "shared/state-cases.json", &f)
	for _, c := range f.Cases {
		t.Run(c.Name, func(t *testing.T) {
			state := c.State
			if c.Context != nil {
				state = Msg(c.Context.Context, c.Context.Message, c.Context.Extra)
			}
			var list []Q
			for _, q := range c.Questions {
				list = append(list, q.build())
			}
			m, err := Questions(list...)
			if c.WantError != "" {
				if err == nil || err.Error() != c.WantError {
					t.Fatalf("err = %v, want %q", err, c.WantError)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(norm(state), norm(c.WantState)) {
				t.Errorf("state = %v", state)
			}
			if got := wire(m); !reflect.DeepEqual(got, norm(c.WantQs)) {
				t.Errorf("questions = %v", got)
			}
		})
	}
}

func TestSharedGateCases(t *testing.T) {
	var f struct {
		Questions map[string]struct {
			Type, Instructions string
			Criteria           json.RawMessage
		}
		Rules []struct {
			Question, Is, Action, Target string
			Below                        *float64
			AtLeast                      *float64 `json:"at_least"`
		}
		Cases []struct {
			Name    string
			Answers map[string]any
			Bands   *Bands
			Want    struct {
				Action, Target string
				Escalated      bool
			}
		}
	}
	load(t, "shared/gate-cases.json", &f)
	var list []Q
	for _, name := range []string{"fixable", "component", "fixability"} {
		q := f.Questions[name]
		switch q.Type {
		case "noul":
			var c struct{ True, False string }
			_ = json.Unmarshal(q.Criteria, &c)
			list = append(list, Noul(name, q.Instructions, c.True, c.False))
		case "choice":
			var c map[string]string
			_ = json.Unmarshal(q.Criteria, &c)
			list = append(list, Choice(name, q.Instructions, c))
		case "score":
			var c []string
			_ = json.Unmarshal(q.Criteria, &c)
			list = append(list, Score(name, q.Instructions, c...))
		}
	}
	var rules []Rule
	for _, r := range f.Rules {
		rules = append(rules, Rule{Question: r.Question, Below: r.Below, AtLeast: r.AtLeast, Is: r.Is, Action: r.Action, Target: r.Target})
	}
	m, _ := Questions(list...)
	var rec []tn.RecordedDecision
	for _, c := range f.Cases {
		body, _ := json.Marshal(map[string]any{"model": "jev-static", "answers": c.Answers,
			"usage": map[string]any{"input_tokens": 1, "output_tokens": 1}})
		rec = append(rec, tn.RecordedDecision{State: map[string]any{"case": c.Name}, Questions: m, Response: body})
	}
	cl, err := tn.CreateClassifier(tn.ClassifierOptions{Style: tn.StyleStatic, Model: "jev-static", Decisions: rec})
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range f.Cases {
		t.Run(c.Name, func(t *testing.T) {
			b := DefaultBands
			if c.Bands != nil {
				b = *c.Bands
			}
			o, err := Gate(context.Background(), cl, map[string]any{"case": c.Name}, list, rules, b)
			if err != nil {
				t.Fatal(err)
			}
			if o.Action != c.Want.Action || o.Target != c.Want.Target || o.Escalated != c.Want.Escalated {
				t.Errorf("got %q/%q esc=%v, want %+v", o.Action, o.Target, o.Escalated, c.Want)
			}
			if o.Escalated && (o.Request == nil || o.Request.Kind != "input" || o.Request.Data["question"] == nil ||
				o.Request.Data["reason"] == "" || o.Request.Data["answers"] == nil) {
				t.Errorf("bad §10 request: %+v", o.Request)
			}
		})
	}
}
