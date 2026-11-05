package io.github.muthuishere.toolnexus.examples;

import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.McpHandler;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolAnnotations;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.Toolkit;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embeddable MCP server (SPEC §7C.1): mount {@code tk.mcpHandler(...)} on your own
 * {@link HttpServer}, authenticate with a bearer token, give each caller its own tool list, and
 * gate a destructive tool behind a confirmation (elicitation when the client supports it, a
 * host-shaped "re-call with a nonce" result otherwise). Mirrors {@code golang/examples/mcphandler}.
 *
 * <pre>./gradlew runMcpHandler</pre>
 *
 * Self-contained: starts the server on an ephemeral port and drives it with three MCP clients
 * (alice with elicitation, alice without, bob). No network, no API key.
 */
public final class McpHandlerExample {
    private McpHandlerExample() {}

    record User(String name, String role) {}

    // Demo tokens only. A real host verifies a JWT/OAuth token here.
    private static final Map<String, User> TOKENS = Map.of(
            "token-alice", new User("alice", "admin"), "token-bob", new User("bob", "viewer"));

    private static List<Tool> tools(McpHandler.RequestContext ctx) {
        User u = (User) ctx.identity();
        List<Tool> list = new ArrayList<>();
        list.add(Tool.withAnnotations(new Tool() {
            @Override public String name() { return "list_records"; }
            @Override public String description() { return "List records"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "native"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext tc) {
                return ToolResult.ok("records for " + ((User) McpHandler.identityFrom(tc)).name() + ": [1, 2]");
            }
        }, new ToolAnnotations(null, true, null, null, null)));
        if (u.role().equals("admin")) {
            list.add(Tool.withAnnotations(
                    NativeTool.of("delete_record", "Delete a record",
                            Map.of("type", "object", "properties", Map.of(
                                    "id", Map.of("type", "string"), "nonce", Map.of("type", "string"))),
                            args -> "deleted " + args.get("id")),
                    new ToolAnnotations("Delete record", null, true, null, null)));
        }
        return list;
    }

    private static McpHandler.Decision beforeCall(McpHandler.RequestContext ctx, Tool t, String args) {
        ToolAnnotations a = t.annotations();
        if (a == null || !Boolean.TRUE.equals(a.destructiveHint())) return McpHandler.Decision.allow();
        if (args.contains("\"nonce\":\"n-123\"")) return McpHandler.Decision.allow(); // the host's own nonce check
        return McpHandler.Decision.confirm("Really delete this record?",
                ToolResult.ok("Confirmation needed: call delete_record again with \"nonce\":\"n-123\"."));
    }

    public static void main(String[] argv) throws Exception {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            McpHandler handler = tk.mcpHandler(new McpHandler.Config()
                    .name("records").version("1.0.0")
                    .authenticate(ex -> {
                        String h = ex.getRequestHeaders().getFirst("Authorization");
                        User u = h == null ? null : TOKENS.get(h.replaceFirst("^Bearer ", ""));
                        if (u == null) throw new IllegalArgumentException("invalid token");
                        return u;
                    })
                    .wwwAuthenticate((ex, e) -> "Bearer resource_metadata=\"https://example.test/.well-known/oauth-protected-resource\"")
                    .listTools(McpHandlerExample::tools)
                    .beforeCall(McpHandlerExample::beforeCall)
                    .onCall(ev -> System.out.printf("  [onCall] %s by %s decision=%s isError=%s %dB%n",
                            ev.name(), ((User) ev.identity()).name(), ev.decision().wire(), ev.isError(), ev.resultBytes())));

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // A multi-threaded executor is required: a confirm waits for a second request.
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/mcp", handler);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            try {
                HttpResponse<String> resp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(base + "/mcp"))
                                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                                .header("Content-Type", "application/json").build(),
                        HttpResponse.BodyHandlers.ofString());
                System.out.println("no token -> " + resp.statusCode() + ", WWW-Authenticate: "
                        + resp.headers().firstValue("WWW-Authenticate").orElse(""));

                System.out.println("alice (admin, client supports elicitation):");
                McpSyncClient alice = connect(base, "token-alice", true);
                show(alice);
                System.out.println("  delete_record -> " + callText(alice, "delete_record", Map.of("id", "7")));

                System.out.println("alice (admin, client WITHOUT elicitation):");
                McpSyncClient alice2 = connect(base, "token-alice", false);
                System.out.println("  delete_record -> " + callText(alice2, "delete_record", Map.of("id", "7")));
                System.out.println("  delete_record+nonce -> " + callText(alice2, "delete_record", Map.of("id", "7", "nonce", "n-123")));

                System.out.println("bob (viewer):");
                McpSyncClient bob = connect(base, "token-bob", false);
                show(bob);
                System.out.println("  delete_record -> " + callText(bob, "delete_record", Map.of("id", "7")));

                for (McpSyncClient c : List.of(alice, alice2, bob)) c.closeGracefully();
            } finally {
                server.stop(0);
            }
        }
    }

    private static McpSyncClient connect(String base, String token, boolean elicit) {
        HttpClientStreamableHttpTransport tr = HttpClientStreamableHttpTransport.builder(base)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .jsonMapper(new JacksonMcpJsonMapperSupplier().get())
                .supportedProtocolVersions(List.of("2025-06-18"))
                .build();
        var spec = McpClient.sync(tr).clientInfo(new McpSchema.Implementation("example", "1"));
        if (elicit) {
            spec.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build());
            spec.elicitation(req -> {
                System.out.println("  [client asked] " + req.message() + " -> accept");
                return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("confirm", true));
            });
        }
        McpSyncClient c = spec.build();
        c.initialize();
        return c;
    }

    private static void show(McpSyncClient c) {
        List<String> names = new ArrayList<>();
        for (McpSchema.Tool t : c.listTools().tools()) names.add(t.name());
        System.out.println("  tools: " + names);
    }

    private static String callText(McpSyncClient c, String name, Map<String, Object> args) {
        try {
            McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest(name, args));
            return ((McpSchema.TextContent) r.content().get(0)).text();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}
