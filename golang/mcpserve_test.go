// MCP (inbound) tests — a toolkit exposed AS an MCP server (SPEC §7C). The
// in-process client (mark3labs NewInProcessClient) drives tools/list + tools/call
// against a built server; the streamable-HTTP path is exercised through a served
// toolkit with the mcp-go streamable HTTP client. Mirrors the js "mcp inbound:"
// tests. Run: go test -race .

package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"

	mcpclient "github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
)

// echoTool echoes its `text` argument back verbatim (source: native).
var echoTool = Tool{
	Name:        "echo",
	Description: "echo back the text",
	Source:      SourceNative,
	InputSchema: JSONSchema{
		"type":                 "object",
		"properties":           map[string]any{"text": map[string]any{"type": "string"}},
		"required":             []any{"text"},
		"additionalProperties": false,
	},
	Execute: func(args map[string]any, _ *ToolContext) (ToolResult, error) {
		text, _ := args["text"].(string)
		return ToolResult{Output: text}, nil
	},
}

// boomTool always errors — proves an Execute error becomes an isError result
// without crashing the server.
var boomTool = Tool{
	Name:        "boom",
	Description: "always explodes",
	Source:      SourceNative,
	InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}, "additionalProperties": false},
	Execute: func(_ map[string]any, _ *ToolContext) (ToolResult, error) {
		return ToolResult{}, errors.New("kaboom")
	},
}

// connectInProc builds an initialized in-process MCP client for the server.
func connectInProc(t *testing.T, srv *server.MCPServer) *mcpclient.Client {
	t.Helper()
	c, err := mcpclient.NewInProcessClient(srv)
	if err != nil {
		t.Fatalf("NewInProcessClient: %v", err)
	}
	if err := c.Start(context.Background()); err != nil {
		t.Fatalf("client Start: %v", err)
	}
	if _, err := c.Initialize(context.Background(), mcp.InitializeRequest{}); err != nil {
		t.Fatalf("client Initialize: %v", err)
	}
	return c
}

func toolNames(tools []mcp.Tool) []string {
	names := make([]string, len(tools))
	for i, t := range tools {
		names[i] = t.Name
	}
	return names
}

func TestMcpInboundExposedFilter(t *testing.T) {
	tools := []Tool{echoTool, boomTool}
	if got := names(ExposedMcpTools(tools, nil)); !reflect.DeepEqual(got, []string{"echo", "boom"}) {
		t.Fatalf("omit ⇒ all: got %v", got)
	}
	if got := names(ExposedMcpTools(tools, &MCPServeConfig{Tools: []string{"echo"}})); !reflect.DeepEqual(got, []string{"echo"}) {
		t.Fatalf("filter: got %v", got)
	}
	// unknown names in the filter are ignored, not an error
	if got := names(ExposedMcpTools(tools, &MCPServeConfig{Tools: []string{"echo", "nope"}})); !reflect.DeepEqual(got, []string{"echo"}) {
		t.Fatalf("unknown ignored: got %v", got)
	}
}

func names(tools []Tool) []string {
	out := make([]string, len(tools))
	for i, t := range tools {
		out[i] = t.Name
	}
	return out
}

func TestMcpInboundInitializeServerInfo(t *testing.T) {
	srv := buildMcpServer([]Tool{echoTool}, &MCPServeConfig{Name: "gateway", Version: "2.0.0"}, nil)
	c, err := mcpclient.NewInProcessClient(srv)
	if err != nil {
		t.Fatalf("NewInProcessClient: %v", err)
	}
	defer c.Close()
	if err := c.Start(context.Background()); err != nil {
		t.Fatalf("Start: %v", err)
	}
	res, err := c.Initialize(context.Background(), mcp.InitializeRequest{})
	if err != nil {
		t.Fatalf("Initialize: %v", err)
	}
	if res.ServerInfo.Name != "gateway" {
		t.Fatalf("serverInfo.name = %q, want gateway", res.ServerInfo.Name)
	}
	if res.ServerInfo.Version != "2.0.0" {
		t.Fatalf("serverInfo.version = %q, want 2.0.0", res.ServerInfo.Version)
	}
}

func TestMcpInboundToolsListSchema(t *testing.T) {
	srv := buildMcpServer([]Tool{echoTool, boomTool}, nil, nil)
	c := connectInProc(t, srv)
	defer c.Close()

	res, err := c.ListTools(context.Background(), mcp.ListToolsRequest{})
	if err != nil {
		t.Fatalf("ListTools: %v", err)
	}
	got := toolNames(res.Tools)
	if len(got) != 2 || !contains(got, "echo") || !contains(got, "boom") {
		t.Fatalf("tool names = %v, want echo+boom", got)
	}
	var echo *mcp.Tool
	for i := range res.Tools {
		if res.Tools[i].Name == "echo" {
			echo = &res.Tools[i]
		}
	}
	if echo == nil {
		t.Fatal("echo tool missing")
	}
	// inputSchema round-trips byte-equal to the tool's parameters.
	if !schemasEqual(t, echo.InputSchema, echoTool.InputSchema) {
		gotJSON, _ := json.Marshal(echo.InputSchema)
		wantJSON, _ := json.Marshal(echoTool.InputSchema)
		t.Fatalf("inputSchema = %s, want %s", gotJSON, wantJSON)
	}
}

func TestMcpInboundToolsFilterNarrows(t *testing.T) {
	cfg := &MCPServeConfig{Tools: []string{"echo"}}
	srv := buildMcpServer(ExposedMcpTools([]Tool{echoTool, boomTool}, cfg), cfg, nil)
	c := connectInProc(t, srv)
	defer c.Close()

	res, err := c.ListTools(context.Background(), mcp.ListToolsRequest{})
	if err != nil {
		t.Fatalf("ListTools: %v", err)
	}
	if got := toolNames(res.Tools); !reflect.DeepEqual(got, []string{"echo"}) {
		t.Fatalf("filtered list = %v, want [echo]", got)
	}
}

func TestMcpInboundCallDispatch(t *testing.T) {
	var calls []OnCallEvent
	srv := buildMcpServer([]Tool{echoTool}, nil, func(ev OnCallEvent) { calls = append(calls, ev) })
	c := connectInProc(t, srv)
	defer c.Close()

	req := mcp.CallToolRequest{}
	req.Params.Name = "echo"
	req.Params.Arguments = map[string]any{"text": "hi"}
	res, err := c.CallTool(context.Background(), req)
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	if res.IsError {
		t.Fatalf("isError = true, want false")
	}
	if text := textOf(t, res); text != "hi" {
		t.Fatalf("content text = %q, want hi", text)
	}
	if len(calls) != 1 {
		t.Fatalf("onCall count = %d, want 1", len(calls))
	}
	if calls[0].Name != "echo" || calls[0].Source != SourceNative || calls[0].IsError {
		t.Fatalf("onCall event = %+v", calls[0])
	}
}

func TestMcpInboundErrorSurvives(t *testing.T) {
	srv := buildMcpServer([]Tool{echoTool, boomTool}, nil, nil)
	c := connectInProc(t, srv)
	defer c.Close()

	boom := mcp.CallToolRequest{}
	boom.Params.Name = "boom"
	boom.Params.Arguments = map[string]any{}
	bad, err := c.CallTool(context.Background(), boom)
	if err != nil {
		t.Fatalf("CallTool(boom): %v", err)
	}
	if !bad.IsError {
		t.Fatalf("boom isError = false, want true")
	}
	if text := textOf(t, bad); !strings.Contains(text, "kaboom") {
		t.Fatalf("boom text = %q, want to contain kaboom", text)
	}

	// server keeps serving
	ok := mcp.CallToolRequest{}
	ok.Params.Name = "echo"
	ok.Params.Arguments = map[string]any{"text": "still up"}
	res, err := c.CallTool(context.Background(), ok)
	if err != nil {
		t.Fatalf("CallTool(echo) after boom: %v", err)
	}
	if res.IsError {
		t.Fatalf("echo isError = true after boom")
	}
	if text := textOf(t, res); text != "still up" {
		t.Fatalf("echo text = %q, want 'still up'", text)
	}
}

func TestMcpInboundStreamableHTTPRoundTrip(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{echoTool}})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{MCP: &MCPServeConfig{Name: "http-gw"}})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()

	c, err := mcpclient.NewStreamableHttpClient(handle.URL + "/mcp")
	if err != nil {
		t.Fatalf("NewStreamableHttpClient: %v", err)
	}
	defer c.Close()
	if err := c.Start(context.Background()); err != nil {
		t.Fatalf("Start: %v", err)
	}
	initRes, err := c.Initialize(context.Background(), mcp.InitializeRequest{})
	if err != nil {
		t.Fatalf("Initialize: %v", err)
	}
	if initRes.ServerInfo.Name != "http-gw" {
		t.Fatalf("serverInfo.name = %q, want http-gw", initRes.ServerInfo.Name)
	}
	list, err := c.ListTools(context.Background(), mcp.ListToolsRequest{})
	if err != nil {
		t.Fatalf("ListTools: %v", err)
	}
	if !contains(toolNames(list.Tools), "echo") {
		t.Fatalf("tools = %v, want to contain echo", toolNames(list.Tools))
	}
	req := mcp.CallToolRequest{}
	req.Params.Name = "echo"
	req.Params.Arguments = map[string]any{"text": "over http"}
	res, err := c.CallTool(context.Background(), req)
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	if text := textOf(t, res); text != "over http" {
		t.Fatalf("text = %q, want 'over http'", text)
	}
}

func TestMcpInboundAbsentNoSurface(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{echoTool}})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	client := CreateClient(ClientOptions{BaseURL: "http://unused", Style: StyleOpenAI, Model: "x", APIKey: "k"})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{Client: client, A2A: &A2AConfig{Name: "only-a2a"}}) // no MCP
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()

	// A POST /mcp with an MCP-style JSON-RPC body is handled by the A2A POST
	// handler as an unknown method (not an MCP endpoint) → JSON-RPC error.
	out := rpcPost(t, handle.URL+"/mcp", "tools/list", map[string]any{})
	if _, ok := out["error"]; !ok {
		t.Fatalf("expected a JSON-RPC error for /mcp without an MCP profile; got %v", out)
	}
	if _, ok := out["result"]; ok {
		t.Fatalf("expected no result; got %v", out["result"])
	}
}

func TestMcpInboundTopLevelConfigBlock(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{
		McpConfig:  map[string]any{"mcpServer": map[string]any{"name": "from-config"}},
		Builtins:   false,
		ExtraTools: []Tool{echoTool},
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	// Serve() with no inline MCP falls back to the config block.
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()

	c, err := mcpclient.NewStreamableHttpClient(handle.URL + "/mcp")
	if err != nil {
		t.Fatalf("NewStreamableHttpClient: %v", err)
	}
	defer c.Close()
	if err := c.Start(context.Background()); err != nil {
		t.Fatalf("Start: %v", err)
	}
	res, err := c.Initialize(context.Background(), mcp.InitializeRequest{})
	if err != nil {
		t.Fatalf("Initialize: %v", err)
	}
	if res.ServerInfo.Name != "from-config" {
		t.Fatalf("serverInfo.name = %q, want from-config", res.ServerInfo.Name)
	}
}

// --- helpers -------------------------------------------------------------

func contains(ss []string, s string) bool {
	for _, x := range ss {
		if x == s {
			return true
		}
	}
	return false
}

// textOf extracts the first text content part of a CallToolResult.
func textOf(t *testing.T, res *mcp.CallToolResult) string {
	t.Helper()
	if len(res.Content) == 0 {
		t.Fatalf("result has no content")
	}
	tc, ok := mcp.AsTextContent(res.Content[0])
	if !ok {
		t.Fatalf("first content part is not text: %T", res.Content[0])
	}
	return tc.Text
}

// schemasEqual compares the served inputSchema against the original tool schema
// by normalizing both to generic JSON.
func schemasEqual(t *testing.T, got mcp.ToolInputSchema, want JSONSchema) bool {
	t.Helper()
	gb, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("marshal got schema: %v", err)
	}
	wb, err := json.Marshal(want)
	if err != nil {
		t.Fatalf("marshal want schema: %v", err)
	}
	var gm, wm map[string]any
	_ = json.Unmarshal(gb, &gm)
	_ = json.Unmarshal(wb, &wm)
	return reflect.DeepEqual(gm, wm)
}
