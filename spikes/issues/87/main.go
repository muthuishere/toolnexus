// Spike for issue #87 — one Agent, one Spec, two entry points.
//
// The SAME agents.Spec (a guardrail denying `bash`, a Soul, a Budget) is driven
// twice: once through the runtime path (Agent.Run) and once through the
// standalone Loop path (Agent.Loop(...).Run). The mock LLM always asks for
// `bash`. We record whether the tool's Execute actually ran, and what system
// prompt the provider was shown.
//
// No network, no API key: the LLM is an injected http.RoundTripper.
package main

import (
	"context"
	"fmt"
	"net/http"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
)

var executed []string // every bash invocation that actually reached Execute

func bashTool() tn.Tool {
	return tn.Tool{
		Name:        "bash",
		Description: "run a shell command",
		InputSchema: tn.JSONSchema{
			"type":       "object",
			"properties": map[string]any{"command": map[string]any{"type": "string"}},
			"required":   []any{"command"},
		},
		Source: tn.SourceCustom,
		Execute: func(args map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
			cmd, _ := args["command"].(string)
			executed = append(executed, cmd)
			return tn.ToolResult{Output: "EXECUTED: " + cmd}, nil
		},
	}
}

// The one Spec both paths are handed.
func spec() agents.Spec {
	return agents.Spec{
		Does:   "operates the fleet",
		Soul:   "You are careful.",
		Tools:  []tn.Tool{bashTool()},
		Budget: &agents.Budget{MaxTurns: 2},
		Guardrails: []agents.Guardrail{
			func(ev tn.BeforeToolEvent) string {
				if ev.Name == "bash" {
					return "bash is denied in this harness"
				}
				return ""
			},
		},
	}
}

func main() {
	// ---------- path A: runtime / Def (Agent.Run) ----------
	executed = nil
	m := &mockLLM{}
	a := agents.New("ops", spec())
	r, rt := a.Run(agents.Options{
		Transport: m,
		LLM:       &agents.LLMOptions{BaseURL: "http://mock/v1", Style: tn.StyleOpenAI, Model: "mock", APIKey: "none"},
	}, "run ls")
	_ = rt
	fmt.Println("== A. runtime path — ag.Run(...) ==")
	fmt.Printf("  text          : %s\n", r.Text)
	fmt.Printf("  status        : %s\n", r.Status)
	fmt.Printf("  bash executed : %v   <- guardrail %s\n", executed, verdict(len(executed) == 0))
	fmt.Printf("  system prompt : %q\n", m.system)

	// ---------- path B: standalone Loop (Agent.Loop(...).Run) ----------
	executed = nil
	m2 := &mockLLM{}
	tk, err := tn.CreateToolkit(context.Background(), tn.Options{
		Builtins: false, ExtraTools: []tn.Tool{bashTool()},
	})
	if err != nil {
		panic(err)
	}
	b := agents.New("ops", spec())
	out, err := b.Loop(tn.ClientOptions{
		BaseURL:    "http://mock/v1",
		Style:      tn.StyleOpenAI,
		Model:      "mock",
		APIKey:     "none",
		HTTPClient: &http.Client{Transport: m2},
	}, tk).Run(context.Background(), "run ls", agents.RunOpts{})
	if err != nil {
		panic(err)
	}
	fmt.Println("\n== B. loop path — ag.Loop(clientOptions, tk).Run(...) ==")
	fmt.Printf("  text          : %s\n", out.Text)
	fmt.Printf("  status        : %s\n", out.Status)
	fmt.Printf("  bash executed : %v   <- guardrail %s\n", executed, verdict(len(executed) == 0))
	fmt.Printf("  system prompt : %q\n", m2.system)

	// ---------- path C: the Loop path with the Spec applied by hand ----------
	executed = nil
	m3 := &mockLLM{}
	tk3, _ := tn.CreateToolkit(context.Background(), tn.Options{
		Builtins: false, ExtraTools: []tn.Tool{bashTool()},
	})
	c := agents.New("ops", spec())
	out3, err := c.Loop(tn.ClientOptions{
		BaseURL:      "http://mock/v1",
		Style:        tn.StyleOpenAI,
		Model:        "mock",
		APIKey:       "none",
		HTTPClient:   &http.Client{Transport: m3},
		SystemPrompt: c.Spec.Soul,              // what Loop would have to do
		Hooks:        guardedHooksCopy(c.Spec), // (spike-local copy of the unexported fn)
		MaxTurns:     c.Spec.Budget.MaxTurns,
	}, tk3).Run(context.Background(), "run ls", agents.RunOpts{})
	if err != nil {
		panic(err)
	}
	fmt.Println("\n== C. loop path + the Spec applied by hand (proposed option (a)) ==")
	fmt.Printf("  text          : %s\n", out3.Text)
	fmt.Printf("  status        : %s\n", out3.Status)
	fmt.Printf("  bash executed : %v   <- guardrail %s\n", executed, verdict(len(executed) == 0))
	fmt.Printf("  system prompt : %q\n", m3.system)
}

func verdict(denied bool) string {
	if denied {
		return "FIRED (tool never ran)"
	}
	return "DID NOT RUN (tool executed)"
}
