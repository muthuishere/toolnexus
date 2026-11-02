package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"unicode/utf8"
)

func tierToolkit(t *testing.T, ran *[]string) *Toolkit {
	t.Helper()
	mk := func(name, desc string, hints *ToolHints) Tool {
		tl := NativeTool(name, desc, map[string]any{"type": "object", "properties": map[string]any{"x": map[string]any{"type": "string"}}},
			func(_ context.Context, a map[string]any) (string, error) {
				*ran = append(*ran, fmt.Sprintf("%s(%v)", name, a["x"]))
				return name + " ok", nil
			})
		tl.Hints = hints
		return tl
	}
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{
		mk("zeta_read", "Read zeta.\nsecond line hidden", &ToolHints{ReadOnly: true}),
		mk("alpha_read", "Read alpha.", &ToolHints{ReadOnly: true}),
		mk("deploy", "Deploy the service to production.", &ToolHints{Destructive: true}),
		mk("lint", "Lint the code.", nil),
	}})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func TestToolTierPlanAndDescription(t *testing.T) {
	var ran []string
	tk := tierToolkit(t, &ran)
	c := CreateClient(ClientOptions{BaseURL: "http://x", Model: "m", APIKey: "k",
		ToolTiers: &ToolTierOptions{Core: []string{"zeta_read", "alpha_read"}, Deferred: []string{"deploy"}}})
	got := c.requestTools(tk)
	var names []string
	for _, tl := range got {
		names = append(names, tl.Name)
	}
	if strings.Join(names, ",") != "alpha_read,zeta_read,tool_search,call_tool" {
		t.Fatalf("tools = %v", names)
	}
	desc := got[2].Description
	if !strings.Contains(desc, "- lint — Lint the code.") || !strings.Contains(desc, "(name only): deploy") || strings.Contains(desc, "Deploy the service") {
		t.Fatalf("tool_search description:\n%s", desc)
	}
	plan := c.ToolTierPlan(tk)
	if plan["deploy"] != TierDeferred || plan["lint"] != TierListed || plan["alpha_read"] != TierCore {
		t.Fatalf("plan = %v", plan)
	}
	// a tool registered after the plan froze: not in the array or the description,
	// still found by search
	tk.Register(NativeTool("late", "A late tool.", nil, func(context.Context, map[string]any) (string, error) { return "late ok", nil }))
	again := c.requestTools(tk)
	if len(again) != 4 || again[2].Description != desc {
		t.Fatal("the tools array changed after a late Register")
	}
	r := c.toolSearch(tk, map[string]any{"query": "late"})
	if !strings.Contains(r.Output, `"name": "late"`) {
		t.Fatalf("search: %s", r.Output)
	}
}

func TestToolTierBudgetDemotesLeastUsed(t *testing.T) {
	var ran []string
	tk := tierToolkit(t, &ran)
	uses := map[string]int{"alpha_read": 9, "zeta_read": 1, "lint": 5, "deploy": 0}
	base := buildPlan(&ToolTierOptions{Core: []string{"alpha_read", "zeta_read"}}, tk.Tools())
	count := defaultCountTokens
	fixed := count(toolDef(base.search)) + count(toolDef(base.call))
	one := count(toolDef(func() Tool { x, _ := tk.Get("alpha_read"); return x }()))
	// room for tool_search/call_tool + exactly one core schema (+ slack for the
	// description growing by one listed line)
	p := buildPlan(&ToolTierOptions{Core: []string{"alpha_read", "zeta_read"}, BudgetTokens: fixed + one + 15,
		UsageCount: func(n string) int { return uses[n] }}, tk.Tools())
	if p.tier["alpha_read"] != TierCore || p.tier["zeta_read"] != TierListed {
		t.Fatalf("want the least-used core tool demoted: %v", p.tier)
	}
	// a tiny budget pushes listed tools to deferred too, least used first
	p = buildPlan(&ToolTierOptions{Core: []string{"alpha_read"}, BudgetTokens: 1,
		UsageCount: func(n string) int { return uses[n] }}, tk.Tools())
	for n, tr := range p.tier {
		if tr != TierDeferred {
			t.Fatalf("%s = %s, want everything deferred under budget 1", n, tr)
		}
	}
}

// The loop: tool_search loads deploy, the model calls it via call_tool, the
// permission hook sees the real name and hints, and the tools array is the
// same bytes on every request.
func TestToolTierLoopCallToolUnwraps(t *testing.T) {
	var ran []string
	tk := tierToolkit(t, &ran)
	var mu sync.Mutex
	var toolArrays []string
	step := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		b, _ := json.Marshal(body["tools"])
		toolArrays = append(toolArrays, string(b))
		step++
		n := step
		mu.Unlock()
		var content []any
		switch n {
		case 1:
			content = []any{map[string]any{"type": "tool_use", "id": "s1", "name": "tool_search", "input": map[string]any{"query": "select:deploy"}}}
		case 2:
			content = []any{map[string]any{"type": "tool_use", "id": "c1", "name": "call_tool", "input": map[string]any{"name": "deploy", "arguments": map[string]any{"x": "prod"}}}}
		case 3:
			content = []any{map[string]any{"type": "tool_use", "id": "d1", "name": "lint", "input": map[string]any{"x": "direct"}}}
		default:
			content = []any{map[string]any{"type": "text", "text": "done"}}
		}
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"content": content, "stop_reason": "tool_use", "usage": map[string]any{"input_tokens": 1, "output_tokens": 1}})
	}))
	defer srv.Close()
	var gated []string
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleAnthropic, Model: "m", APIKey: "k",
		ToolTiers: &ToolTierOptions{Core: []string{"alpha_read"}, Deferred: []string{"deploy"}},
		Hooks: &Hooks{BeforeTool: func(_ context.Context, ev BeforeToolEvent) (*ToolOverride, error) {
			d := "?"
			if ev.Hints != nil {
				d = fmt.Sprint(ev.Hints.Destructive)
			}
			gated = append(gated, ev.Name+":"+d)
			return nil, nil
		}}})
	res, err := c.Run(context.Background(), "ship it", tk)
	if err != nil || res.Status != "done" {
		t.Fatalf("res=%+v err=%v", res, err)
	}
	if !strings.Contains(res.ToolCalls[0].Output, `"name": "deploy"`) {
		t.Fatalf("search output: %s", res.ToolCalls[0].Output)
	}
	if strings.Join(ran, ",") != "deploy(prod),lint(direct)" {
		t.Fatalf("ran = %v", ran)
	}
	// RunResult.ToolCalls names the real tool with its inner args, like hooks and stream events.
	var recorded []string
	for _, tc := range res.ToolCalls {
		recorded = append(recorded, fmt.Sprintf("%s(%v)", tc.Name, tc.Args["x"]))
	}
	if strings.Join(recorded, ",") != "tool_search(<nil>),deploy(prod),lint(direct)" {
		t.Fatalf("ToolCalls = %v", recorded)
	}
	if strings.Join(gated, ",") != "deploy:true,lint:?" {
		t.Fatalf("BeforeTool saw %v (tool_search is engine-internal; call_tool must be unwrapped)", gated)
	}
	for i := range toolArrays {
		if toolArrays[i] != toolArrays[0] {
			t.Fatalf("tools array changed at request %d", i+1)
		}
	}
}

func TestToolTierStreamNamesRealTool(t *testing.T) {
	var ran []string
	tk := tierToolkit(t, &ran)
	step := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		step++
		w.Header().Set("content-type", "text/event-stream")
		sse := func(v any) { b, _ := json.Marshal(v); fmt.Fprintf(w, "data: %s\n\n", b) }
		if step == 1 {
			args, _ := json.Marshal(map[string]any{"name": "deploy", "arguments": map[string]any{"x": "prod"}})
			sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{
				map[string]any{"index": 0, "id": "c1", "type": "function", "function": map[string]any{"name": "call_tool", "arguments": string(args)}}}}}}})
			sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "tool_calls"}}})
		} else {
			sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": "done"}, "finish_reason": "stop"}}})
		}
		fmt.Fprint(w, "data: [DONE]\n\n")
	}))
	defer srv.Close()
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k",
		ToolTiers: &ToolTierOptions{Core: []string{"alpha_read"}}})
	ch, err := c.Stream(context.Background(), "go", tk)
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for ev := range ch {
		if ev.Type == "tool_call" || ev.Type == "tool_result" {
			got = append(got, fmt.Sprintf("%s:%s:%s:%v", ev.Type, ev.Name, ev.Via, ev.Args["x"]))
		}
		if ev.Type == "error" {
			t.Fatal(ev.Err)
		}
	}
	if strings.Join(got, ",") != "tool_call:deploy:call_tool:prod,tool_result:deploy:call_tool:<nil>" {
		t.Fatalf("events = %v", got)
	}
	if strings.Join(ran, ",") != "deploy(prod)" {
		t.Fatalf("ran = %v", ran)
	}
}

// The 120-byte cut never splits a UTF-8 character: it keeps the longest valid
// prefix of at most 120 bytes (é is 2 bytes; byte 120 falls inside it).
func TestToolTierFirstLineCutsOnRuneBoundary(t *testing.T) {
	in := strings.Repeat("a", 119) + "é tail"
	got := firstLine(in)
	if want := strings.Repeat("a", 119) + "…"; got != want {
		t.Fatalf("firstLine = %q, want %q", got, want)
	}
	if !utf8.ValidString(got) {
		t.Fatal("invalid UTF-8")
	}
	// exactly 120 bytes ending on a whole 3-byte rune is kept whole
	in = strings.Repeat("a", 117) + "€" + "z"
	if got := firstLine(in); got != strings.Repeat("a", 117)+"€…" {
		t.Fatalf("firstLine = %q", got)
	}
}

// tool_search output is json.MarshalIndent: sorted keys, two-space indent and
// <, >, & escaped as <, >, &. Every port pins these bytes.
func TestToolSearchJSONIsHTMLEscaped(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{
		NativeTool("amp", "Use <a & b> here", map[string]any{"type": "object", "properties": map[string]any{"x": map[string]any{"type": "string"}}},
			func(context.Context, map[string]any) (string, error) { return "", nil }),
	}})
	if err != nil {
		t.Fatal(err)
	}
	c := CreateClient(ClientOptions{BaseURL: "http://x", Model: "m", APIKey: "k", ToolTiers: &ToolTierOptions{}})
	r := c.toolSearch(tk, map[string]any{"query": "select:amp"})
	want := "Loaded tools (call by name, or with call_tool):\n[\n  {\n    \"description\": \"Use \\u003ca \\u0026 b\\u003e here\",\n    \"inputSchema\": {\n      \"properties\": {\n        \"x\": {\n          \"type\": \"string\"\n        }\n      },\n      \"type\": \"object\"\n    },\n    \"name\": \"amp\"\n  }\n]"
	if r.Output != want {
		t.Fatalf("got:\n%s\nwant:\n%s", r.Output, want)
	}
}
