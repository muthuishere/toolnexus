

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ADR 0020 §D4 — the composable surface: the judge looks <b>on</b> this, <b>asks</b> these,
 * <b>rules</b> by this. Adapters return EXISTING types; here {@link Guardrail}.
 */
public record Judge(Function<Object, Object> on, Map<String, Classifier.Question> ask, Rule rule, boolean failClosed) {

    /** The port's {@code Loop.Guardrail} shape: {@code ""}/{@code "allow"} permits, any other
     *  string denies with that reason. */
    @FunctionalInterface
    public interface Guardrail { String check(Object event); }

    public record Verdict(String kept, Map<String, Object> evidence, String model, boolean calibrated) {}

    /** How a {@link Classifier.Decision} becomes a verdict. */
    public sealed interface Rule {
        Verdict apply(Classifier.Decision d);

        /** Ordered cut-points on one {@code score} answer: the first band whose {@code upto}
         *  exceeds the score wins; the last band is the fallthrough. */
        record Bands(String key, List<Band> bands) implements Rule {
            @Override public Verdict apply(Classifier.Decision d) {
                var a = d.score(key);
                String kept = bands.getLast().name();
                for (Band b : bands) { if (a.score() < b.upto()) { kept = b.name(); break; } }
                var ev = new LinkedHashMap<String, Object>();
                ev.put(key, a.score());
                ev.put("confidence", a.confidence());
                return new Verdict(kept, ev, d.model(), d.calibrated());
            }
        }

        /** The single highest-probability option of one {@code choice} answer. */
        record One(String key) implements Rule {
            @Override public Verdict apply(Classifier.Decision d) {
                var a = d.choice(key);
                return new Verdict(a.choice(), Map.copyOf(a.probabilities()), d.model(), d.calibrated());
            }
        }
    }

    public record Band(String name, double upto) {}

    public static Judge of(Function<Object, Object> on, Map<String, Classifier.Question> ask, Rule rule) {
        return new Judge(on, ask, rule, true);
    }

    public Verdict rule(Classifier c, Object event) {
        try {
            return rule.apply(c.evaluate(on.apply(event), ask));
        } catch (RuntimeException e) {
            if (failClosed) return new Verdict("deny", Map.of("error", e.getMessage()), "", false);
            throw e;
        }
    }

    /**
     * §D4 adapter. INVARIANT: a judge may move a call toward ask/deny, never toward allow — so
     * it returns only "" (allow) or a reason, and is composed BEHIND any earlier guardrail by
     * {@link #firstDenyWins}.
     */
    public Guardrail asGuardrail(Classifier c) {
        return event -> {
            Verdict v = rule(c, event);
            return switch (v.kept()) {
                case "allow" -> "";
                case "ask" -> "ask: " + v.evidence();   // §10 Pending in the real seam
                default -> v.kept() + ": " + v.evidence();
            };
        };
    }

    /** Existing behaviour (golang/agents/loop.go:24-47, java Loop.guardedHooks). */
    public static Guardrail firstDenyWins(List<Guardrail> guardrails) {
        return event -> {
            for (Guardrail g : guardrails) {
                String verdict = g.check(event);
                if (verdict != null && !verdict.isEmpty() && !"allow".equals(verdict)) return verdict;
            }
            return "";
        };
    }
}
