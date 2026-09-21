// Package client is a MINIMAL ACP (Agent Client Protocol) client, built only
// to settle the ADR-0031 spike gate against a real child process over real
// OS pipes. It is not meant to be a reusable library — it does the bare
// minimum: initialize, session/new, session/prompt, demultiplexing by
// JSON-RPC id (because session/update notifications interleave with
// responses), accumulating ONLY agent_message_chunk, and answering
// session/request_permission with the first allow-kind option.
package client

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

type rpcMsg struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// PermissionTimeoutErr is returned when a turn hangs waiting on an
// unanswered session/request_permission past the client's own timeout.
// A real client without this timeout would hang forever — this is the
// trap ADR-0031 gate item #2 is about.
type PermissionTimeoutErr struct{ Waited time.Duration }

func (e *PermissionTimeoutErr) Error() string {
	return fmt.Sprintf("acp: turn hung waiting on session/request_permission for %s", e.Waited)
}

// Client is one warm ACP session: one child process, one session id, alive
// across many prompts.
type Client struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Scanner

	mu        sync.Mutex
	nextID    int64
	pending   map[string]chan rpcMsg // request id -> reply channel (our requests)
	sessionID string

	updateSink     updateSinkFn
	permissionSeen bool
	lastDirty      string

	// AutoAnswerPermission, when true (the default), answers
	// session/request_permission with the FIRST allow-kind option the
	// second such a request arrives during a Prompt call. Set false to
	// simulate a host that never answers (gate item #2, the hang case).
	AutoAnswerPermission bool

	// PermissionTimeout bounds how long Prompt waits for a turn that is
	// blocked on an unanswered permission request. Zero disables the bound
	// (real hang, used to prove the failure exists before showing the fix).
	PermissionTimeout time.Duration

	closeOnce sync.Once
}

// Start spawns the agent as a child process (real OS pipes for stdin/stdout,
// exactly like the ADR's "one JSON object per line over a child process's
// stdin/stdout") and completes ACP `initialize`.
func Start(binPath string, args ...string) (*Client, error) {
	cmd := exec.Command(binPath, args...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = nil // discard; the fake agent doesn't log to stderr

	if err := cmd.Start(); err != nil {
		return nil, err
	}

	c := &Client{
		cmd:                  cmd,
		stdin:                stdin,
		stdout:               bufio.NewScanner(stdout),
		pending:              make(map[string]chan rpcMsg),
		AutoAnswerPermission: true,
		PermissionTimeout:    5 * time.Second,
	}
	c.stdout.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)

	go c.readLoop()

	if _, err := c.call("initialize", map[string]any{
		"protocolVersion": 1,
		"clientCapabilities": map[string]any{
			"fs": map[string]any{"readTextFile": false, "writeTextFile": false},
		},
	}); err != nil {
		return nil, fmt.Errorf("initialize: %w", err)
	}

	cwd, err := os.Getwd()
	if err != nil {
		return nil, err
	}
	res, err := c.call("session/new", map[string]any{"cwd": cwd, "mcpServers": []any{}})
	if err != nil {
		return nil, fmt.Errorf("session/new: %w", err)
	}
	var sn struct {
		SessionID string `json:"sessionId"`
	}
	_ = json.Unmarshal(res, &sn)
	c.sessionID = sn.SessionID

	return c, nil
}

// readLoop demultiplexes EVERYTHING arriving on stdout: replies to our
// requests (by id, matched against c.pending), session/update notifications
// (routed to the active Prompt call via updateSink), and server-initiated
// requests like session/request_permission (answered inline here).
func (c *Client) readLoop() {
	for c.stdout.Scan() {
		line := c.stdout.Bytes()
		if len(strings.TrimSpace(string(line))) == 0 {
			continue
		}
		var msg rpcMsg
		if err := json.Unmarshal(line, &msg); err != nil {
			continue
		}

		switch {
		case msg.Method == "" && len(msg.ID) > 0:
			// a reply to one of OUR requests
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
			c.mu.Lock()
			auto := c.AutoAnswerPermission
			c.mu.Unlock()
			c.mu.Lock()
			c.permissionSeen = true
			c.mu.Unlock()
			if auto {
				c.answerPermissionFirstAllow(msg)
			}
			// else: deliberately drop it on the floor (the "hang" scenario)

		default:
			// unhandled server->client request; nothing this spike needs.
		}
	}
}

// updateSink, when set, receives the raw params of every session/update
// notification while a Prompt call is in flight.
type updateSinkFn func(raw json.RawMessage)

func (c *Client) answerPermissionFirstAllow(req rpcMsg) {
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

func (c *Client) reply(id json.RawMessage, result any) {
	r, _ := json.Marshal(result)
	c.write(rpcMsg{JSONRPC: "2.0", ID: id, Result: r})
}

func (c *Client) write(v any) {
	b, _ := json.Marshal(v)
	c.mu.Lock()
	defer c.mu.Unlock()
	c.stdin.Write(b)
	c.stdin.Write([]byte("\n"))
}

func (c *Client) call(method string, params any) (json.RawMessage, error) {
	c.mu.Lock()
	c.nextID++
	id := fmt.Sprintf("c-%d", c.nextID)
	ch := make(chan rpcMsg, 1)
	c.pending[id] = ch
	c.mu.Unlock()

	idRaw, _ := json.Marshal(id)
	p, _ := json.Marshal(params)
	c.write(rpcMsg{JSONRPC: "2.0", ID: idRaw, Method: method, Params: p})

	select {
	case msg := <-ch:
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		if msg.Error != nil {
			return nil, fmt.Errorf("acp error %d: %s", msg.Error.Code, msg.Error.Message)
		}
		return msg.Result, nil
	case <-time.After(10 * time.Second):
		return nil, fmt.Errorf("acp: %s timed out", method)
	}
}

// Prompt sends one FULL prompt (per the ADR's proposed default: the whole
// assembled request, every turn) and accumulates ONLY agent_message_chunk
// text into the returned answer. It returns PermissionTimeoutErr if the
// turn is still blocked on an unanswered permission request after
// c.PermissionTimeout (0 = wait forever, used to first PROVE the hang).
func (c *Client) Prompt(ctx context.Context, text string) (string, error) {
	c.mu.Lock()
	c.nextID++
	id := fmt.Sprintf("c-%d", c.nextID)
	replyCh := make(chan rpcMsg, 1)
	c.pending[id] = replyCh

	var mu sync.Mutex
	var clean strings.Builder
	var dirty strings.Builder // includes thought/tool noise, for the "noisy" comparison
	c.updateSink = func(raw json.RawMessage) {
		var upd struct {
			SessionID string `json:"sessionId"`
			Update    struct {
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
		mu.Lock()
		defer mu.Unlock()
		switch upd.Update.SessionUpdate {
		case "agent_message_chunk":
			clean.WriteString(upd.Update.Content.Text)
			dirty.WriteString(upd.Update.Content.Text)
		case "agent_thought_chunk":
			dirty.WriteString(upd.Update.Content.Text)
		}
	}
	c.mu.Unlock()

	idRaw, _ := json.Marshal(id)
	p, _ := json.Marshal(map[string]any{
		"sessionId": c.sessionID,
		"prompt":    []map[string]any{{"type": "text", "text": text}},
	})
	c.write(rpcMsg{JSONRPC: "2.0", ID: idRaw, Method: "session/prompt", Params: p})

	var timeoutCh <-chan time.Time
	if c.PermissionTimeout > 0 {
		timeoutCh = time.After(c.PermissionTimeout)
	}

	select {
	case msg := <-replyCh:
		c.mu.Lock()
		delete(c.pending, id)
		c.updateSink = nil
		c.mu.Unlock()
		if msg.Error != nil {
			return "", fmt.Errorf("acp error %d: %s", msg.Error.Code, msg.Error.Message)
		}
		mu.Lock()
		defer mu.Unlock()
		c.lastDirty = dirty.String()
		return clean.String(), nil
	case <-timeoutCh:
		c.mu.Lock()
		delete(c.pending, id)
		c.updateSink = nil
		c.mu.Unlock()
		return "", &PermissionTimeoutErr{Waited: c.PermissionTimeout}
	case <-ctx.Done():
		return "", ctx.Err()
	}
}

// LastDirty returns the unfiltered accumulation (every chunk kind) from the
// most recent Prompt call — only used to DEMONSTRATE the corruption gate
// item; a real client never needs this.
func (c *Client) LastDirty() string { return c.lastDirty }

// Close terminates the child process. Idempotent.
func (c *Client) Close() error {
	var err error
	c.closeOnce.Do(func() {
		c.stdin.Close()
		err = c.cmd.Process.Kill()
		c.cmd.Wait()
	})
	return err
}
