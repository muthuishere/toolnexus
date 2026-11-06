// Command mcp2026server is the server half of the MCP 2026-07-28 interop test
// (interop/mcp2026/run.sh): a toolkit behind MCPHandler on the 2026-07-28
// wire, protected by MCPServerAuth + the dev authorization server, plus an
// unprotected mount and a legacy-only mount. It prints one JSON line
// {"base","issuer"} and serves until stdin closes. Test tooling, not shipped.
//
//	mcp2026server [--keycloak] <skillsDir>
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"sync"

	toolnexus "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/internal/devauth"
)

// Live mode (--keycloak): interop/mcp2026/keycloak/docker-compose.yml.
const (
	keycloakIssuer = "http://localhost:8180/realms/toolnexus"
	keycloakAddr   = "127.0.0.1:7199"
)

func main() {
	args := os.Args[1:]
	keycloak := len(args) > 0 && args[0] == "--keycloak"
	if keycloak {
		args = args[1:]
	}
	skillsDir := "../examples/skills"
	if len(args) > 0 {
		skillsDir = args[0]
	}
	issuer := keycloakIssuer
	if !keycloak {
		as, err := devauth.Start(devauth.Options{
			Clients: map[string][]string{"interop-client": {"http://127.0.0.1/callback"}},
		})
		if err != nil {
			log.Fatal(err)
		}
		defer as.Close()
		issuer = as.Issuer
	}

	obj := toolnexus.JSONSchema{"type": "object"}
	tools := []toolnexus.Tool{
		{Name: "echo", Description: "Echo the given text back.", Source: toolnexus.SourceNative,
			InputSchema: toolnexus.JSONSchema{"type": "object", "properties": map[string]any{"text": map[string]any{"type": "string"}}, "required": []any{"text"}},
			Execute: func(a map[string]any, _ *toolnexus.ToolContext) (toolnexus.ToolResult, error) {
				s, _ := a["text"].(string)
				return toolnexus.ToolResult{Output: s, Structured: map[string]any{"text": s}}, nil
			}},
		{Name: "greet", Description: "Greet the user; asks for their name.", Source: toolnexus.SourceNative, InputSchema: obj,
			Execute: func(_ map[string]any, tc *toolnexus.ToolContext) (toolnexus.ToolResult, error) {
				if a := tc.Answer; a != nil && a.Ok {
					n, _ := a.Data["name"].(string)
					return toolnexus.ToolResult{Output: "Hello, " + n + "!"}, nil
				}
				return toolnexus.Pending(toolnexus.Request{ID: "name", Kind: "input", Prompt: "What is your name?",
					Data: map[string]any{"schema": map[string]any{"type": "object", "properties": map[string]any{"name": map[string]any{"type": "string"}}, "required": []any{"name"}}}}), nil
			}},
		{Name: "wipe", Description: "Destructive; asks for confirmation first.", Source: toolnexus.SourceNative, InputSchema: obj,
			Execute: func(map[string]any, *toolnexus.ToolContext) (toolnexus.ToolResult, error) {
				return toolnexus.ToolResult{Output: "wiped"}, nil
			}},
		{Name: "whoami", Description: "Who is calling.", Source: toolnexus.SourceNative, InputSchema: obj,
			Execute: func(_ map[string]any, tc *toolnexus.ToolContext) (toolnexus.ToolResult, error) {
				p, _ := toolnexus.IdentityFrom(tc.Ctx).(toolnexus.MCPPrincipal)
				return toolnexus.ToolResult{Output: p.Subject}, nil
			}},
	}
	tk, err := toolnexus.CreateToolkit(context.Background(), toolnexus.Options{})
	if err != nil {
		log.Fatal(err)
	}

	var mu sync.Mutex
	var seen []string // "<mount> <MCP-Protocol-Version> <Mcp-Method>"
	logged := func(mount string, h http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			mu.Lock()
			seen = append(seen, fmt.Sprintf("%s %s %s", mount, r.Header.Get("MCP-Protocol-Version"), r.Header.Get("Mcp-Method")))
			mu.Unlock()
			h.ServeHTTP(w, r)
		})
	}
	executions := map[string]int{}
	cfg := func() toolnexus.MCPHandlerConfig {
		return toolnexus.MCPHandlerConfig{
			Name: "interop", Version: "1.0.0",
			ProtocolVersions: []string{toolnexus.MCPProtocol2026},
			ListTools:        func(context.Context) ([]toolnexus.Tool, error) { return tools, nil },
			ListSkills:       func(context.Context) ([]toolnexus.MCPSkill, error) { return toolnexus.LoadMCPSkills(skillsDir) },
			BeforeCall: func(_ context.Context, t toolnexus.Tool, _ json.RawMessage) (toolnexus.MCPDecision, error) {
				if t.Name == "wipe" {
					return toolnexus.MCPDecision{Kind: toolnexus.MCPConfirm, Message: "Really wipe?"}, nil
				}
				return toolnexus.MCPDecision{}, nil
			},
			OnCall: func(ev toolnexus.MCPCallEvent) {
				mu.Lock()
				executions[ev.Name]++
				mu.Unlock()
			},
		}
	}
	auth := &toolnexus.MCPServerAuth{Issuer: issuer, Scopes: map[string][]string{"*": {"mcp"}, "whoami": {"mcp:whoami"}}}
	addr := "127.0.0.1:0"
	if keycloak {
		// The realm's audience mapper pins aud = http://127.0.0.1:7199/mcp.
		addr = keycloakAddr
		auth.Audience = "http://" + keycloakAddr + "/mcp"
		auth.Scopes = map[string][]string{"*": {"mcp:tools"}, "whoami": {"mcp:greet"}}
	}
	protected := cfg()
	protected.Authenticate = auth.Authenticate

	mux := http.NewServeMux()
	mux.Handle("/open/mcp", logged("open", tk.MCPHandler(cfg())))
	mux.Handle("/mcp", logged("auth", tk.MCPHandler(protected)))
	mux.Handle("/legacy/mcp", logged("legacy", tk.MCPHandler(toolnexus.MCPHandlerConfig{Stateless: true,
		ListTools: func(context.Context) ([]toolnexus.Tool, error) { return tools, nil }})))
	mux.Handle("/.well-known/oauth-protected-resource/mcp", auth.Metadata())
	mux.Handle("/.well-known/oauth-protected-resource", auth.Metadata())
	mux.HandleFunc("/log", func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]any{"seen": seen, "calls": executions})
	})
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal(err)
	}
	go func() { _ = http.Serve(ln, mux) }()
	line, _ := json.Marshal(map[string]string{"base": "http://" + ln.Addr().String(), "issuer": issuer})
	fmt.Println(string(line))
	// Serve until stdin closes (the runner owns our lifetime).
	_, _ = bufio.NewReader(os.Stdin).ReadString(0)
}
