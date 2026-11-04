// CLI client, ACP transport (SPEC §8C, ADR 0037 "ACP primary").
//
// On the first model call the user's toolkit is served as a streamable-HTTP MCP
// server on 127.0.0.1:<ephemeral>/mcp, guarded by a per-session bearer token,
// and handed to the agent in session/new mcpServers (http when the agent
// advertises mcpCapabilities.http, else a stdio bridge; a project MCP file in a
// temp cwd for agents that ignore session servers). The agent calls the tools
// natively; toolnexus hooks run at the server boundary. Every permission
// request is decided per toolCallId: tools served by our server are allowed,
// everything else (the agent's own shell, edit, fetch …) is rejected.
package toolnexus

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/mark3labs/mcp-go/server"
)

// CliServedSkillToolName is the name the toolkit's `skill` tool is served
// under to an ACP agent. Several agents (copilot, devin) ship their own tool
// called `skill`, and a model asked to use "skill" picks the agent's.
const CliServedSkillToolName = "load_skill"

// cliACPServerName is the MCP server name the agent sees.
const cliACPServerName = "toolnexus"

// CliMCPTokenEnv carries the bearer token to a stdio bridge.
const CliMCPTokenEnv = "TOOLNEXUS_MCP_TOKEN"

// cliACPLaunchError marks a failure to start the agent or to complete
// initialize — the only failures on which Auto falls back to the one-shot CLI.
type cliACPLaunchError struct{ err error }

func (e *cliACPLaunchError) Error() string { return e.err.Error() }
func (e *cliACPLaunchError) Unwrap() error { return e.err }

// CliPermissionDecision records one answered session/request_permission.
type CliPermissionDecision struct {
	ToolCallID string
	Title      string
	Allowed    bool
}

type cliACPSession struct {
	client  *ACPClient
	httpSrv *http.Server
	url     string
	token   string
	tempDir string
	served  map[string]bool
	timeout int
	hint    string

	mu        sync.Mutex
	calls     map[string]string // toolCallId -> first non-empty title seen
	decisions map[string]bool   // toolCallId -> allowed
	log       []CliPermissionDecision
	ran       []string // served tool names our server executed
}

// ACPSessionInfo is a read-only view of the live ACP session, for tests and
// diagnostics. The bearer token is never exposed.
type ACPSessionInfo struct {
	URL            string
	MCPServersSent []any
	Permissions    []CliPermissionDecision
	ServedCalls    []string
	SessionCwd     string
}

// ACPSession returns the live session's info (false when the one-shot
// transport is in use or no call has opened a session yet). Header and env
// values in MCPServersSent are redacted.
func (c *CliClient) ACPSession() (ACPSessionInfo, bool) {
	c.mu.Lock()
	s := c.acp
	c.mu.Unlock()
	if s == nil {
		return ACPSessionInfo{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return ACPSessionInfo{
		URL:            s.url,
		MCPServersSent: redactACPServers(s.client.MCPServersSent),
		Permissions:    append([]CliPermissionDecision{}, s.log...),
		ServedCalls:    append([]string{}, s.ran...),
		SessionCwd:     s.tempDir,
	}, true
}

func redactACPServers(in []any) []any {
	b, _ := json.Marshal(in)
	var out []any
	_ = json.Unmarshal(b, &out)
	for _, e := range out {
		m, _ := e.(map[string]any)
		for _, k := range []string{"headers", "env"} {
			if arr, ok := m[k].([]any); ok {
				for _, nv := range arr {
					if x, ok := nv.(map[string]any); ok {
						x["value"] = "[redacted]"
					}
				}
			}
		}
	}
	return out
}

func openCliACPSession(ctx context.Context, c *CliClient) (*cliACPSession, error) {
	argv := c.acpArgv
	bin, err := exec.LookPath(argv[0])
	if err != nil {
		return nil, &cliACPLaunchError{fmt.Errorf("cli: command not found: %s", argv[0])}
	}
	tokBytes := make([]byte, 32)
	if _, err := rand.Read(tokBytes); err != nil {
		return nil, fmt.Errorf("cli: acp: token: %w", err)
	}
	s := &cliACPSession{
		token:     hex.EncodeToString(tokBytes),
		served:    map[string]bool{},
		calls:     map[string]string{},
		decisions: map[string]bool{},
		timeout:   c.timeout,
	}
	if c.style.ACP != nil {
		s.hint = c.style.ACP.SystemHint
	}
	tools := cliServedTools(c.opts.Toolkit, c.opts.Hooks, func(name string) {
		s.mu.Lock()
		s.ran = append(s.ran, name)
		s.mu.Unlock()
	})
	for _, t := range tools {
		s.served[t.Name] = true
	}
	if err := s.serve(tools); err != nil {
		return nil, err
	}

	cwd := c.opts.Cwd
	if cwd == "" {
		cwd, _ = os.Getwd()
	}
	if abs, err := filepath.Abs(cwd); err == nil {
		cwd = abs
	}
	var servers []ACPMCPServer
	if c.style.ACP != nil && c.style.ACP.ProjectMCPFile != "" && len(c.opts.ACPCommand) == 0 {
		dir, err := os.MkdirTemp("", "toolnexus-acp-*")
		if err != nil {
			s.stopServer()
			return nil, err
		}
		s.tempDir, cwd = dir, dir
		f := filepath.Join(dir, c.style.ACP.ProjectMCPFile)
		_ = os.MkdirAll(filepath.Dir(f), 0o700)
		b, _ := json.Marshal(map[string]any{"mcpServers": map[string]any{cliACPServerName: map[string]any{
			"type": "http", "url": s.url, "headers": map[string]string{"Authorization": "Bearer " + s.token},
		}}})
		if err := os.WriteFile(f, b, 0o600); err != nil {
			s.Close()
			return nil, err
		}
	} else {
		entry := ACPMCPServer{Name: cliACPServerName, URL: s.url,
			Headers: map[string]string{"Authorization": "Bearer " + s.token}}
		bridge := c.opts.BridgeCommand
		if len(bridge) == 0 {
			if p, err := exec.LookPath("toolnexus"); err == nil {
				bridge = []string{p, "mcp-bridge"}
			}
		}
		if len(bridge) > 0 {
			entry.Command = bridge[0]
			entry.Args = append(append([]string{}, bridge[1:]...), s.url)
			entry.Env = map[string]string{CliMCPTokenEnv: s.token}
		}
		servers = []ACPMCPServer{entry}
	}

	var stderr cappedBuffer
	timeout := time.Duration(c.timeout) * time.Millisecond
	client, err := LoadACP(ctx, ACPOptions{
		Command:           bin,
		Args:              argv[1:],
		Env:               expandCliEnv(c.env),
		Cwd:               cwd,
		PermissionTimeout: timeout,
		RequestTimeout:    timeout,
		OnPermission:      s.permission,
		OnUpdate: func(raw json.RawMessage) {
			s.update(raw)
			if c.opts.OnACPUpdate != nil {
				c.opts.OnACPUpdate(raw)
			}
		},
		MCPServers: servers,
		Model:      c.opts.Model,
		Stderr:     &stderr,
	})
	if err != nil {
		s.Close()
		msg := err.Error()
		if t := strings.TrimSpace(stderr.String()); t != "" {
			msg += ": " + t
		}
		err = errors.New(strings.Replace(msg, "toolnexus: acp:", "cli: acp:", 1))
		if strings.HasPrefix(msg, "toolnexus: acp: start") || strings.HasPrefix(msg, "toolnexus: acp: initialize") {
			return nil, &cliACPLaunchError{err}
		}
		return nil, err
	}
	s.client = client
	return s, nil
}

func (s *cliACPSession) serve(tools []Tool) error {
	srv := buildMcpServer(tools, &MCPServeConfig{Name: cliACPServerName}, nil)
	h := server.NewStreamableHTTPServer(srv, server.WithStateLess(true))
	want := []byte("Bearer " + s.token)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/mcp" {
			http.NotFound(w, r)
			return
		}
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), want) != 1 {
			w.Header().Set("WWW-Authenticate", "Bearer")
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		h.ServeHTTP(w, r)
	})
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("cli: acp: serve toolkit: %w", err)
	}
	s.url = fmt.Sprintf("http://127.0.0.1:%d/mcp", ln.Addr().(*net.TCPAddr).Port)
	s.httpSrv = &http.Server{Handler: handler, ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = s.httpSrv.Serve(ln) }()
	return nil
}

func (s *cliACPSession) stopServer() {
	if s.httpSrv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = s.httpSrv.Shutdown(ctx)
	}
}

// Close ends the agent, stops the server and removes the temp session dir.
func (s *cliACPSession) Close() error {
	var err error
	if s.client != nil {
		_ = s.client.Close()
	}
	s.stopServer()
	if s.tempDir != "" {
		err = os.RemoveAll(s.tempDir)
	}
	return err
}

func (s *cliACPSession) generate(ctx context.Context, req InProcessRequest, emit func(StreamEvent)) (InProcessResponse, error) {
	req.Messages = cliWithSystemHint(req.Messages, s.hint)
	res, err := s.client.GenerateStream(ctx, req, emit)
	var pt *ACPPermissionTimeoutError
	if errors.As(err, &pt) {
		return InProcessResponse{}, fmt.Errorf("cli: timed out after %dms", s.timeout)
	}
	return res, err
}

// cliWithSystemHint appends a style's ACP system hint to the first system
// message (a copy; the caller's messages are not mutated), or prepends a system
// message holding only the hint when the conversation has none.
func cliWithSystemHint(msgs []any, hint string) []any {
	if hint == "" {
		return msgs
	}
	out := make([]any, 0, len(msgs)+1)
	if len(msgs) > 0 {
		if m, ok := msgs[0].(map[string]any); ok && m["role"] == "system" {
			if c, ok := m["content"].(string); ok {
				cp := make(map[string]any, len(m))
				for k, v := range m {
					cp[k] = v
				}
				if c == "" {
					cp["content"] = hint
				} else {
					cp["content"] = c + "\n\n" + hint
				}
				return append(append(out, cp), msgs[1:]...)
			}
		}
	}
	out = append(out, map[string]any{"role": "system", "content": hint})
	return append(out, msgs...)
}

// update remembers the first non-empty title of each toolCallId (devin puts
// the name only on the tool_call update and sends an empty permission title).
func (s *cliACPSession) update(raw json.RawMessage) {
	var u struct {
		Update struct {
			Kind       string `json:"sessionUpdate"`
			ToolCallID string `json:"toolCallId"`
			Title      string `json:"title"`
		} `json:"update"`
	}
	if json.Unmarshal(raw, &u) != nil || u.Update.ToolCallID == "" || u.Update.Title == "" {
		return
	}
	if u.Update.Kind != "tool_call" && u.Update.Kind != "tool_call_update" {
		return
	}
	s.mu.Lock()
	if _, ok := s.calls[u.Update.ToolCallID]; !ok {
		s.calls[u.Update.ToolCallID] = u.Update.Title
	}
	s.mu.Unlock()
}

// permission decides one session/request_permission by its toolCallId: the
// decision is made once per id (from the permission's own title, else the
// title recorded for that id) and never re-made. Allowed ⇒ an allow option;
// otherwise "" so the ACP client answers with a reject option.
func (s *cliACPSession) permission(_ context.Context, params json.RawMessage) (string, error) {
	var p struct {
		ToolCall struct {
			ToolCallID string `json:"toolCallId"`
			Title      string `json:"title"`
		} `json:"toolCall"`
		Options []struct {
			OptionID string `json:"optionId"`
			Kind     string `json:"kind"`
		} `json:"options"`
	}
	_ = json.Unmarshal(params, &p)
	id := p.ToolCall.ToolCallID
	s.mu.Lock()
	allowed, decided := s.decisions[id]
	title := p.ToolCall.Title
	if title == "" {
		title = s.calls[id]
	}
	if !decided {
		allowed = id != "" && cliIsServedCall(title, s.served)
		if id != "" {
			s.decisions[id] = allowed
		}
	}
	s.log = append(s.log, CliPermissionDecision{ToolCallID: id, Title: title, Allowed: allowed})
	s.mu.Unlock()
	if !allowed {
		return "", nil
	}
	for _, o := range p.Options {
		if o.Kind == "allow_once" {
			return o.OptionID, nil
		}
	}
	for _, o := range p.Options {
		if strings.HasPrefix(o.Kind, "allow") {
			return o.OptionID, nil
		}
	}
	return "", nil
}

// cliIsServedCall matches a permission title against the exact spellings
// agents use for a tool of OUR server: mcp__toolnexus__<tool> (claude),
// toolnexus/<tool>, "<tool> (toolnexus MCP Server)" (gemini), "Calling <tool>
// from toolnexus" (devin), "<tool>(…)" (gemini skill), or the bare tool name
// (copilot). No substring matching.
func cliIsServedCall(title string, served map[string]bool) bool {
	if title == "" {
		return false
	}
	for n := range served {
		switch title {
		case n, "mcp__" + cliACPServerName + "__" + n, cliACPServerName + "/" + n,
			n + " (" + cliACPServerName + " MCP Server)", "Calling " + n + " from " + cliACPServerName:
			return true
		}
		if strings.HasPrefix(title, n+"(") && strings.HasSuffix(title, ")") {
			return true
		}
	}
	return false
}

// cliServedTools is the toolkit as served to the agent: the `skill` tool is
// renamed to CliServedSkillToolName with the skills list in its description,
// and every Execute runs through Hooks.BeforeTool / AfterTool.
func cliServedTools(tk *Toolkit, hooks *Hooks, ran func(string)) []Tool {
	if tk == nil {
		return nil
	}
	src := tk.Tools()
	out := make([]Tool, 0, len(src))
	for _, t := range src {
		t := t
		name := t.Name
		if t.Source == SourceSkill && t.Name == "skill" {
			t.Name = CliServedSkillToolName
			if sp := tk.SkillsPrompt(); sp != "" {
				t.Description = t.Description + "\n\n" + sp
			}
		}
		inner := t.Execute
		served := t.Name
		t.Execute = func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			ctx := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				ctx = tctx.Ctx
			}
			if hooks != nil && hooks.BeforeTool != nil {
				ov, err := hooks.BeforeTool(ctx, BeforeToolEvent{Name: name, Args: args, Hints: t.Hints})
				if err != nil {
					return ToolResult{Output: err.Error(), IsError: true}, nil
				}
				if ov != nil && ov.Result != nil {
					return *ov.Result, nil
				}
				if ov != nil && ov.Args != nil {
					args = ov.Args
				}
			}
			if ran != nil {
				ran(served)
			}
			res, err := inner(args, tctx)
			if err != nil {
				res = ToolResult{Output: err.Error(), IsError: true}
			}
			if hooks != nil && hooks.AfterTool != nil {
				ov, herr := hooks.AfterTool(ctx, AfterToolEvent{Name: name, Args: args, Result: res})
				if herr != nil {
					return ToolResult{Output: herr.Error(), IsError: true}, nil
				}
				if ov != nil && ov.Result != nil {
					res = *ov.Result
				}
			}
			return res, nil
		}
		out = append(out, t)
	}
	return out
}

// RunMCPBridge is the stdio side of a stdio McpServer entry: each JSON-RPC
// line read from in is POSTed to the toolkit's streamable-HTTP endpoint (with
// the bearer token when set), and each reply — plain JSON or SSE `data:`
// frames — is written to out as one line. `toolnexus mcp-bridge <url>` runs it
// with the token from TOOLNEXUS_MCP_TOKEN.
func RunMCPBridge(url, token string, in io.Reader, out io.Writer) error {
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	var session string
	for sc.Scan() {
		line := bytes.TrimSpace(sc.Bytes())
		if len(line) == 0 {
			continue
		}
		req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(line))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json, text/event-stream")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
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
		if resp.StatusCode == http.StatusUnauthorized {
			return errors.New("mcp-bridge: unauthorized (missing or wrong " + CliMCPTokenEnv + ")")
		}
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

// cappedBuffer keeps the last 4 KiB of the agent's stderr for error messages.
type cappedBuffer struct {
	mu  sync.Mutex
	buf []byte
}

func (b *cappedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.buf = append(b.buf, p...)
	if len(b.buf) > 4096 {
		b.buf = b.buf[len(b.buf)-4096:]
	}
	return len(p), nil
}

func (b *cappedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return string(b.buf)
}
