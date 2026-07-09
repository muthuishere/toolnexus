package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * §10 Suspension — byte-identical wire data. A tool that needs an out-of-band, async
 * resolution (login, approval, input) returns a {@link ToolResult} carrying
 * {@code metadata.pending = Request}. The keys are pinned EXACTLY as
 * {@code id, kind, prompt, url, data, expiresAt} across all five ports (they serialize
 * over the wire and cross agent boundaries) — NOT idiomatic-cased like {@code RunResult}.
 *
 * <ul>
 *   <li>{@code id} — unique per suspension; the correlation key.</li>
 *   <li>{@code kind} — {@code "authorization" | "approval" | "input" | ...} (open vocabulary).</li>
 *   <li>{@code prompt} — what is being asked, in human words.</li>
 *   <li>{@code url} — present when the action happens at a link (optional).</li>
 *   <li>{@code data} — kind-specific extra, e.g. choices to pick from (optional).</li>
 *   <li>{@code expiresAt} — RFC3339; the request is stale after this (optional).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Request(String id, String kind, String prompt, String url,
                      Map<String, Object> data, String expiresAt) {

    /** Convenience: a request with only the required fields. */
    public Request(String id, String kind, String prompt) {
        this(id, kind, prompt, null, null, null);
    }

    /** Return a copy with a fresh {@code id} (used when a producer omits one). */
    public Request withId(String newId) {
        return new Request(newId, kind, prompt, url, data, expiresAt);
    }
}
