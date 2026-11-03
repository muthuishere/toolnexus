package mcp2026

// Server-side authorization: the MCP server as an OAuth 2.1 protected resource
// (MCP 2026-07-28 basic/authorization). One struct, set Server.Auth, done.

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
	"slices"
	"strings"
	"sync"
	"time"
)

// ServerAuth protects a Server. Scopes["*"] is required for every request (it is also the
// advertised scopes_supported); Scopes[toolName] is additionally required for that tools/call.
type ServerAuth struct {
	Issuer   string              // the authorization server's issuer identifier
	Audience string              // canonical resource URI; "" = derived from the request (scheme://host/path)
	Scopes   map[string][]string // "*" = every request; tool name = that tool
	// Verify replaces the default JWT/JWKS verifier (opaque tokens, introspection, ...).
	Verify func(ctx context.Context, token string) (Principal, error)
	HTTP   *http.Client // for AS metadata + JWKS fetches; nil = defaultHTTP()

	mu      sync.Mutex
	keys    map[string]*rsa.PublicKey // JWKS cache by kid
	fetched time.Time                 // last JWKS fetch: unknown kids refetch at most every 30s
}

// Principal is the authenticated caller, available to tool handlers via PrincipalFrom(ctx).
type Principal struct {
	Subject string
	Scopes  []string
	Claims  map[string]any
}

type principalKey struct{}

func PrincipalFrom(ctx context.Context) (Principal, bool) {
	p, ok := ctx.Value(principalKey{}).(Principal)
	return p, ok
}

const prmPath = "/.well-known/oauth-protected-resource"

func (a *ServerAuth) audience(r *http.Request) string {
	if a.Audience != "" {
		return a.Audience
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return scheme + "://" + r.Host + "/mcp"
}

// serveMetadata answers RFC 9728 PRM at both the path-inserted and root well-known URIs.
func (a *ServerAuth) serveMetadata(w http.ResponseWriter, r *http.Request) bool {
	if r.URL.Path != prmPath && r.URL.Path != prmPath+"/mcp" {
		return false
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(obj{"resource": a.audience(r), "authorization_servers": []string{a.Issuer},
		"scopes_supported": a.Scopes["*"], "bearer_methods_supported": []string{"header"}})
	return true
}

// check authenticates the request; on failure it writes 401/403 and returns false.
func (a *ServerAuth) check(w http.ResponseWriter, r *http.Request, method string, p obj) (context.Context, bool) {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	challenge := func(status int, extra string, scopes []string) {
		v := fmt.Sprintf(`Bearer resource_metadata="%s://%s%s/mcp"`, scheme, r.Host, prmPath)
		if len(scopes) > 0 {
			v += fmt.Sprintf(`, scope="%s"`, strings.Join(scopes, " "))
		}
		w.Header().Set("WWW-Authenticate", v+extra)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		w.Write([]byte(`{"error":"unauthorized"}`))
	}
	tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || tok == "" {
		challenge(http.StatusUnauthorized, "", a.Scopes["*"])
		return nil, false
	}
	verify := a.Verify
	if verify == nil {
		verify = func(ctx context.Context, t string) (Principal, error) { return a.verifyJWT(ctx, t, a.audience(r)) }
	}
	pr, err := verify(r.Context(), tok)
	if err != nil { // never echo the token or the detailed reason
		challenge(http.StatusUnauthorized, `, error="invalid_token"`, a.Scopes["*"])
		return nil, false
	}
	need := slices.Clone(a.Scopes["*"])
	if method == "tools/call" {
		name, _ := p["name"].(string)
		need = append(need, a.Scopes[name]...)
	}
	var missing []string
	for _, s := range need {
		if !slices.Contains(pr.Scopes, s) {
			missing = append(missing, s)
		}
	}
	if len(missing) > 0 {
		challenge(http.StatusForbidden, `, error="insufficient_scope"`, need)
		return nil, false
	}
	return context.WithValue(r.Context(), principalKey{}, pr), true
}

// --- default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ---

func (a *ServerAuth) verifyJWT(ctx context.Context, tok, aud string) (Principal, error) {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		return Principal{}, errors.New("malformed jwt")
	}
	var hdr struct{ Alg, Kid string }
	var claims map[string]any
	if decodeSeg(parts[0], &hdr) != nil || decodeSeg(parts[1], &claims) != nil || hdr.Alg != "RS256" {
		return Principal{}, errors.New("bad jwt header/claims")
	}
	key, err := a.key(ctx, hdr.Kid)
	if err != nil {
		return Principal{}, err
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return Principal{}, err
	}
	h := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if err := rsa.VerifyPKCS1v15(key, crypto.SHA256, h[:], sig); err != nil {
		return Principal{}, errors.New("bad signature")
	}
	if claims["iss"] != a.Issuer {
		return Principal{}, errors.New("wrong issuer")
	}
	if !audContains(claims["aud"], aud) {
		return Principal{}, errors.New("wrong audience")
	}
	if exp, _ := claims["exp"].(float64); int64(exp) <= time.Now().Unix() {
		return Principal{}, errors.New("expired")
	}
	sub, _ := claims["sub"].(string)
	sc, _ := claims["scope"].(string)
	return Principal{Subject: sub, Scopes: strings.Fields(sc), Claims: claims}, nil
}

func audContains(v any, aud string) bool {
	switch x := v.(type) {
	case string:
		return x == aud
	case []any:
		return slices.Contains(x, any(aud))
	}
	return false
}

func decodeSeg(s string, v any) error {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func (a *ServerAuth) key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if k := a.keys[kid]; k != nil {
		return k, nil
	}
	if a.keys != nil && time.Since(a.fetched) < 30*time.Second { // garbage kids must not hammer the AS
		return nil, errors.New("unknown kid")
	}
	hc := a.HTTP
	if hc == nil {
		hc = defaultHTTP()
	}
	md, err := discoverAS(ctx, hc, a.Issuer) // same RFC 8414 / OIDC order the client uses
	if err != nil {
		return nil, err
	}
	var jwks struct {
		Keys []struct{ Kty, Kid, N, E string }
	}
	jwksURI, _ := md["jwks_uri"].(string)
	if err := getJSON(ctx, hc, jwksURI, &jwks); err != nil {
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
