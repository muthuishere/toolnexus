package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Batteries.Item;

import java.util.List;

/**
 * Decides which agent skills are relevant to the request (SPEC §8B Batteries). No hook: skills
 * live in the system prompt; feed {@code selected} into the skill allowlist instead — and never
 * pass an empty {@code selected} as an allowlist (empty ⇒ all).
 */
public final class SkillRelevanceClassifier {

    public static final class Options extends Batteries.PolicyOptions<Options> {}

    /** {@code selected} / {@code dropped} are names in input order. */
    public record Verdict(List<String> selected, List<String> dropped, boolean calibrated, String error) {}

    private final Classifier c;
    private final Options opts;

    public SkillRelevanceClassifier(Classifier c, Options o) {
        Batteries.requireOnError("SkillRelevance", o == null ? null : o.onError);
        this.c = c;
        this.opts = o;
    }

    public Verdict select(String prompt, List<Item> skills) {
        Batteries.Picked p = Batteries.relevance(c, opts, Batteries.ROLE_SKILL_RELEVANCE, "skill", "relevant to",
                "the skill's instructions would help with this request", "the skill is unrelated to this request",
                prompt, skills);
        return new Verdict(p.selected(), p.dropped(), p.calibrated(), p.error());
    }
}
