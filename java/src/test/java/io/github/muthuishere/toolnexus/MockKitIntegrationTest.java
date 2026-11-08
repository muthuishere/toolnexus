package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.acp.AcpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-2 integration suite over the mock kit (tools/mock-llm/): a mixed toolkit (MCP stdio + HTTP
 * on both protocol paths, HTTP tool, A2A agent, native tool, skills), a client run over it, the
 * in-process one-shot CLI client (fake_cli.py) and the ACP client (fake_acp_agent.py).
 * TN_MOCK_KIT_DIR overrides the kit directory (used for mutation checks).
 */
class MockKitIntegrationTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path KIT = System.getenv("TN_MOCK_KIT_DIR") != null
            ? Path.of(System.getenv("TN_MOCK_KIT_DIR")).toAbsolutePath()
            : REPO.resolve("tools/mock-llm");
    private static final List<Process> procs = new ArrayList<>();
    private static String base;      // mock LLM .../v1
    private static String origin;    // mock LLM origin
    private static String mcpHttp;   // mock_mcp --http (2026 discover path)
    private static String mcpLegacy; // mock_mcp --http --legacy (frozen path)

    private static String spawn(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("python3");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        procs.add(p);
        String line = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).readLine();
        assertNotNull(line, "no banner from " + cmd);
        int i = line.indexOf("http://");
        assertTrue(i >= 0, "unexpected banner: " + line);
        return line.substring(i).trim();
    }

    @BeforeAll
    static void startKit() throws Exception {
        try {
            new ProcessBuilder("python3", "--version").start().waitFor();
        } catch (IOException e) {
            Assumptions.abort("python3 not available; skipping mock-kit integration suite");
        }
        String env = System.getenv("TN_MOCK_LLM_URL");
        base = env != null && !env.isBlank() ? env.replaceAll("/+$", "")
                : spawn(KIT.resolve("mock_llm.py").toString(), "--port", "0", "--quiet");
        origin = base.replaceAll("/v1$", "");
        mcpHttp = spawn(KIT.resolve("mock_mcp.py").toString(), "--http", "--port", "0");
        mcpLegacy = spawn(KIT.resolve("mock_mcp.py").toString(), "--http", "--port", "0", "--legacy");
    }

    @AfterAll
    static void stopKit() {
        procs.forEach(Process::destroy);
    }

    private static final List<Map<String, Object>> addCalls = new CopyOnWriteArrayList<>();

    private static NativeTool nativeAdd() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("a", Map.of("type", "number"));
        props.put("b", Map.of("type", "number"));
        schema.put("properties", props);
        schema.put("required", List.of("a", "b"));
        return NativeTool.of("add", "Add two numbers.", schema, args -> {
            addCalls.add(args);
            return String.valueOf(((Number) args.get("a")).intValue() + ((Number) args.get("b")).intValue());
        });
    }

    private static Toolkit mixedToolkit() {
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("mcpstdio", Map.of("type", "local",
                "command", List.of("python3", KIT.resolve("mock_mcp.py").toString()), "timeout", 30000));
        servers.put("mcphttp", Map.of("type", "remote", "url", mcpHttp, "timeout", 30000));
        servers.put("mcplegacy", Map.of("type", "remote", "url", mcpLegacy, "timeout", 30000));
        HttpTool.Options h = new HttpTool.Options();
        h.name = "http_add";
        h.description = "Add two integers over HTTP.";
        h.method = "POST";
        h.url = base + "/tools/add";
        h.body = "json";
        h.inputSchema = Map.of("type", "object",
                "properties", Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "integer")),
                "required", List.of("a", "b"));
        return Toolkit.create(new Toolkit.Options().builtins(false)
                .mcpConfig(Map.of("mcpServers", servers))
                .skillsDir(REPO.resolve("examples/skills").toString())
                .agents(A2A.agent(origin + "/.well-known/agent-card.json", null, 30_000L, 20L))
                .extraTools(nativeAdd(), HttpTool.of(h)));
    }

    @Test
    void mixedToolkitEverySource() {
        addCalls.clear();
        try (Toolkit tk = mixedToolkit()) {
            Set<String> names = tk.tools().stream().map(Tool::name).collect(Collectors.toSet());
            for (String n : List.of("mcpstdio_add", "mcpstdio_echo", "mcphttp_add", "mcphttp_echo",
                    "mcplegacy_add", "mcplegacy_echo", "http_add", "mockagent_summarize", "add", "skill")) {
                assertTrue(names.contains(n), n + " missing from " + names);
            }
            Map<String, Object> ab = Map.of("a", 2, "b", 3);
            for (String mcp : List.of("mcpstdio_add", "mcphttp_add", "mcplegacy_add")) {
                ToolResult r = tk.execute(mcp, ab);
                assertFalse(r.isError(), mcp + ": " + r.output());
                assertEquals("5", r.output().strip(), mcp);
            }
            ToolResult echo = tk.execute("mcphttp_echo", Map.of("text", "hi"));
            // Java surfaces MCP structuredContent as the JSON output text.
            assertEquals(Map.of("text", "hi"), Json.toMap(echo.output()));

            ToolResult http = tk.execute("http_add", ab);
            assertFalse(http.isError(), http.output());
            assertTrue(http.output().replace(" ", "").contains("{\"sum\":5}"), http.output());

            ToolResult a2a = tk.execute("mockagent_summarize", Map.of("task", "x"));
            assertFalse(a2a.isError(), a2a.output());
            assertTrue(a2a.output().contains("A2A mock done: x"), a2a.output());

            ToolResult nat = tk.execute("add", ab);
            assertEquals("5", nat.output());
            assertEquals(1, addCalls.size());

            ToolResult skill = tk.execute("skill", Map.of("name", "hello-world"));
            assertFalse(skill.isError(), skill.output());
            assertTrue(skill.output().contains("This is the skill body."), skill.output());
        }
    }

    @Test
    void clientRunOverMixedToolkit() {
        try (Toolkit tk = mixedToolkit()) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("mock-model").apiKey("mock-key"));
            LlmClient.RunResult res = client.run("use a tool", tk);
            assertEquals(1, res.toolCalls.size(), "exactly one tool call");
            LlmClient.ToolCall call = res.toolCalls.get(0);
            assertFalse(call.isError, call.name + ": " + call.output);
            assertTrue(tk.tools().stream().anyMatch(t -> t.name().equals(call.name)), call.name);
            assertEquals("Tool result: " + call.output.strip(), res.text);
        }
    }

    // ------------------------------------------------------------------ one-shot CLI (fake_cli.py)

    @SuppressWarnings("unchecked")
    private static String render(InProcess.Request req) {
        List<String> lines = new ArrayList<>(List.of("You are the model inside a tool-calling loop. Tools you may call:"));
        for (Object o : req.tools) {
            Map<String, Object> f = (Map<String, Object>) ((Map<String, Object>) o).get("function");
            lines.add("- " + f.get("name") + ": " + f.get("description") + " args=" + Json.stringify(f.get("parameters")));
        }
        lines.add("");
        lines.add("Conversation:");
        Map<Object, Object> called = new HashMap<>();
        for (Object o : req.messages) {
            Map<String, Object> m = (Map<String, Object>) o;
            Object content = m.get("content");
            if ("tool".equals(m.get("role"))) {
                lines.add("[tool " + called.getOrDefault(m.get("tool_call_id"), m.get("tool_call_id")) + "] " + content);
            } else if (m.get("tool_calls") != null) {
                List<Object> fns = new ArrayList<>();
                for (Object c : (List<Object>) m.get("tool_calls")) {
                    Map<String, Object> fn = (Map<String, Object>) ((Map<String, Object>) c).get("function");
                    called.put(((Map<String, Object>) c).get("id"), fn.get("name"));
                    fns.add(fn);
                }
                lines.add("[assistant called] " + Json.stringify(fns));
            } else {
                lines.add("[" + m.get("role") + "] " + (content instanceof String s ? s : Json.stringify(content)));
            }
        }
        lines.add("");
        lines.add("Reply with ONLY one JSON object: {\"tool_calls\":[{\"name\":\"...\",\"arguments\":{...}}]} to call tools,");
        lines.add("or {\"content\":\"...\"} with the final answer. No prose, no code fences.");
        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private static InProcess.Response parse(String out) {
        int s = out.indexOf('{'), e = out.lastIndexOf('}');
        if (s >= 0 && e > s) {
            Map<String, Object> r = Json.toMap(out.substring(s, e + 1));
            if (r.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()) {
                List<InProcess.ToolCall> tcs = new ArrayList<>();
                for (Object c : calls) {
                    Map<String, Object> call = (Map<String, Object>) c;
                    Object args = call.get("arguments");
                    tcs.add(new InProcess.ToolCall((String) call.get("name"), args == null ? Map.of() : args));
                }
                return InProcess.Response.toolCalls(tcs.toArray(InProcess.ToolCall[]::new));
            }
            if (r.get("content") instanceof String str) return InProcess.Response.content(str);
        }
        return InProcess.Response.content(out.strip());
    }

    @Test
    void oneShotCliClient() {
        List<String> cmd = List.of("python3", KIT.resolve("fake_cli.py").toString());
        LlmClient client = InProcess.createClient(new InProcess.Options().model("fake-cli").generate(req -> {
            try {
                Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
                    try { return p.getInputStream().readAllBytes(); } catch (IOException ex) { return new byte[0]; }
                });
                try (OutputStream in = p.getOutputStream()) { in.write(render(req).getBytes(StandardCharsets.UTF_8)); }
                assertTrue(p.waitFor(30, TimeUnit.SECONDS), "fake_cli timed out");
                assertEquals(0, p.exitValue(), "fake_cli exit");
                return parse(new String(out.join(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }));
        addCalls.clear();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(nativeAdd()))) {
            LlmClient.RunResult r = client.run("Use the add tool to add 19 and 23, then tell me the sum.", tk);
            assertEquals("The sum is 42.", r.text);
            assertEquals(1, addCalls.size());
            assertEquals(19, ((Number) addCalls.get(0).get("a")).intValue());
            assertEquals(23, ((Number) addCalls.get(0).get("b")).intValue());
        }
    }

    // ------------------------------------------------------------------ ACP (fake_acp_agent.py)

    private record AcpRun(LlmClient.RunResult result, String streamed, RuntimeException error) {}

    private AcpRun acpTurn(boolean serveToolkit, String permission, String prompt, boolean cancelOnWorking)
            throws Exception {
        addCalls.clear();
        StringBuilder streamed = new StringBuilder();
        LlmClient.CancelToken cancel = new LlmClient.CancelToken();
        try (Toolkit toolkit = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(nativeAdd()));
             McpServe.LocalServer server = toolkit.asMcpServer();
             Toolkit empty = Toolkit.create(new Toolkit.Options().builtins(false))) {
            AcpClient.Options o = new AcpClient.Options()
                    .command(List.of("python3", KIT.resolve("fake_acp_agent.py").toString()))
                    .timeoutMs(30_000)
                    .onUpdate(u -> {
                        if (cancelOnWorking && u.toString().contains("Working...")) cancel.cancel();
                    });
            if (permission != null) o.onPermission(p -> permission);
            if (serveToolkit) {
                o.mcpServers(List.of(Map.of("type", "http", "name", "toolnexus", "url", server.url(),
                        "headers", List.of(Map.of("name", "Authorization", "value", "Bearer " + server.token())))));
            }
            try (AcpClient agent = AcpClient.start(o)) {
                LlmClient client = InProcess.createClient(new InProcess.Options().model("fake-acp")
                        .generateStream((req, emit) -> {
                            try { return agent.generateStream(req, emit); }
                            catch (IOException e) { throw new UncheckedIOException(e); }
                        }));
                if (cancelOnWorking) {
                    try {
                        return new AcpRun(client.run(prompt, empty, null, cancel), "", null);
                    } catch (RuntimeException e) {
                        return new AcpRun(null, "", e);
                    }
                }
                LlmClient.RunResult r = client.stream(prompt, empty, ev -> {
                    if (ev.type() == LlmClient.StreamEvent.Kind.TEXT && ev.delta() != null) streamed.append(ev.delta());
                });
                return new AcpRun(r, streamed.toString(), null);
            }
        }
    }

    @Test
    void acpApprovedRunsServedTool() throws Exception {
        AcpRun run = acpTurn(true, "ok", "Use the add tool to add 17 and 25.", false);
        assertEquals("The sum is 42.", run.result().text);
        assertEquals("The sum is 42.", run.streamed());
        assertEquals(1, addCalls.size(), "add executed through the served toolkit");
        assertEquals(17, ((Number) addCalls.get(0).get("a")).intValue());
        assertEquals(25, ((Number) addCalls.get(0).get("b")).intValue());
    }

    @Test
    void acpPermissionRejected() throws Exception {
        AcpRun run = acpTurn(true, "no", "Use the add tool to add 17 and 25.", false);
        assertEquals("Permission denied.", run.result().text);
        assertEquals(0, addCalls.size(), "add must not run when permission is rejected");
    }

    @Test
    void acpNoMcpServerPlainReply() throws Exception {
        AcpRun run = acpTurn(false, null, "hello agent", false);
        // The ACP client renders the transcript into the prompt, so the echo carries it.
        assertTrue(run.result().text.startsWith("Mock ACP reply to: ") && run.result().text.contains("hello agent"),
                run.result().text);
    }

    @Test
    void acpCancel() throws Exception {
        AcpRun run = acpTurn(false, null, "please [wait] here", true);
        assertNull(run.result(), "a cancelled turn must not complete");
        assertInstanceOf(LlmClient.CancelledException.class, run.error(), String.valueOf(run.error()));
    }
}
