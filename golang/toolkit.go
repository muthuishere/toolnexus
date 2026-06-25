// Toolkit — aggregates dynamic MCP tools + the skill tool + custom tools into a
// single uniform list, with provider adapters and a single Execute() router.
package toolnexus

import (
	"context"
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
}

// Toolkit aggregates all tools and routes execution.
type Toolkit struct {
	order  []string
	byName map[string]Tool
	mcp    *McpSource
	skill  *SkillSource
}

// CreateToolkit builds a Toolkit from the given options.
func CreateToolkit(ctx context.Context, opts Options) (*Toolkit, error) {
	var (
		mcp   *McpSource
		skill *SkillSource
	)

	if opts.McpConfig != nil {
		m, err := LoadMcp(opts.McpConfig)
		if err != nil {
			return nil, err
		}
		mcp = m
	}
	if len(opts.SkillsDir) > 0 {
		skill = LoadSkills(opts.SkillsDir...)
	}

	tk := &Toolkit{byName: map[string]Tool{}, mcp: mcp, skill: skill}

	var all []Tool
	if mcp != nil {
		all = append(all, mcp.Tools...)
	}
	if skill != nil {
		all = append(all, skill.Tool)
	}
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
