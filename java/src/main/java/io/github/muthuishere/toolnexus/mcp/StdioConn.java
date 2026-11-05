package io.github.muthuishere.toolnexus.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    private StdioConn(Process proc) {
        this.proc = proc;
        this.in = proc.getOutputStream();
    }

    static StdioConn start(ProcessBuilder pb, OutputStream stderr) throws IOException {
        if (stderr == null && pb.redirectError() == ProcessBuilder.Redirect.PIPE) pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        StdioConn s = new StdioConn(p);
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

    private void read(InputStream out) {
        McpException end = new McpException("stdio server closed its stdout");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                Map<String, Object> m;
                try {
                    m = map(parse(line.getBytes(StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    continue; // noise
                }
                if (m == null || m.get("method") != null || m.get("id") == null) continue; // not a response: ignored
                CompletableFuture<Map<String, Object>> f = pending.remove(String.valueOf(m.get("id")));
                if (f != null) f.complete(m);
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
