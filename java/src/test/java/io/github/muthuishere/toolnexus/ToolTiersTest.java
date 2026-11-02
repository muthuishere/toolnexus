package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tool tiers (change add-prompt-cache, phase 2), Java port of {@code golang/tool_tiers_test.go}. */
class ToolTiersTest {

    static Toolkit tierToolkit(List<String> ran) {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")));
        java.util.function.BiFunction<String, String, Tool> mk = (name, desc) -> NativeTool.of(name, desc, schema,
                (Map<String, Object> a) -> {
                    ran.add(name + "(" + a.get("x") + ")");
                    return name + " ok";
                });
        return Toolkit.create(new Toolkit.Options().builtins(false).extraTools(
                Tool.withHints(mk.apply("zeta_read", "Read zeta.\nsecond line hidden"), ToolHints.READ_ONLY),
                Tool.withHints(mk.apply("alpha_read", "Read alpha."), ToolHints.READ_ONLY),
                Tool.withHints(mk.apply("deploy", "Deploy the service to production."), ToolHints.DESTRUCTIVE),
                mk.apply("lint", "Lint the code.")));
    }

    static List<String> names(List<Tool> tools) {
        List<String> out = new ArrayList<>();
        for (Tool t : tools) out.add(t.name());
        return out;
    }

    @Test
    void planAndDescription() {
        List<String> ran = new CopyOnWriteArrayList<>();
        Toolkit tk = tierToolkit(ran);
        LlmClient c = LlmClient.create(new LlmClient.Options().baseUrl("http://x").model("m").apiKey("k")
                .toolTiers(new ToolTiers().core("zeta_read", "alpha_read").deferred("deploy")));
        List<Tool> got = c.requestTools(tk);
        assertEquals(List.of("alpha_read", "zeta_read", "tool_search", "call_tool"), names(got));
        String desc = got.get(2).description();
        assertTrue(desc.contains("- lint — Lint the code.") && desc.contains("(name only): deploy")
                && !desc.contains("Deploy the service"), desc);
        Map<String, ToolTiers.Tier> plan = c.toolTierPlan(tk);
        assertEquals(ToolTiers.Tier.DEFERRED, plan.get("deploy"));
        assertEquals(ToolTiers.Tier.LISTED, plan.get("lint"));
        assertEquals(ToolTiers.Tier.CORE, plan.get("alpha_read"));
        // a tool registered after the plan froze: not in the array or the description, found by search
        tk.register(NativeTool.of("late", "A late tool.", null, (Map<String, Object> a) -> "late ok"));
        List<Tool> again = c.requestTools(tk);
        assertEquals(4, again.size());
        assertEquals(desc, again.get(2).description(), "the tools array changed after a late register");
        ToolResult r = ToolTiers.search(tk, Map.of("query", "late"));
        assertTrue(r.output().contains("\"name\": \"late\""), r.output());
        // resetToolTiers rebuilds: the late tool is listed now
        c.resetToolTiers(tk);
        assertTrue(c.requestTools(tk).get(2).description().contains("- late — A late tool."));
    }

    @Test
    void budgetDemotesLeastUsed() {
        Toolkit tk = tierToolkit(new ArrayList<>());
        Map<String, Integer> uses = Map.of("alpha_read", 9, "zeta_read", 1, "lint", 5, "deploy", 0);
        ToolTiers.Plan base = ToolTiers.buildPlan(new ToolTiers().core("alpha_read", "zeta_read"), tk.tools());
        int fixed = ToolTiers.defaultCountTokens(ToolTiers.toolDef(base.search))
                + ToolTiers.defaultCountTokens(ToolTiers.toolDef(base.call));
        int one = ToolTiers.defaultCountTokens(ToolTiers.toolDef(tk.get("alpha_read")));
        ToolTiers.Plan p = ToolTiers.buildPlan(new ToolTiers().core("alpha_read", "zeta_read")
                .budgetTokens(fixed + one + 15).usageCount(n -> uses.getOrDefault(n, 0)), tk.tools());
        assertEquals(ToolTiers.Tier.CORE, p.tier.get("alpha_read"), p.tier.toString());
        assertEquals(ToolTiers.Tier.LISTED, p.tier.get("zeta_read"), p.tier.toString());
        p = ToolTiers.buildPlan(new ToolTiers().core("alpha_read").budgetTokens(1)
                .usageCount(n -> uses.getOrDefault(n, 0)), tk.tools());
        for (Map.Entry<String, ToolTiers.Tier> e : p.tier.entrySet()) {
            assertEquals(ToolTiers.Tier.DEFERRED, e.getValue(), e.getKey() + " under budget 1");
        }
    }

    /** tool_search loads deploy, the model calls it via call_tool, the permission hook sees the
     * real name and hints, and the tools array is the same bytes on every request. */
    @Test
    void loopCallToolUnwraps() throws IOException {
        List<String> ran = new CopyOnWriteArrayList<>();
        Toolkit tk = tierToolkit(ran);
        List<String> toolArrays = new CopyOnWriteArrayList<>();
        AtomicInteger step = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, Object> body = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            toolArrays.add(Json.stringify(body.get("tools")));
            Object content = switch (step.incrementAndGet()) {
                case 1 -> List.of(Map.of("type", "tool_use", "id", "s1", "name", "tool_search", "input", Map.of("query", "select:deploy")));
                case 2 -> List.of(Map.of("type", "tool_use", "id", "c1", "name", "call_tool",
                        "input", Map.of("name", "deploy", "arguments", Map.of("x", "prod"))));
                case 3 -> List.of(Map.of("type", "tool_use", "id", "d1", "name", "lint", "input", Map.of("x", "direct")));
                default -> List.of(Map.of("type", "text", "text", "done"));
            };
            HostEngineAsksTest.writeJson(ex, Json.stringify(Map.of("content", content, "stop_reason", "tool_use",
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1))));
        });
        server.start();
        List<String> gated = new CopyOnWriteArrayList<>();
        try {
            LlmClient c = LlmClient.create(new LlmClient.Options()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).style("anthropic").model("m").apiKey("k")
                    .toolTiers(new ToolTiers().core("alpha_read").deferred("deploy"))
                    .hooks(new LlmClient.Hooks().beforeTool(ev -> {
                        gated.add(ev.name() + ":" + (ev.hints() == null ? "?" : String.valueOf(ev.hints().destructive())));
                        return null;
                    })));
            LlmClient.RunResult res = c.run("ship it", tk);
            assertEquals("done", res.status, res.text);
            assertTrue(res.toolCalls.get(0).output.contains("\"name\": \"deploy\""), res.toolCalls.get(0).output);
            assertEquals(List.of("deploy(prod)", "lint(direct)"), ran);
            StringBuilder rec = new StringBuilder();
            for (LlmClient.ToolCall tc : res.toolCalls) {
                if (rec.length() > 0) rec.append(',');
                rec.append(tc.name).append('(').append(tc.args == null ? null : tc.args.get("x")).append(')');
            }
            assertEquals("tool_search(null),deploy(prod),lint(direct)", rec.toString(),
                    "RunResult records the real tool + inner args for call_tool");
            assertEquals(List.of("deploy:true", "lint:?"), gated,
                    "tool_search is engine-internal; call_tool must be unwrapped");
            for (String a : toolArrays) assertEquals(toolArrays.get(0), a, "tools array changed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamNamesRealTool() throws IOException {
        List<String> ran = new CopyOnWriteArrayList<>();
        Toolkit tk = tierToolkit(ran);
        AtomicInteger step = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String sse;
            if (step.incrementAndGet() == 1) {
                String args = Json.stringify(Map.of("name", "deploy", "arguments", Map.of("x", "prod")));
                sse = "data: " + Json.stringify(Map.of("choices", List.of(Map.of("delta", Map.of("tool_calls", List.of(
                        Map.of("index", 0, "id", "c1", "type", "function",
                                "function", Map.of("name", "call_tool", "arguments", args)))))))) + "\n\n"
                        + "data: " + Json.stringify(Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", "tool_calls")))) + "\n\n";
            } else {
                sse = "data: " + Json.stringify(Map.of("choices", List.of(Map.of("delta", Map.of("content", "done"), "finish_reason", "stop")))) + "\n\n";
            }
            HostEngineAsksTest.writeSse(ex, sse + "data: [DONE]\n\n");
        });
        server.start();
        List<String> got = new ArrayList<>();
        LlmClient.RunResult sres;
        try {
            LlmClient c = LlmClient.create(new LlmClient.Options()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).style("openai").model("m").apiKey("k")
                    .toolTiers(new ToolTiers().core("alpha_read")));
            sres = c.stream("go", tk, ev -> {
                if (ev.type() == LlmClient.StreamEvent.Kind.TOOL_CALL || ev.type() == LlmClient.StreamEvent.Kind.TOOL_RESULT) {
                    got.add(ev.type() + ":" + ev.name() + ":" + ev.via() + ":" + (ev.args() == null ? null : ev.args().get("x")));
                }
            });
        } finally {
            server.stop(0);
        }
        assertEquals(List.of("TOOL_CALL:deploy:call_tool:prod", "TOOL_RESULT:deploy:call_tool:null"), got);
        assertEquals(List.of("deploy(prod)"), ran);
        assertEquals("deploy", sres.toolCalls.get(0).name, "stream RunResult records the real tool");
        assertEquals("prod", sres.toolCalls.get(0).args.get("x"));
    }

    @Test
    void searchOutputIsGoShapedJson() {
        Toolkit tk = tierToolkit(new ArrayList<>());
        ToolResult r = ToolTiers.search(tk, Map.of("query", "select:lint"));
        assertEquals("Loaded tools (call by name, or with call_tool):\n[\n  {\n    \"description\": \"Lint the code.\",\n"
                + "    \"inputSchema\": {\n      \"properties\": {\n        \"x\": {\n          \"type\": \"string\"\n        }\n"
                + "      },\n      \"type\": \"object\"\n    },\n    \"name\": \"lint\"\n  }\n]", r.output());
        assertTrue(ToolTiers.search(tk, Map.of("query", "select:nope")).isError());
        assertEquals("No matching tools.", ToolTiers.search(tk, Map.of("query", "zzzz")).output());
    }

    /** Same bytes as golang TestToolSearchJSONIsHTMLEscaped: sorted keys, 2-space indent, &lt;&gt;&amp; escaped. */
    @Test
    void toolSearchJsonEscapesLikeGo() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")));
        Tool amp = NativeTool.of("amp", "Use <a & b> here", schema, (Map<String, Object> a) -> "");
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(amp));
        String want = "Loaded tools (call by name, or with call_tool):\n[\n  {\n    \"description\": \"Use \\u003ca \\u0026 b\\u003e here\",\n"
                + "    \"inputSchema\": {\n      \"properties\": {\n        \"x\": {\n          \"type\": \"string\"\n"
                + "        }\n      },\n      \"type\": \"object\"\n    },\n    \"name\": \"amp\"\n  }\n]";
        assertEquals(want, ToolTiers.search(tk, Map.of("query", "select:amp")).output());
    }

    @Test
    void firstLineCutsOnCharacterBoundary() {
        assertEquals("a".repeat(119) + "…", ToolTiers.firstLine("a".repeat(119) + "é tail"));
        assertEquals("a".repeat(117) + "€…", ToolTiers.firstLine("a".repeat(117) + "€z"));
        assertEquals("a".repeat(118) + "…", ToolTiers.firstLine("a".repeat(118) + "\uD83D\uDE00z"));
    }
}
