// Example 09 — a real authorization server: Keycloak 26.8.1 protecting an MCP server.
//
// Start Keycloak first (realm "toolnexus", user alice / alice-test-pw, client "toolnexus-mcp"):
//
//	cd keycloak && docker compose up -d && cd ..
//	KEYCLOAK=1 go run ./examples/09-keycloak                 # logs in through your system browser
//	KEYCLOAK=1 HEADLESS_LOGIN=1 go run ./examples/09-keycloak # scripted login (used by the test)
//	cd keycloak && docker compose down -v
//
// The MCP server listens on 127.0.0.1:7199 because the realm's audience mapper puts
// aud = http://127.0.0.1:7199/mcp into tokens; ServerAuth.Audience must equal it.
package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strings"

	mcp "toolnexus/spikes/mcp2026"
)

const (
	issuer   = "http://localhost:8180/realms/toolnexus" // exactly the `iss` Keycloak puts in tokens
	mcpAddr  = "127.0.0.1:7199"
	resource = "http://" + mcpAddr + "/mcp"
)

func main() {
	if os.Getenv("KEYCLOAK") != "1" {
		fmt.Println("skipped: set KEYCLOAK=1 with keycloak/docker-compose.yml running")
		return
	}

	// ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
	srv := mcp.FixtureServer().Tool("whoami", "Who is calling", func(ctx context.Context, _ mcp.Obj) (any, error) {
		p, _ := mcp.PrincipalFrom(ctx)
		// Subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
		return fmt.Sprintf("client=%v scopes=%s subject-set=%v", p.Claims["azp"], strings.Join(p.Scopes, " "), p.Subject != ""), nil
	})
	srv.Auth = &mcp.ServerAuth{
		Issuer:   issuer,
		Audience: resource, // = the audience mapper's included.custom.audience
		Scopes:   map[string][]string{"*": {"mcp:tools"}, "greet": {"mcp:greet"}},
	}
	ln, err := net.Listen("tcp", mcpAddr)
	if err != nil {
		log.Fatal(err)
	}
	go http.Serve(ln, srv)
	defer ln.Close()

	// ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
	open := mcp.LoopbackAuthorizer{} // nil Open = system browser
	if os.Getenv("HEADLESS_LOGIN") == "1" {
		open.Open = headlessLogin("alice", "alice-test-pw") // throwaway local test user
	}
	c := mcp.NewClient(resource)
	c.Auth = &mcp.OAuth{
		ClientIDs:  map[string]string{issuer: "toolnexus-mcp"}, // client id bound to THIS issuer
		AllowHTTP:  true,                                       // dev Keycloak is plain HTTP; never in production
		Authorizer: open,
	}

	res, err := c.CallTool("whoami", nil, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("whoami:", mcp.Text(res))

	// greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
	res, err = c.CallTool("greet", mcp.Obj{}, func(string, mcp.Obj) (mcp.Obj, error) {
		return mcp.Obj{"action": "accept", "content": mcp.Obj{"name": "Alice"}}, nil
	})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("greet:", mcp.Text(res))
}
