// Example 07 — a web app that calls a protected MCP server for its users (pause/resume mode).
//
// Run: go run ./examples/07-oauth-web-pause-resume   (hermetic: everything runs on 127.0.0.1)
//
// A server-side app must never block a request waiting for a login. With PauseAuthorizer the
// call returns a *PendingError instead ({ID, Kind:"authorization", Prompt, URL, ExpiresAt},
// the shape of toolnexus SPEC §10 Request). The app sends the browser to Pending.URL; the
// authorization server redirects back; the app resumes and gets the ORIGINAL call's result.
//
// WebHandler packages that whole dance as one http.Handler. The manual version is shown too.
package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	// ---- demo harness: dev authorization server + protected MCP server ----
	as := mcp.StartDevAuthServer() // DEV/TEST ONLY
	defer as.Close()
	as.Clients["my-web-app"] = []string{"http://127.0.0.1/tool", "http://127.0.0.1/callback"}
	srv := mcp.FixtureServer()
	srv.Auth = &mcp.ServerAuth{Issuer: as.Issuer}
	mcpTS := httptest.NewServer(srv)
	defer mcpTS.Close()
	// ---- end harness ----

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		log.Fatal(err)
	}
	app := "http://" + ln.Addr().String()

	// ---- the web app ----
	c := mcp.NewClient(mcpTS.URL + "/mcp")
	c.Auth = &mcp.OAuth{ClientID: "my-web-app", Authorizer: mcp.PauseAuthorizer{Redirect: app + "/tool"}}

	mux := http.NewServeMux()
	// (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
	// with ?code&state, WebHandler resumes, and the tool's text is written to the response.
	mux.Handle("/tool", c.Auth.WebHandler(func(r *http.Request) (mcp.Obj, error) {
		return c.CallTool("echo", mcp.Obj{"text": r.URL.Query().Get("text")}, nil)
	}))
	go http.Serve(ln, mux)
	// ---- end web app ----

	// A person's browser opens /tool?text=... (as.Client() follows redirects and trusts the dev TLS).
	browser := as.Client()
	resp, err := browser.Get(app + "/tool?text=resumed+after+consent")
	if err != nil {
		log.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	fmt.Println("(a) WebHandler:", resp.StatusCode, string(body))

	// (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
	c2 := mcp.NewClient(mcpTS.URL + "/mcp")
	c2.Auth = &mcp.OAuth{ClientID: "my-web-app", Authorizer: mcp.PauseAuthorizer{Redirect: "http://127.0.0.1/callback"}}
	_, err = c2.CallTool("echo", mcp.Obj{"text": "resumed by hand"}, nil)
	p := mcp.PendingOf(err) // nil when the call did not pause
	if p == nil {
		log.Fatalf("expected a pending authorization, got %v", err)
	}
	fmt.Println("(b) paused:", p.Kind, "-", p.Prompt != "", "- has URL:", p.URL != "")

	callbackURL := approveAndCaptureCallback(as, p.URL) // the user logs in; you receive the callback URL
	res, err := c2.Auth.Resume(context.Background(), p.ID, callbackURL)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("(b) resumed:", mcp.Text(res))
}

// approveAndCaptureCallback stands in for a person logging in: it opens the authorization URL
// and returns the URL the authorization server redirected back to (without following it).
func approveAndCaptureCallback(as *mcp.DevAuthServer, authURL string) string {
	hc := *as.Client()
	hc.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := hc.Get(authURL)
	if err != nil {
		log.Fatal(err)
	}
	resp.Body.Close()
	return resp.Header.Get("Location")
}
