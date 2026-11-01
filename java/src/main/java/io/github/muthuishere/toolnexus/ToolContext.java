package io.github.muthuishere.toolnexus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional context passed to {@link Tool#execute}. Carries an optional timeout
 * (milliseconds) and a cooperative cancellation flag.
 */
public final class ToolContext {
    private final Long timeoutMs;
    private final AtomicBoolean cancelled;
    private final Answer answer;
    private final String callId;

    public ToolContext() {
        this(null, new AtomicBoolean(false), null);
    }

    public ToolContext(Long timeoutMs) {
        this(timeoutMs, new AtomicBoolean(false), null);
    }

    public ToolContext(Long timeoutMs, AtomicBoolean cancelled) {
        this(timeoutMs, cancelled, null);
    }

    public ToolContext(Long timeoutMs, AtomicBoolean cancelled, Answer answer) {
        this(timeoutMs, cancelled, answer, null);
    }

    /** Full constructor, with the model's id for this tool call (SPEC §4C). */
    public ToolContext(Long timeoutMs, AtomicBoolean cancelled, Answer answer, String callId) {
        this.timeoutMs = timeoutMs;
        this.cancelled = cancelled == null ? new AtomicBoolean(false) : cancelled;
        this.answer = answer;
        this.callId = callId == null ? "" : callId;
    }

    /**
     * The model's id for this tool call when the client loop runs the tool; {@code ""} outside
     * the loop. A message's calls run concurrently, so this tells sibling calls apart (SPEC §4C).
     */
    public String callId() {
        return callId;
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

    /**
     * §10 Suspension. Present ONLY on a post-{@code waitFor} retry: the resolution of a prior
     * suspension. A tool reads {@code ctx.answer().data()} when the resolution IS the payload
     * ({@code kind:"input"}) and ignores it when the world changed out-of-band
     * ({@code kind:"authorization"} — the session is now valid). {@code null} on a normal call.
     */
    public Answer answer() {
        return answer;
    }
}
