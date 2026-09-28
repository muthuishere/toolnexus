package main

import (
	"context"
	"os"
	"strings"
	"testing"

	j "toolnexus.spike/judgeadapters/judge"
)

// The whole pipeline, hermetic: replays the tape recorded from a LIVE run
// (go run . --record), matched by call name, not by exact state bytes.
func TestPipelineReplaysLiveRun(t *testing.T) {
	m, err := replayMode()
	if err != nil {
		t.Fatal(err)
	}
	vs, err := run(context.Background(), m.c, "testdata/shop", m.w)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string][3]string{ // func -> {triage, plan, test}
		"Code":         {"skip", "", ""},                      // already covered, never asked
		"String":       {"skip", "happy_path", ""},            // trivial formatting
		"percentOf":    {"needs_input", "regression", ""},     // re-asked, views split -> human
		"ApplyCoupon":  {"keep", "regression", "accepted"},    // the bug report steers the plan
		"RoundCents":   {"needs_input", "happy_path", ""},     // unsure in every view
		"Total":        {"needs_input", "happy_path", ""},     //
		"ValidateLine": {"keep", "happy_path", "needs_input"}, // re-asked views agree -> keep
	}
	for _, v := range vs {
		w, ok := want[v.Name]
		if !ok || v.Triage != w[0] || v.Plan != w[1] || v.Test != w[2] {
			t.Errorf("%s: got %q/%q/%q, want %v (%s)", v.Name, v.Triage, v.Plan, v.Test, w, v.Why)
		}
		if v.Name == "ApplyCoupon" && !strings.Contains(v.Why, "caught the bug") {
			t.Errorf("accepted test must fail on shipped code and pass on the fix: %s", v.Why)
		}
	}
	if len(vs) != len(want) {
		t.Errorf("got %d verdicts, want %d", len(vs), len(want))
	}
}

// A test that executes lines but asserts nothing is vetoed by CODE (no mutant
// dies) before the classifier is ever asked.
func TestCoverageOnlyTestIsRejectedByMutation(t *testing.T) {
	test, err := os.ReadFile("testdata/coverage_only_test.go.txt")
	if err != nil {
		t.Fatal(err)
	}
	v := Verdict{Fn: Fn{Name: "RoundCents"}}
	if err := verify(context.Background(), nil, "testdata/shop", 16, &v, string(test)); err != nil {
		t.Fatal(err)
	}
	if v.Test != "rejected" || !strings.Contains(v.Why, "no mutant killed") {
		t.Fatalf("got %q: %s", v.Test, v.Why)
	}
}

// A replay miss names the call instead of "no recorded decision".
func TestTapeMissNamesTheKey(t *testing.T) {
	c, _ := (&j.Tape{}).Replayer()
	_, err := j.Ask(j.WithKey(context.Background(), "plan"), c, map[string]any{}, []j.Q{planQ("X")})
	if err == nil || !strings.Contains(err.Error(), `"plan"`) {
		t.Fatalf("want a miss naming the key, got %v", err)
	}
}
