package io.github.muthuishere.toolnexus.agents;

import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.TranscriptSearch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static io.github.muthuishere.toolnexus.agents.Agents.DREAM_PROMPT;
import static io.github.muthuishere.toolnexus.agents.Agents.HEARTBEAT_PROMPT;
import static io.github.muthuishere.toolnexus.agents.Agents.agentFromDir;
import static io.github.muthuishere.toolnexus.agents.Agents.composeSoul;
import static io.github.muthuishere.toolnexus.agents.Agents.dreamer;
import static io.github.muthuishere.toolnexus.agents.Agents.memoryTool;
import static io.github.muthuishere.toolnexus.agents.Agents.startAgent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §7E "Memory hierarchy" + "Dreaming", driven by the shared fixtures
 * {@code examples/memory-hierarchy/fixture.json} and {@code examples/dreaming/fixture.json}.
 * Hermetic: a scripted localhost LLM, a virtual clock, temp dirs. Behavior reference:
 * {@code js/test/memory-hierarchy.test.ts}.
 */
@SuppressWarnings("unchecked")
class MemoryHierarchyTest {

    static final Map<String, Object> MH = load("memory-hierarchy");
    static final Map<String, Object> DR = load("dreaming");

    static Map<String, Object> load(String name) {
        for (Path p : List.of(Path.of("..", "examples", name, "fixture.json"), Path.of("examples", name, "fixture.json"))) {
            if (Files.exists(p)) {
                try {
                    return Json.toMap(Files.readString(p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("fixture not found: " + name);
    }

    static Map<String, Object> m(Object o, String k) { return (Map<String, Object>) ((Map<String, Object>) o).get(k); }

    static List<Object> l(Object o, String k) { return (List<Object>) ((Map<String, Object>) o).get(k); }

    static String s(Object o, String k) { return (String) ((Map<String, Object>) o).get(k); }

    static String sha256(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Path mkHome(Map<String, Object> files) throws IOException {
        Path dir = Files.createTempDirectory("memh-");
        for (Map.Entry<String, Object> e : files.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, (String) e.getValue());
        }
        return dir;
    }

    /** Every file under {@code dir}, keyed by '/'-separated relative path. */
    static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.filter(Files::isRegularFile).toList()) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return out;
    }

    static Map<String, Object> res(ToolResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("output", r.output());
        m.put("isError", r.isError());
        return m;
    }

    /** A stub store over the fixture conversations: list() in fixture order, save() forbidden. */
    static final class StubStore implements LlmClient.ListableConversationStore {
        final List<Object> conversations;
        final AtomicInteger reads = new AtomicInteger();

        StubStore(List<Object> conversations) { this.conversations = conversations; }

        @Override public List<Object> get(String id) {
            reads.incrementAndGet();
            for (Object c : conversations) if (id.equals(s(c, "id"))) return l(c, "messages");
            return null;
        }

        @Override public void save(String id, List<Object> messages) {
            throw new AssertionError("transcript search must never write");
        }

        @Override public List<LlmClient.ConversationInfo> list() {
            reads.incrementAndGet();
            List<LlmClient.ConversationInfo> out = new ArrayList<>();
            for (Object c : conversations) out.add(new LlmClient.ConversationInfo(s(c, "id"), java.time.Instant.EPOCH));
            return out;
        }
    }

    static final class NoListStore implements LlmClient.ConversationStore {
        @Override public List<Object> get(String id) { return null; }
        @Override public void save(String id, List<Object> messages) {}
    }

    // =====================================================================
    // G1 — index cap
    // =====================================================================

    static String capMemory(Map<String, Object> c) {
        if (c.get("memory") != null) return (String) c.get("memory");
        Map<String, Object> g = m(c, "generate");
        StringBuilder b = new StringBuilder();
        int count = ((Number) g.get("count")).intValue();
        for (int i = 1; i <= count; i++) b.append(((String) g.get("line")).replace("<i>", String.valueOf(i))).append('\n');
        return b.toString();
    }

    static Agents.MemoryIndexCap cap(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) return null;
        if (Boolean.TRUE.equals(v)) return Agents.MemoryIndexCap.preset();
        Map<String, Object> o = (Map<String, Object>) v;
        return new Agents.MemoryIndexCap(
                o.get("maxLines") != null ? ((Number) o.get("maxLines")).intValue() : null,
                o.get("maxBytes") != null ? ((Number) o.get("maxBytes")).intValue() : null);
    }

    @TestFactory
    Stream<DynamicTest> g1IndexCap() {
        return l(m(MH, "indexCap"), "cases").stream().map(o -> {
            Map<String, Object> c = (Map<String, Object>) o;
            return DynamicTest.dynamicTest("G1 index cap: " + c.get("name"), () -> {
                String mem = capMemory(c);
                Map<String, Object> files = new LinkedHashMap<>(m(c, "files"));
                files.put("MEMORY.md", mem);
                Path dir = mkHome(files);
                Map<String, Object> opts = m(c, "options");
                String soul = composeSoul(dir, cap(opts.get("memoryIndexCap")), Boolean.TRUE.equals(opts.get("notes"))).soul();
                Map<String, Object> e = m(c, "expect");
                assertEquals(e.get("soulSha256"), sha256(soul));
                assertEquals(((Number) e.get("soulBytes")).intValue(), soul.getBytes(StandardCharsets.UTF_8).length);
                assertTrue(soul.endsWith((String) e.get("memorySectionTail")));
                assertEquals(e.get("equalsUncappedSoul"), soul.equals(composeSoul(dir).soul()));
                assertEquals(mem, Files.readString(dir.resolve("MEMORY.md"))); // disk untouched
            });
        });
    }

    @Test
    void g1FromDirThreadsCap() throws IOException {
        Map<String, Object> c = (Map<String, Object>) l(m(MH, "indexCap"), "cases").stream()
                .filter(x -> "lines-cap-preset-notes-off".equals(s(x, "name"))).findFirst().orElseThrow();
        Map<String, Object> files = new LinkedHashMap<>(m(c, "files"));
        files.put("MEMORY.md", capMemory(c));
        Path dir = mkHome(files);
        String soul = agentFromDir(dir, new Agents.AgentSpec().memoryIndexCap(Agents.MemoryIndexCap.preset())).spec.soul;
        assertEquals(s(m(c, "expect"), "soulSha256"), sha256(soul));
        assertTrue(soul.contains("- fact 200\n\n[MEMORY.md index truncated") && !soul.contains("- fact 201"));
        assertEquals(500, Files.readString(dir.resolve("MEMORY.md")).split("\n").length);
    }

    @Test
    void g1InvalidCapThrows() throws IOException {
        Path dir = mkHome(Map.of("MEMORY.md", "- a\n"));
        assertThrows(IllegalArgumentException.class, () -> composeSoul(dir, new Agents.MemoryIndexCap(0, null), false));
        assertThrows(IllegalArgumentException.class, () -> composeSoul(dir, new Agents.MemoryIndexCap(null, -1), false));
    }

    // =====================================================================
    // G2 — notes tier
    // =====================================================================

    @Test
    void g2SchemasAndDescriptionsPinned() {
        Map<String, Object> mt = m(MH, "memoryTool");
        Tool off = memoryTool(Path.of("/x"));
        assertEquals(s(m(mt, "notesOff"), "description"), off.description());
        assertEquals(Json.stringify(m(m(mt, "notesOff"), "inputSchema")), Json.stringify(off.inputSchema()));
        assertEquals(Json.stringify(off.inputSchema()), Json.stringify(memoryTool(Path.of("/x"), false).inputSchema()));
        Tool on = memoryTool(Path.of("/x"), true);
        assertEquals(s(m(mt, "notesOn"), "description"), on.description());
        assertEquals(Json.stringify(m(m(mt, "notesOn"), "inputSchema")), Json.stringify(on.inputSchema()));
    }

    @TestFactory
    Stream<DynamicTest> g2MemoryTool() {
        return l(m(MH, "memoryTool"), "cases").stream().map(o -> {
            Map<String, Object> c = (Map<String, Object>) o;
            return DynamicTest.dynamicTest("G2 memory tool: " + c.get("name"), () -> {
                Path dir = mkHome(m(c, "seed"));
                Tool tool = memoryTool(dir, Boolean.TRUE.equals(c.get("notes")));
                for (Object op : l(c, "ops")) {
                    ToolResult r = tool.execute(m(op, "args"), null);
                    assertEquals(m(op, "expect"), res(r), String.valueOf(m(op, "args")));
                }
                assertEquals(new TreeMap<>((Map<String, String>) (Map<?, ?>) m(c, "expectFiles")), snapshot(dir));
                if (c.get("soulMustNotContain") != null) {
                    assertFalse(composeSoul(dir, null, true).soul().contains((String) c.get("soulMustNotContain")));
                }
            });
        });
    }

    @Test
    void g2ListSkipsNonRegular() throws IOException {
        Map<String, Object> cfg = m(m(MH, "memoryTool"), "listSkipsNonRegular");
        Path dir = mkHome(m(cfg, "regular"));
        try {
            for (Map.Entry<String, Object> e : m(cfg, "symlinks").entrySet()) {
                Files.createSymbolicLink(dir.resolve(e.getKey()), Path.of((String) e.getValue()));
            }
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + e);
        }
        for (Object d : l(cfg, "dirs")) Files.createDirectories(dir.resolve((String) d));
        ToolResult r = memoryTool(dir, true).execute(Map.of("action", "list"), null);
        assertFalse(r.isError(), r.output());
        assertEquals(s(cfg, "expectList"), r.output());
    }

    @Test
    void g2FromDirNotesWiresNotesTool() throws IOException {
        Path dir = mkHome(Map.of("SOUL.md", "S"));
        Tool mem = agentFromDir(dir, new Agents.AgentSpec().notes(true)).spec.tools.stream()
                .filter(t -> t.name().equals("memory")).findFirst().orElseThrow();
        assertEquals(s(m(m(MH, "memoryTool"), "notesOn"), "description"), mem.description());
    }

    // =====================================================================
    // G3 — transcript search
    // =====================================================================

    static List<Object> mhConversations() { return l(m(m(MH, "transcriptSearch"), "store"), "conversations"); }

    @Test
    void g3SchemaPinned() {
        Map<String, Object> ts = m(MH, "transcriptSearch");
        Tool t = TranscriptSearch.transcriptSearchTool(new StubStore(List.of()));
        assertEquals(s(m(ts, "defaults"), "name"), t.name());
        assertEquals(s(ts, "description"), t.description());
        assertEquals(Json.stringify(m(ts, "inputSchema")), Json.stringify(t.inputSchema()));
    }

    @TestFactory
    Stream<DynamicTest> g3TranscriptSearch() {
        return l(m(MH, "transcriptSearch"), "cases").stream().map(o -> {
            Map<String, Object> c = (Map<String, Object>) o;
            return DynamicTest.dynamicTest("G3 transcript search: " + c.get("name"), () -> {
                TranscriptSearch.Options opts = TranscriptSearch.Options.defaults();
                Map<String, Object> co = m(c, "options");
                if (co != null) {
                    if (co.get("maxResults") != null) opts = opts.maxResults(((Number) co.get("maxResults")).intValue());
                    if (co.get("snippetChars") != null) opts = opts.snippetChars(((Number) co.get("snippetChars")).intValue());
                }
                List<Object> convs = Boolean.TRUE.equals(c.get("emptyStore")) ? List.of() : mhConversations();
                Tool t = TranscriptSearch.transcriptSearchTool(new StubStore(convs), opts);
                // the parsed fixture keeps a JSON null as a present key (null-limit case)
                Map<String, Object> args = new LinkedHashMap<>(m(c, "args"));
                assertEquals(m(c, "expect"), res(t.execute(args, null)));
            });
        });
    }

    @Test
    void g3EmptyQueryNoReadAndListRequired() {
        StubStore store = new StubStore(mhConversations());
        ToolResult r = TranscriptSearch.transcriptSearchTool(store).execute(Map.of("query", ""), null);
        assertTrue(r.isError());
        assertEquals(0, store.reads.get());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TranscriptSearch.transcriptSearchTool(new NoListStore()));
        assertEquals(s(m(m(MH, "transcriptSearch"), "storeWithoutList"), "error"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> TranscriptSearch.transcriptSearchTool(store, TranscriptSearch.Options.defaults().maxResults(0)));
    }

    @Test
    void g3ShippedInMemoryStore() {
        LlmClient.InMemoryConversationStore store = new LlmClient.InMemoryConversationStore();
        store.save("only", List.of(Map.of("role", "user", "content", "The vault code is 4417")));
        assertEquals("only#0 user: The vault code is 4417",
                TranscriptSearch.transcriptSearchTool(store).execute(Map.of("query", "VAULT"), null).output());
    }

    // =====================================================================
    // G4 — write safety
    // =====================================================================

    @Test
    void g4ConcurrentAddsTwoToolsOneHome() throws Exception {
        Map<String, Object> cfg = m(m(MH, "writeSafety"), "concurrentAdds");
        int writers = ((Number) cfg.get("writers")).intValue();
        int adds = ((Number) cfg.get("addsEach")).intValue();
        int expect = ((Number) cfg.get("expectEntries")).intValue();
        Path dir = mkHome(Map.of());
        // two tools over ONE home, the second spelled differently (the resolved path is the lock key)
        List<Tool> tools = List.of(memoryTool(dir), memoryTool(dir.resolve(".").resolve("x").getParent()));
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            for (int n = 0; n < adds; n++) {
                Tool t = tools.get(w % tools.size());
                String text = "w" + w + "-" + n;
                threads.add(Thread.startVirtualThread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    t.execute(Map.of("action", "add", "text", text), null);
                }));
            }
        }
        go.countDown();
        for (Thread th : threads) th.join();
        String[] lines = Files.readString(dir.resolve("MEMORY.md")).stripTrailing().split("\n");
        assertEquals(expect, lines.length);
        assertEquals(expect, new HashSet<>(List.of(lines)).size());
    }

    @Test
    void g4ConcurrentAddsViaSymlink() throws Exception {
        Map<String, Object> cfg = m(m(MH, "writeSafety"), "concurrentAddsViaSymlink");
        int writers = ((Number) cfg.get("writers")).intValue();
        int adds = ((Number) cfg.get("addsEach")).intValue();
        int expect = ((Number) cfg.get("expectEntries")).intValue();
        Path dir = mkHome(Map.of());
        Path link = dir.resolveSibling(dir.getFileName() + "-link");
        try {
            Files.createSymbolicLink(link, dir);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + e);
        }
        List<Tool> tools = List.of(memoryTool(link), memoryTool(dir));
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            for (int n = 0; n < adds; n++) {
                Tool t = tools.get(w % tools.size());
                String text = "w" + w + "-" + n;
                threads.add(Thread.startVirtualThread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    t.execute(Map.of("action", "add", "text", text), null);
                }));
            }
        }
        go.countDown();
        for (Thread th : threads) th.join();
        String[] lines = Files.readString(dir.resolve("MEMORY.md")).stripTrailing().split("\n");
        assertEquals(expect, lines.length);
        assertEquals(expect, new HashSet<>(List.of(lines)).size());
        Files.delete(link);
    }

    @Test
    void g4LargeFileAddNoTruncationNoTemp() throws IOException {
        Map<String, Object> cfg = m(m(MH, "writeSafety"), "largeFileAdd");
        String original = ((String) cfg.get("fill")).repeat(((Number) cfg.get("bytes")).intValue());
        Path dir = mkHome(Map.of("MEMORY.md", original));
        ToolResult r = memoryTool(dir, true).execute(Map.of("action", "add", "text", cfg.get("addText")), null);
        assertFalse(r.isError(), r.output());
        assertEquals(original + "\n- " + cfg.get("addText") + "\n", Files.readString(dir.resolve("MEMORY.md")));
        try (Stream<Path> ls = Files.list(dir)) {
            assertEquals(List.of("MEMORY.md"), ls.map(p -> p.getFileName().toString()).toList());
        }
    }

    // =====================================================================
    // Dreaming
    // =====================================================================

    static HttpServer server;
    static String base;
    static volatile String dreamScript = "dreamer-fix";

    @BeforeAll
    static void startMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            Map<String, Object> req = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> msg = respond(req);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("choices", List.of(Map.of("message", msg)));
            payload.put("usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2));
            byte[] out = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopMock() { server.stop(0); }

    static Map<String, Object> text(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content);
        return m;
    }

    static Map<String, Object> callStep(List<Object> steps, int i) {
        Map<String, Object> tc = (Map<String, Object>) l(steps.get(i), "toolCalls").get(0);
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", tc.get("name"));
        fn.put("arguments", Json.stringify(tc.get("args")));
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "d" + i);
        call.put("type", "function");
        call.put("function", fn);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", null);
        m.put("tool_calls", List.of(call));
        return m;
    }

    /** The fixture's scripted LLM: the dreamer is recognised by its system prompt, never model id. */
    static Map<String, Object> respond(Map<String, Object> req) {
        String sys = "";
        List<String> tool = new ArrayList<>();
        for (Object o : (List<Object>) req.get("messages")) {
            Map<String, Object> mm = (Map<String, Object>) o;
            if ("system".equals(mm.get("role"))) sys = String.valueOf(mm.get("content"));
            if ("tool".equals(mm.get("role"))) tool.add(String.valueOf(mm.get("content")));
        }
        if (sys.contains("You are dreaming:")) {
            List<Object> steps = l(m(DR, "mockLLM").get("scripts"), dreamScript);
            if ("dreamer-idle".equals(dreamScript)) return text(s(steps.get(0), "text"));
            int n = tool.size();
            if (n <= 1) return callStep(steps, n);
            if (n == 2) {
                if (tool.get(0).contains("Mt. Moon exit is south") && tool.get(1).contains("exit is north")) return callStep(steps, 2);
                return text("HEARTBEAT_OK");
            }
            if (n == 3) return callStep(steps, 3);
            return text(s(steps.get(4), "text"));
        }
        if ("m-navigator".equals(req.get("model"))) {
            return text(sys.contains("Mt. Moon exit is north") ? "Head north out of Mt. Moon." : "Head south out of Mt. Moon.");
        }
        return text("HEARTBEAT_OK");
    }

    static RuntimeOptions rt() { return new RuntimeOptions().baseUrl(base).apiKey("test"); }

    static StubStore store() { return new StubStore(l(m(DR, "store"), "conversations")); }

    @Test
    void d0DreamPromptExact() {
        assertEquals(DR.get("dreamPrompt"), DREAM_PROMPT);
    }

    @Test
    void d7DreamGuard() throws IOException {
        Map<String, Object> e = m(m(DR, "expect"), "D7_dreamGuard");
        Path home = mkHome(m(e, "files"));
        Tool mem = dreamer(home, store()).spec.tools.get(0);
        for (Object o : l(e, "cases")) {
            Map<String, Object> c = (Map<String, Object>) o;
            Map<String, Object> want = new LinkedHashMap<>();
            want.put("output", c.get("output"));
            want.put("isError", c.get("isError"));
            assertEquals(want, res(mem.execute(m(c, "args"), null)), String.valueOf(c.get("name")));
        }
        Map<String, String> snap = snapshot(home);
        assertEquals(new TreeSet<>(m(e, "files").keySet()), new TreeSet<>(snap.keySet()));
        for (Map.Entry<String, Object> f : m(e, "filesAfter").entrySet()) assertEquals(f.getValue(), snap.get(f.getKey()), f.getKey());
        Map<String, Object> p = m(e, "plainToolStillWrites");
        Map<String, Object> want = new LinkedHashMap<>();
        want.put("output", p.get("output"));
        want.put("isError", p.get("isError"));
        assertEquals(want, res(memoryTool(home, true).execute(m(p, "args"), null)));
    }

    @Test
    void d8DreamPromptRemovesOnlyContradicted() {
        Map<String, Object> e = m(m(DR, "expect"), "D8_promptKeepsUnmentionedClaims");
        assertTrue(DREAM_PROMPT.contains(s(e, "dreamPromptContains")));
        assertFalse(DREAM_PROMPT.contains(s(e, "dreamPromptNotContains")));
    }

    @Test
    void d1BuildGateMtMoon() throws IOException {
        Map<String, Object> g = m(m(DR, "expect"), "D1_mtMoonBuildGate");
        Path home = mkHome(m(DR, "home"));
        dreamScript = "dreamer-fix";
        TaskResult before = agentFromDir(home, new Agents.AgentSpec().model(s(m(g, "before"), "model")))
                .run(rt(), s(m(g, "before"), "prompt"));
        assertEquals(s(m(g, "before"), "text"), before.text());

        TaskResult pass = dreamer(home, store()).run(rt(), HEARTBEAT_PROMPT);
        assertEquals(s(m(g, "pass"), "replyText"), pass.text());
        Map<String, String> snap = snapshot(home);
        for (Map.Entry<String, Object> e : m(m(g, "after"), "files").entrySet()) {
            assertEquals(e.getValue(), snap.get(e.getKey()), e.getKey());
        }
        assertEquals(1, snap.get("memory/log.md").lines().filter(x -> x.startsWith("- ")).count());

        Map<String, Object> next = m(m(g, "after"), "nextSession");
        TaskResult after = agentFromDir(home, new Agents.AgentSpec().model(s(next, "model"))).run(rt(), s(next, "prompt"));
        assertEquals(s(next, "text"), after.text());

        // D2 (second case): a second pass finds nothing to do — silent, nothing written.
        Map<String, String> frozen = snapshot(home);
        TaskResult again = dreamer(home, store()).run(rt(), HEARTBEAT_PROMPT);
        assertTrue(again.text().contains("HEARTBEAT_OK"), again.text());
        assertEquals(frozen, snapshot(home));
    }

    @Test
    void d2IdleDreamerWritesNothing() throws IOException {
        Path home = mkHome(m(DR, "home"));
        Map<String, String> frozen = snapshot(home);
        dreamScript = "dreamer-idle";
        try {
            assertTrue(dreamer(home, store()).run(rt(), HEARTBEAT_PROMPT).text().contains("HEARTBEAT_OK"));
        } finally {
            dreamScript = "dreamer-fix";
        }
        assertEquals(frozen, snapshot(home));
    }

    @Test
    void d3DreamerToolsExactly() throws IOException {
        Map<String, Object> e = m(m(DR, "expect"), "D3_dreamerToolsExactly");
        Path home = mkHome(m(DR, "home"));
        List<Tool> fakes = new ArrayList<>();
        for (Object n : l(e, "personaTools")) fakes.add(fake((String) n));
        Agents.AgentSpec spec = new Agents.AgentSpec();
        spec.tools = fakes;
        Agents.Agent persona = agentFromDir(home, spec);
        List<Object> expectPersona = new ArrayList<>(l(e, "personaTools"));
        expectPersona.add("memory");
        assertEquals(expectPersona, persona.spec.tools.stream().map(Tool::name).map(Object.class::cast).toList());
        List<Tool> tools = dreamer(home, store()).spec.tools;
        assertEquals(l(e, "dreamerTools"), tools.stream().map(Tool::name).map(Object.class::cast).toList());
        assertEquals(s(m(m(MH, "memoryTool"), "notesOn"), "description"), tools.get(0).description());
    }

    static Tool fake(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return ToolResult.ok("x"); }
        };
    }

    @Test
    void d3bDreamerSoulNameStoreChecks() throws IOException {
        Map<String, Object> e = m(m(DR, "expect"), "D3b_dreamerSoul");
        Path plain = mkHome(m(DR, "home"));
        assertEquals(e.get("soulWithoutExtras"), dreamer(plain, store()).spec.soul);
        assertEquals(plain.getFileName() + "-dreamer", dreamer(plain, store()).name);
        assertEquals("inherit", dreamer(plain, store()).spec.model);
        Map<String, Object> files = new LinkedHashMap<>(m(DR, "home"));
        files.put("DREAM.md", e.get("DREAM.md"));
        Path extras = mkHome(files);
        assertEquals(e.get("soul"), dreamer(extras, store(), s(e, "instructions"), null, null).spec.soul);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> dreamer(plain, new NoListStore()))
                .getMessage().contains("list()"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> dreamer(plain, null))
                .getMessage().contains("store is required"));
        assertThrows(IllegalArgumentException.class,
                () -> agentFromDir(plain, new Agents.AgentSpec().dream(new Agents.Dream(0, store()))));
    }

    @Test
    void d4FromDirDreamRunsOnSameRuntime() throws IOException {
        Map<String, Object> e = m(m(DR, "expect"), "D4_fromDirDream");
        Path home = mkHome(m(DR, "home"));
        VirtualClock clock = new VirtualClock();
        List<String> reports = new CopyOnWriteArrayList<>();
        dreamScript = "dreamer-fix";
        Agents.StartedAgent started = startAgent(
                agentFromDir(home, new Agents.AgentSpec().model("m-quiet").dream(new Agents.Dream(50, store()))),
                rt().clock(clock), 1000L, reports::add);
        clock.advance(50);
        Map<String, Object> files = m(m(e, "after"), "files");
        waitUntil(() -> {
            try {
                Map<String, String> snap = snapshot(home);
                return files.entrySet().stream().allMatch(f -> f.getValue().equals(snap.get(f.getKey())));
            } catch (IOException ex) {
                return false;
            }
        }, 5000);
        Map<String, String> snap = snapshot(home);
        for (Map.Entry<String, Object> f : files.entrySet()) assertEquals(f.getValue(), snap.get(f.getKey()), f.getKey());
        started.stop();
        assertEquals(List.of(), reports);
        assertTrue(started.rt.trace().stream().anyMatch(x -> x.contains("-dreamer")), "dreamer spawned on the same runtime");
    }

    @Test
    void d5AbsentDreamUnchanged() throws IOException {
        Path home = mkHome(m(DR, "home"));
        Map<String, String> frozen = snapshot(home);
        VirtualClock clock = new VirtualClock();
        Agents.Agent persona = agentFromDir(home, new Agents.AgentSpec().model("m-quiet"));
        assertEquals(1, persona.registry().size());
        Agents.StartedAgent started = startAgent(persona, rt().clock(clock), 50L, null);
        for (int i = 0; i < 4; i++) {
            long before = started.rt.trace().stream().filter(x -> x.contains("(done")).count();
            clock.advance(50);
            waitUntil(() -> started.rt.trace().stream().filter(x -> x.contains("(done")).count() > before, 3000);
        }
        started.stop();
        assertEquals(frozen, snapshot(home));
        assertFalse(started.rt.trace().stream().anyMatch(x -> x.contains("dreamer")));
    }

    @Test
    void d6DreamerNameCollision() throws IOException {
        Map<String, Object> e = m(m(DR, "expect"), "D6_dreamerNameCollision");
        String name = s(e, "personaName");
        Path home = mkHome(m(DR, "home"));
        VirtualClock clock = new VirtualClock();
        Agents.Agent clash = Agents.agent(name + "-dreamer", new Agents.AgentSpec().does("team member"));
        Agents.Agent persona = agentFromDir(home, new Agents.AgentSpec().name(name).model("m-quiet")
                .team(clash).dream(new Agents.Dream(50, store())));
        IllegalStateException err = assertThrows(IllegalStateException.class,
                () -> startAgent(persona, rt().clock(clock), 50L, null));
        assertEquals(s(e, "error"), err.getMessage());
        assertEquals(0, clock.scheduledCount(), "nothing scheduled");
    }

    static void waitUntil(BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !cond.getAsBoolean()) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** A virtual clock holding every scheduled interval; {@link #advance} fires those due. */
    static final class VirtualClock implements RuntimeClock {
        private final AtomicLong now = new AtomicLong();
        private final List<long[]> next = new CopyOnWriteArrayList<>();      // {dueAt, everyMs, id}
        private final Map<Long, Runnable> tasks = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        @Override public long millis() { return now.get(); }

        long scheduledCount() { return ids.get(); }

        @Override public AutoCloseable schedule(long everyMs, Runnable task) {
            long id = ids.incrementAndGet();
            tasks.put(id, task);
            next.add(new long[] {now.get() + everyMs, everyMs, id});
            return () -> tasks.remove(id);
        }

        void advance(long ms) {
            long target = now.get() + ms;
            while (true) {
                long[] due = null;
                for (long[] t : next) if (tasks.containsKey(t[2]) && t[0] <= target && (due == null || t[0] < due[0])) due = t;
                if (due == null) break;
                now.set(due[0]);
                due[0] += due[1];
                Runnable r = tasks.get(due[2]);
                if (r != null) r.run();
            }
            now.set(target);
        }
    }
}
