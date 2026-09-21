// ACP (Agent Client Protocol) model source (ADR 0025, issue #96).
//
// ACP is to *agents* what MCP is to *tools*: JSON-RPC 2.0, one object per
// line, over a child process's stdin/stdout — the exact framing local stdio
// MCP already uses in mcp.go. This file ships ACP as a `Generate` source
// (LoadACP(...).Generate has the exact shape of InProcessOptions.Generate,
// see inprocess.go), so the tool-calling loop, skills, MCP, adapters and
// sub-agents are untouched. It is NOT a new tool source and NOT a new
// client — see ADR 0025 and openspec/changes/add-acp-model-source.
//
// The warm session is the feature (ADR 0025's measurements): the agent
// process and its ACP session are created once by LoadACP and then serve
// every turn as a `session/prompt` on that same session — amortising the
// process-startup cost that dominates a cold `devin -p` call.
//
// The hard part is that toolnexus assembles a COMPLETE request every turn
// (the full message array), while an ACP session is STATEFUL — it already
// has the transcript. Sending the whole thing again each turn makes a naive
// agent answer a stale, near-duplicate prompt from its own history. The
// mitigation (default, per ADR 0025): render the full transcript into the
// prompt text every turn, and append an explicit "this supersedes every
// earlier prompt" marker naming the latest turn — see renderACPPrompt.
//
// Everything else is mechanical: demultiplex stdout by JSON-RPC id because
// `session/update` notifications interleave with our own request replies;
// accumulate ONLY `agent_message_chunk` text (thoughts and tool narration
// must be dropped or they corrupt structured output the host expects back);
// answer `session/request_permission` with the first `allow`-kind option —
// an unanswered permission request hangs the turn forever, even in bypass
// mode; serialise turns on one session (one ACP session is one conversation,
// concurrent prompts must not interleave into one transcript); and keep the
// child process's lifetime independent of any one turn's cancellation.
package toolnexus

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// acpSupersedesMarker names the current/latest user turn as superseding all
// earlier prompts in the (stateful) ACP session's own transcript. The exact
// text is a client-side convention, not part of the ACP protocol; kept
// recognizable and stable so an agent (or a test fixture) can key off it.
const acpSupersedesMarker = "SUPERSEDES-ALL-PRIOR:"

// ---------------------------------------------------------------------------
// Wire types (JSON-RPC 2.0, one object per line — identical framing to local
// stdio MCP in mcp.go, just without an SDK: ACP has no mature Go client yet).
// ---------------------------------------------------------------------------

type acpMsg struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *acpError       `json:"error,omitempty"`
}

type acpError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// ACPPermissionTimeoutError is returned when a turn is still blocked on an
// unanswered `session/request_permission` after ACPOptions.PermissionTimeout
// — the safety net for a broken/misbehaving agent. The normal path never
// hits this: a well-formed permission request is auto-answered the moment it
// arrives (see ACPClient.readLoop), so the turn completes immediately after.
type ACPPermissionTimeoutError struct{ Waited time.Duration }

func (e *ACPPermissionTimeoutError) Error() string {
	return fmt.Sprintf("toolnexus: acp: turn hung waiting on session/request_permission for %s", e.Waited)
}

// ---------------------------------------------------------------------------
// ACPOptions / LoadACP
// ---------------------------------------------------------------------------

// ACPOptions configures the child ACP agent process and the session opened
// against it. Only Command is required.
type ACPOptions struct {
	// Command is the ACP agent binary to spawn (e.g. "devin", with
	// Args: []string{"acp"}).
	Command string
	Args    []string
	// Env is appended to the child's environment (which otherwise inherits
	// this process's environment, exactly like exec.Command's default).
	Env []string

	// Cwd is the absolute working directory handed to `session/new`. A real
	// `devin acp` REJECTS session/new with -32602 without an absolute cwd
	// (ADR 0025) — if left empty, LoadACP resolves os.Getwd() and requires
	// that it is absolute (it always is on every supported OS).
	Cwd string

	// ProtocolVersion is negotiated in `initialize`. Defaults to 1.
	ProtocolVersion int

	// Mode, if set, issues an optional `session/set_mode` after
	// `session/new` (e.g. an agent's "bypass permissions" or "plan" mode).
	Mode string

	// PermissionTimeout bounds how long a turn waits on an unanswered
	// session/request_permission before returning ACPPermissionTimeoutError.
	// Defaults to 30s. This is a safety net for a broken agent, not the
	// normal path: a well-formed request is answered immediately.
	PermissionTimeout time.Duration

	// RequestTimeout bounds `initialize` / `session/new` / `session/set_mode`
	// and the reply half of `session/prompt`. Defaults to 30s.
	RequestTimeout time.Duration
}

// ACPClient is one warm ACP session: one child process, one session id,
// alive across many turns. Build one with LoadACP; its Generate method has
// the exact shape InProcessOptions.Generate expects.
type ACPClient struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Scanner

	mu      sync.Mutex // guards pending + nextID + updateSink
	nextID  int64
	pending map[string]chan acpMsg

	sessionID string

	// promptMu serialises turns: one ACP session is one conversation, and
	// concurrent session/prompt calls would interleave into one transcript.
	promptMu sync.Mutex

	updateSink func(json.RawMessage)

	permissionTimeout time.Duration
	requestTimeout    time.Duration

	closeOnce sync.Once
	closeErr  error
}

// LoadACP spawns the ACP agent as a child process, completes `initialize` +
// `session/new` (+ optional `session/set_mode`), and returns a live,
// warm ACPClient. ctx bounds only this setup handshake — once LoadACP
// returns, the child process's lifetime is independent of ctx (ADR 0025:
// "process lifetime independent of any one turn's cancellation").
func LoadACP(ctx context.Context, opts ACPOptions) (*ACPClient, error) {
	if opts.Command == "" {
		return nil, fmt.Errorf("toolnexus: acp: ACPOptions.Command is required")
	}

	protocolVersion := opts.ProtocolVersion
	if protocolVersion == 0 {
		protocolVersion = 1
	}
	permissionTimeout := opts.PermissionTimeout
	if permissionTimeout <= 0 {
		permissionTimeout = 30 * time.Second
	}
	requestTimeout := opts.RequestTimeout
	if requestTimeout <= 0 {
		requestTimeout = 30 * time.Second
	}

	cwd := opts.Cwd
	if cwd == "" {
		wd, err := os.Getwd()
		if err != nil {
			return nil, fmt.Errorf("toolnexus: acp: resolve cwd: %w", err)
		}
		cwd = wd
	}
	if !filepath.IsAbs(cwd) {
		return nil, fmt.Errorf("toolnexus: acp: Cwd must be absolute, got %q", cwd)
	}

	// The child process is spawned with a background context deliberately —
	// NOT ctx — so it is never killed by the caller cancelling the setup
	// context after LoadACP has returned. ctx only bounds the handshake below.
	cmd := exec.Command(opts.Command, opts.Args...)
	if len(opts.Env) > 0 {
		cmd.Env = append(os.Environ(), opts.Env...)
	}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("toolnexus: acp: stdin pipe: %w", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("toolnexus: acp: stdout pipe: %w", err)
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("toolnexus: acp: start %q: %w", opts.Command, err)
	}

	c := &ACPClient{
		cmd:               cmd,
		stdin:             stdin,
		stdout:            bufio.NewScanner(stdout),
		pending:           make(map[string]chan acpMsg),
		permissionTimeout: permissionTimeout,
		requestTimeout:    requestTimeout,
	}
	c.stdout.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	go c.readLoop()

	if _, err := c.call(ctx, "initialize", map[string]any{
		"protocolVersion": protocolVersion,
		"clientCapabilities": map[string]any{
			"fs": map[string]any{"readTextFile": false, "writeTextFile": false},
		},
	}); err != nil {
		_ = c.Close()
		return nil, fmt.Errorf("toolnexus: acp: initialize: %w", err)
	}

	res, err := c.call(ctx, "session/new", map[string]any{"cwd": cwd, "mcpServers": []any{}})
	if err != nil {
		_ = c.Close()
		return nil, fmt.Errorf("toolnexus: acp: session/new: %w", err)
	}
	var sn struct {
		SessionID string `json:"sessionId"`
	}
	if err := json.Unmarshal(res, &sn); err != nil || sn.SessionID == "" {
		_ = c.Close()
		return nil, fmt.Errorf("toolnexus: acp: session/new: no sessionId in response")
	}
	c.sessionID = sn.SessionID

	if opts.Mode != "" {
		if _, err := c.call(ctx, "session/set_mode", map[string]any{
			"sessionId": c.sessionID,
			"modeId":    opts.Mode,
		}); err != nil {
			_ = c.Close()
			return nil, fmt.Errorf("toolnexus: acp: session/set_mode: %w", err)
		}
	}

	return c, nil
}

// ---------------------------------------------------------------------------
// readLoop: demultiplex everything arriving on the child's stdout.
// ---------------------------------------------------------------------------

func (c *ACPClient) readLoop() {
	for c.stdout.Scan() {
		line := c.stdout.Bytes()
		if len(strings.TrimSpace(string(line))) == 0 {
			continue
		}
		var msg acpMsg
		if err := json.Unmarshal(line, &msg); err != nil {
			continue
		}

		switch {
		case msg.Method == "" && len(msg.ID) > 0:
			// A reply to one of OUR requests (initialize / session/new /
			// session/set_mode / session/prompt), matched by id.
			var idStr string
			_ = json.Unmarshal(msg.ID, &idStr)
			c.mu.Lock()
			ch, ok := c.pending[idStr]
			c.mu.Unlock()
			if ok {
				ch <- msg
			}

		case msg.Method == "session/update":
			c.mu.Lock()
			sink := c.updateSink
			c.mu.Unlock()
			if sink != nil {
				sink(msg.Params)
			}

		case msg.Method == "session/request_permission" && len(msg.ID) > 0:
			// Answered inline, from the read loop itself, so it can never be
			// held up behind whatever sendPrompt is doing — an unanswered
			// permission request hangs the turn forever (ADR 0025).
			c.answerPermissionFirstAllow(msg)

		default:
			// Unhandled server->client notification/request; nothing this
			// client needs (e.g. other session/update variants, fs/* calls
			// we declared unsupported in clientCapabilities).
		}
	}
}

func (c *ACPClient) answerPermissionFirstAllow(req acpMsg) {
	var params struct {
		Options []struct {
			OptionID string `json:"optionId"`
			Kind     string `json:"kind"`
		} `json:"options"`
	}
	_ = json.Unmarshal(req.Params, &params)
	chosen := ""
	for _, o := range params.Options {
		if strings.HasPrefix(o.Kind, "allow") {
			chosen = o.OptionID
			break
		}
	}
	result := map[string]any{"outcome": map[string]any{"outcome": "cancelled"}}
	if chosen != "" {
		result = map[string]any{"outcome": map[string]any{"outcome": "selected", "optionId": chosen}}
	}
	c.reply(req.ID, result)
}

func (c *ACPClient) reply(id json.RawMessage, result any) {
	r, _ := json.Marshal(result)
	c.write(acpMsg{JSONRPC: "2.0", ID: id, Result: r})
}

func (c *ACPClient) write(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	_, _ = c.stdin.Write(b)
	_, _ = c.stdin.Write([]byte("\n"))
}

// call sends one request and waits for its reply, honoring both ctx and
// c.requestTimeout — used for the one-shot setup calls (initialize,
// session/new, session/set_mode), never for session/prompt (see
// sendPrompt, which has its own permission-aware wait).
func (c *ACPClient) call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	c.mu.Lock()
	c.nextID++
	id := fmt.Sprintf("c-%d", c.nextID)
	ch := make(chan acpMsg, 1)
	c.pending[id] = ch
	c.mu.Unlock()

	idRaw, _ := json.Marshal(id)
	p, _ := json.Marshal(params)
	c.write(acpMsg{JSONRPC: "2.0", ID: idRaw, Method: method, Params: p})

	timer := time.NewTimer(c.requestTimeout)
	defer timer.Stop()

	select {
	case msg := <-ch:
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		if msg.Error != nil {
			return nil, fmt.Errorf("acp error %d: %s", msg.Error.Code, msg.Error.Message)
		}
		return msg.Result, nil
	case <-timer.C:
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, fmt.Errorf("acp: %s timed out after %s", method, c.requestTimeout)
	case <-ctx.Done():
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, ctx.Err()
	}
}

// ---------------------------------------------------------------------------
// sendPrompt: one session/prompt turn, accumulating only agent_message_chunk.
// ---------------------------------------------------------------------------

func (c *ACPClient) sendPrompt(text string) (string, error) {
	c.mu.Lock()
	c.nextID++
	id := fmt.Sprintf("c-%d", c.nextID)
	replyCh := make(chan acpMsg, 1)
	c.pending[id] = replyCh

	var accMu sync.Mutex
	var clean strings.Builder
	c.updateSink = func(raw json.RawMessage) {
		var upd struct {
			Update struct {
				SessionUpdate string `json:"sessionUpdate"`
				Content       struct {
					Type string `json:"type"`
					Text string `json:"text"`
				} `json:"content"`
			} `json:"update"`
		}
		if err := json.Unmarshal(raw, &upd); err != nil {
			return
		}
		// Only agent_message_chunk forms the reply: agent_thought_chunk and
		// tool-call narration (tool_call / tool_call_update, which carry no
		// "content" field this struct decodes anyway) are dropped entirely —
		// mixing them corrupts structured output (ADR 0025 gate item #3).
		if upd.Update.SessionUpdate != "agent_message_chunk" {
			return
		}
		accMu.Lock()
		clean.WriteString(upd.Update.Content.Text)
		accMu.Unlock()
	}
	c.mu.Unlock()

	idRaw, _ := json.Marshal(id)
	p, _ := json.Marshal(map[string]any{
		"sessionId": c.sessionID,
		"prompt":    []map[string]any{{"type": "text", "text": text}},
	})
	c.write(acpMsg{JSONRPC: "2.0", ID: idRaw, Method: "session/prompt", Params: p})

	timer := time.NewTimer(c.permissionTimeout)
	defer timer.Stop()

	select {
	case msg := <-replyCh:
		c.mu.Lock()
		delete(c.pending, id)
		c.updateSink = nil
		c.mu.Unlock()
		if msg.Error != nil {
			return "", fmt.Errorf("acp error %d: %s", msg.Error.Code, msg.Error.Message)
		}
		accMu.Lock()
		defer accMu.Unlock()
		return clean.String(), nil
	case <-timer.C:
		c.mu.Lock()
		delete(c.pending, id)
		c.updateSink = nil
		c.mu.Unlock()
		return "", &ACPPermissionTimeoutError{Waited: c.permissionTimeout}
	}
}

// ---------------------------------------------------------------------------
// Generate: the seam into InProcessOptions.Generate / CreateInProcessClient.
// ---------------------------------------------------------------------------

// Generate renders the full assembled request (req.Messages, flattened to
// role+content text) as the prompt for this turn, appends the
// acpSupersedesMarker naming the latest user turn, sends exactly one
// session/prompt on the warm session, and returns the accumulated
// agent_message_chunk text. Turns are serialised: only one session/prompt is
// ever in flight at a time on this client.
func (c *ACPClient) Generate(req InProcessRequest) (InProcessResponse, error) {
	prompt := renderACPPrompt(req)

	c.promptMu.Lock()
	defer c.promptMu.Unlock()

	content, err := c.sendPrompt(prompt)
	if err != nil {
		return InProcessResponse{}, err
	}
	return InProcessResponse{Content: content}, nil
}

// renderACPPrompt flattens req.Messages into "role: content" lines (the
// FULL request, per turn — an ACP session is stateful, so sending only the
// delta would make the client a second, shadow copy of conversation state)
// and appends the supersedes marker naming the latest user turn, so a
// stateful agent answers the current request rather than an earlier
// near-duplicate already sitting in its own session history (ADR 0025).
func renderACPPrompt(req InProcessRequest) string {
	var b strings.Builder
	lastUser := ""
	for _, m := range req.Messages {
		role, content := acpFlattenMessage(m)
		if role == "" && content == "" {
			continue
		}
		fmt.Fprintf(&b, "%s: %s\n", role, content)
		if role == "user" && content != "" {
			lastUser = content
		}
	}
	if lastUser == "" && len(req.Messages) > 0 {
		_, lastUser = acpFlattenMessage(req.Messages[len(req.Messages)-1])
	}
	fmt.Fprintf(&b, "\n%s %s", acpSupersedesMarker, lastUser)
	return b.String()
}

// acpFlattenMessage renders one message's role + content as plain text.
// Content is usually a string; anything else (multimodal parts, etc.) is
// JSON-encoded so it still renders as recognizable text rather than being
// silently dropped.
func acpFlattenMessage(m any) (role, content string) {
	mm, ok := m.(map[string]any)
	if !ok {
		return "", ""
	}
	role, _ = mm["role"].(string)
	switch v := mm["content"].(type) {
	case string:
		content = v
	case nil:
		content = ""
	default:
		if b, err := json.Marshal(v); err == nil {
			content = string(b)
		}
	}
	return role, content
}

// ---------------------------------------------------------------------------
// Close
// ---------------------------------------------------------------------------

// Close terminates the child process and is idempotent — calling it more
// than once is a no-op past the first call.
func (c *ACPClient) Close() error {
	c.closeOnce.Do(func() {
		_ = c.stdin.Close()
		if c.cmd.Process != nil {
			_ = c.cmd.Process.Kill()
		}
		c.closeErr = c.cmd.Wait()
	})
	return c.closeErr
}
