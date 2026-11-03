package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Client is a stateless MCP 2026-07-28 Streamable HTTP client.
type Client struct {
	URL     string
	Version string
	Info    obj
	Caps    obj
	HTTP    *http.Client
	Auth    *OAuth // nil = no authorization (HTTP only; stdio servers take credentials from their env)
	// ToolSearch: Tools() exposes search_tools + call_tool instead of every server tool (see toolsearch.go).
	ToolSearch  bool
	Description string // what this server is for; shown in search_tools results
	stdio       *stdioConn
	toolCache   []obj
	toolExpiry  time.Time
	toolMu      sync.Mutex
	nextID      atomic.Int64
	// headerOverride lets the self-test forge headers (negative cases only).
	headerOverride map[string]string
}

func NewClient(url string) *Client {
	return &Client{URL: url, Version: ProtocolVersion, HTTP: defaultHTTP(),
		Info: obj{"name": "toolnexus-spike-go", "version": "0.0.1"},
		Caps: obj{"elicitation": obj{"form": obj{}}}}
}

// rpcMsg is one JSON-RPC response.
type rpcMsg struct {
	Result obj       `json:"result"`
	Error  *rpcError `json:"error"`
}

// Call sends one request; params gets the per-request _meta merged in.
//
// Retry rule (spec issue #3394): a request is re-sent ONLY after a 401, or a 403 insufficient_scope,
// carrying WWW-Authenticate — the server rejected it before dispatch, so the tool never ran. A lost
// connection, timeout, 5xx or unreadable response is returned as an error and NEVER retried: the
// server may already have executed the call.
func (c *Client) Call(method string, params obj) (obj, error) {
	if params == nil {
		params = obj{}
	}
	params["_meta"] = obj{MetaVersion: c.Version, MetaClientCaps: c.Caps, MetaClientInfo: c.Info}
	id := c.nextID.Add(1)
	body, _ := json.Marshal(obj{"jsonrpc": "2.0", "id": id, "method": method, "params": params})
	var msg rpcMsg
	var err error
	if c.stdio != nil {
		err = c.stdio.roundTrip(id, body, &msg)
	} else {
		err = c.httpRoundTrip(method, params, body, &msg)
	}
	if err != nil {
		return nil, err
	}
	if msg.Error != nil {
		return nil, msg.Error
	}
	if msg.Result == nil { // hostile/broken server: no result and no error
		return nil, errors.New("response has neither result nor error")
	}
	if _, ok := msg.Result["resultType"]; !ok {
		msg.Result["resultType"] = "complete" // earlier-protocol servers
	}
	return msg.Result, nil
}

func (c *Client) httpRoundTrip(method string, params obj, body []byte, msg *rpcMsg) error {
	ctx := context.Background()
	var resp *http.Response
	for attempt := 0; ; attempt++ {
		var err error
		sent := ""
		if c.Auth != nil {
			sent = c.Auth.bearer(ctx, c.URL)
		}
		if resp, err = c.post(ctx, method, params, body, sent); err != nil {
			return err // never retried: the server may have executed it
		}
		www := resp.Header.Get("WWW-Authenticate")
		if c.Auth == nil || (resp.StatusCode != 401 && resp.StatusCode != 403) || www == "" {
			break
		}
		resp.Body.Close()
		if attempt >= 3 { // spec: Step-Up retry limit
			return fmt.Errorf("authorization failed: HTTP %d after %d attempts", resp.StatusCode, attempt+1)
		}
		if err := c.Auth.challenge(ctx, c.URL, resp.StatusCode, www, sent, func() (obj, error) { return c.Call(method, params) }); err != nil {
			return err
		}
	}
	defer resp.Body.Close()
	if strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		// Take the last data: payload that carries a result or error (the final response).
		sc := bufio.NewScanner(resp.Body)
		sc.Buffer(make([]byte, 1<<20), 16<<20)
		for sc.Scan() {
			if d, ok := strings.CutPrefix(sc.Text(), "data:"); ok {
				var m map[string]json.RawMessage
				if json.Unmarshal([]byte(d), &m) == nil && (m["result"] != nil || m["error"] != nil) {
					_ = json.Unmarshal([]byte(d), msg)
				}
			}
		}
	} else if err := json.NewDecoder(resp.Body).Decode(msg); err != nil {
		return fmt.Errorf("HTTP %d: %w", resp.StatusCode, err)
	}
	if msg.Result == nil && msg.Error == nil {
		return fmt.Errorf("HTTP %d: response has neither result nor error", resp.StatusCode)
	}
	return nil
}

func encodeHeader(v string) string {
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

// Responder answers one MRTR input request (stands in for toolnexus §10 waitFor).
type Responder func(key string, req obj) (obj, error)

// CallTool runs tools/call, looping over input_required rounds via respond.
func (c *Client) CallTool(name string, args obj, respond Responder) (obj, error) {
	return c.callTool(name, args, respond, nil, nil, 0)
}

func (c *Client) callTool(name string, args obj, respond Responder, responses obj, state any, round int) (obj, error) {
	for ; round < 8; round++ {
		p := obj{"name": name, "arguments": args}
		if responses != nil {
			p["inputResponses"] = responses
		}
		if state != nil {
			p["requestState"] = state
		}
		res, err := c.Call("tools/call", p)
		if pe := (*PendingError)(nil); errors.As(err, &pe) {
			// The paused request got 401/403 before dispatch: the server has NOT run this round.
			// Resume sends exactly this round (same inputResponses/requestState) for the first time —
			// earlier rounds are not replayed and the user is not re-asked.
			resp, st, r := responses, state, round
			c.Auth.setRetry(pe.Pending.ID, func() (obj, error) { return c.callTool(name, args, respond, resp, st, r) })
		}
		if err != nil || res["resultType"] != "input_required" {
			return res, err
		}
		state = res["requestState"]
		responses = obj{}
		reqs, _ := res["inputRequests"].(map[string]any)
		if respond == nil {
			return nil, fmt.Errorf("tools/call %s: server asked for input but no Responder was given", name)
		}
		for k, v := range reqs {
			r, ok := v.(map[string]any)
			if !ok {
				return nil, fmt.Errorf("tools/call %s: malformed input request %q", name, k)
			}
			ans, err := respond(k, r)
			if err != nil {
				return nil, err
			}
			responses[k] = ans
		}
	}
	return nil, fmt.Errorf("tools/call %s: too many input_required rounds", name)
}

func (c *Client) post(ctx context.Context, method string, params obj, body []byte, bearer string) (*http.Response, error) {
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, c.URL, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	req.Header.Set("MCP-Protocol-Version", c.Version)
	req.Header.Set("Mcp-Method", method)
	switch method {
	case "tools/call", "prompts/get":
		n, _ := params["name"].(string)
		req.Header.Set("Mcp-Name", encodeHeader(n))
	case "resources/read":
		u, _ := params["uri"].(string)
		req.Header.Set("Mcp-Name", encodeHeader(u))
	}
	for k, v := range c.headerOverride {
		req.Header.Set(k, v)
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	return c.HTTP.Do(req)
}

// DefaultTimeout bounds every request made through defaultHTTP (a hung server cannot hang the caller).
const DefaultTimeout = 30 * time.Second

// devTransport is http.DefaultTransport plus trust for any DevAuthServer started in this process,
// so `ServerAuth{Issuer: as.Issuer}` and a zero-config OAuth client need no HTTP wiring in dev.
var devTransport atomic.Pointer[http.Transport]

type devAwareRT struct{}

func (devAwareRT) RoundTrip(r *http.Request) (*http.Response, error) {
	if t := devTransport.Load(); t != nil {
		return t.RoundTrip(r)
	}
	return http.DefaultTransport.RoundTrip(r)
}

// CloseIdleConnections lets sharedHTTP.CloseIdleConnections reach the real transport.
func (devAwareRT) CloseIdleConnections() {
	if t := devTransport.Load(); t != nil {
		t.CloseIdleConnections()
	}
	http.DefaultTransport.(*http.Transport).CloseIdleConnections()
}

var sharedHTTP = &http.Client{Timeout: DefaultTimeout, Transport: devAwareRT{}}

func defaultHTTP() *http.Client { return sharedHTTP }

var devTrustMu sync.Mutex

func trustDevCert(cert *x509.Certificate) {
	devTrustMu.Lock()
	defer devTrustMu.Unlock()
	pool, err := x509.SystemCertPool()
	if err != nil || pool == nil {
		pool = x509.NewCertPool()
	}
	if cur := devTransport.Load(); cur != nil && cur.TLSClientConfig != nil && cur.TLSClientConfig.RootCAs != nil {
		pool = cur.TLSClientConfig.RootCAs.Clone()
	}
	pool.AddCert(cert)
	t := http.DefaultTransport.(*http.Transport).Clone()
	t.TLSClientConfig = &tls.Config{RootCAs: pool}
	t.MaxIdleConnsPerHost = 256
	devTransport.Store(t)
}
