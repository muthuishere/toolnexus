// Spike for issues #88 and #90 against golang/agents at v0.18.1's behaviour.
//
//	A) TaskResult.TotalTokens vs rt.UsageTokens(rt.Root)  (#88)
//	B) a maxTurns stop carries no machine-readable reason   (#90 part 1)
//	C) a side-effecting tool re-runs across Resume          (#90 part 2)
//
// Everything runs against the scripted mock in mock.go: no network, no key.
// Nothing outside this directory is modified.
package main

import (
	"fmt"
	"reflect"
	"strings"
	"sync/atomic"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
)

func hdr(s string) { fmt.Printf("\n%s\n%s\n", s, strings.Repeat("=", len(s))) }

func main() {
	scenarioA()
	scenarioB()
	scenarioC()
}

// ---- A: the token ledger ---------------------------------------------------

func scenarioA() {
	hdr("A. #88 — parent + one child: res.TotalTokens vs rt.UsageTokens(rt.Root)")

	explore := agents.New("explore", agents.Spec{
		Does:  "survey a codebase",
		Model: "m-child",
	})
	coord := agents.New("coordinator", agents.Spec{
		Does:  "coordinate",
		Model: "m-parent",
		Team:  []*agents.Agent{explore},
	})

	res, rt := coord.Run(agents.Options{Transport: &mockLLM{}}, "survey and report")

	fmt.Printf("res.Status                : %q\n", res.Status)
	fmt.Printf("res.Turns                 : %d\n", res.Turns)
	fmt.Printf("res.TotalTokens           : %d   <- what Agent.Run hands you\n", res.TotalTokens)
	fmt.Printf("rt.UsageTokens(rt.Root)   : %d   <- the tree ledger the docs promise\n", rt.UsageTokens(rt.Root))
	fmt.Println("per-handle (rt.List()):")
	for _, v := range rt.List() {
		fmt.Printf("  %-28s state=%-9s turns=%d tokens=%d\n", v.ID, v.State, v.Turns, v.Tokens)
	}
	fmt.Printf("\nunder-report: %d of %d tokens missing (%.0f%%) with ONE child\n",
		rt.UsageTokens(rt.Root)-res.TotalTokens, rt.UsageTokens(rt.Root),
		100*float64(rt.UsageTokens(rt.Root)-res.TotalTokens)/float64(rt.UsageTokens(rt.Root)))
}

// ---- B: the stop reason ----------------------------------------------------

func scenarioB() {
	hdr("B. #90.1 — a maxTurns stop carries no machine-readable reason")

	noop := tn.Tool{
		Name: "noop", Description: "does nothing",
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      tn.SourceCustom,
		Execute: func(map[string]any, *tn.ToolContext) (tn.ToolResult, error) {
			return tn.ToolResult{Output: "noop ok"}, nil
		},
	}
	looper := agents.New("looper", agents.Spec{
		Does: "never finishes", Model: "m-loop",
		Tools:  []tn.Tool{noop},
		Budget: &agents.Budget{MaxTurns: 3},
	})

	res, _ := looper.Run(agents.Options{Transport: &mockLLM{}}, "go")

	fmt.Printf("res.Status  : %q\n", res.Status)
	fmt.Printf("res.IsError : %v\n", res.IsError)
	fmt.Printf("res.Text    : %q   <- the ONLY carrier of the reason: prose\n", res.Text)
	fmt.Println("\nTaskResult's full field set (reflect):")
	t := reflect.TypeOf(res)
	for i := 0; i < t.NumField(); i++ {
		fmt.Printf("  %-12s %s\n", t.Field(i).Name, t.Field(i).Type)
	}
	fmt.Println("\n  -> no `Limit`. tn.RunResult HAS one; it is dropped at the handle boundary.")
	fmt.Println("     A host must string-match res.Text to tell maxTurns from a completion-gate")
	fmt.Println("     stop from a budget stop — all three arrive as status \"incomplete\".")
}

// ---- C: the resumed turn ---------------------------------------------------

func scenarioC() {
	hdr("C. #90.2 — a side-effecting tool across Resume (invocations before vs after)")

	var counter int64
	var steps []string
	counterTool := tn.Tool{
		Name: "counter", Description: "performs an IRREVERSIBLE step (stand-in for git push / HTTP POST)",
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{
			"step": map[string]any{"type": "string"},
		}},
		Source: tn.SourceCustom,
		Execute: func(args map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
			n := atomic.AddInt64(&counter, 1)
			step, _ := args["step"].(string)
			steps = append(steps, fmt.Sprintf("#%d %s", n, step))
			return tn.ToolResult{Output: fmt.Sprintf("side effect applied (invocation %d, step=%s)", n, step)}, nil
		},
	}
	approve := tn.Tool{
		Name: "approve", Description: "asks a human to approve, then reports the decision",
		InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      tn.SourceCustom,
		Execute: func(_ map[string]any, ctx *tn.ToolContext) (tn.ToolResult, error) {
			if ctx != nil && ctx.Answer != nil && ctx.Answer.Ok {
				return tn.ToolResult{Output: "APPROVED by the human"}, nil
			}
			return tn.Pending(tn.Request{
				Kind: "approval", Prompt: "may I commit?", ID: "req-1",
			}), nil
		},
	}

	worker := agents.New("worker", agents.Spec{
		Does: "stage, get approval, commit", Model: "m-sus",
		Tools: []tn.Tool{counterTool, approve},
	})

	rtOpts := agents.Options{Transport: &mockLLM{}}
	res, rt := worker.Run(rtOpts, "stage the change, get approval, then commit")

	fmt.Printf("BEFORE RESUME\n")
	fmt.Printf("  res.Status        : %q\n", res.Status)
	if res.Pending != nil {
		fmt.Printf("  res.Pending.Kind  : %q  prompt=%q  data.path=%v\n",
			res.Pending.Kind, res.Pending.Prompt, res.Pending.Data["path"])
	}
	fmt.Printf("  res.Turns         : %d\n", res.Turns)
	fmt.Printf("  res.TotalTokens   : %d\n", res.TotalTokens)
	before := atomic.LoadInt64(&counter)
	fmt.Printf("  counter invocations: %d   %v\n", before, steps)

	// What is in the store for the suspended handle at the moment of parking?
	var suspendedID string
	for _, v := range rt.List() {
		if v.State == "suspended" {
			suspendedID = v.ID
		}
	}
	hist, _ := rt.ConversationStore().Get(suspendedID)
	fmt.Printf("  stored transcript for %s: %d message(s)  <- THE DROP POINT\n", suspendedID, len(hist))

	// Resume. Note the signature: only `error` comes back.
	err := rt.Resume(tn.Answer{ID: "req-1", Ok: true})
	fmt.Printf("\nrt.Resume(...) returned: %v   (signature: func(tn.Answer) error — no TaskResult)\n", err)

	after := atomic.LoadInt64(&counter)
	fmt.Printf("\nAFTER RESUME\n")
	fmt.Printf("  counter invocations: %d   %v\n", after, steps)
	fmt.Printf("  replayed the side effect %d extra time(s)\n", after-before-1)
	fmt.Println("  handles after resume:")
	var finalTokens, finalTurns int
	for _, v := range rt.List() {
		fmt.Printf("    %-10s state=%-9s turns=%d tokens=%d\n", v.ID, v.State, v.Turns, v.Tokens)
		if v.ID == suspendedID {
			finalTokens, finalTurns = v.Tokens, v.Turns
		}
	}
	fmt.Printf("\n  tokens %d -> %d, turns %d -> %d (they DO grow, as documented)\n",
		res.TotalTokens, finalTokens, res.Turns, finalTurns)
	fmt.Println("  ...but there is no supported way to read the resumed TaskResult:")
	fmt.Println("    Agent.Run never handed back the *Handle, and rt.List() yields HandleView,")
	fmt.Println("    which carries no Text. rt.Wait needs a *Handle the host does not hold.")
	fmt.Printf("\n  intended plan was 2 side effects (stage, commit); actual: %d\n", after)
}
