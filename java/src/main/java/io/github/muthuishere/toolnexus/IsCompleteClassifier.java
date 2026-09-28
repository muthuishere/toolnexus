package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Batteries.OnError;
import io.github.muthuishere.toolnexus.Judge.Answer;
import io.github.muthuishere.toolnexus.Judge.Band;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks whether a final answer completes its task (SPEC §8B Batteries): one {@code noul}
 * {@code complete}; complete ⇔ band yes. No hook (ADR 0035).
 */
public final class IsCompleteClassifier {

    public static final class Options extends Batteries.PolicyOptions<Options> {}

    /** {@code p} null when the answer is missing or the classifier failed. */
    public record Verdict(boolean complete, Double p, Band band, boolean calibrated, String error) {}

    private final Classifier c;
    private final Options opts;

    public IsCompleteClassifier(Classifier c, Options o) {
        Batteries.requireOnError("IsComplete", o == null ? null : o.onError);
        this.c = c;
        this.opts = o;
    }

    public Verdict check(String task, String answer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("answer", answer);
        Batteries.Asked a;
        try {
            a = Batteries.ask(c, Batteries.state(Batteries.roleOr(opts.role, Batteries.ROLE_IS_COMPLETE), data),
                    List.of(Judge.noul("complete", "Does `answer` fully complete the request in `task`?",
                            new Classifier.NoulCriteria("every part of the task is done and nothing asked for is missing",
                                    "part of the task is missing, wrong or only promised"))), opts.bands);
        } catch (RuntimeException e) {
            return new Verdict(opts.onError == OnError.OPEN, null, Band.UNCERTAIN, false, Batteries.message(e));
        }
        Answer x = a.answers().get("complete");
        if (x == null) return new Verdict(false, null, Band.UNCERTAIN, a.calibrated(), null);
        return new Verdict(x.band() == Band.YES, x.value(), x.band(), a.calibrated(), null);
    }
}
