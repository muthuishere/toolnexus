// Dynamic MCP source. Mirrors opencode's mcp/index.ts + mcp/catalog.ts, but
// standalone: connect to every configured server, list tools, and convert each
// into a uniform Tool. Protocol 2026-07-28 is spoken first by toolnexus's own
// client (mcp2026.go); this SDK-based path (github.com/mark3labs/mcp-go) is the
// FROZEN FALLBACK for older servers (SPEC §2.2a, §2.F), reached only when
// server/discover is not answered the 2026 way.

package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	mcpclient "github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/client/transport"
	"github.com/mark3labs/mcp-go/mcp"
)

const (
	defaultTimeout = 30 * time.Second
)

// ---------------------------------------------------------------------------
// MCP elicitation bridge (§10). A server can ask US for input mid-`tools/call`
// (a reverse-request). We map it onto the one WaitFor: form mode → kind:"input",
// URL mode → kind:"authorization". The two mappings are pure + byte-parity-tested.
// ---------------------------------------------------------------------------

var elcSeq atomic.Uint64

// ElicitationToRequest maps an MCP `elicitation/create` request onto a §10 Request.
func ElicitationToRequest(params mcp.ElicitationParams) Request {
	isURL := params.Mode == "url"
	req := Request{
		ID:     "elc-" + strconv.FormatInt(time.Now().UnixMilli(), 36) + "-" + strconv.FormatUint(elcSeq.Add(1), 10),
		Prompt: params.Message,
	}
	if isURL {
		req.Kind = "authorization"
		if params.URL != "" {
			req.URL = params.URL
		}
	} else {
		req.Kind = "input"
		if params.RequestedSchema != nil {
			req.Data = map[string]any{"schema": params.RequestedSchema}
		}
	}
	return req
}

// AnswerToElicitResult maps a resolved §10 Answer back onto an MCP ElicitationResult.
// Ok→accept; Reason=="declined"→decline; everything else→cancel.
func AnswerToElicitResult(answer Answer) *mcp.ElicitationResult {
	res := &mcp.ElicitationResult{}
	if answer.Ok {
		res.Action = mcp.ElicitationResponseActionAccept
		if answer.Data != nil {
			res.Content = answer.Data
		} else {
			res.Content = map[string]any{}
		}
		return res
	}
	if answer.Reason == "declined" {
		res.Action = mcp.ElicitationResponseActionDecline
	} else {
		res.Action = mcp.ElicitationResponseActionCancel
	}
	return res
}

// elicitationBridge adapts a host WaitFor onto the SDK's ElicitationHandler: it
// maps ElicitationRequest→Request→WaitFor→Answer→ElicitationResult.
type elicitationBridge struct {
	waitFor func(Request) (Answer, error)
}

func (b elicitationBridge) Elicit(ctx context.Context, request mcp.ElicitationRequest) (*mcp.ElicitationResult, error) {
	answer, err := b.waitFor(ElicitationToRequest(request.Params))
	if err != nil {
		return nil, err
	}
	return AnswerToElicitResult(answer), nil
}

// elicitationOptions returns the client options that advertise the elicitation
// capability + register the bridge — ONLY when a waitFor exists to satisfy it. A
// waitFor-less host degrades cleanly (the server won't elicit). (§10 elicitation bridge)
func elicitationOptions(waitFor func(Request) (Answer, error)) []mcpclient.ClientOption {
	if waitFor == nil {
		return nil
	}
	return []mcpclient.ClientOption{mcpclient.WithElicitationHandler(elicitationBridge{waitFor})}
}

// ServerConfig is a single MCP server entry. The same struct holds both local
// (stdio) and remote (HTTP/SSE) fields; the active transport is inferred.
type ServerConfig struct {
	Type        string            `json:"type"` // "local" | "remote" | "" (inferred)
	Command     []string          `json:"command"`
	Environment map[string]string `json:"environment"`
	Env         map[string]string `json:"env"` // alias for Environment
	Cwd         string            `json:"cwd"`
	URL         string            `json:"url"`
	Headers     map[string]string `json:"headers"`
	Enabled     *bool             `json:"enabled"`
	Disabled    *bool             `json:"disabled"`
	Timeout     int               `json:"timeout"` // milliseconds
	// Tools (§2 Gap 7) is a per-server tool allowlist keyed on ORIGINAL tool name;
	// same semantics as the builtins/skills filter (nil/empty ⇒ all; ≥1 true ⇒
	// allowlist; only-false ⇒ drop-list; unknown ⇒ ignore+warn). Applied to the
	// listed defs before sanitize/prefix.
	Tools map[string]bool `json:"tools"`
	// Auth configures OAuth for a remote server (§2A). Absent ⇒ a 401 still
	// triggers the flow (CIMD if the AS supports it).
	Auth *McpAuthConfig `json:"auth,omitempty"`
	// Description says what this server is for (tool search, §2.8).
	Description string `json:"description,omitempty"`
	// ToolSearch exposes <server>_search_tools + <server>_call_tool instead of
	// every server tool (§2.8; 2026 path only).
	ToolSearch bool `json:"toolSearch,omitempty"`
}

// McpConfig maps server name -> config.
type McpConfig map[string]ServerConfig

func (c ServerConfig) isRemote() bool {
	if c.Type == "remote" {
		return true
	}
	if c.Type == "local" {
		return false
	}
	return c.URL != ""
}

func (c ServerConfig) isEnabled() bool {
	if c.Disabled != nil && *c.Disabled {
		return false
	}
	if c.Enabled != nil && !*c.Enabled {
		return false
	}
	return true
}

func (c ServerConfig) timeout() time.Duration {
	if c.Timeout > 0 {
		return time.Duration(c.Timeout) * time.Millisecond
	}
	return defaultTimeout
}

// ParseMcpConfig accepts a file path, raw JSON bytes, an McpConfig, or a parsed
// map wrapped under mcpServers/servers/mcp (or the bare map).
func ParseMcpConfig(input any) (McpConfig, error) {
	switch v := input.(type) {
	case McpConfig:
		return v, nil
	case string:
		data, err := os.ReadFile(v)
		if err != nil {
			return nil, fmt.Errorf("read mcp config: %w", err)
		}
		return parseMcpBytes(data)
	case []byte:
		return parseMcpBytes(v)
	case map[string]any:
		// Re-marshal so we can route through the wrapper-key logic uniformly.
		data, err := json.Marshal(v)
		if err != nil {
			return nil, err
		}
		return parseMcpBytes(data)
	default:
		return nil, fmt.Errorf("unsupported mcp config type: %T", input)
	}
}

func parseMcpBytes(data []byte) (McpConfig, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("parse mcp config: %w", err)
	}
	// Accept top-level mcpServers / servers / mcp, else treat the whole object
	// as the server map.
	for _, key := range []string{"mcpServers", "servers", "mcp"} {
		if servers, ok := raw[key]; ok {
			cfg, err := decodeServers(servers)
			if err != nil {
				return nil, fmt.Errorf("parse mcp config %q: %w", key, err)
			}
			return cfg, nil
		}
	}
	// Bare map of servers — but strip the reserved sibling config keys
	// (builtins §4A, agents §7A, a2a §7B) so they are not mistaken for MCP servers when
	// no wrapper key is present.
	delete(raw, "builtins")
	delete(raw, "agents")
	delete(raw, "a2a")
	delete(raw, "mcpServer")
	stripped, err := json.Marshal(raw)
	if err != nil {
		return nil, fmt.Errorf("parse mcp config: %w", err)
	}
	cfg, err := decodeServers(stripped)
	if err != nil {
		return nil, fmt.Errorf("parse mcp config: %w", err)
	}
	return cfg, nil
}

// decodeServers decodes the server map, normalizing each entry's `command`
// (a string or an array of strings) plus optional `args` (array of strings,
// appended) into the Command argv. Errors name the server. A remote entry
// ignores `args`. No shell is involved: the argv is spawned as-is. (§2)
func decodeServers(data []byte) (McpConfig, error) {
	var rawServers map[string]json.RawMessage
	if err := json.Unmarshal(data, &rawServers); err != nil {
		return nil, err
	}
	if rawServers == nil {
		return nil, nil
	}
	cfg := make(McpConfig, len(rawServers))
	for name, body := range rawServers {
		var entry struct {
			ServerConfig
			Command json.RawMessage `json:"command"`
			Args    json.RawMessage `json:"args"`
		}
		if err := json.Unmarshal(body, &entry); err != nil {
			return nil, fmt.Errorf("mcp server %q: %w", name, err)
		}
		sc := entry.ServerConfig
		argv, err := normalizeCommand(name, entry.Command, entry.Args, sc.isRemote())
		if err != nil {
			return nil, err
		}
		sc.Command = argv
		cfg[name] = sc
	}
	return cfg, nil
}

func isJSONNull(raw json.RawMessage) bool {
	return len(raw) == 0 || string(raw) == "null"
}

func normalizeCommand(name string, command, args json.RawMessage, remote bool) ([]string, error) {
	if isJSONNull(command) {
		// No command: a local server fails at connect time ("has no command"), as before.
		return nil, nil
	}
	var argv []string
	var str string
	if err := json.Unmarshal(command, &str); err == nil {
		if str == "" {
			return nil, fmt.Errorf("mcp server %q: invalid command: empty string", name)
		}
		argv = []string{str}
	} else {
		var arr []any
		if err := json.Unmarshal(command, &arr); err != nil {
			return nil, fmt.Errorf("mcp server %q: invalid command: must be a string or an array of strings", name)
		}
		if len(arr) == 0 {
			return nil, fmt.Errorf("mcp server %q: invalid command: empty array", name)
		}
		for _, el := range arr {
			s, ok := el.(string)
			if !ok {
				return nil, fmt.Errorf("mcp server %q: invalid command: array elements must be strings", name)
			}
			argv = append(argv, s)
		}
	}
	if remote || isJSONNull(args) {
		return argv, nil
	}
	var arr []any
	if err := json.Unmarshal(args, &arr); err != nil {
		return nil, fmt.Errorf("mcp server %q: invalid args: must be an array of strings", name)
	}
	for _, el := range arr {
		s, ok := el.(string)
		if !ok {
			return nil, fmt.Errorf("mcp server %q: invalid args: elements must be strings", name)
		}
		argv = append(argv, s)
	}
	return argv, nil
}

// ExpandEnvHeaders returns a NEW map with each header VALUE having ${ENV_VAR}
// replaced by os.Getenv(name) (unset vars become ""). nil in ⇒ nil out. Mirrors
// js/src/mcp.ts expandEnvHeaders. Values are never logged.
func ExpandEnvHeaders(headers map[string]string) map[string]string {
	if headers == nil {
		return nil
	}
	out := make(map[string]string, len(headers))
	for k, v := range headers {
		out[k] = envVarRe.ReplaceAllStringFunc(v, func(m string) string {
			name := envVarRe.FindStringSubmatch(m)[1]
			return os.Getenv(name)
		})
	}
	return out
}

func isTextContent(c mcp.Content) (string, bool) {
	if tc, ok := mcp.AsTextContent(c); ok {
		return tc.Text, true
	}
	return "", false
}

func formatToolErrorContent(content []mcp.Content) string {
	parts := make([]string, 0, len(content))
	for _, c := range content {
		if text, ok := isTextContent(c); ok {
			if strings.TrimSpace(text) != "" {
				parts = append(parts, text)
			}
		}
	}
	if len(parts) == 0 {
		return "MCP tool returned an error"
	}
	return strings.Join(parts, "\n\n")
}

func joinTextContent(content []mcp.Content) string {
	parts := make([]string, 0, len(content))
	for _, c := range content {
		if text, ok := isTextContent(c); ok {
			parts = append(parts, text)
			continue
		}
		// An embedded resource carrying TEXT is text: it joins output in place,
		// in content order, rather than becoming a part (§0.4).
		if er, ok := mcp.AsEmbeddedResource(c); ok {
			if tr, ok := mcp.AsTextResourceContents(er.Resource); ok {
				parts = append(parts, tr.Text)
			}
		}
	}
	return strings.Join(parts, "\n")
}

// mcpContentParts maps every NON-TEXT content[] entry to a §1B ContentPart:
// image⇒image, audio⇒audio, resource_link⇒file{url}, resource-with-blob⇒file{data}.
// A resource carrying text is not a part — joinTextContent appends it to output.
// Nothing is dropped silently, on any branch (§0.4).
func mcpContentParts(content []mcp.Content) []ContentPart {
	var parts []ContentPart
	for _, c := range content {
		switch {
		case isTextOf(c):
			// text ⇒ output, not a part
		default:
			if ic, ok := mcp.AsImageContent(c); ok {
				parts = append(parts, ContentPart{Type: PartImage, MimeType: ic.MIMEType, Data: ic.Data})
				continue
			}
			if ac, ok := mcp.AsAudioContent(c); ok {
				parts = append(parts, ContentPart{Type: PartAudio, MimeType: ac.MIMEType, Data: ac.Data})
				continue
			}
			// ResourceLink has no As… helper in mark3labs/mcp-go — match the type.
			if rl, ok := c.(mcp.ResourceLink); ok {
				parts = append(parts, ContentPart{Type: PartFile, MimeType: rl.MIMEType, URL: rl.URI, Name: rl.Name})
				continue
			}
			if rl, ok := c.(*mcp.ResourceLink); ok && rl != nil {
				parts = append(parts, ContentPart{Type: PartFile, MimeType: rl.MIMEType, URL: rl.URI, Name: rl.Name})
				continue
			}
			if er, ok := mcp.AsEmbeddedResource(c); ok {
				if br, ok := mcp.AsBlobResourceContents(er.Resource); ok {
					parts = append(parts, ContentPart{Type: PartFile, MimeType: br.MIMEType, Data: br.Blob, Name: br.URI})
				}
			}
		}
	}
	return parts
}

func isTextOf(c mcp.Content) bool {
	_, ok := isTextContent(c)
	return ok
}

// describeParts names the returned parts, so an image-only MCP result reaches the
// model as a description rather than an empty string (§0.4).
func describeParts(parts []ContentPart) string {
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		out = append(out, describePart(p))
	}
	return strings.Join(out, "\n")
}

// buildInputSchema mirrors the JS conversion: spread the server's schema, then
// force type:"object", default properties to {}, and additionalProperties:false.
func buildInputSchema(def mcp.Tool) JSONSchema {
	schema := JSONSchema{}
	in := def.InputSchema
	if len(in.Defs) > 0 {
		schema["$defs"] = in.Defs
	}
	if len(in.Required) > 0 {
		schema["required"] = in.Required
	}
	properties := map[string]any{}
	if in.Properties != nil {
		properties = in.Properties
	}
	schema["type"] = "object"
	schema["properties"] = properties
	schema["additionalProperties"] = false
	return schema
}

// progressRouter dispatches a client's notifications/progress to the in-flight
// tools/call that owns the token (§2 idle timeout). One per client; unmatched or
// unknown tokens are dropped, so one chatty call never holds another open.
type progressRouter struct {
	mu      sync.Mutex
	pending map[string]func()
}

var progressTokenSeq atomic.Uint64

func newProgressRouter(client *mcpclient.Client) *progressRouter {
	r := &progressRouter{pending: map[string]func(){}}
	client.OnNotification(func(n mcp.JSONRPCNotification) {
		if n.Method != "notifications/progress" {
			return
		}
		tok, ok := n.Params.AdditionalFields["progressToken"]
		if !ok || tok == nil {
			return
		}
		r.mu.Lock()
		reset := r.pending[fmt.Sprint(tok)]
		r.mu.Unlock()
		if reset != nil {
			reset()
		}
	})
	return r
}

func (r *progressRouter) register(token string, reset func()) func() {
	r.mu.Lock()
	r.pending[token] = reset
	r.mu.Unlock()
	return func() {
		r.mu.Lock()
		delete(r.pending, token)
		r.mu.Unlock()
	}
}

// errMcpIdleTimeout is the cancel cause when a tools/call sees `timeout` of
// silence (no matching progress notification and no response).
var errMcpIdleTimeout = errors.New("mcp tool call timed out")

func convertTool(server string, def mcp.Tool, client *mcpclient.Client, defTimeout time.Duration, router *progressRouter) Tool {
	name := def.Name
	return Tool{
		Name:        Sanitize(server) + "_" + Sanitize(def.Name),
		Description: def.Description,
		InputSchema: buildInputSchema(def),
		Source:      SourceMCP,
		Hints:       mcpHints(def.Annotations),
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			meta := map[string]any{"server": server}
			parent := context.Background()
			timeout := defTimeout
			if tctx != nil {
				if tctx.Ctx != nil {
					parent = tctx.Ctx
				}
				if tctx.Timeout > 0 {
					timeout = time.Duration(tctx.Timeout) * time.Millisecond
				}
			}
			// Idle timeout (§2): a context.WithTimeout deadline cannot be extended,
			// so a resettable timer cancels the call after `timeout` of silence; each
			// progress notification carrying this call's token re-arms it. Parent
			// cancellation still propagates through WithCancelCause.
			ctx, cancel := context.WithCancelCause(parent)
			defer cancel(nil)
			idle := time.AfterFunc(timeout, func() {
				cancel(fmt.Errorf("%w after %dms without progress", errMcpIdleTimeout, timeout.Milliseconds()))
			})
			defer idle.Stop()

			if args == nil {
				args = map[string]any{}
			}
			req := mcp.CallToolRequest{}
			req.Params.Name = name
			req.Params.Arguments = args
			token := fmt.Sprintf("toolnexus-%d", progressTokenSeq.Add(1))
			req.Params.Meta = &mcp.Meta{ProgressToken: token}
			if router != nil {
				defer router.register(token, func() {
					if ctx.Err() == nil {
						idle.Reset(timeout)
					}
				})()
			}

			result, err := client.CallTool(ctx, req)
			if err != nil {
				if cause := context.Cause(ctx); cause != nil && errors.Is(cause, errMcpIdleTimeout) {
					return ToolResult{Output: cause.Error(), IsError: true, Metadata: meta}, nil
				}
				return ToolResult{Output: err.Error(), IsError: true, Metadata: meta}, nil
			}
			// Collect parts BEFORE any branch: the structuredContent short-circuit
			// below never looks at Content, and the error path did not either —
			// either one would silently drop a returned image (§0.4).
			parts := mcpContentParts(result.Content)
			if result.IsError {
				return ToolResult{Output: formatToolErrorContent(result.Content), IsError: true, Parts: parts, Metadata: meta}, nil
			}
			if result.StructuredContent != nil {
				b, mErr := json.Marshal(result.StructuredContent)
				if mErr != nil {
					return ToolResult{Output: mErr.Error(), IsError: true, Metadata: meta}, nil
				}
				return ToolResult{Output: string(b), IsError: false, Parts: parts, Metadata: meta}, nil
			}
			output := joinTextContent(result.Content)
			if output == "" && len(parts) > 0 {
				output = describeParts(parts)
			}
			return ToolResult{Output: output, IsError: false, Parts: parts, Metadata: meta}, nil
		},
	}
}

// McpSource is the result of connecting to all configured servers.
type McpSource struct {
	Tools  []Tool
	Status map[string]McpStatus
	close  func()
	// servers are the connected clients by server name (for ResourceTools).
	servers map[string]mcpServerConn
	// SkillDirs hold skills downloaded from 2026 servers' skills extension
	// (§2.7); CreateToolkit adds them to the `skill` inventory. Removed on Close.
	SkillDirs []string
}

type mcpServerConn struct {
	client  *mcpclient.Client // frozen-fallback SDK client (nil on the 2026 path)
	v26     *mcp26Server      // 2026 path (nil on fallback)
	timeout time.Duration
}

// Close disconnects every connected client.
func (m *McpSource) Close() {
	if m != nil && m.close != nil {
		m.close()
	}
}

// applyToolsFilter (§2 Gap 7) applies a per-server tool allowlist to the listed
// defs. Same semantics as the builtins/skills filter. Keyed on ORIGINAL tool name.
func applyToolsFilter(server string, defs []mcp.Tool, filter map[string]bool) []mcp.Tool {
	return applyNameFilter(server, defs, func(d mcp.Tool) string { return d.Name }, filter)
}

// applyNameFilter is applyToolsFilter over any def shape (shared with the 2026 path).
func applyNameFilter[T any](server string, defs []T, nameOf func(T) string, filter map[string]bool) []T {
	if len(filter) == 0 {
		return defs
	}
	hasTrue := false
	for _, v := range filter {
		if v {
			hasTrue = true
			break
		}
	}
	present := map[string]bool{}
	for _, d := range defs {
		present[nameOf(d)] = true
	}
	for k := range filter {
		if !present[k] {
			log.Printf("[toolnexus] server %q tools filter name %q matched no tool", server, k)
		}
	}
	out := make([]T, 0, len(defs))
	for _, d := range defs {
		v, ok := filter[nameOf(d)]
		keep := false
		if hasTrue {
			keep = ok && v
		} else {
			keep = !(ok && !v)
		}
		if keep {
			out = append(out, d)
		}
	}
	return out
}

func connectServer(ctx context.Context, name string, cfg ServerConfig, waitFor func(Request) (Answer, error), auth ...*mcpOAuth) (*mcpclient.Client, error) {
	timeout := cfg.timeout()

	if cfg.isRemote() {
		var oa *mcpOAuth
		if len(auth) > 0 {
			oa = auth[0]
		}
		client, err := newRemoteClient(ctx, cfg, waitFor, oa)
		if err == nil {
			err = initClient(ctx, client, timeout)
			if err != nil {
				client.Close()
			}
		}
		// §2A.1a: a 401 from the SDK path triggers the same OAuth flow once
		// (through a token-less 2026 probe that reads WWW-Authenticate), then retries.
		if err != nil && oa != nil && errors.Is(err, transport.ErrUnauthorized) {
			probe := &mcp26Client{url: cfg.URL, headers: cfg.Headers, auth: oa}
			pctx, cancel := context.WithTimeout(ctx, timeout)
			_, perr := probe.call(pctx, "server/discover", nil)
			cancel()
			var ap *mcpAuthPending
			if errors.As(perr, &ap) {
				return nil, perr
			}
			client, err = newRemoteClient(ctx, cfg, waitFor, oa)
			if err == nil {
				if err = initClient(ctx, client, timeout); err != nil {
					client.Close()
				}
			}
		}
		if err != nil {
			return nil, err
		}
		return client, nil
	}

	if len(cfg.Command) == 0 {
		return nil, fmt.Errorf("local server %q has no command", name)
	}
	envMap := cfg.Environment
	if envMap == nil {
		envMap = cfg.Env
	}
	env := append([]string{}, os.Environ()...)
	for k, v := range envMap {
		env = append(env, k+"="+v)
	}
	var opts []transport.StdioOption
	if cfg.Cwd != "" {
		opts = append(opts, transport.WithCommandFunc(func(ctx context.Context, command string, environ, args []string) (*exec.Cmd, error) {
			cmd := exec.CommandContext(ctx, command, args...)
			cmd.Env = environ
			cmd.Dir = cfg.Cwd
			return cmd, nil
		}))
	}
	// Build the transport + client directly (instead of the NewStdioMCPClientWithOptions
	// convenience) so the elicitation handler can be attached when a waitFor is present.
	stdioTransport := transport.NewStdioWithOptions(cfg.Command[0], env, cfg.Command[1:], opts...)
	// The stdio child is spawned via exec.CommandContext, so its lifetime is bound to the context
	// passed to Start — it must be a background context torn down by client.Close(), NOT a
	// timeout/cancel context, or the child is SIGKILL'd the moment Start returns (breaking
	// tools/list). Local exec Start does not meaningfully block; parent-ctx cancellation is honored
	// by initClient/listTools below.
	if err := stdioTransport.Start(context.Background()); err != nil {
		return nil, fmt.Errorf("failed to start stdio transport: %w", err)
	}
	client := mcpclient.NewClient(stdioTransport, elicitationOptions(waitFor)...)
	// client.Start on the already-started transport is idempotent; it is what wires the
	// transport's notification handler to client.OnNotification (progress, §2 idle timeout).
	if err := client.Start(context.Background()); err != nil {
		client.Close()
		return nil, fmt.Errorf("failed to start stdio client: %w", err)
	}
	if err := initClient(ctx, client, timeout); err != nil {
		client.Close()
		return nil, err
	}
	return client, nil
}

func newRemoteClient(ctx context.Context, cfg ServerConfig, waitFor func(Request) (Answer, error), auth *mcpOAuth) (*mcpclient.Client, error) {
	timeout := cfg.timeout()
	// Expand ${ENV_VAR} in header values so tokens live in the environment, not
	// the committed config. Values are never logged.
	headers := ExpandEnvHeaders(cfg.Headers)
	// OAuth (§2A.1a): the bearer is attached per request, so a refreshed token is
	// picked up without reconnecting.
	bearerHeader := func(ctx context.Context) map[string]string {
		if auth == nil {
			return nil
		}
		if t := auth.bearer(ctx, cfg.URL); t != "" {
			return map[string]string{"Authorization": "Bearer " + t}
		}
		return nil
	}

	// Prefer streamable HTTP, fall back to SSE. Build transport + client directly
	// (instead of the NewStreamableHttpClient / NewSSEMCPClient convenience) so the
	// elicitation handler can be attached when a waitFor is present.
	var httpOpts []transport.StreamableHTTPCOption
	if len(headers) > 0 {
		httpOpts = append(httpOpts, transport.WithHTTPHeaders(headers))
	}
	if auth != nil {
		httpOpts = append(httpOpts, transport.WithHTTPHeaderFunc(bearerHeader))
	}
	httpTransport, err := transport.NewStreamableHTTP(cfg.URL, httpOpts...)
	if err == nil {
		clientOpts := elicitationOptions(waitFor)
		if httpTransport.GetSessionId() != "" {
			clientOpts = append(clientOpts, mcpclient.WithSession())
		}
		httpClient := mcpclient.NewClient(httpTransport, clientOpts...)
		httpStartCtx, httpCancel := context.WithTimeout(ctx, timeout)
		startErr := httpClient.Start(httpStartCtx)
		httpCancel()
		if startErr == nil {
			return httpClient, nil
		} else {
			httpClient.Close()
			err = startErr
		}
	}

	var sseOpts []transport.ClientOption
	if len(headers) > 0 {
		sseOpts = append(sseOpts, mcpclient.WithHeaders(headers))
	}
	if auth != nil {
		sseOpts = append(sseOpts, transport.WithHeaderFunc(bearerHeader))
	}
	sseTransport, sseErr := transport.NewSSE(cfg.URL, sseOpts...)
	if sseErr != nil {
		return nil, fmt.Errorf("streamable-http failed (%v) and sse setup failed: %w", err, sseErr)
	}
	sseClient := mcpclient.NewClient(sseTransport, elicitationOptions(waitFor)...)
	// The SSE transport owns its event stream via context.WithCancel(startCtx), so the start
	// context must live for the client's lifetime (torn down by client.Close()) — a timeout/cancel
	// context would drop the stream the moment Start returns. Use background; the primary
	// streamable-HTTP path above stays timeout-bounded, and initClient/listTools remain ctx-aware.
	if startErr := sseClient.Start(context.Background()); startErr != nil {
		sseClient.Close()
		return nil, fmt.Errorf("streamable-http failed (%v) and sse start failed: %w", err, startErr)
	}
	return sseClient, nil
}

func initClient(ctx context.Context, client *mcpclient.Client, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req := mcp.InitializeRequest{}
	req.Params.ProtocolVersion = mcp.LATEST_PROTOCOL_VERSION
	req.Params.ClientInfo = mcp.Implementation{Name: "toolnexus", Version: "0.1.0"}
	_, err := client.Initialize(ctx, req)
	return err
}

func listTools(ctx context.Context, client *mcpclient.Client, timeout time.Duration) ([]mcp.Tool, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	// ListTools follows nextCursor pagination internally.
	res, err := client.ListTools(ctx, mcp.ListToolsRequest{})
	if err != nil {
		return nil, err
	}
	return res.Tools, nil
}

// LoadMcp connects to every enabled server, lists + converts tools. Failures
// are isolated — one bad server logs a warning and is marked failed. When a
// waitFor is given, connected clients advertise the elicitation capability and
// bridge server elicitation mid-`tools/call` onto it (§10); omit ⇒ not advertised.
func LoadMcp(input any, waitFor ...func(Request) (Answer, error)) (*McpSource, error) {
	return LoadMcpWithContext(context.Background(), input, waitFor...)
}

// LoadMcpWithContext (§2 Gap 3) is the context-aware entry point. The caller's
// ctx propagates through connect, initialize, and list (and bounds the SSE
// fallback start); a per-server timeout within budget marks only that server
// failed and the build continues, but parent-ctx cancellation/deadline aborts the
// whole load and returns ctx.Err(). LoadMcp keeps its signature and delegates here
// with context.Background().
func LoadMcpWithContext(ctx context.Context, input any, waitFor ...func(Request) (Answer, error)) (*McpSource, error) {
	var wf func(Request) (Answer, error)
	if len(waitFor) > 0 {
		wf = waitFor[0]
	}
	return LoadMcpWithOptions(ctx, input, McpLoadOptions{WaitFor: wf})
}

// McpServerResult is one server's outcome, reported by McpLoadOptions.OnServer
// the moment that server finishes — it does not wait for slower servers.
type McpServerResult struct {
	Name   string
	Status McpStatus
	// Tools are this server's converted tools (nil unless Status is connected).
	// They stay usable until the returned McpSource is closed.
	Tools []Tool
	// Err is why the server failed (nil when connected or disabled).
	Err error
}

// McpLoadOptions configures LoadMcpWithOptions.
type McpLoadOptions struct {
	// WaitFor bridges MCP elicitation onto §10, as in LoadMcpWithContext.
	WaitFor func(Request) (Answer, error)
	// OnServer, when set, is called once per server as soon as it is connected,
	// failed or found disabled, so a host can register a fast server's tools
	// (Toolkit.Register) while a slow server is still connecting. Calls are
	// serialized (never concurrent with each other) but come from load
	// goroutines; keep them short.
	OnServer func(McpServerResult)
	// TokenStore persists OAuth tokens per resource (§2A.1a). nil ⇒ in-memory per load.
	TokenStore TokenStore
	// OpenURL opens the authorization URL in `auto` mode. nil ⇒ the OS opener.
	OpenURL func(string) error
	// DisableTools (final names) are also hidden from tool search (§2.8).
	DisableTools []string
}

// LoadMcpWithOptions is LoadMcpWithContext plus per-server readiness
// (McpLoadOptions.OnServer). It still returns only after every server has
// finished, with the same McpSource, status and cancellation semantics: if ctx
// is cancelled the whole load returns ctx.Err() and closes every client —
// including ones whose tools OnServer already handed out.
func LoadMcpWithOptions(ctx context.Context, input any, opts McpLoadOptions) (*McpSource, error) {
	config, err := ParseMcpConfig(input)
	if err != nil {
		return nil, err
	}
	wf := opts.WaitFor

	var (
		mu      sync.Mutex
		cbMu    sync.Mutex
		servers = map[string]mcpServerConn{}
		tools   []Tool
		status  = map[string]McpStatus{}
		clients []*mcpclient.Client
		v26s    []*mcp26Server
		wg      sync.WaitGroup
	)
	store := opts.TokenStore
	if store == nil {
		store = &MemoryTokenStore{}
	}
	disabled := map[string]bool{}
	for _, n := range opts.DisableTools {
		disabled[n] = true
	}
	copts := mcpConnectOpts{waitFor: wf, tokenStore: store, openURL: opts.OpenURL, disabled: disabled}
	report := func(r McpServerResult) {
		if opts.OnServer == nil {
			return
		}
		cbMu.Lock()
		defer cbMu.Unlock()
		opts.OnServer(r)
	}

	fail := func(name string, err error) {
		mu.Lock()
		status[name] = StatusFailed
		mu.Unlock()
		log.Printf("[toolnexus] MCP server %q failed: %v", name, err)
		report(McpServerResult{Name: name, Status: StatusFailed, Err: err})
	}

	for name, cfg := range config {
		name, cfg := name, cfg
		if !cfg.isEnabled() {
			mu.Lock()
			status[name] = StatusDisabled
			mu.Unlock()
			report(McpServerResult{Name: name, Status: StatusDisabled})
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			// Discover first (§2.2); the SDK path below is the frozen fallback.
			s26, auth, fallback, dErr := connect2026(ctx, name, cfg, copts)
			if dErr != nil {
				var ap *mcpAuthPending
				if errors.As(dErr, &ap) {
					dErr = errors.New("authorization pending")
				}
				fail(name, dErr)
				return
			}
			if !fallback {
				converted, tErr := s26.tools(ctx)
				if tErr != nil {
					s26.close()
					fail(name, tErr)
					return
				}
				if d := s26.discovery; d != nil && d.skills {
					s26.loadSkills(ctx)
				}
				mu.Lock()
				tools = append(tools, converted...)
				v26s = append(v26s, s26)
				servers[name] = mcpServerConn{v26: s26, timeout: cfg.timeout()}
				status[name] = StatusConnected
				mu.Unlock()
				report(McpServerResult{Name: name, Status: StatusConnected, Tools: converted})
				return
			}
			client, cErr := connectServer(ctx, name, cfg, wf, auth)
			if cErr != nil {
				mu.Lock()
				status[name] = StatusFailed
				mu.Unlock()
				log.Printf("[toolnexus] MCP server %q failed: %v", name, cErr)
				report(McpServerResult{Name: name, Status: StatusFailed, Err: cErr})
				return
			}
			defs, lErr := listTools(ctx, client, cfg.timeout())
			if lErr != nil {
				client.Close()
				mu.Lock()
				status[name] = StatusFailed
				mu.Unlock()
				log.Printf("[toolnexus] MCP server %q list tools failed: %v", name, lErr)
				report(McpServerResult{Name: name, Status: StatusFailed, Err: lErr})
				return
			}
			defs = applyToolsFilter(name, defs, cfg.Tools)
			router := newProgressRouter(client)
			converted := make([]Tool, 0, len(defs))
			for _, def := range defs {
				converted = append(converted, convertTool(name, def, client, cfg.timeout(), router))
			}
			mu.Lock()
			tools = append(tools, converted...)
			clients = append(clients, client)
			servers[name] = mcpServerConn{client: client, timeout: cfg.timeout()}
			status[name] = StatusConnected
			mu.Unlock()
			report(McpServerResult{Name: name, Status: StatusConnected, Tools: converted})
		}()
	}
	wg.Wait()

	// Parent cancellation/deadline aborts the whole build (§2 Gap 3, A1).
	closeAll := func() {
		for _, c := range clients {
			_ = c.Close()
		}
		for _, s := range v26s {
			s.close()
		}
	}
	if ctx.Err() != nil {
		closeAll()
		return nil, ctx.Err()
	}
	var skillDirs []string
	for _, s := range v26s {
		if s.skillDir != "" {
			skillDirs = append(skillDirs, s.skillDir)
		}
	}
	sort.Strings(skillDirs)
	return &McpSource{
		Tools:     tools,
		Status:    status,
		servers:   servers,
		SkillDirs: skillDirs,
		close:     closeAll,
	}, nil
}

// ToolInfo (§2 Gap 6) is one listed tool definition (original, unprefixed name).
type ToolInfo struct {
	Name        string     `json:"name"`
	Description string     `json:"description"`
	InputSchema JSONSchema `json:"inputSchema"`
}

// McpInventory (§2 Gap 6) is the result of a list-only pass over an MCP config.
type McpInventory struct {
	// Tools maps server name → its full listed tool defs, UNFILTERED by
	// ServerConfig.Tools (it exists to author/validate those allowlists).
	Tools  map[string][]ToolInfo `json:"tools"`
	Status map[string]McpStatus  `json:"status"`
}

// ListMcpTools (§2 Gap 6) connects to every enabled server in the config, lists
// tool definitions, and DISCONNECTS before returning — no toolkit, no Execute
// wiring, nothing left running. ctx-aware per Gap 3. Failure isolation as in
// LoadMcp: a bad server is Status failed, never an error for the whole call.
func ListMcpTools(ctx context.Context, input any) (*McpInventory, error) {
	config, err := ParseMcpConfig(input)
	if err != nil {
		return nil, err
	}
	var (
		mu       sync.Mutex
		byServer = map[string][]ToolInfo{}
		status   = map[string]McpStatus{}
		wg       sync.WaitGroup
	)
	for name, cfg := range config {
		name, cfg := name, cfg
		if !cfg.isEnabled() {
			mu.Lock()
			status[name] = StatusDisabled
			mu.Unlock()
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			s26, auth, fallback, dErr := connect2026(ctx, name, cfg, mcpConnectOpts{})
			if dErr == nil && !fallback {
				infos, lErr := s26.toolInfos(ctx)
				s26.close()
				mu.Lock()
				if lErr != nil {
					status[name] = StatusFailed
				} else {
					byServer[name] = infos
					status[name] = StatusConnected
				}
				mu.Unlock()
				return
			}
			if dErr != nil {
				mu.Lock()
				status[name] = StatusFailed
				mu.Unlock()
				log.Printf("[toolnexus] MCP server %q failed: %v", name, dErr)
				return
			}
			client, cErr := connectServer(ctx, name, cfg, nil, auth)
			if cErr != nil {
				mu.Lock()
				status[name] = StatusFailed
				mu.Unlock()
				log.Printf("[toolnexus] MCP server %q failed: %v", name, cErr)
				return
			}
			defs, lErr := listTools(ctx, client, cfg.timeout())
			client.Close()
			if lErr != nil {
				mu.Lock()
				status[name] = StatusFailed
				mu.Unlock()
				log.Printf("[toolnexus] MCP server %q list tools failed: %v", name, lErr)
				return
			}
			infos := make([]ToolInfo, 0, len(defs))
			for _, def := range defs {
				infos = append(infos, ToolInfo{Name: def.Name, Description: def.Description, InputSchema: buildInputSchema(def)})
			}
			mu.Lock()
			byServer[name] = infos
			status[name] = StatusConnected
			mu.Unlock()
		}()
	}
	wg.Wait()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	return &McpInventory{Tools: byServer, Status: status}, nil
}

// mcpHints maps MCP tool annotations to ToolHints. No hint fields at all ⇒ nil
// (unknown). Defaults follow the MCP spec: readOnlyHint false, destructiveHint
// true, openWorldHint true when absent.
func mcpHints(a mcp.ToolAnnotation) *ToolHints {
	if a.ReadOnlyHint == nil && a.DestructiveHint == nil && a.OpenWorldHint == nil {
		return nil
	}
	h := ToolHints{Destructive: true, OpenWorld: true}
	if a.ReadOnlyHint != nil {
		h.ReadOnly = *a.ReadOnlyHint
	}
	if a.DestructiveHint != nil {
		h.Destructive = *a.DestructiveHint
	}
	if a.OpenWorldHint != nil {
		h.OpenWorld = *a.OpenWorldHint
	}
	if h.ReadOnly {
		h.Destructive = false
	}
	return &h
}
