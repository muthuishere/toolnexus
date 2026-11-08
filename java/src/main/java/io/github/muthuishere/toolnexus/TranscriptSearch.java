package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code transcriptSearchTool} — a read-only search over a conversation store (SPEC §7E
 * "Memory hierarchy" → transcript search). The ground-truth tier of agent memory: a dreaming
 * persona checks its notes against what actually happened, not against its own notes.
 */
public final class TranscriptSearch {

    private TranscriptSearch() {}

    /** The tool description (fixture-pinned). */
    public static final String DESCRIPTION =
            "Search past conversation transcripts — the ground truth of what actually happened. "
                    + "Case-insensitive substring match over message text. Returns one line per matching "
                    + "message: <conversationId>#<index> <role>: <snippet>. Read-only.";

    /** Options: most hits per call (and the {@code limit} ceiling), snippet length in code
     * points, tool name. */
    public record Options(int maxResults, int snippetChars, String name) {
        public static Options defaults() { return new Options(20, 200, "transcript_search"); }
        public Options maxResults(int v) { return new Options(v, snippetChars, name); }
        public Options snippetChars(int v) { return new Options(maxResults, v, name); }
        public Options name(String v) { return new Options(maxResults, snippetChars, v); }
    }

    /** {@link #transcriptSearchTool(LlmClient.ConversationStore, Options)} with the defaults. */
    public static Tool transcriptSearchTool(LlmClient.ConversationStore store) {
        return transcriptSearchTool(store, Options.defaults());
    }

    /**
     * A read-only {@link Tool} over {@code store}. Construction throws unless the store can
     * {@code list()} ({@link LlmClient.ListableConversationStore}). Input
     * {@code {query, conversationId?, limit?}}; output one line per hit
     * ({@code <conversationId>#<index> <role>: <snippet>}), {@code no matches} when none, and a
     * trailing {@code (+<k> more)} when hits exceed the limit. Never writes.
     */
    public static Tool transcriptSearchTool(LlmClient.ConversationStore store, Options opts) {
        if (!(store instanceof LlmClient.ListableConversationStore listable)) {
            throw new IllegalArgumentException("transcriptSearchTool: the store must implement list()");
        }
        Options o = opts != null ? opts : Options.defaults();
        if (o.maxResults() < 1) throw new IllegalArgumentException("transcriptSearchTool: maxResults must be an integer >= 1");
        if (o.snippetChars() < 1) throw new IllegalArgumentException("transcriptSearchTool: snippetChars must be an integer >= 1");
        int maxResults = o.maxResults();
        int snippetChars = o.snippetChars();
        String name = o.name() != null ? o.name() : "transcript_search";

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", schemaProp("string", "Text to find (case-insensitive substring)."));
        props.put("conversationId", schemaProp("string", "Search only this conversation."));
        props.put("limit", schemaProp("integer", "Most hits to return (default and max " + maxResults + ")."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("query"));

        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return DESCRIPTION; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                Object qRaw = args.get("query");
                String query = qRaw instanceof String s ? normalize(s) : "";
                if (query.isEmpty()) return ToolResult.error("query is required");
                int limit = maxResults;
                Object lv = args.get("limit");
                if (lv != null) {
                    if (!(lv instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                            || n.doubleValue() < 1) {
                        return ToolResult.error("limit must be an integer >= 1");
                    }
                    limit = (int) Math.min(n.doubleValue(), maxResults);
                }
                List<String> ids = new ArrayList<>();
                if (args.get("conversationId") instanceof String cid) ids.add(cid);
                else for (LlmClient.ConversationInfo e : listable.list()) ids.add(e.id());
                int[] q = fold(query).codePoints().toArray();
                List<String> hits = new ArrayList<>();
                int total = 0;
                for (String id : ids) {
                    List<Object> msgs = listable.get(id);
                    if (msgs == null) continue;
                    for (int index = 0; index < msgs.size(); index++) {
                        Object msg = msgs.get(index);
                        int[] cps = messageText(msg).codePoints().toArray();
                        int at = indexOf(foldCps(cps), q);
                        if (at < 0) continue;
                        total++;
                        if (hits.size() < limit) {
                            String role = msg instanceof Map<?, ?> m && m.get("role") instanceof String r ? r : "unknown";
                            hits.add(id + "#" + index + " " + role + ": " + snippet(cps, at, q.length, snippetChars));
                        }
                    }
                }
                if (total == 0) return ToolResult.ok("no matches");
                if (total > hits.size()) hits.add("(+" + (total - hits.size()) + " more)");
                return ToolResult.ok(String.join("\n", hits));
            }
        };
    }

    private static Map<String, Object> schemaProp(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private static boolean isAsciiSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    /** Collapse every run of ASCII whitespace to one space and trim (§7E). */
    static String normalize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean inWs = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAsciiSpace(c)) {
                if (!inWs) b.append(' ');
                inWs = true;
            } else {
                b.append(c);
                inWs = false;
            }
        }
        int start = b.length() > 0 && b.charAt(0) == ' ' ? 1 : 0;
        int end = b.length() > start && b.charAt(b.length() - 1) == ' ' ? b.length() - 1 : b.length();
        return b.substring(start, end);
    }

    /** ASCII-only case fold — never Unicode lowercasing (it changes lengths, differs per port). */
    private static String fold(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return b.toString();
    }

    private static int[] foldCps(int[] cps) {
        int[] out = cps.clone();
        for (int i = 0; i < out.length; i++) if (out[i] >= 'A' && out[i] <= 'Z') out[i] += 32;
        return out;
    }

    private static void partsText(Object content, List<String> out) {
        if (content instanceof String s) {
            out.add(s);
            return;
        }
        if (!(content instanceof List<?> parts)) return;
        for (Object p : parts) {
            if (!(p instanceof Map<?, ?> part)) continue;
            Object type = part.get("type");
            if ("text".equals(type) && part.get("text") instanceof String t) out.add(t);
            else if ("tool_use".equals(type)) {
                Object input = part.get("input");
                out.add(Json.stringify(input != null ? input : Map.of()));
            } else if ("tool_result".equals(type)) partsText(part.get("content"), out);
        }
    }

    /** The searchable text of one stored message (§7E): string content, text parts, tool_use
     * input as compact JSON, tool_result content, then each OpenAI tool call's {@code arguments}
     * string — pieces joined by "\n", then whitespace-normalized. Images are not text. */
    public static String messageText(Object msg) {
        if (!(msg instanceof Map<?, ?> m)) return "";
        List<String> pieces = new ArrayList<>();
        partsText(m.get("content"), pieces);
        if (m.get("tool_calls") instanceof List<?> calls) {
            for (Object tc : calls) {
                if (tc instanceof Map<?, ?> call && call.get("function") instanceof Map<?, ?> fn
                        && fn.get("arguments") instanceof String a) {
                    pieces.add(a);
                }
            }
        }
        return normalize(String.join("\n", pieces));
    }

    private static int indexOf(int[] hay, int[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Snippet of {@code size} code points centred on the match; {@code …} marks elision. */
    private static String snippet(int[] cps, int at, int qlen, int size) {
        if (cps.length <= size) return new String(cps, 0, cps.length);
        int start = at + qlen / 2 - size / 2;
        start = Math.max(0, Math.min(start, cps.length - size));
        int end = start + size;
        return (start > 0 ? "…" : "") + new String(cps, start, size) + (end < cps.length ? "…" : "");
    }
}
