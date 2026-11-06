package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/server"
)

func loadOne(t *testing.T, cfg map[string]any, opts McpLoadOptions) (*McpSource, McpServerResult) {
	t.Helper()
	var got McpServerResult
	opts.OnServer = func(r McpServerResult) { got = r }
	src, err := LoadMcpWithOptions(context.Background(), map[string]any{"fx": cfg}, opts)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(src.Close)
	return src, got
}

func toolNamed(t *testing.T, src *McpSource, name string) Tool {
	t.Helper()
	for _, tl := range src.Tools {
		if tl.Name == name {
			return tl
		}
	}
	t.Fatalf("no tool %s in %v", name, src.Status)
	return Tool{}
}

func run(t *testing.T, tl Tool, args map[string]any, tctx *ToolContext) ToolResult {
	t.Helper()
	r, err := tl.Execute(args, tctx)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestMcp26HTTPEchoAndHeaders(t *testing.T) {
	fx := newFxServer(t)
	u := fx.start(t)
	src, _ := loadOne(t, map[string]any{"url": u}, McpLoadOptions{})
	if src.Status["fx"] != StatusConnected {
		t.Fatalf("status %v", src.Status)
	}
	r := run(t, toolNamed(t, src, "fx_echo"), map[string]any{"text": "hi toolnexus"}, nil)
	if r.IsError || r.Output != `{"text":"hi toolnexus"}` || r.Metadata["server"] != "fx" {
		t.Fatalf("echo: %+v", r)
	}
	h, _ := fx.headers.Load("tools/call")
	hd := h.(http.Header)
	if hd.Get("MCP-Protocol-Version") != "2026-07-28" || hd.Get("Mcp-Method") != "tools/call" || hd.Get("Mcp-Name") != "echo" {
		t.Fatalf("headers %v", hd)
	}
	if _, ok := fx.headers.Load("initialize"); ok {
		t.Fatal("initialize must never be sent on the 2026 path")
	}
	if encodeMcpHeader("héllo") != "=?base64?aMOpbGxv?=" || encodeMcpHeader(" x") != "=?base64?IHg=?=" || encodeMcpHeader("ok") != "ok" {
		t.Fatal("Mcp-Name sentinel encoding")
	}
}

func TestMcp26MRTRWaitFor(t *testing.T) {
	fx := newFxServer(t)
	asked := 0
	wf := func(r Request) (Answer, error) {
		asked++
		if r.Kind != "input" || r.Prompt != "What is your name?" || r.Data["schema"] == nil || !strings.HasPrefix(r.ID, "mrtr-") {
			t.Errorf("request %+v", r)
		}
		return Answer{ID: r.ID, Ok: true, Data: map[string]any{"name": "toolnexus"}}, nil
	}
	src, _ := loadOne(t, map[string]any{"url": fx.start(t)}, McpLoadOptions{WaitFor: wf})
	r := run(t, toolNamed(t, src, "fx_greet"), nil, nil)
	if r.Output != "Hello, toolnexus!" || asked != 1 || fx.count("greet") != 1 {
		t.Fatalf("greet %+v asked=%d execs=%d", r, asked, fx.count("greet"))
	}
}

func TestMcp26MRTRPendingResume(t *testing.T) {
	fx := newFxServer(t)
	src, _ := loadOne(t, map[string]any{"url": fx.start(t)}, McpLoadOptions{})
	greet := toolNamed(t, src, "fx_greet")
	r := run(t, greet, nil, nil)
	req := PendingOf(r)
	if req == nil || req.Kind != "input" || !strings.HasPrefix(req.ID, "mrtr-") {
		t.Fatalf("want pending, got %+v", r)
	}
	r = run(t, greet, nil, &ToolContext{Answer: &Answer{ID: req.ID, Ok: true, Data: map[string]any{"name": "toolnexus"}}})
	if r.Output != "Hello, toolnexus!" || fx.count("greet") != 1 {
		t.Fatalf("resume %+v", r)
	}
}

func TestMcp26Stdio(t *testing.T) {
	src, _ := loadOne(t, map[string]any{"command": []any{os.Args[0]}, "env": map[string]any{"TN_STDIO_MCP26": "1"}},
		McpLoadOptions{WaitFor: func(r Request) (Answer, error) {
			return Answer{ID: r.ID, Ok: true, Data: map[string]any{"name": "stdio"}}, nil
		}})
	if src.Status["fx"] != StatusConnected {
		t.Fatalf("status %v", src.Status)
	}
	echo := toolNamed(t, src, "fx_echo")
	var wg sync.WaitGroup // concurrent calls demultiplexed by id
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			r, _ := echo.Execute(map[string]any{"text": fmt.Sprint(i)}, nil)
			if r.Output != fmt.Sprintf(`{"text":"%d"}`, i) {
				t.Errorf("stdio echo %d: %+v", i, r)
			}
		}(i)
	}
	wg.Wait()
	if r := run(t, toolNamed(t, src, "fx_greet"), nil, nil); r.Output != "Hello, stdio!" {
		t.Fatalf("stdio greet %+v", r)
	}
}

func TestMcp26FallbackToOldServer(t *testing.T) {
	srv := buildMcpServer([]Tool{mkTool("a")}, nil, nil)
	plain := httptest.NewServer(server.NewStreamableHTTPServer(srv, server.WithStateLess(true)))
	defer plain.Close()
	// A server that answers unknown methods with 500 (Java SDK) also falls back.
	five := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Mcp-Method") == "server/discover" {
			w.WriteHeader(500)
			return
		}
		server.NewStreamableHTTPServer(srv, server.WithStateLess(true)).ServeHTTP(w, r)
	}))
	defer five.Close()
	for _, u := range []string{plain.URL + "/mcp", five.URL + "/mcp"} {
		src, _ := loadOne(t, map[string]any{"url": u}, McpLoadOptions{})
		if src.Status["fx"] != StatusConnected || len(src.Tools) != 1 || src.Tools[0].Name != "fx_a" {
			t.Fatalf("fallback %s: %v %d", u, src.Status, len(src.Tools))
		}
	}
	// Connection refused stays a load failure.
	dead := httptest.NewServer(http.NotFoundHandler())
	du := dead.URL
	dead.Close()
	src, res := loadOne(t, map[string]any{"url": du + "/mcp"}, McpLoadOptions{})
	if src.Status["fx"] != StatusFailed || res.Err == nil {
		t.Fatalf("refused: %v", src.Status)
	}
}

func TestMcp26NoHiddenRetry(t *testing.T) {
	fx := newFxServer(t)
	fx.failAfterRun = 502
	src, _ := loadOne(t, map[string]any{"url": fx.start(t)}, McpLoadOptions{})
	r := run(t, toolNamed(t, src, "fx_echo"), map[string]any{"text": "x"}, nil)
	if !r.IsError || fx.count("echo") != 1 {
		t.Fatalf("want one execution + caller error: %+v execs=%d", r, fx.count("echo"))
	}
}

func TestMcp26ToolSearch(t *testing.T) {
	fx := newFxServer(t)
	u := fx.start(t)
	src, _ := loadOne(t, map[string]any{"url": u, "toolSearch": true, "description": "the fixture"}, McpLoadOptions{})
	if len(src.Tools) != 2 || src.Tools[0].Name != "fx_search_tools" || src.Tools[1].Name != "fx_call_tool" {
		t.Fatalf("tools %v", src.Tools)
	}
	search := src.Tools[0]
	if search.Description != "Search the tools of the fixture by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool." {
		t.Fatal(search.Description)
	}
	r := run(t, search, map[string]any{"query": "echo text"}, nil)
	if !strings.HasPrefix(r.Output, `[{"name":"echo","description":"Echo the given text back.","inputSchema":{"properties":{"text":{"type":"string"}},"required":["text"],"type":"object"},"server":"the fixture"}`) {
		t.Fatalf("search %s", r.Output)
	}
	if r := run(t, search, map[string]any{"query": "zzz"}, nil); r.Output != "[]" {
		t.Fatalf("no match %s", r.Output)
	}
	call := src.Tools[1]
	if r := run(t, call, map[string]any{"name": "echo", "arguments": map[string]any{"text": "via"}}, nil); r.Output != `{"text":"via"}` {
		t.Fatalf("call_tool %+v", r)
	}
	if r := run(t, call, map[string]any{"name": "nope"}, nil); !r.IsError || r.Output != `unknown tool "nope" on fx` {
		t.Fatalf("unknown %+v", r)
	}
	// Filters apply before search: a dropped or disabled tool is unknown.
	src2, _ := loadOne(t, map[string]any{"url": u, "toolSearch": true, "tools": map[string]any{"echo": false}},
		McpLoadOptions{DisableTools: []string{"fx_whoami"}})
	for _, n := range []string{"echo", "whoami"} {
		if r := run(t, src2.Tools[1], map[string]any{"name": n}, nil); r.Output != fmt.Sprintf("unknown tool %q on fx", n) {
			t.Fatalf("filtered %s: %+v", n, r)
		}
	}
	if r := run(t, src2.Tools[0], map[string]any{"query": "echo"}, nil); r.Output != "[]" {
		t.Fatalf("filtered search %s", r.Output)
	}
}

func TestMcp26SkillsJoinInventory(t *testing.T) {
	fx := newFxServer(t)
	tk, err := CreateToolkit(context.Background(), Options{McpConfig: map[string]any{"mcpServers": map[string]any{"fx": map[string]any{"url": fx.start(t)}}}})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	skill, ok := tk.Get("skill")
	if !ok {
		t.Fatal("no skill tool")
	}
	r, _ := skill.Execute(map[string]any{"name": "hello-world"}, nil)
	local, _ := os.ReadFile("../examples/skills/hello-world/SKILL.md")
	_, body, _, _ := parseFrontmatter(string(local))
	if r.IsError || !strings.Contains(r.Output, strings.TrimSpace(body)) {
		t.Fatalf("remote skill body: %s", r.Output)
	}
}

func TestMcp26SkillURIRejected(t *testing.T) {
	raw, _ := os.ReadFile("../examples/mcp-2026/expected.json")
	var exp struct {
		SkillURIRejected []string `json:"skillUriRejected"`
	}
	_ = json.Unmarshal(raw, &exp)
	if len(exp.SkillURIRejected) == 0 {
		t.Fatal("fixture")
	}
	for _, u := range exp.SkillURIRejected {
		if _, _, err := parseSkillURI(u); err == nil {
			t.Errorf("accepted %q", u)
		}
	}
	if n, rel, err := parseSkillURI("skill://hello-world/SKILL.md"); err != nil || n != "hello-world" || rel != "SKILL.md" {
		t.Fatal("valid URI rejected")
	}
}

// ---- OAuth ----

func protected(t *testing.T) (*fxServer, *devAS, string) {
	as := startDevAS(t)
	fx := newFxServer(t)
	fx.as = as
	fx.scopes = map[string][]string{"*": {"mcp:read"}, "greet": {"mcp:greet"}}
	return fx, as, fx.start(t)
}

func authCfg(u string, auth map[string]any) map[string]any {
	auth["allowHttp"] = true
	return map[string]any{"url": u, "auth": auth}
}

func TestMcp26OAuthAuto(t *testing.T) {
	for _, form := range []bool{false, true} {
		fx, as, u := protected(t)
		as.formReply = form
		store := &MemoryTokenStore{}
		src, res := loadOne(t, authCfg(u, map[string]any{"clientId": "dev-client"}), McpLoadOptions{OpenURL: follow, TokenStore: store})
		if src.Status["fx"] != StatusConnected {
			t.Fatalf("form=%v: %v", form, res.Err)
		}
		if r := run(t, toolNamed(t, src, "fx_whoami"), nil, nil); r.Output != "dev-user" {
			t.Fatalf("whoami %+v", r)
		}
		q := as.lastAuthorizeQuery
		if q.Get("resource") != u || q.Get("code_challenge_method") != "S256" || q.Get("scope") != "mcp:read" {
			t.Fatalf("authorize query %v", q)
		}
		tok, _ := store.Load(u)
		if s := fmt.Sprintf("%v %+v %#v %s", *tok, *tok, *tok, tok.String()); strings.Contains(s, tok.AccessToken) || strings.Contains(s, tok.RefreshToken) {
			t.Fatal("token value in string form")
		}
		_ = fx
	}
}

func TestMcp26OAuthPauseWaitForAndStepUpPending(t *testing.T) {
	fx, as, u := protected(t)
	cfg := authCfg(u, map[string]any{"clientId": "dev-client", "mode": "pause", "redirectUri": "https://app.example/cb"})
	// Load without waitFor: pause at load ⇒ failed "authorization pending".
	src, res := loadOne(t, cfg, McpLoadOptions{})
	if src.Status["fx"] != StatusFailed || res.Err == nil || res.Err.Error() != "authorization pending" {
		t.Fatalf("load pause: %v", res.Err)
	}
	// With waitFor: the host answers Answer.data.callbackUrl.
	wf := func(r Request) (Answer, error) {
		if r.Kind != "authorization" || !strings.HasPrefix(r.ID, "auth-") || r.Prompt != "Authorize access to "+u || r.ExpiresAt == "" {
			t.Errorf("auth request %+v", r)
		}
		cb, err := visit(r.URL)
		return Answer{ID: r.ID, Ok: true, Data: map[string]any{"callbackUrl": cb}}, err
	}
	src, res = loadOne(t, cfg, McpLoadOptions{WaitFor: wf})
	if src.Status["fx"] != StatusConnected {
		t.Fatalf("pause+waitFor: %v", res.Err)
	}
	// Seeded mcp:read token, no waitFor: greet needs step-up ⇒ pending auth-,
	// resume ⇒ same round sent once ⇒ MRTR pending ⇒ resume ⇒ runs once.
	store := &MemoryTokenStore{}
	at, rt := as.mint("dev-client", u, "mcp:read", time.Hour)
	store.Save(u, &Token{AccessToken: at, RefreshToken: rt, Issuer: as.issuer, ClientID: "dev-client", Resource: u, Scopes: []string{"mcp:read"}, ExpiresAt: time.Now().Add(time.Hour)})
	src, res = loadOne(t, cfg, McpLoadOptions{TokenStore: store})
	if src.Status["fx"] != StatusConnected {
		t.Fatalf("seeded: %v", res.Err)
	}
	greet := toolNamed(t, src, "fx_greet")
	r := run(t, greet, nil, nil)
	p := PendingOf(r)
	if p == nil || p.Kind != "authorization" || !strings.HasPrefix(p.ID, "auth-") {
		t.Fatalf("want auth pending, got %+v", r)
	}
	cb, _ := visit(p.URL)
	if as.lastAuthorizeQuery.Get("scope") != "mcp:read mcp:greet" {
		t.Fatalf("step-up scope %q", as.lastAuthorizeQuery.Get("scope"))
	}
	r = run(t, greet, nil, &ToolContext{Answer: &Answer{ID: p.ID, Ok: true, Data: map[string]any{"callbackUrl": cb}}})
	p2 := PendingOf(r)
	if p2 == nil || p2.Kind != "input" {
		t.Fatalf("want input pending, got %+v", r)
	}
	r = run(t, greet, nil, &ToolContext{Answer: &Answer{ID: p2.ID, Ok: true, Data: map[string]any{"name": "t"}}})
	if r.Output != "Hello, t!" || fx.count("greet") != 1 {
		t.Fatalf("final %+v", r)
	}
	// Unknown id.
	r = run(t, greet, nil, &ToolContext{Answer: &Answer{ID: "auth-nope", Ok: true}})
	if r.Output != `unknown pending authorization "auth-nope"` {
		t.Fatal(r.Output)
	}
}

func TestMcp26OAuthErrors(t *testing.T) {
	cases := []struct {
		name string
		knob func(*devAS)
		auth map[string]any
		want string
	}{
		{"iss mismatch", func(a *devAS) { a.issOverride = "https://evil.example" }, map[string]any{"clientId": "c"}, "authorization response iss mismatch (possible mix-up attack)"},
		{"no S256", func(a *devAS) { a.noS256 = true }, map[string]any{"clientId": "c"}, "authorization server does not advertise PKCE S256; refusing"},
		{"no client identity", func(a *devAS) { a.noCIMD = true }, map[string]any{}, "register an app with"},
	}
	for _, c := range cases {
		_, as, u := protected(t)
		c.knob(as)
		store := &MemoryTokenStore{}
		src, res := loadOne(t, authCfg(u, c.auth), McpLoadOptions{OpenURL: follow, TokenStore: store})
		if src.Status["fx"] != StatusFailed || res.Err == nil || !strings.Contains(res.Err.Error(), c.want) {
			t.Fatalf("%s: %v", c.name, res.Err)
		}
		if _, ok := store.Load(u); ok {
			t.Fatalf("%s: token stored", c.name)
		}
		if c.name == "no client identity" && !strings.HasSuffix(res.Err.Error(), "register an app with "+as.issuer+" and set auth.clientId (dynamic client registration is not supported)") {
			t.Fatal(res.Err)
		}
	}
	// state mismatch (pause, host returns a tampered callback)
	_, _, u := protected(t)
	wf := func(r Request) (Answer, error) {
		cb, _ := visit(r.URL)
		cb = strings.Replace(cb, "state=", "state=x", 1)
		return Answer{ID: r.ID, Ok: true, Data: map[string]any{"callbackUrl": cb}}, nil
	}
	_, res := loadOne(t, authCfg(u, map[string]any{"clientId": "c", "mode": "pause", "redirectUri": "https://app.example/cb"}), McpLoadOptions{WaitFor: wf})
	if res.Err == nil || !strings.Contains(res.Err.Error(), "authorization response state mismatch") {
		t.Fatalf("state: %v", res.Err)
	}
	// denied
	_, res = loadOne(t, authCfg(u, map[string]any{"clientId": "c", "mode": "pause", "redirectUri": "https://app.example/cb"}),
		McpLoadOptions{WaitFor: func(r Request) (Answer, error) { return Answer{ID: r.ID, Ok: false}, nil }})
	if res.Err == nil || !strings.Contains(res.Err.Error(), "authorization denied: cancelled") {
		t.Fatalf("denied: %v", res.Err)
	}
}

func TestMcp26OAuthRefreshSingleFlight(t *testing.T) {
	for _, omit := range []bool{false, true} {
		fx, as, u := protected(t)
		as.rejectRefreshResource = omit
		store := &MemoryTokenStore{}
		at, rt := as.mint("dev-client", u, "mcp:read", time.Hour)
		store.Save(u, &Token{AccessToken: at, RefreshToken: rt, Issuer: as.issuer, ClientID: "dev-client", Resource: u,
			Scopes: []string{"mcp:read"}, ExpiresAt: time.Now().Add(-time.Minute)})
		auth := map[string]any{"clientId": "dev-client"}
		if omit {
			auth["omitRefreshResource"] = true
		}
		src, res := loadOne(t, authCfg(u, auth), McpLoadOptions{TokenStore: store, OpenURL: func(string) error { return errors.New("no browser") }})
		if src.Status["fx"] != StatusConnected {
			t.Fatalf("omit=%v: %v", omit, res.Err)
		}
		echo := toolNamed(t, src, "fx_echo")
		var wg sync.WaitGroup
		for i := 0; i < 100; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				if r, _ := echo.Execute(map[string]any{"text": "x"}, nil); r.IsError {
					t.Errorf("call: %s", r.Output)
				}
			}()
		}
		wg.Wait()
		authz, refreshes := as.stats()
		if refreshes != 1 || authz != 0 || fx.count("echo") != 100 {
			t.Fatalf("omit=%v: refreshes=%d authorizations=%d", omit, refreshes, authz)
		}
	}
}

func TestMcp26SearchKeepsSchemaOrderAndSSE(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID     int    `json:"id"`
			Method string `json:"method"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		res := `{"supportedVersions":["2026-07-28"]}`
		if req.Method == "tools/list" {
			res = `{"tools":[{"name":"zeta_find","description":"Find zeta.","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}}}]}`
		}
		w.Header().Set("Content-Type", "text/event-stream") // SSE reply
		fmt.Fprintf(w, "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":%s}\n\n", req.ID, res)
	}))
	defer ts.Close()
	src, _ := loadOne(t, map[string]any{"url": ts.URL, "toolSearch": true}, McpLoadOptions{})
	r := run(t, src.Tools[0], map[string]any{"query": "zeta"}, nil)
	if r.Output != `[{"name":"zeta_find","description":"Find zeta.","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},"server":""}]` {
		t.Fatal(r.Output)
	}
	if !strings.Contains(src.Tools[0].Description, "Search the tools of fx by") {
		t.Fatal(src.Tools[0].Description)
	}
}
