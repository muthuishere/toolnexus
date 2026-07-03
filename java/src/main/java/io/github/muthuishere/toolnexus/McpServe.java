package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP — INBOUND: serve a toolkit as an MCP server (SPEC §7C). Mirrors the JS
 * reference ({@code js/src/mcpserve.ts}).
 *
 * <p>The inbound mirror of the A2A {@code serve} (§7B). Where A2A advertises the
 * toolkit's <b>skills</b> and fulfils a Task through the whole client loop, MCP
 * advertises the toolkit's <b>unified tools</b> (every source — mcp · skill ·
 * native · http · builtin · a2a) and dispatches each {@code tools/call} straight
 * to {@link Tool#execute}. The calling MCP client <i>is</i> the LLM host, so there
 * is <b>no client, no Task, and no TaskStore</b> — just {@code tools/list} +
 * {@code tools/call} over the toolkit registry.
 *
 * <p>Served over <b>streamable-HTTP</b>, built on the same official
 * {@code io.modelcontextprotocol.sdk:mcp} the client side already uses: a
 * {@link McpStatelessSyncServer} mounted at {@code POST /mcp} on the
 * {@link A2AServer} HTTP server, alongside the A2A routes. The JDK
 * {@link com.sun.net.httpserver.HttpServer} is bridged to the SDK's stateless MCP
 * handler by {@link JdkStatelessHttpTransport}.
 */
public final class McpServe {
    private McpServe() {}

    private static final String DEFAULT_NAME = "toolnexus";
    private static final String DEFAULT_VERSION = "0.1.0";
    static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    // -----------------------------------------------------------------------
    // Config + callback.
    // -----------------------------------------------------------------------

    /** Opt-in MCP serve profile — the toolkit is exposed as an MCP server. */
    public static final class MCPServeConfig {
        /** Advertised server name (initialize {@code serverInfo.name}). Default "toolnexus". */
        public String name;
        /** Advertised server version. Default "0.1.0". */
        public String version;
        /** Subset of tool names to expose; null ⇒ all. Unknown names are ignored. */
        public List<String> tools;

        public MCPServeConfig name(String v) { this.name = v; return this; }
        public MCPServeConfig version(String v) { this.version = v; return this; }
        public MCPServeConfig tools(List<String> v) { this.tools = v; return this; }

        /** Build an MCPServeConfig from a top-level {@code mcpServer} config block (parsed JSON map). */
        public static MCPServeConfig fromMap(Map<String, Object> m) {
            MCPServeConfig cfg = new MCPServeConfig();
            if (m == null) return cfg;
            if (m.get("name") instanceof String s) cfg.name = s;
            if (m.get("version") instanceof String s) cfg.version = s;
            if (m.get("tools") instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) if (o != null) out.add(String.valueOf(o));
                cfg.tools = out;
            }
            return cfg;
        }
    }

    /** Surfaced on each inbound {@code tools/call}. */
    public record OnCallEvent(String name, String source, long ms, boolean isError) {}

    /** Host callback fired on each inbound {@code tools/call}. */
    @FunctionalInterface
    public interface OnCall {
        void accept(OnCallEvent ev);
    }

    // -----------------------------------------------------------------------
    // Tool filtering.
    // -----------------------------------------------------------------------

    /**
     * The tools this profile exposes: every toolkit tool, filtered to
     * {@code cfg.tools} when set (unknown names in the filter are ignored, never
     * an error). Mirrors JS {@code exposedMcpTools}.
     */
    public static List<Tool> exposedMcpTools(List<Tool> tools, MCPServeConfig cfg) {
        if (cfg == null || cfg.tools == null) return tools;
        java.util.Set<String> wanted = new java.util.HashSet<>(cfg.tools);
        List<Tool> out = new ArrayList<>();
        for (Tool t : tools) if (wanted.contains(t.name())) out.add(t);
        return out;
    }

    // -----------------------------------------------------------------------
    // Server builders — the SDK's MCP server in server mode.
    // -----------------------------------------------------------------------

    private static String nameOf(MCPServeConfig cfg) {
        return cfg != null && cfg.name != null ? cfg.name : DEFAULT_NAME;
    }

    private static String versionOf(MCPServeConfig cfg) {
        return cfg != null && cfg.version != null ? cfg.version : DEFAULT_VERSION;
    }

    /** Convert a uniform {@link Tool} into an SDK {@link McpSchema.Tool} (name verbatim). */
    private static McpSchema.Tool toMcpTool(Tool t) {
        Map<String, Object> schema = t.inputSchema() == null ? new LinkedHashMap<>() : t.inputSchema();
        return McpSchema.Tool.builder()
                .name(t.name())
                .description(t.description() == null ? "" : t.description())
                .inputSchema(schema)
                .build();
    }

    /**
     * Dispatch a single {@code tools/call} to {@link Tool#execute} and map the
     * {@link ToolResult} to a {@link McpSchema.CallToolResult} ({@code output} →
     * one text content part, {@code isError} propagates). An {@code execute} throw
     * becomes an {@code isError:true} result — never a crash. Fires {@code onCall}.
     */
    private static McpSchema.CallToolResult dispatch(Tool tool, McpSchema.CallToolRequest req, OnCall onCall) {
        long started = System.currentTimeMillis();
        ToolResult result;
        try {
            Map<String, Object> args = req.arguments() == null ? Map.of() : req.arguments();
            result = tool.execute(args, null);
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            result = ToolResult.error(msg);
        }
        if (onCall != null) {
            try {
                onCall.accept(new OnCallEvent(tool.name(), tool.source(),
                        System.currentTimeMillis() - started, result.isError()));
            } catch (Exception ignored) {
                // Host callback errors are isolated.
            }
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(result.output())
                .isError(result.isError())
                .build();
    }

    private static McpSchema.ServerCapabilities capabilities() {
        return McpSchema.ServerCapabilities.builder().tools(false).build();
    }

    /**
     * Build a stateless MCP server over a caller-supplied
     * {@link McpStatelessServerTransport}. A fresh server is cheap; the HTTP path
     * builds one at {@code serve()} startup and reuses it (stateless — no session).
     */
    static McpStatelessSyncServer buildStatelessServer(McpStatelessServerTransport transport,
                                                       List<Tool> tools, MCPServeConfig cfg, OnCall onCall) {
        var spec = McpServer.sync(transport)
                .serverInfo(nameOf(cfg), versionOf(cfg))
                .capabilities(capabilities());
        // Dispatch straight to Tool.execute (no SDK-side schema validation) so the
        // tool's own result — including its isError text — surfaces verbatim, matching
        // the JS reference which validates nothing before execute.
        spec.validateToolInputs(false);
        for (Tool t : tools) {
            BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
                    (ctx, req) -> dispatch(t, req, onCall);
            spec.toolCall(toMcpTool(t), handler);
        }
        return spec.build();
    }

    // -----------------------------------------------------------------------
    // streamable-HTTP transport shim — bridges the JDK HttpServer to the SDK's
    // stateless MCP handler (the SDK's own HTTP transports are servlet-based;
    // this port's serve() runs on com.sun.net.httpserver, so we adapt).
    // -----------------------------------------------------------------------

    /**
     * A stateless MCP server transport that hands POSTed JSON-RPC messages to the
     * SDK handler and writes the SDK's JSON-RPC response back over a JDK
     * {@link HttpExchange}. Mirrors, on the JDK HTTP server, exactly what the SDK's
     * {@code HttpServletStatelessServerTransport} does over the Servlet API.
     */
    public static final class JdkStatelessHttpTransport implements McpStatelessServerTransport {
        private volatile McpStatelessServerHandler handler;

        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            this.handler = handler;
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        /** Handle one {@code /mcp} request off the JDK HttpServer. */
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"POST".equals(method)) {
                // Stateless: no standalone SSE stream. Matches the SDK servlet's doGet → 405.
                sendText(exchange, 405, "");
                return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept == null || !(accept.contains("application/json") && accept.contains("text/event-stream"))) {
                sendJson(exchange, 400,
                        "{\"error\":\"Both application/json and text/event-stream required in Accept header\"}");
                return;
            }
            McpStatelessServerHandler h = this.handler;
            if (h == null) {
                sendText(exchange, 500, "mcp handler not ready");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            McpSchema.JSONRPCMessage msg;
            try {
                msg = McpSchema.deserializeJsonRpcMessage(JSON_MAPPER, body);
            } catch (Exception e) {
                sendJson(exchange, 400, "{\"error\":\"invalid JSON-RPC message\"}");
                return;
            }
            try {
                if (msg instanceof McpSchema.JSONRPCRequest req) {
                    McpSchema.JSONRPCResponse resp = h.handleRequest(McpTransportContext.EMPTY, req).block();
                    sendJson(exchange, 200, JSON_MAPPER.writeValueAsString(resp));
                } else if (msg instanceof McpSchema.JSONRPCNotification notif) {
                    h.handleNotification(McpTransportContext.EMPTY, notif).block();
                    sendText(exchange, 202, "");
                } else {
                    sendJson(exchange, 400, "{\"error\":\"unsupported JSON-RPC message\"}");
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"mcp error\"}");
            }
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // 202/405 with empty body — send a zero-length response.
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            exchange.close();
        }
    }
}
