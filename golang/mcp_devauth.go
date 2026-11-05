// MCPDevAuthServer — DEVELOPMENT AND TESTS ONLY. NEVER USE IN PRODUCTION.
//
// An in-process OAuth 2.1 authorization server that auto-approves every
// request as "dev-user" (no login, no consent): RFC 8414 metadata, JWKS,
// authorization code + PKCE S256, resource indicators (RFC 8707), `iss` in the
// response (RFC 9207), refresh-token rotation, pre-registered clients and
// Client ID Metadata Documents. No dynamic client registration. Plain HTTP on
// 127.0.0.1, so a toolnexus client needs auth.allowHttp:true against it.
//
//	as, _ := toolnexus.StartMCPDevAuthServer(toolnexus.MCPDevAuthOptions{Clients: map[string][]string{"my-app": {"http://127.0.0.1/callback"}}})
//	defer as.Close()
//	auth := &toolnexus.MCPServerAuth{Issuer: as.Issuer, Scopes: map[string][]string{"*": {"mcp"}}}

package toolnexus

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// MCPDevAuthOptions configures StartMCPDevAuthServer.
type MCPDevAuthOptions struct {
	// Clients: pre-registered client_id → allowed redirect URIs (a loopback
	// redirect matches on any port, RFC 8252 §7.3).
	Clients map[string][]string
	// Secrets: confidential clients, client_id → secret (basic or post).
	Secrets map[string]string
	// AccessTTL of issued access tokens. Default 1 h.
	AccessTTL time.Duration
	// NoCIMD stops advertising client_id_metadata_document_supported.
	NoCIMD bool
	// RejectRefreshResource answers invalid_target when a refresh carries
	// `resource` (an Entra-like provider, for client tests).
	RejectRefreshResource bool
	// MetadataClient fetches Client ID Metadata Documents. Default: a 10 s client.
	MetadataClient *http.Client
}

// MCPDevAuthServer is the running dev authorization server.
type MCPDevAuthServer struct {
	Issuer string // http://127.0.0.1:<port>

	opts  MCPDevAuthOptions
	key   *rsa.PrivateKey
	ln    net.Listener
	srv   *http.Server
	mu    sync.Mutex
	codes map[string]mcpDevGrant
	rts   map[string]mcpDevGrant
	auths int
}

type mcpDevGrant struct{ clientID, redirect, challenge, resource, scope string }

// StartMCPDevAuthServer starts the dev AS on 127.0.0.1:<random>.
func StartMCPDevAuthServer(opts MCPDevAuthOptions) (*MCPDevAuthServer, error) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	if opts.MetadataClient == nil {
		opts.MetadataClient = &http.Client{Timeout: 10 * time.Second}
	}
	d := &MCPDevAuthServer{Issuer: "http://" + ln.Addr().String(), opts: opts, key: key, ln: ln,
		codes: map[string]mcpDevGrant{}, rts: map[string]mcpDevGrant{}}
	d.srv = &http.Server{Handler: d, ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = d.srv.Serve(ln) }()
	return d, nil
}

// Close stops the server.
func (d *MCPDevAuthServer) Close() { _ = d.srv.Close() }

// Authorizations is how many /authorize requests were served.
func (d *MCPDevAuthServer) Authorizations() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.auths
}

// Mint signs an access token with the server's key (tests: forge expired or
// wrong-audience tokens).
func (d *MCPDevAuthServer) Mint(claims map[string]any) string {
	h, _ := json.Marshal(map[string]string{"alg": "RS256", "typ": "JWT", "kid": "dev"})
	c, _ := json.Marshal(claims)
	in := base64.RawURLEncoding.EncodeToString(h) + "." + base64.RawURLEncoding.EncodeToString(c)
	sum := sha256.Sum256([]byte(in))
	sig, _ := rsa.SignPKCS1v15(rand.Reader, d.key, crypto.SHA256, sum[:])
	return in + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func mcpDevRand(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func (d *MCPDevAuthServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	js := func(status int, v any) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(v)
	}
	switch r.URL.Path {
	case "/.well-known/oauth-authorization-server":
		js(200, map[string]any{"issuer": d.Issuer, "authorization_endpoint": d.Issuer + "/authorize",
			"token_endpoint": d.Issuer + "/token", "jwks_uri": d.Issuer + "/jwks",
			"response_types_supported": []string{"code"}, "grant_types_supported": []string{"authorization_code", "refresh_token"},
			"code_challenge_methods_supported":               []string{"S256"},
			"token_endpoint_auth_methods_supported":          []string{"none", "client_secret_basic", "client_secret_post"},
			"authorization_response_iss_parameter_supported": true,
			"client_id_metadata_document_supported":          !d.opts.NoCIMD})
	case "/jwks":
		js(200, map[string]any{"keys": []map[string]any{{"kty": "RSA", "kid": "dev", "alg": "RS256", "use": "sig",
			"n": base64.RawURLEncoding.EncodeToString(d.key.N.Bytes()),
			"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(d.key.E)).Bytes())}}})
	case "/authorize":
		d.authorize(w, r)
	case "/token":
		d.token(w, r, js)
	default:
		http.NotFound(w, r)
	}
}

func (d *MCPDevAuthServer) authorize(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	d.mu.Lock()
	d.auths++
	d.mu.Unlock()
	cid, redirect := q.Get("client_id"), q.Get("redirect_uri")
	if !d.redirectAllowed(r, cid, redirect) {
		http.Error(w, "invalid_client or redirect_uri", http.StatusBadRequest) // never redirect to an unverified URI
		return
	}
	back := func(v url.Values) {
		v.Set("state", q.Get("state"))
		v.Set("iss", d.Issuer)
		sep := "?"
		if strings.Contains(redirect, "?") {
			sep = "&"
		}
		http.Redirect(w, r, redirect+sep+v.Encode(), http.StatusFound)
	}
	if q.Get("response_type") != "code" || q.Get("code_challenge_method") != "S256" || q.Get("code_challenge") == "" {
		back(url.Values{"error": {"invalid_request"}})
		return
	}
	if q.Get("resource") == "" {
		back(url.Values{"error": {"invalid_target"}})
		return
	}
	code := mcpDevRand(16)
	d.mu.Lock()
	d.codes[code] = mcpDevGrant{cid, redirect, q.Get("code_challenge"), q.Get("resource"), q.Get("scope")}
	d.mu.Unlock()
	back(url.Values{"code": {code}})
}

func (d *MCPDevAuthServer) redirectAllowed(r *http.Request, cid, redirect string) bool {
	allowed := d.opts.Clients[cid]
	if strings.HasPrefix(cid, "https://") || strings.HasPrefix(cid, "http://") { // Client ID Metadata Document
		if d.opts.NoCIMD {
			return false
		}
		var doc struct {
			ClientID     string   `json:"client_id"`
			ClientName   string   `json:"client_name"`
			RedirectURIs []string `json:"redirect_uris"`
		}
		if mcpGetJSON(r.Context(), d.opts.MetadataClient, cid, &doc) != nil || doc.ClientID != cid || doc.ClientName == "" {
			return false
		}
		allowed = doc.RedirectURIs
	}
	ru, err := url.Parse(redirect)
	if err != nil {
		return false
	}
	for _, a := range allowed {
		if a == redirect {
			return true
		}
		au, err := url.Parse(a)
		if err != nil {
			continue
		}
		if ip := net.ParseIP(ru.Hostname()); ip != nil && ip.IsLoopback() && au.Hostname() == ru.Hostname() &&
			au.Scheme == ru.Scheme && au.Path == ru.Path {
			return true
		}
	}
	return false
}

func (d *MCPDevAuthServer) token(w http.ResponseWriter, r *http.Request, js func(int, any)) {
	_ = r.ParseForm()
	f := r.PostForm
	bad := func(e string) { js(http.StatusBadRequest, map[string]any{"error": e}) }
	d.mu.Lock()
	defer d.mu.Unlock()
	if id, sec, ok := r.BasicAuth(); ok {
		id, _ = url.QueryUnescape(id)
		sec, _ = url.QueryUnescape(sec)
		if (f.Get("client_id") != "" && f.Get("client_id") != id) || d.opts.Secrets[id] == "" || d.opts.Secrets[id] != sec {
			js(http.StatusUnauthorized, map[string]any{"error": "invalid_client"})
			return
		}
		f.Set("client_id", id)
	} else if want := d.opts.Secrets[f.Get("client_id")]; want != "" && f.Get("client_secret") != want {
		js(http.StatusUnauthorized, map[string]any{"error": "invalid_client"})
		return
	}
	var g mcpDevGrant
	switch f.Get("grant_type") {
	case "authorization_code":
		var ok bool
		if g, ok = d.codes[f.Get("code")]; !ok {
			bad("invalid_grant")
			return
		}
		delete(d.codes, f.Get("code"))
		sum := sha256.Sum256([]byte(f.Get("code_verifier")))
		if base64.RawURLEncoding.EncodeToString(sum[:]) != g.challenge ||
			f.Get("client_id") != g.clientID || f.Get("redirect_uri") != g.redirect || f.Get("resource") != g.resource {
			bad("invalid_grant")
			return
		}
	case "refresh_token":
		var ok bool
		if g, ok = d.rts[f.Get("refresh_token")]; !ok || f.Get("client_id") != g.clientID {
			bad("invalid_grant")
			return
		}
		delete(d.rts, f.Get("refresh_token"))
		if res := f.Get("resource"); res != "" && (res != g.resource || d.opts.RejectRefreshResource) {
			bad("invalid_target")
			return
		}
	default:
		bad("unsupported_grant_type")
		return
	}
	ttl := d.opts.AccessTTL
	if ttl <= 0 {
		ttl = time.Hour
	}
	rt := mcpDevRand(24)
	d.rts[rt] = g
	now := time.Now()
	at := d.Mint(map[string]any{"iss": d.Issuer, "sub": "dev-user", "aud": g.resource, "scope": g.scope,
		"client_id": g.clientID, "jti": mcpDevRand(8), "exp": now.Add(ttl).Unix(), "iat": now.Unix()})
	js(http.StatusOK, map[string]any{"access_token": at, "token_type": "Bearer", "expires_in": int(ttl.Seconds()),
		"refresh_token": rt, "scope": g.scope})
}
