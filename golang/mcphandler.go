// MCP — INBOUND, embeddable: a plain streamable-HTTP MCP server as an
// http.Handler with generic per-request hooks (SPEC §7C.1, ADR 0039).
//
// Serve(addr, {mcp}) (mcpserve.go) owns a listener and exposes one static tool
// list. MCPHandler is what a host mounts on its own mux, behind its own TLS,
// CORS and OAuth. Every policy is a hook; toolnexus has no notion of tenant,
// user, role or storage — the host decides those inside the hooks.
//
// The transport is implemented here over plain JSON-RPC (no mcp-go server):
// mcp-go's server cannot resolve tools per request, and only dequeues a
// server→client request (elicitation) on a GET stream. Here a confirm
// elicitation rides the SAME POST's SSE response, which the mcp-go client (and
// the spec, 2025-06-18) supports. No dependency is added.

package toolnexus

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// MCPHandlerImplementedVersions are the MCP protocol versions whose wire
// MCPHandler actually implements (ADR 0039 §versions). A handler accepts only
// these, however it is configured:
//
//   - 2025-06-18 — the baseline (the default).
//
//   - 2025-11-25 — its server-visible wire deltas over 2025-06-18 are optional
//     fields (icons, title/description on Implementation, tasks, URL-mode
//     elicitation) plus one rule the handler implements: invalid tool
//     arguments are a tool execution error (isError), not -32602.
//
//   - 2026-07-28 — opt-in only (never a default): the stateless wire of
//     SPEC §7C.2 (server/discover, header validation, MRTR, skills extension),
//     served by mcphandler2026.go.
//
// Not accepted: 2025-03-26 (permits JSON-RPC batching, which this handler
// rejects).
var MCPHandlerImplementedVersions = []string{"2026-07-28", "2025-11-25", "2025-06-18"}

// mcpDefaultVersions is the default MCPHandlerConfig.ProtocolVersions.
var mcpDefaultVersions = []string{"2025-06-18"}

// MCPDecisionKind is the outcome of a BeforeCall gate.
type MCPDecisionKind string

const (
	MCPAllow   MCPDecisionKind = "allow"
	MCPDeny    MCPDecisionKind = "deny"
	MCPConfirm MCPDecisionKind = "confirm"
)

// MCPDecision is what BeforeCall returns. The zero value allows.
//
//   - Allow: the tool runs.
//   - Deny: the tool does not run; the caller gets Result when set, else an
//     isError result carrying Message.
//   - Confirm: the client is asked (elicitation/create, Message as the prompt)
//     when it declared the elicitation capability; accept ⇒ the tool runs,
//     decline/cancel ⇒ isError "declined". When the client cannot elicit, the
//     caller gets Result when set (e.g. a host's "re-call with nonce" result),
//     else an isError "confirmation required: <Message>".
type MCPDecision struct {
	Kind    MCPDecisionKind
	Message string
	Result  *ToolResult
}

// MCPServerInfo is advertised at initialize.
type MCPServerInfo struct {
	Name         string
	Title        string // serverInfo.title (display name); "" ⇒ omitted
	Version      string
	Instructions string
}

// MCPResource is one entry of resources/list.
type MCPResource struct {
	URI         string
	Name        string
	Title       string
	Description string
	MimeType    string
}

// MCPResourceContent is one entry of resources/read. Set Text, or Blob
// (base64).
type MCPResourceContent struct {
	URI      string
	MimeType string
	Text     string
	Blob     string
}

// MCPPromptArgument describes one prompt argument.
type MCPPromptArgument struct {
	Name        string
	Description string
	Required    bool
}

// MCPPrompt is one entry of prompts/list.
type MCPPrompt struct {
	Name        string
	Title       string
	Description string
	Arguments   []MCPPromptArgument
}

// MCPPromptMessage is one text message of a prompts/get result.
type MCPPromptMessage struct {
	Role string // "user" | "assistant"
	Text string
}

// MCPPromptResult is the prompts/get result.
type MCPPromptResult struct {
	Description string
	Messages    []MCPPromptMessage
}

// MCPSessionIDs issues and checks Mcp-Session-Id values. The default is an
// in-memory set. A host plugs its own (signed ids, a DB lookup) so any replica
// can validate any session; the backing store is the host's.
type MCPSessionIDs interface {
	Generate(ctx context.Context) (string, error)
	// Validate reports whether id is a live session (false ⇒ HTTP 404).
	Validate(ctx context.Context, id string) (bool, error)
	Terminate(ctx context.Context, id string) error
}

// MCPCallEvent is surfaced once per inbound tools/call.
type MCPCallEvent struct {
	Name        string
	Source      ToolSource
	Ms          int64
	IsError     bool
	Err         error // the Execute error, when it returned one
	ResultBytes int   // size of the JSON CallToolResult sent back
	Decision    MCPDecisionKind
	Identity    any
	SessionID   string
}

// MCPHandlerConfig configures MCPHandler. Every field is optional.
type MCPHandlerConfig struct {
	// Name/Version/Instructions are the static serverInfo; ServerInfo, when
	// set, wins. Defaults "toolnexus" / "0.1.0".
	Name         string
	Title        string
	Version      string
	Instructions string
	ServerInfo   func(ctx context.Context) MCPServerInfo

	// ProtocolVersions this handler accepts, newest first. Entries outside
	// MCPHandlerImplementedVersions are dropped. Default ["2025-06-18"].
	// initialize echoes the client's version when accepted, else the first.
	// "2026-07-28" is opt-in (SPEC §7C.2): initialize never answers it (that
	// wire has no initialize; clients use server/discover), and on a mount
	// that also lists legacy versions a request is served on the 2026 wire
	// only when it carries an Mcp-Method header or a _meta protocol version.
	ProtocolVersions []string

	// Authenticate runs on every HTTP request before JSON-RPC parsing. Its
	// result reaches every hook and tool via IdentityFrom(ctx). An error ⇒
	// HTTP 401 with the WWW-Authenticate header from WWWAuthenticate (when set
	// and non-empty).
	Authenticate    func(r *http.Request) (any, error)
	WWWAuthenticate func(r *http.Request, err error) string

	// ListTools resolves the tool list per request, for tools/list AND the
	// tools/call lookup (a name outside it is never executed). Tools may be
	// built at runtime. nil ⇒ the toolkit's tools.
	ListTools func(ctx context.Context) ([]Tool, error)

	// BeforeCall gates each tools/call. args is the raw JSON arguments.
	BeforeCall func(ctx context.Context, tool Tool, args json.RawMessage) (MCPDecision, error)
	// ConfirmTimeout bounds an elicitation round-trip. Default 5 minutes.
	ConfirmTimeout time.Duration

	// OnCall fires once per tools/call; its panics are isolated.
	OnCall func(ev MCPCallEvent)

	// Resources / prompts. A nil List hook ⇒ capability not advertised.
	ListResources func(ctx context.Context) ([]MCPResource, error)
	ReadResource  func(ctx context.Context, uri string) ([]MCPResourceContent, error)
	ListPrompts   func(ctx context.Context) ([]MCPPrompt, error)
	GetPrompt     func(ctx context.Context, name string, args map[string]string) (MCPPromptResult, error)

	// ListSkills publishes skills through the SEP-2640 skills extension on the
	// 2026-07-28 wire (skills/list, skills/get, resources/read of skill://
	// URIs). nil ⇒ the extension is not advertised. LoadMCPSkills(dir) builds
	// the list from a skills directory; where skills live is the host's call.
	ListSkills func(ctx context.Context) ([]MCPSkill, error)

	// SessionIDs replaces the default in-memory session set.
	SessionIDs MCPSessionIDs
	// Stateless issues no Mcp-Session-Id and checks none; elicitation is then
	// never used (confirm takes the no-elicitation path).
	Stateless bool
	// StatelessSessionID, when non-empty in Stateless mode, is returned as
	// Mcp-Session-Id on initialize (for clients that expect one) and is never
	// validated.
	StatelessSessionID string

	// MaxBodyBytes caps a POST body. Default 4 MiB.
	MaxBodyBytes int64
}

type mcpCtxKey int

const (
	mcpIdentityKey mcpCtxKey = iota
	mcpRequestKey
	mcpSessionKey
	mcpMetaKey
)

// MCPRequestMetaFrom returns the `params._meta` of the JSON-RPC request being
// served (nil when absent), e.g. per-request client hints.
func MCPRequestMetaFrom(ctx context.Context) map[string]any {
	if ctx == nil {
		return nil
	}
	m, _ := ctx.Value(mcpMetaKey).(map[string]any)
	return m
}

// IdentityFrom returns the value Authenticate produced for this request (nil
// when no Authenticate is configured).
func IdentityFrom(ctx context.Context) any {
	if ctx == nil {
		return nil
	}
	return ctx.Value(mcpIdentityKey)
}

// RequestFrom returns the inbound *http.Request MCPHandler is serving (e.g. to
// read the Host header in ServerInfo or ListTools).
func RequestFrom(ctx context.Context) *http.Request {
	if ctx == nil {
		return nil
	}
	r, _ := ctx.Value(mcpRequestKey).(*http.Request)
	return r
}

// MCPSessionIDFrom returns the Mcp-Session-Id of the request ("" if none).
func MCPSessionIDFrom(ctx context.Context) string {
	if ctx == nil {
		return ""
	}
	s, _ := ctx.Value(mcpSessionKey).(string)
	return s
}

// MCPHTTPHandler is the embeddable MCP server returned by MCPHandler.
type MCPHTTPHandler struct {
	tk       *Toolkit
	cfg      MCPHandlerConfig
	ids      MCPSessionIDs
	versions []string // every accepted version, newest first
	legacy   []string // versions minus 2026-07-28: the initialize/session wire

	mu      sync.Mutex
	caps    map[string]bool          // session → client declared elicitation
	streams map[chan []byte]struct{} // open GET streams
	pending map[string]chan json.RawMessage
	seq     atomic.Int64
}

// MCPHandler returns a streamable-HTTP MCP server for this toolkit, to mount on
// the host's own mux. Serve(addr, {mcp}) is unchanged.
func (tk *Toolkit) MCPHandler(cfg MCPHandlerConfig) *MCPHTTPHandler {
	h := &MCPHTTPHandler{
		tk: tk, cfg: cfg, ids: cfg.SessionIDs,
		caps:    map[string]bool{},
		streams: map[chan []byte]struct{}{},
		pending: map[string]chan json.RawMessage{},
	}
	for _, v := range cfg.ProtocolVersions {
		if mcpHasVersion(MCPHandlerImplementedVersions, v) && !mcpHasVersion(h.versions, v) {
			h.versions = append(h.versions, v)
		}
	}
	if len(h.versions) == 0 {
		h.versions = mcpDefaultVersions
	}
	for _, v := range h.versions {
		if v != MCPProtocol2026 {
			h.legacy = append(h.legacy, v)
		}
	}
	if h.ids == nil {
		h.ids = &memorySessionIDs{live: map[string]bool{}}
	}
	if h.cfg.ConfirmTimeout <= 0 {
		h.cfg.ConfirmTimeout = 5 * time.Minute
	}
	if h.cfg.MaxBodyBytes <= 0 {
		h.cfg.MaxBodyBytes = 4 << 20
	}
	return h
}

// NotifyToolsChanged sends notifications/tools/list_changed on every open GET
// stream. Clients re-run tools/list (which calls ListTools again).
func (h *MCPHTTPHandler) NotifyToolsChanged() {
	msg, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.streams {
		select {
		case ch <- msg:
		default: // slow stream: drop rather than block the host
		}
	}
}

type memorySessionIDs struct {
	mu   sync.Mutex
	live map[string]bool
}

func (m *memorySessionIDs) Generate(context.Context) (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	id := hex.EncodeToString(b)
	m.mu.Lock()
	m.live[id] = true
	m.mu.Unlock()
	return id, nil
}

func (m *memorySessionIDs) Validate(_ context.Context, id string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.live[id], nil
}

func (m *memorySessionIDs) Terminate(_ context.Context, id string) error {
	m.mu.Lock()
	delete(m.live, id)
	m.mu.Unlock()
	return nil
}

// ── HTTP ──────────────────────────────────────────────────────────────────

func (h *MCPHTTPHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	ctx := context.WithValue(r.Context(), mcpRequestKey, r)
	if r.Method == http.MethodPost && mcpHasVersion(h.versions, MCPProtocol2026) && h.handlePost2026(ctx, w, r) {
		return
	}
	if h.only2026() && r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	if h.cfg.Authenticate != nil {
		id, err := h.cfg.Authenticate(r)
		if err != nil {
			h.writeAuthFailure(w, r, err)
			return
		}
		ctx = context.WithValue(ctx, mcpIdentityKey, id)
	}
	switch r.Method {
	case http.MethodPost:
		h.handlePost(ctx, w, r)
	case http.MethodGet:
		h.handleGet(ctx, w, r)
	case http.MethodDelete:
		h.handleDelete(ctx, w, r)
	default:
		w.Header().Set("Allow", "GET, POST, DELETE")
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
	}
}

type mcpRPCMessage struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   json.RawMessage `json:"error,omitempty"`
}

// checkSession validates the Mcp-Session-Id header (stateful mode) and the
// MCP-Protocol-Version header. It writes the HTTP error itself on failure.
func (h *MCPHTTPHandler) checkSession(ctx context.Context, w http.ResponseWriter, r *http.Request) (context.Context, string, bool) {
	if v := r.Header.Get("MCP-Protocol-Version"); v != "" && !mcpHasVersion(h.versions, v) {
		http.Error(w, "Bad Request: unsupported MCP-Protocol-Version", http.StatusBadRequest)
		return ctx, "", false
	}
	if h.cfg.Stateless {
		return ctx, "", true
	}
	sid := r.Header.Get("Mcp-Session-Id")
	if sid == "" {
		http.Error(w, "Bad Request: missing Mcp-Session-Id", http.StatusBadRequest)
		return ctx, "", false
	}
	ok, err := h.ids.Validate(ctx, sid)
	if err != nil || !ok {
		http.Error(w, "Session not found", http.StatusNotFound)
		return ctx, "", false
	}
	return context.WithValue(ctx, mcpSessionKey, sid), sid, true
}

func (h *MCPHTTPHandler) handlePost(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	if mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || mt != "application/json" {
		http.Error(w, "Unsupported Media Type: must be application/json", http.StatusUnsupportedMediaType)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, h.cfg.MaxBodyBytes+1))
	if err != nil {
		writeMcpRPCError(w, nil, -32700, "read error")
		return
	}
	if int64(len(body)) > h.cfg.MaxBodyBytes {
		http.Error(w, "Request Entity Too Large", http.StatusRequestEntityTooLarge)
		return
	}
	trimmed := strings.TrimSpace(string(body))
	if strings.HasPrefix(trimmed, "[") {
		// JSON-RPC batching was removed in protocol 2025-06-18.
		writeMcpRPCError(w, nil, -32600, "batch requests are not supported")
		return
	}
	var msg mcpRPCMessage
	if err := json.Unmarshal(body, &msg); err != nil || msg.JSONRPC != "2.0" {
		writeMcpRPCError(w, nil, -32700, "parse error")
		return
	}

	if msg.Method == "initialize" {
		h.handleInitialize(ctx, w, msg)
		return
	}

	ctx, sid, ok := h.checkSession(ctx, w, r)
	if !ok {
		return
	}

	// A response from the client (the answer to our elicitation).
	if msg.Method == "" {
		if len(msg.ID) > 0 {
			h.deliver(sid, msg)
		}
		w.WriteHeader(http.StatusAccepted)
		return
	}
	// A notification (no id): acknowledge.
	if len(msg.ID) == 0 || string(msg.ID) == "null" {
		w.WriteHeader(http.StatusAccepted)
		return
	}

	var pm struct {
		Meta map[string]any `json:"_meta"`
	}
	if json.Unmarshal(msg.Params, &pm) == nil && pm.Meta != nil {
		ctx = context.WithValue(ctx, mcpMetaKey, pm.Meta)
	}
	rw := &mcpRPCWriter{w: w}
	result, rerr := h.dispatch(ctx, rw, sid, msg)
	rw.respond(msg.ID, result, rerr)
}

func (h *MCPHTTPHandler) handleInitialize(ctx context.Context, w http.ResponseWriter, msg mcpRPCMessage) {
	var p struct {
		ProtocolVersion string `json:"protocolVersion"`
		Capabilities    struct {
			Elicitation json.RawMessage `json:"elicitation"`
		} `json:"capabilities"`
	}
	_ = json.Unmarshal(msg.Params, &p)
	version := h.legacy[0]
	if mcpHasVersion(h.legacy, p.ProtocolVersion) {
		version = p.ProtocolVersion
	}

	sid := ""
	if h.cfg.Stateless {
		sid = h.cfg.StatelessSessionID
	} else {
		var err error
		if sid, err = h.ids.Generate(ctx); err != nil {
			writeMcpRPCError(w, msg.ID, -32603, "cannot create session")
			return
		}
		ctx = context.WithValue(ctx, mcpSessionKey, sid)
		el := len(p.Capabilities.Elicitation) > 0 && string(p.Capabilities.Elicitation) != "null"
		h.mu.Lock()
		h.caps[sid] = el
		h.mu.Unlock()
	}

	info := MCPServerInfo{Name: h.cfg.Name, Title: h.cfg.Title, Version: h.cfg.Version, Instructions: h.cfg.Instructions}
	if h.cfg.ServerInfo != nil {
		info = h.cfg.ServerInfo(ctx)
	}
	if info.Name == "" {
		info.Name = mcpServeDefaultName
	}
	if info.Version == "" {
		info.Version = mcpServeDefaultVersion
	}
	caps := map[string]any{"tools": map[string]any{"listChanged": true}}
	if h.cfg.ListResources != nil {
		caps["resources"] = map[string]any{}
	}
	if h.cfg.ListPrompts != nil {
		caps["prompts"] = map[string]any{}
	}
	res := map[string]any{
		"protocolVersion": version,
		"capabilities":    caps,
		"serverInfo":      omitEmpty(map[string]any{"name": info.Name, "title": info.Title, "version": info.Version}),
	}
	if info.Instructions != "" {
		res["instructions"] = info.Instructions
	}
	if sid != "" {
		w.Header().Set("Mcp-Session-Id", sid)
	}
	(&mcpRPCWriter{w: w}).respond(msg.ID, res, nil)
}

func (h *MCPHTTPHandler) handleGet(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	if !strings.Contains(r.Header.Get("Accept"), "text/event-stream") {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	ctx, _, ok := h.checkSession(ctx, w, r)
	if !ok {
		return
	}
	fl, _ := w.(http.Flusher)
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusOK)
	if fl != nil {
		fl.Flush()
	}
	ch := make(chan []byte, 16)
	h.mu.Lock()
	h.streams[ch] = struct{}{}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.streams, ch)
		h.mu.Unlock()
	}()
	bw := bufio.NewWriter(w)
	for {
		select {
		case <-ctx.Done():
			return
		case m := <-ch:
			fmt.Fprintf(bw, "event: message\ndata: %s\n\n", m)
			if bw.Flush() != nil {
				return
			}
			if fl != nil {
				fl.Flush()
			}
		}
	}
}

func (h *MCPHTTPHandler) handleDelete(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	if h.cfg.Stateless {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	ctx, sid, ok := h.checkSession(ctx, w, r)
	if !ok {
		return
	}
	if err := h.ids.Terminate(ctx, sid); err != nil {
		http.Error(w, "cannot terminate session", http.StatusInternalServerError)
		return
	}
	h.mu.Lock()
	delete(h.caps, sid)
	h.mu.Unlock()
	w.WriteHeader(http.StatusOK)
}

// ── JSON-RPC dispatch ─────────────────────────────────────────────────────

func (h *MCPHTTPHandler) dispatch(ctx context.Context, rw *mcpRPCWriter, sid string, msg mcpRPCMessage) (any, *rpcError) {
	switch msg.Method {
	case "ping":
		return map[string]any{}, nil
	case "tools/list":
		tools, err := h.tools(ctx)
		if err != nil {
			return nil, &rpcError{-32603, err.Error()}
		}
		out := make([]map[string]any, 0, len(tools))
		for _, t := range tools {
			out = append(out, mcpWireTool(t))
		}
		return map[string]any{"tools": out}, nil
	case "tools/call":
		return h.callTool(ctx, rw, sid, msg.Params)
	case "resources/list":
		if h.cfg.ListResources == nil {
			break
		}
		rs, err := h.cfg.ListResources(ctx)
		if err != nil {
			return nil, &rpcError{-32603, err.Error()}
		}
		out := make([]map[string]any, 0, len(rs))
		for _, r := range rs {
			out = append(out, omitEmpty(map[string]any{"uri": r.URI, "name": r.Name, "title": r.Title, "description": r.Description, "mimeType": r.MimeType}))
		}
		return map[string]any{"resources": out}, nil
	case "resources/read":
		if h.cfg.ListResources == nil || h.cfg.ReadResource == nil {
			break
		}
		var p struct {
			URI string `json:"uri"`
		}
		_ = json.Unmarshal(msg.Params, &p)
		cs, err := h.cfg.ReadResource(ctx, p.URI)
		if err != nil {
			return nil, &rpcError{-32002, err.Error()}
		}
		out := make([]map[string]any, 0, len(cs))
		for _, c := range cs {
			m := omitEmpty(map[string]any{"uri": c.URI, "mimeType": c.MimeType, "blob": c.Blob})
			if c.Blob == "" {
				m["text"] = c.Text
			}
			out = append(out, m)
		}
		return map[string]any{"contents": out}, nil
	case "prompts/list":
		if h.cfg.ListPrompts == nil {
			break
		}
		ps, err := h.cfg.ListPrompts(ctx)
		if err != nil {
			return nil, &rpcError{-32603, err.Error()}
		}
		out := make([]map[string]any, 0, len(ps))
		for _, p := range ps {
			m := omitEmpty(map[string]any{"name": p.Name, "title": p.Title, "description": p.Description})
			if len(p.Arguments) > 0 {
				args := make([]map[string]any, 0, len(p.Arguments))
				for _, a := range p.Arguments {
					am := omitEmpty(map[string]any{"name": a.Name, "description": a.Description})
					if a.Required {
						am["required"] = true
					}
					args = append(args, am)
				}
				m["arguments"] = args
			}
			out = append(out, m)
		}
		return map[string]any{"prompts": out}, nil
	case "prompts/get":
		if h.cfg.ListPrompts == nil || h.cfg.GetPrompt == nil {
			break
		}
		var p struct {
			Name      string            `json:"name"`
			Arguments map[string]string `json:"arguments"`
		}
		if err := json.Unmarshal(msg.Params, &p); err != nil {
			return nil, &rpcError{-32602, "invalid params"}
		}
		pr, err := h.cfg.GetPrompt(ctx, p.Name, p.Arguments)
		if err != nil {
			return nil, &rpcError{-32602, err.Error()}
		}
		msgs := make([]map[string]any, 0, len(pr.Messages))
		for _, m := range pr.Messages {
			msgs = append(msgs, map[string]any{"role": m.Role, "content": map[string]any{"type": "text", "text": m.Text}})
		}
		return omitEmpty(map[string]any{"description": pr.Description, "messages": msgs}), nil
	}
	return nil, &rpcError{-32601, "method not found: " + msg.Method}
}

func (h *MCPHTTPHandler) tools(ctx context.Context) ([]Tool, error) {
	if h.cfg.ListTools != nil {
		return h.cfg.ListTools(ctx)
	}
	return h.tk.Tools(), nil
}

func (h *MCPHTTPHandler) callTool(ctx context.Context, rw *mcpRPCWriter, sid string, params json.RawMessage) (any, *rpcError) {
	var p struct {
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
	}
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &rpcError{-32602, "invalid params"}
	}
	tools, err := h.tools(ctx)
	if err != nil {
		return nil, &rpcError{-32603, err.Error()}
	}
	var tool *Tool
	for i := range tools {
		if tools[i].Name == p.Name {
			tool = &tools[i]
			break
		}
	}
	if tool == nil || tool.Execute == nil {
		return nil, &rpcError{-32602, "tool not found: " + p.Name}
	}
	args := map[string]any{}
	if len(p.Arguments) > 0 && string(p.Arguments) != "null" {
		if err := json.Unmarshal(p.Arguments, &args); err != nil {
			if r := RequestFrom(ctx); r != nil && r.Header.Get("MCP-Protocol-Version") == "2025-11-25" {
				// 2025-11-25: input validation errors are tool execution errors.
				b, _ := json.Marshal(toolResultToMcp(ToolResult{Output: "invalid arguments: must be an object", IsError: true}))
				return json.RawMessage(b), nil
			}
			return nil, &rpcError{-32602, "arguments must be an object"}
		}
	} else {
		p.Arguments = json.RawMessage("{}")
	}

	started := time.Now()
	ev := MCPCallEvent{Name: tool.Name, Source: tool.Source, Decision: MCPAllow, Identity: IdentityFrom(ctx), SessionID: sid}
	finish := func(res ToolResult, execErr error) any {
		wire := toolResultToMcp(res)
		b, _ := json.Marshal(wire)
		ev.Ms = time.Since(started).Milliseconds()
		ev.IsError = res.IsError
		ev.Err = execErr
		ev.ResultBytes = len(b)
		if h.cfg.OnCall != nil {
			func() {
				defer func() { _ = recover() }()
				h.cfg.OnCall(ev)
			}()
		}
		return json.RawMessage(b)
	}

	if h.cfg.BeforeCall != nil {
		d, err := h.cfg.BeforeCall(ctx, *tool, p.Arguments)
		if err != nil {
			d = MCPDecision{Kind: MCPDeny, Message: err.Error()}
		}
		if d.Kind == "" {
			d.Kind = MCPAllow
		}
		ev.Decision = d.Kind
		switch d.Kind {
		case MCPDeny:
			if d.Result != nil {
				return finish(*d.Result, nil), nil
			}
			msg := d.Message
			if msg == "" {
				msg = "denied"
			}
			return finish(ToolResult{Output: msg, IsError: true}, nil), nil
		case MCPConfirm:
			if !h.canElicit(sid) {
				if d.Result != nil {
					return finish(*d.Result, nil), nil
				}
				return finish(ToolResult{Output: "confirmation required: " + d.Message, IsError: true}, nil), nil
			}
			accepted, err := h.elicitConfirm(ctx, rw, sid, d.Message)
			if err != nil {
				return finish(ToolResult{Output: "confirmation failed: " + err.Error(), IsError: true}, nil), nil
			}
			if !accepted {
				return finish(ToolResult{Output: "declined", IsError: true}, nil), nil
			}
		}
	}

	res, execErr := tool.Execute(args, &ToolContext{Ctx: ctx})
	if execErr != nil {
		res = ToolResult{Output: execErr.Error(), IsError: true}
	}
	return finish(res, execErr), nil
}

func (h *MCPHTTPHandler) canElicit(sid string) bool {
	if sid == "" {
		return false
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.caps[sid]
}

// elicitConfirm sends elicitation/create on this POST's SSE stream and waits
// for the client's answer (a separate POST, delivered via h.pending).
func (h *MCPHTTPHandler) elicitConfirm(ctx context.Context, rw *mcpRPCWriter, sid, message string) (bool, error) {
	id := fmt.Sprintf("toolnexus-confirm-%d", h.seq.Add(1))
	key := sid + "\x00" + id
	ch := make(chan json.RawMessage, 1)
	h.mu.Lock()
	h.pending[key] = ch
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.pending, key)
		h.mu.Unlock()
	}()

	if message == "" {
		message = "Confirm this action?"
	}
	req := map[string]any{
		"jsonrpc": "2.0", "id": id, "method": "elicitation/create",
		"params": map[string]any{
			"message": message,
			"requestedSchema": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"confirm": map[string]any{"type": "boolean", "title": "Confirm", "description": message},
				},
			},
		},
	}
	if err := rw.event(req); err != nil {
		return false, err
	}

	tctx, cancel := context.WithTimeout(ctx, h.cfg.ConfirmTimeout)
	defer cancel()
	select {
	case raw := <-ch:
		var m mcpRPCMessage
		_ = json.Unmarshal(raw, &m)
		if len(m.Error) > 0 && string(m.Error) != "null" {
			return false, errors.New("client returned an error")
		}
		var r struct {
			Action  string         `json:"action"`
			Content map[string]any `json:"content"`
		}
		if err := json.Unmarshal(m.Result, &r); err != nil {
			return false, err
		}
		if r.Action != "accept" {
			return false, nil
		}
		if v, ok := r.Content["confirm"].(bool); ok && !v {
			return false, nil
		}
		return true, nil
	case <-tctx.Done():
		return false, tctx.Err()
	}
}

func (h *MCPHTTPHandler) deliver(sid string, msg mcpRPCMessage) {
	var id string
	if json.Unmarshal(msg.ID, &id) != nil {
		return
	}
	raw, _ := json.Marshal(msg)
	h.mu.Lock()
	ch := h.pending[sid+"\x00"+id]
	h.mu.Unlock()
	if ch != nil {
		select {
		case ch <- raw:
		default:
		}
	}
}

// ── wire helpers ──────────────────────────────────────────────────────────

// mcpRPCWriter answers one POST: plain JSON, unless a server→client request was
// sent first, in which case the response is upgraded to an SSE stream.
type mcpRPCWriter struct {
	w   http.ResponseWriter
	sse bool
}

func (rw *mcpRPCWriter) event(v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	if !rw.sse {
		rw.w.Header().Set("Content-Type", "text/event-stream")
		rw.w.Header().Set("Cache-Control", "no-cache")
		rw.w.WriteHeader(http.StatusOK)
		rw.sse = true
	}
	if _, err := fmt.Fprintf(rw.w, "event: message\ndata: %s\n\n", b); err != nil {
		return err
	}
	if fl, ok := rw.w.(http.Flusher); ok {
		fl.Flush()
	}
	return nil
}

func (rw *mcpRPCWriter) respond(id json.RawMessage, result any, rerr *rpcError) {
	msg := map[string]any{"jsonrpc": "2.0", "id": id}
	if rerr != nil {
		msg["error"] = rerr
	} else {
		msg["result"] = result
	}
	if rw.sse {
		_ = rw.event(msg)
		return
	}
	rw.w.Header().Set("Content-Type", "application/json")
	rw.w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(rw.w).Encode(msg)
}

func writeMcpRPCError(w http.ResponseWriter, id json.RawMessage, code int, message string) {
	if id == nil {
		id = json.RawMessage("null")
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusBadRequest)
	_ = json.NewEncoder(w).Encode(map[string]any{"jsonrpc": "2.0", "id": id, "error": rpcError{code, message}})
}

// mcpWireTool is the tools/list entry for t: name verbatim, description,
// inputSchema, and annotations (Tool.Annotations verbatim; else derived from
// Hints; else omitted).
func mcpWireTool(t Tool) map[string]any {
	schema := t.InputSchema
	if schema == nil {
		schema = JSONSchema{"type": "object"}
	}
	m := map[string]any{"name": t.Name, "description": t.Description, "inputSchema": schema}
	if t.OutputSchema != nil {
		m["outputSchema"] = t.OutputSchema
	}
	ann := map[string]any{}
	if a := t.Annotations; a != nil {
		if a.Title != "" {
			ann["title"] = a.Title
		}
		putBool(ann, "readOnlyHint", a.ReadOnlyHint)
		putBool(ann, "destructiveHint", a.DestructiveHint)
		putBool(ann, "idempotentHint", a.IdempotentHint)
		putBool(ann, "openWorldHint", a.OpenWorldHint)
	} else if hh := t.Hints; hh != nil {
		ann["readOnlyHint"] = hh.ReadOnly
		ann["destructiveHint"] = hh.Destructive
		ann["openWorldHint"] = hh.OpenWorld
	}
	if len(ann) > 0 {
		m["annotations"] = ann
	}
	return m
}

func putBool(m map[string]any, k string, v *bool) {
	if v != nil {
		m[k] = *v
	}
}

func omitEmpty(m map[string]any) map[string]any {
	for k, v := range m {
		if s, ok := v.(string); ok && s == "" {
			delete(m, k)
		}
	}
	return m
}

func mcpHasVersion(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}
