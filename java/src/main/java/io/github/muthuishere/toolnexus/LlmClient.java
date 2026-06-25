package io.github.muthuishere.toolnexus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified LLM client — the host loop. Give it a base URL + a style
 * ({@code "openai" | "anthropic"}) and it runs the tool-calling agent loop against
 * a {@link Toolkit}. Mirrors the JS reference ({@code js/src/client.ts}).
 */
public final class LlmClient {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static final class Options {
        public String baseUrl;
        public String style; // "openai" | "anthropic"
        public String model;
        public String apiKey;          // from env if null
        public Map<String, String> headers;
        public String systemPrompt;
        public Integer maxTurns;       // default 10

        public Options baseUrl(String v) { this.baseUrl = v; return this; }
        public Options style(String v) { this.style = v; return this; }
        public Options model(String v) { this.model = v; return this; }
        public Options apiKey(String v) { this.apiKey = v; return this; }
        public Options headers(Map<String, String> v) { this.headers = v; return this; }
        public Options systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Options maxTurns(int v) { this.maxTurns = v; return this; }
    }

    public static final class ToolCall {
        public final String name;
        public final Map<String, Object> args;

        ToolCall(String name, Map<String, Object> args) {
            this.name = name;
            this.args = args;
        }
    }

    public static final class RunResult {
        public final String text;
        public final List<Object> messages;
        public final List<ToolCall> toolCalls;

        RunResult(String text, List<Object> messages, List<ToolCall> toolCalls) {
            this.text = text;
            this.messages = messages;
            this.toolCalls = toolCalls;
        }
    }

    private final Options opts;

    private LlmClient(Options opts) {
        this.opts = opts;
    }

    public static LlmClient create(Options opts) {
        return new LlmClient(opts);
    }

    public RunResult run(String prompt, Toolkit toolkit) {
        return "anthropic".equals(opts.style)
                ? runAnthropic(prompt, toolkit)
                : runOpenAI(prompt, toolkit);
    }

    private String resolveKey() {
        if (opts.apiKey != null && !opts.apiKey.isEmpty()) return opts.apiKey;
        String openrouter = System.getenv("OPENROUTER_API_KEY");
        String openai = System.getenv("OPENAI_API_KEY");
        String anthropic = System.getenv("ANTHROPIC_API_KEY");
        String key = firstNonEmpty(openrouter,
                "anthropic".equals(opts.style) ? anthropic : openai,
                openai, anthropic);
        if (key == null) {
            throw new RuntimeException(
                    "No API key: set apiKey or OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY");
        }
        return key;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private String system(Toolkit toolkit) {
        List<String> parts = new ArrayList<>();
        if (opts.systemPrompt != null && !opts.systemPrompt.isEmpty()) parts.add(opts.systemPrompt);
        String sp = toolkit.skillsPrompt();
        if (sp != null && !sp.isEmpty()) parts.add(sp);
        return String.join("\n\n", parts);
    }

    private int maxTurns() {
        return opts.maxTurns != null ? opts.maxTurns : 10;
    }

    private String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ---- OpenAI-style: POST {baseUrl}/chat/completions ----
    @SuppressWarnings("unchecked")
    private RunResult runOpenAI(String prompt, Toolkit toolkit) {
        String key = resolveKey();
        List<Object> messages = new ArrayList<>();
        String system = system(toolkit);
        if (!system.isEmpty()) messages.add(msg("system", system));
        messages.add(msg("user", prompt));
        List<Map<String, Object>> tools = toolkit.toOpenAI();
        List<ToolCall> toolCalls = new ArrayList<>();

        for (int turn = 0; turn < maxTurns(); turn++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", opts.model);
            body.put("messages", messages);
            body.put("tools", tools);
            body.put("tool_choice", "auto");

            String url = stripTrailingSlash(opts.baseUrl) + "/chat/completions";
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + key);
            headers.put("Content-Type", "application/json");
            if (opts.headers != null) headers.putAll(opts.headers);

            Map<String, Object> data = postJson(url, headers, body);
            List<Object> choices = (List<Object>) data.get("choices");
            Map<String, Object> message = (Map<String, Object>) ((Map<String, Object>) choices.get(0)).get("message");
            messages.add(message);
            List<Object> calls = (List<Object>) message.get("tool_calls");
            if (calls == null || calls.isEmpty()) {
                Object content = message.get("content");
                return new RunResult(content == null ? "" : String.valueOf(content), messages, toolCalls);
            }
            for (Object callObj : calls) {
                Map<String, Object> call = (Map<String, Object>) callObj;
                Map<String, Object> fn = (Map<String, Object>) call.get("function");
                String fnName = String.valueOf(fn.get("name"));
                Map<String, Object> args = Json.parseObjectLoose(
                        fn.get("arguments") == null ? "{}" : String.valueOf(fn.get("arguments")));
                toolCalls.add(new ToolCall(fnName, args));
                ToolResult result = toolkit.execute(fnName, args);
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", call.get("id"));
                toolMsg.put("content", result.output());
                messages.add(toolMsg);
            }
        }
        return new RunResult(lastAssistantText(messages), messages, toolCalls);
    }

    // ---- Anthropic-style: POST {baseUrl}/messages ----
    @SuppressWarnings("unchecked")
    private RunResult runAnthropic(String prompt, Toolkit toolkit) {
        String key = resolveKey();
        String base = stripTrailingSlash(opts.baseUrl);
        String endpoint = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        String system = system(toolkit);
        List<Object> messages = new ArrayList<>();
        messages.add(msg("user", prompt));
        List<Map<String, Object>> tools = toolkit.toAnthropic();
        List<ToolCall> toolCalls = new ArrayList<>();

        for (int turn = 0; turn < maxTurns(); turn++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", opts.model);
            body.put("max_tokens", 4096);
            if (!system.isEmpty()) body.put("system", system);
            body.put("messages", messages);
            body.put("tools", tools);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-api-key", key);
            headers.put("anthropic-version", "2023-06-01");
            headers.put("Content-Type", "application/json");
            if (opts.headers != null) headers.putAll(opts.headers);

            Map<String, Object> data = postJson(endpoint, headers, body);
            List<Object> content = (List<Object>) data.get("content");
            if (content == null) content = new ArrayList<>();
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", content);
            messages.add(assistant);

            List<Map<String, Object>> uses = new ArrayList<>();
            for (Object b : content) {
                Map<String, Object> block = (Map<String, Object>) b;
                if ("tool_use".equals(block.get("type"))) uses.add(block);
            }
            if (uses.isEmpty()) {
                StringBuilder text = new StringBuilder();
                for (Object b : content) {
                    Map<String, Object> block = (Map<String, Object>) b;
                    if ("text".equals(block.get("type"))) text.append(String.valueOf(block.get("text")));
                }
                return new RunResult(text.toString(), messages, toolCalls);
            }
            List<Object> results = new ArrayList<>();
            for (Map<String, Object> use : uses) {
                Map<String, Object> input = (Map<String, Object>) use.get("input");
                if (input == null) input = new LinkedHashMap<>();
                String useName = String.valueOf(use.get("name"));
                toolCalls.add(new ToolCall(useName, input));
                ToolResult result = toolkit.execute(useName, input);
                Map<String, Object> tr = new LinkedHashMap<>();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", use.get("id"));
                tr.put("content", result.output());
                tr.put("is_error", result.isError());
                results.add(tr);
            }
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", results);
            messages.add(userMsg);
        }
        return new RunResult("", messages, toolCalls);
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private Map<String, Object> postJson(String url, Map<String, String> headers, Map<String, Object> body) {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }
        try {
            HttpResponse<String> res = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new RuntimeException("LLM " + res.statusCode() + ": " + res.body());
            }
            return Json.toMap(res.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String lastAssistantText(List<Object> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object o = messages.get(i);
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                if ("assistant".equals(m.get("role")) && m.get("content") instanceof String) {
                    return (String) m.get("content");
                }
            }
        }
        return "";
    }
}
