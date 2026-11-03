// Example 06 — call an OAuth-protected MCP server with zero auth configuration (auto mode).
//
// Run: go run ./examples/06-oauth-zero-config   (hermetic: everything runs on 127.0.0.1)
//
// The client learns everything from the server's 401: Protected Resource Metadata -> the
// authorization server's metadata -> PKCE S256 login with `resource` -> token -> retry.
// You only say WHO the client is: a pre-registered ClientID, or a CIMD ClientMetadataURL.
// There is no Dynamic Client Registration.
package main

import (
	"fmt"
	"log"
	"net/http/httptest"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	// ---- demo harness: an authorization server + a protected MCP server, both in-process ----
	as := mcp.StartDevAuthServer() // DEV/TEST ONLY: auto-approves everyone as "dev-user"
	defer as.Close()
	as.Clients["my-app"] = []string{"http://127.0.0.1/callback"} // the AS admin registered "my-app"

	srv := mcp.FixtureServer()
	srv.Auth = &mcp.ServerAuth{Issuer: as.Issuer,
		Scopes: map[string][]string{"*": {"mcp:read"}, "greet": {"mcp:greet"}}} // greet needs more
	ts := httptest.NewServer(srv)
	defer ts.Close()
	// ---- end harness ----

	store := &mcp.MemoryTokenStore{} // implement mcp.TokenStore to persist tokens (keyed by resource)

	// The user code. A real app leaves Authorizer nil: LoopbackAuthorizer{} opens the system
	// browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
	c := mcp.NewClient(ts.URL + "/mcp")
	c.Auth = &mcp.OAuth{
		ClientID:   "my-app",
		Store:      store,
		Authorizer: mcp.LoopbackAuthorizer{Open: as.Approve},
	}

	res, err := c.CallTool("echo", mcp.Obj{"text": "hello after login"}, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("echo:", mcp.Text(res))

	// Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
	// re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
	res, err = c.CallTool("greet", mcp.Obj{}, func(string, mcp.Obj) (mcp.Obj, error) {
		return mcp.Obj{"action": "accept", "content": mcp.Obj{"name": "Ada"}}, nil
	})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("greet:", mcp.Text(res))

	// A second client sharing the token store needs no new login.
	c2 := mcp.NewClient(ts.URL + "/mcp")
	c2.Auth = &mcp.OAuth{ClientID: "my-app", Store: store, Authorizer: mcp.LoopbackAuthorizer{Open: as.Approve}}
	if _, err := c2.CallTool("echo", mcp.Obj{"text": "again"}, nil); err != nil {
		log.Fatal(err)
	}
	logins, _ := as.Stats()
	fmt.Println("browser logins:", logins) // 1 login + 1 step-up

	tok, _ := store.Load(ts.URL + "/mcp")
	fmt.Println("token stored for this issuer:", tok.Issuer == as.Issuer) // fmt.Print(tok) is redacted
}
