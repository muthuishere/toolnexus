// Toolkit — aggregates dynamic MCP tools + the skill tool + custom tools into a
// single uniform list, with provider adapters and a single Execute() router.
package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
)

// Options configures CreateToolkit.
type Options struct {
	// McpConfig is a path to an MCP config file, raw JSON bytes, an McpConfig,
	// or a parsed map. Nil/empty skips MCP loading.
	McpConfig any
	// SkillsDir is one or more skill roots. Empty skips skill loading.
	SkillsDir []string
	// ExtraTools are your own custom tools, always added to the toolkit.
	ExtraTools []Tool
	// Builtins toggles the built-in tool source (§4A). Default ON. Accepts nil
	// (default on), a bool, or a BuiltinsConfig — same precedence as MCP. When
	// nil, a top-level `builtins` key on a parsed McpConfig map is consulted.
	Builtins any
	// Agents are remote A2A agents — each advertised skill becomes a tool
	// (source:"a2a"). A top-level `agents` block on a parsed McpConfig map is
	// merged in as well.
	Agents []Agent
	// WaitFor is a host resolver for out-of-band input (§10). When set, connected
	// MCP servers may elicit input from the human mid-`tools/call` and it is bridged
	// onto this WaitFor (form→kind:"input", URL→kind:"authorization"). Typically the
	// same function passed to the client. Omit ⇒ MCP elicitation is not advertised.
	WaitFor func(Request) (Answer, error)
}

// Toolkit aggregates all tools and routes execution.
type Toolkit struct {
	order  []string
	byName map[string]Tool
	mcp    *McpSource
	skill  *SkillSource
	// a2aConfig is the A2A inbound profile from a top-level `a2a` config block;
	// Serve() falls back to it when no inline A2A option is given.
	a2aConfig *A2AConfig
	// mcpServerConfig is the MCP inbound serve profile from a top-level
	// `mcpServer` (singular) config block — distinct from the client-side plural
	// `mcpServers`. Serve() falls back to it when no inline MCP option is given.
	mcpServerConfig *MCPServeConfig
}

// CreateToolkit builds a Toolkit from the given options.
func CreateToolkit(ctx context.Context, opts Options) (*Toolkit, error) {
	var (
		mcp   *McpSource
		skill *SkillSource
	)

	if opts.McpConfig != nil {
		m, err := LoadMcp(opts.McpConfig, opts.WaitFor)
		if err != nil {
			return nil, err
		}
		mcp = m
	}
	if len(opts.SkillsDir) > 0 {
		skill = LoadSkills(opts.SkillsDir...)
	}

	tk := &Toolkit{byName: map[string]Tool{}, mcp: mcp, skill: skill}

	// The toggle comes from the `Builtins` option, or a top-level `builtins` key
	// on a parsed McpConfig map — same precedence as MCP's isEnabled.
	builtinsCfg := opts.Builtins
	if builtinsCfg == nil {
		if m, ok := opts.McpConfig.(map[string]any); ok {
			if b, exists := m["builtins"]; exists {
				builtinsCfg = b
			}
		}
	}
	builtins := SelectBuiltins(builtinsCfg)

	// Remote A2A agents come from the `Agents` option plus a top-level `agents`
	// block on a parsed McpConfig map (mirrors mcpServers). Each is resolved to
	// its skill tools; a failing agent is isolated and never breaks the toolkit.
	agentDescriptors := append([]Agent{}, opts.Agents...)
	if m, ok := opts.McpConfig.(map[string]any); ok {
		if a, exists := m["agents"]; exists {
			var block AgentsConfig
			if b, err := json.Marshal(a); err == nil {
				_ = json.Unmarshal(b, &block)
			}
			agentDescriptors = append(agentDescriptors, ParseAgentsConfig(block)...)
		}
	}
	var agentTools []Tool
	for _, ag := range agentDescriptors {
		ts, err := AgentTools(ctx, ag)
		if err != nil {
			log.Printf("[toolnexus] A2A agent %q failed: %v", ag.Card, err)
			continue
		}
		agentTools = append(agentTools, ts...)
	}

	// A2A inbound profile: a top-level `a2a` block on a parsed McpConfig map
	// (mirrors builtins/agents). Serve() prefers an inline A2A over this.
	if m, ok := opts.McpConfig.(map[string]any); ok {
		if a, exists := m["a2a"]; exists {
			var cfg A2AConfig
			if b, err := json.Marshal(a); err == nil {
				_ = json.Unmarshal(b, &cfg)
			}
			tk.a2aConfig = &cfg
		}
	}

	// MCP inbound serve profile: a top-level `mcpServer` (singular) block on a
	// parsed McpConfig map — distinct from the client-side `mcpServers`.
	// Serve() prefers an inline MCP option over this.
	if m, ok := opts.McpConfig.(map[string]any); ok {
		if v, exists := m["mcpServer"]; exists {
			var cfg MCPServeConfig
			if b, err := json.Marshal(v); err == nil {
				_ = json.Unmarshal(b, &cfg)
			}
			tk.mcpServerConfig = &cfg
		}
	}

	// Builtins are the lowest-precedence source: a host ExtraTools entry with the
	// same name shadows a builtin (SPEC §4). Drop shadowed builtins up front,
	// then apply the normal first-wins dedupe. Order: MCP → skill → builtin →
	// agents → extras.
	extraNames := make(map[string]bool, len(opts.ExtraTools))
	for _, t := range opts.ExtraTools {
		extraNames[t.Name] = true
	}

	var all []Tool
	if mcp != nil {
		all = append(all, mcp.Tools...)
	}
	if skill != nil {
		all = append(all, skill.Tool)
	}
	for _, b := range builtins {
		if !extraNames[b.Name] {
			all = append(all, b)
		}
	}
	all = append(all, agentTools...)
	all = append(all, opts.ExtraTools...)

	for _, t := range all {
		if _, exists := tk.byName[t.Name]; exists {
			log.Printf("[toolnexus] duplicate tool name %q — keeping first", t.Name)
			continue
		}
		tk.byName[t.Name] = t
		tk.order = append(tk.order, t.Name)
	}

	return tk, nil
}

// Register adds tools to the toolkit (first-name-wins; duplicates are skipped
// with a warning). Returns the toolkit for chaining.
func (tk *Toolkit) Register(tools ...Tool) *Toolkit {
	for _, t := range tools {
		if _, exists := tk.byName[t.Name]; exists {
			log.Printf("[toolnexus] duplicate tool name %q — keeping first", t.Name)
			continue
		}
		tk.byName[t.Name] = t
		tk.order = append(tk.order, t.Name)
	}
	return tk
}

// AddAgent fetches a remote A2A agent's card at runtime and registers its skills
// as tools. agentOrCardURL is an Agent value or a bare card URL string; opts
// (may be nil) supplies headers/timeout/pollEvery when a string is given.
// First-name-wins dedupe.
func (tk *Toolkit) AddAgent(ctx context.Context, agentOrCardURL any, opts *Agent) (*Toolkit, error) {
	var ag Agent
	switch v := agentOrCardURL.(type) {
	case string:
		ag = Agent{Card: v}
		if opts != nil {
			ag.Headers = opts.Headers
			ag.Timeout = opts.Timeout
			ag.PollEvery = opts.PollEvery
		}
	case Agent:
		ag = v
	default:
		return tk, fmt.Errorf("addAgent: unsupported argument type %T", agentOrCardURL)
	}
	tools, err := AgentTools(ctx, ag)
	if err != nil {
		return tk, err
	}
	return tk.Register(tools...), nil
}

// Serve exposes this toolkit as an agent over HTTP. When the A2A profile is
// present (ServeOptions.A2A, or a top-level `a2a` config block the toolkit was
// built from), it mounts the A2A Agent Card (/.well-known/agent-card.json, built
// from skills) and a JSON-RPC endpoint (SendMessage + GetTask) fulfilled by the
// client. A message's A2A contextId keys the conversation via Client.Ask, so a
// peer's turns are remembered through the client's ConversationStore; no
// contextId ⇒ a stateless Run. When A2A is absent, no A2A routes are mounted.
// Returns a stoppable handle. Mirrors js Toolkit.serve and SPEC §7B / §8.
func (tk *Toolkit) Serve(addr string, opts ServeOptions) (*ServeHandle, error) {
	a2a := opts.A2A
	if a2a == nil {
		a2a = tk.a2aConfig
	}
	mcp := opts.MCP
	if mcp == nil {
		mcp = tk.mcpServerConfig
	}
	var skills []SkillInfo
	if tk.skill != nil {
		for _, s := range tk.skill.Skills {
			skills = append(skills, s)
		}
	}
	var mcpTools []Tool
	if mcp != nil {
		mcpTools = tk.Tools()
	}
	client := opts.Client
	return startA2AServer(startServerOptions{
		addr:   addr,
		a2a:    a2a,
		skills: skills,
		runTask: func(text, contextID string) (RunResult, error) {
			// A message's A2A contextId keys the conversation via Client.Ask, so a
			// peer's turns are remembered through the client's ConversationStore; no
			// contextId ⇒ a stateless Run.
			if contextID != "" {
				return client.Ask(context.Background(), text, tk, contextID)
			}
			return client.Run(context.Background(), text, tk)
		},
		onTask:   opts.OnTask,
		mcp:      mcp,
		mcpTools: mcpTools,
		onCall:   opts.OnCall,
	})
}

// Tools returns all tools (mcp tools + skill tool + extras), in insertion order.
func (tk *Toolkit) Tools() []Tool {
	out := make([]Tool, 0, len(tk.order))
	for _, name := range tk.order {
		out = append(out, tk.byName[name])
	}
	return out
}

// Get returns a tool by name, or false if absent.
func (tk *Toolkit) Get(name string) (Tool, bool) {
	t, ok := tk.byName[name]
	return t, ok
}

// Execute routes to the right tool and runs it.
func (tk *Toolkit) Execute(ctx context.Context, name string, args map[string]any) (ToolResult, error) {
	tool, ok := tk.byName[name]
	if !ok {
		return ToolResult{Output: fmt.Sprintf("Unknown tool: %s", name), IsError: true}, nil
	}
	var tctx *ToolContext
	if ctx != nil {
		tctx = &ToolContext{Ctx: ctx}
	}
	if args == nil {
		args = map[string]any{}
	}
	return tool.Execute(args, tctx)
}

// executeWithAnswer is Execute with a §10 resolution attached to the context
// (ToolContext.Answer). Used by the client loop to re-run a suspended tool once
// after WaitFor resolves.
func (tk *Toolkit) executeWithAnswer(ctx context.Context, name string, args map[string]any, answer *Answer) (ToolResult, error) {
	tool, ok := tk.byName[name]
	if !ok {
		return ToolResult{Output: fmt.Sprintf("Unknown tool: %s", name), IsError: true}, nil
	}
	if args == nil {
		args = map[string]any{}
	}
	return tool.Execute(args, &ToolContext{Ctx: ctx, Answer: answer})
}

// SkillsPrompt returns the markdown skill catalog for the system prompt.
func (tk *Toolkit) SkillsPrompt() string {
	if tk.skill == nil {
		return ""
	}
	return tk.skill.Prompt()
}

// McpStatus returns each server's connection status.
func (tk *Toolkit) McpStatus() map[string]McpStatus {
	if tk.mcp == nil {
		return map[string]McpStatus{}
	}
	return tk.mcp.Status
}

// ToOpenAI returns the OpenAI tool schema for all tools.
func (tk *Toolkit) ToOpenAI() []any { return ToOpenAI(tk.Tools()) }

// ToAnthropic returns the Anthropic tool schema for all tools.
func (tk *Toolkit) ToAnthropic() []any { return ToAnthropic(tk.Tools()) }

// ToGemini returns the Gemini tool schema for all tools.
func (tk *Toolkit) ToGemini() []any { return ToGemini(tk.Tools()) }

// Close disconnects every MCP client.
func (tk *Toolkit) Close() {
	if tk.mcp != nil {
		tk.mcp.Close()
	}
}
