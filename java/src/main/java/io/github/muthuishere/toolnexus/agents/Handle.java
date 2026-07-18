package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Request;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * A live agent (SPEC §7D "Handle state machine"): {@code {id, def, state, inbox, budget,
 * children}}. The inbox is agent STATE — persistable and observable — never a runtime/language
 * mailbox. Ids are deterministic and parent-scoped ({@code root/coordinator.1/explore.2}), never
 * random. All mutable fields are guarded by the runtime's single lock; {@link #state} is volatile
 * so observers can read it without taking the lock.
 *
 * <p>Handles are capabilities: post/wake what you hold, wait only on handles you spawned.
 */
public final class Handle {

    public enum State {
        IDLE, RUNNING, SUSPENDED, CLOSED;

        /** Lowercase form used in transition traces ({@code "idle"} etc.) — parity with the
         * shared fixtures' state strings. */
        public String label() { return name().toLowerCase(Locale.ROOT); }
    }

    /** "Unlimited" pool sentinel (a quarter of {@code Long.MAX_VALUE} never exhausts and never
     * overflows under roll-up subtraction). */
    public static final long NO_LIMIT = Long.MAX_VALUE / 4;

    public volatile State state = State.IDLE;
    public final Deque<InboxItem> inbox = new ArrayDeque<>();
    public final List<Handle> children = new ArrayList<>();

    // §7D budgets: pools + effective caps — effective = min(own, parent remaining) at spawn.
    public long poolTokens;
    public long poolToolCalls;
    public final long poolChildren;
    public final int effMaxTurns;
    public final int effMaxConcurrent;
    public final int effMaxDepth;
    /** Wall budget (ms); {@code NO_LIMIT} = unbounded. The window opens at the first turn. */
    public final long effMaxWallMs;
    /** Clock millis at the first turn; 0 = not started (wall budget window not yet open). */
    long wallStartMs;

    public long usageTotal;
    public int turnsTotal;
    public Request pendingReq;
    public List<String> pendingPath;
    /** §7D cancellation: the token bound to the in-flight Run ({@code interrupt} cancels it). */
    LlmClient.CancelToken runCancel;
    /** {@code "interrupted" | "closed"} — set BEFORE the token is cancelled, so the abort is
     * classified by state, never by exception type. */
    String abortReason;
    final List<CompletableFuture<TaskResult>> waiters = new ArrayList<>();
    /** Queued wake prompts (per-parent concurrency gate; FIFO slot transfer). */
    final Deque<String> wakeQueue = new ArrayDeque<>();
    /** Items consumed by the in-flight turn — restored on abort (transactional drain). */
    List<InboxItem> drained = List.of();
    /** Set when spawned via the {@code task} tool: {@code agent + ":" + prompt} (reattachment key). */
    String taskKey;
    /** Last completed turn result — what {@code wait} answers immediately on a settled handle. */
    public TaskResult lastResult;
    int runningChildren;
    int seq;

    public final int depth;
    public final String id;
    public final AgentDef def;
    public final Handle parent;

    Handle(String id, AgentDef def, Handle parent) {
        this.id = id;
        this.def = def;
        this.parent = parent;
        this.depth = parent != null ? parent.depth + 1 : 0;
        // §7D carve: effective = min(own, parent remaining).
        Budget own = def.budget != null ? def.budget : new Budget();
        long pTok = parent != null ? parent.poolTokens : NO_LIMIT;
        this.poolTokens = Math.min(own.maxTokens != null ? own.maxTokens : pTok, pTok);
        long pCalls = parent != null ? parent.poolToolCalls : NO_LIMIT;
        this.poolToolCalls = Math.min(own.maxToolCalls != null ? own.maxToolCalls : pCalls, pCalls);
        this.poolChildren = own.maxChildren != null ? own.maxChildren : NO_LIMIT;
        this.effMaxTurns = own.maxTurns != null ? own.maxTurns : 6;
        this.effMaxConcurrent = own.maxConcurrent != null ? own.maxConcurrent : 8;
        this.effMaxDepth = own.maxDepth != null ? own.maxDepth : 3;
        this.effMaxWallMs = own.maxWallMs != null ? own.maxWallMs : NO_LIMIT;
    }
}
