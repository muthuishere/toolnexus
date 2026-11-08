// Round-2 integration suite against the rest of the mock kit (tools/mock-llm):
// MCP over stdio + HTTP (2026 and legacy paths), an HTTP tool, the mock A2A
// agent, skills, the in-process one-shot CLI client and the ACP client.
// TN_MOCK_KIT_DIR overrides the kit directory (used for mutation checks).

package toolnexus

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func mockKitDir(t *testing.T) (python, dir string) {
	t.Helper()
	py, err := exec.LookPath("python3")
	if err != nil {
		t.Skip("python3 not found on PATH; the mock kit suite needs it")
	}
	dir = os.Getenv("TN_MOCK_KIT_DIR")
	if dir == "" {
		dir = filepath.Join("..", "tools", "mock-llm")
	}
	dir, _ = filepath.Abs(dir)
	if _, err := os.Stat(filepath.Join(dir, "mock_mcp.py")); err != nil {
		t.Skip("mock kit not found at " + dir)
	}
	return py, dir
}

// mockMCPHTTP starts mock_mcp.py --http and returns its /mcp URL.
func mockMCPHTTP(t *testing.T, legacy bool) string {
	t.Helper()
	py, dir := mockKitDir(t)
	args := []string{filepath.Join(dir, "mock_mcp.py"), "--http", "--port", "0"}
	if legacy {
		args = append(args, "--legacy")
	}
	cmd := exec.Command(py, args...)
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatalf("start mock mcp: %v", err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	lines := make(chan string, 1)
	go func() {
		sc := bufio.NewScanner(out)
		if sc.Scan() {
			lines <- sc.Text()
		}
		close(lines)
		_, _ = io.Copy(io.Discard, out)
	}()
	const prefix = "toolnexus mock mcp at "
	select {
	case line := <-lines:
		if !strings.HasPrefix(line, prefix) {
			t.Fatalf("unexpected mock mcp banner: %q", line)
		}
		return strings.TrimSpace(strings.TrimPrefix(line, prefix))
	case <-time.After(10 * time.Second):
		t.Fatal("mock mcp did not print its URL within 10s")
	}
	return ""
}

func mockNativeAdd(seen *[][2]float64, mu *sync.Mutex) Tool {
	type addArgs struct {
		A float64 `json:"a"`
		B float64 `json:"b"`
	}
	return NativeToolReflect("native_add", "Add two numbers natively.", func(_ context.Context, in addArgs) (string, error) {
		if seen != nil {
			mu.Lock()
			*seen = append(*seen, [2]float64{in.A, in.B})
			mu.Unlock()
		}
		return strconv.FormatFloat(in.A+in.B, 'f', -1, 64), nil
	})
}

// mixedToolkit builds the toolkit of case h. Every source is present.
func mixedToolkit(t *testing.T) (*Toolkit, string) {
	t.Helper()
	py, dir := mockKitDir(t)
	base := mockLLM(t)
	origin := strings.TrimSuffix(base, "/v1")
	cfg := map[string]any{"mcpServers": map[string]any{
		"mstdio":  map[string]any{"type": "local", "command": []any{py, filepath.Join(dir, "mock_mcp.py")}, "timeout": 15000},
		"mhttp":   map[string]any{"type": "remote", "url": mockMCPHTTP(t, false), "timeout": 15000},
		"mlegacy": map[string]any{"type": "remote", "url": mockMCPHTTP(t, true), "timeout": 15000},
	}}
	httpAdd := HTTPTool(HTTPToolOptions{
		Name: "http_add", Description: "Add two numbers over HTTP.", Method: "POST", URL: base + "/tools/add",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{
			"a": map[string]any{"type": "integer"}, "b": map[string]any{"type": "integer"}}, "required": []any{"a", "b"}},
	})
	tk, err := CreateToolkit(context.Background(), Options{
		McpConfig:  cfg,
		SkillsDir:  []string{filepath.Join("..", "examples", "skills")},
		ExtraTools: []Tool{httpAdd, mockNativeAdd(nil, nil)},
		Agents:     []Agent{{Card: origin + "/.well-known/agent-card.json", PollEvery: 20, Timeout: 15000}},
		Builtins:   false,
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	t.Cleanup(tk.Close)
	return tk, base
}

func TestMockKitMixedToolkit(t *testing.T) {
	tk, _ := mixedToolkit(t)
	ctx := context.Background()

	for name, st := range tk.McpStatus() {
		if st != StatusConnected {
			t.Errorf("mcp %s status = %s", name, st)
		}
	}
	// Path check: the two plain servers stay on the 2026 direct path; --legacy falls back.
	for name, want26 := range map[string]bool{"mstdio": true, "mhttp": true, "mlegacy": false} {
		conn, ok := tk.mcp.servers[name]
		if !ok {
			t.Errorf("mcp server %s not connected", name)
			continue
		}
		if got := conn.v26 != nil; got != want26 {
			t.Errorf("mcp %s on 2026 path = %v, want %v", name, got, want26)
		}
	}

	have := map[string]bool{}
	for _, tool := range tk.Tools() {
		have[tool.Name] = true
	}
	for _, n := range []string{"mstdio_add", "mstdio_echo", "mhttp_add", "mhttp_echo", "mlegacy_add",
		"http_add", "native_add", "mockagent_summarize", "skill"} {
		if !have[n] {
			t.Errorf("tool %q missing; have %v", n, have)
		}
	}

	exec := func(name string, args map[string]any) string {
		t.Helper()
		r, err := tk.Execute(ctx, name, args)
		if err != nil || r.IsError {
			t.Fatalf("%s: %v %+v", name, err, r)
		}
		return r.Output
	}
	for _, n := range []string{"mstdio_add", "mhttp_add", "mlegacy_add"} {
		if out := exec(n, map[string]any{"a": 2, "b": 3}); out != "5" {
			t.Errorf("%s(2,3) = %q, want 5", n, out)
		}
	}
	if out := exec("mhttp_echo", map[string]any{"text": "hi"}); !strings.Contains(out, "hi") {
		t.Errorf("mhttp_echo = %q", out)
	}
	var sum map[string]any
	if out := exec("http_add", map[string]any{"a": 2, "b": 3}); json.Unmarshal([]byte(out), &sum) != nil || sum["sum"] != float64(5) {
		t.Errorf("http_add = %q, want {\"sum\":5}", out)
	}
	if out := exec("native_add", map[string]any{"a": 2, "b": 3}); out != "5" {
		t.Errorf("native_add = %q", out)
	}
	if out := exec("mockagent_summarize", map[string]any{"task": "x"}); out != "A2A mock done: x" {
		t.Errorf("a2a = %q, want %q", out, "A2A mock done: x")
	}
	if out := exec("skill", map[string]any{"name": "hello-world"}); !strings.Contains(out, "# Hello World Skill") {
		t.Errorf("skill output missing hello-world body: %q", out)
	}
}

func TestMockKitClientRunOverMixedToolkit(t *testing.T) {
	tk, base := mixedToolkit(t)
	first := tk.Tools()[0].Name
	res, err := mockLLMClient(base, StyleOpenAI).Run(context.Background(), "Use a tool.", tk)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if len(res.ToolCalls) != 1 || res.ToolCalls[0].Name != first || res.ToolCalls[0].IsError {
		t.Fatalf("tool calls = %+v, want one successful call to %s", res.ToolCalls, first)
	}
	t.Logf("first tool %s -> %q", first, res.ToolCalls[0].Output)
	want := "Tool result: " + strings.TrimSpace(res.ToolCalls[0].Output)
	if res.Text != want || res.ToolCalls[0].Output == "" {
		t.Errorf("text = %q, want %q", res.Text, want)
	}
}

// oneShotRender mirrors site/recipes/in-process-client/golang/one-shot-cli.
func oneShotRender(req InProcessRequest) string {
	var b strings.Builder
	b.WriteString("Tools you may call:\n")
	for _, tl := range req.Tools {
		f, _ := tl.(map[string]any)["function"].(map[string]any)
		params, _ := json.Marshal(f["parameters"])
		fmt.Fprintf(&b, "- %v: %v args=%s\n", f["name"], f["description"], params)
	}
	b.WriteString("\nConversation:\n")
	called := map[string]any{}
	for _, raw := range req.Messages {
		m, _ := raw.(map[string]any)
		calls, _ := m["tool_calls"].([]any)
		switch {
		case m["role"] == "tool":
			fmt.Fprintf(&b, "[tool %v] %v\n", called[fmt.Sprint(m["tool_call_id"])], m["content"])
		case len(calls) > 0:
			for _, c := range calls {
				cm, _ := c.(map[string]any)
				fn, _ := cm["function"].(map[string]any)
				called[fmt.Sprint(cm["id"])] = fn["name"]
			}
			fmt.Fprintf(&b, "[assistant called] %d tool(s)\n", len(calls))
		default:
			fmt.Fprintf(&b, "[%v] %v\n", m["role"], m["content"])
		}
	}
	b.WriteString("\nReply with ONLY one JSON object: {\"tool_calls\":[...]} or {\"content\":\"...\"}.\n")
	return b.String()
}

func oneShotParse(out string) InProcessResponse {
	var r struct {
		ToolCalls []struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		} `json:"tool_calls"`
		Content *string `json:"content"`
	}
	i, j := strings.Index(out, "{"), strings.LastIndex(out, "}")
	if i >= 0 && j > i && json.Unmarshal([]byte(out[i:j+1]), &r) == nil {
		if len(r.ToolCalls) > 0 {
			calls := make([]InProcessToolCall, 0, len(r.ToolCalls))
			for _, c := range r.ToolCalls {
				calls = append(calls, InProcessToolCall{Name: c.Name, Arguments: c.Arguments})
			}
			return InProcessResponse{ToolCalls: calls}
		}
		if r.Content != nil {
			return InProcessResponse{Content: *r.Content}
		}
	}
	return InProcessResponse{Content: strings.TrimSpace(out)}
}

func TestMockKitOneShotCLI(t *testing.T) {
	py, dir := mockKitDir(t)
	var seen []float64
	add := NativeTool("add", "Add two numbers.", JSONSchema{"type": "object", "properties": map[string]any{
		"a": map[string]any{"type": "number"}, "b": map[string]any{"type": "number"}}},
		func(_ context.Context, args map[string]any) (string, error) {
			a, _ := args["a"].(float64)
			b, _ := args["b"].(float64)
			seen = append(seen, a, b)
			return strconv.FormatFloat(a+b, 'f', -1, 64), nil
		})
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{add}})
	if err != nil {
		t.Fatal(err)
	}
	client := CreateInProcessClient(InProcessOptions{
		Model: "fake_cli",
		GenerateStream: func(ctx context.Context, req InProcessRequest, _ func(StreamEvent)) (InProcessResponse, error) {
			cmd := exec.CommandContext(ctx, py, filepath.Join(dir, "fake_cli.py"))
			cmd.Stdin = strings.NewReader(oneShotRender(req))
			out, err := cmd.Output()
			if err != nil {
				return InProcessResponse{}, err
			}
			return oneShotParse(string(out)), nil
		},
	})
	res, err := client.Run(context.Background(), "Add 19 and 23.", tk)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if res.Text != "The sum is 42." {
		t.Errorf("text = %q", res.Text)
	}
	if fmt.Sprint(seen) != "[19 23]" {
		t.Errorf("add args = %v, want [19 23]", seen)
	}
}

// startFakeACP serves a native add toolkit as MCP and connects the fake agent.
func startFakeACP(t *testing.T, serve bool, onPerm func(context.Context, json.RawMessage) (string, error)) (*Client, *[][2]float64) {
	t.Helper()
	py, dir := mockKitDir(t)
	ctx := context.Background()
	var mu sync.Mutex
	seen := &[][2]float64{}
	opts := ACPOptions{Command: py, Args: []string{filepath.Join(dir, "fake_acp_agent.py")},
		OnPermission: onPerm, PermissionTimeout: 15 * time.Second, RequestTimeout: 15 * time.Second}
	if serve {
		add := mockNativeAdd(seen, &mu)
		add.Name = "add"
		tk, err := CreateToolkit(ctx, Options{Builtins: false, ExtraTools: []Tool{add}})
		if err != nil {
			t.Fatal(err)
		}
		srv, err := tk.AsMCPServer(nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { srv.Close() })
		opts.MCPServers = []map[string]any{{"type": "http", "name": "toolnexus", "url": srv.URL,
			"headers": []map[string]any{{"name": "Authorization", "value": "Bearer " + srv.Token}}}}
	}
	agent, err := LoadACP(ctx, opts)
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	t.Cleanup(func() { agent.Close() })
	return CreateInProcessClient(InProcessOptions{Model: "fake_acp", GenerateStream: agent.GenerateStream}), seen
}

func mockKitDrain(t *testing.T, ch <-chan StreamEvent, onText func(string)) (text, final string, acpTools []string, err error) {
	t.Helper()
	var sb strings.Builder
	for ev := range ch {
		switch ev.Type {
		case "text":
			sb.WriteString(ev.Delta)
			if onText != nil {
				onText(sb.String())
			}
		case "tool_call":
			if ev.Via == "acp" {
				acpTools = append(acpTools, ev.Name)
			}
		case "done":
			final = ev.Result.Text
		case "error":
			err = ev.Err
		}
	}
	return sb.String(), final, acpTools, err
}

func TestMockKitACP(t *testing.T) {
	empty, _ := CreateToolkit(context.Background(), Options{Builtins: false})

	t.Run("approved", func(t *testing.T) {
		client, seen := startFakeACP(t, true, nil)
		ch, err := client.Stream(context.Background(), "Use the add tool to add 17 and 25.", empty)
		if err != nil {
			t.Fatal(err)
		}
		text, final, tools, err := mockKitDrain(t, ch, nil)
		if err != nil {
			t.Fatalf("stream error: %v", err)
		}
		if text != "The sum is 42." || final != "The sum is 42." {
			t.Errorf("streamed %q final %q, want %q", text, final, "The sum is 42.")
		}
		if len(tools) == 0 {
			t.Error("no acp tool_call event")
		}
		if fmt.Sprint(*seen) != "[[17 25]]" {
			t.Errorf("add executed with %v, want [[17 25]]", *seen)
		}
	})

	t.Run("rejected", func(t *testing.T) {
		asked := false
		client, seen := startFakeACP(t, true, func(_ context.Context, params json.RawMessage) (string, error) {
			asked = strings.Contains(string(params), "reject_once")
			return "no", nil
		})
		res, err := client.Run(context.Background(), "Use the add tool to add 17 and 25.", empty)
		if err != nil {
			t.Fatalf("Run: %v", err)
		}
		if res.Text != "Permission denied." || !asked || len(*seen) != 0 {
			t.Errorf("text %q asked %v add calls %v", res.Text, asked, *seen)
		}
	})

	t.Run("no-mcp", func(t *testing.T) {
		client, _ := startFakeACP(t, false, nil)
		res, err := client.Run(context.Background(), "ping", empty)
		if err != nil {
			t.Fatalf("Run: %v", err)
		}
		if !strings.HasPrefix(res.Text, "Mock ACP reply to: ") || !strings.Contains(res.Text, "ping") {
			t.Errorf("text = %q", res.Text)
		}
	})

	t.Run("cancel", func(t *testing.T) {
		client, _ := startFakeACP(t, false, nil)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		ch, err := client.Stream(ctx, "please [wait]", empty)
		if err != nil {
			t.Fatal(err)
		}
		start := time.Now()
		text, final, _, err := mockKitDrain(t, ch, func(sofar string) {
			if strings.Contains(sofar, "Working...") {
				cancel()
			}
		})
		if !errors.Is(err, context.Canceled) || final != "" {
			t.Errorf("err = %v final %q, want context.Canceled", err, final)
		}
		if !strings.Contains(text, "Working...") {
			t.Errorf("text before cancel = %q", text)
		}
		if time.Since(start) > 10*time.Second {
			t.Error("cancel waited for the request timeout: the agent never answered stopReason cancelled")
		}
		// The session stays warm after a cancel: the next turn still works.
		res, err := client.Run(context.Background(), "again", empty)
		if err != nil || !strings.Contains(res.Text, "Mock ACP reply to: ") {
			t.Errorf("turn after cancel: %q %v", res.Text, err)
		}
	})
}
