package io.github.muthuishere.toolnexus;

import tools.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC §7G.11 hermetic conformance: replays EVERY case of {@code conformance/media/}
 * ({@code verbs.json}, {@code schemas.json}, {@code cases.json}, {@code clients.json},
 * {@code cli.json}, {@code routing.json}) — read from the repo, never copied — against an
 * in-process fake HTTP server, the portable fake CLI, and test-only custom clients.
 */
class MediaConformanceTest {

    static final Path CONF = Path.of("..", "conformance", "media").toAbsolutePath().normalize();

    static Map<String, Object> readJson(String file) throws IOException {
        return Json.MAPPER.readValue(Files.readString(CONF.resolve(file)), new TypeReference<Map<String, Object>>() {});
    }

    static Object normalise(Object o) {
        try {
            return Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(o), Object.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Replace every placeholder in every string of a JSON value. */
    @SuppressWarnings("unchecked")
    static Object subst(Object v, Map<String, String> vars) {
        if (v instanceof String s) {
            for (Map.Entry<String, String> e : vars.entrySet()) s = s.replace(e.getKey(), e.getValue());
            return s;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put((String) e.getKey(), subst(e.getValue(), vars));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(subst(o, vars));
            return out;
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Fake server: script[] in order, the LAST entry repeats; records requests.
    // ------------------------------------------------------------------

    record Recorded(String method, String path, byte[] body, Map<String, String> headers) {
        String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    }

    static final class FakeServer implements AutoCloseable {
        final HttpServer server;
        final List<Recorded> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger next = new AtomicInteger();
        volatile List<Map<String, Object>> script = List.of();

        FakeServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void reset(List<Map<String, Object>> s) {
            script = s;
            next.set(0);
            requests.clear();
        }

        private void handle(HttpExchange ex) throws IOException {
            byte[] body = ex.getRequestBody().readAllBytes();
            Map<String, String> h = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.isEmpty() ? "" : v.get(0)));
            String q = ex.getRequestURI().getRawQuery();
            requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q), body, h));
            int i = Math.min(next.getAndIncrement(), script.size() - 1);
            Map<String, Object> entry = script.get(i);
            int status = ((Number) entry.get("status")).intValue();
            byte[] bytes;
            if (entry.containsKey("bodyBase64")) bytes = Base64.getDecoder().decode((String) entry.get("bodyBase64"));
            else if (entry.containsKey("rawBody")) bytes = ((String) entry.get("rawBody")).getBytes(StandardCharsets.UTF_8);
            else bytes = Json.stringify(entry.get("body")).getBytes(StandardCharsets.UTF_8);
            if (entry.get("headers") instanceof Map<?, ?> rh) {
                for (Map.Entry<?, ?> e : rh.entrySet()) ex.getResponseHeaders().add((String) e.getKey(), (String) e.getValue());
            }
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }

        @Override public void close() { server.stop(0); }
    }

    /** Env seam: exactly the case's env (no real keys ever leak in). */
    static Function<String, String> envOf(Object env, Map<String, String> vars) {
        Map<String, String> m = new HashMap<>();
        if (env instanceof Map<?, ?> e) for (Map.Entry<?, ?> x : e.entrySet()) m.put((String) x.getKey(), (String) subst(x.getValue(), vars));
        return m::get;
    }

    @SuppressWarnings("unchecked")
    static Media.Config config(Object raw) {
        Media.Config c = new Media.Config();
        if (!(raw instanceof Map<?, ?> m)) return c;
        if (m.get("model") instanceof String s) c.model(s);
        if (m.get("models") instanceof Map<?, ?> mm) c.models((Map<String, String>) mm);
        if (m.get("key") instanceof String s) c.key(s);
        if (m.get("url") instanceof String s) c.url(s);
        if (m.get("command") instanceof String s) c.command(s);
        if (m.get("command") instanceof List<?> l) c.command((List<String>) l);
        if (m.get("loop") instanceof Map<?, ?> l) {
            Media.Loop loop = new Media.Loop();
            if (l.get("intervalMs") instanceof Number n) loop.intervalMs = n.longValue();
            if (l.get("timeoutMs") instanceof Number n) loop.timeoutMs = n.longValue();
            c.loop(loop);
        }
        if (m.get("gather") instanceof Map<?, ?> g) {
            Media.Gather gather = new Media.Gather();
            if (g.get("mode") instanceof String s) gather.mode(s);
            if (g.get("dir") instanceof String s) gather.dir(s);
            if (g.get("maxBytes") instanceof Number n) gather.maxBytes(n.longValue());
            if (g.get("keep") instanceof Boolean b) gather.keep(b);
            if (g.get("name") instanceof String s) gather.name(s);
            c.gather(gather);
        }
        return c;
    }

    // ------------------------------------------------------------------
    // Shared assertions
    // ------------------------------------------------------------------

    record Outcome(Media.MediaResult result, RuntimeException error, List<String> progress) {}

    static Outcome call(Media media, String verb, Map<String, Object> args) {
        return call(media, verb, args, null);
    }

    /** ctxTimeoutMs (D12): the function face runs with ctx.deadline = now + ctxTimeoutMs. */
    static Outcome call(Media media, String verb, Map<String, Object> args, Object ctxTimeoutMs) {
        List<String> progress = Collections.synchronizedList(new ArrayList<>());
        Media.Ctx ctx = new Media.Ctx().progress(p -> {
            assertEquals(verb, p.verb());
            progress.add(p.status());
        });
        if (ctxTimeoutMs instanceof Number n) ctx.deadline(java.time.Instant.now().plusMillis(n.longValue()));
        try {
            return new Outcome(media.generate(verb, args, ctx), null, progress);
        } catch (RuntimeException e) {
            return new Outcome(null, e, progress);
        }
    }

    @SuppressWarnings("unchecked")
    static void assertOutcome(Map<String, Object> c, Outcome o, String dir, String client, String verb) throws IOException {
        String name = (String) c.get("name");
        if (c.containsKey("error") || c.containsKey("errorPrefix")) {
            assertNull(o.result(), name + ": expected an error");
            assertNotNull(o.error(), name);
            if (c.containsKey("error")) assertEquals(c.get("error"), o.error().getMessage(), name);
            else assertTrue(o.error().getMessage().startsWith((String) c.get("errorPrefix")),
                    name + ": " + o.error().getMessage());
            if (c.containsKey("status")) {
                assertInstanceOf(Media.MediaException.class, o.error(), name);
                assertEquals(c.get("status"), ((Media.MediaException) o.error()).status(), name);
                assertEquals("unavailable".equals(c.get("status")), o.error() instanceof Media.MediaUnavailableException, name);
            }
        } else {
            if (o.error() != null) throw new AssertionError(name + ": unexpected error: " + o.error().getMessage(), o.error());
            assertResult(name, (Map<String, Object>) c.get("result"), o.result(), dir, client, verb);
        }
        if (c.containsKey("expectProgress")) assertEquals(c.get("expectProgress"), o.progress(), name);
    }

    @SuppressWarnings("unchecked")
    static void assertResult(String name, Map<String, Object> exp, Media.MediaResult r, String dir, String client,
                             String verb) throws IOException {
        if (exp == null) return;
        assertEquals(exp.get("status"), r.status(), name + " status");
        assertEquals(exp.get("text"), r.text(), name + " text");
        assertEquals(exp.get("jobId"), r.jobId(), name + " jobId");
        // §7G.10b: style / model asserted only when the case names them
        if (exp.containsKey("style")) assertEquals(exp.get("style"), r.style(), name + " style");
        if (exp.containsKey("model")) assertEquals(exp.get("model"), r.model(), name + " model");
        // §7G.12 D21: usage exact when expected, absent otherwise
        assertEquals(normalise(exp.get("usage")), r.usage() == null ? null : normalise(r.usage().toMap()), name + " usage");
        List<Map<String, Object>> files = (List<Map<String, Object>>) exp.get("files");
        assertEquals(files.size(), r.files().size(), name + " file count");
        for (int i = 0; i < files.size(); i++) {
            Map<String, Object> e = files.get(i);
            Media.MediaFile f = r.files().get(i);
            assertEquals(e.get("type"), f.type(), name + " type");
            assertEquals(e.get("mimeType"), f.mimeType(), name + " mimeType");
            if (e.get("saved") instanceof Map<?, ?> saved) {
                assertNotNull(f.path(), name + " saved path");
                Path p = Path.of(f.path());
                assertTrue(p.isAbsolute(), name + " absolute");
                assertEquals(Path.of(dir), p.getParent(), name + " lives directly in {dir}");
                assertTrue(Pattern.matches("^" + Pattern.quote(client + "-" + verb + "-") + "[0-9]+-[0-9]+"
                        + Pattern.quote((String) saved.get("ext")) + "$", p.getFileName().toString()), name + " " + p);
                assertEquals("file://" + f.path(), f.url(), name + " url");
                assertNull(f.data(), name + " data");
                assertArrayEquals(Base64.getDecoder().decode((String) saved.get("bytesBase64")), Files.readAllBytes(p), name + " bytes");
            } else {
                assertEquals(e.get("url"), f.url(), name + " url");
                assertEquals(e.get("data"), f.data(), name + " data");
                assertEquals(e.get("path"), f.path(), name + " path");
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void assertTool(String name, Map<String, Object> tool, ToolResult tr, String dir) {
        assertEquals(tool.get("isError"), tr.isError(), name + " isError: " + tr.output());
        if (tool.containsKey("output")) assertEquals(tool.get("output"), tr.output(), name + " output");
        if (tool.containsKey("outputPrefix")) assertTrue(tr.output().startsWith((String) tool.get("outputPrefix")), name + " " + tr.output());
        if (tool.containsKey("outputPattern")) {
            String re = ((String) tool.get("outputPattern")).replace("{dir}", Pattern.quote(dir));
            assertTrue(Pattern.compile(re).matcher(tr.output()).matches(), name + " output " + tr.output() + " !~ " + re);
        }
        if (tool.containsKey("parts")) {
            assertNotNull(tr.parts(), name + " parts");
            assertEquals(normalise(tool.get("parts")), normalise(tr.parts()), name + " parts");
        }
        if (Boolean.TRUE.equals(tool.get("noParts")) || Boolean.TRUE.equals(tool.get("isError"))) {
            assertNull(tr.parts(), name + " no parts");
        }
        if (tool.containsKey("metadata")) assertEquals(normalise(tool.get("metadata")), normalise(tr.metadata()), name + " metadata");
        if (tool.containsKey("modelParts")) {
            assertEquals(normalise(tool.get("modelParts")), normalise(LlmClient.modelParts(tr)), name + " modelParts");
            if (((List<?>) tool.get("modelParts")).isEmpty()) assertPlainOutputEveryStyle(name, tr);
        }
    }

    /** D9: a media result with no image part reaches the model as the plain output string in every style. */
    static void assertPlainOutputEveryStyle(String name, ToolResult tr) {
        for (String style : List.of("openai", "anthropic")) {
            LlmClient c = LlmClient.create(new LlmClient.Options().baseUrl("http://127.0.0.1:1").style(style).model("m").apiKey("k"));
            assertEquals(tr.output(), c.toolResultContent(tr), name + " D9 " + style);
        }
    }

    /** D21 noUsage / D20 runFilePath on a function-face result and its tool result. */
    static void assertUsageAndRunFiles(String name, Map<String, Object> c, Outcome o, ToolResult tr) {
        if (Boolean.TRUE.equals(c.get("noUsage"))) {
            if (o != null && o.result() != null) assertNull(o.result().usage(), name + " noUsage result");
            if (tr != null && tr.metadata() != null) assertFalse(tr.metadata().containsKey("usage"), name + " noUsage metadata");
        }
        if (tr != null && c.get("runFilePath") instanceof Boolean want) {
            // D20: RunResult.files built by the port's run-files function from a toolCalls record
            LlmClient.ToolCall call = new LlmClient.ToolCall("t", Map.of(), tr.output(), tr.isError(), tr.metadata(),
                    tr.parts(), "c1", tr.partPaths());
            List<LlmClient.RunFile> files = LlmClient.runFiles(List.of(call));
            assertFalse(files.isEmpty(), name + " run files");
            for (ContentPart p : tr.parts()) assertFalse(Json.stringify(p).contains("\"path\""), name + " part holds a path");
            for (int i = 0; i < files.size(); i++) {
                String path = files.get(i).path();
                if (want) {
                    assertNotNull(path, name + " RunFile.path #" + i);
                    assertTrue(Path.of(path).isAbsolute(), name + " absolute RunFile.path");
                    if (o != null && o.result() != null && o.result().files().size() == files.size()) {
                        // function-face files differ by run (fresh dir/seq), so compare shape only
                        assertNotNull(o.result().files().get(i).path(), name + " MediaFile.path #" + i);
                    }
                    assertEquals(tr.partPaths().get(i), path, name + " RunFile.path == MediaFile.path");
                } else {
                    assertNull(path, name + " RunFile.path absent #" + i);
                }
            }
        }
    }

    static void assertHeaderPatterns(String name, Map<String, Object> c, List<Recorded> got) {
        if (!(c.get("expectHeaderPatterns") instanceof Map<?, ?> hp)) return;
        assertFalse(got.isEmpty(), name);
        for (Recorded r : got) {
            for (Map.Entry<?, ?> h : hp.entrySet()) {
                String v = r.headers().get(((String) h.getKey()).toLowerCase(Locale.ROOT));
                assertNotNull(v, name + " " + h.getKey() + " " + r.path());
                assertTrue(Pattern.compile((String) h.getValue()).matcher(v).find(), name + " " + h.getKey() + "=" + v + " " + r.path());
            }
        }
    }

    static ToolResult runTool(Media media, String toolName, Map<String, Object> args) {
        Tool t = media.tools().stream().filter(x -> x.name().equals(toolName)).findFirst()
                .orElseThrow(() -> new AssertionError("no tool " + toolName));
        assertEquals("media", t.source());
        ToolResult tr = t.execute(args, new ToolContext());
        assertNotNull(tr);
        return tr;
    }

    /** The tool for a verb (tool expectations of error cases carry no name). */
    static String toolOf(String verb) {
        for (Map<String, Object> v : MediaSupport.VERBS) if (v.get("verb").equals(verb)) return (String) v.get("tool");
        return null;
    }

    @SuppressWarnings("unchecked")
    static void assertRequests(String name, Map<String, Object> c, List<Recorded> got, boolean strictContentType) throws IOException {
        if (c.containsKey("expectRequests")) {
            List<Map<String, Object>> exp = (List<Map<String, Object>>) c.get("expectRequests");
            assertEquals(exp.size(), got.size(), name + " request count " + got.stream().map(Recorded::path).toList());
            for (int i = 0; i < exp.size(); i++) {
                Map<String, Object> e = exp.get(i);
                Recorded r = got.get(i);
                assertEquals(e.get("method"), r.method(), name + " method #" + i);
                assertEquals(e.get("path"), r.path(), name + " path #" + i);
                if (e.containsKey("body")) {
                    assertEquals(normalise(e.get("body")), Json.MAPPER.readValue(r.body(), Object.class), name + " body #" + i);
                    assertEquals("application/json", r.headers().get("content-type"), name + " content-type #" + i);
                } else if (strictContentType && !e.containsKey("multipart")) {
                    assertNull(r.headers().get("content-type"), name + " no Content-Type without a body");
                    assertEquals(0, r.body().length);
                }
                if (e.get("multipart") instanceof List<?> mp) {
                    assertMultipart(name + " #" + i, (List<Map<String, Object>>) mp, r);
                }
                if (e.get("headers") instanceof Map<?, ?> hs) {
                    for (Map.Entry<?, ?> h : hs.entrySet()) {
                        assertEquals(h.getValue(), r.headers().get(((String) h.getKey()).toLowerCase(Locale.ROOT)), name + " header " + h.getKey() + " #" + i);
                    }
                }
                if (e.get("absentHeaders") instanceof List<?> ab) {
                    for (Object h : ab) assertFalse(r.headers().containsKey(((String) h).toLowerCase(Locale.ROOT)), name + " absent " + h + " #" + i);
                }
            }
        }
        if (c.get("expectHeaders") instanceof Map<?, ?> hs) {
            assertFalse(got.isEmpty(), name);
            for (Recorded r : got) {
                for (Map.Entry<?, ?> h : hs.entrySet()) {
                    assertEquals(h.getValue(), r.headers().get(((String) h.getKey()).toLowerCase(Locale.ROOT)), name + " " + h.getKey() + " " + r.path());
                }
            }
        }
    }

    /** Parse multipart/form-data and compare parts in order. */
    static void assertMultipart(String name, List<Map<String, Object>> exp, Recorded r) {
        String ct = r.headers().get("content-type");
        assertNotNull(ct, name + " multipart content-type");
        assertTrue(ct.startsWith("multipart/form-data"), name + " " + ct);
        String boundary = ct.substring(ct.indexOf("boundary=") + 9);
        String body = new String(r.body(), StandardCharsets.ISO_8859_1);
        String[] chunks = body.split(Pattern.quote("--" + boundary));
        List<Map<String, Object>> parts = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.isEmpty() || chunk.startsWith("--")) continue;
            String c = chunk.startsWith("\r\n") ? chunk.substring(2) : chunk;
            int sep = c.indexOf("\r\n\r\n");
            String head = c.substring(0, sep);
            String content = c.substring(sep + 4);
            if (content.endsWith("\r\n")) content = content.substring(0, content.length() - 2);
            Map<String, Object> p = new LinkedHashMap<>();
            String pName = null, filename = null, pct = null;
            for (String line : head.split("\r\n")) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.startsWith("content-disposition:")) {
                    java.util.regex.Matcher m = Pattern.compile("name=\"([^\"]*)\"").matcher(line);
                    if (m.find()) pName = m.group(1);
                    java.util.regex.Matcher f = Pattern.compile("filename=\"([^\"]*)\"").matcher(line);
                    if (f.find()) filename = f.group(1);
                } else if (l.startsWith("content-type:")) {
                    pct = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            byte[] bytes = content.getBytes(StandardCharsets.ISO_8859_1);
            p.put("name", pName);
            if (filename != null) {
                p.put("filename", filename);
                p.put("contentType", pct);
                p.put("bytesBase64", Base64.getEncoder().encodeToString(bytes));
            } else {
                p.put("value", new String(bytes, StandardCharsets.UTF_8));
            }
            parts.add(p);
        }
        assertEquals(normalise(exp), normalise(parts), name + " multipart");
    }

    static Path tmp(String prefix) throws IOException {
        return Files.createTempDirectory(prefix).toAbsolutePath();
    }

    // ------------------------------------------------------------------
    // verbs.json + a2e.json — embedded verbatim
    // ------------------------------------------------------------------

    @Test
    void verbsAndA2ePresetEmbeddedVerbatim() throws IOException {
        assertEquals(Files.readString(CONF.resolve("verbs.json")), MediaSupport.VERBS_JSON);
        assertEquals(Files.readString(CONF.resolve("../../examples/media-styles/a2e.json").normalize()), A2EMediaClient.presetJson());
        assertEquals(7, MediaSupport.VERBS.size());
        assertEquals(List.of("image", "video", "speech", "music", "transcribe", "cloneVoice", "talk"),
                MediaSupport.VERBS.stream().map(v -> v.get("verb")).toList());
    }

    // ------------------------------------------------------------------
    // schemas.json — deep equality AND order
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void assertTools(String name, List<Map<String, Object>> expected, List<Tool> tools) {
        assertEquals(expected.size(), tools.size(), name);
        for (int i = 0; i < tools.size(); i++) {
            Tool t = tools.get(i);
            Map<String, Object> e = expected.get(i);
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("name", t.name());
            actual.put("description", t.description());
            actual.put("source", t.source());
            actual.put("inputSchema", t.inputSchema());
            assertEquals(normalise(e), normalise(actual), name + " " + t.name());
            // key order of properties and required is contract too
            assertEquals(Json.stringify(e.get("inputSchema")), Json.stringify(t.inputSchema()), name + " " + t.name());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemasJson() throws IOException {
        Map<String, Object> doc = readJson("schemas.json");
        Media m = Media.create(Media.styleMediaClient(Media.Style.of((Map<String, Object>) doc.get("style"))));
        assertTools("schemas.json", (List<Map<String, Object>>) doc.get("tools"), m.tools());
    }

    // ------------------------------------------------------------------
    // cases.json — the style engine
    // ------------------------------------------------------------------

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> casesJson() throws IOException {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) readJson("cases.json").get("cases");
        assertEquals(49, cases.size(), "exact cases.json count");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) tests.add(DynamicTest.dynamicTest((String) c.get("name"), () -> runStyleCase(c)));
        return tests;
    }

    @SuppressWarnings("unchecked")
    private void runStyleCase(Map<String, Object> raw) throws Exception {
        Function<String, String> saved = Media.env;
        try (FakeServer fs = new FakeServer()) {
            Map<String, String> vars = Map.of("{server}", fs.url());
            Map<String, Object> c = (Map<String, Object>) subst(raw, vars);
            String name = (String) c.get("name");
            Media.env = envOf(c.get("env"), vars);
            Media.Style style = Media.Style.of((Map<String, Object>) normalise(c.get("style")));
            List<Map<String, Object>> script = (List<Map<String, Object>>) c.get("script");
            if (Boolean.TRUE.equals(c.get("styleError"))) {
                Media.MediaException se = assertThrows(Media.MediaException.class, () -> Media.styleMediaClient(style));
                assertEquals(c.get("error"), se.getMessage(), name);
                Path f = Files.createTempFile("style", ".json");
                Files.writeString(f, Json.stringify(style.spec()));
                Media.MediaException le = assertThrows(Media.MediaException.class, () -> Media.loadMediaStyle(f.toString()));
                assertEquals(c.get("error"), le.getMessage(), name);
                Files.deleteIfExists(f);
                assertTrue(fs.requests.isEmpty());
                return;
            }
            String dir = tmp("media-dir").toString();
            Map<String, Object> cfg = (Map<String, Object>) c.getOrDefault("config", Map.of());
            Media media = Media.create(Media.styleMediaClient(style, config(cfg)));
            String verb = (String) c.get("verb");
            Map<String, Object> args = (Map<String, Object>) c.get("args");

            fs.reset(script);
            Outcome o = call(media, verb, args);
            assertOutcome(c, o, dir, "style", verb);
            assertRequests(name, c, fs.requests, true);
            assertHeaderPatterns(name, c, fs.requests);
            assertUsageAndRunFiles(name, c, o, null);

            Map<String, Object> tool = (Map<String, Object>) c.get("tool");
            if (tool == null) {
                if (!media.supports(verb)) assertTrue(media.tools().stream().noneMatch(t -> t.name().equals(toolOf(verb))));
                return;
            }
            fs.reset(script);
            ToolResult tr = runTool(media, tool.get("name") != null ? (String) tool.get("name") : toolOf(verb), args);
            assertTool(name, tool, tr, dir);
            assertHeaderPatterns(name, c, fs.requests);
            assertUsageAndRunFiles(name, c, null, tr);
        } finally {
            Media.env = saved;
        }
    }

    // ------------------------------------------------------------------
    // clients.json — built-in API clients
    // ------------------------------------------------------------------

    static MediaClient builtIn(String client, Media.Config cfg) {
        return switch (client) {
            case "a2e" -> new A2EMediaClient(cfg);
            case "elevenlabs" -> new ElevenLabsMediaClient(cfg);
            case "openai" -> new OpenAIMediaClient(cfg);
            case "openrouter" -> new OpenRouterMediaClient(cfg);
            case "fal" -> new FalMediaClient(cfg);
            case "grok" -> new GrokMediaClient(cfg);
            case "codex" -> new CodexMediaClient(cfg);
            default -> throw new IllegalArgumentException(client);
        };
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> clientsJson() throws IOException {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) readJson("clients.json").get("cases");
        assertEquals(76, cases.size(), "exact clients.json count");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) tests.add(DynamicTest.dynamicTest((String) c.get("name"), () -> runClientCase(c)));
        return tests;
    }

    @SuppressWarnings("unchecked")
    private void runClientCase(Map<String, Object> raw) throws Exception {
        Function<String, String> saved = Media.env;
        try (FakeServer fs = new FakeServer()) {
            Path inputs = tmp("media-inputs");
            if (raw.get("inputFiles") instanceof Map<?, ?> in) {
                for (Map.Entry<?, ?> e : in.entrySet()) {
                    Files.write(inputs.resolve((String) e.getKey()), Base64.getDecoder().decode((String) e.getValue()));
                }
            }
            String clientName = (String) raw.get("client");
            String verb = (String) raw.get("verb");
            for (String face : List.of("function", "tool")) {
                String dir = tmp("media-dir").toString();
                Map<String, String> vars = Map.of("{server}", fs.url(), "{dir}", dir, "{inputs}", inputs.toString());
                Map<String, Object> c = (Map<String, Object>) subst(raw, vars);
                String name = c.get("name") + " (" + face + ")";
                Media.env = envOf(c.get("env"), vars);
                Media media = Media.create(builtIn(clientName, config(c.get("config"))));
                Map<String, Object> args = (Map<String, Object>) c.get("args");
                fs.reset((List<Map<String, Object>>) c.get("script"));
                if (face.equals("function")) {
                    long t0 = System.nanoTime();
                    Outcome o = call(media, verb, args, c.get("ctxTimeoutMs"));
                    long elapsed = (System.nanoTime() - t0) / 1_000_000L;
                    if (c.get("maxElapsedMs") instanceof Number n) assertTrue(elapsed <= n.longValue(), name + " took " + elapsed + "ms");
                    assertOutcome(c, o, dir, clientName, verb);
                    assertRequests(name, c, fs.requests, false);
                    assertHeaderPatterns(name, c, fs.requests);
                    assertUsageAndRunFiles(name, c, o, null);
                } else {
                    Map<String, Object> tool = (Map<String, Object>) c.get("tool");
                    if (tool == null || c.containsKey("ctxTimeoutMs")) continue;
                    ToolResult tr = runTool(media, tool.get("name") != null ? (String) tool.get("name") : toolOf(verb), args);
                    assertTool(name, tool, tr, dir);
                    if (c.containsKey("expectRequests")) assertRequests(name, c, fs.requests, false);
                    assertHeaderPatterns(name, c, fs.requests);
                    assertUsageAndRunFiles(name, c, null, tr);
                }
            }
        } finally {
            Media.env = saved;
        }
    }

    // ------------------------------------------------------------------
    // cli.json — CLI clients over the portable fake CLI
    // ------------------------------------------------------------------

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> cliJson() throws IOException {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) readJson("cli.json").get("cases");
        assertEquals(35, cases.size(), "exact cli.json count");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) tests.add(DynamicTest.dynamicTest((String) c.get("name"), () -> runCliCase(c)));
        return tests;
    }

    @SuppressWarnings("unchecked")
    private void runCliCase(Map<String, Object> raw) throws Exception {
        Function<String, String> saved = Media.env;
        try {
            String style = (String) raw.get("style");
            String clientName = MediaStyle.label(style);
            String verb = (String) raw.get("verb");
            String fake = CONF.resolve("fake-cli/fake-cli.sh").toString();
            for (String face : List.of("function", "tool")) {
                Path work = tmp("media-cli");
                Path fixture = work.resolve("fixture.jsonl");
                Files.write(fixture, (List<String>) raw.get("fixture"), StandardCharsets.UTF_8);
                Path out = Files.createDirectory(work.resolve("out"));
                String dir = Files.createDirectory(work.resolve("dir")).toString();
                Map<String, String> vars = Map.of("{out}", out.toString(), "{dir}", dir);
                Map<String, Object> c = (Map<String, Object>) subst(raw, vars);
                String name = c.get("name") + " (" + face + ")";
                Media.env = envOf(c.get("env"), vars);
                Media.Config cfg = config(c.get("config"));
                cfg.command(c.get("command") instanceof List<?> cmd ? (List<String>) cmd
                        : List.of("/bin/sh", fake, fixture.toString(), out.toString()));
                Media media = Media.create(Media.loadMediaStyle(style, cfg));
                Map<String, Object> args = (Map<String, Object>) c.get("args");

                long t0 = System.nanoTime();
                if (face.equals("function")) {
                    Outcome o = call(media, verb, args, c.get("ctxTimeoutMs"));
                    assertOutcome(c, o, dir, clientName, verb);
                    assertUsageAndRunFiles(name, c, o, null);
                    if (Boolean.TRUE.equals(c.get("oneShot"))) {
                        Outcome one;
                        try {
                            one = new Outcome(Media.generateMedia(style, verb, args, cfg), null, List.of());
                        } catch (RuntimeException e) {
                            one = new Outcome(null, e, List.of());
                        }
                        assertOutcome(c, one, dir, clientName, verb);
                    }
                } else {
                    Map<String, Object> tool = (Map<String, Object>) c.get("tool");
                    if (tool == null || c.containsKey("ctxTimeoutMs")) continue;
                    ToolResult tr = runTool(media, tool.get("name") != null ? (String) tool.get("name") : toolOf(verb), args);
                    assertTool(name, tool, tr, dir);
                    assertUsageAndRunFiles(name, c, null, tr);
                }
                long elapsed = (System.nanoTime() - t0) / 1_000_000L;
                if (c.get("maxElapsedMs") instanceof Number n) assertTrue(elapsed <= n.longValue(), name + " took " + elapsed + "ms");

                Path argv = out.resolve("argv.txt");
                if (Boolean.TRUE.equals(c.get("expectNoSpawn"))) assertFalse(Files.exists(argv), name + " spawned");
                if (c.get("expectArgv") instanceof List<?> ea) {
                    List<String> got = Files.readAllLines(argv, StandardCharsets.UTF_8);
                    String cwd = Files.readString(out.resolve("cwd.txt")).trim();
                    assertEquals(ea.size(), got.size(), name + " argv " + got);
                    for (int i = 0; i < ea.size(); i++) {
                        if ("{cwd}".equals(ea.get(i))) {
                            // D17: the client passes the realpath, the fake records `pwd -P` — byte for byte
                            assertEquals(cwd, got.get(i), name + " --cwd");
                        } else {
                            assertEquals(ea.get(i), got.get(i), name + " argv #" + i);
                        }
                    }
                }
                if (c.get("expectEnv") instanceof Map<?, ?> ee && ee.get("GROK_CONFIG") != null) {
                    assertEquals(ee.get("GROK_CONFIG"), Files.readString(out.resolve("env-GROK_CONFIG.txt")), name + " GROK_CONFIG");
                }
                if (c.get("expectExists") instanceof List<?> ex) for (Object p : ex) assertTrue(Files.exists(Path.of((String) p)), name + " exists " + p);
                if (c.get("expectMissing") instanceof List<?> mi) for (Object p : mi) assertFalse(Files.exists(Path.of((String) p)), name + " missing " + p);
            }
        } finally {
            Media.env = saved;
        }
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> cliAvailabilityCases() throws IOException {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) readJson("cli.json").get("availabilityCases");
        assertEquals(7, cases.size(), "exact cli.json availabilityCases count");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> raw : cases) {
            tests.add(DynamicTest.dynamicTest((String) raw.get("name"), () -> {
                Function<String, String> saved = Media.env;
                try {
                    String dir = tmp("media-avail").toRealPath().toString();
                    Map<String, String> vars = Map.of("{dir}", dir);
                    Map<String, Object> c = (Map<String, Object>) subst(raw, vars);
                    Function<String, String> caseEnv = envOf(c.get("env"), vars);
                    List<?> unset = c.get("unsetEnv") instanceof List<?> u ? u : List.of();
                    Media.env = k -> {
                        String v = caseEnv.apply(k);
                        if (v != null) return v;
                        return unset.contains(k) ? null : System.getenv(k);
                    };
                    if (c.get("createFiles") instanceof List<?> cf) for (Object f : cf) Files.createFile(Path.of((String) f));
                    Media.Style st;
                    if (c.get("style") instanceof String preset) {
                        st = Media.loadMediaStyle(preset, config(c.get("config")));
                    } else {
                        st = Media.Style.of((Map<String, Object>) c.get("style"));
                        StyleMediaClient.validate(st);
                    }
                    long t0 = System.nanoTime();
                    Media.Availability a = st.available();
                    assertTrue((System.nanoTime() - t0) / 1_000_000L < 1000, "available() took too long");
                    Map<String, Object> got = new LinkedHashMap<>();
                    got.put("installed", a.installed());
                    got.put("loggedIn", a.loggedIn());
                    got.put("reason", a.reason());
                    assertEquals(c.get("expect"), got);
                } finally {
                    Media.env = saved;
                }
            }));
        }
        return tests;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> cliVerbCases() throws IOException {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) readJson("cli.json").get("verbCases");
        assertEquals(8, cases.size(), "exact cli.json verbCases count");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> vc : cases) {
            tests.add(DynamicTest.dynamicTest((String) vc.get("name"), () -> {
                List<MediaClient> srcs = new ArrayList<>();
                for (Object s : (List<Object>) vc.get("styles")) {
                    if (s instanceof String preset) {
                        srcs.add(Media.loadMediaStyle(preset,
                                new Media.Config().command("/nonexistent/toolnexus-must-not-run")));
                    } else {
                        Media.Style st = Media.Style.of((Map<String, Object>) s);
                        StyleMediaClient.validate(st);
                        srcs.add(st);
                    }
                }
                Media m = Media.create(srcs);
                assertEquals(vc.get("expectVerbs"), m.verbs(), "verbs");
                List<String> names = new ArrayList<>();
                for (Tool t : m.tools()) names.add(t.name());
                assertEquals(vc.get("expectTools"), names, "tools");
                Media.MediaException e = assertThrows(Media.MediaException.class,
                        () -> m.generate((String) vc.get("unsupported"), Map.of()));
                assertEquals(vc.get("error"), e.getMessage());
            }));
        }
        return tests;
    }

    // ------------------------------------------------------------------
    // routing.json — createMedia routing with test-only custom clients
    // ------------------------------------------------------------------

    record Call(String client, String verb, Map<String, Object> args) {}

    @SuppressWarnings("unchecked")
    static List<MediaClient> testClients(List<Map<String, Object>> specs, List<Call> calls) {
        List<MediaClient> out = new ArrayList<>();
        for (Map<String, Object> spec : specs) {
            String cname = (String) spec.get("name");
            Map<String, Map<String, Object>> verbs = (Map<String, Map<String, Object>>) spec.get("verbs");
            out.add(new MediaClient() {
                @Override public String name() { return cname; }
                @Override public boolean supports(String verb) { return verbs.containsKey(verb); }
                @Override public List<Media.ArgSpec> args(String verb) {
                    Map<String, Object> v = verbs.get(verb);
                    if (v == null || !(v.get("args") instanceof List<?> l)) return null;
                    List<Media.ArgSpec> a = new ArrayList<>();
                    for (Object o : l) {
                        Map<String, Object> m = (Map<String, Object>) o;
                        a.add(new Media.ArgSpec((String) m.get("name"), Boolean.TRUE.equals(m.get("required"))));
                    }
                    return a;
                }
                @Override public Media.MediaResult call(String verb, Map<String, Object> args, Media.Ctx ctx) {
                    calls.add(new Call(cname, verb, new LinkedHashMap<>(args)));
                    Map<String, Object> outcome = (Map<String, Object>) verbs.get(verb).get("outcome");
                    if (outcome.containsKey("error")) {
                        String msg = (String) outcome.get("error");
                        String st = (String) outcome.get("status");
                        if ("unavailable".equals(st)) throw new Media.MediaUnavailableException(msg);
                        throw new Media.MediaException(msg, st, null);
                    }
                    Map<String, Object> r = (Map<String, Object>) outcome.get("result");
                    List<Media.MediaFile> files = new ArrayList<>();
                    for (Map<String, Object> f : (List<Map<String, Object>>) r.get("files")) {
                        files.add(new Media.MediaFile((String) f.get("type"), (String) f.get("mimeType"),
                                (String) f.get("url"), (String) f.get("path"), (String) f.get("data")));
                    }
                    Media.MediaUsage usage = null;
                    if (r.get("usage") instanceof Map<?, ?> u) {
                        usage = new Media.MediaUsage(u.get("inputTokens") instanceof Number n ? n.longValue() : null,
                                u.get("outputTokens") instanceof Number n ? n.longValue() : null,
                                u.get("costUsd") instanceof Number n ? n.doubleValue() : null,
                                (Map<String, Object>) u.get("raw"));
                    }
                    return new Media.MediaResult(files, (String) r.get("text"), (String) r.get("jobId"), (String) r.get("status"), usage);
                }
            });
        }
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> routingJson() throws IOException {
        Map<String, Object> doc = readJson("routing.json");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) doc.get("cases");
        assertEquals(19, cases.size(), "exact routing.json count");
        List<Map<String, Object>> schemaCases = (List<Map<String, Object>>) doc.get("schemaCases");
        assertEquals(1, schemaCases.size(), "exact routing.json schemaCases count");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) tests.add(DynamicTest.dynamicTest((String) c.get("name"), () -> runRoutingCase(c)));
        for (Map<String, Object> c : schemaCases) {
            tests.add(DynamicTest.dynamicTest("schema " + c.get("name"), () -> assertTools((String) c.get("name"),
                    (List<Map<String, Object>>) c.get("tools"),
                    Media.create(testClients((List<Map<String, Object>>) c.get("clients"), new ArrayList<>())).tools())));
        }
        tests.add(DynamicTest.dynamicTest("emptyClients", () -> {
            String err = (String) ((Map<String, Object>) doc.get("emptyClients")).get("error");
            assertEquals(err, assertThrows(Media.MediaException.class, () -> Media.create(List.of())).getMessage());
            assertEquals(err, assertThrows(Media.MediaException.class, () -> Media.create()).getMessage());
        }));
        return tests;
    }

    @SuppressWarnings("unchecked")
    private void runRoutingCase(Map<String, Object> c) throws IOException {
        String name = (String) c.get("name");
        String verb = (String) c.get("verb");
        Map<String, Object> args = (Map<String, Object>) c.get("args");
        List<Call> calls = new CopyOnWriteArrayList<>();
        Media media = Media.create(testClients((List<Map<String, Object>>) c.get("clients"), calls));
        Outcome o = call(media, verb, args);
        if (c.containsKey("expectCalls")) {
            List<Object> got = new ArrayList<>();
            for (Call k : calls) got.add(Map.of("client", k.client(), "verb", k.verb(), "args", k.args()));
            assertEquals(normalise(c.get("expectCalls")), normalise(got), name + " calls");
        }
        assertOutcome(c, o, null, null, verb);
        Map<String, Object> tool = (Map<String, Object>) c.get("tool");
        if (tool == null) return;
        Media fresh = Media.create(testClients((List<Map<String, Object>>) c.get("clients"), new CopyOnWriteArrayList<>()));
        ToolResult tr = runTool(fresh, tool.get("name") != null ? (String) tool.get("name") : toolOf(verb), args);
        assertTool(name, tool, tr, "");
    }

    // ------------------------------------------------------------------
    // Model visibility through real assembly (§7G.6): only image parts reach the model.
    // ------------------------------------------------------------------

    @Test
    void videoAndAudioPartsNeverReachTheModel() {
        Map<String, Object> meta = Map.of("source", "media", "verb", "video", "client", "x", "status", "completed");
        ToolResult video = new ToolResult("video ready: https://x/v.mp4", false, meta,
                List.of(new ContentPart("file", null, "video/mp4", null, "https://x/v.mp4", null)));
        assertEquals(List.of(), LlmClient.modelParts(video));
        ToolResult image = new ToolResult("image ready: https://x/a.png", false, meta,
                List.of(new ContentPart("image", null, "image/png", null, "https://x/a.png", null),
                        new ContentPart("audio", null, "audio/mpeg", "AAAA", null, null)));
        assertEquals(1, LlmClient.modelParts(image).size());
        // A non-media tool keeps every non-text part (byte-identical to before).
        ToolResult other = new ToolResult("x", false, Map.of("source", "custom"),
                List.of(new ContentPart("audio", null, "audio/mpeg", "AAAA", null, null)));
        assertEquals(1, LlmClient.modelParts(other).size());
    }

    /** §7G.12 D9 (+D8): no image part ⇒ every message style sends the plain output string. */
    @Test
    void noImagePartsSendsPlainOutputInEveryStyle() {
        Map<String, Object> meta = Map.of("source", "media", "verb", "video", "client", "x", "status", "completed");
        List<ToolResult> results = List.of(
                new ToolResult("video ready: https://x/v.mp4", false, meta,
                        List.of(new ContentPart("file", null, "video/mp4", null, "https://x/v.mp4", null))),
                new ToolResult("audio ready: https://x/a.mp3", false, meta,
                        List.of(new ContentPart("audio", null, "audio/mpeg", "AAAA", null, null),
                                ContentPart.text("transcript text"))),
                new ToolResult("video ready: (no files)", false, meta, List.of()));
        for (ToolResult r : results) {
            assertEquals(List.of(), LlmClient.modelParts(r));
            for (String style : List.of("openai", "anthropic")) {
                LlmClient c = LlmClient.create(new LlmClient.Options().baseUrl("http://127.0.0.1:1").style(style).model("m").apiKey("k"));
                Object content = c.toolResultContent(r);
                assertInstanceOf(String.class, content, style + " " + r.output());
                assertEquals(r.output(), content, style);
            }
        }
    }
}
