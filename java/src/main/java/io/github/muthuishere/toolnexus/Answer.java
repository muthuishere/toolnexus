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

    // ---- the Answer payload contract (§10, ADR 0026) ----------------------

    /** The ONE {@code data} key that carries a tool's output back into a run. Named, because
     * {@code {"value": …}} and {@code {"answers": […]}} both look equally plausible and neither
     * is read — the whole of #89. */
    public static final String OUTPUT_KEY = "output";

    /** The multi-call relay shape, recognised BEFORE {@link #OUTPUT_KEY} (A3). */
    public static final String RESULTS_KEY = "results";

    /** Accompanies {@link #OUTPUT_KEY}: whether the host's execution failed. */
    public static final String IS_ERROR_KEY = "isError";

    /**
     * The recognised {@code data} keys, in PRECEDENCE order (A3). An {@code ok=true} answer
     * carrying none of them has no determinable result, and must be an error to the host rather
     * than a fabricated tool result handed to the model.
     */
    public static final java.util.List<String> RECOGNISED_KEYS = java.util.List.of(RESULTS_KEY, OUTPUT_KEY);

    /** True iff this answer carries at least one recognised key — the ONLY case in which a
     * partial relay answer may still be filled in. */
    public boolean hasRecognisedKey() {
        if (data == null) return false;
        for (String k : RECOGNISED_KEYS) if (data.containsKey(k)) return true;
        return false;
    }

    /**
     * The Java spelling of the cross-port {@code AnswerOutput(id, output)} constructor: a
     * satisfied answer whose payload IS the tool output, under the one key that is actually read.
     *
     * @throws IllegalArgumentException if {@code output} is null — an "ok" answer that carries no
     *         determinable result is the failure this constructor exists to make impossible
     */
    public static Answer output(String id, String output) {
        if (output == null) {
            throw new IllegalArgumentException(
                    "toolnexus: Answer.output requires a result; an ok=true answer with no output "
                            + "would fabricate a tool result for the model");
        }
        return new Answer(id, true, Map.of(OUTPUT_KEY, output), null);
    }

    /**
     * The Java spelling of the cross-port {@code AnswerDeclined(id, reason)} constructor: a
     * refusal, with the reason the loop rule does NOT branch on but a host and an MCP
     * elicitation bridge both need ({@code "declined" | "cancelled" | "expired"}).
     */
    public static Answer declined(String id, String reason) {
        return new Answer(id, false, null, reason == null || reason.isEmpty() ? "declined" : reason);
    }

    /**
     * The output this answer carries, or null when it carries none.
     *
     * @throws IllegalArgumentException if {@code data.output} is present but is NOT a string.
     *         It used to degrade to {@code ""} through a silent type assertion, so a host that
     *         handed back a number or an object was told the tool had returned empty.
     */
    public String outputOf() {
        Object raw = data == null ? null : data.get(OUTPUT_KEY);
        if (raw == null) return null;
        if (!(raw instanceof String str)) {
            throw new IllegalArgumentException("toolnexus: Answer.data.\"" + OUTPUT_KEY
                    + "\" must be a string, got " + raw.getClass().getName());
        }
        return str;
    }
}
