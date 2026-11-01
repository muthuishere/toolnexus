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

    /** The connected clients by server name, for {@link #resourceTools()}. */
    private final Map<String, ServerConn> servers;

    private record ServerConn(McpSyncClient client, long timeout) {}

    private McpSource(List<Tool> tools, Map<String, String> status, List<McpSyncClient> clients,
                      Map<String, ServerConn> servers) {
        this.tools = tools;
        this.status = status;
        this.clients = clients;
        this.servers = servers;
    }

    /**
     * One server's outcome, reported by {@link LoadOptions#onServer} the moment that server
     * finishes — never waiting for a slower server (SPEC §4C).
     *
     * @param name   the server name
     * @param status {@code "connected" | "failed" | "disabled"}
     * @param tools  this server's converted tools (empty unless connected); usable until the
     *               returned {@link McpSource} is closed
     * @param error  why the server failed ({@code null} when connected or disabled)
     */
    public record ServerResult(String name, String status, List<Tool> tools, Throwable error) {}

    /** Options for {@link #load(Object, LoadOptions)}. Fluent setters; every field optional. */
    public static final class LoadOptions {
        /** Bridges MCP elicitation onto §10, as in {@link #load(Object, Function)}. */
        public Function<Request, Answer> waitFor;
        /** §2 Gap 3 cancellation signal, as in {@link #load(Object, Function, CancelSignal)}. */
        public CancelSignal cancel;
        /**
         * Called once per server as soon as it is connected, failed or found disabled, so a host
         * can {@link Toolkit#register} a fast server's tools while a slow one still connects.
         * Calls are serialized (never concurrent with each other) but come from load threads;
         * keep them short.
         */
        public java.util.function.Consumer<ServerResult> onServer;

        public LoadOptions waitFor(Function<Request, Answer> v) { this.waitFor = v; return this; }
        public LoadOptions cancel(CancelSignal v) { this.cancel = v; return this; }
        public LoadOptions onServer(java.util.function.Consumer<ServerResult> v) { this.onServer = v; return this; }
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

    /**
     * Parse MCP configuration into a flat map of <em>server name → server config</em>, without
     * connecting to anything — no child process is spawned and no HTTP request is made. This is the
     * cheap check to run before paying for {@link #load}.
     *
     * <p>Accepts three input shapes:
     * <ul>
     *   <li>a filesystem path to an {@code mcp.json} (anything {@link Files#isRegularFile} accepts) —
     *       read from disk and parsed;</li>
     *   <li>a raw JSON string — parsed directly (a {@code String} that is not an existing regular
     *       file is treated as JSON);</li>
     *   <li>an already-parsed {@code Map<String, Object>} — normalized only.</li>
     * </ul>
     * Any other object is coerced with {@link String#valueOf} and then treated as above.
     *
     * <p>The wrapper keys {@code mcpServers}, {@code servers} and {@code mcp} are all unwrapped (in
     * that order of precedence). A bare map of servers is accepted too, with the sibling top-level
     * toolnexus keys {@code builtins} / {@code agents} / {@code a2a} / {@code mcpServer} stripped so
     * they are never mistaken for MCP servers. Parsing does <b>not</b> filter: a server with
     * {@code enabled:false} / {@code disabled:true} is still present in the result, and is honoured
     * later by {@link #load}.
     *
     * @param input a config file path, a raw JSON string, or a parsed config map
     * @return the servers map. This is <b>not</b> a defensive copy: when a wrapper key is present the
     *         caller's own nested map is returned as-is (matching the JS/Go reference
     *         {@code parseMcpConfig}), and the bare-map form returns a shallow copy so the input is
     *         left untouched. toolnexus itself only ever reads this map. If you intend to mutate the
     *         result — to inject a header or drop a server — copy it first.
     * @throws RuntimeException if the file cannot be read, or if the JSON is malformed
     *         ({@code "Invalid JSON: …"})
     * @throws ClassCastException if the config parses but the wrapper key does not hold an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseConfig(Object input) {
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
        if (servers != null) {
            validateCommands((Map<String, Object>) servers);
            return (Map<String, Object>) servers;
        }
        // Bare map of servers — but strip sibling top-level config keys
        // (builtins/agents/a2a/mcpServer) so they are not mistaken for MCP servers
        // when no wrapper key is present. Copy so the caller's map is left untouched.
        Map<String, Object> stripped = new LinkedHashMap<>(raw);
        stripped.remove("builtins");
        stripped.remove("agents");
        stripped.remove("a2a");
        stripped.remove("mcpServer");
        validateCommands(stripped);
        return stripped;
    }

    /** Fail the parse (naming the server) for any local server whose {@code command}/{@code args} is malformed. */
    @SuppressWarnings("unchecked")
    private static void validateCommands(Map<String, Object> servers) {
        for (Map.Entry<String, Object> e : servers.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> cfg = (Map<String, Object>) e.getValue();
            if (isRemote(cfg) || !cfg.containsKey("command")) continue;
            commandArgv(e.getKey(), cfg);
        }
    }

    /**
     * §2 {@code command} + {@code args} (Claude shape): normalize a local server's config into the
     * argv to spawn. {@code command} may be a non-empty string or a non-empty array of strings; an
     * optional {@code args} array of strings is appended. No shell interpretation is applied.
     *
     * @throws IllegalArgumentException {@code mcp server "<name>": invalid command} /
     *         {@code mcp server "<name>": invalid args} for an empty or non-string shape
     */
    public static List<String> commandArgv(String server, Map<String, Object> cfg) {
        List<String> argv = new ArrayList<>();
        Object command = cfg.get("command");
        if (command instanceof String str) {
            if (str.isEmpty()) throw invalid(server, "command");
            argv.add(str);
        } else if (command instanceof List<?> list) {
            if (list.isEmpty()) throw invalid(server, "command");
            for (Object el : list) {
                if (!(el instanceof String str) || (argv.isEmpty() && str.isEmpty())) throw invalid(server, "command");
                argv.add(str);
            }
        } else {
            throw invalid(server, "command");
        }
        Object args = cfg.get("args");
        if (args != null) {
            if (!(args instanceof List<?> list)) throw invalid(server, "args");
            for (Object el : list) {
                if (!(el instanceof String str)) throw invalid(server, "args");
                argv.add(str);
            }
        }
        return argv;
    }

    private static IllegalArgumentException invalid(String server, String field) {
        return new IllegalArgumentException("mcp server \"" + server + "\": invalid " + field);
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
        return load(input, new LoadOptions());
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
    public static McpSource load(Object input, Function<Request, Answer> waitFor, CancelSignal cancel) {
        return load(input, new LoadOptions().waitFor(waitFor).cancel(cancel));
    }

    /**
     * {@link #load(Object, Function, CancelSignal)} plus per-server readiness
     * ({@link LoadOptions#onServer}, SPEC §4C). It still returns only after every server has
     * finished, with the same source, status and cancellation semantics: a cancelled load throws
     * and closes every client — including ones whose tools {@code onServer} already handed out.
     */
    @SuppressWarnings("unchecked")
    public static McpSource load(Object input, LoadOptions opts) {
        LoadOptions o = opts != null ? opts : new LoadOptions();
        Function<Request, Answer> waitFor = o.waitFor;
        CancelSignal cancel = o.cancel;
        Map<String, Object> config = parseConfig(input);
        List<Tool> tools = new ArrayList<>();
        Map<String, String> status = new LinkedHashMap<>();
        List<McpSyncClient> clients = new ArrayList<>();
        Map<String, ServerConn> servers = new LinkedHashMap<>();
        Object cbLock = new Object();
        java.util.function.Consumer<ServerResult> report = r -> {
            if (o.onServer == null) return;
            synchronized (cbLock) {
                o.onServer.accept(r);
            }
        };

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
                    report.accept(new ServerResult(name, "disabled", List.of(), null));
                    return;
                }
                long timeout = timeoutOf(cfg);
                McpSyncClient client = null;
                try {
                    McpClientTransport transport = isRemote(cfg)
                            ? buildRemoteTransport(cfg)
                            : buildLocalTransport(name, cfg);
                    // §2 idle timeout: the SDK's requestTimeout is a hard per-request deadline, so it
                    // is lifted out of the way and the budget is enforced by us — tools/list keeps a
                    // per-phase `timeout` deadline (bounded()), tools/call an idle one (progress-reset).
                    ProgressRegistry progress = new ProgressRegistry();
                    McpClient.SyncSpec spec = McpClient.sync(transport)
                            .requestTimeout(UNBOUNDED_REQUEST)
                            .progressConsumer(progress::onProgress)
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
                    final McpSyncClient listing = client;
                    List<McpSchema.Tool> defs = applyToolsFilter(name,
                            bounded(() -> paginateTools(listing), timeout), toolsFilterOf(cfg));
                    List<Tool> converted = new ArrayList<>();
                    for (McpSchema.Tool def : defs) {
                        converted.add(convertTool(name, def, client, timeout, progress));
                    }
                    synchronized (lock) {
                        tools.addAll(converted);
                        clients.add(client);
                        servers.put(name, new ServerConn(client, timeout));
                        status.put(name, "connected");
                    }
                    report.accept(new ServerResult(name, "connected", List.copyOf(converted), null));
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
                    report.accept(new ServerResult(name, "failed", List.of(), e));
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

        return new McpSource(tools, status, clients, servers);
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
                            : buildLocalTransport(name, cfg);
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
    private static McpClientTransport buildLocalTransport(String server, Map<String, Object> cfg) {
        if (cfg.get("command") == null) {
            throw new IllegalArgumentException("local server has no command");
        }
        List<String> argv = commandArgv(server, cfg);
        String cmd = argv.get(0);
        List<String> args = new ArrayList<>(argv.subList(1, argv.size()));

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

    /** Effectively-unbounded SDK request timeout; our own budgets (bounded / idle) govern instead. */
    private static final Duration UNBOUNDED_REQUEST = Duration.ofDays(1);

    /**
     * Per-client correlation of {@code notifications/progress} to in-flight calls. The official SDK
     * exposes progress only as a client-wide consumer, so each {@code tools/call} attaches its own
     * fresh token in {@code _meta.progressToken} and registers it here; only a notification whose
     * token matches a registered call stamps that call's last-activity time (D4).
     */
    static final class ProgressRegistry {
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> live =
                new java.util.concurrent.ConcurrentHashMap<>();

        void onProgress(McpSchema.ProgressNotification n) {
            if (n == null || n.progressToken() == null) return;
            java.util.concurrent.atomic.AtomicLong last = live.get(String.valueOf(n.progressToken()));
            if (last != null) last.set(System.nanoTime());
        }

        java.util.concurrent.atomic.AtomicLong register(String token) {
            java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
            live.put(token, last);
            return last;
        }

        void unregister(String token) {
            live.remove(token);
        }
    }

    /** Run {@code work} with a hard {@code timeoutMs} deadline (load phases keep per-phase budgets). */
    private static <T> T bounded(java.util.concurrent.Callable<T> work, long timeoutMs) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(work);
        Thread.ofVirtual().name("toolnexus-mcp-bounded").start(task);
        try {
            return task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            task.cancel(true);
            throw new RuntimeException("MCP request timed out after " + timeoutMs + "ms");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof Exception ex) throw ex;
            throw new RuntimeException(c);
        }
    }

    /**
     * §2 idle timeout for {@code tools/call}: the call is abandoned after {@code timeoutMs} with no
     * matching progress notification (each one resets the budget), or promptly when {@code ctx} is
     * cancelled (cancellation wins over progress).
     */
    static McpSchema.CallToolResult callWithIdleTimeout(McpSyncClient client, String mcpName,
                                                        Map<String, Object> args, long timeoutMs,
                                                        ProgressRegistry progress, ToolContext ctx) throws Exception {
        String token = "toolnexus-" + java.util.UUID.randomUUID();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("progressToken", token);
        McpSchema.CallToolRequest req = new McpSchema.CallToolRequest(mcpName, args, meta);
        java.util.concurrent.atomic.AtomicLong last = progress.register(token);
        java.util.concurrent.FutureTask<McpSchema.CallToolResult> task =
                new java.util.concurrent.FutureTask<>(() -> client.callTool(req));
        Thread.ofVirtual().name("toolnexus-mcp-call-" + mcpName).start(task);
        long budget = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (true) {
                if (ctx != null && ctx.isCancelled()) {
                    task.cancel(true);
                    throw new java.util.concurrent.CancellationException("MCP tool call cancelled");
                }
                long remaining = budget - (System.nanoTime() - last.get());
                if (remaining <= 0) {
                    task.cancel(true);
                    throw new RuntimeException("MCP tool call timed out after " + timeoutMs + "ms without progress");
                }
                try {
                    return task.get(Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20)),
                            java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // re-check cancellation + idle budget
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof Exception ex) throw ex;
                    throw new RuntimeException(c);
                }
            }
        } finally {
            progress.unregister(token);
        }
    }

    private static Tool convertTool(String server, McpSchema.Tool def, McpSyncClient client, long timeout,
                                    ProgressRegistry progress) {
        final Map<String, Object> inputSchema = normalizeSchema(def);
        final String description = def.description() == null ? "" : def.description();
        final String toolName = Tool.sanitize(server) + "_" + Tool.sanitize(def.name());
        final String mcpName = def.name();
        final ToolHints hints = mcpHints(def.annotations());

        return new Tool() {
            @Override
            public String name() {
                return toolName;
            }

            @Override
            public ToolHints hints() {
                return hints;
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
                    McpSchema.CallToolResult result =
                            callWithIdleTimeout(client, mcpName, argMap, timeout, progress, ctx);
                    // §2: parts are collected BEFORE the isError / structuredContent
                    // short-circuits — a server returning structured content *and* an image
                    // must keep the image. Nothing non-text is ever dropped silently.
                    Mapped mapped = mapContent(result.content());
                    if (Boolean.TRUE.equals(result.isError())) {
                        return new ToolResult(formatToolErrorContent(result.content()), true, meta,
                                mapped.parts);
                    }
                    Object structured = result.structuredContent();
                    if (structured != null) {
                        return new ToolResult(Json.stringify(structured), false, meta, mapped.parts);
                    }
                    String output = joinTextContent(result.content());
                    // A `resource` carrying text is appended to output (§2), not made a part.
                    if (!mapped.resourceText.isEmpty()) {
                        output = output.isEmpty() ? mapped.resourceText : output + "\n" + mapped.resourceText;
                    }
                    // An image-only result must not be the empty string: name what came back.
                    if (output.isEmpty() && !mapped.parts.isEmpty()) {
                        output = ContentParts.describeForOutput(mapped.parts);
                    }
                    return new ToolResult(output, false, meta, mapped.parts);
                } catch (Exception e) {
                    return new ToolResult(e.getMessage() == null ? String.valueOf(e) : e.getMessage(), true, meta);
                }
            }
        };
    }

    /**
     * Map MCP tool annotations to {@link ToolHints} (SPEC §4C). No hint fields at all ⇒ {@code null}
     * (unknown). Defaults follow the MCP spec: readOnlyHint false, destructiveHint true,
     * openWorldHint true when absent; a read-only tool is never destructive.
     */
    static ToolHints mcpHints(McpSchema.ToolAnnotations a) {
        if (a == null || (a.readOnlyHint() == null && a.destructiveHint() == null && a.openWorldHint() == null)) {
            return null;
        }
        boolean readOnly = Boolean.TRUE.equals(a.readOnlyHint());
        boolean destructive = a.destructiveHint() == null || a.destructiveHint();
        boolean openWorld = a.openWorldHint() == null || a.openWorldHint();
        if (readOnly) destructive = false;
        return new ToolHints(readOnly, destructive, openWorld);
    }

    // -----------------------------------------------------------------------
    // MCP resource tools (SPEC §4C): opt-in — a host registers resourceTools().
    // -----------------------------------------------------------------------

    private List<String> serverNames() {
        List<String> names = new ArrayList<>(servers.keySet());
        java.util.Collections.sort(names);
        return names;
    }

    private ServerConn conn(String server) {
        ServerConn c = servers.get(server);
        if (c == null) {
            throw new IllegalArgumentException("no connected MCP server \"" + server + "\" (connected: "
                    + String.join(", ", serverNames()) + ")");
        }
        return c;
    }

    private static String errMsg(Throwable e) {
        Throwable c = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
        return c.getMessage() == null ? String.valueOf(c) : c.getMessage();
    }

    /**
     * {@code list_mcp_resources} and {@code read_mcp_resource}, bound to this source's connected
     * servers. Both are read-only and stop working when the source is closed. Opt-in: register
     * them on a toolkit ({@code tk.register(src.resourceTools().toArray(new Tool[0]))}).
     */
    public List<Tool> resourceTools() {
        McpSource self = this;
        Map<String, Object> listProps = new LinkedHashMap<>();
        listProps.put("server", Map.of("type", "string", "description", "Only this server (default: all)"));
        Map<String, Object> listSchema = new LinkedHashMap<>();
        listSchema.put("type", "object");
        listSchema.put("properties", listProps);
        listSchema.put("additionalProperties", false);
        Tool list = new Tool() {
            @Override public String name() { return "list_mcp_resources"; }
            @Override public String description() {
                return "List the resources MCP servers offer (uri, name, description, mimeType). Optionally for one server.";
            }
            @Override public Map<String, Object> inputSchema() { return listSchema; }
            @Override public String source() { return "mcp"; }
            @Override public ToolHints hints() { return ToolHints.READ_ONLY; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                List<String> names = self.serverNames();
                Object s = args == null ? null : args.get("server");
                if (s instanceof String srv && !srv.isEmpty()) {
                    try {
                        self.conn(srv);
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error("list_mcp_resources: " + e.getMessage());
                    }
                    names = List.of(srv);
                }
                List<String> lines = new ArrayList<>();
                List<Object> items = new ArrayList<>();
                for (String name : names) {
                    ServerConn c = self.servers.get(name);
                    McpSchema.ListResourcesResult res;
                    try {
                        res = bounded(() -> c.client().listResources(), c.timeout());
                    } catch (Exception e) {
                        lines.add(name + ": error: " + errMsg(e));
                        continue;
                    }
                    for (McpSchema.Resource r : res.resources()) {
                        StringBuilder line = new StringBuilder(name + " " + r.uri());
                        if (r.name() != null && !r.name().isEmpty()) line.append(" — ").append(r.name());
                        if (r.mimeType() != null && !r.mimeType().isEmpty()) line.append(" (").append(r.mimeType()).append(")");
                        if (r.description() != null && !r.description().isEmpty()) line.append(": ").append(r.description());
                        lines.add(line.toString());
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("server", name);
                        item.put("uri", r.uri());
                        item.put("name", r.name() == null ? "" : r.name());
                        item.put("mimeType", r.mimeType() == null ? "" : r.mimeType());
                        item.put("description", r.description() == null ? "" : r.description());
                        items.add(item);
                    }
                }
                if (lines.isEmpty()) {
                    return ToolResult.ok("No resources.", new LinkedHashMap<>(Map.of("count", 0)));
                }
                Map<String, Object> md = new LinkedHashMap<>();
                md.put("count", items.size());
                md.put("resources", items);
                return ToolResult.ok(String.join("\n", lines), md);
            }
        };
        Map<String, Object> readProps = new LinkedHashMap<>();
        readProps.put("server", Map.of("type", "string", "description", "The MCP server name"));
        readProps.put("uri", Map.of("type", "string", "description", "The resource uri"));
        Map<String, Object> readSchema = new LinkedHashMap<>();
        readSchema.put("type", "object");
        readSchema.put("properties", readProps);
        readSchema.put("required", List.of("server", "uri"));
        readSchema.put("additionalProperties", false);
        Tool read = new Tool() {
            @Override public String name() { return "read_mcp_resource"; }
            @Override public String description() {
                return "Read one MCP resource by server and uri. Text comes back as text; binary content is described, not inlined.";
            }
            @Override public Map<String, Object> inputSchema() { return readSchema; }
            @Override public String source() { return "mcp"; }
            @Override public ToolHints hints() { return ToolHints.READ_ONLY; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                String server = args != null && args.get("server") instanceof String v ? v : "";
                String uri = args != null && args.get("uri") instanceof String v ? v : "";
                ServerConn c;
                try {
                    c = self.conn(server);
                } catch (IllegalArgumentException e) {
                    return ToolResult.error("read_mcp_resource: " + e.getMessage());
                }
                if (uri.isEmpty()) return ToolResult.error("read_mcp_resource: uri is required");
                McpSchema.ReadResourceResult res;
                try {
                    res = bounded(() -> c.client().readResource(new McpSchema.ReadResourceRequest(uri)), c.timeout());
                } catch (Exception e) {
                    return ToolResult.error("read_mcp_resource: " + errMsg(e));
                }
                List<String> parts = new ArrayList<>();
                for (McpSchema.ResourceContents rc : res.contents()) {
                    if (rc instanceof McpSchema.TextResourceContents t) {
                        parts.add(t.text());
                    } else if (rc instanceof McpSchema.BlobResourceContents b) {
                        parts.add("[binary " + b.mimeType() + ", " + (b.blob() == null ? 0 : b.blob().length())
                                + " base64 chars]");
                    }
                }
                Map<String, Object> md = new LinkedHashMap<>();
                md.put("server", server);
                md.put("uri", uri);
                return ToolResult.ok(String.join("\n", parts), md);
            }
        };
        return List.of(list, read);
    }

    /** §2: {@code content[]} split into non-text {@link ContentPart}s and appended resource text. */
    private record Mapped(List<ContentPart> parts, String resourceText) {}

    /**
     * Map an MCP {@code content[]} onto §1B parts: {@code image}⇒image, {@code audio}⇒audio,
     * {@code resource_link}⇒{@code file{url}}, {@code resource} with a blob⇒{@code file{data}},
     * {@code resource} with text⇒appended to {@code output}. Base64 strings are taken from the
     * SDK <b>verbatim</b>; no {@code McpSchema} type escapes into our transcript (the SDK ships a
     * different Jackson major).
     *
     * <p>{@code McpSchema.Content} is a plain interface, not sealed — the {@code else} arm is
     * mandatory and there is no exhaustiveness check to lean on.
     */
    private static Mapped mapContent(List<McpSchema.Content> content) {
        List<ContentPart> parts = new ArrayList<>();
        List<String> resourceText = new ArrayList<>();
        if (content == null) return new Mapped(parts, "");
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent) {
                continue; // text is `output`, not a part
            } else if (c instanceof McpSchema.ImageContent img) {
                parts.add(ContentPart.ofBase64(ContentPart.IMAGE, img.mimeType(), img.data()));
            } else if (c instanceof McpSchema.AudioContent aud) {
                parts.add(ContentPart.ofBase64(ContentPart.AUDIO, aud.mimeType(), aud.data()));
            } else if (c instanceof McpSchema.ResourceLink link) {
                ContentPart p = ContentPart.ofUrl(ContentPart.FILE,
                        link.mimeType() == null || link.mimeType().isEmpty()
                                ? "application/octet-stream" : link.mimeType(),
                        link.uri());
                parts.add(link.name() == null || link.name().isEmpty() ? p : p.withName(link.name()));
            } else if (c instanceof McpSchema.EmbeddedResource emb) {
                McpSchema.ResourceContents res = emb.resource();
                if (res instanceof McpSchema.BlobResourceContents blob) {
                    ContentPart p = ContentPart.ofBase64(ContentPart.FILE,
                            blob.mimeType() == null || blob.mimeType().isEmpty()
                                    ? "application/octet-stream" : blob.mimeType(),
                            blob.blob());
                    parts.add(blob.uri() == null || blob.uri().isEmpty() ? p : p.withName(blob.uri()));
                } else if (res instanceof McpSchema.TextResourceContents txt) {
                    if (txt.text() != null && !txt.text().isEmpty()) resourceText.add(txt.text());
                }
            }
            // else: a content kind this SDK version added and the spec has no mapping for.
            // It is not silently discarded — it has no representation yet; §2's table is fixed.
        }
        return new Mapped(parts, String.join("\n", resourceText));
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
