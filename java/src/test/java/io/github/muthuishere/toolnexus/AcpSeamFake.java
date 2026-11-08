package io.github.muthuishere.toolnexus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fake ACP agent {@code AcpSeamConformanceTest} spawns (SPEC §8C,
 * {@code conformance/acp-client/cases.json}). Records each received request as
 * {@code <method> <params-json>} lines to {@code TN_FAKE_REC} and honours TN_FAKE_DRIP,
 * TN_FAKE_TOOLUPDATE, TN_FAKE_NO_CONFIG_OPTION, TN_FAKE_EXIT_ON_PROMPT and TN_FAKE_FS_REQUEST.
 */
public final class AcpSeamFake {
    private AcpSeamFake() {}

    private static final PrintStream OUT = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
            StandardCharsets.UTF_8);
    private static BufferedReader in;

    private static boolean on(String k) {
        return "1".equals(System.getenv(k));
    }

    private static void rec(String line) {
        String p = System.getenv("TN_FAKE_REC");
        if (p == null || p.isEmpty()) return;
        try {
            Files.writeString(Path.of(p), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static void send(Map<String, Object> m) {
        synchronized (OUT) {
            OUT.println(Json.stringify(m));
            OUT.flush();
        }
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static void reply(Object id, Object result) {
        send(obj("jsonrpc", "2.0", "id", id, "result", result));
    }

    private static void chunk(String kind, String text) {
        send(obj("jsonrpc", "2.0", "method", "session/update", "params", obj("sessionId", "s1", "update",
                obj("sessionUpdate", kind, "content", obj("type", "text", "text", text)))));
    }

    public static void main(String[] args) throws Exception {
        in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            Map<String, Object> m = Json.toMap(line);
            if (!m.containsKey("method")) continue;
            String method = String.valueOf(m.get("method"));
            Object id = m.get("id");
            rec(method + " " + Json.stringify(m.get("params")) + "\n");
            switch (method) {
                case "initialize" -> reply(id, obj("protocolVersion", 1, "agentCapabilities", obj()));
                case "session/new" -> reply(id, obj("sessionId", "s1"));
                case "session/set_config_option" -> {
                    if (on("TN_FAKE_NO_CONFIG_OPTION")) {
                        send(obj("jsonrpc", "2.0", "id", id, "error", obj("code", -32601, "message", "nope")));
                    } else {
                        reply(id, Map.of());
                    }
                }
                case "session/set_model" -> reply(id, Map.of());
                case "session/prompt" -> {
                    if (on("TN_FAKE_EXIT_ON_PROMPT")) System.exit(3);
                    if (on("TN_FAKE_DRIP")) {
                        chunk("agent_thought_chunk", "pondering");
                        String[] chunks = {"drip-1 ", "drip-2 ", "drip-3"};
                        for (int i = 0; i < chunks.length; i++) {
                            if (i > 0) Thread.sleep(200);
                            chunk("agent_message_chunk", chunks[i]);
                        }
                        reply(id, obj("stopReason", "end_turn"));
                        break;
                    }
                    if (on("TN_FAKE_TOOLUPDATE")) {
                        // stream.acpToolUpdate (issue #188): rawInput + title arrive on tool_call_update.
                        for (Map<String, Object> u : List.of(
                                obj("sessionUpdate", "tool_call", "toolCallId", "t1", "title", "Write",
                                        "kind", "edit", "status", "pending"),
                                obj("sessionUpdate", "tool_call_update", "toolCallId", "t1", "status", "in_progress",
                                        "title", "Write /tmp/a.txt",
                                        "rawInput", obj("file_path", "/tmp/a.txt", "content", "hi")),
                                obj("sessionUpdate", "tool_call_update", "toolCallId", "t1", "status", "in_progress"),
                                obj("sessionUpdate", "tool_call_update", "toolCallId", "t1", "status", "completed",
                                        "content", List.of(obj("type", "content",
                                                "content", obj("type", "text", "text", "wrote")))))) {
                            send(obj("jsonrpc", "2.0", "method", "session/update",
                                    "params", obj("sessionId", "s1", "update", u)));
                        }
                        chunk("agent_message_chunk", "done");
                        reply(id, obj("stopReason", "end_turn"));
                        break;
                    }
                    if (on("TN_FAKE_FS_REQUEST")) {
                        send(obj("jsonrpc", "2.0", "id", "fs-1", "method", "fs/read_text_file",
                                "params", obj("sessionId", "s1", "path", "/etc/hosts")));
                        String r;
                        String text = "no answer";
                        while ((r = in.readLine()) != null) {
                            Map<String, Object> a = Json.toMap(r);
                            if ("fs-1".equals(a.get("id"))) {
                                if (a.get("error") instanceof Map<?, ?> e && e.get("code") instanceof Number n
                                        && n.intValue() == -32601) {
                                    text = "ok";
                                } else {
                                    text = "bad " + r;
                                }
                                break;
                            }
                        }
                        chunk("agent_message_chunk", text);
                        reply(id, obj("stopReason", "end_turn"));
                        break;
                    }
                    chunk("agent_message_chunk", "hello");
                    reply(id, obj("stopReason", "end_turn"));
                }
                default -> {
                    if (id != null) reply(id, Map.of());
                }
            }
        }
    }

    static List<String> command() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(javaBin, "-cp", System.getProperty("java.class.path"), AcpSeamFake.class.getName());
    }
}
