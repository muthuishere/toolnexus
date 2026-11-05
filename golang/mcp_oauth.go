// MCP authorization client (SPEC.md §2A.1, §2A.1a). Stdlib only.
// 401/403 → WWW-Authenticate → PRM (RFC 9728) → AS metadata (RFC 8414 / OIDC)
// → client identity (pre-registered | CIMD; never DCR) → code + PKCE S256 +
// resource (RFC 8707) → iss (RFC 9207) + state check → token → retry.
// Tokens, codes, verifiers and secrets are never logged and never in error text.

package toolnexus

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os/exec"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// McpAuthConfig is the per-server `auth` object in mcp.json (§2A.1). All optional.
type McpAuthConfig struct {
	ClientID            string            `json:"clientId,omitempty"`
	ClientSecret        string            `json:"clientSecret,omitempty"`    // never logged
	TokenAuthMethod     string            `json:"tokenAuthMethod,omitempty"` // client_secret_basic | client_secret_post
	ClientIDs           map[string]string `json:"clientIds,omitempty"`       // issuer → pre-registered client_id
	ClientMetadataURL   string            `json:"clientMetadataUrl,omitempty"`
	RedirectURI         string            `json:"redirectUri,omitempty"`
	Mode                string            `json:"mode,omitempty"` // "auto" (default) | "pause"
	OmitRefreshResource bool              `json:"omitRefreshResource,omitempty"`
	AllowHTTP           bool              `json:"allowHttp,omitempty"` // dev/test only
}

// Token is an OAuth access grant bound to the issuer that minted it and the
// resource (MCP server URI) it is for. Its string forms never show a token value.
type Token struct {
	AccessToken  string
	RefreshToken string
	ExpiresAt    time.Time // zero = no expiry known
	Scopes       []string
	Issuer       string
	ClientID     string
	Resource     string
}

func (t Token) String() string   { return "Token{issuer=" + t.Issuer + ", redacted}" }
func (t Token) GoString() string { return t.String() }

// TokenStore persists tokens per resource. Implementations keep them confidential.
type TokenStore interface {
	Load(resource string) (*Token, bool)
	Save(resource string, t *Token)
	Delete(resource string)
}

// MemoryTokenStore is the default TokenStore (in-memory, per load).
type MemoryTokenStore struct {
	mu sync.Mutex
	m  map[string]*Token
}

func (s *MemoryTokenStore) Load(r string) (*Token, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.m[r]
	return t, ok
}

func (s *MemoryTokenStore) Save(r string, t *Token) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.m == nil {
		s.m = map[string]*Token{}
	}
	s.m[r] = t
}

func (s *MemoryTokenStore) Delete(r string) { s.mu.Lock(); delete(s.m, r); s.mu.Unlock() }

// mcpAuthPending is returned instead of blocking when pause mode has no waitFor.
type mcpAuthPending struct{ Request Request }

func (e *mcpAuthPending) Error() string { return "authorization pending: " + e.Request.Prompt }

var errMcpAuthPause = errors.New("authorization paused")

type mcpAuthSession interface {
	redirectURI() string
	// wait returns the full callback URL, or errMcpAuthPause.
	wait(ctx context.Context, req Request) (string, error)
	close()
}

// pauseSession: no browser. With a waitFor the §10 authorization Request is
// answered synchronously (Answer.data.callbackUrl); without one, it pauses.
type pauseSession struct {
	redirect string
	waitFor  func(Request) (Answer, error)
}

func (p pauseSession) redirectURI() string { return p.redirect }
func (p pauseSession) close()              {}
func (p pauseSession) wait(_ context.Context, req Request) (string, error) {
	if p.waitFor == nil {
		return "", errMcpAuthPause
	}
	ans, err := p.waitFor(req)
	if err != nil {
		return "", err
	}
	return callbackFromAnswer(ans)
}

func callbackFromAnswer(ans Answer) (string, error) {
	if !ans.Ok {
		reason := ans.Reason
		if reason == "" {
			reason = "cancelled"
		}
		return "", fmt.Errorf("authorization denied: %s", reason)
	}
	cb, _ := ans.Data["callbackUrl"].(string)
	if cb == "" {
		return "", errors.New("authorization answer has no callbackUrl")
	}
	return cb, nil
}

// loopbackSession: open the browser (openURL hook) and receive the callback on
// http://127.0.0.1:<random>/callback (or the configured redirectUri's host/path).
type loopbackSession struct {
	ln   net.Listener
	srv  *http.Server
	path string
	cb   chan string
	open func(string) error
}

func startLoopback(redirect string, open func(string) error) (*loopbackSession, error) {
	addr, path := "127.0.0.1:0", "/callback"
	if redirect != "" {
		u, err := url.Parse(redirect)
		if err != nil {
			return nil, err
		}
		addr, path = u.Host, u.Path
	}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	s := &loopbackSession{ln: ln, path: path, cb: make(chan string, 1), open: open}
	if s.open == nil {
		s.open = openBrowser
	}
	s.srv = &http.Server{ReadHeaderTimeout: 10 * time.Second, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != s.path {
			http.NotFound(w, r)
			return
		}
		select {
		case s.cb <- "http://" + r.Host + r.URL.RequestURI():
		default:
		}
		_, _ = w.Write([]byte("Authorization received. You can close this window."))
	})}
	go func() { _ = s.srv.Serve(ln) }()
	return s, nil
}

func (s *loopbackSession) redirectURI() string { return "http://" + s.ln.Addr().String() + s.path }
func (s *loopbackSession) close()              { _ = s.srv.Close() }
func (s *loopbackSession) wait(ctx context.Context, req Request) (string, error) {
	opened := make(chan error, 1)
	go func() { opened <- s.open(req.URL) }() // an opener may block (tests follow redirects)
	timeout := time.NewTimer(5 * time.Minute)
	defer timeout.Stop()
	for {
		select {
		case u := <-s.cb:
			return u, nil
		case err := <-opened:
			if err != nil {
				return "", fmt.Errorf("open authorization URL: %w", err)
			}
			opened = nil
		case <-ctx.Done():
			return "", ctx.Err()
		case <-timeout.C:
			return "", errors.New("authorization timed out")
		}
	}
}

func openBrowser(u string) error {
	switch runtime.GOOS {
	case "darwin":
		return exec.Command("open", u).Start()
	case "windows":
		return exec.Command("rundll32", "url.dll,FileProtocolHandler", u).Start()
	}
	return exec.Command("xdg-open", u).Start()
}

// mcpOAuth is the per-server client auth engine.
type mcpOAuth struct {
	cfg     McpAuthConfig
	store   TokenStore
	openURL func(string) error
	waitFor func(Request) (Answer, error)
	hc      *http.Client

	mu     sync.Mutex
	authMu sync.Mutex        // one refresh / one interactive authorization at a time
	issuer map[string]string // resource → discovered issuer (only then is a stored token sent)
	flows  map[string]*mcpAuthFlow
}

type mcpAuthFlow struct {
	resource, issuer, clientID, redirect, verifier, state, tokenEndpoint string
	issSupported                                                         bool
	scopes                                                               []string
	expires                                                              time.Time
}

var mcpOAuthHTTP = &http.Client{Timeout: 30 * time.Second}

func newMcpOAuth(cfg *McpAuthConfig, store TokenStore, openURL func(string) error, waitFor func(Request) (Answer, error)) *mcpOAuth {
	o := &mcpOAuth{store: store, openURL: openURL, waitFor: waitFor, hc: mcpOAuthHTTP}
	if cfg != nil {
		o.cfg = *cfg
	}
	if o.store == nil {
		o.store = &MemoryTokenStore{}
	}
	return o
}

// bearer returns the access token to send for resource ("" = none), refreshing on expiry.
func (o *mcpOAuth) bearer(ctx context.Context, resource string) string {
	o.mu.Lock()
	iss := o.issuer[resource]
	o.mu.Unlock()
	t, ok := o.store.Load(resource)
	if !ok || t == nil || iss == "" || t.Issuer != iss {
		return ""
	}
	if tokenExpired(t) {
		o.authMu.Lock() // single-flight: N concurrent callers share one refresh
		defer o.authMu.Unlock()
		if t, ok = o.store.Load(resource); !ok || t == nil {
			return ""
		}
		if tokenExpired(t) && o.refresh(ctx, t) != nil {
			return ""
		}
		if t, ok = o.store.Load(resource); !ok || t == nil {
			return ""
		}
	}
	return t.AccessToken
}

func tokenExpired(t *Token) bool {
	return !t.ExpiresAt.IsZero() && time.Now().After(t.ExpiresAt.Add(-10*time.Second))
}

func (o *mcpOAuth) refresh(ctx context.Context, t *Token) error {
	if t.RefreshToken == "" {
		return errors.New("no refresh token")
	}
	md, err := o.discoverAS(ctx, t.Issuer)
	if err != nil {
		return err
	}
	ep, _ := md["token_endpoint"].(string)
	form := url.Values{"grant_type": {"refresh_token"}, "refresh_token": {t.RefreshToken}, "client_id": {t.ClientID}}
	if !o.cfg.OmitRefreshResource {
		form.Set("resource", t.Resource)
	}
	nt, err := o.tokenRequest(ctx, ep, form, t.Issuer, t.Resource, t.ClientID, t.Scopes)
	if err != nil {
		o.store.Delete(t.Resource)
		return err
	}
	if nt.RefreshToken == "" {
		nt.RefreshToken = t.RefreshToken
	}
	o.store.Save(t.Resource, nt)
	return nil
}

// challenge handles a 401/403. nil = retry now; *mcpAuthPending = paused.
// sent is the access token the failed request carried ("" = none).
func (o *mcpOAuth) challenge(ctx context.Context, resource string, status int, www, sent string) error {
	params := parseBearerChallenge(www)
	o.authMu.Lock()
	defer o.authMu.Unlock()
	cur, _ := o.store.Load(resource)
	o.mu.Lock()
	known := o.issuer[resource]
	o.mu.Unlock()
	if cur != nil && known != "" && cur.Issuer == known && cur.AccessToken != sent && !tokenExpired(cur) {
		return nil // another caller refreshed/authorized while we waited
	}
	if status == http.StatusUnauthorized && cur != nil && cur.RefreshToken != "" && params["error"] == "invalid_token" {
		if o.refresh(ctx, cur) == nil {
			return nil
		}
		cur = nil
	}
	if status == http.StatusForbidden && params["error"] != "insufficient_scope" {
		return fmt.Errorf("forbidden: %s", params["error"])
	}
	prm, err := o.fetchPRM(ctx, resource, params["resource_metadata"])
	if err != nil {
		return err
	}
	servers, _ := prm["authorization_servers"].([]any)
	if len(servers) == 0 {
		return errors.New("PRM lists no authorization_servers")
	}
	issuer, _ := servers[0].(string)
	if !o.cfg.AllowHTTP && !strings.HasPrefix(issuer, "https://") {
		return errors.New("authorization server must use https")
	}
	md, err := o.discoverAS(ctx, issuer)
	if err != nil {
		return err
	}
	if m, _ := md["code_challenge_methods_supported"].([]any); !slices.Contains(m, any("S256")) {
		return errors.New("authorization server does not advertise PKCE S256; refusing")
	}
	o.mu.Lock()
	if o.issuer == nil {
		o.issuer = map[string]string{}
	}
	o.issuer[resource] = issuer
	o.mu.Unlock()
	if status == http.StatusUnauthorized && cur != nil && cur.Issuer == issuer && params["error"] == "" && cur.AccessToken != sent {
		return nil // first request after a restart: the issuer is now confirmed, send the stored token
	}
	scopes := strings.Fields(params["scope"])
	if len(scopes) == 0 {
		scopes = append(scopes, stringList(prm["scopes_supported"])...)
	}
	if cur != nil && status == http.StatusForbidden { // step-up: union with what was granted
		for _, s := range cur.Scopes {
			if !slices.Contains(scopes, s) {
				scopes = append(scopes, s)
			}
		}
	}
	clientID := o.cfg.ClientIDs[issuer]
	if clientID == "" {
		clientID = o.cfg.ClientID
	}
	cimd := md["client_id_metadata_document_supported"] == true
	if clientID == "" && o.cfg.ClientMetadataURL != "" && cimd {
		clientID = o.cfg.ClientMetadataURL
	}
	if clientID == "" { // no DCR, ever
		why := "it does not support Client ID Metadata Documents"
		if cimd {
			why = "auth.clientMetadataUrl is not set"
		}
		return fmt.Errorf("no client identity for %s: %s and no client id is configured — register an app with %s and set auth.clientId (dynamic client registration is not supported)", issuer, why, issuer)
	}
	authEP, _ := md["authorization_endpoint"].(string)
	tokenEP, _ := md["token_endpoint"].(string)
	sess, err := o.begin()
	if err != nil {
		return err
	}
	f := &mcpAuthFlow{resource: resource, issuer: issuer, clientID: clientID, redirect: sess.redirectURI(),
		verifier: oauthRand(32), state: oauthRand(16), tokenEndpoint: tokenEP, scopes: scopes,
		issSupported: md["authorization_response_iss_parameter_supported"] == true, expires: time.Now().Add(10 * time.Minute)}
	ch := sha256.Sum256([]byte(f.verifier))
	q := url.Values{"response_type": {"code"}, "client_id": {clientID}, "redirect_uri": {f.redirect},
		"state": {f.state}, "code_challenge": {base64.RawURLEncoding.EncodeToString(ch[:])},
		"code_challenge_method": {"S256"}, "resource": {resource}}
	if len(scopes) > 0 {
		q.Set("scope", strings.Join(scopes, " "))
	}
	req := Request{ID: "auth-" + oauthRand(12), Kind: "authorization", URL: authEP + "?" + q.Encode(),
		Prompt: "Authorize access to " + resource, ExpiresAt: f.expires.UTC().Format(time.RFC3339)}
	cb, err := sess.wait(ctx, req)
	if errors.Is(err, errMcpAuthPause) {
		o.mu.Lock()
		if o.flows == nil {
			o.flows = map[string]*mcpAuthFlow{}
		}
		for id, old := range o.flows {
			if time.Now().After(old.expires) {
				delete(o.flows, id)
			}
		}
		o.flows[req.ID] = f
		o.mu.Unlock()
		return &mcpAuthPending{Request: req}
	}
	sess.close()
	if err != nil {
		return err
	}
	return o.complete(ctx, f, cb)
}

func (o *mcpOAuth) begin() (mcpAuthSession, error) {
	if o.cfg.Mode == "pause" {
		if o.cfg.RedirectURI == "" {
			return nil, errors.New("auth.redirectUri is required for pause mode")
		}
		return pauseSession{redirect: o.cfg.RedirectURI, waitFor: o.waitFor}, nil
	}
	return startLoopback(o.cfg.RedirectURI, o.openURL)
}

// resume completes a paused authorization with the callback URL the AS redirected to.
func (o *mcpOAuth) resume(ctx context.Context, id, callbackURL string) error {
	o.mu.Lock()
	f := o.flows[id]
	delete(o.flows, id)
	o.mu.Unlock()
	if f == nil {
		return fmt.Errorf("unknown pending authorization %q", id)
	}
	if time.Now().After(f.expires) {
		return errors.New("pending authorization expired")
	}
	return o.complete(ctx, f, callbackURL)
}

func (o *mcpOAuth) complete(ctx context.Context, f *mcpAuthFlow, callbackURL string) error {
	u, err := url.Parse(callbackURL)
	if err != nil {
		return errors.New("authorization callback URL is malformed")
	}
	q := u.Query()
	if iss, has := q["iss"]; has { // RFC 9207: validated before anything else, error responses included
		if iss[0] != f.issuer {
			return errors.New("authorization response iss mismatch (possible mix-up attack)")
		}
	} else if f.issSupported {
		return errors.New("authorization response missing iss")
	}
	if q.Get("state") != f.state {
		return errors.New("authorization response state mismatch")
	}
	if e := q.Get("error"); e != "" {
		return fmt.Errorf("authorization denied: %s", e)
	}
	t, err := o.tokenRequest(ctx, f.tokenEndpoint, url.Values{"grant_type": {"authorization_code"}, "code": {q.Get("code")},
		"redirect_uri": {f.redirect}, "client_id": {f.clientID}, "code_verifier": {f.verifier}, "resource": {f.resource}},
		f.issuer, f.resource, f.clientID, f.scopes)
	if err != nil {
		return err
	}
	o.store.Save(f.resource, t)
	return nil
}

func (o *mcpOAuth) tokenRequest(ctx context.Context, ep string, form url.Values, issuer, resource, clientID string, scopes []string) (*Token, error) {
	method := o.cfg.TokenAuthMethod
	if clientID == o.cfg.ClientMetadataURL || o.cfg.ClientSecret == "" {
		method = "none" // CIMD and secretless clients are public
	} else if method == "" {
		method = "client_secret_basic"
	}
	if method == "client_secret_post" {
		form.Set("client_secret", o.cfg.ClientSecret)
	}
	if method == "client_secret_basic" {
		form.Del("client_id") // RFC 6749 §2.3.1: one authentication method only
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, ep, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, errors.New("token endpoint: bad URL")
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	if method == "client_secret_basic" {
		req.SetBasicAuth(url.QueryEscape(clientID), url.QueryEscape(o.cfg.ClientSecret))
	}
	resp, err := o.hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("token endpoint: %w", redactURLError(err))
	}
	defer resp.Body.Close()
	var tr struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int    `json:"expires_in"`
		Scope        string `json:"scope"`
		Error        string `json:"error"`
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	err = json.Unmarshal(body, &tr)
	if err != nil && strings.HasPrefix(resp.Header.Get("Content-Type"), "application/x-www-form-urlencoded") {
		if v, perr := url.ParseQuery(string(body)); perr == nil {
			tr.AccessToken, tr.RefreshToken, tr.Scope, tr.Error = v.Get("access_token"), v.Get("refresh_token"), v.Get("scope"), v.Get("error")
			tr.ExpiresIn, _ = strconv.Atoi(v.Get("expires_in"))
			err = nil
		}
	}
	if err != nil || resp.StatusCode != 200 || tr.AccessToken == "" {
		return nil, fmt.Errorf("token endpoint: HTTP %d %s", resp.StatusCode, tr.Error) // never the body
	}
	t := &Token{AccessToken: tr.AccessToken, RefreshToken: tr.RefreshToken, Issuer: issuer, Resource: resource,
		ClientID: clientID, Scopes: scopes}
	if tr.Scope != "" {
		t.Scopes = strings.Fields(tr.Scope)
	}
	if tr.ExpiresIn > 0 {
		t.ExpiresAt = time.Now().Add(time.Duration(tr.ExpiresIn) * time.Second)
	}
	return t, nil
}

// redactURLError drops the URL from a transport error (it could carry a code).
func redactURLError(err error) error {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Err
	}
	return err
}

const mcpPRMPath = "/.well-known/oauth-protected-resource"

func (o *mcpOAuth) fetchPRM(ctx context.Context, resource, hinted string) (jsonObj, error) {
	cands := []string{hinted}
	if hinted == "" {
		u, err := url.Parse(resource)
		if err != nil {
			return nil, err
		}
		root := u.Scheme + "://" + u.Host + mcpPRMPath
		cands = nil
		if p := strings.TrimSuffix(u.Path, "/"); p != "" {
			cands = append(cands, root+p)
		}
		cands = append(cands, root)
	}
	var last error
	for _, c := range cands {
		var m jsonObj
		if last = o.getJSON(ctx, c, &m); last == nil {
			if m["resource"] != resource {
				return nil, fmt.Errorf("PRM resource %v does not match %s", m["resource"], resource)
			}
			return m, nil
		}
	}
	return nil, fmt.Errorf("protected resource metadata: %w", last)
}

func (o *mcpOAuth) discoverAS(ctx context.Context, issuer string) (jsonObj, error) {
	u, err := url.Parse(issuer)
	if err != nil {
		return nil, err
	}
	base := u.Scheme + "://" + u.Host
	p := strings.TrimSuffix(u.Path, "/")
	cands := []string{base + "/.well-known/oauth-authorization-server", base + "/.well-known/openid-configuration"}
	if p != "" {
		cands = []string{base + "/.well-known/oauth-authorization-server" + p,
			base + "/.well-known/openid-configuration" + p, base + p + "/.well-known/openid-configuration"}
	}
	for _, c := range cands {
		var m jsonObj
		if o.getJSON(ctx, c, &m) == nil {
			if m["issuer"] != issuer {
				return nil, fmt.Errorf("AS metadata issuer %v != %s", m["issuer"], issuer)
			}
			return m, nil
		}
	}
	return nil, fmt.Errorf("no authorization server metadata for %s", issuer)
}

func (o *mcpOAuth) getJSON(ctx context.Context, u string, v any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	resp, err := o.hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("GET %s: HTTP %d", u, resp.StatusCode)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(v)
}

// parseBearerChallenge parses `Bearer k="v", k2=v2` (RFC 6750 §3).
func parseBearerChallenge(h string) map[string]string {
	out := map[string]string{}
	h, _ = strings.CutPrefix(strings.TrimSpace(h), "Bearer")
	for h = strings.TrimSpace(h); h != ""; {
		eq := strings.IndexByte(h, '=')
		if eq < 0 {
			break
		}
		k := strings.ToLower(strings.TrimSpace(h[:eq]))
		h = strings.TrimSpace(h[eq+1:])
		var v string
		if strings.HasPrefix(h, `"`) {
			end := 1
			for end < len(h) && h[end] != '"' {
				if h[end] == '\\' {
					end++
				}
				end++
			}
			v = strings.ReplaceAll(h[1:min(end, len(h))], `\"`, `"`)
			h = h[min(end+1, len(h)):]
		} else {
			end := strings.IndexByte(h, ',')
			if end < 0 {
				end = len(h)
			}
			v, h = strings.TrimSpace(h[:end]), h[end:]
		}
		out[k] = v
		h = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(h), ","))
	}
	return out
}

func stringList(v any) []string {
	var out []string
	if xs, ok := v.([]any); ok {
		for _, x := range xs {
			if s, ok := x.(string); ok {
				out = append(out, s)
			}
		}
	}
	return out
}

func oauthRand(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}
