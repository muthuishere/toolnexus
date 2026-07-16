// MCP — INBOUND: serve a toolkit as an MCP server (SPEC §7C).
//
// The inbound mirror of the A2A Serve (§7B). Where A2A advertises the toolkit's
// SKILLS and fulfils a Task through the whole client loop, MCP advertises the
// toolkit's UNIFIED tools (every source — mcp · skill · native · http · builtin ·
// a2a) and dispatches each tools/call straight to Tool.Execute. The calling MCP
// client IS the LLM host, so there is no client, no Task, and no TaskStore — just
// tools/list + tools/call over the toolkit registry.
//
// Transport — streamable-HTTP, built on the same github.com/mark3labs/mcp-go the
// client side already uses: mounted at POST /mcp on the Serve(addr, …) server,
// alongside the A2A routes (see serve.go).

package toolnexus

import (
	"context"
	"encoding/json"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
)

const (
	mcpServeDefaultName    = "toolnexus"
	mcpServeDefaultVersion = "0.1.0"
)

// MCPServeConfig is the opt-in MCP serve profile — the toolkit is exposed as an
// MCP server.
type MCPServeConfig struct {
	// Name is the advertised server name (initialize serverInfo.name). Default
	// "toolnexus".
	Name string `json:"name,omitempty"`
	// Version is the advertised server version. Default "0.1.0".
	Version string `json:"version,omitempty"`
	// Tools is the subset of tool names to expose; nil ⇒ all. Unknown names in
	// the filter are ignored (never an error).
	Tools []string `json:"tools,omitempty"`
}

// OnCallEvent is surfaced on each inbound tools/call.
type OnCallEvent struct {
	Name    string
	Source  ToolSource
	Ms      int64
	IsError bool
}

// OnCall is the per-call callback passed to Serve.
type OnCall func(ev OnCallEvent)

// ExposedMcpTools returns the tools this profile exposes: every toolkit tool,
// filtered to cfg.Tools when set (unknown names in the filter are ignored, never
// an error).
func ExposedMcpTools(tools []Tool, cfg *MCPServeConfig) []Tool {
	if cfg == nil || cfg.Tools == nil {
		return tools
	}
	wanted := make(map[string]bool, len(cfg.Tools))
	for _, n := range cfg.Tools {
		wanted[n] = true
	}
	out := make([]Tool, 0, len(tools))
	for _, t := range tools {
		if wanted[t.Name] {
			out = append(out, t)
		}
	}
	return out
}

// buildMcpServer builds a mark3labs MCPServer exposing tools as MCP tools.
// tools/list maps each Tool's name (verbatim — already sanitized at
// registration), description, and inputSchema; tools/call dispatches to
// Tool.Execute and maps the ToolResult to a CallToolResult (output → text,
// isError propagates). A fresh server is cheap to build, so the HTTP path makes
// one per server instance (stateless).
func buildMcpServer(tools []Tool, cfg *MCPServeConfig, onCall OnCall) *server.MCPServer {
	name := mcpServeDefaultName
	version := mcpServeDefaultVersion
	if cfg != nil {
		if cfg.Name != "" {
			name = cfg.Name
		}
		if cfg.Version != "" {
			version = cfg.Version
		}
	}

	srv := server.NewMCPServer(name, version, server.WithToolCapabilities(false))
	for _, t := range tools {
		srv.AddTool(mcpToolFor(t), mcpHandlerFor(t, onCall))
	}
	return srv
}

// mcpToolFor builds the wire tool definition for t. The name is used verbatim
// (already sanitized at registration — NOT re-sanitized) and the inputSchema is
// the tool's parameters JSON Schema, passed through as a raw schema so it is
// advertised byte-for-byte.
func mcpToolFor(t Tool) mcp.Tool {
	schema := t.InputSchema
	if schema == nil {
		schema = JSONSchema{}
	}
	raw, err := json.Marshal(schema)
	if err != nil {
		raw = []byte("{}")
	}
	return mcp.NewToolWithRawSchema(t.Name, t.Description, raw)
}

// mcpHandlerFor wraps Tool.Execute as an MCP tool handler. An Execute error (or a
// non-nil error return) becomes an isError result — never a protocol crash. The
// onCall callback fires per call and its own errors/panics are isolated.
func mcpHandlerFor(t Tool, onCall OnCall) server.ToolHandlerFunc {
	return func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		started := time.Now()

		var tctx *ToolContext
		if ctx != nil {
			tctx = &ToolContext{Ctx: ctx}
		}
		res, err := t.Execute(req.GetArguments(), tctx)
		if err != nil {
			res = ToolResult{Output: err.Error(), IsError: true}
		}

		if onCall != nil {
			func() {
				defer func() { _ = recover() }()
				onCall(OnCallEvent{
					Name:    t.Name,
					Source:  t.Source,
					Ms:      time.Since(started).Milliseconds(),
					IsError: res.IsError,
				})
			}()
		}

		out := mcp.NewToolResultText(res.Output)
		out.IsError = res.IsError
		return out, nil
	}
}
