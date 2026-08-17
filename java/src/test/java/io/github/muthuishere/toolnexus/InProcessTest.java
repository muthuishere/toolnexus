package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InProcess.createClient — a model in this JVM, with no wire configuration.
 * openspec/changes/add-in-process-client. Mirrored in all seven ports.
 */
class InProcessTest {

    private static Tool addTool() {
        return NativeTool.of("add", "Add two numbers.",
                Map.of("type", "object",
                        "properties", Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number")),
                        "required", List.of("a", "b")),
                args -> String.valueOf((long) (((Number) args.get("a")).doubleValue()
                        + ((Number) args.get("b")).doubleValue())));
    }

    @Test
    void noWireConfigurationIsRequired() throws IOException {
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        // No baseUrl. No apiKey. No style. That is the whole point.
        LlmClient client = InProcess.createClient(new InProcess.Options()
                .model("my-local")
                .generate(req -> InProcess.Response.content("hello from in-process")));

        LlmClient.RunResult r = client.run("hi", tk);
        assertEquals("hello from in-process", r.text);
        assertEquals("done", r.status);
        tk.close();
    }

    @Test
    void generateSeesTheAssembledRequest() throws IOException {
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        tk.register(addTool());
        AtomicReference<InProcess.Request> seen = new AtomicReference<>();

        LlmClient client = InProcess.createClient(new InProcess.Options()
                .model("my-local").systemPrompt("You are terse.")
                .generate(req -> { seen.set(req); return InProcess.Response.content("ok"); }));
        client.run("What is 2 + 3?", tk);

        assertEquals("my-local", seen.get().model);
        assertTrue(seen.get().tools.size() >= 1, "tool schemas are offered");
        String messages = Json.stringify(seen.get().messages);
        assertTrue(messages.contains("terse"), messages);
        assertTrue(messages.contains("2 + 3"), messages);
        tk.close();
    }

    @Test
    void toolCallsLoopBackWithTheResult() throws IOException {
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        tk.register(addTool());
        AtomicInteger n = new AtomicInteger();

        LlmClient client = InProcess.createClient(new InProcess.Options().model("m")
                .generate(req -> n.incrementAndGet() == 1
                        ? InProcess.Response.toolCalls(new InProcess.ToolCall("add", Map.of("a", 2, "b", 3)))
                        : InProcess.Response.content("the answer is 5")));

        LlmClient.RunResult r = client.run("What is 2 + 3?", tk);
        assertEquals(1, r.toolCalls.size());
        assertEquals("add", r.toolCalls.get(0).name);
        assertEquals("5", r.toolCalls.get(0).output);
        tk.close();
    }

    @Test
    void argumentsStructuredOrPreEncoded() throws IOException {
        for (Object args : List.of(Map.of("a", 2, "b", 3), "{\"a\":2,\"b\":3}")) {
            Toolkit tk = Toolkit.create(new Toolkit.Options());
            tk.register(addTool());
            AtomicInteger n = new AtomicInteger();
            LlmClient client = InProcess.createClient(new InProcess.Options().model("m")
                    .generate(req -> n.incrementAndGet() == 1
                            ? InProcess.Response.toolCalls(new InProcess.ToolCall("add", args))
                            : InProcess.Response.content("done")));
            LlmClient.RunResult r = client.run("go", tk);
            assertEquals("5", r.toolCalls.get(0).output, "args form: " + args);
            tk.close();
        }
    }

    @Test
    void usageIsOptionalAndDerived() throws IOException {
        Toolkit tk = Toolkit.create(new Toolkit.Options());

        LlmClient bare = InProcess.createClient(new InProcess.Options().model("m")
                .generate(req -> InProcess.Response.content("x")));
        assertEquals(0, bare.run("hi", tk).usage.totalTokens, "absent usage is zero, not a failure");

        LlmClient counted = InProcess.createClient(new InProcess.Options().model("m")
                .generate(req -> InProcess.Response.content("x").usage(11, 4)));
        LlmClient.RunResult r = counted.run("hi", tk);
        assertEquals(11, r.usage.promptTokens);
        assertEquals(15, r.usage.totalTokens, "total is derived when not given");
        tk.close();
    }

    @Test
    void streamingIsRefusedLoudly() throws IOException {
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = InProcess.createClient(new InProcess.Options().model("m")
                .generate(req -> InProcess.Response.content("x")));

        StringBuilder text = new StringBuilder();
        Exception boom = assertThrows(Exception.class,
                () -> client.stream("hi", tk, ev -> {
                    if (ev.type() == LlmClient.StreamEvent.Kind.TEXT) text.append(ev.delta());
                }));
        assertTrue(String.valueOf(boom.getMessage()).contains("does not support streaming")
                        || String.valueOf(boom.getCause()).contains("does not support streaming"),
                "message was: " + boom);
        assertEquals(0, text.length(), "no delta may be emitted before refusing");
        tk.close();
    }

    @Test
    void failuresAreNotRetriedByDefault() throws IOException {
        // There is no wire, so there is no transient failure to ride out: a generate that
        // fails will fail again, and retrying only buys backoff over the caller's own bug.
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = InProcess.createClient(new InProcess.Options().model("m")
                .generate(req -> { calls.incrementAndGet(); throw new IllegalStateException("my model blew up"); }));

        long started = System.nanoTime();
        assertThrows(Exception.class, () -> client.run("hi", tk));
        assertEquals(1, calls.get(), "a local failure is not transient");
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500, "must fail immediately");
        tk.close();
    }

    @Test
    void generateIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> InProcess.createClient(new InProcess.Options().model("m")));
    }
}
