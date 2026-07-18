package io.github.muthuishere.toolnexus;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A tiny, self-contained stdio MCP server exposing tools {@code a}, {@code b}, {@code c}
 * (each echoes its own name). Launched as a child JVM by
 * {@link StdioOutboundChildStaysAliveTest} to drive a genuine outbound stdio MCP
 * connection hermetically — no external process, no network, no python. Mirrors the Go
 * regression guard's re-exec-as-stdio-server pattern (rag_consumer_test.go, TN_STDIO_MCP=1).
 *
 * <p>Writes NOTHING to stdout except JSON-RPC — the parent reads stdout as the MCP stream.
 */
public final class StdioMcpServerMain {
    private StdioMcpServerMain() {}

    public static void main(String[] args) throws InterruptedException {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        // Default constructor binds to System.in / System.out (the JSON-RPC pipes).
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);

        McpServer.sync(transport)
                .serverInfo("toolnexus-stdio-test-server", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(toolDef("a", inputSchema), (exchange, req) -> echo("a"))
                .toolCall(toolDef("b", inputSchema), (exchange, req) -> echo("b"))
                .toolCall(toolDef("c", inputSchema), (exchange, req) -> echo("c"))
                .build();

        // Keep the JVM alive; the parent tears the child down via the transport's
        // closeGracefully (StdioClientTransport destroys the subprocess).
        new CountDownLatch(1).await();
    }

    private static McpSchema.Tool toolDef(String name, Map<String, Object> inputSchema) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(name)
                .inputSchema(inputSchema)
                .build();
    }

    private static McpSchema.CallToolResult echo(String name) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(name)), false, null, null);
    }
}
