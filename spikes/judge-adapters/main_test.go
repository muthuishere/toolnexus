package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	. "toolnexus.spike/judgeadapters/judge"

	tn "github.com/muthuishere/toolnexus/golang"
)

type row struct {
	name       string
	answers    map[string]any // wire "answers" object
	wantBase   string         // baseline action ("" fall through, "ERR" step error)
	wantAdapt  string         // adapter action
	wantTarget string
	escalated  bool
}

func noul(p float64) map[string]any { return map[string]any{"type": "noul", "noul": p} }
func choice(c string, conf float64, probs map[string]float64) map[string]any {
	return map[string]any{"type": "choice", "choice": c, "confidence": conf, "probabilities": probs}
}

var sharp = map[string]float64{"pricing": 0.9, "checkout": 0.08, "frontend": 0.01, "infra": 0.01}
var split = map[string]float64{"pricing": 0.40, "checkout": 0.35, "frontend": 0.15, "infra": 0.10}
var flat = map[string]float64{"pricing": 0.27, "checkout": 0.25, "frontend": 0.24, "infra": 0.24}

var rows = []row{
	// the three rows from the analyst
	{"unfixable", map[string]any{"fixable": noul(0.12), "component": choice("pricing", 0.85, sharp)}, "fail", "fail", "", false},
	{"sure-pricing", map[string]any{"fixable": noul(0.9), "component": choice("pricing", 0.85, sharp)}, "skip_to", "skip_to", "fix-pricing", false},
	{"unsure-both", map[string]any{"fixable": noul(0.55), "component": choice("pricing", 0.40, split)}, "skip_to", "needs_input", "", true},
	// edges
	{"noul-at-low", map[string]any{"fixable": noul(0.30), "component": choice("checkout", 0.9, sharp)}, "", "needs_input", "", true},
	{"noul-at-high", map[string]any{"fixable": noul(0.70), "component": choice("checkout", 0.9, sharp)}, "", "needs_input", "", true},
	{"noul-just-above-high", map[string]any{"fixable": noul(0.71), "component": choice("checkout", 0.9, sharp)}, "", "", "", false},
	{"choice-conf-at-high", map[string]any{"fixable": noul(0.9), "component": choice("pricing", 0.70, sharp)}, "skip_to", "needs_input", "", true},
	{"near-uniform-high-conf", map[string]any{"fixable": noul(0.9), "component": choice("pricing", 0.80, flat)}, "skip_to", "needs_input", "", true},
	{"missing-component", map[string]any{"fixable": noul(0.9)}, "ERR", "needs_input", "", true},
}

func stateFor(r row) map[string]any {
	s := map[string]any{"case": r.name}
	for k, v := range bug {
		s[k] = v
	}
	return s
}

func staticClassifier(t *testing.T) *tn.Classifier {
	t.Helper()
	var rec []tn.RecordedDecision
	for _, r := range rows {
		body, _ := json.Marshal(map[string]any{"model": "jev-static", "answers": r.answers,
			"usage": map[string]any{"input_tokens": 1, "output_tokens": 1}})
		rec = append(rec, tn.RecordedDecision{State: stateFor(r), Questions: questions(), Response: body})
	}
	c, err := tn.CreateClassifier(tn.ClassifierOptions{Style: tn.StyleStatic, Model: "jev-static", Decisions: rec})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestGateVsBaseline(t *testing.T) {
	c := staticClassifier(t)
	ctx := context.Background()
	for _, r := range rows {
		t.Run(r.name, func(t *testing.T) {
			d, err := c.Evaluate(ctx, stateFor(r), questions())
			if err != nil {
				t.Fatal(err)
			}
			ba, _, berr := baselineApply(d, questionTypes, rules)
			if berr != nil {
				ba = "ERR"
			}
			if ba != r.wantBase {
				t.Errorf("baseline = %q, want %q", ba, r.wantBase)
			}
			o, err := Gate(ctx, c, stateFor(r), qs, rules, DefaultBands)
			if err != nil {
				t.Fatal(err)
			}
			if o.Action != r.wantAdapt || o.Target != r.wantTarget || o.Escalated != r.escalated {
				t.Errorf("adapter = %+v, want %q/%q esc=%v", o, r.wantAdapt, r.wantTarget, r.escalated)
			}
			if o.Escalated {
				rq := o.Request
				if rq == nil || rq.Kind != "input" || rq.ID == "" || rq.Data["answers"] == nil || rq.Data["reason"] == "" {
					t.Fatalf("escalation request malformed: %+v", rq)
				}
				// the §10 request must round-trip as wire JSON with the probabilities in it
				b, err := json.Marshal(rq)
				if err != nil {
					t.Fatal(err)
				}
				if _, hasChoice := r.answers["component"]; hasChoice && !strings.Contains(string(b), `"probabilities"`) {
					t.Errorf("request lacks probabilities: %s", b)
				}
				t.Logf("escalate: %s", rq.Prompt)
			}
		})
	}
}

// The real wfnexus fixability gate is a score. Pins the score-confidence branch of check.
func TestGateScore(t *testing.T) {
	sq := []Q{Score("fixability", "How fixable is this bug from the report alone?",
		"needs product input", "needs investigation", "fixable from the report")}
	qs, _ := Questions(sq...)
	sr := []Rule{{Question: "fixability", AtLeast: f(1.5), Action: "skip_to", Target: "draft-pr"}}
	score := func(s, conf float64) map[string]any {
		return map[string]any{"type": "score", "score": s, "confidence": conf,
			"probabilities": map[string]float64{"0": 0.05, "1": 0.15, "2": 0.8},
			"legend":        map[string]string{"0": "needs product input", "1": "needs investigation", "2": "fixable from the report"}}
	}
	cases := []struct {
		name, want string
		ans        map[string]any
		esc        bool
	}{
		{"sure-high", "skip_to", score(1.8, 0.9), false},
		{"sure-low", "", score(0.4, 0.9), false},
		{"conf-at-high", "needs_input", score(1.8, 0.70), true},
		{"unsure-high", "needs_input", score(1.8, 0.5), true},
	}
	var rec []tn.RecordedDecision
	for _, c := range cases {
		body, _ := json.Marshal(map[string]any{"model": "jev-static", "answers": map[string]any{"fixability": c.ans},
			"usage": map[string]any{"input_tokens": 1, "output_tokens": 1}})
		rec = append(rec, tn.RecordedDecision{State: map[string]any{"case": c.name}, Questions: qs, Response: body})
	}
	cl, err := tn.CreateClassifier(tn.ClassifierOptions{Style: tn.StyleStatic, Model: "jev-static", Decisions: rec})
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			o, err := Gate(context.Background(), cl, map[string]any{"case": c.name}, sq, sr, DefaultBands)
			if err != nil {
				t.Fatal(err)
			}
			if o.Action != c.want || o.Escalated != c.esc {
				t.Errorf("got %q esc=%v, want %q esc=%v", o.Action, o.Escalated, c.want, c.esc)
			}
		})
	}
}
