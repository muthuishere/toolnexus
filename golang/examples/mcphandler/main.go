// Embeddable MCP server (SPEC §7C.1): mount tk.MCPHandler on your own mux,
// authenticate with a bearer token, give each caller its own tool list, and
// gate a destructive tool behind a confirmation (elicitation when the client
// supports it, a host-shaped "re-call with a nonce" result otherwise).
//
//	go run ./examples/mcphandler
//
// Self-contained: it starts the server on an ephemeral port and drives it with
// three MCP clients (alice with elicitation, alice without, bob).
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"

	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/client/transport"
	"github.com/mark3labs/mcp-go/mcp"
	"github.com/muthuishere/toolnexus/golang"
)

type user struct{ Name, Role string }

// Demo tokens only. A real host verifies a JWT/OAuth token here.
var tokens = map[string]user{"token-alice": {"alice", "admin"}, "token-bob": {"bob", "viewer"}}

func tools(ctx context.Context) ([]toolnexus.Tool, error) {
	u := toolnexus.IdentityFrom(ctx).(user)
	list := []toolnexus.Tool{{
		Name: "list_records", Description: "List records", Source: toolnexus.SourceNative,
		InputSchema: toolnexus.JSONSchema{"type": "object"},
		Annotations: &toolnexus.ToolAnnotations{ReadOnlyHint: ptr(true)},
		Execute: func(_ map[string]any, tc *toolnexus.ToolContext) (toolnexus.ToolResult, error) {
			return toolnexus.ToolResult{Output: "records for " + toolnexus.IdentityFrom(tc.Ctx).(user).Name + ": [1, 2]"}, nil
		},
	}}
	if u.Role == "admin" {
		list = append(list, toolnexus.Tool{
			Name: "delete_record", Description: "Delete a record", Source: toolnexus.SourceNative,
			InputSchema: toolnexus.JSONSchema{"type": "object", "properties": map[string]any{
				"id": map[string]any{"type": "string"}, "nonce": map[string]any{"type": "string"}}},
			Annotations: &toolnexus.ToolAnnotations{Title: "Delete record", DestructiveHint: ptr(true)},
			Execute: func(args map[string]any, _ *toolnexus.ToolContext) (toolnexus.ToolResult, error) {
				return toolnexus.ToolResult{Output: fmt.Sprintf("deleted %v", args["id"])}, nil
			},
		})
	}
	return list, nil
}

func beforeCall(_ context.Context, t toolnexus.Tool, args json.RawMessage) (toolnexus.MCPDecision, error) {
	if t.Annotations == nil || t.Annotations.DestructiveHint == nil || !*t.Annotations.DestructiveHint {
		return toolnexus.MCPDecision{Kind: toolnexus.MCPAllow}, nil
	}
	if bytes.Contains(args, []byte(`"nonce":"n-123"`)) { // the host's own nonce check
		return toolnexus.MCPDecision{Kind: toolnexus.MCPAllow}, nil
	}
	return toolnexus.MCPDecision{
		Kind: toolnexus.MCPConfirm, Message: "Really delete this record?",
		Result: &toolnexus.ToolResult{Output: `Confirmation needed: call delete_record again with "nonce":"n-123".`},
	}, nil
}

func main() {
	tk, err := toolnexus.CreateToolkit(context.Background(), toolnexus.Options{})
	if err != nil {
		log.Fatal(err)
	}
	defer tk.Close()

	mux := http.NewServeMux()
	mux.Handle("/mcp", tk.MCPHandler(toolnexus.MCPHandlerConfig{
		Name: "records", Version: "1.0.0",
		Authenticate: func(r *http.Request) (any, error) {
			if u, ok := tokens[strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")]; ok {
				return u, nil
			}
			return nil, errors.New("invalid token")
		},
		WWWAuthenticate: func(*http.Request, error) string {
			return `Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource"`
		},
		ListTools:  tools,
		BeforeCall: beforeCall,
		OnCall: func(ev toolnexus.MCPCallEvent) {
			fmt.Printf("  [onCall] %s by %s decision=%s isError=%v %dB\n", ev.Name, ev.Identity.(user).Name, ev.Decision, ev.IsError, ev.ResultBytes)
		},
	}))
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		log.Fatal(err)
	}
	go func() { _ = http.Serve(ln, mux) }()
	url := "http://" + ln.Addr().String() + "/mcp"

	resp, _ := http.Post(url, "application/json", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
	fmt.Printf("no token -> %d, WWW-Authenticate: %s\n", resp.StatusCode, resp.Header.Get("WWW-Authenticate"))
	resp.Body.Close()

	fmt.Println("alice (admin, client supports elicitation):")
	alice := connect(url, "token-alice", client.WithElicitationHandler(yes{}))
	show(alice)
	fmt.Println("  delete_record ->", callText(alice, "delete_record", map[string]any{"id": "7"}))

	fmt.Println("alice (admin, client WITHOUT elicitation):")
	alice2 := connect(url, "token-alice")
	fmt.Println("  delete_record ->", callText(alice2, "delete_record", map[string]any{"id": "7"}))
	fmt.Println("  delete_record+nonce ->", callText(alice2, "delete_record", map[string]any{"id": "7", "nonce": "n-123"}))

	fmt.Println("bob (viewer):")
	bob := connect(url, "token-bob")
	show(bob)
	fmt.Println("  delete_record ->", callText(bob, "delete_record", map[string]any{"id": "7"}))
}

type yes struct{}

func (yes) Elicit(_ context.Context, r mcp.ElicitationRequest) (*mcp.ElicitationResult, error) {
	fmt.Printf("  [client asked] %s -> accept\n", r.Params.Message)
	return &mcp.ElicitationResult{ElicitationResponse: mcp.ElicitationResponse{
		Action: mcp.ElicitationResponseActionAccept, Content: map[string]any{"confirm": true}}}, nil
}

func connect(url, token string, opts ...client.ClientOption) *client.Client {
	tr, err := transport.NewStreamableHTTP(url, transport.WithHTTPHeaders(map[string]string{"Authorization": "Bearer " + token}))
	if err != nil {
		log.Fatal(err)
	}
	c := client.NewClient(tr, opts...)
	if err := c.Start(context.Background()); err != nil {
		log.Fatal(err)
	}
	req := mcp.InitializeRequest{}
	req.Params.ProtocolVersion = "2025-06-18"
	if _, err := c.Initialize(context.Background(), req); err != nil {
		log.Fatal(err)
	}
	return c
}

func show(c *client.Client) {
	res, err := c.ListTools(context.Background(), mcp.ListToolsRequest{})
	if err != nil {
		log.Fatal(err)
	}
	var names []string
	for _, t := range res.Tools {
		names = append(names, t.Name)
	}
	fmt.Println("  tools:", names)
}

func callText(c *client.Client, name string, args map[string]any) string {
	req := mcp.CallToolRequest{}
	req.Params.Name, req.Params.Arguments = name, args
	res, err := c.CallTool(context.Background(), req)
	if err != nil {
		return "error: " + err.Error()
	}
	return res.Content[0].(mcp.TextContent).Text
}

func ptr(b bool) *bool { return &b }
