package io.github.muthuishere.toolnexus.agents;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The runtime's injectable clock (SPEC §7D "Lifecycle &amp; runtime obligations"): every timer,
 * timestamp, and deadline the runtime owns goes through this seam so fixtures can run on a
 * virtual clock and produce identical transition traces deterministically.
 *
 * <p>Two methods: {@link #millis()} (wall-budget bookkeeping, timestamps) and
 * {@link #schedule(long, Runnable)} (recurring timers — heartbeats). Tests supply a manual
 * implementation that advances time and fires ticks explicitly.
 */
public interface RuntimeClock {

    /** Current time in milliseconds (monotonic-enough for budget/wall accounting). */
    long millis();

    /** Schedule {@code task} every {@code everyMs}; closing the returned handle stops it. */
    AutoCloseable schedule(long everyMs, Runnable task);

    /** The real clock: {@code System.currentTimeMillis} + a daemon scheduler per subscription. */
    static RuntimeClock system() {
        return new RuntimeClock() {
            @Override public long millis() { return System.currentTimeMillis(); }

            @Override public AutoCloseable schedule(long everyMs, Runnable task) {
                ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread th = new Thread(r, "toolnexus-agent-timer");
                    th.setDaemon(true);
                    return th;
                });
                ScheduledFuture<?> f = timer.scheduleAtFixedRate(task, everyMs, everyMs,
                        TimeUnit.MILLISECONDS);
                return () -> {
                    f.cancel(false);
                    timer.shutdownNow();
                };
            }
        };
    }
}
