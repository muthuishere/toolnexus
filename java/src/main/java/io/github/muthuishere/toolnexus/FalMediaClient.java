package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §7G.9 — fal ({@code fal}): the queue API for any hosted model id. image (live-verified on
 * {@code fal-ai/flux/schnell}), video (implemented per the provider's docs, unverified). Key
 * {@code FAL_API_KEY} ({@code Authorization: Key}), default url {@code https://queue.fal.run},
 * poll interval 500 ms. {@code status_url} / {@code response_url} follow the §7G.8 origin rule.
 */
public final class FalMediaClient extends ApiMediaClient {

    private static final Map<String, List<Media.ArgSpec>> ARGS = Map.of(
            "image", List.of(req("prompt"), opt("width"), opt("height"), opt("seed")),
            "video", List.of(req("prompt"), opt("image"), opt("seconds"), opt("aspectRatio")));

    public FalMediaClient() {
        this(null);
    }

    public FalMediaClient(Media.Config config) {
        super("fal", config, "FAL_API_KEY", "https://queue.fal.run", 500L);
    }

    @Override Map<String, String> authHeaders(String key) {
        return Map.of("Authorization", "Key " + key);
    }

    @Override Map<String, List<Media.ArgSpec>> argTable() {
        return ARGS;
    }

    @Override
    public Media.MediaResult image(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("image", ctx);
        String model = MediaSupport.modelFor(config, "image", "image", "fal-ai/flux/schnell");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", args.get("prompt"));
        body.put("num_images", 1);
        if (model.contains("schnell")) {
            body.put("image_size", "square");
            body.put("num_inference_steps", 4);
        }
        Integer w = MediaSupport.intArg(args, "width");
        Integer h = MediaSupport.intArg(args, "height");
        if (w != null || h != null) {
            Map<String, Object> size = new LinkedHashMap<>();
            size.put("width", w != null ? w : h);
            size.put("height", h != null ? h : w);
            body.put("image_size", size);
        }
        if (args.get("seed") != null) body.put("seed", args.get("seed"));
        return queue("image", model, body, job, ctx);
    }

    @Override
    public Media.MediaResult video(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("video", ctx);
        String image = MediaSupport.strArg(args, "image");
        String model = config.models != null && config.models.get("video") != null && !config.models.get("video").isEmpty()
                ? config.models.get("video")
                : image != null ? "fal-ai/kling-video/v2.1/standard/image-to-video"
                : "fal-ai/kling-video/v2.1/standard/text-to-video";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", args.get("prompt"));
        Integer seconds = MediaSupport.intArg(args, "seconds");
        body.put("duration", String.valueOf(seconds == null ? 5 : seconds));
        if (image != null) body.put("image_url", image);
        String ar = MediaSupport.strArg(args, "aspectRatio");
        if (ar != null) body.put("aspect_ratio", ar);
        return queue("video", model, body, job, ctx);
    }

    private Media.MediaResult queue(String verb, String model, Map<String, Object> body, MediaSupport.Job job,
                                    Media.Ctx ctx) {
        Object sub = job.json(job.api("POST", url() + "/" + model,
                MediaSupport.jsonBody(body, options(ctx)), "application/json"));
        job.jobId = MediaSupport.jobIdOf(MediaSupport.readPath(sub, "request_id"));
        if (job.jobId == null) throw job.failed(verb + ": no job id at request_id");
        String reqPath = url() + "/" + model + "/requests/" + MediaSupport.pctEncode(job.jobId);
        String statusUrl = MediaSupport.readPath(sub, "status_url") instanceof String s && !s.isEmpty()
                ? s : reqPath + "/status";
        String responseUrl = MediaSupport.readPath(sub, "response_url") instanceof String s && !s.isEmpty()
                ? s : reqPath;
        while (true) {
            job.sleep(intervalMs());
            Object st = job.json(job.api("GET", statusUrl, null, null));
            String s = MediaSupport.renderStatus(MediaSupport.readPath(st, "status"));
            job.report(s);
            if (s.equals("COMPLETED")) break;
            Object err = MediaSupport.readPath(st, "error");
            String e = err == null ? "" : MediaSupport.render(err);
            if (!e.isEmpty()) throw job.failed("job " + job.jobId + " failed: " + e);
            if (job.expired()) throw job.timeout();
        }
        Object res = job.json(job.api("GET", responseUrl, null, null));
        List<String> urls = new ArrayList<>();
        if (MediaSupport.readPath(res, "images") instanceof List<?> images) {
            for (Object im : images) if (MediaSupport.readPath(im, "url") instanceof String u && !u.isEmpty()) urls.add(u);
        }
        for (String p : List.of("image.url", "video.url", "audio.url", "audio_file.url")) {
            if (MediaSupport.readPath(res, p) instanceof String u && !u.isEmpty()) urls.add(u);
        }
        if (urls.isEmpty()) throw job.failed(verb + ": no result in response");
        MediaSupport.Gather g = gather(verb, job);
        List<Media.MediaFile> files = new ArrayList<>();
        for (String u : urls) files.add(g.url(u));
        return new Media.MediaResult(files, null, job.jobId, "completed");
    }
}
