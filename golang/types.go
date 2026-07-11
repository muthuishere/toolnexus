// Package toolnexus gives any LLM the same two dynamic capabilities
// opencode has: dynamic MCP servers and dynamic agent skills, surfaced as a
// single uniform Toolkit with provider adapters. See ../SPEC.md.
package toolnexus

import (
	"context"
	"regexp"
	"strconv"
	"sync/atomic"
	"time"
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
	// Answer is present ONLY on a post-WaitFor retry (§10): the resolution of a
	// prior suspension. A tool reads Answer.Data when the resolution IS the
	// payload (kind:"input"), and ignores it when the world changed out-of-band
	// (kind:"authorization" — the session is now valid). Mirrors JS ToolContext.answer.
	Answer *Answer
}

// Request is byte-identical suspension wire data (§10). A tool that needs an
// out-of-band, async resolution (login, approval, input) returns a normal
// ToolResult carrying metadata.pending = Request. Keys are pinned across all
// ports (they serialize over the wire and cross agent boundaries) — NOT
// idiomatic-cased like RunResult.
type Request struct {
	ID        string         `json:"id"`   // unique per suspension; the correlation key
	Kind      string         `json:"kind"` // "authorization" | "approval" | "input" | ... (open vocabulary)
	Prompt    string         `json:"prompt"`
	URL       string         `json:"url,omitempty"`       // present when the action happens at a link
	Data      map[string]any `json:"data,omitempty"`      // kind-specific extra
	ExpiresAt string         `json:"expiresAt,omitempty"` // RFC3339; stale after this
}

// Answer is byte-identical resolution wire data (§10). It echoes Request.ID.
type Answer struct {
	ID   string         `json:"id"`             // echoes Request.ID
	Ok   bool           `json:"ok"`             // satisfied, vs declined / aborted / expired
	Data map[string]any `json:"data,omitempty"` // kind-specific payload (e.g. the value entered)
	// Reason: when Ok is false, why (advisory — the loop rule branches only on Ok).
	// Distinguishes an explicit refusal from a dismissal/timeout; the MCP elicitation
	// bridge maps "declined"→decline and everything else→cancel. "declined"|"cancelled"|"expired". (R1)
	Reason string `json:"reason,omitempty"`
}

var pendingSeq atomic.Uint64

// Pending is a producer helper: it returns a suspension — a ToolResult with
// metadata.pending = a Request. If req.ID is empty a unique id is generated.
// Mirrors JS pending(). Sugar, not required (any ToolResult with a Request under
// metadata.pending is a suspension).
func Pending(req Request) ToolResult {
	if req.ID == "" {
		req.ID = "pnd-" + strconv.FormatInt(time.Now().UnixMilli(), 36) + "-" + strconv.FormatUint(pendingSeq.Add(1), 10)
	}
	out := req.Prompt
	if req.URL != "" {
		out += "\n" + req.URL
	}
	return ToolResult{
		Output:   out,
		IsError:  true,
		Metadata: map[string]any{"pending": req},
	}
}

// AuthRequired is sugar for the common case: kind:"authorization" at a login URL.
// Mirrors JS authRequired().
func AuthRequired(url, prompt string) ToolResult {
	if prompt == "" {
		prompt = "Authorization required to continue"
	}
	return Pending(Request{Kind: "authorization", Prompt: prompt, URL: url})
}

// PendingOf reads the suspension off a result, if any (§10). Returns nil when the
// result is not a suspension. Mirrors JS pendingOf().
func PendingOf(result ToolResult) *Request {
	if result.Metadata == nil {
		return nil
	}
	switch v := result.Metadata["pending"].(type) {
	case Request:
		r := v
		return &r
	case *Request:
		return v
	default:
		return nil
	}
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
