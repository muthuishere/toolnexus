package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Classifier.ChoiceQuestion;
import io.github.muthuishere.toolnexus.Classifier.ClassifierException;
import io.github.muthuishere.toolnexus.Classifier.ClassifierUnsupported;
import io.github.muthuishere.toolnexus.Classifier.NoulQuestion;
import io.github.muthuishere.toolnexus.Classifier.Question;
import io.github.muthuishere.toolnexus.Classifier.RawQuestion;
import io.github.muthuishere.toolnexus.Classifier.ScoreQuestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Decisions API wire (SPEC.md §8B "Decisions wire", OpenSpec change
 * {@code add-classifier-openai-decisions}, issue #157). OpenAI's {@code POST /v1/decisions} takes
 * the same three question kinds as System One under other names (predicate / choice / score) as
 * an ORDERED LIST, and answers in that order. This class maps a §8B question set onto that list
 * and reads the answers back into the one {@link Classifier.Decision} shape, so a caller never
 * sees which wire answered.
 */
final class ClassifierDecisions {
    private ClassifierDecisions() {}

    /** The Decisions API's per-request question cap. */
    static final int MAX_QUESTIONS = 400;

    /**
     * The question list, in ASCII key order. Caller keys are NOT sent (no {@code name}): §8B keys
     * are addressing, not content, and answers come back in request order. Every check here fires
     * before any request.
     */
    static List<Object> questions(String backend, String model, Map<String, Question> questions) {
        if (questions.size() > MAX_QUESTIONS) {
            throw new ClassifierException("classifier: the decisions wire takes at most " + MAX_QUESTIONS
                    + " questions, got " + questions.size());
        }
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Question> e : new TreeMap<>(questions).entrySet()) {
            String k = e.getKey();
            Map<String, Object> m = new LinkedHashMap<>();
            switch (e.getValue()) {
                case NoulQuestion q -> {
                    String instr = q.instructions() == null ? "" : q.instructions();
                    if (q.criteria() != null) {
                        instr += "\n\nTrue means: " + q.criteria().whenTrue()
                                + "\nFalse means: " + q.criteria().whenFalse();
                    }
                    m.put("type", "predicate");
                    m.put("instructions", instr);
                }
                case ChoiceQuestion q -> {
                    int n = q.criteria() == null ? 0 : q.criteria().size();
                    if (n < 2) {
                        throw new ClassifierException("classifier: question \"" + k + "\": the decisions wire needs 2.."
                                + Classifier.MAX_CHOICE_OPTIONS + " choice options, got " + n);
                    }
                    List<Object> choices = new ArrayList<>();
                    for (Map.Entry<String, String> c : new TreeMap<>(q.criteria()).entrySet()) {
                        Map<String, Object> cm = new LinkedHashMap<>();
                        cm.put("value", c.getKey());
                        if (c.getValue() != null && !c.getValue().isEmpty()) cm.put("description", c.getValue());
                        choices.add(cm);
                    }
                    m.put("type", "choice");
                    m.put("instructions", q.instructions());
                    m.put("choices", choices);
                }
                case ScoreQuestion q -> {
                    List<Object> levels = new ArrayList<>();
                    for (String l : q.criteria()) levels.add(Map.of("label", l));
                    m.put("type", "score");
                    m.put("instructions", q.instructions());
                    m.put("levels", levels);
                }
                case RawQuestion q -> throw new ClassifierUnsupported(backend, model,
                        "raw question type \"" + q.type() + "\"");
            }
            out.add(m);
        }
        return out;
    }

    /** The state as sent: a string verbatim, anything else as its canonical JSON text; with images,
     * one user message holding that text then the images as data URLs, in collection order. */
    static Object input(ClassifierBackends.LiftResult p) {
        String text = p.state() instanceof String s ? s : Classifier.Canon.write(p.state());
        if (p.images().isEmpty()) return text;
        List<Object> content = new ArrayList<>();
        content.add(Map.of("type", "input_text", "text", text));
        for (ClassifierBackends.Lifted im : p.images()) {
            content.add(Map.of("type", "input_image", "image_url", "data:image/" + im.format + ";base64," + im.data));
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", content);
        return List.of(msg);
    }

    /** A Decisions API reply as a System One-shaped body (canonical JSON), so decode, validation
     * and the cache are the one path. {@code calibrated} is false: not a System One model. */
    static String reply(String body, Map<String, Question> questions) {
        ClassifierException malformed = new ClassifierException("classifier: decisions: malformed reply");
        Object parsed;
        try {
            parsed = Json.MAPPER.readValue(body, Object.class);
        } catch (Exception e) {
            throw malformed;
        }
        if (!(parsed instanceof Map<?, ?> r) || !(r.get("answers") instanceof List<?> list)) throw malformed;
        List<String> keys = new ArrayList<>(new TreeMap<>(questions).keySet());
        if (list.size() != keys.size()) {
            throw new ClassifierException("classifier: decisions: " + list.size() + " answers for "
                    + keys.size() + " questions");
        }
        Map<String, Object> answers = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (!(list.get(i) instanceof Map<?, ?> a)) throw malformed;
            String type = a.get("type") instanceof String s ? s : "";
            if ("refusal".equals(type)) {
                throw new ClassifierException("classifier: decisions: question \"" + k + "\" was refused");
            }
            Question q = questions.get(k);
            String want = switch (q.type()) {
                case "noul" -> "predicate";
                case "choice" -> "choice";
                case "score" -> "score";
                default -> "";
            };
            if (!type.equals(want)) {
                throw new ClassifierException("classifier: decisions: answer \"" + k + "\" is a " + type
                        + " answer, not " + want);
            }
            Map<String, Object> probs = new LinkedHashMap<>();
            if (a.get("probabilities") instanceof List<?> pl) {
                for (Object o : pl) {
                    if (o instanceof Map<?, ?> pm) probs.put(value(pm.get("value")), num(pm.get("probability")));
                }
            }
            Map<String, Object> ans = new LinkedHashMap<>();
            switch (q) {
                case NoulQuestion nq -> {
                    ans.put("type", "noul");
                    ans.put("noul", num(a.get("probability")));
                }
                case ChoiceQuestion cq -> {
                    ans.put("type", "choice");
                    ans.put("choice", value(a.get("choice")));
                    ans.put("probabilities", probs);
                    ans.put("confidence", num(a.get("confidence")));
                }
                case ScoreQuestion sq -> {
                    Map<String, Object> legend = new LinkedHashMap<>();
                    for (int j = 0; j < sq.criteria().size(); j++) legend.put(Integer.toString(j), sq.criteria().get(j));
                    ans.put("type", "score");
                    ans.put("score", num(a.get("score")));
                    ans.put("legend", legend);
                    ans.put("probabilities", probs);
                    ans.put("confidence", num(a.get("confidence")));
                }
                case RawQuestion rq -> throw malformed; // unreachable: rejected before the request
            }
            answers.put(k, ans);
        }
        Map<?, ?> u = r.get("usage") instanceof Map<?, ?> um ? um : Map.of();
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", tokens(u.get("input_tokens")));
        usage.put("output_tokens", tokens(u.get("output_tokens")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answers", answers);
        out.put("calibrated", false);
        out.put("model", r.get("model") instanceof String s ? s : "");
        out.put("usage", usage);
        return Classifier.Canon.write(out);
    }

    private static Number num(Object v) {
        return v instanceof Number n ? n : 0;
    }

    private static long tokens(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    /** A choice value or level index as the §8B key: a string as written, a boolean as
     * {@code "true"}/{@code "false"}, a number in its shortest form. */
    static String value(Object v) {
        if (v instanceof String s) return s;
        if (v instanceof Boolean b) return b.toString();
        if (v instanceof Number n) return Classifier.Canon.number(n);
        return String.valueOf(v);
    }
}
