package io.github.muthuishere.toolnexus.spike;

/**
 * SPIKE — hierarchical budget (law 5). Mirrors {@code js/spike/runtime.ts Budget}.
 * Null field = unlimited (carved from the parent's remaining pool at spawn).
 */
public final class Budget {
    public Integer maxTurns;
    public Long maxTokens;
    public Integer maxChildren;
    public Integer maxConcurrent;
    public Integer maxDepth;

    public Budget maxTurns(int v) { this.maxTurns = v; return this; }
    public Budget maxTokens(long v) { this.maxTokens = v; return this; }
    public Budget maxChildren(int v) { this.maxChildren = v; return this; }
    public Budget maxConcurrent(int v) { this.maxConcurrent = v; return this; }
    public Budget maxDepth(int v) { this.maxDepth = v; return this; }

    /** {@code {...base, ...override}} — override fields win when non-null. */
    static Budget merge(Budget base, Budget override) {
        Budget b = new Budget();
        Budget lo = base == null ? new Budget() : base;
        Budget hi = override == null ? new Budget() : override;
        b.maxTurns = hi.maxTurns != null ? hi.maxTurns : lo.maxTurns;
        b.maxTokens = hi.maxTokens != null ? hi.maxTokens : lo.maxTokens;
        b.maxChildren = hi.maxChildren != null ? hi.maxChildren : lo.maxChildren;
        b.maxConcurrent = hi.maxConcurrent != null ? hi.maxConcurrent : lo.maxConcurrent;
        b.maxDepth = hi.maxDepth != null ? hi.maxDepth : lo.maxDepth;
        return b;
    }
}
