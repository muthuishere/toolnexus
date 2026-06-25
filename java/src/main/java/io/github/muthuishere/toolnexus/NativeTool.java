package io.github.muthuishere.toolnexus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Programmatic native tool (source {@code "native"}). Wrap a plain function as a
 * uniform {@link Tool}.
 *
 * <p>The function may return a {@link String} (wrapped as output) or a full
 * {@link ToolResult}. A thrown exception becomes an error result.
 */
public final class NativeTool implements Tool {
    private static final Map<String, Object> EMPTY_SCHEMA = emptySchema();

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final String source;
    private final BiFunction<Map<String, Object>, ToolContext, Object> fn;

    private NativeTool(String name, String description, Map<String, Object> inputSchema, String source,
                       BiFunction<Map<String, Object>, ToolContext, Object> fn) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema == null ? EMPTY_SCHEMA : inputSchema;
        this.source = source == null ? "native" : source;
        this.fn = fn;
    }

    /** Build from a function that ignores the context. */
    public static NativeTool of(String name, String description, Map<String, Object> inputSchema,
                                Function<Map<String, Object>, Object> fn) {
        return new NativeTool(name, description, inputSchema, "native", (args, ctx) -> fn.apply(args));
    }

    /** Build from a function that receives the context. */
    public static NativeTool of(String name, String description, Map<String, Object> inputSchema,
                                BiFunction<Map<String, Object>, ToolContext, Object> fn) {
        return new NativeTool(name, description, inputSchema, "native", fn);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        try {
            Object out = fn.apply(args == null ? new LinkedHashMap<>() : args, ctx);
            if (out instanceof ToolResult) {
                return (ToolResult) out;
            }
            if (out instanceof String) {
                return ToolResult.ok((String) out);
            }
            return ToolResult.ok(Json.stringify(out));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
            return ToolResult.error(msg);
        }
    }

    private static Map<String, Object> emptySchema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", new LinkedHashMap<>());
        s.put("additionalProperties", false);
        return s;
    }
}
