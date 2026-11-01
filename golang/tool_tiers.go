package toolnexus

// Tool tiers (change add-prompt-cache, phase 2). With ClientOptions.ToolTiers
// set, only CORE tools are sent with full schemas. LISTED tools appear as
// "name — one line" and DEFERRED tools by name only, both inside the
// description of a `tool_search` tool that returns full schemas on demand (as
// a tool result: the tools array itself never changes mid-session, so the
// cached prefix survives). A loaded tool is called directly by name, or through
// `call_tool` {name, arguments} on providers that refuse calls to tools absent
// from the array; the loop unwraps call_tool, so hooks, hints and permission
// gates always see the real tool. golang only for now; other ports pending.

import (
	"encoding/json"
	"sort"
	"strings"
	"sync"
)

// ToolTierOptions configures ClientOptions.ToolTiers.
type ToolTierOptions struct {
	// Core tools are always sent with their full schema.
	Core []string
	// Deferred tools are named only. Every other tool is Listed (name + one line).
	// A tool registered after the plan is frozen is deferred and is found by search.
	Deferred []string
	// BudgetTokens caps the tokens of what is sent up front: the core schemas plus
	// the tool_search and call_tool definitions. 0 ⇒ 5% of ContextWindow; both 0
	// ⇒ no budget. Over budget, the least-used core tools move to listed, then the
	// least-used listed tools to deferred, until it fits.
	BudgetTokens  int
	ContextWindow int
	// UsageCount is the host's per-tool usage stat (higher = more used), read only
	// when the plan is built. nil ⇒ all zero (ties break by larger schema first,
	// then name).
	UsageCount func(name string) int
	// CountTokens estimates tokens of a JSON-encodable value; default ceil(bytes/4).
	CountTokens func(v any) int
}

// ToolTier is where a tool sits in a plan.
type ToolTier string

const (
	TierCore     ToolTier = "core"
	TierListed   ToolTier = "listed"
	TierDeferred ToolTier = "deferred"
)

// toolPlan is the frozen tier assignment for one toolkit.
type toolPlan struct {
	tier   map[string]ToolTier
	core   []string // sorted
	listed []string // sorted
	defer_ []string // sorted
	search Tool
	call   Tool
}

type tierCache struct {
	mu    sync.Mutex
	plans map[*Toolkit]*toolPlan
}

// ToolTierPlan reports the frozen plan for tk (built on first use), for a host
// UI or log: tool name → tier. Nil when ToolTiers is not set.
func (c *Client) ToolTierPlan(tk *Toolkit) map[string]ToolTier {
	p := c.plan(tk)
	if p == nil {
		return nil
	}
	out := make(map[string]ToolTier, len(p.tier))
	for k, v := range p.tier {
		out[k] = v
	}
	return out
}

// ResetToolTiers forgets tk's plan so the next request rebuilds it — call it at
// a new session start (e.g. after updating usage stats). Mid-session it breaks
// the cached prefix.
func (c *Client) ResetToolTiers(tk *Toolkit) {
	c.tiers.mu.Lock()
	delete(c.tiers.plans, tk)
	c.tiers.mu.Unlock()
}

func (c *Client) plan(tk *Toolkit) *toolPlan {
	o := c.opts.ToolTiers
	if o == nil || tk == nil {
		return nil
	}
	c.tiers.mu.Lock()
	defer c.tiers.mu.Unlock()
	if c.tiers.plans == nil {
		c.tiers.plans = map[*Toolkit]*toolPlan{}
	}
	if p, ok := c.tiers.plans[tk]; ok {
		return p
	}
	p := buildPlan(o, tk.Tools())
	c.tiers.plans[tk] = p
	return p
}

func defaultCountTokens(v any) int {
	b, _ := json.Marshal(v)
	return (len(b) + 3) / 4
}

func buildPlan(o *ToolTierOptions, tools []Tool) *toolPlan {
	count := o.CountTokens
	if count == nil {
		count = defaultCountTokens
	}
	usage := func(n string) int {
		if o.UsageCount == nil {
			return 0
		}
		return o.UsageCount(n)
	}
	byName := map[string]Tool{}
	for _, t := range tools {
		byName[t.Name] = t
	}
	tier := map[string]ToolTier{}
	for _, t := range tools {
		tier[t.Name] = TierListed
	}
	for _, n := range o.Deferred {
		if _, ok := tier[n]; ok {
			tier[n] = TierDeferred
		}
	}
	for _, n := range o.Core {
		if _, ok := tier[n]; ok {
			tier[n] = TierCore
		}
	}
	p := &toolPlan{tier: tier}
	p.rebuild(byName)

	budget := o.BudgetTokens
	if budget == 0 && o.ContextWindow > 0 {
		budget = o.ContextWindow / 20
	}
	if budget > 0 {
		cost := func() int {
			n := count(toolDef(p.search)) + count(toolDef(p.call))
			for _, name := range p.core {
				n += count(toolDef(byName[name]))
			}
			return n
		}
		// least used first; ties: bigger schema first, then name
		order := func(names []string) []string {
			out := append([]string(nil), names...)
			sort.SliceStable(out, func(i, j int) bool {
				ui, uj := usage(out[i]), usage(out[j])
				if ui != uj {
					return ui < uj
				}
				si, sj := count(toolDef(byName[out[i]])), count(toolDef(byName[out[j]]))
				if si != sj {
					return si > sj
				}
				return out[i] < out[j]
			})
			return out
		}
		for _, n := range order(p.core) {
			if cost() <= budget {
				break
			}
			tier[n] = TierListed
			p.rebuild(byName)
		}
		for _, n := range order(p.listed) {
			if cost() <= budget {
				break
			}
			tier[n] = TierDeferred
			p.rebuild(byName)
		}
	}
	return p
}

func toolDef(t Tool) map[string]any {
	return map[string]any{"name": t.Name, "description": t.Description, "inputSchema": t.InputSchema}
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}
	const max = 120
	if len(s) > max {
		s = s[:max] + "…"
	}
	return s
}

// rebuild recomputes the sorted tier lists and the tool_search description.
func (p *toolPlan) rebuild(byName map[string]Tool) {
	p.core, p.listed, p.defer_ = nil, nil, nil
	for n, t := range p.tier {
		switch t {
		case TierCore:
			p.core = append(p.core, n)
		case TierListed:
			p.listed = append(p.listed, n)
		default:
			p.defer_ = append(p.defer_, n)
		}
	}
	sort.Strings(p.core)
	sort.Strings(p.listed)
	sort.Strings(p.defer_)
	var sb strings.Builder
	sb.WriteString("Load the full schema of tools that are not shown in full, then call them by name (or with call_tool). Query by keywords, or \"select:a,b\" for exact names.")
	if len(p.listed) > 0 {
		sb.WriteString("\n\nAvailable tools:")
		for _, n := range p.listed {
			sb.WriteString("\n- " + n)
			if d := firstLine(byName[n].Description); d != "" {
				sb.WriteString(" — " + d)
			}
		}
	}
	if len(p.defer_) > 0 {
		sb.WriteString("\n\nMore tools (name only): " + strings.Join(p.defer_, ", "))
	}
	p.search = Tool{
		Name:        "tool_search",
		Description: sb.String(),
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{
			"query": map[string]any{"type": "string", "description": "Keywords, or select:name1,name2"},
			"limit": map[string]any{"type": "number", "description": "Maximum results for a keyword query (default 5)"},
		}, "required": []string{"query"}, "additionalProperties": false},
		Source: SourceBuiltin,
		Hints:  &ToolHints{ReadOnly: true},
	}
	p.call = Tool{
		Name:        "call_tool",
		Description: "Call a tool loaded with tool_search, by name, with its arguments.",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{
			"name":      map[string]any{"type": "string"},
			"arguments": map[string]any{"type": "object"},
		}, "required": []string{"name"}, "additionalProperties": false},
		Source: SourceBuiltin,
	}
}

// requestTools is the tools array for a request: core tools (sorted) then
// tool_search and call_tool. Without tiers it is every tool, as before.
func (c *Client) requestTools(tk *Toolkit) []Tool {
	p := c.plan(tk)
	if p == nil {
		return tk.Tools()
	}
	out := make([]Tool, 0, len(p.core)+2)
	for _, n := range p.core {
		if t, ok := tk.Get(n); ok {
			out = append(out, t)
		}
	}
	return append(out, p.search, p.call)
}

// unwrapCall maps call_tool{name, arguments} to the real call. ok=false when
// tiers are off or name is not call_tool.
func (c *Client) unwrapCall(name string, args map[string]any) (string, map[string]any, bool) {
	if c.opts.ToolTiers == nil || name != "call_tool" {
		return name, args, false
	}
	real, _ := args["name"].(string)
	inner, _ := args["arguments"].(map[string]any)
	if inner == nil {
		inner = map[string]any{}
	}
	return real, inner, true
}

// toolSearch runs tool_search against tk (every tool, any tier).
func (c *Client) toolSearch(tk *Toolkit, args map[string]any) ToolResult {
	q, _ := args["query"].(string)
	q = strings.TrimSpace(q)
	if q == "" {
		return ToolResult{Output: "tool_search: query is required", IsError: true}
	}
	all := tk.Tools()
	var picked []Tool
	if strings.HasPrefix(q, "select:") {
		var missing []string
		for _, n := range strings.Split(strings.TrimPrefix(q, "select:"), ",") {
			n = strings.TrimSpace(n)
			if n == "" {
				continue
			}
			if t, ok := tk.Get(n); ok {
				picked = append(picked, t)
			} else {
				missing = append(missing, n)
			}
		}
		if len(missing) > 0 && len(picked) == 0 {
			return ToolResult{Output: "tool_search: no such tool: " + strings.Join(missing, ", "), IsError: true}
		}
	} else {
		limit := 5
		if l, ok := argNumber(args, "limit"); ok && l >= 1 {
			limit = int(l)
		}
		terms := strings.Fields(strings.ToLower(q))
		type scored struct {
			t     Tool
			score int
		}
		var hits []scored
		for _, t := range all {
			name, desc := strings.ToLower(t.Name), strings.ToLower(t.Description)
			s := 0
			for _, term := range terms {
				if strings.Contains(name, term) {
					s += 3
				}
				if strings.Contains(desc, term) {
					s++
				}
			}
			if s > 0 {
				hits = append(hits, scored{t, s})
			}
		}
		sort.SliceStable(hits, func(i, j int) bool {
			if hits[i].score != hits[j].score {
				return hits[i].score > hits[j].score
			}
			return hits[i].t.Name < hits[j].t.Name
		})
		for i := 0; i < len(hits) && i < limit; i++ {
			picked = append(picked, hits[i].t)
		}
	}
	if len(picked) == 0 {
		return ToolResult{Output: "No matching tools.", Metadata: map[string]any{"count": 0}}
	}
	defs := make([]any, 0, len(picked))
	names := make([]string, 0, len(picked))
	for _, t := range picked {
		defs = append(defs, toolDef(t))
		names = append(names, t.Name)
	}
	b, _ := json.MarshalIndent(defs, "", "  ")
	return ToolResult{
		Output:   "Loaded tools (call by name, or with call_tool):\n" + string(b),
		Metadata: map[string]any{"count": len(picked), "tools": names},
	}
}
