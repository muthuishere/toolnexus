// Tests for the ADR-0001 rag_go consumer needs (7 gaps) + the DisableTools/
// DisableSkills ergonomic layer. Client-side gaps use an httptest LLM that
// captures the request body; MCP-side gaps use a real streamable-HTTP MCP server
// built from buildMcpServer (hermetic, no external network).
package toolnexus

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/server"
)

// TestMain re-execs this test binary as a real stdio MCP server (exposing a,b,c) when
// TN_STDIO_MCP=1, so TestStdioOutboundChildStaysAlive can drive a genuine outbound stdio
// connection hermetically (no external process, no network).
func TestMain(m *testing.M) {
	if os.Getenv("TN_STDIO_MCP") == "1" {
		_ = server.ServeStdio(buildMcpServer([]Tool{mkTool("a"), mkTool("b"), mkTool("c")}, nil, nil))
		return
	}
	os.Exit(m.Run())
}

// TestStdioOutboundChildStaysAlive is the regression guard for the v0.9.0 stdio bug: the child
// process must NOT be killed after connect. It was, when the transport Start ran on a
// timeout/cancel context (mark3labs spawns via exec.CommandContext), so tools/list hit a dead
// pipe. This drives a real outbound stdio server and asserts the tools load AND one executes.
func TestStdioOutboundChildStaysAlive(t *testing.T) {
	cfg := map[string]any{"srv": map[string]any{
		"command":     []any{os.Args[0]},
		"environment": map[string]any{"TN_STDIO_MCP": "1"},
	}}
	src, err := LoadMcp(cfg)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	defer src.Close()
	if src.Status["srv"] != StatusConnected {
		t.Fatalf("stdio server not connected: status=%v", src.Status["srv"])
	}
	names := rcNames(src.Tools)
	if !names["srv_a"] || !names["srv_b"] || !names["srv_c"] {
		t.Fatalf("stdio tools/list returned %v (child likely killed after connect)", names)
	}
	var srvA Tool
	for _, tl := range src.Tools {
		if tl.Name == "srv_a" {
			srvA = tl
		}
	}
	res, err := srvA.Execute(map[string]any{}, nil)
	if err != nil || res.IsError {
		t.Fatalf("execute srv_a on live stdio child: err=%v res=%+v", err, res)
	}
}

// captureLLM returns an httptest server that records the last decoded request body.
func captureLLM(t *testing.T) (*httptest.Server, func() map[string]any) {
	t.Helper()
	var mu sync.Mutex
	var last map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		mu.Lock()
		last = map[string]any{}
		_ = json.Unmarshal(raw, &last)
		mu.Unlock()
		w.Header().Set("content-type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"ok"}}],"content":[{"type":"text","text":"ok"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"input_tokens":1,"output_tokens":1}}`))
	}))
	return srv, func() map[string]any { mu.Lock(); defer mu.Unlock(); return last }
}

func mkTool(name string) Tool {
	return Tool{
		Name:        name,
		Description: name,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Source:      SourceNative,
		Execute:     func(map[string]any, *ToolContext) (ToolResult, error) { return ToolResult{Output: name}, nil },
	}
}

func TestGap1RequestParamsMergeAndCallerWins(t *testing.T) {
	srv, body := captureLLM(t)
	defer srv.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})

	oa := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k",
		RequestParams: map[string]any{"temperature": 0.42, "chat_template_kwargs": map[string]any{"enable_thinking": false}}})
	_, _ = oa.Run(context.Background(), "hi", tk)
	if body()["temperature"] != 0.42 {
		t.Fatalf("openai temperature = %v", body()["temperature"])
	}
	if body()["chat_template_kwargs"] == nil {
		t.Fatalf("chat_template_kwargs missing")
	}

	an := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleAnthropic, Model: "m", APIKey: "k",
		RequestParams: map[string]any{"max_tokens": float64(999)}})
	_, _ = an.Run(context.Background(), "hi", tk)
	if body()["max_tokens"] != float64(999) {
		t.Fatalf("anthropic max_tokens = %v, want 999 (caller wins over 4096)", body()["max_tokens"])
	}
}

func TestGap1BodyTransformAndForbiddenKeys(t *testing.T) {
	srv, body := captureLLM(t)
	defer srv.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	c := CreateClient(ClientOptions{
		BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k",
		RequestParams: map[string]any{"temperature": 0.5, "messages": []any{"nope"}, "tools": []any{1}, "stream": true},
		BodyTransform: func(b map[string]any) map[string]any { b["injected"] = "yes"; delete(b, "temperature"); return b },
	})
	_, _ = c.Run(context.Background(), "hi", tk)
	if body()["injected"] != "yes" {
		t.Fatalf("bodyTransform output not sent")
	}
	if _, ok := body()["temperature"]; ok {
		t.Fatalf("bodyTransform ran before marshal but key survived")
	}
	if _, ok := body()["stream"]; ok {
		t.Fatalf("forbidden 'stream' override leaked into a non-stream call")
	}
	msgs, _ := body()["messages"].([]any)
	if len(msgs) == 0 {
		t.Fatalf("forbidden 'messages' override replaced the real messages")
	}
}

type recordRT struct {
	inner http.RoundTripper
	hits  int
}

func (r *recordRT) RoundTrip(req *http.Request) (*http.Response, error) {
	r.hits++
	return r.inner.RoundTrip(req)
}

func TestGap2HTTPClientInjected(t *testing.T) {
	srv, _ := captureLLM(t)
	defer srv.Close()
	rt := &recordRT{inner: http.DefaultTransport}
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", HTTPClient: &http.Client{Transport: rt}})
	if _, err := c.Run(context.Background(), "hi", tk); err != nil {
		t.Fatalf("run: %v", err)
	}
	if rt.hits == 0 {
		t.Fatalf("injected HTTPClient was not used")
	}
}

func TestGap5OmitEmptyTools(t *testing.T) {
	srv, body := captureLLM(t)
	defer srv.Close()

	empty, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	c1 := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	_, _ = c1.Run(context.Background(), "hi", empty)
	if _, ok := body()["tools"]; ok {
		t.Fatalf("empty toolkit still sent tools")
	}
	if _, ok := body()["tool_choice"]; ok {
		t.Fatalf("empty toolkit still sent tool_choice")
	}

	one, _ := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{mkTool("t")}})
	c2 := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	_, _ = c2.Run(context.Background(), "hi", one)
	if _, ok := body()["tools"]; !ok {
		t.Fatalf("non-empty toolkit dropped tools")
	}
	if body()["tool_choice"] != "auto" {
		t.Fatalf("non-empty toolkit dropped tool_choice")
	}
}

func TestGap4ConversationStoreAccessor(t *testing.T) {
	custom := NewInMemoryConversationStore()
	c1 := CreateClient(ClientOptions{BaseURL: "http://x", Style: StyleOpenAI, Model: "m", Store: custom})
	if c1.ConversationStore() != custom {
		t.Fatalf("accessor did not return the supplied store")
	}

	srv, _ := captureLLM(t)
	defer srv.Close()
	tk, _ := CreateToolkit(context.Background(), Options{Builtins: false})
	c2 := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	if c2.ConversationStore() == nil {
		t.Fatalf("default store is nil")
	}
	_, _ = c2.Ask(context.Background(), "hi", tk, "conv1")
	hist, _ := c2.ConversationStore().Get("conv1")
	if len(hist) < 2 {
		t.Fatalf("default store did not hold the saved transcript, got %d msgs", len(hist))
	}
}

// startMCP builds a real streamable-HTTP MCP server exposing tools a,b,c.
func startMCP(t *testing.T) (url string, closeFn func()) {
	t.Helper()
	srv := buildMcpServer([]Tool{mkTool("a"), mkTool("b"), mkTool("c")}, nil, nil)
	ts := httptest.NewServer(server.NewStreamableHTTPServer(srv, server.WithStateLess(true)))
	return ts.URL + "/mcp", ts.Close
}

func TestGap7PerServerToolAllowlist(t *testing.T) {
	url, done := startMCP(t)
	defer done()

	allow, err := LoadMcp(map[string]any{"srv": map[string]any{"url": url, "tools": map[string]any{"a": true, "b": true}}})
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	defer allow.Close()
	names := rcNames(allow.Tools)
	if len(names) != 2 || !names["srv_a"] || !names["srv_b"] {
		t.Fatalf("allowlist tools = %v, want srv_a,srv_b", names)
	}

	drop, _ := LoadMcp(map[string]any{"srv": map[string]any{"url": url, "tools": map[string]any{"c": false}}})
	defer drop.Close()
	if n := rcNames(drop.Tools); len(n) != 2 || n["srv_c"] {
		t.Fatalf("drop-list tools = %v, want srv_a,srv_b", n)
	}

	all, _ := LoadMcp(map[string]any{"srv": map[string]any{"url": url}})
	defer all.Close()
	if n := rcNames(all.Tools); len(n) != 3 {
		t.Fatalf("nil filter tools = %v, want all 3", n)
	}
}

func TestGap6ListMcpTools(t *testing.T) {
	url, done := startMCP(t)
	defer done()
	inv, err := ListMcpTools(context.Background(), map[string]any{
		"good": map[string]any{"url": url, "tools": map[string]any{"a": true}}, // filter IGNORED by inventory
		"bad":  map[string]any{"url": "http://127.0.0.1:1/mcp", "timeout": 500},
	})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(inv.Tools["good"]) != 3 {
		t.Fatalf("inventory should be unfiltered (a,b,c), got %d", len(inv.Tools["good"]))
	}
	if inv.Status["good"] != StatusConnected {
		t.Fatalf("good status = %v", inv.Status["good"])
	}
	if inv.Status["bad"] != StatusFailed {
		t.Fatalf("bad status = %v, want failed", inv.Status["bad"])
	}
}

func TestGap3HangingBoundedAndCancel(t *testing.T) {
	// A server that accepts but never responds → connect/list bounded by timeout.
	// The handler blocks on `unblock` (closed at cleanup, before Close, so Close
	// doesn't wait forever on an in-flight request).
	unblock := make(chan struct{})
	hang := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-unblock }))
	defer hang.Close()
	defer close(unblock)

	t0 := time.Now()
	src, err := LoadMcp(map[string]any{"hang": map[string]any{"url": hang.URL + "/mcp", "timeout": 500}})
	if err != nil {
		t.Fatalf("load returned error (per-server timeout should isolate): %v", err)
	}
	defer src.Close()
	if src.Status["hang"] != StatusFailed {
		t.Fatalf("hanging server status = %v, want failed", src.Status["hang"])
	}
	if dt := time.Since(t0); dt > 5*time.Second {
		t.Fatalf("not bounded by timeout: took %v", dt)
	}

	// Parent-ctx cancellation aborts the whole build with ctx.Err().
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := LoadMcpWithContext(ctx, map[string]any{"hang": map[string]any{"url": hang.URL + "/mcp", "timeout": 60000}}); err == nil {
		t.Fatalf("cancelled ctx should error the whole load")
	}
}

func TestDisableToolsAndSkills(t *testing.T) {
	url, done := startMCP(t)
	defer done()
	tk, err := CreateToolkit(context.Background(), Options{
		Builtins:     false,
		McpConfig:    map[string]any{"srv": map[string]any{"url": url}},
		DisableTools: []string{"srv_b"},
	})
	if err != nil {
		t.Fatalf("toolkit: %v", err)
	}
	if _, ok := tk.Get("srv_b"); ok {
		t.Fatalf("DisableTools did not drop srv_b")
	}
	if _, ok := tk.Get("srv_a"); !ok {
		t.Fatalf("DisableTools dropped too much (srv_a gone)")
	}

	// DisableSkills drops a skill by name from the catalog.
	sk, err := CreateToolkit(context.Background(), Options{
		Builtins:      false,
		Skills:        []SkillDef{{Name: "keep", Description: "k", Content: "k"}, {Name: "drop", Description: "d", Content: "d"}},
		DisableSkills: []string{"drop"},
	})
	if err != nil {
		t.Fatalf("skills toolkit: %v", err)
	}
	prompt := sk.SkillsPrompt()
	if !strings.Contains(prompt, "keep") || strings.Contains(prompt, "**drop**") {
		t.Fatalf("DisableSkills wrong; prompt:\n%s", prompt)
	}
}

func rcNames(ts []Tool) map[string]bool {
	m := map[string]bool{}
	for _, t := range ts {
		m[t.Name] = true
	}
	return m
}
