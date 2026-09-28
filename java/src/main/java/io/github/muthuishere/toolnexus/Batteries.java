package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Judge.Answer;
import io.github.muthuishere.toolnexus.Judge.Bands;
import io.github.muthuishere.toolnexus.Judge.Named;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared pieces of the judge batteries (SPEC.md §8B "Batteries", change add-judge-batteries):
 * {@link OnError}, the default role sentences (contract, pinned by
 * {@code examples/judge/batteries/}), {@link #latestUserText}, and the option bases. The
 * batteries themselves are {@link ToolGuardClassifier}, {@link ToolRelevanceClassifier},
 * {@link SkillRelevanceClassifier}, {@link ToolResultFilterClassifier},
 * {@link IsCompleteClassifier}, {@link AgentRouterClassifier}, {@link ContentGuardClassifier} and
 * {@link ModelRouterClassifier}. They are advisory: nothing here is a security control.
 */
public final class Batteries {
    private Batteries() {}

    /** The host's required decision for a classifier error. */
    public enum OnError {
        OPEN, CLOSED;

        @Override public String toString() { return name().toLowerCase(); }
    }

    // Default role sentences (contract).
    public static final String ROLE_TOOL_GUARD = "You review one tool call an AI agent is about to make and rate how risky it is to run it without a human approving it.";
    public static final String ROLE_TOOL_RELEVANCE = "You decide which tools an AI agent needs for the user's request, so the tools it does not need can be left out.";
    public static final String ROLE_SKILL_RELEVANCE = "You decide which agent skills are relevant to the user's request, so the skills it does not need can be left out.";
    public static final String ROLE_TOOL_RESULT_FILTER = "You decide which parts of a tool's output are relevant to the query, so the irrelevant parts can be dropped.";
    public static final String ROLE_IS_COMPLETE = "You check whether an AI agent's final answer completes the task it was given.";
    public static final String ROLE_AGENT_ROUTER = "You route a task to the agent best suited to do it.";
    public static final String ROLE_CONTENT_GUARD = "You screen text that is about to enter or leave an AI agent.";
    public static final String ROLE_MODEL_ROUTER = "You pick the cheapest model that can handle the user's request well.";

    /** Options every battery accepts: {@code bands} (default 0.30/0.70) and {@code role}. */
    @SuppressWarnings("unchecked")
    public abstract static class BaseOptions<T extends BaseOptions<T>> {
        public Bands bands;
        public String role;

        public T bands(Bands v) { this.bands = v; return (T) this; }
        public T role(String v) { this.role = v; return (T) this; }
    }

    /** Options of a battery whose error outcome is a policy choice: {@code onError} is required. */
    @SuppressWarnings("unchecked")
    public abstract static class PolicyOptions<T extends PolicyOptions<T>> extends BaseOptions<T> {
        public OnError onError;

        public T onError(OnError v) { this.onError = v; return (T) this; }
    }

    static void requireOnError(String battery, OnError o) {
        if (o == null) {
            throw new IllegalArgumentException(battery + ": onError is required and must be \"open\" or \"closed\"");
        }
    }

    static Bands bandsOr(Bands b) { return b == null ? Bands.DEFAULT : b; }

    static String roleOr(String role, String def) { return role == null || role.isEmpty() ? def : role; }

    /** One battery evaluation: answers banded by name, and whether the backend is calibrated. */
    record Asked(Map<String, Answer> answers, boolean calibrated) {}

    /** Runs the classifier; the caller turns a thrown error into its verdict. */
    static Asked ask(Classifier c, Object state, List<Named> qs, Bands bands) {
        Bands b = bandsOr(bands);
        Classifier.Decision d = c.evaluate(state, Judge.questions(qs));
        Map<String, Answer> out = new LinkedHashMap<>();
        d.answers().forEach((k, a) -> out.put(k, Answer.of(k, a, b)));
        return new Asked(out, d.calibrated());
    }

    static String message(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    static Map<String, Object> state(String role, Map<String, Object> data) {
        return Judge.State.of(role, data);
    }

    /**
     * Calls {@code next} with the event as {@code own} leaves it and merges field by field:
     * {@code next}'s non-absent fields win; fields it leaves absent keep {@code own}'s.
     */
    static LlmClient.LLMOverride mergeLLM(LlmClient.BeforeLLMEvent ev, LlmClient.LLMOverride own,
                                          Function<LlmClient.BeforeLLMEvent, LlmClient.LLMOverride> next) {
        if (next == null) return own;
        if (own == null) return next.apply(ev);
        LlmClient.BeforeLLMEvent seen = new LlmClient.BeforeLLMEvent(
                own.messages() != null ? own.messages() : ev.messages(),
                own.tools() != null ? own.tools() : ev.tools(),
                present(own.model()) ? own.model() : ev.model(),
                ev.turn());
        LlmClient.LLMOverride nx = next.apply(seen);
        if (nx == null) return own;
        return new LlmClient.LLMOverride(
                nx.messages() != null ? nx.messages() : own.messages(),
                nx.tools() != null ? nx.tools() : own.tools(),
                present(nx.model()) ? nx.model() : own.model());
    }

    static boolean present(String s) { return s != null && !s.isEmpty(); }

    /** Reads a message or provider entry as a map ({@code Map} as-is, anything else via JSON). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        try {
            return Json.toMap(Json.stringify(v));
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * The text of the last user message that has text: string content, or every
     * {@code {type:"text"}} part's {@code text} joined with {@code "\n"}. A user message with no
     * text (tool_result only) is skipped. {@code ""} when there is none.
     */
    public static String latestUserText(List<?> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = asMap(messages.get(i));
            if (!"user".equals(m.get("role"))) continue;
            Object c = m.get("content");
            if (c instanceof String s) {
                if (!s.isEmpty()) return s;
            } else if (c instanceof List<?> parts) {
                List<String> texts = new ArrayList<>();
                for (Object p : parts) {
                    if (p instanceof Map<?, ?> pm && "text".equals(pm.get("type")) && pm.get("text") instanceof String t) {
                        texts.add(t);
                    }
                }
                if (!texts.isEmpty()) return String.join("\n", texts);
            }
        }
        return "";
    }

    /** A named, described candidate (a tool or a skill). {@code description} may be null. */
    public record Item(String name, String description) {}

    record Picked(List<String> selected, List<String> dropped, boolean calibrated, String error) {}

    /** ToolRelevance / SkillRelevance: band no drops; yes, uncertain and missing keep. */
    static Picked relevance(Classifier c, PolicyOptions<?> o, String defRole, String noun, String verb,
                            String whenTrue, String whenFalse, String prompt, List<Item> items) {
        List<String> selected = new ArrayList<>(), dropped = new ArrayList<>();
        if (items == null || items.isEmpty()) return new Picked(selected, dropped, true, null);
        List<Named> qs = new ArrayList<>();
        for (Item it : items) {
            String ins = "Is the " + noun + " `" + it.name() + "` " + verb + " the request in `user_request`?";
            if (it.description() != null && !it.description().isEmpty()) ins += " The " + noun + ": " + it.description();
            qs.add(Judge.noul(it.name(), ins, new Classifier.NoulCriteria(whenTrue, whenFalse)));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_request", prompt);
        Asked a;
        try {
            a = ask(c, state(roleOr(o.role, defRole), data), qs, o.bands);
        } catch (RuntimeException e) {
            for (Item it : items) (o.onError == OnError.OPEN ? selected : dropped).add(it.name());
            return new Picked(selected, dropped, false, message(e));
        }
        for (Item it : items) {
            Answer x = a.answers().get(it.name());
            (x != null && x.band() == Judge.Band.NO ? dropped : selected).add(it.name());
        }
        return new Picked(selected, dropped, a.calibrated(), null);
    }
}
