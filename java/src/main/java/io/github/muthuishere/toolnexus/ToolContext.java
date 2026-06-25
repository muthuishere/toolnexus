package io.github.muthuishere.toolnexus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional context passed to {@link Tool#execute}. Carries an optional timeout
 * (milliseconds) and a cooperative cancellation flag.
 */
public final class ToolContext {
    private final Long timeoutMs;
    private final AtomicBoolean cancelled;

    public ToolContext() {
        this(null, new AtomicBoolean(false));
    }

    public ToolContext(Long timeoutMs) {
        this(timeoutMs, new AtomicBoolean(false));
    }

    public ToolContext(Long timeoutMs, AtomicBoolean cancelled) {
        this.timeoutMs = timeoutMs;
        this.cancelled = cancelled == null ? new AtomicBoolean(false) : cancelled;
    }

    /** Timeout in milliseconds, or null for "use the tool default". */
    public Long timeoutMs() {
        return timeoutMs;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public AtomicBoolean cancellationToken() {
        return cancelled;
    }
}
