package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * §7G Media generation (source {@code "media"}): image, video, speech, music, transcription,
 * voice cloning and talking-head video behind one interface with two faces — typed verbs a host
 * calls ({@link #image}, {@link #video}, …, {@link #generate}) and tools an LLM calls
 * ({@link #tools()}).
 *
 * <p>Providers are {@link MediaClient}s: built-in API clients ({@link A2EMediaClient},
 * {@link ElevenLabsMediaClient}, {@link OpenAIMediaClient}, {@link OpenRouterMediaClient},
 * {@link FalMediaClient}), CLI clients ({@link GrokMediaClient}, {@link CodexMediaClient}), style
 * clients ({@link #styleMediaClient}) or your own. {@link #create} routes each call to the first
 * client that supports the verb and falls through <b>only</b> on
 * {@link MediaUnavailableException}.
 *
 * <p>Cancellation follows the port's idiom: interrupt the calling thread, pass a
 * {@link LlmClient.CancelToken}, or set {@link Ctx#cancelled}; the call then throws
 * {@link LlmClient.CancelledException}.
 */
public final class Media {

    /** Env lookup seam (package-private so tests can inject an environment). */
    static Function<String, String> env = System::getenv;

    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    /** One accepted canonical arg of a verb and whether the client requires it (§7G.2). */
    public record ArgSpec(String name, boolean required) {
        public ArgSpec {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * {@code MediaFile = {type, mimeType, url?, path?, data?}} — {@code type} is the §1B part type
     * ({@code image}, {@code audio}, {@code file} for video); which of url / path / data are set is
     * decided by gather (§7G.5).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaFile(String type, String mimeType, String url, String path, String data) {
        /** A URL file. */
        public static MediaFile ofUrl(String type, String mimeType, String url) {
            return new MediaFile(type, mimeType, url, null, null);
        }
    }

    /**
     * {@code MediaUsage = {inputTokens?, outputTokens?, costUsd?, raw?}} (§7G.12 D21): only what the
     * provider reported, never estimated. {@code raw} carries provider fields kept verbatim.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaUsage(Long inputTokens, Long outputTokens, Double costUsd, Map<String, Object> raw) {
        /** {@code null} when every field is absent (no {@code usage} key at all). */
        static MediaUsage ofNullable(Long in, Long out, Double cost, Map<String, Object> raw) {
            if (in == null && out == null && cost == null && (raw == null || raw.isEmpty())) return null;
            return new MediaUsage(in, out, cost, raw == null || raw.isEmpty() ? null : raw);
        }

        /** The JSON shape ({@code tool metadata.usage}). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (inputTokens != null) m.put("inputTokens", inputTokens);
            if (outputTokens != null) m.put("outputTokens", outputTokens);
            if (costUsd != null) m.put("costUsd", costUsd);
            if (raw != null) m.put("raw", raw);
            return m;
        }
    }

    /** {@code MediaResult = {files, text?, jobId?, status, usage?}}. {@code files} is never null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaResult(List<MediaFile> files, String text, String jobId, String status, MediaUsage usage) {
        public MediaResult {
            files = files == null ? List.of() : List.copyOf(files);
            if (status == null) status = "completed";
        }

        public MediaResult(List<MediaFile> files, String text, String jobId, String status) {
            this(files, text, jobId, status, null);
        }
    }

    /** A progress report: {@code {verb, client, jobId?, status, elapsedMs}}. */
    public record Progress(String verb, String client, String jobId, String status, long elapsedMs) {}

    /**
     * The media error: the exact §7G message, the media {@code status}
     * ({@code failed|timeout|cancelled|unavailable}) and the job id when known.
     */
    public static class MediaException extends RuntimeException {
        private final String status;
        private final String jobId;

        public MediaException(String message) {
            this(message, "failed", null);
        }

        public MediaException(String message, String status, String jobId) {
            super(message);
            this.status = status == null ? "failed" : status;
            this.jobId = jobId;
        }

        public String status() { return status; }

        public String jobId() { return jobId; }
    }

    /** Status {@code unavailable} (§7G.2): the call was valid but this client cannot serve it now. */
    public static final class MediaUnavailableException extends MediaException {
        public MediaUnavailableException(String message) {
            this(message, null);
        }

        public MediaUnavailableException(String message, String jobId) {
            super(message, "unavailable", jobId);
        }
    }

    /** Per-call context: cancellation, absolute deadline, progress, provider options. */
    public static final class Ctx {
        public BooleanSupplier cancelled;
        public Instant deadline;
        public Consumer<Progress> progress;
        /** Function face only: top-level keys overwrite the JSON body (or multipart fields) last. */
        public Map<String, Object> providerOptions;

        public Ctx cancelled(BooleanSupplier v) { this.cancelled = v; return this; }
        public Ctx deadline(Instant v) { this.deadline = v; return this; }
        public Ctx progress(Consumer<Progress> v) { this.progress = v; return this; }
        public Ctx providerOptions(Map<String, Object> v) { this.providerOptions = v; return this; }
    }

    /** {@code loop} config: poll interval, job deadline, progress callback (§7G.4). */
    public static final class Loop {
        public Long intervalMs;
        public Long timeoutMs;
        public Consumer<Progress> onProgress;

        public Loop intervalMs(long v) { this.intervalMs = v; return this; }
        public Loop timeoutMs(long v) { this.timeoutMs = v; return this; }
        public Loop onProgress(Consumer<Progress> v) { this.onProgress = v; return this; }
    }

    /** {@code gather} config (§7G.5): {@code mode} url|download|inline, {@code dir}, {@code maxBytes}, {@code keep}. */
    public static final class Gather {
        public String mode;
        public String dir;
        public Long maxBytes;
        public Boolean keep;

        public Gather mode(String v) { this.mode = v; return this; }
        public Gather dir(String v) { this.dir = v; return this; }
        public Gather maxBytes(long v) { this.maxBytes = v; return this; }
        public Gather keep(boolean v) { this.keep = v; return this; }
    }

    /**
     * The one client config shape (§7G.4): {@code model, models, key, url, command, loop, gather,
     * httpClient}. Fields a client does not use are ignored. A key is never logged.
     */
    public static final class Config {
        public String model;
        public Map<String, String> models;
        public String key;
        public String url;
        public List<String> command;
        public Loop loop;
        public Gather gather;
        public HttpClient httpClient;

        public Config model(String v) { this.model = v; return this; }
        public Config models(Map<String, String> v) { this.models = v; return this; }
        public Config key(String v) { this.key = v; return this; }
        public Config url(String v) { this.url = v; return this; }
        /** One executable (never shell-split). */
        public Config command(String v) { this.command = v == null ? null : List.of(v); return this; }
        public Config command(List<String> v) { this.command = v == null ? null : List.copyOf(v); return this; }
        public Config loop(Loop v) { this.loop = v; return this; }
        public Config gather(Gather v) { this.gather = v; return this; }
        public Config httpClient(HttpClient v) { this.httpClient = v; return this; }

        static Config orEmpty(Config c) {
            return c == null ? new Config() : c;
        }
    }

    /** A provider style (§7G.7), held as its parsed JSON object. */
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

    public record MusicInput(String prompt, Integer seconds, Boolean instrumental, String lyrics,
                             Map<String, Object> providerOptions) {
        public MusicInput(String prompt) { this(prompt, null, null, null, null); }
    }

    public record TranscribeInput(String audio, String language, Map<String, Object> providerOptions) {
        public TranscribeInput(String audio) { this(audio, null, null); }
    }

    public record CloneVoiceInput(String name, List<String> samples, Map<String, Object> providerOptions) {
        public CloneVoiceInput(String name, List<String> samples) { this(name, samples, null); }
    }

    public record TalkInput(String image, String audio, String text, String voice,
                            Map<String, Object> providerOptions) {
        public TalkInput(String image, String audio) { this(image, audio, null, null, null); }
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private final List<MediaClient> clients;

    private Media(List<MediaClient> clients) {
        if (clients == null || clients.isEmpty()) throw new MediaException("media: no clients");
        for (MediaClient c : clients) Objects.requireNonNull(c, "media client");
        this.clients = List.copyOf(clients);
    }

    /** {@code createMedia(client | [client])} (§7G.3). An empty list fails: {@code media: no clients}. */
    public static Media create(MediaClient... clients) {
        return new Media(clients == null ? List.of() : List.of(clients));
    }

    public static Media create(List<MediaClient> clients) {
        return new Media(clients == null ? List.of() : clients);
    }

    /** Alias of {@link #create(MediaClient...)} matching the spec's spelling. */
    public static Media createMedia(MediaClient... clients) {
        return create(clients);
    }

    public static Media createMedia(List<MediaClient> clients) {
        return create(clients);
    }

    /** Read one style file (JSON). Unknown verbs and empty arg paths fail here. */
    public static Style loadMediaStyle(String path) {
        Style s;
        try {
            s = Style.parse(Files.readString(Path.of(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MediaException("media: cannot read style " + path + ": " + e.getMessage());
        }
        StyleMediaClient.validate(s);
        return s;
    }

    /** Alias of {@link #loadMediaStyle}. */
    public static Style loadStyle(String path) {
        return loadMediaStyle(path);
    }

    /** A {@link MediaClient} over a parsed style (§7G.7), named {@code style.name ?? "style"}. */
    public static StyleMediaClient styleMediaClient(Style style) {
        return new StyleMediaClient(style, null);
    }

    public static StyleMediaClient styleMediaClient(Style style, Config config) {
        return new StyleMediaClient(style, config);
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

    public MediaResult music(MusicInput in) { return music(in, null); }
    public MediaResult music(MusicInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "prompt", in.prompt()); put(a, "seconds", in.seconds());
        put(a, "instrumental", in.instrumental()); put(a, "lyrics", in.lyrics());
        return generate("music", a, in.providerOptions(), cancel);
    }

    public MediaResult transcribe(TranscribeInput in) { return transcribe(in, null); }
    public MediaResult transcribe(TranscribeInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "audio", in.audio()); put(a, "language", in.language());
        return generate("transcribe", a, in.providerOptions(), cancel);
    }

    public MediaResult cloneVoice(CloneVoiceInput in) { return cloneVoice(in, null); }
    public MediaResult cloneVoice(CloneVoiceInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "name", in.name()); put(a, "samples", in.samples() == null ? null : new ArrayList<>(in.samples()));
        return generate("cloneVoice", a, in.providerOptions(), cancel);
    }

    public MediaResult talk(TalkInput in) { return talk(in, null); }
    public MediaResult talk(TalkInput in, LlmClient.CancelToken cancel) {
        Map<String, Object> a = new LinkedHashMap<>();
        put(a, "image", in.image()); put(a, "audio", in.audio());
        put(a, "text", in.text()); put(a, "voice", in.voice());
        return generate("talk", a, in.providerOptions(), cancel);
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    /** Whether any client supports {@code verb}. */
    public boolean supports(String verb) {
        for (MediaClient c : clients) if (c.supports(verb)) return true;
        return false;
    }

    /** Generic entry: run {@code verb} with canonical {@code args}. */
    public MediaResult generate(String verb, Map<String, Object> args) {
        return generate(verb, args, (Ctx) null);
    }

    /**
     * Generic entry with {@code providerOptions} and an optional cancel token. Throws
     * {@link MediaException} with the exact §7G message, or {@link LlmClient.CancelledException}.
     */
    public MediaResult generate(String verb, Map<String, Object> args, Map<String, Object> providerOptions,
                                LlmClient.CancelToken cancel) {
        Ctx ctx = new Ctx().providerOptions(providerOptions);
        if (cancel != null) ctx.cancelled(cancel::isCancelled);
        if (cancel != null) cancel.register(Thread.currentThread());
        try {
            return generate(verb, args, ctx);
        } finally {
            if (cancel != null) {
                cancel.unregister();
                if (cancel.isCancelled()) Thread.interrupted(); // clear our own interrupt
            }
        }
    }

    /** Generic entry with a full {@link Ctx}. */
    public MediaResult generate(String verb, Map<String, Object> args, Ctx ctx) {
        return route(verb, args, ctx == null ? new Ctx() : ctx).result;
    }

    // ------------------------------------------------------------------
    // Routing (§7G.3)
    // ------------------------------------------------------------------

    private record Served(MediaResult result, String client) {}

    private static List<ArgSpec> specsOf(MediaClient c, String verb) {
        List<ArgSpec> s = c.args(verb);
        return s == null ? MediaSupport.canonicalArgs(verb) : s;
    }

    private static Set<String> accepted(List<ArgSpec> specs) {
        Set<String> out = new HashSet<>();
        for (ArgSpec a : specs) out.add(a.name());
        return out;
    }

    /** The first required arg (canonical order) absent from {@code args}, or null. */
    private static String missingRequired(String verb, List<ArgSpec> specs, Map<String, Object> args) {
        Set<String> required = new HashSet<>();
        for (ArgSpec a : specs) if (a.required()) required.add(a.name());
        for (ArgSpec a : MediaSupport.canonicalArgs(verb)) {
            if (required.contains(a.name()) && !args.containsKey(a.name())) return a.name();
        }
        return null;
    }

    private Served route(String verb, Map<String, Object> rawArgs, Ctx ctx) {
        List<MediaClient> candidates = new ArrayList<>();
        if (MediaSupport.isVerb(verb)) for (MediaClient c : clients) if (c.supports(verb)) candidates.add(c);
        if (candidates.isEmpty()) throw new MediaException("media: no client supports \"" + verb + "\"");
        Map<String, Object> args = MediaSupport.clean(rawArgs);

        // §7G.3 step 3 (D7/D19): a non-canonical key fails at once, before any client is tried.
        Set<String> canonical = accepted(MediaSupport.canonicalArgs(verb));
        List<String> keys = new ArrayList<>(args.keySet());
        keys.sort(null);
        for (String k : keys) {
            if (!canonical.contains(k)) throw new MediaException(verb + ": argument \"" + k + "\" not supported");
        }

        // §7G.3 step 4 (D19): every candidate, the first included, is skipped when it does not
        // accept an arg or lacks a required one; a skip counts exactly like unavailable.
        List<String> reasons = new ArrayList<>();
        for (MediaClient c : candidates) {
            List<ArgSpec> cSpecs = specsOf(c, verb);
            Set<String> acc = accepted(cSpecs);
            String rejected = null;
            for (String k : keys) if (!acc.contains(k)) { rejected = k; break; }
            if (rejected != null) {
                reasons.add(c.name() + ": argument \"" + rejected + "\" not supported");
                continue;
            }
            String missing = missingRequired(verb, cSpecs, args);
            if (missing != null) {
                reasons.add(c.name() + ": missing required argument \"" + missing + "\"");
                continue;
            }
            try {
                return new Served(c.call(verb, new LinkedHashMap<>(args), ctx), c.name());
            } catch (MediaUnavailableException e) {
                reasons.add(e.getMessage());
            }
        }
        throw new MediaUnavailableException("media: no client available for \"" + verb + "\": "
                + String.join("; ", reasons));
    }

    // ------------------------------------------------------------------
    // Tool face (§7G.6)
    // ------------------------------------------------------------------

    /** One {@link Tool} (source {@code "media"}) per verb any client supports, in canonical order. */
    @SuppressWarnings("unchecked")
    public List<Tool> tools() {
        List<Tool> out = new ArrayList<>();
        for (Map<String, Object> v : MediaSupport.VERBS) {
            String verb = (String) v.get("verb");
            MediaClient first = null;
            for (MediaClient c : clients) if (c.supports(verb)) { first = c; break; }
            if (first == null) continue;
            List<ArgSpec> specs = specsOf(first, verb);
            Set<String> ok = accepted(specs);
            Set<String> req = new HashSet<>();
            for (ArgSpec a : specs) if (a.required()) req.add(a.name());
            Map<String, Object> props = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>();
            for (Map<String, Object> a : (List<Map<String, Object>>) v.get("args")) {
                String name = (String) a.get("name");
                if (!ok.contains(name)) continue;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", a.get("type"));
                if ("array".equals(a.get("type"))) p.put("items", new LinkedHashMap<>(Map.of("type", a.get("items"))));
                p.put("description", a.get("description"));
                props.put(name, p);
                if (req.contains(name)) required.add(name);
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
        /** Each call is an independent job: always concurrency-safe (§7G.6, §8 Tool scheduling). */
        @Override public boolean concurrencySafe(Map<String, Object> args) { return true; }

        @Override
        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            Served served;
            List<ContentPart> parts = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            try {
                Ctx c = new Ctx();
                if (ctx != null) c.cancelled(ctx::isCancelled);
                served = media.route(verb, args == null ? Map.of() : args, c);
                if (!"text".equals(MediaSupport.kindOf(verb))) {
                    for (MediaFile f : served.result.files()) {
                        lines.add(line(f));
                        parts.add(part(f));
                        paths.add(f.path()); // §7G.12 D20: RunFile.path, never in the part
                    }
                }
            } catch (RuntimeException e) {
                return ToolResult.error(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            }
            MediaResult res = served.result;
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", "media");
            meta.put("verb", verb);
            meta.put("client", served.client);
            if (res.jobId() != null) meta.put("jobId", res.jobId());
            meta.put("status", res.status());
            if (res.usage() != null) meta.put("usage", res.usage().toMap()); // §7G.12 D21
            if ("text".equals(MediaSupport.kindOf(verb))) {
                return new ToolResult(res.text() == null ? "" : res.text(), false, meta, null);
            }
            String kind = res.files().isEmpty() ? MediaSupport.kindOf(verb) : res.files().get(0).type();
            String word = switch (kind) {
                case "file", "video" -> "video";
                case "audio" -> "audio";
                default -> "image";
            };
            return new ToolResult(word + " ready: " + String.join("\n", lines), false, meta, parts, paths);
        }

        private static String line(MediaFile f) {
            if (f.path() != null) return f.path();
            if (f.url() != null) return f.url();
            int n = f.data() == null ? 0 : Base64.getDecoder().decode(f.data()).length;
            return "inline " + f.mimeType() + " " + n + " bytes";
        }

        /** A part never holds a path (§1B): data, an http(s) url, or the local file read now. */
        private ContentPart part(MediaFile f) {
            if (f.data() != null) return new ContentPart(f.type(), null, f.mimeType(), f.data(), null, null);
            if (f.url() != null && MediaSupport.isHttp(f.url())) {
                return new ContentPart(f.type(), null, f.mimeType(), null, f.url(), null);
            }
            String p = f.path() != null ? f.path()
                    : f.url() != null && f.url().startsWith("file://") ? f.url().substring(7) : f.url();
            try {
                byte[] b = Files.readAllBytes(Path.of(p));
                return new ContentPart(f.type(), null, f.mimeType(), Base64.getEncoder().encodeToString(b), null, null);
            } catch (IOException | RuntimeException e) {
                throw new MediaException(verb + ": cannot read \"" + p + "\": " + e.getMessage());
            }
        }
    }
}
