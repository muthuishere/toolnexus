// Example 08 — serve MCP tools behind OAuth: one Issuer line, per-tool scopes, the caller's
// identity in the handler, and a custom Verify hook for opaque tokens.
//
// Run: go run ./examples/08-serve-protected   (hermetic: everything runs on 127.0.0.1)
//
// With Server.Auth set the server: serves Protected Resource Metadata at
// /.well-known/oauth-protected-resource/mcp, answers 401 + WWW-Authenticate without a valid
// token, 403 insufficient_scope when a tool needs more, and verifies RS256 JWTs against the
// issuer's JWKS (signature, iss, aud = this server's URL, exp).
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	as := mcp.StartDevAuthServer() // DEV/TEST ONLY. Production: your Keycloak / Entra / Auth0 issuer.
	defer as.Close()
	as.Clients["my-app"] = []string{"http://127.0.0.1/callback"}

	// ---- (1) the server: Issuer one-liner + per-tool scope + Principal ----
	srv := mcp.NewServer().
		Tool("whoami", "Who is calling", func(ctx context.Context, _ mcp.Obj) (any, error) {
			p, _ := mcp.PrincipalFrom(ctx) // Subject, Scopes, Claims of the verified token
			return "hello " + p.Subject + " (" + strings.Join(p.Scopes, " ") + ")", nil
		}).
		Tool("delete_all", "Dangerous admin tool", func(context.Context, mcp.Obj) (any, error) {
			return "deleted", nil
		})
	srv.Auth = &mcp.ServerAuth{
		Issuer: as.Issuer, // the whole required config
		Scopes: map[string][]string{
			"*":          {"mcp:read"},  // every request
			"delete_all": {"mcp:admin"}, // additionally required for this tool
		},
	}
	ts := httptest.NewServer(srv) // production: http.ListenAndServe(addr, srv)
	defer ts.Close()
	// ---- end server ----

	// No token: 401 with WWW-Authenticate pointing at the resource metadata.
	req, _ := http.NewRequest("POST", ts.URL+"/mcp", strings.NewReader(
		`{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("MCP-Protocol-Version", "2026-07-28")
	req.Header.Set("Mcp-Method", "tools/list")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		log.Fatal(err)
	}
	resp.Body.Close()
	fmt.Println("no token:", resp.StatusCode, "| WWW-Authenticate has resource_metadata:",
		strings.Contains(resp.Header.Get("WWW-Authenticate"), "resource_metadata="))

	// The metadata a client discovers (RFC 9728): which AS to use and which scopes exist.
	var prm struct {
		AuthorizationServers []string `json:"authorization_servers"`
		ScopesSupported      []string `json:"scopes_supported"`
	}
	resp, err = http.Get(ts.URL + "/.well-known/oauth-protected-resource/mcp")
	if err != nil {
		log.Fatal(err)
	}
	json.NewDecoder(resp.Body).Decode(&prm)
	resp.Body.Close()
	fmt.Println("metadata: issuer ok:", prm.AuthorizationServers[0] == as.Issuer, "| scopes:", prm.ScopesSupported)

	// A logged-in client (see example 06): whoami gets the principal; delete_all steps up.
	c := mcp.NewClient(ts.URL + "/mcp")
	c.Auth = &mcp.OAuth{ClientID: "my-app", Authorizer: mcp.LoopbackAuthorizer{Open: as.Approve}}
	res, err := c.CallTool("whoami", nil, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("whoami:", mcp.Text(res))
	res, err = c.CallTool("delete_all", nil, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("delete_all after step-up:", mcp.Text(res))
	res, _ = c.CallTool("whoami", nil, nil)
	fmt.Println("whoami:", mcp.Text(res))

	// ---- (2) custom Verify: opaque API keys / token introspection instead of JWT ----
	keys := map[string]mcp.Principal{ // e.g. a lookup in your DB, or an RFC 7662 introspection call
		"svc-key-123": {Subject: "billing-service", Scopes: []string{"mcp:read"}},
	}
	srv2 := mcp.NewServer().Tool("whoami", "Who is calling", func(ctx context.Context, _ mcp.Obj) (any, error) {
		p, _ := mcp.PrincipalFrom(ctx)
		return "hello " + p.Subject, nil
	})
	srv2.Auth = &mcp.ServerAuth{
		Issuer: as.Issuer, // still advertised in the resource metadata
		Scopes: map[string][]string{"*": {"mcp:read"}},
		Verify: func(_ context.Context, token string) (mcp.Principal, error) {
			if p, ok := keys[token]; ok {
				return p, nil
			}
			return mcp.Principal{}, errors.New("unknown key") // -> 401 invalid_token
		},
	}
	ts2 := httptest.NewServer(srv2)
	defer ts2.Close()

	// A service caller with a static key: plain http.Client with a header-adding transport.
	svc := mcp.NewClient(ts2.URL + "/mcp")
	svc.HTTP = &http.Client{Transport: bearer("svc-key-123")}
	res, err = svc.CallTool("whoami", nil, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("verify hook:", mcp.Text(res))

	bad := mcp.NewClient(ts2.URL + "/mcp")
	bad.HTTP = &http.Client{Transport: bearer("wrong")}
	_, err = bad.CallTool("whoami", nil, nil)
	fmt.Println("wrong key rejected:", err != nil)
}

// bearer adds a static Authorization header to every request.
type bearer string

func (b bearer) RoundTrip(r *http.Request) (*http.Response, error) {
	r = r.Clone(r.Context())
	r.Header.Set("Authorization", "Bearer "+string(b))
	return http.DefaultTransport.RoundTrip(r)
}
