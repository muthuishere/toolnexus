package io.github.muthuishere.toolnexus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Typed decisions (SPEC.md §8B) — the contract for a <b>judgment</b>, as {@link Tool} is the
 * contract for an <b>action</b>. A System One model takes a state plus pre-declared, typed
 * questions and returns calibrated answers with no free text. It has no messages, no tool
 * calling and no streaming, so it never enters the client loop.
 *
 * <p>A classifier <b>interprets; it never authorises</b>. Schema validity is not correctness: a
 * decision can be confidently wrong, and "cannot hallucinate" means only that the returned value
 * is in the declared schema. Numeric limits, permission checks and allowlists stay in code.
 * Nothing here is a security control.
 *
 * <pre>{@code
 * Classifier judge = Classifier.create(new Classifier.Options()
 *         .style("static")
 *         .model("typesafe/jev-1.13")
 *         .decisions(recorded));
 * Classifier.Decision d = judge.evaluate(state, Map.of(
 *         "risk", new Classifier.ScoreQuestion("How hard would this be to undo?", levels)));
 * double risk = d.score("risk").score();
 * }</pre>
 */
public final class Classifier {

    // ------------------------------------------------------------------ constants

    /** The System One endpoint base. */
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai/v1";
    /** The floating model alias. Pin it (e.g. {@code jev-1.13.0}) once thresholds are tuned. */
    public static final String DEFAULT_MODEL = "jev-latest";
    /** The NAME of the env var holding the credential — never the value. */
    public static final String DEFAULT_API_KEY_ENV = "TYPESAFE_API_KEY";
    /** Bounds ONE request (a classifier has no loop to bound). */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;

    /** Client-side cap on a choice's named options (§8B). */
    public static final int MAX_CHOICE_OPTIONS = 255;
    /** Bounds of a score rubric (§8B). */
    public static final int MIN_SCORE_LEVELS = 2;
    public static final int MAX_SCORE_LEVELS = 10;

    /**
     * The ABSOLUTE tolerance on {@code max|p - 1/n|}, compared INCLUSIVELY (§8B). Pinned across
     * every port; {@code examples/judge/near-uniform.json} pins both sides.
     */
    public static final double NEAR_UNIFORM_TOLERANCE = 0.05;

    /** The two {@code event()} strings this seam emits into the §8 {@code onMetric} sink. */
    public static final String METRIC_EVALUATE = "classifier.evaluate";
    public static final String METRIC_WARNING = "classifier.warning";

    /** The four backends. */
    public static final String STYLE_SYSTEMONE = "systemone";
    public static final String STYLE_LLM = "llm";
    public static final String STYLE_CUSTOM = "custom";
    public static final String STYLE_STATIC = "static";

    /** Statuses retried by default, alongside network throws. Same set as §8, plus 408. */
    private static final Set<Integer> RETRYABLE = Set.of(408, 429, 500, 502, 503, 504);

    // ------------------------------------------------------------------ failures

    /** Every failure out of this seam. Never carries a credential or an expanded header value. */
    public static final class ClassifierException extends RuntimeException {
        public ClassifierException(String message) { super(message); }
        public ClassifierException(String message, Throwable cause) { super(message, cause); }
    }

    // ------------------------------------------------------------------ questions

    /**
     * One pre-declared question. The set is CLOSED — sealed over the three §8B shapes, which
     * differ only in what {@code criteria} is on the wire: absent, an object, or an ordered array.
     */
    public sealed interface Question permits NoulQuestion, ChoiceQuestion, ScoreQuestion {
        /** The wire discriminator: {@code "noul" | "choice" | "score"}. */
        String type();

        /** The question's wire projection — what the canonical bytes are built from. */
        Map<String, Object> wire();

        /** Enforce the client-side limits, naming the offending question key. */
        default void validate(String key) {}
    }

    /**
     * Labels the true and false cases of a noul question. Absent and empty are DIFFERENT values
     * and both are preserved on the wire, which is why {@link NoulQuestion#criteria()} is
     * nullable: {@code null} omits {@code criteria} entirely, while a record of two empty strings
     * emits {@code {"false":"","true":""}}.
     *
     * <p>Named {@code whenTrue}/{@code whenFalse} because {@code true}/{@code false} are Java
     * keywords; the wire keys are {@code "true"}/{@code "false"}.
     */
    public record NoulCriteria(String whenTrue, String whenFalse) {}

    /**
     * The probability that a statement holds, one number in {@code 0..1}. It reports NO
     * confidence: the number IS the answer.
     */
    public record NoulQuestion(String instructions, NoulCriteria criteria) implements Question {
        public NoulQuestion(String instructions) { this(instructions, null); }

        @Override public String type() { return "noul"; }

        @Override public Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "noul");
            m.put("instructions", instructions);
            if (criteria != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("true", nullToEmpty(criteria.whenTrue()));
                c.put("false", nullToEmpty(criteria.whenFalse()));
                m.put("criteria", c);
            }
            return m;
        }
    }

    /**
     * One option from a named set, 1..255 options.
     *
     * <p><b>THE ENCODING OBLIGATION IS THE CALLER'S</b> (§8B, {@code docs/adr/0021} D1):
     * {@code criteria[id]} is the only thing that differentiates one option from another to the
     * model. Passing the id itself, an empty string, or one value repeated is schema-valid,
     * returns HTTP 200 and a well-formed distribution — and ranks at chance (measured: 17 apples
     * described by consequence, 0/1/0 described by id).
     */
    public record ChoiceQuestion(String instructions, Map<String, String> criteria) implements Question {
        public ChoiceQuestion {
            criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
        }

        @Override public String type() { return "choice"; }

        @Override public Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "choice");
            m.put("instructions", instructions);
            m.put("criteria", new LinkedHashMap<String, Object>(criteria));
            return m;
        }

        @Override public void validate(String key) {
            int n = criteria.size();
            if (n < 1 || n > MAX_CHOICE_OPTIONS) {
                throw new ClassifierException("classifier: question \"" + key + "\": a choice needs 1.."
                        + MAX_CHOICE_OPTIONS + " options, got " + n);
            }
        }
    }

    /**
     * A rating against an ORDERED rubric of 2..10 levels. The list order IS the level numbering,
     * so it is never sorted — a "sort everything" canonicaliser silently renumbers the rubric.
     */
    public record ScoreQuestion(String instructions, List<String> criteria) implements Question {
        public ScoreQuestion {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }

        @Override public String type() { return "score"; }

        @Override public Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "score");
            m.put("instructions", instructions);
            m.put("criteria", new ArrayList<Object>(criteria));
            return m;
        }

        @Override public void validate(String key) {
            int n = criteria.size();
            if (n < MIN_SCORE_LEVELS || n > MAX_SCORE_LEVELS) {
                throw new ClassifierException("classifier: question \"" + key + "\": a score needs "
                        + MIN_SCORE_LEVELS + ".." + MAX_SCORE_LEVELS + " ordered levels, got " + n);
            }
        }
    }

    /**
     * Build a {@link ChoiceQuestion} from any (name, description) pairs — a {@link Tool}, a skill,
     * an agent, an A2A card skill. The description must say what picking that option would MEAN;
     * see the encoding obligation on {@link ChoiceQuestion}.
     */
    public static ChoiceQuestion choiceOver(String instructions, Map<String, String> items) {
        return new ChoiceQuestion(instructions, items);
    }

    // ------------------------------------------------------------------ answers

    /**
     * One typed answer. The set is CLOSED and the wire's {@code type} field is the discriminator.
     *
     * <p>Named {@code DecisionAnswer} rather than {@code Answer} because §10 already owns
     * {@link Answer} (the suspension resolution). Same idea, different seam.
     */
    public sealed interface DecisionAnswer permits NoulAnswer, ChoiceAnswer, ScoreAnswer {
        /** The wire discriminator: {@code "noul" | "choice" | "score"}. */
        String type();
    }

    /** The probability that a statement holds. Carries NO confidence: the number is the answer. */
    public record NoulAnswer(double noul) implements DecisionAnswer {
        @Override public String type() { return "noul"; }
    }

    /**
     * One option from the offered set, with a probability for every offered option.
     *
     * @param nearUniform DERIVED from {@code probabilities} on decode and never read from the wire
     *                    (§8B). ADVISORY, NOT a correctness signal: it detects an encoding that
     *                    gave the model nothing to rank on, and cannot distinguish a good encoding
     *                    from a subtly wrong one. {@code calibrated} carries the same caveat.
     */
    public record ChoiceAnswer(String choice, Map<String, Double> probabilities,
                               double confidence, boolean nearUniform) implements DecisionAnswer {
        public ChoiceAnswer {
            probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
        }

        @Override public String type() { return "choice"; }
    }

    /**
     * A rating against the ordered rubric. {@code score} MAY fall between levels ({@code 1.21} is
     * a real answer) and is always within the rubric's bounds.
     */
    public record ScoreAnswer(double score, Map<String, String> legend,
                              Map<String, Double> probabilities, double confidence) implements DecisionAnswer {
        public ScoreAnswer {
            legend = legend == null ? Map.of() : Map.copyOf(legend);
            probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
        }

        @Override public String type() { return "score"; }

        /** The legend in LEVEL order, which the map itself loses ({@code "2"} sorts before {@code "10"}). */
        public List<String> levels() {
            List<String> keys = new ArrayList<>(legend.keySet());
            keys.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
            List<String> out = new ArrayList<>(keys.size());
            for (String k : keys) out.add(legend.get(k));
            return List.copyOf(out);
        }
    }

    /** The wire's usage block. {@code cost} is absent on some backends ({@code null}). */
    public record Usage(long inputTokens, long outputTokens, Double cost) {}

    /**
     * One answer per question, keyed by the CALLER's keys. The keys are addressing, not content:
     * they are never transmitted, so a key may be a tool, skill or agent name verbatim.
     *
     * @param model      echoes what actually answered, which may be more specific than what was asked for
     * @param calibrated {@code systemone} reports true; {@code llm} reports false unless it derived
     *                   the probabilities from provider token probabilities. A THRESHOLD TUNED
     *                   AGAINST ONE BACKEND DOES NOT TRANSFER TO ANOTHER.
     */
    public record Decision(String model, Map<String, DecisionAnswer> answers, Usage usage, boolean calibrated) {
        public Decision {
            answers = answers == null ? Map.of() : Map.copyOf(answers);
        }

        /** @throws ClassifierException if {@code key} is absent or is not a noul answer. */
        public NoulAnswer noul(String key) { return typed(key, NoulAnswer.class, "noul"); }

        /** @throws ClassifierException if {@code key} is absent or is not a choice answer. */
        public ChoiceAnswer choice(String key) { return typed(key, ChoiceAnswer.class, "choice"); }

        /** @throws ClassifierException if {@code key} is absent or is not a score answer. */
        public ScoreAnswer score(String key) { return typed(key, ScoreAnswer.class, "score"); }

        private <T extends DecisionAnswer> T typed(String key, Class<T> want, String wanted) {
            DecisionAnswer a = answers.get(key);
            if (a == null) {
                throw new ClassifierException("classifier: no answer \"" + key + "\" in this decision");
            }
            if (!want.isInstance(a)) {
                throw new ClassifierException("classifier: answer \"" + key + "\" is a " + a.type()
                        + " answer, not " + wanted);
            }
            return want.cast(a);
        }
    }

    /**
     * Whether a choice answer's probability map is indistinguishable from flat (§8B):
     *
     * <pre>nearUniform  ⇔  max over i of |p_i - 1/n|  &lt;=  0.05</pre>
     *
     * <p>{@code n} is the number of ENTRIES IN THE MAP and the values are taken AS RETURNED — not
     * renormalised, not sorted, not rounded; an offered option absent from the map counts as 0 by
     * not being an entry. The tolerance is ABSOLUTE (a relative band collapses below the wire's
     * two-decimal rounding on a 255-option roster) and the comparison is INCLUSIVE. {@code n == 1}
     * is trivially uniform. An EMPTY map has no distribution at all and is reported false.
     */
    public static boolean nearUniform(Map<String, Double> probabilities) {
        int n = probabilities == null ? 0 : probabilities.size();
        if (n == 0) return false;
        if (n == 1) return true;
        double target = 1.0 / n;
        for (double p : probabilities.values()) {
            if (Math.abs(p - target) > NEAR_UNIFORM_TOLERANCE) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the wire

    /**
     * The bytes the byte-identity claim covers: {@code model} + {@code questions}, keys sorted
     * recursively in ASCII order, arrays never reordered, compact separators, and
     * {@code <>&'"} plus non-ASCII transmitted RAW.
     *
     * <p>{@code state} is deliberately NOT here. It is transmitted verbatim as the host supplied
     * it and is outside the claim, because numbers do not canonicalise across languages
     * ({@code -0.0} renders four ways across our own seven runtimes). Do not re-widen this: a
     * caller who needs their state pinned canonicalises it themselves before handing it over.
     */
    public static byte[] canonicalRequest(String model, Map<String, Question> questions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("questions", questionsWire(questions));
        return Canon.write(body).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> questionsWire(Map<String, Question> questions) {
        Map<String, Object> qs = new LinkedHashMap<>();
        for (Map.Entry<String, Question> e : questions.entrySet()) {
            if (e.getValue() == null) {
                throw new ClassifierException("classifier: question \"" + e.getKey() + "\" is null");
            }
            qs.put(e.getKey(), e.getValue().wire());
        }
        return qs;
    }

    /**
     * Canonical JSON. HAND-ROLLED on purpose: Jackson sorts keys natively but formats numbers
     * wrongly — it writes {@code 0.0} for an integer {@code 0} and {@code 1.6716E-5} for
     * {@code 0.000016716}, while round-tripping the base fixture byte-perfectly
     * ({@code spikes/classifier/reports/99-verdict.md} §3). Keys sort with
     * {@link String#compareTo} (code-point order), NEVER a {@code Collator}; arrays are never
     * reordered; {@code <>&'"} and non-ASCII are emitted raw.
     */
    static final class Canon {
        private Canon() {}

        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            emit(v, sb);
            return sb.toString();
        }

        private static void emit(Object v, StringBuilder sb) {
            switch (v) {
                case null -> sb.append("null");
                case String s -> string(s, sb);
                case Boolean b -> sb.append(b.booleanValue());
                case Number n -> sb.append(number(n));
                case Map<?, ?> m -> {
                    sb.append('{');
                    boolean first = true;
                    TreeMap<String, Object> sorted = new TreeMap<>(); // String.compareTo = ASCII/code-point
                    for (Map.Entry<?, ?> e : m.entrySet()) sorted.put((String) e.getKey(), e.getValue());
                    for (Map.Entry<String, Object> e : sorted.entrySet()) {
                        if (!first) sb.append(',');
                        first = false;
                        string(e.getKey(), sb);
                        sb.append(':');
                        emit(e.getValue(), sb);
                    }
                    sb.append('}');
                }
                case List<?> l -> { // arrays are NEVER reordered: a rubric's order is its numbering
                    sb.append('[');
                    for (int i = 0; i < l.size(); i++) {
                        if (i > 0) sb.append(',');
                        emit(l.get(i), sb);
                    }
                    sb.append(']');
                }
                default -> throw new ClassifierException("classifier: not JSON: " + v.getClass().getName());
            }
        }

        /** ECMAScript-shortest number text: {@code 0}, {@code 1.21}, {@code 0.000016716}. */
        static String number(Number n) {
            if (n instanceof Integer || n instanceof Long || n instanceof Short
                    || n instanceof Byte || n instanceof java.math.BigInteger) {
                return n.toString();
            }
            double d = n.doubleValue();
            if (!Double.isFinite(d)) throw new ClassifierException("classifier: non-finite number");
            if (d == Math.rint(d) && Math.abs(d) < 1e21) {
                return BigDecimal.valueOf(d).setScale(0, RoundingMode.UNNECESSARY).toBigInteger().toString();
            }
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }

        private static void string(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    // <>&' and non-ASCII are NOT escaped: they go out raw (§8B).
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }

    // ------------------------------------------------------------------ options

    /**
     * One entry of the {@code static} corpus. Keyed on the canonical request AND the state:
     * several recorded entries legitimately share one questions payload and differ only in state
     * (the three guard bands of {@code examples/judge/decisions.json} do exactly that), so a
     * corpus keyed on the canonical request alone cannot tell them apart.
     *
     * @param response the recorded backend response body, verbatim JSON text
     */
    public record RecordedDecision(Object state, Map<String, Question> questions, String response) {}

    /**
     * Mirrors §8 {@link LlmClient.Options} field-for-field wherever a field makes sense, so a host
     * that has configured one has configured the other.
     */
    public static final class Options {
        /** {@code "systemone" | "llm" | "custom" | "static"}. Null ⇒ {@code "systemone"}. */
        public String style;
        /** The API base. Null ⇒ {@link #DEFAULT_BASE_URL}. OpenRouter serves this wire today. */
        public String baseUrl;
        /** Null ⇒ {@link #DEFAULT_MODEL}. Pin it once thresholds are tuned. */
        public String model;
        /** The NAME of the env var holding the credential, never the value — read at call time and
         * never logged. Null ⇒ {@link #DEFAULT_API_KEY_ENV}. §8's {@code apiKey} takes a VALUE;
         * this option deliberately does not. */
        public String apiKeyEnv;
        /** Extra request headers. Values expand {@code ${ENV_VAR}} from the environment AT CALL
         * TIME and are NEVER logged, identically to remote-MCP headers (§2). */
        public Map<String, String> headers;
        /** Bounds ONE request (a classifier has no loop to bound). Null ⇒ 10 s. */
        public Long timeoutMs;
        /** §8 Gap 2: the injectable transport. Scope is the classifier path only. */
        public HttpClient httpClient;
        /** Retries on transient errors (408/429/5xx/network). Null ⇒ 2. */
        public Integer retries;
        /** §8 Resilience: classify a failed attempt into {@link LlmClient.Tier#RETRY} or
         * {@link LlmClient.Tier#FAIL}. REUSES the client's {@link LlmClient.ErrorInfo}/{@code Tier}
         * and the {@code Retry-After} delay-seconds rule verbatim — there is no second retry
         * policy here, and no {@code "suspend"} tier either. */
        public Function<LlmClient.ErrorInfo, LlmClient.Tier> onError;
        /** §8 Gap 1: extra top-level keys shallow-merged into the request body after the
         * classifier builds its own — a {@code requestParams} key WINS on collision. */
        public Map<String, Object> requestParams;
        /** §8 Gap 1: receives the assembled body (AFTER the {@code requestParams} merge) and
         * returns the body to send. Returning null ⇒ unchanged. */
        public Function<Map<String, Object>, Map<String, Object>> bodyTransform;
        /** Emits {@code classifier.evaluate} events into the SAME §8 sink, and carries the
         * degenerate-criteria warning as {@code classifier.warning}. */
        public Consumer<LlmClient.MetricEvent> onMetric;
        /** The §8 client to emulate over. {@code style: "llm"} only. */
        public LlmClient client;
        /** The host's own function. {@code style: "custom"} only; every wire option is ignored. */
        public java.util.function.BiFunction<Object, Map<String, Question>, Decision> evaluate;
        /** The recorded corpus. {@code style: "static"} only. */
        public List<RecordedDecision> decisions;

        /** Environment lookup, for tests only — the process environment otherwise. */
        Function<String, String> env;

        public Options style(String v) { this.style = v; return this; }
        public Options baseUrl(String v) { this.baseUrl = v; return this; }
        public Options model(String v) { this.model = v; return this; }
        public Options apiKeyEnv(String v) { this.apiKeyEnv = v; return this; }
        public Options headers(Map<String, String> v) { this.headers = v; return this; }
        public Options timeoutMs(long v) { this.timeoutMs = v; return this; }
        public Options httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Options retries(int v) { this.retries = v; return this; }
        public Options onError(Function<LlmClient.ErrorInfo, LlmClient.Tier> v) { this.onError = v; return this; }
        public Options requestParams(Map<String, Object> v) { this.requestParams = v; return this; }
        public Options bodyTransform(Function<Map<String, Object>, Map<String, Object>> v) { this.bodyTransform = v; return this; }
        public Options onMetric(Consumer<LlmClient.MetricEvent> v) { this.onMetric = v; return this; }
        public Options client(LlmClient v) { this.client = v; return this; }
        public Options evaluate(java.util.function.BiFunction<Object, Map<String, Question>, Decision> v) { this.evaluate = v; return this; }
        public Options decisions(List<RecordedDecision> v) { this.decisions = v; return this; }
        Options env(Function<String, String> v) { this.env = v; return this; }
    }

    // ------------------------------------------------------------------ the seam

    private final Options opts;
    private final String style;
    private final String baseUrl;
    private final String model;
    private final String apiKeyEnv;
    private final long timeoutMs;
    private final int retries;
    private final HttpClient http;
    private final Function<String, String> env;
    private final Map<String, String> staticCorpus;
    /** Once-per-question-key set for the degenerate warning, so a per-turn judge floods nothing. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private Classifier(Options opts, Map<String, String> staticCorpus) {
        this.opts = opts;
        this.style = opts.style == null ? STYLE_SYSTEMONE : opts.style;
        this.baseUrl = opts.baseUrl == null ? DEFAULT_BASE_URL : opts.baseUrl;
        this.model = opts.model == null ? DEFAULT_MODEL : opts.model;
        this.apiKeyEnv = opts.apiKeyEnv == null ? DEFAULT_API_KEY_ENV : opts.apiKeyEnv;
        this.timeoutMs = opts.timeoutMs == null ? DEFAULT_TIMEOUT_MS : opts.timeoutMs;
        this.retries = opts.retries == null || opts.retries <= 0 ? 2 : opts.retries;
        this.http = opts.httpClient != null ? opts.httpClient : HttpClient.newHttpClient();
        this.env = opts.env != null ? opts.env : System::getenv;
        this.staticCorpus = staticCorpus;
    }

    /**
     * Build a classifier, applying the §8B defaults and rejecting a style whose required option is
     * missing BEFORE any call is made.
     */
    public static Classifier create(Options opts) {
        if (opts == null) opts = new Options();
        String style = opts.style == null ? STYLE_SYSTEMONE : opts.style;
        String model = opts.model == null ? DEFAULT_MODEL : opts.model;
        Map<String, String> corpus = null;
        switch (style) {
            case STYLE_SYSTEMONE -> { }
            case STYLE_LLM -> {
                if (opts.client == null) {
                    throw new ClassifierException("classifier: style \"llm\" requires client");
                }
            }
            case STYLE_CUSTOM -> {
                if (opts.evaluate == null) {
                    throw new ClassifierException("classifier: style \"custom\" requires evaluate");
                }
            }
            case STYLE_STATIC -> {
                corpus = new LinkedHashMap<>();
                List<RecordedDecision> recorded = opts.decisions == null ? List.of() : opts.decisions;
                for (int i = 0; i < recorded.size(); i++) {
                    RecordedDecision rec = recorded.get(i);
                    try {
                        corpus.put(staticKey(model, rec.state(), rec.questions()), rec.response());
                    } catch (RuntimeException e) {
                        throw new ClassifierException("classifier: recorded decision " + i + ": "
                                + e.getMessage(), e);
                    }
                }
            }
            default -> throw new ClassifierException("classifier: unknown style \"" + style + "\"");
        }
        return new Classifier(opts, corpus);
    }

    /**
     * The whole contract: a state plus typed questions in, a {@link Decision} out. Questions are
     * INDEPENDENT — one answer is never context for another.
     *
     * @param state a string, a map, or a list — whatever the host already has. Transmitted verbatim.
     */
    public Decision evaluate(Object state, Map<String, Question> questions) {
        long t0 = System.currentTimeMillis();
        if (questions == null || questions.isEmpty()) {
            throw new ClassifierException("classifier: no questions to evaluate");
        }
        // Limits are enforced CLIENT-SIDE, before the request: the caller finds out faster and more
        // legibly than from the backend's own 400. Keys are walked in sorted order so the same
        // malformed set always names the same key first.
        for (String key : sortedKeys(questions)) {
            Question q = questions.get(key);
            if (q == null) throw new ClassifierException("classifier: question \"" + key + "\" is null");
            try {
                q.validate(key);
            } catch (RuntimeException e) {
                emitError(t0, e);
                throw e;
            }
        }
        // Detection, never repair (ADR 0020/0021): the request goes out BYTE-UNCHANGED and the
        // warning is the entire observable effect.
        reportDegenerate(questions);

        Decision d;
        try {
            d = switch (style) {
                case STYLE_CUSTOM -> opts.evaluate.apply(state, questions);
                case STYLE_STATIC -> evaluateStatic(state, questions);
                case STYLE_LLM -> evaluateLlm(state, questions);
                default -> evaluateSystemOne(state, questions);
            };
        } catch (RuntimeException e) {
            emitError(t0, e);
            throw e;
        }
        emit(new LlmClient.MetricEvent.ClassifierEvaluate(
                d.model(), "ok", System.currentTimeMillis() - t0,
                d.usage() == null ? 0 : d.usage().inputTokens(),
                d.usage() == null ? 0 : d.usage().outputTokens(), null));
        return d;
    }

    private static List<String> sortedKeys(Map<String, Question> questions) {
        List<String> keys = new ArrayList<>(questions.keySet());
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    // ------------------------------------------------------------------ degenerate

    /**
     * Emit ONE warning per degenerate question key per classifier, naming the key, and change
     * nothing about the request. Repairing would invent option descriptions the caller did not
     * write, and the library has no way to know what the options mean.
     */
    private void reportDegenerate(Map<String, Question> questions) {
        for (String key : sortedKeys(questions)) {
            if (!(questions.get(key) instanceof ChoiceQuestion q)) continue;
            String reason = degenerateReason(q.criteria());
            if (reason == null) continue;
            if (!warned.add(key)) continue;
            emit(new LlmClient.MetricEvent.ClassifierWarning(key,
                    "classifier: question \"" + key + "\" has degenerate criteria (" + reason
                            + ") — every option reads the same to the model and the answer ranks at "
                            + "chance; describe what picking each option would MEAN (SPEC.md §8B)"));
        }
    }

    /**
     * The §8B predicate. Degenerate ⇔ ANY of: (1) every value empty, (2) every value equal to its
     * own key, (3) every value identical to every other ({@code n >= 2}). A single-option choice
     * ({@code n == 1}) is NEVER reported: there is nothing to differentiate — which is why the
     * {@code n < 2} gate comes first for all three rules.
     *
     * @return the human reason, or {@code null} when the criteria are fine
     */
    static String degenerateReason(Map<String, String> criteria) {
        if (criteria == null || criteria.size() < 2) return null;
        boolean allEmpty = true, allEqualKey = true, allIdentical = true;
        String first = null;
        boolean seen = false;
        for (Map.Entry<String, String> e : criteria.entrySet()) {
            String v = nullToEmpty(e.getValue());
            if (!v.isEmpty()) allEmpty = false;
            if (!v.equals(e.getKey())) allEqualKey = false;
            if (!seen) { first = v; seen = true; }
            else if (!v.equals(first)) allIdentical = false;
        }
        if (allEmpty) return "every description is empty";
        if (allEqualKey) return "every description is just its own option id";
        if (allIdentical) return "every description is identical";
        return null;
    }

    // ------------------------------------------------------------------ backends

    /**
     * Assemble the request: the canonical model + questions, plus state VERBATIM as the host
     * supplied it, then the §8 Gap 1 pipeline in §8 order (base → requestParams merge →
     * bodyTransform → marshal).
     */
    private String body(Object state, Map<String, Question> questions) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("model", model);
        b.put("questions", questionsWire(questions));
        b.put("state", state);
        if (opts.requestParams != null) b.putAll(opts.requestParams); // a requestParams key WINS
        if (opts.bodyTransform != null) {
            Map<String, Object> out = opts.bodyTransform.apply(b);
            if (out != null) b = out;
        }
        return Canon.write(b);
    }

    private static String staticKey(String model, Object state, Map<String, Question> questions) {
        return new String(canonicalRequest(model, questions), StandardCharsets.UTF_8)
                + "\u0000" + Canon.write(state);
    }

    private Decision evaluateStatic(Object state, Map<String, Question> questions) {
        String raw = staticCorpus.get(staticKey(model, state, questions));
        if (raw == null) {
            // Never a guess, never a neighbouring band (ADR 0020).
            throw new ClassifierException("classifier: static: no recorded decision for this request+state");
        }
        return toDecision(Json.toMap(raw));
    }

    private Decision evaluateSystemOne(Object state, Map<String, Question> questions) {
        return toDecision(Json.toMap(post(body(state, questions))));
    }

    /**
     * The one POST, with the §8 retry budget, reusing the client's {@code ErrorInfo}/{@code Tier}
     * classifier and the {@code Retry-After} delay-seconds rule verbatim.
     *
     * <p>NO CREDENTIAL VALUE AND NO EXPANDED HEADER VALUE APPEARS ON ANY PATH OUT OF HERE: an
     * authentication failure names the status and the endpoint, nothing else, and a 401/403 body
     * is never echoed back (a gateway happily reflects a bad Authorization header into its own
     * error text).
     */
    private String post(String payload) {
        String endpoint = baseUrl.replaceAll("/+$", "") + "/systemone";
        ClassifierException last = null;
        for (int attempt = 0; ; attempt++) {
            int status = 0;
            String bodyText = null;
            String retryAfter = null;
            Throwable thrown = null;
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                // Read at call time. Never logged, never returned, never in an error.
                String key = env.apply(apiKeyEnv);
                if (key != null && !key.isEmpty()) rb.header("Authorization", "Bearer " + key);
                if (opts.headers != null) {
                    // ${ENV_VAR} expands at call time, exactly as remote-MCP headers do (§2).
                    for (Map.Entry<String, String> e
                            : McpSource.expandEnvHeaders(opts.headers, env).entrySet()) {
                        if (e.getValue() != null) rb.header(e.getKey(), e.getValue());
                    }
                }
                HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                status = res.statusCode();
                if (status >= 200 && status < 300) return res.body();
                bodyText = res.body();
                retryAfter = res.headers().firstValue("retry-after").orElse(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ClassifierException("classifier: POST " + endpoint + ": interrupted", e);
            } catch (Exception e) {
                thrown = e;
            }
            last = thrown != null
                    ? new ClassifierException("classifier: POST " + endpoint + ": " + thrown, thrown)
                    : new ClassifierException("classifier: POST " + endpoint + ": HTTP " + status
                            + cause(status, bodyText));
            if (attempt >= retries) throw last;
            boolean retryable = thrown != null || RETRYABLE.contains(status);
            LlmClient.Tier tier = opts.onError != null
                    ? opts.onError.apply(new LlmClient.ErrorInfo(thrown, status, attempt, retryable))
                    : (retryable ? LlmClient.Tier.RETRY : LlmClient.Tier.FAIL);
            if (tier != LlmClient.Tier.RETRY) throw last;
            long delay = LlmClient.retryAfterDelayMs(retryAfter).orElse(500L << attempt);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw last;
            }
        }
    }

    /**
     * Surface a backend's reported cause INTACT so a caller can tell a limit error from a
     * transport fault — EXCEPT on an authentication status, whose body routinely reflects the
     * credential or the header that was sent.
     */
    private static String cause(int status, String body) {
        if (status == 401 || status == 403 || body == null) return "";
        String s = body.strip();
        if (s.isEmpty()) return "";
        if (s.length() > 200) s = s.substring(0, 200) + "…";
        return ": " + s;
    }

    /**
     * Render the three question types as ONE structured-output call on any §8 client — the
     * vendor-neutral fallback, so a host with no System One credential runs the same questions on
     * a cheap chat model. {@code calibrated} is FALSE: the numbers are the model's self-report,
     * not token probabilities.
     */
    private Decision evaluateLlm(Object state, Map<String, Question> questions) {
        String prompt = "Answer every question about the state below. Questions are INDEPENDENT: "
                + "one answer is never context for another.\n\n"
                + "STATE:\n" + Canon.write(state) + "\n\nQUESTIONS:\n"
                + Canon.write(questionsWire(questions)) + "\n\n"
                + "Reply with JSON only, no prose and no code fence, shaped exactly:\n"
                + "{\"answers\":{\"<key>\":{\"type\":\"noul\",\"noul\":0.0}}}\n"
                + "A \"noul\" answer is {\"type\":\"noul\",\"noul\":<0..1>}. A \"choice\" answer is "
                + "{\"type\":\"choice\",\"choice\":\"<one offered option id>\",\"probabilities\":"
                + "{\"<every offered option id>\":<0..1>},\"confidence\":<0..1>}. "
                + "A \"score\" answer is {\"type\":\"score\",\"score\":<a number within the rubric "
                + "bounds, fractional allowed>,\"legend\":{\"0\":\"<level 0>\",…},\"probabilities\":"
                + "{\"0\":<0..1>,…},\"confidence\":<0..1>}.";
        LlmClient.RunResult run = opts.client.run(prompt, null);
        Decision d = toDecision(Json.toMap(firstJsonObject(run.text)));
        // The model reports no calibration and none is derived here. Never repaired, never
        // asserted as calibrated (ADR 0020).
        return new Decision(
                d.model() == null || d.model().isEmpty() ? model : d.model(),
                d.answers(),
                new Usage(run.usage.promptTokens, run.usage.completionTokens, null),
                false);
    }

    /**
     * Extract the outermost JSON object from a model reply, which may arrive wrapped in a code
     * fence or prose. It does NOT repair malformed JSON — an unparseable answer is no answer.
     */
    static String firstJsonObject(String s) {
        int start = s == null ? -1 : s.indexOf('{');
        int end = s == null ? -1 : s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ClassifierException("classifier: llm: no JSON object in the reply");
        }
        return s.substring(start, end + 1);
    }

    // ------------------------------------------------------------------ decoding

    /**
     * The discriminated decode — one arrow switch on the wire's {@code type} field, and the single
     * place {@code nearUniform} is derived.
     */
    static Decision toDecision(Map<String, Object> raw) {
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        Object rawAnswers = raw.get("answers");
        if (rawAnswers instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> a)) {
                    throw new ClassifierException("classifier: answer \"" + key + "\" is not an object");
                }
                Map<String, Object> ans = asStringMap(a);
                String type = String.valueOf(ans.get("type"));
                answers.put(key, switch (type) {
                    case "noul" -> new NoulAnswer(num(ans.get("noul")));
                    case "choice" -> {
                        Map<String, Double> p = numbers(ans.get("probabilities"));
                        yield new ChoiceAnswer(str(ans.get("choice")), p,
                                num(ans.get("confidence")), nearUniform(p));
                    }
                    case "score" -> new ScoreAnswer(num(ans.get("score")),
                            strings(ans.get("legend")), numbers(ans.get("probabilities")),
                            num(ans.get("confidence")));
                    default -> throw new ClassifierException(
                            "classifier: answer \"" + key + "\": unknown type \"" + type + "\"");
                });
            }
        }
        Usage usage = new Usage(0, 0, null);
        if (raw.get("usage") instanceof Map<?, ?> u) {
            Map<String, Object> um = asStringMap(u);
            usage = new Usage((long) num(um.get("input_tokens")), (long) num(um.get("output_tokens")),
                    um.get("cost") instanceof Number c ? c.doubleValue() : null);
        }
        // Absent ⇒ true: the systemone wire reports calibration by being itself. A backend that is
        // not calibrated says so explicitly.
        boolean calibrated = !(raw.get("calibrated") instanceof Boolean b) || b;
        return new Decision(str(raw.get("model")), answers, usage, calibrated);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** A zero probability stays an ENTRY — it is never dropped for being zero. */
    private static Map<String, Double> numbers(Object v) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), num(e.getValue()));
            }
        }
        return out;
    }

    private static Map<String, String> strings(Object v) {
        Map<String, String> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    // ------------------------------------------------------------------ metrics

    private void emit(LlmClient.MetricEvent ev) {
        if (opts.onMetric != null) opts.onMetric.accept(ev);
    }

    private void emitError(long t0, RuntimeException e) {
        emit(new LlmClient.MetricEvent.ClassifierEvaluate(
                model, "error", System.currentTimeMillis() - t0, 0, 0, e.getMessage()));
    }
}
