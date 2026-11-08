package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** add-tool-concurrency (SPEC §8 "Tool scheduling"), Java port of golang/tool_concurrency_test.go. */
class ToolConcurrencyTest {

    /** Answers turn 1 with one call per name and turn 2 with "done", in all four wire modes. */
    static HttpServer server(List<String> names) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger turn = new AtomicInteger();
        srv.createContext("/", ex -> {
            Map<String, Object> body = Json.parseObjectLoose(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean first = turn.incrementAndGet() == 1;
            boolean anth = ex.getRequestURI().getPath().contains("messages");
            boolean stream = Boolean.TRUE.equals(body.get("stream"));
            String out;
            String type;
            if (!stream) {
                type = "application/json";
                if (anth) {
                    List<Object> blocks = new ArrayList<>();
                    for (int i = 0; i < names.size(); i++) blocks.add(Map.of("type", "tool_use", "id", "c" + (i + 1), "name", names.get(i), "input", Map.of()));
                    out = Json.stringify(first ? Map.of("content", blocks, "stop_reason", "tool_use")
                            : Map.of("content", List.of(Map.of("type", "text", "text", "done")), "stop_reason", "end_turn"));
                } else {
                    List<Object> calls = new ArrayList<>();
                    for (int i = 0; i < names.size(); i++) calls.add(Map.of("id", "c" + (i + 1), "type", "function",
                            "function", Map.of("name", names.get(i), "arguments", "{}")));
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", "assistant");
                    if (first) { msg.put("content", null); msg.put("tool_calls", calls); } else msg.put("content", "done");
                    out = Json.stringify(Map.of("choices", List.of(Map.of("message", msg))));
                }
            } else {
                type = "text/event-stream";
                List<Object> ev = new ArrayList<>();
                if (!anth) {
                    if (first) {
                        for (int i = 0; i < names.size(); i++) ev.add(Map.of("choices", List.of(Map.of("delta", Map.of("tool_calls", List.of(Map.of(
                                "index", i, "id", "c" + (i + 1), "type", "function", "function", Map.of("name", names.get(i), "arguments", "{}"))))))));
                        ev.add(Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", "tool_calls"))));
                    } else {
                        ev.add(Map.of("choices", List.of(Map.of("delta", Map.of("content", "done")))));
                        ev.add(Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", "stop"))));
                    }
                } else {
                    if (first) {
                        for (int i = 0; i < names.size(); i++) {
                            ev.add(Map.of("type", "content_block_start", "index", i, "content_block", Map.of("type", "tool_use", "id", "c" + (i + 1), "name", names.get(i))));
                            ev.add(Map.of("type", "content_block_delta", "index", i, "delta", Map.of("type", "input_json_delta", "partial_json", "{}")));
                            ev.add(Map.of("type", "content_block_stop", "index", i));
                        }
                        ev.add(Map.of("type", "message_delta", "delta", Map.of("stop_reason", "tool_use")));
                    } else {
                        ev.add(Map.of("type", "content_block_start", "index", 0, "content_block", Map.of("type", "text")));
                        ev.add(Map.of("type", "content_block_delta", "index", 0, "delta", Map.of("type", "text_delta", "text", "done")));
                        ev.add(Map.of("type", "message_delta", "delta", Map.of("stop_reason", "end_turn")));
                    }
                    ev.add(Map.of("type", "message_stop"));
                }
                StringBuilder sb = new StringBuilder();
                for (Object e : ev) sb.append("data: ").append(Json.stringify(e)).append("\n\n");
                if (!anth) sb.append("data: [DONE]\n\n");
                out = sb.toString();
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("content-type", type);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        srv.start();
        return srv;
    }

    static final class Tracker {
        int inflight;
        int peak;
        final List<String> log = new ArrayList<>();

        Tool tool(String name, boolean readOnly) {
            return tool(name, readOnly, 60);
        }

        Tool tool(String name, boolean readOnly, long ms) {
            Tool t = NativeTool.of(name, name, Map.of("type", "object", "properties", Map.of()), (Map<String, Object> a) -> {
                synchronized (this) {
                    inflight++;
                    peak = Math.max(peak, inflight);
                    log.add("start " + name);
                }
                try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                synchronized (this) {
                    inflight--;
                    log.add("end " + name);
                }
                return "out " + name;
            });
            return readOnly ? Tool.withHints(t, ToolHints.READ_ONLY) : t;
        }
    }

    static List<String> run(String style, boolean stream, List<String> names, List<Tool> tools, Integer cap) throws IOException {
        return run(style, stream, names, tools, cap, false);
    }

    static List<String> run(String style, boolean stream, List<String> names, List<Tool> tools, Integer cap, boolean outputs) throws IOException {
        HttpServer srv = server(names);
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(tools.toArray(new Tool[0])));
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort() + ("openai".equals(style) ? "/v1" : "");
            LlmClient.Options o = new LlmClient.Options().baseUrl(base).style(style).model("m").apiKey("k");
            if (cap != null) o.maxToolConcurrency(cap);
            LlmClient c = LlmClient.create(o);
            LlmClient.RunResult r = stream ? c.stream("go", tk, ev -> {}) : c.run("go", tk);
            List<String> got = new ArrayList<>();
            for (LlmClient.ToolCall tc : r.toolCalls) got.add(outputs ? tc.output : tc.name);
            return got;
        } finally {
            tk.close();
            srv.stop(0);
        }
    }

    static List<String> reads(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= n; i++) names.add("r" + i);
        return names;
    }

    static List<Tool> tools(Tracker tr, List<String> names) {
        List<Tool> out = new ArrayList<>();
        for (String n : names) out.add(tr.tool(n, n.startsWith("r")));
        return out;
    }

    @ParameterizedTest
    @CsvSource({"openai,false", "anthropic,false", "openai,true", "anthropic,true"})
    void unsafeCallsSerialize(String style, boolean stream) throws IOException {
        Tracker tr = new Tracker();
        List<String> names = List.of("w1", "w2");
        List<String> got = run(style, stream, names, tools(tr, names), null);
        assertEquals(1, tr.peak);
        assertEquals(List.of("start w1", "end w1", "start w2", "end w2"), tr.log);
        assertEquals(names, got);
    }

    @ParameterizedTest
    @CsvSource({"openai,false", "anthropic,false", "openai,true", "anthropic,true"})
    void readOnlyCallsOverlap(String style, boolean stream) throws IOException {
        Tracker tr = new Tracker();
        List<String> names = reads(6);
        List<String> got = run(style, stream, names, tools(tr, names), null);
        assertEquals(6, tr.peak);
        assertEquals(names, got);
    }

    @ParameterizedTest
    @CsvSource({"openai,false", "anthropic,false", "openai,true", "anthropic,true"})
    void resultsKeepEmittedOrderWhenCallsFinishOutOfOrder(String style, boolean stream) throws IOException {
        Tracker tr = new Tracker();
        List<String> names = List.of("r1", "r2", "r3", "r4");
        // r1 is slowest, r4 fastest: completion order is the reverse of emitted order
        List<Tool> ts = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) ts.add(tr.tool(names.get(i), true, 40L * (names.size() - i)));
        List<String> outputs = run(style, stream, names, ts, null, true);
        assertEquals(List.of("end r4", "end r3", "end r2", "end r1"), tr.log.stream().filter(e -> e.startsWith("end ")).toList());
        // each call keeps its own output, in emitted order
        assertEquals(List.of("out r1", "out r2", "out r3", "out r4"), outputs);
    }

    @ParameterizedTest
    @CsvSource({"openai,false", "anthropic,false", "openai,true", "anthropic,true"})
    void capIsHonoured(String style, boolean stream) throws IOException {
        Tracker tr = new Tracker();
        List<String> names = reads(6);
        run(style, stream, names, tools(tr, names), 2);
        assertEquals(2, tr.peak);
    }

    @ParameterizedTest
    @CsvSource({"openai,false", "anthropic,false", "openai,true", "anthropic,true"})
    void mixedRunsKeepOrder(String style, boolean stream) throws IOException {
        Tracker tr = new Tracker();
        List<String> names = List.of("r1", "r2", "w", "r3", "r4");
        List<String> got = run(style, stream, names, tools(tr, names), null);
        for (String r : List.of("r1", "r2")) assertTrue(tr.log.indexOf("end " + r) < tr.log.indexOf("start w"), tr.log.toString());
        for (String r : List.of("r3", "r4")) assertTrue(tr.log.indexOf("start " + r) > tr.log.indexOf("end w"), tr.log.toString());
        assertEquals(2, tr.peak);
        assertEquals(names, got);
    }

    @Test
    void envOverrideAndFallback() {
        assertEquals(3, ToolScheduler.resolveMaxToolConcurrency(null, k -> "3"));
        assertEquals(4, ToolScheduler.resolveMaxToolConcurrency(4, k -> "3"));
        for (String bad : new String[] {"0", "-2", "abc", "", null}) {
            assertEquals(10, ToolScheduler.resolveMaxToolConcurrency(null, k -> bad), String.valueOf(bad));
        }
        assertEquals(10, ToolScheduler.resolveMaxToolConcurrency(0, k -> null));
    }

    @Test
    void safetyRule() {
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        try {
            Map<String, Boolean> want = new LinkedHashMap<>();
            for (String s : List.of("read", "grep", "glob", "webfetch")) want.put(s, true);
            for (String s : List.of("bash", "write", "edit", "apply_patch")) want.put(s, false);
            want.forEach((name, safe) -> {
                Tool t = tk.get(name);
                assertNotNull(t, name);
                assertEquals(safe, ToolScheduler.isConcurrencySafe(t, Map.of()), name);
            });
        } finally {
            tk.close();
        }
        Tool plain = NativeTool.of("x", "", null, (Map<String, Object> a) -> "");
        assertFalse(ToolScheduler.isConcurrencySafe(plain, Map.of()), "no hints ⇒ unsafe");
        Tool own = Tool.withConcurrencySafe(Tool.withHints(plain, ToolHints.READ_ONLY), a -> Boolean.TRUE.equals(a.get("dry")));
        assertFalse(ToolScheduler.isConcurrencySafe(own, Map.of()));
        assertTrue(ToolScheduler.isConcurrencySafe(own, Map.of("dry", true)));
    }
}
