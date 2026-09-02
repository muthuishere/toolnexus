package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §1B / §2 / §6 / §8A / §11 — multimodal content parts.
 *
 * <p>Hermetic: a localhost {@code HttpServer} stands in for the provider and records every
 * request body, and the MCP round trip runs the port's own client against the port's own served
 * toolkit over an ephemeral port. No network, no key, no live model.
 *
 * <p>The base64 assertions read the COMMITTED golden ({@code examples/media/fixture.png.base64}) —
 * never a re-encoding — because regenerating the fixture is not byte-stable across zlib versions.
 */
class MultimodalContentTest {

    private static final Path FIXTURE = Path.of(TestFixtures.fixture("media/fixture.png"));
    private static final Path GOLDEN = Path.of(TestFixtures.fixture("media/fixture.png.base64"));

    private static String golden() throws IOException {
        return Files.readString(GOLDEN).trim();
    }

    // ------------------------------------------------------------------
    // A stub provider that records what it was sent.
    // ------------------------------------------------------------------

    /** Replies with the canned turn-1 body, then the canned turn-2 body, recording each request. */
    private static final class Stub implements AutoCloseable {
        final HttpServer server;
        final List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();
        private final Map<String, Object> first;
        private final Map<String, Object> second;

        Stub(Map<String, Object> first, Map<String, Object> second) throws IOException {
            this.first = first;
            this.second = second;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bodies.add(Json.toMap(raw));
                String json = Json.stringify(bodies.size() == 1 ? this.first : this.second);
                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @SuppressWarnings("unchecked")
        List<Object> messagesOf(int requestIndex) {
            return (List<Object>) bodies.get(requestIndex).get("messages");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Map<String, Object> openAIText(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text);
        return Map.of("choices", List.of(Map.of("message", message)),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2));
    }

    private static Map<String, Object> openAIToolCalls(String... names) {
        List<Object> calls = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            calls.add(Map.of("id", "c" + (i + 1), "type", "function",
                    "function", Map.of("name", names[i], "arguments", "{}")));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", calls);
        return Map.of("choices", List.of(Map.of("message", message)),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2));
    }

    private static Map<String, Object> anthropicText(String text) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)),
                "stop_reason", "end_turn",
                "usage", Map.of("input_tokens", 1, "output_tokens", 1));
    }

    private static Map<String, Object> anthropicToolUse(String name) {
        return Map.of("content", List.of(Map.of("type", "tool_use", "id", "toolu_1",
                        "name", name, "input", Map.of())),
                "stop_reason", "tool_use",
                "usage", Map.of("input_tokens", 1, "output_tokens", 1));
    }

    private static LlmClient client(String base, String style) {
        return LlmClient.create(new LlmClient.Options()
                .baseUrl(base).style(style).model("stub").apiKey("k"));
    }

    /** A tool whose result carries {@code parts}. */
    private static Tool partsTool(String name, String output, List<ContentPart> parts) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "returns content parts"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
            }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return ToolResult.ok(output, parts);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocks(Object content) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(content instanceof List<?> list)) return out;
        for (Object o : list) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    // ==================================================================
    // §1B — the model and its edge constructors
    // ==================================================================

    @Test
    void fixtureEncodesToTheCommittedGolden() throws IOException {
        ContentPart part = ContentPart.ofFile(FIXTURE);
        assertEquals("image", part.type());
        assertEquals("image/png", part.mimeType());
        assertEquals(golden(), part.data());
        assertNull(part.url(), "a path-built part must not carry a url");
        assertEquals(82, part.bytes());
        // The part carries no path: a persisted transcript replays without the file.
        assertFalse(Json.stringify(part).contains("fixture.png"));
    }

    @Test
    void aJavaIoFileEncodesToTheSameGolden() throws IOException {
        // §1B "accept broadly, store narrowly": java.io.File is the object most Java callers
        // hold; it must land on exactly the Path result, and leave no handle in the part.
        ContentPart part = ContentPart.ofFile(FIXTURE.toFile());
        assertEquals("image", part.type());
        assertEquals("image/png", part.mimeType());
        assertEquals(golden(), part.data());
        assertNull(part.url());
        assertEquals(82, part.bytes());
        assertFalse(Json.stringify(part).contains("fixture.png"));
        assertEquals(ContentPart.ofFile(FIXTURE), part);
        // and the checked sibling agrees
        assertEquals(part, ContentPart.ofFileChecked(FIXTURE.toFile()));
        // an unknown extension with no explicit mime names the extension
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ContentPart.ofFile(Path.of("a.xyz").toFile()));
        assertTrue(e.getMessage().contains("xyz"), e.getMessage());
        // an unreadable file is the port's established idiom: UncheckedIOException
        assertThrows(java.io.UncheckedIOException.class,
                () -> ContentPart.ofFile(Path.of("no-such-dir/missing.png").toFile()));
    }

    @Test
    void aStreamIsReadEagerlyAndLeftOpen() throws IOException {
        // An explicit mimeType, from a FileInputStream over the shared fixture.
        try (FileInputStream in = new FileInputStream(FIXTURE.toFile())) {
            ContentPart part = ContentPart.ofStream(in, "image/png");
            assertEquals("image", part.type());
            assertEquals("image/png", part.mimeType());
            assertEquals(golden(), part.data());
            assertNull(part.url());
            assertEquals(82, part.bytes());
            // the stream was read to exhaustion at construction, not held for later
            assertEquals(-1, in.read(), "ofStream must consume the stream eagerly");
            // ... and NOT closed: it is the caller's to close.
            assertTrue(in.getChannel().isOpen(), "ofStream must not close a caller-supplied stream");
        }
        // A name instead of a mime type: the §6 extension table supplies image/png.
        byte[] raw = Files.readAllBytes(FIXTURE);
        ByteArrayInputStream bytes = new ByteArrayInputStream(raw);
        ContentPart named = ContentPart.ofStream(bytes, "fixture.png", null);
        assertEquals("image", named.type());
        assertEquals("image/png", named.mimeType());
        assertEquals(golden(), named.data());
        // no handle, no path, no name leaks into a png part's wire form
        String wire = Json.stringify(named);
        assertFalse(wire.contains("fixture.png"), wire);
        assertFalse(wire.contains("java.io"), wire);
        assertNull(named.name());
        // the checked sibling agrees, and an unknown extension names itself
        assertEquals(named, ContentPart.ofStreamChecked(new ByteArrayInputStream(raw), "fixture.png", null));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> ContentPart.ofStream(new ByteArrayInputStream(raw), "notes.xyz", null));
        assertTrue(unknown.getMessage().contains("xyz"), unknown.getMessage());
        // a stream with neither a mime type nor a name is a typed error, never a sniff
        assertThrows(IllegalArgumentException.class,
                () -> ContentPart.ofStream(new ByteArrayInputStream(raw), (String) null));
        // a failing stream reports through the port's idiom rather than storing a broken part
        InputStream boom = new InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("nope");
            }
        };
        assertThrows(java.io.UncheckedIOException.class, () -> ContentPart.ofStream(boom, "image/png"));
    }

    @Test
    void aStreamRespectsTheEdgeSizeLimitAndTheFilePartName() throws IOException {
        byte[] raw = Files.readAllBytes(FIXTURE);
        // a name on a `file` part becomes its display name (a png stays an image part, above)
        ContentPart doc = ContentPart.ofStream(new ByteArrayInputStream(raw), "report.pdf", null);
        assertEquals("file", doc.type());
        assertEquals("application/pdf", doc.mimeType());
        assertEquals("report.pdf", doc.name());
        // maxPartBytes stays a construction fast-fail for the stream forms too
        ContentPart.setMaxPartBytes(10);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> ContentPart.ofStream(new ByteArrayInputStream(raw), "image/png"));
            assertThrows(IllegalArgumentException.class,
                    () -> ContentPart.ofFile(FIXTURE.toFile()));
        } finally {
            ContentPart.setMaxPartBytes(0);
        }
    }

    @Test
    void aPartWithBothDataAndUrlIsRejected() {
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class, () ->
                new ContentPart("image", null, "image/png", "AAA=", "https://x/y.png", null));
        assertTrue(both.getMessage().contains("not both"), both.getMessage());
        IllegalArgumentException neither = assertThrows(IllegalArgumentException.class, () ->
                new ContentPart("image", null, "image/png", null, null, null));
        assertTrue(neither.getMessage().contains("neither"), neither.getMessage());
        assertThrows(IllegalArgumentException.class, () ->
                new ContentPart("image", null, null, "AAA=", null, null));
    }

    @Test
    void aDataUrlIsNormalisedAtConstruction() throws IOException {
        ContentPart part = ContentPart.ofUrl("image", "data:image/png;base64," + golden());
        assertEquals("image/png", part.mimeType());
        assertEquals(golden(), part.data());
        assertNull(part.url(), "a data: URL must never be stored as a url");
        // an https: URL is kept as-is
        ContentPart remote = ContentPart.ofUrl("image", "image/png", "https://example.com/a.png");
        assertEquals("https://example.com/a.png", remote.url());
        assertNull(remote.data());
    }

    @Test
    void aUrlBearingPartRendersBytesAsZeroInAllThreeStrings() {
        // SPEC §1B: "A part carrying a url instead of data renders <bytes> as 0" — not empty,
        // not null, not a NullPointerException, not the URL's length.
        ContentPart part = ContentPart.ofUrl("image", "image/png", "https://example.com/a.png");
        assertEquals(0, part.bytes());
        assertEquals("image (image/png, 0 bytes)", ContentParts.describe(part));
        assertEquals("[unsupported image part (image/png, 0 bytes)]", ContentParts.placeholder(part));
        assertEquals("image (image/png, 0 bytes)", ContentParts.describeForOutput(List.of(part)));
    }

    @Test
    void anUnknownExtensionIsRefusedByName() throws IOException {
        Path odd = Files.createTempDirectory("tnx").resolve("thing.xyz");
        Files.writeString(odd, "hi");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ContentPart.ofFile(odd));
        assertTrue(e.getMessage().contains("xyz"), e.getMessage());
        // ... but an explicit mime type is accepted
        assertEquals("text/plain", ContentPart.ofFile(odd, "text/plain").mimeType());
    }

    @Test
    void anOversizedPartIsRejectedAtTheEdge() {
        ContentPart.setMaxPartBytes(1024);
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ContentPart.image("image/png", new byte[2048]));
            assertTrue(e.getMessage().contains("2048") && e.getMessage().contains("1024"), e.getMessage());
            assertNotNull(ContentPart.image("image/png", new byte[16]));
        } finally {
            ContentPart.setMaxPartBytes(0);
        }
    }

    @Test
    void aPartNeverRendersItsBytes() throws IOException {
        ContentPart part = ContentPart.ofFile(FIXTURE);
        assertFalse(part.toString().contains(golden().substring(0, 16)), part.toString());
        assertEquals(Map.of("type", "image", "mimeType", "image/png", "bytes", 82L), part.describe());
        assertFalse(ToolResult.ok("shot", List.of(part)).toString().contains("iVBOR"));
    }

    // ==================================================================
    // §6 — the `read` builtin's media table
    // ==================================================================

    @Test
    void readingAPngYieldsAnImagePart() throws IOException {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options())) {
            ToolResult r = tk.get("read").execute(Map.of("path", FIXTURE.toString()), null);
            assertFalse(r.isError(), r.output());
            assertTrue(r.output().contains("image/png"), r.output());
            assertTrue(r.output().contains("fixture.png"), r.output());
            assertEquals(1, r.parts().size());
            assertEquals("image", r.parts().get(0).type());
            assertEquals(golden(), r.parts().get(0).data());
        }
    }

    @Test
    void readingATextFileIsUnchanged() throws IOException {
        Path md = Files.createTempDirectory("tnx").resolve("a.md");
        Files.writeString(md, "one\ntwo\nthree\n");
        try (Toolkit tk = Toolkit.create(new Toolkit.Options())) {
            ToolResult r = tk.get("read").execute(Map.of("path", md.toString(), "offset", 2, "limit", 2), null);
            assertEquals("two\nthree", r.output());
            assertNull(r.parts(), "a text read must not gain parts");
        }
    }

    @Test
    void anUnrecognisedBinaryYieldsAnErrorResult() throws IOException {
        Path bin = Files.createTempDirectory("tnx").resolve("blob.bin");
        Files.write(bin, new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, 0x00});
        try (Toolkit tk = Toolkit.create(new Toolkit.Options())) {
            ToolResult r = tk.get("read").execute(Map.of("path", bin.toString()), null);
            assertTrue(r.isError(), "undecodable bytes must be an error RESULT");
            assertTrue(r.output().contains("blob.bin"), r.output());
        }
    }

    // ==================================================================
    // §8A — loop input
    // ==================================================================

    @Test
    void theStringPromptPathIsUnchanged() throws IOException {
        try (Stub stub = new Stub(openAIText("ok"), openAIText("ok"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                client(stub.base(), "openai").run("hi", tk);
            }
            List<Object> messages = stub.messagesOf(0);
            Map<String, Object> user = asMap(messages.get(messages.size() - 1));
            assertEquals("user", user.get("role"));
            assertEquals("hi", user.get("content"), "the string path must stay byte-identical");
        }
    }

    @Test
    void partOrderingIsPreserved() throws IOException {
        try (Stub stub = new Stub(openAIText("ok"), openAIText("ok"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                client(stub.base(), "openai").run(List.of(
                        ContentPart.text("before"),
                        ContentPart.ofFile(FIXTURE),
                        ContentPart.text("after")), tk);
            }
            List<Object> messages = stub.messagesOf(0);
            List<Map<String, Object>> got = blocks(asMap(messages.get(messages.size() - 1)).get("content"));
            assertEquals(3, got.size());
            assertEquals(List.of("text", "image_url", "text"),
                    List.of(got.get(0).get("type"), got.get(1).get("type"), got.get(2).get("type")));
            assertEquals("before", got.get(0).get("text"));
            assertEquals("after", got.get(2).get("text"));
            assertEquals("data:image/png;base64," + golden(), asMap(got.get(1).get("image_url")).get("url"));
        }
    }

    @Test
    void anImagePartReachesAnthropicAsABase64Source() throws IOException {
        try (Stub stub = new Stub(anthropicText("ok"), anthropicText("ok"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                client(stub.base(), "anthropic").run(List.of(ContentPart.ofFile(FIXTURE)), tk);
            }
            List<Map<String, Object>> got = blocks(asMap(stub.messagesOf(0).get(0)).get("content"));
            assertEquals("image", got.get(0).get("type"));
            Map<String, Object> source = asMap(got.get(0).get("source"));
            assertEquals("base64", source.get("type"));
            assertEquals("image/png", source.get("media_type"));
            assertEquals(golden(), source.get("data"));
        }
    }

    @Test
    void anAttachedAudioPartToAnthropicErrorsBeforeAnyHttpCall() throws IOException {
        try (Stub stub = new Stub(anthropicText("ok"), anthropicText("ok"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                LlmClient.UnsupportedPartException e = assertThrows(
                        LlmClient.UnsupportedPartException.class,
                        () -> client(stub.base(), "anthropic").run(
                                List.of(ContentPart.audio("audio/mpeg", new byte[]{1, 2, 3})), tk));
                assertTrue(e.getMessage().contains("audio") && e.getMessage().contains("anthropic"),
                        e.getMessage());
            }
            assertEquals(0, stub.bodies.size(), "no HTTP request may be made");
        }
    }

    // ==================================================================
    // §8A — tool-result emission and the relocation rule
    // ==================================================================

    @Test
    void aTextOnlyToolResultIsByteIdentical() throws IOException {
        try (Stub stub = new Stub(openAIToolCalls("plain"), openAIText("done"))) {
            Tool plain = NativeTool.of("plain", "text only",
                    Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                    args -> "just text");
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(plain))) {
                client(stub.base(), "openai").run("go", tk);
            }
            List<Object> second = stub.messagesOf(1);
            Map<String, Object> toolMsg = asMap(second.get(second.size() - 1));
            assertEquals("tool", toolMsg.get("role"));
            assertEquals("just text", toolMsg.get("content"), "a text-only result must stay a string");
        }
    }

    @Test
    void openAiRelocatesEveryToolImageIntoOneSyntheticUserMessage() throws IOException {
        try (Stub stub = new Stub(openAIToolCalls("shotA", "shotB"), openAIText("done"))) {
            ContentPart img = ContentPart.ofFile(FIXTURE);
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false)
                    .extraTools(partsTool("shotA", "screenshot A", List.of(img)),
                            partsTool("shotB", "screenshot B", List.of(img))))) {
                LlmClient.RunResult result = client(stub.base(), "openai").run("go", tk);

                List<Object> sent = stub.messagesOf(1);
                // the two tool messages carry their `output` text only
                Map<String, Object> t1 = asMap(sent.get(sent.size() - 3));
                Map<String, Object> t2 = asMap(sent.get(sent.size() - 2));
                assertEquals("tool", t1.get("role"));
                assertEquals("screenshot A", t1.get("content"));
                assertEquals("screenshot B", t2.get("content"));

                // exactly ONE synthetic user message follows, in tool-call order
                Map<String, Object> synthetic = asMap(sent.get(sent.size() - 1));
                assertEquals("user", synthetic.get("role"));
                List<Map<String, Object>> got = blocks(synthetic.get("content"));
                assertEquals(4, got.size());
                assertEquals("Output of tool shotA (c1):", got.get(0).get("text"));
                assertEquals("image_url", got.get(1).get("type"));
                assertEquals("Output of tool shotB (c2):", got.get(2).get("text"));
                assertEquals("image_url", got.get(3).get("type"));

                // ... and it is an adapter artifact: never in the canonical transcript
                long users = result.messages.stream()
                        .filter(m -> m instanceof Map && "user".equals(asMap(m).get("role"))).count();
                assertEquals(1, users, "the synthetic message leaked into the transcript");
            }
        }
    }

    @Test
    void anthropicReceivesTheImageInsideTheToolResult() throws IOException {
        try (Stub stub = new Stub(anthropicToolUse("shot"), anthropicText("done"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false)
                    .extraTools(partsTool("shot", "a screenshot", List.of(ContentPart.ofFile(FIXTURE)))))) {
                LlmClient.RunResult result = client(stub.base(), "anthropic").run("go", tk);

                List<Object> sent = stub.messagesOf(1);
                Map<String, Object> resultTurn = asMap(sent.get(sent.size() - 1));
                assertEquals("user", resultTurn.get("role"));
                List<Map<String, Object>> toolResults = blocks(resultTurn.get("content"));
                assertEquals(1, toolResults.size(), "no synthetic message may be emitted");
                Map<String, Object> tr = toolResults.get(0);
                assertEquals("tool_result", tr.get("type"));
                assertEquals("toolu_1", tr.get("tool_use_id"));
                List<Map<String, Object>> inner = blocks(tr.get("content"));
                assertEquals("a screenshot", inner.get(0).get("text"));
                assertEquals("image", inner.get(1).get("type"));
                assertEquals(golden(), asMap(inner.get(1).get("source")).get("data"));
                assertEquals("done", result.text);
            }
        }
    }

    @Test
    void mcpDerivedAudioDegradesInsteadOfFailingTheRun() throws IOException {
        try (Stub stub = new Stub(anthropicToolUse("clip"), anthropicText("done"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false)
                    .extraTools(partsTool("clip", "an audio clip",
                            List.of(ContentPart.audio("audio/mpeg", new byte[]{1, 2, 3})))))) {
                LlmClient.RunResult result = client(stub.base(), "anthropic").run("go", tk);
                assertEquals("done", result.text, "a server-volunteered audio clip must not fail the run");
                String sent = Json.stringify(stub.bodies.get(1));
                assertTrue(sent.contains("unsupported audio part (audio/mpeg, 3 bytes)"), sent);
            }
        }
    }

    @Test
    void theOverrideForcesUniformStrictness() throws IOException {
        try (Stub stub = new Stub(anthropicToolUse("clip"), anthropicText("done"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false)
                    .extraTools(partsTool("clip", "an audio clip",
                            List.of(ContentPart.audio("audio/mpeg", new byte[]{1, 2, 3})))))) {
                LlmClient strict = LlmClient.create(new LlmClient.Options()
                        .baseUrl(stub.base()).style("anthropic").model("stub").apiKey("k")
                        .onUnsupportedPart("error"));
                assertThrows(LlmClient.UnsupportedPartException.class, () -> strict.run("go", tk));
            }
        }
    }

    // ==================================================================
    // §1B — maxPartBytes is enforced at assembly, over every part
    // regardless of provenance (not only in the edge constructors)
    // ==================================================================

    @Test
    void anOversizedAttachedPartErrorsBeforeAnyHttpCall() throws IOException {
        try (Stub stub = new Stub(anthropicText("ok"), anthropicText("ok"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                LlmClient limited = LlmClient.create(new LlmClient.Options()
                        .baseUrl(stub.base()).style("anthropic").model("stub").apiKey("k")
                        .maxPartBytes(16));
                LlmClient.UnsupportedPartException e = assertThrows(
                        LlmClient.UnsupportedPartException.class,
                        () -> limited.run(List.of(ContentPart.image("image/png", new byte[64])), tk));
                assertTrue(e.getMessage().contains("64") && e.getMessage().contains("16"), e.getMessage());
            }
            assertEquals(0, stub.bodies.size(), "no HTTP request may be made");
        }
    }

    @Test
    void anOversizedMcpDerivedPartDegradesAndTheRunCompletes() throws IOException {
        try (Stub stub = new Stub(anthropicToolUse("shot"), anthropicText("done"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false)
                    .extraTools(partsTool("shot", "a screenshot",
                            List.of(ContentPart.image("image/png", new byte[64])))))) {
                LlmClient limited = LlmClient.create(new LlmClient.Options()
                        .baseUrl(stub.base()).style("anthropic").model("stub").apiKey("k")
                        .maxPartBytes(16));
                LlmClient.RunResult result = limited.run("go", tk);
                assertEquals("done", result.text,
                        "a server-volunteered oversized part must not fail the run");
                String sent = Json.stringify(stub.bodies.get(1));
                assertTrue(sent.contains("unsupported image part (image/png, 64 bytes)"), sent);
            }
        }
    }

    @Test
    void theOversizePartWarningFiresOnlyOnce() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (Stub stub = new Stub(anthropicToolUse("shot"), anthropicText("done"))) {
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false)
                    .extraTools(partsTool("shot", "a screenshot",
                            List.of(ContentPart.image("image/png", new byte[64]),
                                    ContentPart.image("image/png", new byte[64])))))) {
                LlmClient limited = LlmClient.create(new LlmClient.Options()
                        .baseUrl(stub.base()).style("anthropic").model("stub").apiKey("k")
                        .maxPartBytes(16));
                limited.run("go", tk);
            }
        } finally {
            System.setErr(original);
        }
        String stderr = captured.toString(StandardCharsets.UTF_8);
        int occurrences = stderr.split("maxPartBytes limit", -1).length - 1;
        assertEquals(1, occurrences, "the warning must fire once per client, not once per part: " + stderr);
    }

    @Test
    void partsDoNotCollideWithSuspension() throws IOException {
        try (Stub stub = new Stub(openAIToolCalls("gated"), openAIText("done"))) {
            Tool gated = new Tool() {
                @Override public String name() { return "gated"; }
                @Override public String description() { return "suspends, with parts"; }
                @Override public Map<String, Object> inputSchema() {
                    return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
                }
                @Override public String source() { return "custom"; }
                @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                    ToolResult pending = ToolResult.authRequired("https://example.com/login");
                    return new ToolResult(pending.output(), true, pending.metadata(),
                            List.of(ContentPart.ofFile(FIXTURE)));
                }
            };
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(gated))) {
                // no waitFor ⇒ the run halts durably, exactly as it would without parts
                LlmClient.RunResult result = client(stub.base(), "openai").run("go", tk);
                assertEquals("pending", result.status);
                assertNotNull(result.pending);
            }
        }
    }

    // ==================================================================
    // §2 — MCP results
    // ==================================================================

    /** Serve a toolkit over MCP, then read it back through the port's own MCP client. */
    @Test
    void mcpPreservesNonTextContentAndLeavesTextOnlyAlone() throws IOException {
        Tool shot = partsTool("shot", "a screenshot", List.of(ContentPart.ofFile(FIXTURE)));
        Tool plain = NativeTool.of("plain", "text only",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                args -> "just text");
        try (Toolkit served = Toolkit.create(new Toolkit.Options().builtins(false)
                .extraTools(shot, plain))) {
            A2AServer.ServeHandle srv = served.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig()));
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false)
                    .mcpConfig(Map.of("srv", Map.of("url", srv.url() + "/mcp"))))) {
                ToolResult image = tk.get("srv_shot").execute(Map.of(), null);
                assertEquals("a screenshot", image.output());
                assertNotNull(image.parts(), "the image was dropped crossing MCP");
                assertEquals(1, image.parts().size());
                assertEquals("image", image.parts().get(0).type());
                assertEquals("image/png", image.parts().get(0).mimeType());
                assertEquals(golden(), image.parts().get(0).data());

                ToolResult text = tk.get("srv_plain").execute(Map.of(), null);
                assertEquals("just text", text.output());
                assertNull(text.parts(), "a text-only MCP result must stay byte-identical");
            } finally {
                srv.stop();
            }
        }
    }

    // ==================================================================
    // §11 — translate
    // ==================================================================

    @Test
    void anImagePartInContentSurvivesTranslation() throws IOException {
        try (Stub stub = new Stub(anthropicText("ok"), anthropicText("ok"))) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("role", "user");
            user.put("content", List.of(
                    Map.of("type", "text", "text", "what is this?"),
                    Map.of("type", "image", "mimeType", "image/png", "data", golden())));
            client(stub.base(), "anthropic").translate(new Translate.Request()
                    .messages(List.of(user)));

            List<Map<String, Object>> got = blocks(asMap(stub.messagesOf(0).get(0)).get("content"));
            assertEquals(2, got.size(), "a non-text part was dropped");
            assertEquals("text", got.get(0).get("type"));
            assertEquals("image", got.get(1).get("type"));
            assertEquals(golden(), asMap(got.get(1).get("source")).get("data"));
        }
    }

    @Test
    void textOnlyPartsAreConcatenated() throws IOException {
        try (Stub stub = new Stub(anthropicText("ok"), anthropicText("ok"))) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("role", "user");
            user.put("content", List.of(
                    Map.of("type", "text", "text", "one "),
                    Map.of("type", "text", "text", "two")));
            client(stub.base(), "anthropic").translate(new Translate.Request().messages(List.of(user)));
            assertEquals("one two", asMap(stub.messagesOf(0).get(0)).get("content"));
        }
    }

    // ==================================================================
    // §9 — parts are charged for, and never logged
    // ==================================================================

    @Test
    void aPartIsNotFreeToTheCompactor() throws IOException {
        ContentPart big = ContentPart.image("image/png", new byte[2 * 1024 * 1024]);
        Map<String, Object> withImage = new LinkedHashMap<>();
        withImage.put("role", "user");
        withImage.put("content", List.of(ContentPart.text("hi"), big));

        int charged = Compaction.estimateTokens(List.of(withImage));
        assertTrue(charged >= 2000, "a 2 MB image must not be ~free; charged " + charged);
        assertEquals((int) (big.bytes() / 750), Compaction.estimatePartTokens(big));

        // a transcript with no parts is estimated exactly as before
        Map<String, Object> plain = Map.of("role", "user", "content", "hi");
        assertEquals((int) Math.ceil(Json.stringify(plain).length() / 4.0),
                Compaction.estimateTokens(List.of(plain)));
    }

    @Test
    void theTokenEstimateFloorIsPinnedAt85() {
        // SPEC §1B: max(85, floor(decodedBytes / 750)) — pin the floor itself, not just the
        // formula shape, so changing 85 back to 1 (or any other constant) fails the suite.
        ContentPart small = ContentPart.image("image/png", new byte[82]);
        assertEquals(85, Compaction.estimatePartTokens(small), "below the floor, 85 must win");

        ContentPart atCrossover = ContentPart.image("image/png", new byte[750_000]);
        assertEquals(1000, Compaction.estimatePartTokens(atCrossover), "750000/750 = 1000, above the floor");
    }
}
