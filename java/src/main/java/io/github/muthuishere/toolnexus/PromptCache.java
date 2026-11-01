package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt caching (change {@code add-prompt-cache}, SPEC "Prompt caching"). Set
 * {@link LlmClient.Options#promptCache} to ask the provider to cache the stable request prefix.
 * Absent ⇒ the request body is byte-identical. Applied before {@code requestParams} and
 * {@code bodyTransform}, so both can still override it.
 */
public final class PromptCache {
    /** Groups requests that share a prefix (one per session). Sent as openai
     * {@code prompt_cache_key}. Ignored by anthropic, which caches by prefix. */
    public String key;
    /** Ask for the provider's longer cache lifetime where one exists (anthropic {@code ttl:"1h"}). */
    public boolean longTTL;

    public PromptCache key(String v) { this.key = v; return this; }
    public PromptCache longTTL(boolean v) { this.longTTL = v; return this; }

    /**
     * Mark the cacheable prefix in an assembled body, in place. The transcript's own maps are
     * never mutated: every touched entry is copied.
     * <ul>
     *   <li>anthropic: {@code cache_control} breakpoints on the last tool, the system prompt and
     *       the last content block of the latest message (3 of the 4 allowed).</li>
     *   <li>openai: {@code prompt_cache_key = key} when set; the prefix is cached automatically.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static void apply(Map<String, Object> body, String style, PromptCache o) {
        if ("anthropic".equals(style)) {
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("type", "ephemeral");
            if (o.longTTL) mark.put("ttl", "1h");
            if (body.get("tools") instanceof List<?> tools && !tools.isEmpty()) {
                List<Object> out = new ArrayList<>(tools);
                out.set(out.size() - 1, withCacheControl(out.get(out.size() - 1), mark));
                body.put("tools", out);
            }
            Object sys = body.get("system");
            if (sys instanceof String s && !s.isEmpty()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "text");
                block.put("text", s);
                block.put("cache_control", mark);
                body.put("system", new ArrayList<>(List.of(block)));
            } else if (sys instanceof List<?> l && !l.isEmpty()) {
                List<Object> out = new ArrayList<>(l);
                out.set(out.size() - 1, withCacheControl(out.get(out.size() - 1), mark));
                body.put("system", out);
            }
            if (body.get("messages") instanceof List<?> msgs && !msgs.isEmpty()) {
                List<Object> out = new ArrayList<>(msgs);
                if (out.get(out.size() - 1) instanceof Map<?, ?> last) {
                    Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) last);
                    Object content = m.get("content");
                    if (content instanceof String c) {
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "text");
                        block.put("text", c);
                        block.put("cache_control", mark);
                        m.put("content", new ArrayList<>(List.of(block)));
                    } else if (content instanceof List<?> blocks && !blocks.isEmpty()) {
                        List<Object> nb = new ArrayList<>(blocks);
                        nb.set(nb.size() - 1, withCacheControl(nb.get(nb.size() - 1), mark));
                        m.put("content", nb);
                    }
                    out.set(out.size() - 1, m);
                }
                body.put("messages", out);
            }
        } else if (o.key != null && !o.key.isEmpty()) {
            body.put("prompt_cache_key", o.key);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object withCacheControl(Object v, Map<String, Object> mark) {
        if (!(v instanceof Map<?, ?> m)) return v;
        Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) m);
        c.put("cache_control", mark);
        return c;
    }
}
