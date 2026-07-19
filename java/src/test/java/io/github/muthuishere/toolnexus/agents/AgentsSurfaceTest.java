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

import static io.github.muthuishere.toolnexus.agents.Agents.agent;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §7D "Level-1 surface" conformance (S10, S11, S13): {@code agent()} + team wiring + soul
 * file, team scoping (registry = reachable graph), and the {@code asTool()} bridge into the classic
 * API. The §7E persona surface ({@code agentFromDir} + memory + heartbeat) lives in
 * {@link AgentHomeTest}.
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

    // S12 — Level 2 (agentFromDir + heartbeat) moved to AgentHomeTest (§7E, H1–H7) --------------

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
