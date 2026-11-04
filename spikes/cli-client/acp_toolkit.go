// Spike (ADR 0037, ACP primary): open an ACP session whose agent sees the
// user's OWN toolkit. On open, the toolkit is served as MCP on
// 127.0.0.1:<ephemeral> (Toolkit.Serve, SPEC §7C) and handed to the agent in
// session/new mcpServers — as an "http" entry when the agent advertises
// agentCapabilities.mcpCapabilities.http, otherwise as a "stdio" entry that runs
// this binary's `bridge <url>` mode. The server stops with the session.
//
// The agent, not the toolnexus loop, executes the tools here: the toolkit's
// tools are MCP tools to it. Proof that a tool ran comes from the server side
// (ServeOptions.OnCall), never from the model's text alone.
//
// Not library code: golang/acp.go is untouched (it still sends mcpServers: []).
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// ACPAgent is how to launch one ACP agent. Command is required.
type ACPAgent struct {
	Command string
	Args    []string
	Env     []string
	Cwd     string
	// Mode, if set, is sent as session/set_mode after session/new.
	Mode string
	// Model, if set, is sent as session/set_model (unstable in ACP; ignored on error).
	Model string
	// ClientCapsMeta, if set, is sent as initialize clientCapabilities._meta
	// (vendor extension flags, e.g. devin's "cognition.ai/mcp").
	ClientCapsMeta map[string]any
	// ProjectMCPFile, if set (e.g. ".mcp.json"), is for agents that ignore
	// session/new mcpServers and read MCP servers from the project dir
	// instead (devin): the session runs in a fresh temp cwd holding that file
	// with the toolkit as an http server. The temp dir goes with the session.
	ProjectMCPFile string
}

// ToolkitSessionOptions tunes the spike session.
type ToolkitSessionOptions struct {
	// ForceStdio hands the toolkit over as a stdio entry (bridge) even when
	// the agent accepts http — to exercise the bridge.
	ForceStdio bool
	// BridgeCommand is the argv that runs `bridge <url>`; default: this binary.
	BridgeCommand []string
	// ServerName is the MCP server name the agent sees. Default "toolnexus".
	ServerName string
	TimeoutMs  int // per prompt; default 300000 (owner decision 2026-11-04)
	OnUpdate   func(json.RawMessage)
}

// PermissionLog records every session/request_permission and our answer.
type PermissionLog struct {
	Title    string `json:"title"`
	Ours     bool   `json:"ours"`
	OptionID string `json:"optionId"`
}

// ToolkitSession is one ACP session with the toolkit attached.
type ToolkitSession struct {
	cmd     *exec.Cmd
	stdin   io.WriteCloser
	mu      sync.Mutex
	nextID  int64
	pending map[string]chan acpMessage
	done    chan struct{}

	serve      *tn.ServeHandle
	ourTools   map[string]bool
	serverName string
	timeout    time.Duration
	onUpdate   func(json.RawMessage)

	SessionID  string
	AgentInfo  json.RawMessage // initialize result: agentInfo
	MCPCaps    json.RawMessage // initialize result: agentCapabilities.mcpCapabilities
	MCPEntry   map[string]any  // the exact entry sent in session/new mcpServers
	MCPURL     string
	tempDir    string
	SessionNew json.RawMessage // raw session/new result (modes, models)
	Stderr     *bytes.Buffer

	turnMu   sync.Mutex
	text     strings.Builder
	agentTCs []string
	served   []string // tool names our MCP server executed (OnCall)
	perms    []PermissionLog
	titles   map[string]string // toolCallId -> title from tool_call updates
}

type acpMessage struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *struct {
		Code    int             `json:"code"`
		Message string          `json:"message"`
		Data    json.RawMessage `json:"data,omitempty"`
	} `json:"error,omitempty"`
}

// HTTPEntry / StdioEntry are the two ACP McpServer shapes (ACP schema
// McpServer: http {type,name,url,headers[]}, stdio {name,command,args[],env[]}).
func HTTPEntry(name, url string) map[string]any {
	return map[string]any{"type": "http", "name": name, "url": url, "headers": []any{}}
}

func StdioEntry(name string, argv []string) map[string]any {
	return map[string]any{"name": name, "command": argv[0], "args": argv[1:], "env": []any{}}
}

// OpenToolkitSession serves tk as MCP, launches the agent, and opens a session
// whose mcpServers carries the toolkit.
func OpenToolkitSession(ctx context.Context, tk *tn.Toolkit, agent ACPAgent, o ToolkitSessionOptions) (*ToolkitSession, error) {
	if agent.Command == "" {
		return nil, errors.New("acp: Command is required")
	}
	if o.ServerName == "" {
		o.ServerName = "toolnexus"
	}
	if o.TimeoutMs <= 0 {
		o.TimeoutMs = 300000
	}
	s := &ToolkitSession{pending: map[string]chan acpMessage{}, done: make(chan struct{}),
		ourTools: map[string]bool{}, serverName: o.ServerName,
		timeout: time.Duration(o.TimeoutMs) * time.Millisecond, onUpdate: o.OnUpdate, Stderr: &bytes.Buffer{}}
	for _, t := range tk.Tools() {
		s.ourTools[t.Name] = true
	}
	h, err := tk.Serve("127.0.0.1:0", tn.ServeOptions{
		MCP: &tn.MCPServeConfig{Name: o.ServerName},
		OnCall: func(ev tn.OnCallEvent) {
			s.mu.Lock()
			s.served = append(s.served, ev.Name)
			s.mu.Unlock()
		},
	})
	if err != nil {
		return nil, fmt.Errorf("acp: serve toolkit: %w", err)
	}
	s.serve = h
	s.MCPURL = h.URL + "/mcp"

	cwd := agent.Cwd
	if cwd == "" {
		cwd, _ = os.Getwd()
	}
	if agent.ProjectMCPFile != "" {
		dir, err := os.MkdirTemp("", "acp-session-*")
		if err != nil {
			_ = h.Stop()
			return nil, err
		}
		s.tempDir, cwd = dir, dir
		f := filepath.Join(dir, agent.ProjectMCPFile)
		_ = os.MkdirAll(filepath.Dir(f), 0o755)
		b, _ := json.Marshal(map[string]any{"mcpServers": map[string]any{o.ServerName: map[string]any{"type": "http", "url": s.MCPURL}}})
		if err := os.WriteFile(f, b, 0o600); err != nil {
			_ = h.Stop()
			return nil, err
		}
	}
	cmd := exec.Command(agent.Command, agent.Args...)
	cmd.Dir = cwd
	cmd.Env = append(os.Environ(), agent.Env...)
	cmd.Stderr = s.Stderr
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	if err := cmd.Start(); err != nil {
		_ = h.Stop()
		return nil, fmt.Errorf("acp: command not found: %s", agent.Command)
	}
	s.cmd, s.stdin = cmd, stdin
	go s.readLoop(stdout)

	clientCaps := map[string]any{
		"fs":       map[string]any{"readTextFile": false, "writeTextFile": false},
		"terminal": false,
	}
	if agent.ClientCapsMeta != nil {
		clientCaps["_meta"] = agent.ClientCapsMeta
	}
	initRes, err := s.call(ctx, "initialize", map[string]any{"protocolVersion": 1, "clientCapabilities": clientCaps})
	if err != nil {
		_ = s.Close()
		return nil, fmt.Errorf("acp: initialize: %w", err)
	}
	var ir struct {
		AgentInfo         json.RawMessage `json:"agentInfo"`
		AgentCapabilities struct {
			MCP json.RawMessage `json:"mcpCapabilities"`
		} `json:"agentCapabilities"`
		AuthMethods []struct {
			ID string `json:"id"`
		} `json:"authMethods"`
	}
	_ = json.Unmarshal(initRes, &ir)
	s.AgentInfo, s.MCPCaps = ir.AgentInfo, ir.AgentCapabilities.MCP
	var caps struct {
		HTTP bool `json:"http"`
	}
	_ = json.Unmarshal(ir.AgentCapabilities.MCP, &caps)

	if caps.HTTP && !o.ForceStdio {
		s.MCPEntry = HTTPEntry(o.ServerName, s.MCPURL)
	} else {
		argv := o.BridgeCommand
		if len(argv) == 0 {
			self, _ := os.Executable()
			argv = []string{self}
		}
		s.MCPEntry = StdioEntry(o.ServerName, append(append([]string{}, argv...), "bridge", s.MCPURL))
	}
	res, err := s.call(ctx, "session/new", map[string]any{"cwd": cwd, "mcpServers": []any{s.MCPEntry}})
	if err != nil {
		_ = s.Close()
		return nil, fmt.Errorf("acp: session/new: %w", err)
	}
	var sn struct {
		SessionID string `json:"sessionId"`
	}
	if json.Unmarshal(res, &sn) != nil || sn.SessionID == "" {
		_ = s.Close()
		return nil, errors.New("acp: session/new: no sessionId")
	}
	s.SessionID = sn.SessionID
	s.SessionNew = res
	if agent.Mode != "" {
		if _, err := s.call(ctx, "session/set_mode", map[string]any{"sessionId": s.SessionID, "modeId": agent.Mode}); err != nil {
			_ = s.Close()
			return nil, fmt.Errorf("acp: session/set_mode: %w", err)
		}
	}
	if agent.Model != "" {
		// The model id passes through unchanged. Stable ACP: session/set_config_option
		// on the "model" option; older agents: unstable session/set_model.
		if _, err := s.call(ctx, "session/set_config_option", map[string]any{"sessionId": s.SessionID, "configId": "model", "value": agent.Model}); err != nil {
			if _, err2 := s.call(ctx, "session/set_model", map[string]any{"sessionId": s.SessionID, "modelId": agent.Model}); err2 != nil {
				_ = s.Close()
				return nil, fmt.Errorf("acp: set model %q: %v / %v", agent.Model, err, err2)
			}
		}
	}
	return s, nil
}

// TurnResult is what one prompt produced.
type TurnResult struct {
	Text        string          `json:"text"`
	StopReason  string          `json:"stopReason"`
	AgentTools  []string        `json:"agentToolCalls"`  // tool_call titles the agent reported
	ServedTools []string        `json:"servedToolCalls"` // tools OUR server executed
	Permissions []PermissionLog `json:"permissions"`
}

// Prompt runs one turn.
func (s *ToolkitSession) Prompt(ctx context.Context, text string) (TurnResult, error) {
	s.turnMu.Lock()
	defer s.turnMu.Unlock()
	s.mu.Lock()
	s.text.Reset()
	s.agentTCs, s.served, s.perms = nil, nil, nil
	s.mu.Unlock()
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	res, err := s.call(ctx, "session/prompt", map[string]any{
		"sessionId": s.SessionID,
		"prompt":    []any{map[string]any{"type": "text", "text": text}},
	})
	s.mu.Lock()
	defer s.mu.Unlock()
	out := TurnResult{Text: s.text.String(), AgentTools: s.agentTCs, ServedTools: s.served, Permissions: s.perms}
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return out, fmt.Errorf("acp: timed out after %dms", s.timeout.Milliseconds())
		}
		return out, err
	}
	var pr struct {
		StopReason string `json:"stopReason"`
	}
	_ = json.Unmarshal(res, &pr)
	out.StopReason = pr.StopReason
	return out, nil
}

// Close ends the agent process group and stops the toolkit's MCP server.
func (s *ToolkitSession) Close() error {
	if s.stdin != nil {
		_ = s.stdin.Close()
	}
	if s.cmd != nil && s.cmd.Process != nil {
		_ = syscall.Kill(-s.cmd.Process.Pid, syscall.SIGTERM)
		select {
		case <-s.done:
		case <-time.After(3 * time.Second):
			_ = syscall.Kill(-s.cmd.Process.Pid, syscall.SIGKILL)
		}
		_ = s.cmd.Wait()
	}
	if s.tempDir != "" {
		_ = os.RemoveAll(s.tempDir)
	}
	if s.serve != nil {
		return s.serve.Stop()
	}
	return nil
}

func (s *ToolkitSession) readLoop(r io.Reader) {
	defer close(s.done)
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	for sc.Scan() {
		var m acpMessage
		if json.Unmarshal(sc.Bytes(), &m) != nil {
			continue // agents sometimes log to stdout
		}
		switch {
		case m.Method == "" && len(m.ID) > 0:
			s.mu.Lock()
			ch := s.pending[string(m.ID)]
			delete(s.pending, string(m.ID))
			s.mu.Unlock()
			if ch != nil {
				ch <- m
			}
		case m.Method == "session/update":
			s.onSessionUpdate(m.Params)
		case m.Method == "session/request_permission":
			s.answerPermission(m)
		case len(m.ID) > 0:
			s.write(map[string]any{"jsonrpc": "2.0", "id": m.ID,
				"error": map[string]any{"code": -32601, "message": "method not found: " + m.Method}})
		}
	}
	s.mu.Lock()
	for id, ch := range s.pending {
		close(ch)
		delete(s.pending, id)
	}
	s.mu.Unlock()
}

func (s *ToolkitSession) onSessionUpdate(p json.RawMessage) {
	if s.onUpdate != nil {
		s.onUpdate(p)
	}
	var u struct {
		Update struct {
			Kind    string `json:"sessionUpdate"`
			Content struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
			Title      string `json:"title"`
			ToolCallID string `json:"toolCallId"`
		} `json:"update"`
	}
	if json.Unmarshal(p, &u) != nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	switch u.Update.Kind {
	case "agent_message_chunk":
		if u.Update.Content.Type == "text" {
			s.text.WriteString(u.Update.Content.Text)
		}
	case "tool_call", "tool_call_update":
		if u.Update.Title == "" {
			break
		}
		if u.Update.Kind == "tool_call" {
			s.agentTCs = append(s.agentTCs, u.Update.Title)
		}
		if s.titles == nil {
			s.titles = map[string]string{}
		}
		s.titles[u.Update.ToolCallID] = u.Update.Title
	}
}

// IsOurs reports whether a permission request targets a tool of OUR toolkit
// server. Agents spell MCP tools differently (claude: mcp__<server>__<tool>,
// others: "<server>/<tool>" or the bare tool name), so the request's title and
// raw input are matched against our server name and tool names.
func (s *ToolkitSession) IsOurs(params json.RawMessage) (bool, string) {
	var p struct {
		ToolCall struct {
			ToolCallID string          `json:"toolCallId"`
			Title      string          `json:"title"`
			RawInput   json.RawMessage `json:"rawInput"`
			Kind       string          `json:"kind"`
		} `json:"toolCall"`
	}
	_ = json.Unmarshal(params, &p)
	title := p.ToolCall.Title
	if title == "" { // devin sends an empty title; the tool_call update had it
		s.mu.Lock()
		title = s.titles[p.ToolCall.ToolCallID]
		s.mu.Unlock()
	}
	hay := title + " " + string(p.ToolCall.RawInput)
	if strings.Contains(hay, "mcp__"+s.serverName+"__") || strings.Contains(hay, s.serverName+"/") ||
		strings.HasSuffix(title, " from "+s.serverName) ||
		strings.Contains(hay, s.serverName+"_") || strings.Contains(hay, s.serverName+":") {
		return true, title
	}
	for name := range s.ourTools {
		if title == name || strings.HasSuffix(title, "__"+name) || strings.HasSuffix(title, "/"+name) ||
			strings.HasPrefix(title, name+" ") || strings.HasPrefix(title, name+"(") {
			return true, title
		}
	}
	return false, title
}

// answerPermission auto-approves only OUR toolkit server's tools; everything
// else (the agent's own shell/edit/fetch tools) is rejected.
func (s *ToolkitSession) answerPermission(m acpMessage) {
	ours, title := s.IsOurs(m.Params)
	var p struct {
		Options []struct {
			OptionID string `json:"optionId"`
			Kind     string `json:"kind"`
		} `json:"options"`
	}
	_ = json.Unmarshal(m.Params, &p)
	pick := func(prefix string) string {
		for _, o := range p.Options {
			if o.Kind == prefix+"_once" {
				return o.OptionID
			}
		}
		for _, o := range p.Options {
			if strings.HasPrefix(o.Kind, prefix) {
				return o.OptionID
			}
		}
		return ""
	}
	id := pick("reject")
	if ours {
		id = pick("allow")
	}
	s.mu.Lock()
	s.perms = append(s.perms, PermissionLog{Title: title, Ours: ours, OptionID: id})
	s.mu.Unlock()
	outcome := map[string]any{"outcome": "cancelled"}
	if id != "" {
		outcome = map[string]any{"outcome": "selected", "optionId": id}
	}
	s.write(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{"outcome": outcome}})
}

func (s *ToolkitSession) write(v any) {
	b, _ := json.Marshal(v)
	s.mu.Lock()
	defer s.mu.Unlock()
	_, _ = s.stdin.Write(append(b, '\n'))
}

func (s *ToolkitSession) call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	s.mu.Lock()
	s.nextID++
	id := fmt.Sprintf("%d", s.nextID)
	ch := make(chan acpMessage, 1)
	s.pending[id] = ch
	s.mu.Unlock()
	s.write(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(id), "method": method, "params": params})
	select {
	case m, ok := <-ch:
		if !ok {
			return nil, fmt.Errorf("agent exited: %s", tail(s.Stderr.String(), 400))
		}
		if m.Error != nil {
			return nil, fmt.Errorf("%d %s %s", m.Error.Code, m.Error.Message, string(m.Error.Data))
		}
		return m.Result, nil
	case <-ctx.Done():
		if method == "session/prompt" {
			s.write(map[string]any{"jsonrpc": "2.0", "method": "session/cancel", "params": map[string]any{"sessionId": s.SessionID}})
		}
		return nil, ctx.Err()
	}
}

func tail(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[len(s)-n:]
}

// RunBridge is the stdio side of a stdio McpServer entry: it relays each
// JSON-RPC line from stdin to the toolkit's streamable-HTTP endpoint and writes
// the reply (plain JSON or SSE `data:` frames) back as one line.
func RunBridge(url string, in io.Reader, out io.Writer) error {
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	var session string
	for sc.Scan() {
		line := bytes.TrimSpace(sc.Bytes())
		if len(line) == 0 {
			continue
		}
		req, _ := http.NewRequest("POST", url, bytes.NewReader(line))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json, text/event-stream")
		if session != "" {
			req.Header.Set("Mcp-Session-Id", session)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return err
		}
		if v := resp.Header.Get("Mcp-Session-Id"); v != "" {
			session = v
		}
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
			for _, l := range strings.Split(string(body), "\n") {
				if d, ok := strings.CutPrefix(strings.TrimRight(l, "\r"), "data:"); ok {
					fmt.Fprintln(out, strings.TrimSpace(d))
				}
			}
			continue
		}
		if b := bytes.TrimSpace(body); len(b) > 0 && b[0] == '{' {
			fmt.Fprintln(out, string(b))
		}
	}
	return sc.Err()
}
