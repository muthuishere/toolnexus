package io.github.muthuishere.toolnexus;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic MCP source. Mirrors opencode's mcp/index.ts + the JS reference
 * ({@code js/src/mcp.ts}): connect to every configured server (stdio for local,
 * streamable-HTTP for remote), list tools (paginated), and convert each into a
 * uniform {@link Tool}. Failures are isolated — one bad server never breaks the
 * toolkit.
 */
public final class McpSource implements AutoCloseable {
    private static final long DEFAULT_TIMEOUT = 30_000L;
    private static final int MAX_LIST_PAGES = 1_000;
    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final List<Tool> tools;
    private final Map<String, String> status; // server -> "connected" | "disabled" | "failed"
    private final List<McpSyncClient> clients;

    private McpSource(List<Tool> tools, Map<String, String> status, List<McpSyncClient> clients) {
        this.tools = tools;
        this.status = status;
        this.clients = clients;
    }

    public List<Tool> tools() {
        return tools;
    }

    /** server -> "connected" | "disabled" | "failed". */
    public Map<String, String> status() {
        return status;
    }

    @Override
    public void close() {
        for (McpSyncClient c : clients) {
            try {
                c.closeGracefully();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
    }

    /** Accept a path, a raw JSON string, or a parsed config map. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseConfig(Object input) {
        Map<String, Object> raw;
        if (input instanceof Map) {
            raw = (Map<String, Object>) input;
        } else {
            String s = String.valueOf(input);
            String text;
            Path p = Path.of(s);
            if (Files.isRegularFile(p)) {
                try {
                    text = Files.readString(p);
                } catch (IOException e) {
                    throw new RuntimeException("Cannot read MCP config: " + s, e);
                }
            } else {
                text = s; // treat as raw JSON
            }
            raw = Json.toMap(text);
        }
        Object servers = raw.get("mcpServers");
        if (servers == null) servers = raw.get("servers");
        if (servers == null) servers = raw.get("mcp");
        if (servers == null) servers = raw;
        return (Map<String, Object>) servers;
    }

    @SuppressWarnings("unchecked")
    private static boolean isRemote(Map<String, Object> cfg) {
        Object type = cfg.get("type");
        if ("remote".equals(type)) return true;
        if ("local".equals(type)) return false;
        Object url = cfg.get("url");
        return url instanceof String;
    }

    private static boolean isEnabled(Map<String, Object> cfg) {
        if (Boolean.TRUE.equals(cfg.get("disabled"))) return false;
        if (Boolean.FALSE.equals(cfg.get("enabled"))) return false;
        return true;
    }

    private static long timeoutOf(Map<String, Object> cfg) {
        Object t = cfg.get("timeout");
        if (t instanceof Number) return ((Number) t).longValue();
        return DEFAULT_TIMEOUT;
    }

    /** Connect to every enabled server, list + convert tools. Failures are isolated. */
    @SuppressWarnings("unchecked")
    public static McpSource load(Object input) {
        Map<String, Object> config = parseConfig(input);
        List<Tool> tools = new ArrayList<>();
        Map<String, String> status = new LinkedHashMap<>();
        List<McpSyncClient> clients = new ArrayList<>();

        // Connect each server in parallel (failures isolated per server).
        List<Thread> threads = new ArrayList<>();
        Object lock = new Object();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> cfg = (Map<String, Object>) entry.getValue();
            Thread th = new Thread(() -> {
                if (!isEnabled(cfg)) {
                    synchronized (lock) {
                        status.put(name, "disabled");
                    }
                    return;
                }
                long timeout = timeoutOf(cfg);
                McpSyncClient client = null;
                try {
                    McpClientTransport transport = isRemote(cfg)
                            ? buildRemoteTransport(cfg)
                            : buildLocalTransport(cfg);
                    client = McpClient.sync(transport)
                            .requestTimeout(Duration.ofMillis(timeout))
                            .initializationTimeout(Duration.ofMillis(timeout))
                            .clientInfo(new McpSchema.Implementation("toolnexus", "0.1.0"))
                            .build();
                    client.initialize();
                    List<McpSchema.Tool> defs = paginateTools(client);
                    List<Tool> converted = new ArrayList<>();
                    for (McpSchema.Tool def : defs) {
                        converted.add(convertTool(name, def, client, timeout));
                    }
                    synchronized (lock) {
                        tools.addAll(converted);
                        clients.add(client);
                        status.put(name, "connected");
                    }
                } catch (Exception e) {
                    synchronized (lock) {
                        status.put(name, "failed");
                    }
                    if (client != null) {
                        try {
                            client.closeGracefully();
                        } catch (Exception ignored) {
                        }
                    }
                    System.err.println("[toolnexus] MCP server \"" + name + "\" failed: " + e.getMessage());
                }
            }, "toolnexus-mcp-" + name);
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return new McpSource(tools, status, clients);
    }

    @SuppressWarnings("unchecked")
    private static McpClientTransport buildLocalTransport(Map<String, Object> cfg) {
        List<Object> command = (List<Object>) cfg.get("command");
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("local server has no command");
        }
        String cmd = String.valueOf(command.get(0));
        List<String> args = new ArrayList<>();
        for (int i = 1; i < command.size(); i++) args.add(String.valueOf(command.get(i)));

        Map<String, String> env = new HashMap<>();
        // merge process env + configured environment/env
        env.putAll(System.getenv());
        Map<String, Object> extra = (Map<String, Object>) cfg.get("environment");
        if (extra == null) extra = (Map<String, Object>) cfg.get("env");
        if (extra != null) {
            for (Map.Entry<String, Object> e : extra.entrySet()) {
                env.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        ServerParameters.Builder pb = ServerParameters.builder(cmd).args(args).env(env);
        ServerParameters params = pb.build();

        final Object cwd = cfg.get("cwd");
        if (cwd == null) {
            return new StdioClientTransport(params, JSON_MAPPER);
        }
        // getProcessBuilder() is protected; subclass to set the working directory.
        return new StdioClientTransport(params, JSON_MAPPER) {
            @Override
            protected ProcessBuilder getProcessBuilder() {
                return super.getProcessBuilder().directory(new java.io.File(String.valueOf(cwd)));
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static McpClientTransport buildRemoteTransport(Map<String, Object> cfg) {
        String url = String.valueOf(cfg.get("url"));
        Map<String, Object> headers = (Map<String, Object>) cfg.get("headers");

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder();
        if (headers != null) {
            for (Map.Entry<String, Object> e : headers.entrySet()) {
                reqBuilder.header(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        try {
            return HttpClientStreamableHttpTransport.builder(url)
                    .requestBuilder(reqBuilder)
                    .jsonMapper(JSON_MAPPER)
                    .build();
        } catch (Exception streamableFailed) {
            // Fall back to SSE transport.
            HttpRequest.Builder sseReq = HttpRequest.newBuilder();
            if (headers != null) {
                for (Map.Entry<String, Object> e : headers.entrySet()) {
                    sseReq.header(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            return HttpClientSseClientTransport.builder(url)
                    .requestBuilder(sseReq)
                    .jsonMapper(JSON_MAPPER)
                    .build();
        }
    }

    private static List<McpSchema.Tool> paginateTools(McpSyncClient client) {
        List<McpSchema.Tool> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            McpSchema.ListToolsResult res = client.listTools(cursor);
            if (res.tools() != null) result.addAll(res.tools());
            String next = res.nextCursor();
            if (next == null) return result;
            if (seen.contains(next)) {
                throw new RuntimeException("MCP list returned duplicate cursor: " + next);
            }
            seen.add(next);
            cursor = next;
        }
        throw new RuntimeException("MCP list exceeded " + MAX_LIST_PAGES + " pages");
    }

    private static Tool convertTool(String server, McpSchema.Tool def, McpSyncClient client, long timeout) {
        Map<String, Object> base = def.inputSchema() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(def.inputSchema());
        base.put("type", "object");
        Object props = base.get("properties");
        base.put("properties", props == null ? new LinkedHashMap<>() : props);
        base.put("additionalProperties", false);
        final Map<String, Object> inputSchema = base;
        final String description = def.description() == null ? "" : def.description();
        final String toolName = Tool.sanitize(server) + "_" + Tool.sanitize(def.name());
        final String mcpName = def.name();

        return new Tool() {
            @Override
            public String name() {
                return toolName;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return inputSchema;
            }

            @Override
            public String source() {
                return "mcp";
            }

            @Override
            public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("server", server);
                try {
                    Map<String, Object> argMap = args == null ? new LinkedHashMap<>() : args;
                    McpSchema.CallToolRequest req = new McpSchema.CallToolRequest(mcpName, argMap);
                    McpSchema.CallToolResult result = client.callTool(req);
                    if (Boolean.TRUE.equals(result.isError())) {
                        return new ToolResult(formatToolErrorContent(result.content()), true, meta);
                    }
                    Object structured = result.structuredContent();
                    if (structured != null) {
                        return new ToolResult(Json.stringify(structured), false, meta);
                    }
                    return new ToolResult(joinTextContent(result.content()), false, meta);
                } catch (Exception e) {
                    return new ToolResult(e.getMessage() == null ? String.valueOf(e) : e.getMessage(), true, meta);
                }
            }
        };
    }

    private static String joinTextContent(List<McpSchema.Content> content) {
        if (content == null) return "";
        List<String> parts = new ArrayList<>();
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent) {
                parts.add(((McpSchema.TextContent) c).text());
            }
        }
        return String.join("\n", parts);
    }

    private static String formatToolErrorContent(List<McpSchema.Content> content) {
        if (content == null) return "MCP tool returned an error";
        List<String> parts = new ArrayList<>();
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent) {
                String t = ((McpSchema.TextContent) c).text();
                if (t != null && !t.trim().isEmpty()) parts.add(t);
            }
        }
        String joined = String.join("\n\n", parts);
        return joined.isEmpty() ? "MCP tool returned an error" : joined;
    }
}
