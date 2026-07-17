package io.github.muthuishere.toolnexus.spike;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE demo — acceptance scenarios S1–S9 for the agent-runtime substrate v2.
 * Java port of {@code js/spike/demo.ts}: same scenarios, same check names, same mock-LLM
 * behavior. Zero network beyond localhost, zero cost: the LLM is a scripted
 * {@code com.sun.net.httpserver.HttpServer} keyed by the request body's {@code model}
 * (the established Java mock pattern; the JS spike keys its mock {@code fetch} identically).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpikeDemoTest {

    private static HttpServer server;
    private static String base;
    /** S8's slow model blocks on this latch until the scenario releases it. */
    private static final AtomicReference<CountDownLatch> slowGate = new AtomicReference<>();

    // ---------- scripted mock LLM (openai style, keyed by body.model) ----------

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
            for (Object o : msgs) {
                if (o instanceof Map<?, ?> m && "tool".equals(m.get("role"))) {
                    toolMsgs.add(String.valueOf(m.get("content")));
                }
            }
            Map<String, Object> message;
            switch (model) {
                case "m-coordinator" -> {
                    if (toolMsgs.isEmpty()) {
                        message = calls(
                                call("c1", "task", Map.of("agent", "explore", "prompt", "find A")),
                                call("c2", "task", Map.of("agent", "explore", "prompt", "find B")));
                    } else {
                        message = text("synthesis: " + String.join(" + ", toolMsgs));
                    }
                }
                case "m-explore" -> {
                    if (toolMsgs.isEmpty()) message = calls(call("e1", "lookup", Map.of("q", "x")));
                    else message = text("found:" + toolMsgs.get(0));
                }
                case "m-approver-parent" -> {
                    if (toolMsgs.isEmpty()) {
                        message = calls(call("p1", "task", Map.of("agent", "asker", "prompt", "get the secret")));
                    } else {
                        message = text("parent-final: " + toolMsgs.get(toolMsgs.size() - 1));
                    }
                }
                case "m-asker" -> {
                    boolean approved = toolMsgs.stream().anyMatch(c -> c.contains("secret-token"));
                    if (!approved) message = calls(call("a1", "check_secret", Map.of()));
                    else message = text("asker-done: secret-token");
                }
                case "m-peer" -> {
                    String last = msgs.isEmpty() ? "" :
                            String.valueOf(((Map<String, Object>) msgs.get(msgs.size() - 1)).get("content"));
                    Matcher m = Pattern.compile("^\\d+\\.", Pattern.MULTILINE).matcher(last);
                    int items = 0;
                    while (m.find()) items++;
                    message = text("processed " + items + " items");
                }
                case "m-loop" -> // never finishes: always another tool call → maxTurns/incomplete
                        message = calls(call("l" + toolMsgs.size(), "lookup", Map.of("q", "again")));
                case "m-slow" -> {
                    CountDownLatch gate = slowGate.get();
                    try {
                        if (gate != null) gate.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    message = text("slow-done");
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
        CountDownLatch gate = slowGate.get();
        if (gate != null) gate.countDown();
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

    // ---------- scoped tools ----------

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

    private static Tool checkSecretTool() {
        return new Tool() {
            @Override public String name() { return "check_secret"; }
            @Override public String description() { return "needs human approval"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                if (ctx != null && ctx.answer() != null && ctx.answer().ok()) return ToolResult.ok("secret-token");
                return ToolResult.pending(new Request(null, "approval", "approve secret access?"));
            }
        };
    }

    // ---------- registry ----------

    private static Map<String, AgentDef> registry() {
        Tool lookup = lookupTool();
        Map<String, AgentDef> reg = new LinkedHashMap<>();
        reg.put("coordinator", new AgentDef("coordinator", "splits and delegates", "coordinate", "m-coordinator"));
        reg.put("explore", new AgentDef("explore", "read-only research", "explore", "m-explore").tools(List.of(lookup)));
        reg.put("approverParent", new AgentDef("approverParent", "delegates, holds approval authority", "", "m-approver-parent"));
        reg.put("asker", new AgentDef("asker", "needs approvals", "", "m-asker").tools(List.of(checkSecretTool())));
        reg.put("peer", new AgentDef("peer", "team member", "", "m-peer"));
        reg.put("looper", new AgentDef("looper", "never finishes", "", "m-loop").tools(List.of(lookup)));
        reg.put("slow", new AgentDef("slow", "slow worker", "", "m-slow"));
        return reg;
    }

    // ---------- harness ----------

    private static void check(String name, boolean cond, String detail) {
        System.out.println("  " + (cond ? "✅" : "❌") + " " + name + (cond ? "" : " " + detail));
        assertTrue(cond, name + (detail.isEmpty() ? "" : " — " + detail));
    }

    private static void check(String name, boolean cond) { check(name, cond, ""); }

    private static void section(String s) { System.out.println("\n━━ " + s); }

    private static void awaitUntil(Supplier<Boolean> cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.get() && System.currentTimeMillis() < end) {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ---------- scenarios ----------

    // S1+S2 — fan-out, isolation, parallelism, usage roll-up ------------------
    @Test
    @Order(1)
    void s1s2_taskFanOut_contextIsolation_parallel_usageRollup() {
        section("S1/S2 task fan-out · context isolation · parallel · usage roll-up");
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(registry()));
        Handle coord = rt.spawn(rt.root, "coordinator").handle();
        var fut = rt.futureResult(coord);
        rt.wake(coord, "answer using two lookups");
        TaskResult r = fut.join();
        check("parent reaches done", "done".equals(r.status()), r.text());
        check("parent got BOTH child answers",
                r.text().contains("found:data(x)") && r.text().split("found:", -1).length == 3, r.text());
        check("parent ran 2 turns only (children's turns not in parent)", r.turns() == 2, "turns=" + r.turns());
        check("two children spawned with deterministic ids",
                rt.traceHas("/coordinator.1/explore.1:") && rt.traceHas("/explore.2:"));
        check("children ran CONCURRENTLY (observed ≥2 turns in flight)",
                rt.maxObservedConcurrentTurns() >= 2, "max=" + rt.maxObservedConcurrentTurns());
        // roll-up: coord usage = own 2 turns×40 + children 2×(2 turns×40) = 240
        check("usage rolls up to parent (G3 ledger)", coord.usageTotal == 240, "usage=" + coord.usageTotal);
        check("children auto-closed after task",
                rt.list().stream().allMatch(h -> h.id().equals(coord.id) || h.state() == Handle.State.CLOSED));
    }

    // S3 — suspension escalated INLINE: nearest interpreter wins --------------
    @Test
    @Order(2)
    void s3_childSuspends_parentWaitForAnswers_nearestInterpreter() {
        section("S3 child suspends → parent's waitFor answers (nearest interpreter)");
        Map<String, AgentDef> reg = registry();
        reg.get("approverParent").waitFor = req -> new Answer(req.id(), true);
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(reg));
        Handle p = rt.spawn(rt.root, "approverParent").handle();
        var fut = rt.futureResult(p);
        rt.wake(p, "do the secret thing");
        TaskResult r = fut.join();
        check("run completed (no durable pending)", "done".equals(r.status()), r.status());
        check("child's approval flowed through parent authority",
                r.text().contains("asker-done: secret-token"), r.text());
        check("trace shows suspended→running round-trip",
                rt.traceHas("running→suspended") && rt.traceHas("suspended→running"));
        check("escalation chose an ANCESTOR (not self)",
                rt.traceHas("escalate → root/approverParent.1 answers"));
    }

    // S4 — durable pending at root + resume cascade from checkpoint -----------
    @Test
    @Order(3)
    void s4_noInterpreterAnywhere_durablePending_resumeCascadesFromCheckpoint() {
        section("S4 no interpreter anywhere → durable pending; resume() cascades from checkpoint");
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(registry()));
        Handle p = rt.spawn(rt.root, "approverParent").handle();
        var fut = rt.futureResult(p);
        rt.wake(p, "do the secret thing");
        TaskResult r1 = fut.join();
        check("root run returned status=pending", "pending".equals(r1.status()), r1.status());
        check("Request carries the handle PATH to the leaf",
                r1.pendingPath() != null && String.join("/", r1.pendingPath()).contains("approverParent.1"),
                String.valueOf(r1.pendingPath()));
        check("both levels parked (suspended), zero tokens burning",
                rt.list().stream().allMatch(h -> h.state() == Handle.State.SUSPENDED));
        long before = rt.list().stream().filter(h -> h.id().contains("asker")).findFirst().orElseThrow().tokens();
        rt.resume(new Answer(r1.pending().id(), true));
        check("leaf resumed AT checkpoint (prior turns preserved)",
                rt.traceHas("resume with Answer(ok=true) at checkpoint"));
        check("parent cascade REATTACHED to the finished child (no re-execution)",
                rt.traceHas("task replay → REATTACH"));
        check("parent reached done after cascade", p.state == Handle.State.IDLE, p.state.label());
        check("child did not restart from scratch (usage grew, not reset)",
                rt.list().stream().filter(h -> h.id().contains("asker")).findFirst().orElseThrow().tokens() > before);
    }

    // S5 — team peer: coalesced drain + provenance + timer dedupe -------------
    @Test
    @Order(4)
    void s5_unsolicitedRail_postsCoalesceIntoOneTurn_provenance_ticksDedupe() {
        section("S5 unsolicited rail: posts coalesce into ONE turn, provenance marked, ticks dedupe");
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(registry()));
        Handle peer = rt.spawn(rt.root, "peer").handle();
        rt.post(peer, new InboxItem("root/coordinator.1", "peer", "update 1"));
        rt.post(peer, new InboxItem("external", "external", "webhook payload"));
        rt.post(peer, new InboxItem("clock", "timer", "tick"));
        rt.post(peer, new InboxItem("clock", "timer", "tick"));
        rt.post(peer, new InboxItem("clock", "timer", "tick"));
        var fut = rt.futureResult(peer);
        rt.wake(peer);
        TaskResult r = fut.join();
        check("one wake = ONE turn for all items", r.turns() == 1, "turns=" + r.turns());
        check("2 messages + 3 ticks coalesced to 3 items", "processed 3 items".equals(r.text()), r.text());
    }

    // S6 — backpressure: inbox gate, concurrency gate, global turn gate -------
    @Test
    @Order(5)
    void s6_backpressure_loudInboxReject_perParentConcurrencyQueue_globalTurnGate() {
        section("S6 backpressure: loud inbox reject · per-parent concurrency queue · global turn gate");
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(registry()).inboxCap(2));
        Handle peer = rt.spawn(rt.root, "peer").handle();
        rt.post(peer, new InboxItem("a", "peer", "1"));
        rt.post(peer, new InboxItem("a", "peer", "2"));
        AgentRuntime.Ack third = rt.post(peer, new InboxItem("a", "peer", "3"));
        check("inbox gate: third post REJECTED loudly to sender",
                !third.ok() && third.error().contains("inbox full"));

        Map<String, AgentDef> reg2 = registry();
        reg2.get("coordinator").budget = new Budget().maxConcurrent(1);
        AgentRuntime rt2 = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(reg2));
        Handle c2 = rt2.spawn(rt2.root, "coordinator").handle();
        var fut2 = rt2.futureResult(c2);
        rt2.wake(c2, "go");
        TaskResult r2 = fut2.join();
        check("concurrency gate: 2nd child QUEUED then DEQUEUED, still completes",
                "done".equals(r2.status()) && rt2.traceHas("wake QUEUED") && rt2.traceHas("DEQUEUED wake"),
                r2.text());
        check("concurrency gate: never >1 child turn in flight… but parent turn allowed",
                rt2.maxObservedConcurrentTurns() <= 2);

        AgentRuntime rt3 = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(registry())
                .maxConcurrentTurns(1));
        Handle c3 = rt3.spawn(rt3.root, "coordinator").handle();
        var fut3 = rt3.futureResult(c3);
        rt3.wake(c3, "go");
        fut3.join();
        check("global turn gate: with cap 1, max observed concurrent turns = 1",
                rt3.maxObservedConcurrentTurns() == 1, "max=" + rt3.maxObservedConcurrentTurns());
    }

    // S7 — budgets: carve, exhaustion=incomplete, maxTurns visible ------------
    @Test
    @Order(6)
    void s7_budgets_hierarchicalCarve_exhaustionIncomplete_maxTurnsLoud() {
        section("S7 budgets: hierarchical carve · exhaustion = incomplete (never crash) · maxTurns loud");
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(registry()));
        Handle c = rt.spawn(rt.root, "coordinator", new Budget().maxTokens(100).maxChildren(2)).handle();
        Handle kid = rt.spawn(c, "explore", new Budget().maxTokens(500)).handle();
        check("carve: child asked 500, parent pool 100 → effective 100",
                kid.poolTokens == 100, String.valueOf(kid.poolTokens));
        Handle kid2 = rt.spawn(c, "explore").handle();
        AgentRuntime.Spawn kid3 = rt.spawn(c, "explore");
        check("maxChildren enforced at spawn",
                kid3.error() != null && kid3.error().contains("maxChildren"));
        var futKid = rt.futureResult(kid);
        rt.wake(kid, "go");
        futKid.join(); // burns 80 tokens (2 turns×40) from both kid and parent pools
        check("roll-up drains the PARENT pool too", c.poolTokens == 20, String.valueOf(c.poolTokens));
        var futKid2 = rt.futureResult(kid2);
        rt.wake(kid2, "go");
        TaskResult r2 = futKid2.join();
        check("pool nearly gone: sibling still ran (20 left > 0)", "done".equals(r2.status()));
        TaskResult r3 = rt.runTurn(kid2, "again");
        check("pool exhausted → status=incomplete, partial work preserved, NO crash",
                "incomplete".equals(r3.status()) && r3.text().contains("budget exhausted"), r3.status());

        Map<String, AgentDef> reg4 = registry();
        reg4.get("looper").budget = new Budget().maxTurns(3);
        AgentRuntime rt4 = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(reg4));
        Handle lp = rt4.spawn(rt4.root, "looper").handle();
        var futLp = rt4.futureResult(lp);
        rt4.wake(lp, "loop forever");
        TaskResult rl = futLp.join();
        check("maxTurns cap is LOUD: status=incomplete (not silent done)",
                "incomplete".equals(rl.status()), rl.status());
    }

    // S8 — interrupt: aborts the TURN, not the agent --------------------------
    @Test
    @Order(7)
    void s8_interrupt_turnAbortToIdle_inboxIntact_notAKill() {
        section("S8 interrupt = turn-abort → idle (inbox intact); NOT a kill");
        slowGate.set(new CountDownLatch(1));
        try {
            AgentRuntime rt = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(registry()));
            Handle s = rt.spawn(rt.root, "slow").handle();
            rt.post(s, new InboxItem("root", "peer", "for later"));
            var w = rt.futureResult(s);
            rt.wake(s, "work slowly");
            awaitUntil(() -> s.state == Handle.State.RUNNING, 2000);
            check("agent is mid-turn (running)", s.state == Handle.State.RUNNING);
            rt.interrupt(s);
            TaskResult r = w.join();
            check("turn aborted → isError result, status=interrupted",
                    r.isError() && "interrupted".equals(r.status()), r.status());
            awaitUntil(() -> s.state == Handle.State.IDLE, 2000);
            check("agent is IDLE (alive), not closed", s.state == Handle.State.IDLE, s.state.label());
            check("inbox survived the interrupt", s.inbox.size() == 1);
        } finally {
            slowGate.get().countDown();
        }
    }

    // S9 — close cascade: leaf-first; post-after-close rejected ---------------
    @Test
    @Order(8)
    void s9_stopAll_closeRootCascadesLeafFirst_closedHandlesRejectPosts() {
        section("S9 stop-all: close(root) cascades leaf-first; closed handles reject posts");
        Map<String, AgentDef> reg = registry();
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions().baseUrl(base).registry(reg));
        Handle c = rt.spawn(rt.root, "coordinator").handle();
        Handle k1 = rt.spawn(c, "peer").handle();
        Handle k2 = rt.spawn(k1, "peer").handle(); // grandchild
        List<String> closeOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        for (Handle h : List.of(c, k1, k2)) h.def.onClose = (hh, reason) -> closeOrder.add(hh.id);
        rt.close(rt.root);
        check("leaf-first order (grandchild → child → parent)",
                closeOrder.size() == 3 && closeOrder.get(0).equals(k2.id)
                        && closeOrder.get(1).equals(k1.id) && closeOrder.get(2).equals(c.id),
                String.join(" → ", closeOrder));
        AgentRuntime.Ack p = rt.post(k1, new InboxItem("x", "peer", "late"));
        check("post after close = loud isError", !p.ok() && p.error().contains("closed"));
    }
}
