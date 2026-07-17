package io.github.muthuishere.toolnexus.spike;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SPIKE — runtime options. Mirrors {@code js/spike/runtime.ts RuntimeOptions}.
 * Where the JS spike injects a mock {@code fetch}, Java points {@code baseUrl} at a
 * localhost stub LLM (the established Java mock pattern).
 */
public final class RuntimeOptions {
    /** The LLM endpoint (a localhost {@code com.sun.net.httpserver} stub in the spike). */
    public String baseUrl;
    public Map<String, AgentDef> registry = new LinkedHashMap<>();
    public Integer inboxCap;            // default 8
    public Integer maxConcurrentTurns;  // default 8 — the global turn gate
    public Long shutdownMs;             // default 200 — graceful close bound
    /** Model substituted for defs declaring {@code "inherit"}. */
    public String defaultModel;

    public RuntimeOptions baseUrl(String v) { this.baseUrl = v; return this; }
    public RuntimeOptions registry(Map<String, AgentDef> v) { this.registry = v; return this; }
    public RuntimeOptions inboxCap(int v) { this.inboxCap = v; return this; }
    public RuntimeOptions maxConcurrentTurns(int v) { this.maxConcurrentTurns = v; return this; }
    public RuntimeOptions shutdownMs(long v) { this.shutdownMs = v; return this; }
    public RuntimeOptions defaultModel(String v) { this.defaultModel = v; return this; }

    /** Copy the scalar options with a different registry (the Agent UX layer builds its own). */
    RuntimeOptions copyWithRegistry(Map<String, AgentDef> reg) {
        RuntimeOptions o = new RuntimeOptions();
        o.baseUrl = baseUrl;
        o.registry = reg;
        o.inboxCap = inboxCap;
        o.maxConcurrentTurns = maxConcurrentTurns;
        o.shutdownMs = shutdownMs;
        o.defaultModel = defaultModel;
        return o;
    }
}
