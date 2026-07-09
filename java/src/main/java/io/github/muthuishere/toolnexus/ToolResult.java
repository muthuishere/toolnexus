package io.github.muthuishere.toolnexus;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The uniform result of executing a {@link Tool}.
 *
 * <ul>
 *   <li>{@code output} — text handed back to the model.</li>
 *   <li>{@code isError} — whether the call failed.</li>
 *   <li>{@code metadata} — free-form (title, server, skill name, ...); may be null.</li>
 * </ul>
 */
public final class ToolResult {
    private final String output;
    private final boolean isError;
    private final Map<String, Object> metadata;

    public ToolResult(String output, boolean isError, Map<String, Object> metadata) {
        this.output = output == null ? "" : output;
        this.isError = isError;
        this.metadata = metadata;
    }

    public String output() {
        return output;
    }

    public boolean isError() {
        return isError;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public static ToolResult ok(String output) {
        return new ToolResult(output, false, null);
    }

    public static ToolResult ok(String output, Map<String, Object> metadata) {
        return new ToolResult(output, false, metadata);
    }

    public static ToolResult error(String output) {
        return new ToolResult(output, true, null);
    }

    public static ToolResult error(String output, Map<String, Object> metadata) {
        return new ToolResult(output, true, metadata);
    }

    // ------------------------------------------------------------------
    // §10 Suspension — Pending / waitFor. A tool that can't finish in one
    // shot returns a suspension: a normal ToolResult (contract unchanged)
    // carrying metadata.pending = Request. Mirrors js/src/types.ts.
    // ------------------------------------------------------------------

    private static final AtomicLong PENDING_SEQ = new AtomicLong();

    private static String genId() {
        return "pnd-" + Long.toString(System.currentTimeMillis(), 36) + "-" + PENDING_SEQ.incrementAndGet();
    }

    /**
     * Producer helper: return a suspension. A {@link ToolResult} with {@code metadata.pending}
     * = the given {@link Request}. If the request has no {@code id}, one is generated. The
     * {@code output} is a human-readable fallback ({@code prompt} + the URL if present) and
     * {@code isError} is true (it did not produce a usable answer).
     */
    public static ToolResult pending(Request request) {
        Request req = request.id() == null || request.id().isEmpty() ? request.withId(genId()) : request;
        String output = req.prompt() + (req.url() != null ? "\n" + req.url() : "");
        return new ToolResult(output, true, Map.of("pending", req));
    }

    /** Sugar for the common case: {@code kind:"authorization"} at a login URL. */
    public static ToolResult authRequired(String url) {
        return authRequired(url, "Authorization required to continue");
    }

    /** Sugar for the common case: {@code kind:"authorization"} at a login URL, with a prompt. */
    public static ToolResult authRequired(String url, String prompt) {
        return pending(new Request(genId(), "authorization", prompt, url, null, null));
    }

    /** Read the suspension off a result, if any. Returns {@code null} when not a suspension. */
    public static Request pendingOf(ToolResult result) {
        if (result == null || result.metadata == null) return null;
        Object p = result.metadata.get("pending");
        return p instanceof Request ? (Request) p : null;
    }

    @Override
    public String toString() {
        return "ToolResult{isError=" + isError + ", output=" + output + "}";
    }
}
