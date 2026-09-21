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

    private volatile String sessionId;
    private volatile java.util.function.Consumer<Map<String, Object>> updateSink;

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
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();

        AcpClient client = new AcpClient(process);
        client.timeoutMs = timeoutMs;
        try {
            client.initializeSession();
        } catch (IOException e) {
            client.close();
            throw e;
        }
        return client;
    }

    private void initializeSession() throws IOException {
        Map<String, Object> fs = new LinkedHashMap<>();
        fs.put("readTextFile", false);
        fs.put("writeTextFile", false);
        Map<String, Object> clientCapabilities = new LinkedHashMap<>();
        clientCapabilities.put("fs", fs);
        Map<String, Object> initParams = new LinkedHashMap<>();
        initParams.put("protocolVersion", 1);
        initParams.put("clientCapabilities", clientCapabilities);
        call("initialize", initParams);

        // A real `devin acp` rejects session/new with -32602 unless cwd is ABSOLUTE and an
        // mcpServers array (empty is fine) is present. Never produced by a fake that doesn't
        // validate params, only by the real thing (ADR 0031 spike).
        String cwd = new File(System.getProperty("user.dir")).getAbsoluteFile().getPath();
        Map<String, Object> sessionParams = new LinkedHashMap<>();
        sessionParams.put("cwd", cwd);
        sessionParams.put("mcpServers", List.of());
        Map<String, Object> result = call("session/new", sessionParams);
        Object sid = result.get("sessionId");
        if (sid == null) {
            throw new IOException("toolnexus: ACP session/new returned no sessionId");
        }
        this.sessionId = String.valueOf(sid);
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
            throw new IOException("toolnexus: ACP session/prompt failed", e.getCause());
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
            failAllPending(new IOException("toolnexus: ACP connection closed"));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(Map<String, Object> msg) {
        Object idObj = msg.get("id");
        Object methodObj = msg.get("method");
        String method = methodObj == null ? null : String.valueOf(methodObj);

        if (method == null && idObj != null) {
            // a reply to one of OUR requests (initialize / session/new / session/prompt / ...)
            CompletableFuture<Map<String, Object>> future = pending.remove(String.valueOf(idObj));
            if (future != null) future.complete(msg);
            return;
        }

        if ("session/update".equals(method)) {
            java.util.function.Consumer<Map<String, Object>> sink = updateSink;
            Object params = msg.get("params");
            if (sink != null && params instanceof Map) {
                sink.accept((Map<String, Object>) params);
            }
            return;
        }

        if ("session/request_permission".equals(method) && idObj != null) {
            handlePermissionRequest(idObj, (Map<String, Object>) msg.get("params"));
            return;
        }

        // any other server-initiated request this client doesn't specifically handle: reply
        // empty rather than leave it hanging, matching the fake server's own default behavior.
        if (idObj != null) {
            reply(idObj, Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePermissionRequest(Object id, Map<String, Object> params) {
        if (!autoAnswerPermission) {
            return; // deliberately dropped on the floor — the caller's own timeout is what fires
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

    private Map<String, Object> call(String method, Object params) throws IOException {
        String id = "c-" + nextId.incrementAndGet();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.put("params", params);
        writeLine(req);

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
            throw new IOException("toolnexus: ACP " + method + " failed", e.getCause());
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
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
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
