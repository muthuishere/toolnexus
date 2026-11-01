package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Tool tiers (change {@code add-prompt-cache}, phase 2; SPEC "Tool tiers"). With
 * {@link LlmClient.Options#toolTiers} set, only CORE tools are sent with full schemas. LISTED
 * tools appear as {@code "- name — one line"} and DEFERRED tools by name only, both inside the
 * description of a {@code tool_search} tool that returns full schemas on demand — as a tool
 * result, so the tools array never changes mid-session and the cached prefix survives. A loaded
 * tool is called directly by name, or through {@code call_tool {name, arguments}}; the loop
 * unwraps {@code call_tool} BEFORE hooks, so gates and hints always see the real tool.
 */
public final class ToolTiers {
    /** Always sent with their full schema. */
    public List<String> core = List.of();
    /** Named only. Every other tool is listed. A tool registered after the plan froze is
     * deferred and found by search. */
    public List<String> deferred = List.of();
    /** Caps the tokens sent up front (core schemas + tool_search + call_tool). 0 ⇒ 5% of
     * {@link #contextWindow}; both 0 ⇒ no budget. */
    public int budgetTokens;
    public int contextWindow;
    /** The host's per-tool usage stat (higher = more used), read only when the plan is built.
     * {@code null} ⇒ all zero (ties break by larger schema first, then name). */
    public ToIntFunction<String> usageCount;
    /** Estimates the tokens of a JSON-encodable value; default {@code ceil(bytes/4)}. */
    public Function<Object, Integer> countTokens;

    public ToolTiers core(List<String> v) { this.core = v; return this; }
    public ToolTiers core(String... v) { this.core = List.of(v); return this; }
    public ToolTiers deferred(List<String> v) { this.deferred = v; return this; }
    public ToolTiers deferred(String... v) { this.deferred = List.of(v); return this; }
    public ToolTiers budgetTokens(int v) { this.budgetTokens = v; return this; }
    public ToolTiers contextWindow(int v) { this.contextWindow = v; return this; }
    public ToolTiers usageCount(ToIntFunction<String> v) { this.usageCount = v; return this; }
    public ToolTiers countTokens(Function<Object, Integer> v) { this.countTokens = v; return this; }

    /** Where a tool sits in a plan. */
    public enum Tier {
        CORE("core"), LISTED("listed"), DEFERRED("deferred");
        private final String wire;
        Tier(String wire) { this.wire = wire; }
        @Override public String toString() { return wire; }
    }

    // ------------------------------------------------------------------
    // the frozen plan for one toolkit
    // ------------------------------------------------------------------

    static final class Plan {
        final Map<String, Tier> tier;
        List<String> core = List.of();
        List<String> listed = List.of();
        List<String> deferred = List.of();
        Tool search;
        Tool call;

        Plan(Map<String, Tier> tier) { this.tier = tier; }

        void rebuild(Map<String, Tool> byName) {
            List<String> c = new ArrayList<>(), l = new ArrayList<>(), d = new ArrayList<>();
            for (Map.Entry<String, Tier> e : tier.entrySet()) {
                switch (e.getValue()) {
                    case CORE -> c.add(e.getKey());
                    case LISTED -> l.add(e.getKey());
                    default -> d.add(e.getKey());
                }
            }
            Collections.sort(c);
            Collections.sort(l);
            Collections.sort(d);
            core = c;
            listed = l;
            deferred = d;
            StringBuilder sb = new StringBuilder("Load the full schema of tools that are not shown in full, then call them by name "
                    + "(or with call_tool). Query by keywords, or \"select:a,b\" for exact names.");
            if (!l.isEmpty()) {
                sb.append("\n\nAvailable tools:");
                for (String n : l) {
                    sb.append("\n- ").append(n);
                    String desc = firstLine(byName.get(n).description());
                    if (!desc.isEmpty()) sb.append(" — ").append(desc);
                }
            }
            if (!d.isEmpty()) sb.append("\n\nMore tools (name only): ").append(String.join(", ", d));
            Map<String, Object> sprops = new LinkedHashMap<>();
            sprops.put("query", Map.of("type", "string", "description", "Keywords, or select:name1,name2"));
            sprops.put("limit", Map.of("type", "number", "description", "Maximum results for a keyword query (default 5)"));
            search = meta("tool_search", sb.toString(), schema(sprops, List.of("query")), ToolHints.READ_ONLY);
            Map<String, Object> cprops = new LinkedHashMap<>();
            cprops.put("name", Map.of("type", "string"));
            cprops.put("arguments", Map.of("type", "object"));
            call = meta("call_tool", "Call a tool loaded with tool_search, by name, with its arguments.",
                    schema(cprops, List.of("name")), null);
        }
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    /** A meta tool. It is never executed through the toolkit: the loop intercepts or unwraps it. */
    private static Tool meta(String name, String description, Map<String, Object> schema, ToolHints hints) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public String source() { return "builtin"; }
            @Override public ToolHints hints() { return hints; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return ToolResult.error(name + ": handled by the client loop");
            }
        };
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        if (i >= 0) s = s.substring(0, i);
        // Go truncates at 120 BYTES; for ASCII descriptions that is 120 chars.
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 120) {
            s = new String(b, 0, 120, StandardCharsets.UTF_8);
            if (s.endsWith("�")) s = s.substring(0, s.length() - 1);
            s = s + "…";
        }
        return s;
    }

    static Map<String, Object> toolDef(Tool t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("description", t.description());
        m.put("inputSchema", t.inputSchema());
        return m;
    }

    static int defaultCountTokens(Object v) {
        return (goJson(v).getBytes(StandardCharsets.UTF_8).length + 3) / 4;
    }

    static Plan buildPlan(ToolTiers o, List<Tool> tools) {
        Function<Object, Integer> count = o.countTokens != null ? o.countTokens : ToolTiers::defaultCountTokens;
        ToIntFunction<String> usage = n -> o.usageCount == null ? 0 : o.usageCount.applyAsInt(n);
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool t : tools) byName.put(t.name(), t);
        Map<String, Tier> tier = new LinkedHashMap<>();
        for (Tool t : tools) tier.put(t.name(), Tier.LISTED);
        for (String n : o.deferred == null ? List.<String>of() : o.deferred) {
            if (tier.containsKey(n)) tier.put(n, Tier.DEFERRED);
        }
        for (String n : o.core == null ? List.<String>of() : o.core) {
            if (tier.containsKey(n)) tier.put(n, Tier.CORE);
        }
        Plan p = new Plan(tier);
        p.rebuild(byName);

        int budget = o.budgetTokens;
        if (budget == 0 && o.contextWindow > 0) budget = o.contextWindow / 20;
        if (budget > 0) {
            java.util.function.IntSupplier cost = () -> {
                int n = count.apply(toolDef(p.search)) + count.apply(toolDef(p.call));
                for (String name : p.core) n += count.apply(toolDef(byName.get(name)));
                return n;
            };
            // least used first; ties: bigger schema first, then name
            Function<List<String>, List<String>> order = names -> {
                List<String> out = new ArrayList<>(names);
                out.sort(Comparator.<String>comparingInt(usage::applyAsInt)
                        .thenComparing(Comparator.<String>comparingInt(n -> count.apply(toolDef(byName.get(n)))).reversed())
                        .thenComparing(Comparator.naturalOrder()));
                return out;
            };
            for (String n : order.apply(p.core)) {
                if (cost.getAsInt() <= budget) break;
                tier.put(n, Tier.LISTED);
                p.rebuild(byName);
            }
            for (String n : order.apply(p.listed)) {
                if (cost.getAsInt() <= budget) break;
                tier.put(n, Tier.DEFERRED);
                p.rebuild(byName);
            }
        }
        return p;
    }

    /** {@code tool_search} against every tool of {@code tk}, any tier. */
    static ToolResult search(Toolkit tk, Map<String, Object> args) {
        String q = args != null && args.get("query") instanceof String s ? s.trim() : "";
        if (q.isEmpty()) return ToolResult.error("tool_search: query is required");
        List<Tool> picked = new ArrayList<>();
        if (q.startsWith("select:")) {
            List<String> missing = new ArrayList<>();
            for (String raw : q.substring("select:".length()).split(",", -1)) {
                String n = raw.trim();
                if (n.isEmpty()) continue;
                Tool t = tk.get(n);
                if (t != null) picked.add(t);
                else missing.add(n);
            }
            if (!missing.isEmpty() && picked.isEmpty()) {
                return ToolResult.error("tool_search: no such tool: " + String.join(", ", missing));
            }
        } else {
            int limit = 5;
            if (args.get("limit") instanceof Number l && l.doubleValue() >= 1) limit = (int) l.doubleValue();
            String[] terms = q.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
            List<Object[]> hits = new ArrayList<>(); // [tool, score]
            for (Tool t : tk.tools()) {
                String name = t.name().toLowerCase(java.util.Locale.ROOT);
                String desc = t.description() == null ? "" : t.description().toLowerCase(java.util.Locale.ROOT);
                int s = 0;
                for (String term : terms) {
                    if (term.isEmpty()) continue;
                    if (name.contains(term)) s += 3;
                    if (desc.contains(term)) s++;
                }
                if (s > 0) hits.add(new Object[]{t, s});
            }
            hits.sort((a, b) -> {
                int c = Integer.compare((Integer) b[1], (Integer) a[1]);
                return c != 0 ? c : ((Tool) a[0]).name().compareTo(((Tool) b[0]).name());
            });
            for (int i = 0; i < hits.size() && i < limit; i++) picked.add((Tool) hits.get(i)[0]);
        }
        if (picked.isEmpty()) {
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("count", 0);
            return ToolResult.ok("No matching tools.", md);
        }
        List<Object> defs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Tool t : picked) {
            defs.add(toolDef(t));
            names.add(t.name());
        }
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("count", picked.size());
        md.put("tools", names);
        return ToolResult.ok("Loaded tools (call by name, or with call_tool):\n" + goIndent(goJson(defs)), md);
    }

    // ------------------------------------------------------------------
    // Go-compatible JSON (sorted keys, HTML-escaped, MarshalIndent "  ") so the
    // tool_search text and the token estimate match the reference byte-for-byte.
    // ------------------------------------------------------------------

    private static final ObjectMapper SORTED = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    static String goJson(Object v) {
        String s;
        try {
            s = SORTED.writeValueAsString(v);
        } catch (Exception e) {
            s = "null";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Re-indent compact JSON the way Go's {@code json.MarshalIndent(v, "", "  ")} does. */
    static String goIndent(String compact) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inStr = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\') {
                    out.append(compact.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> { inStr = true; out.append(c); }
                case '{', '[' -> {
                    char close = c == '{' ? '}' : ']';
                    if (i + 1 < compact.length() && compact.charAt(i + 1) == close) {
                        out.append(c).append(close);
                        i++;
                    } else {
                        depth++;
                        out.append(c).append('\n').append("  ".repeat(depth));
                    }
                }
                case '}', ']' -> {
                    depth--;
                    out.append('\n').append("  ".repeat(depth)).append(c);
                }
                case ',' -> out.append(",\n").append("  ".repeat(depth));
                case ':' -> out.append(": ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
