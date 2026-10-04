package io.github.muthuishere.toolnexus.acp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@code session/new} — a warm session that every subsequent {@link #generate} reuses. The
 * agent is a real tool-calling model ({@code openspec/changes/add-acp-tool-calling}, SPEC §8
 * "ACP model source"): each prompt carries the OpenAI-shaped request — messages, including
 * earlier tool calls and their results, plus the tool schemas — and the agent's JSON reply is
 * parsed back into content or tool calls, which the loop executes through the toolkit. Because
 * an ACP session is stateful and toolnexus assembles a complete request every turn, each
 * {@code generate} also appends an explicit {@code SUPERSEDES-ALL-PRIOR:} marker naming the
 * latest user turn, so a stateful agent answers the fresh prompt rather than a near-duplicate
 * earlier one (ADR 0031's spike gate).
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
     * When true (the default), {@code session/request_permission} is answered immediately from
     * the reader thread — with the first {@code reject}-kind option, or the first
     * {@code allow}-kind option when {@link #allowAgentTools} is set; no matching option ⇒
     * {@code cancelled}. An unanswered permission request hangs the turn forever — see ADR 0031
     * gate item #2. Set false only to reproduce that hang deliberately (tests).
     */
    public volatile boolean autoAnswerPermission = true;

    /**
     * Lets the agent run tools of its OWN: a {@code session/request_permission} is then answered
     * with the first {@code allow}-kind option. Default false — the first {@code reject}-kind
     * option — because toolnexus is the tool executor (SPEC §8, {@code add-acp-tool-calling}):
     * an agent that runs {@code bash} itself has escaped every hook and any builtin execution
     * seam (ADR 0033).
     */
    public volatile boolean allowAgentTools = false;

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
     * re-initializes, never opens a new session. Sends the FULL OpenAI-shaped request (messages,
     * including earlier tool calls and their results, plus the tool schemas — see
     * {@link #assemblePrompt}) and parses the accumulated {@code agent_message_chunk} text into
     * one assistant message: tool calls or content ({@link #parseReply}, SPEC §8 "ACP model
     * source"). The loop then executes those tool calls through the toolkit, exactly as for any
     * other model.
     *
     * <p>Turns on this client are serialized: a single {@code AcpClient} is one ACP session, and
     * one session is one conversation, so concurrent callers are gated onto one turn at a time.
     */
    public InProcess.Response generate(InProcess.Request request) throws IOException {
        String assembled = assemblePrompt(request);
        turnLock.lock();
        try {
            return parseReply(prompt(assembled));
        } finally {
            turnLock.unlock();
        }
    }

    /** Byte-pinned by SPEC §8 "ACP model source" — identical in all seven ports. */
    static final String PREAMBLE =
            "You are the language model behind a tool-calling client. The client executes tools; you never do.\n"
            + "Do not run commands, read or edit files, or use any tool of your own.\n"
            + "The REQUEST below is the complete conversation in OpenAI chat-completions format: \"messages\" holds every message so far, including earlier tool calls and their results; \"tools\" lists the only tools you may call.\n"
            + "Reply with exactly one JSON object and nothing else: no prose, no markdown fences.\n"
            + "To give the final answer: {\"content\": \"<answer>\"}\n"
            + "To call tools: {\"tool_calls\": [{\"id\": \"<unique id>\", \"type\": \"function\", \"function\": {\"name\": \"<tool name>\", \"arguments\": \"<JSON-encoded arguments>\"}}]}\n"
            + "Never both. Use tool results already in \"messages\" instead of calling the same tool again.\n";

    /** Strict: one JSON value and nothing after it, so prose after an object falls through to
     * the first-{@code {}..last-{@code }} slice exactly as in every other port. */
    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Assembles {@code PREAMBLE + "\nREQUEST:\n" + JSON + "\n\nSUPERSEDES-ALL-PRIOR: " + latest
     * user text}, where JSON is the compact {@code {"messages":[...],"tools":[...]}} object
     * (absent arrays render as {@code []}; Jackson never HTML-escapes). The FULL request goes
     * every turn (an ACP session is stateful; a delta would make the client a shadow copy of
     * conversation state), and the supersedes marker keeps a stateful agent off an earlier
     * near-duplicate in its own history (ADR 0031).
     */
    static String assemblePrompt(InProcess.Request request) {
        List<Object> messages = request != null && request.messages != null ? request.messages : List.of();
        List<Object> tools = request != null && request.tools != null ? request.tools : List.of();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", messages);
        payload.put("tools", tools);

        String latest = "";
        boolean found = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Map<?, ?> m && "user".equals(m.get("role"))) {
                latest = contentText(m.get("content"));
                found = true;
                break;
            }
        }
        if (!found && !messages.isEmpty() && messages.get(messages.size() - 1) instanceof Map<?, ?> m) {
            latest = contentText(m.get("content"));
        }
        return PREAMBLE + "\nREQUEST:\n" + Json.stringify(payload) + "\n\n" + SUPERSEDES_MARKER + " " + latest;
    }

    /** A message's content for the supersedes line: a string as is; an array of parts ⇒ the
     * text of its {@code type:"text"} parts joined by one space; anything else ⇒ "". */
    private static String contentText(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof List<?> parts) {
            List<String> texts = new ArrayList<>();
            for (Object p : parts) {
                if (p instanceof Map<?, ?> pm && "text".equals(pm.get("type")) && pm.get("text") instanceof String t) {
                    texts.add(t);
                }
            }
            return String.join(" ", texts);
        }
        return "";
    }

    /**
     * Turns the agent's reply text into one assistant message, by the algorithm SPEC §8 pins:
     * strip fences, parse (or the first-{@code {}..last-{@code }} slice), unwrap
     * {@code choices[0].message} / {@code message}, then {@code tool_calls} ⇒ tool calls,
     * {@code content} ⇒ content, anything else ⇒ the original text untouched (e.g. structured
     * output the host asked for).
     */
    static InProcess.Response parseReply(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            s = nl >= 0 ? s.substring(nl + 1).strip() : "";
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }

        Map<String, Object> obj = parseObject(s);
        if (obj == null) {
            int i = s.indexOf('{');
            int j = s.lastIndexOf('}');
            if (i >= 0 && j > i) obj = parseObject(s.substring(i, j + 1));
        }
        if (obj == null) return InProcess.Response.content(text);

        if (obj.get("choices") instanceof List<?> choices && !choices.isEmpty()) {
            if (choices.get(0) instanceof Map<?, ?> first && first.get("message") instanceof Map<?, ?> msg) {
                obj = asStringMap(msg);
            }
        } else if (obj.get("message") instanceof Map<?, ?> msg) {
            obj = asStringMap(msg);
        }

        if (obj.get("tool_calls") instanceof List<?> raw) {
            List<InProcess.ToolCall> calls = new ArrayList<>();
            for (Object el : raw) {
                if (!(el instanceof Map<?, ?> em)) continue;
                Map<?, ?> fn = em.get("function") instanceof Map<?, ?> f ? f : em;
                if (!(fn.get("name") instanceof String name) || name.isEmpty()) continue;
                // a string passes through as pre-encoded; anything else is structured
                Object args = fn.get("arguments") != null ? fn.get("arguments") : new LinkedHashMap<String, Object>();
                InProcess.ToolCall call = new InProcess.ToolCall(name, args);
                if (em.get("id") instanceof String id && !id.isEmpty()) call.id(id);
                calls.add(call);
            }
            if (!calls.isEmpty()) {
                InProcess.Response r = new InProcess.Response();
                r.toolCalls = calls;
                return r;
            }
        }

        if (obj.containsKey("content")) {
            Object c = obj.get("content");
            if (c instanceof String str) return InProcess.Response.content(str);
            if (c == null) return InProcess.Response.content("");
            return InProcess.Response.content(Json.stringify(c));
        }
        return InProcess.Response.content(text);
    }

    private static Map<String, Object> parseObject(String s) {
        try {
            return STRICT.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null; // not JSON, not an object, or trailing content
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
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
        // Reject by default: toolnexus is the tool executor (SPEC §8, add-acp-tool-calling); an
        // agent that runs a tool itself has escaped every hook. allowAgentTools opts back in.
        String want = allowAgentTools ? "allow" : "reject";
        String chosen = null;
        Object optionsObj = params == null ? null : params.get("options");
        if (optionsObj instanceof List<?> options) {
            for (Object o : options) {
                if (o instanceof Map<?, ?> opt) {
                    Object kind = opt.get("kind");
                    if (kind != null && String.valueOf(kind).startsWith(want)) {
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
