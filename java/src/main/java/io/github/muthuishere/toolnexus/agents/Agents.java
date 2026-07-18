package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Level-1 surface (SPEC §7D "Level-1 surface"): {@code agent(name, spec)} →
 * {@link Agent#run(RuntimeOptions, String)} (one-shot) and {@link Agent#asTool(RuntimeOptions)}
 * (the bridge into the classic API's {@code extraTools} — the axiom's other direction: an Agent
 * IS a Tool). Everything compiles down to the six verbs of {@link AgentRuntime}.
 *
 * <p>Team scoping is the wiring model: listing agents in {@link AgentSpec#team} IS the subagent
 * graph. A runtime registry is the transitive closure of the entry agent's team graph —
 * unreachable agents are not present. Children get no {@code task} reach unless their own spec
 * declares a team (recursion is opt-in, never default).
 */
public final class Agents {

    private Agents() {}

    /** The heartbeat sentinel — a wake whose result contains it produces no outbound report. */
    public static final String HEARTBEAT_OK = "HEARTBEAT_OK";

    /** Bootstrap discovery order for {@link #agentFromDir}, injected as {@code ## <file>} sections. */
    public static final List<String> BOOTSTRAP_ORDER = List.of(
            "AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md");
    static final long MAX_BOOTSTRAP_FILE_BYTES = 2L * 1024 * 1024;

    /** The declarative spec behind {@link #agent(String, AgentSpec)} — SPEC §7D field-for-field:
     * {@code does, uses(tools), soul|soulFile, team, budget, model("inherit"), waitFor, onSpawn,
     * onClose}. */
    public static final class AgentSpec {
        /** Optional explicit name ({@link #agentFromDir} override). */
        public String name;
        /** What the delegating model sees — the required routing description. */
        public String does;
        /** The toolkit VIEW for this agent (scoping is the whole security model). */
        public List<Tool> tools;
        /** Identity: inline soul text… */
        public String soul;
        /** …or a soul file path (AGENTS.md / SOUL.md), read at registry-build time. */
        public Path soulFile;
        /** {@code task}-tool targets: listing agents here IS the subagent wiring. */
        public List<Agent> team;
        public Budget budget;
        /** Model id; {@code null} ⇒ {@code "inherit"} (the runtime default). */
        public String model;
        /** §10 interpreter authority for this agent's subtree. */
        public Function<Request, Answer> waitFor;
        public Consumer<Handle> onSpawn;
        public BiConsumer<Handle, String> onClose;

        public AgentSpec name(String v) { this.name = v; return this; }
        public AgentSpec does(String v) { this.does = v; return this; }
        public AgentSpec tools(Tool... v) { this.tools = List.of(v); return this; }
        public AgentSpec soul(String v) { this.soul = v; return this; }
        public AgentSpec soulFile(Path v) { this.soulFile = v; return this; }
        public AgentSpec team(Agent... v) { this.team = List.of(v); return this; }
        public AgentSpec budget(Budget v) { this.budget = v; return this; }
        public AgentSpec model(String v) { this.model = v; return this; }
        public AgentSpec waitFor(Function<Request, Answer> v) { this.waitFor = v; return this; }
        public AgentSpec onSpawn(Consumer<Handle> v) { this.onSpawn = v; return this; }
        public AgentSpec onClose(BiConsumer<Handle, String> v) { this.onClose = v; return this; }
    }

    /** The one new noun. */
    public static Agent agent(String name, AgentSpec spec) {
        return new Agent(name, spec);
    }

    public static final class Agent {
        public final String name;
        public final AgentSpec spec;

        Agent(String name, AgentSpec spec) {
            this.name = name;
            this.spec = spec;
        }

        /** Collect this agent + its whole team graph into a runtime registry (transitive closure
         * of the entry agent's team graph — unreachable agents are absent). */
        public Map<String, AgentDef> registry(Map<String, AgentDef> acc) {
            if (acc.containsKey(name)) return acc;
            String soul = spec.soul != null ? spec.soul : "";
            if (spec.soulFile != null) {
                try {
                    soul = Files.readString(spec.soulFile);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            AgentDef def = new AgentDef(name, spec.does, soul, spec.model != null ? spec.model : "inherit");
            def.tools = spec.tools;
            def.budget = spec.budget;
            def.waitFor = spec.waitFor;
            def.onSpawn = spec.onSpawn;
            def.onClose = spec.onClose;
            // Team scoping: task targets = ONLY this agent's team (empty = no delegation at all —
            // recursion is opt-in, never default).
            List<String> targets = new ArrayList<>();
            if (spec.team != null) for (Agent a : spec.team) targets.add(a.name);
            def.team = targets;
            acc.put(name, def);
            if (spec.team != null) for (Agent a : spec.team) a.registry(acc);
            return acc;
        }

        public Map<String, AgentDef> registry() {
            return registry(new LinkedHashMap<>());
        }

        /** One-shot: build a runtime scoped to this agent's team graph, run the prompt to
         * completion, tear down (unless the run parked {@code pending} — a durable pending keeps
         * the tree suspended for {@link AgentRuntime#resume}). */
        public TaskResult run(RuntimeOptions rtOpts, String prompt) {
            AgentRuntime rt = new AgentRuntime(rtOpts.copyWithRegistry(registry()));
            AgentRuntime.Spawn sp = rt.spawn(rt.root, name);
            if (sp.error() != null) return new TaskResult(sp.error(), true, "error", null, null, 0, 0);
            var fut = rt.futureResult(sp.handle());
            rt.wake(sp.handle(), prompt);
            TaskResult r = fut.join();
            if (!"pending".equals(r.status())) rt.close(rt.root);
            return r;
        }

        /** The bridge: an Agent IS a Tool — drop it into the classic API's {@code extraTools}.
         * The tool takes {@code {prompt}}, runs the agent's own loop in isolation, and returns
         * ONLY its final text + metadata {@code {agent, turns, totalTokens}}. */
        public Tool asTool(RuntimeOptions rtOpts) {
            Agent self = this;
            return new Tool() {
                @Override public String name() { return self.name; }
                @Override public String description() { return self.spec.does; }
                @Override public Map<String, Object> inputSchema() {
                    return Map.of("type", "object",
                            "properties", Map.of("prompt", Map.of("type", "string")),
                            "required", List.of("prompt"));
                }
                @Override public String source() { return "custom"; }
                @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                    TaskResult r = self.run(rtOpts, String.valueOf(args.get("prompt")));
                    Map<String, Object> md = new LinkedHashMap<>();
                    md.put("agent", self.name);
                    md.put("turns", r.turns());
                    md.put("totalTokens", r.totalTokens());
                    return new ToolResult(r.text(), r.isError(), md);
                }
            };
        }
    }

    /**
     * Level 2: the directory IS the agent. Discovers (all optional, in {@link #BOOTSTRAP_ORDER}):
     * AGENTS.md, SOUL.md, IDENTITY.md, USER.md, TOOLS.md, HEARTBEAT.md, MEMORY.md — injected as
     * named {@code ## <file>} sections into the soul at session start.
     */
    public static Agent agentFromDir(Path dir, AgentSpec overrides) {
        List<String> sections = new ArrayList<>();
        for (String f : BOOTSTRAP_ORDER) {
            Path p = dir.resolve(f);
            if (Files.exists(p)) {
                try {
                    long size = Files.size(p);
                    String body = Files.readString(p);
                    if (size > MAX_BOOTSTRAP_FILE_BYTES) {
                        body = body.substring(0, (int) Math.min(body.length(), MAX_BOOTSTRAP_FILE_BYTES))
                                + "\n[truncated: file exceeds bootstrap cap]";
                    }
                    sections.add("## " + f + "\n\n" + body.strip());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        AgentSpec spec = overrides != null ? overrides : new AgentSpec();
        String name = spec.name != null ? spec.name : dir.getFileName().toString();
        if (spec.does == null) spec.does = "persona agent from " + dir;
        spec.soul = String.join("\n\n", sections);
        return agent(name, spec);
    }

    /** A long-lived persona started by {@link #startAgent}: its handle, its runtime, and the
     * heartbeat subscription. {@link #stop()} closes the whole tree. */
    public static final class StartedAgent {
        public final Handle handle;
        public final AgentRuntime rt;
        private final AutoCloseable heartbeat;

        StartedAgent(Handle handle, AgentRuntime rt, AutoCloseable heartbeat) {
            this.handle = handle;
            this.rt = rt;
            this.heartbeat = heartbeat;
        }

        public void stop() {
            if (heartbeat != null) {
                try {
                    heartbeat.close();
                } catch (Exception ignored) {
                    // a timer that fails to close must not block the close cascade
                }
            }
            rt.close(rt.root);
        }
    }

    /**
     * Level 2: start a long-lived persona. The heartbeat (driven by the runtime's injectable
     * {@link RuntimeClock} — virtual in fixtures) posts a tick to the agent's own inbox
     * (unsolicited rail; ticks coalesce) and wakes it; results containing {@link #HEARTBEAT_OK}
     * stay silent, anything else is reported.
     */
    public static StartedAgent startAgent(Agent a, RuntimeOptions rtOpts, Long everyMs,
                                          Consumer<String> onReport) {
        AgentRuntime rt = new AgentRuntime(rtOpts.copyWithRegistry(a.registry()));
        AgentRuntime.Spawn sp = rt.spawn(rt.root, a.name);
        if (sp.error() != null) throw new IllegalStateException(sp.error());
        Handle handle = sp.handle();
        AutoCloseable heartbeat = null;
        if (everyMs != null) {
            heartbeat = rt.clock().schedule(everyMs, () -> {
                rt.post(handle, new InboxItem("clock", "timer", "tick"));
                if (handle.state == Handle.State.IDLE) {
                    Thread.startVirtualThread(() -> {
                        TaskResult r = rt.runTurn(handle,
                                "Heartbeat. Read your HEARTBEAT.md section and follow it. "
                                        + "If nothing needs attention, reply HEARTBEAT_OK.");
                        if (!r.isError() && !r.text().contains(HEARTBEAT_OK) && onReport != null) {
                            onReport.accept(r.text());
                        }
                    });
                }
            });
        }
        return new StartedAgent(handle, rt, heartbeat);
    }
}
