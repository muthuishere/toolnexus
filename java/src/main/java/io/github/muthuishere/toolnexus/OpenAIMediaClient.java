package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §7G.9 — OpenAI ({@code openai}; {@code url} points it at any OpenAI-compatible server): image
 * (live-verified), video, speech, transcribe (implemented per the provider's docs, unverified).
 * Key {@code OPENAI_API_KEY} ({@code Authorization: Bearer}), default url
 * {@code https://api.openai.com/v1}, primary model (image) {@code gpt-image-1-mini}, poll interval
 * 5000 ms.
 */
public final class OpenAIMediaClient extends ApiMediaClient {

    private static final Map<String, List<Media.ArgSpec>> ARGS = Map.of(
            "image", List.of(req("prompt"), opt("width"), opt("height")),
            "video", List.of(req("prompt"), opt("seconds")),
            "speech", List.of(req("text"), opt("voice")),
            "transcribe", List.of(req("audio"), opt("language")));

    public OpenAIMediaClient() {
        this(null);
    }

    public OpenAIMediaClient(Media.Config config) {
        super("openai", config, "OPENAI_API_KEY", "https://api.openai.com/v1", 5000L);
    }

    @Override Map<String, String> authHeaders(String key) {
        return Map.of("Authorization", "Bearer " + key);
    }

    @Override Map<String, List<Media.ArgSpec>> argTable() {
        return ARGS;
    }

    @Override
    public Media.MediaResult image(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("image", ctx);
        String model = MediaSupport.modelFor(config, "image", "image", "gpt-image-1-mini");
        Integer w = MediaSupport.intArg(args, "width");
        Integer h = MediaSupport.intArg(args, "height");
        if (w == null) w = h;
        if (h == null) h = w;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", args.get("prompt"));
        body.put("n", 1);
        body.put("size", w == null ? "1024x1024" : w + "x" + h);
        if (model.startsWith("gpt-image")) body.put("quality", "low");
        Object res = job.json(job.api("POST", url() + "/images/generations",
                MediaSupport.jsonBody(body, options(ctx)), "application/json"));
        String mime = switch (String.valueOf(MediaSupport.readPath(res, "output_format"))) {
            case "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
        MediaSupport.Gather g = gather("image", job);
        List<Media.MediaFile> files = new ArrayList<>();
        if (MediaSupport.readPath(res, "data") instanceof List<?> data) {
            for (Object o : data) {
                if (!(o instanceof Map<?, ?> e)) continue;
                if (e.get("b64_json") instanceof String b && !b.isEmpty()) files.add(g.base64(b, mime));
                else if (e.get("url") instanceof String u && !u.isEmpty()) files.add(g.url(u));
            }
        }
        if (files.isEmpty()) throw job.failed("image: no result at data");
        // §7G.12 D21: usage.input_tokens / output_tokens as reported; OpenAI reports no cost.
        Media.MediaUsage usage = Media.MediaUsage.ofNullable(MediaSupport.longOf(MediaSupport.readPath(res, "usage.input_tokens")),
                MediaSupport.longOf(MediaSupport.readPath(res, "usage.output_tokens")), null, null);
        return new Media.MediaResult(files, null, null, "completed", usage);
    }

    @Override
    public Media.MediaResult speech(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("speech", ctx);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MediaSupport.modelFor(config, "speech", "image", "gpt-4o-mini-tts"));
        body.put("input", args.get("text"));
        String voice = MediaSupport.strArg(args, "voice");
        body.put("voice", voice == null ? "alloy" : voice);
        MediaSupport.Resp r = job.api("POST", url() + "/audio/speech",
                MediaSupport.jsonBody(body, options(ctx)), "application/json");
        return new Media.MediaResult(List.of(gather("speech", job).bytes(r.body(), "audio/mpeg")), null, null, "completed");
    }

    @Override
    public Media.MediaResult transcribe(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("transcribe", ctx);
        MediaSupport.Input in = MediaSupport.readInput(job, "transcribe", MediaSupport.strArg(args, "audio"), maxBytes());
        MediaSupport.Multipart mp = new MediaSupport.Multipart()
                .field("model", MediaSupport.modelFor(config, "transcribe", "image", "gpt-4o-mini-transcribe"));
        String lang = MediaSupport.strArg(args, "language");
        if (lang != null) mp.field("language", lang);
        mp.file("file", in).options(options(ctx));
        Object res = job.json(job.api("POST", url() + "/audio/transcriptions", mp.body(), mp.contentType()));
        Object text = MediaSupport.readPath(res, "text");
        if (!(text instanceof String s)) throw job.failed("transcribe: no result at text");
        return new Media.MediaResult(List.of(), s, null, "completed");
    }

    @Override
    public Media.MediaResult video(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("video", ctx);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MediaSupport.modelFor(config, "video", "image", "sora-2"));
        body.put("prompt", args.get("prompt"));
        Integer seconds = MediaSupport.intArg(args, "seconds");
        body.put("seconds", String.valueOf(seconds == null ? 4 : seconds));
        Object start = job.json(job.api("POST", url() + "/videos",
                MediaSupport.jsonBody(body, options(ctx)), "application/json"));
        job.jobId = MediaSupport.jobIdOf(MediaSupport.readPath(start, "id"));
        if (job.jobId == null) throw job.failed("video: no job id at id");
        String pollUrl = url() + "/videos/" + MediaSupport.pctEncode(job.jobId);
        while (true) {
            job.sleep(intervalMs());
            Object st = job.json(job.api("GET", pollUrl, null, null));
            String s = MediaSupport.renderStatus(MediaSupport.readPath(st, "status"));
            job.report(s);
            if (s.equals("completed")) break;
            if (s.equals("failed")) {
                Object m = MediaSupport.readPath(st, "error.message");
                String msg = m == null ? "" : MediaSupport.render(m);
                throw job.failed("job " + job.jobId + " failed" + (msg.isEmpty() ? "" : ": " + msg));
            }
            if (job.expired()) throw job.timeout();
        }
        MediaSupport.Resp r = job.api("GET", pollUrl + "/content", null, null);
        return new Media.MediaResult(List.of(gather("video", job).bytes(r.body(), "video/mp4")), null, job.jobId,
                "completed");
    }
}
