package io.github.muthuishere.toolnexus;

import tools.jackson.core.type.TypeReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared internals of the §7G media clients: the canonical verb table, the API base (headers,
 * redirects, deadlines, unavailable mapping — §7G.8), gather (§7G.5), input references (§7G.1)
 * and multipart bodies. Package-private: the public surface is {@link Media} and the clients.
 */
final class MediaSupport {

    private MediaSupport() {}

    static final long DEFAULT_TIMEOUT_MS = 600_000L;
    static final long DEFAULT_MAX_BYTES = 104_857_600L;
    static final HttpClient DEFAULT_HTTP = HttpClient.newHttpClient(); // Redirect.NEVER: we follow ourselves

    // ------------------------------------------------------------------
    // Canonical verbs — conformance/media/verbs.json, embedded verbatim as a resource.
    // ------------------------------------------------------------------

    static final String VERBS_JSON = resource("media/verbs.json");
    static final String A2E_JSON = resource("media/a2e.json");

    @SuppressWarnings("unchecked")
    static final List<Map<String, Object>> VERBS =
            (List<Map<String, Object>>) Json.toMap(VERBS_JSON).get("verbs");

    static String resource(String name) {
        try (InputStream in = MediaSupport.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("toolnexus: missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("toolnexus: cannot read resource " + name, e);
        }
    }

    static Map<String, Object> verbSpec(String verb) {
        for (Map<String, Object> v : VERBS) if (v.get("verb").equals(verb)) return v;
        return null;
    }

    static boolean isVerb(String verb) {
        return verbSpec(verb) != null;
    }

    /** The canonical args of {@code verb}, in canonical order. */
    @SuppressWarnings("unchecked")
    static List<Media.ArgSpec> canonicalArgs(String verb) {
        Map<String, Object> v = verbSpec(verb);
        List<Media.ArgSpec> out = new ArrayList<>();
        if (v == null) return out;
        for (Map<String, Object> a : (List<Map<String, Object>>) v.get("args")) {
            out.add(new Media.ArgSpec((String) a.get("name"), Boolean.TRUE.equals(a.get("required"))));
        }
        return out;
    }

    /** {@code image|video|audio|text} — the kind of result a verb produces. */
    static String kindOf(String verb) {
        return switch (verb) {
            case "image" -> "image";
            case "video", "talk" -> "video";
            case "speech", "music" -> "audio";
            default -> "text";
        };
    }

    /** §1B part type of a media kind: image ⇒ image, audio ⇒ audio, video ⇒ file. */
    static String fileType(String kind) {
        return switch (kind == null ? "" : kind) {
            case "audio" -> "audio";
            case "video" -> "file";
            default -> "image";
        };
    }

    // ------------------------------------------------------------------
    // Mime / extension tables (§7G.5, §7G.7 step 8)
    // ------------------------------------------------------------------

    static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"), Map.entry("gif", "image/gif"), Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"), Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"), Map.entry("ogg", "audio/ogg"), Map.entry("m4a", "audio/mp4"),
            Map.entry("flac", "audio/flac"));

    private static final Map<String, String> EXT = Map.ofEntries(
            Map.entry("image/png", ".png"), Map.entry("image/jpeg", ".jpg"), Map.entry("image/webp", ".webp"),
            Map.entry("image/gif", ".gif"), Map.entry("video/mp4", ".mp4"), Map.entry("video/webm", ".webm"),
            Map.entry("video/quicktime", ".mov"), Map.entry("audio/mpeg", ".mp3"), Map.entry("audio/wav", ".wav"),
            Map.entry("audio/ogg", ".ogg"), Map.entry("audio/mp4", ".m4a"), Map.entry("audio/flac", ".flac"),
            Map.entry("audio/x-wav", ".wav"), Map.entry("audio/wave", ".wav"), Map.entry("audio/aac", ".aac"),
            Map.entry("text/plain", ".txt"));

    static String extFor(String mime) {
        return EXT.getOrDefault(mime, ".bin");
    }

    private static final Pattern URL_EXT = Pattern.compile("\\.([A-Za-z0-9]{1,5})$");

    /**
     * §7G.12 D22: the saved-file extension — the URL path's own extension when it has one (last
     * segment, {@code .} + 1–5 ASCII letters/digits, lowercased), else the mime table, else .bin.
     */
    static String extFor(String mime, String url) {
        if (url != null) {
            String path = url;
            try {
                String p = URI.create(url).getRawPath();
                if (p != null) path = p;
            } catch (IllegalArgumentException ignored) {
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
            }
            String last = path.substring(path.lastIndexOf('/') + 1);
            Matcher m = URL_EXT.matcher(last);
            if (m.find() && m.start() > 0) return "." + m.group(1).toLowerCase(Locale.ROOT);
        }
        return extFor(mime);
    }

    /** §7G.12 D23: the default media User-Agent, {@code toolnexus/<version> (java)}. */
    static final String USER_AGENT = "toolnexus/" + version() + " (java)";

    private static String version() {
        try (var in = MediaSupport.class.getResourceAsStream("/io/github/muthuishere/toolnexus/version.txt")) {
            if (in != null) {
                String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (IOException ignored) {
        }
        String v = MediaSupport.class.getPackage().getImplementationVersion();
        return v == null || v.isEmpty() ? "0.0.0" : v;
    }

    static String kindDefaultMime(String kind) {
        return switch (kind == null ? "" : kind) {
            case "video" -> "video/mp4";
            case "audio" -> "audio/mpeg";
            default -> "image/png";
        };
    }

    /** The mime of a file name / path by the extension table, or {@code null}. */
    static String mimeOfName(String name) {
        String last = name.substring(name.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        if (dot < 0) return null;
        return MIME.get(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The URL's path (query and fragment stripped). */
    static String urlPath(String url) {
        String u = url;
        int q = u.indexOf('#'); if (q >= 0) u = u.substring(0, q);
        q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int scheme = u.indexOf("://");
        if (scheme >= 0) {
            int slash = u.indexOf('/', scheme + 3);
            return slash >= 0 ? u.substring(slash) : "";
        }
        return u;
    }

    /** §7G.7 step 8: extension of the URL path, else the kind default. */
    static String mimeOf(String url, String kind) {
        String m = mimeOfName(urlPath(url));
        return m != null ? m : kindDefaultMime(kind);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** RFC 3986 unreserved kept; everything else %XX (uppercase) of its UTF-8 bytes. */
    static String pctEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
            int c = by & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') sb.append((char) c);
            else sb.append('%').append(String.format("%02X", c));
        }
        return sb.toString();
    }

    /** The first {@code n} code points; {@code …} appended when truncated. */
    static String cap(String text, int n) {
        if (text.codePointCount(0, text.length()) <= n) return text;
        return text.substring(0, text.offsetByCodePoints(0, n)) + "…";
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** String as-is; an integral number as integer text; other number shortest form; else JSON. */
    static String render(Object o) {
        if (o instanceof String s) return s;
        if (o instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString());
            if (d.signum() == 0 || d.stripTrailingZeros().scale() <= 0) return d.toBigInteger().toString();
            return n.toString();
        }
        return Json.stringify(o);
    }

    static boolean isIntegral(Number n) {
        try {
            BigDecimal d = new BigDecimal(n.toString());
            return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A job id: a non-empty string or an integral number (§7G.8); else {@code null}. */
    static String jobIdOf(Object id) {
        if (id instanceof String s && !s.isEmpty()) return s;
        if (id instanceof Number n && isIntegral(n)) return render(n);
        return null;
    }

    /** Poll status rendering (§7G.8): string, integral, other number; else "". */
    static String renderStatus(Object st) {
        if (st instanceof String || st instanceof Number) return render(st);
        return "";
    }

    static boolean jsonEq(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) return false;
            for (Object k : ma.keySet()) if (!jsonEq(ma.get(k), mb.get(k))) return false;
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) if (!jsonEq(la.get(i), lb.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    private static boolean isIndex(String seg) {
        return !seg.isEmpty() && seg.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    static Object readPath(Object root, String path) {
        if (path == null) return null;
        if (path.isEmpty()) return root;
        Object cur = root;
        for (String seg : path.split("\\.", -1)) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(seg);
            } else if (cur instanceof List<?> l && isIndex(seg)) {
                int i;
                try { i = Integer.parseInt(seg); } catch (NumberFormatException e) { return null; }
                cur = i < l.size() ? l.get(i) : null;
            } else {
                return null;
            }
            if (cur == null) return null;
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    static void writePath(Map<String, Object> root, String path, Object value) {
        String[] segs = path.split("\\.", -1);
        Object cur = root;
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            boolean last = i == segs.length - 1;
            Object next = last ? value : (isIndex(segs[i + 1]) ? new ArrayList<>() : new LinkedHashMap<>());
            if (cur instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) cur;
                if (last) { m.put(seg, value); return; }
                Object existing = m.get(seg);
                if (!(existing instanceof Map) && !(existing instanceof List)
                        || existing instanceof List && !isIndex(segs[i + 1])) { m.put(seg, next); existing = next; }
                cur = existing;
            } else if (cur instanceof List) {
                List<Object> l = (List<Object>) cur;
                int idx;
                try { idx = Integer.parseInt(seg); } catch (NumberFormatException e) { return; }
                while (l.size() <= idx) l.add(null);
                if (last) { l.set(idx, value); return; }
                Object existing = l.get(idx);
                if (!(existing instanceof Map) && !(existing instanceof List)
                        || existing instanceof List && !isIndex(segs[i + 1])) { l.set(idx, next); existing = next; }
                cur = existing;
            } else {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Object deepCopyValue(Object v) {
        if (v instanceof Map) return deepCopy((Map<String, Object>) v);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(deepCopyValue(o));
            return out;
        }
        return v;
    }

    static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), deepCopyValue(e.getValue()));
        return out;
    }

    static String trimSlash(String url) {
        String u = url == null ? "" : url;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static String origin(URI u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        int port = u.getPort();
        if (port < 0) port = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
        return scheme + "://" + (u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT)) + ":" + port;
    }

    static String originOf(String url) {
        try {
            return origin(URI.create(url));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static boolean isHttp(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    static long positive(Object o, long dflt) {
        return o instanceof Number n && n.doubleValue() > 0 ? n.longValue() : dflt;
    }

    static Map<String, Object> clean(Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (args != null) for (Map.Entry<String, Object> e : args.entrySet()) if (e.getValue() != null) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** The configured key, else the env var read now; {@code null} when neither is non-empty. */
    static String keyOf(Media.Config cfg, String env) {
        if (cfg.key != null && !cfg.key.isEmpty()) return cfg.key;
        String v = Media.env.apply(env);
        return v == null || v.isEmpty() ? null : v;
    }

    static Media.MediaUnavailableException noKey(String client, String env) {
        return new Media.MediaUnavailableException(client + ": no API key (set " + env + ")", null);
    }

    static String modelFor(Media.Config cfg, String verb, String primaryVerb, String dflt) {
        if (cfg.models != null && cfg.models.get(verb) != null && !cfg.models.get(verb).isEmpty()) return cfg.models.get(verb);
        if (verb.equals(primaryVerb) && cfg.model != null && !cfg.model.isEmpty()) return cfg.model;
        return dflt;
    }

    static Integer intArg(Map<String, Object> args, String name) {
        Object o = args.get(name);
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static String strArg(Map<String, Object> args, String name) {
        Object o = args.get(name);
        return o == null ? null : render(o);
    }

    static void checkCancel(BooleanSupplier cancel) {
        if ((cancel != null && cancel.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
            throw new LlmClient.CancelledException("media cancelled");
        }
    }

    static Object parseJson(byte[] body) {
        return parseJsonOrNull(body);
    }

    private static final Object NOT_JSON = new Object();

    private static Object parseJsonOrNull(byte[] body) {
        try {
            return Json.MAPPER.readValue(body, new TypeReference<Object>() {});
        } catch (Exception e) {
            return NOT_JSON;
        }
    }

    // ------------------------------------------------------------------
    // Job — one verb call against an API: headers, redirects, deadline, progress.
    // ------------------------------------------------------------------

    record Resp(int status, HttpHeaders headers, byte[] body, boolean overflow) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static final class Job {
        final String client;
        final String verb;
        final HttpClient http;
        final String baseOrigin;
        final Map<String, String> headers;
        final BooleanSupplier cancel;
        final long startNanos;
        final long deadlineNanos;
        final long timeoutMs;
        final Consumer<Media.Progress> progress;
        String jobId;

        Job(String client, String verb, Media.Config cfg, String baseUrl, Map<String, String> headers,
            Media.Ctx ctx, long timeoutMs) {
            this.client = client;
            this.verb = verb;
            this.http = cfg.httpClient != null ? cfg.httpClient : DEFAULT_HTTP;
            this.baseOrigin = originOf(baseUrl);
            this.headers = headers == null ? Map.of() : headers;
            this.cancel = ctx == null ? null : ctx.cancelled;
            this.timeoutMs = timeoutMs;
            this.startNanos = System.nanoTime();
            long d = startNanos + timeoutMs * 1_000_000L;
            if (ctx != null && ctx.deadline != null) {
                long left = java.time.Duration.between(java.time.Instant.now(), ctx.deadline).toNanos();
                d = Math.min(d, startNanos + left);
            }
            this.deadlineNanos = d;
            this.progress = ctx != null && ctx.progress != null ? ctx.progress
                    : cfg.loop != null ? cfg.loop.onProgress : null;
        }

        Media.MediaException timeout() {
            String msg = jobId != null ? "job " + jobId + " timed out after " + timeoutMs + "ms"
                    : verb + ": timed out after " + timeoutMs + "ms";
            return new Media.MediaException(msg, "timeout", jobId);
        }

        Media.MediaException failed(String msg) {
            return new Media.MediaException(msg, "failed", jobId);
        }

        boolean expired() {
            return System.nanoTime() - deadlineNanos >= 0;
        }

        void checkCancel() {
            MediaSupport.checkCancel(cancel);
        }

        void report(String status) {
            if (progress != null) {
                progress.accept(new Media.Progress(verb, client, jobId, status,
                        (System.nanoTime() - startNanos) / 1_000_000L));
            }
        }

        /** Wait {@code ms} (cancellable); never past the deadline. */
        void sleep(long ms) {
            long end = Math.min(System.nanoTime() + Math.max(0, ms) * 1_000_000L, deadlineNanos);
            try {
                while (true) {
                    checkCancel();
                    long left = end - System.nanoTime();
                    if (left <= 0) return;
                    Thread.sleep(Math.min(left / 1_000_000L + 1, 50));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmClient.CancelledException("media cancelled");
            }
        }

        /**
         * One HTTP exchange with §7G.8 redirects: 301/302/303/307/308 with Location are followed
         * (≤ 10), 301/302/303 after a non-GET/HEAD become GET without body, client headers only
         * on the base URL's origin (and only when {@code withHeaders}), every hop bounded by the
         * job deadline. The body is read up to {@code limit + 1} bytes.
         */
        Resp send(String method, String url, byte[] body, String contentType, boolean withHeaders, long limit) {
            checkCancel();
            URI target;
            try {
                target = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw failed(verb + ": " + e.getMessage());
            }
            for (int hops = 0; ; hops++) {
                HttpRequest.Builder b;
                try {
                    b = HttpRequest.newBuilder(target);
                } catch (IllegalArgumentException e) {
                    throw failed(verb + ": " + e.getMessage());
                }
                boolean ua = false;
                if (withHeaders && origin(target).equals(baseOrigin)) {
                    for (Map.Entry<String, String> h : headers.entrySet()) {
                        b.header(h.getKey(), h.getValue());
                        if (h.getKey().equalsIgnoreCase("User-Agent")) ua = true;
                    }
                }
                if (!ua) b.header("User-Agent", USER_AGENT); // §7G.12 D23
                if (body != null) {
                    if (contentType != null) b.header("Content-Type", contentType);
                    b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
                } else {
                    b.method(method, HttpRequest.BodyPublishers.noBody());
                }
                Resp r = await(b.build(), limit);
                int sc = r.status();
                String loc = r.headers().firstValue("Location").orElse(null);
                if (loc == null || !(sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308)) return r;
                if (hops >= 10) throw failed(verb + ": too many redirects");
                target = target.resolve(loc);
                if ((sc == 301 || sc == 302 || sc == 303)
                        && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                    method = "GET";
                    body = null;
                }
            }
        }

        private Resp await(HttpRequest req, long limit) {
            CompletableFuture<HttpResponse<byte[]>> f = http.sendAsync(req, info -> new Capped(limit));
            try {
                while (true) {
                    checkCancel();
                    long left = deadlineNanos - System.nanoTime();
                    if (left <= 0) throw timeout();
                    try {
                        HttpResponse<byte[]> r = f.get(Math.min(left, 50_000_000L), TimeUnit.NANOSECONDS);
                        byte[] b = r.body() == null ? new byte[0] : r.body();
                        return new Resp(r.statusCode(), r.headers(), b, b.length > limit);
                    } catch (TimeoutException ignored) {
                        // re-check cancel and deadline
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmClient.CancelledException("media cancelled");
            } catch (ExecutionException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                if (c instanceof java.net.http.HttpTimeoutException) throw timeout();
                throw failed(verb + ": " + (c.getMessage() == null ? c.toString() : c.getMessage()));
            } finally {
                if (!f.isDone()) f.cancel(true);
            }
        }

        /** An API call: client headers, non-2xx mapped (402 / 429 quota ⇒ unavailable). */
        Resp api(String method, String url, byte[] body, String contentType) {
            Resp r = send(method, url, body, contentType, true, Long.MAX_VALUE - 1);
            checkStatus(r);
            return r;
        }

        void checkStatus(Resp r) {
            int sc = r.status();
            if (sc >= 200 && sc < 300) return;
            String text = r.text();
            if (sc == 402 || (sc == 429 && quotaWords(text))) {
                throw new Media.MediaUnavailableException(client + ": HTTP " + sc + ": " + cap(text, 1000), jobId);
            }
            throw failed("HTTP " + sc + ": " + cap(text, 1000));
        }

        Object json(Resp r) {
            Object parsed = parseJsonOrNull(r.body());
            if (parsed == NOT_JSON) throw failed(verb + ": response is not JSON");
            return parsed;
        }

        Object apiJson(String method, String url, Object jsonBody) {
            byte[] body = jsonBody == null ? null : Json.stringify(jsonBody).getBytes(StandardCharsets.UTF_8);
            return json(api(method, url, body, jsonBody == null ? null : "application/json"));
        }
    }

    static boolean quotaWords(String text) {
        String l = text.toLowerCase(Locale.ROOT);
        return l.contains("quota") || l.contains("credit") || l.contains("billing") || l.contains("balance");
    }

    /** Collects a response body up to {@code limit + 1} bytes, then stops reading. */
    static final class Capped implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final CompletableFuture<byte[]> cf = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private Flow.Subscription sub;

        Capped(long limit) { this.limit = limit; }

        @Override public CompletionStage<byte[]> getBody() { return cf; }

        @Override public void onSubscribe(Flow.Subscription s) {
            sub = s;
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            if (cf.isDone()) return;
            for (ByteBuffer b : items) {
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.write(chunk, 0, chunk.length);
                if (out.size() > limit) {
                    sub.cancel();
                    cf.complete(out.toByteArray());
                    return;
                }
            }
        }

        @Override public void onError(Throwable t) {
            if (!cf.isDone()) cf.completeExceptionally(t);
        }

        @Override public void onComplete() {
            if (!cf.isDone()) cf.complete(out.toByteArray());
        }
    }

    // ------------------------------------------------------------------
    // Gather (§7G.5)
    // ------------------------------------------------------------------

    private static final AtomicLong SEQ = new AtomicLong();

    static final class Gather {
        final String client;
        final String verb;
        final String kind;
        final String mode;
        final String dir;
        final long maxBytes;
        final boolean keep;
        final Job job;

        Gather(String client, String verb, String kind, Media.Config cfg, String defaultMode, Job job) {
            this.client = client;
            this.verb = verb;
            this.kind = kind;
            Media.Gather g = cfg.gather;
            String m = g == null || g.mode == null || g.mode.isEmpty() ? defaultMode : g.mode;
            this.mode = m;
            this.dir = g == null || g.dir == null || g.dir.isEmpty()
                    ? Path.of(System.getProperty("java.io.tmpdir"), "toolnexus-media").toString() : g.dir;
            this.maxBytes = g == null || g.maxBytes == null || g.maxBytes <= 0 ? DEFAULT_MAX_BYTES : g.maxBytes;
            this.keep = g == null || g.keep == null || g.keep;
            this.job = job;
        }

        String type() {
            return fileType(kind);
        }

        Media.MediaException tooBig() {
            return new Media.MediaException(verb + ": file exceeds gather.maxBytes (" + maxBytes + " bytes)", "failed",
                    job == null ? null : job.jobId);
        }

        private Path save(byte[] b, String mime, String fromUrl) {
            try {
                Path d = Path.of(dir).toAbsolutePath();
                Files.createDirectories(d);
                Path p = d.resolve(client + "-" + verb + "-" + System.currentTimeMillis() + "-"
                        + SEQ.incrementAndGet() + extFor(mime, fromUrl));
                Files.write(p, b);
                return p;
            } catch (IOException e) {
                throw new Media.MediaException(verb + ": cannot save file: " + e.getMessage(), "failed",
                        job == null ? null : job.jobId);
            }
        }

        Media.MediaFile bytes(byte[] b, String mime) {
            return bytes(b, mime, null);
        }

        /** {@code fromUrl}: the URL the bytes came from (D22 extension), or null. */
        Media.MediaFile bytes(byte[] b, String mime, String fromUrl) {
            if (b.length > maxBytes) throw tooBig();
            if ("inline".equals(mode)) {
                return new Media.MediaFile(type(), mime, null, null, Base64.getEncoder().encodeToString(b));
            }
            Path p = save(b, mime, fromUrl);
            return new Media.MediaFile(type(), mime, "file://" + p, p.toString(), null);
        }

        Media.MediaFile base64(String b64, String mime) {
            byte[] b;
            try {
                b = Base64.getMimeDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new Media.MediaException(verb + ": invalid base64 result", "failed", job == null ? null : job.jobId);
            }
            return bytes(b, mime);
        }

        Media.MediaFile dataUrl(String url) {
            int comma = url.indexOf(',');
            String meta = comma < 0 ? url.substring(5) : url.substring(5, comma);
            String payload = comma < 0 ? "" : url.substring(comma + 1);
            boolean b64 = meta.endsWith(";base64");
            String mime = (b64 ? meta.substring(0, meta.length() - 7) : meta);
            int semi = mime.indexOf(';');
            if (semi >= 0) mime = mime.substring(0, semi);
            if (mime.isEmpty()) mime = kindDefaultMime(kind);
            if (b64) return base64(payload, mime);
            return bytes(java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8), mime);
        }

        /** A URL result: http(s), data: or file:// (anything else is kept as given). */
        Media.MediaFile url(String url) {
            String l = url.toLowerCase(Locale.ROOT);
            if (l.startsWith("data:")) return dataUrl(url);
            if (l.startsWith("file://")) return local(url.substring("file://".length()));
            if ("url".equals(mode) || !isHttp(url) || job == null) {
                return new Media.MediaFile(type(), mimeOf(url, kind), url, null, null);
            }
            Resp r = job.send("GET", url, null, null, false, maxBytes);
            if (r.status() < 200 || r.status() >= 300) {
                throw job.failed(verb + ": download failed: HTTP " + r.status());
            }
            if (r.overflow()) {
                throw job.failed(verb + ": download failed: file exceeds gather.maxBytes (" + maxBytes + " bytes)");
            }
            String mime = r.headers().firstValue("Content-Type").map(MediaSupport::stripParams).orElse("");
            if (mime.isEmpty() || mime.equalsIgnoreCase("application/octet-stream")) mime = mimeOf(url, kind);
            return bytes(r.body(), mime, url);
        }

        /** A local path result (CLI clients). */
        Media.MediaFile local(String path) {
            Path p = Path.of(path).toAbsolutePath();
            String mime = mimeOfName(p.toString());
            if (mime == null) mime = kindDefaultMime(kind);
            if ("url".equals(mode)) {
                return new Media.MediaFile(type(), mime, "file://" + p, p.toString(), null);
            }
            byte[] b;
            try {
                if (Files.size(p) > maxBytes) throw tooBig();
                b = Files.readAllBytes(p);
            } catch (IOException e) {
                throw new Media.MediaException(verb + ": cannot read \"" + path + "\": " + e.getMessage(), "failed",
                        job == null ? null : job.jobId);
            }
            Media.MediaFile f = bytes(b, mime);
            if (!"inline".equals(mode) && !keep) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            }
            return f;
        }
    }

    /** Compact JSON with object keys sorted by code point at every depth; array order kept (§7G.12 D15). */
    @SuppressWarnings("unchecked")
    static String sortedJson(Object o) {
        return Json.stringify(sortKeys(o));
    }

    private static Object sortKeys(Object o) {
        if (o instanceof Map<?, ?> m) {
            java.util.TreeMap<String, Object> out = new java.util.TreeMap<>(MediaSupport::compareCodePoints);
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), sortKeys(e.getValue()));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(sortKeys(x));
            return out;
        }
        return o;
    }

    private static int compareCodePoints(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** An integral JSON number as a Long, else null (§7G.12 D21). */
    static Long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    static String stripParams(String ct) {
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Input references (§7G.1)
    // ------------------------------------------------------------------

    record Input(byte[] bytes, String filename, String contentType) {}

    static Input readInput(Job job, String verb, String value, long maxBytes) {
        String prefix = verb + ": cannot read \"" + value + "\"";
        byte[] bytes;
        String name;
        if (isHttp(value)) {
            Resp r;
            try {
                r = job.send("GET", value, null, null, false, maxBytes);
            } catch (Media.MediaException e) {
                if ("timeout".equals(e.status())) throw e;
                throw new Media.MediaException(prefix + ": " + e.getMessage(), "failed", null);
            }
            if (r.status() < 200 || r.status() >= 300) {
                throw new Media.MediaException(prefix + ": HTTP " + r.status(), "failed", null);
            }
            if (r.overflow()) throw new Media.MediaException(prefix + ": file exceeds gather.maxBytes (" + maxBytes + " bytes)", "failed", null);
            bytes = r.body();
            name = urlPath(value);
        } else {
            String path = value.startsWith("file://") ? value.substring("file://".length()) : value;
            try {
                Path p = Path.of(path);
                if (Files.size(p) > maxBytes) {
                    throw new Media.MediaException(prefix + ": file exceeds gather.maxBytes (" + maxBytes + " bytes)", "failed", null);
                }
                bytes = Files.readAllBytes(p);
            } catch (IOException | RuntimeException e) {
                if (e instanceof Media.MediaException me) throw me;
                throw new Media.MediaException(prefix + ": " + e.getMessage(), "failed", null);
            }
            name = path;
        }
        String last = name.substring(name.lastIndexOf('/') + 1);
        if (last.isEmpty()) last = "input";
        String ct = mimeOfName(last);
        return new Input(bytes, last, ct == null ? "application/octet-stream" : ct);
    }

    // ------------------------------------------------------------------
    // multipart/form-data
    // ------------------------------------------------------------------

    static final class Multipart {
        private record Part(String name, String value, String filename, String contentType, byte[] bytes) {}

        private final List<Part> parts = new ArrayList<>();
        final String boundary = "toolnexus-" + UUID.randomUUID().toString().replace("-", "");

        Multipart field(String name, String value) {
            parts.add(new Part(name, value, null, null, null));
            return this;
        }

        Multipart file(String name, Input in) {
            parts.add(new Part(name, null, in.filename(), in.contentType(), in.bytes()));
            return this;
        }

        /** providerOptions: replace the first field of that name, else add it. */
        Multipart set(String name, String value) {
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).name().equals(name) && parts.get(i).filename() == null) {
                    parts.set(i, new Part(name, value, null, null, null));
                    return this;
                }
            }
            return field(name, value);
        }

        Multipart options(Map<String, Object> providerOptions) {
            if (providerOptions != null) {
                for (Map.Entry<String, Object> e : providerOptions.entrySet()) {
                    if (e.getValue() != null) set(e.getKey(), render(e.getValue()));
                }
            }
            return this;
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        byte[] body() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Part p : parts) {
                StringBuilder h = new StringBuilder("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"").append(p.name()).append('"');
                if (p.filename() != null) {
                    h.append("; filename=\"").append(p.filename()).append('"').append("\r\n")
                            .append("Content-Type: ").append(p.contentType());
                }
                h.append("\r\n\r\n");
                out.writeBytes(h.toString().getBytes(StandardCharsets.UTF_8));
                out.writeBytes(p.filename() != null ? p.bytes() : p.value().getBytes(StandardCharsets.UTF_8));
                out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        }
    }

    /** JSON body with providerOptions' top-level keys written last. */
    static byte[] jsonBody(Map<String, Object> body, Map<String, Object> providerOptions) {
        if (providerOptions != null) {
            for (Map.Entry<String, Object> e : providerOptions.entrySet()) body.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return Json.stringify(body).getBytes(StandardCharsets.UTF_8);
    }
}
