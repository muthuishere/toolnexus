package main

import (
	"fmt"
	. "toolnexus.spike/judgeadapters/judge"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Faithful port of today's wfnexus behaviour, for side-by-side:
//   - vals flattening: bug-fixer-platform apps/api/internal/engine/decide.go:64-99
//     (noul -> float, choice -> string ONLY, confidence/nearUniform dropped from vals)
//   - decideGate + asFloat: decide.go:139-171
//   - applyDecideGates first-match loop: engine.go:865-892
//
// Returns the fired action/target, or "" to fall through, or the step error.
// decide.go returns the accessor error (a missing answer fails the step), so we do too.
func baselineFlatten(d tn.Decision, types map[string]string) (map[string]any, error) {
	vals := map[string]any{}
	for key, t := range types {
		switch t {
		case "noul":
			a, err := d.Noul(key)
			if err != nil {
				return nil, fmt.Errorf("decide: %w", err)
			}
			vals[key] = a.Noul
		case "choice":
			a, err := d.Choice(key)
			if err != nil {
				return nil, fmt.Errorf("decide: %w", err)
			}
			vals[key] = a.Choice
		case "score":
			a, err := d.Score(key)
			if err != nil {
				return nil, fmt.Errorf("decide: %w", err)
			}
			vals[key] = a.Score
		}
	}
	return vals, nil
}

func baselineGate(g Rule, vals map[string]any) bool {
	v, ok := vals[g.Question]
	if !ok {
		return false
	}
	switch {
	case g.Below != nil:
		n, ok := v.(float64)
		return ok && n < *g.Below
	case g.AtLeast != nil:
		n, ok := v.(float64)
		return ok && n >= *g.AtLeast
	case g.Is != "":
		s, ok := v.(string)
		return ok && s == g.Is
	}
	return false
}

func baselineApply(d tn.Decision, types map[string]string, rules []Rule) (action, target string, err error) {
	vals, err := baselineFlatten(d, types)
	if err != nil {
		return "", "", err
	}
	for _, g := range rules {
		if baselineGate(g, vals) {
			return g.Action, g.Target, nil
		}
	}
	return "", "", nil
}
