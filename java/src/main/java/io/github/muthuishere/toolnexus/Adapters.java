package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider adapters — turn the uniform tool list into each LLM's tool schema.
 * Schema only; execution is identical for every provider.
 */
public final class Adapters {
    private Adapters() {}

    public static List<Map<String, Object>> toOpenAI(List<Tool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.put("parameters", t.inputSchema());
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", fn);
            out.add(wrapper);
        }
        return out;
    }

    public static List<Map<String, Object>> toAnthropic(List<Tool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : tools) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("input_schema", t.inputSchema());
            out.add(m);
        }
        return out;
    }

    public static List<Map<String, Object>> toGemini(List<Tool> tools) {
        List<Map<String, Object>> decls = new ArrayList<>();
        for (Tool t : tools) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("parameters", t.inputSchema());
            decls.add(m);
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("functionDeclarations", decls);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(wrapper);
        return out;
    }
}
