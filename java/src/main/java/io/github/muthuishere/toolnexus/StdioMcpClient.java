package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The frozen (pre-2026) MCP client over a LOCAL stdio server, in-house — no MCP SDK session, no
 * Reactor on this path (SPEC §2.F, §2.2a). Servers that speak 2026-07-28 never get here: they answer
 * {@code server/discover} and stay on {@link Mcp2026}; this client takes over the same child when the
 * probe says the server is older, and negotiates 2025-11-25 down to 2024-11-05 in {@code initialize}.
 *
 * <p>Design (same as rust/src/mcp/stdio.rs): a caller writes its request line straight to the child's
 * stdin and parks on its own pending slot; ONE reader thread scans stdout for newlines in a reused
 * byte buffer and routes each response to its caller by {@code id}. Server→client requests (ping,
 * elicitation, roots, …) are answered from a separate virtual thread, so the reader never waits on
 * the stdin lock — a caller blocked writing a large request to a server that is itself blocked writing
 * stdout would otherwise deadlock both. The SDK's {@code McpSchema} records and Jackson 3 mapper are
 * kept for the result types, so content mapping is unchanged.
 */
final class StdioMcpClient implements McpSource.McpSession {
    /** Offered in {@code initialize}; the server may answer any of {@link #SUPPORTED}. */
    static final String PROTOCOL_VERSION = "2025-11-25";
    static final List<String> SUPPORTED = List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
    private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {};

    /** A JSON-RPC response, parsed straight into its result type (unknown members ignored). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reply<T>(T result, McpSchema.JSONRPCResponse.JSONRPCError error) {}

    private static final TypeRef<Reply<McpSchema.InitializeResult>> INIT = new TypeRef<>() {};
    private static final TypeRef<Reply<McpSchema.ListToolsResult>> LIST_TOOLS = new TypeRef<>() {};
    private static final TypeRef<Reply<McpSchema.CallToolResult>> CALL = new TypeRef<>() {};
    private static final TypeRef<Reply<Map<String, Object>>> RAW = new TypeRef<>() {};
    private static final TypeRef<Reply<McpSchema.ReadResourceResult>> READ = new TypeRef<>() {};
    private static final byte[] HEAD = "{\"jsonrpc\":\"2.0\",\"id\":".getBytes(StandardCharsets.UTF_8);

    private final Process proc;
    private final OutputStream stdin;
    private final McpJsonMapper mapper;
    private final Object writeLock = new Object();
    private final Map<Long, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Consumer<Object> onProgress;
    private final Function<Request, Answer> waitFor;
    private volatile RuntimeException closedErr;
    private volatile boolean closing;
    private McpSchema.InitializeResult init;

    private StdioMcpClient(Process proc, McpJsonMapper mapper, Consumer<Object> onProgress,
                           Function<Request, Answer> waitFor) {
        this.proc = proc;
        this.stdin = proc.getOutputStream();
        this.mapper = mapper;
        this.onProgress = onProgress;
        this.waitFor = waitFor;
    }

    /** Takes over a running child (the §2.2a probe hand-off, or a freshly spawned one) and starts reading. */
    static StdioMcpClient attach(Process proc, McpJsonMapper mapper, Consumer<Object> onProgress,
                                 Function<Request, Answer> waitFor) {
        StdioMcpClient c = new StdioMcpClient(proc, mapper, onProgress, waitFor);
        Thread.ofPlatform().daemon().name("toolnexus-stdio-reader").start(c::readLoop);
        return c;
    }

    // ------------------------------------------------------------------ reader

    private void readLoop() {
        InputStream in = proc.getInputStream();
        byte[] buf = new byte[16 * 1024];
        int start = 0, end = 0;
        try {
            while (true) {
                int nl = -1;
                for (int i = start; i < end; i++) if (buf[i] == '\n') { nl = i; break; }
                if (nl < 0) {
                    if (start > 0) { // compact
                        System.arraycopy(buf, start, buf, 0, end - start);
                        end -= start;
                        start = 0;
                    }
                    if (end == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    int n = in.read(buf, end, buf.length - end);
                    if (n < 0) break;
                    end += n;
                    continue;
                }
                dispatch(buf, start, nl - start);
                start = nl + 1;
                if (start == end) start = end = 0;
            }
        } catch (IOException ignored) {
            // the stream closed under us: treated as EOF
        }
        RuntimeException err = new RuntimeException("MCP stdio server closed the connection");
        closedErr = err;
        for (Long id : List.copyOf(pending.keySet())) {
            CompletableFuture<byte[]> f = pending.remove(id);
            if (f != null) f.completeExceptionally(err);
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(byte[] buf, int off, int len) {
        while (len > 0 && (buf[off + len - 1] == '\r' || buf[off + len - 1] == ' ')) len--;
        if (len == 0) return;
        // Skim the envelope only: a response's bytes go to its caller, which parses them straight
        // into the result type — the reader never builds a tree of a (possibly large) result.
        Object id = null;
        String method = null;
        try (JsonParser jp = Json.MAPPER.createParser(buf, off, len)) {
            if (jp.nextToken() != JsonToken.START_OBJECT) return;
            for (JsonToken t = jp.nextToken(); t == JsonToken.PROPERTY_NAME; t = jp.nextToken()) {
                String field = jp.currentName();
                JsonToken v = jp.nextToken();
                if ("id".equals(field)) {
                    id = v == JsonToken.VALUE_NUMBER_INT ? (Object) jp.getLongValue()
                            : v == JsonToken.VALUE_STRING ? jp.getString() : null;
                } else if ("method".equals(field) && v == JsonToken.VALUE_STRING) {
                    method = jp.getString();
                } else {
                    jp.skipChildren();
                }
            }
        } catch (Exception e) {
            return; // noise on stdout (logs, malformed lines) is ignored
        }
        if (method == null) {
            Long key = idKey(id);
            if (key == null) return;
            CompletableFuture<byte[]> f = pending.remove(key);
            if (f != null) f.complete(java.util.Arrays.copyOfRange(buf, off, off + len));
            return;
        }
        Map<String, Object> m;
        try {
            m = mapper.readValue(java.util.Arrays.copyOfRange(buf, off, off + len), MAP);
        } catch (Exception e) {
            return;
        }
        {
            if (id != null) {
                Thread.ofVirtual().name("toolnexus-stdio-server-request")
                        .start(() -> answerServerRequest(m.get("id"), String.valueOf(m.get("method")), m.get("params")));
            } else if (McpSchema.METHOD_NOTIFICATION_PROGRESS.equals(method) && onProgress != null
                    && m.get("params") instanceof Map<?, ?> p) {
                onProgress.accept(((Map<String, Object>) p).get("progressToken"));
            }
            // other notifications (logging, list_changed, …) are ignored
        }
    }

    private static Long idKey(Object id) {
        if (id instanceof Number n) return n.longValue();
        if (id instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ server → client requests

    @SuppressWarnings("unchecked")
    private void answerServerRequest(Object id, String method, Object params) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        try {
            switch (method) {
                case McpSchema.METHOD_PING -> msg.put("result", Map.of());
                case McpSchema.METHOD_ELICITATION_CREATE -> {
                    if (waitFor == null) {
                        msg.put("error", error(-32601, "Method not found: " + method, null));
                    } else {
                        Map<String, Object> p = params instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
                        McpSchema.ElicitRequest req = "url".equals(p.get("mode"))
                                ? mapper.convertValue(p, McpSchema.ElicitUrlRequest.class)
                                : mapper.convertValue(p, McpSchema.ElicitFormRequest.class);
                        McpSchema.ElicitResult r = McpSource.answerToElicitResult(
                                waitFor.apply(McpSource.elicitationToRequest(req)));
                        msg.put("result", r);
                    }
                }
                case McpSchema.METHOD_ROOTS_LIST -> msg.put("error", error(-32601, "Roots not supported",
                        Map.of("reason", "Client does not have roots capability")));
                default -> msg.put("error", error(-32601, "Method not found: " + method, null));
            }
        } catch (Exception e) {
            msg.remove("result");
            msg.put("error", error(-32603, e.getMessage() == null ? String.valueOf(e) : e.getMessage(), null));
        }
        try {
            writeLine(mapper.writeValueAsBytes(msg));
        } catch (IOException ignored) {
            // the child is gone; nothing to answer
        }
    }

    private static Map<String, Object> error(int code, String message, Object data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        if (data != null) e.put("data", data);
        return e;
    }

    // ------------------------------------------------------------------ client → server

    private void writeLine(byte[] line) throws IOException {
        synchronized (writeLock) {
            stdin.write(line); // Jackson never emits a raw newline
            stdin.write('\n');
            stdin.flush();
        }
    }

    /**
     * One request; waits for its reply ({@code timeoutMs <= 0} ⇒ no deadline). Interrupting the caller
     * abandons the request (the interrupt flag stays set, wrapped as the SDK's blocking call did).
     */
    private <T> T request(String method, Object params, long timeoutMs, TypeRef<Reply<T>> type) {
        abandoned.remove();
        RuntimeException closed = closedErr;
        if (closed != null) throw closed;
        long id = ids.incrementAndGet();
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            byte[] tail = mapper.writeValueAsBytes(params == null
                    ? Map.of("method", method) : Map.of("method", method, "params", params));
            byte[] idb = Long.toString(id).getBytes(StandardCharsets.US_ASCII);
            synchronized (writeLock) {
                stdin.write(HEAD);
                stdin.write(idb);
                stdin.write(',');
                stdin.write(tail, 1, tail.length - 1); // tail is "{...}": splice after the id
                stdin.write('\n');
                stdin.flush();
            }
            if (closedErr != null && !f.isDone()) throw closedErr;
            Reply<T> reply = mapper.readValue(timeoutMs > 0 ? f.get(timeoutMs, TimeUnit.MILLISECONDS) : f.get(), type);
            if (reply.error() != null) throw new McpError(reply.error());
            if (reply.result() == null) throw new RuntimeException("MCP response has neither result nor error");
            return reply.result();
        } catch (IOException e) {
            throw closedErr != null ? closedErr : new RuntimeException("stdio write: " + e.getMessage(), e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new RuntimeException(e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("MCP request timed out after " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            abandoned.set(id); // the caller may tell the server why (abandon)
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            pending.remove(id); // a late reply to this id is dropped by the reader
        }
    }

    /** The id of the last request this thread gave up on (interrupted), for {@link #abandon}. */
    private final ThreadLocal<Long> abandoned = new ThreadLocal<>();

    /**
     * Tell the server the request this thread just gave up on is cancelled
     * ({@code notifications/cancelled {requestId, reason}}), as rust/src/mcp/stdio.rs does on an idle
     * timeout ({@code "request timeout"}) or caller cancel ({@code "cancelled"}). Best effort.
     */
    @Override
    public void abandon(String reason) {
        Long id = abandoned.get();
        abandoned.remove();
        if (id == null || closedErr != null) return;
        try {
            writeLine(mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                    "params", Map.of("requestId", id, "reason", reason))));
        } catch (IOException ignored) {
            // the child is gone
        }
    }

    private void notifyServer(String method) throws IOException {
        writeLine(("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    /** {@code initialize} (+ the elicitation capability when a §10 waitFor exists), then {@code notifications/initialized}. */
    void initialize(long timeoutMs) throws IOException {
        McpSchema.ClientCapabilities caps = waitFor != null
                ? McpSchema.ClientCapabilities.builder().elicitation(true, true).build()
                : McpSchema.ClientCapabilities.builder().build();
        McpSchema.InitializeRequest req = new McpSchema.InitializeRequest(PROTOCOL_VERSION, caps,
                new McpSchema.Implementation("toolnexus", "0.1.0"));
        McpSchema.InitializeResult r = request(McpSchema.METHOD_INITIALIZE, req, timeoutMs, INIT);
        if (!SUPPORTED.contains(r.protocolVersion())) {
            throw new RuntimeException("Unsupported protocol version from the server: " + r.protocolVersion());
        }
        init = r;
        notifyServer(McpSchema.METHOD_NOTIFICATION_INITIALIZED);
    }

    private void requireTools() {
        if (init == null || init.capabilities() == null || init.capabilities().tools() == null) {
            throw new IllegalStateException("Server does not provide tools capability");
        }
    }

    private void requireResources() {
        if (init == null || init.capabilities() == null || init.capabilities().resources() == null) {
            throw new IllegalStateException("Server does not provide the resources capability");
        }
    }

    /** Per-request deadline for list/read calls ({@code <= 0} ⇒ none; tools/call is governed by the idle watchdog). */
    long requestTimeoutMs;

    @Override
    public McpSchema.ListToolsResult listTools(String cursor) {
        requireTools();
        return request(McpSchema.METHOD_TOOLS_LIST,
                cursor == null ? Map.of() : Map.of("cursor", cursor), requestTimeoutMs, LIST_TOOLS);
    }

    @Override
    public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest req) {
        requireTools();
        return request(McpSchema.METHOD_TOOLS_CALL, req, 0, CALL);
    }

    @Override
    @SuppressWarnings("unchecked")
    public McpSchema.ListResourcesResult listResources() {
        requireResources();
        List<Object> all = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> page = request(McpSchema.METHOD_RESOURCES_LIST,
                    cursor == null ? Map.of() : Map.of("cursor", cursor), requestTimeoutMs, RAW);
            if (page.get("resources") instanceof List<?> l) all.addAll(l);
            Object next = page.get("nextCursor");
            cursor = next == null ? null : String.valueOf(next);
        } while (cursor != null);
        return mapper.convertValue(Map.of("resources", all), McpSchema.ListResourcesResult.class);
    }

    @Override
    public McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest req) {
        requireResources();
        return request(McpSchema.METHOD_RESOURCES_READ, req, requestTimeoutMs, READ);
    }

    /** Close stdin, wait briefly, then terminate the child and anything it spawned. */
    @Override
    public void closeGracefully() {
        if (closing) return;
        closing = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.descendants().forEach(ProcessHandle::destroy);
                proc.destroy();
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.descendants().forEach(ProcessHandle::destroyForcibly);
                    proc.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
