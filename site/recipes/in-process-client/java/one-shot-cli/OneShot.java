import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** One CLI process per turn: the prompt goes in on stdin; the reply comes back on stdout. */
@SuppressWarnings("unchecked")
public final class OneShot {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String json(Object v) {
        try { return JSON.writeValueAsString(v); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // Render the whole request as one prompt: tools, transcript, and the reply contract.
    static String render(InProcess.Request req) {
        List<String> lines = new ArrayList<>(List.of("You are the model inside a tool-calling loop. Tools you may call:"));
        for (Object o : req.tools) {
            Map<String, Object> f = (Map<String, Object>) ((Map<String, Object>) o).get("function");
            lines.add("- " + f.get("name") + ": " + f.get("description") + " args=" + json(f.get("parameters")));
        }
        lines.add("");
        lines.add("Conversation:");
        Map<Object, Object> called = new HashMap<>(); // tool_call_id -> tool name, so results read "[tool add] 5"
        for (Object o : req.messages) {
            Map<String, Object> m = (Map<String, Object>) o;
            Object content = m.get("content");
            if ("tool".equals(m.get("role"))) {
                lines.add("[tool " + called.getOrDefault(m.get("tool_call_id"), m.get("tool_call_id")) + "] " + content);
            } else if (m.get("tool_calls") != null) {
                List<Object> fns = new ArrayList<>();
                for (Object c : (List<Object>) m.get("tool_calls")) {
                    Map<String, Object> fn = (Map<String, Object>) ((Map<String, Object>) c).get("function");
                    called.put(((Map<String, Object>) c).get("id"), fn.get("name"));
                    fns.add(fn);
                }
                lines.add("[assistant called] " + json(fns));
            } else {
                lines.add("[" + m.get("role") + "] " + (content instanceof String s ? s : json(content)));
            }
        }
        lines.add("");
        lines.add("Reply with ONLY one JSON object: {\"tool_calls\":[{\"name\":\"...\",\"arguments\":{...}}]} to call tools,");
        lines.add("or {\"content\":\"...\"} with the final answer. No prose, no code fences.");
        return String.join("\n", lines);
    }

    // Pull the first {...} out of the reply, tolerating fences or chatter around it.
    static InProcess.Response parse(String out) {
        int start = out.indexOf('{'), end = out.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                Map<String, Object> r = JSON.readValue(out.substring(start, end + 1), Map.class);
                if (r.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()) {
                    List<InProcess.ToolCall> tcs = new ArrayList<>();
                    for (Object c : calls) {
                        Map<String, Object> call = (Map<String, Object>) c;
                        Object args = call.get("arguments");
                        tcs.add(new InProcess.ToolCall((String) call.get("name"), args == null ? Map.of() : args));
                    }
                    return InProcess.Response.toolCalls(tcs.toArray(InProcess.ToolCall[]::new));
                }
                if (r.get("content") instanceof String s) return InProcess.Response.content(s);
            } catch (IOException | ClassCastException ignored) { }
        }
        return InProcess.Response.content(out.strip()); // not our contract: treat it as the answer
    }

    public static LlmClient client(List<String> command) { return client(command, command.get(0), 300_000); }

    public static LlmClient client(List<String> command, String model, long timeoutMs) {
        return InProcess.createClient(new InProcess.Options().model(model).generate(req -> {
            try {
                Process p = new ProcessBuilder(command).start();
                CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
                CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()));
                try (OutputStream in = p.getOutputStream()) { in.write(render(req).getBytes(StandardCharsets.UTF_8)); }
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    throw new IOException(command.get(0) + " timed out after " + timeoutMs + " ms");
                }
                if (p.exitValue() != 0)
                    throw new IOException(command.get(0) + " exited " + p.exitValue() + ": "
                            + new String(err.join(), StandardCharsets.UTF_8));
                return parse(new String(out.join(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }));
    }

    private static byte[] readAll(InputStream s) {
        try (s) { return s.readAllBytes(); } catch (IOException e) { return new byte[0]; }
    }
}
