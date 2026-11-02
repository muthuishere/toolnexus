package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC §7G conformance: replays {@code conformance/media/cases.json} and {@code schemas.json}
 * (read from the repo, never copied) against an in-process fake HTTP server, plus cancellation,
 * typed verbs, Toolkit registration and {@code RunResult.files}.
 */
class MediaTest {

    private static final Path CONF = Path.of("..", "conformance", "media");

    private static Map<String, Object> readJson(String file) throws IOException {
        return Json.MAPPER.readValue(Files.readString(CONF.resolve(file)), new TypeReference<Map<String, Object>>() {});
    }

    private static Object normalise(Object o) {
        try {
            return Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(o), Object.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // Fake server: script[] in order, the LAST entry repeats; records requests.
    // ------------------------------------------------------------------

    record Recorded(String method, String path, String body, Map<String, String> headers) {}

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
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> h = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.isEmpty() ? "" : v.get(0)));
            requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getPath(), body, h));
            int i = Math.min(next.getAndIncrement(), script.size() - 1);
            Map<String, Object> entry = script.get(i);
            int status = ((Number) entry.get("status")).intValue();
            String text = entry.containsKey("rawBody") ? (String) entry.get("rawBody") : Json.stringify(entry.get("body"));
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }

        @Override public void close() { server.stop(0); }
    }

    @SuppressWarnings("unchecked")
    private static Media.Style styleFor(Map<String, Object> c, String serverUrl) {
        Map<String, Object> style = (Map<String, Object>) normalise(c.get("style"));
        style.put("baseUrl", ((String) style.get("baseUrl")).replace("{server}", serverUrl));
        return Media.Style.of(style);
    }

    private static Map<String, Object> resultMap(Media.MediaResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> files = new ArrayList<>();
        for (Media.File f : r.files()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("type", f.type()); fm.put("mimeType", f.mimeType()); fm.put("url", f.url());
            files.add(fm);
        }
        m.put("files", files);
        if (r.text() != null) m.put("text", r.text());
        if (r.jobId() != null) m.put("jobId", r.jobId());
        m.put("status", r.status());
        return m;
    }

    // ------------------------------------------------------------------
    // cases.json
    // ------------------------------------------------------------------

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> conformanceCases() throws IOException {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) readJson("cases.json").get("cases");
        assertFalse(cases.isEmpty());
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) tests.add(DynamicTest.dynamicTest((String) c.get("name"), () -> runCase(c)));
        return tests;
    }

    @SuppressWarnings("unchecked")
    private void runCase(Map<String, Object> c) throws Exception {
        Map<String, String> env = c.get("env") == null ? Map.of() : (Map<String, String>) c.get("env");
        Function<String, String> saved = Media.env;
        Media.env = env::get;
        try (FakeServer fs = new FakeServer()) {
            List<Map<String, Object>> script = (List<Map<String, Object>>) c.get("script");
            List<String> progress = Collections.synchronizedList(new ArrayList<>());
            Media media = Media.create(styleFor(c, fs.url()), new Media.Options().onProgress(p -> {
                assertEquals(c.get("verb"), p.verb());
                progress.add(p.status());
            }));
            String verb = (String) c.get("verb");
            Map<String, Object> args = (Map<String, Object>) c.get("args");

            // --- function face ---
            fs.reset(script);
            Media.MediaResult result = null;
            String error = null;
            try {
                result = media.generate(verb, args);
            } catch (Media.MediaException e) {
                error = e.getMessage();
            }
            if (c.containsKey("error")) {
                assertNull(result, "expected an error");
                assertEquals(c.get("error"), error);
            } else {
                assertNull(error, "unexpected error");
                assertEquals(normalise(c.get("result")), normalise(resultMap(result)));
            }
            if (c.containsKey("expectProgress")) assertEquals(c.get("expectProgress"), progress);
            if (c.containsKey("expectRequests")) {
                List<Map<String, Object>> exp = (List<Map<String, Object>>) c.get("expectRequests");
                assertEquals(exp.size(), fs.requests.size(), "request count");
                for (int i = 0; i < exp.size(); i++) {
                    Map<String, Object> e = exp.get(i);
                    Recorded r = fs.requests.get(i);
                    assertEquals(e.get("method"), r.method(), "method #" + i);
                    assertEquals(e.get("path"), r.path(), "path #" + i);
                    if (e.containsKey("body")) {
                        assertEquals(normalise(e.get("body")), Json.MAPPER.readValue(r.body(), Object.class), "body #" + i);
                        assertEquals("application/json", r.headers().get("content-type"));
                    } else {
                        assertNull(r.headers().get("content-type"), "no Content-Type without a body");
                        assertEquals("", r.body());
                    }
                }
            }
            if (c.containsKey("expectHeaders")) {
                assertFalse(fs.requests.isEmpty());
                for (Recorded r : fs.requests) {
                    for (Map.Entry<String, String> h : ((Map<String, String>) c.get("expectHeaders")).entrySet()) {
                        assertEquals(h.getValue(), r.headers().get(h.getKey().toLowerCase()), h.getKey());
                    }
                }
            }

            // --- tool face ---
            Map<String, Object> tool = (Map<String, Object>) c.get("tool");
            if (tool == null) {
                assertTrue(media.tools().stream().noneMatch(x -> x.name().contains(verb)));
                return;
            }
            fs.reset(script);
            Tool t = media.tools().stream().filter(x -> x.name().equals(tool.get("name"))).findFirst().orElseThrow();
            assertEquals("media", t.source());
            ToolResult tr = t.execute(args, new ToolContext());
            assertEquals(tool.get("isError"), tr.isError());
            assertEquals(tool.get("output"), tr.output());
            if (tool.containsKey("parts")) {
                List<Object> parts = new ArrayList<>();
                for (ContentPart p : tr.parts()) parts.add(p);
                assertEquals(normalise(tool.get("parts")), normalise(parts));
            } else {
                assertNull(tr.parts());
            }
            if (tool.containsKey("metadata")) {
                assertEquals(normalise(tool.get("metadata")), normalise(tr.metadata()));
            } else {
                assertNull(tr.metadata());
            }
        } finally {
            Media.env = saved;
        }
    }

    @Test
    void unknownVerbHasNoTool() {
        Media m = Media.create(Media.Style.parse(
                "{\"baseUrl\":\"http://x\",\"verbs\":{\"image\":{\"mode\":\"sync\",\"kind\":\"image\",\"start\":{\"path\":\"/s\"},\"args\":{\"prompt\":\"p\"},\"resultPath\":\"u\"}}}"));
        assertEquals(List.of("generate_image"), m.tools().stream().map(Tool::name).toList());
    }

    // ------------------------------------------------------------------
    // schemas.json — deep equality AND order
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void toolSchemasMatchConformance() throws IOException {
        Map<String, Object> doc = readJson("schemas.json");
        Media m = Media.create(Media.Style.of((Map<String, Object>) doc.get("style")));
        List<Map<String, Object>> expected = (List<Map<String, Object>>) doc.get("tools");
        List<Tool> tools = m.tools();
        assertEquals(expected.size(), tools.size());
        for (int i = 0; i < tools.size(); i++) {
            Tool t = tools.get(i);
            Map<String, Object> e = expected.get(i);
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("name", t.name());
            actual.put("description", t.description());
            actual.put("source", t.source());
            actual.put("inputSchema", t.inputSchema());
            assertEquals(normalise(e), normalise(actual), t.name());
            // property order and required order are contract too
            Map<String, Object> eProps = (Map<String, Object>) ((Map<String, Object>) e.get("inputSchema")).get("properties");
            Map<String, Object> aProps = (Map<String, Object>) t.inputSchema().get("properties");
            assertEquals(new ArrayList<>(eProps.keySet()), new ArrayList<>(aProps.keySet()));
            assertEquals(Json.stringify(e.get("inputSchema")), Json.stringify(t.inputSchema()));
        }
    }

    // ------------------------------------------------------------------
    // Typed verbs, providerOptions, first-style resolution, loadStyle
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void typedVerbProviderOptionsAndFirstStyleWins() throws Exception {
        try (FakeServer fs = new FakeServer()) {
            fs.reset(List.of(Map.of("status", 200, "body", Map.of("u", "https://x/a.mp3"))));
            Media.Style speechOnly = Media.Style.parse(("{\"baseUrl\":\"" + fs.url() + "/one\",\"verbs\":{\"speech\":{\"mode\":\"sync\","
                    + "\"kind\":\"audio\",\"start\":{\"path\":\"/s\"},\"defaults\":{\"fmt\":\"wav\",\"keep\":1},\"args\":{\"text\":\"t\",\"voice\":\"v.id\"},\"resultPath\":\"u\"}}}"));
            Media.Style other = Media.Style.parse(("{\"baseUrl\":\"" + fs.url() + "/two\",\"verbs\":{\"speech\":{\"mode\":\"sync\","
                    + "\"kind\":\"audio\",\"start\":{\"path\":\"/s\"},\"args\":{\"text\":\"t\"},\"resultPath\":\"u\"}}}"));
            Path tmp = Files.createTempFile("style", ".json");
            Files.writeString(tmp, Json.stringify(speechOnly.spec()));
            Media m = Media.create(List.of(Media.loadStyle(tmp.toString()), other), null);
            Media.MediaResult r = m.speech(new Media.SpeechInput("hi", "alloy", Map.of("fmt", "mp3", "extra", true)));
            assertEquals(List.of(new Media.File("audio", "audio/mpeg", "https://x/a.mp3")), r.files());
            assertEquals("completed", r.status());
            assertNull(r.jobId());
            Recorded req = fs.requests.get(0);
            assertEquals("/one/s", req.path());
            assertEquals(Map.of("fmt", "mp3", "keep", 1, "t", "hi", "v", Map.of("id", "alloy"), "extra", true),
                    Json.MAPPER.readValue(req.body(), Map.class));
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void unsupportedArgsNameFirstSortedAndNullIsAbsent() {
        Media m = Media.create(Media.Style.parse(
                "{\"baseUrl\":\"http://127.0.0.1:1\",\"verbs\":{\"image\":{\"mode\":\"sync\",\"kind\":\"image\",\"start\":{\"path\":\"/s\"},\"args\":{\"prompt\":\"p\"},\"resultPath\":\"u\"}}}"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("prompt", "x");
        args.put("zeta", 1);
        args.put("seed", 2);
        args.put("Width", 3);   // 'W' < 'p' < 's' < 'z' in code-point order
        args.put("aNull", null); // null ⇒ absent, never reported
        Media.MediaException e = assertThrows(Media.MediaException.class, () -> m.generate("image", args));
        assertEquals("image: argument \"Width\" not supported by this style", e.getMessage());
    }

    @Test
    void toolkitMediaOptionRegistersTools() {
        Media m = Media.create(Media.Style.parse(
                "{\"baseUrl\":\"http://x\",\"verbs\":{\"transcribe\":{\"mode\":\"sync\",\"kind\":\"text\",\"start\":{\"path\":\"/s\"},\"args\":{\"audio\":\"a\"},\"resultPath\":\"t\"}}}"));
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).media(m))) {
            Tool t = tk.get("transcribe_audio");
            assertNotNull(t);
            assertEquals("media", t.source());
        }
    }

    @Test
    void mediaToolBeatsSameNamedExtraTool() {
        Media m = Media.create(Media.Style.parse(
                "{\"baseUrl\":\"http://x\",\"verbs\":{\"image\":{\"mode\":\"sync\",\"kind\":\"image\",\"start\":{\"path\":\"/s\"},\"args\":{\"prompt\":\"p\"},\"resultPath\":\"u\"}}}"));
        Tool impostor = new Tool() {
            @Override public String name() { return "generate_image"; }
            @Override public String description() { return "impostor"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> a, ToolContext c) { return ToolResult.ok("x"); }
        };
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(impostor).media(m))) {
            assertEquals("media", tk.get("generate_image").source());
        }
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    private static Media pollingForever(FakeServer fs) {
        fs.reset(List.of(Map.of("status", 200, "body", Map.of("id", "j9")),
                Map.of("status", 200, "body", Map.of("st", "processing"))));
        return Media.create(Media.Style.parse("{\"baseUrl\":\"" + fs.url() + "\",\"verbs\":{\"video\":{\"mode\":\"async\",\"kind\":\"video\","
                + "\"start\":{\"path\":\"/v\"},\"args\":{\"prompt\":\"p\"},\"idPath\":\"id\",\"poll\":{\"path\":\"/v/{id}\",\"statusPath\":\"st\","
                + "\"done\":[\"ok\"],\"failed\":[\"x\"],\"intervalMs\":5,\"timeoutMs\":60000},\"resultPath\":\"u\"}}}"));
    }

    @Test
    void cancelTokenStopsPolling() throws Exception {
        try (FakeServer fs = new FakeServer()) {
            Media m = pollingForever(fs);
            LlmClient.CancelToken token = new LlmClient.CancelToken();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try { m.video(new Media.VideoInput("wave"), token); } catch (Throwable e) { thrown.set(e); }
            });
            t.start();
            Thread.sleep(80);
            token.cancel();
            t.join(5000);
            assertFalse(t.isAlive());
            assertInstanceOf(LlmClient.CancelledException.class, thrown.get());
            assertTrue(fs.requests.size() >= 2, "it was polling");
        }
    }

    @Test
    void threadInterruptStopsPolling() throws Exception {
        try (FakeServer fs = new FakeServer()) {
            Media m = pollingForever(fs);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try { m.generate("video", Map.of("prompt", "wave")); } catch (Throwable e) { thrown.set(e); }
            });
            t.start();
            Thread.sleep(80);
            t.interrupt();
            t.join(5000);
            assertFalse(t.isAlive());
            assertInstanceOf(LlmClient.CancelledException.class, thrown.get());
        }
    }

    // ------------------------------------------------------------------
    // RunResult.files — scripted fake LLM, every run path
    // ------------------------------------------------------------------

    private static final Tool SNAP = new Tool() {
        @Override public String name() { return "snap"; }
        @Override public String description() { return "take a picture"; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }
        @Override public String source() { return "custom"; }
        @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return ToolResult.ok("image ready", List.of(
                    ContentPart.text("caption"),
                    new ContentPart("image", null, "image/png", null, "https://x/p.png", null),
                    new ContentPart("audio", null, "audio/wav", "AAAA", null, null)));
        }
    };

    private static final Tool PLAIN = new Tool() {
        @Override public String name() { return "plain"; }
        @Override public String description() { return "text only"; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }
        @Override public String source() { return "custom"; }
        @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return ToolResult.ok("ok"); }
    };

    private static void respond(HttpExchange ex, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String sse(Object... events) {
        StringBuilder sb = new StringBuilder();
        for (Object e : events) sb.append("data: ").append(Json.stringify(e)).append("\n\n");
        return sb.toString();
    }

    /** First turn: tool calls plain(c0) + snap(c1); second turn: final text. */
    @SuppressWarnings("unchecked")
    private static HttpServer fakeLlm() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            Map<String, Object> req = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean stream = Boolean.TRUE.equals(req.get("stream"));
            boolean anthropic = ex.getRequestURI().getPath().endsWith("/messages");
            List<Object> msgs = (List<Object>) req.get("messages");
            boolean second = Json.stringify(msgs).contains(anthropic ? "tool_result" : "\"role\":\"tool\"");
            Map<String, Object> usage = Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2);
            if (!anthropic) {
                if (!stream) {
                    Map<String, Object> msg = second ? Map.of("role", "assistant", "content", "done")
                            : Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                                    Map.of("id", "c0", "type", "function", "function", Map.of("name", "plain", "arguments", "{}")),
                                    Map.of("id", "c1", "type", "function", "function", Map.of("name", "snap", "arguments", "{}"))));
                    respond(ex, Json.stringify(Map.of("choices", List.of(Map.of("message", msg,
                            "finish_reason", second ? "stop" : "tool_calls")), "usage", usage)), "application/json");
                } else if (second) {
                    respond(ex, sse(Map.of("choices", List.of(Map.of("delta", Map.of("content", "done")))),
                            Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", "stop")), "usage", usage))
                            + "data: [DONE]\n\n", "text/event-stream");
                } else {
                    respond(ex, sse(Map.of("choices", List.of(Map.of("delta", Map.of("tool_calls", List.of(
                                    Map.of("index", 0, "id", "c0", "type", "function", "function", Map.of("name", "plain", "arguments", "{}")),
                                    Map.of("index", 1, "id", "c1", "type", "function", "function", Map.of("name", "snap", "arguments", "{}"))))))),
                            Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", "tool_calls")), "usage", usage))
                            + "data: [DONE]\n\n", "text/event-stream");
                }
                return;
            }
            Map<String, Object> au = Map.of("input_tokens", 1, "output_tokens", 1);
            if (!stream) {
                List<Object> content = second ? List.of(Map.of("type", "text", "text", "done"))
                        : List.of(Map.of("type", "tool_use", "id", "c0", "name", "plain", "input", Map.of()),
                                  Map.of("type", "tool_use", "id", "c1", "name", "snap", "input", Map.of()));
                respond(ex, Json.stringify(Map.of("role", "assistant", "content", content,
                        "stop_reason", second ? "end_turn" : "tool_use", "usage", au)), "application/json");
                return;
            }
            StringBuilder sb = new StringBuilder();
            java.util.function.BiConsumer<String, Object> ev = (name, data) ->
                    sb.append("event: ").append(name).append("\ndata: ").append(Json.stringify(data)).append("\n\n");
            ev.accept("message_start", Map.of("type", "message_start", "message", Map.of("usage", au)));
            if (second) {
                ev.accept("content_block_start", Map.of("type", "content_block_start", "index", 0, "content_block", Map.of("type", "text", "text", "")));
                ev.accept("content_block_delta", Map.of("type", "content_block_delta", "index", 0, "delta", Map.of("type", "text_delta", "text", "done")));
                ev.accept("content_block_stop", Map.of("type", "content_block_stop", "index", 0));
            } else {
                String[][] calls = {{"c0", "plain"}, {"c1", "snap"}};
                for (int i = 0; i < 2; i++) {
                    ev.accept("content_block_start", Map.of("type", "content_block_start", "index", i,
                            "content_block", Map.of("type", "tool_use", "id", calls[i][0], "name", calls[i][1], "input", Map.of())));
                    ev.accept("content_block_delta", Map.of("type", "content_block_delta", "index", i,
                            "delta", Map.of("type", "input_json_delta", "partial_json", "{}")));
                    ev.accept("content_block_stop", Map.of("type", "content_block_stop", "index", i));
                }
            }
            ev.accept("message_delta", Map.of("type", "message_delta", "delta", Map.of("stop_reason", second ? "end_turn" : "tool_use"), "usage", au));
            ev.accept("message_stop", Map.of("type", "message_stop"));
            respond(ex, sb.toString(), "text/event-stream");
        });
        s.start();
        return s;
    }

    private static void assertFiles(LlmClient.RunResult r) {
        assertEquals("done", r.text);
        assertEquals(List.of(
                new LlmClient.RunFile("image", "image/png", "https://x/p.png", null, "snap", "c1"),
                new LlmClient.RunFile("audio", "audio/wav", null, "AAAA", "snap", "c1")), r.files);
        assertEquals(2, r.toolCalls.size());
        assertNull(r.toolCalls.get(0).parts, "parts absent when the ToolResult had none");
        assertEquals(3, r.toolCalls.get(1).parts.size());
        assertEquals("c1", r.toolCalls.get(1).callId);
    }

    @Test
    void runResultFilesInEveryRunPath() throws Exception {
        HttpServer s = fakeLlm();
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(SNAP, PLAIN))) {
            for (String style : List.of("openai", "anthropic")) {
                LlmClient c = LlmClient.create(new LlmClient.Options().baseUrl(base).style(style).model("m").apiKey("k"));
                assertFiles(c.run("go", tk));
                assertFiles(c.stream("go", tk, ev -> {}));
            }
        } finally {
            s.stop(0);
        }
    }

    @Test
    void runResultFilesEmptyWithoutMedia() {
        LlmClient c = InProcess.createClient(new InProcess.Options().model("m")
                .generate(req -> InProcess.Response.content("hi")));
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            LlmClient.RunResult r = c.run("x", tk);
            assertNotNull(r.files);
            assertTrue(r.files.isEmpty());
        }
    }
}
