package main

import (
	"os"
	"path/filepath"
	"testing"
)

// fake CLIs: print a fixed verdict and exit with jevx's code, so the gate's
// parsing and exit-code handling are tested hermetically.
func fake(t *testing.T, name, out string, code int) string {
	p := filepath.Join(t.TempDir(), name)
	s := "#!/bin/sh\necho '" + out + "'\nexit " + string(rune('0'+code)) + "\n"
	if err := os.WriteFile(p, []byte(s), 0o755); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestGateComparesVerdicts(t *testing.T) {
	its := []item{{ID: "a", Verb: "is", Question: "q?", In: "x"}}
	if rs := run(its, fake(t, "j", "no 0.10", 1), fake(t, "m", "no 0.12", 1), ""); !rs[0].Match {
		t.Fatalf("%+v", rs)
	}
	if rs := run(its, fake(t, "j", "yes 0.90", 0), fake(t, "m", "unsure 0.70", 3), ""); rs[0].Match {
		t.Fatalf("%+v", rs)
	}
	// jevx ask prints NAME VERDICT P
	its = []item{{ID: "r", Verb: "rate", Question: "sev?", Levels: []string{"low", "high"}, In: "x"}}
	if rs := run(its, fake(t, "j", "q   high   0.99", 0), fake(t, "m", "high 0.99", 0), ""); !rs[0].Match {
		t.Fatalf("%+v", rs)
	}
	// an error on both sides is NOT a match
	if rs := run(its, fake(t, "j", "", 4), fake(t, "m", "", 4), ""); rs[0].Match || rs[0].Jevx != "ERROR" {
		t.Fatalf("%+v", rs)
	}
}

func TestJevxArgsUseFresh(t *testing.T) {
	a := jevxArgs(item{Verb: "pick", Question: "Which team?", Options: []string{"web=frontend"}, In: "x"}, "jev")
	want := []string{"pick", "Which team?", "web=frontend", "--fresh", "--no-context", "--in", "x", "--profile", "jev"}
	if len(a) != len(want) {
		t.Fatalf("%v", a)
	}
	for i := range a {
		if a[i] != want[i] {
			t.Fatalf("%v", a)
		}
	}
}
