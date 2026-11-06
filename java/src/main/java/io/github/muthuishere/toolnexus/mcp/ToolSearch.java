package io.github.muthuishere.toolnexus.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.muthuishere.toolnexus.mcp.Mcp.*;

/**
 * Lazy tool search: the model sees search_tools(query, limit?) -> ranked {name, description, inputSchema, server}
 * and call_tool(name, arguments) instead of all N tools. Ranking is BM25 over name (x2) + description.
 */
final class ToolSearch {
    private ToolSearch() {}

    static List<Map<String, Object>> metaTools(String description) {
        String about = description == null || description.isEmpty() ? "this MCP server" : description;
        return List.of(
                obj("name", "search_tools", "description", "Search the tools of " + about
                                + " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.",
                        "inputSchema", obj("type", "object", "properties", obj("query", obj("type", "string"), "limit", obj("type", "integer")),
                                "required", List.of("query"))),
                obj("name", "call_tool", "description", "Run a tool found with search_tools.",
                        "inputSchema", obj("type", "object", "properties", obj("name", obj("type", "string"), "arguments", obj("type", "object")),
                                "required", List.of("name"))));
    }

    static List<Map<String, Object>> rank(List<Map<String, Object>> tools, String query, int limit, String server) {
        List<List<String>> docs = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        int total = 0;
        for (Map<String, Object> t : tools) {
            List<String> nt = tokens(str(t.get("name")));
            List<String> d = new ArrayList<>(nt);
            d.addAll(nt);
            d.addAll(tokens(str(t.get("description"))));
            docs.add(d);
            total += d.size();
            for (String w : new HashSet<>(d)) df.merge(w, 1, Integer::sum);
        }
        double n = tools.size(), avg = total / Math.max(1, n);
        record Hit(int i, double score) {}
        List<Hit> hits = new ArrayList<>();
        List<String> q = tokens(query);
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Integer> tf = new HashMap<>();
            for (String w : docs.get(i)) tf.merge(w, 1, Integer::sum);
            double s = 0;
            for (String w : q) {
                double f = tf.getOrDefault(w, 0);
                if (f == 0) continue;
                double dfw = df.get(w);
                double idf = Math.log(1 + (n - dfw + 0.5) / (dfw + 0.5));
                s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * docs.get(i).size() / avg));
            }
            if (s > 0) hits.add(new Hit(i, s));
        }
        hits.sort((a, b) -> Double.compare(b.score, a.score)); // stable
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hit h : hits.subList(0, Math.min(limit, hits.size()))) {
            Map<String, Object> t = tools.get(h.i);
            out.add(obj("name", t.get("name"), "description", t.get("description"), "inputSchema", t.get("inputSchema"), "server", server));
        }
        return out;
    }

    /** Lowercases and splits on non-alphanumerics and camelCase boundaries. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean prevLower = false;
        for (int cp : s.codePoints().toArray()) {
            if (Character.isUpperCase(cp) && prevLower) {
                flush(out, cur);
                cur.appendCodePoint(cp);
            } else if (Character.isLetter(cp) || Character.isDigit(cp)) {
                cur.appendCodePoint(cp);
            } else {
                flush(out, cur);
            }
            prevLower = Character.isLowerCase(cp);
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        if (!cur.isEmpty()) {
            out.add(cur.toString().toLowerCase(Locale.ROOT));
            cur.setLength(0);
        }
    }

}
