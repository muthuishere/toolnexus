package io.github.muthuishere.toolnexus.agents;

import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static io.github.muthuishere.toolnexus.agents.Agents.HEARTBEAT_OK;
import static io.github.muthuishere.toolnexus.agents.Agents.agentFromDir;
import static io.github.muthuishere.toolnexus.agents.Agents.composeSoul;
import static io.github.muthuishere.toolnexus.agents.Agents.memoryTool;
import static io.github.muthuishere.toolnexus.agents.Agents.startAgent;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §7E "Agent home — personas" conformance (H1–H7, 15 checks), against the shared fixture
 * {@code examples/persona-agent/fixture.json} (behavior reference: {@code js/spike/home.ts} +
 * {@code home-demo.ts}). The directory IS the agent — ordered bootstrap files compose the soul
 * (frozen snapshot); the {@code memory} builtin persists to DISK (loads next session, never
 * mutates the live prompt); the heartbeat wakes on the runtime's injectable clock and stays
 * silent on {@code HEARTBEAT_OK}. Reuses {@link MockLlm} (the fixture's scripted turns).
 */
class AgentHomeTest {

    private static MockLlm mock;

    @BeforeAll
    static void start() { mock = new MockLlm(); }

    @AfterAll
    static void stop() { mock.close(); }

    private static RuntimeOptions rtOpts() {
        return new RuntimeOptions().baseUrl(mock.base).apiKey("test");
    }

    private static void check(String name, boolean cond, String detail) {
        assertTrue(cond, name + (detail == null || detail.isEmpty() ? "" : " — " + detail));
    }

    private static void check(String name, boolean cond) { check(name, cond, ""); }

    private static Path mkdir(Map<String, String> files) throws IOException {
        Path dir = Files.createTempDirectory("home-");
        for (Map.Entry<String, String> e : files.entrySet()) {
            Files.writeString(dir.resolve(e.getKey()), e.getValue());
        }
        return dir;
    }

    /** A real bootstrap dir straight from the shared fixture ({@code examples/persona-agent}). */
    private static Path fixtureDir() throws IOException {
        return mkdir(Map.of(
                "SOUL.md", "You are Ava, a calm support assistant.",
                "USER.md", "The user is Muthu; timezone IST.",
                "HEARTBEAT.md", "If it is time, remind about the 3pm sync.",
                "MEMORY.md", "- Onboarded 2026-07."));
    }

    private static String read(Path dir, String file) throws IOException {
        return Files.readString(dir.resolve(file));
    }

    private static List<String> toolNames(Agents.Agent a) {
        if (a.spec.tools == null) return List.of();
        return a.spec.tools.stream().map(Tool::name).toList();
    }

    // ---- H1: the directory IS the agent — ordered bootstrap files → soul ----
    @Test
    void h1_orderedBootstrapDiscoveryAndInjection() throws Exception {
        // Fixture bootstrapDir: SOUL/USER/HEARTBEAT/MEMORY (AGENTS/IDENTITY/TOOLS absent).
        Path dir = fixtureDir();
        Agents.ComposedSoul composed = composeSoul(dir);
        check("discovers only present files, in canonical order",
                String.join(",", composed.found()).equals("SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md"),
                String.join(",", composed.found()));
        String soul = composed.soul();
        check("injected as ## sections, SOUL before USER before MEMORY",
                soul.indexOf("## SOUL.md") < soul.indexOf("## USER.md")
                        && soul.indexOf("## USER.md") < soul.indexOf("## MEMORY.md"));
        Agents.Agent ava = agentFromDir(dir, new Agents.AgentSpec().model("m-echo-soul"));
        TaskResult r = ava.run(rtOpts(), "who are you?");
        check("the composed soul reaches the child system prompt",
                "sections:[SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md]".equals(r.text()), r.text());
    }

    // ---- H2: per-file 2 MB cap ---------------------------------------------
    @Test
    void h2_perFileByteCap() throws Exception {
        Path dir = mkdir(Map.of("SOUL.md", "x".repeat(3 * 1024 * 1024)));
        String before = read(dir, "SOUL.md");
        String soul = composeSoul(dir).soul();
        check("a >2 MB file is truncated with a notice",
                soul.contains("[truncated: exceeds 2 MB bootstrap cap]") && soul.length() < 2.1 * 1024 * 1024);
        check("the on-disk file is untouched by truncation", read(dir, "SOUL.md").equals(before));
    }

    // ---- H3: the memory tool — add/replace/remove persist to disk ----------
    @Test
    void h3_memoryToolWritesToDisk() throws Exception {
        Path dir = mkdir(Map.of("MEMORY.md", "- Likes green tea."));
        Tool tool = memoryTool(dir);
        ToolContext ctx = new ToolContext();

        tool.execute(Map.of("action", "add", "text", "Likes hiking"), ctx);
        check("add appends an entry", read(dir, "MEMORY.md").contains("- Likes hiking"));

        tool.execute(Map.of("action", "replace", "text", "green tea", "with", "oolong"), ctx);
        check("replace swaps a substring", read(dir, "MEMORY.md").contains("oolong"));

        tool.execute(Map.of("action", "remove", "text", "- Likes hiking\n"), ctx);
        check("remove deletes an entry", !read(dir, "MEMORY.md").contains("hiking"));

        String beforeMiss = read(dir, "MEMORY.md");
        ToolResult miss = tool.execute(Map.of("action", "replace", "text", "nonexistent", "with", "x"), ctx);
        check("replace of a missing substring is a loud isError, file unchanged",
                miss.isError() && read(dir, "MEMORY.md").equals(beforeMiss), miss.output());

        ToolResult userWrite = tool.execute(Map.of("action", "add", "target", "user", "text", "Speaks Tamil"), ctx);
        check("target=user writes USER.md, not MEMORY.md",
                !userWrite.isError() && read(dir, "USER.md").contains("Speaks Tamil"));
    }

    // ---- H4: frozen-snapshot — a mid-session write loads NEXT session -------
    @Test
    void h4_frozenSnapshotInjection() throws Exception {
        Path dir = fixtureDir();
        Agents.Agent ava = agentFromDir(dir, new Agents.AgentSpec().model("m-remember"));
        ava.run(rtOpts(), "note my coffee preference");
        check("the write landed on disk", read(dir, "USER.md").contains("Prefers dark roast"),
                read(dir, "USER.md"));
        // A SECOND, fresh fromDir re-reads the file → the snapshot now carries the note.
        Agents.Agent ava2 = agentFromDir(dir, new Agents.AgentSpec().model("m-recall"));
        TaskResult r2 = ava2.run(rtOpts(), "what do you know about me?");
        check("next session's snapshot carries the persisted memory",
                "I recall: dark roast".equals(r2.text()), r2.text());
    }

    // ---- H5: heartbeat — silent HEARTBEAT_OK, surfacing, ticks coalesce -----
    @Test
    void h5_heartbeatSilentSurfaceAndCoalesce() throws Exception {
        // (a) a persona with no due duty (no "3pm sync" in its soul) → HEARTBEAT_OK → nothing surfaces
        Path quietDir = mkdir(Map.of("SOUL.md", "You are Ava, calm."));
        Agents.Agent quiet = agentFromDir(quietDir, new Agents.AgentSpec().model("m-heartbeat"));
        List<String> quietReports = new CopyOnWriteArrayList<>();
        ManualClock quietClock = new ManualClock();
        Agents.StartedAgent quietRun = startAgent(quiet, rtOpts().clock(quietClock), 20L, quietReports::add);
        for (int i = 0; i < 3; i++) fireAndSettle(quietClock, quietRun.rt);
        quietRun.stop();
        check("a HEARTBEAT_OK beat stays silent (no report to the host)",
                quietReports.isEmpty(), String.valueOf(quietReports));

        // (b) a due HEARTBEAT.md → the reminder surfaces to onBeat (fixture bootstrap dir)
        Path dir = fixtureDir();
        Agents.Agent due = agentFromDir(dir, new Agents.AgentSpec().model("m-heartbeat"));
        List<String> onBeat = new CopyOnWriteArrayList<>();
        ManualClock clock = new ManualClock();
        Agents.StartedAgent started = startAgent(due, rtOpts().clock(clock), 20L, onBeat::add);
        for (int i = 0; i < 3; i++) fireAndSettle(clock, started.rt);
        started.stop();
        check("a due heartbeat surfaces its reminder to onBeat",
                onBeat.size() >= 1 && onBeat.stream().allMatch(b -> b.contains("3pm sync")),
                String.valueOf(onBeat));

        // (c) ticks coalesce: several intervals elapse while one gated turn is in flight → the
        // queued ticks drain as ONE turn, not one per tick. (pin: keep a turn genuinely in-flight.)
        CountDownLatch gate = mock.gateHeartbeat();
        Path cDir = fixtureDir();
        Agents.Agent slow = agentFromDir(cDir, new Agents.AgentSpec().model("m-heartbeat-gated"));
        ManualClock cClock = new ManualClock();
        Agents.StartedAgent coalesce = startAgent(slow, rtOpts().clock(cClock), 20L, null);
        cClock.fire();                                   // beat 1 — wakes; the turn blocks on the gate
        waitUntil(() -> coalesce.handle.state == Handle.State.RUNNING, 3000);
        cClock.fire();                                   // beat 2 — handle running, tick coalesces
        cClock.fire();                                   // beat 3 — still running, tick coalesces
        sleep(80);
        long wakes = coalesce.rt.trace().stream().filter(l -> l.contains("idle→running (wake)")).count();
        check("several beats over one in-flight turn coalesce to a SINGLE wake", wakes == 1, "wakes=" + wakes);
        gate.countDown();                                // release the turn
        coalesce.stop();
    }

    // ---- H6: read-only persona (memory:false) — no memory tool -------------
    @Test
    void h6_memoryFalseOmitsTheTool() throws Exception {
        Path dir = mkdir(Map.of("SOUL.md", "Read-only Ava."));
        Agents.Agent ava = agentFromDir(dir, new Agents.AgentSpec().memory(false));
        check("no memory tool when memory:false", !toolNames(ava).contains("memory"), String.valueOf(toolNames(ava)));
    }

    // ---- H7: recipe — 'dream' = fromDir + memory (composition, not new API) -
    @Test
    void h7_dreamIsFromDirPlusMemory() throws Exception {
        Path dir = mkdir(Map.of(
                "SOUL.md", "Nightly consolidator.",
                "HEARTBEAT.md", "Consolidate: merge duplicate notes into MEMORY.md."));
        Agents.Agent dream = agentFromDir(dir, new Agents.AgentSpec().model("m-echo-soul"));
        check("a dream agent is just fromDir + memory tool (composition, not new surface)",
                toolNames(dream).contains("memory"), String.valueOf(toolNames(dream)));
    }

    // ---- helpers -----------------------------------------------------------

    /** Fire one heartbeat interval on the virtual clock and wait for the woken turn to complete. */
    private static void fireAndSettle(ManualClock clock, AgentRuntime rt) {
        long before = rt.trace().stream().filter(l -> l.contains("(done")).count();
        clock.fire();
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (rt.trace().stream().filter(l -> l.contains("(done")).count() > before) {
                // The turn's transition is recorded before its woken thread runs onReport — a short
                // grace lets that report (if any) land before the test reads it.
                sleep(60);
                return;
            }
            sleep(5);
        }
    }

    private static void waitUntil(BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            sleep(5);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A virtual clock (SPEC §7E "all timing goes through the runtime's injectable clock"): the
     * scheduled heartbeat task is captured and fired explicitly by the test — no wall-clock waits,
     * deterministic transition traces.
     */
    private static final class ManualClock implements RuntimeClock {
        private final AtomicLong now = new AtomicLong(0);
        private final AtomicReference<Runnable> task = new AtomicReference<>();

        @Override public long millis() { return now.get(); }

        @Override public AutoCloseable schedule(long everyMs, Runnable t) {
            task.set(t);
            return () -> task.set(null);
        }

        /** Advance time by one interval and run the captured heartbeat task once. */
        void fire() {
            now.addAndGet(20);
            Runnable t = task.get();
            if (t != null) t.run();
        }
    }
}
