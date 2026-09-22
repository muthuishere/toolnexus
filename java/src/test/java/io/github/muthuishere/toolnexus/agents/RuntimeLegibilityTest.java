package io.github.muthuishere.toolnexus.agents;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3 / ADR 0025 — "the runtime path must be as legible as the loop".
 *
 * <p>Two separate claims, tested separately: the token/limit reporting on {@link TaskResult}, and
 * the Java-only §0 break where a durable resume replayed the literal string {@code "continue"} and
 * dropped both the caller's prompt and the drained inbox.
 *
 * <p>Hermetic: a scripted localhost model, no network, no API key.
 */
class RuntimeLegibilityTest {

    /** A scripted model that records the user message of every turn. */
    private static final class Model implements AutoCloseable {
        private final HttpServer server;
        final String base;
        final List<String> userTurns = new ArrayList<>();
        final AtomicBoolean suspendNext = new AtomicBoolean(true);

        @SuppressWarnings("unchecked")
        Model() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", (HttpExchange ex) -> {
                Map<String, Object> req =
                        Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                List<Object> msgs = req.get("messages") instanceof List<?> l ? (List<Object>) l : List.of();
                String lastUser = "";
                boolean sawToolResult = false;
                for (Object o : msgs) {
                    if (o instanceof Map<?, ?> m) {
                        if ("user".equals(m.get("role"))) lastUser = String.valueOf(m.get("content"));
                        if ("tool".equals(m.get("role"))) sawToolResult = true;
                    }
                }
                synchronized (userTurns) {
                    userTurns.add(lastUser);
                }
                Map<String, Object> message = sawToolResult
                        ? text("done for: " + lastUser)
                        : calls("c1", "approve");
                respond(ex, message);
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override public void close() { server.stop(0); }
    }

    private static Map<String, Object> text(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> calls(String id, String name) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("arguments", "{}");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("type", "function");
        c.put("function", fn);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", null);
        m.put("tool_calls", List.of(c));
        return m;
    }

    private static void respond(HttpExchange ex, Map<String, Object> message) throws IOException {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("choices", List.of(choice));
        body.put("usage", Map.of("prompt_tokens", 10, "completion_tokens", 10, "total_tokens", 20));
        byte[] out = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** A tool that suspends the FIRST time and completes once it is handed an Answer. */
    private static Tool approver() {
        return NativeTool.of("approve", "asks a human to approve",
                Map.of("type", "object", "properties", Map.of()),
                (Map<String, Object> args, ToolContext ctx) -> {
                    if (ctx != null && ctx.answer() != null) return ToolResult.ok("approved");
                    return ToolResult.pending(new Request("req-1", "approval", "may I proceed?"));
                });
    }

    private static RuntimeOptions opts(Model model, Map<String, AgentDef> reg) {
        return new RuntimeOptions().baseUrl(model.base).style("openai")
                .apiKey("test-only-not-a-secret").defaultModel("m").registry(reg);
    }

    private static Map<String, AgentDef> registry() {
        AgentDef d = new AgentDef("worker", "does the work", "", "m");
        d.tools = List.of(approver());
        Map<String, AgentDef> reg = new LinkedHashMap<>();
        reg.put("worker", d);
        return reg;
    }

    // ---------------------------------------------------------------------

    @Test
    void resumeReplaysTheSuspendedTurnsOwnInput_notTheLiteralContinue() {
        try (Model model = new Model()) {
            AgentRuntime rt = new AgentRuntime(opts(model, registry()));
            Handle h = rt.spawn(rt.root, "worker").handle();

            // An unsolicited post rides along with the prompt into the turn that suspends.
            rt.post(h, new InboxItem("ops", "chat", "also check the changelog"));
            var fut = rt.futureResult(h);
            rt.wake(h, "deploy to staging");
            TaskResult pending = settle(fut);
            assertEquals(TaskResult.STATUS_PENDING, pending.status(), pending.text());

            TaskResult resumed = rt.resume(Answer.output("req-1", "yes"));

            // A4: resume hands back the result of the topmost handle the cascade re-ran.
            assertNotNull(resumed);
            assertEquals(TaskResult.STATUS_DONE, resumed.status(), resumed.text());

            String replayed;
            synchronized (model.userTurns) {
                replayed = model.userTurns.get(model.userTurns.size() - 1);
            }
            assertFalse("continue".equals(replayed),
                    "the resumed turn must not replay the literal \"continue\"");
            assertTrue(replayed.contains("deploy to staging"),
                    "the caller's prompt survives the suspension: " + replayed);
            assertTrue(replayed.contains("also check the changelog"),
                    "the DRAINED INBOX survives the suspension: " + replayed);
        }
    }

    @Test
    void totalTokensIsTheCumulativeTreeTotalOnEveryStatus_andOwnTokensIsThePerAgentFigure() {
        try (Model model = new Model()) {
            AgentRuntime rt = new AgentRuntime(opts(model, registry()));
            Handle h = rt.spawn(rt.root, "worker").handle();
            var fut = rt.futureResult(h);
            rt.wake(h, "go");
            TaskResult pending = settle(fut);

            assertEquals(TaskResult.STATUS_PENDING, pending.status());
            assertEquals(h.usageTotal, pending.totalTokens(),
                    "totalTokens is the cumulative tree total, on pending too");
            assertTrue(pending.ownTokens() > 0, "ownTokens carries this agent's own turn");
            assertTrue(pending.totalTokens() >= pending.ownTokens());

            TaskResult done = rt.resume(Answer.output("req-1", "yes"));
            assertEquals(h.usageTotal, done.totalTokens(),
                    "and on done — one field, one meaning");
            assertTrue(done.totalTokens() > pending.totalTokens(), "the tree total only grows");
        }
    }

    /**
     * D3: a limit stop NAMES the limit, and names it with the CANONICAL spelling (A14) rather
     * than java's internal pool name. A wake on a SUSPENDED handle is refused as "busy" and never settles, so the turn is
     * refused with a "busy" early result that never settles. (The earlier shape of this test woke
     * a SUSPENDED handle and hung forever on a waiter that could not be flushed.)
     */
    @Test
    void limitNamesWhatStoppedTheRun_includingABudgetStop() {
        try (Model model = new Model()) {
            Map<String, AgentDef> reg = registry();
            reg.get("worker").budget = new Budget().maxTokens(1); // one turn's spend exhausts it
            AgentRuntime rt = new AgentRuntime(opts(model, reg));
            Handle h = rt.spawn(rt.root, "worker").handle();

            var fut = rt.futureResult(h);
            rt.wake(h, "go");
            assertEquals(TaskResult.STATUS_PENDING, settle(fut).status());

            // The first turn spent the whole token pool, so the resumed turn is refused by the
            // budget walk and SETTLES incomplete — partial work preserved, limit named.
            TaskResult budget = rt.resume(Answer.output("req-1", "yes"));
            assertEquals(TaskResult.STATUS_INCOMPLETE, budget.status(), budget.text());
            assertTrue(budget.text().startsWith("budget exhausted"), budget.text());
            assertEquals("maxTokens", budget.limit(),
                    "a budget stop names the BUDGET FIELD, spelled as SPEC spells it — never the "
                            + "internal pool name \"tokens\"");
        }
    }

    /** Join with a deadline: a settle that never arrives must FAIL the suite, never hang it. */
    private static TaskResult settle(java.util.concurrent.CompletableFuture<TaskResult> f) {
        try {
            return f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("no settle within 30s: " + e, e);
        }
    }

    /**
     * A13a. {@code turns} is the handle's OWN CUMULATIVE round trips, reported identically on
     * EVERY status — not the single run's figure on done/pending/incomplete and the cumulative
     * one on error/closed/timeout. It is NOT rolled up the ancestor chain: that is tokens, and
     * {@code examples/subagent-fanout/fixture.json} pins {@code parentTurns: 2} beside
     * {@code parentUsageTotal: 240} precisely because the two fields behave differently.
     */
    @Test
    void a13a_turnsIsTheHandlesOwnCumulativeFigure_onEveryStatus() {
        try (Model model = new Model()) {
            AgentRuntime rt = new AgentRuntime(opts(model, registry()));
            Handle h = rt.spawn(rt.root, "worker").handle();
            var fut = rt.futureResult(h);
            rt.wake(h, "go");
            TaskResult pending = settle(fut);
            assertEquals(h.turnsTotal, pending.turns(), "cumulative on pending");

            TaskResult done = rt.resume(Answer.output("req-1", "yes"));
            assertEquals(h.turnsTotal, done.turns(), "…and on done — one field, one meaning");
            assertTrue(done.turns() >= pending.turns(), "a handle's own turn count only grows");
        }
    }

    /**
     * A18 — the INVARIANT, not an instance: a limit stop MUST name its limit, and a non-limit
     * stop MUST leave the field empty. Three settles of different shapes are driven through it
     * and every value is checked for membership of its own CLOSED vocabulary. The bug class this
     * catches is a settle whose {@code status} says "timeout"/"incomplete" while the {@code limit}
     * a host branches on is empty — the two fields contradicting each other inside the very
     * feature added so hosts could branch.
     */
    @Test
    void a18_statusAndLimitNeverContradictEachOther() {
        try (Model model = new Model()) {
            List<TaskResult> settles = new ArrayList<>();

            // (1) a `done` settle.
            AgentRuntime rt = new AgentRuntime(opts(model, registry()));
            Handle h = rt.spawn(rt.root, "worker").handle();
            var f1 = rt.futureResult(h);
            rt.wake(h, "go");
            settles.add(settle(f1));                                 // pending
            settles.add(rt.resume(Answer.output("req-1", "yes")));    // done

            // (2) a budget `incomplete` settle. The handle must be IDLE for the wake to reach
            // the budget walk, so the suspended turn is resumed to completion first.
            Map<String, AgentDef> reg = registry();
            reg.get("worker").budget = new Budget().maxTokens(1);
            AgentRuntime rt2 = new AgentRuntime(opts(model, reg));
            Handle h2 = rt2.spawn(rt2.root, "worker").handle();
            var f2 = rt2.futureResult(h2);
            rt2.wake(h2, "go");
            settles.add(settle(f2));
            settles.add(rt2.resume(Answer.output("req-1", "yes")));
            var f3 = rt2.futureResult(h2);
            rt2.wake(h2, "again");
            settles.add(settle(f3));   // budget refusal -> incomplete + maxTokens

            // (3) a `closed` settle, driven EXPLICITLY on a handle that never ran — waiting on a
            // handle that HAS run returns its settled lastResult, not a fresh closed one.
            AgentRuntime rt3 = new AgentRuntime(opts(model, registry()));
            Handle h3 = rt3.spawn(rt3.root, "worker").handle();
            rt3.close(h3);
            TaskResult closed = rt3.waitOn(h3, 0L);
            assertEquals(TaskResult.STATUS_CLOSED, closed.status(), "the closed branch was driven");
            settles.add(closed);

            // (4) a `timeout` settle. Deterministic WITHOUT a clock and without a real-time
            // race: the handle is idle and was never woken, so NOTHING can ever complete the
            // waiter — the deadline is the only possible outcome. (A short deadline racing a
            // real response is the shape that gives an intermittent green meaning nothing.)
            AgentRuntime rt4 = new AgentRuntime(opts(model, registry()));
            Handle h4 = rt4.spawn(rt4.root, "worker").handle();
            TaskResult timedOut = rt4.waitOn(h4, 10L);
            assertEquals(TaskResult.STATUS_TIMEOUT, timedOut.status(), "the timeout branch was driven");
            settles.add(timedOut);

            boolean sawLimitStop = false;
            for (TaskResult r : settles) {
                assertTrue(TaskResult.STATUSES.contains(r.status()),
                        "status is a member of the seven-value vocabulary: " + r.status());
                boolean isLimitStop = TaskResult.STATUS_INCOMPLETE.equals(r.status())
                        || TaskResult.STATUS_TIMEOUT.equals(r.status());
                if (isLimitStop) {
                    sawLimitStop = true;
                    assertNotNull(r.limit(), "a limit stop MUST name its limit: " + r.text());
                    assertTrue(TaskResult.LIMITS.contains(r.limit()),
                            "…and it must be one of the canonical nine, not an internal name: " + r.limit());
                } else {
                    assertNull(r.limit(), "a non-limit stop leaves `limit` empty: "
                            + r.status() + " / " + r.limit());
                }
            }
            assertTrue(sawLimitStop, "the invariant was exercised against at least one limit stop");
        }
    }

    /**
     * A14/A21: the internal pool names java's budget walk uses are mapped onto the canonical
     * spelling at the ONE chokepoint (TaskResult's compact constructor), so none can leak.
     */
    @Test
    void a14_internalPoolNamesNeverReachThePublicLimitField() {
        for (String internal : List.of("tokens", "toolCalls", "wallMs", "maxTurns", "completion", "timeout")) {
            TaskResult r = new TaskResult("x", true, TaskResult.STATUS_INCOMPLETE, null, null,
                    0, 0, 0L, internal);
            assertTrue(TaskResult.LIMITS.contains(r.limit()),
                    "internal \"" + internal + "\" mapped to a canonical value, got " + r.limit());
        }
        assertEquals("maxTokens", new TaskResult("x", true, TaskResult.STATUS_INCOMPLETE, null, null,
                0, 0, 0L, "tokens").limit());
        assertEquals("maxWallMs", new TaskResult("x", true, TaskResult.STATUS_TIMEOUT, null, null,
                0, 0, 0L, "wallMs").limit());
    }

    /**
     * A21 — the STRUCTURAL half: a NON-limit status that FORWARDS a limit (the latent shape
     * csharp found twice and golang once) cannot produce a contradicting pair, because the
     * chokepoint empties it. This is what a per-site fix plus a test does not give you.
     */
    @Test
    void a21_aNonLimitStatusCannotCarryALimitEvenIfASiteForwardsOne() {
        for (String status : List.of(TaskResult.STATUS_DONE, TaskResult.STATUS_PENDING,
                TaskResult.STATUS_CLOSED, TaskResult.STATUS_ERROR, TaskResult.STATUS_INTERRUPTED)) {
            TaskResult r = new TaskResult("x", false, status, null, null, 0, 0, 0L, "maxTurns");
            assertNull(r.limit(), status + " must never carry a limit, even when forwarded one");
        }
    }

    @Test
    void theSevenValueStatusVocabularyIsNamed() {
        assertEquals(List.of("done", "pending", "incomplete", "interrupted", "closed", "timeout", "error"),
                TaskResult.STATUSES);
    }
}
