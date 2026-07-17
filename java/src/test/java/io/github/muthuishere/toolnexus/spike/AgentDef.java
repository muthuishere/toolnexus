package io.github.muthuishere.toolnexus.spike;

import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * SPIKE — an agent definition. Mirrors {@code js/spike/runtime.ts AgentDef}.
 * Mutable on purpose (the JS spike mutates defs in scenarios, e.g. onClose in S9).
 */
public final class AgentDef {
    public String name;
    public String description;
    public String systemPrompt;
    public String model;
    public Budget budget;
    /** Scoped tool set for this agent (builtins off in the spike; scoping = the toolkit view). */
    public List<Tool> tools;
    /** This agent's interpreter authority (§10). Absent ⇒ escalate/durable. */
    public Function<Request, Answer> waitFor;
    public Consumer<Handle> onSpawn;
    public BiConsumer<Handle, String> onClose;
    /** Team scoping for the task tool: null = whole registry, list = only these agents. */
    public List<String> taskTargets;

    public AgentDef(String name, String description, String systemPrompt, String model) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.model = model;
    }

    public AgentDef budget(Budget v) { this.budget = v; return this; }
    public AgentDef tools(List<Tool> v) { this.tools = v; return this; }
    public AgentDef waitFor(Function<Request, Answer> v) { this.waitFor = v; return this; }
    public AgentDef onSpawn(Consumer<Handle> v) { this.onSpawn = v; return this; }
    public AgentDef onClose(BiConsumer<Handle, String> v) { this.onClose = v; return this; }
    public AgentDef taskTargets(List<String> v) { this.taskTargets = v; return this; }

    /** Copy with a merged budget — used when spawn() carries a budget override. */
    AgentDef withBudget(Budget b) {
        AgentDef d = new AgentDef(name, description, systemPrompt, model);
        d.budget = b;
        d.tools = tools;
        d.waitFor = waitFor;
        d.onSpawn = onSpawn;
        d.onClose = onClose;
        d.taskTargets = taskTargets;
        return d;
    }
}
