// testscout: write tests that matter, not tests for coverage.
//
//	1 Understand (code)       coverage per function — code counts, the classifier never does
//	2 Triage     (classifier) how much would a test here protect users?
//	3 Gate       (code)       keep sure+high, skip sure+low, escalate the unsure to a human (§10)
//	4 Write      (LLM)        draft a scenario test per kept function (recorded offline)
//	5 Verify     (code+clf)   run it, measure coverage delta, reject tests that only chase lines
//
// Offline (default) is hermetic. Live: SCOUT_LIVE=1 with OPENROUTER_API_KEY set
// (read by the library by NAME; this program never reads or prints it).
package main

import (
	"context"
	"fmt"
	"os"

	tn "github.com/muthuishere/toolnexus/golang"
	j "toolnexus.spike/judgeadapters/judge"
)

// Fn is one function of the target package, as code sees it.
type Fn struct {
	Name, Sig, Body string
	Cov             float64 // % of statements covered by the existing tests
}

// Verdict is what the pipeline decided about one function.
type Verdict struct {
	Fn
	Triage string // keep | skip | needs_input
	Reason string
	Test   string // test verdict: accepted | rejected | needs_input | ""
	Note   string
}

func f(v float64) *float64 { return &v }

// ---- Stage 2 + 3: the questions and the gate that reads them.
var triageQs = []j.Q{
	j.Score("value", "How much would a test here protect users?",
		"trivial accessor/formatting", "internal glue", "business rule with branches", "money or user-facing error path"),
	j.Noul("noticed", "A bug here would be noticed by a customer."),
}
var triageRules = []j.Rule{
	{Question: "value", Below: f(1.5), Action: "skip"},    // trivial or glue
	{Question: "noticed", Below: f(0.30), Action: "skip"}, // nobody would see it
	{Question: "noticed", AtLeast: f(0.70), Action: "keep"},
}

// ---- Stage 5: the question that separates a real test from coverage-chasing.
var verifyQs = []j.Q{j.Noul("asserts_behaviour",
	"The test asserts behaviour a user would notice, not just executes lines.")}
var verifyRules = []j.Rule{
	{Question: "asserts_behaviour", Below: f(0.30), Action: "rejected"},
	{Question: "asserts_behaviour", AtLeast: f(0.70), Action: "accepted"},
}

func triageState(fn Fn) map[string]any {
	return map[string]any{"func": fn.Name, "signature": fn.Sig, "source": fn.Body, "coverage_pct": fn.Cov}
}
func verifyState(fn Fn, test string) map[string]any {
	return map[string]any{"func": fn.Name, "source": fn.Body, "test": test}
}

// Stage 1 — Understand. Pure code: run the existing tests, read per-func coverage.
func understand(shop string) ([]Fn, float64, error) {
	fns, err := parseFuncs(shop)
	if err != nil {
		return nil, 0, err
	}
	cov, total, _, err := coverage(shop)
	if err != nil {
		return nil, 0, err
	}
	for i := range fns {
		fns[i].Cov = cov[fns[i].Name]
	}
	return fns, total, nil
}

// Stages 2+3 — Triage and Gate. One Ask per uncovered function; the gate decides.
func triage(ctx context.Context, c *tn.Classifier, fn Fn) (Verdict, error) {
	v := Verdict{Fn: fn}
	if fn.Cov == 100 {
		v.Triage, v.Reason = "skip", "already covered"
		return v, nil
	}
	o, err := j.Gate(ctx, c, triageState(fn), triageQs, triageRules, j.DefaultBands)
	if err != nil {
		return v, err
	}
	switch {
	case o.Escalated:
		v.Triage, v.Reason = "needs_input", "needs a human: "+o.Request.Prompt
	case o.Action == "":
		v.Triage, v.Reason = "needs_input", "needs a human: no rule fired (value high, customer impact unsure)"
	default:
		v.Triage = o.Action
		v.Reason = fmt.Sprintf("value=%.1f noticed=%.2f", o.Answers["value"].DecisionAnswer.(tn.ScoreAnswer).Score,
			o.Answers["noticed"].DecisionAnswer.(tn.NoulAnswer).Noul)
	}
	return v, nil
}

// Stage 5 — Verify. Code runs the test and measures coverage; the classifier
// only judges whether the test asserts anything a user would notice.
func verify(ctx context.Context, c *tn.Classifier, shop string, before float64, v *Verdict, test string) error {
	passed, after, err := runWith(shop, v.Name, test)
	if err != nil {
		v.Test, v.Note = "rejected", "does not compile: "+err.Error()
		return nil
	}
	o, err := j.Gate(ctx, c, verifyState(v.Fn, test), verifyQs, verifyRules, j.DefaultBands)
	if err != nil {
		return err
	}
	v.Test = o.Action
	if o.Escalated || v.Test == "" {
		v.Test = "needs_input"
	}
	v.Note = fmt.Sprintf("coverage %.1f%% -> %.1f%%", before, after)
	if v.Test == "rejected" {
		v.Note += ", coverage-only: asserts nothing"
	} else if !passed {
		v.Note += ", FAILS on current code: caught a real bug"
	}
	return nil
}

// run is the whole pipeline; writeTest is stage 4 (live LLM or recorded file).
func run(ctx context.Context, c *tn.Classifier, shop string, writeTest func(Fn) (string, error)) ([]Verdict, error) {
	fns, before, err := understand(shop)
	if err != nil {
		return nil, err
	}
	var out []Verdict
	for _, fn := range fns {
		v, err := triage(ctx, c, fn)
		if err != nil {
			return nil, err
		}
		if v.Triage == "keep" {
			test, err := writeTest(fn)
			if err != nil {
				return nil, err
			}
			if err := verify(ctx, c, shop, before, &v, test); err != nil {
				return nil, err
			}
		}
		out = append(out, v)
	}
	return out, nil
}

func main() {
	ctx, shop := context.Background(), "testdata/shop"
	c, write, err := offline(shop)
	if os.Getenv("SCOUT_LIVE") == "1" {
		c, write, err = live(ctx)
	}
	if err == nil {
		var vs []Verdict
		if vs, err = run(ctx, c, shop, write); err == nil {
			printTable(vs)
			return
		}
	}
	fmt.Fprintln(os.Stderr, "testscout:", err)
	os.Exit(1)
}
