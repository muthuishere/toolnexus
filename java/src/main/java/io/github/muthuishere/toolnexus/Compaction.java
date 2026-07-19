package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Context compaction (SPEC §7F) — a pure {@code messages → messages} helper wired through the
 * existing §8 {@code beforeLLM} seam, adding <em>no</em> loop behavior. A long-lived agent grows
 * its transcript until it overflows the model window; {@link #compactor(Options)} returns a
 * {@code beforeLLM} hook that summarizes the older body and keeps a recent tail.
 *
 * <p>Below {@code maxTokens} it is a byte-identical <b>no-op</b> (returns {@code null}). Above it,
 * the compacted transcript is {@code [leading system prompt (verbatim), summary system message,
 * (flush reminder?), …tail]}, holding two invariants:
 * <ul>
 *   <li><b>Tool-pair safety.</b> The retained tail begins at a {@code user} turn — chosen as the
 *       largest user-boundary tail that fits {@code keepTail}, falling back to the most recent
 *       user turn (safety over size) — so no {@code tool} message is ever orphaned from the
 *       {@code assistant} carrying its {@code tool_call_id}.</li>
 *   <li><b>System prompt preserved.</b> A leading {@code system} message (identity / soul / skills)
 *       is kept unchanged; only the body between it and the tail is summarized.</li>
 * </ul>
 *
 * <p>Compaction is entirely opt-in: absent a compactor, a run is byte-identical to today.
 */
public final class Compaction {

    private Compaction() {}

    /**
     * Cheap, deterministic token estimate: {@code ceil(chars/4)} of each message's JSON
     * serialization, summed over the transcript. This is an <em>estimator, not a tokenizer</em> —
     * exactness is the host's call (override via {@link Options#countTokens}). Mirrors the JS
     * spike ({@code sum ceil(JSON-length(m)/4)}).
     */
    public static int estimateTokens(List<Object> messages) {
        int n = 0;
        for (Object m : messages) {
            n += (int) Math.ceil(Json.stringify(m).length() / 4.0);
        }
        return n;
    }

    /** Options for {@link #compactor(Options)}. Fluent setters; {@link #summarize} is required. */
    public static final class Options {
        /** Compact only when the transcript estimate exceeds this many tokens. */
        public int maxTokens;
        /** Keep at least this many tokens of the most recent tail. Default {@code maxTokens/2}. */
        public Integer keepTail;
        /** Produce a summary of the older messages. MAY call an LLM. Required. */
        public Function<List<Object>, String> summarize;
        /** Token estimator; default {@link #estimateTokens}. */
        public Function<List<Object>, Integer> countTokens;
        /**
         * When set, inject a pre-compact system reminder to persist durable facts via the §7E
         * {@code memory} tool before the head is summarized. Off by default.
         */
        public boolean flushToMemory;

        public Options maxTokens(int v) { this.maxTokens = v; return this; }
        public Options keepTail(Integer v) { this.keepTail = v; return this; }
        public Options summarize(Function<List<Object>, String> v) { this.summarize = v; return this; }
        public Options countTokens(Function<List<Object>, Integer> v) { this.countTokens = v; return this; }
        public Options flushToMemory(boolean v) { this.flushToMemory = v; return this; }
    }

    /**
     * Build a {@code beforeLLM} hook that compacts the transcript when it grows past
     * {@code maxTokens}. The returned function yields a {@link LlmClient.LLMOverride} carrying the
     * compacted messages only when it actually compacts; otherwise {@code null} (no-op).
     */
    public static Function<LlmClient.BeforeLLMEvent, LlmClient.LLMOverride> compactor(Options opts) {
        if (opts.summarize == null) {
            throw new IllegalArgumentException("compactor: summarize is required");
        }
        final Function<List<Object>, Integer> count =
                opts.countTokens != null ? opts.countTokens : Compaction::estimateTokens;
        final int keepTail = opts.keepTail != null ? opts.keepTail : opts.maxTokens / 2;

        return ev -> {
            List<Object> msgs = ev.messages();
            if (count.apply(msgs) <= opts.maxTokens) {
                return null; // under budget → byte-identical no-op
            }

            // Preserve a leading system prompt verbatim (identity/soul/skills — never summarized).
            int head0 = (!msgs.isEmpty() && "system".equals(roleOf(msgs.get(0)))) ? 1 : 0;
            List<Object> system = new ArrayList<>(msgs.subList(0, head0));

            // Find the split: the LARGEST tail (from a clean USER boundary) that fits keepTail.
            // Scanning to a user turn guarantees tool-pair safety — a tail starting at a user
            // message can never orphan a `tool` result from its `tool_calls`.
            int split = msgs.size();
            for (int i = msgs.size() - 1; i > head0; i--) {
                List<Object> tailFromI = msgs.subList(i, msgs.size());
                if ("user".equals(roleOf(msgs.get(i))) && count.apply(tailFromI) <= keepTail) {
                    split = i;
                }
                if (count.apply(tailFromI) > keepTail) {
                    break;
                }
            }
            // If no clean user boundary fit, fall back to the most recent user turn so we still
            // never split a tool group (may keep more than keepTail — safety over size).
            if (split == msgs.size()) {
                for (int i = msgs.size() - 1; i > head0; i--) {
                    if ("user".equals(roleOf(msgs.get(i)))) {
                        split = i;
                        break;
                    }
                }
            }
            if (split <= head0) {
                return null; // nothing safely compactible
            }

            List<Object> older = new ArrayList<>(msgs.subList(head0, split));
            List<Object> tail = new ArrayList<>(msgs.subList(split, msgs.size()));
            if (older.isEmpty()) {
                return null;
            }

            String summary = opts.summarize.apply(older);

            List<Object> result = new ArrayList<>(system);
            result.add(systemMessage("[Summary of earlier conversation]\n" + summary));
            if (opts.flushToMemory) {
                result.add(systemMessage("Before continuing: if anything from earlier is worth"
                        + " keeping, save it with the memory tool now — the earlier transcript is"
                        + " about to be summarized."));
            }
            result.addAll(tail);
            return new LlmClient.LLMOverride(result, null);
        };
    }

    @SuppressWarnings("unchecked")
    private static String roleOf(Object message) {
        if (message instanceof Map<?, ?> m) {
            Object role = ((Map<String, Object>) m).get("role");
            return role == null ? null : String.valueOf(role);
        }
        return null;
    }

    private static Map<String, Object> systemMessage(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "system");
        m.put("content", content);
        return m;
    }
}
