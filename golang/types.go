// Package toolnexus gives any LLM the same two dynamic capabilities
// opencode has: dynamic MCP servers and dynamic agent skills, surfaced as a
// single uniform Toolkit with provider adapters. See ../SPEC.md.
package toolnexus

import (
	"context"
	"regexp"
)

// JSONSchema is a JSON-Schema object ({type:"object", properties, ...}).
// Modeled as a free-form map so it serializes byte-identically to the JS
// reference implementation.
type JSONSchema = map[string]any

// ToolSource identifies where a tool came from.
type ToolSource string

const (
	SourceMCP     ToolSource = "mcp"
	SourceSkill   ToolSource = "skill"
	SourceNative  ToolSource = "native"
	SourceHTTP    ToolSource = "http"
	SourceCustom  ToolSource = "custom"
	SourceBuiltin ToolSource = "builtin"
	SourceA2A     ToolSource = "a2a"
)

// ToolResult is the uniform result handed back to the model.
type ToolResult struct {
	Output   string         `json:"output"`
	IsError  bool           `json:"isError"`
	Metadata map[string]any `json:"metadata,omitempty"`
}

// ToolContext is optional per-call context.
type ToolContext struct {
	// Ctx carries cancellation (mirrors the JS AbortSignal).
	Ctx context.Context
	// Timeout overrides the tool's default timeout, in milliseconds.
	Timeout int
}

// Tool is the single uniform abstraction. Mirrors opencode's dynamicTool.
type Tool struct {
	Name        string     `json:"name"`
	Description string     `json:"description"`
	InputSchema JSONSchema `json:"inputSchema"`
	Source      ToolSource `json:"source"`
	// Execute runs the tool. ctx may be nil.
	Execute func(args map[string]any, ctx *ToolContext) (ToolResult, error) `json:"-"`
}

// McpStatus is the queryable connection state of a server.
type McpStatus string

const (
	StatusConnected McpStatus = "connected"
	StatusDisabled  McpStatus = "disabled"
	StatusFailed    McpStatus = "failed"
)

var sanitizeRe = regexp.MustCompile(`[^a-zA-Z0-9_-]`)

// Sanitize replaces anything outside [a-zA-Z0-9_-] with "_" (same as opencode).
func Sanitize(value string) string {
	return sanitizeRe.ReplaceAllString(value, "_")
}
