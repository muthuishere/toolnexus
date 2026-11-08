// Command mcp2026client is the Go CLIENT runner of the MCP 2026-07-28 interop
// matrix (interop/mcp2026/README.md). It runs the shared scenario list against
// one server runner and prints one line per scenario:
//
//	PASS <id>
//	FAIL <id>: <reason>
//
// Exit 0 iff every scenario passed. Test tooling, not shipped.
//
//	mcp2026client <base> <skillsDir>             # hermetic scenarios vs a server runner
//	mcp2026client --keycloak <mcpURL> <skillsDir> # live: Keycloak scenarios (KEYCLOAK=1)
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

var failed bool

func scenario(id string, fn func() error) {
	defer func() {
		if r := recover(); r != nil {
			failed = true
			fmt.Printf("FAIL %s: panic %v\n", id, r)
		}
	}()
	if err := fn(); err != nil {
		failed = true
		fmt.Printf("FAIL %s: %s\n", id, strings.ReplaceAll(err.Error(), "\n", " "))
		return
	}
	fmt.Printf("PASS %s\n", id)
}

func answers(r toolnexus.Request) (toolnexus.Answer, error) {
	switch r.Prompt {
	case "What is your name?":
		return toolnexus.Answer{ID: r.ID, Ok: true, Data: map[string]any{"name": "toolnexus"}}, nil
	case "Really wipe?":
		return toolnexus.Answer{ID: r.ID, Ok: true, Data: map[string]any{"confirm": true}}, nil
	}
	return toolnexus.Answer{ID: r.ID, Ok: false}, fmt.Errorf("unexpected request %q", r.Prompt)
}

func load(cfg map[string]any, opts toolnexus.McpLoadOptions) (*toolnexus.McpSource, error) {
	src, err := toolnexus.LoadMcpWithOptions(context.Background(), map[string]any{"srv": cfg}, opts)
	if err != nil {
		return nil, err
	}
	if src.Status["srv"] != toolnexus.StatusConnected {
		src.Close()
		return nil, fmt.Errorf("not connected: %v", src.Status)
	}
	return src, nil
}

func tool(src *toolnexus.McpSource, name string) (toolnexus.Tool, error) {
	for _, t := range src.Tools {
		if t.Name == name {
			return t, nil
		}
	}
	return toolnexus.Tool{}, fmt.Errorf("no tool %s", name)
}

func run(src *toolnexus.McpSource, name string, args map[string]any) (toolnexus.ToolResult, error) {
	t, err := tool(src, name)
	if err != nil {
		return toolnexus.ToolResult{}, err
	}
	return t.Execute(args, &toolnexus.ToolContext{Ctx: context.Background()})
}

func expect(src *toolnexus.McpSource, name string, args map[string]any, want string) error {
	r, err := run(src, name, args)
	if err != nil {
		return err
	}
	if r.IsError || r.Output != want {
		return fmt.Errorf("%s: got %q (isError=%v), want %q", name, r.Output, r.IsError, want)
	}
	return nil
}

func get(u string) error {
	resp, err := http.Get(u)
	if err != nil {
		return err
	}
	return resp.Body.Close()
}

func main() {
	if len(os.Args) == 4 && os.Args[1] == "--keycloak" {
		keycloak(os.Args[2])
	} else if len(os.Args) == 3 {
		hermetic(os.Args[1], os.Args[2])
	} else {
		fmt.Fprintln(os.Stderr, "usage: mcp2026client <base> <skillsDir> | --keycloak <mcpURL> <skillsDir>")
		os.Exit(2)
	}
	if failed {
		os.Exit(1)
	}
}

func hermetic(base, skillsDir string) {
	open := map[string]any{"url": base + "/open/mcp"}

	scenario("discover", func() error {
		src, err := load(open, toolnexus.McpLoadOptions{WaitFor: answers})
		if err != nil {
			return err
		}
		defer src.Close()
		var names []string
		for _, t := range src.Tools {
			names = append(names, t.Name)
		}
		if got := strings.Join(names, ","); got != "srv_echo,srv_greet,srv_whoami,srv_wipe" {
			return fmt.Errorf("tools %s", got)
		}
		if err := expect(src, "srv_echo", map[string]any{"text": "hi"}, `{"text":"hi"}`); err != nil {
			return err
		}
		resp, err := http.Get(base + "/log")
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		var l struct{ Seen []string }
		_ = json.NewDecoder(resp.Body).Decode(&l)
		for _, s := range l.Seen {
			if strings.HasPrefix(s, "open ") && !strings.HasPrefix(s, "open 2026-07-28 ") {
				return fmt.Errorf("non-2026 request on the 2026 mount: %q", s)
			}
		}
		return nil
	})

	scenario("mrtr_input", func() error {
		src, err := load(open, toolnexus.McpLoadOptions{WaitFor: answers})
		if err != nil {
			return err
		}
		defer src.Close()
		return expect(src, "srv_greet", nil, "Hello, toolnexus!")
	})

	scenario("mrtr_confirm", func() error {
		src, err := load(open, toolnexus.McpLoadOptions{WaitFor: answers})
		if err != nil {
			return err
		}
		defer src.Close()
		return expect(src, "srv_wipe", nil, "wiped")
	})

	scenario("mrtr_pending", func() error {
		src, err := load(open, toolnexus.McpLoadOptions{})
		if err != nil {
			return err
		}
		defer src.Close()
		r, err := run(src, "srv_greet", nil)
		if err != nil {
			return err
		}
		if req := toolnexus.PendingOf(r); req == nil || req.Kind != "input" || req.Prompt != "What is your name?" {
			return fmt.Errorf("not pending input: %+v", r)
		}
		return nil
	})

	scenario("skills", func() error {
		tk, err := toolnexus.CreateToolkit(context.Background(), toolnexus.Options{McpConfig: map[string]any{"mcpServers": map[string]any{"srv": open}}})
		if err != nil {
			return err
		}
		defer tk.Close()
		skill, ok := tk.Get("skill")
		if !ok {
			return errors.New("no skill tool")
		}
		r, err := skill.Execute(map[string]any{"name": "hello-world"}, nil)
		if err != nil {
			return err
		}
		body, err := skillBody(skillsDir)
		if err != nil {
			return err
		}
		if r.IsError || !strings.Contains(r.Output, body) {
			return fmt.Errorf("remote skill output lacks the SKILL.md body: %.200q", r.Output)
		}
		return nil
	})

	scenario("tool_search", func() error {
		src, err := load(map[string]any{"url": base + "/open/mcp", "toolSearch": true, "description": "interop"}, toolnexus.McpLoadOptions{WaitFor: answers})
		if err != nil {
			return err
		}
		defer src.Close()
		if len(src.Tools) != 2 {
			return fmt.Errorf("tool search exposes %d tools, want 2", len(src.Tools))
		}
		r, err := src.Tools[0].Execute(map[string]any{"query": "echo text"}, &toolnexus.ToolContext{Ctx: context.Background()})
		if err != nil {
			return err
		}
		if !strings.HasPrefix(r.Output, `[{"name":"echo"`) {
			return fmt.Errorf("search output %.200q", r.Output)
		}
		r, err = src.Tools[1].Execute(map[string]any{"name": "greet"}, &toolnexus.ToolContext{Ctx: context.Background()})
		if err != nil {
			return err
		}
		if r.Output != "Hello, toolnexus!" {
			return fmt.Errorf("call_tool greet %q", r.Output)
		}
		return nil
	})

	authCfg := map[string]any{"url": base + "/mcp", "auth": map[string]any{"clientId": "interop-client", "allowHttp": true}}
	store := &toolnexus.MemoryTokenStore{}
	var authed *toolnexus.McpSource
	scenario("oauth_preregistered", func() error {
		src, err := load(authCfg, toolnexus.McpLoadOptions{OpenURL: get, TokenStore: store, WaitFor: answers})
		if err != nil {
			return err
		}
		authed = src
		return expect(src, "srv_echo", map[string]any{"text": "authed"}, `{"text":"authed"}`)
	})

	scenario("oauth_stepup", func() error {
		if authed == nil {
			return errors.New("needs oauth_preregistered")
		}
		defer authed.Close()
		if err := expect(authed, "srv_whoami", nil, "dev-user"); err != nil {
			return err
		}
		tok, _ := store.Load(base + "/mcp")
		if tok == nil || !strings.Contains(" "+strings.Join(tok.Scopes, " ")+" ", " mcp:whoami ") {
			return fmt.Errorf("stored token scopes lack mcp:whoami: %+v", tok)
		}
		return nil
	})

	scenario("oauth_cimd", func() error {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return err
		}
		docURL := "http://" + ln.Addr().String() + "/client.json"
		hs := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"client_id": docURL, "client_name": "interop CIMD",
				"redirect_uris": []string{"http://127.0.0.1/callback"}})
		})}
		go func() { _ = hs.Serve(ln) }()
		defer hs.Close()
		var bad error
		follow := func(u string) error {
			if !strings.Contains(u, "client_id="+url.QueryEscape(docURL)) {
				bad = fmt.Errorf("authorize URL lacks the CIMD client_id: %s", u)
			}
			return get(u)
		}
		src, err := load(map[string]any{"url": base + "/mcp", "auth": map[string]any{"clientMetadataUrl": docURL, "allowHttp": true}},
			toolnexus.McpLoadOptions{OpenURL: follow})
		if err != nil {
			return err
		}
		defer src.Close()
		if err := expect(src, "srv_echo", map[string]any{"text": "cimd"}, `{"text":"cimd"}`); err != nil {
			return err
		}
		return bad
	})

	scenario("oauth_no_identity", func() error {
		src, err := toolnexus.LoadMcpWithOptions(context.Background(), map[string]any{"srv": map[string]any{"url": base + "/mcp", "auth": map[string]any{"allowHttp": true}}},
			toolnexus.McpLoadOptions{OpenURL: func(string) error { return nil }})
		if err != nil {
			return err
		}
		defer src.Close()
		if src.Status["srv"] != toolnexus.StatusFailed {
			return fmt.Errorf("status %v, want failed", src.Status)
		}
		return nil
	})

	scenario("legacy_fallback", func() error {
		src, err := load(map[string]any{"url": base + "/legacy/mcp"}, toolnexus.McpLoadOptions{})
		if err != nil {
			return err
		}
		defer src.Close()
		return expect(src, "srv_echo", map[string]any{"text": "old"}, `{"text":"old"}`)
	})
}

// skillBody is hello-world's SKILL.md without its frontmatter, trimmed.
func skillBody(dir string) (string, error) {
	b, err := os.ReadFile(filepath.Join(dir, "hello-world", "SKILL.md"))
	if err != nil {
		return "", err
	}
	s := strings.ReplaceAll(string(b), "\r\n", "\n")
	if strings.HasPrefix(s, "---\n") {
		if i := strings.Index(s[4:], "\n---"); i >= 0 {
			s = s[4+i+4:]
		}
	}
	return strings.TrimSpace(s), nil
}

// ---- live Keycloak (interop/mcp2026/keycloak) ----

const (
	kcIssuer   = "http://localhost:8180/realms/toolnexus"
	kcClientID = "toolnexus-mcp"
	kcUser     = "alice"
	kcPassword = "alice-test-pw" // throwaway user of the local-only realm-toolnexus.json
)

var (
	kcLoginForm = regexp.MustCompile(`<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"`)
	kcAnyForm   = regexp.MustCompile(`<form[^>]*action="([^"]+)"`)
)

// kcOpen is a headless browser: follow the authorize URL, submit Keycloak's
// login (and consent) form, then deliver the loopback callback.
func kcOpener() func(string) error {
	jar, _ := cookiejar.New(nil)
	hc := &http.Client{Jar: jar, Timeout: 15 * time.Second, CheckRedirect: func(r *http.Request, _ []*http.Request) error {
		if r.URL.Hostname() == "127.0.0.1" {
			return http.ErrUseLastResponse
		}
		return nil
	}}
	return func(authURL string) error {
		resp, err := hc.Get(authURL)
		if err != nil {
			return err
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		for page := 0; resp.StatusCode == 200 && page < 3; page++ {
			var action string
			var form url.Values
			if m := kcLoginForm.FindSubmatch(body); m != nil {
				action, form = string(m[1]), url.Values{"username": {kcUser}, "password": {kcPassword}, "credentialId": {""}}
			} else if m := kcAnyForm.FindSubmatch(body); m != nil && strings.Contains(string(body), `name="accept"`) {
				action, form = string(m[1]), url.Values{"accept": {"Yes"}}
			} else {
				break
			}
			target, err := resp.Request.URL.Parse(html.UnescapeString(action))
			if err != nil {
				return err
			}
			if resp, err = hc.PostForm(target.String(), form); err != nil {
				return err
			}
			body, _ = io.ReadAll(resp.Body)
			resp.Body.Close()
		}
		if resp.StatusCode != 302 {
			return fmt.Errorf("keycloak login: HTTP %d", resp.StatusCode)
		}
		return get(resp.Header.Get("Location"))
	}
}

func keycloak(mcpURL string) {
	store := &toolnexus.MemoryTokenStore{}
	var src *toolnexus.McpSource
	scenario("kc_login", func() error {
		var err error
		src, err = load(map[string]any{"url": mcpURL, "auth": map[string]any{"clientId": kcClientID, "allowHttp": true}},
			toolnexus.McpLoadOptions{OpenURL: kcOpener(), TokenStore: store, WaitFor: answers})
		if err != nil {
			return err
		}
		return expect(src, "srv_echo", map[string]any{"text": "keycloak"}, `{"text":"keycloak"}`)
	})
	scenario("kc_stepup", func() error {
		if src == nil {
			return errors.New("needs kc_login")
		}
		defer src.Close()
		r, err := run(src, "srv_whoami", nil)
		if err != nil {
			return err
		}
		if r.IsError || r.Output == "" {
			return fmt.Errorf("whoami %+v", r)
		}
		tok, _ := store.Load(mcpURL)
		if tok == nil || !strings.Contains(" "+strings.Join(tok.Scopes, " ")+" ", " mcp:greet ") {
			return fmt.Errorf("stored token scopes lack mcp:greet: %+v", tok)
		}
		return nil
	})
}
