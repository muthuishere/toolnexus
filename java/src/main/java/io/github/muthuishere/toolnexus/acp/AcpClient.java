package io.github.muthuishere.toolnexus.acp;

import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A client for an ACP (Agent Client Protocol) agent, spoken over a child process's
 * stdin/stdout as JSON-RPC 2.0, one object per line — the semantic counterpart, for a whole
 * agent, of what {@code McpSource} is for one tool server (issue #96, ADR 0031,
 * {@code openspec/changes/add-acp-model-source}).
 *
 * <p>{@link #start} spawns the agent, negotiates {@code initialize}, and opens ONE
 * {@code session/new} — a warm session that every subsequent {@link #generate} reuses. Because
 * an ACP session is stateful and toolnexus assembles a complete request every turn, each
 * {@code generate} appends an explicit {@code SUPERSEDES-ALL-PRIOR:} marker naming the current
 * turn, so a stateful agent answers the fresh prompt rather than a near-duplicate earlier one
 * (ADR 0031's spike gate).
 *
 * <p>Implements {@code Function<InProcess.Request, InProcess.Response>} so an instance plugs
 * directly into {@link InProcess.Options#generate} or {@code RuntimeOptions.inProcess} with no
 * adapter — see {@code java/src/test/java/io/github/muthuishere/toolnexus/acp/AcpClientTest.java}.
 *
 * <p>NOT the MCP SDK's {@code StdioClientTransport} — ACP is not MCP. This is a thin,
 * purpose-built subprocess + line-JSON-RPC layer using plain {@link ProcessBuilder}/{@link
 * Process}, mirroring {@code spikes/acp/client/client.go}.
 */
public final class AcpClient implements Closeable, Function<InProcess.Request, InProcess.Response> {

    /** Default bound on a single JSON-RPC round trip (init, session/new, or one prompt turn). */
    public static final long DEFAULT_TIMEOUT_MS = 10_000;

    /** The literal marker a stateful agent is told to treat as authoritative over prior turns. */
    static final String SUPERSEDES_MARKER = "SUPERSEDES-ALL-PRIOR:";

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final Thread readerThread;

    private final Object writeLock = new Object();
    private final ReentrantLock turnLock = new ReentrantLock();

    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Map<String, String> pendingMethod = new ConcurrentHashMap<>();
    private volatile String sessionId;
    private volatile java.util.function.Consumer<Map<String, Object>> updateSink;
    private volatile Options options = new Options();
    private final StringBuilder stderrTail = new StringBuilder();

    /** The exact {@code mcpServers} list sent in {@code session/new} (SPEC §8C). */
    public volatile List<Object> mcpServersSent = List.of();
    /** The agent's raw {@code initialize} result. */
    public volatile Map<String, Object> initializeResult = Map.of();

    /**
     * One {@code session/new} mcpServers entry. Set {@code url} for the http shape
     * ({@code {type:"http",name,url,headers[]}}), {@code command} for the stdio shape
     * ({@code {name,command,args[],env[]}} — no type field), or both to let the agent's advertised
     * {@code mcpCapabilities.http} pick. Headers and env go on the wire as {@code [{name,value}]}
     * arrays sorted by name; their values are never logged.
     */
    public static final class McpServer {
        public String name;
        public String url;
        public Map<String, String> headers = new LinkedHashMap<>();
        public String command;
        public List<String> args = new ArrayList<>();
        public Map<String, String> env = new LinkedHashMap<>();

        public McpServer name(String v) { this.name = v; return this; }
        public McpServer url(String v) { this.url = v; return this; }
        public McpServer headers(Map<String, String> v) { this.headers = v; return this; }
        public McpServer command(String v) { this.command = v; return this; }
        public McpServer args(List<String> v) { this.args = v; return this; }
        public McpServer env(Map<String, String> v) { this.env = v; return this; }

        Map<String, Object> wire(boolean httpOk) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (url != null && !url.isEmpty() && (httpOk || command == null || command.isEmpty())) {
                m.put("type", "http");
                m.put("name", name);
                m.put("url", url);
                m.put("headers", nameValues(headers));
                return m;
            }
            m.put("name", name);
            m.put("command", command);
            m.put("args", args == null ? List.of() : new ArrayList<>(args));
            m.put("env", nameValues(env));
            return m;
        }

        private static List<Object> nameValues(Map<String, String> m) {
            List<Object> out = new ArrayList<>();
            if (m == null) return out;
            for (String k : new java.util.TreeSet<>(m.keySet())) {
                Map<String, Object> nv = new LinkedHashMap<>();
                nv.put("name", k);
                nv.put("value", m.get(k));
                out.add(nv);
            }
            return out;
        }
    }

    /** Launch options for {@link #start(Options)} (SPEC §8C "ACP model source additions"). */
    public static final class Options {
        /** The full argv that starts the agent. Required. */
        public List<String> command;
        /** Added to the child's environment. */
        public Map<String, String> env;
        /** Session cwd and the child's working directory (default: this process's). */
        public String cwd;
        /** Round-trip bound for every request, including one prompt turn. */
        public long timeoutMs = DEFAULT_TIMEOUT_MS;
        /** Sent in {@code session/new} mcpServers (empty ⇒ {@code []}). */
        public List<McpServer> mcpServers = new ArrayList<>();
        /** Sent as {@code initialize} clientCapabilities._meta when set. Opaque. */
        public Map<String, Object> clientCapsMeta;
        /** Passed through unchanged: session/set_config_option {configId:"model"}, then session/set_model. */
        public String model;
        /**
         * Decides a {@code session/request_permission}: given its params, return the optionId to
         * select, or {@code null} to answer with the first reject-kind option (else cancelled).
         * Unset ⇒ the first allow-kind option, as {@link #start(List)} always did.
         */
        public Function<Map<String, Object>, String> onPermission;
        /** Observes every raw {@code session/update} params (on the read thread: must not block). */
        public java.util.function.Consumer<Map<String, Object>> onUpdate;

        public Options command(List<String> v) { this.command = v; return this; }
        public Options env(Map<String, String> v) { this.env = v; return this; }
        public Options cwd(String v) { this.cwd = v; return this; }
        public Options timeoutMs(long v) { this.timeoutMs = v; return this; }
        public Options mcpServers(List<McpServer> v) { this.mcpServers = v; return this; }
        public Options clientCapsMeta(Map<String, Object> v) { this.clientCapsMeta = v; return this; }
        public Options model(String v) { this.model = v; return this; }
        public Options onPermission(Function<Map<String, Object>, String> v) { this.onPermission = v; return this; }
        public Options onUpdate(java.util.function.Consumer<Map<String, Object>> v) { this.onUpdate = v; return this; }
    }

    /**
     * A failure of {@link #start(Options)}, tagged with the stage that failed: {@code start}
     * (spawn), {@code initialize}, {@code session/new} or {@code set model}.
     */
    public static final class StartException extends IOException {
        public final String stage;
        /** The agent's captured stderr tail at the time of failure ("" when none). Never logged by toolnexus. */
        public String stderr = "";

        StartException(String stage, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
        }
    }

    /**
     * When true (the default), {@code session/request_permission} is answered immediately with
     * the first option whose {@code kind} starts with {@code allow}. An unanswered permission
     * request hangs the turn forever — see ADR 0031 gate item #2. Set false only to reproduce
     * that hang deliberately (tests).
     */
    public volatile boolean autoAnswerPermission = true;

    /** Bounds how long {@link #generate} waits for a {@code session/prompt} reply (and how long
     * {@code initialize}/{@code session/new} wait during {@link #start}). Configurable so a
     * hermetic test against a broken fake server does not hang the build forever. */
    public volatile long timeoutMs = DEFAULT_TIMEOUT_MS;

    private AcpClient(Process process) throws IOException {
        this.process = process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.readerThread = new Thread(this::readLoop, "acp-client-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /** Spawn {@code command} as the ACP agent and complete {@code initialize} + {@code session/new}. */
    public static AcpClient start(List<String> command) throws IOException {
        return start(command, DEFAULT_TIMEOUT_MS);
    }

    /** {@link #start(List)} with an explicit round-trip timeout. */
    public static AcpClient start(List<String> command, long timeoutMs) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("toolnexus: AcpClient.start requires a non-empty command");
        }
        return start(new Options().command(command).timeoutMs(timeoutMs));
    }

    /**
     * Spawn the agent from {@code opts}, complete {@code initialize} + {@code session/new} (with
     * {@code opts.mcpServers}), then pass {@code opts.model} through. Failures are
     * {@link StartException}s naming the stage. The agent's stderr is captured (last 4 KiB,
     * {@link #stderrTail()}).
     */
    public static AcpClient start(Options opts) throws IOException {
        if (opts == null || opts.command == null || opts.command.isEmpty()) {
            throw new IllegalArgumentException("toolnexus: AcpClient.start requires a non-empty command");
        }
        ProcessBuilder pb = new ProcessBuilder(opts.command);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        if (opts.cwd != null && !opts.cwd.isEmpty()) pb.directory(new File(opts.cwd));
        if (opts.env != null) pb.environment().putAll(opts.env);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new StartException("start", "toolnexus: acp: start \"" + opts.command.get(0) + "\": "
                    + e.getMessage(), e);
        }

        AcpClient client = new AcpClient(process);
        client.options = opts;
        client.timeoutMs = opts.timeoutMs;
        client.drainStderr();
        try {
            client.initializeSession();
        } catch (IOException e) {
            if (e instanceof StartException se) {
                try {
                    process.waitFor(500, TimeUnit.MILLISECONDS); // let a dying agent's stderr drain
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                se.stderr = client.stderrTail();
            }
            client.close();
            throw e;
        }
        return client;
    }

    /** The last 4 KiB the agent wrote to stderr. */
    public String stderrTail() {
        synchronized (stderrTail) {
            return stderrTail.toString();
        }
    }

    private void drainStderr() {
        Thread t = new Thread(() -> {
            try (java.io.InputStream err = process.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = err.read(buf)) > 0) {
                    synchronized (stderrTail) {
                        stderrTail.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (stderrTail.length() > 4096) stderrTail.delete(0, stderrTail.length() - 4096);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "acp-client-stderr");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("unchecked")
    private void initializeSession() throws IOException {
        Options opts = options;
        Map<String, Object> fs = new LinkedHashMap<>();
        fs.put("readTextFile", false);
        fs.put("writeTextFile", false);
        Map<String, Object> clientCapabilities = new LinkedHashMap<>();
        clientCapabilities.put("fs", fs);
        clientCapabilities.put("terminal", false);
        if (opts.clientCapsMeta != null) clientCapabilities.put("_meta", opts.clientCapsMeta);
        Map<String, Object> initParams = new LinkedHashMap<>();
        initParams.put("protocolVersion", 1);
        initParams.put("clientCapabilities", clientCapabilities);
        Map<String, Object> init;
        try {
            init = call("initialize", initParams);
        } catch (IOException e) {
            throw new StartException("initialize", "toolnexus: acp: initialize: " + e.getMessage(), e);
        }
        initializeResult = init;
        boolean httpOk = false;
        if (init.get("agentCapabilities") instanceof Map<?, ?> caps
                && caps.get("mcpCapabilities") instanceof Map<?, ?> mcp) {
            httpOk = Boolean.TRUE.equals(mcp.get("http"));
        }
        List<Object> servers = new ArrayList<>();
        if (opts.mcpServers != null) for (McpServer s : opts.mcpServers) servers.add(s.wire(httpOk));
        mcpServersSent = servers;

        // A real `devin acp` rejects session/new with -32602 unless cwd is ABSOLUTE and an
        // mcpServers array (empty is fine) is present. Never produced by a fake that doesn't
        // validate params, only by the real thing (ADR 0031 spike).
        String cwd = new File(opts.cwd != null && !opts.cwd.isEmpty() ? opts.cwd : System.getProperty("user.dir"))
                .getAbsoluteFile().getPath();
        Map<String, Object> sessionParams = new LinkedHashMap<>();
        sessionParams.put("cwd", cwd);
        sessionParams.put("mcpServers", servers);
        Map<String, Object> result;
        try {
            result = call("session/new", sessionParams);
        } catch (IOException e) {
            throw new StartException("session/new", "toolnexus: acp: session/new: " + e.getMessage(), e);
        }
        Object sid = result.get("sessionId");
        if (sid == null) {
            throw new StartException("session/new", "toolnexus: ACP session/new returned no sessionId", null);
        }
        this.sessionId = String.valueOf(sid);

        if (opts.model != null && !opts.model.isEmpty()) {
            // Passed through unchanged: the stable ACP spelling first, then the older session/set_model.
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("sessionId", sessionId);
            cfg.put("configId", "model");
            cfg.put("value", opts.model);
            try {
                call("session/set_config_option", cfg);
            } catch (IOException e1) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("sessionId", sessionId);
                sm.put("modelId", opts.model);
                try {
                    call("session/set_model", sm);
                } catch (IOException e2) {
                    throw new StartException("set model", "toolnexus: acp: set model \"" + opts.model + "\": "
                            + e1.getMessage() + "; " + e2.getMessage(), e2);
                }
            }
        }
    }

    /** Optional: negotiate a session mode via {@code session/set_mode}. */
    public void setMode(String modeId) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sessionId);
        params.put("modeId", modeId);
        call("session/set_mode", params);
    }

    /** The ACP session id assigned by {@code session/new}, stable across every {@link #generate}. */
    public String sessionId() {
        return sessionId;
    }

    // ---- Function<InProcess.Request, InProcess.Response> --------------------------------

    @Override
    public InProcess.Response apply(InProcess.Request request) {
        try {
            return generate(request);
        } catch (IOException e) {
            throw new RuntimeException("toolnexus: ACP generate failed", e);
        }
    }

    /**
     * One {@code session/prompt} on the already-open, warm session — never spawns, never
     * re-initializes, never opens a new session. Assembles the FULL request (per ADR 0031's
     * chosen default: stateless-by-default, matching every other toolnexus model source) plus
     * the supersedes marker, then returns the accumulated {@code agent_message_chunk} text.
     *
     * <p>Turns on this client are serialized: a single {@code AcpClient} is one ACP session, and
     * one session is one conversation, so concurrent callers are gated onto one turn at a time.
     */
    public InProcess.Response generate(InProcess.Request request) throws IOException {
        String assembled = assemblePrompt(request);
        turnLock.lock();
        try {
            String reply = prompt(assembled);
            return InProcess.Response.content(reply);
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * Builds the full assembled request text from {@code request.messages} (deterministic,
     * one line per message: {@code "<role>: <content>"}) plus the literal
     * {@code SUPERSEDES-ALL-PRIOR: <current turn's text>} marker, where "current turn's text" is
     * the last message's content — the convention proven against the Go spike's fakeagent
     * ({@code stale} scenario).
     */
    static String assemblePrompt(InProcess.Request request) {
        StringBuilder sb = new StringBuilder();
        String currentText = "";
        if (request != null && request.messages != null) {
            for (Object m : request.messages) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(formatMessage(m));
                Object content = extractContent(m);
                if (content != null) currentText = contentToText(content);
            }
        }
        if (sb.length() > 0) sb.append('\n');
        sb.append(SUPERSEDES_MARKER).append(' ').append(currentText);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String formatMessage(Object m) {
        if (!(m instanceof Map)) return String.valueOf(m);
        Map<String, Object> map = (Map<String, Object>) m;
        String role = String.valueOf(map.getOrDefault("role", "user"));
        return role + ": " + contentToText(map.get("content"));
    }

    @SuppressWarnings("unchecked")
    private static Object extractContent(Object m) {
        if (!(m instanceof Map)) return null;
        return ((Map<String, Object>) m).get("content");
    }

    private static String contentToText(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        return Json.stringify(content);
    }

    private String prompt(String text) throws IOException {
        String id = "c-" + nextId.incrementAndGet();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);

        StringBuilder accumulated = new StringBuilder();
        updateSink = params -> {
            Object update = params.get("update");
            if (!(update instanceof Map)) return;
            Map<?, ?> upd = (Map<?, ?>) update;
            if (!"agent_message_chunk".equals(upd.get("sessionUpdate"))) return;
            Object content = upd.get("content");
            if (content instanceof Map<?, ?> c) {
                Object t = c.get("text");
                if (t != null) {
                    synchronized (accumulated) {
                        accumulated.append(String.valueOf(t));
                    }
                }
            }
        };

        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        Map<String, Object> promptParams = new LinkedHashMap<>();
        promptParams.put("sessionId", sessionId);
        promptParams.put("prompt", List.of(part));
        pendingMethod.put(id, "session/prompt");

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", "session/prompt");
        req.put("params", promptParams);
        writeLine(req);

        Map<String, Object> msg;
        try {
            msg = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("toolnexus: ACP session/prompt timed out after " + timeoutMs
                    + "ms (an unanswered session/request_permission hangs a turn forever)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("toolnexus: ACP session/prompt interrupted", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw new IOException(c != null && c.getMessage() != null ? c.getMessage()
                    : "toolnexus: ACP session/prompt failed", c);
        } finally {
            updateSink = null;
        }

        Object error = msg.get("error");
        if (error instanceof Map<?, ?> errMap) {
            throw new IOException("toolnexus: ACP error " + errMap.get("code") + ": " + errMap.get("message"));
        }
        synchronized (accumulated) {
            return accumulated.toString();
        }
    }

    // ---- transport: readLoop + call/reply/write -------------------------------------------

    private void readLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> msg;
                try {
                    msg = Json.toMap(line);
                } catch (RuntimeException e) {
                    continue; // not one JSON object on this line; ignore, matching the spike client
                }
                handleMessage(msg);
            }
        } catch (IOException ignored) {
            // stdout closed — the process exited or we're closing.
        } finally {
            failAllPendingExited();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(Map<String, Object> msg) {
        Object idObj = msg.get("id");
        Object methodObj = msg.get("method");
        String method = methodObj == null ? null : String.valueOf(methodObj);

        if (method == null && idObj != null) {
            // a reply to one of OUR requests (initialize / session/new / session/prompt / ...)
            pendingMethod.remove(String.valueOf(idObj));
            CompletableFuture<Map<String, Object>> future = pending.remove(String.valueOf(idObj));
            if (future != null) future.complete(msg);
            return;
        }

        if ("session/update".equals(method)) {
            java.util.function.Consumer<Map<String, Object>> sink = updateSink;
            Object params = msg.get("params");
            java.util.function.Consumer<Map<String, Object>> observer = options.onUpdate;
            if (observer != null && params instanceof Map) {
                try {
                    observer.accept((Map<String, Object>) params);
                } catch (RuntimeException ignored) {
                }
            }
            if (sink != null && params instanceof Map) {
                sink.accept((Map<String, Object>) params);
            }
            return;
        }

        if ("session/request_permission".equals(method) && idObj != null) {
            handlePermissionRequest(idObj, (Map<String, Object>) msg.get("params"));
            return;
        }

        // An agent->client REQUEST this client does not implement (fs/*, terminal/* — declared
        // unsupported in clientCapabilities): answer -32601, or the agent waits on it forever.
        if (method != null && idObj != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", -32601);
            err.put("message", "method not found: " + method);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jsonrpc", "2.0");
            resp.put("id", idObj);
            resp.put("error", err);
            writeLine(resp);
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePermissionRequest(Object id, Map<String, Object> params) {
        if (!autoAnswerPermission) {
            return; // deliberately dropped on the floor — the caller's own timeout is what fires
        }
        Function<Map<String, Object>, String> host = this.options.onPermission;
        if (host != null) {
            String pick = null;
            try {
                pick = host.apply(params == null ? Map.of() : params);
            } catch (RuntimeException ignored) {
            }
            if (pick == null || pick.isEmpty()) pick = firstOptionOfKind(params, "reject");
            Map<String, Object> outcome = new LinkedHashMap<>();
            if (pick != null) {
                outcome.put("outcome", "selected");
                outcome.put("optionId", pick);
            } else {
                outcome.put("outcome", "cancelled");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outcome", outcome);
            reply(id, result);
            return;
        }
        String chosen = null;
        Object optionsObj = params == null ? null : params.get("options");
        if (optionsObj instanceof List<?> options) {
            for (Object o : options) {
                if (o instanceof Map<?, ?> opt) {
                    Object kind = opt.get("kind");
                    if (kind != null && String.valueOf(kind).startsWith("allow")) {
                        chosen = String.valueOf(opt.get("optionId"));
                        break;
                    }
                }
            }
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        if (chosen != null) {
            outcome.put("outcome", "selected");
            outcome.put("optionId", chosen);
        } else {
            outcome.put("outcome", "cancelled");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", outcome);
        reply(id, result);
    }

    private static String firstOptionOfKind(Map<String, Object> params, String prefix) {
        Object optionsObj = params == null ? null : params.get("options");
        if (optionsObj instanceof List<?> options) {
            for (Object o : options) {
                if (o instanceof Map<?, ?> opt && opt.get("kind") != null
                        && String.valueOf(opt.get("kind")).startsWith(prefix)) {
                    return String.valueOf(opt.get("optionId"));
                }
            }
        }
        return null;
    }

    private Map<String, Object> call(String method, Object params) throws IOException {
        String id = "c-" + nextId.incrementAndGet();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);
        pendingMethod.put(id, method);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.put("params", params);
        try {
            writeLine(req);
        } catch (RuntimeException e) {
            pending.remove(id);
            throw new IOException("toolnexus: acp: agent exited before replying to " + method, e);
        }

        Map<String, Object> msg;
        try {
            msg = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("toolnexus: ACP " + method + " timed out after " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("toolnexus: ACP " + method + " interrupted", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw new IOException(c != null && c.getMessage() != null ? c.getMessage()
                    : "toolnexus: ACP " + method + " failed", c);
        }
        Object error = msg.get("error");
        if (error instanceof Map<?, ?> errMap) {
            throw new IOException("toolnexus: ACP error " + errMap.get("code") + ": " + errMap.get("message"));
        }
        Object result = msg.get("result");
        return result instanceof Map ? (Map<String, Object>) result : new LinkedHashMap<>();
    }

    private void reply(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        writeLine(resp);
    }

    private void writeLine(Object msg) {
        String json = Json.stringify(msg);
        synchronized (writeLock) {
            try {
                stdin.write(json);
                stdin.write("\n");
                stdin.flush();
            } catch (IOException e) {
                throw new RuntimeException("toolnexus: ACP write failed", e);
            }
        }
    }

    /** Fail-fast: the agent's stdout ended, so every waiting request fails now, naming its method. */
    private void failAllPendingExited() {
        for (String key : new ArrayList<>(pending.keySet())) {
            CompletableFuture<Map<String, Object>> future = pending.remove(key);
            String m = pendingMethod.getOrDefault(key, "request");
            if (future != null) {
                future.completeExceptionally(new IOException("toolnexus: acp: agent exited before replying to " + m));
            }
        }
    }

    private void failAllPending(Exception e) {
        for (String key : new ArrayList<>(pending.keySet())) {
            CompletableFuture<Map<String, Object>> future = pending.remove(key);
            if (future != null) future.completeExceptionally(e);
        }
    }

    /**
     * Terminates the agent process. Idempotent — calling this more than once is safe. Process
     * lifetime is independent of any one turn's outcome: closing only happens here, never as a
     * side effect of a failed or cancelled {@link #generate} call.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        // The whole tree: an adapter launch (`npx …`) is a tree, not one process.
        java.util.List<ProcessHandle> tree = process.descendants().toList();
        tree.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            tree.forEach(ProcessHandle::destroyForcibly);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            stdout.close();
        } catch (IOException ignored) {
        }
        failAllPending(new IOException("toolnexus: ACP client closed"));
    }
}
