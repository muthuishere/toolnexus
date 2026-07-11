package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * §10 Suspension — byte-identical wire data. The resolution of a {@link Request}, produced
 * by the host's {@code waitFor}. Keys pinned EXACTLY as {@code id, ok, data} across all ports.
 *
 * <ul>
 *   <li>{@code id} — echoes {@link Request#id()}.</li>
 *   <li>{@code ok} — satisfied, vs declined / aborted / expired.</li>
 *   <li>{@code data} — kind-specific payload, e.g. the value entered (optional).</li>
 *   <li>{@code reason} — when {@code ok} is false, why (advisory — the loop rule branches only on
 *       {@code ok}). {@code "declined" | "cancelled" | "expired"}; {@code null} when ok/unspecified.
 *       Distinguishes an explicit refusal from a dismissal/timeout; the MCP elicitation bridge maps
 *       {@code declined}→{@code decline} and everything else→{@code cancel}. (R1)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Answer(String id, boolean ok, Map<String, Object> data, String reason) {

    /** Convenience: an answer with a payload but no reason. */
    public Answer(String id, boolean ok, Map<String, Object> data) {
        this(id, ok, data, null);
    }

    /** Convenience: an answer with no payload. */
    public Answer(String id, boolean ok) {
        this(id, ok, null, null);
    }
}
