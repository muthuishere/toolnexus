package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.LlmClient;

import java.util.LinkedHashMap;
import java.util.Map;

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

    public RuntimeOptions baseUrl(String v) { this.baseUrl = v; return this; }
    public RuntimeOptions style(String v) { this.style = v; return this; }
    public RuntimeOptions apiKey(String v) { this.apiKey = v; return this; }
    public RuntimeOptions defaultModel(String v) { this.defaultModel = v; return this; }
    public RuntimeOptions registry(Map<String, AgentDef> v) { this.registry = v; return this; }
    public RuntimeOptions inboxCap(int v) { this.inboxCap = v; return this; }
    public RuntimeOptions maxConcurrentTurns(int v) { this.maxConcurrentTurns = v; return this; }
    public RuntimeOptions shutdownMs(long v) { this.shutdownMs = v; return this; }
    public RuntimeOptions store(LlmClient.ConversationStore v) { this.store = v; return this; }
    public RuntimeOptions clock(RuntimeClock v) { this.clock = v; return this; }

    /** Copy the scalar options with a different registry (the {@link Agents} layer builds its own). */
    RuntimeOptions copyWithRegistry(Map<String, AgentDef> reg) {
        RuntimeOptions o = new RuntimeOptions();
        o.baseUrl = baseUrl;
        o.style = style;
        o.apiKey = apiKey;
        o.defaultModel = defaultModel;
        o.registry = reg;
        o.inboxCap = inboxCap;
        o.maxConcurrentTurns = maxConcurrentTurns;
        o.shutdownMs = shutdownMs;
        o.store = store;
        o.clock = clock;
        return o;
    }
}
