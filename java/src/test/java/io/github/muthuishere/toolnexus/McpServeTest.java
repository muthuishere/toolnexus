package io.github.muthuishere.toolnexus;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP inbound — "serve as an MCP server" suite (SPEC §7C). Mirrors the nine
 * {@code "mcp inbound:"} tests in the JS reference ({@code js/test/unit.test.ts}).
 *
 * <p>The Java MCP SDK ships no in-memory/linked transport (unlike the JS SDK's
 * {@code InMemoryTransport.createLinkedPair()}), so — as SPEC §7C permits — these
 * tests connect the port's own MCP <b>client</b> to the served toolkit over an
 * ephemeral streamable-HTTP port and assert the {@code tools/list} /
 * {@code tools/call} round-trips. Hermetic: localhost only, no real LLM.
 */
class McpServeTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // Schema shared by the test tools: {type, properties:{text:string}, required:[text], additionalProperties:false}.
    private static Map<String, Object> textSchema() {
        Map<String, Object> textProp = new LinkedHashMap<>();
        textProp.put("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", textProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("text"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Tool echoTool() {
        return NativeTool.of("echo", "echoes back", textSchema(), args -> args.get("text"));
    }

    private static Tool boomTool() {
        return NativeTool.of("boom", "always throws", textSchema(), args -> {
            throw new RuntimeException("kaboom");
        });
    }

    /** Connect an MCP client to a served toolkit's {@code /mcp} endpoint and initialize it. */
    private static McpSyncClient connect(String baseUrl) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(baseUrl + "/mcp")
                .jsonMapper(McpServe.JSON_MAPPER)
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
                .build();
        client.initialize();
        return client;
    }

    private static String firstText(McpSchema.CallToolResult res) {
        for (McpSchema.Content c : res.content()) {
            if (c instanceof McpSchema.TextContent t) return t.text();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // 1. exposedMcpTools filters by name, omit ⇒ all.
    // -----------------------------------------------------------------------

    @Test
    void exposedMcpToolsFiltersByNameOmitAll() {
        List<Tool> tools = List.of(echoTool(), boomTool());
        assertEquals(List.of("echo", "boom"), names(McpServe.exposedMcpTools(tools, null)));
        assertEquals(List.of("echo"),
                names(McpServe.exposedMcpTools(tools, new McpServe.MCPServeConfig().tools(List.of("echo")))));
        // unknown names in the filter are ignored, not an error
        assertEquals(List.of("echo"),
                names(McpServe.exposedMcpTools(tools, new McpServe.MCPServeConfig().tools(List.of("echo", "nope")))));
    }

    private static List<String> names(List<Tool> tools) {
        List<String> out = new ArrayList<>();
        for (Tool t : tools) out.add(t.name());
        return out;
    }

    // -----------------------------------------------------------------------
    // 2. initialize reports serverInfo from the profile.
    // -----------------------------------------------------------------------

    @Test
    void initializeReportsServerInfoFromProfile() {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(echoTool()))) {
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig().name("gateway").version("2.0.0")));
            McpSyncClient client = connect(srv.url());
            try {
                McpSchema.Implementation info = client.getServerInfo();
                assertEquals("gateway", info.name());
                assertEquals("2.0.0", info.version());
            } finally {
                client.closeGracefully();
                srv.stop();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 3. tools/list advertises unified tools with inputSchema=parameters.
    // -----------------------------------------------------------------------

    @Test
    void toolsListAdvertisesUnifiedToolsWithInputSchema() {
        Tool echo = echoTool();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(echo, boomTool()))) {
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig()));
            McpSyncClient client = connect(srv.url());
            try {
                List<McpSchema.Tool> tools = client.listTools().tools();
                List<String> got = new ArrayList<>();
                for (McpSchema.Tool t : tools) got.add(t.name());
                got.sort(String::compareTo);
                assertEquals(List.of("boom", "echo"), got);
                McpSchema.Tool advertised = tools.stream()
                        .filter(t -> t.name().equals("echo")).findFirst().orElseThrow();
                assertEquals(echo.inputSchema(), advertised.inputSchema());
            } finally {
                client.closeGracefully();
                srv.stop();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 4. mcp.tools narrows the advertised surface.
    // -----------------------------------------------------------------------

    @Test
    void mcpToolsNarrowsTheAdvertisedSurface() {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(echoTool(), boomTool()))) {
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig().tools(List.of("echo"))));
            McpSyncClient client = connect(srv.url());
            try {
                List<String> got = names(new ArrayList<>());
                for (McpSchema.Tool t : client.listTools().tools()) got.add(t.name());
                assertEquals(List.of("echo"), got);
            } finally {
                client.closeGracefully();
                srv.stop();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5. tools/call dispatches to execute → text content, isError false, onCall fires.
    // -----------------------------------------------------------------------

    @Test
    void toolsCallDispatchesToExecuteTextContent() {
        List<McpServe.OnCallEvent> calls = new CopyOnWriteArrayList<>();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(echoTool()))) {
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig()).onCall(calls::add));
            McpSyncClient client = connect(srv.url());
            try {
                McpSchema.CallToolResult res = client.callTool(
                        new McpSchema.CallToolRequest("echo", Map.of("text", "hi")));
                assertFalse(Boolean.TRUE.equals(res.isError()));
                assertEquals("hi", firstText(res));
                assertEquals(1, calls.size());
                assertEquals("echo", calls.get(0).name());
                assertEquals("native", calls.get(0).source());
                assertFalse(calls.get(0).isError());
            } finally {
                client.closeGracefully();
                srv.stop();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6. erroring tool → isError result, server survives.
    // -----------------------------------------------------------------------

    @Test
    void erroringToolBecomesIsErrorResultServerSurvives() {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(echoTool(), boomTool()))) {
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig()));
            McpSyncClient client = connect(srv.url());
            try {
                McpSchema.CallToolResult bad = client.callTool(
                        new McpSchema.CallToolRequest("boom", Map.of()));
                assertTrue(Boolean.TRUE.equals(bad.isError()));
                assertTrue(firstText(bad).contains("kaboom"), "error text: " + firstText(bad));

                // server keeps serving
                McpSchema.CallToolResult ok = client.callTool(
                        new McpSchema.CallToolRequest("echo", Map.of("text", "still up")));
                assertFalse(Boolean.TRUE.equals(ok.isError()));
                assertEquals("still up", firstText(ok));
            } finally {
                client.closeGracefully();
                srv.stop();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 7. streamable-HTTP /mcp round-trip via serve().
    // -----------------------------------------------------------------------

    @Test
    void streamableHttpRoundTripViaServe() {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(echoTool()))) {
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig().name("http-gw")));
            McpSyncClient client = connect(srv.url());
            try {
                assertEquals("http-gw", client.getServerInfo().name());
                boolean hasEcho = client.listTools().tools().stream().anyMatch(t -> t.name().equals("echo"));
                assertTrue(hasEcho);
                McpSchema.CallToolResult res = client.callTool(
                        new McpSchema.CallToolRequest("echo", Map.of("text", "over http")));
                assertEquals("over http", firstText(res));
            } finally {
                client.closeGracefully();
                srv.stop();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 8. absent profile ⇒ no /mcp surface (A2A handler treats it as unknown method).
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void absentProfileNoMcpSurface() throws Exception {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(echoTool()))) {
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().a2a(new A2AServer.A2AConfig().name("only-a2a")));
            try {
                Map<String, Object> rpc = new LinkedHashMap<>();
                rpc.put("jsonrpc", "2.0");
                rpc.put("id", 1);
                rpc.put("method", "tools/list");
                rpc.put("params", Map.of());
                HttpResponse<String> res = HTTP.send(
                        HttpRequest.newBuilder(URI.create(srv.url() + "/mcp"))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json, text/event-stream")
                                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(rpc)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                Map<String, Object> body = Json.toMap(res.body());
                // A2A POST handler treats it as an unknown JSON-RPC method (not an MCP endpoint).
                assertTrue(body.containsKey("error"), "expected a JSON-RPC error, not an MCP result");
                assertNull(body.get("result"));
            } finally {
                srv.stop();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 9. top-level mcpServer config block is picked up by serve().
    // -----------------------------------------------------------------------

    @Test
    void topLevelMcpServerConfigBlockIsPickedUp() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mcpServer", Map.of("name", "from-config"));
        try (Toolkit tk = Toolkit.create(new Toolkit.Options()
                .mcpConfig(config).builtins(false).extraTools(echoTool()))) {
            // serve() with no inline mcp falls back to the config block.
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0", new Toolkit.ServeOptions());
            McpSyncClient client = connect(srv.url());
            try {
                assertEquals("from-config", client.getServerInfo().name());
            } finally {
                client.closeGracefully();
                srv.stop();
            }
        }
    }
}
