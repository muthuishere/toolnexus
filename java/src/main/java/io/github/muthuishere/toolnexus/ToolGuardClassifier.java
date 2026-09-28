package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Batteries.OnError;
import io.github.muthuishere.toolnexus.Judge.Answer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Rates how risky one tool call is to run without a human approving it (SPEC §8B Batteries).
 * One {@code score} question {@code risk} over {@link #RISK_RUBRIC}: missing ⇒ ask; unsure ⇒ ask;
 * {@code < askAt} (1.5) ⇒ allow; {@code < denyAt} (2.5) ⇒ ask; else deny. Advisory only.
 */
public final class ToolGuardClassifier {

    /** The default 4-level risk rubric (contract). */
    public static final List<String> RISK_RUBRIC = List.of(
            "harmless: it only reads or looks something up and changes nothing",
            "reversible: it changes local or draft state that is easy to undo",
            "consequential: it sends, publishes, deploys or changes shared state that is hard to undo",
            "destructive: it deletes data, moves money, touches credentials or cannot be undone");

    static final String RISK_INSTRUCTIONS =
            "How risky is it to run the call described by `tool_name` with `arguments` without a human approving it?";

    public static final class Options extends Batteries.PolicyOptions<Options> {
        public Double askAt;
        public Double denyAt;

        public Options askAt(double v) { this.askAt = v; return this; }
        public Options denyAt(double v) { this.denyAt = v; return this; }
    }

    /** The call being rated; {@code description} may be null. */
    public record Call(String name, Map<String, Object> arguments, String description) {
        public Call(String name, Map<String, Object> arguments) { this(name, arguments, null); }
    }

    /** {@code action} allow | ask | deny; {@code risk} null when absent; {@code error} null unless it failed. */
    public record Verdict(String action, String reason, Double risk, boolean sure, boolean calibrated, String error) {}

    private final Classifier c;
    private final Options opts;
    private final double askAt, denyAt;

    public ToolGuardClassifier(Classifier c, Options o) {
        Batteries.requireOnError("ToolGuard", o == null ? null : o.onError);
        this.c = c;
        this.opts = o;
        this.askAt = o.askAt != null ? o.askAt : 1.5;
        this.denyAt = o.denyAt != null ? o.denyAt : 2.5;
    }

    public Verdict check(Call call) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool_name", call.name());
        data.put("arguments", call.arguments() == null ? Map.of() : call.arguments());
        if (call.description() != null && !call.description().isEmpty()) data.put("tool_description", call.description());
        Object st = Batteries.state(Batteries.roleOr(opts.role, Batteries.ROLE_TOOL_GUARD), data);
        Batteries.Asked a;
        try {
            a = Batteries.ask(c, st, List.of(Judge.score("risk", RISK_INSTRUCTIONS, RISK_RUBRIC)), opts.bands);
        } catch (RuntimeException e) {
            return new Verdict(opts.onError == OnError.OPEN ? "allow" : "deny", "classifier error",
                    null, false, false, Batteries.message(e));
        }
        Answer x = a.answers().get("risk");
        if (x == null) return new Verdict("ask", "missing answer", null, false, a.calibrated(), null);
        double v = x.value();
        String action, reason;
        if (!x.sure()) { action = "ask"; reason = "uncertain"; }
        else if (v < askAt) { action = "allow"; reason = "low risk"; }
        else if (v < denyAt) { action = "ask"; reason = "medium risk"; }
        else { action = "deny"; reason = "high risk"; }
        return new Verdict(action, reason, v, x.sure(), a.calibrated(), null);
    }

    /**
     * A {@code beforeTool} hook: allow delegates to {@code next}; deny short-circuits; ask
     * short-circuits with a §10 approval {@link Request}. {@code next} may be null.
     */
    public Function<LlmClient.BeforeToolEvent, LlmClient.ToolOverride> asHook(
            Function<LlmClient.BeforeToolEvent, LlmClient.ToolOverride> next) {
        return ev -> {
            Map<String, Object> args = ev.args() == null ? Map.of() : ev.args();
            Verdict v = check(new Call(ev.name(), args));
            switch (v.action()) {
                case "allow":
                    return next != null ? next.apply(ev) : null;
                case "deny":
                    return LlmClient.ToolOverride.withResult(ToolResult.error("denied by tool guard: " + v.reason()));
                default:
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("tool", ev.name());
                    data.put("arguments", args);
                    data.put("reason", v.reason());
                    data.put("risk", v.risk());
                    Request req = new Request("toolguard:" + (ev.id() == null ? "" : ev.id()), "approval",
                            "Approve the call to " + ev.name() + "? (" + v.reason() + ")", null, data, null);
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("pending", req);
                    return LlmClient.ToolOverride.withResult(ToolResult.error("approval required: " + ev.name(), meta));
            }
        };
    }
}
