package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Classifier.ChoiceAnswer;
import io.github.muthuishere.toolnexus.Classifier.ChoiceQuestion;
import io.github.muthuishere.toolnexus.Classifier.ClassifierException;
import io.github.muthuishere.toolnexus.Classifier.Decision;
import io.github.muthuishere.toolnexus.Classifier.DecisionAnswer;
import io.github.muthuishere.toolnexus.Classifier.NoulAnswer;
import io.github.muthuishere.toolnexus.Classifier.NoulQuestion;
import io.github.muthuishere.toolnexus.Classifier.Question;
import io.github.muthuishere.toolnexus.Classifier.ScoreAnswer;
import io.github.muthuishere.toolnexus.Classifier.ScoreQuestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple judgments (SPEC.md §8B "Simple judgments — ask / gate"): a thin layer over any
 * {@link Classifier}. The wire is unchanged — builders produce exactly the §8B
 * {@code evaluate(state, questions)} inputs.
 *
 * <pre>{@code
 * import static io.github.muthuishere.toolnexus.Judge.*;
 *
 * var answers = ask(classifier, State.of(role, Map.of("message_received", msg)), List.of(
 *         noul("is_appropriate", "Does message_received contain inappropriate language?"),
 *         noul("does_this_help", "Does message_received help donkey kong win?")));
 * answers.get("is_appropriate").band();   // YES | NO | UNCERTAIN
 * }</pre>
 *
 * <p>A gate never authorises; it only declines to decide.
 */
public final class Judge {
    private Judge() {}

    // ------------------------------------------------------------------ questions

    /** One named question of an ordered list. */
    public record Named(String name, Question question) {}

    public static Named noul(String name, String instructions) {
        return new Named(name, new NoulQuestion(instructions));
    }

    public static Named noul(String name, String instructions, Classifier.NoulCriteria criteria) {
        return new Named(name, new NoulQuestion(instructions, criteria));
    }

    public static Named choice(String name, String instructions, Map<String, String> options) {
        return new Named(name, new ChoiceQuestion(instructions, options));
    }

    public static Named score(String name, String instructions, List<String> levels) {
        return new Named(name, new ScoreQuestion(instructions, levels));
    }

    public static Named score(String name, String instructions, String... levels) {
        return score(name, instructions, List.of(levels));
    }

    /** Ordered list → the §8B question map. A duplicate name is an error naming it, before any request. */
    public static Map<String, Question> questions(List<Named> list) {
        Map<String, Question> out = new LinkedHashMap<>();
        for (Named q : list) {
            if (out.putIfAbsent(q.name(), q.question()) != null) {
                throw new ClassifierException("duplicate question name \"" + q.name() + "\"");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ state

    /** State builders. The wire has no role field; the state carries it. */
    public static final class State {
        private State() {}

        /** A copy of a map, order kept. */
        public static Map<String, Object> of(Map<String, ?> data) {
            return new LinkedHashMap<>(data);
        }

        /**
         * {@code role} next to the data's fields at the top level; non-object data goes under
         * {@code data}. The role is never copied into question instructions.
         */
        public static Map<String, Object> of(String role, Object data) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("role", role);
            if (data instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) s.put(String.valueOf(e.getKey()), e.getValue());
            } else {
                s.put("data", data);
            }
            return s;
        }

        /** Sugar: {@code {context, message}}. */
        public static Map<String, Object> context(String context, String message) {
            return context(context, message, Map.of());
        }

        /** Sugar: {@code {context, message, ...extra}}. */
        public static Map<String, Object> context(String context, String message, Map<String, ?> extra) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("context", context);
            s.put("message", message);
            if (extra != null) s.putAll(extra);
            return s;
        }
    }

    // ------------------------------------------------------------------ bands

    public enum Band {
        YES, NO, UNCERTAIN;

        @Override public String toString() { return name().toLowerCase(); }
    }

    /** Cut-points, EXCLUSIVE on the confident side: exactly {@code low} or {@code high} is uncertain. */
    public record Bands(double low, double high) {
        public static final Bands DEFAULT = new Bands(0.30, 0.70);

        public Band of(double p) {
            return p < low ? Band.NO : p > high ? Band.YES : Band.UNCERTAIN;
        }

        /** Choice: sure iff confidence &gt; high and not near-uniform. Score: sure iff confidence &gt; high. */
        public boolean sure(DecisionAnswer a) {
            return switch (a) {
                case NoulAnswer n -> of(n.noul()) != Band.UNCERTAIN;
                case ChoiceAnswer c -> !c.nearUniform() && c.confidence() > high;
                case ScoreAnswer s -> s.confidence() > high;
            };
        }
    }

    // ------------------------------------------------------------------ ask

    /**
     * One answer by name. {@code band} is meaningful for a noul ({@code null} otherwise);
     * {@code sure} is meaningful for choice/score (for a noul: band is not uncertain).
     */
    public record Answer(String name, DecisionAnswer raw, Band band, boolean sure) {
        /** The one number: noul probability, score value, or choice confidence. */
        public double value() {
            return switch (raw) {
                case NoulAnswer n -> n.noul();
                case ScoreAnswer s -> s.score();
                case ChoiceAnswer c -> c.confidence();
            };
        }

        /** The picked option of a choice answer; {@code null} for any other type. */
        public String choice() {
            return raw instanceof ChoiceAnswer c ? c.choice() : null;
        }

        static Answer of(String name, DecisionAnswer a, Bands b) {
            Band band = a instanceof NoulAnswer n ? b.of(n.noul()) : null;
            return new Answer(name, a, band, b.sure(a));
        }
    }

    public static Map<String, Answer> ask(Classifier c, Object state, List<Named> qs) {
        return ask(c, state, qs, Bands.DEFAULT);
    }

    public static Map<String, Answer> ask(Classifier c, Object state, List<Named> qs, Bands bands) {
        Bands b = bands == null ? Bands.DEFAULT : bands;
        Map<String, Answer> out = new LinkedHashMap<>();
        c.evaluate(state, questions(qs)).answers().forEach((k, a) -> out.put(k, Answer.of(k, a, b)));
        return out;
    }

    // ------------------------------------------------------------------ gate

    /** One rule; exactly one of {@code below} / {@code atLeast} / {@code is} is set. */
    public record Rule(String question, Double below, Double atLeast, String is, String action, String target) {
        public static Rule below(String q, double v, String action) { return new Rule(q, v, null, null, action, ""); }
        public static Rule below(String q, double v, String action, String target) { return new Rule(q, v, null, null, action, target); }
        public static Rule atLeast(String q, double v, String action) { return new Rule(q, null, v, null, action, ""); }
        public static Rule atLeast(String q, double v, String action, String target) { return new Rule(q, null, v, null, action, target); }
        public static Rule is(String q, String value, String action) { return new Rule(q, null, null, value, action, ""); }
        public static Rule is(String q, String value, String action, String target) { return new Rule(q, null, null, value, action, target); }
    }

    /**
     * {@code action ""} = fall through (run the step). An escalation is {@code needs_input} with a
     * §10 {@link Request} of kind {@code input}.
     */
    public record Outcome(String action, String target, boolean escalated, Request request,
                          Map<String, DecisionAnswer> answers) {}

    /** Rules + fall-through. A non-empty {@code defaultAction} fires when no rule does; empty escalates. */
    public record Policy(List<Rule> rules, String defaultAction, Bands bands, boolean skipUncertain) {
        public Policy(List<Rule> rules) { this(rules, "", null, false); }

        public Outcome decide(Classifier c, Object state, List<Named> qs) {
            return apply(c.evaluate(state, questions(qs)).answers());
        }

        /** The pure half: no classifier call. */
        public Outcome apply(Map<String, DecisionAnswer> answers) {
            return run(answers, rules, bands, skipUncertain, true, defaultAction);
        }
    }

    public static Outcome gate(Classifier c, Object state, List<Named> qs, List<Rule> rules) {
        return gate(c, state, qs, rules, Bands.DEFAULT);
    }

    public static Outcome gate(Classifier c, Object state, List<Named> qs, List<Rule> rules, Bands bands) {
        return gateAnswers(c.evaluate(state, questions(qs)).answers(), rules, bands);
    }

    /** The pure half of {@link #gate}: first-match; no rule fired falls through with action {@code ""}. */
    public static Outcome gateAnswers(Map<String, DecisionAnswer> answers, List<Rule> rules, Bands bands) {
        return run(answers, rules, bands, false, false, "");
    }

    private static Outcome run(Map<String, DecisionAnswer> answers, List<Rule> rules, Bands bands,
                               boolean skipUncertain, boolean policy, String defaultAction) {
        Bands b = bands == null ? Bands.DEFAULT : bands;
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            DecisionAnswer a = answers.get(r.question());
            String why;
            if (a == null) why = "missing answer \"" + r.question() + "\"";
            else if (!b.sure(a)) {
                if (skipUncertain) continue;
                why = "uncertain answer \"" + r.question() + "\"";
            } else if ((r.is() != null) != (a instanceof ChoiceAnswer)) why = "rule does not fit a " + a.type() + " answer";
            else if (r.is() == null && r.below() == null && r.atLeast() == null) why = "rule has no condition";
            else why = null;
            if (why != null) return escalate("gate:" + i + ":" + r.question(), r.question(), why, answers);
            boolean fired = switch (a) {
                case ChoiceAnswer ch -> ch.choice().equals(r.is());
                case NoulAnswer n -> r.below() != null ? n.noul() < r.below() : n.noul() >= r.atLeast();
                case ScoreAnswer s -> r.below() != null ? s.score() < r.below() : s.score() >= r.atLeast();
            };
            if (fired) return new Outcome(r.action(), r.target() == null ? "" : r.target(), false, null, answers);
        }
        if (policy && (defaultAction == null || defaultAction.isEmpty())) {
            return escalate("gate:default", "", "no rule fired", answers);
        }
        return new Outcome(policy ? defaultAction : "", "", false, null, answers);
    }

    private static Outcome escalate(String id, String question, String why, Map<String, DecisionAnswer> answers) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("reason", why);
        data.put("answers", answers);
        String prompt = question.isEmpty()
                ? "Classifier decided nothing (" + why + ")."
                : "Classifier is unsure about \"" + question + "\" (" + why + ").";
        return new Outcome("needs_input", "", true, new Request(id, "input", prompt, null, data, null), answers);
    }

    // ------------------------------------------------------------------ tape

    /**
     * Record live decisions by call name, then replay them with no network. A replay of an
     * unrecorded call name fails naming it.
     */
    public static final class Tape {
        private final Classifier live;
        private final Map<String, Decision> recorded = new ConcurrentHashMap<>();

        /** @param live the classifier to record from; {@code null} for a replay-only tape */
        public Tape(Classifier live) { this.live = live; }

        /** A classifier that evaluates through {@code live} and records the decision under {@code call}. */
        public Classifier record(String call) {
            if (live == null) throw new ClassifierException("tape: no live classifier to record \"" + call + "\"");
            return Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM).evaluate((s, q) -> {
                Decision d = live.evaluate(s, q);
                recorded.put(call, d);
                return d;
            }));
        }

        /** Store a decision directly (e.g. loaded from disk). */
        public Tape put(String call, Decision d) { recorded.put(call, d); return this; }

        /** A classifier that replays the decision recorded under {@code call}; sends no request. */
        public Classifier replay(String call) {
            return Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM).evaluate((s, q) -> {
                Decision d = recorded.get(call);
                if (d == null) throw new ClassifierException("tape: no recorded decision for call \"" + call + "\"");
                return d;
            }));
        }

        /** The recorded call names. */
        public List<String> calls() { return new ArrayList<>(recorded.keySet()); }
    }
}
