package main

// Plumbing for main.go: go tooling, mutation check, live vs replay mode, printing.

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
	j "toolnexus.spike/judgeadapters/judge"
)

// parseFuncs reads every non-test func: name, signature line, full source.
func parseFuncs(dir string) ([]Fn, error) {
	fset := token.NewFileSet()
	var out []Fn
	files, _ := filepath.Glob(filepath.Join(dir, "*.go"))
	for _, p := range files {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		src, err := os.ReadFile(p)
		if err != nil {
			return nil, err
		}
		file, err := parser.ParseFile(fset, p, src, 0)
		if err != nil {
			return nil, err
		}
		for _, d := range file.Decls {
			if fd, ok := d.(*ast.FuncDecl); ok {
				body := string(src[fset.Position(fd.Pos()).Offset:fset.Position(fd.End()).Offset])
				out = append(out, Fn{Name: fd.Name.Name, Sig: strings.TrimSpace(strings.SplitN(body, "{", 2)[0]), Body: body})
			}
		}
	}
	return out, nil
}

// coverage runs `go test -coverprofile` + `go tool cover -func` in dir.
// A failing test still yields numbers; err is only for "could not run at all".
func coverage(dir string) (perFunc map[string]float64, total float64, passed bool, err error) {
	prof := filepath.Join(dir, "cover.out")
	defer os.Remove(prof)
	out, testErr := exec.Command("go", "-C", dir, "test", "-coverprofile=cover.out", ".").CombinedOutput()
	if _, statErr := os.Stat(prof); statErr != nil {
		return nil, 0, false, fmt.Errorf("go test: %s", firstLine(string(out)))
	}
	fn, err := exec.Command("go", "-C", dir, "tool", "cover", "-func=cover.out").Output()
	if err != nil {
		return nil, 0, false, err
	}
	perFunc = map[string]float64{}
	sc := bufio.NewScanner(strings.NewReader(string(fn)))
	for sc.Scan() {
		f := strings.Fields(sc.Text()) // shop/coupon.go:20:  Code  100.0%
		if len(f) < 3 {
			continue
		}
		pct, _ := strconv.ParseFloat(strings.TrimSuffix(f[len(f)-1], "%"), 64)
		if f[0] == "total:" {
			total = pct
		} else {
			perFunc[f[1]] = pct
		}
	}
	return perFunc, total, testErr == nil, nil
}

// runWith copies the package to a temp dir, adds the drafted test, measures.
// src, when non-nil, replaces shop.go (the fixed code or a mutant).
func runWith(shop string, src []byte, name, test string) (passed bool, total float64, err error) {
	tmp, err := os.MkdirTemp("", "testscout-")
	if err != nil {
		return false, 0, err
	}
	defer os.RemoveAll(tmp)
	if err := os.CopyFS(tmp, os.DirFS(shop)); err != nil {
		return false, 0, err
	}
	if src != nil {
		if err := os.WriteFile(filepath.Join(tmp, "shop.go"), src, 0o644); err != nil {
			return false, 0, err
		}
	}
	if err := os.WriteFile(filepath.Join(tmp, "scout_"+name+"_test.go"), []byte(test), 0o644); err != nil {
		return false, 0, err
	}
	_, total, passed, err = coverage(tmp)
	return passed, total, err
}

func firstLine(s string) string { return strings.SplitN(strings.TrimSpace(s), "\n", 2)[0] }

// ---- Verify's code half: run the draft on current code, on fixed code, on mutants.

// The one-line fix the checkout-500 report points at. Applied to a copy only.
const bugLine, fixLine = `strings.TrimPrefix(code, "SAVE"))`, `strings.TrimSpace(strings.TrimPrefix(code, "SAVE")))`

type checkResult struct {
	Compiles, PassesCurrent, PassesFixed bool
	CovAfter                             float64
	Killed, Mutants                      int
}

func (r checkResult) String(before float64) string {
	if !r.Compiles {
		return "does not compile"
	}
	s := fmt.Sprintf("cov %.1f->%.1f%%, mutants killed %d/%d", before, r.CovAfter, r.Killed, r.Mutants)
	if !r.PassesCurrent && r.PassesFixed {
		s += ", FAILS on shipped code, passes on fix: caught the bug"
	}
	return s
}

// check runs the drafted test against the shipped package, the fixed package,
// and up to 6 single-operator mutants of the target func in the fixed package.
func check(shop, name, test string) (checkResult, error) {
	var r checkResult
	src, err := os.ReadFile(filepath.Join(shop, "shop.go"))
	if err != nil {
		return r, err
	}
	fixed := bytes.Replace(src, []byte(bugLine), []byte(fixLine), 1)
	passed, cov, err := runWith(shop, nil, name, test)
	if err != nil {
		return r, nil // does not compile
	}
	r.Compiles, r.PassesCurrent, r.CovAfter = true, passed, cov
	if r.PassesFixed, _, err = runWith(shop, fixed, name, test); err != nil {
		return r, err
	}
	for _, m := range mutants(fixed, name) {
		ok, _, err := runWith(shop, m, name, test)
		if err != nil {
			continue // a mutant that does not compile is not a mutant
		}
		r.Mutants++
		if !ok {
			r.Killed++
		}
	}
	return r, nil
}

var flip = map[token.Token]token.Token{token.ADD: token.SUB, token.SUB: token.ADD, token.LSS: token.LEQ,
	token.LEQ: token.LSS, token.GTR: token.GEQ, token.GEQ: token.GTR, token.EQL: token.NEQ, token.NEQ: token.EQL,
	token.LOR: token.LAND, token.LAND: token.LOR, token.MUL: token.QUO}

// mutants flips one operator or bumps one int constant inside func name.
func mutants(src []byte, name string) [][]byte {
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "shop.go", src, parser.ParseComments)
	if err != nil {
		return nil
	}
	var out [][]byte
	emit := func() {
		var b bytes.Buffer
		if format.Node(&b, fset, file) == nil && len(out) < 6 {
			out = append(out, b.Bytes())
		}
	}
	for _, d := range file.Decls {
		fd, ok := d.(*ast.FuncDecl)
		if !ok || fd.Name.Name != name {
			continue
		}
		ast.Inspect(fd.Body, func(n ast.Node) bool {
			switch x := n.(type) {
			case *ast.BinaryExpr:
				if to, ok := flip[x.Op]; ok {
					old := x.Op
					x.Op = to
					emit()
					x.Op = old
				}
			case *ast.BasicLit:
				if v, err := strconv.Atoi(x.Value); err == nil && x.Kind == token.INT {
					old := x.Value
					x.Value = strconv.Itoa(v + 1)
					emit()
					x.Value = old
				}
			}
			return true
		})
	}
	return out
}

// ---- helpers the stages share.

// perFunc splits "value:ApplyCoupon" keyed answers into ApplyCoupon -> {value}.
func perFunc(a j.Answers) map[string]j.Answers {
	out := map[string]j.Answers{}
	for k, v := range a {
		q, fn, _ := strings.Cut(k, ":")
		if out[fn] == nil {
			out[fn] = j.Answers{}
		}
		out[fn][q] = v
	}
	return out
}

func why(a j.Answers) string {
	return fmt.Sprintf("value=%.1f(%s) noticed=%.2f", a["value"].Value(), a["value"].Band, a["noticed"].Value())
}

// ---- modes: live (TypeSafe + OpenRouter, optionally taped) or replay.

// Writer is stage 5: draft a test for fn covering the planned scenario.
type Writer interface {
	Write(ctx context.Context, fn Fn, scenario string) (string, error)
}

type llmCall struct {
	Fn      string
	Ms      int64
	In, Out int
}

type mode struct {
	c      *tn.Classifier
	w      Writer
	tape   *j.Tape
	writer *liveWriter
}

const tapePath = "testdata/recorded/tape.json"

func liveMode(ctx context.Context, record bool) (*mode, error) {
	c, err := tn.CreateClassifier(tn.ClassifierOptions{Timeout: 60 * time.Second}) // TypeSafe systemone, jev-latest, TYPESAFE_API_KEY
	if err != nil {
		return nil, err
	}
	t := &j.Tape{}
	if c, err = t.Recording(c); err != nil {
		return nil, err
	}
	model := os.Getenv("SCOUT_MODEL")
	if model == "" {
		model = "openai/gpt-4.1-mini"
	}
	src, err := os.ReadFile("testdata/shop/shop.go")
	if err != nil {
		return nil, err
	}
	w := &liveWriter{src: string(src), drafts: map[string]string{}, llm: tn.CreateClient(tn.ClientOptions{
		BaseURL: "https://openrouter.ai/api/v1", Style: tn.StyleOpenAI, Model: model, // key: OPENROUTER_API_KEY, by name
		SystemPrompt: "You write Go tests that assert behaviour a customer would notice. Reply with exactly one ```go block containing a complete file in package shop, no other text."})}
	return &mode{c: c, w: w, tape: t, writer: w}, nil
}

func replayMode() (*mode, error) {
	t, err := j.LoadTape(tapePath)
	if err != nil {
		return nil, err
	}
	c, err := t.Replayer()
	return &mode{c: c, w: replayWriter{}, tape: t}, err
}

var fence = regexp.MustCompile("(?s)```(?:go)?\n(.*?)```")

var scenarioText = map[string]string{
	"happy_path": "the normal valid input a customer sends most often",
	"boundary":   "values at the edge of the allowed range",
	"invalid":    "invalid input rejected with the customer-facing error",
	"regression": "the exact input from this open bug report: " + bugReport,
}

type liveWriter struct {
	llm    *tn.Client
	src    string
	drafts map[string]string
	calls  []llmCall
}

// Write: no tools needed, so the toolkit is nil (the client handles a nil *Toolkit).
func (w *liveWriter) Write(ctx context.Context, fn Fn, scenario string) (string, error) {
	start := time.Now()
	res, err := w.llm.Run(ctx, fmt.Sprintf("Package source:\n\n```go\n%s```\n\nWrite a table-driven test for %s. Cover first: %s. Name the test Test%s_<Scenario> and each case after the customer scenario. Assert the CORRECT behaviour the customer expects, even where the current code is buggy. Do not redeclare package helpers.",
		w.src, fn.Name, scenarioText[scenario], fn.Name), nil)
	if err != nil {
		return "", err
	}
	w.calls = append(w.calls, llmCall{fn.Name, time.Since(start).Milliseconds(), res.Usage.PromptTokens, res.Usage.CompletionTokens})
	test := res.Text
	if m := fence.FindStringSubmatch(res.Text); m != nil {
		test = m[1]
	}
	w.drafts[fn.Name] = test
	return test, nil
}

type replayWriter struct{}

func (replayWriter) Write(_ context.Context, fn Fn, _ string) (string, error) {
	b, err := os.ReadFile(filepath.Join("testdata/recorded", fn.Name+"_test.go"))
	return string(b), err
}

// save writes the tape and the drafted tests: the offline test replays real answers.
func (m *mode) save() error {
	if err := os.MkdirAll("testdata/recorded", 0o755); err != nil {
		return err
	}
	for name, test := range m.writer.drafts {
		if err := os.WriteFile(filepath.Join("testdata/recorded", name+"_test.go"), []byte(test), 0o644); err != nil {
			return err
		}
	}
	return m.tape.Save(tapePath)
}

// ---- printing.

func printTable(vs []Verdict) {
	fmt.Printf("%-12s %5s  %-11s %-10s %-9s %s\n", "FUNC", "COV%", "TRIAGE", "PLAN", "TEST", "WHY")
	for _, v := range vs {
		fmt.Printf("%-12s %5.1f  %-11s %-10s %-9s %s\n", v.Name, v.Cov, v.Triage, v.Plan, v.Test, v.Why)
	}
}

// printCost: per-stage calls, wall time and tokens, measured at the seam.
func printCost(m *mode) {
	type agg struct {
		n       int
		ms      int64
		in, out int
	}
	st := map[string]*agg{}
	for _, c := range m.tape.Calls {
		k, _, _ := strings.Cut(c.Key, ":")
		if st[k] == nil {
			st[k] = &agg{}
		}
		a := st[k]
		a.n, a.ms, a.in, a.out = a.n+1, a.ms+c.Ms, a.in+c.In, a.out+c.Out
	}
	if m.writer != nil {
		for _, c := range m.writer.calls {
			if st["write(llm)"] == nil {
				st["write(llm)"] = &agg{}
			}
			a := st["write(llm)"]
			a.n, a.ms, a.in, a.out = a.n+1, a.ms+c.Ms, a.in+c.In, a.out+c.Out
		}
	}
	keys := make([]string, 0, len(st))
	for k := range st {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	fmt.Printf("\n%-11s %5s %8s %8s %8s\n", "STAGE", "CALLS", "WALL_MS", "IN_TOK", "OUT_TOK")
	for _, k := range keys {
		a := st[k]
		fmt.Printf("%-11s %5d %8d %8d %8d\n", k, a.n, a.ms, a.in, a.out)
	}
}
