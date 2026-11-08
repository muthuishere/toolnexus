package io.github.muthuishere.toolnexus.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.github.muthuishere.toolnexus.mcp.Mcp.*;

/**
 * stdio client transport (MCP 2026-07-28 basic/transports): spawn the server, one JSON-RPC message per line
 * on stdin/stdout. stderr is the server's log stream: inherited or copied to a caller stream, never parsed.
 * No headers, no auth — the _meta carries the version. Responses are demultiplexed by id (concurrent calls OK).
 */
final class StdioConn {
    /** No reply within the request timeout (a §2.2a fallback trigger for {@code server/discover}). */
    static final class TimeoutException extends McpException {
        TimeoutException(String m) { super(m); }
    }

    private final Process proc;
    private final OutputStream in;
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private volatile McpException err; // set once stdout closes
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    /**
     * Probe mode (§2.2a): the reader stops after delivering the first response, BEFORE issuing another
     * read, so the same child can be handed to the frozen SDK transport ({@link #detach}) or resumed
     * ({@link #resume}). Reads are line-exact from the process stream (no private read-ahead buffer).
     */
    private volatile boolean probe;

    private StdioConn(Process proc) {
        this.proc = proc;
        this.in = proc.getOutputStream();
    }

    static StdioConn start(ProcessBuilder pb, OutputStream stderr) throws IOException { return start(pb, stderr, false); }

    static StdioConn start(ProcessBuilder pb, OutputStream stderr, boolean probe) throws IOException {
        if (stderr == null && pb.redirectError() == ProcessBuilder.Redirect.PIPE) pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        StdioConn s = new StdioConn(p);
        s.probe = probe;
        if (stderr != null && pb.redirectError() == ProcessBuilder.Redirect.PIPE)
            Thread.ofVirtual().start(() -> {
                try (InputStream e = p.getErrorStream()) {
                    e.transferTo(stderr);
                } catch (IOException ignored) {
                    // process gone
                }
            });
        Thread.ofVirtual().start(() -> s.read(p.getInputStream()));
        return s;
    }

    /** One line, read byte-exact: nothing past the newline is consumed. Null at EOF. */
    private static byte[] readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return b.toByteArray();
            b.write(c);
        }
        return b.size() > 0 ? b.toByteArray() : null;
    }

    private void read(InputStream out) {
        McpException end = new McpException("stdio server closed its stdout");
        try {
            for (byte[] line; (line = readLine(out)) != null; ) {
                Map<String, Object> m;
                try {
                    m = map(parse(line));
                } catch (IOException e) {
                    continue; // noise
                }
                if (m == null || m.get("method") != null || m.get("id") == null) continue; // not a response: ignored
                CompletableFuture<Map<String, Object>> f = pending.remove(String.valueOf(m.get("id")));
                boolean stop = probe; // decided before the waiter wakes: no further read() once handed over
                if (f != null) f.complete(m);
                if (stop) return; // reader parked; detach() or resume() decides who reads next
            }
        } catch (IOException e) {
            end = new McpException("stdio read: " + e.getMessage(), e);
        }
        err = end;
        done.complete(null);
        pending.values().forEach(f -> f.completeExceptionally(err));
    }

    /** Writes one line and waits for the response with the same id. Never retried. */
    Map<String, Object> roundTrip(long id, byte[] body, java.time.Duration timeout) {
        String key = String.valueOf(id);
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        pending.put(key, f);
        try {
            if (err != null) throw err;
            synchronized (in) {
                in.write(body); // Jackson never emits a raw newline
                in.write('\n');
                in.flush();
            }
            Map<String, Object> msg = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (msg.get("result") == null && msg.get("error") == null) throw new McpException("response has neither result nor error");
            return msg;
        } catch (IOException e) {
            throw err != null ? err : new McpException("stdio write: " + e.getMessage(), e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof McpException m ? m : new McpException(String.valueOf(e.getCause()), e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new TimeoutException("MCP request timed out after " + timeout.toMillis() + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted", e);
        } finally {
            pending.remove(key);
        }
    }

    /** Probe answered with a 2026 result: keep reading on this conn. */
    void resume() {
        probe = false;
        Thread.ofVirtual().start(() -> read(proc.getInputStream()));
    }

    /**
     * Probe answered fast but the server is old: hand the live child (stdin/stdout untouched beyond the
     * discover line) to the frozen path. The caller now owns the process, including destroying it.
     */
    Process detach() { return proc; }

    void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            done.get(2, TimeUnit.SECONDS);
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (Exception e) {
            proc.destroyForcibly();
        }
        try {
            proc.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
