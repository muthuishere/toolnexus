package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.acp.AcpClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The CLI client's ACP transport (SPEC §8C, ADR 0037 "ACP primary").
 *
 * <p>On the first model call the caller's toolkit is served as a streamable-HTTP MCP server on
 * {@code 127.0.0.1:<ephemeral>/mcp}, guarded by a per-session bearer token, and handed to the agent
 * in {@code session/new} mcpServers (http when the agent advertises {@code mcpCapabilities.http},
 * else a stdio bridge; a project MCP file in a temp cwd for agents that ignore session servers).
 * Every permission request is decided once per {@code toolCallId}: tools served by our server are
 * allowed, everything else (the agent's own shell, edit, fetch …) is rejected.
 */
public final class CliAcpSession {

    /** A failure to start the agent or complete {@code initialize} — the only failures AUTO falls back on. */
    static final class LaunchException extends Exception {
        LaunchException(String message, Throwable cause) { super(message, cause); }
    }

    /** One answered {@code session/request_permission}. */
    public record PermissionDecision(String toolCallId, String title, boolean allowed) {}

    /**
     * A read-only view of the live session, for tests and diagnostics. Header and env values in
     * {@code mcpServersSent} are redacted; the bearer token is never exposed.
     */
    public record Info(String url, List<Object> mcpServersSent, List<PermissionDecision> permissions,
                       List<String> servedCalls, String sessionCwd) {}

    private AcpClient client;
    private HttpServer http;
    private String url;
    private final String token;
    private Path tempDir;
    private final Set<String> served = new LinkedHashSet<>();
    private final int timeout;
    private final String hint;

    private final Object mu = new Object();
    private final Map<String, String> calls = new HashMap<>();      // toolCallId -> first non-empty title
    private final Map<String, Boolean> decisions = new HashMap<>(); // toolCallId -> allowed
    private final List<PermissionDecision> log = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();

    private CliAcpSession(String token, int timeout, String hint) {
        this.token = token;
        this.timeout = timeout;
        this.hint = hint;
    }

    static CliAcpSession open(CliClient c) throws LaunchException {
        List<String> argv = c.acpArgv;
        String bin = CliClient.lookPath(argv.get(0));
        if (bin == null) throw new LaunchException("cli: command not found: " + argv.get(0), null);
        byte[] tok = new byte[32];
        new SecureRandom().nextBytes(tok);
        String hint = c.style.acp != null ? c.style.acp.systemHint : null;
        CliAcpSession s = new CliAcpSession(HexFormat.of().formatHex(tok), c.timeout, hint);
        LlmClient.Hooks hooks = c.opts.inProcess != null ? c.opts.inProcess.hooks : null;
        List<Tool> tools = servedTools(c.opts.toolkit, hooks, name -> {
            synchronized (s.mu) { s.ran.add(name); }
        });
        for (Tool t : tools) s.served.add(t.name());
        try {
            s.serve(tools);
        } catch (IOException e) {
            throw new CliClient.CliException("cli: acp: serve toolkit: " + e.getMessage(), e);
        }

        String cwd = c.opts.cwd;
        if (cwd == null || cwd.isEmpty()) cwd = System.getProperty("user.dir");
        cwd = Path.of(cwd).toAbsolutePath().toString();
        List<AcpClient.McpServer> servers = new ArrayList<>();
        boolean projectFile = c.style.acp != null && c.style.acp.projectMcpFile != null
                && !c.style.acp.projectMcpFile.isEmpty()
                && (c.opts.acpCommand == null || c.opts.acpCommand.isEmpty());
        try {
            if (projectFile) {
                s.tempDir = Files.createTempDirectory("toolnexus-acp-");
                cwd = s.tempDir.toString();
                Path f = s.tempDir.resolve(c.style.acp.projectMcpFile);
                Files.createDirectories(f.getParent());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "http");
                entry.put("url", s.url);
                entry.put("headers", Map.of("Authorization", "Bearer " + s.token));
                Map<String, Object> doc = Map.of("mcpServers", Map.of(CliClient.ACP_SERVER_NAME, entry));
                Files.writeString(f, Json.stringify(doc));
                try {
                    Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // non-POSIX file system
                }
            } else {
                AcpClient.McpServer entry = new AcpClient.McpServer().name(CliClient.ACP_SERVER_NAME).url(s.url)
                        .headers(Map.of("Authorization", "Bearer " + s.token));
                List<String> bridge = c.opts.bridgeCommand;
                if (bridge == null || bridge.isEmpty()) {
                    String p = CliClient.lookPath("toolnexus");
                    if (p != null) bridge = List.of(p, "mcp-bridge");
                }
                if (bridge != null && !bridge.isEmpty()) {
                    List<String> args = new ArrayList<>(bridge.subList(1, bridge.size()));
                    args.add(s.url);
                    entry.command(bridge.get(0)).args(args).env(Map.of(CliClient.MCP_TOKEN_ENV, s.token));
                }
                servers.add(entry);
            }
        } catch (IOException e) {
            s.close();
            throw new CliClient.CliException("cli: acp: " + e.getMessage(), e);
        }

        List<String> cmd = new ArrayList<>(argv);
        cmd.set(0, bin);
        AcpClient.Options ao = new AcpClient.Options()
                .command(cmd)
                .env(CliClient.expandEnv(c.env))
                .cwd(cwd)
                .timeoutMs(c.timeout)
                .mcpServers(servers)
                .model(c.model.isEmpty() ? null : c.model)
                .onPermission(s::permission)
                .onUpdate(u -> {
                    s.update(u);
                    if (c.opts.onAcpUpdate != null) c.opts.onAcpUpdate.accept(u);
                });
        try {
            s.client = AcpClient.start(ao);
        } catch (AcpClient.StartException e) {
            s.close();
            String msg = e.getMessage();
            String err = e.stderr == null ? "" : e.stderr.strip();
            if (!err.isEmpty()) msg += ": " + err;
            msg = msg.replaceFirst("^toolnexus: acp:", "cli: acp:");
            if (e.stage.equals("start") || e.stage.equals("initialize")) throw new LaunchException(msg, e);
            throw new CliClient.CliException(msg, e);
        } catch (IOException e) {
            s.close();
            throw new LaunchException("cli: acp: start: " + e.getMessage(), e);
        }
        return s;
    }

    private void serve(List<Tool> tools) throws IOException {
        McpServe.JdkStatelessHttpTransport transport = new McpServe.JdkStatelessHttpTransport();
        McpServe.buildStatelessServer(transport, tools,
                new McpServe.MCPServeConfig().name(CliClient.ACP_SERVER_NAME), null);
        byte[] want = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", ex -> {
            try {
                if (!"/mcp".equals(ex.getRequestURI().getPath())) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                String got = ex.getRequestHeaders().getFirst("Authorization");
                if (got == null || !MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), want)) {
                    byte[] body = "unauthorized\n".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(401, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }
                transport.handle(ex);
            } finally {
                ex.close();
            }
        });
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cli-acp-mcp");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        url = "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
    }

    /** Ends the agent, stops the server and removes the temp session dir. */
    void close() {
        if (client != null) client.close();
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (tempDir != null) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    Info info() {
        synchronized (mu) {
            return new Info(url, redact(client == null ? List.of() : client.mcpServersSent),
                    List.copyOf(log), List.copyOf(ran), tempDir == null ? "" : tempDir.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> redact(List<Object> in) {
        List<Object> out = new ArrayList<>();
        for (Object e : in) {
            Object copy = Json.toMap(Json.stringify(e));
            if (copy instanceof Map<?, ?> m) {
                for (String k : List.of("headers", "env")) {
                    if (m.get(k) instanceof List<?> arr) {
                        for (Object nv : arr) if (nv instanceof Map<?, ?> x) ((Map<String, Object>) x).put("value", "[redacted]");
                    }
                }
            }
            out.add(copy);
        }
        return out;
    }

    InProcess.Response generate(InProcess.Request req) {
        InProcess.Request r = new InProcess.Request(withSystemHint(req.messages, hint), req.tools, req.model, req.body);
        try {
            return client.generate(r);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("timed out")) throw new CliClient.CliException("cli: timed out after " + timeout + "ms", e);
            throw new CliClient.CliException(m.replaceFirst("^toolnexus: acp:", "cli: acp:"), e);
        }
    }

    /**
     * Appends a style's ACP system hint to the first system message (a copy; the caller's messages
     * are not mutated), or prepends a system message holding only the hint when there is none.
     */
    @SuppressWarnings("unchecked")
    static List<Object> withSystemHint(List<Object> msgs, String hint) {
        if (hint == null || hint.isEmpty()) return msgs;
        List<Object> out = new ArrayList<>();
        if (msgs != null && !msgs.isEmpty() && msgs.get(0) instanceof Map<?, ?> m
                && "system".equals(m.get("role")) && m.get("content") instanceof String c) {
            Map<String, Object> cp = new LinkedHashMap<>((Map<String, Object>) m);
            cp.put("content", c.isEmpty() ? hint : c + "\n\n" + hint);
            out.add(cp);
            out.addAll(msgs.subList(1, msgs.size()));
            return out;
        }
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", hint);
        out.add(sys);
        if (msgs != null) out.addAll(msgs);
        return out;
    }

    /** Remembers the first non-empty title of each toolCallId (devin titles only the tool_call update). */
    private void update(Map<String, Object> params) {
        if (!(params.get("update") instanceof Map<?, ?> u)) return;
        Object kind = u.get("sessionUpdate");
        if (!"tool_call".equals(kind) && !"tool_call_update".equals(kind)) return;
        if (!(u.get("toolCallId") instanceof String id) || id.isEmpty()) return;
        if (!(u.get("title") instanceof String title) || title.isEmpty()) return;
        synchronized (mu) {
            calls.putIfAbsent(id, title);
        }
    }

    /**
     * Decides one {@code session/request_permission} by its toolCallId, once (from its own title,
     * else the title recorded for that id). Allowed ⇒ an allow option; otherwise null so the ACP
     * client answers with a reject option.
     */
    private String permission(Map<String, Object> params) {
        String id = "";
        String title = "";
        if (params.get("toolCall") instanceof Map<?, ?> tc) {
            if (tc.get("toolCallId") instanceof String s) id = s;
            if (tc.get("title") instanceof String s) title = s;
        }
        boolean allowed;
        synchronized (mu) {
            if (title.isEmpty()) title = calls.getOrDefault(id, "");
            Boolean d = decisions.get(id);
            if (d != null) {
                allowed = d;
            } else {
                allowed = !id.isEmpty() && isServedCall(title, served);
                if (!id.isEmpty()) decisions.put(id, allowed);
            }
            log.add(new PermissionDecision(id, title, allowed));
        }
        if (!allowed) return null;
        List<?> options = params.get("options") instanceof List<?> l ? l : List.of();
        for (Object o : options) {
            if (o instanceof Map<?, ?> m && "allow_once".equals(m.get("kind"))) return String.valueOf(m.get("optionId"));
        }
        for (Object o : options) {
            if (o instanceof Map<?, ?> m && m.get("kind") instanceof String k && k.startsWith("allow")) {
                return String.valueOf(m.get("optionId"));
            }
        }
        return null;
    }

    /**
     * Matches a permission title against the exact spellings agents use for a tool of OUR server:
     * {@code mcp__toolnexus__<tool>} (claude), {@code toolnexus/<tool>},
     * {@code "<tool> (toolnexus MCP Server)"} (gemini), {@code "Calling <tool> from toolnexus"}
     * (devin), {@code "<tool>(…)"} (gemini skill), or the bare tool name (copilot). No substring matching.
     */
    public static boolean isServedCall(String title, Set<String> served) {
        if (title == null || title.isEmpty()) return false;
        String sv = CliClient.ACP_SERVER_NAME;
        for (String n : served) {
            if (title.equals(n) || title.equals("mcp__" + sv + "__" + n) || title.equals(sv + "/" + n)
                    || title.equals(n + " (" + sv + " MCP Server)") || title.equals("Calling " + n + " from " + sv)) {
                return true;
            }
            if (title.startsWith(n + "(") && title.endsWith(")")) return true;
        }
        return false;
    }

    /**
     * The toolkit as served to the agent: {@code skill} renamed to {@code load_skill} with the
     * skills list in its description, every execute run through {@code beforeTool}/{@code afterTool}.
     */
    static List<Tool> servedTools(Toolkit tk, LlmClient.Hooks hooks, java.util.function.Consumer<String> ranSink) {
        if (tk == null) return List.of();
        List<Tool> out = new ArrayList<>();
        for (Tool t : tk.tools()) {
            String name = t.name();
            boolean isSkill = "skill".equals(t.source()) && "skill".equals(name);
            String servedName = isSkill ? CliClient.SERVED_SKILL_TOOL_NAME : name;
            String desc = t.description();
            if (isSkill) {
                String sp = tk.skillsPrompt();
                if (sp != null && !sp.isEmpty()) desc = desc + "\n\n" + sp;
            }
            String description = desc;
            out.add(new Tool() {
                @Override public String name() { return servedName; }
                @Override public String description() { return description; }
                @Override public Map<String, Object> inputSchema() { return t.inputSchema(); }
                @Override public String source() { return t.source(); }
                @Override public ToolHints hints() { return t.hints(); }

                @Override
                public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                    Map<String, Object> a = args;
                    try {
                        if (hooks != null && hooks.beforeTool != null) {
                            LlmClient.ToolOverride ov = hooks.beforeTool.apply(
                                    new LlmClient.BeforeToolEvent(name, a, null, 0, t.hints()));
                            if (ov != null && ov.result() != null) return ov.result();
                            if (ov != null && ov.args() != null) a = ov.args();
                        }
                    } catch (RuntimeException e) {
                        return ToolResult.error(String.valueOf(e.getMessage()));
                    }
                    if (ranSink != null) ranSink.accept(servedName);
                    ToolResult res;
                    try {
                        res = t.execute(a, ctx);
                    } catch (RuntimeException e) {
                        res = ToolResult.error(String.valueOf(e.getMessage()));
                    }
                    try {
                        if (hooks != null && hooks.afterTool != null) {
                            LlmClient.ToolOverride ov = hooks.afterTool.apply(
                                    new LlmClient.AfterToolEvent(name, a, res, null, 0));
                            if (ov != null && ov.result() != null) res = ov.result();
                        }
                    } catch (RuntimeException e) {
                        return ToolResult.error(String.valueOf(e.getMessage()));
                    }
                    return res;
                }
            });
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // The stdio ⇄ HTTP bridge.
    // ---------------------------------------------------------------------------------------

    /**
     * The stdio side of a stdio McpServer entry: each JSON-RPC line read from {@code in} is POSTed
     * to the toolkit's streamable-HTTP endpoint (with the bearer token when set), and each reply —
     * plain JSON or SSE {@code data:} frames — is written to {@code out} as one line.
     * {@code toolnexus mcp-bridge <url>} runs it with the token from {@code TOOLNEXUS_MCP_TOKEN}.
     */
    public static void runMcpBridge(String url, String token, InputStream in, OutputStream out) throws IOException {
        HttpClient hc = HttpClient.newHttpClient();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String session = null;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(line));
            if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
            if (session != null) b.header("Mcp-Session-Id", session);
            HttpResponse<String> resp;
            try {
                resp = hc.send(b.build(), HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("mcp-bridge: interrupted", e);
            }
            String sid = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (sid != null && !sid.isEmpty()) session = sid;
            if (resp.statusCode() == 401) {
                throw new IOException("mcp-bridge: unauthorized (missing or wrong " + CliClient.MCP_TOKEN_ENV + ")");
            }
            String ct = resp.headers().firstValue("Content-Type").orElse("");
            String body = resp.body() == null ? "" : resp.body();
            if (ct.startsWith("text/event-stream")) {
                for (String l : body.split("\n", -1)) {
                    String x = l.endsWith("\r") ? l.substring(0, l.length() - 1) : l;
                    if (x.startsWith("data:")) ps.println(x.substring(5).strip());
                }
                continue;
            }
            String t = body.strip();
            if (!t.isEmpty() && t.charAt(0) == '{') ps.println(t);
        }
        ps.flush();
    }
}
