package main

// Client-side OAuth for MCP 2026-07-28 (basic/authorization). Stdlib only.
// Flow: 401/403 -> WWW-Authenticate -> PRM (RFC 9728) -> AS metadata (RFC 8414 / OIDC)
// -> client id (pre-registered | CIMD) -> code + PKCE S256 + resource (RFC 8707)
// -> iss (RFC 9207) + state check -> token -> retry with Bearer.

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

// Token is bound to the issuer that minted it and the resource it is for. Never log it.
type Token struct {
	AccessToken, RefreshToken  string
	Expiry                     time.Time
	Scopes                     []string
	Issuer, Resource, ClientID string
}

func (t *Token) String() string { return "Token{issuer=" + t.Issuer + ", redacted}" }

// TokenStore persists tokens per resource. Implementations must keep them confidential.
type TokenStore interface {
	Load(resource string) (*Token, bool)
	Save(resource string, t *Token)
	Delete(resource string)
}

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

// Pending mirrors toolnexus SPEC §10 Request (kind "authorization").
type Pending struct {
	ID, Kind, Prompt, URL, ExpiresAt string
}

// PendingError is returned instead of blocking when the Authorizer pauses.
// Call OAuth.Resume(ctx, ID, callbackURL) to finish and get the original result.
type PendingError struct{ Pending Pending }

func (e *PendingError) Error() string { return "authorization pending: " + e.Pending.Prompt }

// PendingOf returns the Pending inside err, or nil.
func PendingOf(err error) *Pending {
	var pe *PendingError
	if errors.As(err, &pe) {
		return &pe.Pending
	}
	return nil
}

// WebHandler serves one tool call to a browser user, pausing for authorization when needed:
// call runs; if it is pending, the browser is redirected to the AS; when the AS redirects back
// to this same handler (use its URL as PauseAuthorizer.Redirect), the original call is resumed
// and its text result written. Errors become 502.
func (o *OAuth) WebHandler(call func(r *http.Request) (obj, error)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var res obj
		var err error
		if q := r.URL.Query(); q.Get("state") != "" && (q.Has("code") || q.Has("error")) {
			res, err = o.ResumeCallback(r.Context(), r.URL.String())
		} else {
			res, err = call(r)
		}
		if p := PendingOf(err); p != nil {
			http.Redirect(w, r, p.URL, http.StatusFound)
			return
		}
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprint(w, Text(res))
	})
}

// ErrPause is what a pausing AuthSession.Wait returns.
var ErrPause = errors.New("authorization paused")

// Authorizer decides how the user reaches the authorization URL and how the callback comes back.
type Authorizer interface {
	Begin(ctx context.Context) (AuthSession, error)
}

type AuthSession interface {
	RedirectURI() string
	// Wait returns the full callback URL, or ErrPause to hand a Pending to the caller.
	Wait(ctx context.Context, p Pending) (string, error)
	Close()
}

// PauseAuthorizer never blocks: every authorization becomes a Pending.
type PauseAuthorizer struct{ Redirect string }

func (a PauseAuthorizer) Begin(context.Context) (AuthSession, error) { return a, nil }
func (a PauseAuthorizer) RedirectURI() string                        { return a.Redirect }
func (a PauseAuthorizer) Wait(context.Context, Pending) (string, error) {
	return "", ErrPause
}
func (a PauseAuthorizer) Close() {}

// LoopbackAuthorizer opens a browser and listens on 127.0.0.1:<random>/callback (RFC 8252 §7.3).
type LoopbackAuthorizer struct {
	Open func(url string) error // nil = system browser
}

type loopbackSession struct {
	ln   net.Listener
	srv  *http.Server
	cb   chan string
	open func(string) error
}

func (a LoopbackAuthorizer) Begin(context.Context) (AuthSession, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	s := &loopbackSession{ln: ln, cb: make(chan string, 1), open: a.Open}
	if s.open == nil {
		s.open = openBrowser
	}
	s.srv = &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/callback" {
			http.NotFound(w, r)
			return
		}
		select {
		case s.cb <- "http://" + r.Host + r.URL.RequestURI():
		default:
		}
		w.Write([]byte("Authorization received. You can close this window."))
	})}
	go s.srv.Serve(ln)
	return s, nil
}
func (s *loopbackSession) RedirectURI() string { return "http://" + s.ln.Addr().String() + "/callback" }
func (s *loopbackSession) Close()              { s.srv.Close() }
func (s *loopbackSession) Wait(ctx context.Context, p Pending) (string, error) {
	opened := make(chan error, 1)
	go func() { opened <- s.open(p.URL) }() // the opener may block (tests follow redirects synchronously)
	select {
	case u := <-s.cb:
		return u, nil
	case err := <-opened: // a failed opener must not leave us waiting 5 minutes for a callback that cannot come
		if err != nil {
			return "", fmt.Errorf("open authorization URL: %w", err)
		}
		select {
		case u := <-s.cb:
			return u, nil
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(5 * time.Minute):
			return "", errors.New("authorization timed out")
		}
	case <-ctx.Done():
		return "", ctx.Err()
	case <-time.After(5 * time.Minute):
		return "", errors.New("authorization timed out")
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

// OAuth is the client-side auth engine; attach it as Client.Auth.
type OAuth struct {
	Authorizer      Authorizer // nil = LoopbackAuthorizer{} (system browser + loopback callback)
	ClientID        string     // pre-registered client_id for any issuer not in ClientIDs
	ClientSecret    string     // optional: makes the pre-registered client confidential. Never logged.
	TokenAuthMethod string     // "none" | "client_secret_basic" | "client_secret_post"; "" = basic if ClientSecret else none
	// OmitRefreshResource drops the RFC 8707 `resource` from refresh_token requests (authorize + code
	// exchange still send it). Entra rejects it on refresh (python-sdk #2578). Default: sent everywhere.
	OmitRefreshResource bool
	Store               TokenStore        // nil = in-memory
	ClientIDs           map[string]string // issuer -> pre-registered client_id (bound to that issuer)
	ClientMetadataURL   string            // CIMD client_id (HTTPS URL), used when the AS advertises support
	HTTP                *http.Client      // for PRM / AS / token calls; nil = defaultHTTP() (timeout, trusts in-process DevAuthServers)
	AllowHTTP           bool              // allow non-HTTPS AS endpoints (dev only; spec requires HTTPS)

	mu     sync.Mutex
	authMu sync.Mutex        // serialises refresh + interactive authorization (one login / one refresh at a time)
	issuer map[string]string // resource -> discovered issuer (only then do we send a stored token)
	flows  map[string]*authFlow
}

type authFlow struct {
	resource, issuer, clientID, redirect, verifier, state, tokenEndpoint string
	issSupported                                                         bool
	scopes                                                               []string
	expires                                                              time.Time
	retry                                                                func() (obj, error)
}

func (o *OAuth) hc() *http.Client {
	if o.HTTP != nil {
		return o.HTTP
	}
	return defaultHTTP()
}

func (o *OAuth) store() TokenStore {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.Store == nil {
		o.Store = &MemoryTokenStore{}
	}
	return o.Store
}

// bearer returns the token to send for resource, refreshing if expired. "" = send none.
func (o *OAuth) bearer(ctx context.Context, resource string) string {
	o.mu.Lock()
	iss := o.issuer[resource]
	o.mu.Unlock()
	t, ok := o.store().Load(resource)
	if !ok || iss == "" || t.Issuer != iss { // only tokens from this server's AS (spec: Token Handling)
		return ""
	}
	if expired(t) {
		o.authMu.Lock() // one refresh for N concurrent callers (refresh tokens rotate: a 2nd refresh would fail)
		defer o.authMu.Unlock()
		if t, ok = o.store().Load(resource); !ok {
			return ""
		}
		if expired(t) && o.refresh(ctx, t) != nil {
			return ""
		}
		t, _ = o.store().Load(resource)
	}
	return t.AccessToken
}

func expired(t *Token) bool {
	return !t.Expiry.IsZero() && time.Now().After(t.Expiry.Add(-10*time.Second))
}

func (o *OAuth) refresh(ctx context.Context, t *Token) error {
	if t.RefreshToken == "" {
		return errors.New("no refresh token")
	}
	md, err := discoverAS(ctx, o.hc(), t.Issuer)
	if err != nil {
		return err
	}
	ep, _ := md["token_endpoint"].(string)
	form := url.Values{"grant_type": {"refresh_token"}, "refresh_token": {t.RefreshToken}, "client_id": {t.ClientID}}
	if !o.OmitRefreshResource {
		form.Set("resource", t.Resource)
	}
	nt, err := o.tokenRequest(ctx, ep, form, t.Issuer, t.Resource, t.ClientID, t.Scopes)
	if err != nil {
		o.store().Delete(t.Resource)
		return err
	}
	if nt.RefreshToken == "" {
		nt.RefreshToken = t.RefreshToken
	}
	o.store().Save(t.Resource, nt)
	return nil
}

// challenge handles a 401/403. nil = retry the request now; *PendingError = paused.
// sent is the access token the failed request carried ("" = none).
func (o *OAuth) challenge(ctx context.Context, resource string, status int, www, sent string, retry func() (obj, error)) error {
	params := parseChallenge(www)
	o.authMu.Lock() // N concurrent 401s -> one authorization, the rest retry with its token
	defer o.authMu.Unlock()
	cur, _ := o.store().Load(resource)
	o.mu.Lock()
	known := o.issuer[resource]
	o.mu.Unlock()
	if cur != nil && known != "" && cur.Issuer == known && cur.AccessToken != sent && !expired(cur) {
		return nil // someone else refreshed/authorized while we waited
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
	issuer, _ := servers[0].(string) // RFC 9728 §7.6: client's choice; we take the first
	if !o.AllowHTTP && !strings.HasPrefix(issuer, "https://") {
		return errors.New("authorization server must use https")
	}
	md, err := discoverAS(ctx, o.hc(), issuer)
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
	if status == http.StatusUnauthorized && cur != nil && cur.Issuer == issuer && params["error"] == "" {
		return nil // first request after restart: we now know the issuer, so the stored token may be sent
	}
	// Scope selection: challenge scope, else scopes_supported; on step-up union with previous.
	scopes := strings.Fields(params["scope"])
	if len(scopes) == 0 {
		for _, s := range asSlice(prm["scopes_supported"]) {
			scopes = append(scopes, s)
		}
	}
	if cur != nil && status == http.StatusForbidden {
		for _, s := range cur.Scopes {
			if !slices.Contains(scopes, s) {
				scopes = append(scopes, s)
			}
		}
	}
	clientID := o.ClientIDs[issuer]
	if clientID == "" {
		clientID = o.ClientID
	}
	if clientID == "" && o.ClientMetadataURL != "" && md["client_id_metadata_document_supported"] == true {
		clientID = o.ClientMetadataURL
	}
	if clientID == "" { // no DCR, ever: the user registers an app once and configures its id
		why := "it does not support Client ID Metadata Documents"
		if md["client_id_metadata_document_supported"] == true {
			why = "OAuth.ClientMetadataURL is not set"
		}
		return fmt.Errorf("no client identity for %s: %s and no client id is configured — register an app with %s and set OAuth.ClientID (dynamic client registration is not supported)", issuer, why, issuer)
	}
	authEP, _ := md["authorization_endpoint"].(string)
	tokenEP, _ := md["token_endpoint"].(string)
	az := o.Authorizer
	if az == nil {
		az = LoopbackAuthorizer{}
	}
	sess, err := az.Begin(ctx)
	if err != nil {
		return err
	}
	f := &authFlow{resource: resource, issuer: issuer, clientID: clientID, redirect: sess.RedirectURI(),
		verifier: randStr(32), state: randStr(16), tokenEndpoint: tokenEP, scopes: scopes, retry: retry,
		issSupported: md["authorization_response_iss_parameter_supported"] == true, expires: time.Now().Add(10 * time.Minute)}
	ch := sha256.Sum256([]byte(f.verifier))
	q := url.Values{"response_type": {"code"}, "client_id": {clientID}, "redirect_uri": {f.redirect},
		"state": {f.state}, "code_challenge": {base64.RawURLEncoding.EncodeToString(ch[:])},
		"code_challenge_method": {"S256"}, "resource": {resource}}
	if len(scopes) > 0 {
		q.Set("scope", strings.Join(scopes, " "))
	}
	p := Pending{ID: "auth-" + randStr(12), Kind: "authorization", URL: authEP + "?" + q.Encode(),
		Prompt: "Authorize access to " + resource, ExpiresAt: f.expires.UTC().Format(time.RFC3339)}
	cb, err := sess.Wait(ctx, p)
	if errors.Is(err, ErrPause) {
		o.mu.Lock()
		if o.flows == nil {
			o.flows = map[string]*authFlow{}
		}
		for id, old := range o.flows { // drop abandoned pendings so they cannot pile up
			if time.Now().After(old.expires) {
				delete(o.flows, id)
			}
		}
		o.flows[p.ID] = f
		o.mu.Unlock()
		return &PendingError{Pending: p}
	}
	sess.Close()
	if err != nil {
		return err
	}
	return o.complete(ctx, f, cb)
}

func (o *OAuth) setRetry(id string, f func() (obj, error)) {
	o.mu.Lock()
	if fl := o.flows[id]; fl != nil {
		fl.retry = f
	}
	o.mu.Unlock()
}

// Resume finishes a paused authorization and re-runs the original operation.
func (o *OAuth) Resume(ctx context.Context, id, callbackURL string) (obj, error) {
	o.mu.Lock()
	f := o.flows[id]
	delete(o.flows, id)
	o.mu.Unlock()
	if f == nil {
		return nil, fmt.Errorf("unknown pending authorization %q", id)
	}
	if time.Now().After(f.expires) {
		return nil, errors.New("pending authorization expired")
	}
	if err := o.complete(ctx, f, callbackURL); err != nil {
		return nil, err
	}
	return f.retry()
}

// ResumeCallback is Resume for web apps: the pending is found by the callback's state,
// so the handler needs nothing but the URL the browser came back on.
func (o *OAuth) ResumeCallback(ctx context.Context, callbackURL string) (obj, error) {
	u, err := url.Parse(callbackURL)
	if err != nil {
		return nil, err
	}
	st := u.Query().Get("state")
	o.mu.Lock()
	id := ""
	for k, f := range o.flows {
		if st != "" && f.state == st {
			id = k
		}
	}
	o.mu.Unlock()
	return o.Resume(ctx, id, callbackURL)
}

func (o *OAuth) complete(ctx context.Context, f *authFlow, callbackURL string) error {
	u, err := url.Parse(callbackURL)
	if err != nil {
		return err
	}
	q := u.Query()
	// RFC 9207 §2.4 table (spec: Authorization Response Validation) — before anything else, error responses included.
	if iss, has := q["iss"]; has {
		if iss[0] != f.issuer { // simple string comparison, no normalization
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
	o.store().Save(f.resource, t)
	return nil
}

func (o *OAuth) tokenRequest(ctx context.Context, ep string, form url.Values, issuer, resource, clientID string, scopes []string) (*Token, error) {
	method := o.TokenAuthMethod
	if clientID == o.ClientMetadataURL || o.ClientSecret == "" {
		method = "none" // CIMD clients and secretless pre-registered clients are public
	} else if method == "" {
		method = "client_secret_basic"
	}
	if method == "client_secret_post" {
		form.Set("client_secret", o.ClientSecret)
	}
	if method == "client_secret_basic" {
		form.Del("client_id") // RFC 6749 §2.3.1: authenticate with one method only
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, ep, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json") // GitHub answers form-encoded without it
	if method == "client_secret_basic" {
		req.SetBasicAuth(url.QueryEscape(clientID), url.QueryEscape(o.ClientSecret))
	}
	resp, err := o.hc().Do(req)
	if err != nil {
		return nil, err
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
		// RFC 6749 §5.1 mandates JSON, but some ASes (GitHub) still default to form encoding.
		if v, perr := url.ParseQuery(string(body)); perr == nil {
			tr.AccessToken, tr.RefreshToken, tr.Scope, tr.Error = v.Get("access_token"), v.Get("refresh_token"), v.Get("scope"), v.Get("error")
			tr.ExpiresIn, _ = strconv.Atoi(v.Get("expires_in"))
			err = nil
		}
	}
	if err != nil || resp.StatusCode != 200 || tr.AccessToken == "" {
		return nil, fmt.Errorf("token endpoint: HTTP %d %s", resp.StatusCode, tr.Error) // never include the body
	}
	t := &Token{AccessToken: tr.AccessToken, RefreshToken: tr.RefreshToken, Issuer: issuer, Resource: resource,
		ClientID: clientID, Scopes: scopes}
	if tr.Scope != "" {
		t.Scopes = strings.Fields(tr.Scope)
	}
	if tr.ExpiresIn > 0 {
		t.Expiry = time.Now().Add(time.Duration(tr.ExpiresIn) * time.Second)
	}
	return t, nil
}

// fetchPRM: header URL if present, else path-inserted well-known, else root (spec: PRM Discovery Requirements).
func (o *OAuth) fetchPRM(ctx context.Context, resource, hinted string) (obj, error) {
	cands := []string{hinted}
	if hinted == "" {
		u, err := url.Parse(resource)
		if err != nil {
			return nil, err
		}
		root := u.Scheme + "://" + u.Host + prmPath
		cands = nil
		if p := strings.TrimSuffix(u.Path, "/"); p != "" {
			cands = append(cands, root+p)
		}
		cands = append(cands, root)
	}
	var last error
	for _, c := range cands {
		var m obj
		if last = getJSON(ctx, o.hc(), c, &m); last == nil {
			if m["resource"] != resource { // RFC 9728 §3.3
				return nil, fmt.Errorf("PRM resource %v does not match %s", m["resource"], resource)
			}
			return m, nil
		}
	}
	return nil, fmt.Errorf("protected resource metadata: %w", last)
}

// discoverAS tries RFC 8414 then OIDC endpoints in the spec's order and validates issuer equality.
func discoverAS(ctx context.Context, hc *http.Client, issuer string) (obj, error) {
	u, err := url.Parse(issuer)
	if err != nil {
		return nil, err
	}
	base := u.Scheme + "://" + u.Host
	p := strings.TrimSuffix(u.Path, "/")
	var cands []string
	if p != "" {
		cands = []string{base + "/.well-known/oauth-authorization-server" + p,
			base + "/.well-known/openid-configuration" + p, base + p + "/.well-known/openid-configuration"}
	} else {
		cands = []string{base + "/.well-known/oauth-authorization-server", base + "/.well-known/openid-configuration"}
	}
	for _, c := range cands {
		var m obj
		if getJSON(ctx, hc, c, &m) == nil {
			if m["issuer"] != issuer {
				return nil, fmt.Errorf("AS metadata issuer %v != %s", m["issuer"], issuer)
			}
			return m, nil
		}
	}
	return nil, fmt.Errorf("no authorization server metadata for %s", issuer)
}

func getJSON(ctx context.Context, hc *http.Client, u string, v any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("GET %s: HTTP %d", u, resp.StatusCode)
	}
	return json.NewDecoder(resp.Body).Decode(v)
}

// parseChallenge parses `Bearer k="v", k2=v2` (RFC 6750 §3 / RFC 9110 auth-param).
func parseChallenge(h string) map[string]string {
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

func asSlice(v any) []string {
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

func randStr(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}
