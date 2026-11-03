package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * §7G.7 — a {@link MediaClient} over a JSON style (the style engine of ADR 0036). Build one with
 * {@link Media#styleMediaClient(Media.Style, Media.Config)}. Uses {@code url}, {@code loop},
 * {@code gather} (default mode {@code url}) and {@code httpClient} from the config; {@code key},
 * when set, is what every {@code ${…}} in the style's headers expands to.
 */
public class StyleMediaClient implements MediaClient {

    private static final List<String> SEVEN =
            List.of("image", "video", "speech", "music", "transcribe", "cloneVoice", "talk");

    private final Map<String, Object> style;
    private final Media.Config config;
    private final String name;
    /** Built-in style clients (a2e): the key env var, checked before any request. */
    private final String keyEnv;
    private final String defaultGather;
    /** Built-in extras written over the body per verb (a2e: model ⇒ image {@code model_type}). */
    private final Map<String, Map<String, Object>> extraBody;

    StyleMediaClient(Media.Style style, Media.Config config) {
        this(style, config, null, "url", Map.of());
    }

    StyleMediaClient(Media.Style style, Media.Config config, String keyEnv, String defaultGather,
                     Map<String, Map<String, Object>> extraBody) {
        validate(style);
        this.style = style.spec();
        this.config = Media.Config.orEmpty(config);
        Object n = this.style.get("name");
        this.name = n instanceof String s && !s.isEmpty() ? s : "style";
        this.keyEnv = keyEnv;
        this.defaultGather = defaultGather;
        this.extraBody = extraBody == null ? Map.of() : extraBody;
    }

    /** Load-time checks: unknown verbs and arguments mapped to the empty path (§7G.7, §7G.8). */
    static void validate(Media.Style s) {
        if (!(s.spec().get("verbs") instanceof Map<?, ?> vs)) return;
        for (Map.Entry<?, ?> ve : vs.entrySet()) {
            if (!SEVEN.contains(String.valueOf(ve.getKey()))) {
                throw new Media.MediaException("style: unknown verb \"" + ve.getKey() + "\"");
            }
            if (ve.getValue() instanceof Map<?, ?> vm && vm.get("args") instanceof Map<?, ?> am) {
                for (Map.Entry<?, ?> e : am.entrySet()) {
                    if ("".equals(e.getValue())) {
                        throw new Media.MediaException("style: empty path for argument \"" + e.getKey() + "\"");
                    }
                }
            }
        }
    }

    @Override public String name() { return name; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verbSpec(String verb) {
        if (style.get("verbs") instanceof Map<?, ?> vs && vs.get(verb) instanceof Map<?, ?> v) {
            return (Map<String, Object>) v;
        }
        return null;
    }

    @Override public boolean supports(String verb) {
        return verbSpec(verb) != null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapped(Map<String, Object> v) {
        return v.get("args") instanceof Map ? (Map<String, Object>) v.get("args") : Map.of();
    }

    /** Mapped canonical args; required = (canonical required ∩ mapped) ∪ the verb's {@code required}. */
    @Override
    public List<Media.ArgSpec> args(String verb) {
        Map<String, Object> v = verbSpec(verb);
        if (v == null) return List.of();
        Map<String, Object> m = mapped(v);
        List<Object> extra = v.get("required") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        List<Media.ArgSpec> out = new ArrayList<>();
        // §7G.12 D6: only canonical args the style maps; a mapped non-canonical name is not accepted.
        for (Media.ArgSpec a : MediaSupport.canonicalArgs(verb)) {
            if (!m.containsKey(a.name())) continue;
            out.add(new Media.ArgSpec(a.name(), a.required() || extra.contains(a.name())));
        }
        return out;
    }

    @Override
    public Media.MediaResult call(String verb, Map<String, Object> args, Media.Ctx ctx) {
        if (!supports(verb)) return MediaClient.super.call(verb, args, ctx);
        return run(verb, args, ctx);
    }

    @Override public Media.MediaResult image(Map<String, Object> a, Media.Ctx c) { return call("image", a, c); }
    @Override public Media.MediaResult video(Map<String, Object> a, Media.Ctx c) { return call("video", a, c); }
    @Override public Media.MediaResult speech(Map<String, Object> a, Media.Ctx c) { return call("speech", a, c); }
    @Override public Media.MediaResult music(Map<String, Object> a, Media.Ctx c) { return call("music", a, c); }
    @Override public Media.MediaResult transcribe(Map<String, Object> a, Media.Ctx c) { return call("transcribe", a, c); }
    @Override public Media.MediaResult cloneVoice(Map<String, Object> a, Media.Ctx c) { return call("cloneVoice", a, c); }
    @Override public Media.MediaResult talk(Map<String, Object> a, Media.Ctx c) { return call("talk", a, c); }

    private static final Pattern ENV = Pattern.compile("\\$\\{([^}]*)}");

    private static String expand(String s, String key) {
        Matcher m = ENV.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = key != null ? key : Media.env.apply(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Media.MediaResult run(String verb, Map<String, Object> rawArgs, Media.Ctx ctx) {
        if (ctx == null) ctx = new Media.Ctx();
        Map<String, Object> args = MediaSupport.clean(rawArgs);
        Map<String, Object> v = verbSpec(verb);
        Map<String, Object> mapped = mapped(v);

        String key = config.key != null && !config.key.isEmpty() ? config.key : null;
        if (keyEnv != null) {
            key = MediaSupport.keyOf(config, keyEnv);
            if (key == null) throw MediaSupport.noKey(name, keyEnv);
        }

        // body = deep copy of defaults; args at their dotPaths; built-in extras; providerOptions last.
        Map<String, Object> body = v.get("defaults") instanceof Map
                ? MediaSupport.deepCopy((Map<String, Object>) v.get("defaults")) : new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object path = mapped.get(e.getKey());
            if (path == null) continue;
            MediaSupport.writePath(body, String.valueOf(path), MediaSupport.deepCopyValue(e.getValue()));
        }
        Map<String, Object> extra = extraBody.get(verb);
        if (extra != null) {
            for (Map.Entry<String, Object> e : extra.entrySet()) MediaSupport.writePath(body, e.getKey(), e.getValue());
        }

        String base = MediaSupport.trimSlash(config.url != null && !config.url.isEmpty()
                ? config.url : MediaSupport.str(style.get("baseUrl")));
        Map<String, String> headers = new LinkedHashMap<>();
        if (style.get("headers") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) style.get("headers")).entrySet()) {
                headers.put(e.getKey(), expand(String.valueOf(e.getValue()), key));
            }
        }
        Map<String, Object> envelope = style.get("errorEnvelope") instanceof Map
                ? (Map<String, Object>) style.get("errorEnvelope") : null;

        Map<String, Object> start = v.get("start") instanceof Map ? (Map<String, Object>) v.get("start") : Map.of();
        String method = start.get("method") == null ? "POST" : MediaSupport.str(start.get("method"));
        boolean async = "async".equals(MediaSupport.str(v.get("mode")));
        Map<String, Object> poll = v.get("poll") instanceof Map ? (Map<String, Object>) v.get("poll") : Map.of();
        Media.Loop loop = config.loop;
        long timeout = loop != null && loop.timeoutMs != null && loop.timeoutMs > 0
                ? loop.timeoutMs : MediaSupport.positive(poll.get("timeoutMs"), MediaSupport.DEFAULT_TIMEOUT_MS);
        long interval = loop != null && loop.intervalMs != null && loop.intervalMs > 0
                ? loop.intervalMs : MediaSupport.positive(poll.get("intervalMs"), 5000L);

        MediaSupport.Job job = new MediaSupport.Job(name, verb, config, base, headers, ctx, timeout);
        Object resp = request(job, method, base + MediaSupport.str(start.get("path")),
                MediaSupport.jsonBody(body, ctx.providerOptions), envelope);

        String kind = MediaSupport.str(v.get("kind"));
        String status;
        if (!async) {
            status = "completed";
        } else {
            String idPath = MediaSupport.str(v.get("idPath"));
            job.jobId = MediaSupport.jobIdOf(MediaSupport.readPath(resp, idPath));
            if (job.jobId == null) throw job.failed(verb + ": no job id at " + idPath);
            String pMethod = poll.get("method") == null ? "GET" : MediaSupport.str(poll.get("method"));
            String pUrl = base + MediaSupport.str(poll.get("path")).replace("{id}", MediaSupport.pctEncode(job.jobId));
            List<Object> done = poll.get("done") instanceof List ? (List<Object>) poll.get("done") : List.of();
            List<Object> failed = poll.get("failed") instanceof List ? (List<Object>) poll.get("failed") : List.of();
            while (true) {
                job.sleep(interval);
                resp = request(job, pMethod, pUrl, null, envelope);
                String s = MediaSupport.renderStatus(MediaSupport.readPath(resp, MediaSupport.str(poll.get("statusPath"))));
                job.report(s);
                if (matches(done, s)) { status = s; break; }
                if (matches(failed, s)) {
                    Object msg = poll.get("errorPath") == null ? null
                            : MediaSupport.readPath(resp, MediaSupport.str(poll.get("errorPath")));
                    String m = msg == null ? "" : MediaSupport.render(msg);
                    throw job.failed("job " + job.jobId + " " + s + (m.isEmpty() ? "" : ": " + m));
                }
                if (job.expired()) throw job.timeout();
            }
        }

        String resultPath = MediaSupport.str(v.get("resultPath"));
        Object val = MediaSupport.readPath(resp, resultPath);
        if ("text".equals(kind)) {
            if (val instanceof String || val instanceof Number) {
                return new Media.MediaResult(List.of(),
                        val instanceof String ? (String) val : MediaSupport.render(val), job.jobId, status);
            }
            throw job.failed(verb + ": no result at " + resultPath);
        }
        List<String> urls = new ArrayList<>();
        if (val instanceof String s) {
            if (!s.isEmpty()) urls.add(s);
        } else if (val instanceof List<?> l) {
            flatten(l, urls);
        }
        if (urls.isEmpty()) throw job.failed(verb + ": no result at " + resultPath);
        MediaSupport.Gather g = new MediaSupport.Gather(name, verb, kind, config, defaultGather, job);
        List<Media.MediaFile> files = new ArrayList<>();
        for (String u : urls) files.add(g.url(u));
        return new Media.MediaResult(files, null, job.jobId, status);
    }

    private static Object request(MediaSupport.Job job, String method, String url, byte[] body,
                                  Map<String, Object> envelope) {
        MediaSupport.Resp r = job.send(method, url, body, body == null ? null : "application/json", true,
                Long.MAX_VALUE - 1);
        job.checkStatus(r);
        Object parsed = job.json(r);
        if (envelope != null) {
            Object code = MediaSupport.readPath(parsed, MediaSupport.str(envelope.get("codePath")));
            if (code != null) {
                List<?> ok = envelope.get("okValues") instanceof List<?> l ? l : List.of();
                boolean isOk = false;
                for (Object o : ok) if (MediaSupport.jsonEq(o, code)) { isOk = true; break; }
                if (!isOk) {
                    Object msg = envelope.get("messagePath") == null ? null
                            : MediaSupport.readPath(parsed, MediaSupport.str(envelope.get("messagePath")));
                    throw job.failed("API error " + MediaSupport.render(code) + ": " + (msg instanceof String s ? s : ""));
                }
            }
        }
        return parsed;
    }

    private static void flatten(List<?> l, List<String> out) {
        for (Object o : l) {
            if (o instanceof String s) { if (!s.isEmpty()) out.add(s); }
            else if (o instanceof List<?> inner) flatten(inner, out);
        }
    }

    private static boolean matches(List<Object> list, String s) {
        for (Object o : list) if (o != null && MediaSupport.render(o).equals(s)) return true;
        return false;
    }
}
