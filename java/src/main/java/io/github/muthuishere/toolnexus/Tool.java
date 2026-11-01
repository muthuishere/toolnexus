package io.github.muthuishere.toolnexus;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The single uniform tool abstraction. Mirrors opencode's {@code dynamicTool}.
 *
 * <p>{@code inputSchema()} is a JSON-Schema object as a plain {@code Map}
 * (e.g. {@code {type:"object", properties:{...}, required:[...]}}). {@code source()}
 * is one of {@code "mcp" | "skill" | "builtin" | "a2a" | "native" | "http" | "custom"}.
 */
public interface Tool {
    String name();

    String description();

    Map<String, Object> inputSchema();

    String source();

    ToolResult execute(Map<String, Object> args, ToolContext ctx);

    /**
     * Advisory behaviour hints (SPEC §4C), passed to {@code Hooks.beforeTool} as
     * {@code BeforeToolEvent.hints}. {@code null} (the default) = unknown; treat as mutating.
     */
    default ToolHints hints() {
        return null;
    }

    /**
     * A view of {@code tool} that reports {@code hints}; everything else delegates. Use it to tag
     * a native/custom tool for a permission gate.
     */
    static Tool withHints(Tool tool, ToolHints hints) {
        return new Tool() {
            @Override public String name() { return tool.name(); }
            @Override public String description() { return tool.description(); }
            @Override public Map<String, Object> inputSchema() { return tool.inputSchema(); }
            @Override public String source() { return tool.source(); }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return tool.execute(args, ctx); }
            @Override public ToolHints hints() { return hints; }
        };
    }

    /** Replace anything outside [a-zA-Z0-9_-] with "_" (same as opencode). */
    Pattern SANITIZE = Pattern.compile("[^a-zA-Z0-9_-]");

    static String sanitize(String value) {
        if (value == null) return "";
        return SANITIZE.matcher(value).replaceAll("_");
    }
}
