package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Change add-host-engine-needs (§2 command+args, §8 FileConversationStore + list/delete) and
 * add-mcp-progress-timeout (§2 idle timeout, against the shared examples/mcp-progress-server.mjs).
 */
class HostEngineNeedsTest {

    // ---------------- §2 command + args ----------------

    private static List<String> argvOf(String json) {
        Map<String, Object> servers = McpSource.parseConfig(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) servers.get("s");
        return McpSource.commandArgv("s", cfg);
    }

    @Test
    void commandStringPlusArgsUnderBothWrappers() {
        assertEquals(List.of("node", "server.mjs"),
                argvOf("{\"mcpServers\":{\"s\":{\"command\":\"node\",\"args\":[\"server.mjs\"]}}}"));
        assertEquals(List.of("node", "server.mjs"),
                argvOf("{\"servers\":{\"s\":{\"command\":\"node\",\"args\":[\"server.mjs\"]}}}"));
        assertEquals(List.of("node", "server.mjs"),
                argvOf("{\"mcp\":{\"s\":{\"command\":\"node\",\"args\":[\"server.mjs\"]}}}"));
    }

    @Test
    void commandArrayFlatFormAndArrayPlusArgs() {
        assertEquals(List.of("node", "server.mjs"), argvOf("{\"s\":{\"command\":[\"node\",\"server.mjs\"]}}"));
        assertEquals(List.of("node", "a", "b"), argvOf("{\"s\":{\"command\":[\"node\",\"a\"],\"args\":[\"b\"]}}"));
    }

    @Test
    void commandStringWithoutArgs() {
        assertEquals(List.of("node"), argvOf("{\"s\":{\"command\":\"node\"}}"));
    }

    @Test
    void argsWithSpacesAreNotShellSplit() {
        assertEquals(List.of("node", "my server.mjs", "--x=a b"),
                argvOf("{\"s\":{\"command\":\"node\",\"args\":[\"my server.mjs\",\"--x=a b\"]}}"));
    }

    private static void assertInvalid(String json, String field) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> McpSource.parseConfig(json));
        assertTrue(e.getMessage().contains("\"bad\""), e.getMessage());
        assertTrue(e.getMessage().contains(field), e.getMessage());
    }

    @Test
    void invalidCommandShapesFailNamingTheServer() {
        assertInvalid("{\"mcpServers\":{\"bad\":{\"command\":\"\"}}}", "command");
        assertInvalid("{\"mcpServers\":{\"bad\":{\"command\":[]}}}", "command");
        assertInvalid("{\"mcpServers\":{\"bad\":{\"command\":[\"node\",1]}}}", "command");
        assertInvalid("{\"mcpServers\":{\"bad\":{\"command\":7}}}", "command");
        assertInvalid("{\"bad\":{\"command\":\"node\",\"args\":\"x.mjs\"}}", "args");
        assertInvalid("{\"bad\":{\"command\":\"node\",\"args\":[\"ok\",2]}}", "args");
    }

    @Test
    void remoteServerIgnoresArgs() {
        Map<String, Object> s = McpSource.parseConfig("{\"r\":{\"url\":\"http://x\",\"args\":\"nonsense\"}}");
        assertTrue(s.containsKey("r"));
    }

    // ---------------- §8 FileConversationStore ----------------

    private static List<Object> msgs(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", text);
        return List.of(m);
    }

    @Test
    void fileStoreSyncModes(@TempDir Path dir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new LlmClient.FileConversationStore(dir, "bogus"));
        String[] modes = {null, "full", "fsync", "none"};
        for (int i = 0; i < modes.length; i++) {
            Path root = dir.resolve("m" + i);
            LlmClient.FileConversationStore s = modes[i] == null
                    ? new LlmClient.FileConversationStore(root)
                    : new LlmClient.FileConversationStore(root, modes[i]);
            s.save("c1", msgs("hi"));
            assertEquals(msgs("hi"), new LlmClient.FileConversationStore(root).get("c1"), "mode " + modes[i]);
            try (Stream<Path> ls = Files.list(root)) {
                assertEquals(List.of("c1.json"), ls.map(p -> p.getFileName().toString()).toList(), "mode " + modes[i]);
            }
        }
        for (LlmClient.FileConversationStore.Sync m : LlmClient.FileConversationStore.Sync.values()) {
            Path root = dir.resolve("e" + m);
            new LlmClient.FileConversationStore(root, m).save("c1", msgs("hi"));
            assertEquals(msgs("hi"), new LlmClient.FileConversationStore(root).get("c1"));
        }
    }

    @Test
    void fileStoreRoundTripsAcrossInstances(@TempDir Path dir) {
        Path root = dir.resolve("convos");
        new LlmClient.FileConversationStore(root).save("a1", msgs("hi"));
        assertEquals(msgs("hi"), new LlmClient.FileConversationStore(root).get("a1"));
        assertNull(new LlmClient.FileConversationStore(root).get("missing"));
    }

    @Test
    void fileStoreRequiresRoot() {
        assertThrows(IllegalArgumentException.class, () -> new LlmClient.FileConversationStore(""));
        assertThrows(IllegalArgumentException.class, () -> new LlmClient.FileConversationStore((Path) null));
    }

    @Test
    void fileStoreRejectsTraversalIds(@TempDir Path dir) {
        LlmClient.FileConversationStore st = new LlmClient.FileConversationStore(dir);
        for (String bad : new String[]{"..", ".", "../x", "a/b", "a\\b", "", "x".repeat(129), "a b"}) {
            assertThrows(IllegalArgumentException.class, () -> st.get(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> st.save(bad, msgs("x")), bad);
            assertThrows(IllegalArgumentException.class, () -> st.delete(bad), bad);
        }
    }

    @Test
    void fileStoreCorruptAndUnknownVersionAreErrors(@TempDir Path dir) throws Exception {
        LlmClient.FileConversationStore st = new LlmClient.FileConversationStore(dir);
        Files.writeString(dir.resolve("c.json"), "{not json");
        assertThrows(RuntimeException.class, () -> st.get("c"));
        Files.writeString(dir.resolve("v.json"), "{\"version\":2,\"messages\":[]}");
        assertThrows(RuntimeException.class, () -> st.get("v"));
        Files.writeString(dir.resolve("ok.json"), "{ \"messages\": [], \"version\": 1 }");
        assertEquals(List.of(), st.get("ok"));
    }

    @Test
    void fileStoreListNewestFirstTiesById(@TempDir Path dir) throws Exception {
        LlmClient.FileConversationStore st = new LlmClient.FileConversationStore(dir);
        st.save("b", msgs("1"));
        st.save("a", msgs("2"));
        st.save("c", msgs("3"));
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        Files.setLastModifiedTime(dir.resolve("a.json"), FileTime.from(t));
        Files.setLastModifiedTime(dir.resolve("b.json"), FileTime.from(t));
        Files.setLastModifiedTime(dir.resolve("c.json"), FileTime.from(t.plusSeconds(60)));
        Files.writeString(dir.resolve(".z.123.tmp"), "x");
        Files.writeString(dir.resolve("bad name.json"), "{}");
        List<String> ids = st.list().stream().map(LlmClient.ConversationInfo::id).toList();
        assertEquals(List.of("c", "a", "b"), ids);
        assertEquals(t.plusSeconds(60), st.list().get(0).updatedAt());
    }

    @Test
    void fileStoreDeleteIsIdempotent(@TempDir Path dir) {
        LlmClient.FileConversationStore st = new LlmClient.FileConversationStore(dir);
        st.save("x", msgs("1"));
        st.delete("x");
        assertNull(st.get("x"));
        assertDoesNotThrow(() -> st.delete("x"));
        assertDoesNotThrow(() -> new LlmClient.FileConversationStore(dir.resolve("never")).delete("x"));
    }

    @Test
    void fileStoreConcurrentSavesLeaveValidFileAndNoTemps(@TempDir Path dir) throws Exception {
        LlmClient.FileConversationStore st = new LlmClient.FileConversationStore(dir);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final int n = i;
            ts.add(Thread.ofPlatform().start(() -> {
                for (int j = 0; j < 10; j++) st.save("same", msgs("t" + n + "-" + j));
            }));
        }
        for (Thread t : ts) t.join();
        List<Object> got = st.get("same");
        assertEquals(1, got.size());
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(List.of("same.json"), s.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void inMemoryStoreListAndDelete() throws Exception {
        LlmClient.InMemoryConversationStore st = new LlmClient.InMemoryConversationStore();
        st.save("b", msgs("1"));
        Thread.sleep(5);
        st.save("a", msgs("2"));
        assertEquals(List.of("a", "b"), st.list().stream().map(LlmClient.ConversationInfo::id).toList());
        st.delete("a");
        st.delete("a");
        assertNull(st.get("a"));
        assertEquals(1, st.list().size());
    }

    // ---------------- §2 MCP idle timeout (shared fixture) ----------------

    private static McpSource progress;

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void startFixture() {
        if (!nodeAvailable()) return;
        String server = TestFixtures.fixture("mcp-progress-server.mjs");
        String cfg = "{\"mcpServers\":{\"prog\":{\"command\":\"node\",\"args\":[" + Json.stringify(server)
                + "],\"timeout\":300}}}";
        progress = McpSource.load(cfg);
    }

    @AfterAll
    static void stopFixture() {
        if (progress != null) progress.close();
    }

    private static Tool slow() {
        Assumptions.assumeTrue(progress != null, "node not available");
        assertEquals("connected", progress.status().get("prog"));
        return progress.tools().stream().filter(t -> t.name().equals("prog_slow")).findFirst().orElseThrow();
    }

    private static Map<String, Object> slowArgs(int total, int interval, boolean wrong) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("totalMs", total);
        a.put("intervalMs", interval);
        if (wrong) a.put("wrongToken", true);
        return a;
    }

    @Test
    void reportingServerOutlivesTheTimeout() {
        ToolResult r = slow().execute(slowArgs(1200, 100, false), new ToolContext());
        assertFalse(r.isError(), r.output());
        Map<String, Object> body = Json.toMap(r.output());
        assertEquals(true, body.get("progressTokenReceived"));
        assertTrue(((Number) body.get("notificationsSent")).intValue() > 0);
    }

    @Test
    void silentServerTimesOut() {
        long t0 = System.nanoTime();
        ToolResult r = slow().execute(slowArgs(1200, 0, false), new ToolContext());
        assertTrue(r.isError());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1100, "should fail near the 300ms timeout");
    }

    @Test
    void wrongTokenDoesNotExtendTheCall() {
        ToolResult r = slow().execute(slowArgs(1200, 100, true), new ToolContext());
        assertTrue(r.isError(), r.output());
    }

    @Test
    void cancellationWinsOverProgress() {
        AtomicBoolean cancel = new AtomicBoolean(false);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            cancel.set(true);
        });
        long t0 = System.nanoTime();
        ToolResult r = slow().execute(slowArgs(3000, 50, false), new ToolContext(null, cancel));
        assertTrue(r.isError());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1500, "cancel must abort promptly");
    }
}
