package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Toolkit — aggregates dynamic MCP tools + the skill tool + custom/native/http
 * tools into a single uniform list, with provider adapters and a single
 * {@code execute()} router. Mirrors the JS reference ({@code js/src/toolkit.ts}).
 */
public final class Toolkit implements AutoCloseable {
    private final McpSource mcp;       // may be null
    private final SkillSource skill;   // may be null
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public static final class Options {
        public Object mcpConfig;            // path string, raw JSON string, or parsed Map
        public List<String> skillsDir;      // one or more skill roots
        public List<Tool> extraTools;       // custom/native/http tools
        public List<Object> annotatedObjects; // objects scanned via Tools.fromObject

        public Options mcpConfig(Object v) { this.mcpConfig = v; return this; }
        public Options skillsDir(String... v) { this.skillsDir = List.of(v); return this; }
        public Options skillsDir(List<String> v) { this.skillsDir = v; return this; }
        public Options extraTools(List<Tool> v) { this.extraTools = v; return this; }
        public Options extraTools(Tool... v) { this.extraTools = List.of(v); return this; }
        public Options annotatedObjects(Object... v) { this.annotatedObjects = List.of(v); return this; }
    }

    private Toolkit(McpSource mcp, SkillSource skill, List<Tool> extraTools) {
        this.mcp = mcp;
        this.skill = skill;
        List<Tool> all = new ArrayList<>();
        if (mcp != null) all.addAll(mcp.tools());
        if (skill != null) all.add(skill.tool());
        if (extraTools != null) all.addAll(extraTools);
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
        return new Toolkit(mcp, skill, extras);
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
