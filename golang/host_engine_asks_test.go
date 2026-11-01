package toolnexus

// go-agent-cli Go-only asks (2026-11-01): Toolkit.Register concurrency,
// per-server MCP readiness (LoadMcpWithOptions.OnServer), WaitForCtx.

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestToolkitRegisterConcurrentWithReads(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	stop := make(chan struct{})
	for r := 0; r < 4; r++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				_ = tk.Tools()
				_, _ = tk.Get("t0")
				_, _ = tk.Execute(context.Background(), "t0", nil)
				_ = tk.ToOpenAI()
			}
		}()
	}
	for i := 0; i < 200; i++ {
		tk.Register(NativeTool(fmt.Sprintf("t%d", i), "d", nil, func(context.Context, map[string]any) (string, error) { return "ok", nil }))
	}
	close(stop)
	wg.Wait()
	if n := len(tk.Tools()); n != 200 {
		t.Fatalf("tools = %d, want 200", n)
	}
}

func TestLoadMcpOnServerReportsFastServerFirst(t *testing.T) {
	if _, err := exec.LookPath("node"); err != nil {
		t.Skip("node not on PATH")
	}
	fixture, _ := filepath.Abs("../examples/mcp-progress-server.mjs")
	cfg := McpConfig{
		"fast": ServerConfig{Command: []string{"node", fixture}, Timeout: 5000},
		"slow": ServerConfig{Command: []string{"sh", "-c", "sleep 1; exec node " + fixture}, Timeout: 5000},
		"bad":  ServerConfig{Command: []string{"/nonexistent/toolnexus-no-such-binary"}, Timeout: 2000},
		"off":  ServerConfig{Command: []string{"node", fixture}, Enabled: boolPtr(false)},
	}
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	start := time.Now()
	var mu sync.Mutex
	var order []string
	at := map[string]time.Duration{}
	results := map[string]McpServerResult{}
	src, err := LoadMcpWithOptions(context.Background(), cfg, McpLoadOptions{OnServer: func(r McpServerResult) {
		mu.Lock()
		defer mu.Unlock()
		order = append(order, r.Name)
		at[r.Name] = time.Since(start)
		results[r.Name] = r
		tk.Register(r.Tools...)
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	if len(order) != 4 {
		t.Fatalf("callbacks = %v, want 4", order)
	}
	if results["fast"].Status != StatusConnected || len(results["fast"].Tools) == 0 {
		t.Fatalf("fast = %+v", results["fast"])
	}
	if results["slow"].Status != StatusConnected {
		t.Fatalf("slow = %+v", results["slow"])
	}
	if results["bad"].Status != StatusFailed || results["bad"].Err == nil {
		t.Fatalf("bad = %+v", results["bad"])
	}
	if results["off"].Status != StatusDisabled {
		t.Fatalf("off = %+v", results["off"])
	}
	if at["fast"] >= at["slow"] || at["slow"]-at["fast"] < 500*time.Millisecond {
		t.Fatalf("fast must arrive well before slow: fast=%v slow=%v", at["fast"], at["slow"])
	}
	if _, ok := tk.Get("fast_slow"); !ok {
		t.Fatalf("fast server's tools not registered via OnServer; have %d tools", len(tk.Tools()))
	}
	t.Logf("fast ready at %v, slow at %v", at["fast"], at["slow"])
}

func TestWaitForCtxCancelsWithTurn(t *testing.T) {
	srv := stubLLM(t)
	defer srv.Close()
	authed := false
	ctx, cancel := context.WithCancel(context.Background())
	got := make(chan error, 1)
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitFor: func(Request) (Answer, error) {
			t.Error("WaitFor must not run when WaitForCtx is set")
			return Answer{}, nil
		},
		WaitForCtx: func(wctx context.Context, req Request) (Answer, error) {
			go func() { time.Sleep(50 * time.Millisecond); cancel() }()
			select {
			case <-wctx.Done():
				got <- wctx.Err()
				return Answer{}, wctx.Err()
			case <-time.After(5 * time.Second):
				got <- nil
				return Answer{ID: req.ID, Ok: true}, nil
			}
		},
	})
	start := time.Now()
	_, err := c.Run(ctx, "what is my balance?", balanceToolkit(t, &authed))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("run err = %v, want context.Canceled", err)
	}
	if werr := <-got; !errors.Is(werr, context.Canceled) {
		t.Fatalf("WaitForCtx saw %v, want the turn ctx cancelled", werr)
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("run took %v after cancel", d)
	}
}

func TestWaitForCtxResolves(t *testing.T) {
	srv := stubLLM(t)
	defer srv.Close()
	authed := false
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		WaitForCtx: func(_ context.Context, req Request) (Answer, error) {
			authed = true
			return Answer{ID: req.ID, Ok: true}, nil
		},
	})
	res, err := c.Run(context.Background(), "what is my balance?", balanceToolkit(t, &authed))
	if err != nil || res.Status != "done" {
		t.Fatalf("res=%+v err=%v", res, err)
	}
}

func TestBuiltinHints(t *testing.T) {
	want := map[string]bool{"read": true, "grep": true, "glob": true, "webfetch": true, "question": true, "todowrite": true,
		"bash": false, "write": false, "edit": false, "apply_patch": false}
	for _, tl := range CreateBuiltinToolsWith(nil) {
		ro, ok := want[tl.Name]
		if !ok || tl.Hints == nil {
			t.Fatalf("%s: hints=%v listed=%v", tl.Name, tl.Hints, ok)
		}
		if tl.Hints.ReadOnly != ro || tl.Hints.Destructive == ro {
			t.Fatalf("%s: hints=%+v", tl.Name, *tl.Hints)
		}
	}
}

func TestMcpHintsMapping(t *testing.T) {
	tr, fa := true, false
	if mcpHints(mcpAnn(nil, nil, nil)) != nil {
		t.Fatal("no annotations must be nil (unknown)")
	}
	if h := mcpHints(mcpAnn(&tr, nil, nil)); !h.ReadOnly || h.Destructive || !h.OpenWorld {
		t.Fatalf("readOnly: %+v", *h)
	}
	if h := mcpHints(mcpAnn(nil, &fa, &fa)); h.ReadOnly || h.Destructive || h.OpenWorld {
		t.Fatalf("non-destructive: %+v", *h)
	}
	if h := mcpHints(mcpAnn(&fa, nil, nil)); h.ReadOnly || !h.Destructive {
		t.Fatalf("spec defaults: %+v", *h)
	}
}

// A plan-mode gate built on Hooks.BeforeTool: a mutating tool is denied, the
// model gets an error tool_result, and the run still finishes.
func TestPermissionGateDeniesMutatingTool(t *testing.T) {
	srv := stubLLM(t)
	defer srv.Close()
	authed := true
	tk := balanceToolkit(t, &authed)
	tool, _ := tk.Get("get_balance")
	tool.Hints = &ToolHints{Destructive: true}
	tk2, _ := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{tool}})
	var seen *ToolHints
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "stub", APIKey: "k",
		Hooks: &Hooks{BeforeTool: func(_ context.Context, ev BeforeToolEvent) (*ToolOverride, error) {
			seen = ev.Hints
			if ev.Hints == nil || !ev.Hints.ReadOnly {
				return &ToolOverride{Result: &ToolResult{Output: "denied: plan mode allows read-only tools only", IsError: true}}, nil
			}
			return nil, nil
		}}})
	res, err := c.Run(context.Background(), "balance?", tk2)
	if err != nil || res.Status != "done" {
		t.Fatalf("res=%+v err=%v", res, err)
	}
	if seen == nil || !seen.Destructive {
		t.Fatalf("BeforeTool hints = %v", seen)
	}
	if len(res.ToolCalls) != 1 || !res.ToolCalls[0].IsError || res.ToolCalls[0].Output != "denied: plan mode allows read-only tools only" {
		t.Fatalf("tool calls = %+v", res.ToolCalls)
	}
}

// Karya: parallel calls from one assistant message each see their own call id.
func TestToolContextCallIDOnParallelCalls(t *testing.T) {
	var mu sync.Mutex
	seen := map[string]string{}
	mk := func(name string) Tool {
		return Tool{Name: name, InputSchema: JSONSchema{"type": "object"}, Source: SourceCustom,
			Execute: func(_ map[string]any, tc *ToolContext) (ToolResult, error) {
				mu.Lock()
				seen[name] = tc.CallID
				mu.Unlock()
				return ToolResult{Output: "ok"}, nil
			}}
	}
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{mk("fast"), mk("slow")}})
	for _, stream := range []bool{false, true} {
		for _, style := range []ClientStyle{StyleOpenAI, StyleAnthropic} {
			seen = map[string]string{}
			srv := twoCallLLM(t, 0) // always answers with calls c1 (fast) + c2 (slow)
			c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: style, Model: "m", APIKey: "k", MaxTurns: 1})
			if stream {
				ch, err := c.Stream(context.Background(), "go", tk)
				if err != nil {
					t.Fatal(err)
				}
				for range ch {
				}
			} else if _, err := c.Run(context.Background(), "go", tk); err != nil {
				t.Fatal(err)
			}
			if seen["fast"] != "c1" || seen["slow"] != "c2" {
				t.Fatalf("style=%s stream=%v: call ids = %v", style, stream, seen)
			}
		}
	}
}
