// MCP server authorization (SPEC §2A.2): an MCPHandler as an OAuth 2.1
// protected resource. MCPServerAuth is a plain helper that produces the
// handler's Authenticate hook and the RFC 9728 metadata handler; the host
// mounts both. Identity, tenants and token storage stay the host's.
//
//	auth := &toolnexus.MCPServerAuth{Issuer: "https://login.example", Scopes: map[string][]string{"*": {"mcp"}}}
//	mux.Handle("/mcp", tk.MCPHandler(toolnexus.MCPHandlerConfig{
//	    ProtocolVersions: []string{"2026-07-28"}, Authenticate: auth.Authenticate}))
//	mux.Handle("/.well-known/oauth-protected-resource/", auth.Metadata())
//	mux.Handle("/.well-known/oauth-protected-resource", auth.Metadata())

package toolnexus

import (
	"context"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"
	"time"
)

// MCPPrincipal is the verified caller. MCPServerAuth.Authenticate returns it,
// so hooks and tools read it with IdentityFrom(ctx).(MCPPrincipal).
type MCPPrincipal struct {
	Subject string
	Scopes  []string
	Claims  map[string]any
}

// MCPServerAuth protects an MCPHandler with bearer tokens.
type MCPServerAuth struct {
	// Issuer is the authorization server's issuer identifier (advertised in
	// the protected resource metadata, and the JWT `iss` the default verifier
	// requires).
	Issuer string
	// Audience is the canonical resource URI (PRM `resource`, the JWT `aud`).
	// "" ⇒ derived per request: <scheme>://<host><ResourcePath>.
	Audience string
	// ResourcePath is where the handler is mounted. Default "/mcp".
	ResourcePath string
	// Scopes: "*" is required for every request (and advertised as
	// scopes_supported); a tool name adds scopes for that tools/call. Exact
	// match, no hierarchy.
	Scopes map[string][]string
	// Verify replaces the default RS256/JWKS verifier (opaque tokens,
	// introspection). It receives the bearer token and the expected audience.
	Verify func(ctx context.Context, token, audience string) (MCPPrincipal, error)
	// HTTPClient fetches AS metadata and JWKS. nil ⇒ a 10 s client.
	HTTPClient *http.Client

	mu      sync.Mutex
	keys    map[string]*rsa.PublicKey
	fetched time.Time
}

// mcpPRMPath is declared in mcp_oauth.go (shared with the client).

func (a *MCPServerAuth) path() string {
	if a.ResourcePath == "" {
		return "/mcp"
	}
	return a.ResourcePath
}

func mcpOrigin(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

func (a *MCPServerAuth) audience(r *http.Request) string {
	if a.Audience != "" {
		return a.Audience
	}
	return mcpOrigin(r) + a.path()
}

// MetadataURL is the RFC 9728 metadata URL the 401 challenge names.
func (a *MCPServerAuth) MetadataURL(r *http.Request) string {
	if a.Audience != "" {
		if u, err := url.Parse(a.Audience); err == nil && u.Host != "" {
			return u.Scheme + "://" + u.Host + mcpPRMPath + u.Path
		}
	}
	return mcpOrigin(r) + mcpPRMPath + a.path()
}

// Metadata serves the protected resource metadata (RFC 9728) for GET at
// /.well-known/oauth-protected-resource and …/oauth-protected-resource<ResourcePath>.
func (a *MCPServerAuth) Metadata() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || (r.URL.Path != mcpPRMPath && r.URL.Path != mcpPRMPath+a.path()) {
			http.NotFound(w, r)
			return
		}
		scopes := a.Scopes["*"]
		if scopes == nil {
			scopes = []string{}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"resource": a.audience(r),
			"authorization_servers": []string{a.Issuer}, "scopes_supported": scopes,
			"bearer_methods_supported": []string{"header"}})
	})
}

func (a *MCPServerAuth) challenge(r *http.Request, status int, scopes []string, errCode string) *MCPAuthError {
	v := fmt.Sprintf(`Bearer resource_metadata="%s"`, a.MetadataURL(r))
	if len(scopes) > 0 {
		v += fmt.Sprintf(`, scope="%s"`, strings.Join(scopes, " "))
	}
	if errCode != "" {
		v += fmt.Sprintf(`, error="%s"`, errCode)
	}
	return &MCPAuthError{Status: status, Challenge: v, Reason: errCode}
}

// Authenticate is an MCPHandlerConfig.Authenticate hook. Missing token ⇒ 401;
// invalid/expired/wrong audience ⇒ 401 error="invalid_token"; a missing scope
// ⇒ 403 error="insufficient_scope" naming the full needed set. Per-tool scopes
// are read from the validated Mcp-Method / Mcp-Name headers, so they apply on
// the 2026-07-28 wire; older wires check Scopes["*"] only. The token and the
// failure reason are never echoed.
func (a *MCPServerAuth) Authenticate(r *http.Request) (any, error) {
	tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || strings.TrimSpace(tok) == "" {
		return nil, a.challenge(r, http.StatusUnauthorized, a.Scopes["*"], "")
	}
	verify := a.Verify
	if verify == nil {
		verify = a.verifyJWT
	}
	p, err := verify(r.Context(), tok, a.audience(r))
	if err != nil {
		return nil, a.challenge(r, http.StatusUnauthorized, a.Scopes["*"], "invalid_token")
	}
	need := slices.Clone(a.Scopes["*"])
	if r.Header.Get("Mcp-Method") == "tools/call" {
		if name, ok := mcpDecodeHeaderValue(r.Header.Get("Mcp-Name")); ok {
			for _, s := range a.Scopes[name] {
				if !slices.Contains(need, s) {
					need = append(need, s)
				}
			}
		}
	}
	for _, s := range need {
		if !slices.Contains(p.Scopes, s) {
			return nil, a.challenge(r, http.StatusForbidden, need, "insufficient_scope")
		}
	}
	return p, nil
}

func (a *MCPServerAuth) verifyJWT(ctx context.Context, tok, aud string) (MCPPrincipal, error) {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		return MCPPrincipal{}, errors.New("malformed jwt")
	}
	var hdr struct{ Alg, Kid string }
	var claims map[string]any
	if mcpDecodeSeg(parts[0], &hdr) != nil || mcpDecodeSeg(parts[1], &claims) != nil || hdr.Alg != "RS256" {
		return MCPPrincipal{}, errors.New("bad jwt header or claims")
	}
	key, err := a.key(ctx, hdr.Kid)
	if err != nil {
		return MCPPrincipal{}, err
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return MCPPrincipal{}, err
	}
	sum := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if rsa.VerifyPKCS1v15(key, crypto.SHA256, sum[:], sig) != nil {
		return MCPPrincipal{}, errors.New("bad signature")
	}
	if claims["iss"] != a.Issuer {
		return MCPPrincipal{}, errors.New("wrong issuer")
	}
	switch x := claims["aud"].(type) {
	case string:
		if x != aud {
			return MCPPrincipal{}, errors.New("wrong audience")
		}
	case []any:
		if !slices.Contains(x, any(aud)) {
			return MCPPrincipal{}, errors.New("wrong audience")
		}
	default:
		return MCPPrincipal{}, errors.New("wrong audience")
	}
	if exp, _ := claims["exp"].(float64); int64(exp) <= time.Now().Unix() {
		return MCPPrincipal{}, errors.New("expired")
	}
	sub, _ := claims["sub"].(string)
	sc, _ := claims["scope"].(string)
	return MCPPrincipal{Subject: sub, Scopes: strings.Fields(sc), Claims: claims}, nil
}

func mcpDecodeSeg(s string, v any) error {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func (a *MCPServerAuth) client() *http.Client {
	if a.HTTPClient != nil {
		return a.HTTPClient
	}
	return &http.Client{Timeout: 10 * time.Second}
}

// key returns the JWKS key for kid; an unknown kid refetches at most every 30 s.
func (a *MCPServerAuth) key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if k := a.keys[kid]; k != nil {
		return k, nil
	}
	if a.keys != nil && time.Since(a.fetched) < 30*time.Second {
		return nil, errors.New("unknown kid")
	}
	md, err := mcpDiscoverAS(ctx, a.client(), a.Issuer)
	if err != nil {
		return nil, err
	}
	jwksURI, _ := md["jwks_uri"].(string)
	var jwks struct {
		Keys []struct{ Kty, Kid, N, E string }
	}
	if err := mcpGetJSON(ctx, a.client(), jwksURI, &jwks); err != nil {
		return nil, err
	}
	a.keys = map[string]*rsa.PublicKey{}
	a.fetched = time.Now()
	for _, k := range jwks.Keys {
		n, e1 := base64.RawURLEncoding.DecodeString(k.N)
		e, e2 := base64.RawURLEncoding.DecodeString(k.E)
		if k.Kty == "RSA" && e1 == nil && e2 == nil {
			a.keys[k.Kid] = &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: int(new(big.Int).SetBytes(e).Int64())}
		}
	}
	if k := a.keys[kid]; k != nil {
		return k, nil
	}
	return nil, errors.New("unknown kid")
}

// mcpDiscoverAS fetches AS metadata in the SPEC §2A.1 step 3 order and checks
// that its issuer matches.
func mcpDiscoverAS(ctx context.Context, hc *http.Client, issuer string) (map[string]any, error) {
	u, err := url.Parse(issuer)
	if err != nil {
		return nil, err
	}
	origin, path := u.Scheme+"://"+u.Host, strings.TrimSuffix(u.Path, "/")
	cands := []string{origin + "/.well-known/oauth-authorization-server" + path, origin + "/.well-known/openid-configuration" + path}
	if path != "" {
		cands = append(cands, origin+path+"/.well-known/openid-configuration")
	}
	for _, c := range cands {
		var md map[string]any
		if mcpGetJSON(ctx, hc, c, &md) == nil && md["issuer"] == issuer {
			return md, nil
		}
	}
	return nil, errors.New("authorization server metadata not found for " + issuer)
}

func mcpGetJSON(ctx context.Context, hc *http.Client, u string, v any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GET %s: HTTP %d", u, resp.StatusCode)
	}
	return json.NewDecoder(resp.Body).Decode(v)
}
