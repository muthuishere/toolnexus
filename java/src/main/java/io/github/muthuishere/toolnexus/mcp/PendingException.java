package io.github.muthuishere.toolnexus.mcp;

/** Thrown instead of blocking when the Authorizer pauses. Call {@link OAuth#resume} to finish. */
public class PendingException extends McpException {
    public final Pending pending;

    public PendingException(Pending p) {
        super("authorization pending: " + p.prompt());
        this.pending = p;
    }
}
