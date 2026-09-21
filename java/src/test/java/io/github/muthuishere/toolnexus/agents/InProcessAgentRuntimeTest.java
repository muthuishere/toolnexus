package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0024 (docs/adr/0024-the-in-process-seam-stops-at-the-top-level-client.md) + its spike
 * (spikes/inprocess-subagent/SPIKE.md), Java port. Mirrors {@code golang/agents/inprocess_test.go}.
 *
 * <p>Proves: (1) ONE {@code generate} function serves BOTH the top-level
 * {@link InProcess#createClient} AND a sub-agent runtime via the new
 * {@code RuntimeOptions.inProcess} option, with zero duplicated adapter code — both build the
 * SAME public {@link InProcess.GenerateBackedHttpClient}; (2) construction-time validation, never
 * precedence, when {@code inProcess} is combined with a wire-shaped option; (3) the global turn
 * gate ({@code GatedHttpClient}) still applies on the new path, with a negative control proving
 * the detector is real.
 */
class InProcessAgentRuntimeTest {

    /**
     * The fake model shared by BOTH the top-level client and the sub-agent runtime — the
     * reporter's actual case. It never touches HTTP: it is handed the assembled
     * {@link InProcess.Request} and returns one {@link InProcess.Response}. It instruments
     * concurrency so the gate test can assert on real overlap, not just a trust-me counter.
     */
    private static final class ScriptedModel {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxSeen = new AtomicInteger();
        final AtomicInteger overlaps = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final long holdMs;

        ScriptedModel() { this(0); }
        ScriptedModel(long holdMs) { this.holdMs = holdMs; }

        InProcess.Response generate(InProcess.Request req) {
            int n = inFlight.incrementAndGet();
            try {
                calls.incrementAndGet();
                maxSeen.getAndUpdate(m -> Math.max(m, n));
                if (n > 1) overlaps.incrementAndGet();
                if (holdMs > 0) {
                    try {
                        Thread.sleep(holdMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return InProcess.Response.content("ok model=" + req.model + " turn-done");
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    private static Map<String, AgentDef> workerRegistry() {
        Map<String, AgentDef> reg = new LinkedHashMap<>();
        reg.put("worker", new AgentDef("worker", "a scripted worker", "", "spike-model"));
        return reg;
    }

    // ---------- (1) one generate, shared by top-level client + sub-agent runtime --------------

    @Test
    void sharedGenerate_topLevelClientAndSubAgentRuntime_noCopiedAdapter() throws Exception {
        ScriptedModel m = new ScriptedModel();

        // Top-level client, driven by m::generate directly.
        LlmClient client = InProcess.createClient(new InProcess.Options()
                .model("spike-model")
                .generate(m::generate));
        try (Toolkit toolkit = Toolkit.create(new Toolkit.Options())) {
            LlmClient.RunResult topRes = client.run("hello", toolkit);
            assertEquals("done", topRes.status, topRes.text);
        }

        // Sub-agent runtime, driven by the SAME m::generate via RuntimeOptions.inProcess.
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions()
                .inProcess(m::generate)
                .registry(workerRegistry()));
        Handle h = rt.spawn(rt.root, "worker").handle();
        CompletableFuture<TaskResult> fut = rt.futureResult(h);
        rt.wake(h, "do the thing");
        TaskResult subRes = fut.get(10, TimeUnit.SECONDS);
        assertEquals("done", subRes.status(), subRes.text());

        assertEquals(2, m.calls.get(), "one top-level ask, one sub-agent turn — the same generate function");
    }

    // ---------- (2) construction-time validation — never precedence ---------------------------

    @Test
    void inProcessAndHttpClientConflict_throwsAtConstruction() {
        ScriptedModel m = new ScriptedModel();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new AgentRuntime(new RuntimeOptions()
                        .httpClient(new InProcess.GenerateBackedHttpClient(m::generate))
                        .inProcess(m::generate)
                        .registry(workerRegistry())));
        assertTrue(e.getMessage().contains("httpClient") && e.getMessage().contains("inProcess"), e.getMessage());
    }

    @Test
    void inProcessAndBaseUrlConflict_throwsAtConstruction() {
        ScriptedModel m = new ScriptedModel();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new AgentRuntime(new RuntimeOptions()
                        .baseUrl("http://example.invalid")
                        .inProcess(m::generate)
                        .registry(workerRegistry())));
        assertTrue(e.getMessage().contains("baseUrl") && e.getMessage().contains("inProcess"), e.getMessage());
    }

    // ---------- (3) the global turn gate still applies, with a negative control ---------------

    /**
     * MaxConcurrentTurns=1, five workers woken concurrently on the NEW
     * {@code RuntimeOptions.inProcess} path. If the global turn gate (GatedHttpClient,
     * AgentRuntime.java) stopped wrapping the transport built from {@code inProcess}, the
     * ScriptedModel would observe &gt;1 in flight and this test fails.
     */
    @Test
    void gateHoldsAtConcurrencyOne() throws Exception {
        ScriptedModel m = new ScriptedModel(15);
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions()
                .inProcess(m::generate)
                .maxConcurrentTurns(1)
                .registry(workerRegistry()));

        int n = 5;
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int idx = i;
            Thread.startVirtualThread(() -> {
                try {
                    Handle h = rt.spawn(rt.root, "worker").handle();
                    CompletableFuture<TaskResult> fut = rt.futureResult(h);
                    rt.wake(h, "job " + idx);
                    TaskResult r = fut.get(10, TimeUnit.SECONDS);
                    assertEquals("done", r.status());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(20, TimeUnit.SECONDS), "workers did not finish in time");

        assertEquals(0, m.overlaps.get(),
                "gate FALSIFIED: " + m.overlaps.get() + " call(s) observed >1 in flight (maxSeen="
                        + m.maxSeen.get() + ") with maxConcurrentTurns=1 — the global turn gate did "
                        + "not wrap the RuntimeOptions.inProcess transport");
        assertEquals(1, rt.maxObservedConcurrentTurns());
    }

    /**
     * The negative control: same 5 concurrent workers, but maxConcurrentTurns=5 (effectively no
     * gate). Proves the assertion above is a real detector, not a tautology — with room to run
     * concurrently, ScriptedModel DOES observe overlap.
     */
    @Test
    void gateControl_wouldCatchABypass() throws Exception {
        ScriptedModel m = new ScriptedModel(15);
        AgentRuntime rt = new AgentRuntime(new RuntimeOptions()
                .inProcess(m::generate)
                .maxConcurrentTurns(5)
                .registry(workerRegistry()));

        int n = 5;
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int idx = i;
            Thread.startVirtualThread(() -> {
                try {
                    Handle h = rt.spawn(rt.root, "worker").handle();
                    CompletableFuture<TaskResult> fut = rt.futureResult(h);
                    rt.wake(h, "job " + idx);
                    fut.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(20, TimeUnit.SECONDS), "workers did not finish in time");

        assertTrue(m.overlaps.get() > 0,
                "control INVALID: expected overlap with maxConcurrentTurns=5 and a 15ms hold, got 0 "
                        + "overlaps (maxSeen=" + m.maxSeen.get() + ") — the detector proves nothing");
    }
}
