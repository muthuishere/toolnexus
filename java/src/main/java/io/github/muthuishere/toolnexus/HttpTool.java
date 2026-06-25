package io.github.muthuishere.toolnexus;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP / REST tool (source {@code "http"}). Declares a remote endpoint as a
 * uniform {@link Tool}. Mirrors the JS reference ({@code js/src/http.ts}).
 *
 * <ul>
 *   <li>{@code {placeholder}} URL substitution (consumed from args).</li>
 *   <li>{@code query} arg names → querystring (GET → all args to query).</li>
 *   <li>body json|form|raw for non-GET requests.</li>
 *   <li>{@code ${ENV}} header expansion from {@code System.getenv} (never logged).</li>
 *   <li>non-2xx ⇒ {@code "HTTP <status>: <body>"} error; 2xx ⇒ body + metadata{status}.</li>
 * </ul>
 */
public final class HttpTool implements Tool {
    private static final long DEFAULT_TIMEOUT = 30_000L;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static final class Options {
        public String name;
        public String description;
        public String method;
        public String url;
        public Map<String, String> headers;
        public List<String> query;
        public String body; // "json" | "form" | "raw"
        public Map<String, Object> inputSchema;
        public Long timeout;
        public String resultMode; // "text" | "json" | "status+text"
    }

    private final Options opts;
    private final String method;
    private final Set<String> querySet;
    private final Map<String, Object> inputSchema;

    private HttpTool(Options opts) {
        this.opts = opts;
        this.method = opts.method.toUpperCase();
        this.querySet = new HashSet<>(opts.query == null ? List.of() : opts.query);
        this.inputSchema = opts.inputSchema != null ? opts.inputSchema : emptySchema();
    }

    public static HttpTool of(Options opts) {
        return new HttpTool(opts);
    }

    @Override
    public String name() {
        return opts.name;
    }

    @Override
    public String description() {
        return opts.description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    @Override
    public String source() {
        return "http";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Map<String, Object> a = new LinkedHashMap<>(args == null ? Map.of() : args);

        // 1. substitute {placeholders} in the URL from args (consumed afterwards)
        StringBuffer sb = new StringBuffer();
        Matcher m = PLACEHOLDER.matcher(opts.url);
        while (m.find()) {
            String key = m.group(1);
            Object val = a.remove(key);
            String enc = URLEncoder.encode(val == null ? "" : String.valueOf(val), StandardCharsets.UTF_8);
            m.appendReplacement(sb, Matcher.quoteReplacement(enc));
        }
        m.appendTail(sb);
        String url = sb.toString();

        // 2. querystring args
        List<String> qsParts = new ArrayList<>();
        List<String> keys = new ArrayList<>(a.keySet());
        for (String key : keys) {
            if (querySet.contains(key) || method.equals("GET")) {
                Object v = a.remove(key);
                qsParts.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
            }
        }
        if (!qsParts.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + String.join("&", qsParts);
        }

        // 3. body
        Map<String, String> expanded = McpSource.expandEnvHeaders(opts.headers);
        Map<String, String> headers = expanded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(expanded);
        String bodyInit = null;
        if (!method.equals("GET") && !method.equals("HEAD") && !a.isEmpty()) {
            String mode = opts.body == null ? "json" : opts.body;
            if (mode.equals("json")) {
                headers.putIfAbsent("Content-Type", "application/json");
                bodyInit = Json.stringify(a);
            } else if (mode.equals("form")) {
                headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
                List<String> formParts = new ArrayList<>();
                for (Map.Entry<String, Object> e : a.entrySet()) {
                    formParts.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
                }
                bodyInit = String.join("&", formParts);
            } else {
                Object b = a.get("body");
                bodyInit = b == null ? "" : String.valueOf(b);
            }
        }

        long timeout = ctx != null && ctx.timeoutMs() != null
                ? ctx.timeoutMs()
                : (opts.timeout != null ? opts.timeout : DEFAULT_TIMEOUT);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeout));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }
        HttpRequest.BodyPublisher publisher = bodyInit == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(bodyInit, StandardCharsets.UTF_8);
        rb.method(method, publisher);

        try {
            HttpResponse<String> res = CLIENT.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            String text = res.body();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("status", status);
            if (status < 200 || status >= 300) {
                return new ToolResult("HTTP " + status + ": " + text, true, meta);
            }
            String output;
            if ("status+text".equals(opts.resultMode)) {
                output = status + "\n" + text;
            } else if ("json".equals(opts.resultMode)) {
                output = Json.stringify(Json.parseLoose(text));
            } else {
                output = text;
            }
            return new ToolResult(output, false, meta);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    private static Map<String, Object> emptySchema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", new LinkedHashMap<>());
        s.put("additionalProperties", false);
        return s;
    }
}
