package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.Toolkit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.muthuishere.toolnexus.agents.Agents.HEARTBEAT_OK;
import static io.github.muthuishere.toolnexus.agents.Agents.agent;
import static io.github.muthuishere.toolnexus.agents.Agents.agentFromDir;
import static io.github.muthuishere.toolnexus.agents.Agents.startAgent;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §7D "Level-1 surface" conformance (S10–S13, 10 checks): {@code agent()} + team wiring +
 * soul file, team scoping (registry = reachable graph), {@code agentFromDir} + a clock-driven
 * heartbeat, and the {@code asTool()} bridge into the classic API.
 */
class AgentsSurfaceTest {

    private static MockLlm mock;

    @BeforeAll
    static void start() { mock = new MockLlm(); }

    @AfterAll
    static void stop() { mock.close(); }

    private static Tool lookupTool() {
        return new Tool() {
            @Override public String name() { return "lookup"; }
            @Override public String description() { return "look something up"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")));
            }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return ToolResult.ok("data(" + args.get("q") + ")");
            }
        };
    }

    private static void check(String name, boolean cond, String detail) {
        assertTrue(cond, name + (detail.isEmpty() ? "" : " — " + detail));
    }

    private static void check(String name, boolean cond) { check(name, cond, ""); }

    private static RuntimeOptions rtOpts() {
        return new RuntimeOptions().baseUrl(mock.base).apiKey("test");
    }

    // S10 — Level 1: the 12-line coding agent -------------------------------
    @Test
    void s10_level1Ux_agentFactory_teamWiring_soulFile() throws IOException {
        Path soulPath = Files.createTempFile("AGENTS-l1", ".md");
        Files.writeString(soulPath, "You are the CODER. Fix things surgically.");
        var explore = agent("explore", new Agents.AgentSpec()
                .does("read-only research").tools(lookupTool()).model("m-explore-bug"));
        var coder = agent("coder", new Agents.AgentSpec()
                .does("implements changes").soulFile(soulPath).team(explore).model("m-coder")
                .budget(new Budget().maxTokens(10_000)));
        TaskResult r = coder.run(rtOpts(), "fix the failing test");
        check("coding agent completes via its team",
                "done".equals(r.status()) && r.text().contains("bug at line 42"), r.text());
        check("soul FILE reached the system prompt", r.text().contains("[soul:loaded]"), r.text());
    }

    // S11 — team scoping: task cannot reach outside the declared team ---------
    @Test
    void s11_teamScoping_taskTargetsAreTheTeam_nothingElse() {
        var stranger = agent("stranger", new Agents.AgentSpec()
                .does("should be unreachable").model("m-explore").tools(lookupTool()));
        var explore = agent("explore", new Agents.AgentSpec()
                .does("read-only research").tools(lookupTool()).model("m-explore"));
        var coder = agent("coder", new Agents.AgentSpec()
                .does("implements").team(explore).model("m-coder"));
        // `stranger` exists in scope but is never wired into coder's team.
        Map<String, AgentDef> reg = coder.registry();
        check("registry contains only the reachable graph",
                String.join(",", reg.keySet().stream().sorted().toList()).equals("coder,explore"),
                String.join(",", reg.keySet()));
        check("coder's task targets are exactly its team",
                List.of("explore").equals(reg.get("coder").team),
                String.valueOf(reg.get("coder").team));
    }

    // S12 — Level 2: agentFromDir + heartbeat + HEARTBEAT_OK silence ----------
    @Test
    void s12_level2Ux_directoryIsTheAgent_heartbeat_silentOk() throws Exception {
        Path dir = Files.createTempDirectory("mia-");
        Files.writeString(dir.resolve("SOUL.md"), "You are Mia. Warm, brief.");
        Files.writeString(dir.resolve("USER.md"), "The user is Muthu.");
        Files.writeString(dir.resolve("MEMORY.md"), "- Likes green tea.");
        Files.writeString(dir.resolve("HEARTBEAT.md"),
                "On heartbeat: if it is watering day, remind to water the plants.");
        var mia = agentFromDir(dir, new Agents.AgentSpec().model("m-mia"));
        TaskResult direct = mia.run(rtOpts(), "hello");
        check("bootstrap files discovered + injected as ## sections",
                "soul-sections:[SOUL.md,USER.md,MEMORY.md]".equals(direct.text()), direct.text());

        List<String> reports = new CopyOnWriteArrayList<>();
        Agents.StartedAgent started = startAgent(mia, rtOpts(), 25L, reports::add);
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            long hb = started.rt.trace().stream().filter(l -> l.contains("idle→running")).count();
            if (hb >= 2) break;
            Thread.sleep(10);
        }
        started.stop();
        long hbTurns = started.rt.trace().stream().filter(l -> l.contains("idle→running")).count();
        check("heartbeat woke the agent repeatedly", hbTurns >= 2, "turns=" + hbTurns);
        check("HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats)",
                reports.stream().allMatch(t -> t.contains("water")), String.valueOf(reports));
        check("agent closed cleanly on stop()", started.handle.state == Handle.State.CLOSED);
    }

    // S13 — the bridge: agent.asTool() inside the classic API -----------------
    @Test
    void s13_bridge_anAgentIsAToolInTheClassicApi() throws Exception {
        var explore = agent("explore", new Agents.AgentSpec()
                .does("read-only research").tools(lookupTool()).model("m-explore-bug"));
        try (Toolkit toolkit = Toolkit.create(new Toolkit.Options()
                .builtins(false).extraTools(explore.asTool(rtOpts())))) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(mock.base).style("openai").model("m-old-api").apiKey("test"));
            LlmClient.RunResult r = client.run("summarize", toolkit);
            check("classic API called the agent like any tool",
                    r.text.contains("old-api summary:") && r.text.contains("bug at line 42"), r.text);
            check("agent metadata surfaced through the tool result",
                    !r.toolCalls.isEmpty() && r.toolCalls.get(0).metadata != null
                            && "explore".equals(r.toolCalls.get(0).metadata.get("agent")));
        }
    }
}
