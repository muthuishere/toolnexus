package io.github.muthuishere.toolnexus;

/**
 * An {@code authenticate} failure with an explicit HTTP status and challenge (SPEC §7C.2).
 * {@link McpServerAuth} throws it; any {@link McpHandler.Authenticate} hook may. On either wire the
 * handler answers {@code status} (0 ⇒ 401) with {@code WWW-Authenticate} = {@code challenge} (a
 * non-empty {@code wwwAuthenticate} hook result wins), {@code Content-Type: application/json} and the
 * body {@code {"error":"unauthorized"}}. {@code reason} is never sent on the wire.
 */
public final class McpAuthError extends Exception {
    private final int status;
    private final String challenge;
    private final String reason;

    public McpAuthError(int status, String challenge, String reason) {
        super(reason == null || reason.isEmpty() ? "unauthorized" : reason, null, false, false);
        this.status = status;
        this.challenge = challenge == null ? "" : challenge;
        this.reason = reason == null ? "" : reason;
    }

    /** The HTTP status (401 or 403; 0 ⇒ 401). */
    public int status() { return status; }

    /** The WWW-Authenticate value. */
    public String challenge() { return challenge; }

    /** Why it failed — for logs only, never on the wire. */
    public String reason() { return reason; }
}
