// gate: the CEO cutover gate. Runs the SAME items through the current jevx
// binary and through minijevx (toolnexus path) and reports where the two
// decisions agree. Cut over only where they match.
//
//	gate --items items.jsonl [--jevx jevx] [--mini ./minijevx] [--profile P]
//
// An item line: {"id":"…","source":"…","verb":"is|pick|rate","question":"…",
// "options":["k=desc",…] | "levels":["low",…], "in":"…"}. Items must be REAL
// (see items/README.md); this tool never generates any.
//
// Both sides are invoked as subprocesses so the comparison covers each CLI end
// to end. jevx is called with --fresh (its cache cannot answer for it) and
// --no-context (its ## Jev sections would change the state; minijevx sends none).
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"strings"
)

type item struct {
	ID       string   `json:"id"`
	Source   string   `json:"source"`
	Verb     string   `json:"verb"`
	Question string   `json:"question"`
	Options  []string `json:"options,omitempty"`
	Levels   []string `json:"levels,omitempty"`
	In       string   `json:"in"`
}

type result struct {
	ID, Jevx, Mini string
	Match          bool
}

// jevxArgs maps an item onto the CURRENT jevx CLI: `is` and `pick` are
// shortcuts; a score goes through `ask --score NAME="Q|l0;l1"`.
func jevxArgs(it item, profile string) []string {
	var a []string
	switch it.Verb {
	case "is":
		a = []string{"is", it.Question}
	case "pick":
		a = append([]string{"pick", it.Question}, it.Options...)
	case "rate":
		a = []string{"ask", "--score", "q=" + it.Question + "|" + strings.Join(it.Levels, ";")}
	}
	a = append(a, "--fresh", "--no-context", "--in", it.In)
	if profile != "" {
		a = append(a, "--profile", profile)
	}
	return a
}

func miniArgs(it item, profile string) []string {
	a := []string{}
	if profile != "" {
		a = append(a, "--profile", profile)
	}
	a = append(a, it.Verb, it.Question)
	a = append(a, it.Options...)
	a = append(a, it.Levels...)
	return append(a, "--in", it.In)
}

// answer runs a CLI and returns its verdict word. Exit 1/3 are answers, not
// errors; anything else non-zero is reported as ERROR (never read as "no").
// Lines look like "VERDICT P" or, for `jevx ask`, "NAME VERDICT P".
func answer(bin string, args []string) string {
	out, err := exec.Command(bin, args...).Output()
	if ee, ok := err.(*exec.ExitError); ok && ee.ExitCode() != 1 && ee.ExitCode() != 3 {
		return "ERROR"
	} else if err != nil && !ok {
		return "ERROR"
	}
	f := strings.Fields(strings.TrimSpace(string(out)))
	switch len(f) {
	case 2:
		return f[0]
	case 3:
		return f[1]
	}
	return "ERROR"
}

func run(items []item, jevx, mini, profile string) []result {
	var rs []result
	for _, it := range items {
		j, m := answer(jevx, jevxArgs(it, profile)), answer(mini, miniArgs(it, profile))
		rs = append(rs, result{it.ID, j, m, j == m && j != "ERROR"})
	}
	return rs
}

func main() {
	path := flag.String("items", "", "JSONL item file")
	jevx := flag.String("jevx", "jevx", "current jevx binary")
	mini := flag.String("mini", "minijevx", "minijevx binary")
	profile := flag.String("profile", "", "profile name passed to both")
	flag.Parse()
	f, err := os.Open(*path)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(4)
	}
	var items []item
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if l := strings.TrimSpace(sc.Text()); l != "" && !strings.HasPrefix(l, "//") {
			var it item
			if err := json.Unmarshal([]byte(l), &it); err != nil {
				fmt.Fprintln(os.Stderr, "bad item:", err)
				os.Exit(4)
			}
			items = append(items, it)
		}
	}
	rs := run(items, *jevx, *mini, *profile)
	n := 0
	fmt.Printf("%-24s %-14s %-14s %s\n", "id", "jevx", "toolnexus", "match")
	for _, r := range rs {
		if r.Match {
			n++
		}
		fmt.Printf("%-24s %-14s %-14s %v\n", r.ID, r.Jevx, r.Mini, r.Match)
	}
	fmt.Printf("\n%d/%d match\n", n, len(rs))
	if n != len(rs) {
		os.Exit(1)
	}
}
