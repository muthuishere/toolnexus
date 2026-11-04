package toolnexus

// MCPHandler (SPEC §7C.1, ADR 0039): the embeddable MCP server, exercised with
// the mark3labs/mcp-go CLIENT over real streamable HTTP (httptest), plus raw
// HTTP checks for the protocol 2025-06-18 transport rules.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/client/transport"
	"github.com/mark3labs/mcp-go/mcp"
)

func hBoolp(b bool) *bool { return &b }

type hTestUser struct{ name string }

// hTestTool builds a tool that echoes the caller identity, counting executions.
func hTestTool(name string, ran *atomic.Int32) Tool {
	return Tool{
		Name: name, Description: name + " tool", Source: SourceNative,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{"id": map[string]any{"type": "string"}}},
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			if ran != nil {
				ran.Add(1)
			}
			u, _ := IdentityFrom(tctx.Ctx).(hTestUser)
			return ToolResult{Output: name + ":" + u.name}, nil
		},
	}
}

func newHandlerTK(t *testing.T) *Toolkit {
	t.Helper()
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(tk.Close)
	return tk
}

// bearerAuth maps "Bearer alice"/"Bearer bob" to identities.
func bearerAuth(r *http.Request) (any, error) {
	tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	switch tok {
	case "alice", "bob":
		return hTestUser{tok}, nil
	}
	return nil, errors.New("bad token")
}

type hElicit struct {
	action mcp.ElicitationResponseAction
	asked  atomic.Int32
}

func (e *hElicit) Elicit(_ context.Context, _ mcp.ElicitationRequest) (*mcp.ElicitationResult, error) {
	e.asked.Add(1)
	content := map[string]any{"confirm": e.action == mcp.ElicitationResponseActionAccept}
	return &mcp.ElicitationResult{ElicitationResponse: mcp.ElicitationResponse{Action: e.action, Content: content}}, nil
}

func hDial(t *testing.T, url, token string, opts ...client.ClientOption) *client.Client {
	t.Helper()
	tr, err := transport.NewStreamableHTTP(url, transport.WithHTTPHeaders(map[string]string{"Authorization": "Bearer " + token}))
	if err != nil {
		t.Fatal(err)
	}
	c := client.NewClient(tr, opts...)
	ctx := context.Background()
	if err := c.Start(ctx); err != nil {
		t.Fatal(err)
	}
	req := mcp.InitializeRequest{}
	req.Params.ProtocolVersion = "2025-06-18"
	req.Params.ClientInfo = mcp.Implementation{Name: "test", Version: "1"}
	if _, err := c.Initialize(ctx, req); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func hCall(t *testing.T, c *client.Client, name string, args map[string]any) (*mcp.CallToolResult, error) {
	t.Helper()
	req := mcp.CallToolRequest{}
	req.Params.Name = name
	req.Params.Arguments = args
	return c.CallTool(context.Background(), req)
}

func hText(r *mcp.CallToolResult) string {
	if r == nil || len(r.Content) == 0 {
		return ""
	}
	if tc, ok := r.Content[0].(mcp.TextContent); ok {
		return tc.Text
	}
	return ""
}

func hNames(t *testing.T, c *client.Client) []string {
	t.Helper()
	res, err := c.ListTools(context.Background(), mcp.ListToolsRequest{})
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, tl := range res.Tools {
		out = append(out, tl.Name)
	}
	return out
}

func TestMCPHandler_AuthRejectsWith401AndWWWAuthenticate(t *testing.T) {
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{
		Authenticate: bearerAuth,
		WWWAuthenticate: func(*http.Request, error) string {
			return `Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource"`
		},
	})
	srv := httptest.NewServer(h)
	defer srv.Close()
	resp, err := http.Post(srv.URL, "application/json", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 401 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if got := resp.Header.Get("WWW-Authenticate"); !strings.Contains(got, "resource_metadata=") {
		t.Fatalf("WWW-Authenticate = %q", got)
	}
}

func TestMCPHandler_PerIdentityToolsAndNotFoundNeverExecutes(t *testing.T) {
	var ranAdmin atomic.Int32
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{
		Authenticate: bearerAuth,
		ListTools: func(ctx context.Context) ([]Tool, error) {
			// runtime-synthesized: none of these are registered in the toolkit
			tools := []Tool{hTestTool("search", nil)}
			if IdentityFrom(ctx).(hTestUser).name == "alice" {
				tools = append(tools, hTestTool("admin_reset", &ranAdmin))
			}
			return tools, nil
		},
	})
	srv := httptest.NewServer(h)
	defer srv.Close()

	alice := hDial(t, srv.URL, "alice")
	bob := hDial(t, srv.URL, "bob")
	if got := strings.Join(hNames(t, alice), ","); got != "search,admin_reset" {
		t.Fatalf("alice tools = %s", got)
	}
	if got := strings.Join(hNames(t, bob), ","); got != "search" {
		t.Fatalf("bob tools = %s", got)
	}
	r, err := hCall(t, bob, "search", nil)
	if err != nil || hText(r) != "search:bob" {
		t.Fatalf("bob search = %v %v", hText(r), err)
	}
	if _, err := hCall(t, bob, "admin_reset", nil); err == nil || !strings.Contains(err.Error(), "tool not found") {
		t.Fatalf("bob admin_reset err = %v", err)
	}
	if ranAdmin.Load() != 0 {
		t.Fatal("admin_reset executed for bob")
	}
	if r, err := hCall(t, alice, "admin_reset", nil); err != nil || hText(r) != "admin_reset:alice" {
		t.Fatalf("alice admin_reset = %v %v", hText(r), err)
	}
}

func gatedHandler(t *testing.T, fallback *ToolResult) (*MCPHTTPHandler, *atomic.Int32) {
	var ran atomic.Int32
	del := hTestTool("delete_record", &ran)
	del.Annotations = &ToolAnnotations{Title: "Delete", DestructiveHint: hBoolp(true), ReadOnlyHint: hBoolp(false), IdempotentHint: hBoolp(true), OpenWorldHint: hBoolp(false)}
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{
		Authenticate: bearerAuth,
		ListTools:    func(context.Context) ([]Tool, error) { return []Tool{del, hTestTool("read", nil)}, nil },
		BeforeCall: func(ctx context.Context, tool Tool, args json.RawMessage) (MCPDecision, error) {
			if tool.Annotations != nil && *tool.Annotations.DestructiveHint {
				if bytes.Contains(args, []byte(`"nonce-ok"`)) {
					return MCPDecision{Kind: MCPAllow}, nil
				}
				return MCPDecision{Kind: MCPConfirm, Message: "Delete the record?", Result: fallback}, nil
			}
			if bytes.Contains(args, []byte(`"forbidden"`)) {
				return MCPDecision{Kind: MCPDeny, Message: "not allowed"}, nil
			}
			return MCPDecision{}, nil
		},
	})
	return h, &ran
}

func TestMCPHandler_ConfirmViaElicitation(t *testing.T) {
	h, ran := gatedHandler(t, nil)
	srv := httptest.NewServer(h)
	defer srv.Close()

	yes := &hElicit{action: mcp.ElicitationResponseActionAccept}
	c := hDial(t, srv.URL, "alice", client.WithElicitationHandler(yes))
	r, err := hCall(t, c, "delete_record", map[string]any{"id": "1"})
	if err != nil || r.IsError || hText(r) != "delete_record:alice" || yes.asked.Load() != 1 || ran.Load() != 1 {
		t.Fatalf("accept: %v %v asked=%d ran=%d", hText(r), err, yes.asked.Load(), ran.Load())
	}

	no := &hElicit{action: mcp.ElicitationResponseActionDecline}
	c2 := hDial(t, srv.URL, "alice", client.WithElicitationHandler(no))
	r, err = hCall(t, c2, "delete_record", map[string]any{"id": "1"})
	if err != nil || !r.IsError || hText(r) != "declined" || ran.Load() != 1 {
		t.Fatalf("decline: %v %v ran=%d", hText(r), err, ran.Load())
	}
}

func TestMCPHandler_ConfirmWithoutElicitation(t *testing.T) {
	// default: plain isError, tool not run
	h, ran := gatedHandler(t, nil)
	srv := httptest.NewServer(h)
	defer srv.Close()
	c := hDial(t, srv.URL, "alice")
	r, err := hCall(t, c, "delete_record", map[string]any{"id": "1"})
	if err != nil || !r.IsError || hText(r) != "confirmation required: Delete the record?" || ran.Load() != 0 {
		t.Fatalf("fallback: %v %v ran=%d", hText(r), err, ran.Load())
	}
	// host-shaped result (nonce re-call), then the re-call is allowed by the host
	h2, ran2 := gatedHandler(t, &ToolResult{Output: `re-call with {"nonce":"nonce-ok"}`})
	srv2 := httptest.NewServer(h2)
	defer srv2.Close()
	c2 := hDial(t, srv2.URL, "alice")
	r, _ = hCall(t, c2, "delete_record", map[string]any{"id": "1"})
	if r.IsError || !strings.Contains(hText(r), "nonce-ok") || ran2.Load() != 0 {
		t.Fatalf("shaped: %v", hText(r))
	}
	r, _ = hCall(t, c2, "delete_record", map[string]any{"id": "1", "nonce": "nonce-ok"})
	if hText(r) != "delete_record:alice" || ran2.Load() != 1 {
		t.Fatalf("re-call: %v", hText(r))
	}
}

func TestMCPHandler_DenyAndAnnotations(t *testing.T) {
	h, _ := gatedHandler(t, nil)
	srv := httptest.NewServer(h)
	defer srv.Close()
	c := hDial(t, srv.URL, "alice")
	r, _ := hCall(t, c, "read", map[string]any{"id": "forbidden"})
	if !r.IsError || hText(r) != "not allowed" {
		t.Fatalf("deny: %v", hText(r))
	}
	res, err := c.ListTools(context.Background(), mcp.ListToolsRequest{})
	if err != nil {
		t.Fatal(err)
	}
	a := res.Tools[0].Annotations
	if a.Title != "Delete" || !*a.DestructiveHint || *a.ReadOnlyHint || !*a.IdempotentHint || *a.OpenWorldHint {
		t.Fatalf("annotations: %+v", a)
	}
}

func TestMCPHandler_OnCallEvent(t *testing.T) {
	var mu sync.Mutex
	var evs []MCPCallEvent
	boom := Tool{Name: "boom", Source: SourceNative, Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
		time.Sleep(5 * time.Millisecond)
		return ToolResult{}, errors.New("kaput")
	}}
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{
		Authenticate: bearerAuth,
		ListTools:    func(context.Context) ([]Tool, error) { return []Tool{boom}, nil },
		OnCall:       func(ev MCPCallEvent) { mu.Lock(); evs = append(evs, ev); mu.Unlock(); panic("isolated") },
	})
	srv := httptest.NewServer(h)
	defer srv.Close()
	c := hDial(t, srv.URL, "bob")
	r, err := hCall(t, c, "boom", nil)
	if err != nil || !r.IsError || hText(r) != "kaput" {
		t.Fatalf("%v %v", hText(r), err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(evs) != 1 {
		t.Fatalf("events %d", len(evs))
	}
	ev := evs[0]
	if ev.Name != "boom" || !ev.IsError || ev.Err == nil || ev.ResultBytes == 0 || ev.Ms < 5 ||
		ev.Identity.(hTestUser).name != "bob" || ev.SessionID == "" || ev.Decision != MCPAllow {
		t.Fatalf("event %+v", ev)
	}
}

func TestMCPHandler_ListChangedNotification(t *testing.T) {
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{
		ListTools: func(context.Context) ([]Tool, error) { return []Tool{hTestTool("a", nil)}, nil },
	})
	srv := httptest.NewServer(h)
	defer srv.Close()
	tr, _ := transport.NewStreamableHTTP(srv.URL, transport.WithContinuousListening())
	c := client.NewClient(tr)
	got := make(chan string, 4)
	c.OnNotification(func(n mcp.JSONRPCNotification) { got <- n.Method })
	ctx := context.Background()
	if err := c.Start(ctx); err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	req := mcp.InitializeRequest{}
	req.Params.ProtocolVersion = "2025-06-18"
	if _, err := c.Initialize(ctx, req); err != nil {
		t.Fatal(err)
	}
	deadline := time.After(5 * time.Second)
	for {
		h.NotifyToolsChanged()
		select {
		case m := <-got:
			if m != "notifications/tools/list_changed" {
				t.Fatalf("got %s", m)
			}
			return
		case <-time.After(50 * time.Millisecond):
		case <-deadline:
			t.Fatal("no list_changed received")
		}
	}
}

func TestMCPHandler_ServerInfoResourcesPrompts(t *testing.T) {
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{
		Authenticate: bearerAuth,
		ServerInfo: func(ctx context.Context) MCPServerInfo {
			return MCPServerInfo{Name: "srv-" + RequestFrom(ctx).Host[:9] + "-" + IdentityFrom(ctx).(hTestUser).name, Version: "9.9.9", Instructions: "be careful"}
		},
		ListResources: func(context.Context) ([]MCPResource, error) {
			return []MCPResource{{URI: "docs://api", Name: "api", MimeType: "text/markdown"}}, nil
		},
		ReadResource: func(ctx context.Context, uri string) ([]MCPResourceContent, error) {
			return []MCPResourceContent{{URI: uri, MimeType: "text/markdown", Text: "# API for " + IdentityFrom(ctx).(hTestUser).name}}, nil
		},
		ListPrompts: func(context.Context) ([]MCPPrompt, error) {
			return []MCPPrompt{{Name: "greet", Arguments: []MCPPromptArgument{{Name: "who", Required: true}}}}, nil
		},
		GetPrompt: func(_ context.Context, name string, args map[string]string) (MCPPromptResult, error) {
			return MCPPromptResult{Messages: []MCPPromptMessage{{Role: "user", Text: "hello " + args["who"]}}}, nil
		},
	})
	srv := httptest.NewServer(h)
	defer srv.Close()

	tr, _ := transport.NewStreamableHTTP(srv.URL, transport.WithHTTPHeaders(map[string]string{"Authorization": "Bearer alice"}))
	c := client.NewClient(tr)
	ctx := context.Background()
	_ = c.Start(ctx)
	defer c.Close()
	req := mcp.InitializeRequest{}
	req.Params.ProtocolVersion = "2025-06-18"
	init, err := c.Initialize(ctx, req)
	if err != nil {
		t.Fatal(err)
	}
	if init.ServerInfo.Name != "srv-127.0.0.1-alice" || init.ServerInfo.Version != "9.9.9" || init.Instructions != "be careful" ||
		init.ProtocolVersion != "2025-06-18" || init.Capabilities.Resources == nil || init.Capabilities.Prompts == nil {
		t.Fatalf("init %+v", init)
	}
	rl, err := c.ListResources(ctx, mcp.ListResourcesRequest{})
	if err != nil || len(rl.Resources) != 1 || rl.Resources[0].URI != "docs://api" {
		t.Fatalf("resources %+v %v", rl, err)
	}
	rr := mcp.ReadResourceRequest{}
	rr.Params.URI = "docs://api"
	rd, err := c.ReadResource(ctx, rr)
	if err != nil || rd.Contents[0].(mcp.TextResourceContents).Text != "# API for alice" {
		t.Fatalf("read %+v %v", rd, err)
	}
	pg := mcp.GetPromptRequest{}
	pg.Params.Name = "greet"
	pg.Params.Arguments = map[string]string{"who": "world"}
	pr, err := c.GetPrompt(ctx, pg)
	if err != nil || pr.Messages[0].Content.(mcp.TextContent).Text != "hello world" {
		t.Fatalf("prompt %+v %v", pr, err)
	}
	pl, err := c.ListPrompts(ctx, mcp.ListPromptsRequest{})
	if err != nil || pl.Prompts[0].Arguments[0].Name != "who" || !pl.Prompts[0].Arguments[0].Required {
		t.Fatalf("prompts %+v %v", pl, err)
	}
}

// ── raw protocol checks (streamable HTTP, 2025-06-18) ─────────────────────

func hRawPost(t *testing.T, url, sid, body string, hdr map[string]string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("POST", url, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	if sid != "" {
		req.Header.Set("Mcp-Session-Id", sid)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func hReadAll(r *http.Response) string { b, _ := io.ReadAll(r.Body); r.Body.Close(); return string(b) }

func TestMCPHandler_ProtocolConformance(t *testing.T) {
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{ListTools: func(context.Context) ([]Tool, error) { return nil, nil }})
	srv := httptest.NewServer(h)
	defer srv.Close()

	// initialize negotiates 2025-06-18 and issues a session id
	resp := hRawPost(t, srv.URL, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}`, nil)
	sid := resp.Header.Get("Mcp-Session-Id")
	body := hReadAll(resp)
	if resp.StatusCode != 200 || sid == "" || !strings.Contains(body, `"protocolVersion":"2025-06-18"`) || !strings.Contains(body, `"listChanged":true`) {
		t.Fatalf("initialize %d %q %s", resp.StatusCode, sid, body)
	}
	// unknown version ⇒ our latest
	resp = hRawPost(t, srv.URL, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}`, nil)
	if b := hReadAll(resp); !strings.Contains(b, `"protocolVersion":"2025-06-18"`) {
		t.Fatalf("negotiate %s", b)
	}
	v := map[string]string{"MCP-Protocol-Version": "2025-06-18"}
	// notification ⇒ 202
	if resp = hRawPost(t, srv.URL, sid, `{"jsonrpc":"2.0","method":"notifications/initialized"}`, v); resp.StatusCode != 202 {
		t.Fatalf("notification %d", resp.StatusCode)
	}
	resp.Body.Close()
	// missing session ⇒ 400, unknown session ⇒ 404
	if resp = hRawPost(t, srv.URL, "", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, v); resp.StatusCode != 400 {
		t.Fatalf("no session %d", resp.StatusCode)
	}
	resp.Body.Close()
	if resp = hRawPost(t, srv.URL, "nope", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, v); resp.StatusCode != 404 {
		t.Fatalf("bad session %d", resp.StatusCode)
	}
	resp.Body.Close()
	// unsupported protocol header ⇒ 400
	if resp = hRawPost(t, srv.URL, sid, `{"jsonrpc":"2.0","id":2,"method":"ping"}`, map[string]string{"MCP-Protocol-Version": "2099-01-01"}); resp.StatusCode != 400 {
		t.Fatalf("bad version %d", resp.StatusCode)
	}
	resp.Body.Close()
	// batch rejected (removed in 2025-06-18)
	if b := hReadAll(hRawPost(t, srv.URL, sid, `[{"jsonrpc":"2.0","id":3,"method":"ping"}]`, v)); !strings.Contains(b, "-32600") {
		t.Fatalf("batch %s", b)
	}
	// ping / unknown method / resources not advertised
	if b := hReadAll(hRawPost(t, srv.URL, sid, `{"jsonrpc":"2.0","id":4,"method":"ping"}`, v)); !strings.Contains(b, `"result":{}`) {
		t.Fatalf("ping %s", b)
	}
	if b := hReadAll(hRawPost(t, srv.URL, sid, `{"jsonrpc":"2.0","id":5,"method":"resources/list"}`, v)); !strings.Contains(b, "-32601") {
		t.Fatalf("resources absent %s", b)
	}
	// wrong content type ⇒ 415; GET without SSE accept ⇒ 405; PUT ⇒ 405
	req, _ := http.NewRequest("POST", srv.URL, strings.NewReader("{}"))
	req.Header.Set("Content-Type", "text/plain")
	if r, _ := http.DefaultClient.Do(req); r.StatusCode != 415 {
		t.Fatalf("content type %d", r.StatusCode)
	}
	if r, _ := http.Get(srv.URL); r.StatusCode != 405 {
		t.Fatalf("GET %d", r.StatusCode)
	}
	// DELETE ends the session; it is then 404
	req, _ = http.NewRequest("DELETE", srv.URL, nil)
	req.Header.Set("Mcp-Session-Id", sid)
	if r, _ := http.DefaultClient.Do(req); r.StatusCode != 200 {
		t.Fatalf("DELETE %d", r.StatusCode)
	}
	if resp = hRawPost(t, srv.URL, sid, `{"jsonrpc":"2.0","id":6,"method":"ping"}`, v); resp.StatusCode != 404 {
		t.Fatalf("after delete %d", resp.StatusCode)
	}
	resp.Body.Close()
}

type hTestIDs struct{ gen, val, term atomic.Int32 }

func (s *hTestIDs) Generate(context.Context) (string, error) {
	s.gen.Add(1)
	return "host-session-1", nil
}
func (s *hTestIDs) Validate(_ context.Context, id string) (bool, error) {
	s.val.Add(1)
	return id == "host-session-1", nil
}
func (s *hTestIDs) Terminate(context.Context, string) error { s.term.Add(1); return nil }

func TestMCPHandler_CustomSessionIDsAndStateless(t *testing.T) {
	ids := &hTestIDs{}
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{SessionIDs: ids, ListTools: func(context.Context) ([]Tool, error) { return []Tool{hTestTool("a", nil)}, nil }})
	srv := httptest.NewServer(h)
	defer srv.Close()
	c := hDial(t, srv.URL, "")
	if r, err := hCall(t, c, "a", nil); err != nil || hText(r) != "a:" {
		t.Fatalf("%v %v", hText(r), err)
	}
	if ids.gen.Load() != 1 || ids.val.Load() == 0 {
		t.Fatalf("gen=%d val=%d", ids.gen.Load(), ids.val.Load())
	}

	st := newHandlerTK(t).MCPHandler(MCPHandlerConfig{Stateless: true, ListTools: func(context.Context) ([]Tool, error) { return []Tool{hTestTool("a", nil)}, nil }})
	srv2 := httptest.NewServer(st)
	defer srv2.Close()
	resp := hRawPost(t, srv2.URL, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`, nil)
	if resp.Header.Get("Mcp-Session-Id") != "" {
		t.Fatal("stateless issued a session id")
	}
	resp.Body.Close()
	if b := hReadAll(hRawPost(t, srv2.URL, "", `{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"a"}}`, nil)); !strings.Contains(b, `a:`) {
		t.Fatalf("stateless call %s", b)
	}
}

func TestMCPHandler_DefaultsToToolkitTools(t *testing.T) {
	tk := newHandlerTK(t)
	tk.Register(hTestTool("registered", nil))
	srv := httptest.NewServer(tk.MCPHandler(MCPHandlerConfig{}))
	defer srv.Close()
	c := hDial(t, srv.URL, "")
	if got := hNames(t, c); len(got) == 0 || !strings.Contains(strings.Join(got, ","), "registered") {
		t.Fatalf("tools %v", got)
	}
}

// ── PR #140 review follow-ups ─────────────────────────────────────────────

func TestMCPHandler_StructuredContentAndOutputSchema(t *testing.T) {
	st := Tool{
		Name: "stats", Source: SourceNative,
		InputSchema:  JSONSchema{"type": "object"},
		OutputSchema: JSONSchema{"type": "object", "properties": map[string]any{"count": map[string]any{"type": "integer"}}},
		Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
			return ToolResult{Structured: map[string]any{"count": 3}}, nil
		},
	}
	plain := hTestTool("plain", nil)
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{ListTools: func(context.Context) ([]Tool, error) { return []Tool{st, plain}, nil }})
	srv := httptest.NewServer(h)
	defer srv.Close()
	c := hDial(t, srv.URL, "")
	lt, err := c.ListTools(context.Background(), mcp.ListToolsRequest{})
	if err != nil || lt.Tools[0].OutputSchema.Type != "object" {
		t.Fatalf("outputSchema %+v %v", lt.Tools[0], err)
	}
	r, err := hCall(t, c, "stats", nil)
	if err != nil {
		t.Fatal(err)
	}
	sc, _ := json.Marshal(r.StructuredContent)
	if string(sc) != `{"count":3}` || hText(r) != `{"count":3}` {
		t.Fatalf("structured %s text %q", sc, hText(r))
	}
	// absent ⇒ no structuredContent / outputSchema on the wire
	raw, _ := json.Marshal(toolResultToMcp(ToolResult{Output: "x"}))
	if strings.Contains(string(raw), "structuredContent") || strings.Contains(string(raw), "_meta") {
		t.Fatalf("absent leaked: %s", raw)
	}
	if _, ok := mcpWireTool(plain)["outputSchema"]; ok {
		t.Fatal("outputSchema emitted when unset")
	}
}

func TestMCPHandler_MetaPassthrough(t *testing.T) {
	var seen map[string]any
	var seenHook map[string]any
	tool := Tool{Name: "m", Source: SourceNative, Execute: func(_ map[string]any, tc *ToolContext) (ToolResult, error) {
		seen = MCPRequestMetaFrom(tc.Ctx)
		return ToolResult{Output: "ok", Meta: map[string]any{"usage": map[string]any{"units": 2}}}, nil
	}}
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{
		Stateless: true,
		ListTools: func(context.Context) ([]Tool, error) { return []Tool{tool}, nil },
		BeforeCall: func(ctx context.Context, _ Tool, _ json.RawMessage) (MCPDecision, error) {
			seenHook = MCPRequestMetaFrom(ctx)
			return MCPDecision{}, nil
		},
	})
	srv := httptest.NewServer(h)
	defer srv.Close()
	b := hReadAll(hRawPost(t, srv.URL, "", `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"m","_meta":{"clientCaps":{"elicitation":{}}}}}`, nil))
	if !strings.Contains(b, `"_meta":{"usage":{"units":2}}`) {
		t.Fatalf("result _meta: %s", b)
	}
	if _, ok := seen["clientCaps"]; !ok {
		t.Fatalf("tool meta %v", seen)
	}
	if _, ok := seenHook["clientCaps"]; !ok {
		t.Fatalf("hook meta %v", seenHook)
	}
}

func TestMCPHandler_ProtocolVersionsPerHandler(t *testing.T) {
	initBody := func(v string) string {
		return `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"` + v + `"}}`
	}
	def := httptest.NewServer(newHandlerTK(t).MCPHandler(MCPHandlerConfig{Stateless: true}))
	defer def.Close()
	newer := httptest.NewServer(newHandlerTK(t).MCPHandler(MCPHandlerConfig{
		Stateless:        true,
		ProtocolVersions: []string{"2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26"},
		ListTools:        func(context.Context) ([]Tool, error) { return []Tool{hTestTool("a", nil)}, nil },
	}))
	defer newer.Close()

	// default handler: 2025-06-18 only
	if b := hReadAll(hRawPost(t, def.URL, "", initBody("2025-11-25"), nil)); !strings.Contains(b, `"protocolVersion":"2025-06-18"`) {
		t.Fatalf("default negotiate %s", b)
	}
	if r := hRawPost(t, def.URL, "", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, map[string]string{"MCP-Protocol-Version": "2025-11-25"}); r.StatusCode != 400 {
		t.Fatalf("default header %d", r.StatusCode)
	}
	// configured handler: 2025-11-25 accepted; unimplemented 2026-07-28 / 2025-03-26 dropped
	if b := hReadAll(hRawPost(t, newer.URL, "", initBody("2025-11-25"), nil)); !strings.Contains(b, `"protocolVersion":"2025-11-25"`) {
		t.Fatalf("newer negotiate %s", b)
	}
	if b := hReadAll(hRawPost(t, newer.URL, "", initBody("2026-07-28"), nil)); !strings.Contains(b, `"protocolVersion":"2025-11-25"`) {
		t.Fatalf("2026-07-28 must fall back to newest implemented: %s", b)
	}
	if r := hRawPost(t, newer.URL, "", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, map[string]string{"MCP-Protocol-Version": "2025-03-26"}); r.StatusCode != 400 {
		t.Fatalf("2025-03-26 header %d", r.StatusCode)
	}
	// 2025-11-25 rule: invalid arguments ⇒ isError result, not -32602
	b := hReadAll(hRawPost(t, newer.URL, "", `{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"a","arguments":[1]}}`, map[string]string{"MCP-Protocol-Version": "2025-11-25"}))
	if !strings.Contains(b, `"isError":true`) || strings.Contains(b, "-32602") {
		t.Fatalf("11-25 validation %s", b)
	}
	b = hReadAll(hRawPost(t, newer.URL, "", `{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"a","arguments":[1]}}`, map[string]string{"MCP-Protocol-Version": "2025-06-18"}))
	if !strings.Contains(b, "-32602") {
		t.Fatalf("06-18 validation %s", b)
	}
}

func TestMCPHandler_StatelessSessionIDAndTitle(t *testing.T) {
	h := newHandlerTK(t).MCPHandler(MCPHandlerConfig{Stateless: true, StatelessSessionID: "stateless", Title: "Records API"})
	srv := httptest.NewServer(h)
	defer srv.Close()
	r := hRawPost(t, srv.URL, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`, nil)
	b := hReadAll(r)
	if r.Header.Get("Mcp-Session-Id") != "stateless" || !strings.Contains(b, `"title":"Records API"`) {
		t.Fatalf("sid %q body %s", r.Header.Get("Mcp-Session-Id"), b)
	}
	// never validated: any or no id works
	if b := hReadAll(hRawPost(t, srv.URL, "whatever", `{"jsonrpc":"2.0","id":2,"method":"ping"}`, nil)); !strings.Contains(b, `"result":{}`) {
		t.Fatalf("ping %s", b)
	}
	// title omitted when unset
	h2 := httptest.NewServer(newHandlerTK(t).MCPHandler(MCPHandlerConfig{Stateless: true}))
	defer h2.Close()
	if b := hReadAll(hRawPost(t, h2.URL, "", `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}`, nil)); strings.Contains(b, `"title"`) {
		t.Fatalf("title leaked %s", b)
	}
}

func TestServe_StructuredResultViaMcpServer(t *testing.T) {
	// The §7C Serve path maps Structured/Meta/OutputSchema too, and stays
	// byte-identical when they are absent.
	tl := mcpToolFor(Tool{Name: "x", InputSchema: JSONSchema{"type": "object"}})
	if b, _ := json.Marshal(tl); strings.Contains(string(b), "outputSchema") {
		t.Fatalf("outputSchema leaked %s", b)
	}
	tl = mcpToolFor(Tool{Name: "x", InputSchema: JSONSchema{"type": "object"}, OutputSchema: JSONSchema{"type": "object"}})
	if b, _ := json.Marshal(tl); !strings.Contains(string(b), `"outputSchema":{"type":"object"}`) {
		t.Fatalf("outputSchema missing %s", b)
	}
	b, _ := json.Marshal(toolResultToMcp(ToolResult{Output: "hi", Structured: map[string]any{"a": 1}, Meta: map[string]any{"k": "v"}}))
	if !strings.Contains(string(b), `"structuredContent":{"a":1}`) || !strings.Contains(string(b), `"_meta":{"k":"v"}`) || !strings.Contains(string(b), `"text":"hi"`) {
		t.Fatalf("wire %s", b)
	}
}
