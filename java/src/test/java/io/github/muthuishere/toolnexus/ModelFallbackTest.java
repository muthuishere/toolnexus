package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** §8 Resilience: opt-in model fallback chain on overload (issue #190). Hermetic. */
class ModelFallbackTest {

    private HttpServer server;
    private final List<String> models = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        LlmClient.envLookup = System::getenv;
    }

    interface Handler { void handle(HttpExchange ex, String model) throws IOException; }

    private int start(Handler h) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String model = String.valueOf(Json.toMap(body).get("model"));
            models.add(model);
            h.handle(ex, model);
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    /** m1 answers {@code status}; anything else answers OK. */
    private int overloadM1(int status) throws IOException {
        return start((ex, model) -> {
            if ("m1".equals(model)) respond(ex, status, "application/json", "{\"error\":\"busy\"}");
            else respond(ex, 200, "application/json", OK);
        });
    }

    private LlmClient.Options opts(int port) {
        return new LlmClient.Options().baseUrl("http://127.0.0.1:" + port).style("openai")
                .model("m1").apiKey("k").retries(0).retryBaseMs(1);
    }

    @Test
    void overload529FallsBackToNextModel() throws IOException {
        int port = overloadM1(529);
        List<String> afterModels = new ArrayList<>();
        List<String> metricModels = new ArrayList<>();
        LlmClient.Hooks hooks = new LlmClient.Hooks();
        hooks.afterLLM = e -> afterModels.add(e.model());
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port).fallbackModels(List.of("", "m1", "m2", "m2")).hooks(hooks)
                .onMetric(e -> { if (e instanceof LlmClient.MetricEvent.Llm l) metricModels.add(l.model() + ":" + l.status()); }));
        LlmClient.RunResult r = c.run("hi", tk);
        assertEquals("ok", r.text);
        assertEquals("m2", r.model);
        assertEquals(List.of("m1", "m2"), models);
        assertEquals(List.of("m2"), afterModels);
        assertEquals(List.of("m2:ok"), metricModels);
        tk.close();
    }

    @Test
    void rateLimit429NeverSwitches() throws IOException {
        int port = overloadM1(429);
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port).fallbackModels(List.of("m2")));
        assertThrows(RuntimeException.class, () -> c.run("hi", tk));
        assertEquals(List.of("m1"), models);
        tk.close();
    }

    @Test
    void onErrorFailOn503DoesNotSwitch() throws IOException {
        int port = overloadM1(503);
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port).retries(2).fallbackModels(List.of("m2"))
                .onError(i -> LlmClient.Tier.FAIL));
        assertThrows(RuntimeException.class, () -> c.run("hi", tk));
        assertEquals(List.of("m1"), models);
        tk.close();
    }

    @Test
    void retryBudgetSpentBeforeSwitching() throws IOException {
        int port = overloadM1(503);
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port).retries(1).fallbackModels(List.of("m2")));
        assertEquals("m2", c.run("hi", tk).model);
        assertEquals(List.of("m1", "m1", "m2"), models);
        tk.close();
    }

    @Test
    void killSwitchDisablesChain() throws IOException {
        int port = overloadM1(529);
        LlmClient.envLookup = k -> "TOOLNEXUS_DISABLE_MODEL_FALLBACK".equals(k) ? "TRUE" : null;
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port).fallbackModels(List.of("m2")));
        assertThrows(RuntimeException.class, () -> c.run("hi", tk));
        assertEquals(List.of("m1"), models);
        tk.close();
    }

    @Test
    void chainExhaustedSurfacesLastError() throws IOException {
        int port = start((ex, m) -> respond(ex, 503, "application/json", "{\"error\":\"busy\"}"));
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port).fallbackModels(List.of("m2", "m3")));
        RuntimeException e = assertThrows(RuntimeException.class, () -> c.run("hi", tk));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
        assertEquals(List.of("m1", "m2", "m3"), models);
        tk.close();
    }

    @Test
    void absentOptionUnchanged() throws IOException {
        int port = overloadM1(529);
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port));
        assertThrows(RuntimeException.class, () -> c.run("hi", tk));
        assertEquals(List.of("m1"), models);
        tk.close();
    }

    @Test
    void streamEmitsFallbackBeforeText() throws IOException {
        int port = start((ex, model) -> {
            if ("m1".equals(model)) { respond(ex, 529, "application/json", "{\"error\":\"busy\"}"); return; }
            respond(ex, 200, "text/event-stream",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hey\"}}]}\n\ndata: [DONE]\n\n");
        });
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port).fallbackModels(List.of("m2")));
        List<LlmClient.StreamEvent> events = new ArrayList<>();
        LlmClient.RunResult r = c.stream("hi", tk, events::add);
        assertEquals("hey", r.text);
        assertEquals("m2", r.model);
        assertEquals(LlmClient.StreamEvent.Kind.FALLBACK, events.get(0).type());
        assertEquals("m1", events.get(0).from());
        assertEquals("m2", events.get(0).to());
        assertEquals(LlmClient.StreamEvent.Kind.TEXT, events.get(1).type());
        assertEquals(1, events.stream().filter(e -> e.type() == LlmClient.StreamEvent.Kind.FALLBACK).count());
        tk.close();
    }

    @Test
    void anthropicStyleFallsBack() throws IOException {
        int port = start((ex, model) -> {
            if ("m1".equals(model)) { respond(ex, 529, "application/json", "{\"error\":\"overloaded\"}"); return; }
            respond(ex, 200, "application/json",
                    "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}");
        });
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient c = LlmClient.create(opts(port).style("anthropic").fallbackModels(List.of("m2")));
        assertEquals("m2", c.run("hi", tk).model);
        assertEquals(List.of("m1", "m2"), models);
        tk.close();
    }
}
