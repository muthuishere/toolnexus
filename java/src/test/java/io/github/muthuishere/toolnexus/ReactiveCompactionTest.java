package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** SPEC §7F.1: replays {@code conformance/reactive_compaction/cases.json} (run + stream, both styles). */
class ReactiveCompactionTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cases() throws Exception {
        Path file = Path.of(TestFixtures.fixture("")).getParent().resolve("conformance/reactive_compaction/cases.json");
        return Json.toMap(Files.readString(file));
    }

    private static int num(Object o) { return ((Number) o).intValue(); }

    @SuppressWarnings("unchecked")
    @TestFactory
    List<DynamicTest> detection() throws Exception {
        List<DynamicTest> out = new ArrayList<>();
        for (Object o : (List<Object>) cases().get("detection")) {
            Map<String, Object> c = (Map<String, Object>) o;
            out.add(DynamicTest.dynamicTest((String) c.get("name"), () ->
                    assertEquals(c.get("overflow"), LlmClient.isContextOverflow(num(c.get("status")), (String) c.get("body")))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    @TestFactory
    List<DynamicTest> loops() throws Exception {
        Map<String, Object> all = cases();
        List<DynamicTest> out = new ArrayList<>();
        for (String style : List.of("openai", "anthropic")) {
            for (boolean stream : new boolean[]{false, true}) {
                for (Object o : (List<Object>) all.get("loops")) {
                    Map<String, Object> c = (Map<String, Object>) o;
                    out.add(DynamicTest.dynamicTest(style + (stream ? " stream " : " run ") + c.get("name"),
                            () -> runCase(all, c, style, stream)));
                }
            }
        }
        return out;
    }

    private static String reply(String style, boolean stream, String kind, int n) {
        boolean tool = kind.equals("tool");
        if (style.equals("openai")) {
            if (!stream) {
                String msg = tool
                        ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c" + n + "\",\"type\":\"function\",\"function\":{\"name\":\"probe\",\"arguments\":\"{}\"}}]}"
                        : "{\"role\":\"assistant\",\"content\":\"done\"}";
                return "{\"choices\":[{\"message\":" + msg + "}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            }
            String delta = tool
                    ? "{\"tool_calls\":[{\"index\":0,\"id\":\"c" + n + "\",\"function\":{\"name\":\"probe\",\"arguments\":\"{}\"}}]}"
                    : "{\"content\":\"done\"}";
            return "data: {\"choices\":[{\"delta\":" + delta + "}]}\n\ndata: [DONE]\n\n";
        }
        String block = tool
                ? "{\"type\":\"tool_use\",\"id\":\"c" + n + "\",\"name\":\"probe\",\"input\":{}}"
                : "{\"type\":\"text\",\"text\":\"done\"}";
        if (!stream) {
            return "{\"content\":[" + block + "],\"stop_reason\":\"" + (tool ? "tool_use" : "end_turn") + "\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        }
        String start = tool
                ? "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"c" + n + "\",\"name\":\"probe\",\"input\":{}}}"
                : "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}";
        String delta = tool
                ? "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}"
                : "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}";
        return "data: " + start + "\n\ndata: " + delta + "\n\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + (tool ? "tool_use" : "end_turn") + "\"}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
    }

    @SuppressWarnings("unchecked")
    private static void runCase(Map<String, Object> all, Map<String, Object> c, String style, boolean stream) throws Exception {
        List<String> script = (List<String>) c.get("script");
        Map<String, Object> ovf = (Map<String, Object>) ((Map<String, Object>) all.get("overflowBodies")).get(style);
        Map<String, Object> bad = (Map<String, Object>) all.get("bad400");
        AtomicInteger calls = new AtomicInteger();
        List<Integer> sentSizes = new ArrayList<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (sentSizes) {
                sentSizes.add(((List<Object>) Json.toMap(req).get("messages")).size());
            }
            int n = calls.getAndIncrement();
            String kind = n < script.size() ? script.get(n) : "text";
            int status = 200;
            String body;
            String ctype = stream ? "text/event-stream" : "application/json";
            if (kind.equals("overflow")) { status = num(ovf.get("status")); body = (String) ovf.get("body"); ctype = "application/json"; }
            else if (kind.equals("bad400")) { status = num(bad.get("status")); body = (String) bad.get("body"); ctype = "application/json"; }
            else body = reply(style, stream, kind, n);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", ctype);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        srv.start();
        AtomicInteger hookCalls = new AtomicInteger();
        boolean noop = Boolean.TRUE.equals(c.get("hookNoop"));
        try {
            Tool probe = NativeTool.of("probe", "probe", Map.of("type", "object", "properties", Map.of()), (Map<String, Object> a) -> "ok");
            Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(probe));
            LlmClient.InMemoryConversationStore store = new LlmClient.InMemoryConversationStore();
            LlmClient.Options opts = new LlmClient.Options().store(store)
                    .baseUrl("http://127.0.0.1:" + srv.getAddress().getPort())
                    .style(style).model("m").apiKey("k").retries(0);
            if (Boolean.TRUE.equals(c.get("option"))) {
                opts.compactOnOverflow(ev -> {
                    hookCalls.incrementAndGet();
                    if (noop) return null;
                    return new LlmClient.LLMOverride(new ArrayList<>(ev.messages().subList(1, ev.messages().size())), null);
                });
            }
            LlmClient client = LlmClient.create(opts);
            List<Object> history = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", i % 2 == 0 ? "user" : "assistant");
                m.put("content", "h" + i);
                history.add(m);
            }
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            java.util.concurrent.Callable<LlmClient.RunResult> go = () -> {
                if (!stream) return client.run("go", tk, history);
                store.save("conv", history);
                return client.stream("go", tk, e -> { }, "conv");
            };
            if (expect.containsKey("text")) {
                LlmClient.RunResult r = go.call();
                assertEquals(expect.get("text"), r.text);
                assertEquals(num(expect.get("compactions")), hookCalls.get());
            } else {
                RuntimeException e = assertThrows(RuntimeException.class, go::call);
                if (expect.get("error").equals("ContextOverflowError")) {
                    if (!(e instanceof LlmClient.ContextOverflowError coe)) { fail("got " + e); return; }
                    assertEquals(num(expect.get("compactions")), coe.compactions);
                    assertEquals(expect.get("message"), coe.getMessage());
                    assertEquals(num(ovf.get("status")), coe.status);
                } else {
                    if (!(e instanceof LlmClient.ProviderException pe)) { fail("got " + e); return; }
                    assertEquals(num(expect.get("status")), pe.status);
                    if (!Boolean.TRUE.equals(c.get("option"))) assertEquals(0, hookCalls.get());
                }
            }
            assertEquals(num(c.get("calls")), calls.get());
            // Each compaction retry sends strictly fewer messages than the overflowing request.
            for (int i = 1; i < script.size() && i < sentSizes.size(); i++) {
                if (script.get(i - 1).equals("overflow") && !noop) assertTrue(sentSizes.get(i) < sentSizes.get(i - 1));
            }
            tk.close();
        } finally {
            srv.stop(0);
        }
    }
}
