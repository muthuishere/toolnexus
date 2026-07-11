// Dynamic MCP source. Mirrors opencode's mcp/index.ts + mcp/catalog.ts, but
// standalone: connect to every configured server, list tools, and convert each
// into a uniform Tool. Built on github.com/mark3labs/mcp-go.
package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
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
			var cfg McpConfig
			if err := json.Unmarshal(servers, &cfg); err != nil {
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
	var cfg McpConfig
	if err := json.Unmarshal(stripped, &cfg); err != nil {
		return nil, fmt.Errorf("parse mcp config: %w", err)
	}
	return cfg, nil
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
		}
	}
	return strings.Join(parts, "\n")
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

func convertTool(server string, def mcp.Tool, client *mcpclient.Client, defTimeout time.Duration) Tool {
	name := def.Name
	return Tool{
		Name:        Sanitize(server) + "_" + Sanitize(def.Name),
		Description: def.Description,
		InputSchema: buildInputSchema(def),
		Source:      SourceMCP,
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
			ctx, cancel := context.WithTimeout(parent, timeout)
			defer cancel()

			if args == nil {
				args = map[string]any{}
			}
			req := mcp.CallToolRequest{}
			req.Params.Name = name
			req.Params.Arguments = args

			result, err := client.CallTool(ctx, req)
			if err != nil {
				return ToolResult{Output: err.Error(), IsError: true, Metadata: meta}, nil
			}
			if result.IsError {
				return ToolResult{Output: formatToolErrorContent(result.Content), IsError: true, Metadata: meta}, nil
			}
			if result.StructuredContent != nil {
				b, mErr := json.Marshal(result.StructuredContent)
				if mErr != nil {
					return ToolResult{Output: mErr.Error(), IsError: true, Metadata: meta}, nil
				}
				return ToolResult{Output: string(b), IsError: false, Metadata: meta}, nil
			}
			return ToolResult{Output: joinTextContent(result.Content), IsError: false, Metadata: meta}, nil
		},
	}
}

// McpSource is the result of connecting to all configured servers.
type McpSource struct {
	Tools  []Tool
	Status map[string]McpStatus
	close  func()
}

// Close disconnects every connected client.
func (m *McpSource) Close() {
	if m != nil && m.close != nil {
		m.close()
	}
}

func connectServer(name string, cfg ServerConfig, waitFor func(Request) (Answer, error)) (*mcpclient.Client, error) {
	timeout := cfg.timeout()

	if cfg.isRemote() {
		client, err := newRemoteClient(cfg, waitFor)
		if err != nil {
			return nil, err
		}
		if err := initClient(client, timeout); err != nil {
			client.Close()
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
	if err := stdioTransport.Start(context.Background()); err != nil {
		return nil, fmt.Errorf("failed to start stdio transport: %w", err)
	}
	client := mcpclient.NewClient(stdioTransport, elicitationOptions(waitFor)...)
	if err := initClient(client, timeout); err != nil {
		client.Close()
		return nil, err
	}
	return client, nil
}

func newRemoteClient(cfg ServerConfig, waitFor func(Request) (Answer, error)) (*mcpclient.Client, error) {
	// Expand ${ENV_VAR} in header values so tokens live in the environment, not
	// the committed config. Values are never logged.
	headers := ExpandEnvHeaders(cfg.Headers)

	// Prefer streamable HTTP, fall back to SSE. Build transport + client directly
	// (instead of the NewStreamableHttpClient / NewSSEMCPClient convenience) so the
	// elicitation handler can be attached when a waitFor is present.
	var httpOpts []transport.StreamableHTTPCOption
	if len(headers) > 0 {
		httpOpts = append(httpOpts, transport.WithHTTPHeaders(headers))
	}
	httpTransport, err := transport.NewStreamableHTTP(cfg.URL, httpOpts...)
	if err == nil {
		clientOpts := elicitationOptions(waitFor)
		if httpTransport.GetSessionId() != "" {
			clientOpts = append(clientOpts, mcpclient.WithSession())
		}
		httpClient := mcpclient.NewClient(httpTransport, clientOpts...)
		if startErr := httpClient.Start(context.Background()); startErr == nil {
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
	sseTransport, sseErr := transport.NewSSE(cfg.URL, sseOpts...)
	if sseErr != nil {
		return nil, fmt.Errorf("streamable-http failed (%v) and sse setup failed: %w", err, sseErr)
	}
	sseClient := mcpclient.NewClient(sseTransport, elicitationOptions(waitFor)...)
	if startErr := sseClient.Start(context.Background()); startErr != nil {
		sseClient.Close()
		return nil, fmt.Errorf("streamable-http failed (%v) and sse start failed: %w", err, startErr)
	}
	return sseClient, nil
}

func initClient(client *mcpclient.Client, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	req := mcp.InitializeRequest{}
	req.Params.ProtocolVersion = mcp.LATEST_PROTOCOL_VERSION
	req.Params.ClientInfo = mcp.Implementation{Name: "toolnexus", Version: "0.1.0"}
	_, err := client.Initialize(ctx, req)
	return err
}

func listTools(client *mcpclient.Client, timeout time.Duration) ([]mcp.Tool, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
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
	config, err := ParseMcpConfig(input)
	if err != nil {
		return nil, err
	}
	var wf func(Request) (Answer, error)
	if len(waitFor) > 0 {
		wf = waitFor[0]
	}

	var (
		mu      sync.Mutex
		tools   []Tool
		status  = map[string]McpStatus{}
		clients []*mcpclient.Client
		wg      sync.WaitGroup
	)

	for name, cfg := range config {
		name, cfg := name, cfg
		if !cfg.isEnabled() {
			status[name] = StatusDisabled
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			client, cErr := connectServer(name, cfg, wf)
			if cErr != nil {
				mu.Lock()
				status[name] = StatusFailed
				mu.Unlock()
				log.Printf("[toolnexus] MCP server %q failed: %v", name, cErr)
				return
			}
			defs, lErr := listTools(client, cfg.timeout())
			if lErr != nil {
				client.Close()
				mu.Lock()
				status[name] = StatusFailed
				mu.Unlock()
				log.Printf("[toolnexus] MCP server %q list tools failed: %v", name, lErr)
				return
			}
			converted := make([]Tool, 0, len(defs))
			for _, def := range defs {
				converted = append(converted, convertTool(name, def, client, cfg.timeout()))
			}
			mu.Lock()
			tools = append(tools, converted...)
			clients = append(clients, client)
			status[name] = StatusConnected
			mu.Unlock()
		}()
	}
	wg.Wait()

	return &McpSource{
		Tools:  tools,
		Status: status,
		close: func() {
			for _, c := range clients {
				_ = c.Close()
			}
		},
	}, nil
}
