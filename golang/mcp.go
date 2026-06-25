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
	"strings"
	"sync"
	"time"

	mcpclient "github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/client/transport"
	"github.com/mark3labs/mcp-go/mcp"
)

const (
	defaultTimeout = 30 * time.Second
)

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
	var cfg McpConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
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

func connectServer(name string, cfg ServerConfig) (*mcpclient.Client, error) {
	timeout := cfg.timeout()

	if cfg.isRemote() {
		client, err := newRemoteClient(cfg)
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
	client, err := mcpclient.NewStdioMCPClientWithOptions(cfg.Command[0], env, cfg.Command[1:], opts...)
	if err != nil {
		return nil, err
	}
	// NewStdioMCPClientWithOptions auto-starts the transport.
	if err := initClient(client, timeout); err != nil {
		client.Close()
		return nil, err
	}
	return client, nil
}

func newRemoteClient(cfg ServerConfig) (*mcpclient.Client, error) {
	// Expand ${ENV_VAR} in header values so tokens live in the environment, not
	// the committed config. Values are never logged.
	headers := ExpandEnvHeaders(cfg.Headers)

	// Prefer streamable HTTP, fall back to SSE.
	var httpOpts []transport.StreamableHTTPCOption
	if len(headers) > 0 {
		httpOpts = append(httpOpts, transport.WithHTTPHeaders(headers))
	}
	httpClient, err := mcpclient.NewStreamableHttpClient(cfg.URL, httpOpts...)
	if err == nil {
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
	sseClient, sseErr := mcpclient.NewSSEMCPClient(cfg.URL, sseOpts...)
	if sseErr != nil {
		return nil, fmt.Errorf("streamable-http failed (%v) and sse setup failed: %w", err, sseErr)
	}
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
// are isolated — one bad server logs a warning and is marked failed.
func LoadMcp(input any) (*McpSource, error) {
	config, err := ParseMcpConfig(input)
	if err != nil {
		return nil, err
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
			client, cErr := connectServer(name, cfg)
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
