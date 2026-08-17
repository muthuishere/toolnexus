package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Loop — a live execution of an Agent, and the completion gate that stops it claiming
 * {@code done} too early. A layer over the shipped §8 client: nothing here changes existing
 * behaviour.
 *
 * <p>The placement law this encodes:
 * <pre>
 *   AgentSpec (the harness) answers "MAY it?"     — capability, ceilings.  Per problem.
 *   RunOptions              answers "with WHAT?"  — model for this call.   Per call.
 *   Loop                    answers "DID it?"     — status, turns.         Observed.
 *   none of them            answers "is it RIGHT?"— a tool, skill or agent.
 * </pre>
 * So {@code Loop} takes no options: it is read, not configured.
 *
 * <p><b>Why this class lives in the root package rather than {@code .agents}:</b>
 * {@link LlmClient.RunResult} is immutable and its constructors are package-private, and the gate
 * must produce a derived result (the accumulated tool calls; the {@code incomplete}/{@code limit}
 * stop). Living here lets it rebuild one WITHOUT widening {@code RunResult}'s public surface —
 * which would be a per-port API divergence for a purely internal need.
 */
public final class Loop {

    /**
     * A POLICY check on a tool call — "may it?", never "is it right?". Return {@code "allow"} (or
     * null) to permit; any other string DENIES with that reason.
     */
    @FunctionalInterface
    public interface Guardrail {
        String check(LlmClient.BeforeToolEvent ev);
    }

    /** What a completion verifier returns. */
    public static final class Verdict {
        public final boolean ok;
        public final String reason;

        public Verdict(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason == null ? "" : reason;
        }

        public static Verdict pass() { return new Verdict(true, ""); }
        public static Verdict fail(String reason) { return new Verdict(false, reason); }
    }

    /**
     * The gate that stops an agent claiming {@code done} before its work verifies. It lives on the
     * agent spec, so it TRAVELS with the agent through delegation — which a host-side retry loop
     * cannot do.
     */
    public static final class Completion {
        /** Judges the run. Receives the tool calls ACCUMULATED across attempts. */
        public final Function<LlmClient.RunResult, Verdict> verify;
        /** REQUIRED. An unbounded verify loop is a denial-of-service on the caller's bill. */
        public final int maxAttempts;

        public Completion(Function<LlmClient.RunResult, Verdict> verify, int maxAttempts) {
            this.verify = verify;
            this.maxAttempts = maxAttempts;
        }
    }

    /**
     * What varies PER CALL. {@code model} is here — not on the spec's default and not on the
     * loop — so the same conversation may change model between turns.
     */
    public static final class RunOptions {
        /** Overrides the agent's model for this call only. Null ⇒ the agent's. */
        public String model;

        public RunOptions model(String v) { this.model = v; return this; }
    }

    /**
     * What a run reports. {@code status} reuses the SHIPPED vocabulary — no new status strings are
     * minted (SPEC.md pins TaskStatus identical across ports).
     */
    public static final class Outcome {
        public final String text;
        public final String status;      // done | incomplete | pending | error
        /** Named whenever {@code status} is not {@code done} — never a silent stop. */
        public final String stoppedBy;
        public final int attempts;
        public final int turns;
        public final LlmClient.RunResult result;

        Outcome(String text, String status, String stoppedBy, int attempts, int turns,
                LlmClient.RunResult result) {
            this.text = text;
            this.status = status;
            this.stoppedBy = stoppedBy;
            this.attempts = attempts;
            this.turns = turns;
            this.result = result;
        }
    }

    /** What the gate calls to run one attempt. */
    @FunctionalInterface
    public interface Ask {
        LlmClient.RunResult run(String prompt);
    }

    private final LlmClient.Options options;
    private final Toolkit toolkit;
    private final String soul;
    private final Completion completion;
    private final List<Guardrail> guardrails;
    private final LlmClient.Hooks hooks;
    private int turns;
    private String status = "idle";

    /**
     * Takes client OPTIONS rather than a built client, because a per-call {@code model} override
     * must be able to change the model — which is fixed when a client is constructed.
     */
    public Loop(LlmClient.Options options, Toolkit toolkit, String soul,
                List<Guardrail> guardrails, LlmClient.Hooks hooks, Completion completion) {
        this.options = options;
        this.toolkit = toolkit;
        this.soul = soul;
        this.guardrails = guardrails;
        this.hooks = hooks;
        this.completion = completion;
    }

    /** Observed, never set by the caller. */
    public String status() { return status; }

    /** Model round trips this loop has spent. */
    public int turns() { return turns; }

    public Outcome run(String prompt) { return run(prompt, new RunOptions()); }

    public Outcome run(String prompt, RunOptions opts) {
        LlmClient client = LlmClient.create(clientOptions(opts));
        status = "running";

        int[] attempts = {0};
        List<Object>[] history = new List[]{null};
        Ask ask = text -> {
            attempts[0]++;
            LlmClient.RunResult r = client.run(text, toolkit, history[0]);
            turns += r.turns;
            history[0] = r.messages;
            return r;
        };

        LlmClient.RunResult r;
        try {
            r = runGated(ask, prompt, completion);
        } catch (RuntimeException e) {
            status = "error";
            throw e;
        }

        if (r.status != null && !"done".equals(r.status)) {
            status = r.status;
            String stoppedBy = "completion".equals(r.limit) ? r.text : "run reported " + r.status;
            return new Outcome(r.text, r.status, stoppedBy, attempts[0], turns, r);
        }
        status = "idle";
        return new Outcome(r.text, "done", null, attempts[0], turns, r);
    }

    /**
     * Applies a per-call model override via {@code requestParams} ({@code model} is not in the
     * forbidden set — the client forbids only messages/tools/stream).
     */
    private LlmClient.Options clientOptions(RunOptions opts) {
        LlmClient.Options o = copyOptions(options);
        if (soul != null && !soul.isEmpty() && (o.systemPrompt == null || o.systemPrompt.isEmpty())) {
            o.systemPrompt = soul;
        }
        o.hooks = guardedHooks(guardrails, hooks != null ? hooks : o.hooks);
        if (opts != null && opts.model != null && !opts.model.isEmpty()) {
            Map<String, Object> rp = new java.util.LinkedHashMap<>();
            if (o.requestParams != null) rp.putAll(o.requestParams);
            rp.put("model", opts.model);
            o.requestParams = rp;
        }
        return o;
    }

    /**
     * {@code LlmClient.Options} has no copy method, so mirror the fields — the loop must never
     * mutate the caller's object, since one Options may back several loops.
     */
    private static LlmClient.Options copyOptions(LlmClient.Options o) {
        LlmClient.Options c = new LlmClient.Options();
        c.baseUrl = o.baseUrl; c.style = o.style; c.model = o.model; c.apiKey = o.apiKey;
        c.headers = o.headers; c.systemPrompt = o.systemPrompt; c.maxTurns = o.maxTurns;
        c.hooks = o.hooks; c.retries = o.retries; c.retryBaseMs = o.retryBaseMs;
        c.timeoutMs = o.timeoutMs; c.store = o.store; c.onMetric = o.onMetric;
        c.waitFor = o.waitFor; c.requestParams = o.requestParams;
        c.bodyTransform = o.bodyTransform; c.httpClient = o.httpClient; c.onError = o.onError;
        return c;
    }

    /**
     * Compiles guardrails into one {@code beforeTool} with FIRST-DENY-WINS, composed ahead of any
     * hook already set. No guardrails ⇒ {@code hooks} is returned untouched, so absent is
     * byte-identical.
     */
    public static LlmClient.Hooks guardedHooks(List<Guardrail> guardrails, LlmClient.Hooks hooks) {
        if (guardrails == null || guardrails.isEmpty()) return hooks;
        final Function<LlmClient.BeforeToolEvent, LlmClient.ToolOverride> prior =
                hooks != null ? hooks.beforeTool : null;

        LlmClient.Hooks merged = new LlmClient.Hooks();
        if (hooks != null) {
            merged.beforeLLM = hooks.beforeLLM;
            merged.afterLLM = hooks.afterLLM;
            merged.afterTool = hooks.afterTool;
        }
        merged.beforeTool = ev -> {
            for (Guardrail g : guardrails) {
                String verdict = g.check(ev);
                if (verdict != null && !verdict.isEmpty() && !"allow".equals(verdict)) {
                    return new LlmClient.ToolOverride(
                            null, new ToolResult("denied: " + verdict, true, null));
                }
            }
            return prior != null ? prior.apply(ev) : null;
        };
        return merged;
    }

    /**
     * The built-in completion verifier. Reads the SHIPPED {@code todowrite} builtin's result
     * metadata and requires every item to be checked.
     *
     * <p>Structural, not domain: it counts unchecked boxes and never learns what a todo means, so
     * the loop stays domain-blind. No plan declared ⇒ nothing to verify ⇒ pass, so the gate never
     * punishes an agent that does not use the builtin.
     */
    public static Verdict allTodosDone(LlmClient.RunResult result) {
        for (int i = result.toolCalls.size() - 1; i >= 0; i--) {
            LlmClient.ToolCall call = result.toolCalls.get(i);
            if (!"todowrite".equals(call.name)) continue;
            Object raw = call.metadata == null ? null : call.metadata.get("todos");
            if (!(raw instanceof List<?> todos)) return Verdict.pass();

            List<String> open = new ArrayList<>();
            for (Object item : todos) {
                if (!(item instanceof Map<?, ?> m)) continue;
                if (!Boolean.TRUE.equals(m.get("completed"))) {
                    Object text = m.get("text");
                    open.add(text == null ? "" : String.valueOf(text));
                }
            }
            return open.isEmpty()
                    ? Verdict.pass()
                    : Verdict.fail(open.size() + " item(s) still open: " + String.join("; ", open));
        }
        return Verdict.pass();
    }

    /**
     * Wraps a client run with the completion gate. SHARED by the standalone loop and the §7D
     * runtime turn, so a delegated child gets exactly the same guarantee as a directly-driven one.
     *
     * <p>Rule 2 in force: a run that is {@code pending} (suspended on a human) or otherwise
     * non-done already carries its own reason, so the gate never re-judges it. That keeps
     * {@code pending} and {@code incomplete} distinct — the caller can always tell whether it owes
     * an Answer or a fix.
     */
    public static LlmClient.RunResult runGated(Ask ask, String prompt, Completion completion) {
        if (completion == null) return ask.run(prompt);
        if (completion.maxAttempts < 1) {
            throw new IllegalArgumentException("toolnexus: Completion.maxAttempts must be >= 1");
        }
        if (completion.verify == null) {
            throw new IllegalArgumentException("toolnexus: Completion.verify is required");
        }

        List<LlmClient.ToolCall> accumulated = new ArrayList<>();
        LlmClient.RunResult last = null;
        String reason = "";

        for (int attempt = 1; attempt <= completion.maxAttempts; attempt++) {
            String text = attempt == 1
                    ? prompt
                    : "Your work did not verify: " + reason + ". Fix it and finish.";
            LlmClient.RunResult r = ask.run(text);

            // The gate judges the ACCUMULATED work, so an agent cannot escape it by declining to
            // re-declare its plan on a retry.
            accumulated.addAll(r.toolCalls);
            r = rebuild(r, r.text, new ArrayList<>(accumulated), r.status, r.limit);
            last = r;

            if (r.status != null && !"done".equals(r.status)) {
                // The run stopped for its own reason (suspension, budget). If the gate was
                // mid-retry the caller must learn BOTH — otherwise a budget stop masks the
                // verification failure and they never see why it was looping.
                if (!reason.isEmpty() && !"pending".equals(r.status)) {
                    r = rebuild(r, r.text + " [while verifying: attempt " + attempt
                            + " last failed: " + reason + "]", r.toolCalls, r.status, r.limit);
                }
                return r;
            }

            Verdict verdict = completion.verify.apply(r);
            if (verdict.ok) return r;
            reason = verdict.reason.isEmpty() ? "unspecified" : verdict.reason;
        }

        // Structured, not prose: `limit` is how a caller (and the §7D runtime) tells WHICH limit
        // stopped the run. `text` carries the human reason.
        return rebuild(last,
                "completion.verify failed " + completion.maxAttempts + "x: " + reason,
                last.toolCalls, "incomplete", "completion");
    }

    /** RunResult is immutable, so a "modified" result is a new one carrying the rest verbatim. */
    private static LlmClient.RunResult rebuild(LlmClient.RunResult r, String text,
                                               List<LlmClient.ToolCall> toolCalls,
                                               String status, String limit) {
        return new LlmClient.RunResult(text, r.messages, toolCalls, r.turns, r.usage, r.model,
                status, r.pending, limit);
    }
}
