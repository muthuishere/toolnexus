package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type env struct {
	as      *DevAuthServer
	mcp     *httptest.Server
	server  *Server
	url     string // canonical resource URI
	cimdURL string
	hc      *http.Client // trusts the shared httptest cert, follows redirects
}

// newEnv starts a dev AS + protected MCP server; cfg runs on the server before it starts serving.
func newEnv(t *testing.T, cfg ...func(*Server)) *env {
	t.Helper()
	as := StartDevAuthServer()
	t.Cleanup(as.Close)
	e := &env{as: as, hc: as.Client()}
	var cimd *httptest.Server
	cimd = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(obj{"client_id": cimd.URL + "/client.json", "client_name": "toolnexus spike",
			"redirect_uris": []string{"http://127.0.0.1/callback"}, "grant_types": []string{"authorization_code", "refresh_token"},
			"response_types": []string{"code"}, "token_endpoint_auth_method": "none"})
	}))
	t.Cleanup(cimd.Close)
	e.cimdURL = cimd.URL + "/client.json"
	e.server = mustServer()
	e.server.Auth = &ServerAuth{Issuer: as.Issuer, HTTP: e.hc,
		Scopes: map[string][]string{"*": {"mcp:read"}, "greet": {"mcp:greet"}}}
	e.server.Tool("whoami", "who is calling", func(ctx context.Context, _ obj) (any, error) {
		p, ok := PrincipalFrom(ctx)
		if !ok {
			return nil, errors.New("no principal")
		}
		return p.Subject + " " + strings.Join(p.Scopes, ","), nil
	})
	for _, f := range cfg {
		f(e.server)
	}
	e.mcp = httptest.NewTLSServer(e.server)
	t.Cleanup(e.mcp.Close)
	e.url = e.mcp.URL + "/mcp"
	return e
}

// follow is a test "browser": GET the auth URL, follow redirects (auto mode lands on the loopback listener).
func (e *env) follow(u string) error {
	resp, err := e.hc.Get(u)
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

// visit returns the AS redirect Location without following it (pause mode: the host "receives" the callback).
func (e *env) visit(t *testing.T, u string) string {
	t.Helper()
	c := *e.hc
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := c.Get(u)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 302 {
		t.Fatalf("authorize: HTTP %d", resp.StatusCode)
	}
	return resp.Header.Get("Location")
}

func (e *env) autoClient() *Client {
	c := NewClient(e.url)
	c.HTTP = e.hc
	c.Auth = &OAuth{HTTP: e.hc, ClientMetadataURL: e.cimdURL, Authorizer: LoopbackAuthorizer{Open: e.follow}}
	return c
}

func (e *env) pauseClient() *Client {
	e.as.Clients["spike-prereg"] = []string{"http://127.0.0.1:9/callback"}
	c := NewClient(e.url)
	c.HTTP = e.hc
	c.Auth = &OAuth{HTTP: e.hc, ClientIDs: map[string]string{e.as.Issuer: "spike-prereg"},
		Authorizer: PauseAuthorizer{Redirect: "http://127.0.0.1:9/callback"}}
	return c
}

func pending(t *testing.T, err error) Pending {
	t.Helper()
	var pe *PendingError
	if !errors.As(err, &pe) {
		t.Fatalf("want *PendingError, got %v", err)
	}
	return pe.Pending
}

func TestExistingStepsUnprotected(t *testing.T) {
	srv := httptest.NewServer(mustServer())
	defer srv.Close()
	if !runChecks(srv.URL + "/mcp") {
		t.Fatal("selftest failed")
	}
}

func TestExistingStepsProtectedAutoMode(t *testing.T) {
	e := newEnv(t)
	if !runChecks(e.url, func(c *Client) { *c = *e.autoClient() }) {
		t.Fatal("protected selftest failed")
	}
}

func TestPauseResume(t *testing.T) {
	e := newEnv(t)
	c := e.pauseClient()
	_, err := c.CallTool("echo", obj{"text": "after login"}, nil)
	p := pending(t, err)
	if p.Kind != "authorization" || p.ID == "" || !strings.HasPrefix(p.URL, e.as.Issuer+"/authorize?") || p.Prompt == "" {
		t.Fatalf("bad pending %+v", p)
	}
	cb := e.visit(t, p.URL)
	q := e.as.LastAuthorizeQuery
	if q.Get("resource") != e.url || q.Get("code_challenge_method") != "S256" || q.Get("scope") != "mcp:read" {
		t.Fatalf("authorize query: resource=%q method=%q scope=%q", q.Get("resource"), q.Get("code_challenge_method"), q.Get("scope"))
	}
	r, err := c.Auth.Resume(context.Background(), p.ID, cb)
	if err != nil || Text(r) != "after login" {
		t.Fatalf("resume: %v %v", r, err)
	}
	if _, err := c.Auth.Resume(context.Background(), p.ID, cb); err == nil {
		t.Fatal("second resume of the same ID must fail")
	}
	if r, err := c.CallTool("echo", obj{"text": "again"}, nil); err != nil || Text(r) != "again" {
		t.Fatalf("follow-up call should reuse token: %v %v", r, err)
	}
	if e.as.Authorizations != 1 {
		t.Fatalf("authorizations=%d", e.as.Authorizations)
	}
}

func TestAutoModeWhoamiPrincipal(t *testing.T) {
	e := newEnv(t)
	r, err := e.autoClient().CallTool("whoami", obj{}, nil)
	if err != nil || Text(r) != "dev-user mcp:read" {
		t.Fatalf("%v %v", r, err)
	}
}

func TestIssMismatchRejected(t *testing.T) {
	e := newEnv(t)
	e.as.IssOverride = "https://evil.example"
	c := e.autoClient()
	_, err := c.CallTool("echo", obj{"text": "x"}, nil)
	if err == nil || !strings.Contains(err.Error(), "iss mismatch") {
		t.Fatalf("want iss mismatch, got %v", err)
	}
	if _, ok := c.Auth.store().Load(e.url); ok {
		t.Fatal("no token may be stored")
	}
}

func TestStateMismatchRejected(t *testing.T) {
	e := newEnv(t)
	c := e.pauseClient()
	_, err := c.CallTool("echo", obj{"text": "x"}, nil)
	p := pending(t, err)
	cb := e.visit(t, p.URL)
	i := strings.Index(cb, "state=")
	cb = cb[:i] + "state=forged&" + cb[i+6:]
	if _, err := c.Auth.Resume(context.Background(), p.ID, cb); err == nil || !strings.Contains(err.Error(), "state mismatch") {
		t.Fatalf("want state mismatch, got %v", err)
	}
}

func TestPKCEVerifiedByAS(t *testing.T) {
	e := newEnv(t)
	c := e.pauseClient()
	_, err := c.CallTool("echo", obj{"text": "x"}, nil)
	p := pending(t, err)
	cb := e.visit(t, p.URL)
	c.Auth.flows[p.ID].verifier = "not-the-verifier" // the AS must reject the exchange
	if _, err := c.Auth.Resume(context.Background(), p.ID, cb); err == nil || !strings.Contains(err.Error(), "invalid_grant") {
		t.Fatalf("want invalid_grant, got %v", err)
	}
}

func TestRefreshOnExpiry(t *testing.T) {
	e := newEnv(t)
	c := e.autoClient()
	if _, err := c.CallTool("echo", obj{"text": "1"}, nil); err != nil {
		t.Fatal(err)
	}
	tok, _ := c.Auth.store().Load(e.url)
	old := tok.AccessToken
	expired := *tok
	expired.Expiry = time.Now().Add(-time.Minute)
	c.Auth.store().Save(e.url, &expired)
	if r, err := c.CallTool("echo", obj{"text": "2"}, nil); err != nil || Text(r) != "2" {
		t.Fatalf("%v %v", r, err)
	}
	tok, _ = c.Auth.store().Load(e.url)
	if e.as.Refreshes != 1 || e.as.Authorizations != 1 || tok.AccessToken == old {
		t.Fatalf("refreshes=%d authorizations=%d rotated=%v", e.as.Refreshes, e.as.Authorizations, tok.AccessToken != old)
	}
}

func TestInsufficientScopeStepUp(t *testing.T) {
	e := newEnv(t)
	c := e.autoClient()
	if _, err := c.CallTool("echo", obj{"text": "1"}, nil); err != nil {
		t.Fatal(err)
	}
	r, err := c.CallTool("greet", obj{}, func(string, obj) (obj, error) {
		return obj{"action": "accept", "content": obj{"name": "scoped"}}, nil
	})
	if err != nil || Text(r) != "Hello, scoped!" {
		t.Fatalf("%v %v", r, err)
	}
	sc := strings.Fields(e.as.LastAuthorizeQuery.Get("scope"))
	if e.as.Authorizations != 2 || len(sc) != 2 || !strings.Contains(strings.Join(sc, " "), "mcp:read") {
		t.Fatalf("authorizations=%d step-up scope=%v (want union)", e.as.Authorizations, sc)
	}
	if _, err := c.CallTool("echo", obj{"text": "still"}, nil); err != nil || e.as.Authorizations != 2 {
		t.Fatalf("previous scope lost: %v", err)
	}
}

func TestPRMWellKnownFallbackAndNoClientIdentity(t *testing.T) {
	e := newEnv(t)
	o := &OAuth{HTTP: e.hc}
	m, err := o.fetchPRM(context.Background(), e.url, "")
	if err != nil || m["resource"] != e.url {
		t.Fatalf("%v %v", m, err)
	}
	c := NewClient(e.url)
	c.HTTP = e.hc
	c.Auth = &OAuth{HTTP: e.hc, Authorizer: PauseAuthorizer{Redirect: "http://127.0.0.1:9/callback"}}
	if _, err := c.Call("tools/list", nil); err == nil || !strings.Contains(err.Error(), "register an app with "+e.as.Issuer) {
		t.Fatalf("want no-identity error, got %v", err)
	}
}

// --- server-side ---

func rawCall(t *testing.T, e *env, token, tool string) (int, string, string) {
	t.Helper()
	body := fmt.Sprintf(`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":%q,"arguments":{"text":"x"},"_meta":{%q:%q}}}`, tool, MetaVersion, ProtocolVersion)
	req, _ := http.NewRequest("POST", e.url, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("MCP-Protocol-Version", ProtocolVersion)
	req.Header.Set("Mcp-Method", "tools/call")
	req.Header.Set("Mcp-Name", tool)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := e.hc.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, resp.Header.Get("WWW-Authenticate"), string(b)
}

func (e *env) claims(mut func(map[string]any)) string {
	c := map[string]any{"iss": e.as.Issuer, "sub": "alice", "aud": e.url, "scope": "mcp:read", "exp": time.Now().Add(time.Hour).Unix()}
	if mut != nil {
		mut(c)
	}
	return e.as.Mint(c)
}

func TestServerJWTViaJWKSAndPrincipal(t *testing.T) {
	e := newEnv(t)
	st, _, body := rawCall(t, e, e.claims(nil), "whoami")
	if st != 200 || !strings.Contains(body, "alice mcp:read") {
		t.Fatalf("%d %s", st, body)
	}
}

func TestServerRejects(t *testing.T) {
	e := newEnv(t)
	cases := map[string]string{
		"missing":        "",
		"wrong audience": e.claims(func(c map[string]any) { c["aud"] = "https://other.example/mcp" }),
		"expired":        e.claims(func(c map[string]any) { c["exp"] = time.Now().Add(-time.Minute).Unix() }),
		"wrong issuer":   e.claims(func(c map[string]any) { c["iss"] = "https://evil.example" }),
		"tampered":       e.claims(nil) + "x",
	}
	for name, tok := range cases {
		st, www, _ := rawCall(t, e, tok, "echo")
		if st != 401 || !strings.Contains(www, `resource_metadata="`+e.mcp.URL+"/.well-known/oauth-protected-resource/mcp") {
			t.Errorf("%s: %d %q", name, st, www)
		}
		if tok != "" && !strings.Contains(www, `error="invalid_token"`) {
			t.Errorf("%s: %q", name, www)
		}
	}
}

func TestServerPerToolScope403(t *testing.T) {
	e := newEnv(t)
	st, www, _ := rawCall(t, e, e.claims(nil), "greet")
	if st != 403 || !strings.Contains(www, `error="insufficient_scope"`) || !strings.Contains(www, `scope="mcp:read mcp:greet"`) {
		t.Fatalf("%d %q", st, www)
	}
	st, _, _ = rawCall(t, e, e.claims(func(c map[string]any) { c["scope"] = "mcp:read mcp:greet" }), "greet")
	if st != 200 {
		t.Fatalf("greet with scope: %d", st)
	}
}

func TestServerCustomVerify(t *testing.T) {
	e := newEnv(t)
	e.server.Auth.Verify = func(_ context.Context, tok string) (Principal, error) {
		if tok != "opaque-ok" {
			return Principal{}, errors.New("nope")
		}
		return Principal{Subject: "svc", Scopes: []string{"mcp:read"}}, nil
	}
	if st, _, body := rawCall(t, e, "opaque-ok", "whoami"); st != 200 || !strings.Contains(body, "svc mcp:read") {
		t.Fatalf("%d %s", st, body)
	}
	if st, _, _ := rawCall(t, e, e.claims(nil), "whoami"); st != 401 {
		t.Fatalf("JWT must not bypass custom Verify: %d", st)
	}
}

// A browser opener that fails must surface its error, not park the caller for the 5-minute callback timeout.
func TestLoopbackOpenerErrorSurfaces(t *testing.T) {
	e := newEnv(t)
	c := e.autoClient()
	c.Auth.Authorizer = LoopbackAuthorizer{Open: func(string) error { return errors.New("no browser") }}
	done := make(chan error, 1)
	go func() { _, err := c.CallTool("echo", obj{"text": "x"}, nil); done <- err }()
	select {
	case err := <-done:
		if err == nil || !strings.Contains(err.Error(), "no browser") {
			t.Fatalf("want opener error, got %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("opener error swallowed: client still waiting for a callback")
	}
}
