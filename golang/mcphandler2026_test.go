package toolnexus

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// post26 sends one 2026-07-28 request (headers + _meta stamped) and returns status, header, body.
func post26(t *testing.T, u, token, method string, params map[string]any) (int, http.Header, map[string]any) {
	t.Helper()
	if params == nil {
		params = map[string]any{}
	}
	meta, _ := params["_meta"].(map[string]any)
	if meta == nil {
		meta = map[string]any{}
	}
	meta[mcpMetaVersion] = MCPProtocol2026
	params["_meta"] = meta
	b, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
	req, _ := http.NewRequest(http.MethodPost, u, strings.NewReader(string(b)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("MCP-Protocol-Version", MCPProtocol2026)
	req.Header.Set("Mcp-Method", method)
	if n, ok := params["name"].(string); ok {
		req.Header.Set("Mcp-Name", n)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, resp.Header, out
}

func whoTool() Tool {
	return Tool{Name: "who", Description: "who", InputSchema: JSONSchema{"type": "object"}, Source: SourceNative,
		Execute: func(_ map[string]any, tc *ToolContext) (ToolResult, error) {
			p, _ := IdentityFrom(tc.Ctx).(MCPPrincipal)
			return ToolResult{Output: p.Subject}, nil
		}}
}

func TestMCPServerAuth_DefaultJWTVerifierWithDevAS(t *testing.T) {
	as, err := StartMCPDevAuthServer(MCPDevAuthOptions{})
	if err != nil {
		t.Fatal(err)
	}
	defer as.Close()
	tk := newHandlerTK(t).Register(whoTool())
	auth := &MCPServerAuth{Issuer: as.Issuer, Scopes: map[string][]string{"*": {"mcp"}, "who": {"mcp:who"}}}
	mux := http.NewServeMux()
	mux.Handle("/mcp", tk.MCPHandler(MCPHandlerConfig{ProtocolVersions: []string{MCPProtocol2026}, Authenticate: auth.Authenticate}))
	mux.Handle("/.well-known/oauth-protected-resource/", auth.Metadata())
	srv := httptest.NewServer(mux)
	defer srv.Close()
	u := srv.URL + "/mcp"
	now := time.Now().Unix()
	mint := func(over map[string]any) string {
		c := map[string]any{"iss": as.Issuer, "sub": "dev-user", "aud": u, "scope": "mcp mcp:who", "exp": now + 60}
		for k, v := range over {
			c[k] = v
		}
		return as.Mint(c)
	}
	if st, _, out := post26(t, u, mint(nil), "tools/call", map[string]any{"name": "who", "arguments": map[string]any{}}); st != 200 ||
		out["result"].(map[string]any)["content"].([]any)[0].(map[string]any)["text"] != "dev-user" {
		t.Fatalf("valid token: %d %v", st, out)
	}
	for name, tok := range map[string]string{
		"wrong audience": mint(map[string]any{"aud": "https://other.example/mcp"}),
		"aud array miss": mint(map[string]any{"aud": []any{"x"}}),
		"expired":        mint(map[string]any{"exp": now - 1}),
		"wrong issuer":   mint(map[string]any{"iss": "https://evil.example"}),
		"tampered":       mint(nil)[:20] + "x" + mint(nil)[21:],
		"not a jwt":      "opaque",
	} {
		st, h, _ := post26(t, u, tok, "server/discover", nil)
		if st != 401 || !strings.Contains(h.Get("WWW-Authenticate"), `error="invalid_token"`) || strings.Contains(h.Get("WWW-Authenticate"), tok) {
			t.Fatalf("%s: %d %q", name, st, h.Get("WWW-Authenticate"))
		}
	}
	st, h, _ := post26(t, u, mint(map[string]any{"scope": "mcp"}), "tools/call", map[string]any{"name": "who", "arguments": map[string]any{}})
	if st != 403 || h.Get("WWW-Authenticate") != `Bearer resource_metadata="`+srv.URL+`/.well-known/oauth-protected-resource/mcp", scope="mcp mcp:who", error="insufficient_scope"` {
		t.Fatalf("step-up: %d %q", st, h.Get("WWW-Authenticate"))
	}
	resp, err := http.Get(srv.URL + "/.well-known/oauth-protected-resource/mcp")
	if err != nil {
		t.Fatal(err)
	}
	var prm map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&prm)
	resp.Body.Close()
	if prm["resource"] != u || prm["authorization_servers"].([]any)[0] != as.Issuer {
		t.Fatalf("prm %v", prm)
	}
}

// pkceLogin runs the authorization-code + PKCE flow against the dev AS with no browser.
func pkceLogin(t *testing.T, as *MCPDevAuthServer, clientID, redirect, resource, scope string) map[string]any {
	t.Helper()
	verifier := "v-" + strings.Repeat("a", 50)
	sum := sha256.Sum256([]byte(verifier))
	q := url.Values{"response_type": {"code"}, "client_id": {clientID}, "redirect_uri": {redirect}, "state": {"s1"},
		"code_challenge": {base64.RawURLEncoding.EncodeToString(sum[:])}, "code_challenge_method": {"S256"},
		"resource": {resource}, "scope": {scope}}
	hc := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := hc.Get(as.Issuer + "/authorize?" + q.Encode())
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	loc, err := url.Parse(resp.Header.Get("Location"))
	if err != nil || resp.StatusCode != 302 {
		t.Fatalf("authorize %d %v", resp.StatusCode, err)
	}
	cb := loc.Query()
	if cb.Get("iss") != as.Issuer || cb.Get("state") != "s1" || cb.Get("code") == "" {
		t.Fatalf("callback %v", cb)
	}
	tr, err := http.PostForm(as.Issuer+"/token", url.Values{"grant_type": {"authorization_code"}, "code": {cb.Get("code")},
		"code_verifier": {verifier}, "client_id": {clientID}, "redirect_uri": {redirect}, "resource": {resource}})
	if err != nil {
		t.Fatal(err)
	}
	defer tr.Body.Close()
	var tok map[string]any
	_ = json.NewDecoder(tr.Body).Decode(&tok)
	if tr.StatusCode != 200 {
		t.Fatalf("token %d %v", tr.StatusCode, tok)
	}
	return tok
}

func TestMCPDevAuthServer_PreRegisteredAndCIMD(t *testing.T) {
	cimd := httptest.NewServer(nil)
	defer cimd.Close()
	docURL := cimd.URL + "/client.json"
	cimd.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"client_id": docURL, "client_name": "CIMD app", "redirect_uris": []string{"http://127.0.0.1/cb"}})
	})
	as, err := StartMCPDevAuthServer(MCPDevAuthOptions{Clients: map[string][]string{"app": {"http://127.0.0.1/cb"}}})
	if err != nil {
		t.Fatal(err)
	}
	defer as.Close()
	tk := newHandlerTK(t).Register(whoTool())
	auth := &MCPServerAuth{Issuer: as.Issuer, Scopes: map[string][]string{"*": {"mcp"}}}
	srv := httptest.NewServer(tk.MCPHandler(MCPHandlerConfig{ProtocolVersions: []string{MCPProtocol2026}, Authenticate: auth.Authenticate}))
	defer srv.Close()
	u := srv.URL + "/mcp"
	for _, cid := range []string{"app", docURL} {
		tok := pkceLogin(t, as, cid, "http://127.0.0.1:5555/cb", u, "mcp")
		st, _, out := post26(t, u, tok["access_token"].(string), "tools/call", map[string]any{"name": "who", "arguments": map[string]any{}})
		if st != 200 || !strings.Contains(mustJSON(out), `"text":"dev-user"`) {
			t.Fatalf("%s: %d %v", cid, st, out)
		}
		// refresh rotates and keeps working
		rr, _ := http.PostForm(as.Issuer+"/token", url.Values{"grant_type": {"refresh_token"}, "refresh_token": {tok["refresh_token"].(string)}, "client_id": {cid}, "resource": {u}})
		if rr.StatusCode != 200 {
			t.Fatalf("refresh %d", rr.StatusCode)
		}
		rr.Body.Close()
	}
	// unknown client / unregistered redirect never redirects
	resp, _ := http.Get(as.Issuer + "/authorize?client_id=nobody&redirect_uri=http://127.0.0.1/cb")
	if resp.StatusCode != 400 {
		t.Fatalf("unknown client %d", resp.StatusCode)
	}
	resp.Body.Close()
	if as.Authorizations() != 3 {
		t.Fatalf("authorizations %d", as.Authorizations())
	}
}

func TestMCPHandler2026_OnCallAndPrompts(t *testing.T) {
	var events []MCPCallEvent
	tk := newHandlerTK(t).Register(whoTool())
	srv := httptest.NewServer(tk.MCPHandler(MCPHandlerConfig{
		ProtocolVersions: []string{MCPProtocol2026},
		OnCall:           func(ev MCPCallEvent) { events = append(events, ev); panic("isolated") },
		ListPrompts: func(context.Context) ([]MCPPrompt, error) {
			return []MCPPrompt{{Name: "p", Description: "d"}}, nil
		},
		GetPrompt: func(_ context.Context, name string, _ map[string]string) (MCPPromptResult, error) {
			return MCPPromptResult{Messages: []MCPPromptMessage{{Role: "user", Text: "hi " + name}}}, nil
		},
	}))
	defer srv.Close()
	if st, _, _ := post26(t, srv.URL, "", "tools/call", map[string]any{"name": "who", "arguments": map[string]any{}}); st != 200 || len(events) != 1 || events[0].Name != "who" {
		t.Fatalf("onCall %d %v", st, events)
	}
	_, _, d := post26(t, srv.URL, "", "server/discover", nil)
	if caps := d["result"].(map[string]any)["capabilities"].(map[string]any); caps["prompts"] == nil || caps["extensions"] != nil || caps["resources"] != nil {
		t.Fatalf("caps %v", caps)
	}
	_, _, g := post26(t, srv.URL, "", "prompts/get", map[string]any{"name": "p"})
	if !strings.Contains(mustJSON(g), `"text":"hi p"`) || !strings.Contains(mustJSON(g), `"resultType":"complete"`) {
		t.Fatalf("prompts/get %v", g)
	}
}

func TestLoadMCPSkills(t *testing.T) {
	skills, err := LoadMCPSkills("../examples/skills")
	if err != nil || len(skills) != 1 || skills[0].Name != "hello-world" || len(skills[0].Files) != 2 ||
		skills[0].Files[0].Path != "SKILL.md" || skills[0].Files[1].Path != "scripts/greet.sh" {
		t.Fatalf("%v %+v", err, skills)
	}
}
