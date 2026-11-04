package io.github.muthuishere.toolnexus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The child processes {@code CliClientTest} spawns (via a launcher script), mirroring the Go
 * test binary's helper modes: a fake ACP agent ({@code TN_FAKE_ACP=1}, argv {@code acp}), a fake
 * one-shot CLI ({@code TN_FAKE_CLI=<mode>}), and the stdio MCP bridge (argv {@code mcp-bridge <url>}).
 */
public final class CliFakes {
    private CliFakes() {}

    private static final PrintStream OUT = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
            StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("mcp-bridge")) {
            try {
                CliAcpSession.runMcpBridge(args[1], System.getenv(CliClient.MCP_TOKEN_ENV), System.in, System.out);
                System.out.flush();
            } catch (IOException e) {
                System.err.println(e.getMessage());
                System.exit(1);
            }
            return;
        }
        if ("1".equals(System.getenv("TN_FAKE_ACP")) && args.length >= 1 && args[0].equals("acp")) {
            fakeAcpAgent();
            return;
        }
        String mode = System.getenv("TN_FAKE_CLI");
        if (mode != null && !mode.isEmpty()) {
            fakeOneShot(mode, args);
            return;
        }
        System.err.println("CliFakes: no mode");
        System.exit(9);
    }

    private static void rec(String line) {
        String p = System.getenv("TN_FAKE_REC");
        if (p == null || p.isEmpty()) return;
        try {
            Files.writeString(Path.of(p), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    // ---- one-shot CLI ----------------------------------------------------------------------

    private static void fakeOneShot(String mode, String[] args) throws Exception {
        String prompt = "";
        String outFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--p" -> prompt = args[++i];
                case "--f" -> prompt = Files.readString(Path.of(args[++i]));
                case "--o" -> outFile = args[++i];
                case "--argv" -> {
                    System.out.print("{\"content\":" + Json.stringify(String.join(" ", args)) + "}");
                    System.out.flush();
                    return;
                }
                default -> {
                    // Style-agnostic detection, so every built-in style's real argv works: the
                    // prompt as an arg, the {{file}} temp file, the {{out}} temp path (not created yet).
                    String a = args[i];
                    if (a.startsWith(CliClient.PREAMBLE.substring(0, 20))) {
                        prompt = a;
                    } else if (Path.of(a).getFileName() != null
                            && Path.of(a).getFileName().toString().contains("toolnexus-cli-")) {
                        if (Files.exists(Path.of(a))) prompt = Files.readString(Path.of(a));
                        else outFile = a;
                    }
                }
            }
        }
        if (prompt.isEmpty()) prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        rec(prompt + "\n=====\n");
        String reply;
        switch (mode) {
            case "exit" -> {
                System.err.print("boom: bad login");
                System.err.flush();
                System.exit(3);
                return;
            }
            case "empty" -> {
                return;
            }
            case "sleep" -> {
                Thread.sleep(10_000);
                reply = "{\"content\":\"late\"}";
            }
            case "nooutfile" -> {
                System.out.print("progress only");
                System.out.flush();
                return;
            }
            case "tool" -> {
                if (prompt.contains("BADGE-77Q")) {
                    reply = "{\"content\": " + Json.stringify("Your badge is BADGE-77Q.") + "}";
                } else {
                    reply = "```json\n{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":"
                            + "\"get_badge_number\",\"arguments\":\"{}\"}}]}\n```\n\n";
                }
            }
            default -> reply = "{\"content\":\"hello\"}";
        }
        if (outFile != null) {
            System.out.print("progress on stdout\n");
            Files.writeString(Path.of(outFile), reply);
            System.out.flush();
            return;
        }
        System.out.print(reply);
        System.out.flush();
    }

    // ---- fake ACP agent --------------------------------------------------------------------

    private static BufferedReader in;
    private static int nextId = 1000;

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

    private static void update(Map<String, Object> u) {
        send(obj("jsonrpc", "2.0", "method", "session/update", "params", obj("sessionId", "s1", "update", u)));
    }

    @SuppressWarnings("unchecked")
    private static String ask(String callId, String title) throws IOException {
        int id = ++nextId;
        send(obj("jsonrpc", "2.0", "id", id, "method", "session/request_permission", "params", obj(
                "sessionId", "s1", "toolCall", obj("toolCallId", callId, "title", title),
                "options", List.of(obj("optionId", "yes", "kind", "allow_once"), obj("optionId", "no", "kind", "reject_once")))));
        String line;
        while ((line = in.readLine()) != null) {
            Map<String, Object> r;
            try {
                r = Json.toMap(line);
            } catch (RuntimeException e) {
                continue;
            }
            if (r.get("id") instanceof Number n && n.intValue() == id && r.get("result") instanceof Map<?, ?> res
                    && res.get("outcome") instanceof Map<?, ?> o) {
                return String.valueOf(o.get("optionId"));
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static void fakeAcpAgent() throws Exception {
        if ("1".equals(System.getenv("FAKE_FAIL_INIT"))) {
            System.err.print("unknown flag: acp");
            System.err.flush();
            System.exit(2);
        }
        in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Map<String, Object> entry = null;
        String cwd = "";
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            Map<String, Object> m = Json.toMap(line);
            String method = String.valueOf(m.get("method"));
            Object id = m.get("id");
            Object params = m.get("params");
            rec(method + " " + Json.stringify(params) + "\n");
            Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : Map.of();
            switch (method) {
                case "initialize" -> reply(id, obj("protocolVersion", 1, "agentCapabilities",
                        obj("mcpCapabilities", obj("http", "1".equals(System.getenv("FAKE_HTTP"))))));
                case "session/new" -> {
                    cwd = String.valueOf(p.get("cwd"));
                    if (p.get("mcpServers") instanceof List<?> l && l.size() == 1) entry = (Map<String, Object>) l.get(0);
                    reply(id, obj("sessionId", "s1"));
                }
                case "session/set_config_option" -> {
                    if ("1".equals(System.getenv("FAKE_NO_CONFIG"))) {
                        send(obj("jsonrpc", "2.0", "id", id, "error", obj("code", -32601, "message", "nope")));
                    } else {
                        reply(id, Map.of());
                    }
                }
                case "session/set_model" -> reply(id, Map.of());
                case "session/prompt" -> {
                    if ("1".equals(System.getenv("FAKE_PROJECT"))) {
                        Map<String, Object> pf = Json.toMap(Files.readString(Path.of(cwd, ".mcp.json")));
                        Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) pf.get("mcpServers")).get("toolnexus");
                        List<Object> hdr = new ArrayList<>();
                        if (e.get("headers") instanceof Map<?, ?> h) {
                            h.forEach((k, v) -> hdr.add(obj("name", k, "value", v)));
                        }
                        entry = obj("type", "http", "url", e.get("url"), "headers", hdr);
                    }
                    update(obj("sessionUpdate", "tool_call", "toolCallId", "tc1", "title", "Calling get_badge_number from toolnexus"));
                    String ours = ask("tc1", "");
                    update(obj("sessionUpdate", "tool_call", "toolCallId", "tc2", "title", "Bash"));
                    String own = ask("tc2", "Bash");
                    String fake = ask("tc3", "my_get_badge_number_wrapper");
                    String again = ask("tc1", "Bash");
                    String text = "perm ours=" + ours + " own=" + own + " fake=" + fake + " again=" + again + "; "
                            + callServed(entry);
                    update(obj("sessionUpdate", "agent_message_chunk", "content", obj("type", "text", "text", text)));
                    reply(id, obj("stopReason", "end_turn"));
                }
                default -> {
                    if (id != null) reply(id, Map.of());
                }
            }
        }
    }

    private static final String[] REQS = {
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fake\",\"version\":\"0\"}}}",
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_badge_number\",\"arguments\":{}}}",
        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"load_skill\",\"arguments\":{\"name\":\"hello-world\"}}}",
    };

    @SuppressWarnings("unchecked")
    private static String callServed(Map<String, Object> entry) {
        List<String> replies = new ArrayList<>();
        int noauth = 0;
        try {
            if (entry != null && "http".equals(entry.get("type"))) {
                HttpClient hc = HttpClient.newHttpClient();
                URI url = URI.create(String.valueOf(entry.get("url")));
                for (String r : REQS) {
                    HttpRequest.Builder b = HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(r));
                    for (Object h : (List<Object>) entry.get("headers")) {
                        Map<String, Object> x = (Map<String, Object>) h;
                        b.header(String.valueOf(x.get("name")), String.valueOf(x.get("value")));
                    }
                    replies.add(hc.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
                }
                noauth = hc.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(REQS[0])).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode();
            } else if (entry != null) {
                List<String> cmd = new ArrayList<>();
                cmd.add(String.valueOf(entry.get("command")));
                for (Object a : (List<Object>) entry.get("args")) cmd.add(String.valueOf(a));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                for (Object e : (List<Object>) entry.get("env")) {
                    Map<String, Object> x = (Map<String, Object>) e;
                    pb.environment().put(String.valueOf(x.get("name")), String.valueOf(x.get("value")));
                }
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process proc = pb.start();
                try (OutputStream os = proc.getOutputStream()) {
                    os.write((String.join("\n", REQS) + "\n").getBytes(StandardCharsets.UTF_8));
                }
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (proc.waitFor() != 0) return "bridge error";
                for (String l : out.strip().split("\n")) replies.add(l);
            }
        } catch (Exception e) {
            return "http error " + e;
        }
        String type = entry == null || entry.get("type") == null ? "<nil>" : String.valueOf(entry.get("type"));
        return "entry=" + type + " noauth=" + noauth + " replies=" + String.join(" | ", replies);
    }
}
