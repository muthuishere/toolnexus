// Level-1 surface (SPEC §7D): one new noun — the Agent — compiled down to the
// six runtime verbs. Declaring agents in each other's Team IS the sub-agent
// wiring; AsTool is the bridge back into the classic API's ExtraTools (the
// axiom's other direction: an Agent is a Tool).
package agents

import (
	"os"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Spec declares an agent at the Level-1 surface.
type Spec struct {
	// Does is what the delegating model sees — the routing description (required).
	Does string
	// Tools is the toolkit VIEW for this agent — scoping is the security model.
	Tools []tn.Tool
	// Soul is the agent's identity/system prompt, inline.
	Soul string
	// SoulFile is the path form of Soul, read at registry-build time; when both
	// are set the file wins if it is readable.
	SoulFile string
	// Team lists the agents this one may delegate to via the task tool.
	// Delegation (and recursion) is opt-in: no Team ⇒ no task tool.
	Team []*Agent
	// Budget caps this agent's subtree.
	Budget *Budget
	// Model is the model id; "" ⇒ "inherit" (the runtime's LLM default).
	Model string
	// WaitFor is this agent's §10 interpreter authority for its subtree.
	WaitFor func(tn.Request) (tn.Answer, error)
	OnSpawn func(h *Handle)
	OnClose func(h *Handle, reason string)
	// Hooks are the §8 lifecycle callbacks for THIS agent's turns; set ⇒ replaces
	// the runtime-wide Options.Hooks for this agent (never merged). The seam a
	// §7F compactor rides, per agent.
	Hooks *tn.Hooks
	// OnMetric is the §8 observability sink for THIS agent's turns; set ⇒
	// replaces Options.OnMetric for this agent (never merged).
	OnMetric func(tn.MetricEvent)
	// Guardrails are POLICY checks on tool calls — "may it?", never "is it
	// right?". They compile into one BeforeTool with FIRST-DENY-WINS: a later
	// guardrail can never widen an earlier denial. Composed with Hooks.BeforeTool,
	// which runs only if every guardrail allows. Empty ⇒ byte-identical.
	Guardrails []Guardrail
	// Completion, when set, is the gate that stops this agent claiming `done`
	// before its work verifies. It travels with the agent, so a caller that
	// delegates via `task` inherits it — which a host-side retry loop cannot do.
	// Nil ⇒ byte-identical.
	Completion *Completion
}

// Guardrail is a POLICY check on a tool call: return "" or "allow" to permit, or
// a reason to deny. Never a correctness check — "did the tests pass" is a tool.
type Guardrail func(ev tn.BeforeToolEvent) string

// Completion is the gate an agent must pass before it may report `done`. The
// loop NEVER interprets Verify — it only counts attempts and reports honestly,
// which is what keeps the loop domain-neutral and therefore portable.
type Completion struct {
	// Verify reports (ok, reason). Reason is fed back to the agent on failure.
	Verify func(tn.RunResult) (bool, string)
	// MaxAttempts is REQUIRED (>= 1): an unbounded gate is a runaway loop.
	MaxAttempts int
}

// Agent is the one new noun.
type Agent struct {
	Name string
	Spec Spec
}

// New builds an Agent.
func New(name string, spec Spec) *Agent {
	return &Agent{Name: name, Spec: spec}
}

// Registry collects this agent plus the TRANSITIVE CLOSURE of its team graph
// into a runtime registry — unreachable agents are not present (§7D team
// scoping).
func (a *Agent) Registry() map[string]Def {
	acc := map[string]Def{}
	a.registryInto(acc)
	return acc
}

func (a *Agent) registryInto(acc map[string]Def) {
	if _, ok := acc[a.Name]; ok {
		return
	}
	soul := a.Spec.Soul
	if a.Spec.SoulFile != "" {
		if b, err := os.ReadFile(a.Spec.SoulFile); err == nil {
			soul = string(b)
		}
	}
	model := a.Spec.Model
	if model == "" {
		model = "inherit"
	}
	team := make([]string, 0, len(a.Spec.Team))
	for _, t := range a.Spec.Team {
		team = append(team, t.Name)
	}
	acc[a.Name] = Def{
		Name:     a.Name,
		Does:     a.Spec.Does,
		Soul:     soul,
		Model:    model,
		Tools:    a.Spec.Tools,
		Team:     team,
		Budget:   a.Spec.Budget,
		WaitFor:  a.Spec.WaitFor,
		OnSpawn:  a.Spec.OnSpawn,
		OnClose:  a.Spec.OnClose,
		Hooks:      guardedHooks(a.Spec),
		Completion: a.Spec.Completion,
		OnMetric: a.Spec.OnMetric,
	}
	for _, t := range a.Spec.Team {
		t.registryInto(acc)
	}
}

// Run is the Level-1 one-shot: build a runtime over this agent's registry, run
// the prompt to completion, and tear down — unless the run parks durably
// (Status "pending"), in which case the runtime is kept alive so the host can
// Resume it with the out-of-band Answer. The runtime is returned alongside for
// exactly that durable-resume path (and for List/Trace inspection).
func (a *Agent) Run(opts Options, prompt string) (TaskResult, *Runtime) {
	opts.Registry = a.Registry()
	rt := NewRuntime(opts)
	h, err := rt.Spawn(rt.Root, a.Name, nil)
	if err != nil {
		return TaskResult{Text: err.Error(), IsError: true, Status: "error"}, rt
	}
	rt.Wake(h, prompt)
	r := rt.Wait(h, 0)
	if r.Status != "pending" {
		rt.Close(rt.Root, nil)
	}
	return r, rt
}

// AsTool is the bridge: an Agent IS a Tool — a named, described, schema'd
// callable whose execute runs the agent's own loop in isolation and returns
// ONLY its final text plus metadata {agent, turns, totalTokens}. Drop it into
// the classic API's ExtraTools; the classic toolkit/client API is untouched.
func (a *Agent) AsTool(opts Options) tn.Tool {
	return tn.Tool{
		Name:        a.Name,
		Description: a.Spec.Does,
		InputSchema: tn.JSONSchema{
			"type":       "object",
			"properties": map[string]any{"prompt": map[string]any{"type": "string"}},
			"required":   []any{"prompt"},
		},
		Source: tn.SourceCustom,
		Execute: func(args map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
			prompt, _ := args["prompt"].(string)
			r, _ := a.Run(opts, prompt)
			return tn.ToolResult{
				Output:   r.Text,
				IsError:  r.IsError,
				Metadata: map[string]any{"agent": a.Name, "turns": r.Turns, "totalTokens": r.TotalTokens},
			}, nil
		},
	}
}

// Harness names the capability set an agent needs to COMPLETE its problem, plus
// the boundaries it may not cross: soul, tools, team, budget ceiling, guardrails,
// the completion gate, and §10 interpreter authority.
//
// It is a FACTORY over Spec, not a second type — so `harness` is the word you
// read while there stays exactly one type underneath, and the two can never
// drift. Model is deliberately absent: Model + Harness = Agent.
//
//	h := agents.Harness(agents.Spec{Soul: …, Tools: …, Guardrails: …})
//	a := agents.New("worker", h)
func Harness(s Spec) Spec { return s }
