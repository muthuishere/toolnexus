package io.github.muthuishere.toolnexus;

import java.util.Map;

/** SPEC §8 "Stop-reason recovery": per-run budgets (corrective 1, continue 3, nudge 1) and the carried text. */
final class StopRecovery {
    /** The three pinned user messages — byte-exact in every port. */
    static final String NUDGE = "Your previous response contained no answer. Continue and give your answer.";
    static final String CONTINUE = "Your previous response was cut off at the output token limit. Continue exactly where it stopped, without repeating anything.";
    static final String CORRECTIVE = "Your previous tool call could not be used: its arguments were not a valid JSON object. Call the tool again with arguments that are a single valid JSON object matching its input schema.";

    private final boolean on;
    private int correct, cont, nudges;
    /** Text of continued truncated responses; reset whenever tools run. */
    String carry = "";
    /** Set by {@link #decide}: whether the assistant message must be dropped from the transcript. */
    boolean dropped;

    StopRecovery(boolean on) { this.on = on; }

    /** Classifies one response (SPEC §8 order); returns the pinned text to append, or null. */
    String decide(boolean lastTurn, boolean hasCalls, boolean malformed, boolean truncated, String text) {
        dropped = false;
        if (!on || lastTurn) return null;
        if (hasCalls && malformed && correct < 1) {
            correct++;
            dropped = true;
            return CORRECTIVE;
        }
        if (!hasCalls && truncated && cont < 3) {
            cont++;
            carry += text;
            return CONTINUE;
        }
        if (!hasCalls && !truncated && text.strip().isEmpty() && nudges < 1) {
            nudges++;
            dropped = true;
            return NUDGE;
        }
        return null;
    }

    /** A raw arguments string that is non-empty (trimmed) and not a JSON object. */
    static boolean malformedArgs(String raw) {
        if (raw == null || raw.isBlank()) return false;
        return !(Json.parseLoose(raw.strip()) instanceof Map);
    }
}
