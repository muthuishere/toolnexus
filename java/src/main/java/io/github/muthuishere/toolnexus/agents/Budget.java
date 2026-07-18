package io.github.muthuishere.toolnexus.agents;

/**
 * Hierarchical budget (SPEC §7D "Budgets"):
 * {@code Budget{maxTurns, maxTokens, maxToolCalls, maxWallMs, maxChildren, maxConcurrent, maxDepth}}.
 * A {@code null} field = unlimited; effective values are carved at spawn
 * ({@code effective = min(own, parent remaining)}) and enforced by a LIVE ancestor-chain walk
 * before each turn and each spawn (carve alone misses sibling spend). Money is deliberately
 * excluded — vendor data; hosts convert externally.
 */
public final class Budget {
    public Integer maxTurns;
    public Long maxTokens;
    public Long maxToolCalls;
    public Long maxWallMs;
    public Integer maxChildren;
    public Integer maxConcurrent;
    public Integer maxDepth;

    public Budget maxTurns(int v) { this.maxTurns = v; return this; }
    public Budget maxTokens(long v) { this.maxTokens = v; return this; }
    public Budget maxToolCalls(long v) { this.maxToolCalls = v; return this; }
    public Budget maxWallMs(long v) { this.maxWallMs = v; return this; }
    public Budget maxChildren(int v) { this.maxChildren = v; return this; }
    public Budget maxConcurrent(int v) { this.maxConcurrent = v; return this; }
    public Budget maxDepth(int v) { this.maxDepth = v; return this; }

    /** {@code {...base, ...override}} — override fields win when non-null. */
    static Budget merge(Budget base, Budget override) {
        Budget lo = base == null ? new Budget() : base;
        Budget hi = override == null ? new Budget() : override;
        Budget b = new Budget();
        b.maxTurns = hi.maxTurns != null ? hi.maxTurns : lo.maxTurns;
        b.maxTokens = hi.maxTokens != null ? hi.maxTokens : lo.maxTokens;
        b.maxToolCalls = hi.maxToolCalls != null ? hi.maxToolCalls : lo.maxToolCalls;
        b.maxWallMs = hi.maxWallMs != null ? hi.maxWallMs : lo.maxWallMs;
        b.maxChildren = hi.maxChildren != null ? hi.maxChildren : lo.maxChildren;
        b.maxConcurrent = hi.maxConcurrent != null ? hi.maxConcurrent : lo.maxConcurrent;
        b.maxDepth = hi.maxDepth != null ? hi.maxDepth : lo.maxDepth;
        return b;
    }
}
