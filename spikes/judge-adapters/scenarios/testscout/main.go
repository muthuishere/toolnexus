// testscout: write tests that matter, not tests for coverage.
//
//	1 Understand (code)        coverage per function — code counts, the classifier never does
//	2 Triage     (classifier)  ONE call scores every uncovered func: value + would a customer notice
//	3 Plan       (classifier)  ONE call picks the scenario to test first, per func, from a bug report
//	4 Gate       (code+clf)    keep / skip / needs_input; unsure -> re-ask up to 3 views, escalate on split
//	5 Write      (LLM)         the toolnexus client drafts the test (OpenRouter); recorded offline
//	6 Verify     (code+clf)    go test, coverage delta, mutants must die; classifier: asserts? named?
//
// Live by default (TypeSafe systemone, key by NAME: TYPESAFE_API_KEY; writer on
// OpenRouter: OPENROUTER_API_KEY). --record writes testdata/recorded so `go test`
// replays the real answers. --offline replays them here too.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"strings"

	tn "github.com/muthuishere/toolnexus/golang"
	j "toolnexus.spike/judgeadapters/judge"
)

// The bug report the Plan stage reads (state, not code).
const bugReport = `checkout-500: customers pasting "SAVE10 " (trailing space) from the promo email get HTTP 500 at checkout instead of 10% off.`

// Fn is one function of the target package, as code sees it.
type Fn struct {
	Name, Sig, Body string
	Cov             float64 // % statements covered by the existing tests
}

// Verdict is what the pipeline decided about one function.
type Verdict struct {
	Fn
	Triage, Plan, Test, Why string
}

func f(v float64) *float64 { return &v }

// ---- 2 Triage: two questions per function, all functions in ONE Evaluate.
func triageQs(name string) []j.Q {
	return []j.Q{
		j.Score("value:"+name, "For function "+name+": how much would a test here protect users?",
			"trivial accessor or formatting", "internal glue", "business rule with branches", "money or user-facing error path"),
		j.Noul("noticed:"+name, "If function "+name+" had a bug, would a customer see it?",
			"a customer sees a wrong price, a wrong or missing error message, or a failed checkout",
			"only other code sees it; the customer-visible result stays correct"),
	}
}

// ---- 3 Plan: options are described by CONSEQUENCE, not by label (ADR 0021).
func planQ(name string) j.Q {
	return j.Choice("scenario:"+name, "For function "+name+": which scenario should its FIRST test cover?", map[string]string{
		"happy_path": "the normal, valid input a customer sends most often returns the right result",
		"boundary":   "values at the edge of an allowed range (0, max, rounding halves) behave correctly",
		"invalid":    "bad input is rejected with the error message a customer actually sees",
		"regression": "the exact input from an open bug report no longer breaks checkout",
	})
}

// ---- 4 Gate: rules over the per-function answers; "no rule fired" escalates.
var triagePolicy = j.Policy{SkipUncertain: true, Rules: []j.Rule{
	{Question: "value", Below: f(1.5), Action: "skip"},      // trivial or glue
	{Question: "noticed", Below: f(0.30), Action: "skip"},   // nobody would see it
	{Question: "value", AtLeast: f(2.5), Action: "keep"},    // money or user-facing error path
	{Question: "noticed", AtLeast: f(0.90), Action: "keep"}, // a customer surely sees it
}} // Default "" -> needs_input

// ---- 6 Verify: two nouls; code has already vetoed compile/mutation failures.
var verifyQs = []j.Q{
	j.Noul("asserts_behaviour", "The test asserts behaviour a customer would notice, not just executes lines."),
	j.Noul("name_describes_scenario", "The test's name (and subtest names) say which customer scenario is checked."),
}
var verifyPolicy = j.Policy{Rules: []j.Rule{
	{Question: "asserts_behaviour", Below: f(0.30), Action: "rejected"},
	{Question: "name_describes_scenario", Below: f(0.30), Action: "rejected"},
	{Question: "asserts_behaviour", AtLeast: f(0.70), Action: "accepted"},
}}

// The role frames every judgment in this pipeline; the function is the data.
const role = "You are a senior Go engineer on a shop's checkout team, deciding which untested " +
	"functions deserve a test and whether a proposed test really protects customers."

func funcData(fn Fn) map[string]any {
	return map[string]any{"name": fn.Name, "signature": fn.Sig, "source": fn.Body, "coverage_pct": fn.Cov}
}
func funcState(fn Fn) map[string]any { return j.State(role, funcData(fn)) }

// Stage 1 — Understand. Pure code: run the existing tests, read per-func coverage.
func understand(shop string) ([]Fn, float64, error) {
	fns, err := parseFuncs(shop)
	if err != nil {
		return nil, 0, err
	}
	cov, total, _, err := coverage(shop)
	for i := range fns {
		fns[i].Cov = cov[fns[i].Name]
	}
	return fns, total, err
}

// Stage 2 — Triage. Speculative fan-out: every uncovered function's questions
// ride ONE Evaluate over one shared state, then are split back per function.
func triage(ctx context.Context, c *tn.Classifier, fns []Fn) (map[string]j.Answers, error) {
	var qs []j.Q
	var st []any
	for _, fn := range fns {
		qs, st = append(qs, triageQs(fn.Name)...), append(st, funcState(fn))
	}
	a, err := j.Ask(j.WithKey(ctx, "triage"), c, map[string]any{"package": "shop (checkout)", "functions": st}, qs)
	return perFunc(a), err
}

// Stage 3 — Plan. One Choice per function, one Evaluate, the bug report in state.
func plan(ctx context.Context, c *tn.Classifier, fns []Fn) (map[string]j.Answers, error) {
	var qs []j.Q
	var st []any
	for _, fn := range fns {
		qs, st = append(qs, planQ(fn.Name)), append(st, funcState(fn))
	}
	a, err := j.Ask(j.WithKey(ctx, "plan"), c, map[string]any{"open_bug_reports": []string{bugReport}, "functions": st}, qs)
	return perFunc(a), err
}

// Stage 4 — Gate. Confident answers decide; an unsure one gets up to two more
// VIEWS (the function alone, then with the bug report). All confident and in
// agreement -> decided; otherwise needs_input with the votes.
func gate(ctx context.Context, c *tn.Classifier, fn Fn, first j.Answers) (string, string, error) {
	o := triagePolicy.Decide(first)
	if !o.Escalated {
		return o.Action, why(first), nil
	}
	votes := []string{o.Action}
	views := []map[string]any{funcState(fn), j.State(role, map[string]any{"open_bug_reports": []string{bugReport}, "function": funcData(fn)})}
	for i, st := range views {
		a, err := j.Ask(j.WithKey(ctx, fmt.Sprintf("gate:%s:%d", fn.Name, i+2)), c, st, triageQs(fn.Name))
		if err != nil {
			return "", "", err
		}
		votes = append(votes, triagePolicy.Decide(perFunc(a)[fn.Name]).Action)
	}
	if votes[1] == votes[2] && votes[1] != "needs_input" {
		return votes[1], fmt.Sprintf("%s; re-asked %v -> agreed", why(first), votes), nil
	}
	return "needs_input", fmt.Sprintf("%s; re-asked %v -> split, ask a human", why(first), votes), nil
}

// Stage 6 — Verify. Code vetoes first (compile, fails-after-fix, no mutant
// killed = coverage-only); only then does the classifier judge the test.
func verify(ctx context.Context, c *tn.Classifier, shop string, before float64, v *Verdict, test string) error {
	r, err := check(shop, v.Name, test)
	if err != nil {
		return err
	}
	v.Why += "; " + r.String(before)
	switch {
	case !r.Compiles:
		v.Test = "rejected"
		return nil
	case !r.PassesFixed:
		v.Test, v.Why = "rejected", v.Why+", wrong expectation (fails on fixed code)"
		return nil
	case r.Killed == 0:
		v.Test, v.Why = "rejected", v.Why+", coverage-only: no mutant killed"
		return nil
	}
	o, err := verifyPolicy.Gate(j.WithKey(ctx, "verify:"+v.Name), c, j.State(role, map[string]any{"function": v.Body, "test": test}), verifyQs)
	if err != nil {
		return err
	}
	v.Test = o.Action
	v.Why += fmt.Sprintf(", asserts=%.2f named=%.2f", o.Answers["asserts_behaviour"].Value(), o.Answers["name_describes_scenario"].Value())
	return nil
}

// run is the whole pipeline; w is stage 5 (live LLM or recorded draft).
func run(ctx context.Context, c *tn.Classifier, shop string, w Writer) ([]Verdict, error) {
	fns, before, err := understand(shop)
	if err != nil {
		return nil, err
	}
	var out, open []Verdict
	var todo []Fn
	for _, fn := range fns {
		if fn.Cov == 100 {
			out = append(out, Verdict{Fn: fn, Triage: "skip", Why: "already covered"})
		} else {
			todo = append(todo, fn)
		}
	}
	tri, err := triage(ctx, c, todo)
	if err != nil {
		return nil, err
	}
	pl, err := plan(ctx, c, todo)
	if err != nil {
		return nil, err
	}
	for _, fn := range todo {
		v := Verdict{Fn: fn, Plan: pl[fn.Name]["scenario"].Choice()}
		if v.Triage, v.Why, err = gate(ctx, c, fn, tri[fn.Name]); err != nil {
			return nil, err
		}
		open = append(open, v)
	}
	for _, v := range open {
		if v.Triage == "keep" {
			test, err := w.Write(ctx, v.Fn, v.Plan)
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
	offline := flag.Bool("offline", false, "replay testdata/recorded instead of calling TypeSafe/OpenRouter")
	record := flag.Bool("record", false, "live run that also writes testdata/recorded (tape + drafted tests)")
	flag.Parse()
	ctx, shop := context.Background(), "testdata/shop"
	var m *mode
	var err error
	if *offline {
		m, err = replayMode()
	} else {
		m, err = liveMode(ctx, *record)
	}
	if err == nil {
		var vs []Verdict
		if vs, err = run(ctx, m.c, shop, m.w); err == nil {
			printTable(vs)
			printCost(m)
			if *record {
				err = m.save()
			}
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "testscout:", strings.TrimSpace(err.Error()))
		os.Exit(1)
	}
}
