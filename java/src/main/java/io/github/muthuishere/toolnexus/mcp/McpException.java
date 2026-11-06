package io.github.muthuishere.toolnexus.mcp;

/** Any failure of an MCP call: transport, protocol, or authorization. */
public class McpException extends RuntimeException {
    public McpException(String message) { super(message); }
    public McpException(String message, Throwable cause) { super(message, cause); }
}
