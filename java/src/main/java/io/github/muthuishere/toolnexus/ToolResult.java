package io.github.muthuishere.toolnexus;

import java.util.Map;

/**
 * The uniform result of executing a {@link Tool}.
 *
 * <ul>
 *   <li>{@code output} — text handed back to the model.</li>
 *   <li>{@code isError} — whether the call failed.</li>
 *   <li>{@code metadata} — free-form (title, server, skill name, ...); may be null.</li>
 * </ul>
 */
public final class ToolResult {
    private final String output;
    private final boolean isError;
    private final Map<String, Object> metadata;

    public ToolResult(String output, boolean isError, Map<String, Object> metadata) {
        this.output = output == null ? "" : output;
        this.isError = isError;
        this.metadata = metadata;
    }

    public String output() {
        return output;
    }

    public boolean isError() {
        return isError;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public static ToolResult ok(String output) {
        return new ToolResult(output, false, null);
    }

    public static ToolResult ok(String output, Map<String, Object> metadata) {
        return new ToolResult(output, false, metadata);
    }

    public static ToolResult error(String output) {
        return new ToolResult(output, true, null);
    }

    public static ToolResult error(String output, Map<String, Object> metadata) {
        return new ToolResult(output, true, metadata);
    }

    @Override
    public String toString() {
        return "ToolResult{isError=" + isError + ", output=" + output + "}";
    }
}
