package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration suite against the shared deterministic mock model (tools/mock-llm/mock_llm.py).
 * Starts the mock as a subprocess unless TN_MOCK_LLM_URL is set; skips when python3 is missing.
 */
class MockLlmIntegrationTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final String DEFAULT_TEXT = "This is a reply from the toolnexus mock model.";
    private static Process proc;
    private static String base; // http://127.0.0.1:<port>/v1
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void startMock() throws Exception {
        String env = System.getenv("TN_MOCK_LLM_URL");
        if (env != null && !env.isBlank()) {
            base = env.replaceAll("/+$", "");
            return;
        }
        Process p;
        try {
            p = new ProcessBuilder("python3", REPO.resolve("tools/mock-llm/mock_llm.py").toString(),
                    "--port", "0", "--quiet").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (java.io.IOException e) {
            Assumptions.abort("python3 not available; skipping mock-LLM integration suite: " + e.getMessage());
            return;
        }
        proc = p;
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        assertNotNull(line, "mock printed nothing");
        int i = line.indexOf("http://");
        assertTrue(i >= 0, "unexpected mock banner: " + line);
        base = line.substring(i).trim();
    }

    @AfterAll
    static void stopMock() {
        if (proc != null) proc.destroy();
    }

    private static String http(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), path + ": " + res.body());
        return res.body();
    }

    private static LlmClient client(String style) {
        return LlmClient.create(new LlmClient.Options()
                .baseUrl(base).style(style).model("mock-model").apiKey("mock-key")
                .retries(2).retryBaseMs(5));
    }

    private static final List<Map<String, Object>> addCalls = new CopyOnWriteArrayList<>();

    private static Toolkit addToolkit() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("a", Map.of("type", "integer"), "b", Map.of("type", "integer")),
                "required", List.of("a", "b"));
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        tk.register(NativeTool.of("add", "Add two integers", schema, args -> {
            addCalls.add(args);
            long a = ((Number) args.get("a")).longValue();
            long b = ((Number) args.get("b")).longValue();
            return String.valueOf(a + b);
        }));
        return tk;
    }

    private void toolRun(String style) {
        addCalls.clear();
        try (Toolkit tk = addToolkit()) {
            LlmClient.RunResult res = client(style).run("add 1 and 1", tk);
            assertEquals(1, addCalls.size(), "tool executed once");
            assertEquals(1, ((Number) addCalls.get(0).get("a")).intValue());
            assertEquals(1, ((Number) addCalls.get(0).get("b")).intValue());
            assertEquals("Tool result: 2", res.text);
        }
    }

    @Test void openaiToolRun() { toolRun("openai"); }

    @Test void anthropicToolRun() { toolRun("anthropic"); }

    private void streamText(String style) {
        StringBuilder sb = new StringBuilder();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            LlmClient.RunResult res = client(style).stream("hello", tk, ev -> {
                if (ev.type() == LlmClient.StreamEvent.Kind.TEXT && ev.delta() != null) sb.append(ev.delta());
            });
            assertEquals(DEFAULT_TEXT, sb.toString());
            assertEquals(DEFAULT_TEXT, res.text);
        }
    }

    @Test void openaiStreaming() { streamText("openai"); }

    @Test void anthropicStreaming() { streamText("anthropic"); }

    @Test
    void imageAttachment() throws Exception {
        Path png = REPO.resolve("examples/media/fixture.png");
        String sha = Files.readString(REPO.resolve("examples/media/fixture.png.sha256")).trim().split("\\s+")[0];
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            for (String style : List.of("openai", "anthropic")) {
                LlmClient.RunResult res = client(style).run(
                        List.of(ContentPart.text("what is this?"), ContentPart.ofFile(png)), tk);
                assertTrue(res.text.contains("image/png 82 bytes sha256=" + sha.substring(0, 12)),
                        style + ": " + res.text);
            }
        }
    }

    @Test
    void resilienceRetriesQueued429() throws Exception {
        http("POST", "/_mock/reset", "{}");
        http("POST", "/_mock/faults",
                "{\"path\":\"/chat/completions\",\"status\":429,\"times\":1,\"retryAfter\":\"0\"}");
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            LlmClient.RunResult res = client("openai").run("hi", tk);
            assertEquals(DEFAULT_TEXT, res.text);
        }
        Map<String, Object> counts = Json.toMap(http("GET", "/_mock/requests", null));
        Object routes = counts.containsKey("counts") ? counts.get("counts") : counts;
        assertEquals(2, ((Number) ((Map<?, ?>) routes).get("/chat/completions")).intValue(), counts.toString());
    }

    private static Map<String, Classifier.Question> questions() {
        Map<String, Classifier.Question> q = new LinkedHashMap<>();
        q.put("urgent", new Classifier.NoulQuestion("Is this urgent?", null));
        q.put("team", new Classifier.ChoiceQuestion("Which team owns this?",
                Map.of("billing", "Billing problems", "infra", "Servers and outages", "sales", "Buying")));
        q.put("severity", new Classifier.ScoreQuestion("How severe is it?", List.of("low", "medium", "high")));
        return q;
    }

    private void assertDecision(Classifier c) {
        String state = "The production database is down and customers cannot pay.";
        Classifier.Decision d = c.evaluate(state, questions());
        assertEquals(0.95, d.noul("urgent").noul(), 1e-9);
        Classifier.ChoiceAnswer ch = d.choice("team");
        assertTrue(List.of("billing", "infra", "sales").contains(ch.choice()), ch.choice());
        assertEquals(1.0, ch.probabilities().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertEquals(0.7, ch.probabilities().get(ch.choice()), 1e-9);
        double s = d.score("severity").score();
        assertTrue(s >= 0 && s <= 2, "score in bounds: " + s);
        Classifier.Decision again = c.evaluate(state, questions());
        assertEquals(ch.choice(), again.choice("team").choice(), "deterministic choice");
        assertEquals(s, again.score("severity").score(), 1e-12, "deterministic score");
    }

    @Test
    void classifierCustomSystemOne() {
        assertDecision(Classifier.create(new Classifier.Options()
                .backend(Classifier.BACKEND_CUSTOM).baseUrl(base)
                .supports(new Classifier.Supports(false)).model("mock-jev")));
    }

    @Test
    void classifierCloudflare() {
        String origin = base.replaceAll("/v1$", "");
        assertDecision(Classifier.create(new Classifier.Options()
                .backend(Classifier.BACKEND_CLOUDFLARE).baseUrl(origin + "/v1/accounts/acc/ai/run")
                .apiKeyEnv("TN_MOCK_UNSET_KEY")));
    }

    @Test
    void mediaAllVerbs() throws Exception {
        Path f = Files.createTempFile("mock-media-style", ".json");
        try {
            Files.writeString(f, http("GET", "/_mock/media-style.json", null));
            Media m = Media.create(Media.loadStyle(f.toString()));

            Media.MediaResult img = m.image(new Media.ImageInput("a red square"));
            assertFile(img, "image/png", "/files/image.png");
            Media.MediaResult vid = m.video(new Media.VideoInput("a short clip"));
            assertFile(vid, "video/mp4", "/files/video.mp4");
            Media.MediaResult sp = m.speech(new Media.SpeechInput("hello there"));
            assertFile(sp, "audio/wav", "/files/speech.wav");
            String audio = base + "/files/speech.wav";
            Media.MediaResult tr = m.transcribe(new Media.TranscribeInput(audio));
            assertEquals("Mock transcript of " + audio + ".", tr.text());
            Media.MediaResult cv = m.cloneVoice(new Media.CloneVoiceInput("narrator", List.of(audio)));
            assertEquals("voice-" + sha256("narrator").substring(0, 8), cv.text());
            Media.MediaResult tk = m.talking(new Media.TalkingInput(base + "/files/image.png", audio));
            assertFile(tk, "video/mp4", "talking.mp4");

            Media.MediaException ex = assertThrows(Media.MediaException.class,
                    () -> m.image(new Media.ImageInput("please [fail] now")));
            assertTrue(ex.getMessage().contains("mock: job asked to fail"), ex.getMessage());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    private static void assertFile(Media.MediaResult r, String mime, String suffix) {
        assertFalse(r.files().isEmpty(), "no files");
        Media.File file = r.files().get(0);
        assertEquals(mime, file.mimeType());
        assertTrue(file.url().endsWith(suffix), file.url());
    }

    private static String sha256(String s) throws Exception {
        byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
