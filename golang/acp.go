// ACP (Agent Client Protocol) model source (ADR 0031, issue #96).
//
// ACP is to *agents* what MCP is to *tools*: JSON-RPC 2.0, one object per
// line, over a child process's stdin/stdout — the exact framing local stdio
// MCP already uses in mcp.go. This file ships ACP as a `Generate` source
// (LoadACP(...).Generate has the exact shape of InProcessOptions.Generate,
// see inprocess.go), so the tool-calling loop, skills, MCP, adapters and
// sub-agents are untouched. It is NOT a new tool source and NOT a new
// client — see ADR 0031 and openspec/changes/add-acp-model-source.
//
// The agent is a real tool-calling model (openspec/changes/add-acp-tool-calling,
// SPEC §8 "ACP model source"): each prompt carries the OpenAI-shaped request —
// messages, including earlier tool calls and their results, plus the tool
// schemas — and the agent's JSON reply is parsed back into content or tool
// calls, which the loop executes through the toolkit.
//
// The warm session is the feature (ADR 0031's measurements): the agent
// process and its ACP session are created once by LoadACP and then serve
// every turn as a `session/prompt` on that same session — amortising the
// process-startup cost that dominates a cold `devin -p` call.
//
// The hard part is that toolnexus assembles a COMPLETE request every turn
// (the full message array), while an ACP session is STATEFUL — it already
// has the transcript. Resending everything each turn grows the agent's
// context quadratically. So (ADR 0036, openspec/changes/add-acp-session-delta)
// the first prompt of a session carries preamble + system + tools + messages,
// and every later prompt carries only the messages appended since — after
// checking that the request really is the conversation this session already
// holds plus our last reply plus something new. Any mismatch (tools changed,
// history edited or compacted, a different conversation, a retry, a failed
// turn) opens a fresh session on the same warm process instead. The record
// kept for that check is never used to BUILD a request, so it cannot drift
// into a wrong answer — the worst a mismatch costs is a fresh session.
//
// Everything else is mechanical: demultiplex stdout by JSON-RPC id because
// `session/update` notifications interleave with our own request replies;
// accumulate ONLY `agent_message_chunk` text (thoughts and tool narration
// must be dropped or they corrupt structured output the host expects back);
// answer `session/request_permission` immediately — the first `reject`-kind
// option by default, since toolnexus executes the tools, or the first
// `allow`-kind option with AllowAgentTools — because an unanswered permission
// request hangs the turn forever, even in bypass mode; serialise turns on one session (one ACP session is one conversation,
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
	"reflect"
	"strings"
	"sync"
	"time"
)

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
	// (ADR 0031) — if left empty, LoadACP resolves os.Getwd() and requires
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

	// AllowAgentTools lets the agent run tools of its OWN: a
	// session/request_permission is then answered with the first
	// `allow`-kind option. Default false — the first `reject`-kind option —
	// because toolnexus is the tool executor (SPEC §8, add-acp-tool-calling):
	// an agent that runs `bash` itself has escaped every hook and any
	// builtin execution seam (ADR 0033).
	AllowAgentTools bool

	// Config is applied, in order, with `session/set_config_option` after
	// every `session/new` (and after Mode) — e.g. {ID: "model", Value:
	// "openai/gpt-5"}. A pass-through: toolnexus neither knows nor validates
	// the ids. The agent advertises them (ACPClient.ConfigOptions) and
	// rejects bad ones; a rejection fails LoadACP, or the turn that reset.
	Config []ACPConfig
}

// ACPConfig is one ACP session config option. Value is a string (a `select`
// option's value id) or a bool (a `boolean` option).
type ACPConfig struct {
	ID    string
	Value any
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

	// Session state — guarded by promptMu (only touched during setup and
	// inside Generate). See planTurn for how it decides opening vs
	// continuation.
	sessionID     string
	sessionFresh  bool // the current session has not been prompted yet
	sentTools     []any
	sentMessages  []any
	lastReply     *InProcessResponse
	configOptions json.RawMessage

	cwd    string
	mode   string
	config []ACPConfig

	// promptMu serialises turns: one ACP session is one conversation, and
	// concurrent session/prompt calls would interleave into one transcript.
	promptMu sync.Mutex

	updateSink func(json.RawMessage)

	permissionTimeout time.Duration
	requestTimeout    time.Duration
	allowAgentTools   bool

	closeOnce sync.Once
	closeErr  error
}

// LoadACP spawns the ACP agent as a child process, completes `initialize` +
// `session/new` (+ optional `session/set_mode`), and returns a live,
// warm ACPClient. ctx bounds only this setup handshake — once LoadACP
// returns, the child process's lifetime is independent of ctx (ADR 0031:
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
		allowAgentTools:   opts.AllowAgentTools,
		cwd:               cwd,
		mode:              opts.Mode,
		config:            opts.Config,
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

	if err := c.openSession(ctx); err != nil {
		_ = c.Close()
		return nil, err
	}

	return c, nil
}

// openSession runs `session/new` (absolute cwd, mcpServers: [] — the host's
// MCP servers and skills stay toolnexus tools, executed by the loop, never
// handed to the agent), then the optional `session/set_mode`, then every
// Config entry. It replaces the current session and clears the turn record.
func (c *ACPClient) openSession(ctx context.Context) error {
	res, err := c.call(ctx, "session/new", map[string]any{"cwd": c.cwd, "mcpServers": []any{}})
	if err != nil {
		return fmt.Errorf("toolnexus: acp: session/new: %w", err)
	}
	var sn struct {
		SessionID     string          `json:"sessionId"`
		ConfigOptions json.RawMessage `json:"configOptions"`
	}
	if err := json.Unmarshal(res, &sn); err != nil || sn.SessionID == "" {
		return fmt.Errorf("toolnexus: acp: session/new: no sessionId in response")
	}
	c.sessionID = sn.SessionID
	c.configOptions = sn.ConfigOptions
	c.sessionFresh = true
	c.sentTools, c.sentMessages, c.lastReply = nil, nil, nil

	if c.mode != "" {
		if _, err := c.call(ctx, "session/set_mode", map[string]any{
			"sessionId": c.sessionID,
			"modeId":    c.mode,
		}); err != nil {
			return fmt.Errorf("toolnexus: acp: session/set_mode: %w", err)
		}
	}
	for _, opt := range c.config {
		params := map[string]any{"sessionId": c.sessionID, "configId": opt.ID, "value": opt.Value}
		if _, isBool := opt.Value.(bool); isBool {
			params["type"] = "boolean"
		}
		res, err := c.call(ctx, "session/set_config_option", params)
		if err != nil {
			return fmt.Errorf("toolnexus: acp: session/set_config_option %q: %w", opt.ID, err)
		}
		// The agent answers with the complete, current option state.
		var so struct {
			ConfigOptions json.RawMessage `json:"configOptions"`
		}
		if json.Unmarshal(res, &so) == nil && len(so.ConfigOptions) > 0 {
			c.configOptions = so.ConfigOptions
		}
	}
	return nil
}

// ConfigOptions returns the agent's advertised session config options, raw
// and unmodified, from the latest session/new or set_config_option answer —
// how a host discovers valid Config ids (models, modes, reasoning levels).
// Nil when the agent advertises none.
func (c *ACPClient) ConfigOptions() json.RawMessage {
	c.promptMu.Lock()
	defer c.promptMu.Unlock()
	return c.configOptions
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
			// permission request hangs the turn forever (ADR 0031).
			c.answerPermission(msg)

		default:
			// Unhandled server->client notification/request; nothing this
			// client needs (e.g. other session/update variants, fs/* calls
			// we declared unsupported in clientCapabilities).
		}
	}
}

// answerPermission selects the first `reject`-kind option by default (the
// client executes tools, the agent must not), or the first `allow`-kind
// option when AllowAgentTools is set; no matching option ⇒ cancelled.
func (c *ACPClient) answerPermission(req acpMsg) {
	var params struct {
		Options []struct {
			OptionID string `json:"optionId"`
			Kind     string `json:"kind"`
		} `json:"options"`
	}
	_ = json.Unmarshal(req.Params, &params)
	want := "reject"
	if c.allowAgentTools {
		want = "allow"
	}
	chosen := ""
	for _, o := range params.Options {
		if strings.HasPrefix(o.Kind, want) {
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
		// mixing them corrupts structured output (ADR 0031 gate item #3).
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

// Generate sends one turn on the warm session and parses the accumulated
// agent_message_chunk text into one assistant message — tool calls or
// content (SPEC §8 "ACP model source"). A turn that extends the session's
// conversation sends only the new messages; anything else opens a fresh
// session first (planTurn). Turns are serialised: only one session/prompt is
// ever in flight at a time on this client.
func (c *ACPClient) Generate(req InProcessRequest) (InProcessResponse, error) {
	c.promptMu.Lock()
	defer c.promptMu.Unlock()

	messages := acpNormalizeList(req.Messages)
	tools := acpNormalizeList(req.Tools)

	continuing, appended, fresh := c.planTurn(messages, tools)
	var prompt string
	if continuing {
		prompt = renderACPContinuation(fresh)
	} else {
		if !c.sessionFresh {
			ctx, cancel := context.WithTimeout(context.Background(), c.requestTimeout)
			err := c.openSession(ctx)
			cancel()
			if err != nil {
				c.discardTurnState()
				return InProcessResponse{}, err
			}
		}
		prompt = renderACPOpening(messages, tools)
	}

	text, err := c.sendPrompt(prompt)
	if err != nil {
		c.discardTurnState()
		return InProcessResponse{}, err
	}
	resp := parseACPReply(text)

	if continuing {
		c.sentMessages = append(c.sentMessages, appended...)
	} else {
		c.sentTools, c.sentMessages = tools, messages
	}
	c.sessionFresh = false
	c.lastReply = &resp
	return resp, nil
}

// discardTurnState forgets what the current session holds, so the next turn
// opens a fresh session — after any failed turn, nothing about the session's
// contents can be trusted.
func (c *ACPClient) discardTurnState() {
	c.sentTools, c.sentMessages, c.lastReply = nil, nil, nil
	c.sessionFresh = false
}

// planTurn decides opening vs continuation (add-acp-session-delta design):
// continue only when the tools are unchanged, the request's messages begin
// with everything already sent, the next message is an assistant message
// equivalent to our last reply (the agent already has it), and at least one
// message follows. It returns the messages to append to the record
// (the reply plus the new ones) and the new ones alone (what is sent).
func (c *ACPClient) planTurn(messages, tools []any) (continuing bool, appended, fresh []any) {
	if c.sessionFresh || c.lastReply == nil || c.sentMessages == nil {
		return false, nil, nil
	}
	if !reflect.DeepEqual(tools, c.sentTools) {
		return false, nil, nil
	}
	n := len(c.sentMessages)
	if len(messages) < n+2 {
		return false, nil, nil
	}
	if !reflect.DeepEqual(messages[:n], c.sentMessages) {
		return false, nil, nil
	}
	if !acpReplyMatches(messages[n], *c.lastReply) {
		return false, nil, nil
	}
	return true, messages[n:], messages[n+1:]
}

// acpReplyMatches reports whether msg is the assistant message the in-process
// layer built from reply: equal content, or the same tool calls in order by
// name and decoded arguments. Ids are ignored — the layer assigns call_<i>
// when the agent gave none.
func acpReplyMatches(msg any, reply InProcessResponse) bool {
	m, ok := msg.(map[string]any)
	if !ok || m["role"] != "assistant" {
		return false
	}
	calls, _ := m["tool_calls"].([]any)
	if len(reply.ToolCalls) == 0 {
		if len(calls) > 0 {
			return false
		}
		content, _ := m["content"].(string)
		return content == reply.Content
	}
	if len(calls) != len(reply.ToolCalls) {
		return false
	}
	for i, want := range reply.ToolCalls {
		cm, _ := calls[i].(map[string]any)
		fn, _ := cm["function"].(map[string]any)
		if fn == nil || fn["name"] != want.Name {
			return false
		}
		if !reflect.DeepEqual(acpDecodeArgs(fn["arguments"]), acpDecodeArgs(want.Arguments)) {
			return false
		}
	}
	return true
}

// acpDecodeArgs brings tool arguments to one comparable shape: a JSON string
// is decoded, anything else normalised through a JSON round trip.
func acpDecodeArgs(v any) any {
	switch a := v.(type) {
	case string:
		var out any
		if json.Unmarshal([]byte(a), &out) == nil {
			return out
		}
		return a
	case json.RawMessage:
		var out any
		if json.Unmarshal(a, &out) == nil {
			return out
		}
		return string(a)
	}
	return acpNormalize(v)
}

// acpNormalize round-trips a value through JSON so typed Go values
// ([]map[string]any, structs, ints) compare structurally with what was sent.
func acpNormalize(v any) any {
	b, err := json.Marshal(v)
	if err != nil {
		return v
	}
	var out any
	if json.Unmarshal(b, &out) != nil {
		return v
	}
	return out
}

func acpNormalizeList(v []any) []any {
	out := make([]any, len(v))
	for i, e := range v {
		out[i] = acpNormalize(e)
	}
	return out
}

// acpPreamble is byte-pinned by SPEC §8 — identical in all seven ports.
const acpPreamble = "You are the language model behind a tool-calling client. The client executes tools; you never do.\n" +
	"Do not run commands, read or edit files, or use any tool of your own.\n" +
	"The REQUEST below is the complete conversation in OpenAI chat-completions format: \"messages\" holds every message so far, including earlier tool calls and their results; \"tools\" lists the only tools you may call.\n" +
	"Reply with exactly one JSON object and nothing else: no prose, no markdown fences.\n" +
	"To give the final answer: {\"content\": \"<answer>\"}\n" +
	"To call tools: {\"tool_calls\": [{\"id\": \"<unique id>\", \"type\": \"function\", \"function\": {\"name\": \"<tool name>\", \"arguments\": \"<JSON-encoded arguments>\"}}]}\n" +
	"Never both. Use tool results already in \"messages\" instead of calling the same tool again.\n"

// acpContinuation is byte-pinned by SPEC §8 (session delta).
const acpContinuation = "Continue the same conversation. NEW MESSAGES below are appended to it in the same OpenAI chat-completions format; the system prompt and tools are unchanged.\n" +
	"Reply with exactly one JSON object and nothing else: no prose, no markdown fences.\n" +
	"{\"content\": \"<answer>\"} for the final answer, or {\"tool_calls\": [...]} in the format given at the start, never both.\n"

// renderACPOpening is a session's first prompt: PREAMBLE + "\nREQUEST:\n" +
// compact {"messages":[...],"tools":[...]}.
func renderACPOpening(messages, tools []any) string {
	return acpPreamble + "\nREQUEST:\n" + acpCompactJSON(struct {
		Messages []any `json:"messages"`
		Tools    []any `json:"tools"`
	}{messages, tools})
}

// renderACPContinuation is every later prompt: CONTINUATION +
// "\nNEW MESSAGES:\n" + compact {"messages":[...new only]}.
func renderACPContinuation(fresh []any) string {
	return acpContinuation + "\nNEW MESSAGES:\n" + acpCompactJSON(struct {
		Messages []any `json:"messages"`
	}{fresh})
}

// acpCompactJSON encodes without HTML escaping (a struct keeps key order).
func acpCompactJSON(v any) string {
	var buf strings.Builder
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
	return strings.TrimRight(buf.String(), "\n")
}

// parseACPReply turns the agent's reply text into one assistant message, by
// the algorithm SPEC §8 pins: strip fences, parse (or the first-{..last-}
// slice), unwrap choices[0].message / message, then tool_calls ⇒ ToolCalls,
// content ⇒ Content, anything else ⇒ the original text untouched.
func parseACPReply(text string) InProcessResponse {
	s := strings.TrimSpace(text)
	if strings.HasPrefix(s, "```") {
		if nl := strings.IndexByte(s, '\n'); nl >= 0 {
			s = s[nl+1:]
		} else {
			s = ""
		}
		s = strings.TrimSpace(s)
		s = strings.TrimSpace(strings.TrimSuffix(s, "```"))
	}

	obj, ok := acpParseObject(s)
	if !ok {
		if i, j := strings.IndexByte(s, '{'), strings.LastIndexByte(s, '}'); i >= 0 && j > i {
			obj, ok = acpParseObject(s[i : j+1])
		}
	}
	if !ok {
		return InProcessResponse{Content: text}
	}

	if choices, isArr := obj["choices"].([]any); isArr && len(choices) > 0 {
		if first, isObj := choices[0].(map[string]any); isObj {
			if msg, isObj := first["message"].(map[string]any); isObj {
				obj = msg
			}
		}
	} else if msg, isObj := obj["message"].(map[string]any); isObj {
		obj = msg
	}

	if raw, isArr := obj["tool_calls"].([]any); isArr {
		var calls []InProcessToolCall
		for _, el := range raw {
			em, isObj := el.(map[string]any)
			if !isObj {
				continue
			}
			fn := em
			if f, isObj := em["function"].(map[string]any); isObj {
				fn = f
			}
			name, _ := fn["name"].(string)
			if name == "" {
				continue
			}
			var args any = map[string]any{}
			if a, present := fn["arguments"]; present && a != nil {
				args = a // a string passes through as pre-encoded; else structured
			}
			id, _ := em["id"].(string)
			calls = append(calls, InProcessToolCall{ID: id, Name: name, Arguments: args})
		}
		if len(calls) > 0 {
			return InProcessResponse{ToolCalls: calls}
		}
	}

	if content, present := obj["content"]; present {
		switch v := content.(type) {
		case string:
			return InProcessResponse{Content: v}
		case nil:
			return InProcessResponse{Content: ""}
		default:
			var buf strings.Builder
			enc := json.NewEncoder(&buf)
			enc.SetEscapeHTML(false)
			_ = enc.Encode(v)
			return InProcessResponse{Content: strings.TrimRight(buf.String(), "\n")}
		}
	}
	return InProcessResponse{Content: text}
}

func acpParseObject(s string) (map[string]any, bool) {
	var obj map[string]any
	if err := json.Unmarshal([]byte(s), &obj); err != nil || obj == nil {
		return nil, false
	}
	return obj, true
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
