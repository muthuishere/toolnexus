package io.github.muthuishere.toolnexus;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * In-process models (SPEC §8 Gap 2, semantic form).
 *
 * <p>A model running in this JVM is not a network endpoint, so it should not need a base URL, an
 * API key, a style, or — the real tax in this port — a 94-line {@link HttpClient} subclass. You
 * supply {@code generate}; everything HTTP-shaped is built here.
 *
 * <p>This is a second constructor, not a second seam: it is built on the shipped injectable
 * {@code httpClient}, so the tool-calling loop, MCP servers, skills, sub-agents, hooks, metrics and
 * the completion gate behave identically.
 */
public final class InProcess {

    private InProcess() {}

    /**
     * One tool call an in-process model asks for. Flat on purpose: the nested {@code function:{}}
     * wrapper is a wire detail, not something a model author should type. {@code arguments} may be
     * any value (encoded for you) or an already-encoded String, passed through unchanged.
     */
    public static final class ToolCall {
        public String id;
        public String name;
        public Object arguments;

        public ToolCall(String name, Object arguments) { this.name = name; this.arguments = arguments; }
        public ToolCall id(String v) { this.id = v; return this; }
    }

    /** Optional token reporting. Absent ⇒ the run reports zero rather than failing. */
    public static final class Usage {
        public int promptTokens;
        public int completionTokens;
        public int totalTokens;

        public Usage(int promptTokens, int completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }
    }

    /** What an in-process model is handed: the assembled request for THIS call. */
    public static final class Request {
        public final List<Object> messages;
        public final List<Object> tools;
        public final String model;
        /** Every key the client assembled, for a model that wants them. */
        public final Map<String, Object> body;

        Request(List<Object> messages, List<Object> tools, String model, Map<String, Object> body) {
            this.messages = messages;
            this.tools = tools;
            this.model = model;
            this.body = body;
        }
    }

    /** Exactly one assistant message. Set content to finish, or toolCalls to ask for tools. */
    public static final class Response {
        public String content;
        public List<ToolCall> toolCalls;
        public Usage usage;

        public static Response content(String text) {
            Response r = new Response();
            r.content = text;
            return r;
        }

        public static Response toolCalls(ToolCall... calls) {
            Response r = new Response();
            r.toolCalls = List.of(calls);
            return r;
        }

        public Response usage(int promptTokens, int completionTokens) {
            this.usage = new Usage(promptTokens, completionTokens);
            return this;
        }
    }

    /** Every client option EXCEPT the three that describe a wire, plus {@code generate}. */
    public static final class Options {
        public String model;
        public Function<Request, Response> generate;
        public String systemPrompt;
        public int maxTurns;
        public LlmClient.Hooks hooks;
        public long timeoutMs;
        public LlmClient.ConversationStore store;
        public java.util.function.Consumer<LlmClient.MetricEvent> onMetric;
        public Function<io.github.muthuishere.toolnexus.Request, Answer> waitFor;
        public Map<String, Object> requestParams;

        public Options model(String v) { this.model = v; return this; }
        public Options generate(Function<Request, Response> v) { this.generate = v; return this; }
        public Options systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Options maxTurns(int v) { this.maxTurns = v; return this; }
        public Options hooks(LlmClient.Hooks v) { this.hooks = v; return this; }
        public Options timeoutMs(long v) { this.timeoutMs = v; return this; }
        public Options store(LlmClient.ConversationStore v) { this.store = v; return this; }
        public Options onMetric(java.util.function.Consumer<LlmClient.MetricEvent> v) { this.onMetric = v; return this; }
        public Options requestParams(Map<String, Object> v) { this.requestParams = v; return this; }
    }

    /**
     * A sentinel. Never dialled — the client below answers every request before the network is
     * reached — but a URL string is built internally, so it must be syntactically valid.
     * {@code .invalid} is reserved by RFC 2606 precisely so a name can never resolve.
     */
    private static final String BASE_URL = "http://in-process.invalid/v1";

    /** Build a client backed by a model running IN THIS PROCESS. */
    public static LlmClient createClient(Options opts) {
        if (opts == null || opts.generate == null) {
            throw new IllegalArgumentException("toolnexus: InProcess.createClient requires a `generate` function");
        }
        LlmClient.Options o = new LlmClient.Options();
        o.baseUrl = BASE_URL;
        o.style = "openai";
        o.model = opts.model;
        o.httpClient = new GenerateBackedHttpClient(opts.generate);
        o.systemPrompt = opts.systemPrompt;
        if (opts.maxTurns > 0) o.maxTurns = opts.maxTurns;
        o.hooks = opts.hooks;
        // Only when positive: 0 is not "no timeout" here, it is "already expired".
        if (opts.timeoutMs > 0) o.timeoutMs = opts.timeoutMs;
        o.store = opts.store;
        o.onMetric = opts.onMetric;
        o.waitFor = opts.waitFor;
        o.requestParams = opts.requestParams;
        return LlmClient.create(o);
    }

    // ---- the HTTP shim the host no longer has to write ---------------------------

    static final class GenerateBackedHttpClient extends HttpClient {
        private final Function<Request, Response> generate;

        GenerateBackedHttpClient(Function<Request, Response> generate) { this.generate = generate; }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
            Map<String, Object> body = readBody(req);

            // A generate returns a whole answer, so a stream would be one chunk pretending to be
            // many — indistinguishable from a real stream by content or delta count. Refuse.
            if (Boolean.TRUE.equals(body.get("stream"))) {
                throw new IOException("toolnexus: InProcess.createClient does not support streaming — "
                        + "`generate` returns a complete answer. Use run(), or supply an httpClient that streams.");
            }

            List<Object> messages = asList(body.get("messages"));
            List<Object> tools = asList(body.get("tools"));
            Response answer = generate.apply(
                    new Request(messages, tools, String.valueOf(body.get("model")), body));
            if (answer == null) answer = Response.content("");

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            if (answer.toolCalls != null && !answer.toolCalls.isEmpty()) {
                List<Object> calls = new ArrayList<>();
                for (int i = 0; i < answer.toolCalls.size(); i++) {
                    ToolCall c = answer.toolCalls.get(i);
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", c.name);
                    fn.put("arguments", encodeArgs(c.arguments));
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("id", c.id != null ? c.id : "call_" + i);
                    call.put("type", "function");
                    call.put("function", fn);
                    calls.add(call);
                }
                message.put("tool_calls", calls);
            } else {
                message.put("content", answer.content != null ? answer.content : "");
            }

            int prompt = answer.usage != null ? answer.usage.promptTokens : 0;
            int completion = answer.usage != null ? answer.usage.completionTokens : 0;
            int total = answer.usage != null && answer.usage.totalTokens > 0
                    ? answer.usage.totalTokens : prompt + completion;

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", message.containsKey("tool_calls") ? "tool_calls" : "stop");

            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", prompt);
            usage.put("completion_tokens", completion);
            usage.put("total_tokens", total);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("choices", List.of(choice));
            envelope.put("usage", usage);

            return (HttpResponse<T>) new JsonResponse(req, Json.stringify(envelope));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> readBody(HttpRequest req) {
            // The client always sends a JSON body; re-reading it here is how the shim stays
            // behind the shipped seam instead of needing a new one.
            java.util.Optional<HttpRequest.BodyPublisher> bp = req.bodyPublisher();
            if (bp.isEmpty()) return Map.of();
            StringBuilder sb = new StringBuilder();
            bp.get().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(java.nio.ByteBuffer item) { sb.append(java.nio.charset.StandardCharsets.UTF_8.decode(item)); }
                @Override public void onError(Throwable t) {}
                @Override public void onComplete() {}
            });
            Map<String, Object> parsed = Json.toMap(sb.toString());
            return parsed != null ? parsed : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static List<Object> asList(Object v) {
            return v instanceof List ? (List<Object>) v : List.of();
        }

        /** Pass an already-encoded string through; encode anything else. */
        private static String encodeArgs(Object v) {
            if (v == null) return "{}";
            if (v instanceof String s) return s;
            return Json.stringify(v);
        }

        // Only send() is ever called by the client; the rest exist because HttpClient is abstract.
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException("in-process client"); }
        @Override public SSLParameters sslParameters() { throw new UnsupportedOperationException("in-process client"); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest r, HttpResponse.BodyHandler<T> h) {
            try {
                return CompletableFuture.completedFuture(send(r, h));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
            return sendAsync(r, h);
        }
    }

    record JsonResponse(HttpRequest req, String payload) implements HttpResponse<String> {
        @Override public int statusCode() { return 200; }
        @Override public String body() { return payload; }
        @Override public HttpRequest request() { return req; }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a, b) -> true);
        }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return req.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
