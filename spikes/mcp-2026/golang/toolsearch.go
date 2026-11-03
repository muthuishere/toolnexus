package mcp2026

// Lazy tool search: with Client.ToolSearch the LLM sees two small tools instead of all N:
// search_tools(query) -> ranked {name, description, inputSchema, server}, and
// call_tool(name, arguments) to run one it found. Ranking is BM25 over name (x2) + description.

import (
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strings"
	"time"
	"unicode"
)

// Tools returns the tool definitions to give the model: every server tool, or (ToolSearch) the
// two meta-tools. Run them with Invoke.
func (c *Client) Tools() ([]obj, error) {
	if !c.ToolSearch {
		return c.listTools()
	}
	about := "this MCP server"
	if c.Description != "" {
		about = c.Description
	}
	return []obj{
		{"name": "search_tools", "description": "Search the tools of " + about + " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.",
			"inputSchema": obj{"type": "object", "properties": obj{"query": obj{"type": "string"}, "limit": obj{"type": "integer"}}, "required": []string{"query"}}},
		{"name": "call_tool", "description": "Run a tool found with search_tools.",
			"inputSchema": obj{"type": "object", "properties": obj{"name": obj{"type": "string"}, "arguments": obj{"type": "object"}}, "required": []string{"name"}}},
	}, nil
}

// Invoke runs a tool the model asked for: a server tool, or search_tools / call_tool in ToolSearch mode.
func (c *Client) Invoke(name string, args obj, respond Responder) (obj, error) {
	if !c.ToolSearch {
		return c.CallTool(name, args, respond)
	}
	switch name {
	case "search_tools":
		q, _ := args["query"].(string)
		limit := 5
		if l, ok := args["limit"].(float64); ok && l > 0 {
			limit = int(l)
		}
		hits, err := c.SearchTools(q, limit)
		if err != nil {
			return nil, err
		}
		return obj{"resultType": "complete", "content": []obj{{"type": "text", "text": mustJSON(hits)}},
			"structuredContent": obj{"tools": hits}, "isError": false}, nil
	case "call_tool":
		n, _ := args["name"].(string)
		a, _ := args["arguments"].(map[string]any)
		return c.CallTool(n, a, respond)
	}
	return nil, fmt.Errorf("unknown tool %q (ToolSearch mode exposes search_tools and call_tool)", name)
}

// SearchTools ranks the server's tools against query (BM25; name tokens count twice).
func (c *Client) SearchTools(query string, limit int) ([]obj, error) {
	tools, err := c.listTools()
	if err != nil {
		return nil, err
	}
	docs := make([][]string, len(tools))
	df := map[string]int{}
	total := 0
	for i, t := range tools {
		n, _ := t["name"].(string)
		d, _ := t["description"].(string)
		nt := tokens(n)
		docs[i] = append(append(nt, nt...), tokens(d)...)
		total += len(docs[i])
		seen := map[string]bool{}
		for _, w := range docs[i] {
			if !seen[w] {
				seen[w] = true
				df[w]++
			}
		}
	}
	avg := float64(total) / math.Max(1, float64(len(tools)))
	type hit struct {
		i     int
		score float64
	}
	var hits []hit
	for i, d := range docs {
		tf := map[string]int{}
		for _, w := range d {
			tf[w]++
		}
		s := 0.0
		for _, q := range tokens(query) {
			f := float64(tf[q])
			if f == 0 {
				continue
			}
			idf := math.Log(1 + (float64(len(tools))-float64(df[q])+0.5)/(float64(df[q])+0.5))
			s += idf * f * 2.2 / (f + 1.2*(0.25+0.75*float64(len(d))/avg))
		}
		if s > 0 {
			hits = append(hits, hit{i, s})
		}
	}
	sort.SliceStable(hits, func(a, b int) bool { return hits[a].score > hits[b].score })
	out := []obj{}
	for _, h := range hits[:min(limit, len(hits))] {
		t := tools[h.i]
		out = append(out, obj{"name": t["name"], "description": t["description"], "inputSchema": t["inputSchema"], "server": c.Description})
	}
	return out, nil
}

// listTools caches tools/list for the server's ttlMs.
func (c *Client) listTools() ([]obj, error) {
	c.toolMu.Lock()
	defer c.toolMu.Unlock()
	if c.toolCache != nil && time.Now().Before(c.toolExpiry) {
		return c.toolCache, nil
	}
	r, err := c.Call("tools/list", nil)
	if err != nil {
		return nil, err
	}
	var tools []obj
	raw, _ := r["tools"].([]any)
	for _, t := range raw {
		if m, ok := t.(map[string]any); ok {
			tools = append(tools, m)
		}
	}
	ttl, _ := r["ttlMs"].(float64)
	c.toolCache, c.toolExpiry = tools, time.Now().Add(time.Duration(ttl)*time.Millisecond)
	return tools, nil
}

// tokens lowercases and splits on non-alphanumerics and camelCase boundaries.
func tokens(s string) []string {
	var out []string
	var cur []rune
	flush := func() {
		if len(cur) > 0 {
			out = append(out, strings.ToLower(string(cur)))
			cur = cur[:0]
		}
	}
	prevLower := false
	for _, r := range s {
		switch {
		case unicode.IsUpper(r) && prevLower:
			flush()
			cur = append(cur, r)
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			cur = append(cur, r)
		default:
			flush()
		}
		prevLower = unicode.IsLower(r)
	}
	flush()
	return out
}

func mustJSON(v any) string { b, _ := json.Marshal(v); return string(b) }
