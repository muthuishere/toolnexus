package toolnexus

// MCP resource tools (change add-go-host-asks, ask #7.3): list_mcp_resources
// and read_mcp_resource over the servers an McpSource connected. Opt-in: a host
// registers McpSource.ResourceTools().

import (
	"context"
	"fmt"
	"sort"
	"strings"

	"github.com/mark3labs/mcp-go/mcp"
)

// ResourceTools returns list_mcp_resources and read_mcp_resource bound to this
// source's connected servers. Both are read-only. They stop working when the
// source is closed. A nil or server-less source still returns the tools; they
// report that no server is connected.
func (m *McpSource) ResourceTools() []Tool {
	ro := &ToolHints{ReadOnly: true}
	conn := func(server string) (mcpServerConn, error) {
		if m != nil {
			if c, ok := m.servers[server]; ok {
				return c, nil
			}
		}
		return mcpServerConn{}, fmt.Errorf("no connected MCP server %q (connected: %s)", server, strings.Join(m.serverNames(), ", "))
	}
	parent := func(tctx *ToolContext) context.Context {
		if tctx != nil && tctx.Ctx != nil {
			return tctx.Ctx
		}
		return context.Background()
	}
	list := Tool{
		Name:        "list_mcp_resources",
		Description: "List the resources MCP servers offer (uri, name, description, mimeType). Optionally for one server.",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{
			"server": map[string]any{"type": "string", "description": "Only this server (default: all)"},
		}, "additionalProperties": false},
		Source: SourceMCP,
		Hints:  ro,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			names := m.serverNames()
			if s, _ := argString(args, "server"); s != "" {
				if _, err := conn(s); err != nil {
					return ToolResult{Output: "list_mcp_resources: " + err.Error(), IsError: true}, nil
				}
				names = []string{s}
			}
			var lines []string
			var items []any
			for _, name := range names {
				c, _ := conn(name)
				ctx, cancel := context.WithTimeout(parent(tctx), c.timeout)
				res, err := c.client.ListResources(ctx, mcp.ListResourcesRequest{})
				cancel()
				if err != nil {
					lines = append(lines, fmt.Sprintf("%s: error: %v", name, err))
					continue
				}
				for _, r := range res.Resources {
					line := fmt.Sprintf("%s %s", name, r.URI)
					if r.Name != "" {
						line += " — " + r.Name
					}
					if r.MIMEType != "" {
						line += " (" + r.MIMEType + ")"
					}
					if r.Description != "" {
						line += ": " + r.Description
					}
					lines = append(lines, line)
					items = append(items, map[string]any{"server": name, "uri": r.URI, "name": r.Name, "mimeType": r.MIMEType, "description": r.Description})
				}
			}
			if len(lines) == 0 {
				return ToolResult{Output: "No resources.", Metadata: map[string]any{"count": 0}}, nil
			}
			return ToolResult{Output: strings.Join(lines, "\n"), Metadata: map[string]any{"count": len(items), "resources": items}}, nil
		},
	}
	read := Tool{
		Name:        "read_mcp_resource",
		Description: "Read one MCP resource by server and uri. Text comes back as text; binary content is described, not inlined.",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{
			"server": map[string]any{"type": "string", "description": "The MCP server name"},
			"uri":    map[string]any{"type": "string", "description": "The resource uri"},
		}, "required": []string{"server", "uri"}, "additionalProperties": false},
		Source: SourceMCP,
		Hints:  ro,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			server, _ := argString(args, "server")
			uri, _ := argString(args, "uri")
			c, err := conn(server)
			if err != nil {
				return ToolResult{Output: "read_mcp_resource: " + err.Error(), IsError: true}, nil
			}
			if uri == "" {
				return ToolResult{Output: "read_mcp_resource: uri is required", IsError: true}, nil
			}
			ctx, cancel := context.WithTimeout(parent(tctx), c.timeout)
			defer cancel()
			req := mcp.ReadResourceRequest{}
			req.Params.URI = uri
			res, err := c.client.ReadResource(ctx, req)
			if err != nil {
				return ToolResult{Output: fmt.Sprintf("read_mcp_resource: %v", err), IsError: true}, nil
			}
			var parts []string
			for _, rc := range res.Contents {
				switch v := rc.(type) {
				case mcp.TextResourceContents:
					parts = append(parts, v.Text)
				case *mcp.TextResourceContents:
					parts = append(parts, v.Text)
				case mcp.BlobResourceContents:
					parts = append(parts, fmt.Sprintf("[binary %s, %d base64 chars]", v.MIMEType, len(v.Blob)))
				case *mcp.BlobResourceContents:
					parts = append(parts, fmt.Sprintf("[binary %s, %d base64 chars]", v.MIMEType, len(v.Blob)))
				}
			}
			return ToolResult{Output: strings.Join(parts, "\n"), Metadata: map[string]any{"server": server, "uri": uri}}, nil
		},
	}
	return []Tool{list, read}
}

func (m *McpSource) serverNames() []string {
	if m == nil {
		return nil
	}
	names := make([]string, 0, len(m.servers))
	for n := range m.servers {
		names = append(names, n)
	}
	sort.Strings(names)
	return names
}
