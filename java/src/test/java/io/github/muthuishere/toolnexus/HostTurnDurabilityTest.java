package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Change add-host-turn-durability: interrupted stateful turns are saved (§8), builtin
 * {@code killGraceMs} (§5), and edit/write/apply_patch diffs in {@code metadata} (§5).
 */
class HostTurnDurabilityTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"model\":\"m-fail\"")) {
                byte[] out = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(500, out.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(out); }
                return;
            }
            boolean anthropic = ex.getRequestURI().getPath().endsWith("/messages");
            Map<String, Object> payload = new LinkedHashMap<>();
            if (anthropic) {
                payload.put("content", List.of(
                        Map.of("type", "tool_use", "id", "t1", "name", "fast", "input", Map.of()),
                        Map.of("type", "tool_use", "id", "t2", "name", "slow", "input", Map.of())));
                payload.put("stop_reason", "tool_use");
                payload.put("usage", Map.of("input_tokens", 1, "output_tokens", 1));
            } else {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "assistant");
                message.put("content", null);
                message.put("tool_calls", List.of(call("c1", "fast"), call("c2", "slow")));
                payload.put("choices", List.of(Map.of("message", message)));
                payload.put("usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2));
            }
            respond(ex, payload);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    private static Map<String, Object> call(String id, String name) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("type", "function");
        c.put("function", Map.of("name", name, "arguments", "{}"));
        return c;
    }

    private static void respond(HttpExchange ex, Map<String, Object> payload) throws IOException {
        byte[] out = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    private static LlmClient client(String style, String model) {
        return LlmClient.create(new LlmClient.Options()
                .baseUrl(base).style(style).model(model).apiKey("test").retries(0));
    }

    private static Tool tool(String name, ToolBody body) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return body.run(); }
        };
    }

    @FunctionalInterface
    interface ToolBody { ToolResult run(); }

    // ---------------- §8 interrupted turns are saved ----------------

    /** Cancel while the second tool call runs; returns the saved transcript. */
    private static List<Object> cancelMidSecondTool(String style) throws Exception {
        CountDownLatch fastDone = new CountDownLatch(1);
        CountDownLatch slowStarted = new CountDownLatch(1);
        Tool fast = tool("fast", () -> { fastDone.countDown(); return ToolResult.ok("fast-ok"); });
        Tool slow = tool("slow", () -> {
            try {
                fastDone.await();
                slowStarted.countDown();
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                return ToolResult.error("slow-was-interrupted");
            }
            return ToolResult.ok("slow-ok");
        });
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(fast, slow));
        LlmClient c = client(style, "m-two");
        LlmClient.CancelToken token = new LlmClient.CancelToken();
        Thread canceller = new Thread(() -> {
            // fastDone fires just BEFORE fast returns; give its result time to land, or the
            // cancel can race it and fast is (correctly) recorded as interrupted.
            try { slowStarted.await(); Thread.sleep(100); } catch (InterruptedException ignored) { }
            token.cancel();
        });
        canceller.start();
        long t0 = System.nanoTime();
        assertThrows(LlmClient.CancelledException.class, () -> c.ask("do both", tk, "conv", token));
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(10), "cancel did not abort the tool wait");
        canceller.join();
        return c.conversationStore().get("conv");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelledMidToolKeepsPromptAndFinishedPairs_openai() throws Exception {
        List<Object> saved = cancelMidSecondTool("openai");
        assertNotNull(saved);
        assertEquals(4, saved.size(), String.valueOf(saved));
        Map<String, Object> user = (Map<String, Object>) saved.get(0);
        assertEquals("user", user.get("role"));
        assertEquals("do both", user.get("content"));
        Map<String, Object> asst = (Map<String, Object>) saved.get(1);
        assertEquals("assistant", asst.get("role"));
        assertEquals(2, ((List<?>) asst.get("tool_calls")).size());
        Map<String, Object> r1 = (Map<String, Object>) saved.get(2);
        assertEquals("tool", r1.get("role"));
        assertEquals("c1", r1.get("tool_call_id"));
        assertEquals("fast-ok", r1.get("content"));
        Map<String, Object> r2 = (Map<String, Object>) saved.get(3);
        assertEquals("tool", r2.get("role"));
        assertEquals("c2", r2.get("tool_call_id"));
        assertEquals("interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.", r2.get("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelledMidToolKeepsPromptAndFinishedPairs_anthropic() throws Exception {
        List<Object> saved = cancelMidSecondTool("anthropic");
        assertNotNull(saved);
        assertEquals(3, saved.size(), String.valueOf(saved));
        assertEquals("user", ((Map<String, Object>) saved.get(0)).get("role"));
        Map<String, Object> asst = (Map<String, Object>) saved.get(1);
        assertEquals("assistant", asst.get("role"));
        Map<String, Object> results = (Map<String, Object>) saved.get(2);
        assertEquals("user", results.get("role"));
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) results.get("content");
        assertEquals(2, blocks.size());
        assertEquals("tool_result", blocks.get(0).get("type"));
        assertEquals("t1", blocks.get(0).get("tool_use_id"));
        assertEquals("fast-ok", blocks.get(0).get("content"));
        assertEquals(false, blocks.get(0).get("is_error"));
        assertEquals("t2", blocks.get(1).get("tool_use_id"));
        assertEquals("interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.", blocks.get(1).get("content"));
        assertEquals(true, blocks.get(1).get("is_error"));
    }

    private static List<Object> prior() {
        List<Object> p = new ArrayList<>();
        p.add(new LinkedHashMap<>(Map.of("role", "user", "content", "earlier")));
        p.add(new LinkedHashMap<>(Map.of("role", "assistant", "content", "before")));
        return p;
    }

    @SuppressWarnings("unchecked")
    private static void assertPriorPlusPrompt(List<Object> saved) {
        assertNotNull(saved);
        assertEquals(3, saved.size(), String.valueOf(saved));
        assertEquals("earlier", ((Map<String, Object>) saved.get(0)).get("content"));
        assertEquals("before", ((Map<String, Object>) saved.get(1)).get("content"));
        Map<String, Object> user = (Map<String, Object>) saved.get(2);
        assertEquals("user", user.get("role"));
        assertEquals("next", user.get("content"));
    }

    @Test
    void providerErrorSavesThePrompt_ask_bothStyles() {
        for (String style : List.of("openai", "anthropic")) {
            LlmClient c = client(style, "m-fail");
            c.conversationStore().save("conv", prior());
            Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
            assertThrows(RuntimeException.class, () -> c.ask("next", tk, "conv"));
            assertPriorPlusPrompt(c.conversationStore().get("conv"));
        }
    }

    @Test
    void providerErrorSavesThePrompt_stream_bothStyles() {
        for (String style : List.of("openai", "anthropic")) {
            LlmClient c = client(style, "m-fail");
            c.conversationStore().save("conv", prior());
            Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
            assertThrows(RuntimeException.class, () -> c.stream("next", tk, ev -> { }, "conv"));
            assertPriorPlusPrompt(c.conversationStore().get("conv"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerErrorOnFreshConversationSavesJustThePrompt() {
        LlmClient c = client("openai", "m-fail");
        assertThrows(RuntimeException.class, () -> c.ask("first", (Toolkit) null, "fresh"));
        List<Object> saved = c.conversationStore().get("fresh");
        assertNotNull(saved);
        assertEquals(1, saved.size());
        assertEquals("first", ((Map<String, Object>) saved.get(0)).get("content"));
    }

    // ---------------- §5 killGraceMs ----------------

    private static Tool builtin(Map<String, Object> cfg, String name) {
        for (Tool t : BuiltinTools.create(cfg)) if (t.name().equals(name)) return t;
        throw new AssertionError(name);
    }

    @Test
    void shortKillGraceKillsTermIgnoringTreeFast(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().startsWith("win"));
        Tool bash = builtin(Map.of("killGraceMs", 100), "bash");
        List<Long> elapsedMs = new ArrayList<>();
        for (int run = 0; run < 10; run++) {
            Path pidfile = dir.resolve("pid" + run);
            // The job AND a background descendant both ignore SIGTERM.
            String cmd = "trap '' TERM; (trap '' TERM; sleep 30) & echo $$ > " + pidfile + "; sleep 30; wait";
            Thread job = new Thread(() -> bash.execute(Map.of("command", cmd), new ToolContext()));
            job.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((!Files.exists(pidfile) || Files.readString(pidfile).isBlank()) && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            long pid = Long.parseLong(Files.readString(pidfile).trim());
            ProcessHandle root = ProcessHandle.of(pid).orElseThrow();
            // wait for the background subshell + its sleep to exist
            while (root.descendants().count() < 3 && System.nanoTime() < until) Thread.sleep(5);
            List<ProcessHandle> tree = new ArrayList<>(root.descendants().toList());
            tree.add(root);
            assertTrue(tree.size() >= 3, "tree " + tree);
            long t0 = System.nanoTime();
            job.interrupt(); // how a run cancel reaches a running tool
            long deadline = t0 + TimeUnit.SECONDS.toNanos(5);
            while (tree.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertFalse(tree.stream().anyMatch(ProcessHandle::isAlive), "job tree survived");
            assertTrue(ms < 1000, "job tree gone after " + ms + "ms");
            elapsedMs.add(ms);
            job.join(5000);
        }
        List<Long> sorted = new ArrayList<>(elapsedMs);
        Collections.sort(sorted);
        System.out.println("[killGraceMs=100] cancel->tree-gone ms: " + elapsedMs
                + " min=" + sorted.get(0) + " median=" + sorted.get(sorted.size() / 2)
                + " max=" + sorted.get(sorted.size() - 1));
    }

    // ---------------- §5 edit diffs ----------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> files(ToolResult r) {
        return (List<Map<String, Object>>) r.metadata().get("files");
    }

    @Test
    void editReportsOneModifiedFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "one\ntwo\nthree\n");
        ToolResult r = builtin(Map.of("baseDir", dir.toString()), "edit")
                .execute(Map.of("path", "a.txt", "oldString", "two", "newString", "TWO"), new ToolContext());
        assertFalse(r.isError(), r.output());
        assertEquals("Edited a.txt (1 replacement)", r.output());
        assertEquals(List.of(Map.of("path", "a.txt", "status", "modified", "additions", 1, "deletions", 1)), files(r));
        String diff = (String) r.metadata().get("diff");
        assertTrue(diff.contains("--- a/a.txt\n+++ b/a.txt\n"), diff);
        assertTrue(diff.contains("\n-two\n"), diff);
        assertTrue(diff.contains("\n+TWO\n"), diff);
        assertTrue(diff.contains("@@ -1,3 +1,3 @@"), diff);
    }

    @Test
    void writeOfNewFileIsAdded(@TempDir Path dir) throws Exception {
        ToolResult r = builtin(Map.of("baseDir", dir.toString()), "write")
                .execute(Map.of("path", "sub/new.txt", "content", "a\nb\nc\n"), new ToolContext());
        assertFalse(r.isError(), r.output());
        assertEquals(List.of(Map.of("path", "sub/new.txt", "status", "added", "additions", 3, "deletions", 0)), files(r));
        String diff = (String) r.metadata().get("diff");
        assertTrue(diff.startsWith("--- /dev/null\n+++ b/sub/new.txt\n@@ -0,0 +1,3 @@\n+a\n+b\n+c\n"), diff);
        assertEquals(6, r.metadata().get("bytes"));
    }

    @Test
    void applyPatchAddAndDelete(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("old.txt"), "x\ny\n");
        String patch = "*** Begin Patch\n*** Add File: added.txt\n+hello\n+world\n*** Delete File: old.txt\n*** End Patch";
        ToolResult r = builtin(Map.of("baseDir", dir.toString()), "apply_patch")
                .execute(Map.of("patchText", patch), new ToolContext());
        assertFalse(r.isError(), r.output());
        assertEquals(List.of(
                Map.of("path", "added.txt", "status", "added", "additions", 2, "deletions", 0),
                Map.of("path", "old.txt", "status", "deleted", "additions", 0, "deletions", 2)), files(r));
        String diff = (String) r.metadata().get("diff");
        assertTrue(diff.contains("+++ b/added.txt"), diff);
        assertTrue(diff.contains("--- a/old.txt\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-x\n-y\n"), diff);
    }
}
