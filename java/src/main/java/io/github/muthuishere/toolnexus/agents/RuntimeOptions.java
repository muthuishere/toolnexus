package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Options for an {@link AgentRuntime}. LLM plumbing ({@code baseUrl}/{@code style}/{@code apiKey})
 * is shared by every handle's per-turn client; the runtime itself owns the cross-cutting
 * infrastructure — ONE {@link LlmClient.ConversationStore} for all handles and one injectable
 * {@link RuntimeClock} (SPEC §7D "Lifecycle &amp; runtime obligations").
 */
public final class RuntimeOptions {
    /** The LLM endpoint every agent's client points at. */
    public String baseUrl;
    /** {@code "openai" | "anthropic"} — default openai. */
    public String style;
    /** API key for the per-turn clients; {@code null} = the client's env resolution. */
    public String apiKey;
    /** Model substituted for defs declaring {@code "inherit"}. */
    public String defaultModel;
    /** The pre-gate LLM HTTP transport (a scripted {@link HttpClient} in fixtures — zero
     * network). {@code null} = {@code HttpClient.newHttpClient()}. The global turn gate wraps
     * whatever this resolves to. Mutually exclusive with {@link #inProcess} — the
     * {@link AgentRuntime} constructor throws if both are set (ADR 0024). */
    public HttpClient httpClient;
    /** A model running IN THIS PROCESS (ADR 0024) — the semantic counterpart to
     * {@link #httpClient}, so a host whose model is a Java function doesn't have to hand-build an
     * {@link HttpClient} to reach the sub-agent runtime. Internally this is turned into an
     * {@link InProcess.GenerateBackedHttpClient} — the SAME public adapter
     * {@link InProcess#createClient} builds — and wrapped by the SAME global turn gate as
     * {@link #httpClient}: there is no second code path. Mutually exclusive with
     * {@link #httpClient} and with {@link #baseUrl} — the {@link AgentRuntime} constructor throws
     * if more than one of {@code httpClient}/{@code inProcess}/{@code baseUrl} is set. */
    public Function<InProcess.Request, InProcess.Response> inProcess;
    public Map<String, AgentDef> registry = new LinkedHashMap<>();
    /** Inbox gate capacity per handle. Default 8. */
    public Integer inboxCap;
    /** The GLOBAL turn gate: max concurrent LLM HTTP calls runtime-wide. Default 8. */
    public Integer maxConcurrentTurns;
    /** Graceful-close bound before escalating to abort. Default 200 ms. */
    public Long shutdownMs;
    /** ONE conversation store for ALL handles (conversation id = handle id, so transcripts
     * genuinely survive turns and durable resume reads real history). {@code null} = an
     * in-memory store owned by the runtime. */
    public LlmClient.ConversationStore store;
    /** Injectable clock for every timer/timestamp/deadline. {@code null} = the system clock. */
    public RuntimeClock clock;
    /** §8 lifecycle hooks handed to EVERY handle's per-turn client (SPEC §7D "The §8 seams on an
     * agent run"). Forwarded verbatim — never composed, wrapped, reordered or read. An
     * {@link AgentDef#hooks} REPLACES this for that agent. {@code null} = no hooks. */
    public LlmClient.Hooks hooks;
    /** §8 observability sink handed to EVERY handle's per-turn client. Forwarded verbatim; an
     * {@link AgentDef#onMetric} REPLACES this for that agent. {@code null} = no sink. */
    public java.util.function.Consumer<LlmClient.MetricEvent> onMetric;

    public RuntimeOptions baseUrl(String v) { this.baseUrl = v; return this; }
    public RuntimeOptions style(String v) { this.style = v; return this; }
    public RuntimeOptions apiKey(String v) { this.apiKey = v; return this; }
    public RuntimeOptions defaultModel(String v) { this.defaultModel = v; return this; }
    public RuntimeOptions httpClient(HttpClient v) { this.httpClient = v; return this; }
    public RuntimeOptions inProcess(Function<InProcess.Request, InProcess.Response> v) { this.inProcess = v; return this; }
    public RuntimeOptions registry(Map<String, AgentDef> v) { this.registry = v; return this; }
    public RuntimeOptions inboxCap(int v) { this.inboxCap = v; return this; }
    public RuntimeOptions maxConcurrentTurns(int v) { this.maxConcurrentTurns = v; return this; }
    public RuntimeOptions shutdownMs(long v) { this.shutdownMs = v; return this; }
    public RuntimeOptions store(LlmClient.ConversationStore v) { this.store = v; return this; }
    public RuntimeOptions clock(RuntimeClock v) { this.clock = v; return this; }
    public RuntimeOptions hooks(LlmClient.Hooks v) { this.hooks = v; return this; }
    public RuntimeOptions onMetric(java.util.function.Consumer<LlmClient.MetricEvent> v) { this.onMetric = v; return this; }

    /** Copy the scalar options with a different registry (the {@link Agents} layer builds its own). */
    RuntimeOptions copyWithRegistry(Map<String, AgentDef> reg) {
        RuntimeOptions o = new RuntimeOptions();
        o.baseUrl = baseUrl;
        o.style = style;
        o.apiKey = apiKey;
        o.defaultModel = defaultModel;
        o.httpClient = httpClient;
        o.inProcess = inProcess;
        o.registry = reg;
        o.inboxCap = inboxCap;
        o.maxConcurrentTurns = maxConcurrentTurns;
        o.shutdownMs = shutdownMs;
        o.store = store;
        o.clock = clock;
        o.hooks = hooks;
        o.onMetric = onMetric;
        return o;
    }
}
