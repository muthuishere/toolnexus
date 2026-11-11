package io.github.muthuishere.toolnexus.acp;

import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Toolkit;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §8C "ACP content parts": media travels as native ACP blocks, both directions. */
class AcpImagePartsTest {

    private static final String B64 = FakeAcpServer.PNG_B64;
    private static final String DESC = "image (image/png, 67 bytes)";

    private static AcpClient start(String scenario) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        return AcpClient.start(List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                FakeAcpServer.class.getName(), "--scenario=" + scenario), 10_000);
    }

    /** One turn with a text part + an image part; returns the session/prompt blocks the agent saw. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> promptBlocks(String scenario) throws Exception {
        try (AcpClient client = start(scenario)) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            msg.put("content", List.of(
                    Map.of("type", "text", "text", "what is in this picture?"),
                    Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + B64))));
            String echoed = client.generate(AcpClientTest.request(List.of(msg))).content;
            return (List<Map<String, Object>>) Json.toMap("{\"p\":" + echoed + "}").get("p");
        }
    }

    private static String texts(List<Map<String, Object>> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> b : blocks) if ("text".equals(b.get("type"))) sb.append(b.get("text"));
        return sb.toString();
    }

    private static List<Map<String, Object>> images(List<Map<String, Object>> blocks) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : blocks) if ("image".equals(b.get("type"))) out.add(b);
        return out;
    }

    @Test
    void imagePartSentAsImageBlock() throws Exception {
        List<Map<String, Object>> blocks = promptBlocks("image");
        List<Map<String, Object>> imgs = images(blocks);
        assertEquals(1, imgs.size(), "blocks=" + blocks);
        assertEquals("image/png", imgs.get(0).get("mimeType"));
        assertEquals(B64, imgs.get(0).get("data"));
        assertTrue(texts(blocks).contains("what is in this picture?"), texts(blocks));
        assertFalse(texts(blocks).contains(B64), "base64 leaked into a text block");
    }

    @Test
    void imagePartPlaceholderWithoutCapability() throws Exception {
        List<Map<String, Object>> blocks = promptBlocks("blocks");
        assertEquals(0, images(blocks).size(), "blocks=" + blocks);
        assertTrue(texts(blocks).contains(DESC), texts(blocks));
        assertFalse(texts(blocks).contains(B64), "base64 leaked into a text block");
    }

    @Test
    void inboundImageBlocksBecomeParts() throws Exception {
        try (AcpClient client = start("image_out")) {
            List<LlmClient.StreamEvent> results = new ArrayList<>();
            InProcess.Response resp = client.generateStream(
                    AcpClientTest.request(List.of(Map.of("role", "user", "content", "shoot"))), ev -> {
                        if (ev.type() == LlmClient.StreamEvent.Kind.TOOL_RESULT) results.add(ev);
                    });
            assertEquals(1, results.size());
            LlmClient.StreamEvent r = results.get(0);
            assertEquals(DESC, r.output());
            // SPEC §8C: the event's parts are full §1B parts, data included.
            assertEquals(List.of(Map.of("type", "image", "mimeType", "image/png", "data", B64)), r.parts());
            assertEquals("here", resp.content);
            assertNotNull(resp.files);
            assertEquals(2, resp.files.size(), String.valueOf(resp.files));
            LlmClient.RunFile f0 = resp.files.get(0), f1 = resp.files.get(1);
            assertEquals("screenshot", f0.from());
            assertEquals("t1", f0.callId());
            assertEquals("image", f0.type());
            assertEquals(B64, f0.data());
            assertEquals("acp", f1.from());
            assertEquals("", f1.callId());
            assertEquals("image/png", f1.mimeType());
            assertEquals(B64, f1.data());
        }
    }

    @Test
    void inboundImageReachesRunResultFiles() throws Exception {
        try (AcpClient client = start("image_out");
             Toolkit toolkit = Toolkit.create(new Toolkit.Options())) {
            LlmClient llm = InProcess.createClient(new InProcess.Options()
                    .model("acp").generateStream((req, emit) -> {
                        try {
                            return client.generateStream(req, emit);
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    }));
            LlmClient.RunResult res = llm.run("shoot", toolkit);
            assertEquals("here", res.text);
            assertEquals(2, res.files.size(), String.valueOf(res.files));
            assertEquals("screenshot", res.files.get(0).from());
            assertEquals(B64, res.files.get(0).data());
            assertEquals("acp", res.files.get(1).from());
            assertEquals(0, res.toolCallCount);

            LlmClient.RunResult done = llm.stream("shoot", toolkit, ev -> {});
            assertEquals(2, done.files.size(), String.valueOf(done.files));
            assertEquals("t1", done.files.get(0).callId());
        }
    }
}
