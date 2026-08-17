package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.agents.AgentDef;
import io.github.muthuishere.toolnexus.agents.Agents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness, loop and the completion gate (openspec/changes/add-harness-and-loop).
 * Hermetic — an ephemeral HttpServer replays scripted assistant messages.
 *
 * Mirrors golang/agents/loop_test.go, js/test/loop.test.ts,
 * python/tests/test_harness_loop.py and csharp HarnessLoopTests case for case: the
 * point of the change is that seven ports agree, and a test that exists in one port
 * only is how that stops being true.
 */
class HarnessLoopTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** The models each request carried, so a test can assert what reached the wire. */
    private final List<String> models = new ArrayList<>();

    private String start(String... messages) throws IOException {
        AtomicInteger i = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int m = body.indexOf("\"model\":\"");
            models.add(m < 0 ? "" : body.substring(m + 9, body.indexOf('"', m + 9)));

            String message = messages[Math.min(i.getAndIncrement(), messages.length - 1)];
            String finish = message.contains("tool_calls") ? "tool_calls" : "stop";
            String json = "{\"choices\":[{\"index\":0,\"message\":" + message
                    + ",\"finish_reason\":\"" + finish + "\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String say(String content) {
        return "{\"role\":\"assistant\",\"content\":\"" + content + "\"}";
    }

    private static String callTodo(String items) {
        return "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"todowrite\",\"arguments\":\"{\\\"todos\\\":["
                + items + "]}\"}}]}";
    }

    private static String todo(String id, String text, boolean done) {
        return "{\\\"id\\\":\\\"" + id + "\\\",\\\"text\\\":\\\"" + text
                + "\\\",\\\"completed\\\":" + done + "}";
    }

    private LlmClient.Options opts(String base) {
        LlmClient.Options o = new LlmClient.Options();
        o.baseUrl = base;
        o.style = "openai";
        o.model = "test-model";
        o.apiKey = "unused";
        return o;
    }

    private Toolkit todoToolkit() throws IOException {
        Toolkit.Options o = new Toolkit.Options();
        o.builtins = Map.of("tools", Map.of(
                "todowrite", true, "bash", false, "read", false, "write", false, "edit", false,
                "glob", false, "grep", false, "webfetch", false, "apply_patch", false,
                "question", false));
        return Toolkit.create(o);
    }

    private Toolkit bareToolkit() throws IOException {
        Toolkit.Options o = new Toolkit.Options();
        o.builtins = false;
        return Toolkit.create(o);
    }

    @Test
    void absentOptionsAreUnchanged() throws IOException {
        String base = start(say("hello"));
        Toolkit tk = bareToolkit();
        Agents.Agent a = Agents.agent("plain", new Agents.AgentSpec().does("answers"));
        Loop.Outcome out = a.loop(opts(base), tk).run("hi");
        assertEquals("done", out.status);
        assertEquals("hello", out.text);
        assertEquals(1, out.attempts);
        assertNull(out.stoppedBy, "a done run names no stop reason");
        tk.close();
    }

    @Test
    void gateBlocksAnOpenTodoThenPasses() throws IOException {
        // Attempt 1 must END with an open item: the client loops on tool calls, so a
        // closing todowrite in the same run would be judged and pass with no retry.
        String base = start(
                callTodo(todo("1", "draft", true) + "," + todo("2", "proofread", false)),
                say("I think I am finished"),
                callTodo(todo("1", "draft", true) + "," + todo("2", "proofread", true)),
                say("all done"));
        Toolkit tk = todoToolkit();
        Agents.Agent a = Agents.agent("gated", new Agents.AgentSpec().does("plans")
                .completion(new Loop.Completion(Loop::allTodosDone, 3)));
        Loop.Outcome out = a.loop(opts(base), tk).run("do the thing");
        assertEquals("done", out.status);
        assertTrue(out.attempts >= 2, "expected a retry, got " + out.attempts);
        tk.close();
    }

    @Test
    void unverifiableRunStopsLoudly() throws IOException {
        String base = start(say("done!"));
        Toolkit tk = bareToolkit();
        Agents.Agent a = Agents.agent("never", new Agents.AgentSpec().does("never verifies")
                .completion(new Loop.Completion(r -> Loop.Verdict.fail("always red"), 2)));
        Loop.Outcome out = a.loop(opts(base), tk).run("go");
        assertEquals("incomplete", out.status, "never a silent done");
        assertEquals(2, out.attempts, "bounded by maxAttempts");
        assertTrue(out.stoppedBy.contains("always red"), out.stoppedBy);
        assertEquals("completion", out.result.limit, "structured, so a caller can tell WHICH limit");
        tk.close();
    }

    @Test
    void maxAttemptsIsRequired() throws IOException {
        String base = start(say("hi"));
        Toolkit tk = bareToolkit();
        Agents.Agent a = Agents.agent("bad", new Agents.AgentSpec().does("x")
                .completion(new Loop.Completion(r -> Loop.Verdict.pass(), 0)));
        assertThrows(IllegalArgumentException.class, () -> a.loop(opts(base), tk).run("go"));
        tk.close();
    }

    @Test
    void noPlanDeclaredPasses() throws IOException {
        String base = start(say("answered without a plan"));
        Toolkit tk = todoToolkit();
        Agents.Agent a = Agents.agent("noplan", new Agents.AgentSpec().does("x")
                .completion(new Loop.Completion(Loop::allTodosDone, 2)));
        Loop.Outcome out = a.loop(opts(base), tk).run("go");
        assertEquals("done", out.status, "the gate must not punish an agent for not using the builtin");
        assertEquals(1, out.attempts);
        tk.close();
    }

    @Test
    void gateJudgesAccumulatedWork() throws IOException {
        // Attempt 1 declares an open item; attempt 2 declares no plan at all. Judging
        // only the latest attempt would see "no plan" and pass.
        String base = start(
                callTodo(todo("1", "ship it", false)),
                say("I am finished, honest"));
        Toolkit tk = todoToolkit();
        Agents.Agent a = Agents.agent("escaper", new Agents.AgentSpec().does("x")
                .completion(new Loop.Completion(Loop::allTodosDone, 2)));
        Loop.Outcome out = a.loop(opts(base), tk).run("go");
        assertEquals("incomplete", out.status, "the earlier open plan must still be visible");
        assertTrue(out.stoppedBy.contains("ship it"), out.stoppedBy);
        tk.close();
    }

    @Test
    void guardrailsFirstDenyWins() {
        int[] seen = {0};
        LlmClient.Hooks hooks = Loop.guardedHooks(List.of(
                ev -> "danger".equals(ev.name()) ? "policy: no" : "allow",
                ev -> { seen[0]++; return "allow"; }), null);

        LlmClient.ToolOverride denied = hooks.beforeTool.apply(
                new LlmClient.BeforeToolEvent("danger", Map.of(), null, 1));
        assertTrue(denied.result().isError());
        assertTrue(denied.result().output().contains("policy: no"));
        assertEquals(0, seen[0], "a later guardrail never runs after a denial");

        assertNull(hooks.beforeTool.apply(new LlmClient.BeforeToolEvent("safe", Map.of(), null, 1)));
        assertEquals(1, seen[0]);
    }

    @Test
    void guardrailsRunBeforeAnExistingHook() {
        int[] prior = {0};
        LlmClient.Hooks existing = new LlmClient.Hooks();
        existing.beforeTool = ev -> { prior[0]++; return null; };

        LlmClient.Hooks hooks = Loop.guardedHooks(
                List.of(ev -> "danger".equals(ev.name()) ? "nope" : "allow"), existing);
        hooks.beforeTool.apply(new LlmClient.BeforeToolEvent("danger", Map.of(), null, 1));
        assertEquals(0, prior[0], "denied => the prior hook is not reached");
        hooks.beforeTool.apply(new LlmClient.BeforeToolEvent("safe", Map.of(), null, 1));
        assertEquals(1, prior[0], "allowed => the prior hook runs");
    }

    @Test
    void guardrailsAndGateSurviveTheRegistryProjection() {
        Agents.Agent child = Agents.agent("child", new Agents.AgentSpec().does("does work")
                .guardrails(ev -> "denied by policy")
                .completion(new Loop.Completion(Loop::allTodosDone, 2)));
        AgentDef def = child.registry().get("child");
        assertNotNull(def.hooks, "the compiled guardrail must be on the projected def");
        assertNotNull(def.hooks.beforeTool);
        assertEquals(2, def.completion.maxAttempts, "the gate travels with the agent");
    }

    @Test
    void perCallModelOverrideReachesTheWire() throws IOException {
        String base = start(say("a"), say("b"));
        Toolkit tk = bareToolkit();
        Loop loop = Agents.agent("m", new Agents.AgentSpec().does("x")).loop(opts(base), tk);
        loop.run("one", new Loop.RunOptions().model("override-model"));
        loop.run("two");
        assertEquals("override-model", models.get(0), "the override reaches the request body");
        assertEquals("test-model", models.get(1), "and does not persist to the next call");
        tk.close();
    }

    @Test
    void turnsAccumulateAndStatusIsObserved() throws IOException {
        String base = start(say("a"), say("b"));
        Toolkit tk = bareToolkit();
        Loop loop = Agents.agent("t", new Agents.AgentSpec().does("x")).loop(opts(base), tk);
        assertEquals("idle", loop.status());
        loop.run("one");
        int afterFirst = loop.turns();
        loop.run("two");
        assertTrue(loop.turns() > afterFirst, "turns accumulate across runs");
        assertEquals("idle", loop.status());
        tk.close();
    }
}
