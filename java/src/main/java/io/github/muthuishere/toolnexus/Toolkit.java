package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Toolkit — aggregates dynamic MCP tools + the skill tool + custom/native/http
 * tools into a single uniform list, with provider adapters and a single
 * {@code execute()} router. Mirrors the JS reference ({@code js/src/toolkit.ts}).
 */
public final class Toolkit implements AutoCloseable {
    private final McpSource mcp;       // may be null
    private final SkillSource skill;   // may be null
    /** A2A profile read from a top-level {@code a2a} config block; {@link #serve} falls back to this. */
    private final A2AServer.A2AConfig a2aConfig; // may be null
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public static final class Options {
        public Object mcpConfig;            // path string, raw JSON string, or parsed Map
        public List<String> skillsDir;      // one or more skill roots
        public List<Tool> extraTools;       // custom/native/http tools
        public List<Object> annotatedObjects; // objects scanned via Tools.fromObject
        /** Built-in tools (§4A). On by default. false | {disabled:true} | {enabled:false} => off. */
        public Object builtins;
        /** Remote A2A agents — each advertised skill becomes a tool (source:"a2a"). */
        public List<A2A.Agent> agents;

        public Options mcpConfig(Object v) { this.mcpConfig = v; return this; }
        public Options skillsDir(String... v) { this.skillsDir = List.of(v); return this; }
        public Options skillsDir(List<String> v) { this.skillsDir = v; return this; }
        public Options extraTools(List<Tool> v) { this.extraTools = v; return this; }
        public Options extraTools(Tool... v) { this.extraTools = List.of(v); return this; }
        public Options annotatedObjects(Object... v) { this.annotatedObjects = List.of(v); return this; }
        public Options builtins(Object v) { this.builtins = v; return this; }
        public Options agents(List<A2A.Agent> v) { this.agents = v; return this; }
        public Options agents(A2A.Agent... v) { this.agents = List.of(v); return this; }
    }

    private Toolkit(McpSource mcp, SkillSource skill, List<Tool> builtins, List<Tool> agents, List<Tool> extraTools,
                   A2AServer.A2AConfig a2aConfig) {
        this.mcp = mcp;
        this.skill = skill;
        this.a2aConfig = a2aConfig;
        // Builtins are the lowest-precedence source: a host extraTools entry with
        // the same name shadows a builtin (SPEC §4). Drop shadowed builtins up
        // front, then apply the normal first-wins dedupe for MCP/skill/agents/extras.
        Set<String> extraNames = new HashSet<>();
        for (Tool t : extraTools) extraNames.add(t.name());
        List<Tool> all = new ArrayList<>();
        if (mcp != null) all.addAll(mcp.tools());
        if (skill != null) all.add(skill.tool());
        for (Tool b : builtins) {
            if (!extraNames.contains(b.name())) all.add(b);
        }
        all.addAll(agents);
        all.addAll(extraTools);
        for (Tool t : all) {
            if (byName.containsKey(t.name())) {
                System.err.println("[toolnexus] duplicate tool name \"" + t.name() + "\" — keeping first");
                continue;
            }
            byName.put(t.name(), t);
        }
    }

    public static Toolkit create(Options opts) {
        McpSource mcp = opts.mcpConfig != null ? McpSource.load(opts.mcpConfig) : null;
        SkillSource skill = opts.skillsDir != null ? SkillSource.load(opts.skillsDir) : null;

        List<Tool> extras = new ArrayList<>();
        if (opts.extraTools != null) extras.addAll(opts.extraTools);
        if (opts.annotatedObjects != null) {
            for (Object o : opts.annotatedObjects) {
                extras.addAll(Tools.fromObject(o));
            }
        }

        // The toggle comes from the `builtins` option, or a top-level `builtins`
        // key on a parsed config Map — same precedence as MCP's isEnabled.
        Object builtinsCfg = opts.builtins;
        if (builtinsCfg == null && opts.mcpConfig instanceof Map
                && ((Map<?, ?>) opts.mcpConfig).containsKey("builtins")) {
            builtinsCfg = ((Map<?, ?>) opts.mcpConfig).get("builtins");
        }
        List<Tool> builtins = BuiltinTools.select(builtinsCfg);

        // Remote A2A agents come from the `agents:[...]` option plus a top-level
        // `agents` block on a parsed config Map (mirrors mcpServers). Each is
        // resolved to its skill tools; a failing agent never breaks the toolkit.
        List<A2A.Agent> descriptors = new ArrayList<>();
        if (opts.agents != null) descriptors.addAll(opts.agents);
        if (opts.mcpConfig instanceof Map && ((Map<?, ?>) opts.mcpConfig).containsKey("agents")) {
            Object block = ((Map<?, ?>) opts.mcpConfig).get("agents");
            @SuppressWarnings("unchecked")
            Map<String, Object> agentsBlock = block instanceof Map ? (Map<String, Object>) block : null;
            descriptors.addAll(A2A.parseAgentsConfig(agentsBlock));
        }
        List<Tool> agentTools = new ArrayList<>();
        for (A2A.Agent ag : descriptors) {
            try {
                agentTools.addAll(A2A.agentTools(ag));
            } catch (Exception e) {
                System.err.println("[toolnexus] A2A agent \"" + ag.card() + "\" failed: "
                        + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            }
        }

        // A2A inbound profile: a top-level `a2a` block on a parsed config Map
        // (mirrors builtins/agents). serve() prefers its inline `a2a` over this.
        A2AServer.A2AConfig a2aConfig = null;
        if (opts.mcpConfig instanceof Map && ((Map<?, ?>) opts.mcpConfig).containsKey("a2a")) {
            Object block = ((Map<?, ?>) opts.mcpConfig).get("a2a");
            if (block instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> a2aBlock = (Map<String, Object>) block;
                a2aConfig = A2AServer.A2AConfig.fromMap(a2aBlock);
            }
        }

        return new Toolkit(mcp, skill, builtins, agentTools, extras, a2aConfig);
    }

    public List<Tool> tools() {
        return new ArrayList<>(byName.values());
    }

    public Tool get(String name) {
        return byName.get(name);
    }

    /** Add native/http/custom tools at runtime. First-name-wins; warn on dup. Chainable. */
    public Toolkit register(Tool... tools) {
        for (Tool t : tools) {
            if (byName.containsKey(t.name())) {
                System.err.println("[toolnexus] duplicate tool name \"" + t.name() + "\" — keeping first");
                continue;
            }
            byName.put(t.name(), t);
        }
        return this;
    }

    /**
     * Fetch a remote A2A agent's card and register its skills as tools. First-name-wins
     * dedupe. Mirrors the JS reference {@code toolkit.addAgent}.
     */
    public Toolkit addAgent(A2A.Agent ag) {
        try {
            return register(A2A.agentTools(ag).toArray(new Tool[0]));
        } catch (Exception e) {
            throw new RuntimeException("A2A addAgent failed: "
                    + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()), e);
        }
    }

    /** Convenience: add an A2A agent from a bare Agent Card URL. */
    public Toolkit addAgent(String cardUrl) {
        return addAgent(A2A.agent(cardUrl));
    }

    /** Options for {@link #serve} — the client loop plus the opt-in A2A profile + callback. */
    public static final class ServeOptions {
        public LlmClient client;
        public A2AServer.A2AConfig a2a;      // null ⇒ fall back to the toolkit's config block
        public A2AServer.OnTask onTask;

        public ServeOptions client(LlmClient v) { this.client = v; return this; }
        public ServeOptions a2a(A2AServer.A2AConfig v) { this.a2a = v; return this; }
        public ServeOptions onTask(A2AServer.OnTask v) { this.onTask = v; return this; }
    }

    /**
     * Serve this toolkit as an agent over HTTP. When the {@code a2a} profile is present
     * (inline, or a top-level {@code a2a} config block the toolkit was built from), it
     * mounts the A2A Agent Card ({@code /.well-known/agent-card.json}, built from skills)
     * and a JSON-RPC endpoint ({@code SendMessage} + {@code GetTask}) fulfilled by the client.
     * A message's A2A {@code contextId} keys the conversation via {@code client.ask}, so a
     * peer's turns are remembered through the client's {@link LlmClient.ConversationStore}.
     * When {@code a2a} is absent, no A2A routes are mounted. Returns a stoppable handle.
     * Mirrors the JS reference {@code toolkit.serve}.
     */
    public A2AServer.ServeHandle serve(String addr, ServeOptions opts) {
        A2AServer.A2AConfig a2a = opts.a2a != null ? opts.a2a : this.a2aConfig;
        List<SkillSource.SkillInfo> skills = skill != null
                ? new ArrayList<>(skill.skills().values())
                : new ArrayList<>();
        LlmClient client = opts.client;
        try {
            // A message's A2A contextId keys the conversation via client.ask, so a peer's turns
            // are remembered through the client's ConversationStore; no contextId ⇒ stateless run.
            return A2AServer.start(addr, a2a, skills,
                    (text, contextId) -> (contextId != null && !contextId.isEmpty())
                            ? client.ask(text, this, contextId)
                            : client.run(text, this),
                    opts.onTask);
        } catch (java.io.IOException e) {
            throw new RuntimeException("serve failed: " + e.getMessage(), e);
        }
    }

    public ToolResult execute(String name, Map<String, Object> args, ToolContext ctx) {
        Tool tool = byName.get(name);
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + name);
        }
        return tool.execute(args == null ? Map.of() : args, ctx);
    }

    public ToolResult execute(String name, Map<String, Object> args) {
        return execute(name, args, null);
    }

    /** Markdown skill catalog for the system prompt. */
    public String skillsPrompt() {
        return skill != null ? skill.prompt() : "";
    }

    /** server -> "connected" | "failed" | "disabled". */
    public Map<String, String> mcpStatus() {
        return mcp != null ? mcp.status() : Map.of();
    }

    public List<Map<String, Object>> toOpenAI() {
        return Adapters.toOpenAI(tools());
    }

    public List<Map<String, Object>> toAnthropic() {
        return Adapters.toAnthropic(tools());
    }

    public List<Map<String, Object>> toGemini() {
        return Adapters.toGemini(tools());
    }

    @Override
    public void close() {
        if (mcp != null) mcp.close();
    }
}
