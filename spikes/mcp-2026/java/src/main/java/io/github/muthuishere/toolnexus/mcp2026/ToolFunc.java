package io.github.muthuishere.toolnexus.mcp2026;

import java.util.Map;

/**
 * Handles one tool call. Return a String (text result), a Map with "content" (a full CallToolResult),
 * or any JSON value (structured). A thrown exception becomes an isError result.
 */
@FunctionalInterface
public interface ToolFunc {
    Object call(ToolContext ctx, Map<String, Object> args) throws Exception;
}
