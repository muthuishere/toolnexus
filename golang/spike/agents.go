// Level-1/2/bridge UX sugar over the substrate (see audit doc "The UX").
// Go port of js/spike/agents.ts. One new noun: Agent(). Everything compiles to
// the six verbs in runtime.go.
//
//	explore := spike.NewAgent("explore", spike.AgentSpec{Does: "...", Tools: []tn.Tool{lookup}})
//	coder := spike.NewAgent("coder", spike.AgentSpec{Does: "...", SoulFile: "AGENTS.md", Team: []*spike.Agent{explore}})
//	r, _ := coder.Run(rtOpts, "fix the bug")
//
//	mia := spike.AgentFromDir("~/mia", nil) // the directory IS the agent
package spike

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// AgentSpec declares an agent at the UX layer.
type AgentSpec struct {
	// Does is what the delegating model sees — the routing description.
	Does string
	// Tools is the toolkit VIEW for this agent (scoping = the whole security model).
	Tools []tn.Tool
	// Soul is identity as inline text (AGENTS.md / SOUL.md content).
	Soul string
	// SoulFile is the path form of Soul — read at registry-build time.
	SoulFile string
	// Team: task-tool targets — listing agents here IS the subagent wiring.
	Team []*Agent
	Budget *Budget
	// Model id; "" ⇒ "inherit" (runtime default).
	Model string
	// WaitFor is the §10 interpreter authority for this agent's subtree.
	WaitFor func(tn.Request) (tn.Answer, error)
	OnSpawn func(h *Handle)
	OnClose func(h *Handle, reason string)
}

// Agent is the one new noun.
type Agent struct {
	Name string
	Spec AgentSpec
}

// NewAgent builds an Agent.
func NewAgent(name string, spec AgentSpec) *Agent {
	return &Agent{Name: name, Spec: spec}
}

// Registry collects this agent + its whole team graph into a runtime registry.
func (a *Agent) Registry() map[string]AgentDef {
	acc := map[string]AgentDef{}
	a.registryInto(acc)
	return acc
}

func (a *Agent) registryInto(acc map[string]AgentDef) {
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
	targets := make([]string, 0, len(a.Spec.Team))
	for _, t := range a.Spec.Team {
		targets = append(targets, t.Name)
	}
	acc[a.Name] = AgentDef{
		Name:         a.Name,
		Description:  a.Spec.Does,
		SystemPrompt: soul,
		Model:        model,
		Tools:        a.Spec.Tools,
		Budget:       a.Spec.Budget,
		WaitFor:      a.Spec.WaitFor,
		OnSpawn:      a.Spec.OnSpawn,
		OnClose:      a.Spec.OnClose,
		// team scoping: task targets = ONLY this agent's team (not the whole registry)
		TaskTargets: targets,
	}
	for _, t := range a.Spec.Team {
		t.registryInto(acc)
	}
}

// Run — Level 1: one-shot. Builds a runtime, runs to completion, tears down.
// The runtime is returned alongside for durable resume (JS attaches it to the
// result; Go returns it).
func (a *Agent) Run(rtOpts RuntimeOptions, prompt string) (TaskResult, *AgentRuntime) {
	rtOpts.Registry = a.Registry()
	rt := NewAgentRuntime(rtOpts)
	h, err := rt.Spawn(rt.Root, a.Name, nil)
	if err != nil {
		return TaskResult{Text: err.Error(), IsError: true, Status: "done"}, rt
	}
	rt.Wake(h, prompt)
	r := rt.Wait(h, 0)
	if r.Status != "pending" {
		rt.Close(rt.Root, nil)
	}
	return r, rt
}

// AsTool — the bridge: an Agent IS a Tool — drop it into the OLD API's ExtraTools.
func (a *Agent) AsTool(rtOpts RuntimeOptions) tn.Tool {
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
			r, _ := a.Run(rtOpts, prompt)
			return tn.ToolResult{
				Output:   r.Text,
				IsError:  r.IsError,
				Metadata: map[string]any{"agent": a.Name, "turns": r.Turns, "totalTokens": r.TotalTokens},
			}, nil
		},
	}
}

// BootstrapOrder — Level 2 discovery order (all optional, openclaw order).
var BootstrapOrder = []string{"AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md"}

const maxBootstrapFileBytes = 2 * 1024 * 1024

// AgentFromDir — Level 2: the directory IS the agent. Discovered files are
// injected as named "## <file>" sections at session start (cache doctrine:
// onSpawn reads memory in).
func AgentFromDir(dir string, opts *AgentSpec, name string) *Agent {
	var sections []string
	for _, f := range BootstrapOrder {
		p := filepath.Join(dir, f)
		b, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		body := string(b)
		if len(b) > maxBootstrapFileBytes {
			body = body[:maxBootstrapFileBytes] + "\n[truncated: file exceeds bootstrap cap]"
		}
		sections = append(sections, fmt.Sprintf("## %s\n\n%s", f, strings.TrimSpace(body)))
	}
	if name == "" {
		name = filepath.Base(dir)
	}
	spec := AgentSpec{}
	if opts != nil {
		spec = *opts
	}
	if spec.Does == "" {
		spec.Does = "persona agent from " + dir
	}
	spec.Soul = strings.Join(sections, "\n\n")
	return NewAgent(name, spec)
}

// HeartbeatOK is the silent-wake sentinel — a heartbeat turn answering this
// produces no outbound report.
const HeartbeatOK = "HEARTBEAT_OK"

const heartbeatPrompt = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK."

// StartedAgent is a long-lived persona started by StartAgent.
type StartedAgent struct {
	Handle *Handle
	RT     *AgentRuntime
	stop   chan struct{}
	ticker *time.Ticker
}

// Stop halts the heartbeat and closes the runtime (leaf-first cascade).
func (s *StartedAgent) Stop() {
	if s.ticker != nil {
		s.ticker.Stop()
	}
	close(s.stop)
	s.RT.Close(s.RT.Root, nil)
}

// StartAgent — Level 2: start a long-lived persona. The heartbeat posts a tick
// to its own inbox (unsolicited rail; ticks coalesce) and wakes it at idle;
// HEARTBEAT_OK results stay silent.
func StartAgent(a *Agent, rtOpts RuntimeOptions, everyMs int, onReport func(text string)) (*StartedAgent, error) {
	rtOpts.Registry = a.Registry()
	rt := NewAgentRuntime(rtOpts)
	handle, err := rt.Spawn(rt.Root, a.Name, nil)
	if err != nil {
		return nil, err
	}
	s := &StartedAgent{Handle: handle, RT: rt, stop: make(chan struct{})}
	if everyMs > 0 {
		s.ticker = time.NewTicker(time.Duration(everyMs) * time.Millisecond)
		go func() {
			for {
				select {
				case <-s.stop:
					return
				case <-s.ticker.C:
					rt.Post(handle, InboxItem{From: "clock", Channel: "timer", Text: "tick"})
					if rt.StateOf(handle) == StateIdle {
						go func() {
							r := rt.RunTurn(handle, heartbeatPrompt)
							if !r.IsError && !strings.Contains(r.Text, HeartbeatOK) && onReport != nil {
								onReport(r.Text)
							}
						}()
					}
				}
			}
		}()
	}
	return s, nil
}
