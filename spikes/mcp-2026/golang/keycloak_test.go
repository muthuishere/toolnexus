package mcp2026

// Live tests against a real Keycloak (keycloak/docker-compose.yml). Skipped unless KEYCLOAK=1,
// so `go test ./...` stays hermetic. Run: cd keycloak && docker compose up -d && cd .. &&
// KEYCLOAK=1 go test -run Keycloak -v ./...

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"
)

const (
	kcBase     = "http://localhost:8180"
	kcIssuer   = kcBase + "/realms/toolnexus"
	kcClientID = "toolnexus-mcp"
	kcUser     = "alice"
	kcPassword = "alice-test-pw"  // local-only throwaway test user from keycloak/realm-toolnexus.json
	kcMCPAddr  = "127.0.0.1:7199" // the realm's audience mapper pins aud = http://127.0.0.1:7199/mcp
	kcMCPURL   = "http://" + kcMCPAddr + "/mcp"
)

// recorder logs every request the OAuth client / server make (URL + form), never headers.
type recorder struct {
	mu   sync.Mutex
	reqs []string
	form []url.Values
}

func (r *recorder) RoundTrip(req *http.Request) (*http.Response, error) {
	var f url.Values
	if req.Body != nil && req.Method == http.MethodPost {
		b, _ := io.ReadAll(req.Body)
		req.Body = io.NopCloser(strings.NewReader(string(b)))
		f, _ = url.ParseQuery(string(b))
	}
	resp, err := http.DefaultTransport.RoundTrip(req)
	code := 0
	if resp != nil {
		code = resp.StatusCode
	}
	r.mu.Lock()
	r.reqs = append(r.reqs, fmt.Sprintf("%s %s -> %d", req.Method, req.URL.String(), code))
	r.form = append(r.form, f)
	r.mu.Unlock()
	return resp, err
}

func (r *recorder) tokenForms() []url.Values {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []url.Values
	for i, s := range r.reqs {
		if strings.Contains(s, "/protocol/openid-connect/token") {
			out = append(out, r.form[i])
		}
	}
	return out
}

// kcBrowser is a headless "browser": cookie jar + Keycloak login form submission, net/http only.
type kcBrowser struct {
	hc        *http.Client
	callbacks []string // every loopback callback URL Keycloak redirected to
	forms     int      // how many times the login form was submitted (SSO reuse => 0)
	consents  int      // consent screens accepted
}

func newKCBrowser() *kcBrowser {
	jar, _ := cookiejar.New(nil)
	b := &kcBrowser{}
	b.hc = &http.Client{Jar: jar, Timeout: 15 * time.Second,
		CheckRedirect: func(req *http.Request, _ []*http.Request) error {
			if req.URL.Hostname() == "127.0.0.1" { // the loopback callback: stop, hand it back
				return http.ErrUseLastResponse
			}
			return nil
		}}
	return b
}

var anyForm = regexp.MustCompile(`<form[^>]*action="([^"]+)"`)

var formAction = regexp.MustCompile(`<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"`)

// login walks the authorize URL to the callback URL (logging in if Keycloak shows the form).
func (b *kcBrowser) login(authURL string) (string, error) {
	resp, err := b.hc.Get(authURL)
	if err != nil {
		return "", err
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	for page := 0; resp.StatusCode == 200 && page < 3; page++ {
		var action string
		var form url.Values
		if m := formAction.FindSubmatch(body); m != nil { // login page
			b.forms++
			action, form = string(m[1]), url.Values{"username": {kcUser}, "password": {kcPassword}, "credentialId": {""}}
		} else if m := anyForm.FindSubmatch(body); m != nil && strings.Contains(string(body), `name="accept"`) {
			b.consents++ // consent page (Keycloak forces it for CIMD clients)
			action, form = string(m[1]), url.Values{"accept": {"Yes"}}
		} else {
			break
		}
		target, err := resp.Request.URL.Parse(html.UnescapeString(action)) // consent action is relative
		if err != nil {
			return "", err
		}
		if resp, err = b.hc.PostForm(target.String(), form); err != nil {
			return "", err
		}
		body, _ = io.ReadAll(resp.Body)
		resp.Body.Close()
	}
	if resp.StatusCode != 302 {
		return "", fmt.Errorf("expected redirect to loopback, got HTTP %d: %s", resp.StatusCode, kcPageText(body))
	}
	loc := resp.Header.Get("Location")
	b.callbacks = append(b.callbacks, loc)
	return loc, nil
}

// open is the LoopbackAuthorizer opener: log in, then deliver the callback to the loopback listener.
func (b *kcBrowser) open(authURL string) error {
	cb, err := b.login(authURL)
	if err != nil {
		fmt.Println("kcBrowser login error:", err)
		return err
	}
	resp, err := http.Get(cb)
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

func kcSkip(t *testing.T) {
	t.Helper()
	if os.Getenv("KEYCLOAK") == "" {
		t.Skip("set KEYCLOAK=1 with keycloak/docker-compose.yml running")
	}
}

// kcServer starts the spike MCP server on the fixed address the realm's audience mapper expects.
func kcServer(t *testing.T, audience string) *ServerAuth {
	t.Helper()
	srv := mustServer()
	auth := &ServerAuth{Issuer: kcIssuer, Audience: audience,
		Scopes: map[string][]string{"*": {"mcp:tools"}, "greet": {"mcp:greet"}}}
	srv.Auth = auth
	srv.Tool("whoami", "who is calling", func(ctx context.Context, _ obj) (any, error) {
		p, ok := PrincipalFrom(ctx)
		if !ok {
			return nil, errors.New("no principal")
		}
		return p.Subject + " " + strings.Join(p.Scopes, ","), nil
	})
	ln, err := net.Listen("tcp", kcMCPAddr)
	if err != nil {
		t.Fatal(err)
	}
	hs := &http.Server{Handler: srv}
	go hs.Serve(ln)
	t.Cleanup(func() { hs.Close() })
	return auth
}

type kcEnv struct {
	rec     *recorder
	browser *kcBrowser
	store   *MemoryTokenStore
}

func newKCEnv(t *testing.T) *kcEnv {
	kcSkip(t)
	kcServer(t, kcMCPURL)
	return &kcEnv{rec: &recorder{}, browser: newKCBrowser(), store: &MemoryTokenStore{}}
}

func (e *kcEnv) client(mod ...func(*OAuth)) *Client {
	c := NewClient(kcMCPURL)
	o := &OAuth{HTTP: &http.Client{Transport: e.rec, Timeout: 15 * time.Second}, AllowHTTP: true,
		ClientIDs: map[string]string{kcIssuer: kcClientID}, Store: e.store,
		Authorizer: LoopbackAuthorizer{Open: e.browser.open}}
	for _, f := range mod {
		f(o)
	}
	c.Auth = o
	return c
}

func TestKeycloakEndToEnd(t *testing.T) {
	e := newKCEnv(t)
	c := e.client()
	r, err := c.CallTool("whoami", obj{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("whoami: %s", Text(r))
	if !strings.Contains(Text(r), "mcp:tools") {
		t.Fatalf("whoami lacks mcp:tools: %s", Text(r))
	}
	for _, l := range e.rec.reqs {
		t.Log(l)
	}
	// AS metadata: which well-known answered?
	var asmd string
	for _, l := range e.rec.reqs {
		if strings.Contains(l, "/.well-known/") && strings.HasSuffix(l, "-> 200") && !strings.Contains(l, prmPath) {
			asmd = l
			break
		}
	}
	if !strings.Contains(asmd, "/.well-known/oauth-authorization-server/realms/toolnexus") {
		t.Fatalf("expected RFC 8414 path-insert metadata, got %q", asmd)
	}
	cb, _ := url.Parse(e.browser.callbacks[0])
	if cb.Query().Get("iss") != kcIssuer {
		t.Fatalf("callback iss = %q (RFC 9207)", cb.Query().Get("iss"))
	}
	tf := e.rec.tokenForms()
	if len(tf) != 1 || tf[0].Get("resource") != kcMCPURL || tf[0].Get("code_verifier") == "" {
		t.Fatalf("code exchange form: %v", tf)
	}
	// Token claims as Keycloak issued them.
	tok, _ := e.store.Load(kcMCPURL)
	var claims map[string]any
	decodeSeg(strings.Split(tok.AccessToken, ".")[1], &claims)
	t.Logf("aud=%v scope=%v azp=%v iss=%v", claims["aud"], claims["scope"], claims["azp"], claims["iss"])
}

func TestKeycloakEightSteps(t *testing.T) {
	e := newKCEnv(t)
	if !runChecks(kcMCPURL, func(c *Client) { *c = *e.client() }) {
		t.Fatal("protected selftest against Keycloak failed")
	}
	t.Logf("login forms submitted: %d, callbacks: %d", e.browser.forms, len(e.browser.callbacks))
}

func kcExpireToken(t *testing.T, s TokenStore) {
	tok, ok := s.Load(kcMCPURL)
	if !ok {
		t.Fatal("no token")
	}
	cp := *tok
	cp.Expiry = time.Now().Add(-time.Minute)
	s.Save(kcMCPURL, &cp)
}

func TestKeycloakRefresh(t *testing.T) {
	for _, omit := range []bool{false, true} {
		t.Run(fmt.Sprintf("omitResource=%v", omit), func(t *testing.T) {
			e := newKCEnv(t)
			c := e.client(func(o *OAuth) { o.OmitRefreshResource = omit })
			if _, err := c.CallTool("echo", obj{"text": "a"}, nil); err != nil {
				t.Fatal(err)
			}
			old, _ := e.store.Load(kcMCPURL)
			kcExpireToken(t, e.store)
			r, err := c.CallTool("echo", obj{"text": "b"}, nil)
			if err != nil || Text(r) != "b" {
				t.Fatalf("after refresh: %v %v", r, err)
			}
			tf := e.rec.tokenForms()
			last := tf[len(tf)-1]
			if last.Get("grant_type") != "refresh_token" || last.Has("resource") == omit {
				t.Fatalf("refresh form: %v", last)
			}
			nt, _ := e.store.Load(kcMCPURL)
			if nt.AccessToken == old.AccessToken || e.browser.forms != 1 {
				t.Fatalf("expected a refreshed token without a second login (forms=%d)", e.browser.forms)
			}
			var claims map[string]any
			decodeSeg(strings.Split(nt.AccessToken, ".")[1], &claims)
			t.Logf("refreshed: aud=%v scope=%v", claims["aud"], claims["scope"])
		})
	}
}

func TestKeycloakStepUp(t *testing.T) {
	e := newKCEnv(t)
	c := e.client()
	if _, err := c.CallTool("echo", obj{"text": "a"}, nil); err != nil {
		t.Fatal(err)
	}
	r, err := c.CallTool("greet", obj{}, func(string, obj) (obj, error) {
		return obj{"action": "accept", "content": obj{"name": "Alice"}}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("greet: %s", Text(r))
	cb := e.browser.callbacks
	if len(cb) != 2 {
		t.Fatalf("want 2 authorizations (login + step-up), got %d", len(cb))
	}
	tok, _ := e.store.Load(kcMCPURL)
	if !strings.Contains(strings.Join(tok.Scopes, " "), "mcp:greet") {
		t.Fatalf("token scopes %v", tok.Scopes)
	}
	t.Logf("step-up login forms submitted: %d (SSO session reused when 1)", e.browser.forms)
}

func TestKeycloakPauseResume(t *testing.T) {
	e := newKCEnv(t)
	redirect := "http://127.0.0.1:9/callback" // never served: the host "receives" the redirect
	c := e.client(func(o *OAuth) { o.Authorizer = PauseAuthorizer{Redirect: redirect} })
	_, err := c.CallTool("echo", obj{"text": "after login"}, nil)
	var pe *PendingError
	if !errors.As(err, &pe) {
		t.Fatalf("want pending, got %v", err)
	}
	cb, err := e.browser.login(pe.Pending.URL)
	if err != nil {
		t.Fatal(err)
	}
	r, err := c.Auth.Resume(context.Background(), pe.Pending.ID, cb)
	if err != nil || Text(r) != "after login" {
		t.Fatalf("resume: %v %v", r, err)
	}
}

func TestKeycloakWrongAudienceRejected(t *testing.T) {
	e := newKCEnv(t)
	c := e.client()
	if _, err := c.CallTool("echo", obj{"text": "a"}, nil); err != nil {
		t.Fatal(err)
	}
	tok, _ := e.store.Load(kcMCPURL)
	// A second server whose canonical URI is NOT in the token's aud.
	srv := mustServer()
	srv.Auth = &ServerAuth{Issuer: kcIssuer, Audience: "http://127.0.0.1:7200/mcp",
		Scopes: map[string][]string{"*": {"mcp:tools"}}}
	ln, err := net.Listen("tcp", "127.0.0.1:7200")
	if err != nil {
		t.Fatal(err)
	}
	hs := &http.Server{Handler: srv}
	go hs.Serve(ln)
	defer hs.Close()
	body, _ := json.Marshal(obj{"jsonrpc": "2.0", "id": 1, "method": "tools/list",
		"params": obj{"_meta": obj{"io.modelcontextprotocol/protocolVersion": ProtocolVersion}}})
	call := func(u, bearer string) *http.Response {
		c2 := NewClient(u)
		resp, err := c2.post(context.Background(), "tools/list", obj{}, body, bearer)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp
	}
	if resp := call("http://127.0.0.1:7200/mcp", tok.AccessToken); resp.StatusCode != 401 ||
		!strings.Contains(resp.Header.Get("WWW-Authenticate"), `error="invalid_token"`) {
		t.Fatalf("wrong-aud: HTTP %d %s", resp.StatusCode, resp.Header.Get("WWW-Authenticate"))
	}
	if resp := call(kcMCPURL, tok.AccessToken); resp.StatusCode != 200 {
		t.Fatalf("right-aud control: HTTP %d", resp.StatusCode)
	}
}

// CIMD: Keycloak 26.8 advertises client_id_metadata_document_supported with --features=cimd.
// The client_id URL must be fetchable BY KEYCLOAK (in docker: host.docker.internal).
func TestKeycloakCIMD(t *testing.T) {
	e := newKCEnv(t)
	docURL := os.Getenv("KEYCLOAK_CIMD_URL") // e.g. an https URL Keycloak can reach; default: plain-http host URL
	if docURL == "" {
		docURL = "http://host.docker.internal:7198/client.json"
		ln, err := net.Listen("tcp", "0.0.0.0:7198")
		if err != nil {
			t.Fatal(err)
		}
		hs := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			t.Logf("Keycloak fetched CIMD: %s %s", r.Method, r.URL)
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(obj{"client_id": docURL, "client_name": "toolnexus spike",
				"redirect_uris": []string{"http://127.0.0.1/callback"}, "grant_types": []string{"authorization_code", "refresh_token"},
				"response_types": []string{"code"}, "token_endpoint_auth_method": "none"})
		})}
		go hs.Serve(ln)
		defer hs.Close()
	}
	c := e.client(func(o *OAuth) { o.ClientIDs = nil; o.ClientMetadataURL = docURL })
	_, err := c.CallTool("echo", obj{"text": "cimd"}, nil)
	if err != nil {
		t.Fatalf("CIMD against Keycloak: %v", err)
	}
	t.Logf("CIMD login worked (consent screens accepted: %d)", e.browser.consents)
}

var kcTitle = regexp.MustCompile(`(?s)<title>(.*?)</title>.*?(?:id="input-error"|class="[^"]*(?:kc-feedback-text|alert|instruction)[^"]*")[^>]*>(.*?)<`)

// kcPageText extracts the title + first feedback message of a Keycloak HTML page (for failure messages).
func kcPageText(b []byte) string {
	if m := kcTitle.FindSubmatch(b); m != nil {
		return strings.TrimSpace(string(m[1])) + ": " + strings.TrimSpace(html.UnescapeString(string(m[2])))
	}
	if m := regexp.MustCompile(`(?s)<title>(.*?)</title>`).FindSubmatch(b); m != nil {
		return strings.TrimSpace(string(m[1]))
	}
	return fmt.Sprintf("%.200s", b)
}

// Probe (logs, does not gate): does Keycloak (resource-indicators feature on, pre-registered client) put an
// RFC 8707 `resource` it has no audience mapper for into `aud`? Full real flow against a server on :7200.
func TestKeycloakResourceIndicatorProbe(t *testing.T) {
	kcSkip(t)
	srv := mustServer()
	other := "http://127.0.0.1:7200/mcp"
	srv.Auth = &ServerAuth{Issuer: kcIssuer, Audience: other, Scopes: map[string][]string{"*": {"mcp:tools"}}}
	ln, err := net.Listen("tcp", "127.0.0.1:7200")
	if err != nil {
		t.Fatal(err)
	}
	hs := &http.Server{Handler: srv}
	go hs.Serve(ln)
	defer hs.Close()
	e := &kcEnv{rec: &recorder{}, browser: newKCBrowser(), store: &MemoryTokenStore{}}
	c := e.client()
	c.URL = other
	_, err = c.CallTool("echo", obj{"text": "x"}, nil)
	if tok, ok := e.store.Load(other); ok {
		var claims map[string]any
		decodeSeg(strings.Split(tok.AccessToken, ".")[1], &claims)
		t.Logf("requested resource=%s -> token aud=%v", other, claims["aud"])
	}
	t.Logf("call result: err=%v (authorizations: %d)", err, len(e.browser.callbacks))
}
