package mcp2026

// The three user stories, each run against the in-process DevAuthServer.
// Lines between "user code" markers are what a user writes; the rest is the demo harness
// (a protected MCP server to talk to, and a headless browser standing in for a person).

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
)

// protectedMCP starts a dev AS and an OAuth-protected MCP server (fixture tools) and returns
// the AS and the server's /mcp URL. The AS admin pre-registers "my-app" for loopback redirects.
func protectedMCP() (*DevAuthServer, string, func()) {
	as := StartDevAuthServer()
	as.Clients["my-app"] = []string{"http://127.0.0.1/callback", "http://127.0.0.1/tool"}
	srv := mustServer()
	srv.Auth = &ServerAuth{Issuer: as.Issuer}
	ts := httptest.NewServer(srv)
	return as, ts.URL + "/mcp", func() { ts.Close(); as.Close() }
}

// Story 1: connect to an OAuth-protected MCP server; the client discovers everything.
func Example_story1ConnectZeroConfig() {
	as, mcpURL, stop := protectedMCP()
	defer stop()

	// --- user code (4 lines; a real app drops `Authorizer:` and gets the system browser) ---
	c := NewClient(mcpURL)
	c.Auth = &OAuth{ClientID: "my-app", Authorizer: LoopbackAuthorizer{Open: as.Approve}}
	res, err := c.CallTool("echo", obj{"text": "hello after login"}, nil)
	fmt.Println(Text(res), err)
	// --- end user code ---

	// Output: hello after login <nil>
}

// Story 2: a web app pauses on authorization, sends the browser to the AS, and resumes.
func Example_story2WebAppPauseResume() {
	as, mcpURL, stop := protectedMCP()
	defer stop()
	mux := http.NewServeMux()
	app := httptest.NewServer(mux)
	defer app.Close()

	// --- user code (5 lines) ---
	c := NewClient(mcpURL)
	c.Auth = &OAuth{ClientID: "my-app", Authorizer: PauseAuthorizer{Redirect: app.URL + "/tool"}}
	mux.Handle("/tool", c.Auth.WebHandler(func(r *http.Request) (obj, error) {
		return c.CallTool("echo", obj{"text": r.URL.Query().Get("text")}, nil)
	}))
	// --- end user code ---

	// The person's browser: /tool -> 302 to the AS -> 302 back to /tool?code=... -> result.
	resp, err := as.Client().Get(app.URL + "/tool?text=resumed+after+consent")
	if err != nil {
		log.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	fmt.Println(resp.StatusCode, string(body))
	// Output: 200 resumed after consent
}

// Story 3: serve tools protected by an authorization server with a one-line Auth.
func Example_story3ServeWithIssuer() {
	as := StartDevAuthServer()
	defer as.Close()
	as.Clients["my-app"] = []string{"http://127.0.0.1/callback"}

	// --- user code (6 lines; production: http.ListenAndServe(addr, srv)) ---
	srv := NewServer().Tool("whoami", "who is calling", func(ctx context.Context, _ obj) (any, error) {
		p, _ := PrincipalFrom(ctx)
		return "hello " + p.Subject, nil
	})
	srv.Auth = &ServerAuth{Issuer: as.Issuer}
	ts := httptest.NewServer(srv)
	// --- end user code ---
	defer ts.Close()

	c := NewClient(ts.URL + "/mcp")
	c.Auth = &OAuth{ClientID: "my-app", Authorizer: LoopbackAuthorizer{Open: as.Approve}}
	res, err := c.CallTool("whoami", nil, nil)
	fmt.Println(Text(res), err)
	// Output: hello dev-user <nil>
}
