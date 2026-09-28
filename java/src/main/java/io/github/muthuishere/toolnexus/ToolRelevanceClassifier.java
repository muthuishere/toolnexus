package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Batteries.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides which tools the request needs (SPEC §8B Batteries): one {@code noul} per tool, keyed by
 * name; band no drops, yes/uncertain/missing keep; error ⇒ open: all, closed: none.
 */
public final class ToolRelevanceClassifier {

    public static final class Options extends Batteries.PolicyOptions<Options> {}

    /** {@code selected} / {@code dropped} are names in input order. */
    public record Verdict(List<String> selected, List<String> dropped, boolean calibrated, String error) {}

    private final Classifier c;
    private final Options opts;

    public ToolRelevanceClassifier(Classifier c, Options o) {
        Batteries.requireOnError("ToolRelevance", o == null ? null : o.onError);
        this.c = c;
        this.opts = o;
    }

    public Verdict select(String prompt, List<Item> tools) {
        Batteries.Picked p = Batteries.relevance(c, opts, Batteries.ROLE_TOOL_RELEVANCE, "tool", "needed for",
                "the request cannot be done well without this tool", "the request can be done without this tool",
                prompt, tools);
        return new Verdict(p.selected(), p.dropped(), p.calibrated(), p.error());
    }

    /** name/description of an openai ({@code {function:{name,…}}}) or anthropic ({@code {name,…}}) tool entry. */
    @SuppressWarnings("unchecked")
    static Item providerTool(Object t) {
        Map<String, Object> m = Batteries.asMap(t);
        if (m.get("function") instanceof Map<?, ?> f) m = (Map<String, Object>) f;
        return new Item(m.get("name") instanceof String n ? n : "", m.get("description") instanceof String d ? d : "");
    }

    /**
     * A {@code beforeLLM} hook that drops the tools the classifier is confident the latest user
     * text does not need (override {@code tools}, original order, only when one was dropped).
     * {@code next} may be null.
     */
    public Function<LlmClient.BeforeLLMEvent, LlmClient.LLMOverride> asHook(
            Function<LlmClient.BeforeLLMEvent, LlmClient.LLMOverride> next) {
        return ev -> {
            String text = Batteries.latestUserText(ev.messages());
            List<Map<String, Object>> tools = ev.tools();
            if (text.isEmpty() || tools == null || tools.isEmpty()) return Batteries.mergeLLM(ev, null, next);
            List<Item> items = new ArrayList<>();
            for (Map<String, Object> t : tools) items.add(providerTool(t));
            Verdict v = select(text, items);
            if (v.dropped().isEmpty()) return Batteries.mergeLLM(ev, null, next);
            Set<String> keep = new HashSet<>(v.selected());
            List<Map<String, Object>> kept = new ArrayList<>();
            for (int i = 0; i < tools.size(); i++) if (keep.contains(items.get(i).name())) kept.add(tools.get(i));
            return Batteries.mergeLLM(ev, new LlmClient.LLMOverride(null, kept), next);
        };
    }
}
