package main

import (
	"context"
	"fmt"
	"os"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Live mode: GATE_LIVE=1 go run .
// The key is read by the library via APIKeyEnv (the NAME only); this program
// never reads or prints it. Asserts shape only — live numbers are not pinned.
func main() {
	if os.Getenv("GATE_LIVE") != "1" {
		fmt.Println("live mode off (set GATE_LIVE=1); run `go test ./...` for the hermetic rows")
		return
	}
	c, err := tn.CreateClassifier(tn.ClassifierOptions{Backend: tn.BackendOpenRouter, Style: tn.StyleSystemOne, Timeout: 30 * time.Second})
	if err != nil {
		fmt.Println("SKIP: CreateClassifier:", err)
		return
	}
	o, err := Gate(context.Background(), c, bug, qs, rules, DefaultBands)
	if err != nil {
		fmt.Println("SKIP: live evaluate failed (no key or backend unreachable):", err)
		return
	}
	if _, ok := o.Answers["fixable"].DecisionAnswer.(tn.NoulAnswer); !ok {
		fmt.Println("SHAPE FAIL: fixable not a noul answer")
		os.Exit(1)
	}
	ch, ok := o.Answers["component"].DecisionAnswer.(tn.ChoiceAnswer)
	if !ok || len(ch.Probabilities) == 0 {
		fmt.Println("SHAPE FAIL: component not a choice answer with probabilities")
		os.Exit(1)
	}
	if o.Escalated != (o.Request != nil) {
		fmt.Println("SHAPE FAIL: Escalated/Request disagree")
		os.Exit(1)
	}
	fmt.Printf("OK shape. fixable=%.2f component=%s conf=%.2f nearUniform=%v -> action=%q target=%q escalated=%v\n",
		o.Answers["fixable"].DecisionAnswer.(tn.NoulAnswer).Noul, ch.Choice, ch.Confidence, ch.NearUniform, o.Action, o.Target, o.Escalated)
}
