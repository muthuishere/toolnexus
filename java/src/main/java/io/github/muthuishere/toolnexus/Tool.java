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
     * JSON Schema of {@link ToolResult#structured()} (SPEC §1). {@code null} (the default) ⇒ omitted
     * everywhere, so the wire stays byte-identical.
     */
    default Map<String, Object> outputSchema() {
        return null;
    }

    /**
     * MCP tool annotations (SPEC §7C.1), advertised verbatim by the MCP handler. {@code null} (the
     * default) ⇒ derived from {@link #hints()}, else omitted.
     */
    default ToolAnnotations annotations() {
        return null;
    }

    /**
     * Whether a call with {@code args} may run alongside other safe calls of the same model
     * response (SPEC §8 "Tool scheduling"). Default: safe only when {@link #hints()} says
     * read-only; a tool without hints is unsafe. Override (or use {@link #withConcurrencySafe})
     * to decide per input.
     */
    default boolean concurrencySafe(Map<String, Object> args) {
        ToolHints h = hints();
        return h != null && h.readOnly();
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
            @Override public boolean concurrencySafe(Map<String, Object> args) { return hints != null && hints.readOnly(); }
            @Override public Map<String, Object> outputSchema() { return tool.outputSchema(); }
            @Override public ToolAnnotations annotations() { return tool.annotations(); }
        };
    }

    /**
     * A view of {@code tool} whose concurrency safety is decided by {@code safe}, per call;
     * everything else (hints included) delegates.
     */
    static Tool withConcurrencySafe(Tool tool, java.util.function.Predicate<Map<String, Object>> safe) {
        return new Tool() {
            @Override public String name() { return tool.name(); }
            @Override public String description() { return tool.description(); }
            @Override public Map<String, Object> inputSchema() { return tool.inputSchema(); }
            @Override public String source() { return tool.source(); }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return tool.execute(args, ctx); }
            @Override public ToolHints hints() { return tool.hints(); }
            @Override public boolean concurrencySafe(Map<String, Object> args) { return safe.test(args); }
            @Override public Map<String, Object> outputSchema() { return tool.outputSchema(); }
            @Override public ToolAnnotations annotations() { return tool.annotations(); }
        };
    }

    /**
     * A view of {@code tool} that advertises {@code outputSchema} (SPEC §1) and {@code annotations}
     * (SPEC §7C.1); either may be {@code null}. Everything else delegates.
     */
    static Tool withMcp(Tool tool, Map<String, Object> outputSchema, ToolAnnotations annotations) {
        return new Tool() {
            @Override public String name() { return tool.name(); }
            @Override public String description() { return tool.description(); }
            @Override public Map<String, Object> inputSchema() { return tool.inputSchema(); }
            @Override public String source() { return tool.source(); }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return tool.execute(args, ctx); }
            @Override public ToolHints hints() { return tool.hints(); }
            @Override public boolean concurrencySafe(Map<String, Object> args) { return tool.concurrencySafe(args); }
            @Override public Map<String, Object> outputSchema() { return outputSchema; }
            @Override public ToolAnnotations annotations() { return annotations; }
        };
    }

    /** A view of {@code tool} that carries MCP {@code annotations} (SPEC §7C.1). */
    static Tool withAnnotations(Tool tool, ToolAnnotations annotations) {
        return withMcp(tool, tool.outputSchema(), annotations);
    }

    /** A view of {@code tool} whose structured result is described by {@code outputSchema} (SPEC §1). */
    static Tool withOutputSchema(Tool tool, Map<String, Object> outputSchema) {
        return withMcp(tool, outputSchema, tool.annotations());
    }

    /** Replace anything outside [a-zA-Z0-9_-] with "_" (same as opencode). */
    Pattern SANITIZE = Pattern.compile("[^a-zA-Z0-9_-]");

    static String sanitize(String value) {
        if (value == null) return "";
        return SANITIZE.matcher(value).replaceAll("_");
    }
}
