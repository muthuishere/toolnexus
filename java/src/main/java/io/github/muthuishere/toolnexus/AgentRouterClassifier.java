package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Judge.Answer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes a task to an agent (SPEC §8B Batteries): one {@code choice} {@code agent} per level of
 * the host's agent tree; a picked node with {@code agents} descends. Unsure, missing or error at
 * any level ⇒ {@code fallback}. No hook: the host dispatches on {@code agent}.
 */
public final class AgentRouterClassifier {

    public static final class Options extends Batteries.BaseOptions<Options> {}

    /** An agent, or a group of agents when {@code agents} is non-empty. */
    public record Node(String name, String description, List<Node> agents) {
        public Node(String name, String description) { this(name, description, List.of()); }

        public Node {
            agents = agents == null ? List.of() : List.copyOf(agents);
        }
    }

    /** {@code probabilities} is the last level asked (null when missing or failed). */
    public record Verdict(String agent, List<String> path, boolean sure, Map<String, Double> probabilities,
                          boolean calibrated, String error) {}

    private final Classifier c;
    private final Options opts;

    public AgentRouterClassifier(Classifier c, Options o) {
        this.c = c;
        this.opts = o == null ? new Options() : o;
    }

    public Verdict pick(String task, List<Node> agents, String fallback) {
        List<String> path = new ArrayList<>();
        boolean calibrated = true;
        Map<String, Double> probs = null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        Object st = Batteries.state(Batteries.roleOr(opts.role, Batteries.ROLE_AGENT_ROUTER), data);
        List<Node> level = agents == null ? List.of() : agents;
        while (!level.isEmpty()) {
            Map<String, String> options = new LinkedHashMap<>();
            for (Node n : level) options.put(n.name(), n.description());
            Batteries.Asked a;
            try {
                a = Batteries.ask(c, st, List.of(Judge.choice("agent", "Which agent should handle `task`?", options)), opts.bands);
            } catch (RuntimeException e) {
                return new Verdict(fallback, path, false, probs, false, Batteries.message(e));
            }
            calibrated = calibrated && a.calibrated();
            Answer x = a.answers().get("agent");
            if (x == null) return new Verdict(fallback, path, false, null, calibrated, null);
            probs = x.raw() instanceof Classifier.ChoiceAnswer ca ? ca.probabilities() : null;
            if (!x.sure()) return new Verdict(fallback, path, false, probs, calibrated, null);
            Node picked = null;
            for (Node n : level) if (n.name().equals(x.choice())) picked = n;
            if (picked == null) return new Verdict(fallback, path, false, probs, calibrated, null);
            path.add(picked.name());
            if (picked.agents().isEmpty()) return new Verdict(picked.name(), path, true, probs, calibrated, null);
            level = picked.agents();
        }
        return new Verdict(fallback, path, false, probs, calibrated, null);
    }
}
