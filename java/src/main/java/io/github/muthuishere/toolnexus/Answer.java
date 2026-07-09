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
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Answer(String id, boolean ok, Map<String, Object> data) {

    /** Convenience: an answer with no payload. */
    public Answer(String id, boolean ok) {
        this(id, ok, null);
    }
}
