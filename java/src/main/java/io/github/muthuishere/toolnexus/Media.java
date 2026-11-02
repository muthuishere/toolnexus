package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * §7G Media generation (source {@code "media"}): image, video, speech, transcription, voice
 * cloning and talking-head generation behind one engine with two faces — typed verbs a host
 * calls ({@link #image}, {@link #video}, …, {@link #generate}) and tools an LLM calls
 * ({@link #tools()}). Providers are {@link Style styles}: JSON data, never code.
 *
 * <p>Cancellation follows the port's idiom: interrupt the calling thread, or pass a
 * {@link LlmClient.CancelToken}; the engine then throws {@link LlmClient.CancelledException}.
 */
public final class Media {

    // ------------------------------------------------------------------
    // The canonical verb table — conformance/media/verbs.json, verbatim.
    // ------------------------------------------------------------------
    private static final String VERBS_JSON = """
            {
              "verbs": [
                { "verb": "image", "tool": "generate_image", "description": "Generate an image from a text prompt.",
                  "args": [
                    { "name": "prompt", "type": "string", "description": "What to draw.", "required": true },
                    { "name": "width", "type": "integer", "description": "Width in pixels." },
                    { "name": "height", "type": "integer", "description": "Height in pixels." },
                    { "name": "aspectRatio", "type": "string", "description": "Aspect ratio, e.g. 16:9." },
                    { "name": "seed", "type": "integer", "description": "Seed for reproducible output." } ] },
                { "verb": "video", "tool": "generate_video", "description": "Generate a short video from a prompt, optionally starting from an image URL. Can take minutes.",
                  "args": [
                    { "name": "prompt", "type": "string", "description": "What should happen in the video.", "required": true },
                    { "name": "image", "type": "string", "description": "URL of the starting image." },
                    { "name": "seconds", "type": "integer", "description": "Video length in seconds." },
                    { "name": "aspectRatio", "type": "string", "description": "Aspect ratio, e.g. 9:16." } ] },
                { "verb": "speech", "tool": "generate_speech", "description": "Speak text aloud and return the audio.",
                  "args": [
                    { "name": "text", "type": "string", "description": "The text to speak.", "required": true },
                    { "name": "voice", "type": "string", "description": "Voice or speaker id." } ] },
                { "verb": "transcribe", "tool": "transcribe_audio", "description": "Transcribe speech in an audio URL to text.",
                  "args": [
                    { "name": "audio", "type": "string", "description": "URL of the audio.", "required": true } ] },
                { "verb": "cloneVoice", "tool": "clone_voice", "description": "Create a cloned voice from audio sample URLs and return its voice id.",
                  "args": [
                    { "name": "name", "type": "string", "description": "Name for the new voice.", "required": true },
                    { "name": "samples", "type": "array", "items": "string", "description": "URLs of audio samples of the voice.", "required": true } ] },
                { "verb": "talking", "tool": "talking_video", "description": "Make the person in an image speak the given audio (lip-synced video).",
                  "args": [
                    { "name": "image", "type": "string", "description": "URL of the face image.", "required": true },
                    { "name": "audio", "type": "string", "description": "URL of the speech audio.", "required": true } ] }
              ]
            }
            """;

    @SuppressWarnings("unchecked")
    private static final List<Map<String, Object>> VERBS =
            (List<Map<String, Object>>) Json.toMap(VERBS_JSON).get("verbs");

    private static final HttpClient DEFAULT_HTTP = HttpClient.newHttpClient();

    /** Env lookup seam (package-private so tests can inject an environment). */
    static Function<String, String> env = System::getenv;

    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    /** A provider style (§7G "Style (JSON)"), held as its parsed JSON object. */
    public record Style(Map<String, Object> spec) {
        public Style {
            Objects.requireNonNull(spec, "style");
        }

        /** Parse a style from JSON text. */
        public static Style parse(String json) {
            return new Style(Json.toMap(json));
        }

        /** A style from an already-parsed JSON object. */
        public static Style of(Map<String, Object> spec) {
            return new Style(spec);
        }
    }

    /** {@code File = {type, mimeType, url}}. */
    public record File(String type, String mimeType, String url) {}

    /** {@code MediaResult = {files, text?, jobId?, status}}. {@code files} is never null. */
    public record MediaResult(List<File> files, String text, String jobId, String status) {
        public MediaResult {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** Progress event passed to {@link Options#onProgress} after every poll. */
    public record Progress(String verb, String jobId, String status) {}

    /** Every engine error (the message is the exact §7G error string). */
    public static final class MediaException extends RuntimeException {
        public MediaException(String message) { super(message); }
    }

    /** Media options: progress callback and the HTTP-client seam (same as the LLM client's). */
    public static final class Options {
        public Consumer<Progress> onProgress;
        public HttpClient httpClient;

        public Options onProgress(Consumer<Progress> v) { this.onProgress = v; return this; }
        public Options httpClient(HttpClient v) { this.httpClient = v; return this; }
    }

    // Typed inputs (function face). Null fields are absent; providerOptions may be null.
    public record ImageInput(String prompt, Integer width, Integer height, String aspectRatio, Integer seed,
                             Map<String, Object> providerOptions) {
        public ImageInput(String prompt) { this(prompt, null, null, null, null, null); }
    }

    public record VideoInput(String prompt, String image, Integer seconds, String aspectRatio,
                             Map<String, Object> providerOptions) {
        public VideoInput(String prompt) { this(prompt, null, null, null, null); }
    }

    public record SpeechInput(String text, String voice, Map<String, Object> providerOptions) {
        public SpeechInput(String text) { this(text, null, null); }
    }

    public record TranscribeInput(String audio, Map<String, Object> providerOptions) {
        public TranscribeInput(String audio) { this(audio, null); }
    }

    public record CloneVoiceInput(String name, List<String> samples, Map<String, Object> providerOptions) {
        public CloneVoiceInput(String name, List<String> samples) { this(name, samples, null); }
    }

    public record TalkingInput(String image, String audio, Map<String, Object> providerOptions) {
        public TalkingInput(String image, String audio) { this(image, audio, null); }
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private final List<Style> styles;
    private final Options opts;

    private Media(List<Style> styles, Options opts) {
        this.styles = List.copyOf(styles);
        this.opts = opts == null ? new Options() : opts;
    }

    /** Read one style file (JSON). */
    public static Style loadStyle(String path) {
        try {
            return Style.parse(Files.readString(Path.of(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MediaException("media: cannot read style " + path + ": " + e.getMessage());
        }
    }

    public static Media create(Style... styles) {
        return new Media(List.of(styles), null);
    }

    public static Media create(Style style, Options opts) {
        return new Media(List.of(style), opts);
    }

    /** A verb resolves to the FIRST style in the list that declares it. */
    public static Media create(List<Style> styles, Options opts) {
        return new Media(styles, opts);
    }

    // ------------------------------------------------------------------
    // Function face
    // ------------------------------------------------------------------

    public MediaResult image(ImageInput in) { return image(in, null); }
    public MediaResult image(ImageInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "prompt", in.prompt()); put(a, "width", in.width()); put(a, "height", in.height());
        put(a, "aspectRatio", in.aspectRatio()); put(a, "seed", in.seed());
        return generate("image", a, in.providerOptions(), cancel);
    }

    public MediaResult video(VideoInput in) { return video(in, null); }
    public MediaResult video(VideoInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "prompt", in.prompt()); put(a, "image", in.image()); put(a, "seconds", in.seconds());
        put(a, "aspectRatio", in.aspectRatio());
        return generate("video", a, in.providerOptions(), cancel);
    }

    public MediaResult speech(SpeechInput in) { return speech(in, null); }
    public MediaResult speech(SpeechInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "text", in.text()); put(a, "voice", in.voice());
        return generate("speech", a, in.providerOptions(), cancel);
    }

    public MediaResult transcribe(TranscribeInput in) { return transcribe(in, null); }
    public MediaResult transcribe(TranscribeInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "audio", in.audio());
        return generate("transcribe", a, in.providerOptions(), cancel);
    }

    public MediaResult cloneVoice(CloneVoiceInput in) { return cloneVoice(in, null); }
    public MediaResult cloneVoice(CloneVoiceInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "name", in.name()); put(a, "samples", in.samples() == null ? null : new ArrayList<>(in.samples()));
        return generate("cloneVoice", a, in.providerOptions(), cancel);
    }

    public MediaResult talking(TalkingInput in) { return talking(in, null); }
    public MediaResult talking(TalkingInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "image", in.image()); put(a, "audio", in.audio());
        return generate("talking", a, in.providerOptions(), cancel);
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    /** Generic entry: run {@code verb} with normalised {@code args}. */
    public MediaResult generate(String verb, Map<String, Object> args) {
        return generate(verb, args, null, null);
    }

    /**
     * Generic entry with {@code providerOptions} (top-level keys overwrite the body last) and an
     * optional cancel token. Throws {@link MediaException} with the exact §7G message, or
     * {@link LlmClient.CancelledException} on cancellation (token or thread interrupt).
     */
    public MediaResult generate(String verb, Map<String, Object> args, Map<String, Object> providerOptions,
                                LlmClient.CancelToken cancel) {
        if (cancel != null) cancel.register(Thread.currentThread());
        try {
            return run(verb, args == null ? Map.of() : args, providerOptions, cancel);
        } finally {
            if (cancel != null) {
                cancel.unregister();
                if (cancel.isCancelled()) Thread.interrupted(); // clear our own interrupt
            }
        }
    }

    // ------------------------------------------------------------------
    // Tool face
    // ------------------------------------------------------------------

    /** One {@link Tool} (source {@code "media"}) per supported verb, in canonical order. */
    @SuppressWarnings("unchecked")
    public List<Tool> tools() {
        List<Tool> out = new ArrayList<>();
        for (Map<String, Object> v : VERBS) {
            String verb = (String) v.get("verb");
            Resolved r = resolve(verb);
            if (r == null) continue;
            Map<String, Object> mapped = r.verb.get("args") instanceof Map
                    ? (Map<String, Object>) r.verb.get("args") : Map.of();
            Map<String, Object> props = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>();
            for (Map<String, Object> a : (List<Map<String, Object>>) v.get("args")) {
                String name = (String) a.get("name");
                if (!mapped.containsKey(name)) continue;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", a.get("type"));
                if ("array".equals(a.get("type"))) p.put("items", new LinkedHashMap<>(Map.of("type", a.get("items"))));
                p.put("description", a.get("description"));
                props.put(name, p);
                if (Boolean.TRUE.equals(a.get("required"))) required.add(name);
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", required);
            out.add(new MediaTool(this, verb, (String) v.get("tool"), (String) v.get("description"), schema));
        }
        return out;
    }

    private static final class MediaTool implements Tool {
        private final Media media;
        private final String verb, name, description;
        private final Map<String, Object> schema;

        MediaTool(Media media, String verb, String name, String description, Map<String, Object> schema) {
            this.media = media; this.verb = verb; this.name = name; this.description = description; this.schema = schema;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public Map<String, Object> inputSchema() { return schema; }
        @Override public String source() { return "media"; }

        @Override
        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            MediaResult res;
            try {
                res = media.generate(verb, args == null ? Map.of() : args, null, null);
            } catch (RuntimeException e) {
                return ToolResult.error(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", "media");
            meta.put("verb", verb);
            if (res.jobId() != null) meta.put("jobId", res.jobId());
            meta.put("status", res.status());
            String kind = media.kindOf(verb);
            if ("text".equals(kind)) {
                return new ToolResult(res.text(), false, meta, null);
            }
            String word = "video".equals(kind) ? "video" : "audio".equals(kind) ? "audio" : "image";
            List<String> urls = new ArrayList<>();
            List<ContentPart> parts = new ArrayList<>();
            for (File f : res.files()) {
                urls.add(f.url());
                parts.add(new ContentPart(f.type(), null, f.mimeType(), null, f.url(), null));
            }
            return new ToolResult(word + " ready: " + String.join("\n", urls), false, meta, parts);
        }
    }

    // ------------------------------------------------------------------
    // Engine
    // ------------------------------------------------------------------

    private record Resolved(Map<String, Object> style, Map<String, Object> verb) {}

    @SuppressWarnings("unchecked")
    private Resolved resolve(String verb) {
        for (Style s : styles) {
            Object vs = s.spec().get("verbs");
            if (vs instanceof Map && ((Map<String, Object>) vs).get(verb) instanceof Map) {
                return new Resolved(s.spec(), (Map<String, Object>) ((Map<String, Object>) vs).get(verb));
            }
        }
        return null;
    }

    private String kindOf(String verb) {
        Resolved r = resolve(verb);
        return r == null ? null : str(r.verb.get("kind"));
    }

    @SuppressWarnings("unchecked")
    private MediaResult run(String verb, Map<String, Object> args, Map<String, Object> providerOptions,
                            LlmClient.CancelToken cancel) {
        Resolved r = resolve(verb);
        if (r == null) throw new MediaException("media: no style supports \"" + verb + "\"");
        Map<String, Object> v = r.verb;
        Map<String, Object> mapped = v.get("args") instanceof Map ? (Map<String, Object>) v.get("args") : Map.of();
        // null ⇒ absent; several unsupported ⇒ name the first in code-point order.
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Object> e : args.entrySet()) if (e.getValue() != null) keys.add(e.getKey());
        keys.sort(null);
        for (String k : keys) {
            if (!mapped.containsKey(k)) {
                throw new MediaException(verb + ": argument \"" + k + "\" not supported by this style");
            }
        }
        // body = deep copy of defaults; args at their dotPaths; providerOptions last.
        Map<String, Object> body = v.get("defaults") instanceof Map
                ? deepCopy((Map<String, Object>) v.get("defaults")) : new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (e.getValue() == null) continue;
            writePath(body, str(mapped.get(e.getKey())), deepCopyValue(e.getValue()));
        }
        if (providerOptions != null) {
            for (Map.Entry<String, Object> e : providerOptions.entrySet()) body.put(e.getKey(), deepCopyValue(e.getValue()));
        }

        String base = str(r.style.get("baseUrl"));
        if (base == null) base = "";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        Map<String, String> headers = new LinkedHashMap<>();
        if (r.style.get("headers") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) r.style.get("headers")).entrySet()) {
                headers.put(e.getKey(), expandEnv(String.valueOf(e.getValue())));
            }
        }
        Map<String, Object> envelope = r.style.get("errorEnvelope") instanceof Map
                ? (Map<String, Object>) r.style.get("errorEnvelope") : null;

        Map<String, Object> start = v.get("start") instanceof Map ? (Map<String, Object>) v.get("start") : Map.of();
        String method = start.get("method") == null ? "POST" : str(start.get("method"));
        Object resp = request(verb, method, base + str(start.get("path")), Json.stringify(body), headers, envelope, cancel);

        String kind = str(v.get("kind"));
        String status;
        String jobId = null;
        if (!"async".equals(str(v.get("mode")))) {
            status = "completed";
        } else {
            String idPath = str(v.get("idPath"));
            Object id = readPath(resp, idPath);
            if (id instanceof String sid) jobId = sid;
            else if (id instanceof Number n && isIntegral(n)) jobId = render(n);
            if (jobId == null) throw new MediaException(verb + ": no job id at " + idPath);
            Map<String, Object> poll = v.get("poll") instanceof Map ? (Map<String, Object>) v.get("poll") : Map.of();
            long interval = poll.get("intervalMs") instanceof Number n ? n.longValue() : 5000L;
            long timeout = poll.get("timeoutMs") instanceof Number n ? n.longValue() : 600000L;
            String pMethod = poll.get("method") == null ? "GET" : str(poll.get("method"));
            String pUrl = base + str(poll.get("path")).replace("{id}", jobId);
            List<Object> done = poll.get("done") instanceof List ? (List<Object>) poll.get("done") : List.of();
            List<Object> failed = poll.get("failed") instanceof List ? (List<Object>) poll.get("failed") : List.of();
            long began = System.nanoTime();
            while (true) {
                sleep(interval, cancel);
                resp = request(verb, pMethod, pUrl, null, headers, envelope, cancel);
                Object st = readPath(resp, str(poll.get("statusPath")));
                String s = st instanceof String ? (String) st : st == null ? "" : render(st);
                if (opts.onProgress != null) opts.onProgress.accept(new Progress(verb, jobId, s));
                if (done.contains(s)) { status = s; break; }
                if (failed.contains(s)) {
                    Object msg = poll.get("errorPath") == null ? null : readPath(resp, str(poll.get("errorPath")));
                    String m = msg == null ? "" : (msg instanceof String ? (String) msg : render(msg));
                    throw new MediaException("job " + jobId + " " + s + (m.isEmpty() ? "" : ": " + m));
                }
                if ((System.nanoTime() - began) / 1_000_000L >= timeout) {
                    throw new MediaException("job " + jobId + " timed out after " + timeout + "ms");
                }
            }
        }

        String resultPath = str(v.get("resultPath"));
        Object val = readPath(resp, resultPath);
        if ("text".equals(kind)) {
            if (val instanceof String || val instanceof Number) {
                return new MediaResult(List.of(), val instanceof String ? (String) val : render(val), jobId, status);
            }
            throw new MediaException(verb + ": no result at " + resultPath);
        }
        List<String> urls = new ArrayList<>();
        if (val instanceof String s) {
            if (!s.isEmpty()) urls.add(s);
        } else if (val instanceof List<?> l) {
            flatten(l, urls);
        }
        if (urls.isEmpty()) throw new MediaException(verb + ": no result at " + resultPath);
        List<File> files = new ArrayList<>();
        for (String u : urls) files.add(new File(fileType(kind), mimeOf(u, kind), u));
        return new MediaResult(files, null, jobId, status);
    }

    private static void flatten(List<?> l, List<String> out) {
        for (Object o : l) {
            if (o instanceof String s) { if (!s.isEmpty()) out.add(s); }
            else if (o instanceof List<?> inner) flatten(inner, out);
        }
    }

    private Object request(String verb, String method, String url, String body, Map<String, String> headers,
                           Map<String, Object> envelope, LlmClient.CancelToken cancel) {
        checkCancel(cancel);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        for (Map.Entry<String, String> h : headers.entrySet()) b.header(h.getKey(), h.getValue());
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpClient http = opts.httpClient != null ? opts.httpClient : DEFAULT_HTTP;
        HttpResponse<String> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClient.CancelledException("media cancelled");
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted() || (cancel != null && cancel.isCancelled())) {
                throw new LlmClient.CancelledException("media cancelled");
            }
            throw new MediaException(verb + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        String text = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new MediaException("HTTP " + resp.statusCode() + ": " + text);
        }
        Object parsed;
        try {
            parsed = Json.MAPPER.readValue(text, new TypeReference<Object>() {});
        } catch (Exception e) {
            throw new MediaException(verb + ": response is not JSON");
        }
        if (envelope != null) {
            Object code = readPath(parsed, str(envelope.get("codePath")));
            if (code != null) {
                List<?> ok = envelope.get("okValues") instanceof List<?> l ? l : List.of();
                boolean isOk = false;
                for (Object o : ok) if (jsonEq(o, code)) { isOk = true; break; }
                if (!isOk) {
                    Object msg = envelope.get("messagePath") == null ? null : readPath(parsed, str(envelope.get("messagePath")));
                    throw new MediaException("API error " + render(code) + ": " + (msg instanceof String s ? s : ""));
                }
            }
        }
        return parsed;
    }

    private static void sleep(long ms, LlmClient.CancelToken cancel) {
        checkCancel(cancel);
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClient.CancelledException("media cancelled");
        }
    }

    private static void checkCancel(LlmClient.CancelToken cancel) {
        if ((cancel != null && cancel.isCancelled()) || Thread.currentThread().isInterrupted()) {
            throw new LlmClient.CancelledException("media cancelled");
        }
    }

    // ------------------------------------------------------------------
    // Helpers: dotPath, env, mime, JSON
    // ------------------------------------------------------------------

    private static final Pattern ENV = Pattern.compile("\\$\\{([^}]*)}");

    static String expandEnv(String s) {
        Matcher m = ENV.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = env.apply(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isIndex(String seg) {
        return !seg.isEmpty() && seg.chars().allMatch(Character::isDigit);
    }

    static Object readPath(Object root, String path) {
        if (path == null) return null;
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
                if (!(existing instanceof Map) && !(existing instanceof List)) { m.put(seg, next); existing = next; }
                cur = existing;
            } else if (cur instanceof List) {
                List<Object> l = (List<Object>) cur;
                int idx = Integer.parseInt(seg);
                while (l.size() <= idx) l.add(null);
                if (last) { l.set(idx, value); return; }
                Object existing = l.get(idx);
                if (!(existing instanceof Map) && !(existing instanceof List)) { l.set(idx, next); existing = next; }
                cur = existing;
            } else {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v) {
        if (v instanceof Map) return deepCopy((Map<String, Object>) v);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(deepCopyValue(o));
            return out;
        }
        return v;
    }

    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), deepCopyValue(e.getValue()));
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** String as-is; an integral number as integer text; anything else its JSON text. */
    private static String render(Object o) {
        if (o instanceof String s) return s;
        if (o instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString());
            if (d.signum() == 0 || d.stripTrailingZeros().scale() <= 0) return d.toBigInteger().toString();
            return n.toString();
        }
        return Json.stringify(o);
    }

    private static boolean isIntegral(Number n) {
        try {
            BigDecimal d = new BigDecimal(n.toString());
            return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean jsonEq(Object a, Object b) {
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

    private static String fileType(String kind) {
        return switch (kind == null ? "" : kind) {
            case "audio" -> "audio";
            case "video" -> "file";
            default -> "image";
        };
    }

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"), Map.entry("gif", "image/gif"), Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"), Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"), Map.entry("ogg", "audio/ogg"), Map.entry("m4a", "audio/mp4"),
            Map.entry("flac", "audio/flac"));

    static String mimeOf(String url, String kind) {
        String u = url;
        int q = u.indexOf('#'); if (q >= 0) u = u.substring(0, q);
        q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int scheme = u.indexOf("://");
        String path = u;
        if (scheme >= 0) {
            int slash = u.indexOf('/', scheme + 3);
            path = slash >= 0 ? u.substring(slash) : "";
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        if (dot >= 0) {
            String m = MIME.get(last.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return switch (kind == null ? "" : kind) {
            case "video" -> "video/mp4";
            case "audio" -> "audio/mpeg";
            default -> "image/png";
        };
    }
}
