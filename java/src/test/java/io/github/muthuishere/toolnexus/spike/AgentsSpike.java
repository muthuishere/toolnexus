package io.github.muthuishere.toolnexus.spike;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * SPIKE — Level-1/2 UX sugar over the substrate (see the audit doc "The UX").
 * Java port of {@code js/spike/agents.ts}. One new noun: {@code agent()}. Everything
 * compiles to the six verbs in {@link AgentRuntime}.
 */
public final class AgentsSpike {

    private AgentsSpike() {}

    /** The HEARTBEAT_OK sentinel — a silent wake produces no outbound message. */
    public static final String HEARTBEAT_OK = "HEARTBEAT_OK";

    /** Bootstrap discovery order (openclaw), injected as {@code ## <file>} sections. */
    public static final List<String> BOOTSTRAP_ORDER = List.of(
            "AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md");
    static final long MAX_BOOTSTRAP_FILE_BYTES = 2L * 1024 * 1024;

    public static final class AgentSpec {
        /** Optional explicit name (agentFromDir override). */
        public String name;
        /** What the delegating model sees — the routing description. */
        public String does;
        /** The toolkit VIEW for this agent (scoping = the whole security model). */
        public List<Tool> tools;
        /** Identity: inline text, or a path to a soul file (AGENTS.md / SOUL.md). */
        public String soul;
        /** Path form of soul — read at registry-build time. */
        public Path soulFile;
        /** task-tool targets: listing agents here IS the subagent wiring. */
        public List<Agent> team;
        public Budget budget;
        /** Model id; {@code "inherit"} = runtime default. */
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

        /** Collect this agent + its whole team graph into a runtime registry. */
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
            // team scoping: task targets = ONLY this agent's team (not the whole registry)
            List<String> targets = new ArrayList<>();
            if (spec.team != null) for (Agent a : spec.team) targets.add(a.name);
            def.taskTargets = targets;
            acc.put(name, def);
            if (spec.team != null) for (Agent a : spec.team) a.registry(acc);
            return acc;
        }

        public Map<String, AgentDef> registry() {
            return registry(new LinkedHashMap<>());
        }

        /** Level 1: one-shot — build a runtime, run to completion, tear down. */
        public TaskResult run(RuntimeOptions rtOpts, String prompt) {
            AgentRuntime rt = new AgentRuntime(rtOpts.copyWithRegistry(registry()));
            AgentRuntime.Spawn sp = rt.spawn(rt.root, name);
            if (sp.error() != null) return new TaskResult(sp.error(), true, "done", null, null, 0, 0);
            var fut = rt.futureResult(sp.handle());
            rt.wake(sp.handle(), prompt);
            TaskResult r = fut.join();
            if (!"pending".equals(r.status())) rt.close(rt.root);
            return r;
        }

        /** Bridge: an Agent IS a Tool — drop it into the OLD API's extraTools. */
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
     * Level 2: the directory IS the agent. Discovers (all optional, openclaw order):
     * AGENTS.md, SOUL.md, IDENTITY.md, USER.md, TOOLS.md, HEARTBEAT.md, MEMORY.md.
     * Injected as named sections at session start (cache doctrine: onSpawn reads memory in).
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

    public static final class StartedAgent {
        public final Handle handle;
        public final AgentRuntime rt;
        private final ScheduledExecutorService timer;

        StartedAgent(Handle handle, AgentRuntime rt, ScheduledExecutorService timer) {
            this.handle = handle;
            this.rt = rt;
            this.timer = timer;
        }

        public void stop() {
            if (timer != null) timer.shutdownNow();
            rt.close(rt.root);
        }
    }

    /**
     * Level 2: start a long-lived persona — the heartbeat posts a tick to its own inbox
     * (unsolicited rail; ticks coalesce) and wakes it; HEARTBEAT_OK results stay silent.
     */
    public static StartedAgent startAgent(Agent a, RuntimeOptions rtOpts, Long everyMs,
                                          Consumer<String> onReport) {
        AgentRuntime rt = new AgentRuntime(rtOpts.copyWithRegistry(a.registry()));
        AgentRuntime.Spawn sp = rt.spawn(rt.root, a.name);
        if (sp.error() != null) throw new IllegalStateException(sp.error());
        Handle handle = sp.handle();
        ScheduledExecutorService timer = null;
        if (everyMs != null) {
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "spike-heartbeat");
                th.setDaemon(true);
                return th;
            });
            timer.scheduleAtFixedRate(() -> {
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
            }, everyMs, everyMs, TimeUnit.MILLISECONDS);
        }
        return new StartedAgent(handle, rt, timer);
    }
}
