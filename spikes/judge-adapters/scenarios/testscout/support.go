package main

// Plumbing for main.go: go-tooling, the two classifier/writer modes, printing.

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
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
func runWith(shop, name, test string) (passed bool, total float64, err error) {
	tmp, err := os.MkdirTemp("", "testscout-")
	if err != nil {
		return false, 0, err
	}
	defer os.RemoveAll(tmp)
	if err := os.CopyFS(tmp, os.DirFS(shop)); err != nil {
		return false, 0, err
	}
	if err := os.WriteFile(filepath.Join(tmp, "scout_"+name+"_test.go"), []byte(test), 0o644); err != nil {
		return false, 0, err
	}
	_, total, passed, err = coverage(tmp)
	return passed, total, err
}

func firstLine(s string) string { return strings.SplitN(strings.TrimSpace(s), "\n", 2)[0] }

// ---- offline: static classifier recordings + recorded test drafts.

type recordings struct {
	Triage map[string]map[string]json.RawMessage `json:"triage"`
	Verify map[string]map[string]json.RawMessage `json:"verify"`
}

// offline builds a static classifier. FRICTION: StyleStatic matches the EXACT
// state, so recordings are keyed by function name and the states are rebuilt
// here with the same triageState/verifyState the pipeline uses.
func offline(shop string) (*tn.Classifier, func(Fn) (string, error), error) {
	var r recordings
	raw, err := os.ReadFile("testdata/recorded/answers.json")
	if err == nil {
		err = json.Unmarshal(raw, &r)
	}
	if err != nil {
		return nil, nil, err
	}
	fns, _, err := understand(shop)
	if err != nil {
		return nil, nil, err
	}
	tq, _ := j.Questions(triageQs...)
	vq, _ := j.Questions(verifyQs...)
	var rec []tn.RecordedDecision
	add := func(state any, qs map[string]tn.Question, ans map[string]json.RawMessage) {
		body, _ := json.Marshal(map[string]any{"model": "jev-static", "answers": ans,
			"usage": map[string]any{"input_tokens": 0, "output_tokens": 0}})
		rec = append(rec, tn.RecordedDecision{State: state, Questions: qs, Response: body})
	}
	for _, fn := range fns {
		if a, ok := r.Triage[fn.Name]; ok {
			add(triageState(fn), tq, a)
		}
		if a, ok := r.Verify[fn.Name]; ok {
			test, err := recordedTest(fn)
			if err != nil {
				return nil, nil, err
			}
			add(verifyState(fn, test), vq, a)
		}
	}
	c, err := tn.CreateClassifier(tn.ClassifierOptions{Style: tn.StyleStatic, Model: "jev-static", Decisions: rec})
	return c, recordedTest, err
}

func recordedTest(fn Fn) (string, error) {
	b, err := os.ReadFile(filepath.Join("testdata/recorded", fn.Name+"_test.go"))
	return string(b), err
}

// ---- live: the toolnexus client writes tests, a hosted classifier judges.

var fence = regexp.MustCompile("(?s)```(?:go)?\n(.*?)```")

func live(ctx context.Context) (*tn.Classifier, func(Fn) (string, error), error) {
	c, err := tn.CreateClassifier(tn.ClassifierOptions{Backend: tn.BackendOpenRouter, Timeout: 60 * time.Second})
	if err != nil {
		return nil, nil, err
	}
	tk, err := tn.CreateToolkit(ctx, tn.Options{})
	if err != nil {
		return nil, nil, err
	}
	model := os.Getenv("SCOUT_MODEL")
	if model == "" {
		model = "openai/gpt-4o-mini"
	}
	llm := tn.CreateClient(tn.ClientOptions{BaseURL: "https://openrouter.ai/api/v1", Style: tn.StyleOpenAI, Model: model,
		SystemPrompt: "You write Go tests that assert behaviour a customer would notice. Reply with one ```go block only."})
	write := func(fn Fn) (string, error) {
		res, err := llm.Run(ctx, "Write a table-driven test in `package shop` for:\n\n"+fn.Body, tk)
		if err != nil {
			return "", err
		}
		if m := fence.FindStringSubmatch(res.Text); m != nil {
			return m[1], nil
		}
		return res.Text, nil
	}
	return c, write, nil
}

func printTable(vs []Verdict) {
	fmt.Printf("%-12s %5s  %-12s %-10s %s\n", "FUNC", "COV%", "TRIAGE", "TEST", "WHY")
	for _, v := range vs {
		why := v.Reason
		if v.Note != "" {
			why += "; " + v.Note
		}
		fmt.Printf("%-12s %5.1f  %-12s %-10s %s\n", v.Name, v.Cov, v.Triage, v.Test, why)
	}
}
