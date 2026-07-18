package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An agent definition (SPEC §7D): identity/system prompt ("soul") × a filtered toolkit view ×
 * the shipped client loop. Registered in an {@link AgentRuntime} registry under {@link #name};
 * {@link #does} is the routing description the delegating model sees on the {@code task} tool.
 */
public final class AgentDef {
    public final String name;
    /** What the delegating model sees — the routing description. */
    public String does;
    /** The soul/system prompt for this agent's own loop. */
    public String soul;
    /** Model id; {@code "inherit"} = the runtime's default model. */
    public String model;
    public Budget budget;
    /** The scoped toolkit VIEW for this agent (scoping is the whole security model). */
    public List<Tool> tools;
    /** §10 interpreter authority for this agent's subtree. Absent ⇒ escalate / durable posture. */
    public Function<Request, Answer> waitFor;
    /** Once, before the first turn — the session-start injection point. */
    public Consumer<Handle> onSpawn;
    /** Before the final checkpoint; reason = {@code closed | interrupted | budget | error}. */
    public BiConsumer<Handle, String> onClose;
    /** Team scoping for the {@code task} tool: {@code null} = no team declared (the agent gets no
     * task targets beyond the registry default), a list = ONLY these agents are reachable. */
    public List<String> team;

    public AgentDef(String name, String does, String soul, String model) {
        this.name = name;
        this.does = does;
        this.soul = soul;
        this.model = model;
    }

    public AgentDef budget(Budget v) { this.budget = v; return this; }
    public AgentDef tools(List<Tool> v) { this.tools = v; return this; }
    public AgentDef waitFor(Function<Request, Answer> v) { this.waitFor = v; return this; }
    public AgentDef onSpawn(Consumer<Handle> v) { this.onSpawn = v; return this; }
    public AgentDef onClose(BiConsumer<Handle, String> v) { this.onClose = v; return this; }
    public AgentDef team(List<String> v) { this.team = v; return this; }

    /** Copy with a merged budget — used when {@code spawn()} carries a budget override. */
    AgentDef withBudget(Budget b) {
        AgentDef d = new AgentDef(name, does, soul, model);
        d.budget = b;
        d.tools = tools;
        d.waitFor = waitFor;
        d.onSpawn = onSpawn;
        d.onClose = onClose;
        d.team = team;
        return d;
    }
}
