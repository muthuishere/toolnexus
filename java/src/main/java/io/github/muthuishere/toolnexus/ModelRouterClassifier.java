package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Judge.Answer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * OPT-IN per-query model routing (SPEC §8 "Right-size routing", §8B Batteries): one
 * {@code choice} {@code model} over the user's ordered model options. A sure pick routes;
 * unsure, missing and error fall back.
 */
public final class ModelRouterClassifier {

    public static final class Options extends Batteries.BaseOptions<Options> {}

    /** One user-supplied model: an id and a prose description of what it is good for. */
    public record Model(String id, String description) {}

    public record Verdict(String model, boolean routed, boolean sure, Map<String, Double> probabilities,
                          boolean calibrated, String error) {}

    private final Classifier c;
    private final List<Model> models;
    private final Options opts;

    public ModelRouterClassifier(Classifier c, List<Model> models, Options o) {
        this.c = c;
        this.models = models == null ? List.of() : List.copyOf(models);
        this.opts = o == null ? new Options() : o;
    }

    public ModelRouterClassifier(Classifier c, List<Model> models) { this(c, models, null); }

    public Verdict pick(String prompt, String fallback) {
        if (models.isEmpty()) return new Verdict(fallback, false, false, null, true, null);
        Map<String, String> options = new LinkedHashMap<>();
        for (Model m : models) options.put(m.id(), m.description());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_request", prompt);
        Batteries.Asked a;
        try {
            a = Batteries.ask(c, Batteries.state(Batteries.roleOr(opts.role, Batteries.ROLE_MODEL_ROUTER), data),
                    List.of(Judge.choice("model", "Which model should answer `user_request`?", options)), opts.bands);
        } catch (RuntimeException e) {
            return new Verdict(fallback, false, false, null, false, Batteries.message(e));
        }
        Answer x = a.answers().get("model");
        if (x == null) return new Verdict(fallback, false, false, null, a.calibrated(), null);
        Map<String, Double> probs = x.raw() instanceof Classifier.ChoiceAnswer ca ? ca.probabilities() : null;
        if (x.sure() && x.choice() != null) return new Verdict(x.choice(), true, true, probs, a.calibrated(), null);
        return new Verdict(fallback, false, false, probs, a.calibrated(), null);
    }

    /**
     * A {@code beforeLLM} hook returning a {@code model} override only when routed to a model other
     * than the turn's configured one. {@code next} may be null; its fields win.
     */
    public Function<LlmClient.BeforeLLMEvent, LlmClient.LLMOverride> asHook(
            Function<LlmClient.BeforeLLMEvent, LlmClient.LLMOverride> next) {
        return ev -> {
            String text = Batteries.latestUserText(ev.messages());
            if (text.isEmpty() || models.isEmpty()) return Batteries.mergeLLM(ev, null, next);
            Verdict v = pick(text, ev.model());
            if (v.routed() && !v.model().equals(ev.model())) {
                return Batteries.mergeLLM(ev, LlmClient.LLMOverride.withModel(v.model()), next);
            }
            return Batteries.mergeLLM(ev, null, next);
        };
    }
}
