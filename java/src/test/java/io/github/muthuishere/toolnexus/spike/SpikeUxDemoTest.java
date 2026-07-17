package io.github.muthuishere.toolnexus.spike;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.Toolkit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static io.github.muthuishere.toolnexus.spike.AgentsSpike.HEARTBEAT_OK;
import static io.github.muthuishere.toolnexus.spike.AgentsSpike.agent;
import static io.github.muthuishere.toolnexus.spike.AgentsSpike.agentFromDir;
import static io.github.muthuishere.toolnexus.spike.AgentsSpike.startAgent;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE demo 2 — the Level-1/2/bridge UX (S10–S13) on the same mock-LLM pattern.
 * Java port of {@code js/spike/demo-ux.ts}: same scenarios, same check names.
 */
class SpikeUxDemoTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void startMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = parseJson(body);
            String model = String.valueOf(req.get("model"));
            List<Object> msgs = req.get("messages") instanceof List<?> l ? (List<Object>) l : List.of();
            List<String> toolMsgs = new ArrayList<>();
            String sys = "";
            for (Object o : msgs) {
                if (o instanceof Map<?, ?> m) {
                    if ("tool".equals(m.get("role"))) toolMsgs.add(String.valueOf(m.get("content")));
                    if ("system".equals(m.get("role"))) sys = String.valueOf(m.get("content"));
                }
            }
            Map<String, Object> message;
            switch (model) {
                case "m-coder" -> {
                    if (toolMsgs.isEmpty()) {
                        message = calls(call("t1", "task", Map.of("agent", "explore", "prompt", "find the bug")));
                    } else {
                        message = text("fixed using: " + toolMsgs.get(0) + " [soul:"
                                + (sys.contains("You are the CODER") ? "loaded" : "missing") + "]");
                    }
                }
                case "m-explore" -> {
                    if (toolMsgs.isEmpty()) message = calls(call("e1", "lookup", Map.of("q", "bug")));
                    else message = text("bug at line 42 (" + toolMsgs.get(0) + ")");
                }
                case "m-mia" -> {
                    // persona: soul sections visible? heartbeat with nothing to do → HEARTBEAT_OK
                    String last = msgs.isEmpty() ? "" :
                            String.valueOf(((Map<String, Object>) msgs.get(msgs.size() - 1)).get("content"));
                    if (last.contains("Heartbeat")) {
                        boolean hasTicks = last.contains("channel=timer");
                        message = text(sys.contains("water the plants") && hasTicks
                                ? "Reminder: water the plants! 🌱" : HEARTBEAT_OK);
                    } else {
                        List<String> present = new ArrayList<>();
                        for (String f : List.of("SOUL.md", "USER.md", "MEMORY.md")) {
                            if (sys.contains("## " + f)) present.add(f);
                        }
                        message = text("soul-sections:[" + String.join(",", present) + "]");
                    }
                }
                case "m-old-api" -> {
                    if (toolMsgs.isEmpty()) message = calls(call("b1", "explore", Map.of("prompt", "scan the repo")));
                    else message = text("old-api summary: " + toolMsgs.get(0));
                }
                default -> message = text("ok");
            }
            respond(exchange, message);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopMock() {
        server.stop(0);
    }

    private static Map<String, Object> parseJson(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Map<String, Object> text(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> call(String id, String name, Map<String, Object> args) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("arguments", Json.stringify(args));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("type", "function");
        c.put("function", fn);
        return c;
    }

    @SafeVarargs
    private static Map<String, Object> calls(Map<String, Object>... callObjs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", null);
        m.put("tool_calls", List.of(callObjs));
        return m;
    }

    private static void respond(HttpExchange exchange, Map<String, Object> message) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("choices", List.of(Map.of("message", message)));
        payload.put("usage", Map.of("prompt_tokens", 30, "completion_tokens", 10, "total_tokens", 40));
        byte[] out = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
    }

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
        System.out.println("  " + (cond ? "✅" : "❌") + " " + name + (cond ? "" : " " + detail));
        assertTrue(cond, name + (detail.isEmpty() ? "" : " — " + detail));
    }

    private static void check(String name, boolean cond) { check(name, cond, ""); }

    private static void section(String s) { System.out.println("\n━━ " + s); }

    private static RuntimeOptions rtOpts() {
        return new RuntimeOptions().baseUrl(base);
    }

    // S10 — Level 1: the 12-line coding agent -------------------------------
    @Test
    void s10_level1Ux_agentFactory_teamWiring_soulFile() throws IOException {
        section("S10 Level-1 UX: agent() + team wiring + soul file");
        Path soulPath = Files.createTempFile("AGENTS-spike", ".md");
        Files.writeString(soulPath, "You are the CODER. Fix things surgically.");
        var explore = agent("explore", new AgentsSpike.AgentSpec()
                .does("read-only research").tools(lookupTool()).model("m-explore"));
        var coder = agent("coder", new AgentsSpike.AgentSpec()
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
        section("S11 team scoping: task targets = the team, nothing else");
        var stranger = agent("stranger", new AgentsSpike.AgentSpec()
                .does("should be unreachable").model("m-explore").tools(lookupTool()));
        var explore = agent("explore", new AgentsSpike.AgentSpec()
                .does("read-only research").tools(lookupTool()).model("m-explore"));
        var coder = agent("coder", new AgentsSpike.AgentSpec()
                .does("implements").team(explore).model("m-coder"));
        // stranger is intentionally never wired into coder's team
        Map<String, AgentDef> reg = coder.registry();
        check("registry contains only the reachable graph",
                String.join(",", reg.keySet().stream().sorted().toList()).equals("coder,explore"),
                String.join(",", reg.keySet()));
        check("coder's task targets are exactly its team",
                List.of("explore").equals(reg.get("coder").taskTargets),
                String.valueOf(reg.get("coder").taskTargets));
    }

    // S12 — Level 2: agentFromDir + heartbeat + HEARTBEAT_OK silence ----------
    @Test
    void s12_level2Ux_directoryIsTheAgent_heartbeat_silentOk() throws Exception {
        section("S12 Level-2 UX: the directory IS the agent · heartbeat · silent OK");
        Path dir = Files.createTempDirectory("mia-");
        Files.writeString(dir.resolve("SOUL.md"), "You are Mia. Warm, brief.");
        Files.writeString(dir.resolve("USER.md"), "The user is Muthu.");
        Files.writeString(dir.resolve("MEMORY.md"), "- Likes green tea.");
        Files.writeString(dir.resolve("HEARTBEAT.md"),
                "On heartbeat: if it is watering day, remind to water the plants.");
        var mia = agentFromDir(dir, new AgentsSpike.AgentSpec().model("m-mia"));
        TaskResult direct = mia.run(rtOpts(), "hello");
        check("bootstrap files discovered + injected as ## sections (openclaw order)",
                "soul-sections:[SOUL.md,USER.md,MEMORY.md]".equals(direct.text()), direct.text());

        List<String> reports = new CopyOnWriteArrayList<>();
        AgentsSpike.StartedAgent started = startAgent(mia, rtOpts(), 25L, reports::add);
        Thread.sleep(140);
        started.stop();
        long hbTurns = started.rt.trace().stream().filter(l -> l.contains("idle→running")).count();
        check("heartbeat woke the agent repeatedly", hbTurns >= 2, "turns=" + hbTurns);
        check("HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats)",
                reports.stream().allMatch(t -> t.contains("water")), String.valueOf(reports));
        check("agent closed cleanly on stop()", started.handle.state == Handle.State.CLOSED);
    }

    // S13 — the bridge: agent.asTool() inside the OLD API ---------------------
    @Test
    void s13_bridge_anAgentIsAToolInTheClassicApi() throws Exception {
        section("S13 bridge: an Agent IS a Tool in the classic createToolkit/createClient API");
        var explore = agent("explore", new AgentsSpike.AgentSpec()
                .does("read-only research").tools(lookupTool()).model("m-explore"));
        try (Toolkit toolkit = Toolkit.create(new Toolkit.Options()
                .builtins(false).extraTools(explore.asTool(rtOpts())))) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("m-old-api").apiKey("spike"));
            LlmClient.RunResult r = client.run("summarize", toolkit);
            check("old API called the agent like any tool",
                    r.text.contains("old-api summary:") && r.text.contains("bug at line 42"), r.text);
            check("agent metadata surfaced through the tool result",
                    !r.toolCalls.isEmpty() && r.toolCalls.get(0).metadata != null
                            && "explore".equals(r.toolCalls.get(0).metadata.get("agent")));
        }
    }
}
