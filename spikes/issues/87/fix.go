package main

// Path C proves option (a) is cheap, WITHOUT touching library source: a
// spike-local copy of the unexported `guardedHooks` (golang/agents/loop.go:26)
// plus Soul, handed to the same Loop through ClientOptions. That is exactly the
// three assignments `Loop.clientFor` would have to make.

import (
	"context"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
)

func guardedHooksCopy(sp agents.Spec) *tn.Hooks {
	if len(sp.Guardrails) == 0 {
		return sp.Hooks
	}
	prior := func(context.Context, tn.BeforeToolEvent) (*tn.ToolOverride, error) { return nil, nil }
	merged := tn.Hooks{}
	if sp.Hooks != nil {
		merged = *sp.Hooks
		if sp.Hooks.BeforeTool != nil {
			prior = sp.Hooks.BeforeTool
		}
	}
	rails := sp.Guardrails
	merged.BeforeTool = func(ctx context.Context, ev tn.BeforeToolEvent) (*tn.ToolOverride, error) {
		for _, g := range rails {
			if v := g(ev); v != "" && v != "allow" {
				return &tn.ToolOverride{Result: &tn.ToolResult{Output: "denied: " + v, IsError: true}}, nil
			}
		}
		return prior(ctx, ev)
	}
	return &merged
}
