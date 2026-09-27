package judge;

import io.github.muthuishere.toolnexus.Classifier;
import io.github.muthuishere.toolnexus.Classifier.*;
import io.github.muthuishere.toolnexus.Request;

import java.util.*;

/** Spike: the whole judge surface in one file. {@code import static judge.Judge.*;} */
public final class Judge {
    private Judge() {}

    // ---- questions: a named, ordered list ----
    public record Q(String name, Question question) {}

    public static Q noul(String name, String instructions) { return new Q(name, new NoulQuestion(instructions)); }
    public static Q choice(String name, String instructions, Map<String, String> options) {
        return new Q(name, new ChoiceQuestion(instructions, options));
    }
    public static Q score(String name, String instructions, List<String> levels) {
        return new Q(name, new ScoreQuestion(instructions, levels));
    }
    public static Q score(String name, String instructions, String... levels) { return score(name, instructions, List.of(levels)); }

    /** Ordered list -> the §8B map. A duplicate name is an error naming the key. */
    public static Map<String, Question> questions(List<Q> qs) {
        Map<String, Question> out = new LinkedHashMap<>();
        for (Q q : qs)
            if (out.putIfAbsent(q.name(), q.question()) != null)
                throw new IllegalArgumentException("duplicate question name \"" + q.name() + "\"");
        return out;
    }

    // ---- state ----
    public static final class State {
        private State() {}
        public static Map<String, Object> of(Map<String, ?> m) { return new LinkedHashMap<>(m); }
        public static Map<String, Object> of(String context, String message) { return of(context, message, Map.of()); }
        public static Map<String, Object> of(String context, String message, Map<String, ?> extra) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("context", context);
            s.put("message", message);
            s.putAll(extra);
            return s;
        }
    }

    // ---- bands ----
    public enum Band {
        YES, NO, UNCERTAIN;
        @Override public String toString() { return name().toLowerCase(); }
    }

    /** Cut points are EXCLUSIVE on the confident side: exactly low/high is uncertain. */
    public record Bands(double low, double high) {
        public static final Bands DEFAULT = new Bands(0.30, 0.70);

        public Band of(double p) { return p < low ? Band.NO : p > high ? Band.YES : Band.UNCERTAIN; }

        /** noul: its probability. choice: sure (conf > high, not near-uniform) or uncertain. score: its confidence. */
        public Band of(DecisionAnswer a) {
            return switch (a) {
                case NoulAnswer n -> of(n.noul());
                case ChoiceAnswer c -> !c.nearUniform() && c.confidence() > high ? Band.YES : Band.UNCERTAIN;
                case ScoreAnswer s -> s.confidence() > high ? Band.YES : Band.UNCERTAIN;
            };
        }
    }

    // ---- ask ----
    public record Answer(Band band, DecisionAnswer raw) {}

    public static Map<String, Answer> ask(Classifier c, Object state, List<Q> qs) { return ask(c, state, qs, Bands.DEFAULT); }

    public static Map<String, Answer> ask(Classifier c, Object state, List<Q> qs, Bands b) {
        Map<String, Answer> out = new LinkedHashMap<>();
        c.evaluate(state, questions(qs)).answers().forEach((k, a) -> out.put(k, new Answer(b.of(a), a)));
        return out;
    }

    // ---- gate ----
    public record Rule(String question, Double below, Double atLeast, String is, String action, String target) {
        public static Rule below(String q, double v, String action) { return new Rule(q, v, null, null, action, ""); }
        public static Rule atLeast(String q, double v, String action, String target) { return new Rule(q, null, v, null, action, target); }
        public static Rule is(String q, String value, String action, String target) { return new Rule(q, null, null, value, action, target); }
    }

    /** action "" = fall through (run the step). */
    public record Outcome(String action, String target, boolean escalated, Request request,
                          Map<String, DecisionAnswer> answers) {}

    public static Outcome gate(Classifier c, Object state, List<Q> qs, List<Rule> rules) {
        return gate(c, state, qs, rules, Bands.DEFAULT);
    }

    public static Outcome gate(Classifier c, Object state, List<Q> qs, List<Rule> rules, Bands b) {
        return apply(c.evaluate(state, questions(qs)).answers(), rules, b == null ? Bands.DEFAULT : b);
    }

    /** Pure half of gate: first-match rules; an unsure answer on rule i escalates and wins. */
    public static Outcome apply(Map<String, DecisionAnswer> answers, List<Rule> rules, Bands b) {
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            DecisionAnswer a = answers.get(r.question());
            String why = a == null ? "missing answer"
                    : (r.is() != null) != (a instanceof ChoiceAnswer) ? "rule does not fit a " + a.type() + " answer"
                    : b.of(a) == Band.UNCERTAIN ? a.type() + " answer is in the uncertain band"
                    : null;
            if (why != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("question", r.question());
                data.put("reason", why);
                data.put("answers", answers);
                Request req = new Request("gate:" + i + ":" + r.question(), "input",
                        "Classifier is unsure about \"" + r.question() + "\" (" + why + ").", null, data, null);
                return new Outcome("needs_input", "", true, req, answers);
            }
            boolean fired = switch (a) {
                case ChoiceAnswer ch -> ch.choice().equals(r.is());
                case NoulAnswer n -> r.below() != null ? n.noul() < r.below() : n.noul() >= r.atLeast();
                case ScoreAnswer s -> r.below() != null ? s.score() < r.below() : s.score() >= r.atLeast();
            };
            if (fired) return new Outcome(r.action(), r.target() == null ? "" : r.target(), false, null, answers);
        }
        return new Outcome("", "", false, null, answers);
    }
}
