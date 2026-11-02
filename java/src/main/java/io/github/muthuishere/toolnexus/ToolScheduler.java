package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tool scheduling (SPEC §8 "Tool scheduling"): the calls of one model response, offered in
 * emitted order, run as consecutive runs. Safe calls overlap, at most {@code cap} at once; an
 * unsafe call waits for every earlier call, runs alone, and nothing after it starts until it
 * finishes. Results keep emitted order because each call still writes its own slot.
 */
public final class ToolScheduler {
    /** The cap when neither the option nor {@code TOOLNEXUS_MAX_TOOL_CONCURRENCY} sets one. */
    public static final int DEFAULT_MAX_TOOL_CONCURRENCY = 10;

    private final Semaphore slots;
    private final List<CompletableFuture<?>> started = new ArrayList<>();
    /** Waits for a future the way the run waits for tools (cancel-aware; never throws a tool's failure). */
    private final Consumer<CompletableFuture<?>> await;
    /** Called when the loop thread is interrupted while waiting for a slot (cancel-aware). */
    private final Runnable onInterrupt;

    ToolScheduler(int cap, Consumer<CompletableFuture<?>> await, Runnable onInterrupt) {
        this.slots = new Semaphore(Math.max(1, cap));
        this.await = await;
        this.onInterrupt = onInterrupt;
    }

    /** option &gt; env {@code TOOLNEXUS_MAX_TOOL_CONCURRENCY} &gt; 10; non-positive or non-numeric fall back. */
    public static int resolveMaxToolConcurrency(Integer option) {
        return resolveMaxToolConcurrency(option, System::getenv);
    }

    /** As {@link #resolveMaxToolConcurrency(Integer)} with an injectable env lookup (tests). */
    static int resolveMaxToolConcurrency(Integer option, java.util.function.Function<String, String> env) {
        if (option != null && option > 0) return option;
        String raw = env.apply("TOOLNEXUS_MAX_TOOL_CONCURRENCY");
        if (raw != null && raw.trim().matches("\\+?\\d+")) {
            try {
                int n = Integer.parseInt(raw.trim().replace("+", ""));
                if (n > 0) return n;
            } catch (NumberFormatException ignored) {
                // too large: fall back
            }
        }
        return DEFAULT_MAX_TOOL_CONCURRENCY;
    }

    /** Whether a call to {@code tool} with {@code args} may run alongside other safe calls. */
    public static boolean isConcurrencySafe(Tool tool, java.util.Map<String, Object> args) {
        return tool != null && tool.concurrencySafe(args);
    }

    /** Schedules one call; call in emitted order. An unsafe call has finished when this returns. */
    <T> CompletableFuture<T> supplyAsync(boolean safe, Supplier<T> work, Executor executor) {
        if (!safe) {
            await.accept(CompletableFuture.allOf(started.toArray(new CompletableFuture[0])).handle((r, e) -> null));
            CompletableFuture<T> f = CompletableFuture.supplyAsync(work, executor);
            started.add(f);
            await.accept(f.handle((r, e) -> null));
            return f;
        }
        try {
            slots.acquire();
        } catch (InterruptedException ie) {
            onInterrupt.run(); // throws when the run was cancelled
            slots.acquireUninterruptibly();
            Thread.currentThread().interrupt();
        }
        CompletableFuture<T> f = CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } finally {
                slots.release();
            }
        }, executor);
        started.add(f);
        return f;
    }
}
