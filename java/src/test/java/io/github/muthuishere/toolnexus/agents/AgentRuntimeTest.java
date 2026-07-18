package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §7D conformance — the runtime substrate against the shared {@code examples/subagent-*}
 * fixtures (S1–S9, 36 checks), plus the runtime-obligation checks the spike deferred
 * (runtime-wide conversation store, turn-gate release on acquirer death).
 *
 * <p>Check names match the cross-language fixture expectations; the mock LLM implements the
 * fixtures' {@code mockLLM} scripts (openai style, 40 tokens/call).
 */
class AgentRuntimeTest {

    private static MockLlm mock;

    @BeforeAll
    static void start() { mock = new MockLlm(); }

    @AfterAll
    static void stop() { mock.close(); }

    // ---------- shared fixture loading ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture(String name) {
        for (Path p : List.of(
                Path.of("..", "examples", name, "fixture.json"),
                Path.of("examples", name, "fixture.json"))) {
            if (Files.exists(p)) {
                try {
                    return Json.toMap(Files.readString(p));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new IllegalStateException("shared fixture not found: " + name);
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

    // ---------- registry (the fixtures' shared agent definitions) ----------

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
        assertTrue(cond, name + (detail.isEmpty() ? "" : " — " + detail));
    }

    private static void check(String name, boolean cond) { check(name, cond, ""); }

    private static void awaitUntil(Supplier<Boolean> cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.get() && System.currentTimeMillis() < end) {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private RuntimeOptions opts() {
        return new RuntimeOptions().baseUrl(mock.base).apiKey("test").registry(registry());
    }

    // S1+S2 — examples/subagent-fanout: fan-out, isolation, parallelism, usage roll-up ----------
    @Test
    @SuppressWarnings("unchecked")
    void s1s2_taskFanOut_contextIsolation_parallel_usageRollup() {
        Map<String, Object> fx = fixture("subagent-fanout");
        Map<String, Object> expect = (Map<String, Object>) fx.get("expect");
        AgentRuntime rt = new AgentRuntime(opts());
        Handle coord = rt.spawn(rt.root, "coordinator").handle();
        var fut = rt.futureResult(coord);
        rt.wake(coord, "answer using two lookups");
        TaskResult r = fut.join();
        check("parent reaches done", "done".equals(r.status()), r.text());
        check("parent got BOTH child answers",
                r.text().contains("found:data(x)") && r.text().split("found:", -1).length == 3, r.text());
        check("parent ran 2 turns only (children's turns not in parent)",
                r.turns() == ((Number) expect.get("parentTurns")).intValue(), "turns=" + r.turns());
        List<String> childIds = (List<String>) expect.get("childIds");
        check("two children spawned with deterministic ids",
                childIds.stream().allMatch(id -> rt.traceHas(id + ":")), String.valueOf(childIds));
        check("children ran CONCURRENTLY (observed >=2 turns in flight)",
                rt.maxObservedConcurrentTurns() >= 2, "max=" + rt.maxObservedConcurrentTurns());
        check("usage rolls up to parent (the ledger)",
                coord.usageTotal == ((Number) expect.get("parentUsageTotal")).longValue(),
                "usage=" + coord.usageTotal);
        check("children auto-closed after task",
                rt.list().stream().allMatch(h -> h.id().equals(coord.id) || h.state() == Handle.State.CLOSED));
    }

    // S3 — examples/subagent-escalation: suspension escalated INLINE, nearest interpreter ------
    @Test
    void s3_childSuspends_parentWaitForAnswers_nearestInterpreter() {
        Map<String, AgentDef> reg = registry();
        reg.get("approverParent").waitFor = req -> new Answer(req.id(), true);
        AgentRuntime rt = new AgentRuntime(opts().registry(reg));
        Handle p = rt.spawn(rt.root, "approverParent").handle();
        var fut = rt.futureResult(p);
        rt.wake(p, "do the secret thing");
        TaskResult r = fut.join();
        check("run completed (no durable pending)", "done".equals(r.status()), r.status());
        check("child's approval flowed through parent authority",
                r.text().contains("asker-done: secret-token"), r.text());
        check("trace shows suspended->running round-trip",
                rt.traceHas("running→suspended") && rt.traceHas("suspended→running"));
        check("escalation chose an ANCESTOR (not self)",
                rt.traceHas("escalate → root/approverParent.1 answers"));
    }

    // S4 — examples/subagent-durable-resume: durable pending at root + resume cascade ----------
    @Test
    void s4_noInterpreterAnywhere_durablePending_resumeCascadesFromCheckpoint() {
        AgentRuntime rt = new AgentRuntime(opts());
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

    // S5 — unsolicited rail: coalesced drain + provenance + timer dedupe -----------------------
    @Test
    void s5_unsolicitedRail_postsCoalesceIntoOneTurn_provenance_ticksDedupe() {
        AgentRuntime rt = new AgentRuntime(opts());
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

    // S6 — backpressure: inbox gate, concurrency gate, global turn gate ------------------------
    @Test
    void s6_backpressure_loudInboxReject_perParentConcurrencyQueue_globalTurnGate() {
        AgentRuntime rt = new AgentRuntime(opts().inboxCap(2));
        Handle peer = rt.spawn(rt.root, "peer").handle();
        rt.post(peer, new InboxItem("a", "peer", "1"));
        rt.post(peer, new InboxItem("a", "peer", "2"));
        AgentRuntime.Ack third = rt.post(peer, new InboxItem("a", "peer", "3"));
        check("inbox gate: third post REJECTED loudly to sender",
                !third.ok() && third.error().contains("inbox full"));

        Map<String, AgentDef> reg2 = registry();
        reg2.get("coordinator").budget = new Budget().maxConcurrent(1);
        AgentRuntime rt2 = new AgentRuntime(opts().registry(reg2));
        Handle c2 = rt2.spawn(rt2.root, "coordinator").handle();
        var fut2 = rt2.futureResult(c2);
        rt2.wake(c2, "go");
        TaskResult r2 = fut2.join();
        check("concurrency gate: 2nd child QUEUED then DEQUEUED, still completes",
                "done".equals(r2.status()) && rt2.traceHas("wake QUEUED") && rt2.traceHas("DEQUEUED wake"),
                r2.text());
        check("concurrency gate: never >1 child turn in flight... but parent turn allowed",
                rt2.maxObservedConcurrentTurns() <= 2);

        AgentRuntime rt3 = new AgentRuntime(opts().maxConcurrentTurns(1));
        Handle c3 = rt3.spawn(rt3.root, "coordinator").handle();
        var fut3 = rt3.futureResult(c3);
        rt3.wake(c3, "go");
        fut3.join();
        check("global turn gate: with cap 1, max observed concurrent turns = 1",
                rt3.maxObservedConcurrentTurns() == 1, "max=" + rt3.maxObservedConcurrentTurns());
    }

    // S7 — examples/subagent-budgets: carve, exhaustion=incomplete, maxTurns loud --------------
    @Test
    void s7_budgets_hierarchicalCarve_exhaustionIncomplete_maxTurnsLoud() {
        AgentRuntime rt = new AgentRuntime(opts());
        Handle c = rt.spawn(rt.root, "coordinator", new Budget().maxTokens(100).maxChildren(2)).handle();
        Handle kid = rt.spawn(c, "explore", new Budget().maxTokens(500)).handle();
        check("carve: child asked 500, parent pool 100 -> effective 100",
                kid.poolTokens == 100, String.valueOf(kid.poolTokens));
        Handle kid2 = rt.spawn(c, "explore").handle();
        AgentRuntime.Spawn kid3 = rt.spawn(c, "explore");
        check("maxChildren enforced at spawn",
                kid3.error() != null && kid3.error().contains("maxChildren"));
        var futKid = rt.futureResult(kid);
        rt.wake(kid, "go");
        futKid.join(); // burns 80 tokens (2 turns x 40) from both kid and parent pools
        check("roll-up drains the PARENT pool too", c.poolTokens == 20, String.valueOf(c.poolTokens));
        var futKid2 = rt.futureResult(kid2);
        rt.wake(kid2, "go");
        TaskResult r2 = futKid2.join();
        check("pool nearly gone: sibling still ran (20 left > 0)", "done".equals(r2.status()));
        TaskResult r3 = rt.runTurn(kid2, "again");
        check("pool exhausted -> status=incomplete, partial work preserved, NO crash",
                "incomplete".equals(r3.status()) && r3.text().contains("budget exhausted"), r3.status());

        Map<String, AgentDef> reg4 = registry();
        reg4.get("looper").budget = new Budget().maxTurns(3);
        AgentRuntime rt4 = new AgentRuntime(opts().registry(reg4));
        Handle lp = rt4.spawn(rt4.root, "looper").handle();
        var futLp = rt4.futureResult(lp);
        rt4.wake(lp, "loop forever");
        TaskResult rl = futLp.join();
        check("maxTurns cap is LOUD: status=incomplete (not silent done)",
                "incomplete".equals(rl.status()), rl.status());
    }

    // S8 — examples/subagent-lifecycle: interrupt aborts the TURN, not the agent ---------------
    @Test
    void s8_interrupt_turnAbortToIdle_inboxIntact_notAKill() {
        mock.slowGate.set(new CountDownLatch(1));
        try {
            AgentRuntime rt = new AgentRuntime(opts());
            Handle s = rt.spawn(rt.root, "slow").handle();
            rt.post(s, new InboxItem("root", "peer", "for later"));
            var w = rt.futureResult(s);
            rt.wake(s, "work slowly");
            awaitUntil(() -> s.state == Handle.State.RUNNING, 2000);
            check("agent is mid-turn (running)", s.state == Handle.State.RUNNING);
            rt.interrupt(s);
            TaskResult r = w.join();
            check("turn aborted -> isError result, status=interrupted",
                    r.isError() && "interrupted".equals(r.status()), r.status());
            awaitUntil(() -> s.state == Handle.State.IDLE, 2000);
            check("agent is IDLE (alive), not closed", s.state == Handle.State.IDLE, s.state.label());
            check("inbox survived the interrupt", s.inbox.size() == 1);
        } finally {
            mock.slowGate.get().countDown();
            mock.slowGate.set(null);
        }
    }

    // S9 — examples/subagent-lifecycle: close cascade leaf-first; post-after-close rejected ----
    @Test
    void s9_stopAll_closeRootCascadesLeafFirst_closedHandlesRejectPosts() {
        AgentRuntime rt = new AgentRuntime(opts());
        Handle c = rt.spawn(rt.root, "coordinator").handle();
        Handle k1 = rt.spawn(c, "peer").handle();
        Handle k2 = rt.spawn(k1, "peer").handle(); // grandchild
        List<String> closeOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        for (Handle h : List.of(c, k1, k2)) h.def.onClose = (hh, reason) -> closeOrder.add(hh.id);
        rt.close(rt.root);
        check("leaf-first order (grandchild -> child -> parent)",
                closeOrder.size() == 3 && closeOrder.get(0).equals(k2.id)
                        && closeOrder.get(1).equals(k1.id) && closeOrder.get(2).equals(c.id),
                String.join(" -> ", closeOrder));
        AgentRuntime.Ack p = rt.post(k1, new InboxItem("x", "peer", "late"));
        check("post after close = loud isError", !p.ok() && p.error().contains("closed"));
    }

    // ---------- runtime obligations beyond the spike (SPEC §7D "Lifecycle & runtime") ---------

    // ONE runtime-wide ConversationStore: conversation id = handle id, transcripts survive turns.
    @Test
    void runtimeWideStore_transcriptSurvivesTurns() {
        AgentRuntime rt = new AgentRuntime(opts());
        Handle peer = rt.spawn(rt.root, "peer").handle();
        TaskResult r1 = rt.runTurn(peer, "1. first thing");
        TaskResult r2 = rt.runTurn(peer, "1. second thing");
        assertTrue(!r1.isError() && !r2.isError(), "both turns complete");
        List<Object> transcript = rt.conversationStore().get(peer.id);
        assertTrue(transcript != null, "the runtime store holds the handle's conversation");
        long userTurns = transcript.stream()
                .filter(m -> m instanceof Map<?, ?> mm && "user".equals(mm.get("role")))
                .count();
        assertTrue(userTurns >= 2, "the stored transcript contains BOTH turns' user messages, got " + userTurns);
    }

    // Turn gate releases on acquirer death: a hard-aborted Run cannot strand the slot.
    @Test
    void turnGate_slotSurvivesKilledRun() {
        mock.slowGate.set(new CountDownLatch(1));
        try {
            AgentRuntime rt = new AgentRuntime(opts().maxConcurrentTurns(1));
            Handle s = rt.spawn(rt.root, "slow").handle();
            var w = rt.futureResult(s);
            rt.wake(s, "hold the gate");
            awaitUntil(() -> s.state == Handle.State.RUNNING, 2000);
            rt.interrupt(s); // kills the Run while it HOLDS the single gate slot
            w.join();
            mock.slowGate.get().countDown(); // release any in-flight mock response
            Handle peer = rt.spawn(rt.root, "peer").handle();
            var fut = rt.futureResult(peer);
            rt.wake(peer, "1. after the abort");
            TaskResult r = fut.join();
            assertTrue("done".equals(r.status()),
                    "a queued acquirer proceeds after the abort (slot released on death), got " + r.status());
        } finally {
            if (mock.slowGate.get() != null) mock.slowGate.get().countDown();
            mock.slowGate.set(null);
        }
    }

    // Name-sorted registry iteration wherever prose is composed (spawn error / task description).
    @Test
    void registryProse_isNameSorted() {
        // The registry map inserts coordinator before asker; composed prose must list the agents
        // in name order regardless of insertion order.
        AgentRuntime rt = new AgentRuntime(opts());
        Handle h = rt.spawn(rt.root, "coordinator").handle();
        AgentRuntime.Spawn bad = rt.spawn(h, "nope");
        String known = bad.error();
        int a = known.indexOf("approverParent");
        int k = known.indexOf("asker");
        int co = known.indexOf("coordinator");
        int e = known.indexOf("explore");
        assertTrue(a >= 0 && k > a && co > k && e > co, "registry prose sorted by name: " + known);
    }
}
