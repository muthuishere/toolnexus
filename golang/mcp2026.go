// MCP 2026-07-28 client (SPEC.md §2.1–§2.6). toolnexus owns this layer — no
// MCP SDK: stateless JSON-RPC over Streamable HTTP (JSON or SSE replies) and
// line-delimited stdio, `server/discover` first. A server that does not answer
// discover the 2026 way falls back to the frozen SDK path in mcp.go (§2.2a).

package toolnexus

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Wire constants (§2.1, byte-exact).
const (
	// McpProtocolVersion is the MCP 2026-07-28 wire version.
	//
	// Deprecated: use MCPProtocol2026, the same value under the canonical name.
	McpProtocolVersion = MCPProtocol2026
	mcpMetaVersion     = "io.modelcontextprotocol/protocolVersion"
	mcpMetaClientCaps  = "io.modelcontextprotocol/clientCapabilities"
	mcpMetaClientInfo  = "io.modelcontextprotocol/clientInfo"
	mcpMetaServerInfo  = "io.modelcontextprotocol/serverInfo"
	mcpSkillsExt       = "io.modelcontextprotocol/skills"

	mcpCodeMethodNotFound      = -32601
	mcpCodeInvalidParams       = -32602
	mcpCodeHeaderMismatch      = -32020
	mcpCodeUnsupportedProtocol = -32022
)

// McpRPCError is a JSON-RPC error response from an MCP server.
type McpRPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    any    `json:"data,omitempty"`
}

func (e *McpRPCError) Error() string { return e.Message }

// mcpHTTPStatusError is a non-2xx HTTP reply that carried no JSON-RPC response.
type mcpHTTPStatusError struct{ Status int }

func (e *mcpHTTPStatusError) Error() string { return "HTTP " + strconv.Itoa(e.Status) }

// errMcpNotRPC: a 2xx reply that is not a JSON-RPC response for the request.
var errMcpNotRPC = errors.New("response is not a JSON-RPC response")

// errMcpStdioNoReply: a stdio probe got no reply in time (old servers may ignore it).
var errMcpStdioNoReply = errors.New("stdio server did not reply to server/discover")

type jsonObj = map[string]any

type rpcReply struct {
	Result jsonObj      `json:"result"`
	Error  *McpRPCError `json:"error"`
	raw    json.RawMessage
}

// UnmarshalJSON keeps the raw result bytes so key order can be preserved
// where it is re-emitted (tool search hits echo inputSchema as sent).
func (r *rpcReply) UnmarshalJSON(b []byte) error {
	var w struct {
		Result json.RawMessage `json:"result"`
		Error  *McpRPCError    `json:"error"`
	}
	if err := json.Unmarshal(b, &w); err != nil {
		return err
	}
	r.Error, r.raw, r.Result = w.Error, nil, nil
	if len(w.Result) > 0 && string(w.Result) != "null" {
		if err := json.Unmarshal(w.Result, &r.Result); err != nil {
			return err
		}
		r.raw = w.Result
	}
	return nil
}

// mcp26Client is one connection to a 2026 server (HTTP or stdio).
type mcp26Client struct {
	url     string
	headers map[string]string // raw config headers; ${ENV} expanded per request, never logged
	http    *http.Client
	auth    *mcpOAuth // nil for stdio
	stdio   *mcp26Stdio
	nextID  atomic.Int64
	// headerOverride forges headers (tests of server-side header checks only).
	headerOverride map[string]string
}

var mcp26HTTP = &http.Client{} // deadlines come from the per-request context

func mcpClientMeta() jsonObj {
	return jsonObj{
		mcpMetaVersion:    MCPProtocol2026,
		mcpMetaClientCaps: jsonObj{"elicitation": jsonObj{"form": jsonObj{}}},
		mcpMetaClientInfo: jsonObj{"name": "toolnexus", "version": "0.1.0"},
	}
}

// call sends one request. Never re-sent after a lost connection, timeout, 5xx
// or unreadable reply (§2.6) — only after a pre-dispatch 401 / 403
// insufficient_scope carrying WWW-Authenticate (handled in httpRoundTrip).
func (c *mcp26Client) call(ctx context.Context, method string, params jsonObj) (jsonObj, error) {
	r, _, err := c.callRaw(ctx, method, params)
	return r, err
}

func (c *mcp26Client) callRaw(ctx context.Context, method string, params jsonObj) (jsonObj, json.RawMessage, error) {
	p := jsonObj{}
	for k, v := range params {
		p[k] = v
	}
	p["_meta"] = mcpClientMeta()
	id := c.nextID.Add(1)
	body, err := json.Marshal(jsonObj{"jsonrpc": "2.0", "id": id, "method": method, "params": p})
	if err != nil {
		return nil, nil, err
	}
	var msg rpcReply
	if c.stdio != nil {
		err = c.stdio.roundTrip(ctx, id, body, &msg)
	} else {
		err = c.httpRoundTrip(ctx, method, p, body, &msg)
	}
	if err != nil {
		return nil, nil, err
	}
	if msg.Error != nil {
		return nil, nil, msg.Error
	}
	if msg.Result == nil {
		return nil, nil, errMcpNotRPC
	}
	if _, ok := msg.Result["resultType"]; !ok {
		msg.Result["resultType"] = "complete"
	}
	return msg.Result, msg.raw, nil
}

func (c *mcp26Client) httpRoundTrip(ctx context.Context, method string, params jsonObj, body []byte, msg *rpcReply) error {
	var resp *http.Response
	for attempt := 0; ; attempt++ {
		sent := ""
		if c.auth != nil {
			sent = c.auth.bearer(ctx, c.url)
		}
		var err error
		if resp, err = c.post(ctx, method, params, body, sent); err != nil {
			return err // never retried: the server may have executed it
		}
		www := resp.Header.Get("WWW-Authenticate")
		if c.auth == nil || (resp.StatusCode != 401 && resp.StatusCode != 403) || www == "" {
			break
		}
		resp.Body.Close()
		if attempt >= 3 {
			return fmt.Errorf("authorization failed: HTTP %d after %d attempts", resp.StatusCode, attempt+1)
		}
		if err := c.auth.challenge(ctx, c.url, resp.StatusCode, www, sent); err != nil {
			return err
		}
	}
	defer resp.Body.Close()
	if strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		sc := bufio.NewScanner(resp.Body)
		sc.Buffer(make([]byte, 64<<10), 16<<20)
		for sc.Scan() {
			if d, ok := strings.CutPrefix(sc.Text(), "data:"); ok {
				var m map[string]json.RawMessage
				if json.Unmarshal([]byte(d), &m) == nil && (m["result"] != nil || m["error"] != nil) {
					*msg = rpcReply{}
					_ = json.Unmarshal([]byte(d), msg)
				}
			}
		}
		if err := sc.Err(); err != nil {
			return err
		}
	} else {
		raw, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
		if err != nil {
			return err
		}
		_ = json.Unmarshal(raw, msg)
	}
	if msg.Error != nil {
		return nil // a JSON-RPC error is the answer, whatever the status
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return &mcpHTTPStatusError{Status: resp.StatusCode}
	}
	if msg.Result == nil {
		return errMcpNotRPC
	}
	return nil
}

func (c *mcp26Client) post(ctx context.Context, method string, params jsonObj, body []byte, bearer string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	for k, v := range ExpandEnvHeaders(c.headers) {
		req.Header.Set(k, v)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	req.Header.Set("MCP-Protocol-Version", MCPProtocol2026)
	req.Header.Set("Mcp-Method", method)
	switch method {
	case "tools/call", "prompts/get":
		n, _ := params["name"].(string)
		req.Header.Set("Mcp-Name", encodeMcpHeader(n))
	case "resources/read":
		u, _ := params["uri"].(string)
		req.Header.Set("Mcp-Name", encodeMcpHeader(u))
	}
	for k, v := range c.headerOverride {
		req.Header.Set(k, v)
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	hc := c.http
	if hc == nil {
		hc = mcp26HTTP
	}
	return hc.Do(req)
}

// encodeMcpHeader sends a value as-is when it is printable ASCII without
// surrounding whitespace and does not look like the sentinel; else base64 (§2.3).
func encodeMcpHeader(v string) string {
	safe := v == strings.TrimSpace(v) && !(strings.HasPrefix(v, "=?base64?") && strings.HasSuffix(v, "?="))
	for _, r := range v {
		if r < 0x20 || r > 0x7e {
			safe = false
		}
	}
	if safe {
		return v
	}
	return "=?base64?" + base64.StdEncoding.EncodeToString([]byte(v)) + "?="
}

func (c *mcp26Client) close() {
	if c.stdio != nil {
		c.stdio.close()
	}
}

// ---------------------------------------------------------------------------
// stdio: one JSON-RPC message per line; responses demultiplexed by id; stderr
// passed through, never parsed; a dead child fails every pending call.
// ---------------------------------------------------------------------------

type mcp26Stdio struct {
	cmd     *exec.Cmd
	in      io.WriteCloser
	wmu     sync.Mutex
	mu      sync.Mutex
	pending map[string]chan []byte
	err     error
	done    chan struct{}
	once    sync.Once

	// Probe handoff (§2.2a): the reader parks after the first response (the
	// discover reply) so the frozen SDK path can take over the same child.
	out    io.Reader
	br     *bufio.Reader
	parked chan struct{} // closed once the reader parked after the first reply
	resume chan bool     // true ⇒ keep reading (2026 path); false ⇒ handed off
}

func startMcp26Stdio(cfg ServerConfig) (*mcp26Stdio, error) {
	cmd := exec.Command(cfg.Command[0], cfg.Command[1:]...)
	envMap := cfg.Environment
	if envMap == nil {
		envMap = cfg.Env
	}
	cmd.Env = append([]string{}, os.Environ()...)
	for k, v := range envMap {
		cmd.Env = append(cmd.Env, k+"="+v)
	}
	cmd.Dir = cfg.Cwd
	cmd.Stderr = os.Stderr
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("failed to start stdio server: %w", err)
	}
	s := &mcp26Stdio{cmd: cmd, in: in, pending: map[string]chan []byte{}, done: make(chan struct{}),
		out: out, br: bufio.NewReaderSize(out, 64<<10), parked: make(chan struct{}), resume: make(chan bool, 1)}
	go s.read()
	return s, nil
}

// read demultiplexes responses by id. After delivering the FIRST response (the
// server/discover reply) it parks before issuing another read: either the 2026
// path resumes it, or handoff() gives the unread stream to the frozen SDK path,
// so no goroutine is left blocked in Read to steal the initialize reply.
func (s *mcp26Stdio) read() {
	first := true
	var rerr error
	for {
		line, err := s.br.ReadBytes('\n')
		line = bytes.TrimRight(line, "\r\n")
		if len(line) > 0 {
			var m struct {
				ID     json.RawMessage `json:"id"`
				Method string          `json:"method"`
			}
			if json.Unmarshal(line, &m) == nil && m.Method == "" && len(m.ID) > 0 {
				s.mu.Lock()
				ch := s.pending[string(m.ID)]
				delete(s.pending, string(m.ID))
				s.mu.Unlock()
				if ch != nil {
					ch <- line
				}
				if first && err == nil {
					first = false
					close(s.parked)
					if !<-s.resume {
						return // handed off: the SDK transport owns stdout now
					}
				}
			}
		}
		if err != nil {
			if err != io.EOF {
				rerr = err
			}
			break
		}
	}
	s.mu.Lock()
	s.err = errors.New("stdio server closed its stdout")
	if rerr != nil {
		s.err = fmt.Errorf("stdio read: %w", rerr)
	}
	s.mu.Unlock()
	close(s.done)
}

// keepReading un-parks the reader for the 2026 path (no-op if never parked).
func (s *mcp26Stdio) keepReading() {
	select {
	case <-s.parked:
		s.resume <- true
	default:
		go func() {
			select {
			case <-s.parked:
				s.resume <- true
			case <-s.done:
			}
		}()
	}
}

// handoff (§2.2a) stops the in-house reader after a discover REPLY and returns
// the child's stdout (any bytes already buffered first) plus a stdin whose
// Close also reaps the process. ok=false ⇒ the reader never parked (no reply).
func (s *mcp26Stdio) handoff() (r io.Reader, w io.WriteCloser, ok bool) {
	select {
	case <-s.parked:
	case <-s.done:
		return nil, nil, false
	case <-time.After(time.Second):
		return nil, nil, false
	}
	s.resume <- false
	rest, _ := s.br.Peek(s.br.Buffered())
	r = io.MultiReader(bytes.NewReader(append([]byte(nil), rest...)), s.out)
	return r, &mcp26HandoffStdin{s: s}, true
}

// mcp26HandoffStdin is the SDK's stdin after handoff; Close closes stdin, then
// waits for the child to exit (kill after 2s), so the SDK Close still reaps it.
type mcp26HandoffStdin struct{ s *mcp26Stdio }

func (h *mcp26HandoffStdin) Write(p []byte) (int, error) { return h.s.in.Write(p) }
func (h *mcp26HandoffStdin) Close() error {
	h.s.once.Do(func() {
		h.s.in.Close()
		ch := make(chan struct{})
		go func() { _ = h.s.cmd.Wait(); close(ch) }()
		select {
		case <-ch:
		case <-time.After(2 * time.Second):
			_ = h.s.cmd.Process.Kill()
			<-ch
		}
	})
	return nil
}

func (s *mcp26Stdio) roundTrip(ctx context.Context, id int64, body []byte, msg *rpcReply) error {
	key := strconv.FormatInt(id, 10)
	ch := make(chan []byte, 1)
	s.mu.Lock()
	if s.err != nil {
		s.mu.Unlock()
		return s.err
	}
	s.pending[key] = ch
	s.mu.Unlock()
	defer func() { s.mu.Lock(); delete(s.pending, key); s.mu.Unlock() }()
	s.wmu.Lock()
	_, err := s.in.Write(append(body, '\n')) // json.Marshal never emits a raw newline
	s.wmu.Unlock()
	if err != nil {
		return err
	}
	select {
	case b := <-ch:
		if err := json.Unmarshal(b, msg); err != nil {
			return err
		}
		if msg.Result == nil && msg.Error == nil {
			return errMcpNotRPC
		}
		return nil
	case <-s.done:
		s.mu.Lock()
		defer s.mu.Unlock()
		return s.err
	case <-ctx.Done():
		return ctx.Err()
	}
}

// close stops the child: close stdin, then kill after 2s.
func (s *mcp26Stdio) close() {
	s.once.Do(func() {
		s.in.Close()
		select {
		case <-s.done:
		case <-time.After(2 * time.Second):
			_ = s.cmd.Process.Kill()
		}
		_ = s.cmd.Wait()
	})
}

func (s *mcp26Stdio) kill() {
	s.once.Do(func() {
		_ = s.cmd.Process.Kill()
		s.in.Close()
		_ = s.cmd.Wait()
	})
}

// ---------------------------------------------------------------------------
// Discover + fallback classification (§2.2, §2.2a).
// ---------------------------------------------------------------------------

type mcp26Discovery struct {
	serverInfo jsonObj
	skills     bool
}

// discoverFallback reports whether a server/discover failure means "frozen
// fallback" (true) rather than a load failure (false).
func discoverFallback(err error) bool {
	var re *McpRPCError
	if errors.As(err, &re) {
		return true
	}
	var he *mcpHTTPStatusError
	if errors.As(err, &he) {
		return he.Status == 400 || he.Status == 404 || he.Status == 405 || he.Status >= 500
	}
	return errors.Is(err, errMcpNotRPC) || errors.Is(err, errMcpStdioNoReply)
}

// discover sends server/discover. ok=false with err=nil ⇒ the result lacks 2026-07-28.
func (c *mcp26Client) discover(ctx context.Context) (*mcp26Discovery, error) {
	res, err := c.call(ctx, "server/discover", nil)
	if err != nil {
		return nil, err
	}
	versions, _ := res["supportedVersions"].([]any)
	if !slices.Contains(versions, any(MCPProtocol2026)) {
		return nil, errMcpNotRPC // treated as fallback
	}
	d := &mcp26Discovery{}
	if si, ok := res["serverInfo"].(map[string]any); ok {
		d.serverInfo = si
	} else if meta, ok := res["_meta"].(map[string]any); ok {
		d.serverInfo, _ = meta[mcpMetaServerInfo].(map[string]any)
	}
	if caps, ok := res["capabilities"].(map[string]any); ok {
		if ext, ok := caps["extensions"].(map[string]any); ok {
			_, d.skills = ext[mcpSkillsExt]
		}
	}
	return d, nil
}
