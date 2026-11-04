package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hermetic tests for the CLI client (SPEC §8C), mirroring {@code golang/cli_test.go}: no network,
 * no real agent CLI. {@link CliFakes} plays the fake ACP agent, the fake one-shot CLI and the
 * stdio MCP bridge, launched through a shell script so the client sees one command, like a real binary.
 */
class CliClientTest {

    static Path fake;
    @TempDir Path tmp;
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @BeforeAll
    static void launcher() throws IOException {
        Path dir = Files.createTempDirectory("toolnexus-clifake-");
        fake = dir.resolve("fakecli");
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Files.writeString(fake, "#!/bin/sh\nexec '" + java + "' -Xshare:auto -XX:TieredStopAtLevel=1 -cp '"
                + System.getProperty("java.class.path") + "' " + CliFakes.class.getName() + " \"$@\"\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        dir.toFile().deleteOnExit();
        fake.toFile().deleteOnExit();
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : cleanup) c.close();
    }

    private Toolkit badgeToolkit() {
        Tool badge = NativeTool.of("get_badge_number", "Returns the badge number.",
                Map.of("type", "object", "properties", Map.of()), args -> "BADGE-77Q");
        Toolkit tk = Toolkit.create(new Toolkit.Options().extraTools(badge).builtins(false)
                .skillsDir(TestFixtures.skillsDir()));
        cleanup.add(tk::close);
        return tk;
    }

    private static CliClient.Options fakeCliOpts(String mode, Object... extra) {
        Map<String, String> env = new HashMap<>();
        env.put("TN_FAKE_CLI", mode);
        return new CliClient.Options().style(CliStyles.CUSTOM).command(fake.toString())
                .args(new ArrayList<>(List.of(extra))).prompt("stdin").answer("stdout").env(env)
                .transport(CliTransports.CLI);
    }

    private CliClient client(CliClient.Options o) {
        CliClient c = CliClient.create(o);
        cleanup.add(c);
        return c;
    }

    private LlmClient.RunResult runCli(CliClient.Options o, Toolkit tk) {
        return client(o).run("What is my badge number?", tk);
    }

    private static String errorOf(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            StringBuilder sb = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) sb.append(t.getMessage()).append(" <- ");
            return sb.toString();
        }
        return null;
    }

    // ---- shared data -------------------------------------------------------------------

    @Test
    void stylesMatchConformance() throws IOException {
        Path dir = Path.of(TestFixtures.fixture("")).getParent().resolve("conformance/cli-styles");
        int n = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) || name.equals("README.md")) continue;
                assertEquals(Files.readString(p), CliClient.resource(name),
                        "java/src/main/resources/.../clistyles/" + name + " differs from conformance/cli-styles (copy it)");
                n++;
            }
        }
        assertEquals(8, n, "expected 7 styles + preamble");
        assertEquals(CliClient.resource("preamble.txt"), CliClient.PREAMBLE);
        for (CliStyles s : CliStyles.values()) {
            if (s == CliStyles.CUSTOM) {
                assertNull(CliClient.styleDef(s));
                continue;
            }
            CliClient.StyleDef d = CliClient.styleDef(s);
            assertEquals(s.fileName(), d.name);
            assertNotNull(d.verified, s + " has no verified note");
            CliClient.create(new CliClient.Options().style(s).command("x").transport(CliTransports.CLI));
        }
        assertNull(CliClient.styleDef(CliStyles.CODEX).acp, "codex must have no ACP launch");
        assertEquals(".mcp.json", CliClient.styleDef(CliStyles.DEVIN).acp.projectMcpFile);
        assertTrue(CliClient.styleDef(CliStyles.GOOSE).verified.startsWith("UNVERIFIED"));
    }

    // ---- construction errors -----------------------------------------------------------

    @Test
    void constructionErrors() {
        record C(CliClient.Options o, String want) {}
        List<C> cases = List.of(
                new C(new CliClient.Options().style(CliStyles.CLAUDE), "cli: Command is required (path or name of the CLI binary)"),
                new C(new CliClient.Options().command("x"), "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"),
                new C(new CliClient.Options().style(CliStyles.GOOSE).command("goose").model("m").transport(CliTransports.CLI),
                        "cli: style \"goose\" takes no model"),
                new C(new CliClient.Options().style(CliStyles.CODEX).command("codex").transport(CliTransports.ACP),
                        "cli: style \"codex\" has no ACP launch; set ACPCommand or use CliTransports.CLI"),
                new C(custom(List.of("{{model}}"), "stdin", "stdout"), "cli: the style needs a model: {{model}} is outside an optional group"),
                new C(custom(List.of("{{nope}}"), "stdin", "stdout"), "cli: unknown placeholder {{nope}}"),
                new C(custom(null, "arg", "stdout"), "cli: prompt \"arg\" needs {{prompt}} in args"),
                new C(custom(null, "file", "stdout"), "cli: prompt \"file\" needs {{file}} in args"),
                new C(custom(null, "stdin", "out"), "cli: answer \"out\" needs {{out}} in args"),
                new C(custom(null, "pipe", "stdout"), "cli: prompt must be stdin, arg or file, not \"pipe\""),
                new C(custom(null, "stdin", "json"), "cli: answer must be stdout or out, not \"json\""));
        for (C c : cases) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CliClient.create(c.o()));
            assertEquals(c.want(), e.getMessage());
        }
    }

    private static CliClient.Options custom(List<Object> args, String prompt, String answer) {
        return new CliClient.Options().style(CliStyles.CUSTOM).command("x").args(args).prompt(prompt).answer(answer);
    }

    // ---- the envelope parser -------------------------------------------------------------

    @Test
    void parseReply() {
        String[][] cases = {
            {"{\"content\":\"hi\"}", "hi", ""},
            {"```json\n{\"content\":\"fenced\"}\n```\n\n", "fenced", ""},
            {"Sure! {\"content\":\"wrapped\"} done", "wrapped", ""},
            {"{\"choices\":[{\"message\":{\"content\":\"openai\"}}]}", "openai", ""},
            {"{\"message\":{\"content\":\"msg\"}}", "msg", ""},
            {"{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"t\",\"arguments\":\"{\\\"x\\\":1}\"}}]}", "", "t"},
            {"{\"tool_calls\":[{\"name\":\"flat\",\"arguments\":{\"x\":1}}]}", "", "flat"},
            {"plain text answer", "plain text answer", ""},
            {"{\"content\":\"x\"} trailing {", "x", ""},
            {"{\"answer\":1}", "{\"answer\":1}", ""},
            {"{\"content\":\"x\"} {\"content\":\"y\"}", "{\"content\":\"x\"} {\"content\":\"y\"}", ""},
        };
        for (String[] c : cases) {
            InProcess.Response r = CliClient.parseReply(c[0]);
            if (!c[2].isEmpty()) {
                assertEquals(1, r.toolCalls.size(), c[0]);
                assertEquals(c[2], r.toolCalls.get(0).name);
                continue;
            }
            assertEquals(c[1], r.content, c[0]);
            assertTrue(r.toolCalls == null || r.toolCalls.isEmpty(), c[0]);
        }
        assertTrue(CliClient.renderPrompt(null, null)
                .startsWith(CliClient.PREAMBLE + "\nREQUEST:\n{\"messages\":[],\"tools\":[]}"));
    }

    // ---- one-shot transport --------------------------------------------------------------

    @Test
    void oneShotToolRoundTrip() throws IOException {
        Path rec = tmp.resolve("rec");
        CliClient.Options o = fakeCliOpts("tool");
        o.env.put("TN_FAKE_REC", rec.toString());
        o.systemPrompt("You are a badge clerk.");
        LlmClient.RunResult res = runCli(o, badgeToolkit());
        assertTrue(res.text.contains("BADGE-77Q"), res.text);
        assertEquals(1, res.toolCalls.size());
        assertEquals("get_badge_number", res.toolCalls.get(0).name);
        String first = Files.readString(rec).split("\n=====\n")[0];
        assertTrue(first.startsWith(CliClient.PREAMBLE + "\nREQUEST:\n"), "prompt must start with the preamble");
        Map<String, Object> req = Json.toMap(first.split("\nREQUEST:\n", 2)[1]);
        @SuppressWarnings("unchecked")
        Map<String, Object> m0 = (Map<String, Object>) ((List<Object>) req.get("messages")).get(0);
        assertEquals("system", m0.get("role"));
        assertTrue(String.valueOf(m0.get("content")).contains("badge clerk"));
        assertFalse(((List<?>) req.get("tools")).isEmpty());
    }

    @Test
    void promptModesAndOutFile() {
        Toolkit tk = badgeToolkit();
        Object[][] modes = {
            {List.of("--p", "{{prompt}}"), "arg", "stdout"},
            {List.of("--f", "{{file}}"), "file", "stdout"},
            {List.of("--o", "{{out}}"), "stdin", "out"},
        };
        for (Object[] m : modes) {
            @SuppressWarnings("unchecked")
            CliClient.Options o = fakeCliOpts("tool").args(new ArrayList<>((List<Object>) m[0]))
                    .prompt((String) m[1]).answer((String) m[2]);
            LlmClient.RunResult res = runCli(o, tk);
            assertTrue(res.text.contains("BADGE-77Q"), m[1] + "/" + m[2] + ": " + res.text);
        }
    }

    @Test
    void modelGroup() {
        CliClient.Options o = fakeCliOpts("x", "--argv", "-q");
        o.args.add(List.of("--model", "{{model}}"));
        LlmClient.RunResult res = runCli(o, null);
        assertFalse(res.text.contains("--model"), "no model ⇒ group dropped: " + res.text);
        o.model("prov/m-1");
        res = runCli(o, null);
        assertTrue(res.text.contains("--model prov/m-1"), res.text);
    }

    @Test
    void pinnedErrors() {
        CliClient.Options noOut = fakeCliOpts("nooutfile", "--x", "{{out}}").answer("out");
        CliClient.Options sleep = fakeCliOpts("sleep").callTimeoutMs(300);
        CliClient.Options missing = fakeCliOpts("x").command("definitely-not-a-cli-xyz");
        Object[][] cases = {
            {fakeCliOpts("exit"), "cli: exit 3: boom: bad login"},
            {fakeCliOpts("empty"), "cli: empty reply"},
            {noOut, "cli: no output file"},
            {sleep, "cli: timed out after 300ms"},
            {missing, "cli: command not found: definitely-not-a-cli-xyz"},
        };
        for (Object[] c : cases) {
            String err = errorOf(() -> runCli((CliClient.Options) c[0], null));
            assertNotNull(err, "want " + c[1]);
            assertTrue(err.contains((String) c[1]), "got " + err + ", want " + c[1]);
        }
    }

    @Test
    void tempFilesRemovedOnEveryPath() throws IOException {
        CliClient c = client(fakeCliOpts("exit", "--f", "{{file}}", "--o", "{{out}}").prompt("file").answer("out"));
        CliClient.Argv a = c.argv("PROMPT");
        assertEquals(2, a.temps().size());
        assertEquals("PROMPT", Files.readString(Path.of(a.temps().get(0))));
        for (String f : a.temps()) Files.deleteIfExists(Path.of(f));
        Set<String> before = Set.copyOf(listTemp());
        assertNotNull(errorOf(() -> c.run("hi")));
        Set<String> after = Set.copyOf(listTemp());
        assertTrue(before.containsAll(after), "temp files left behind: " + after);
    }

    private static List<String> listTemp() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("toolnexus-cli-")).toList();
        }
    }

    @Test
    void oneShotRefusesStreaming() {
        CliClient c = client(fakeCliOpts("x"));
        String err = errorOf(() -> c.stream("hi", null, ev -> { }));
        assertNotNull(err);
        assertTrue(err.contains("cli: streaming is not supported by the one-shot CLI transport; use Run"), err);
    }

    @Test
    void envExpandsAtCallTime() {
        Map<String, String> out = CliClient.expandEnv(Map.of("A", "x-${PATH}-y", "B", "${TN_DEFINITELY_UNSET_VAR}"));
        assertEquals("x-" + System.getenv("PATH") + "-y", out.get("A"));
        assertEquals("", out.get("B"));
    }

    // ---- ACP transport -------------------------------------------------------------------

    private record Acp(CliClient.Options opts, Path rec) {}

    private Acp fakeAcpOpts(Map<String, String> extraEnv) {
        Path rec = tmp.resolve("acprec-" + System.nanoTime());
        Map<String, String> env = new HashMap<>();
        env.put("TN_FAKE_ACP", "1");
        env.put("TN_FAKE_REC", rec.toString());
        env.putAll(extraEnv);
        CliClient.StyleDef d = new CliClient.StyleDef();
        d.name = "fake";
        d.args = new ArrayList<>();
        d.prompt = "stdin";
        d.answer = "stdout";
        d.acp = new CliClient.AcpLaunch();
        d.acp.args = List.of("acp");
        CliClient.Options o = new CliClient.Options().styleDef(d).command(fake.toString()).env(env)
                .toolkit(badgeToolkit()).bridgeCommand(List.of(fake.toString(), "mcp-bridge"))
                .callTimeoutMs(60_000);
        return new Acp(o, rec);
    }

    private static void assertFakeAcpRun(CliClient c, LlmClient.RunResult res, String entry) {
        assertEquals("acp", c.transport(), c.fallbackReason());
        for (String want : List.of("perm ours=yes own=no fake=no again=yes", "entry=" + entry, "BADGE-77Q", "Hello")) {
            assertTrue(res.text.contains(want), "missing " + want + " in " + res.text);
        }
        assertTrue(res.text.contains("\"name\":\"load_skill\"") && !res.text.contains("\"name\":\"skill\""),
                "skill must be served as load_skill, never skill");
        CliAcpSession.Info info = c.acpSession().orElseThrow();
        assertEquals(2, info.servedCalls().size(), "server must record the executed tools: " + info);
    }

    @Test
    void acpHttpEntryWithBearerToken() throws IOException {
        Acp a = fakeAcpOpts(Map.of("FAKE_HTTP", "1"));
        CliClient c = client(a.opts());
        LlmClient.RunResult res = c.run("badge?");
        assertFakeAcpRun(c, res, "http");
        assertTrue(res.text.contains("noauth=401"), "endpoint must refuse a request without the bearer token: " + res.text);
        String rec = Files.readString(a.rec());
        assertTrue(rec.contains("\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer "), rec);
        assertFalse(Json.stringify(c.acpSession().orElseThrow().mcpServersSent()).contains("Bearer "),
                "acpSession must redact the token");
    }

    @Test
    void acpStdioBridgeWhenAgentLacksHttp() throws IOException {
        Acp a = fakeAcpOpts(Map.of("FAKE_HTTP", "0"));
        CliClient c = client(a.opts());
        LlmClient.RunResult res = c.run("badge?");
        assertFakeAcpRun(c, res, "<nil>");
        String rec = Files.readString(a.rec());
        assertTrue(rec.contains("\"env\":[{\"name\":\"TOOLNEXUS_MCP_TOKEN\"") && !rec.contains("\"type\":\"stdio\""), rec);
    }

    @Test
    void acpProjectMcpFile() throws Exception {
        Acp a = fakeAcpOpts(Map.of("FAKE_HTTP", "0", "FAKE_PROJECT", "1"));
        a.opts().styleDef.acp.projectMcpFile = ".mcp.json";
        CliClient c = CliClient.create(a.opts());
        LlmClient.RunResult res = c.run("badge?");
        assertFakeAcpRun(c, res, "http");
        CliAcpSession.Info info = c.acpSession().orElseThrow();
        assertTrue(info.mcpServersSent().isEmpty() && !info.sessionCwd().isEmpty(), info.toString());
        Path f = Path.of(info.sessionCwd(), ".mcp.json");
        assertTrue(Files.exists(f));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        c.close();
        assertFalse(Files.exists(Path.of(info.sessionCwd())), "temp session dir must be removed on close");
        assertThrows(IOException.class, () -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(info.url())).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()), "served endpoint must stop with the session");
    }

    @Test
    void acpModelPassThrough() throws IOException {
        Acp a = fakeAcpOpts(Map.of("FAKE_HTTP", "1", "FAKE_NO_CONFIG", "1"));
        a.opts().model("anthropic/claude-x").transport(CliTransports.ACP); // the fake style's one-shot args take no model
        CliClient c = client(a.opts());
        c.run("badge?");
        String s = Files.readString(a.rec());
        assertTrue(s.contains("session/set_config_option {\"sessionId\":\"s1\",\"configId\":\"model\",\"value\":\"anthropic/claude-x\"}"), s);
        assertTrue(s.contains("session/set_model {\"sessionId\":\"s1\",\"modelId\":\"anthropic/claude-x\"}"), s);
        assertTrue(s.contains("\"terminal\":false"), "terminal capability must be declared false");
    }

    @Test
    void acpFallsBackToOneShot() {
        Acp a = fakeAcpOpts(Map.of("FAKE_FAIL_INIT", "1", "TN_FAKE_CLI", "tool"));
        CliClient c = client(a.opts());
        assertEquals("auto", c.transport(), "undecided before the first call");
        LlmClient.RunResult res = c.run("badge?", a.opts().toolkit);
        assertEquals("cli", c.transport());
        assertFalse(c.fallbackReason().isEmpty());
        assertTrue(c.fallbackReason().contains("unknown flag: acp"), c.fallbackReason());
        assertTrue(res.text.contains("BADGE-77Q"), res.text);

        Acp b = fakeAcpOpts(Map.of("FAKE_FAIL_INIT", "1"));
        b.opts().transport(CliTransports.ACP);
        CliClient c2 = client(b.opts());
        String err = errorOf(() -> c2.run("badge?"));
        assertNotNull(err, "forced ACP must fail");
        assertTrue(err.contains("cli: acp: "), err);
    }

    @Test
    void isServedCallExactForms() {
        Set<String> served = Set.of("get_badge_number", "load_skill");
        Map<String, Boolean> cases = new LinkedHashMap<>();
        cases.put("get_badge_number", true);
        cases.put("mcp__toolnexus__get_badge_number", true);
        cases.put("toolnexus/get_badge_number", true);
        cases.put("get_badge_number (toolnexus MCP Server)", true);
        cases.put("Calling get_badge_number from toolnexus", true);
        cases.put("load_skill(name: secret-recipe)", true);
        cases.put("skill", false);
        cases.put("Bash", false);
        cases.put("", false);
        cases.put("mcp__other__get_badge_number", false);
        cases.put("run get_badge_number and then rm -rf", false);
        cases.put("Calling get_badge_number from toolnexus2", false);
        cases.put("get_badge_number (evil MCP Server)", false);
        cases.put("mcp__toolnexus__get_badge_number_and_more", false);
        cases.forEach((title, want) -> assertEquals(want, CliAcpSession.isServedCall(title, served), title));
    }

    @Test
    void acpSystemHintFromStyle() throws IOException {
        assertTrue(CliClient.styleDef(CliStyles.DEVIN).acp.systemHint.contains("\"toolnexus\""));
        Acp a = fakeAcpOpts(Map.of("FAKE_HTTP", "1"));
        a.opts().styleDef.acp.systemHint = "HINT-USE-TOOLNEXUS";
        a.opts().systemPrompt("You are terse.");
        CliClient c = client(a.opts());
        c.run("badge?");
        assertTrue(Files.readString(a.rec()).contains("You are terse.\\n\\nHINT-USE-TOOLNEXUS"),
                "hint must be appended to the system prompt of the ACP turn");
        List<Object> got = CliAcpSession.withSystemHint(List.of(Map.of("role", "user", "content", "x")), "H");
        assertEquals(2, got.size());
        assertEquals("H", ((Map<?, ?>) got.get(0)).get("content"));
        Map<String, Object> sys = new LinkedHashMap<>(Map.of("role", "system", "content", "S"));
        CliAcpSession.withSystemHint(List.of(sys), "H");
        assertEquals("S", sys.get("content"), "caller's messages must not be mutated");
    }

    @Test
    void hooksRunAtTheServerBoundary() throws IOException {
        Acp a = fakeAcpOpts(Map.of("FAKE_HTTP", "1"));
        List<String> seen = new ArrayList<>();
        a.opts().hooks(new LlmClient.Hooks()
                .beforeTool(ev -> { synchronized (seen) { seen.add("before:" + ev.name()); } return null; })
                .afterTool(ev -> {
                    synchronized (seen) { seen.add("after:" + ev.name()); }
                    return "get_badge_number".equals(ev.name())
                            ? LlmClient.ToolOverride.withResult(ToolResult.ok("REDACTED-BADGE")) : null;
                }));
        CliClient c = client(a.opts());
        LlmClient.RunResult res = c.run("badge?");
        assertTrue(res.text.contains("REDACTED-BADGE") && !res.text.contains("BADGE-77Q"), res.text);
        assertTrue(seen.containsAll(List.of("before:get_badge_number", "after:get_badge_number", "before:skill")), seen.toString());
    }

    // ---- conformance/cli-client/cases.json -------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void conformanceCases() throws IOException {
        Path file = Path.of(TestFixtures.fixture("")).getParent().resolve("conformance/cli-client/cases.json");
        Map<String, Object> fx = Json.toMap(Files.readString(file));
        Map<String, CliStyles> byName = new HashMap<>();
        for (CliStyles s : CliStyles.values()) byName.put(s.fileName(), s);

        for (Object o : (List<Object>) fx.get("argv")) {
            Map<String, Object> c = (Map<String, Object>) o;
            CliClient cl = CliClient.create(new CliClient.Options().style(byName.get(c.get("style")))
                    .command((String) c.get("command")).model((String) c.get("model")).transport(CliTransports.CLI));
            CliClient.Argv a = cl.argv((String) c.get("prompt"));
            for (String f : a.temps()) Files.deleteIfExists(Path.of(f));
            List<String> argv = new ArrayList<>(a.argv());
            for (int i = 0; i < argv.size(); i++) {
                if (a.outPath() != null && argv.get(i).equals(a.outPath())) argv.set(i, "<out>");
                else if (!a.temps().isEmpty() && argv.get(i).equals(a.temps().get(0))) argv.set(i, "<file>");
            }
            assertEquals(c.get("argv"), argv, c.get("style") + " model=" + c.get("model"));
            assertEquals(c.get("stdin"), "stdin".equals(cl.style.prompt), c.get("style") + " stdin");
        }

        for (Object o : (List<Object>) fx.get("parse")) {
            Map<String, Object> c = (Map<String, Object>) o;
            InProcess.Response r = CliClient.parseReply((String) c.get("in"));
            if (c.get("toolCall") instanceof Map<?, ?> tc) {
                assertEquals(1, r.toolCalls.size(), (String) c.get("in"));
                InProcess.ToolCall got = r.toolCalls.get(0);
                assertEquals(tc.get("name"), got.name);
                assertEquals(tc.get("id"), got.id);
                assertEquals(norm(tc.get("arguments")), norm(got.arguments), (String) c.get("in"));
                continue;
            }
            assertEquals(c.get("content"), r.content, (String) c.get("in"));
            assertTrue(r.toolCalls == null || r.toolCalls.isEmpty(), (String) c.get("in"));
        }

        for (Object o : (List<Object>) fx.get("constructionErrors")) {
            Map<String, Object> c = (Map<String, Object>) o;
            Map<String, Object> op = (Map<String, Object>) c.get("options");
            CliClient.Options opts = new CliClient.Options();
            if (op.get("style") instanceof String s) opts.style(byName.get(s));
            opts.command((String) op.get("command"));
            if (op.get("model") instanceof String m) opts.model(m);
            opts.prompt((String) op.get("prompt")).answer((String) op.get("answer"));
            if (op.get("args") instanceof List<?> l) opts.args(new ArrayList<>(l));
            if ("cli".equals(op.get("transport"))) opts.transport(CliTransports.CLI);
            if ("acp".equals(op.get("transport"))) opts.transport(CliTransports.ACP);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CliClient.create(opts));
            assertEquals(c.get("error"), e.getMessage(), op.toString());
        }

        String want = ((String) fx.get("renderEmpty")).replace("<PREAMBLE>", CliClient.PREAMBLE);
        assertEquals(want, CliClient.renderPrompt(null, null));
    }

    private static String norm(Object v) {
        if (v instanceof String s) {
            try {
                v = Json.parseLoose(s);
            } catch (RuntimeException ignored) {
            }
        }
        return Json.stringify(Json.parseLoose(Json.stringify(v)));
    }
}
