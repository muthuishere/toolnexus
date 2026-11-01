package toolnexus

import "github.com/mark3labs/mcp-go/mcp"

func mcpAnn(ro, de, ow *bool) mcp.ToolAnnotation {
	return mcp.ToolAnnotation{ReadOnlyHint: ro, DestructiveHint: de, OpenWorldHint: ow}
}
