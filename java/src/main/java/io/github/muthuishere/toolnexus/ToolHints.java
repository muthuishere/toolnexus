package io.github.muthuishere.toolnexus;

/**
 * Advisory behaviour hints for a {@link Tool} (named after MCP tool annotations), so a host
 * permission gate built on {@code Hooks.beforeTool} can treat read-only tools differently (e.g.
 * allow them in a plan mode). SPEC §4C.
 *
 * <p>{@code null} hints mean unknown: treat the tool as mutating. Builtins always carry hints; MCP
 * tools carry them when the server sends annotations. Hints are advisory (a server can mislabel
 * its tools) and are never serialized into any adapter or wire output.
 *
 * @param readOnly    the tool does not change its environment
 * @param destructive the tool may overwrite or delete things (meaningful when not read-only)
 * @param openWorld   the tool reaches outside the host (network, external systems)
 */
public record ToolHints(boolean readOnly, boolean destructive, boolean openWorld) {
    /** Read-only, closed-world. */
    public static final ToolHints READ_ONLY = new ToolHints(true, false, false);
    /** Mutating and destructive, closed-world. */
    public static final ToolHints DESTRUCTIVE = new ToolHints(false, true, false);
}
