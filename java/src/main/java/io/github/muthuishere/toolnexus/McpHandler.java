package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP — INBOUND, embeddable: a plain streamable-HTTP MCP server as a JDK {@link HttpHandler} with
 * generic per-request hooks (SPEC §7C.1, ADR 0039). Mirrors the Go reference
 * ({@code golang/mcphandler.go}).
 *
 * <p>{@code toolkit.serve(addr, …)} owns a listener and exposes one static tool list. An
 * {@code McpHandler} is what a host mounts on its own {@link com.sun.net.httpserver.HttpServer}
 * context, behind its own TLS, CORS and OAuth. Every policy is a hook; toolnexus has no notion of
 * tenant, user, role or storage.
 *
 * <p>The JSON-RPC transport is implemented here by hand (no MCP SDK server, no SDK types on the
 * public API). A confirm elicitation rides the SAME POST's SSE response.
 *
 * <p><b>Executor:</b> GET streams and confirm round-trips block a handler thread while another
 * request is served, so the host's {@code HttpServer} MUST have a multi-threaded executor (e.g.
 * {@code server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())}); the JDK default runs
 * every exchange on one dispatcher thread.
 */
public final class McpHandler implements HttpHandler {

    /** The protocol versions whose wire this handler implements (ADR 0039 §versions). */
    public static final List<String> IMPLEMENTED_VERSIONS = List.of("2026-07-28", "2025-11-25", "2025-06-18");
    /**
     * The MCP 2026-07-28 protocol version (SPEC §7C.2): opt-in only, never a default. Listing it in
     * {@link Config#protocolVersions} enables the stateless wire (server/discover, header
     * validation, MRTR, skills extension) on this handler.
     */
    public static final String PROTOCOL_2026 = "2026-07-28";
    /** The SEP-2640 skills extension id advertised by server/discover when {@code listSkills} is set. */
    public static final String SKILLS_EXTENSION = "io.modelcontextprotocol/skills";
    private static final List<String> DEFAULT_VERSIONS = List.of("2025-06-18");
    private static final String DEFAULT_NAME = "toolnexus";
    private static final String DEFAULT_VERSION = "0.1.0";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // ── public types ─────────────────────────────────────────────────────

    /** The outcome kind of a {@code beforeCall} gate. */
    public enum DecisionKind {
        ALLOW, DENY, CONFIRM;

        /** The lowercase wire/event name ("allow" | "deny" | "confirm"). */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What {@code beforeCall} returns. {@code null} (or a null kind) allows.
     * <ul>
     *   <li>ALLOW: the tool runs.</li>
     *   <li>DENY: the tool does not run; the caller gets {@code result} when set, else an isError
     *       result carrying {@code message} (default "denied").</li>
     *   <li>CONFIRM: the client is asked (elicitation/create) when it declared the elicitation
     *       capability; otherwise the caller gets {@code result} when set, else isError
     *       "confirmation required: &lt;message&gt;".</li>
     * </ul>
     */
    public record Decision(DecisionKind kind, String message, ToolResult result) {
        public static Decision allow() { return new Decision(DecisionKind.ALLOW, null, null); }
        public static Decision deny(String message) { return new Decision(DecisionKind.DENY, message, null); }
        public static Decision deny(String message, ToolResult result) { return new Decision(DecisionKind.DENY, message, result); }
        public static Decision confirm(String message) { return new Decision(DecisionKind.CONFIRM, message, null); }
        public static Decision confirm(String message, ToolResult fallback) { return new Decision(DecisionKind.CONFIRM, message, fallback); }
    }

    /** Advertised at initialize. {@code title} empty/null ⇒ omitted. */
    public record ServerInfo(String name, String title, String version, String instructions) {}

    /** One entry of resources/list. */
    public record Resource(String uri, String name, String title, String description, String mimeType) {}

    /** One entry of resources/read. Set {@code text}, or {@code blob} (base64). */
    public record ResourceContent(String uri, String mimeType, String text, String blob) {}

    /** One prompt argument. */
    public record PromptArgument(String name, String description, boolean required) {}

    /** One entry of prompts/list. */
    public record Prompt(String name, String title, String description, List<PromptArgument> arguments) {}

    /** One text message of a prompts/get result; role is "user" | "assistant". */
    public record PromptMessage(String role, String text) {}

    /** The prompts/get result. */
    public record PromptResult(String description, List<PromptMessage> messages) {}

    /** Surfaced once per inbound tools/call. {@code err} is the execute throwable, when any. */
    public record CallEvent(String name, String source, long ms, boolean isError, Throwable err,
                            int resultBytes, DecisionKind decision, Object identity, String sessionId) {}

    /**
     * The inbound request being served — reachable from every hook and, via
     * {@link ToolContext#mcp()}, from the tool. SPEC §7C.1 identityFrom / requestFrom /
     * mcpSessionIdFrom / mcpRequestMetaFrom.
     */
    public static final class RequestContext {
        private final Object identity;
        private final HttpExchange exchange;
        private final String sessionId;
        private final Map<String, Object> requestMeta;

        RequestContext(Object identity, HttpExchange exchange, String sessionId, Map<String, Object> requestMeta) {
            this.identity = identity;
            this.exchange = exchange;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.requestMeta = requestMeta;
        }

        /** What {@code authenticate} produced ({@code null} when none is configured). */
        public Object identity() { return identity; }

        /** The inbound HTTP exchange (e.g. to read the Host header). */
        public HttpExchange exchange() { return exchange; }

        /** The Mcp-Session-Id of the request ("" when none). */
        public String sessionId() { return sessionId; }

        /** The {@code params._meta} of the JSON-RPC request ({@code null} when absent). */
        public Map<String, Object> requestMeta() { return requestMeta; }

        RequestContext withSession(String sid) { return new RequestContext(identity, exchange, sid, requestMeta); }

        RequestContext withMeta(Map<String, Object> meta) { return new RequestContext(identity, exchange, sessionId, meta); }
    }

    /** SPEC §7C.1 identityFrom: the authenticated identity of the MCP call, or {@code null}. */
    public static Object identityFrom(ToolContext ctx) {
        return ctx == null || ctx.mcp() == null ? null : ctx.mcp().identity();
    }

    /** SPEC §7C.1 requestFrom: the inbound HTTP exchange, or {@code null}. */
    public static HttpExchange requestFrom(ToolContext ctx) {
        return ctx == null || ctx.mcp() == null ? null : ctx.mcp().exchange();
    }

    /** SPEC §7C.1 mcpSessionIdFrom: the Mcp-Session-Id, or "". */
    public static String mcpSessionIdFrom(ToolContext ctx) {
        return ctx == null || ctx.mcp() == null ? "" : ctx.mcp().sessionId();
    }

    /** SPEC §7C.1 mcpRequestMetaFrom: the request {@code params._meta}, or {@code null}. */
    public static Map<String, Object> mcpRequestMetaFrom(ToolContext ctx) {
        return ctx == null || ctx.mcp() == null ? null : ctx.mcp().requestMeta();
    }

    @FunctionalInterface public interface Authenticate { Object authenticate(HttpExchange exchange) throws Exception; }
    @FunctionalInterface public interface WwwAuthenticate { String header(HttpExchange exchange, Exception error); }
    @FunctionalInterface public interface ServerInfoHook { ServerInfo serverInfo(RequestContext ctx); }
    @FunctionalInterface public interface ListTools { List<Tool> listTools(RequestContext ctx) throws Exception; }
    /** {@code rawArgs} is the JSON text of the call arguments ("{}" when absent). */
    @FunctionalInterface public interface BeforeCall { Decision beforeCall(RequestContext ctx, Tool tool, String rawArgs) throws Exception; }
    @FunctionalInterface public interface OnCall { void onCall(CallEvent ev); }
    @FunctionalInterface public interface ListResources { List<Resource> listResources(RequestContext ctx) throws Exception; }
    @FunctionalInterface public interface ReadResource { List<ResourceContent> readResource(RequestContext ctx, String uri) throws Exception; }
    @FunctionalInterface public interface ListPrompts { List<Prompt> listPrompts(RequestContext ctx) throws Exception; }
    @FunctionalInterface public interface GetPrompt { PromptResult getPrompt(RequestContext ctx, String name, Map<String, String> args) throws Exception; }
    /** SPEC §7C.2: the skills published through the SEP-2640 skills extension on the 2026-07-28 wire. */
    @FunctionalInterface public interface ListSkills { List<Skill> listSkills(RequestContext ctx) throws Exception; }

    /**
     * One skill published through the skills extension (SEP-2640, SPEC §7C.2). {@code files} are
     * paths relative to the skill directory ("/"-separated) and MUST include "SKILL.md".
     * {@code frontmatter} null ⇒ {@code {name}}. {@link #loadMcpSkills} builds these from a directory.
     */
    public record Skill(String name, Map<String, Object> frontmatter, List<SkillFile> files) {}

    /** One text file of a {@link Skill}. */
    public record SkillFile(String path, String text) {}

    /**
     * SPEC §7C.2 loadMcpSkills: every skill under {@code dir} (any depth, {@code **}/SKILL.md, symlinks
     * not followed) for {@link Config#listSkills}. Frontmatter is {@code {name, description}} (§3
     * parsing); a skill without a name is skipped; files are every regular UTF-8 file under the skill
     * directory (binary files skipped), sorted by path. Skills are sorted by name.
     */
    public static List<Skill> loadMcpSkills(java.nio.file.Path dir) throws IOException {
        return McpHandler2026.loadSkills(dir);
    }

    /**
     * Issues and checks Mcp-Session-Id values. Default: an in-memory set. A host plugs its own
     * (signed ids, a DB lookup) so any replica can validate any session.
     */
    public interface SessionIds {
        String generate(RequestContext ctx) throws Exception;
        /** Whether {@code id} is a live session (false ⇒ HTTP 404). */
        boolean validate(RequestContext ctx, String id) throws Exception;
        void terminate(RequestContext ctx, String id) throws Exception;
    }

    /** Configures an {@link McpHandler}. Every field is optional. */
    public static final class Config {
        /** Static serverInfo; {@link #serverInfo} wins. Defaults "toolnexus" / "0.1.0". */
        public String name, title, version, instructions;
        public ServerInfoHook serverInfo;
        /** Accepted versions, newest first; entries outside {@link #IMPLEMENTED_VERSIONS} dropped. Default ["2025-06-18"]. */
        public List<String> protocolVersions;
        public Authenticate authenticate;
        public WwwAuthenticate wwwAuthenticate;
        /** Per request, for tools/list AND the tools/call lookup; null ⇒ the toolkit's tools. */
        public ListTools listTools;
        public BeforeCall beforeCall;
        /** Bounds an elicitation round-trip. Default 5 minutes. */
        public Duration confirmTimeout;
        public OnCall onCall;
        public ListResources listResources;
        public ReadResource readResource;
        public ListPrompts listPrompts;
        public GetPrompt getPrompt;
        /** SPEC §7C.2 skills extension (2026-07-28 wire only); null ⇒ not advertised. */
        public ListSkills listSkills;
        public SessionIds sessionIds;
        /** No Mcp-Session-Id issued or checked; confirm never elicits. */
        public boolean stateless;
        /** Stateless only: returned as Mcp-Session-Id on initialize, never validated. */
        public String statelessSessionId;
        /** Caps a POST body. Default 4 MiB. */
        public long maxBodyBytes;

        public Config name(String v) { this.name = v; return this; }
        public Config title(String v) { this.title = v; return this; }
        public Config version(String v) { this.version = v; return this; }
        public Config instructions(String v) { this.instructions = v; return this; }
        public Config serverInfo(ServerInfoHook v) { this.serverInfo = v; return this; }
        public Config protocolVersions(List<String> v) { this.protocolVersions = v; return this; }
        public Config protocolVersions(String... v) { this.protocolVersions = List.of(v); return this; }
        public Config authenticate(Authenticate v) { this.authenticate = v; return this; }
        public Config wwwAuthenticate(WwwAuthenticate v) { this.wwwAuthenticate = v; return this; }
        public Config listTools(ListTools v) { this.listTools = v; return this; }
        public Config beforeCall(BeforeCall v) { this.beforeCall = v; return this; }
        public Config confirmTimeout(Duration v) { this.confirmTimeout = v; return this; }
        public Config onCall(OnCall v) { this.onCall = v; return this; }
        public Config listResources(ListResources v) { this.listResources = v; return this; }
        public Config readResource(ReadResource v) { this.readResource = v; return this; }
        public Config listPrompts(ListPrompts v) { this.listPrompts = v; return this; }
        public Config getPrompt(GetPrompt v) { this.getPrompt = v; return this; }
        public Config listSkills(ListSkills v) { this.listSkills = v; return this; }
        public Config sessionIds(SessionIds v) { this.sessionIds = v; return this; }
        public Config stateless(boolean v) { this.stateless = v; return this; }
        public Config statelessSessionId(String v) { this.statelessSessionId = v; return this; }
        public Config maxBodyBytes(long v) { this.maxBodyBytes = v; return this; }
    }

    // ── state ────────────────────────────────────────────────────────────

    private final Toolkit toolkit;
    private final Config cfg;
    private final SessionIds ids;
    private final List<String> versions;      // every accepted version, newest first
    private final List<String> legacy;        // versions minus 2026-07-28: the initialize/session wire
    private final Duration confirmTimeout;
    private final long maxBodyBytes;

    private final Map<String, Boolean> caps = new ConcurrentHashMap<>();
    private final Set<BlockingQueue<String>> streams = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    McpHandler(Toolkit toolkit, Config cfg) {
        this.toolkit = toolkit;
        this.cfg = cfg == null ? new Config() : cfg;
        List<String> vs = new ArrayList<>();
        if (this.cfg.protocolVersions != null) {
            for (String v : this.cfg.protocolVersions) {
                if (IMPLEMENTED_VERSIONS.contains(v) && !vs.contains(v)) vs.add(v);
            }
        }
        this.versions = vs.isEmpty() ? DEFAULT_VERSIONS : List.copyOf(vs);
        List<String> lg = new ArrayList<>(this.versions);
        lg.remove(PROTOCOL_2026);
        this.legacy = List.copyOf(lg);
        this.ids = this.cfg.sessionIds != null ? this.cfg.sessionIds : new MemorySessionIds();
        this.confirmTimeout = this.cfg.confirmTimeout == null || this.cfg.confirmTimeout.isNegative()
                || this.cfg.confirmTimeout.isZero() ? Duration.ofMinutes(5) : this.cfg.confirmTimeout;
        this.maxBodyBytes = this.cfg.maxBodyBytes <= 0 ? 4L << 20 : this.cfg.maxBodyBytes;
    }

    /** The versions this handler accepts, newest first. */
    public List<String> protocolVersions() {
        return versions;
    }

    /**
     * Send notifications/tools/list_changed on every open GET stream. Clients re-run tools/list
     * (which calls listTools again). A slow stream drops the message rather than block.
     */
    public void notifyToolsChanged() {
        String msg = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}";
        for (BlockingQueue<String> q : streams) q.offer(msg);
    }

    private static final class MemorySessionIds implements SessionIds {
        private static final SecureRandom RNG = new SecureRandom();
        private final Set<String> live = ConcurrentHashMap.newKeySet();

        @Override public String generate(RequestContext ctx) {
            byte[] b = new byte[16];
            RNG.nextBytes(b);
            String id = HexFormat.of().formatHex(b);
            live.add(id);
            return id;
        }

        @Override public boolean validate(RequestContext ctx, String id) { return live.contains(id); }

        @Override public void terminate(RequestContext ctx, String id) { live.remove(id); }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if ("POST".equals(ex.getRequestMethod()) && versions.contains(PROTOCOL_2026) && handlePost2026(ex)) return;
            if (only2026() && !"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "POST");
                httpError(ex, 405, "Method Not Allowed");
                return;
            }
            RequestContext ctx = new RequestContext(null, ex, "", null);
            if (cfg.authenticate != null) {
                Object identity;
                try {
                    identity = cfg.authenticate.authenticate(ex);
                } catch (Exception e) {
                    writeAuthFailure(ex, e);
                    return;
                }
                ctx = new RequestContext(identity, ex, "", null);
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> handlePost(ctx, ex);
                case "GET" -> handleGet(ctx, ex);
                case "DELETE" -> handleDelete(ctx, ex);
                default -> {
                    ex.getResponseHeaders().set("Allow", "GET, POST, DELETE");
                    httpError(ex, 405, "Method Not Allowed");
                }
            }
        } finally {
            ex.close();
        }
    }

    /** Validates MCP-Protocol-Version and (stateful) Mcp-Session-Id; null ⇒ the error was written. */
    private RequestContext checkSession(RequestContext ctx, HttpExchange ex) throws IOException {
        String v = header(ex, "MCP-Protocol-Version");
        if (!v.isEmpty() && !versions.contains(v)) {
            httpError(ex, 400, "Bad Request: unsupported MCP-Protocol-Version");
            return null;
        }
        if (cfg.stateless) return ctx;
        String sid = header(ex, "Mcp-Session-Id");
        if (sid.isEmpty()) {
            httpError(ex, 400, "Bad Request: missing Mcp-Session-Id");
            return null;
        }
        boolean ok;
        try {
            ok = ids.validate(ctx, sid);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            httpError(ex, 404, "Session not found");
            return null;
        }
        return ctx.withSession(sid);
    }

    private void handlePost(RequestContext ctx, HttpExchange ex) throws IOException {
        if (!"application/json".equals(mediaType(header(ex, "Content-Type")))) {
            httpError(ex, 415, "Unsupported Media Type: must be application/json");
            return;
        }
        byte[] body;
        try {
            body = readLimited(ex.getRequestBody(), maxBodyBytes + 1);
        } catch (IOException e) {
            writeRpcError(ex, null, -32700, "read error");
            return;
        }
        if (body.length > maxBodyBytes) {
            httpError(ex, 413, "Request Entity Too Large");
            return;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.strip().startsWith("[")) {
            // JSON-RPC batching was removed in protocol 2025-06-18.
            writeRpcError(ex, null, -32600, "batch requests are not supported");
            return;
        }
        JsonNode msg;
        try {
            msg = MAPPER.readTree(text);
        } catch (Exception e) {
            msg = null;
        }
        if (msg == null || !msg.isObject() || !"2.0".equals(textOrNull(msg.get("jsonrpc")))
                || !stringOrAbsent(msg.get("method"))) {
            writeRpcError(ex, null, -32700, "parse error");
            return;
        }
        String method = msg.hasNonNull("method") ? msg.get("method").asText() : "";
        JsonNode id = msg.get("id");
        JsonNode params = msg.get("params");

        if ("initialize".equals(method)) {
            handleInitialize(ctx, ex, id, params);
            return;
        }
        ctx = checkSession(ctx, ex);
        if (ctx == null) return;

        // A response from the client (the answer to our elicitation).
        if (method.isEmpty()) {
            if (id != null) deliver(ctx.sessionId(), id, msg);
            empty(ex, 202);
            return;
        }
        // A notification (no id): acknowledge.
        if (id == null || id.isNull()) {
            empty(ex, 202);
            return;
        }
        if (params != null && params.isObject() && params.get("_meta") != null && params.get("_meta").isObject()) {
            ctx = ctx.withMeta(toMap(params.get("_meta")));
        }
        RpcWriter rw = new RpcWriter(ex);
        Object result;
        RpcError err = null;
        try {
            result = dispatch(ctx, rw, method, params);
        } catch (RpcError e) {
            result = null;
            err = e;
        }
        rw.respond(id, result, err);
    }

    private void handleInitialize(RequestContext ctx, HttpExchange ex, JsonNode id, JsonNode params) throws IOException {
        String clientVersion = params != null ? textOrNull(params.get("protocolVersion")) : null;
        String version = clientVersion != null && legacy.contains(clientVersion) ? clientVersion : legacy.get(0);

        String sid = "";
        if (cfg.stateless) {
            sid = cfg.statelessSessionId == null ? "" : cfg.statelessSessionId;
        } else {
            try {
                sid = ids.generate(ctx);
            } catch (Exception e) {
                sid = null;
            }
            if (sid == null || sid.isEmpty()) {
                writeRpcError(ex, id, -32603, "cannot create session");
                return;
            }
            ctx = ctx.withSession(sid);
            JsonNode capsNode = params != null ? params.path("capabilities").get("elicitation") : null;
            caps.put(sid, capsNode != null && !capsNode.isNull());
        }

        ServerInfo info = new ServerInfo(cfg.name, cfg.title, cfg.version, cfg.instructions);
        if (cfg.serverInfo != null) info = cfg.serverInfo.serverInfo(ctx);
        if (info == null) info = new ServerInfo(null, null, null, null);
        String name = isEmpty(info.name()) ? DEFAULT_NAME : info.name();
        String ver = isEmpty(info.version()) ? DEFAULT_VERSION : info.version();

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", true));
        if (cfg.listResources != null) capabilities.put("resources", Map.of());
        if (cfg.listPrompts != null) capabilities.put("prompts", Map.of());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("protocolVersion", version);
        res.put("capabilities", capabilities);
        res.put("serverInfo", omitEmpty(kv("name", name, "title", info.title(), "version", ver)));
        if (!isEmpty(info.instructions())) res.put("instructions", info.instructions());
        if (!sid.isEmpty()) ex.getResponseHeaders().set("Mcp-Session-Id", sid);
        new RpcWriter(ex).respond(id, res, null);
    }

    private void handleGet(RequestContext ctx, HttpExchange ex) throws IOException {
        if (!header(ex, "Accept").contains("text/event-stream")) {
            httpError(ex, 405, "Method Not Allowed");
            return;
        }
        if (checkSession(ctx, ex) == null) return;
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        os.flush();
        BlockingQueue<String> q = new LinkedBlockingQueue<>(16);
        streams.add(q);
        try {
            while (true) {
                String m;
                try {
                    m = q.poll(15, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // An SSE comment on idle detects a gone client (Go learns it from the request ctx).
                String frame = m == null ? ": keepalive\n\n" : "event: message\ndata: " + m + "\n\n";
                os.write(frame.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException gone) {
            // client went away
        } finally {
            streams.remove(q);
        }
    }

    private void handleDelete(RequestContext ctx, HttpExchange ex) throws IOException {
        if (cfg.stateless) {
            httpError(ex, 405, "Method Not Allowed");
            return;
        }
        ctx = checkSession(ctx, ex);
        if (ctx == null) return;
        try {
            ids.terminate(ctx, ctx.sessionId());
        } catch (Exception e) {
            httpError(ex, 500, "cannot terminate session");
            return;
        }
        caps.remove(ctx.sessionId());
        empty(ex, 200);
    }

    // ── JSON-RPC dispatch ────────────────────────────────────────────────

    private static final class RpcError extends Exception {
        final int code;

        RpcError(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    private static String msgOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private Object dispatch(RequestContext ctx, RpcWriter rw, String method, JsonNode params) throws RpcError, IOException {
        switch (method) {
            case "ping":
                return Map.of();
            case "tools/list": {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Tool t : tools(ctx)) out.add(wireTool(t));
                return Map.of("tools", out);
            }
            case "tools/call":
                return callTool(ctx, rw, params);
            case "resources/list": {
                if (cfg.listResources == null) break;
                List<Resource> rs;
                try {
                    rs = cfg.listResources.listResources(ctx);
                } catch (Exception e) {
                    throw new RpcError(-32603, msgOf(e));
                }
                List<Map<String, Object>> out = new ArrayList<>();
                if (rs != null) for (Resource r : rs) {
                    out.add(omitEmpty(kv("uri", r.uri(), "name", r.name(), "title", r.title(),
                            "description", r.description(), "mimeType", r.mimeType())));
                }
                return Map.of("resources", out);
            }
            case "resources/read": {
                if (cfg.listResources == null || cfg.readResource == null) break;
                String uri = params != null ? textOrNull(params.get("uri")) : null;
                List<ResourceContent> cs;
                try {
                    cs = cfg.readResource.readResource(ctx, uri == null ? "" : uri);
                } catch (Exception e) {
                    throw new RpcError(-32002, msgOf(e));
                }
                List<Map<String, Object>> out = new ArrayList<>();
                if (cs != null) for (ResourceContent c : cs) {
                    Map<String, Object> m = omitEmpty(kv("uri", c.uri(), "mimeType", c.mimeType(), "blob", c.blob()));
                    if (isEmpty(c.blob())) m.put("text", c.text() == null ? "" : c.text());
                    out.add(m);
                }
                return Map.of("contents", out);
            }
            case "prompts/list": {
                if (cfg.listPrompts == null) break;
                List<Prompt> ps;
                try {
                    ps = cfg.listPrompts.listPrompts(ctx);
                } catch (Exception e) {
                    throw new RpcError(-32603, msgOf(e));
                }
                List<Map<String, Object>> out = new ArrayList<>();
                if (ps != null) for (Prompt p : ps) {
                    Map<String, Object> m = omitEmpty(kv("name", p.name(), "title", p.title(), "description", p.description()));
                    if (p.arguments() != null && !p.arguments().isEmpty()) {
                        List<Map<String, Object>> args = new ArrayList<>();
                        for (PromptArgument a : p.arguments()) {
                            Map<String, Object> am = omitEmpty(kv("name", a.name(), "description", a.description()));
                            if (a.required()) am.put("required", true);
                            args.add(am);
                        }
                        m.put("arguments", args);
                    }
                    out.add(m);
                }
                return Map.of("prompts", out);
            }
            case "prompts/get": {
                if (cfg.listPrompts == null || cfg.getPrompt == null) break;
                if (params == null || !(params.isObject() || params.isNull())) throw new RpcError(-32602, "invalid params");
                String name = "";
                Map<String, String> args = null;
                if (params.isObject()) {
                    JsonNode n = params.get("name");
                    if (n != null && !n.isNull() && !n.isTextual()) throw new RpcError(-32602, "invalid params");
                    if (n != null && n.isTextual()) name = n.asText();
                    JsonNode a = params.get("arguments");
                    if (a != null && !a.isNull()) {
                        if (!a.isObject()) throw new RpcError(-32602, "invalid params");
                        args = new LinkedHashMap<>();
                        for (Iterator<Map.Entry<String, JsonNode>> it = a.fields(); it.hasNext(); ) {
                            Map.Entry<String, JsonNode> e = it.next();
                            if (e.getValue().isNull()) { args.put(e.getKey(), ""); continue; }
                            if (!e.getValue().isTextual()) throw new RpcError(-32602, "invalid params");
                            args.put(e.getKey(), e.getValue().asText());
                        }
                    }
                }
                PromptResult pr;
                try {
                    pr = cfg.getPrompt.getPrompt(ctx, name, args == null ? Map.of() : args);
                } catch (Exception e) {
                    throw new RpcError(-32602, msgOf(e));
                }
                List<Map<String, Object>> msgs = new ArrayList<>();
                if (pr != null && pr.messages() != null) for (PromptMessage m : pr.messages()) {
                    msgs.add(kv("role", m.role(), "content", kv("type", "text", "text", m.text() == null ? "" : m.text())));
                }
                return omitEmpty(kv("description", pr == null ? null : pr.description(), "messages", msgs));
            }
            default:
                break;
        }
        throw new RpcError(-32601, "method not found: " + method);
    }

    private List<Tool> tools(RequestContext ctx) throws RpcError {
        if (cfg.listTools == null) return toolkit.tools();
        try {
            List<Tool> ts = cfg.listTools.listTools(ctx);
            return ts == null ? List.of() : ts;
        } catch (Exception e) {
            throw new RpcError(-32603, msgOf(e));
        }
    }

    private Object callTool(RequestContext ctx, RpcWriter rw, JsonNode params) throws RpcError, IOException {
        if (params == null || !(params.isObject() || params.isNull())) throw new RpcError(-32602, "invalid params");
        String name = "";
        JsonNode rawArgs = null;
        if (params.isObject()) {
            JsonNode n = params.get("name");
            if (n != null && !n.isNull() && !n.isTextual()) throw new RpcError(-32602, "invalid params");
            if (n != null && n.isTextual()) name = n.asText();
            rawArgs = params.get("arguments");
        }
        Tool tool = null;
        for (Tool t : tools(ctx)) {
            if (t != null && name.equals(t.name())) { tool = t; break; }
        }
        if (tool == null) throw new RpcError(-32602, "tool not found: " + name);

        Map<String, Object> args;
        String rawText;
        if (rawArgs != null && !rawArgs.isNull()) {
            if (!rawArgs.isObject()) {
                if ("2025-11-25".equals(header(ctx.exchange(), "MCP-Protocol-Version"))) {
                    // 2025-11-25: input validation errors are tool execution errors.
                    return wireResult(ToolResult.error("invalid arguments: must be an object"));
                }
                throw new RpcError(-32602, "arguments must be an object");
            }
            args = toMap(rawArgs);
            rawText = rawArgs.toString();
        } else {
            args = new LinkedHashMap<>();
            rawText = "{}";
        }

        long started = System.nanoTime();
        final Tool chosen = tool;
        DecisionKind decision = DecisionKind.ALLOW;

        if (cfg.beforeCall != null) {
            Decision d;
            try {
                d = cfg.beforeCall.beforeCall(ctx, chosen, rawText);
            } catch (Exception e) {
                d = Decision.deny(msgOf(e));
            }
            if (d == null) d = Decision.allow();
            DecisionKind kind = d.kind() == null ? DecisionKind.ALLOW : d.kind();
            decision = kind;
            if (kind == DecisionKind.DENY) {
                if (d.result() != null) return finish(ctx, chosen, decision, started, d.result(), null);
                String m = isEmpty(d.message()) ? "denied" : d.message();
                return finish(ctx, chosen, decision, started, ToolResult.error(m), null);
            }
            if (kind == DecisionKind.CONFIRM) {
                if (!canElicit(ctx.sessionId())) {
                    if (d.result() != null) return finish(ctx, chosen, decision, started, d.result(), null);
                    return finish(ctx, chosen, decision, started,
                            ToolResult.error("confirmation required: " + (d.message() == null ? "" : d.message())), null);
                }
                boolean accepted;
                try {
                    accepted = elicitConfirm(ctx, rw, d.message());
                } catch (Exception e) {
                    return finish(ctx, chosen, decision, started,
                            ToolResult.error("confirmation failed: " + msgOf(e)), null);
                }
                if (!accepted) return finish(ctx, chosen, decision, started, ToolResult.error("declined"), null);
            }
        }

        ToolResult res;
        Throwable execErr = null;
        try {
            res = chosen.execute(args, new ToolContext(null, null, null, null, ctx));
            if (res == null) res = ToolResult.ok("");
        } catch (Throwable e) {
            execErr = e;
            res = ToolResult.error(msgOf(e));
        }
        return finish(ctx, chosen, decision, started, res, execErr);
    }

    private Object finish(RequestContext ctx, Tool tool, DecisionKind decision, long startedNanos,
                          ToolResult res, Throwable execErr) {
        Map<String, Object> wire = wireResult(res);
        int bytes = Json.stringify(wire).getBytes(StandardCharsets.UTF_8).length;
        if (cfg.onCall != null) {
            try {
                cfg.onCall.onCall(new CallEvent(tool.name(), tool.source(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos), res.isError(), execErr,
                        bytes, decision, ctx.identity(), ctx.sessionId()));
            } catch (Throwable ignored) {
                // host callback failures are isolated
            }
        }
        return wire;
    }

    private boolean canElicit(String sid) {
        return sid != null && !sid.isEmpty() && Boolean.TRUE.equals(caps.get(sid));
    }

    /** Sends elicitation/create on this POST's SSE stream and waits for the answer (a later POST). */
    private boolean elicitConfirm(RequestContext ctx, RpcWriter rw, String message) throws Exception {
        String id = "toolnexus-confirm-" + seq.incrementAndGet();
        String key = ctx.sessionId() + "\u0000" + id;
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(key, fut);
        try {
            if (isEmpty(message)) message = "Confirm this action?";
            Map<String, Object> confirm = kv("type", "boolean", "title", "Confirm", "description", message);
            Map<String, Object> schema = kv("type", "object", "properties", kv("confirm", confirm));
            Map<String, Object> req = kv("jsonrpc", "2.0", "id", id, "method", "elicitation/create",
                    "params", kv("message", message, "requestedSchema", schema));
            rw.event(req);
            JsonNode answer;
            try {
                answer = fut.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw new Exception("context deadline exceeded");
            }
            JsonNode err = answer.get("error");
            if (err != null && !err.isNull()) throw new Exception("client returned an error");
            JsonNode result = answer.get("result");
            if (result == null || !result.isObject()) throw new Exception("invalid elicitation result");
            if (!"accept".equals(textOrNull(result.get("action")))) return false;
            JsonNode c = result.path("content").get("confirm");
            return c == null || !c.isBoolean() || c.asBoolean();
        } finally {
            pending.remove(key);
        }
    }

    private void deliver(String sid, JsonNode id, JsonNode msg) {
        if (!id.isTextual()) return;
        CompletableFuture<JsonNode> f = pending.get(sid + "\u0000" + id.asText());
        if (f != null) f.complete(msg);
    }

    // ── MCP 2026-07-28 (SPEC §7C.2, ADR 0040) ────────────────────────────

    private boolean only2026() {
        return versions.size() == 1 && PROTOCOL_2026.equals(versions.get(0));
    }

    /** Answers an {@code authenticate} failure (both wires). */
    private void writeAuthFailure(HttpExchange ex, Exception e) throws IOException {
        String challenge = "";
        if (cfg.wwwAuthenticate != null) {
            String v = cfg.wwwAuthenticate.header(ex, e);
            if (v != null) challenge = v;
        }
        if (e instanceof McpAuthError ae) {
            if (challenge.isEmpty() && ae.challenge() != null) challenge = ae.challenge();
            if (!challenge.isEmpty()) ex.getResponseHeaders().set("WWW-Authenticate", challenge);
            int status = ae.status() == 0 ? 401 : ae.status();
            sendBytes(ex, status, "application/json", "{\"error\":\"unauthorized\"}");
            return;
        }
        if (!challenge.isEmpty()) ex.getResponseHeaders().set("WWW-Authenticate", challenge);
        httpError(ex, 401, "Unauthorized");
    }

    /**
     * Reads and routes a POST when the 2026 profile is enabled. Returns false (body restored) when
     * the request belongs to the legacy wire.
     */
    private boolean handlePost2026(HttpExchange ex) throws IOException {
        if (!"application/json".equals(mediaType(header(ex, "Content-Type")))) return false; // legacy answers 415
        byte[] body;
        try {
            body = readLimited(ex.getRequestBody(), maxBodyBytes + 1);
        } catch (IOException e) {
            return false;
        }
        ex.setStreams(new java.io.ByteArrayInputStream(body), null);
        if (body.length > maxBodyBytes) return false;
        String text = new String(body, StandardCharsets.UTF_8);
        String trimmed = text.strip();
        JsonNode msg = null;
        if (!trimmed.startsWith("[") && McpHandler2026.validUtf8(body)) {
            try {
                msg = MAPPER.readTree(text);
            } catch (Exception e) {
                msg = null;
            }
        }
        if (msg == null || !msg.isObject() || !"2.0".equals(textOrNull(msg.get("jsonrpc")))
                || !stringOrAbsent(msg.get("method"))) {
            if (!only2026()) return false;
            if (trimmed.startsWith("[")) write26(ex, 400, null, null, new Rpc26Error(400, -32600, "batch requests are not supported", null));
            else write26(ex, 400, null, null, new Rpc26Error(400, -32700, "Parse error", null));
            return true;
        }
        String method = msg.hasNonNull("method") ? msg.get("method").asText() : "";
        JsonNode params = msg.get("params");
        Map<String, Object> pm = params != null && params.isObject() ? toMap(params) : null;
        Map<String, Object> meta = pm != null && pm.get("_meta") instanceof Map<?, ?> mm ? castMap(mm) : null;
        if (!route2026(ex, method, meta)) return false;
        serve2026(ex, msg, method, params, pm, meta);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** SPEC §7C.2 routing: whether this POST is served on the 2026-07-28 profile. */
    private boolean route2026(HttpExchange ex, String method, Map<String, Object> meta) {
        if (only2026()) return true;
        if ("initialize".equals(method)) return false;
        String hv = header(ex, "MCP-Protocol-Version");
        if (!hv.isEmpty() && legacy.contains(hv)) return false;
        return "server/discover".equals(method) || !header(ex, "Mcp-Method").isEmpty()
                || (meta != null && meta.containsKey(McpHandler2026.META_VERSION));
    }

    static final class Rpc26Error extends Exception {
        final int status;
        final int code;
        final Object data;

        Rpc26Error(int status, int code, String message, Object data) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
            this.data = data;
        }
    }

    private static Rpc26Error invalid26(String m) { return new Rpc26Error(200, -32602, m, null); }

    private static Rpc26Error internal26(Throwable e) { return new Rpc26Error(200, -32603, msgOf(e), null); }

    private void serve2026(HttpExchange ex, JsonNode msg, String method, JsonNode params,
                           Map<String, Object> p, Map<String, Object> meta) throws IOException {
        JsonNode id = msg.get("id");
        if (method.isEmpty() || id == null || id.isNull()) {
            empty(ex, 202); // a notification (or a stray response): acknowledged
            return;
        }
        Rpc26Error bad = McpHandler2026.validate(ex, method, p, meta);
        if (bad != null) {
            write26(ex, bad.status, id, null, bad);
            return;
        }
        RequestContext ctx = new RequestContext(null, ex, "", null);
        if (cfg.authenticate != null) {
            Object identity;
            try {
                identity = cfg.authenticate.authenticate(ex);
            } catch (Exception e) {
                writeAuthFailure(ex, e);
                return;
            }
            ctx = new RequestContext(identity, ex, "", null);
        }
        if (meta != null) ctx = ctx.withMeta(meta);
        Map<String, Object> res = null;
        Rpc26Error err = null;
        try {
            res = dispatch2026(ctx, method, params, p == null ? new LinkedHashMap<>() : p, meta);
        } catch (Rpc26Error e) {
            err = e;
        }
        write26(ex, err == null ? 200 : err.status, id, res, err);
    }

    private static void write26(HttpExchange ex, int status, JsonNode id, Map<String, Object> result, Rpc26Error e) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id == null ? NullNode.getInstance() : id);
        if (e != null) {
            Map<String, Object> er = kv("code", e.code, "message", e.getMessage());
            if (e.data != null) er.put("data", e.data);
            msg.put("error", er);
        } else {
            msg.put("result", result);
        }
        sendBytes(ex, status, "application/json", Json.stringify(msg) + "\n");
    }

    private ServerInfo info2026(RequestContext ctx) {
        ServerInfo info = new ServerInfo(cfg.name, cfg.title, cfg.version, cfg.instructions);
        if (cfg.serverInfo != null) info = cfg.serverInfo.serverInfo(ctx);
        if (info == null) info = new ServerInfo(null, null, null, null);
        return new ServerInfo(isEmpty(info.name()) ? DEFAULT_NAME : info.name(), info.title(),
                isEmpty(info.version()) ? DEFAULT_VERSION : info.version(), info.instructions());
    }

    /** Stamps resultType and _meta.serverInfo (merged after any existing _meta keys). */
    private Map<String, Object> complete26(RequestContext ctx, Map<String, Object> m, String resultType) {
        ServerInfo info = info2026(ctx);
        Map<String, Object> meta = new LinkedHashMap<>();
        if (m.get("_meta") instanceof Map<?, ?> old) meta.putAll(castMap(old));
        meta.put(McpHandler2026.META_SERVER_INFO, omitEmpty(kv("name", info.name(), "title", info.title(), "version", info.version())));
        m.put("_meta", meta);
        m.put("resultType", resultType);
        return m;
    }

    private String cacheScope26() {
        return cfg.authenticate != null ? "private" : "public";
    }

    private Map<String, Object> dispatch2026(RequestContext ctx, String method, JsonNode params,
                                             Map<String, Object> p, Map<String, Object> meta) throws Rpc26Error {
        switch (method) {
            case "server/discover": {
                ServerInfo info = info2026(ctx);
                Map<String, Object> caps = new LinkedHashMap<>();
                caps.put("tools", new LinkedHashMap<>());
                if (cfg.listResources != null || cfg.listSkills != null) caps.put("resources", new LinkedHashMap<>());
                if (cfg.listPrompts != null) caps.put("prompts", new LinkedHashMap<>());
                if (cfg.listSkills != null) caps.put("extensions", kv(SKILLS_EXTENSION, new LinkedHashMap<>()));
                Map<String, Object> res = kv("supportedVersions", List.of(PROTOCOL_2026), "capabilities", caps,
                        "ttlMs", 3600000, "cacheScope", "public");
                if (!isEmpty(info.instructions())) res.put("instructions", info.instructions());
                return complete26(ctx, res, "complete");
            }
            case "ping":
                return complete26(ctx, new LinkedHashMap<>(), "complete");
            case "tools/list": {
                List<Tool> ts;
                try {
                    ts = new ArrayList<>(tools(ctx));
                } catch (RpcError e) {
                    throw new Rpc26Error(200, e.code, e.getMessage(), null);
                }
                ts.removeIf(java.util.Objects::isNull);
                ts.sort((a, b) -> McpHandler2026.compareCodePoints(a.name(), b.name()));
                List<Map<String, Object>> out = new ArrayList<>();
                for (Tool t : ts) out.add(wireTool(t));
                return complete26(ctx, kv("tools", out, "ttlMs", 60000, "cacheScope", cacheScope26()), "complete");
            }
            case "tools/call":
                return callTool2026(ctx, p, meta);
            case "skills/list": {
                if (cfg.listSkills == null) break;
                List<Map<String, Object>> list = new ArrayList<>();
                for (Skill s : skills26(ctx)) {
                    list.add(kv("uri", "skill://" + s.name() + "/SKILL.md", "frontmatter", McpHandler2026.frontmatter(s)));
                }
                return complete26(ctx, kv("skills", list, "ttlMs", 60000, "cacheScope", cacheScope26()), "complete");
            }
            case "skills/get": {
                if (cfg.listSkills == null) break;
                String uri = p.get("uri") instanceof String s ? s : "";
                try {
                    McpHandler2026.parseSkillUri(uri);
                } catch (IllegalArgumentException e) {
                    throw invalid26("Invalid skill URI: " + e.getMessage());
                }
                for (Skill s : skills26(ctx)) {
                    if (("skill://" + s.name() + "/SKILL.md").equals(uri)) {
                        return complete26(ctx, kv("skill", McpHandler2026.skillEntry(s)), "complete");
                    }
                }
                throw invalid26("Unknown skill: " + uri);
            }
            case "resources/read": {
                String uri = p.get("uri") instanceof String s ? s : "";
                if (uri.startsWith("skill://") && cfg.listSkills != null) {
                    String[] nr;
                    try {
                        nr = McpHandler2026.parseSkillUri(uri);
                    } catch (IllegalArgumentException e) {
                        throw invalid26("Invalid resource URI: " + e.getMessage());
                    }
                    for (Skill s : skills26(ctx)) {
                        if (!s.name().equals(nr[0]) || s.files() == null) continue;
                        for (SkillFile f : s.files()) {
                            if (f.path().equals(nr[1])) {
                                String mt = nr[1].endsWith(".md") ? "text/markdown" : "text/plain";
                                List<Map<String, Object>> contents = List.of(kv("uri", uri, "mimeType", mt,
                                        "text", f.text() == null ? "" : f.text()));
                                return complete26(ctx, kv("contents", contents, "ttlMs", 60000,
                                        "cacheScope", cacheScope26()), "complete");
                            }
                        }
                    }
                    throw invalid26("Resource not found: " + uri);
                }
                if (cfg.listResources == null || cfg.readResource == null) break;
                return legacy26(ctx, method, params);
            }
            case "resources/list":
                if (cfg.listResources == null) break;
                return legacy26(ctx, method, params);
            case "prompts/list":
            case "prompts/get":
                if (cfg.listPrompts == null) break;
                return legacy26(ctx, method, params);
            default:
                break;
        }
        throw new Rpc26Error(404, -32601, "Method not found: " + method, null);
    }

    /** The §7C.1 payload of {@code method}, in the 2026 envelope. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> legacy26(RequestContext ctx, String method, JsonNode params) throws Rpc26Error {
        Object res;
        try {
            res = dispatch(ctx, null, method, params);
        } catch (RpcError e) {
            throw new Rpc26Error(200, e.code, e.getMessage(), null);
        } catch (IOException e) {
            throw internal26(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (res instanceof Map<?, ?> rm) m.putAll((Map<String, Object>) rm);
        return complete26(ctx, m, "complete");
    }

    private List<Skill> skills26(RequestContext ctx) throws Rpc26Error {
        List<Skill> skills;
        try {
            skills = cfg.listSkills.listSkills(ctx);
        } catch (Exception e) {
            throw internal26(e);
        }
        List<Skill> out = new ArrayList<>();
        if (skills != null) for (Skill s : skills) if (s != null && s.name() != null) out.add(s);
        out.sort((a, b) -> McpHandler2026.compareCodePoints(a.name(), b.name()));
        return out;
    }

    private Map<String, Object> callTool2026(RequestContext ctx, Map<String, Object> p, Map<String, Object> meta) throws Rpc26Error {
        String name = p.get("name") instanceof String s ? s : "";
        List<Tool> ts;
        try {
            ts = tools(ctx);
        } catch (RpcError e) {
            throw new Rpc26Error(200, e.code, e.getMessage(), null);
        }
        Tool tool = null;
        for (Tool t : ts) {
            if (t != null && name.equals(t.name())) { tool = t; break; }
        }
        if (tool == null) throw invalid26("Unknown tool: " + name);
        final Tool chosen = tool;
        long started = System.nanoTime();
        DecisionKind[] decision = {DecisionKind.ALLOW};

        java.util.function.BiFunction<Map<String, Object>, Object[], Map<String, Object>> finish = (m, errs) -> {
            boolean isErr = (Boolean) errs[0];
            Throwable execErr = (Throwable) errs[1];
            int bytes = Json.stringify(m).getBytes(StandardCharsets.UTF_8).length;
            if (cfg.onCall != null) {
                try {
                    cfg.onCall.onCall(new CallEvent(chosen.name(), chosen.source(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), isErr, execErr,
                            bytes, decision[0], ctx.identity(), ""));
                } catch (Throwable ignored) {
                    // host callback failures are isolated
                }
            }
            return m;
        };
        java.util.function.BiFunction<ToolResult, Throwable, Map<String, Object>> result = (res, execErr) ->
                finish.apply(complete26(ctx, new LinkedHashMap<>(wireResult(res)), "complete"),
                        new Object[]{res.isError(), execErr});

        Object rawArgs = p.get("arguments");
        Map<String, Object> args = new LinkedHashMap<>();
        if (rawArgs != null) {
            if (!(rawArgs instanceof Map<?, ?> am)) {
                return result.apply(ToolResult.error("invalid arguments: must be an object"), null);
            }
            args = castMap(am);
        }
        String rawText = Json.stringify(args);

        McpHandler2026.State state = McpHandler2026.decodeState(p.get("requestState"));
        if (state != null && !name.equals(state.tool())) state = null;
        Map<String, Object> responses = p.get("inputResponses") instanceof Map<?, ?> rm ? castMap(rm) : null;
        boolean confirmed = state != null && state.confirmed();

        if (cfg.beforeCall != null) {
            Decision d;
            try {
                d = cfg.beforeCall.beforeCall(ctx, chosen, rawText);
            } catch (Exception e) {
                d = Decision.deny(msgOf(e));
            }
            if (d == null) d = Decision.allow();
            DecisionKind kind = d.kind() == null ? DecisionKind.ALLOW : d.kind();
            decision[0] = kind;
            if (kind == DecisionKind.DENY) {
                if (d.result() != null) return result.apply(d.result(), null);
                return result.apply(ToolResult.error(isEmpty(d.message()) ? "denied" : d.message()), null);
            }
            if (kind == DecisionKind.CONFIRM && !confirmed) {
                Answer ans = responses == null ? null : McpHandler2026.inputResponse("confirm", responses.get("confirm"));
                if (state != null && ans != null) {
                    if (!ans.ok()) return result.apply(ToolResult.error("declined"), null);
                    if (ans.data() != null && Boolean.FALSE.equals(ans.data().get("confirm"))) {
                        return result.apply(ToolResult.error("declined"), null);
                    }
                    confirmed = true;
                    responses = null; // the confirm round is consumed
                } else if (!McpHandler2026.canElicit(meta)) {
                    if (d.result() != null) return result.apply(d.result(), null);
                    return result.apply(ToolResult.error("confirmation required: " + (d.message() == null ? "" : d.message())), null);
                } else {
                    String msg = isEmpty(d.message()) ? "Confirm this action?" : d.message();
                    Map<String, Object> schema = kv("type", "object", "properties",
                            kv("confirm", kv("type", "boolean", "title", "Confirm", "description", msg)));
                    Map<String, Object> ir = kv("method", "elicitation/create", "params",
                            kv("mode", "form", "message", msg, "requestedSchema", schema));
                    return finish.apply(complete26(ctx, kv("inputRequests", kv("confirm", ir),
                            "requestState", McpHandler2026.encodeState(name, false)), "input_required"), new Object[]{false, null});
                }
            }
        }

        Answer answer = null;
        if (state != null && responses != null && responses.size() == 1) {
            Map.Entry<String, Object> e = responses.entrySet().iterator().next();
            answer = McpHandler2026.inputResponse(e.getKey(), e.getValue());
        }
        ToolResult res;
        Throwable execErr = null;
        try {
            res = chosen.execute(args, new ToolContext(null, null, answer, null, ctx));
            if (res == null) res = ToolResult.ok("");
        } catch (Throwable e) {
            execErr = e;
            res = ToolResult.error(msgOf(e));
        }
        Request req = execErr == null ? ToolResult.pendingOf(res) : null;
        if (req != null) {
            return finish.apply(complete26(ctx, kv("inputRequests", kv(req.id(), McpHandler2026.inputRequest(req)),
                    "requestState", McpHandler2026.encodeState(name, confirmed)), "input_required"), new Object[]{false, null});
        }
        return result.apply(res, execErr);
    }

    // ── wire helpers ─────────────────────────────────────────────────────

    /** Answers one POST: plain JSON, unless a server→client request was sent first (then SSE). */
    private static final class RpcWriter {
        private final HttpExchange ex;
        private boolean sse;

        RpcWriter(HttpExchange ex) { this.ex = ex; }

        synchronized void event(Object v) throws IOException {
            if (!sse) {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, 0);
                sse = true;
            }
            OutputStream os = ex.getResponseBody();
            os.write(("event: message\ndata: " + Json.stringify(v) + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        void respond(JsonNode id, Object result, RpcError err) throws IOException {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jsonrpc", "2.0");
            msg.put("id", id == null ? NullNode.getInstance() : id);
            if (err != null) msg.put("error", kv("code", err.code, "message", err.getMessage()));
            else msg.put("result", result);
            if (sse) {
                event(msg);
                return;
            }
            sendBytes(ex, 200, "application/json", Json.stringify(msg) + "\n");
        }
    }

    private static void writeRpcError(HttpExchange ex, JsonNode id, int code, String message) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id == null ? NullNode.getInstance() : id);
        msg.put("error", kv("code", code, "message", message));
        sendBytes(ex, 400, "application/json", Json.stringify(msg) + "\n");
    }

    private static void httpError(HttpExchange ex, int status, String text) throws IOException {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        sendBytes(ex, status, "text/plain; charset=utf-8", text + "\n");
    }

    private static void sendBytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void empty(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
    }

    /**
     * The tools/list entry for {@code t}: name verbatim, description, inputSchema, outputSchema when
     * set, and annotations (verbatim; else derived from hints; else omitted).
     */
    static Map<String, Object> wireTool(Tool t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("description", t.description() == null ? "" : t.description());
        m.put("inputSchema", t.inputSchema() == null ? Map.of("type", "object") : t.inputSchema());
        if (t.outputSchema() != null) m.put("outputSchema", t.outputSchema());
        Map<String, Object> ann = new LinkedHashMap<>();
        ToolAnnotations a = t.annotations();
        if (a != null) {
            if (!isEmpty(a.title())) ann.put("title", a.title());
            if (a.readOnlyHint() != null) ann.put("readOnlyHint", a.readOnlyHint());
            if (a.destructiveHint() != null) ann.put("destructiveHint", a.destructiveHint());
            if (a.idempotentHint() != null) ann.put("idempotentHint", a.idempotentHint());
            if (a.openWorldHint() != null) ann.put("openWorldHint", a.openWorldHint());
        } else if (t.hints() != null) {
            ToolHints h = t.hints();
            ann.put("readOnlyHint", h.readOnly());
            ann.put("destructiveHint", h.destructive());
            ann.put("openWorldHint", h.openWorld());
        }
        if (!ann.isEmpty()) m.put("annotations", ann);
        return m;
    }

    /**
     * The MCP CallToolResult for {@code res} (SPEC §7C): output → text part (the JSON of
     * {@code structured} when output is empty), then §1B parts; isError only when true;
     * structuredContent / _meta only when set.
     */
    static Map<String, Object> wireResult(ToolResult res) {
        String text = res.output();
        if (res.structured() != null && text.isEmpty()) text = Json.stringify(res.structured());
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(kv("type", "text", "text", text));
        if (res.parts() != null) for (ContentPart p : res.parts()) {
            Map<String, Object> c = partToMcp(p);
            if (c != null) content.add(c);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", content);
        if (res.structured() != null) m.put("structuredContent", res.structured());
        if (res.isError()) m.put("isError", true);
        if (res.meta() != null) m.put("_meta", res.meta());
        return m;
    }

    private static Map<String, Object> partToMcp(ContentPart p) {
        boolean hasData = !isEmpty(p.data());
        switch (p.type()) {
            case ContentPart.TEXT:
                return kv("type", "text", "text", p.text() == null ? "" : p.text());
            case ContentPart.IMAGE:
            case ContentPart.AUDIO:
                if (hasData) return kv("type", p.type(), "data", p.data(), "mimeType", p.mimeType());
                return resourceLink(p);
            case ContentPart.FILE:
                if (hasData) {
                    return kv("type", "resource", "resource",
                            omitEmpty(kv("uri", p.name(), "mimeType", p.mimeType(), "blob", p.data())));
                }
                return resourceLink(p);
            default:
                return null;
        }
    }

    private static Map<String, Object> resourceLink(ContentPart p) {
        Map<String, Object> m = kv("type", "resource_link", "uri", p.url() == null ? "" : p.url(),
                "name", p.name() == null ? "" : p.name());
        if (!isEmpty(p.mimeType())) m.put("mimeType", p.mimeType());
        return m;
    }

    private static Map<String, Object> kv(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    /** Drops null and "" string values (Go's omitEmpty over string fields). */
    private static Map<String, Object> omitEmpty(Map<String, Object> m) {
        m.entrySet().removeIf(e -> e.getValue() == null || (e.getValue() instanceof String s && s.isEmpty()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode n) {
        return MAPPER.convertValue(n, LinkedHashMap.class);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String textOrNull(JsonNode n) {
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private static boolean stringOrAbsent(JsonNode n) {
        return n == null || n.isNull() || n.isTextual();
    }

    private static String header(HttpExchange ex, String name) {
        String v = ex.getRequestHeaders().getFirst(name);
        return v == null ? "" : v;
    }

    private static String mediaType(String contentType) {
        int i = contentType.indexOf(';');
        String t = (i >= 0 ? contentType.substring(0, i) : contentType).strip();
        return t.toLowerCase(Locale.ROOT);
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        return in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE));
    }
}
