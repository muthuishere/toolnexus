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
            n += (int) Math.ceil(Json.stringify(redactParts(m)).length() / 4.0);
            n += partTokens(m);
        }
        return n;
    }

    /**
     * §1B: a part is charged a <b>byte-derived</b> estimate ({@code bytes/750}, floored at
     * {@link #MIN_PART_TOKENS}) — never the length of its {@code mimeType} string, which would
     * score a 5 MB image at ~3 tokens and make it uncompactable. Its base64 is excluded from the
     * character estimate ({@link #redactParts}) so it is not charged twice.
     */
    public static int estimatePartTokens(ContentPart part) {
        if (part == null || ContentPart.TEXT.equals(part.type())) return 0;
        return (int) Math.max(MIN_PART_TOKENS, part.bytes() / 750);
    }

    /** Floor for a non-text part, so a URL-only part is not free either. */
    private static final int MIN_PART_TOKENS = 85;

    private static int partTokens(Object message) {
        int n = 0;
        for (ContentPart p : partsOf(message)) n += estimatePartTokens(p);
        return n;
    }

    private static List<ContentPart> partsOf(Object message) {
        List<ContentPart> out = new ArrayList<>();
        if (message instanceof ContentPart p) {
            out.add(p);
        } else if (message instanceof List<?> list) {
            for (Object o : list) out.addAll(partsOf(o));
        } else if (message instanceof Map<?, ?> m) {
            for (Object v : m.values()) out.addAll(partsOf(v));
        }
        return out;
    }

    /**
     * A copy of {@code message} with every {@link ContentPart}'s base64 replaced by its
     * descriptor — parts are charged by {@link #estimatePartTokens}, and a part's {@code data}
     * never reaches a log line or an estimate string. A transcript holding no parts is untouched,
     * so the estimate is byte-identical to a pre-multimodal port.
     */
    private static Object redactParts(Object message) {
        if (message instanceof ContentPart p) return p.describe();
        if (message instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(redactParts(o));
            return out;
        }
        if (message instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(e.getKey(), redactParts(e.getValue()));
            return out;
        }
        return message;
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
            // A boundary is a user turn that does NOT carry a tool result: under the anthropic
            // dialect tool results ride inside a `user` message, so splitting there would orphan
            // them from the assistant `tool_use` about to be summarized away.
            int split = msgs.size();
            for (int i = msgs.size() - 1; i > head0; i--) {
                List<Object> tailFromI = msgs.subList(i, msgs.size());
                if (isBoundary(msgs.get(i)) && count.apply(tailFromI) <= keepTail) {
                    split = i;
                }
                if (count.apply(tailFromI) > keepTail) {
                    break;
                }
            }
            // If no clean boundary fit, fall back to the most recent one so we still never split
            // a tool group (may keep more than keepTail — safety over size).
            if (split == msgs.size()) {
                for (int i = msgs.size() - 1; i > head0; i--) {
                    if (isBoundary(msgs.get(i))) {
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
    /**
     * A valid tail boundary: a {@code user} turn that is not carrying a tool result. Under the
     * anthropic dialect a tool result is a {@code user} message whose content holds
     * {@code tool_result} blocks, making it a tool-group member rather than a boundary.
     */
    private static boolean isBoundary(Object message) {
        return "user".equals(roleOf(message)) && !carriesToolResult(message);
    }

    @SuppressWarnings("unchecked")
    private static boolean carriesToolResult(Object message) {
        if (!(message instanceof Map<?, ?> m)) {
            return false;
        }
        Object content = ((Map<String, Object>) m).get("content");
        if (!(content instanceof List<?> blocks)) {
            return false;
        }
        for (Object b : blocks) {
            if (b instanceof Map<?, ?> bm && "tool_result".equals(((Map<String, Object>) bm).get("type"))) {
                return true;
            }
        }
        return false;
    }

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
