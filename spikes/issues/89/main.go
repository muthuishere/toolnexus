// Spike for issue #89 — which keys of Answer.Data actually reach the tool?
//
// One suspending tool (`ask_human`, SPEC §10 kind:"input"), one mock LLM, and
// four resumes that differ ONLY in the key the host puts in Answer.Data:
//
//	A  durable RunWithAnswer, Data{"value":   "staging"}     <- issue #89's report
//	B  durable RunWithAnswer, Data{"output":  "staging"}     <- control (the one key that works)
//	C  durable RunWithAnswer, Data{"answers": ["staging"]}   <- the key the DOCS show
//	D  inline waitFor,        Data{"value":   "staging"}     <- the other §10 path
//
// For each we print: did the tool's Execute run again, what did it receive in
// ctx.Answer, what tool_result did the model actually see, and what status was
// the host handed back.
//
// No network, no API key: the LLM is an injected http.RoundTripper.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	tn "github.com/muthuishere/toolnexus/golang"
)

// what the tool observed, reset per scenario
var (
	executions  int
	sawAnswer   *tn.Answer
	returnedOut string
)

// askHuman suspends on first call (§10 kind "input"); on the post-resume retry
// it returns whatever the human's Answer.Data carried. A textbook §10 tool.
func askHuman() tn.Tool {
	return tn.Tool{
		Name:        "ask_human",
		Description: "ask the operator a question and wait for the reply",
		InputSchema: tn.JSONSchema{
			"type":       "object",
			"properties": map[string]any{"question": map[string]any{"type": "string"}},
		},
		Source: tn.SourceCustom,
		Execute: func(args map[string]any, ctx *tn.ToolContext) (tn.ToolResult, error) {
			executions++
			if ctx != nil && ctx.Answer != nil {
				a := *ctx.Answer
				sawAnswer = &a
				raw, _ := json.Marshal(a.Data)
				returnedOut = "human replied: " + string(raw)
				return tn.ToolResult{Output: returnedOut}, nil
			}
			q, _ := args["question"].(string)
			return tn.Pending(tn.Request{
				ID: "req_1", Kind: "input", Prompt: q,
			}), nil
		},
	}
}

func toolkit() *tn.Toolkit {
	tk, err := tn.CreateToolkit(context.Background(), tn.Options{
		Builtins: false, ExtraTools: []tn.Tool{askHuman()},
	})
	if err != nil {
		panic(err)
	}
	return tk
}

func client(waitFor func(tn.Request) (tn.Answer, error)) *tn.Client {
	return clientWithStore(waitFor, nil)
}

func clientWithStore(waitFor func(tn.Request) (tn.Answer, error), store tn.ConversationStore) *tn.Client {
	return tn.CreateClient(tn.ClientOptions{
		BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, Model: "mock", APIKey: "none",
		HTTPClient: &http.Client{Transport: &mockLLM{}},
		WaitFor:    waitFor,
		Store:      store,
	})
}

func reset() { executions, sawAnswer, returnedOut = 0, nil, "" }

// durable runs the halt/persist/resume cycle with the given Answer.Data.
func durable(label string, data map[string]any) {
	reset()
	ctx := context.Background()
	tk := toolkit()
	c := client(nil) // no WaitFor => the run halts durably (§10 rule 2)

	halted, err := c.Run(ctx, "pick an environment", tk)
	if err != nil {
		panic(err)
	}
	if halted.Status != "pending" || halted.Pending == nil {
		panic("expected a durable halt, got " + halted.Status)
	}

	// ... hours later, in another process: the human answered.
	resumed, err := client(nil).RunWithAnswer(ctx, toolkit(), halted.Messages, *halted.Pending,
		tn.Answer{ID: halted.Pending.ID, Ok: true, Data: data})

	report(label, data, resumed, err)
}

// inline runs the same tool with a WaitFor that returns the given Answer.Data.
func inline(label string, data map[string]any) {
	reset()
	tk := toolkit()
	c := client(func(req tn.Request) (tn.Answer, error) {
		return tn.Answer{ID: req.ID, Ok: true, Data: data}, nil
	})
	res, err := c.Run(context.Background(), "pick an environment", tk)
	report(label, data, res, err)
}

func report(label string, data map[string]any, res tn.RunResult, err error) {
	raw, _ := json.Marshal(data)
	fmt.Printf("\n== %s ==\n", label)
	fmt.Printf("  Answer.Data sent   : %s\n", raw)
	fmt.Printf("  err                : %v\n", err)
	fmt.Printf("  RunResult.Status   : %q\n", res.Status)
	fmt.Printf("  tool Execute ran   : %d time(s)\n", executions)
	if sawAnswer != nil {
		seen, _ := json.Marshal(sawAnswer.Data)
		fmt.Printf("  tool saw ctx.Answer: %s\n", seen)
	} else {
		fmt.Printf("  tool saw ctx.Answer: <never re-executed>\n")
	}
	fmt.Printf("  model's final text : %s\n", res.Text)
	fmt.Printf("  VERDICT            : %s\n", verdict(res))
}

func verdict(res tn.RunResult) string {
	switch {
	case strings.Contains(res.Text, "no result supplied on resume"):
		return "ANSWER LOST — host told status \"done\", the model got a FABRICATED tool error"
	case sawAnswer != nil:
		return "answer delivered to the tool itself (ctx.Answer)"
	case res.Status == "done":
		return "value reached the MODEL, but the tool was never re-executed (§10 rule 1 not applied)"
	}
	return "status " + res.Status
}

// docsFlow reproduces the documented two-phase durable resume verbatim
// (site/src/content/docs/suspension.mdx:300-341): phase 1 halts with no
// waitFor under a conversation id; phase 2 is a DIFFERENT client, with a
// waitFor returning Data{"answers": [reply]}, asking the same id.
func docsFlow(label string, data map[string]any) {
	reset()
	ctx := context.Background()
	store := tn.NewInMemoryConversationStore() // stands in for the host's DB
	const convID = "conv-1"

	phase1 := clientWithStore(nil, store)
	halted, err := phase1.Ask(ctx, "pick an environment", toolkit(), convID)
	if err != nil {
		panic(err)
	}
	fmt.Printf("\n== %s ==\n", label)
	fmt.Printf("  phase 1 status     : %q (pending=%v)\n", halted.Status, halted.Pending != nil)

	phase2 := clientWithStore(func(req tn.Request) (tn.Answer, error) {
		return tn.Answer{ID: req.ID, Ok: true, Data: data}, nil
	}, store)
	done, err := phase2.Ask(ctx, "pick an environment", toolkit(), convID)

	raw, _ := json.Marshal(data)
	fmt.Printf("  Answer.Data sent   : %s\n", raw)
	fmt.Printf("  err                : %v\n", err)
	fmt.Printf("  RunResult.Status   : %q\n", done.Status)
	fmt.Printf("  tool Execute ran   : %d time(s)\n", executions)
	if sawAnswer != nil {
		seen, _ := json.Marshal(sawAnswer.Data)
		fmt.Printf("  tool saw ctx.Answer: %s\n", seen)
	} else {
		fmt.Printf("  tool saw ctx.Answer: <never re-executed>\n")
	}
	fmt.Printf("  model's final text : %s\n", done.Text)
	fmt.Printf("  VERDICT            : %s\n", verdict(done))
}

func main() {
	fmt.Println("issue #89 — does Answer.Data reach the tool? (mock LLM, no network)")

	durable(`A. durable RunWithAnswer — Data{"value": "staging"}   (issue #89)`,
		map[string]any{"value": "staging"})
	durable(`B. durable RunWithAnswer — Data{"output": "staging"}  (control)`,
		map[string]any{"output": "staging"})
	durable(`C. durable RunWithAnswer — Data{"answers": [...]}     (the DOCS' example)`,
		map[string]any{"answers": []string{"staging"}})
	docsFlow(`E. the DOCS' two-phase flow — ask(id) + waitFor Data{"answers": [...]}`,
		map[string]any{"answers": []string{"staging"}})

	inline(`D. inline waitFor        — Data{"value": "staging"}   (the other §10 path)`,
		map[string]any{"value": "staging"})
}
