package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change add-go-host-asks (ask #7), Java port. Mirrors {@code golang/host_builtin_extras_test.go}:
 * background shells, websearch, notebook_edit, MCP resource tools, grep context, read/grep
 * truncation metadata.
 */
class HostBuiltinExtrasTest {

    static Tool extra(Map<String, Object> cfg, String name) {
        return BuiltinTools.create(cfg).stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("builtin " + name + " not present"));
    }

    static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }

    @Test
    void optInBuiltinsAbsentByDefault() {
        for (Tool t : BuiltinTools.create(Map.of())) {
            assertFalse(List.of("bash_output", "kill_shell", "websearch", "notebook_edit").contains(t.name()),
                    t.name() + " must be opt-in");
            if (t.name().equals("bash")) {
                assertFalse(((Map<?, ?>) t.inputSchema().get("properties")).containsKey("run_in_background"));
            }
        }
        assertEquals(10, BuiltinTools.create(Map.of()).size());
    }

    @Test
    void backgroundShellLifecycle() throws Exception {
        Assumptions.assumeFalse(windows(), "posix");
        try (BackgroundShells bg = new BackgroundShells()) {
            Map<String, Object> cfg = Map.of("background", bg, "killGraceMs", 100);
            Tool bash = extra(cfg, "bash"), out = extra(cfg, "bash_output"), kill = extra(cfg, "kill_shell");

            ToolResult r = bash.execute(args("command", "echo one; sleep 0.2; echo two", "run_in_background", true), null);
            String id = (String) r.metadata().get("backgroundId");
            assertFalse(r.isError(), r.output());
            assertNotNull(id);
            long deadline = System.currentTimeMillis() + 5000;
            StringBuilder all = new StringBuilder();
            while (true) {
                ToolResult o = out.execute(args("id", id), null);
                all.append(o.output().split("\n\\[status:", 2)[0]);
                if ("exited".equals(o.metadata().get("status"))) {
                    assertEquals(0, o.metadata().get("exitCode"), o.metadata().toString());
                    break;
                }
                assertTrue(System.currentTimeMillis() < deadline, "never exited");
                Thread.sleep(50);
            }
            String s = all.toString();
            assertEquals(1, s.split("one", -1).length - 1, "incremental output repeated or lost: " + s);
            assertEquals(1, s.split("two", -1).length - 1, "incremental output repeated or lost: " + s);

            // a stubborn command with a child, killed as a tree
            r = bash.execute(args("command", "trap '' TERM; sleep 30 & wait", "run_in_background", true), null);
            String id2 = (String) r.metadata().get("backgroundId");
            Thread.sleep(100);
            long t0 = System.currentTimeMillis();
            ToolResult k = kill.execute(args("id", id2), null);
            assertFalse(k.isError(), k.output());
            assertEquals(true, k.metadata().get("killed"));
            assertTrue(System.currentTimeMillis() - t0 < 2000, "kill took too long");
            ToolResult o = out.execute(args("id", id2), null);
            assertEquals("killed", o.metadata().get("status"), o.metadata().toString());
            assertEquals(false, kill.execute(args("id", id2), null).metadata().get("killed"));
            assertTrue(out.execute(args("id", "nope"), null).isError(), "unknown id must be an error");
            assertEquals(2, bg.list().size());
        }
    }

    @Test
    void webSearchProvider() {
        String[] gotQ = new String[1];
        int[] gotN = new int[1];
        WebSearch provider = (q, n) -> {
            gotQ[0] = q;
            gotN[0] = n;
            return List.of(new WebSearch.Result("A", "https://a.example", "sa"),
                    new WebSearch.Result("B", "https://b.example", ""));
        };
        Tool ws = extra(Map.of("webSearch", provider), "websearch");
        assertTrue(ws.hints().readOnly() && ws.hints().openWorld());
        ToolResult r = ws.execute(args("query", "go", "limit", 1), null);
        assertEquals("go", gotQ[0]);
        assertEquals(1, gotN[0]);
        assertEquals("1. A\n   https://a.example\n   sa", r.output());
    }

    @Test
    @SuppressWarnings("unchecked")
    void notebookEdit(@TempDir Path dir) throws Exception {
        String nb = "{\"cells\":[{\"cell_type\":\"code\",\"id\":\"a\",\"metadata\":{},\"source\":[\"x = 1\\n\"],"
                + "\"outputs\":[{\"output_type\":\"stream\",\"text\":[\"1\"]}],\"execution_count\":3},"
                + "{\"cell_type\":\"markdown\",\"id\":\"b\",\"metadata\":{},\"source\":[\"# T\"]}],"
                + "\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}";
        Path p = dir.resolve("n.ipynb");
        Files.writeString(p, nb);
        Tool ne = extra(Map.of("notebookEdit", true, "baseDir", dir.toString()), "notebook_edit");
        java.util.function.Supplier<List<Object>> load = () -> {
            try {
                return (List<Object>) Json.toMap(Files.readString(p)).get("cells");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ToolResult r = ne.execute(args("path", "n.ipynb", "cell_id", "a", "new_source", "x = 2\ny = 3"), null);
        assertFalse(r.isError(), r.output());
        Map<String, Object> c0 = (Map<String, Object>) load.get().get(0);
        assertEquals(0, ((List<?>) c0.get("outputs")).size());
        assertTrue(c0.containsKey("execution_count") && c0.get("execution_count") == null);
        assertEquals(List.of("x = 2\n", "y = 3"), c0.get("source"));

        r = ne.execute(args("path", "n.ipynb", "cell_index", 0, "edit_mode", "insert", "cell_type", "markdown", "new_source", "hi"), null);
        assertFalse(r.isError(), r.output());
        List<Object> cells = load.get();
        assertEquals(3, cells.size());
        assertEquals("markdown", ((Map<String, Object>) cells.get(1)).get("cell_type"));

        r = ne.execute(args("path", "n.ipynb", "cell_id", "b", "edit_mode", "delete"), null);
        assertFalse(r.isError(), r.output());
        assertEquals(2, load.get().size());
        assertNotNull(r.metadata().get("files"));
        assertTrue(ne.execute(args("path", "n.ipynb", "cell_id", "zz"), null).isError(), "unknown cell id must be an error");
    }

    @Test
    void grepContextAndTruncation(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("f.txt"), "a\nb\nHIT1\nc\nd\ne\nf\nHIT2\ng\n");
        Tool grep = extra(Map.of("baseDir", dir.toString()), "grep");
        ToolResult r = grep.execute(args("pattern", "HIT", "context", 1), null);
        assertEquals("f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g", r.output());
        r = grep.execute(args("pattern", "HIT", "before", 0, "after", 4), null);
        assertEquals("f.txt:3:HIT1\nf.txt-4-c\nf.txt-5-d\nf.txt-6-e\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g", r.output());
        r = grep.execute(args("pattern", "HIT", "limit", 1), null);
        assertEquals("f.txt:3:HIT1", r.output());
        assertEquals(true, r.metadata().get("truncated"));
        assertEquals(2, r.metadata().get("total"));
        // no context asked ⇒ the pinned bytes, no truncation keys
        r = grep.execute(args("pattern", "HIT"), null);
        assertEquals("f.txt:3:HIT1\nf.txt:8:HIT2", r.output());
        assertFalse(r.metadata().containsKey("truncated"));

        Tool read = extra(Map.of("baseDir", dir.toString()), "read");
        r = read.execute(args("path", "f.txt", "limit", 2), null);
        assertEquals("a\nb", r.output());
        assertEquals(true, r.metadata().get("truncated"));
        assertEquals(9, r.metadata().get("totalLines"));
        assertEquals(3, r.metadata().get("nextOffset"));
        r = read.execute(args("path", "f.txt", "offset", 8), null);
        assertEquals("HIT2\ng\n", r.output());
        assertTrue(r.metadata() == null || !r.metadata().containsKey("truncated"));
    }

    @Test
    void mcpResourceTools() {
        Assumptions.assumeTrue(HostEngineAsksTest.nodeAvailable(), "node not on PATH");
        String fixture = TestFixtures.fixture("mcp-progress-server.mjs");
        String cfg = "{\"mcpServers\":{\"progress\":{\"command\":[\"node\"," + Json.stringify(fixture) + "],\"timeout\":5000}}}";
        try (McpSource src = McpSource.load(cfg)) {
            List<Tool> tools = src.resourceTools();
            Tool list = tools.get(0), read = tools.get(1);
            ToolResult r = list.execute(Map.of(), null);
            assertFalse(r.isError(), r.output());
            assertEquals("progress fixture://hello — hello (text/plain): A fixed text resource.", r.output());
            r = read.execute(Map.of("server", "progress", "uri", "fixture://hello"), null);
            assertFalse(r.isError(), r.output());
            assertEquals("hello resource", r.output());
            assertTrue(read.execute(Map.of("server", "progress", "uri", "fixture://nope"), null).isError(),
                    "missing resource must be an error");
            assertTrue(read.execute(Map.of("server", "nope", "uri", "x"), null).isError(), "unknown server must be an error");
            assertTrue(list.hints().readOnly() && read.hints().readOnly(), "resource tools are read-only");
        }
    }
}
