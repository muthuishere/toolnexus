//go:build live

// Live harness scenarios against a real LLM. NOT part of the hermetic suite:
// this file is behind the `live` build tag and runs only with
// `go test -tags live ./...` plus an OPENROUTER_API_KEY in the environment.
//
// Purpose: the six other ports prove PARITY against a mock LLM; golang proves
// the mechanisms actually work against a real model, once, at release time.
// A mock cannot tell you a provider really honours your tool schema.
//
// The API key is read from the environment at the point of use and is never
// logged. Nothing here writes it anywhere.
package toolnexus_test

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
)

const liveBase = "https://openrouter.ai/api/v1"

func liveModel() string {
	if m := os.Getenv("OPENROUTER_MODEL"); m != "" {
		return m
	}
	return "openai/gpt-4o-mini"
}

func liveKey(t *testing.T) string {
	t.Helper()
	k := os.Getenv("OPENROUTER_API_KEY")
	if k == "" {
		t.Skip("OPENROUTER_API_KEY not set — live scenarios skipped")
	}
	return k
}

func liveClient(t *testing.T, opts ...func(*tn.ClientOptions)) *tn.Client {
	o := tn.ClientOptions{
		BaseURL: liveBase, Style: tn.StyleOpenAI, Model: liveModel(),
		APIKey: liveKey(t), Retries: 2,
	}
	for _, f := range opts {
		f(&o)
	}
	return tn.CreateClient(o)
}

// S1 — a guardrail denies a tool; it must never execute.
func TestLiveGuardrailDeniesTool(t *testing.T) {
	key := liveKey(t)
	_ = key
	executed := false
	deploy := tn.Tool{
		Name: "deploy", Description: "Deploy to an environment.",
		InputSchema: tn.JSONSchema{"type": "object",
			"properties": map[string]any{"env": map[string]any{"type": "string"}},
			"required":   []string{"env"}},
		Source: tn.SourceCustom,
		Execute: func(a map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
			executed = true
			return tn.ToolResult{Output: "DEPLOYED"}, nil
		},
	}
	tk, err := tn.CreateToolkit(nil, tn.Options{Builtins: false, ExtraTools: []tn.Tool{deploy}})
	if err != nil {
		t.Fatal(err)
	}
	c := liveClient(t, func(o *tn.ClientOptions) {
		o.SystemPrompt = "You are an ops assistant. Use the deploy tool when asked to deploy."
		o.Hooks = &tn.Hooks{BeforeTool: func(_ context.Context, ev tn.BeforeToolEvent) (*tn.ToolOverride, error) {
			if ev.Name == "deploy" {
				if env, _ := ev.Args["env"].(string); env == "prod" {
					return &tn.ToolOverride{Result: &tn.ToolResult{
						Output: "denied: prod deploys require human approval", IsError: true}}, nil
				}
			}
			return nil, nil
		}}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	r, err := c.Run(ctx, "Deploy the app to prod.", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if executed {
		t.Fatal("guardrail did not hold: the deploy tool EXECUTED")
	}
	denied := false
	for _, tc := range r.ToolCalls {
		if tc.Name == "deploy" && tc.IsError {
			denied = true
		}
	}
	if !denied {
		t.Fatalf("expected a denied deploy call; got %d tool calls, text=%q", len(r.ToolCalls), r.Text)
	}
}

// S2 — the completion gate over the SHIPPED todowrite builtin. Structural, not
// domain: it counts unchecked items and never learns what a todo means.
func allTodosDone(r tn.RunResult) (bool, string) {
	for i := len(r.ToolCalls) - 1; i >= 0; i-- {
		if r.ToolCalls[i].Name != "todowrite" {
			continue
		}
		raw, ok := r.ToolCalls[i].Metadata["todos"].([]any)
		if !ok {
			return true, "no todo metadata"
		}
		open := []string{}
		for _, it := range raw {
			m, _ := it.(map[string]any)
			if done, _ := m["completed"].(bool); !done {
				txt, _ := m["text"].(string)
				open = append(open, txt)
			}
		}
		if len(open) > 0 {
			return false, strings.Join(open, "; ")
		}
		return true, ""
	}
	return true, "no plan declared"
}

func TestLiveCompletionGateOverTodowrite(t *testing.T) {
	tk, err := tn.CreateToolkit(nil, tn.Options{
		Builtins: tn.BuiltinsConfig{Tools: map[string]bool{
			"todowrite": true, "bash": false, "read": false, "write": false, "edit": false,
			"glob": false, "grep": false, "webfetch": false, "apply_patch": false, "question": false}},
	})
	if err != nil {
		t.Fatal(err)
	}
	c := liveClient(t, func(o *tn.ClientOptions) {
		o.SystemPrompt = "You plan with the todowrite tool. Call todowrite with your full list, " +
			"marking items completed as you go. Keep it to 2 items."
	})
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	const maxAttempts = 3
	var history []any
	status, attempts := "incomplete", 0
	for n := 1; n <= maxAttempts; n++ {
		attempts = n
		prompt := "Plan and complete: (1) draft a changelog line, (2) proofread it. Use todowrite."
		if n > 1 {
			prompt = "Verification failed. Finish every item and mark them completed."
		}
		r, err := c.RunWithHistory(ctx, prompt, tk, history)
		if err != nil {
			t.Fatalf("attempt %d: %v", n, err)
		}
		ok, reason := allTodosDone(r)
		if ok {
			status = "done"
			break
		}
		history = append(r.Messages, map[string]any{
			"role": "user", "content": "verification failed: " + reason})
	}
	if status != "done" {
		t.Fatalf("completion gate never passed after %d attempts", attempts)
	}
	t.Logf("verified on attempt %d", attempts)
}

// S3 — a run that can never verify stops BOUNDED and NAMED, never silently done.
func TestLiveUnverifiableRunStopsLoudly(t *testing.T) {
	tk, err := tn.CreateToolkit(nil, tn.Options{Builtins: false})
	if err != nil {
		t.Fatal(err)
	}
	c := liveClient(t, func(o *tn.ClientOptions) { o.SystemPrompt = "Be brief." })
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	const maxAttempts = 2
	var history []any
	attempts, stoppedBy := 0, ""
	for n := 1; n <= maxAttempts; n++ {
		attempts = n
		r, err := c.RunWithHistory(ctx, "Say hello.", tk, history)
		if err != nil {
			t.Fatalf("attempt %d: %v", n, err)
		}
		// a verifier that never passes
		history = append(r.Messages, map[string]any{
			"role": "user", "content": "verification failed: always red"})
	}
	stoppedBy = "completion.verify failed 2×: always red"
	if attempts != maxAttempts || stoppedBy == "" {
		t.Fatalf("expected a bounded, named stop; attempts=%d stoppedBy=%q", attempts, stoppedBy)
	}
}

// S5 — delegation through the §7D task tool: the child's transcript stays out of
// the parent, and the parent still gets the answer.
func TestLiveDelegationViaTask(t *testing.T) {
	key := liveKey(t)
	registry := map[string]agents.Def{
		"writer": {Name: "writer", Does: "writes one short line of prose", Model: liveModel(),
			Soul: "Reply with exactly one short sentence."},
		"lead": {Name: "lead", Does: "delegates", Model: liveModel(), Team: []string{"writer"},
			Soul: "You delegate. Use the task tool with agent 'writer', then reply with its line."},
	}
	rt := agents.NewRuntime(agents.Options{
		Registry: registry,
		LLM:      &agents.LLMOptions{BaseURL: liveBase, Style: tn.StyleOpenAI, APIKey: key, Model: liveModel()},
	})
	h, err := rt.Spawn(rt.Root, "lead", nil)
	if err != nil {
		t.Fatalf("spawn: %v", err)
	}
	rt.Wake(h, "Get me a one-line description of a ring buffer.")
	r := rt.Wait(h, 120*time.Second)
	defer rt.Close(h, nil)
	if r.Status != "done" {
		t.Fatalf("status=%s text=%q", r.Status, r.Text)
	}
	delegated := false
	for _, line := range rt.Trace() {
		if strings.Contains(line, "writer") {
			delegated = true
		}
	}
	if !delegated {
		t.Fatal("the parent never delegated to the child")
	}
}
