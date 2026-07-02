package io.github.muthuishere.toolnexus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Unified LLM client — the host loop. Give it a base URL + a style
 * ({@code "openai" | "anthropic"}) and it runs the tool-calling agent loop against
 * a {@link Toolkit}. Mirrors the JS reference ({@code js/src/client.ts}).
 */
public final class LlmClient {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** HTTP statuses that are retried (alongside IOExceptions). Mirrors JS {@code RETRYABLE}. */
    private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504);

    public static final class Options {
        public String baseUrl;
        public String style; // "openai" | "anthropic"
        public String model;
        public String apiKey;          // from env if null
        public Map<String, String> headers;
        public String systemPrompt;
        public Integer maxTurns;       // default 10
        public Hooks hooks;            // optional lifecycle middleware; null = no hooks
        /** Retries on transient LLM errors (429/5xx/network). Default 2. */
        public Integer retries;        // default 2
        /** Base backoff in ms (exponential + jitter). Default 500. */
        public Integer retryBaseMs;    // default 500
        /** Whole-run deadline in ms; aborts the run (and its in-flight request) when exceeded. */
        public Long timeoutMs;         // optional; null = no deadline
        /** Conversation provider for {@link LlmClient#ask}. Default: in-memory (process lifetime).
         * Supply a file/db store to persist conversations across processes. */
        public ConversationStore store; // optional; null = in-memory default
        /** Observability sink — receives semantic {@link MetricEvent}s as the loop runs (§8
         * Observability). Forward to statsd/logs/OTel/anything. No cost when unset. The same
         * events also feed the built-in Prometheus registry ({@link LlmClient#metrics()}). */
        public Consumer<MetricEvent> onMetric; // optional; null = no sink

        public Options baseUrl(String v) { this.baseUrl = v; return this; }
        public Options style(String v) { this.style = v; return this; }
        public Options model(String v) { this.model = v; return this; }
        public Options apiKey(String v) { this.apiKey = v; return this; }
        public Options headers(Map<String, String> v) { this.headers = v; return this; }
        public Options systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Options maxTurns(int v) { this.maxTurns = v; return this; }
        public Options hooks(Hooks v) { this.hooks = v; return this; }
        public Options retries(int v) { this.retries = v; return this; }
        public Options retryBaseMs(int v) { this.retryBaseMs = v; return this; }
        public Options timeoutMs(long v) { this.timeoutMs = v; return this; }
        public Options store(ConversationStore v) { this.store = v; return this; }
        public Options onMetric(Consumer<MetricEvent> v) { this.onMetric = v; return this; }
    }

    // ------------------------------------------------------------------
    // Observability (§8) — semantic metric events + a zero-dependency
    // Prometheus registry fed by the same events. Mirrors the JS `MetricEvent`
    // union / `MetricsRegistry` (js/src/client.ts). Field names use idiomatic
    // Java camelCase (promptTokens/isError/toolCalls) — NOT byte-identical to
    // the JS event object; only the `metrics()` Prometheus TEXT is byte-identical.
    // ------------------------------------------------------------------

    /**
     * Semantic observability events (§8). NOT counter/histogram primitives — readable records the
     * host can forward anywhere. The same events also feed the built-in Prometheus registry
     * ({@link #metrics()}). Modeled as a sealed interface (the type is the discriminator; the
     * {@link #event()} string mirrors the JS {@code event} field for convenience).
     */
    public sealed interface MetricEvent permits MetricEvent.Llm, MetricEvent.Tool, MetricEvent.Run {
        /** The event kind: {@code "llm"}, {@code "tool"}, or {@code "run"}. */
        String event();

        /** One LLM round trip. */
        record Llm(String model, String status, long ms, long promptTokens, long completionTokens)
                implements MetricEvent {
            @Override public String event() { return "llm"; }
        }

        /** One tool call. */
        record Tool(String tool, String source, boolean isError, long ms) implements MetricEvent {
            @Override public String event() { return "tool"; }
        }

        /** One completed {@code run}/{@code ask}. {@code error} is null on success. */
        record Run(String model, int turns, int toolCalls, long totalTokens, long ms, String error)
                implements MetricEvent {
            @Override public String event() { return "run"; }
        }
    }

    // ------------------------------------------------------------------
    // Conversation memory (§8 "Conversation memory") — where ask() remembers
    // transcripts. Mirrors the JS `ConversationStore` / `InMemoryConversationStore`
    // (js/src/client.ts). Shaped like the A2A TaskStore for consistency: an
    // interface + an in-memory default, keyed by a string id, nullable get().
    // ------------------------------------------------------------------

    /**
     * Where {@link #ask} conversations are remembered — two methods. Ship the in-memory
     * default; implement this for a file/db/redis provider to persist across processes.
     */
    public interface ConversationStore {
        /** Return the stored transcript for {@code id}, or {@code null} if none. */
        List<Object> get(String id);

        /** Persist the (updated) transcript for {@code id}. */
        void save(String id, List<Object> messages);
    }

    /** Default conversation provider — keeps transcripts in memory for the client's lifetime. */
    public static final class InMemoryConversationStore implements ConversationStore {
        private final Map<String, List<Object>> map = new ConcurrentHashMap<>();

        @Override
        public List<Object> get(String id) {
            List<Object> m = map.get(id);
            return m != null ? new ArrayList<>(m) : null;
        }

        @Override
        public void save(String id, List<Object> messages) {
            map.put(id, new ArrayList<>(messages));
        }
    }

    /** Thrown when a run exceeds {@link Options#timeoutMs} (or its in-flight request times out). */
    public static final class TimeoutException extends RuntimeException {
        public TimeoutException(String message) { super(message); }
    }

    // ------------------------------------------------------------------
    // Hooks (lifecycle middleware) — see SPEC.md §8 "Hooks". Mirrors the JS
    // `Hooks` interface (js/src/client.ts). Each callback is optional: a null
    // field on the Hooks object = skip that callback. Where JS callbacks are
    // async, the Java equivalents run synchronously on the calling thread.
    // ------------------------------------------------------------------

    /** Event passed to {@link Hooks#beforeLLM}: the about-to-be-sent request. */
    public record BeforeLLMEvent(List<Object> messages, List<Map<String, Object>> tools,
                                 String model, int turn) {}

    /** Return value of {@link Hooks#beforeLLM}: non-null fields replace the request's. */
    public record LLMOverride(List<Object> messages, List<Map<String, Object>> tools) {}

    /** Event passed to {@link Hooks#afterLLM}: the raw provider response (carries usage). */
    public record AfterLLMEvent(Map<String, Object> response, String model, int turn) {}

    /** Event passed to {@link Hooks#beforeTool}: the call the model wants to make. */
    public record BeforeToolEvent(String name, Map<String, Object> args, String id, int turn) {}

    /** Event passed to {@link Hooks#afterTool}: the call plus the result it produced. */
    public record AfterToolEvent(String name, Map<String, Object> args, ToolResult result,
                                 String id, int turn) {}

    /**
     * Return value of {@link Hooks#beforeTool} / {@link Hooks#afterTool}.
     * <ul>
     *   <li>{@code result} — non-null SHORT-CIRCUITS (beforeTool: deny/cache, the real
     *       tool never runs) or REPLACES the result (afterTool: redact/annotate).</li>
     *   <li>{@code args} — non-null rewrites the call's arguments (beforeTool only).</li>
     * </ul>
     */
    public record ToolOverride(Map<String, Object> args, ToolResult result) {
        public static ToolOverride withResult(ToolResult result) { return new ToolOverride(null, result); }
        public static ToolOverride withArgs(Map<String, Object> args) { return new ToolOverride(args, null); }
    }

    /**
     * Four optional callbacks around the agent loop. Build with the static factory
     * methods or set the public fields directly; any unset field is skipped.
     */
    public static final class Hooks {
        public Function<BeforeLLMEvent, LLMOverride> beforeLLM;   // may return null
        public Consumer<AfterLLMEvent> afterLLM;                  // observe only
        public Function<BeforeToolEvent, ToolOverride> beforeTool; // may return null
        public Function<AfterToolEvent, ToolOverride> afterTool;   // may return null

        public Hooks beforeLLM(Function<BeforeLLMEvent, LLMOverride> v) { this.beforeLLM = v; return this; }
        public Hooks afterLLM(Consumer<AfterLLMEvent> v) { this.afterLLM = v; return this; }
        public Hooks beforeTool(Function<BeforeToolEvent, ToolOverride> v) { this.beforeTool = v; return this; }
        public Hooks afterTool(Function<AfterToolEvent, ToolOverride> v) { this.afterTool = v; return this; }
    }

    /** Carries the (possibly rewritten) args and the final result of a {@link #runTool} call. */
    private record ToolRun(Map<String, Object> args, ToolResult result) {}

    /**
     * Run one tool through the beforeTool/afterTool hooks, mirroring JS {@code runTool}:
     * beforeTool may rewrite args or short-circuit with a result; otherwise the toolkit
     * executes; afterTool may replace the result.
     */
    private ToolRun runTool(Toolkit toolkit, String name, Map<String, Object> args, String id, int turn) {
        Hooks h = opts.hooks;
        Tool tool = toolkit.get(name);
        String source = tool != null ? tool.source() : "custom";
        long t0 = System.currentTimeMillis();
        Map<String, Object> a = args;
        if (h != null && h.beforeTool != null) {
            ToolOverride ov = h.beforeTool.apply(new BeforeToolEvent(name, a, id, turn));
            if (ov != null && ov.result() != null) { // short-circuit (deny/cache) — still a tool call for metrics
                emit(new MetricEvent.Tool(name, source, ov.result().isError(), System.currentTimeMillis() - t0));
                return new ToolRun(a, ov.result());
            }
            if (ov != null && ov.args() != null) a = ov.args();
        }
        ToolResult result = toolkit.execute(name, a);
        if (h != null && h.afterTool != null) {
            ToolOverride ov = h.afterTool.apply(new AfterToolEvent(name, a, result, id, turn));
            if (ov != null && ov.result() != null) result = ov.result();
        }
        emit(new MetricEvent.Tool(name, source, result.isError(), System.currentTimeMillis() - t0));
        return new ToolRun(a, result);
    }

    /** A tool call the model made, plus its result + metadata. */
    public static final class ToolCall {
        public final String name;
        public final Map<String, Object> args;
        public final String output;
        public final boolean isError;
        public final Map<String, Object> metadata;

        ToolCall(String name, Map<String, Object> args,
                 String output, boolean isError, Map<String, Object> metadata) {
            this.name = name;
            this.args = args;
            this.output = output;
            this.isError = isError;
            this.metadata = metadata;
        }
    }

    /** Token usage, summed across every LLM round trip in the run. */
    public static final class Usage {
        public long promptTokens;
        public long completionTokens;
        public long totalTokens;
    }

    public static final class RunResult {
        public final String text;
        public final List<Object> messages;
        /** Every tool call made, with its output, error flag, and metadata. */
        public final List<ToolCall> toolCalls;
        /** Total number of tool calls (= toolCalls.size()). */
        public final int toolCallCount;
        /** Number of LLM round trips. */
        public final int turns;
        /** Aggregated token usage across all turns. */
        public final Usage usage;
        /** The model used. */
        public final String model;

        RunResult(String text, List<Object> messages, List<ToolCall> toolCalls,
                  int turns, Usage usage, String model) {
            this.text = text;
            this.messages = messages;
            this.toolCalls = toolCalls;
            this.toolCallCount = toolCalls.size();
            this.turns = turns;
            this.usage = usage;
            this.model = model;
        }
    }

    /**
     * An event emitted by {@link #stream}. Modeled as a single record with a {@code type}
     * discriminator + nullable fields (the JS reference uses a discriminated union). Use the
     * static factories to construct, and {@link Kind} to switch on.
     *
     * <ul>
     *   <li>{@code TEXT} — an assistant text token delta ({@code delta} set).</li>
     *   <li>{@code TOOL_CALL} — a tool about to run ({@code id}, {@code name}, {@code args} set).</li>
     *   <li>{@code TOOL_RESULT} — after it ran ({@code id}, {@code name}, {@code output},
     *       {@code isError} set).</li>
     *   <li>{@code USAGE} — token usage so far ({@code usage} set).</li>
     *   <li>{@code DONE} — terminal event carrying the final {@link RunResult} ({@code result} set).</li>
     * </ul>
     */
    public record StreamEvent(Kind type, String delta, String id, String name,
                              Map<String, Object> args, String output, boolean isError,
                              Usage usage, RunResult result) {
        public enum Kind { TEXT, TOOL_CALL, TOOL_RESULT, USAGE, DONE }

        static StreamEvent text(String delta) {
            return new StreamEvent(Kind.TEXT, delta, null, null, null, null, false, null, null);
        }
        static StreamEvent toolCall(String id, String name, Map<String, Object> args) {
            return new StreamEvent(Kind.TOOL_CALL, null, id, name, args, null, false, null, null);
        }
        static StreamEvent toolResult(String id, String name, String output, boolean isError) {
            return new StreamEvent(Kind.TOOL_RESULT, null, id, name, null, output, isError, null, null);
        }
        static StreamEvent usage(Usage usage) {
            return new StreamEvent(Kind.USAGE, null, null, null, null, null, false, usage, null);
        }
        static StreamEvent done(RunResult result) {
            return new StreamEvent(Kind.DONE, null, null, null, null, null, false, null, result);
        }
    }

    /**
     * Sum the {@code usage} object from one LLM response into {@code acc}.
     * openai reads {@code prompt_tokens}/{@code completion_tokens}/{@code total_tokens};
     * anthropic reads {@code input_tokens}->prompt, {@code output_tokens}->completion,
     * total=input+output.
     */
    static void addUsage(Usage acc, Map<String, Object> raw, String style) {
        if (raw == null) return;
        if ("anthropic".equals(style)) {
            long in = asLong(raw.get("input_tokens"));
            long out = asLong(raw.get("output_tokens"));
            acc.promptTokens += in;
            acc.completionTokens += out;
            acc.totalTokens += in + out;
        } else {
            long in = asLong(raw.get("prompt_tokens"));
            long out = asLong(raw.get("completion_tokens"));
            Object total = raw.get("total_tokens");
            acc.promptTokens += in;
            acc.completionTokens += out;
            acc.totalTokens += total != null ? asLong(total) : in + out;
        }
    }

    private static long asLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0;
        try {
            return (long) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private final Options opts;
    /** Conversation provider for {@link #ask} — from {@code opts.store}, else in-memory. */
    private final ConversationStore store;
    /** Cumulative Prometheus registry fed by the same events {@code onMetric} sees. */
    private final MetricsRegistry registry = new MetricsRegistry();

    private LlmClient(Options opts) {
        this.opts = opts;
        this.store = opts.store != null ? opts.store : new InMemoryConversationStore();
    }

    public static LlmClient create(Options opts) {
        return new LlmClient(opts);
    }

    /**
     * Prometheus text exposition of cumulative metrics (§8). Always valid, empty-but-valid
     * before any activity (only {@code # HELP}/{@code # TYPE} lines). The rendered text is
     * byte-identical across all ports.
     */
    public String metrics() {
        return registry.render();
    }

    /** Feed a semantic metric event to the built-in registry and the optional {@code onMetric} sink. */
    private void emit(MetricEvent ev) {
        registry.record(ev);
        if (opts.onMetric != null) opts.onMetric.accept(ev);
    }

    /** Per-call token counts from one raw usage payload (not summed). Mirrors JS {@code perCall}. */
    private static long[] perCall(Map<String, Object> raw, String style) {
        if (raw == null) return new long[]{0, 0};
        if ("anthropic".equals(style)) {
            return new long[]{asLong(raw.get("input_tokens")), asLong(raw.get("output_tokens"))};
        }
        return new long[]{asLong(raw.get("prompt_tokens")), asLong(raw.get("completion_tokens"))};
    }

    public RunResult run(String prompt, Toolkit toolkit) {
        return run(prompt, toolkit, null);
    }

    /**
     * Stateful ask. With an {@code id}, the client's {@link ConversationStore} remembers the
     * conversation: it loads that id's transcript, runs, saves the updated transcript, and
     * returns the answer — so the next {@code ask} with the same {@code id} continues it.
     * Without an {@code id} (null/empty) it is a stateless one-shot (identical to {@link #run}).
     * Mirrors the JS {@code ask(prompt, { toolkit, id })}.
     */
    public RunResult ask(String prompt, Toolkit toolkit, String id) {
        return ask(prompt, toolkit, id, null);
    }

    /**
     * {@code ask} with a block-style streaming callback. When {@code onText} is non-null, the
     * streaming loop runs, each assistant text delta is forwarded to {@code onText}, and the final
     * {@link RunResult} is STILL returned (the return type never changes). Memory (id load/save) is
     * handled by {@link #stream} itself, so there's no duplication. Omit {@code onText} (null) ⇒ the
     * non-streaming path. Mirrors the JS {@code ask(prompt, { id, on_text })}.
     */
    public RunResult ask(String prompt, Toolkit toolkit, String id, Consumer<String> onText) {
        if (onText != null) {
            return stream(prompt, toolkit, ev -> {
                if (ev.type() == StreamEvent.Kind.TEXT) onText.accept(ev.delta());
            }, id);
        }
        if (id == null || id.isEmpty()) return run(prompt, toolkit);
        List<Object> history = store.get(id);
        if (history == null) history = new ArrayList<>();
        RunResult result = run(prompt, toolkit, history);
        store.save(id, result.messages);
        return result;
    }

    /**
     * Conversation-memory overload: continue a prior {@code history} transcript. When
     * {@code history} is non-empty it is NOT re-seeded with the system prompt (it already
     * carries it); the new user turn is appended and the run continues. {@code RunResult.messages}
     * is the full updated transcript. Mirrors JS {@code run(prompt, { history })}.
     */
    public RunResult run(String prompt, Toolkit toolkit, List<Object> history) {
        Deadline deadline = newDeadline();
        return "anthropic".equals(opts.style)
                ? runAnthropic(prompt, toolkit, history, deadline)
                : runOpenAI(prompt, toolkit, history, deadline);
    }

    /** A stateful multi-turn conversation that retains history across {@link Conversation#send} calls. */
    public Conversation conversation(Toolkit toolkit) {
        return new Conversation(this, toolkit);
    }

    /**
     * Streaming variant: drive the same agent loop (hooks, tools, telemetry as {@link #run})
     * but deliver incremental events to {@code onEvent} as they happen — text deltas, tool
     * calls/results, usage, and a terminal {@code done} carrying the {@link RunResult}.
     * Returns the same {@link RunResult} for convenience. This is the required Java stream API.
     */
    public RunResult stream(String prompt, Toolkit toolkit, Consumer<StreamEvent> onEvent) {
        return stream(prompt, toolkit, onEvent, null);
    }

    /**
     * Streaming with conversation memory. Like {@link #ask}, a non-null/non-empty {@code id} makes
     * the stream stateful: the thread's transcript is loaded from the {@link ConversationStore} as
     * history before streaming, and the updated transcript is saved back under {@code id} once the
     * terminal {@code done} event has fired. A null/empty {@code id} ⇒ stateless. Mirrors the JS
     * {@code stream(prompt, { id })}.
     */
    public RunResult stream(String prompt, Toolkit toolkit, Consumer<StreamEvent> onEvent, String id) {
        Deadline deadline = newDeadline();
        boolean stateful = id != null && !id.isEmpty();
        List<Object> history = null;
        if (stateful) {
            history = store.get(id);
            if (history == null) history = new ArrayList<>();
        }
        RunResult result = "anthropic".equals(opts.style)
                ? streamAnthropic(prompt, toolkit, onEvent, deadline, history)
                : streamOpenAI(prompt, toolkit, onEvent, deadline, history);
        if (stateful) store.save(id, result.messages);
        return result;
    }

    /**
     * Blocking {@link Stream} variant of {@link #stream(String, Toolkit, Consumer)}: collects
     * every event then returns them as a stream. Offered for convenience; the {@link Consumer}
     * form above is the primary (truly incremental) API.
     */
    public Stream<StreamEvent> stream(String prompt, Toolkit toolkit) {
        List<StreamEvent> events = new ArrayList<>();
        stream(prompt, toolkit, events::add);
        return events.stream();
    }

    private String resolveKey() {
        if (opts.apiKey != null && !opts.apiKey.isEmpty()) return opts.apiKey;
        String openrouter = System.getenv("OPENROUTER_API_KEY");
        String openai = System.getenv("OPENAI_API_KEY");
        String anthropic = System.getenv("ANTHROPIC_API_KEY");
        String key = firstNonEmpty(openrouter,
                "anthropic".equals(opts.style) ? anthropic : openai,
                openai, anthropic);
        if (key == null) {
            throw new RuntimeException(
                    "No API key: set apiKey or OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY");
        }
        return key;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private String system(Toolkit toolkit) {
        List<String> parts = new ArrayList<>();
        if (opts.systemPrompt != null && !opts.systemPrompt.isEmpty()) parts.add(opts.systemPrompt);
        String sp = toolkit.skillsPrompt();
        if (sp != null && !sp.isEmpty()) parts.add(sp);
        return String.join("\n\n", parts);
    }

    private int maxTurns() {
        return opts.maxTurns != null ? opts.maxTurns : 10;
    }

    private String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Emit the terminal {@code run} metric event and build the {@link RunResult}. */
    private RunResult endRun(long runStart, String text, List<Object> messages,
                             List<ToolCall> toolCalls, int turns, Usage usage) {
        emit(new MetricEvent.Run(opts.model, turns, toolCalls.size(), usage.totalTokens,
                System.currentTimeMillis() - runStart, null));
        return new RunResult(text, messages, toolCalls, turns, usage, opts.model);
    }

    /** Emit a {@code run} error metric event (once, on a thrown run). */
    private void emitRunError(long runStart, List<ToolCall> toolCalls, int turns, Usage usage, RuntimeException e) {
        emit(new MetricEvent.Run(opts.model, turns, toolCalls.size(), usage.totalTokens,
                System.currentTimeMillis() - runStart, e.getMessage() != null ? e.getMessage() : e.toString()));
    }

    // ---- OpenAI-style: POST {baseUrl}/chat/completions ----
    @SuppressWarnings("unchecked")
    private RunResult runOpenAI(String prompt, Toolkit toolkit, List<Object> history, Deadline deadline) {
        String key = resolveKey();
        List<Object> messages = new ArrayList<>();
        if (history != null && !history.isEmpty()) {
            // Continuing a conversation: history already carries the system prompt — don't re-add.
            messages.addAll(history);
        } else {
            String system = system(toolkit);
            if (!system.isEmpty()) messages.add(msg("system", system));
        }
        messages.add(msg("user", prompt));
        List<Map<String, Object>> tools = toolkit.toOpenAI();
        List<ToolCall> toolCalls = new ArrayList<>();
        Usage usage = new Usage();
        int turns = 0;
        long runStart = System.currentTimeMillis();

        // One virtual-thread executor for the whole run; tool calls in a turn run on it.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int turn = 0; turn < maxTurns(); turn++) {
                turns++;
                if (opts.hooks != null && opts.hooks.beforeLLM != null) {
                    LLMOverride ov = opts.hooks.beforeLLM.apply(
                            new BeforeLLMEvent(messages, tools, opts.model, turn));
                    if (ov != null && ov.messages() != null) messages = ov.messages();
                    if (ov != null && ov.tools() != null) tools = ov.tools();
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", opts.model);
                body.put("messages", messages);
                body.put("tools", tools);
                body.put("tool_choice", "auto");

                String url = stripTrailingSlash(opts.baseUrl) + "/chat/completions";
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer " + key);
                headers.put("Content-Type", "application/json");
                if (opts.headers != null) headers.putAll(opts.headers);

                Map<String, Object> data = llmCallJson(url, headers, body, deadline, "openai");
                addUsage(usage, (Map<String, Object>) data.get("usage"), "openai");
                if (opts.hooks != null && opts.hooks.afterLLM != null) {
                    opts.hooks.afterLLM.accept(new AfterLLMEvent(data, opts.model, turn));
                }
                List<Object> choices = (List<Object>) data.get("choices");
                Map<String, Object> message =
                        (Map<String, Object>) ((Map<String, Object>) choices.get(0)).get("message");
                messages.add(message);
                List<Object> calls = (List<Object>) message.get("tool_calls");
                if (calls == null || calls.isEmpty()) {
                    Object content = message.get("content");
                    return endRun(runStart, content == null ? "" : String.valueOf(content),
                            messages, toolCalls, turns, usage);
                }

                // Execute all tool calls in this turn concurrently (true parallel tool calling).
                int n = calls.size();
                Map<String, Object>[] toolMsgs = new Map[n];
                ToolCall[] recorded = new ToolCall[n];
                CompletableFuture<Void>[] futures = new CompletableFuture[n];
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    Map<String, Object> call = (Map<String, Object>) calls.get(i);
                    Map<String, Object> fn = (Map<String, Object>) call.get("function");
                    String fnName = String.valueOf(fn.get("name"));
                    Map<String, Object> args = Json.parseObjectLoose(
                            fn.get("arguments") == null ? "{}" : String.valueOf(fn.get("arguments")));
                    Object callId = call.get("id");
                    final int t = turn;
                    futures[idx] = CompletableFuture.supplyAsync(
                            () -> runTool(toolkit, fnName,
                                    args, callId == null ? null : String.valueOf(callId), t),
                            executor).thenAccept(run -> {
                        recorded[idx] = new ToolCall(fnName, run.args(),
                                run.result().output(), run.result().isError(), run.result().metadata());
                        Map<String, Object> toolMsg = new LinkedHashMap<>();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", callId);
                        toolMsg.put("content", run.result().output());
                        toolMsgs[idx] = toolMsg;
                    });
                }
                CompletableFuture.allOf(futures).join();

                // Append in original order; record toolCalls in original order (after join → thread-safe).
                for (int i = 0; i < n; i++) toolCalls.add(recorded[i]);
                for (int i = 0; i < n; i++) messages.add(toolMsgs[i]);
            }
            return endRun(runStart, lastAssistantText(messages), messages, toolCalls, turns, usage);
        } catch (RuntimeException e) {
            emitRunError(runStart, toolCalls, turns, usage, e);
            throw e;
        } finally {
            executor.shutdown();
        }
    }

    // ---- Anthropic-style: POST {baseUrl}/messages ----
    @SuppressWarnings("unchecked")
    private RunResult runAnthropic(String prompt, Toolkit toolkit, List<Object> history, Deadline deadline) {
        String key = resolveKey();
        String base = stripTrailingSlash(opts.baseUrl);
        String endpoint = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        String system = system(toolkit);
        List<Object> messages = new ArrayList<>();
        // Anthropic carries the system prompt out-of-band (the `system` field), not in messages,
        // so continuing history just means appending to the prior transcript.
        if (history != null && !history.isEmpty()) messages.addAll(history);
        messages.add(msg("user", prompt));
        List<Map<String, Object>> tools = toolkit.toAnthropic();
        List<ToolCall> toolCalls = new ArrayList<>();
        Usage usage = new Usage();
        int turns = 0;
        long runStart = System.currentTimeMillis();

        // One virtual-thread executor for the whole run; tool calls in a turn run on it.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int turn = 0; turn < maxTurns(); turn++) {
                turns++;
                if (opts.hooks != null && opts.hooks.beforeLLM != null) {
                    LLMOverride ov = opts.hooks.beforeLLM.apply(
                            new BeforeLLMEvent(messages, tools, opts.model, turn));
                    if (ov != null && ov.messages() != null) messages = ov.messages();
                    if (ov != null && ov.tools() != null) tools = ov.tools();
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", opts.model);
                body.put("max_tokens", 4096);
                if (!system.isEmpty()) body.put("system", system);
                body.put("messages", messages);
                body.put("tools", tools);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("x-api-key", key);
                headers.put("anthropic-version", "2023-06-01");
                headers.put("Content-Type", "application/json");
                if (opts.headers != null) headers.putAll(opts.headers);

                Map<String, Object> data = llmCallJson(endpoint, headers, body, deadline, "anthropic");
                addUsage(usage, (Map<String, Object>) data.get("usage"), "anthropic");
                if (opts.hooks != null && opts.hooks.afterLLM != null) {
                    opts.hooks.afterLLM.accept(new AfterLLMEvent(data, opts.model, turn));
                }
                List<Object> content = (List<Object>) data.get("content");
                if (content == null) content = new ArrayList<>();
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", content);
                messages.add(assistant);

                List<Map<String, Object>> uses = new ArrayList<>();
                for (Object b : content) {
                    Map<String, Object> block = (Map<String, Object>) b;
                    if ("tool_use".equals(block.get("type"))) uses.add(block);
                }
                if (uses.isEmpty()) {
                    StringBuilder text = new StringBuilder();
                    for (Object b : content) {
                        Map<String, Object> block = (Map<String, Object>) b;
                        if ("text".equals(block.get("type"))) text.append(String.valueOf(block.get("text")));
                    }
                    return endRun(runStart, text.toString(), messages, toolCalls, turns, usage);
                }

                // Execute all tool_use blocks in this turn concurrently (true parallel tool calling).
                int n = uses.size();
                Map<String, Object>[] resultBlocks = new Map[n];
                ToolCall[] recorded = new ToolCall[n];
                CompletableFuture<Void>[] futures = new CompletableFuture[n];
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    Map<String, Object> use = uses.get(i);
                    Map<String, Object> input = (Map<String, Object>) use.get("input");
                    if (input == null) input = new LinkedHashMap<>();
                    final Map<String, Object> useInput = input;
                    String useName = String.valueOf(use.get("name"));
                    Object useId = use.get("id");
                    final int t = turn;
                    futures[idx] = CompletableFuture.supplyAsync(
                            () -> runTool(toolkit, useName, useInput,
                                    useId == null ? null : String.valueOf(useId), t),
                            executor).thenAccept(run -> {
                        recorded[idx] = new ToolCall(useName, run.args(),
                                run.result().output(), run.result().isError(), run.result().metadata());
                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("type", "tool_result");
                        tr.put("tool_use_id", useId);
                        tr.put("content", run.result().output());
                        tr.put("is_error", run.result().isError());
                        resultBlocks[idx] = tr;
                    });
                }
                CompletableFuture.allOf(futures).join();

                // Record toolCalls and build the result list in original order (after join → thread-safe).
                for (int i = 0; i < n; i++) toolCalls.add(recorded[i]);
                List<Object> results = new ArrayList<>(n);
                for (int i = 0; i < n; i++) results.add(resultBlocks[i]);

                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", results);
                messages.add(userMsg);
            }
            return endRun(runStart, "", messages, toolCalls, turns, usage);
        } catch (RuntimeException e) {
            emitRunError(runStart, toolCalls, turns, usage, e);
            throw e;
        } finally {
            executor.shutdown();
        }
    }

    // ---- Streaming: OpenAI-style (SSE, line-by-line) ----
    @SuppressWarnings("unchecked")
    private RunResult streamOpenAI(String prompt, Toolkit toolkit, Consumer<StreamEvent> onEvent,
                                   Deadline deadline, List<Object> history) {
        String key = resolveKey();
        List<Object> messages = new ArrayList<>();
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        } else {
            String system = system(toolkit);
            if (!system.isEmpty()) messages.add(msg("system", system));
        }
        messages.add(msg("user", prompt));
        List<Map<String, Object>> tools = toolkit.toOpenAI();
        List<ToolCall> toolCalls = new ArrayList<>();
        Usage usage = new Usage();
        int turns = 0;
        long runStart = System.currentTimeMillis();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int turn = 0; turn < maxTurns(); turn++) {
                turns++;
                if (opts.hooks != null && opts.hooks.beforeLLM != null) {
                    LLMOverride ov = opts.hooks.beforeLLM.apply(new BeforeLLMEvent(messages, tools, opts.model, turn));
                    if (ov != null && ov.messages() != null) messages = ov.messages();
                    if (ov != null && ov.tools() != null) tools = ov.tools();
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", opts.model);
                body.put("messages", messages);
                body.put("tools", tools);
                body.put("tool_choice", "auto");
                body.put("stream", true);
                Map<String, Object> streamOpts = new LinkedHashMap<>();
                streamOpts.put("include_usage", true);
                body.put("stream_options", streamOpts);

                String url = stripTrailingSlash(opts.baseUrl) + "/chat/completions";
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer " + key);
                headers.put("Content-Type", "application/json");
                if (opts.headers != null) headers.putAll(opts.headers);

                long t0 = System.currentTimeMillis();
                long beforeP = usage.promptTokens, beforeC = usage.completionTokens;
                StringBuilder content = new StringBuilder();
                // index -> accumulated tool call (id/name/args assembled across deltas).
                Map<Integer, String[]> acc = new LinkedHashMap<>(); // [id, name, args]
                List<Integer> order = new ArrayList<>();
                try {
                    // The JDK HttpClient streams the SSE response as lines — ideal for SSE.
                    HttpResponse<Stream<String>> res =
                            llmSend(url, headers, body, deadline, HttpResponse.BodyHandlers.ofLines());
                    if (res.statusCode() < 200 || res.statusCode() >= 300) {
                        String b = res.body() == null ? "" : res.body().collect(Collectors.joining("\n"));
                        throw new RuntimeException("LLM " + res.statusCode() + ": " + b);
                    }
                    try (Stream<String> lines = res.body()) {
                        for (String line : (Iterable<String>) lines::iterator) {
                            if (!line.startsWith("data:")) continue;
                            String payload = line.substring(5).trim();
                            if ("[DONE]".equals(payload)) break;
                            Map<String, Object> j = parseObjOrNull(payload);
                            if (j == null) continue;
                            addUsage(usage, (Map<String, Object>) j.get("usage"), "openai");
                            List<Object> choices = (List<Object>) j.get("choices");
                            if (choices == null || choices.isEmpty()) continue;
                            Map<String, Object> delta = (Map<String, Object>) ((Map<String, Object>) choices.get(0)).get("delta");
                            if (delta == null) continue;
                            Object dc = delta.get("content");
                            if (dc instanceof String s && !s.isEmpty()) {
                                content.append(s);
                                onEvent.accept(StreamEvent.text(s));
                            }
                            List<Object> tcs = (List<Object>) delta.get("tool_calls");
                            if (tcs != null) {
                                for (Object o : tcs) {
                                    Map<String, Object> tc = (Map<String, Object>) o;
                                    int index = tc.get("index") instanceof Number n ? n.intValue() : 0;
                                    String[] slot = acc.get(index);
                                    if (slot == null) { slot = new String[]{"", "", ""}; acc.put(index, slot); order.add(index); }
                                    if (tc.get("id") != null) slot[0] = String.valueOf(tc.get("id"));
                                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                                    if (fn != null) {
                                        if (fn.get("name") != null) slot[1] += String.valueOf(fn.get("name"));
                                        if (fn.get("arguments") != null) slot[2] += String.valueOf(fn.get("arguments"));
                                    }
                                }
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    emit(new MetricEvent.Llm(opts.model, "error", System.currentTimeMillis() - t0, 0, 0));
                    throw e;
                }
                emit(new MetricEvent.Llm(opts.model, "ok", System.currentTimeMillis() - t0,
                        usage.promptTokens - beforeP, usage.completionTokens - beforeC));
                if (opts.hooks != null && opts.hooks.afterLLM != null) {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("streamed", true);
                    resp.put("usage", null);
                    opts.hooks.afterLLM.accept(new AfterLLMEvent(resp, opts.model, turn));
                }

                if (order.isEmpty()) {
                    messages.add(msg("assistant", content.toString()));
                    onEvent.accept(StreamEvent.usage(copyUsage(usage)));
                    RunResult done = endRun(runStart, content.toString(), messages, toolCalls, turns, usage);
                    onEvent.accept(StreamEvent.done(done));
                    return done;
                }

                // Append the assistant message carrying the assembled tool_calls.
                List<Object> assembledCalls = new ArrayList<>();
                for (int index : order) {
                    String[] slot = acc.get(index);
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", slot[1]);
                    fn.put("arguments", slot[2]);
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("id", slot[0]);
                    call.put("type", "function");
                    call.put("function", fn);
                    assembledCalls.add(call);
                }
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", content.length() == 0 ? null : content.toString());
                assistant.put("tool_calls", assembledCalls);
                messages.add(assistant);

                int n = order.size();
                for (int index : order) {
                    String[] slot = acc.get(index);
                    onEvent.accept(StreamEvent.toolCall(slot[0], slot[1], Json.parseObjectLoose(slot[2])));
                }
                ToolRun[] runs = new ToolRun[n];
                CompletableFuture<Void>[] futures = new CompletableFuture[n];
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    String[] slot = acc.get(order.get(i));
                    Map<String, Object> args = Json.parseObjectLoose(slot[2]);
                    final int t = turn;
                    futures[idx] = CompletableFuture.supplyAsync(
                            () -> runTool(toolkit, slot[1], args, slot[0], t), executor)
                            .thenAccept(r -> runs[idx] = r);
                }
                CompletableFuture.allOf(futures).join();
                for (int i = 0; i < n; i++) {
                    String[] slot = acc.get(order.get(i));
                    ToolRun r = runs[i];
                    toolCalls.add(new ToolCall(slot[1], r.args(), r.result().output(), r.result().isError(), r.result().metadata()));
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", slot[0]);
                    toolMsg.put("content", r.result().output());
                    messages.add(toolMsg);
                    onEvent.accept(StreamEvent.toolResult(slot[0], slot[1], r.result().output(), r.result().isError()));
                }
            }
            RunResult done = endRun(runStart, lastAssistantText(messages), messages, toolCalls, turns, usage);
            onEvent.accept(StreamEvent.done(done));
            return done;
        } catch (RuntimeException e) {
            emitRunError(runStart, toolCalls, turns, usage, e);
            throw e;
        } finally {
            executor.shutdown();
        }
    }

    // ---- Streaming: Anthropic-style (SSE content_block_* / message_delta) ----
    @SuppressWarnings("unchecked")
    private RunResult streamAnthropic(String prompt, Toolkit toolkit, Consumer<StreamEvent> onEvent,
                                      Deadline deadline, List<Object> history) {
        String key = resolveKey();
        String base = stripTrailingSlash(opts.baseUrl);
        String endpoint = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        String system = system(toolkit);
        List<Object> messages = new ArrayList<>();
        if (history != null && !history.isEmpty()) messages.addAll(history);
        messages.add(msg("user", prompt));
        List<Map<String, Object>> tools = toolkit.toAnthropic();
        List<ToolCall> toolCalls = new ArrayList<>();
        Usage usage = new Usage();
        int turns = 0;
        long runStart = System.currentTimeMillis();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int turn = 0; turn < maxTurns(); turn++) {
                turns++;
                if (opts.hooks != null && opts.hooks.beforeLLM != null) {
                    LLMOverride ov = opts.hooks.beforeLLM.apply(new BeforeLLMEvent(messages, tools, opts.model, turn));
                    if (ov != null && ov.messages() != null) messages = ov.messages();
                    if (ov != null && ov.tools() != null) tools = ov.tools();
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", opts.model);
                body.put("max_tokens", 4096);
                if (!system.isEmpty()) body.put("system", system);
                body.put("messages", messages);
                body.put("tools", tools);
                body.put("stream", true);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("x-api-key", key);
                headers.put("anthropic-version", "2023-06-01");
                headers.put("Content-Type", "application/json");
                if (opts.headers != null) headers.putAll(opts.headers);

                long t0 = System.currentTimeMillis();
                long beforeP = usage.promptTokens, beforeC = usage.completionTokens;
                // index -> block being assembled: {type, text, id, name, json}
                Map<Integer, Map<String, Object>> blocks = new LinkedHashMap<>();
                List<Integer> order = new ArrayList<>();
                String[] stopReason = {""};
                try {
                    HttpResponse<Stream<String>> res =
                            llmSend(endpoint, headers, body, deadline, HttpResponse.BodyHandlers.ofLines());
                    if (res.statusCode() < 200 || res.statusCode() >= 300) {
                        String b = res.body() == null ? "" : res.body().collect(Collectors.joining("\n"));
                        throw new RuntimeException("LLM " + res.statusCode() + ": " + b);
                    }
                    try (Stream<String> lines = res.body()) {
                        for (String line : (Iterable<String>) lines::iterator) {
                            if (!line.startsWith("data:")) continue;
                            Map<String, Object> j = parseObjOrNull(line.substring(5).trim());
                            if (j == null) continue;
                            String type = String.valueOf(j.get("type"));
                            if ("message_start".equals(type)) {
                                Map<String, Object> m = (Map<String, Object>) j.get("message");
                                if (m != null) addUsage(usage, (Map<String, Object>) m.get("usage"), "anthropic");
                            } else if ("content_block_start".equals(type)) {
                                int index = j.get("index") instanceof Number n ? n.intValue() : 0;
                                Map<String, Object> cb = (Map<String, Object>) j.get("content_block");
                                Map<String, Object> b = new LinkedHashMap<>();
                                b.put("type", cb.get("type"));
                                b.put("id", cb.get("id"));
                                b.put("name", cb.get("name"));
                                b.put("text", new StringBuilder());
                                b.put("json", new StringBuilder());
                                blocks.put(index, b);
                                order.add(index);
                            } else if ("content_block_delta".equals(type)) {
                                int index = j.get("index") instanceof Number n ? n.intValue() : 0;
                                Map<String, Object> b = blocks.get(index);
                                if (b == null) continue;
                                Map<String, Object> delta = (Map<String, Object>) j.get("delta");
                                String dtype = String.valueOf(delta.get("type"));
                                if ("text_delta".equals(dtype)) {
                                    String t = String.valueOf(delta.get("text"));
                                    ((StringBuilder) b.get("text")).append(t);
                                    onEvent.accept(StreamEvent.text(t));
                                } else if ("input_json_delta".equals(dtype)) {
                                    ((StringBuilder) b.get("json")).append(String.valueOf(delta.get("partial_json")));
                                }
                            } else if ("message_delta".equals(type)) {
                                Map<String, Object> delta = (Map<String, Object>) j.get("delta");
                                if (delta != null && delta.get("stop_reason") != null) {
                                    stopReason[0] = String.valueOf(delta.get("stop_reason"));
                                }
                                addUsage(usage, (Map<String, Object>) j.get("usage"), "anthropic");
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    emit(new MetricEvent.Llm(opts.model, "error", System.currentTimeMillis() - t0, 0, 0));
                    throw e;
                }
                emit(new MetricEvent.Llm(opts.model, "ok", System.currentTimeMillis() - t0,
                        usage.promptTokens - beforeP, usage.completionTokens - beforeC));
                if (opts.hooks != null && opts.hooks.afterLLM != null) {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("streamed", true);
                    opts.hooks.afterLLM.accept(new AfterLLMEvent(resp, opts.model, turn));
                }

                // Build the assistant content blocks from what we assembled.
                List<Object> content = new ArrayList<>();
                List<Map<String, Object>> uses = new ArrayList<>();
                for (int index : order) {
                    Map<String, Object> b = blocks.get(index);
                    if ("tool_use".equals(b.get("type"))) {
                        Map<String, Object> tu = new LinkedHashMap<>();
                        tu.put("type", "tool_use");
                        tu.put("id", b.get("id"));
                        tu.put("name", b.get("name"));
                        String json = b.get("json").toString();
                        tu.put("input", Json.parseObjectLoose(json.isEmpty() ? "{}" : json));
                        content.add(tu);
                        uses.add(tu);
                    } else {
                        Map<String, Object> tx = new LinkedHashMap<>();
                        tx.put("type", "text");
                        tx.put("text", b.get("text").toString());
                        content.add(tx);
                    }
                }
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", content);
                messages.add(assistant);

                if (!"tool_use".equals(stopReason[0]) || uses.isEmpty()) {
                    StringBuilder text = new StringBuilder();
                    for (Object b : content) {
                        Map<String, Object> blk = (Map<String, Object>) b;
                        if ("text".equals(blk.get("type"))) text.append(String.valueOf(blk.get("text")));
                    }
                    onEvent.accept(StreamEvent.usage(copyUsage(usage)));
                    RunResult done = endRun(runStart, text.toString(), messages, toolCalls, turns, usage);
                    onEvent.accept(StreamEvent.done(done));
                    return done;
                }

                int n = uses.size();
                for (Map<String, Object> u : uses) {
                    onEvent.accept(StreamEvent.toolCall(String.valueOf(u.get("id")), String.valueOf(u.get("name")),
                            (Map<String, Object>) u.get("input")));
                }
                ToolRun[] runs = new ToolRun[n];
                CompletableFuture<Void>[] futures = new CompletableFuture[n];
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    Map<String, Object> u = uses.get(i);
                    Map<String, Object> input = (Map<String, Object>) u.get("input");
                    if (input == null) input = new LinkedHashMap<>();
                    final Map<String, Object> in = input;
                    String name = String.valueOf(u.get("name"));
                    String id = String.valueOf(u.get("id"));
                    final int t = turn;
                    futures[idx] = CompletableFuture.supplyAsync(
                            () -> runTool(toolkit, name, in, id, t), executor)
                            .thenAccept(r -> runs[idx] = r);
                }
                CompletableFuture.allOf(futures).join();
                List<Object> results = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    Map<String, Object> u = uses.get(i);
                    ToolRun r = runs[i];
                    String name = String.valueOf(u.get("name"));
                    String id = String.valueOf(u.get("id"));
                    toolCalls.add(new ToolCall(name, r.args(), r.result().output(), r.result().isError(), r.result().metadata()));
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", id);
                    tr.put("content", r.result().output());
                    tr.put("is_error", r.result().isError());
                    results.add(tr);
                    onEvent.accept(StreamEvent.toolResult(id, name, r.result().output(), r.result().isError()));
                }
                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", results);
                messages.add(userMsg);
            }
            RunResult done = endRun(runStart, "", messages, toolCalls, turns, usage);
            onEvent.accept(StreamEvent.done(done));
            return done;
        } catch (RuntimeException e) {
            emitRunError(runStart, toolCalls, turns, usage, e);
            throw e;
        } finally {
            executor.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Prometheus registry (zero-dependency; §8 Observability). In-memory,
    // cumulative, thread-safe. Turns the same MetricEvents `onMetric` sees into
    // counters/histograms and renders the text exposition format by hand. The
    // rendered text is BYTE-IDENTICAL across all ports — see js/src/client.ts.
    // ------------------------------------------------------------------

    /** FIXED across all ports for byte-parity of {@code ..._duration_seconds} histograms. Seconds. */
    private static final double[] DURATION_BUCKETS = {0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60};
    /** {@code le} label strings, pinned to JS {@code String(bucket)} formatting (no trailing {@code .0}). */
    private static final String[] BUCKET_LE = {"0.05", "0.1", "0.25", "0.5", "1", "2.5", "5", "10", "30", "60"};

    private static final class Hist {
        final long[] counts = new long[DURATION_BUCKETS.length];
        double sum;
        long count;
    }

    private static final class MetricsRegistry {
        private final Map<String, Long> llmRequests = new LinkedHashMap<>();   // labels: model,status
        private final Map<String, Long> llmTokens = new LinkedHashMap<>();     // labels: type
        private final Map<String, Hist> llmDuration = new LinkedHashMap<>();   // labels: model
        private final Map<String, Long> toolCalls = new LinkedHashMap<>();     // labels: tool,source,is_error
        private final Map<String, Hist> toolDuration = new LinkedHashMap<>();  // labels: tool
        private final Map<String, Long> runErrors = new LinkedHashMap<>();     // labels: model

        synchronized void record(MetricEvent ev) {
            if (ev instanceof MetricEvent.Llm e) {
                inc(llmRequests, labelStr(pair("model", e.model()), pair("status", e.status())), 1);
                if ("ok".equals(e.status())) {
                    inc(llmTokens, labelStr(pair("type", "prompt")), e.promptTokens());
                    inc(llmTokens, labelStr(pair("type", "completion")), e.completionTokens());
                }
                observe(llmDuration, labelStr(pair("model", e.model())), e.ms() / 1000.0);
            } else if (ev instanceof MetricEvent.Tool e) {
                inc(toolCalls, labelStr(pair("tool", e.tool()), pair("source", e.source()),
                        pair("is_error", String.valueOf(e.isError()))), 1);
                observe(toolDuration, labelStr(pair("tool", e.tool())), e.ms() / 1000.0);
            } else if (ev instanceof MetricEvent.Run e) {
                if (e.error() != null) inc(runErrors, labelStr(pair("model", e.model())), 1);
            }
        }

        synchronized String render() {
            List<String> out = new ArrayList<>();
            renderCounter(out, "toolnexus_llm_requests_total", "Total LLM requests.", llmRequests);
            renderCounter(out, "toolnexus_llm_tokens_total", "Total tokens, by type.", llmTokens);
            renderHistogram(out, "toolnexus_llm_request_duration_seconds", "LLM request duration in seconds.", llmDuration);
            renderCounter(out, "toolnexus_tool_calls_total", "Total tool calls.", toolCalls);
            renderHistogram(out, "toolnexus_tool_duration_seconds", "Tool execution duration in seconds.", toolDuration);
            renderCounter(out, "toolnexus_run_errors_total", "Total run errors.", runErrors);
            return String.join("\n", out) + "\n";
        }

        private static void renderCounter(List<String> out, String name, String help, Map<String, Long> m) {
            out.add("# HELP " + name + " " + help);
            out.add("# TYPE " + name + " counter");
            List<String> keys = new ArrayList<>(m.keySet());
            keys.sort(null);
            for (String key : keys) {
                out.add(key.isEmpty() ? name + " " + m.get(key) : name + "{" + key + "} " + m.get(key));
            }
        }

        private static void renderHistogram(List<String> out, String name, String help, Map<String, Hist> m) {
            out.add("# HELP " + name + " " + help);
            out.add("# TYPE " + name + " histogram");
            List<String> keys = new ArrayList<>(m.keySet());
            keys.sort(null);
            for (String key : keys) {
                Hist h = m.get(key);
                for (int i = 0; i < DURATION_BUCKETS.length; i++) {
                    out.add(name + "_bucket{" + withLe(key, BUCKET_LE[i]) + "} " + h.counts[i]);
                }
                out.add(name + "_bucket{" + withLe(key, "+Inf") + "} " + h.count);
                out.add(key.isEmpty() ? name + "_sum " + num(h.sum) : name + "_sum{" + key + "} " + num(h.sum));
                out.add(key.isEmpty() ? name + "_count " + h.count : name + "_count{" + key + "} " + h.count);
            }
        }
    }

    private static void inc(Map<String, Long> m, String key, long by) {
        m.merge(key, by, Long::sum);
    }

    private static void observe(Map<String, Hist> m, String key, double seconds) {
        Hist h = m.computeIfAbsent(key, k -> new Hist());
        h.sum += seconds;
        h.count++;
        for (int i = 0; i < DURATION_BUCKETS.length; i++) if (seconds <= DURATION_BUCKETS[i]) h.counts[i]++;
    }

    private record LabelPair(String key, String value) {}

    private static LabelPair pair(String k, String v) { return new LabelPair(k, v); }

    /** Render an ordered list of label pairs to {@code k="v",k="v"} (order is load-bearing for parity). */
    private static String labelStr(LabelPair... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(pairs[i].key()).append("=\"").append(escapeLabel(pairs[i].value())).append("\"");
        }
        return sb.toString();
    }

    /** Escape a Prometheus label value: backslash, double-quote, newline. */
    private static String escapeLabel(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String withLe(String base, String le) {
        return base.isEmpty() ? "le=\"" + le + "\"" : base + ",le=\"" + le + "\"";
    }

    /** Render a metric value: whole numbers as integers (matches JS {@code String(n)}; no {@code .0}). */
    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static Usage copyUsage(Usage u) {
        Usage c = new Usage();
        c.promptTokens = u.promptTokens;
        c.completionTokens = u.completionTokens;
        c.totalTokens = u.totalTokens;
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObjOrNull(String s) {
        try {
            Object o = Json.parseLoose(s);
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // ------------------------------------------------------------------
    // Resilience: a whole-run monotonic deadline (from timeoutMs) + retry on
    // 429/5xx/IOException with exponential backoff + jitter, honoring Retry-After.
    // Mirrors the JS makeSignal / llmFetch. Timeouts/aborts are never retried.
    // ------------------------------------------------------------------

    private int retries() { return opts.retries != null ? opts.retries : 2; }
    private long retryBaseMs() { return opts.retryBaseMs != null ? opts.retryBaseMs : 500L; }

    /** A run-scoped monotonic deadline. {@code null} timeoutMs => no deadline (deadlineNanos absent). */
    private static final class Deadline {
        final Long endNanos; // null = unbounded
        final long timeoutMs;
        Deadline(Long endNanos, long timeoutMs) { this.endNanos = endNanos; this.timeoutMs = timeoutMs; }
        boolean bounded() { return endNanos != null; }
        long remainingMs() {
            if (endNanos == null) return Long.MAX_VALUE;
            return (endNanos - System.nanoTime()) / 1_000_000L;
        }
        void check() {
            if (endNanos != null && System.nanoTime() >= endNanos) {
                throw new TimeoutException("run timeout after " + timeoutMs + "ms");
            }
        }
    }

    private Deadline newDeadline() {
        if (opts.timeoutMs == null) return new Deadline(null, 0);
        return new Deadline(System.nanoTime() + opts.timeoutMs * 1_000_000L, opts.timeoutMs);
    }

    private HttpRequest buildRequest(String url, Map<String, String> headers,
                                     Map<String, Object> body, Deadline deadline) {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        // Per-request timeout = remaining run budget (capped > 0); JS sets request timeout from the signal.
        if (deadline.bounded()) {
            long remain = deadline.remainingMs();
            if (remain <= 0) throw new TimeoutException("run timeout after " + deadline.timeoutMs + "ms");
            rb.timeout(Duration.ofMillis(remain));
        }
        return rb.build();
    }

    /**
     * Send with retry on 429/5xx + IOException, exponential backoff + jitter, honoring Retry-After.
     * The whole-run {@code deadline} is enforced before/after each attempt; timeouts are not retried.
     */
    private <T> HttpResponse<T> llmSend(String url, Map<String, String> headers,
                                        Map<String, Object> body, Deadline deadline,
                                        HttpResponse.BodyHandler<T> handler) {
        int retries = retries();
        long base = retryBaseMs();
        RuntimeException lastErr = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            deadline.check();
            HttpRequest req = buildRequest(url, headers, body, deadline);
            try {
                HttpResponse<T> res = HTTP.send(req, handler);
                int status = res.statusCode();
                if (status >= 200 && status < 300) return res;
                if (!RETRYABLE.contains(status) || attempt == retries) return res; // caller handles non-2xx
                long wait = retryAfterMs(res).orElse((long) (base * Math.pow(2, attempt) + Math.random() * 100));
                sleep(wait, deadline);
            } catch (HttpTimeoutException e) {
                throw new TimeoutException("run timeout after " + deadline.timeoutMs + "ms"); // not retried
            } catch (IOException e) {
                lastErr = new RuntimeException("LLM request failed: " + e.getMessage(), e);
                if (attempt == retries) throw lastErr;
                sleep((long) (base * Math.pow(2, attempt) + Math.random() * 100), deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM request interrupted", e);
            }
        }
        throw lastErr != null ? lastErr : new RuntimeException("LLM request failed");
    }

    private static java.util.OptionalLong retryAfterMs(HttpResponse<?> res) {
        return res.headers().firstValue("retry-after")
                .map(String::trim)
                .filter(s -> s.matches("\\d+"))
                .map(s -> java.util.OptionalLong.of(Long.parseLong(s) * 1000L))
                .orElse(java.util.OptionalLong.empty());
    }

    /** Sleep {@code ms}, but never past the run deadline (and throw if the deadline is hit). */
    private void sleep(long ms, Deadline deadline) {
        if (ms <= 0) { deadline.check(); return; }
        long capped = deadline.bounded() ? Math.min(ms, Math.max(0, deadline.remainingMs())) : ms;
        try {
            Thread.sleep(Math.max(0, capped));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted during backoff", e);
        }
        deadline.check();
    }

    /**
     * One non-streaming LLM call with an {@code llm} metric event (ok/error + per-call tokens + ms).
     * Mirrors JS {@code llmCallJson}: emits an error event and rethrows on failure.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> llmCallJson(String url, Map<String, String> headers,
                                            Map<String, Object> body, Deadline deadline, String style) {
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> data = postJson(url, headers, body, deadline);
            long[] tok = perCall((Map<String, Object>) data.get("usage"), style);
            emit(new MetricEvent.Llm(opts.model, "ok", System.currentTimeMillis() - t0, tok[0], tok[1]));
            return data;
        } catch (RuntimeException e) {
            emit(new MetricEvent.Llm(opts.model, "error", System.currentTimeMillis() - t0, 0, 0));
            throw e;
        }
    }

    /** Send and parse a JSON object response, retrying transient failures + enforcing the deadline. */
    private Map<String, Object> postJson(String url, Map<String, String> headers,
                                         Map<String, Object> body, Deadline deadline) {
        HttpResponse<String> res = llmSend(url, headers, body, deadline, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new RuntimeException("LLM " + res.statusCode() + ": " + res.body());
        }
        return Json.toMap(res.body());
    }

    @SuppressWarnings("unchecked")
    private static String lastAssistantText(List<Object> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object o = messages.get(i);
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                if ("assistant".equals(m.get("role")) && m.get("content") instanceof String) {
                    return (String) m.get("content");
                }
            }
        }
        return "";
    }

    /**
     * Stateful multi-turn conversation (memory). Each {@link #send} continues the same
     * transcript: the system prompt is added once (on the first turn, inside {@link #run}),
     * and prior history is carried forward automatically. Mirrors the JS {@code Conversation}.
     */
    public static final class Conversation {
        private final LlmClient client;
        private final Toolkit toolkit;
        /** Full running transcript (system + user + assistant + tool messages). */
        private List<Object> messages = new ArrayList<>();

        Conversation(LlmClient client, Toolkit toolkit) {
            this.client = client;
            this.toolkit = toolkit;
        }

        /** Send the next user turn; prior history is retained automatically. */
        public RunResult send(String prompt) {
            RunResult result = client.run(prompt, toolkit, messages);
            this.messages = result.messages;
            return result;
        }

        /** The full running transcript. */
        public List<Object> messages() {
            return messages;
        }

        /** Reset the conversation memory. */
        public void reset() {
            this.messages = new ArrayList<>();
        }
    }
}
