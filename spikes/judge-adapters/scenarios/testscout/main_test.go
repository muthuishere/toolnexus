package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The whole pipeline, offline and hermetic, with its decisions pinned.
func TestPipelineOffline(t *testing.T) {
	c, write, err := offline("testdata/shop")
	if err != nil {
		t.Fatal(err)
	}
	vs, err := run(context.Background(), c, "testdata/shop", write)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string][2]string{ // func -> {triage, test}
		"Code":        {"skip", ""},         // getter, already covered
		"String":      {"skip", ""},         // trivial formatting
		"percentOf":   {"needs_input", ""},  // classifier unsure -> §10 human
		"ApplyCoupon": {"keep", "accepted"}, // money + user-facing error path
		"RoundCents":  {"keep", "rejected"}, // kept, but the draft only chases coverage
	}
	for _, v := range vs {
		w, ok := want[v.Name]
		if !ok || v.Triage != w[0] || v.Test != w[1] {
			t.Errorf("%s: got triage=%q test=%q, want %v", v.Name, v.Triage, v.Test, w)
		}
		if v.Name == "ApplyCoupon" && !strings.Contains(v.Note, "caught a real bug") {
			t.Errorf("good test should FAIL on the buggy code: %s", v.Note)
		}
	}
	if len(vs) != len(want) {
		t.Errorf("got %d verdicts, want %d", len(vs), len(want))
	}
}

// The accepted test is only worth keeping if it goes green once the bug is fixed.
func TestGoodTestPassesOnFixedCode(t *testing.T) {
	fixed := t.TempDir()
	if err := os.CopyFS(fixed, os.DirFS("testdata/shop")); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(fixed, "coupon.go")
	src, _ := os.ReadFile(p)
	patched := strings.Replace(string(src), `strings.TrimPrefix(code, "SAVE")`, `strings.TrimSpace(strings.TrimPrefix(code, "SAVE"))`, 1)
	if patched == string(src) {
		t.Fatal("fix did not apply")
	}
	os.WriteFile(p, []byte(patched), 0o644)
	test, _ := recordedTest(Fn{Name: "ApplyCoupon"})
	passed, _, err := runWith(fixed, "ApplyCoupon", test)
	if err != nil || !passed {
		t.Fatalf("good test should pass on fixed code: passed=%v err=%v", passed, err)
	}
}
