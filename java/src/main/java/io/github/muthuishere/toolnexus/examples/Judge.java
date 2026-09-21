package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.Classifier;
import io.github.muthuishere.toolnexus.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as {@code Tool} is the contract for an
 * ACTION.
 *
 * <pre>./gradlew runJudge</pre>
 *
 * With {@code OPENROUTER_API_KEY} set it calls the live System One backend; with no key it replays
 * one recorded decision through the {@code static} backend, so the example runs offline with no
 * credential.
 */
public final class Judge {

    /** The state: whatever the host already has. Sent verbatim, never canonicalised. */
    private static final String TICKET =
            "Ticket 4021: my card was charged twice for the annual plan on Tuesday, and the second charge "
                    + "has not been refunded. I am not blocked from working, but I would like the money back this week.";

    private static final String MODEL = "typesafe/jev-1.13";

    /**
     * All three question types in ONE call: many questions, one round trip, one state ingest. The
     * questions are INDEPENDENT — one answer is never context for another.
     *
     * <p>The {@code choice} descriptions are the whole ball game (ADR 0021): {@code criteria[id]} is
     * the only thing that tells the model what picking {@code billing} rather than {@code technical}
     * would MEAN. Options described by their own id are schema-valid, return HTTP 200 — and rank at
     * chance (17 apples -&gt; 0). So: every option carries a real sentence, all three use the SAME
     * template ("own it here when the problem is X: a, b, c"), and no arithmetic is pushed onto the
     * model — the host does the counting and hands over the conclusion.
     */
    private static final Map<String, Classifier.Question> QUESTIONS = Map.of(
            "wants_money_back", new Classifier.NoulQuestion("Is the customer asking for money to be returned?"),
            "department", new Classifier.ChoiceQuestion("Which desk should own this ticket?", Map.of(
                    "billing", "own it here when the problem is money that moved: a duplicate charge, a wrong invoice, a refund owed",
                    "shipping", "own it here when the problem is a physical parcel: a late delivery, a package damaged in transit",
                    "technical", "own it here when the problem is the product itself: a login that fails, a feature that errors")),
            "urgency", new Classifier.ScoreQuestion("How fast does this ticket need a human?", List.of(
                    "the customer is working normally and is waiting on an answer",
                    "the customer is inconvenienced and will chase if nobody replies today",
                    "the customer is blocked from working right now and every hour costs them")));

    /** One decision recorded off the live backend, so this file runs with no key and no network. */
    private static final String RECORDED = """
            {"model":"typesafe/jev-1.13-20260917",
             "answers":{
               "wants_money_back":{"type":"noul","noul":0.99},
               "department":{"type":"choice","choice":"billing","probabilities":{"technical":0,"shipping":0,"billing":1},"confidence":1},
               "urgency":{"type":"score","score":0.49,
                 "legend":{"0":"the customer is working normally and is waiting on an answer","1":"the customer is inconvenienced and will chase if nobody replies today","2":"the customer is blocked from working right now and every hour costs them"},
                 "probabilities":{"0":0.52,"1":0.48,"2":0},"confidence":0.27}},
             "usage":{"input_tokens":516,"output_tokens":72,"cost":0.000021672}}
            """;

    public static void main(String[] args) {
        String key = System.getenv("OPENROUTER_API_KEY");
        boolean live = key != null && !key.isBlank();

        Classifier.Options opts = live
                ? new Classifier.Options()
                        .baseUrl("https://openrouter.ai/api/v1") // serves the System One wire today
                        .model(MODEL)
                        .apiKeyEnv("OPENROUTER_API_KEY")        // the NAME of an env var, never the value
                        .onMetric(ev -> {
                            if (ev instanceof LlmClient.MetricEvent.ClassifierWarning w) {
                                System.out.println("warning: " + w.warning());
                            }
                        })
                : new Classifier.Options()
                        .style(Classifier.STYLE_STATIC)
                        .model(MODEL)
                        .decisions(List.of(new Classifier.RecordedDecision(TICKET, QUESTIONS, RECORDED)));

        Classifier judge = Classifier.create(opts);
        System.out.println(live
                ? "backend: systemone (live)"
                : "backend: static (recorded — set OPENROUTER_API_KEY to go live)");

        Classifier.Decision d = judge.evaluate(TICKET, QUESTIONS);

        Classifier.NoulAnswer want = d.noul("wants_money_back");
        Classifier.ChoiceAnswer dept = d.choice("department");
        Classifier.ScoreAnswer urg = d.score("urgency");

        int level = (int) Math.round(urg.score());
        System.out.println();
        System.out.println("model answering: " + d.model());
        System.out.println("wants_money_back: " + want.noul()
                + "   (a noul carries NO confidence — the number IS the answer)");
        System.out.println("department:       " + dept.choice()
                + "  p=" + probs(dept.probabilities()) + " confidence=" + dept.confidence());
        System.out.println("urgency:          " + urg.score()
                + "  of 0.." + (urg.legend().size() - 1) + "  p=" + probs(urg.probabilities()));
        System.out.println("  level " + level + ": " + urg.legend().get(String.valueOf(level))
                + "   (a score MAY fall between levels)");

        // The two health flags, and what they actually mean.
        System.out.println();
        System.out.println("calibrated: " + d.calibrated()
                + "  — these probabilities came from a calibrated backend, so a threshold tuned here transfers."
                + " An 'llm'-style backend reports false and your thresholds do NOT carry over.");
        System.out.println("nearUniform(department): " + dept.nearUniform()
                + "  — max|p - 1/n| <= 0.05, derived from the response. True would mean the model had nothing"
                + " to rank on (usually undescribed options). Advisory, NOT correctness.");

        Classifier.Usage u = d.usage();
        System.out.println();
        System.out.println("usage: " + u.inputTokens() + " in / " + u.outputTokens() + " out"
                + (u.cost() == null ? "" : " / $" + new java.math.BigDecimal(u.cost().toString()).toPlainString()));
    }

    /** Render a probability map with stable, sorted keys — a HashMap's order is not an answer. */
    private static String probs(Map<String, Double> p) {
        return new TreeMap<>(p).entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private Judge() {}
}
