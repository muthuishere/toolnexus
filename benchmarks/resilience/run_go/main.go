// toolnexus (Go port) RESILIENCE runner.
//
// Runs the eight adverse-condition scenarios (R1-R8) against the shared
// resilience mock LLM + local MCP stubs, printing ONE JSON line per scenario:
//
//	{"lang","scenario","outcome","detail","elapsed_ms"}
//
// outcome ∈ graceful | crash | hang | wrong. Uses a composite replace → the
// local Go port. Nothing calls a real LLM; nothing leaves the box.
//
// Env: BENCH_REPO, MCP_PYTHON, MOCK_BASE, MCP_HANG.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

var (
	repo    = os.Getenv("BENCH_REPO")
	mcpPy   = os.Getenv("MCP_PYTHON")
	mockBase = envOr("MOCK_BASE", "http://127.0.0.1:8901")
	mcpHang = os.Getenv("MCP_HANG")
	good    = repo + "/benchmarks/mcp_server.py"
)

const hardCap = 20 * time.Second

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func goodCfg() map[string]any {
	return map[string]any{"type": "local", "command": []any{mcpPy, good}, "enabled": true, "timeout": 30000}
}

func closedPort() int {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 59999
	}
	p := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return p
}

func boomTool() toolnexus.Tool {
	schema := map[string]any{"type": "object", "properties": map[string]any{}}
	return toolnexus.NativeTool("boom", "A tool that always raises.", schema,
		func(ctx context.Context, args map[string]any) (string, error) {
			return "", errors.New("boom: native tool failed on purpose")
		})
}

func toolNames(tk *toolnexus.Toolkit) []string {
	var n []string
	for _, t := range tk.Tools() {
		n = append(n, t.Name)
	}
	sort.Strings(n)
	return n
}

// each scenario returns (outcome, detail)

func r1(ctx context.Context) (string, string) {
	cfg := map[string]any{"mcpServers": map[string]any{
		"good": goodCfg(),
		"bad":  map[string]any{"type": "local", "command": []any{"/nonexistent/tn-badcmd-xyz"}, "enabled": true, "timeout": 2000},
	}}
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{McpConfig: cfg, Builtins: false})
	if err != nil {
		return "crash", "CreateToolkit errored: " + err.Error()
	}
	defer tk.Close()
	st := tk.McpStatus()
	names := toolNames(tk)
	ok := st["bad"] == "failed" && st["good"] == "connected" && len(names) > 0
	return gw(ok), fmt.Sprintf("status=%v tools=%v", st, names)
}

func r2(ctx context.Context) (string, string) {
	port := closedPort()
	cfg := map[string]any{"mcpServers": map[string]any{
		"good":   goodCfg(),
		"remote": map[string]any{"type": "remote", "url": fmt.Sprintf("http://127.0.0.1:%d/mcp", port), "enabled": true, "timeout": 2000},
	}}
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{McpConfig: cfg, Builtins: false})
	if err != nil {
		return "crash", "CreateToolkit errored: " + err.Error()
	}
	defer tk.Close()
	st := tk.McpStatus()
	names := toolNames(tk)
	ok := st["remote"] == "failed" && st["good"] == "connected" && len(names) > 0
	return gw(ok), fmt.Sprintf("status=%v tools=%v", st, names)
}

func r3(ctx context.Context) (string, string) {
	cfg := map[string]any{"mcpServers": map[string]any{
		"good": goodCfg(),
		"hang": map[string]any{"type": "local", "command": []any{mcpPy, mcpHang}, "enabled": true, "timeout": 2000},
	}}
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{McpConfig: cfg, Builtins: false})
	if err != nil {
		return "crash", "CreateToolkit errored: " + err.Error()
	}
	defer tk.Close()
	st := tk.McpStatus()
	names := toolNames(tk)
	ok := st["hang"] == "failed" && st["good"] == "connected" && len(names) > 0
	return gw(ok), fmt.Sprintf("status=%v tools=%v", st, names)
}

func r4(ctx context.Context) (string, string) {
	p := filepath.Join(os.TempDir(), fmt.Sprintf("tn-bad-%d.json", time.Now().UnixNano()))
	os.WriteFile(p, []byte(`{ "mcpServers": { "x": { "type": "local", `), 0o600)
	defer os.Remove(p)
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{McpConfig: p, Builtins: false})
	if err != nil {
		return "graceful", "returned error: " + trunc(err.Error(), 80)
	}
	tk.Close()
	return "wrong", "malformed config did not error"
}

func runLLM(ctx context.Context, suffix string, retries int, tools []toolnexus.Tool) (toolnexus.RunResult, error) {
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{Builtins: false, ExtraTools: tools})
	if err != nil {
		return toolnexus.RunResult{}, err
	}
	defer tk.Close()
	client := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL: mockBase + "/" + suffix, Style: toolnexus.StyleOpenAI, Model: "mock-model",
		APIKey: "sk-mock-not-real", Retries: retries, RetryBaseMs: 1,
	})
	return client.Run(ctx, "hello", tk)
}

func r5(ctx context.Context) (string, string) {
	r, err := runLLM(ctx, "e402", 2, nil)
	if err != nil {
		return "graceful", "surfaced error: " + trunc(err.Error(), 80)
	}
	return "wrong", "402 did not surface; text=" + r.Text
}

func r6(ctx context.Context) (string, string) {
	suffix := fmt.Sprintf("retry/%d", time.Now().UnixNano())
	r, err := runLLM(ctx, suffix, 3, nil)
	if err != nil {
		return "crash", "did not recover from 429: " + trunc(err.Error(), 80)
	}
	if strings.Contains(r.Text, "RESILIENCE_OK") {
		return "graceful", "retried then succeeded: text=" + r.Text
	}
	return "wrong", "unexpected final text=" + r.Text
}

func r7(ctx context.Context) (string, string) {
	r, err := runLLM(ctx, "e500", 2, nil)
	if err != nil {
		return "graceful", "surfaced after retries: " + trunc(err.Error(), 80)
	}
	return "wrong", "500 did not surface; text=" + r.Text
}

func r8(ctx context.Context) (string, string) {
	r, err := runLLM(ctx, "boom", 2, []toolnexus.Tool{boomTool()})
	if err != nil {
		return "crash", "tool error not fed back: " + trunc(err.Error(), 80)
	}
	boomErrs := 0
	for _, c := range r.ToolCalls {
		if c.Name == "boom" && c.IsError {
			boomErrs++
		}
	}
	if strings.Contains(r.Text, "RESILIENCE_OK") && boomErrs > 0 {
		return "graceful", "tool error fed back, loop reached final: text=" + r.Text
	}
	return "wrong", fmt.Sprintf("text=%q boomErrors=%d", r.Text, boomErrs)
}

func gw(ok bool) string {
	if ok {
		return "graceful"
	}
	return "wrong"
}

func trunc(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

type scenario struct {
	key string
	fn  func(context.Context) (string, string)
}

func main() {
	scenarios := []scenario{
		{"R1", r1}, {"R2", r2}, {"R3", r3}, {"R4", r4},
		{"R5", r5}, {"R6", r6}, {"R7", r7}, {"R8", r8},
	}
	only := os.Getenv("ONLY_SCENARIO")
	for _, sc := range scenarios {
		if only != "" && sc.key != only {
			continue
		}
		t0 := time.Now()
		outCh := make(chan [2]string, 1)
		ctx, cancel := context.WithTimeout(context.Background(), hardCap)
		go func(fn func(context.Context) (string, string)) {
			defer func() {
				if r := recover(); r != nil {
					outCh <- [2]string{"crash", fmt.Sprintf("panic: %v", r)}
				}
			}()
			o, d := fn(ctx)
			outCh <- [2]string{o, d}
		}(sc.fn)

		var outcome, detail string
		select {
		case res := <-outCh:
			outcome, detail = res[0], res[1]
		case <-time.After(hardCap):
			outcome, detail = "hang", fmt.Sprintf("exceeded %s hard cap", hardCap)
		}
		cancel()
		elapsed := float64(time.Since(t0).Microseconds()) / 1000.0
		line := map[string]any{
			"lang": "go", "scenario": sc.key, "outcome": outcome,
			"detail": detail, "elapsed_ms": elapsed,
		}
		b, _ := json.Marshal(line)
		fmt.Println(string(b))
	}
}
