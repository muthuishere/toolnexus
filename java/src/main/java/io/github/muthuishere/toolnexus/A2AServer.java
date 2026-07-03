package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A2A (agent-to-agent) — INBOUND: serve a toolkit as a remote A2A agent. Mirrors
 * the JS reference ({@code js/src/serve.ts}) byte-for-byte on the wire.
 *
 * <p>{@code toolkit.serve(addr, { client, a2a })} stands up a minimal HTTP server
 * that, when the {@code a2a} profile is present, exposes:
 *
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} → an Agent Card built from the
 *       toolkit's <b>skills</b> (SKILL.md name+description), never raw tools.</li>
 *   <li>{@code POST /} → JSON-RPC 2.0: {@code SendMessage} (submit a Task, fulfil
 *       it asynchronously via {@code client.run}) + {@code GetTask} (poll).</li>
 * </ul>
 *
 * <p>This is the inbound counterpart to {@link A2A} (outbound) and speaks the same
 * wire subset (verified against a2a-python). Task persistence is a pluggable
 * {@link TaskStore} (in-memory default; file / custom selectable via {@code a2a.store}).
 * See {@code ../../SPEC.md §7B} and {@code openspec/changes/add-a2a-agents}.
 */
public final class A2AServer {
    private A2AServer() {}

    private static final String PROTOCOL_VERSION = "0.3.0";
    private static final String DEFAULT_VERSION = "0.1.0";
    private static final String CARD_PATH = "/.well-known/agent-card.json";

    // -----------------------------------------------------------------------
    // TaskStore adapter — pluggable persistence for served Tasks.
    // -----------------------------------------------------------------------

    /**
     * Pluggable Task persistence. A Task is a plain JSON map (the same wire shape
     * {@link A2A} consumes). {@code get} returns {@code null} for an unknown id.
     */
    public interface TaskStore {
        /** The current Task by id, or {@code null} if none. */
        Map<String, Object> get(String id);

        /** Persist a Task (keyed by its {@code id}). */
        void save(Map<String, Object> task);
    }

    /** Default in-memory store — Tasks live only for the lifetime of the process. */
    public static final class InMemoryTaskStore implements TaskStore {
        private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> get(String id) {
            return tasks.get(id);
        }

        @Override
        public void save(Map<String, Object> task) {
            tasks.put(String.valueOf(task.get("id")), task);
        }
    }

    /** File-backed store — one JSON file per Task id, under {@code dir}. */
    public static final class FileTaskStore implements TaskStore {
        private final Path dir;

        public FileTaskStore(String dir) {
            this.dir = Path.of(dir);
            try {
                Files.createDirectories(this.dir);
            } catch (IOException e) {
                throw new RuntimeException("Cannot create A2A store dir: " + dir, e);
            }
        }

        private Path file(String id) {
            return dir.resolve(Tool.sanitize(id) + ".json");
        }

        @Override
        public Map<String, Object> get(String id) {
            try {
                return Json.toMap(Files.readString(file(id)));
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void save(Map<String, Object> task) {
            try {
                Files.writeString(file(String.valueOf(task.get("id"))), Json.stringify(task));
            } catch (IOException e) {
                throw new RuntimeException("Cannot write A2A task: " + e.getMessage(), e);
            }
        }
    }

    /** Map a {@code store} selector to a concrete TaskStore. {@code "file:<dir>"} → FileTaskStore. */
    public static TaskStore resolveStore(Object store) {
        if (store == null || "memory".equals(store)) return new InMemoryTaskStore();
        if (store instanceof TaskStore) return (TaskStore) store;
        if (store instanceof String) {
            String s = (String) store;
            if (s.startsWith("file:")) return new FileTaskStore(s.substring("file:".length()));
            throw new RuntimeException("Unknown A2A store: \"" + s + "\" (expected \"memory\" or \"file:<dir>\")");
        }
        throw new RuntimeException("Unknown A2A store: " + store);
    }

    // -----------------------------------------------------------------------
    // A2A serve config + handle.
    // -----------------------------------------------------------------------

    /** Agent Card provider block — surfaced only when configured. */
    public record A2AProvider(String organization, String url) {}

    /** Opt-in A2A profile for {@code toolkit.serve} — configures the Agent Card + store. */
    public static final class A2AConfig {
        public String name;
        public String description;
        public String version;
        public A2AProvider provider;
        /** Subset of the toolkit's skill ids/names to advertise; null ⇒ all. */
        public List<String> skills;
        /** {@code "memory"} (default) | {@code "file:<dir>"} | a custom {@link TaskStore} | null. */
        public Object store;

        public A2AConfig name(String v) { this.name = v; return this; }
        public A2AConfig description(String v) { this.description = v; return this; }
        public A2AConfig version(String v) { this.version = v; return this; }
        public A2AConfig provider(A2AProvider v) { this.provider = v; return this; }
        public A2AConfig skills(List<String> v) { this.skills = v; return this; }
        public A2AConfig store(Object v) { this.store = v; return this; }

        /** Build an A2AConfig from a top-level {@code a2a} config block (parsed JSON map). */
        @SuppressWarnings("unchecked")
        public static A2AConfig fromMap(Map<String, Object> m) {
            A2AConfig cfg = new A2AConfig();
            if (m == null) return cfg;
            if (m.get("name") instanceof String s) cfg.name = s;
            if (m.get("description") instanceof String s) cfg.description = s;
            if (m.get("version") instanceof String s) cfg.version = s;
            if (m.get("provider") instanceof Map<?, ?> p) {
                Object org = p.get("organization");
                Object url = p.get("url");
                cfg.provider = new A2AProvider(
                        org == null ? null : String.valueOf(org),
                        url == null ? null : String.valueOf(url));
            }
            if (m.get("skills") instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) if (o != null) out.add(String.valueOf(o));
                cfg.skills = out;
            }
            if (m.get("store") instanceof String s) cfg.store = s;
            return cfg;
        }
    }

    /** Surfaced on each Task's terminal state, carrying the RunResult telemetry. */
    public record OnTaskEvent(String id, String skill, Map<String, Object> task,
                              LlmClient.RunResult result, String state) {}

    /** Host callback fired on each served Task's terminal state. */
    @FunctionalInterface
    public interface OnTask {
        void accept(OnTaskEvent ev);
    }

    /**
     * Runs one served Task through the client loop. {@code contextId} (from the A2A
     * message, may be {@code null}) keys the conversation so served turns are remembered
     * via the client's {@link LlmClient.ConversationStore}. Mirrors JS {@code runTask(text, contextId)}.
     */
    @FunctionalInterface
    public interface RunTask {
        LlmClient.RunResult run(String text, String contextId);
    }

    /** Handle returned by {@code toolkit.serve} — the base URL plus a stop/close method. */
    public interface ServeHandle {
        /** Base URL of the server, e.g. {@code http://127.0.0.1:PORT}. */
        String url();

        void stop();

        /** Alias for {@link #stop()}. */
        default void close() {
            stop();
        }
    }

    // -----------------------------------------------------------------------
    // Card builder.
    // -----------------------------------------------------------------------

    /**
     * Build an Agent Card from the toolkit's skills. {@code skills} are the
     * SkillSource's {@code SkillInfo}s (never raw tools); filtered to
     * {@code cfg.skills} when given. {@code url} is the JSON-RPC endpoint peers POST to.
     */
    public static Map<String, Object> buildAgentCard(A2AConfig cfg, List<SkillSource.SkillInfo> skills, String url) {
        List<String> wanted = cfg.skills;
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", cfg.name != null ? cfg.name : "toolnexus-agent");
        card.put("description", cfg.description != null ? cfg.description : "");
        card.put("version", cfg.version != null ? cfg.version : DEFAULT_VERSION);
        card.put("protocolVersion", PROTOCOL_VERSION);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("streaming", false);
        capabilities.put("pushNotifications", false);
        card.put("capabilities", capabilities);
        card.put("defaultInputModes", List.of("text"));
        card.put("defaultOutputModes", List.of("text"));
        List<Map<String, Object>> skillList = new ArrayList<>();
        for (SkillSource.SkillInfo s : skills) {
            if (wanted != null && !wanted.contains(s.name)) continue;
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.name);
            sm.put("name", s.name);
            sm.put("description", s.description != null ? s.description : "");
            skillList.add(sm);
        }
        card.put("skills", skillList);
        card.put("url", url);
        if (cfg.provider != null) {
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("organization", cfg.provider.organization());
            prov.put("url", cfg.provider.url());
            card.put("provider", prov);
        }
        return card;
    }

    // -----------------------------------------------------------------------
    // Server.
    // -----------------------------------------------------------------------

    /**
     * Start the HTTP server. Delegated to by {@code Toolkit.serve}. When {@code a2a}
     * is absent, the server answers 404 to everything (a minimal base for now).
     */
    public static ServeHandle start(String addr, A2AConfig a2a, List<SkillSource.SkillInfo> skills,
                                    RunTask runTask, OnTask onTask) throws IOException {
        return start(addr, a2a, skills, runTask, onTask, null, null, null);
    }

    /**
     * Start the HTTP server with both the A2A profile (inbound §7B) and the MCP
     * serve profile (inbound §7C). When {@code mcp} is present, a stateless MCP
     * server is co-mounted at {@code POST /mcp}, independent of A2A; when both
     * profiles are absent, the server answers 404 to everything.
     */
    public static ServeHandle start(String addr, A2AConfig a2a, List<SkillSource.SkillInfo> skills,
                                    RunTask runTask, OnTask onTask,
                                    McpServe.MCPServeConfig mcp, List<Tool> mcpTools, McpServe.OnCall onCall)
            throws IOException {
        String[] hp = splitAddr(addr);
        String host = hp[0];
        int port = Integer.parseInt(hp[1].trim());
        TaskStore store = a2a != null ? resolveStore(a2a.store) : null;

        // MCP serve profile (§7C): a stateless SDK MCP server bridged onto this
        // JDK HttpServer at POST /mcp, built once and reused (stateless — no session).
        final McpServe.JdkStatelessHttpTransport mcpTransport;
        final io.modelcontextprotocol.server.McpStatelessSyncServer mcpServer;
        if (mcp != null) {
            mcpTransport = new McpServe.JdkStatelessHttpTransport();
            mcpServer = McpServe.buildStatelessServer(mcpTransport,
                    McpServe.exposedMcpTools(mcpTools == null ? List.of() : mcpTools, mcp), mcp, onCall);
        } else {
            mcpTransport = null;
            mcpServer = null;
        }

        // A single virtual-thread executor drives background fulfilment off the
        // request threads; a fulfilment error never propagates to the server.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/", exchange -> {
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                // MCP streamable-HTTP profile (independent of A2A): checked first so
                // /mcp is never shadowed by the A2A POST handler. Absent ⇒ no /mcp.
                if (mcpTransport != null && "/mcp".equals(path)) {
                    mcpTransport.handle(exchange);
                    return;
                }

                // No A2A profile ⇒ no A2A routes (an MCP-only server 404s everything else).
                if (a2a == null || store == null) {
                    sendText(exchange, 404, "not found");
                    return;
                }

                if ("GET".equals(method) && CARD_PATH.equals(path)) {
                    String url = baseUrl(host, server.getAddress().getPort()) + "/";
                    sendJson(exchange, 200, Json.stringify(buildAgentCard(a2a, skills, url)));
                    return;
                }

                if ("POST".equals(method)) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    Map<String, Object> rpc;
                    try {
                        rpc = Json.toMap(body);
                    } catch (Exception e) {
                        sendJson(exchange, 200, rpcEnvelope(null, errorPayload(-32700, "Parse error")));
                        return;
                    }
                    Object rpcId = rpc.get("id");
                    Object methodObj = rpc.get("method");
                    String rpcMethod = methodObj == null ? null : String.valueOf(methodObj);

                    if ("SendMessage".equals(rpcMethod)) {
                        Map<String, Object> params = asMap(rpc.get("params"));
                        String text = messageText(params);
                        Map<String, Object> message = params == null ? null : asMap(params.get("message"));
                        String contextId = message != null && message.get("contextId") instanceof String
                                ? (String) message.get("contextId") : null;
                        String id = UUID.randomUUID().toString();
                        Map<String, Object> submitted = task(id, "submitted");
                        // contextId groups a peer's turns into one conversation — carry it on the Task.
                        if (contextId != null) submitted.put("contextId", contextId);
                        final String ctx = contextId;
                        // Persist submitted, kick fulfilment async, return the id immediately.
                        store.save(submitted);
                        executor.submit(() -> fulfil(id, text, ctx, store, runTask, onTask));
                        sendJson(exchange, 200, rpcEnvelope(rpcId, okPayload(submitted)));
                        return;
                    }
                    if ("GetTask".equals(rpcMethod)) {
                        Map<String, Object> params = asMap(rpc.get("params"));
                        Object idObj = params == null ? null : params.get("id");
                        String id = idObj == null ? "" : String.valueOf(idObj);
                        Map<String, Object> t = store.get(id);
                        if (t == null) {
                            sendJson(exchange, 200, rpcEnvelope(rpcId, errorPayload(-32001, "Task not found: " + id)));
                        } else {
                            sendJson(exchange, 200, rpcEnvelope(rpcId, okPayload(t)));
                        }
                        return;
                    }
                    sendJson(exchange, 200, rpcEnvelope(rpcId, errorPayload(-32601, "Method not found: " + rpcMethod)));
                    return;
                }

                sendText(exchange, 404, "not found");
            } catch (Exception e) {
                // A handler crash must never take the server down.
                try {
                    sendText(exchange, 500, "error");
                } catch (Exception ignored) {
                    // response already committed
                }
            }
        });

        server.start();
        String base = baseUrl(host, server.getAddress().getPort());

        return new ServeHandle() {
            @Override
            public String url() {
                return base;
            }

            @Override
            public void stop() {
                if (mcpServer != null) {
                    try {
                        mcpServer.closeGracefully();
                    } catch (Exception ignored) {
                        // best-effort teardown
                    }
                }
                server.stop(0);
                executor.shutdownNow();
            }
        };
    }

    /**
     * Background fulfilment: submitted → working → (completed | failed). Never
     * throws — a fulfilment error becomes a {@code failed} Task, keeping the server alive.
     */
    static void fulfil(String id, String text, String contextId, TaskStore store,
                       RunTask runTask, OnTask onTask) {
        Map<String, Object> task;
        LlmClient.RunResult result = null;
        String state;
        try {
            store.save(task(id, "working"));
            // Thread contextId so the client's ConversationStore remembers across tasks in the same context.
            result = runTask.run(text, contextId);
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("artifactId", UUID.randomUUID().toString());
            artifact.put("parts", List.of(textPart(result.text)));
            task = new LinkedHashMap<>();
            task.put("id", id);
            task.put("status", statusState("completed"));
            task.put("artifacts", List.of(artifact));
            state = "completed";
        } catch (Throwable e) {
            String detail = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "agent");
            message.put("parts", List.of(textPart(detail)));
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("state", "failed");
            status.put("message", message);
            task = new LinkedHashMap<>();
            task.put("id", id);
            task.put("status", status);
            state = "failed";
        }
        try {
            store.save(task);
        } catch (Exception ignored) {
            // A store write failure must not crash the server.
        }
        if (onTask != null) {
            try {
                onTask.accept(new OnTaskEvent(id, null, task, result, state));
            } catch (Exception ignored) {
                // Host callback errors are isolated.
            }
        }
    }

    // -----------------------------------------------------------------------
    // small helpers
    // -----------------------------------------------------------------------

    /** Extract the concatenated text of a JSON-RPC {@code SendMessage} message's parts. */
    private static String messageText(Map<String, Object> params) {
        if (params == null) return "";
        Map<String, Object> message = asMap(params.get("message"));
        if (message == null) return "";
        Object partsObj = message.get("parts");
        if (!(partsObj instanceof List)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object p : (List<?>) partsObj) {
            Map<String, Object> pm = asMap(p);
            if (pm == null) continue;
            if (!"text".equals(pm.get("kind"))) continue;
            if (pm.get("text") instanceof String s) sb.append(s);
        }
        return sb.toString();
    }

    private static Map<String, Object> task(String id, String state) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("status", statusState(state));
        return t;
    }

    private static Map<String, Object> statusState(String state) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", state);
        return status;
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "text");
        p.put("text", text);
        return p;
    }

    /** JSON-RPC envelope: {@code {jsonrpc:"2.0", id, ...payload}} (id null when absent). */
    private static String rpcEnvelope(Object id, Map<String, Object> payload) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.putAll(payload);
        return Json.stringify(env);
    }

    private static Map<String, Object> okPayload(Object result) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("result", result);
        return p;
    }

    private static Map<String, Object> errorPayload(int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("error", err);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
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
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // -----------------------------------------------------------------------
    // Address helpers.
    // -----------------------------------------------------------------------

    /** Split {@code host:port} on the last colon. Missing host ⇒ 127.0.0.1. */
    private static String[] splitAddr(String addr) {
        int idx = addr.lastIndexOf(':');
        if (idx == -1) return new String[]{"127.0.0.1", addr};
        String host = addr.substring(0, idx);
        if (host.isEmpty()) host = "127.0.0.1";
        return new String[]{host, addr.substring(idx + 1)};
    }

    /** Base URL for a listening server; 0.0.0.0/:: is reported as 127.0.0.1 for callers. */
    private static String baseUrl(String host, int port) {
        String h = ("0.0.0.0".equals(host) || "::".equals(host)) ? "127.0.0.1" : host;
        return "http://" + h + ":" + port;
    }
}
