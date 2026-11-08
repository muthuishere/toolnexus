package io.github.muthuishere.toolnexus.mcp2026;

/** A paused authorization; mirrors toolnexus SPEC §10 Request (kind "authorization"). */
public record Pending(String id, String kind, String prompt, String url, String expiresAt) {

    /** The Pending inside a thrown exception (or its causes), or null. */
    public static Pending of(Throwable t) {
        for (; t != null; t = t.getCause()) if (t instanceof PendingException pe) return pe.pending;
        return null;
    }
}
