package io.github.muthuishere.toolnexus;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The single uniform tool abstraction. Mirrors opencode's {@code dynamicTool}.
 *
 * <p>{@code inputSchema()} is a JSON-Schema object as a plain {@code Map}
 * (e.g. {@code {type:"object", properties:{...}, required:[...]}}). {@code source()}
 * is one of {@code "mcp" | "skill" | "native" | "http" | "custom"}.
 */
public interface Tool {
    String name();

    String description();

    Map<String, Object> inputSchema();

    String source();

    ToolResult execute(Map<String, Object> args, ToolContext ctx);

    /** Replace anything outside [a-zA-Z0-9_-] with "_" (same as opencode). */
    Pattern SANITIZE = Pattern.compile("[^a-zA-Z0-9_-]");

    static String sanitize(String value) {
        if (value == null) return "";
        return SANITIZE.matcher(value).replaceAll("_");
    }
}
