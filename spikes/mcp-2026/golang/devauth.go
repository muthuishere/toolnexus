package main

// DevAuthServer — DEV/TEST ONLY. NEVER USE IN PRODUCTION.
// A one-line OAuth 2.1 authorization server that auto-approves every request as "dev-user"
// (no login, no consent screen). It exists so local dev and tests can exercise the real
// MCP auth flow: RFC 8414 metadata, JWKS, auth code + PKCE S256, resource (RFC 8707),
// iss in the response (RFC 9207), refresh-token rotation, pre-registered + CIMD clients.
//
//	as := StartDevAuthServer()              // https://127.0.0.1:<port>/dev
//	srv.Auth = &ServerAuth{Issuer: as.Issuer, HTTP: as.Client(), Scopes: ...}

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"time"
)

type DevAuthServer struct {
	Issuer    string
	Clients   map[string][]string // pre-registered client_id -> redirect URIs
	Secrets   map[string]string   // confidential clients: client_id -> secret (basic or post)
	AccessTTL time.Duration       // default 1h
	FetchHTTP *http.Client        // fetches CIMD documents
	// Test knobs: misbehave on purpose.
	IssOverride           string // non-empty: send this iss instead of the real one
	NoCIMD                bool   // don't advertise client_id_metadata_document_supported
	RejectRefreshResource bool   // Entra-like: invalid_target if a refresh_token grant carries resource
	LastTokenForm         url.Values
	// Counters (read after a test).
	Authorizations, Refreshes int
	LastAuthorizeQuery        url.Values

	srv   *httptest.Server
	key   *rsa.PrivateKey
	mu    sync.Mutex
	codes map[string]devCode
	rts   map[string]devCode
}

type devCode struct {
	clientID, redirect, challenge, resource, scope string
}

// StartDevAuthServer starts a TLS dev AS on 127.0.0.1 with issuer https://127.0.0.1:<port>/dev.
func StartDevAuthServer() *DevAuthServer {
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	d := &DevAuthServer{key: key, Clients: map[string][]string{}, codes: map[string]devCode{}, rts: map[string]devCode{}}
	d.srv = httptest.NewTLSServer(d)
	d.Issuer = d.srv.URL + "/dev"
	d.FetchHTTP = d.srv.Client()
	trustDevCert(d.srv.Certificate()) // dev only: default clients in this process now trust it
	return d
}

// Approve is a headless "browser" for dev/tests: it visits an authorization URL and follows the
// redirect back to the callback (use as LoopbackAuthorizer{Open: as.Approve}).
func (d *DevAuthServer) Approve(u string) error {
	resp, err := d.srv.Client().Get(u)
	if err != nil {
		return err
	}
	return resp.Body.Close()
}

// Stats returns the counters under the lock (safe while the server is running).
func (d *DevAuthServer) Stats() (authorizations, refreshes int) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.Authorizations, d.Refreshes
}

func (d *DevAuthServer) Close()               { d.srv.Close() }
func (d *DevAuthServer) Client() *http.Client { return d.srv.Client() }

// Mint signs an access token directly (tests: forge wrong audience / expired tokens).
func (d *DevAuthServer) Mint(claims map[string]any) string {
	h, _ := json.Marshal(map[string]string{"alg": "RS256", "typ": "JWT", "kid": "dev"})
	c, _ := json.Marshal(claims)
	in := base64.RawURLEncoding.EncodeToString(h) + "." + base64.RawURLEncoding.EncodeToString(c)
	sum := sha256.Sum256([]byte(in))
	sig, _ := rsa.SignPKCS1v15(rand.Reader, d.key, crypto.SHA256, sum[:])
	return in + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func (d *DevAuthServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	js := func(status int, v any) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		json.NewEncoder(w).Encode(v)
	}
	switch r.URL.Path {
	case "/.well-known/oauth-authorization-server/dev":
		js(200, obj{"issuer": d.Issuer, "authorization_endpoint": d.Issuer + "/authorize", "token_endpoint": d.Issuer + "/token",
			"jwks_uri": d.Issuer + "/jwks", "response_types_supported": []string{"code"},
			"grant_types_supported":            []string{"authorization_code", "refresh_token"},
			"code_challenge_methods_supported": []string{"S256"}, "token_endpoint_auth_methods_supported": []string{"none", "client_secret_basic", "client_secret_post"},
			"authorization_response_iss_parameter_supported": true, "client_id_metadata_document_supported": !d.NoCIMD})
	case "/dev/jwks":
		js(200, obj{"keys": []obj{{"kty": "RSA", "kid": "dev", "alg": "RS256", "use": "sig",
			"n": base64.RawURLEncoding.EncodeToString(d.key.N.Bytes()),
			"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(d.key.E)).Bytes())}}})
	case "/dev/authorize":
		d.authorize(w, r)
	case "/dev/token":
		d.token(w, r, js)
	default:
		http.NotFound(w, r)
	}
}

func (d *DevAuthServer) authorize(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	d.mu.Lock()
	d.Authorizations++
	d.LastAuthorizeQuery = q
	d.mu.Unlock()
	cid, redirect := q.Get("client_id"), q.Get("redirect_uri")
	if !d.redirectAllowed(r.Context(), cid, redirect) {
		http.Error(w, "invalid_client or redirect_uri", 400) // never redirect to an unverified URI
		return
	}
	fail := func(e string) {
		http.Redirect(w, r, redirect+"?"+url.Values{"error": {e}, "state": {q.Get("state")}, "iss": {d.Issuer}}.Encode(), 302)
	}
	if q.Get("response_type") != "code" || q.Get("code_challenge_method") != "S256" || q.Get("code_challenge") == "" {
		fail("invalid_request")
		return
	}
	if q.Get("resource") == "" {
		fail("invalid_target")
		return
	}
	code := randStr(16)
	d.mu.Lock()
	d.codes[code] = devCode{cid, redirect, q.Get("code_challenge"), q.Get("resource"), q.Get("scope")}
	d.mu.Unlock()
	iss := d.Issuer
	if d.IssOverride != "" {
		iss = d.IssOverride
	}
	http.Redirect(w, r, redirect+"?"+url.Values{"code": {code}, "state": {q.Get("state")}, "iss": {iss}}.Encode(), 302)
}

// redirectAllowed: exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3).
func (d *DevAuthServer) redirectAllowed(ctx context.Context, cid, redirect string) bool {
	allowed := d.Clients[cid]
	if strings.HasPrefix(cid, "https://") { // Client ID Metadata Document
		var doc struct {
			ClientID     string   `json:"client_id"`
			ClientName   string   `json:"client_name"`
			RedirectURIs []string `json:"redirect_uris"`
		}
		if getJSON(ctx, d.FetchHTTP, cid, &doc) != nil || doc.ClientID != cid || doc.ClientName == "" {
			return false
		}
		allowed = doc.RedirectURIs
	}
	ru, err := url.Parse(redirect)
	if err != nil {
		return false
	}
	for _, a := range allowed {
		au, err := url.Parse(a)
		if err != nil {
			continue
		}
		if a == redirect {
			return true
		}
		if ip := net.ParseIP(ru.Hostname()); ip != nil && ip.IsLoopback() && au.Hostname() == ru.Hostname() &&
			au.Scheme == ru.Scheme && au.Path == ru.Path {
			return true
		}
	}
	return false
}

func (d *DevAuthServer) token(w http.ResponseWriter, r *http.Request, js func(int, any)) {
	r.ParseForm()
	f := r.PostForm
	bad := func(e string) { js(400, obj{"error": e}) }
	d.mu.Lock()
	defer d.mu.Unlock()
	d.LastTokenForm = f
	if id, sec, ok := r.BasicAuth(); ok {
		id, _ = url.QueryUnescape(id)
		sec, _ = url.QueryUnescape(sec)
		if f.Get("client_id") != "" && f.Get("client_id") != id || d.Secrets[id] == "" || d.Secrets[id] != sec {
			js(401, obj{"error": "invalid_client"})
			return
		}
		f.Set("client_id", id)
	} else if want := d.Secrets[f.Get("client_id")]; want != "" && f.Get("client_secret") != want {
		js(401, obj{"error": "invalid_client"})
		return
	}
	var c devCode
	switch f.Get("grant_type") {
	case "authorization_code":
		var ok bool
		if c, ok = d.codes[f.Get("code")]; !ok {
			bad("invalid_grant")
			return
		}
		delete(d.codes, f.Get("code")) // single use
		sum := sha256.Sum256([]byte(f.Get("code_verifier")))
		if base64.RawURLEncoding.EncodeToString(sum[:]) != c.challenge { // PKCE verified here
			bad("invalid_grant")
			return
		}
		if f.Get("client_id") != c.clientID || f.Get("redirect_uri") != c.redirect || f.Get("resource") != c.resource {
			bad("invalid_grant")
			return
		}
	case "refresh_token":
		var ok bool
		if c, ok = d.rts[f.Get("refresh_token")]; !ok || f.Get("client_id") != c.clientID {
			bad("invalid_grant")
			return
		}
		delete(d.rts, f.Get("refresh_token")) // rotation (required for public clients)
		if res := f.Get("resource"); res != "" && (res != c.resource || d.RejectRefreshResource) {
			bad("invalid_target")
			return
		}
		d.Refreshes++
	default:
		bad("unsupported_grant_type")
		return
	}
	ttl := d.AccessTTL
	if ttl == 0 {
		ttl = time.Hour
	}
	rt := randStr(24)
	d.rts[rt] = c
	at := d.Mint(map[string]any{"iss": d.Issuer, "sub": "dev-user", "aud": c.resource, "scope": c.scope,
		"client_id": c.clientID, "jti": randStr(8), "exp": time.Now().Add(ttl).Unix(), "iat": time.Now().Unix()})
	js(200, obj{"access_token": at, "token_type": "Bearer", "expires_in": int(ttl.Seconds()), "refresh_token": rt, "scope": c.scope})
}
