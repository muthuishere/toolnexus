package mcp2026

// Shared wire constants for MCP 2026-07-28 (stateless Streamable HTTP).
const (
	ProtocolVersion = "2026-07-28"
	MetaVersion     = "io.modelcontextprotocol/protocolVersion"
	MetaClientCaps  = "io.modelcontextprotocol/clientCapabilities"
	MetaClientInfo  = "io.modelcontextprotocol/clientInfo"
	MetaServerInfo  = "io.modelcontextprotocol/serverInfo"
	SkillsExt       = "io.modelcontextprotocol/skills"

	CodeMethodNotFound      = -32601
	CodeInvalidParams       = -32602
	CodeHeaderMismatch      = -32020
	CodeUnsupportedProtocol = -32022
)

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    any    `json:"data,omitempty"`
}

func (e *rpcError) Error() string { return e.Message }

type obj = map[string]any
type Obj = obj // exported alias: a JSON object (map[string]any)
