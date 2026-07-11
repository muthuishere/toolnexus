package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors the JS builtin-tool tests ({@code js/test/unit.test.ts}). Hermetic:
 * temp dirs via {@link Files#createTempDirectory}, an in-process
 * {@link HttpServer} for webfetch, no network / no LLM.
 */
class BuiltinToolsTest {

    private static final List<String> BUILTIN_NAMES = List.of(
            "bash", "read", "write", "edit", "grep", "glob",
            "webfetch", "question", "apply_patch", "todowrite");

    private static Tool tool(String name) {
        for (Tool t : BuiltinTools.create()) {
            if (t.name().equals(name)) return t;
        }
        throw new IllegalStateException("no builtin named " + name);
    }

    private static ToolResult run(String name, Map<String, Object> args) {
        return tool(name).execute(args, null);
    }

    // ---- toggle -----------------------------------------------------------

    @Test
    void builtinsEnabledDefaultOnBooleanAndObjectPrecedence() {
        assertTrue(BuiltinTools.enabled(null));
        assertTrue(BuiltinTools.enabled(true));
        assertFalse(BuiltinTools.enabled(false));
        assertTrue(BuiltinTools.enabled(Map.of()));
        assertFalse(BuiltinTools.enabled(Map.of("enabled", false)));
        assertFalse(BuiltinTools.enabled(Map.of("disabled", true)));
        // disabled:true wins regardless of enabled
        assertFalse(BuiltinTools.enabled(Map.of("enabled", true, "disabled", true)));
    }

    @Test
    void defaultAssemblyIncludesAll10BuiltinsEachSourceBuiltin() {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options())) {
            for (String name : BUILTIN_NAMES) {
                Tool t = tk.get(name);
                assertNotNull(t, "builtin " + name + " present");
                assertEquals("builtin", t.source(), name + " source");
            }
            // and they appear in the provider schema arrays
            List<String> names = new ArrayList<>();
            for (Map<String, Object> f : tk.toOpenAI()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) f.get("function");
                names.add((String) fn.get("name"));
            }
            for (String name : BUILTIN_NAMES) assertTrue(names.contains(name), name + " in toOpenAI");
        }
    }

    @Test
    void builtinsToggleOffRemovesAll10ButKeepsSkillAndExtras() {
        List<Object> offs = new ArrayList<>();
        offs.add(false);
        offs.add(Map.of("disabled", true));
        offs.add(Map.of("enabled", false));
        for (Object off : offs) {
            Tool extra = NativeTool.of("myextra", "", null, a -> "x");
            Toolkit.Options opts = new Toolkit.Options()
                    .skillsDir(TestFixtures.skillsDir())
                    .extraTools(extra)
                    .builtins(off);
            try (Toolkit tk = Toolkit.create(opts)) {
                for (String name : BUILTIN_NAMES) {
                    assertNull(tk.get(name), name + " absent when off=" + off);
                }
                assertNotNull(tk.get("skill"), "skill tool unaffected");
                assertNotNull(tk.get("myextra"), "extra tool unaffected");
            }
        }
    }

    @Test
    void builtinsToolsMapDisablesNamedToolsOnlyUnknownIgnored() {
        Toolkit.Options opts = new Toolkit.Options()
                .builtins(Map.of("tools", Map.of("bash", false, "write", false, "nope", false)));
        try (Toolkit tk = Toolkit.create(opts)) {
            assertNull(tk.get("bash"), "bash disabled");
            assertNull(tk.get("write"), "write disabled");
            for (String name : BUILTIN_NAMES) {
                if (name.equals("bash") || name.equals("write")) continue;
                assertNotNull(tk.get(name), name + " still present");
            }
        }
    }

    @Test
    void wholeSourceOffOverridesThePerToolMap() {
        Toolkit.Options opts = new Toolkit.Options()
                .builtins(Map.of("disabled", true, "tools", Map.of("read", true)));
        try (Toolkit tk = Toolkit.create(opts)) {
            for (String name : BUILTIN_NAMES) {
                assertNull(tk.get(name), name + " absent (source off wins)");
            }
        }
    }

    @Test
    void extraToolNamedReadShadowsBuiltin() {
        Tool mine = NativeTool.of("read", "host read", null, a -> "HOST_READ");
        Toolkit.Options opts = new Toolkit.Options().extraTools(mine);
        try (Toolkit tk = Toolkit.create(opts)) {
            Tool r = tk.get("read");
            assertNotNull(r);
            assertEquals("native", r.source(), "host tool wins the collision");
            ToolResult out = tk.execute("read", Map.of());
            assertEquals("HOST_READ", out.output());
        }
    }

    // ---- read / write / edit ---------------------------------------------

    @Test
    void readWriteEditRoundTripReplaceAllAndErrorCases() throws IOException {
        Path dir = Files.createTempDirectory("tn-builtin-");
        try {
            String file = dir.resolve("note.txt").toString();
            ToolResult w = run("write", Map.of("path", file, "content", "alpha\nbeta\nalpha\n"));
            assertFalse(w.isError());
            assertTrue(w.output().matches("Wrote \\d+ bytes to .*"));

            ToolResult r = run("read", Map.of("path", file));
            assertEquals("alpha\nbeta\nalpha\n", r.output());

            // offset/limit window (1-based)
            ToolResult win = run("read", Map.of("path", file, "offset", 2, "limit", 1));
            assertEquals("beta", win.output());

            // edit non-unique without replaceAll => error, no write
            ToolResult dup = run("edit", Map.of("path", file, "oldString", "alpha", "newString", "X"));
            assertTrue(dup.isError());
            assertTrue(dup.output().contains("not unique"));
            assertEquals("alpha\nbeta\nalpha\n", Files.readString(Path.of(file)), "no partial write");

            // edit not-found => error
            ToolResult nf = run("edit", Map.of("path", file, "oldString", "zzz", "newString", "X"));
            assertTrue(nf.isError());
            assertTrue(nf.output().contains("not found"));

            // edit replaceAll
            ToolResult ra = run("edit", Map.of(
                    "path", file, "oldString", "alpha", "newString", "OMEGA", "replaceAll", true));
            assertFalse(ra.isError());
            assertEquals("OMEGA\nbeta\nOMEGA\n", Files.readString(Path.of(file)));

            // edit single unique replace
            ToolResult one = run("edit", Map.of("path", file, "oldString", "beta", "newString", "gamma"));
            assertFalse(one.isError());
            assertEquals("OMEGA\ngamma\nOMEGA\n", Files.readString(Path.of(file)));

            // read missing => error
            ToolResult miss = run("read", Map.of("path", dir.resolve("nope.txt").toString()));
            assertTrue(miss.isError());
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---- grep / glob ------------------------------------------------------

    @Test
    void grepAndGlobFindExpectedFiles() throws IOException {
        Path dir = Files.createTempDirectory("tn-builtin-");
        try {
            Files.writeString(dir.resolve("a.txt"), "needle here\nplain\n");
            Files.writeString(dir.resolve("b.txt"), "no match\nneedle again\n");
            Files.createDirectory(dir.resolve("sub"));
            Files.writeString(dir.resolve("sub").resolve("c.md"), "needle in md\n");

            ToolResult g = run("grep", Map.of("pattern", "needle", "path", dir.toString()));
            assertFalse(g.isError());
            assertEquals(3, g.metadata().get("count"), "three needle lines across files");

            ToolResult gInc = run("grep", Map.of("pattern", "needle", "path", dir.toString(), "include", "*.txt"));
            assertEquals(2, gInc.metadata().get("count"), "include filters to .txt");

            ToolResult gl = run("glob", Map.of("pattern", "*.txt", "path", dir.toString()));
            assertEquals(2, gl.metadata().get("count"), "two .txt files");
            List<String> got = new ArrayList<>(List.of(gl.output().split("\n")));
            got.sort(null);
            assertEquals(List.of("a.txt", "b.txt"), got);

            ToolResult glMd = run("glob", Map.of("pattern", "*.md", "path", dir.toString()));
            assertEquals(1, glMd.metadata().get("count"));
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---- bash -------------------------------------------------------------

    @Test
    void bashSuccessOutputAndNonZeroExitIsError() throws IOException {
        ToolResult okr = run("bash", Map.of("command", "printf 'hello-bash'"));
        assertFalse(okr.isError());
        assertTrue(okr.output().contains("hello-bash"));

        ToolResult bad = run("bash", Map.of("command", "exit 3"));
        assertTrue(bad.isError());
        assertTrue(bad.output().contains("code 3"));
        assertEquals(3, bad.metadata().get("exitCode"));

        // workdir honored
        Path dir = Files.createTempDirectory("tn-builtin-");
        try {
            ToolResult cwd = run("bash", Map.of("command", "pwd", "workdir", dir.toString()));
            assertFalse(cwd.isError());
            assertTrue(cwd.output().contains(dir.getFileName().toString()),
                    "pwd output: " + cwd.output());
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---- webfetch ---------------------------------------------------------

    @Test
    void webfetchLocalhost() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "fetched-body-content"));
        server.createContext("/err", exchange -> respond(exchange, 500, "boom"));
        server.start();
        int port = server.getAddress().getPort();
        try {
            ToolResult okr = run("webfetch", Map.of(
                    "url", "http://127.0.0.1:" + port + "/ok", "format", "text"));
            assertFalse(okr.isError());
            assertTrue(okr.output().contains("fetched-body-content"));
            assertEquals(200, okr.metadata().get("status"));

            ToolResult bad = run("webfetch", Map.of("url", "http://127.0.0.1:" + port + "/err"));
            assertTrue(bad.isError());
            assertTrue(bad.output().startsWith("HTTP 500"));
        } finally {
            server.stop(0);
        }
    }

    // ---- apply_patch ------------------------------------------------------

    @Test
    void applyPatchAddUpdateDeleteRoundTripAndNonMatchAbortsAtomically() throws IOException {
        Path dir = Files.createTempDirectory("tn-builtin-");
        try {
            String toDelete = dir.resolve("gone.txt").toString();
            String toUpdate = dir.resolve("keep.txt").toString();
            String toAdd = dir.resolve("new.txt").toString();
            Files.writeString(Path.of(toDelete), "bye\n");
            Files.writeString(Path.of(toUpdate), "line one\nline two\nline three\n");

            String patch = String.join("\n",
                    "*** Begin Patch",
                    "*** Add File: " + toAdd,
                    "+created line 1",
                    "+created line 2",
                    "*** Update File: " + toUpdate,
                    "@@",
                    " line one",
                    "-line two",
                    "+line TWO changed",
                    " line three",
                    "*** Delete File: " + toDelete,
                    "*** End Patch",
                    "");

            ToolResult res = run("apply_patch", Map.of("patchText", patch));
            assertFalse(res.isError(), res.output());
            assertEquals("created line 1\ncreated line 2", Files.readString(Path.of(toAdd)));
            assertEquals("line one\nline TWO changed\nline three\n", Files.readString(Path.of(toUpdate)));
            assertFalse(Files.exists(Path.of(toDelete)), "deleted");

            // non-matching hunk => error with no partial write
            String addTwo = dir.resolve("should-not-exist.txt").toString();
            String badPatch = String.join("\n",
                    "*** Begin Patch",
                    "*** Add File: " + addTwo,
                    "+content",
                    "*** Update File: " + toUpdate,
                    "@@",
                    "-DOES NOT MATCH",
                    "+whatever",
                    "*** End Patch");
            ToolResult bad = run("apply_patch", Map.of("patchText", badPatch));
            assertTrue(bad.isError());
            assertTrue(bad.output().contains("does not match"));
            assertFalse(Files.exists(Path.of(addTwo)), "no partial write when a later hunk fails");
            assertEquals("line one\nline TWO changed\nline three\n", Files.readString(Path.of(toUpdate)),
                    "update untouched");
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---- question / todowrite --------------------------------------------

    @Test
    void questionSuspendsViaKindQuestionRequestResolvesToTheAnswer() {
        Map<String, Object> q1 = new LinkedHashMap<>();
        q1.put("question", "Pick a color?");
        q1.put("header", "Choice");
        q1.put("options", List.of("red", "green"));
        q1.put("multiple", false);
        Map<String, Object> q2 = new LinkedHashMap<>();
        q2.put("question", "Confirm?");
        List<Map<String, Object>> questions = List.of(q1, q2);

        // A — first call suspends (§10), it does NOT answer immediately.
        ToolResult q = run("question", Map.of("questions", questions));
        Request req = ToolResult.pendingOf(q);
        assertNotNull(req, "returns a suspension carrying metadata.pending");
        assertTrue(q.isError(), "a suspension is not a usable answer");
        assertEquals("question", req.kind());
        assertEquals("Pick a color? (options: red, green)\nConfirm?", req.prompt(), "byte-exact rendered prompt");
        assertEquals(questions, req.data().get("questions"), "structured questions ride in data.questions");

        // B — re-executed after waitFor resolved: the answer is forwarded verbatim.
        Answer answer = new Answer(req.id(), true, Map.of("answers", List.of("red")));
        ToolContext ctx = new ToolContext(null, null, answer);
        ToolResult answered = tool("question").execute(Map.of("questions", questions), ctx);
        assertFalse(answered.isError());
        assertEquals("{\"answers\":[\"red\"]}", answered.output());

        // ok-but-empty resolution → "{}" (agnostic passthrough).
        ToolContext emptyCtx = new ToolContext(null, null, new Answer(req.id(), true));
        ToolResult empty = tool("question").execute(Map.of("questions", questions), emptyCtx);
        assertEquals("{}", empty.output());
    }

    @Test
    void todowriteStructuredRoundTrip() {
        Map<String, Object> t1 = new LinkedHashMap<>();
        t1.put("id", "1");
        t1.put("text", "write code");
        t1.put("completed", true);
        Map<String, Object> t2 = new LinkedHashMap<>();
        t2.put("id", "2");
        t2.put("text", "ship it");
        t2.put("completed", false);
        List<Map<String, Object>> todos = List.of(t1, t2);

        ToolResult t = run("todowrite", Map.of("todos", todos));
        assertFalse(t.isError());
        assertEquals(todos, t.metadata().get("todos"));
        assertTrue(t.output().contains("[x] write code"));
        assertTrue(t.output().contains("[ ] ship it"));
    }

    // ---- skills preamble --------------------------------------------------

    @Test
    void skillsPromptPreamblePresentWithSkillsAbsentWithout() throws IOException {
        SkillSource withSkills = SkillSource.load(TestFixtures.skillsDir());
        String p = withSkills.prompt();
        assertTrue(p.startsWith(SkillSource.SKILLS_PROMPT_PREAMBLE), "starts with the exact preamble");

        List<String> rows = new ArrayList<>();
        withSkills.skills().values().stream()
                .filter(s -> s.description != null)
                .sorted((a, b) -> a.name.compareTo(b.name))
                .forEach(s -> rows.add("- **" + s.name + "**: " + s.description));
        String expected = SkillSource.SKILLS_PROMPT_PREAMBLE + "\n\n## Available Skills\n"
                + String.join("\n", rows);
        assertEquals(expected, p);

        Path empty = Files.createTempDirectory("tn-skills-empty-");
        try {
            SkillSource noSkills = SkillSource.load(empty.toString());
            assertEquals("No skills are currently available.", noSkills.prompt());
            assertFalse(noSkills.prompt().startsWith(SkillSource.SKILLS_PROMPT_PREAMBLE));
        } finally {
            deleteRecursively(empty);
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "text/plain");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
