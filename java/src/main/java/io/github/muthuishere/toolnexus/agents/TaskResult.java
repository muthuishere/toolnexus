package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.Request;

import java.util.List;

/**
 * The uniform cross-handle result (SPEC §7D "Errors: one boundary rule" — failures cross the
 * handle boundary as data, never exceptions; only the root may throw to the host).
 *
 * <p>{@code status} is the CLOSED seven-string vocabulary (SPEC §7D) — see the constants below;
 * identical strings in every port; a failed run is {@code "error"}, never {@code "done"} +
 * {@code isError}. It is NOT the three-value CLIENT vocabulary on
 * {@code LlmClient.RunResult.status}, which has no {@code "timeout"}: the two share a field name
 * and nothing else (ADR 0027).
 *
 * <p>When {@code status == "pending"}, {@code pending} carries the leaf's Request with the
 * suspended handle's deterministic id path stamped at {@code data.path} (§10 agent-escalation
 * addendum) and {@code pendingPath} mirrors it for direct access.
 *
 * @param totalTokens the CUMULATIVE TREE TOTAL for this handle and every descendant, on EVERY
 *                    status. It used to mean the tree total on {@code error}/{@code closed}/
 *                    {@code timeout} and the single turn's own usage on {@code done}/
 *                    {@code pending}/{@code incomplete} — one field with two meanings, which is
 *                    what made a delegating run's token report unreadable (ADR 0025).
 * @param ownTokens   what THIS handle's own turn spent — the per-agent figure {@code totalTokens}
 *                    used to carry on some statuses. Zero when no turn ran.
 * @param limit       which limit stopped the run ({@code "maxTurns"}, {@code "completion"}, a
 *                    budget name such as {@code "maxTokens"}, {@code "timeout"}), or null.
 */
public record TaskResult(String text, boolean isError, String status, Request pending,
                         List<String> pendingPath, int turns, long totalTokens,
                         long ownTokens, String limit) {

    // The AGENT-RUNTIME status vocabulary — SEVEN values (SPEC §7D), named so a host never has to
    // retype a string literal and never has to guess which of the two vocabularies it is reading.
    public static final String STATUS_DONE = "done";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_INCOMPLETE = "incomplete";
    public static final String STATUS_INTERRUPTED = "interrupted";
    public static final String STATUS_CLOSED = "closed";
    public static final String STATUS_TIMEOUT = "timeout";
    public static final String STATUS_ERROR = "error";

    /** The seven §7D statuses, in the order SPEC §7D lists them. */
    public static final List<String> STATUSES = List.of(
            STATUS_DONE, STATUS_PENDING, STATUS_INCOMPLETE, STATUS_INTERRUPTED,
            STATUS_CLOSED, STATUS_TIMEOUT, STATUS_ERROR);

    // The CLOSED `limit` vocabulary (A14): the Budget field that stopped the run, spelled exactly
    // as SPEC spells it, plus the two non-budget stops. Identical in all seven ports — a value a
    // host is told to branch on is useless if each port spells it differently. A port maps its
    // internal pool names onto these AT THE BOUNDARY.
    public static final String LIMIT_MAX_TURNS = "maxTurns";
    public static final String LIMIT_MAX_TOKENS = "maxTokens";
    public static final String LIMIT_MAX_TOOL_CALLS = "maxToolCalls";
    public static final String LIMIT_MAX_WALL_MS = "maxWallMs";
    public static final String LIMIT_MAX_CHILDREN = "maxChildren";
    public static final String LIMIT_MAX_CONCURRENT = "maxConcurrent";
    public static final String LIMIT_MAX_DEPTH = "maxDepth";
    public static final String LIMIT_COMPLETION = "completion";
    public static final String LIMIT_TIMEOUT = "timeout";

    /** The nine canonical {@code limit} values. Note (A17): {@code maxChildren},
     * {@code maxConcurrent} and {@code maxDepth} are ADMISSION refusals, which java reports as a
     * {@code Spawn} error rather than a settled result — this change does not move where they
     * surface, so java simply never emits those three. The spelling is pinned regardless. */
    public static final List<String> LIMITS = List.of(
            LIMIT_MAX_TURNS, LIMIT_MAX_TOKENS, LIMIT_MAX_TOOL_CALLS, LIMIT_MAX_WALL_MS,
            LIMIT_MAX_CHILDREN, LIMIT_MAX_CONCURRENT, LIMIT_MAX_DEPTH,
            LIMIT_COMPLETION, LIMIT_TIMEOUT);

    /**
     * A21 — the STRUCTURAL guarantee, not a per-site discipline: EVERY {@code TaskResult} in the
     * runtime flows through this compact constructor, so a fourteenth construction site cannot
     * reintroduce the contradiction A18 names even by forwarding a value it did not mean to.
     * Two rules, enforced here and nowhere else:
     * <ul>
     *   <li>a LIMIT STOP ({@code incomplete} / {@code timeout}) gets its limit CANONICALISED onto
     *       the closed {@link #LIMITS} vocabulary, so no internal pool name can reach the field;</li>
     *   <li>any other status gets its limit EXPLICITLY EMPTIED, so a site that forwards the
     *       client's limit through a {@code done} or {@code pending} settle cannot make the
     *       status and the limit contradict each other.</li>
     * </ul>
     */
    public TaskResult {
        boolean limitStop = STATUS_INCOMPLETE.equals(status) || STATUS_TIMEOUT.equals(status);
        limit = limitStop ? canonicalLimit(limit) : null;
    }

    /**
     * A14/A20: map an INTERNAL pool/dimension name onto the canonical spelling. Deliberately NOT
     * public — exporting it would leak exactly the internal names A14 exists to keep out of the
     * public field.
     */
    static String canonicalLimit(String internal) {
        if (internal == null || internal.isEmpty()) return null;
        return switch (internal) {
            case "tokens" -> LIMIT_MAX_TOKENS;
            case "toolCalls" -> LIMIT_MAX_TOOL_CALLS;
            case "wallMs", "maxWall" -> LIMIT_MAX_WALL_MS;
            case "turns" -> LIMIT_MAX_TURNS;
            default -> internal; // already canonical: maxTurns, completion, timeout, …
        };
    }

    /** A result with no per-agent figure and no limit to name. */
    public TaskResult(String text, boolean isError, String status, Request pending,
                      List<String> pendingPath, int turns, long totalTokens) {
        this(text, isError, status, pending, pendingPath, turns, totalTokens, 0L, null);
    }
}
