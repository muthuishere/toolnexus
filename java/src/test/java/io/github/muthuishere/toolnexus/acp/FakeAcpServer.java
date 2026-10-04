package io.github.muthuishere.toolnexus.acp;

import io.github.muthuishere.toolnexus.Json;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal, scripted ACP (Agent Client Protocol) server — the Java test-helper counterpart of
 * {@code spikes/acp/fakeagent/main.go}, built ONLY to exercise {@code AcpClientTest} over REAL OS
 * pipes (spawned by the test via {@link ProcessBuilder} against the test classpath, exactly like
 * a real agent binary would be). Not a general ACP implementation.
 *
 * <p>Scenarios ({@code --scenario=}):
 * <ul>
 *   <li>{@code warm} (default) — trivial echo; also reports how many {@code session/new} calls
 *       it has seen, so a test can assert a warm client never re-opens the session per turn.</li>
 *   <li>{@code stale} — a stateful session that answers a near-duplicate prompt with a STALE
 *       answer unless the new prompt carries the {@code SUPERSEDES-ALL-PRIOR:} marker, in which
 *       case it answers fresh.</li>
 *   <li>{@code hang} — sends {@code session/request_permission} and then never replies to
 *       {@code session/prompt}; proves an unanswered permission request hangs a turn.</li>
 *   <li>{@code permission} — sends {@code session/request_permission} and blocks until the
 *       client answers before finishing the turn, replying {@code PERMITTED:<optionId>} (or
 *       {@code PERMITTED:cancelled}) so a test can see which option the client chose.</li>
 *   <li>{@code noisy} — emits {@code agent_thought_chunk} and tool-call narration interleaved
 *       with the real {@code agent_message_chunk}s, which together spell {@code {"answer":true}}.</li>
 *   <li>{@code toolloop} — a scripted tool-calling model: with no {@code role:"tool"} message in
 *       the REQUEST it asks for {@code add(2,3)} (wrapped in prose and a {@code ```json} fence,
 *       object arguments, to exercise the tolerant parser); once a tool result is present it
 *       answers {@code {"content":"The answer is <result>."}}. Each parsed REQUEST is appended as
 *       one JSON line to the {@code --events=<path>} file.</li>
 * </ul>
 */
public final class FakeAcpServer {

    private static final PrintStream out;

    static {
        try {
            out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Object outLock = new Object();
    private static final AtomicLong nextReqId = new AtomicLong();
    private static final BlockingQueue<Map<String, Object>> permReplies = new LinkedBlockingQueue<>();
    private static volatile int sessionNewCount = 0;
    private static volatile String eventsPath;

    private FakeAcpServer() {}

    public static void main(String[] args) throws IOException {
        String scenario = "warm";
        for (String a : args) {
            if (a.startsWith("--scenario=")) scenario = a.substring("--scenario=".length());
            if (a.startsWith("--events=")) eventsPath = a.substring("--events=".length());
        }

        List<String> history = Collections.synchronizedList(new ArrayList<>());

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            Map<String, Object> msg;
            try {
                msg = Json.toMap(line);
            } catch (RuntimeException e) {
                continue;
            }

            Object idObj = msg.get("id");
            Object methodObj = msg.get("method");
            String method = methodObj == null ? null : String.valueOf(methodObj);

            if (method == null && idObj != null) {
                // a reply to a server-initiated request (session/request_permission)
                permReplies.offer(msg);
                continue;
            }

            handle(scenario, idObj, method, msg, history);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handle(String scenario, Object idObj, String method, Map<String, Object> msg,
                                List<String> history) {
        switch (method == null ? "" : method) {
            case "initialize" -> {
                Map<String, Object> caps = new LinkedHashMap<>();
                caps.put("loadSession", false);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", 1);
                result.put("agentCapabilities", caps);
                reply(idObj, result);
            }
            case "session/new" -> {
                sessionNewCount++;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sessionId", "sess-1");
                reply(idObj, result);
            }
            case "session/set_mode", "session/cancel" -> reply(idObj, Map.of());
            case "session/prompt" -> {
                Map<String, Object> params = (Map<String, Object>) msg.get("params");
                String userText = extractPromptText(params);
                Object sid = params != null ? params.getOrDefault("sessionId", "sess-1") : "sess-1";
                history.add(userText);
                List<String> snapshot = new ArrayList<>(history);
                Thread t = new Thread(() -> handlePrompt(scenario, idObj, sid, userText, snapshot));
                t.setDaemon(true);
                t.start();
            }
            default -> {
                if (idObj != null) reply(idObj, Map.of());
            }
        }
    }

    private static void handlePrompt(String scenario, Object id, Object sid, String userText, List<String> history) {
        switch (scenario) {
            case "stale" -> handleStale(id, userText, history);
            case "hang" -> sendRequest("session/request_permission", permissionParams(sid));
            // ^ deliberately never replies to session/prompt after this.
            case "permission" -> handlePermission(id, sid);
            case "noisy" -> handleNoisy(id, sid);
            case "toolloop" -> handleToolLoop(id, sid, userText);
            default -> {
                sendUpdate(sid, "agent_message_chunk", "echo:" + userText + " sessionNewCount=" + sessionNewCount);
                reply(id, Map.of("stopReason", "end_turn"));
            }
        }
    }

    /** Mirrors {@code fakeagent/main.go}'s {@code handleStale}: on a fresh prompt, scan history
     * oldest-first and answer whichever remembered question is a substring of what just arrived
     * — UNLESS the current prompt carries the supersedes marker, in which case answer only the
     * text after it. */
    private static void handleStale(Object id, String userText, List<String> history) {
        String answer;
        int idx = userText.indexOf(AcpClient.SUPERSEDES_MARKER);
        if (idx >= 0) {
            String fresh = userText.substring(idx + AcpClient.SUPERSEDES_MARKER.length()).trim();
            answer = "FRESH-ANSWER-TO:" + fresh;
        } else {
            String matched = history.isEmpty() ? "" : history.get(0);
            for (String h : history) {
                if (userText.contains(h)) {
                    matched = h;
                    break;
                }
            }
            answer = "STALE-ANSWER-TO:" + matched;
        }
        sendUpdate("sess-1", "agent_message_chunk", answer);
        reply(id, Map.of("stopReason", "end_turn"));
    }

    private static void handlePermission(Object id, Object sid) {
        String reqId = sendRequest("session/request_permission", permissionParams(sid));
        Map<String, Object> answer;
        while (true) {
            Map<String, Object> m;
            try {
                m = permReplies.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Object mid = m.get("id");
            if (mid != null && String.valueOf(mid).equals(reqId)) {
                answer = m;
                break;
            }
        }
        String chosen = "cancelled";
        if (answer.get("result") instanceof Map<?, ?> r && r.get("outcome") instanceof Map<?, ?> o
                && "selected".equals(o.get("outcome"))) {
            chosen = String.valueOf(o.get("optionId"));
        }
        sendUpdate(sid, "agent_message_chunk", "PERMITTED:" + chosen);
        reply(id, Map.of("stopReason", "end_turn"));
    }

    private static void handleNoisy(Object id, Object sid) {
        sendUpdate(sid, "agent_thought_chunk", "Let me think about this... ");
        sendToolCall(sid, "t1", "reading files", "in_progress");
        sendUpdate(sid, "agent_message_chunk", "{\"answer\":");
        sendToolCallUpdate(sid, "t1", "completed");
        sendUpdate(sid, "agent_thought_chunk", "now double-checking the number... ");
        sendUpdate(sid, "agent_message_chunk", "true}");
        reply(id, Map.of("stopReason", "end_turn"));
    }

    private static void handleToolLoop(Object id, Object sid, String prompt) {
        String answer = "unparseable prompt";
        Map<String, Object> request = splitRequest(prompt);
        if (request != null) {
            recordEvent(request);
            String toolResult = null;
            if (request.get("messages") instanceof List<?> msgs) {
                for (Object m : msgs) {
                    if (m instanceof Map<?, ?> mm && "tool".equals(mm.get("role"))) {
                        toolResult = String.valueOf(mm.get("content"));
                    }
                }
            }
            if (toolResult == null) {
                answer = "Sure, calling the tool.\n```json\n"
                        + "{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
                        + "{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]}"
                        + "\n```";
            } else {
                answer = Json.stringify(Map.of("content", "The answer is " + toolResult + "."));
            }
        }
        sendUpdate(sid, "agent_message_chunk", answer);
        reply(id, Map.of("stopReason", "end_turn"));
    }

    /** Pulls the REQUEST JSON back out of an assembled prompt; null when it does not split. */
    static Map<String, Object> splitRequest(String prompt) {
        int i = prompt.indexOf("\nREQUEST:\n");
        int j = prompt.lastIndexOf("\n\n" + AcpClient.SUPERSEDES_MARKER + " ");
        if (i < 0 || j < i) return null;
        try {
            return Json.toMap(prompt.substring(i + "\nREQUEST:\n".length(), j));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static synchronized void recordEvent(Object event) {
        if (eventsPath == null) return;
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(eventsPath), Json.stringify(event) + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- wire helpers -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String extractPromptText(Map<String, Object> params) {
        if (params == null) return "";
        Object p = params.get("prompt");
        if (!(p instanceof List<?> list)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object t = m.get("text");
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> permissionParams(Object sid) {
        Map<String, Object> reject = new LinkedHashMap<>();
        reject.put("optionId", "reject");
        reject.put("kind", "reject_once");
        reject.put("name", "Reject");
        Map<String, Object> allow = new LinkedHashMap<>();
        allow.put("optionId", "allow-once");
        allow.put("kind", "allow_once");
        allow.put("name", "Allow");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sid);
        params.put("options", List.of(reject, allow));
        return params;
    }

    private static String sendRequest(String method, Map<String, Object> params) {
        String id = "srv-" + nextReqId.incrementAndGet();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.put("params", params);
        write(msg);
        return id;
    }

    private static void reply(Object id, Object result) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("result", result);
        write(msg);
    }

    private static void sendUpdate(Object sid, String kind, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("sessionUpdate", kind);
        update.put("content", content);
        sendUpdateNotification(sid, update);
    }

    private static void sendToolCall(Object sid, String toolCallId, String title, String status) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("sessionUpdate", "tool_call");
        update.put("toolCallId", toolCallId);
        update.put("title", title);
        update.put("status", status);
        sendUpdateNotification(sid, update);
    }

    private static void sendToolCallUpdate(Object sid, String toolCallId, String status) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("sessionUpdate", "tool_call_update");
        update.put("toolCallId", toolCallId);
        update.put("status", status);
        sendUpdateNotification(sid, update);
    }

    private static void sendUpdateNotification(Object sid, Map<String, Object> update) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sid);
        params.put("update", update);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("method", "session/update");
        msg.put("params", params);
        write(msg);
    }

    private static void write(Object v) {
        String json = Json.stringify(v);
        synchronized (outLock) {
            out.print(json);
            out.print('\n');
            out.flush();
        }
    }
}
