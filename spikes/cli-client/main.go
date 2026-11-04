// Live run: every installed agent CLI makes one real tool call through the
// toolnexus loop. Usage: go run . [style ...]   (default: every installed style)
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

type liveResult struct {
	Style       string   `json:"style"`
	Installed   bool     `json:"installed"`
	OK          bool     `json:"ok"`
	TotalMs     int64    `json:"totalMs"`
	CallMs      []int64  `json:"invocationMs"`
	PromptBytes []int    `json:"promptBytes"`
	ToolCalls   []string `json:"toolCalls"`
	Text        string   `json:"text"`
	Error       string   `json:"error,omitempty"`
	RawReplies  []string `json:"rawReplies"`
}

func main() {
	all, err := BuiltinStyles()
	if err != nil {
		panic(err)
	}
	names := os.Args[1:]
	if len(names) == 0 {
		for n := range all {
			names = append(names, n)
		}
		sort.Strings(names)
	}
	var results []liveResult
	for _, name := range names {
		r := liveResult{Style: name, Installed: Available(CliOptions{Style: CliStyle(name), Command: name})}
		if !r.Installed {
			fmt.Printf("%-9s not installed — skipped\n", name)
			results = append(results, r)
			continue
		}
		r = runOne(name, r)
		status := "OK"
		if !r.OK {
			status = "FAIL"
		}
		fmt.Printf("%-9s %-4s %6dms invocations=%v tools=%v text=%q err=%s\n", name, status, r.TotalMs, r.CallMs, r.ToolCalls, trunc(r.Text, 80), r.Error)
		results = append(results, r)
	}
	b, _ := json.MarshalIndent(results, "", "  ")
	_ = os.WriteFile("live-results.json", append(b, '\n'), 0o644)
}

func runOne(name string, r liveResult) liveResult {
	lookup := tn.NativeTool("lookup_order", "Look up the shipping status of an order by its id. The status is only available through this tool.",
		tn.JSONSchema{"type": "object", "properties": map[string]any{"order_id": map[string]any{"type": "string"}}, "required": []any{"order_id"}},
		func(_ context.Context, args map[string]any) (string, error) {
			id, _ := args["order_id"].(string)
			if id == "A-1042" {
				return `{"order_id":"A-1042","status":"SHIPPED","tracking":"ZX-7731-QP"}`, nil
			}
			return "", fmt.Errorf("no order %q", id)
		})
	tk, err := tn.CreateToolkit(context.Background(), tn.Options{ExtraTools: []tn.Tool{lookup}, Builtins: false})
	if err != nil {
		r.Error = err.Error()
		return r
	}
	defer tk.Close()
	o := CliOptions{Style: CliStyle(name), Command: name, TimeoutMs: 300000} // caller supplies the binary
	o.SystemPrompt = "You are a support assistant. Answer in one short sentence."
	o.MaxTurns = 4
	c, err := CreateCliClient(o, func(t Trace) {
		r.CallMs = append(r.CallMs, t.Ms)
		r.PromptBytes = append(r.PromptBytes, t.Bytes)
		r.RawReplies = append(r.RawReplies, trunc(t.Reply, 400))
	})
	if err != nil {
		r.Error = err.Error()
		return r
	}
	start := time.Now()
	res, err := c.Run(context.Background(), "What is the tracking number for order A-1042?", tk)
	r.TotalMs = time.Since(start).Milliseconds()
	if err != nil {
		r.Error = err.Error()
		return r
	}
	for _, tc := range res.ToolCalls {
		r.ToolCalls = append(r.ToolCalls, tc.Name)
	}
	r.Text = res.Text
	r.OK = len(r.ToolCalls) > 0 && r.ToolCalls[0] == "lookup_order" && strings.Contains(res.Text, "ZX-7731-QP")
	return r
}

func trunc(s string, n int) string {
	if len([]rune(s)) <= n {
		return s
	}
	return string([]rune(s)[:n]) + "…"
}
