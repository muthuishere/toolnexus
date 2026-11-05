package io.github.muthuishere.toolnexus.mcp2026;

import java.util.List;
import java.util.Map;

/** The authenticated caller, available to tool handlers via {@link ToolContext#principal()}. */
public record Principal(String subject, List<String> scopes, Map<String, Object> claims) {
    public Principal(String subject, List<String> scopes) { this(subject, scopes, Map.of()); }
}
