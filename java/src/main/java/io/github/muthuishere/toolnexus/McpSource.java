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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** {@code ${ENV_VAR}} reference in a header value. */
    static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private final List<Tool> tools;
    private final Map<String, String> status; // server -> "connected" | "disabled" | "failed"
    private final List<McpSyncClient> clients;

    /**
     * §2 Gap 3: a cooperative cancellation signal for {@link #load} / {@link #listMcpTools}. Java's
     * MCP transports are not {@code Context}-aware, so this is the port's idiom for the caller's
     * cancel/deadline: each per-server connect+list is bounded by the server {@code timeout} (marking
     * only that server {@code failed} within budget), and a {@code cancelled()} signal aborts the whole
     * load promptly with a {@link java.util.concurrent.CancellationException}. Mirrors Go's
     * {@code LoadMcpWithContext} parent-ctx behavior (ADR A1).
     */
    @FunctionalInterface
    public interface CancelSignal {
        boolean cancelled();
    }

    /** §2 Gap 6: one listed tool definition (original, unprefixed name). */
    public record ToolInfo(String name, String description, Map<String, Object> inputSchema) {}

    /**
     * §2 Gap 6: the result of a list-only pass over an MCP config. {@code tools} maps server name →
     * its full listed tool defs, UNFILTERED by the per-server {@code tools} allowlist (Gap 7) — the
     * inventory exists to author/validate those filters. {@code status} is per server:
     * {@code connected | disabled | failed}.
     */
    public record McpInventory(Map<String, List<ToolInfo>> tools, Map<String, String> status) {}

    // -----------------------------------------------------------------------
    // MCP elicitation bridge (§10). A server can ask US for input mid-`tools/call`
    // (a reverse-request). We map it onto the one `waitFor`: form mode → kind:"input",
    // URL mode → kind:"authorization". The two mappings are pure + byte-parity-tested.
    // -----------------------------------------------------------------------

    private static final AtomicInteger ELC_SEQ = new AtomicInteger(0);

    /** Map an MCP {@code elicitation/create} request onto a §10 {@link Request}. */
    static Request elicitationToRequest(McpSchema.ElicitRequest params) {
        boolean isUrl = "url".equals(params.mode());
        String message = params.message() == null ? "" : params.message();
        String id = "elc-" + Long.toString(System.currentTimeMillis(), 36) + "-" + ELC_SEQ.incrementAndGet();
        String url = null;
        Map<String, Object> data = null;
        if (isUrl && params instanceof McpSchema.ElicitUrlRequest u && u.url() != null) {
            url = u.url();
        }
        if (!isUrl && params instanceof McpSchema.ElicitFormRequest f && f.requestedSchema() != null) {
            data = Map.of("schema", f.requestedSchema());
        }
        return new Request(id, isUrl ? "authorization" : "input", message, url, data, null);
    }

    /**
     * Map a resolved §10 {@link Answer} back onto an MCP {@link McpSchema.ElicitResult}.
     * ok→accept (content = answer.data, or empty map); declined→decline; else→cancel.
     */
    static McpSchema.ElicitResult answerToElicitResult(Answer answer) {
        if (answer.ok()) {
            Map<String, Object> content = answer.data() != null ? answer.data() : Map.of();
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, content);
        }
        McpSchema.ElicitResult.Action action = "declined".equals(answer.reason())
                ? McpSchema.ElicitResult.Action.DECLINE
                : McpSchema.ElicitResult.Action.CANCEL;
        return new McpSchema.ElicitResult(action, null);
    }

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
        if (servers != null) return (Map<String, Object>) servers;
        // Bare map of servers — but strip sibling top-level config keys
        // (builtins/agents/a2a/mcpServer) so they are not mistaken for MCP servers
        // when no wrapper key is present. Copy so the caller's map is left untouched.
        Map<String, Object> stripped = new LinkedHashMap<>(raw);
        stripped.remove("builtins");
        stripped.remove("agents");
        stripped.remove("a2a");
        stripped.remove("mcpServer");
        return stripped;
    }

    /**
     * Expand {@code ${ENV_VAR}} in each header VALUE from {@link System#getenv} so
     * tokens live in the environment, not the committed config. Returns a new map;
     * {@code null} in → {@code null} out. Missing vars expand to {@code ""}. Values
     * are never logged. Mirrors the JS reference {@code expandEnvHeaders}.
     */
    public static Map<String, String> expandEnvHeaders(Map<String, String> headers) {
        return expandEnvHeaders(headers, System::getenv);
    }

    /**
     * Same as {@link #expandEnvHeaders(Map)} but with a pluggable lookup function
     * (for testability without touching the real process environment).
     */
    public static Map<String, String> expandEnvHeaders(Map<String, String> headers, Function<String, String> lookup) {
        if (headers == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String value = e.getValue();
            if (value == null) {
                out.put(e.getKey(), null);
                continue;
            }
            Matcher m = ENV_VAR.matcher(value);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String resolved = lookup.apply(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(resolved == null ? "" : resolved));
            }
            m.appendTail(sb);
            out.put(e.getKey(), sb.toString());
        }
        return out;
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
    public static McpSource load(Object input) {
        return load(input, null);
    }

    /**
     * Connect to every enabled server, list + convert tools. Failures are isolated.
     * When {@code waitFor} is present, each MCP client advertises the elicitation
     * capability and registers a handler that bridges a server {@code elicitation/create}
     * onto the one §10 {@code waitFor} (form→kind:"input", URL→kind:"authorization").
     * A {@code null} {@code waitFor} degrades cleanly — the capability is not advertised.
     */
    public static McpSource load(Object input, Function<Request, Answer> waitFor) {
        return load(input, waitFor, null);
    }

    /**
     * §2 Gap 3: cancellation/timeout-aware load. Each per-server connect+list is bounded by the
     * server {@code timeout} (a per-server timeout within budget marks only that server
     * {@code failed} and the build continues); a {@code cancel} signal aborts the whole load promptly
     * and throws {@link java.util.concurrent.CancellationException} (mirrors Go's parent-ctx abort,
     * ADR A1). A {@code null} {@code cancel} ⇒ today's behavior (join every server).
     */
    @SuppressWarnings("unchecked")
    public static McpSource load(Object input, Function<Request, Answer> waitFor, CancelSignal cancel) {
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
                    McpClient.SyncSpec spec = McpClient.sync(transport)
                            .requestTimeout(Duration.ofMillis(timeout))
                            .initializationTimeout(Duration.ofMillis(timeout))
                            .clientInfo(new McpSchema.Implementation("toolnexus", "0.1.0"));
                    // Advertise elicitation + register the bridge ONLY when a waitFor exists to
                    // satisfy it — a waitFor-less host degrades cleanly (the server won't elicit).
                    // (§10 elicitation bridge) form mode → kind:"input"; URL mode → kind:"authorization".
                    if (waitFor != null) {
                        spec.capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, true).build())
                                .elicitation(req -> answerToElicitResult(waitFor.apply(elicitationToRequest(req))))
                                .urlElicitation(req -> answerToElicitResult(waitFor.apply(elicitationToRequest(req))));
                    }
                    client = spec.build();
                    client.initialize();
                    // §2 Gap 7: apply the per-server tool allowlist (keyed on ORIGINAL name) to the
                    // listed defs BEFORE sanitize/prefix, same semantics as the builtins/skills filter.
                    List<McpSchema.Tool> defs = applyToolsFilter(name, paginateTools(client), toolsFilterOf(cfg));
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
            th.setDaemon(true);
            threads.add(th);
            th.start();
        }
        try {
            awaitOrCancel(threads, cancel);
        } catch (java.util.concurrent.CancellationException ce) {
            // Parent-ctx cancel: release any already-connected clients before surfacing (ADR A1).
            synchronized (lock) {
                for (McpSyncClient c : clients) {
                    try {
                        c.closeGracefully();
                    } catch (Exception ignored) {
                    }
                }
            }
            throw ce;
        }

        return new McpSource(tools, status, clients);
    }

    /**
     * §2 Gap 6: connect to every enabled server, list tool definitions, and DISCONNECT before
     * returning — no toolkit, no Execute wiring, nothing left running. UNFILTERED by the per-server
     * {@code tools} allowlist (Gap 7): the inventory exists to author/validate those filters. Failure
     * isolation as in {@link #load} (a bad server is {@code failed}, never an error for the whole call);
     * cancellation/timeout per Gap 3.
     */
    public static McpInventory listMcpTools(Object input) {
        return listMcpTools(input, null);
    }

    /** §2 Gap 6 with a Gap 3 cancellation signal. See {@link #listMcpTools(Object)}. */
    @SuppressWarnings("unchecked")
    public static McpInventory listMcpTools(Object input, CancelSignal cancel) {
        Map<String, Object> config = parseConfig(input);
        Map<String, List<ToolInfo>> byServer = new LinkedHashMap<>();
        Map<String, String> status = new LinkedHashMap<>();
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
                    List<ToolInfo> infos = new ArrayList<>();
                    for (McpSchema.Tool def : defs) {
                        infos.add(new ToolInfo(def.name(),
                                def.description() == null ? "" : def.description(),
                                normalizeSchema(def)));
                    }
                    synchronized (lock) {
                        byServer.put(name, infos);
                        status.put(name, "connected");
                    }
                } catch (Exception e) {
                    synchronized (lock) {
                        status.put(name, "failed");
                    }
                    System.err.println("[toolnexus] MCP server \"" + name + "\" failed: " + e.getMessage());
                } finally {
                    // list-only: disconnect immediately, nothing is left running.
                    if (client != null) {
                        try {
                            client.closeGracefully();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "toolnexus-mcp-list-" + name);
            th.setDaemon(true);
            threads.add(th);
            th.start();
        }
        awaitOrCancel(threads, cancel);
        return new McpInventory(byServer, status);
    }

    /**
     * §2 Gap 7: per-server tool allowlist keyed on ORIGINAL tool name. Same semantics as the
     * builtins/skills filter: {@code null}/empty ⇒ all tools; ≥1 {@code true} ⇒ allowlist (only
     * true names load); only {@code false} entries ⇒ drop-list over the all-on baseline; unknown
     * names are ignored + warned once. Mirrors Go {@code applyToolsFilter}.
     */
    static List<McpSchema.Tool> applyToolsFilter(String server, List<McpSchema.Tool> defs,
                                                 Map<String, Boolean> filter) {
        if (filter == null || filter.isEmpty()) return defs;
        boolean hasTrue = filter.values().stream().anyMatch(Boolean.TRUE::equals);
        Set<String> present = new HashSet<>();
        for (McpSchema.Tool d : defs) present.add(d.name());
        for (String k : filter.keySet()) {
            if (!present.contains(k)) {
                System.err.println("[toolnexus] server \"" + server + "\" tools filter name \""
                        + k + "\" matched no tool");
            }
        }
        List<McpSchema.Tool> out = new ArrayList<>();
        for (McpSchema.Tool d : defs) {
            Boolean v = filter.get(d.name());
            boolean keep = hasTrue ? Boolean.TRUE.equals(v) : !Boolean.FALSE.equals(v);
            if (keep) out.add(d);
        }
        return out;
    }

    /** Read the per-server {@code tools} allowlist (Gap 7) from a server config, coercing to booleans. */
    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> toolsFilterOf(Map<String, Object> cfg) {
        Object t = cfg.get("tools");
        if (!(t instanceof Map)) return null;
        Map<String, Object> raw = (Map<String, Object>) t;
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            out.put(e.getKey(), Boolean.TRUE.equals(e.getValue()));
        }
        return out;
    }

    /**
     * Wait for every worker thread. When {@code cancel} is null this is a plain join; otherwise it
     * polls the signal and, on cancellation, interrupts the workers and throws
     * {@link java.util.concurrent.CancellationException} (§2 Gap 3). Abandoned workers are daemon and
     * fall away on their own per-server timeout.
     */
    private static void awaitOrCancel(List<Thread> threads, CancelSignal cancel) {
        if (cancel == null) {
            for (Thread th : threads) {
                try {
                    th.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }
        while (true) {
            boolean allDone = true;
            for (Thread th : threads) {
                if (th.isAlive()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) return;
            if (cancel.cancelled()) {
                for (Thread th : threads) th.interrupt();
                throw new java.util.concurrent.CancellationException("MCP load cancelled");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
        Map<String, Object> rawHeaders = (Map<String, Object>) cfg.get("headers");
        // Expand ${ENV_VAR} in header values before they reach the transport so
        // tokens are read from the environment, not sent verbatim (never logged).
        Map<String, String> headers = null;
        if (rawHeaders != null) {
            Map<String, String> stringified = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : rawHeaders.entrySet()) {
                stringified.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
            headers = expandEnvHeaders(stringified);
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) reqBuilder.header(e.getKey(), e.getValue());
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
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null) sseReq.header(e.getKey(), e.getValue());
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

    /**
     * Normalize an MCP tool def's inputSchema: force {@code type:object}, default
     * {@code properties:{}}, {@code additionalProperties:false}. Mirrors the JS/Go reference.
     */
    private static Map<String, Object> normalizeSchema(McpSchema.Tool def) {
        Map<String, Object> base = def.inputSchema() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(def.inputSchema());
        base.put("type", "object");
        Object props = base.get("properties");
        base.put("properties", props == null ? new LinkedHashMap<>() : props);
        base.put("additionalProperties", false);
        return base;
    }

    private static Tool convertTool(String server, McpSchema.Tool def, McpSyncClient client, long timeout) {
        final Map<String, Object> inputSchema = normalizeSchema(def);
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
