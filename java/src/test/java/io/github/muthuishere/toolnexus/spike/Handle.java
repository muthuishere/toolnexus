package io.github.muthuishere.toolnexus.spike;

import io.github.muthuishere.toolnexus.Request;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SPIKE — a live agent: {id, def, state, inbox, budget, children}. The inbox is agent
 * STATE, not a runtime mailbox (persistable/observable). Mirrors {@code js/spike/runtime.ts Handle}.
 * All mutable fields are guarded by the runtime's single lock; {@code state} is volatile so
 * tests can observe it without taking the lock.
 */
public final class Handle {

    public enum State {
        IDLE, RUNNING, SUSPENDED, CLOSED;

        /** Lowercase form used in traces ("idle" etc.), matching the JS string states. */
        public String label() { return name().toLowerCase(Locale.ROOT); }
    }

    /** "Unlimited" token pool sentinel (JS uses Infinity; a quarter of MAX_VALUE never exhausts). */
    public static final long NO_LIMIT = Long.MAX_VALUE / 4;

    public volatile State state = State.IDLE;
    public final Deque<InboxItem> inbox = new ArrayDeque<>();
    public final List<Handle> children = new ArrayList<>();

    // law 5: pools + effective caps — effective = min(own, parent remaining) at spawn
    public long poolTokens;
    public final long poolChildren;
    public final int effMaxTurns;
    public final int effMaxConcurrent;
    public final int effMaxDepth;

    public long usageTotal = 0;
    public int turnsTotal = 0;
    public Request pendingReq;
    public List<String> pendingPath;
    Thread runnerThread;         // the virtual thread driving the in-flight turn (interrupt target)
    String abortReason;          // "interrupted" | "closed" — set BEFORE the thread is interrupted
    public final Map<String, TaskResult> taskCache = new HashMap<>();
    final List<CompletableFuture<TaskResult>> waiters = new ArrayList<>();
    final Deque<String> wakeQueue = new ArrayDeque<>(); // queued wake prompts (concurrency gate)
    List<InboxItem> drained = List.of(); // items consumed by the in-flight turn (restored on abort)
    String taskKey;              // set when spawned via the task tool (idempotent reattachment)
    public TaskResult lastResult; // last completed turn result (survives outside waitOn())
    int runningChildren = 0;
    int seq = 0;

    public final int depth;
    public final String id;
    public final AgentDef def;
    public final Handle parent;

    Handle(String id, AgentDef def, Handle parent) {
        this.id = id;
        this.def = def;
        this.parent = parent;
        this.depth = parent != null ? parent.depth + 1 : 0;
        // Law 5: carve — effective = min(own, parent remaining)
        Budget own = def.budget != null ? def.budget : new Budget();
        long pTok = parent != null ? parent.poolTokens : NO_LIMIT;
        this.poolTokens = Math.min(own.maxTokens != null ? own.maxTokens : pTok, pTok);
        this.poolChildren = own.maxChildren != null ? own.maxChildren : NO_LIMIT;
        this.effMaxTurns = own.maxTurns != null ? own.maxTurns : 6;
        this.effMaxConcurrent = own.maxConcurrent != null ? own.maxConcurrent : 8;
        this.effMaxDepth = own.maxDepth != null ? own.maxDepth : 3;
    }
}
