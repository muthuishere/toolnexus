package toolnexus

// TEST SUPPORT ONLY: an in-process MCP 2026-07-28 fixture server (HTTP + stdio,
// examples/mcp-2026/fixture.json tools + examples/skills) and a dev OAuth
// authorization server. Ported from spikes/mcp-2026/golang (server.go,
// serverauth.go, devauth.go), simplified: opaque tokens, plain http (allowHttp).

import (
	"bufio"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fxServer struct {
	tools  []jsonObj
	skills []fxSkill
	execs  sync.Map // tool name → *atomic.Int64 (completed executions)
	// auth (nil = unprotected)
	as     *devAS
	url    string // canonical resource URI (set after start)
	scopes map[string][]string
	// knobs
	failAfterRun int // HTTP status to send after the tool ran (no-retry tests)
	headers      sync.Map
}

type fxSkill struct {
	uri   string
	fm    jsonObj
	files [][2]string // uri, text (sorted)
}

func newFxServer(t testing.TB) *fxServer {
	raw, err := os.ReadFile("../examples/mcp-2026/fixture.json")
	if err != nil {
		t.Fatal(err)
	}
	var fx struct {
		Tools []jsonObj `json:"tools"`
	}
	if err := json.Unmarshal(raw, &fx); err != nil {
		t.Fatal(err)
	}
	s := &fxServer{tools: fx.Tools}
	s.tools = append(s.tools, jsonObj{"name": "whoami", "description": "Who is calling.", "inputSchema": jsonObj{"type": "object"}})
	sort.Slice(s.tools, func(i, j int) bool { return s.tools[i]["name"].(string) < s.tools[j]["name"].(string) })
	root := "../examples/skills"
	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || d.Name() != "SKILL.md" {
			return err
		}
		dir := filepath.Dir(p)
		name := filepath.Base(dir)
		sk := fxSkill{uri: "skill://" + name + "/SKILL.md", fm: jsonObj{"name": name}}
		_ = filepath.WalkDir(dir, func(fp string, fd os.DirEntry, err error) error {
			if err != nil || fd.IsDir() {
				return err
			}
			rel, _ := filepath.Rel(dir, fp)
			b, _ := os.ReadFile(fp)
			sk.files = append(sk.files, [2]string{"skill://" + name + "/" + filepath.ToSlash(rel), string(b)})
			return nil
		})
		sort.Slice(sk.files, func(i, j int) bool { return sk.files[i][0] < sk.files[j][0] })
		s.skills = append(s.skills, sk)
		return nil
	})
	return s
}

func (s *fxServer) count(tool string) int64 {
	v, _ := s.execs.LoadOrStore(tool, &atomic.Int64{})
	return v.(*atomic.Int64).Load()
}

func (s *fxServer) ran(tool string) {
	v, _ := s.execs.LoadOrStore(tool, &atomic.Int64{})
	v.(*atomic.Int64).Add(1)
}

// start serves s over HTTP at <url>/mcp.
func (s *fxServer) start(t testing.TB) string {
	ts := httptest.NewServer(s)
	t.Cleanup(ts.Close)
	s.url = ts.URL + "/mcp"
	return s.url
}

func fxWrite(w http.ResponseWriter, status int, id any, res jsonObj, e *McpRPCError) {
	msg := jsonObj{"jsonrpc": "2.0", "id": id}
	if e != nil {
		msg["error"] = e
	} else {
		msg["result"] = res
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(msg)
}

func (s *fxServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if s.as != nil && r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, mcpPRMPath) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(jsonObj{"resource": s.url, "authorization_servers": []string{s.as.issuer},
			"scopes_supported": s.scopes["*"], "bearer_methods_supported": []string{"header"}})
		return
	}
	if r.URL.Path != "/mcp" || r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	body, _ := io.ReadAll(r.Body)
	var req struct {
		ID     any     `json:"id"`
		Method string  `json:"method"`
		Params jsonObj `json:"params"`
	}
	if json.Unmarshal(body, &req) != nil {
		fxWrite(w, 400, nil, nil, &McpRPCError{Code: -32700, Message: "Parse error"})
		return
	}
	s.headers.Store(req.Method, r.Header.Clone())
	hv := r.Header.Get("MCP-Protocol-Version")
	if hv == "" || r.Header.Get("Mcp-Method") != req.Method {
		fxWrite(w, 400, req.ID, nil, &McpRPCError{Code: mcpCodeHeaderMismatch, Message: "Header mismatch"})
		return
	}
	if req.Method == "tools/call" {
		want, _ := req.Params["name"].(string)
		if got := fxDecodeHeader(r.Header.Get("Mcp-Name")); got != want {
			fxWrite(w, 400, req.ID, nil, &McpRPCError{Code: mcpCodeHeaderMismatch, Message: "Header mismatch"})
			return
		}
	}
	meta, _ := req.Params["_meta"].(map[string]any)
	if mv, _ := meta[mcpMetaVersion].(string); mv != MCPProtocol2026 || hv != mv {
		fxWrite(w, 400, req.ID, nil, &McpRPCError{Code: mcpCodeUnsupportedProtocol, Message: "Unsupported protocol version",
			Data: jsonObj{"supported": []string{MCPProtocol2026}, "requested": mv}})
		return
	}
	sub := ""
	if s.as != nil {
		var ok bool
		if sub, ok = s.checkAuth(w, r, req.Method, req.Params); !ok {
			return
		}
	}
	res, e := s.dispatch(req.Method, req.Params, sub)
	if s.failAfterRun != 0 && req.Method == "tools/call" {
		w.WriteHeader(s.failAfterRun)
		return
	}
	status := 200
	if e != nil && e.Code == mcpCodeMethodNotFound {
		status = 404
	}
	fxWrite(w, status, req.ID, res, e)
}

func fxDecodeHeader(v string) string {
	if strings.HasPrefix(v, "=?base64?") && strings.HasSuffix(v, "?=") {
		b, _ := base64.StdEncoding.DecodeString(v[9 : len(v)-2])
		return string(b)
	}
	return v
}

func (s *fxServer) checkAuth(w http.ResponseWriter, r *http.Request, method string, p jsonObj) (string, bool) {
	challenge := func(status int, extra string, scopes []string) {
		v := fmt.Sprintf(`Bearer resource_metadata="%s%s/mcp"`, strings.TrimSuffix(s.url, "/mcp"), mcpPRMPath)
		if len(scopes) > 0 {
			v += fmt.Sprintf(`, scope="%s"`, strings.Join(scopes, " "))
		}
		w.Header().Set("WWW-Authenticate", v+extra)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
	}
	tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || tok == "" {
		challenge(401, "", s.scopes["*"])
		return "", false
	}
	g, ok := s.as.lookup(tok)
	if !ok || g.resource != s.url || time.Now().After(g.exp) {
		challenge(401, `, error="invalid_token"`, s.scopes["*"])
		return "", false
	}
	need := slices.Clone(s.scopes["*"])
	if method == "tools/call" {
		n, _ := p["name"].(string)
		need = append(need, s.scopes[n]...)
	}
	have := strings.Fields(g.scope)
	for _, sc := range need {
		if !slices.Contains(have, sc) {
			challenge(403, `, error="insufficient_scope"`, need)
			return "", false
		}
	}
	return "dev-user", true
}

func (s *fxServer) dispatch(method string, p jsonObj, sub string) (jsonObj, *McpRPCError) {
	info := jsonObj{mcpMetaServerInfo: jsonObj{"name": "toolnexus-spike", "version": "0.0.1"}}
	switch method {
	case "server/discover":
		return jsonObj{"resultType": "complete", "supportedVersions": []string{MCPProtocol2026},
			"capabilities": jsonObj{"tools": jsonObj{}, "extensions": jsonObj{mcpSkillsExt: jsonObj{}}}, "_meta": info}, nil
	case "tools/list":
		return jsonObj{"resultType": "complete", "tools": s.tools, "ttlMs": 60000, "cacheScope": "public"}, nil
	case "tools/call":
		name, _ := p["name"].(string)
		args, _ := p["arguments"].(map[string]any)
		text := func(t string, sc any) jsonObj {
			r := jsonObj{"resultType": "complete", "content": []jsonObj{{"type": "text", "text": t}}, "isError": false}
			if sc != nil {
				r["structuredContent"] = sc
			}
			return r
		}
		switch name {
		case "echo":
			s.ran(name)
			t, _ := args["text"].(string)
			return text(t, jsonObj{"text": t}), nil
		case "whoami":
			s.ran(name)
			return text(sub, nil), nil
		case "greet":
			if resp, _ := p["inputResponses"].(map[string]any); resp != nil {
				if nr, _ := resp["name"].(map[string]any); nr != nil && nr["action"] == "accept" {
					if c, _ := nr["content"].(map[string]any); c != nil {
						if n, _ := c["name"].(string); n != "" {
							s.ran(name)
							return text("Hello, "+n+"!", nil), nil
						}
					}
				}
			}
			return jsonObj{"resultType": "input_required", "requestState": "opaque-1", "inputRequests": jsonObj{
				"name": jsonObj{"method": "elicitation/create", "params": jsonObj{"mode": "form", "message": "What is your name?",
					"requestedSchema": jsonObj{"type": "object", "properties": jsonObj{"name": jsonObj{"type": "string"}}, "required": []string{"name"}}}},
			}}, nil
		}
		return nil, &McpRPCError{Code: mcpCodeInvalidParams, Message: "Unknown tool: " + name}
	case "skills/list":
		list := []jsonObj{}
		for _, sk := range s.skills {
			list = append(list, jsonObj{"uri": sk.uri, "frontmatter": sk.fm})
		}
		return jsonObj{"resultType": "complete", "skills": list}, nil
	case "skills/get":
		uri, _ := p["uri"].(string)
		for _, sk := range s.skills {
			if sk.uri == uri {
				res := []jsonObj{}
				for _, f := range sk.files {
					res = append(res, jsonObj{"uri": f[0], "digest": fmt.Sprintf("sha256:%x", sha256.Sum256([]byte(f[1]))), "size": len(f[1])})
				}
				return jsonObj{"resultType": "complete", "skill": jsonObj{"uri": sk.uri, "frontmatter": sk.fm, "resources": res}}, nil
			}
		}
		return nil, &McpRPCError{Code: mcpCodeInvalidParams, Message: "Unknown skill: " + uri}
	case "resources/read":
		uri, _ := p["uri"].(string)
		for _, sk := range s.skills {
			for _, f := range sk.files {
				if f[0] == uri {
					return jsonObj{"resultType": "complete", "contents": []jsonObj{{"uri": uri, "mimeType": "text/markdown", "text": f[1]}}}, nil
				}
			}
		}
		return nil, &McpRPCError{Code: mcpCodeInvalidParams, Message: "Resource not found: " + uri}
	}
	return nil, &McpRPCError{Code: mcpCodeMethodNotFound, Message: "Method not found: " + method}
}

// serveFxStdio serves the fixture as line-delimited JSON-RPC (TestMain re-exec).
func serveFxStdio(s *fxServer, in io.Reader, out io.Writer) {
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 64<<10), 16<<20)
	enc := json.NewEncoder(out)
	for sc.Scan() {
		var req struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params jsonObj         `json:"params"`
		}
		if json.Unmarshal(sc.Bytes(), &req) != nil || len(req.ID) == 0 {
			continue
		}
		res, e := s.dispatch(req.Method, req.Params, "")
		msg := jsonObj{"jsonrpc": "2.0", "id": req.ID}
		if e != nil {
			msg["error"] = e
		} else {
			msg["result"] = res
		}
		_ = enc.Encode(msg)
	}
}

// ---------------------------------------------------------------------------
// devAS — DEV/TEST ONLY authorization server: auto-approves as dev-user.
// ---------------------------------------------------------------------------

type devGrant struct {
	clientID, redirect, challenge, resource, scope string
	exp                                            time.Time
}

type devAS struct {
	issuer                string
	mu                    sync.Mutex
	codes, rts            map[string]devGrant
	ats                   map[string]devGrant
	authorizations        int
	refreshes             int
	lastAuthorizeQuery    url.Values
	noS256, noCIMD        bool
	issOverride           string
	rejectRefreshResource bool
	formReply             bool
	accessTTL             time.Duration
}

func startDevAS(t testing.TB) *devAS {
	d := &devAS{codes: map[string]devGrant{}, rts: map[string]devGrant{}, ats: map[string]devGrant{}}
	ts := httptest.NewServer(d)
	t.Cleanup(ts.Close)
	d.issuer = ts.URL
	return d
}

func (d *devAS) lookup(tok string) (devGrant, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	g, ok := d.ats[tok]
	return g, ok
}

// mint issues an access + refresh token directly (seeding a TokenStore in tests).
func (d *devAS) mint(clientID, resource, scope string, ttl time.Duration) (at, rt string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	g := devGrant{clientID: clientID, resource: resource, scope: scope, exp: time.Now().Add(ttl)}
	at, rt = oauthRand(16), oauthRand(16)
	d.ats[at], d.rts[rt] = g, g
	return at, rt
}

func (d *devAS) stats() (int, int) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.authorizations, d.refreshes
}

func (d *devAS) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	js := func(status int, v any) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(v)
	}
	switch r.URL.Path {
	case "/.well-known/oauth-authorization-server":
		methods := []string{"S256"}
		if d.noS256 {
			methods = []string{"plain"}
		}
		js(200, jsonObj{"issuer": d.issuer, "authorization_endpoint": d.issuer + "/authorize", "token_endpoint": d.issuer + "/token",
			"code_challenge_methods_supported": methods, "authorization_response_iss_parameter_supported": true,
			"client_id_metadata_document_supported": !d.noCIMD})
	case "/authorize":
		q := r.URL.Query()
		d.mu.Lock()
		d.authorizations++
		d.lastAuthorizeQuery = q
		code := oauthRand(16)
		d.codes[code] = devGrant{clientID: q.Get("client_id"), redirect: q.Get("redirect_uri"), challenge: q.Get("code_challenge"),
			resource: q.Get("resource"), scope: q.Get("scope")}
		iss := d.issuer
		if d.issOverride != "" {
			iss = d.issOverride
		}
		d.mu.Unlock()
		http.Redirect(w, r, q.Get("redirect_uri")+"?"+url.Values{"code": {code}, "state": {q.Get("state")}, "iss": {iss}}.Encode(), 302)
	case "/token":
		_ = r.ParseForm()
		f := r.PostForm
		d.mu.Lock()
		defer d.mu.Unlock()
		var g devGrant
		var ok bool
		switch f.Get("grant_type") {
		case "authorization_code":
			g, ok = d.codes[f.Get("code")]
			delete(d.codes, f.Get("code"))
			sum := sha256.Sum256([]byte(f.Get("code_verifier")))
			if !ok || base64.RawURLEncoding.EncodeToString(sum[:]) != g.challenge || f.Get("resource") != g.resource || f.Get("redirect_uri") != g.redirect {
				js(400, jsonObj{"error": "invalid_grant"})
				return
			}
		case "refresh_token":
			g, ok = d.rts[f.Get("refresh_token")]
			delete(d.rts, f.Get("refresh_token"))
			if !ok || (f.Get("resource") != "" && (d.rejectRefreshResource || f.Get("resource") != g.resource)) {
				js(400, jsonObj{"error": "invalid_target"})
				return
			}
			d.refreshes++
		default:
			js(400, jsonObj{"error": "unsupported_grant_type"})
			return
		}
		ttl := d.accessTTL
		if ttl == 0 {
			ttl = time.Hour
		}
		g.exp = time.Now().Add(ttl)
		at, rt := oauthRand(16), oauthRand(16)
		d.ats[at], d.rts[rt] = g, g
		if d.formReply {
			w.Header().Set("Content-Type", "application/x-www-form-urlencoded")
			_, _ = w.Write([]byte(url.Values{"access_token": {at}, "refresh_token": {rt}, "scope": {g.scope},
				"expires_in": {fmt.Sprint(int(ttl.Seconds()))}, "token_type": {"bearer"}}.Encode()))
			return
		}
		js(200, jsonObj{"access_token": at, "token_type": "Bearer", "expires_in": int(ttl.Seconds()), "refresh_token": rt, "scope": g.scope})
	default:
		http.NotFound(w, r)
	}
}

// follow is a headless browser: GET the authorization URL and follow redirects
// (auto mode lands on the loopback listener).
func follow(u string) error {
	resp, err := http.Get(u)
	if err != nil {
		return err
	}
	return resp.Body.Close()
}

// visit returns the AS redirect Location without following it (pause mode).
func visit(u string) (string, error) {
	c := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := c.Get(u)
	if err != nil {
		return "", err
	}
	resp.Body.Close()
	return resp.Header.Get("Location"), nil
}
