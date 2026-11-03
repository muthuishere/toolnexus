package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §7G.9 — OpenRouter ({@code openrouter}): image (live-verified) over chat completions with
 * {@code modalities: ["image","text"]}. Key {@code OPENROUTER_API_KEY}
 * ({@code Authorization: Bearer}), default url {@code https://openrouter.ai/api/v1}, primary model
 * (image) {@code google/gemini-2.5-flash-image}. Video/audio are not built in.
 */
public final class OpenRouterMediaClient extends ApiMediaClient {

    private static final Map<String, List<Media.ArgSpec>> ARGS = Map.of("image", List.of(req("prompt")));

    public OpenRouterMediaClient() {
        this(null);
    }

    public OpenRouterMediaClient(Media.Config config) {
        super("openrouter", config, "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", 5000L);
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
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", args.get("prompt"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MediaSupport.modelFor(config, "image", "image", "google/gemini-2.5-flash-image"));
        body.put("modalities", List.of("image", "text"));
        body.put("messages", List.of(msg));
        Object res = job.json(job.api("POST", url() + "/chat/completions",
                MediaSupport.jsonBody(body, options(ctx)), "application/json"));
        job.jobId = MediaSupport.jobIdOf(MediaSupport.readPath(res, "id"));
        StringBuilder text = new StringBuilder();
        List<String> urls = new ArrayList<>();
        if (MediaSupport.readPath(res, "choices") instanceof List<?> choices) {
            for (Object c : choices) {
                if (MediaSupport.readPath(c, "message.content") instanceof String s) text.append(s);
                if (MediaSupport.readPath(c, "message.images") instanceof List<?> images) {
                    for (Object im : images) {
                        if (MediaSupport.readPath(im, "image_url.url") instanceof String u && !u.isEmpty()) urls.add(u);
                    }
                }
            }
        }
        if (urls.isEmpty()) {
            throw job.failed("image: no image in response"
                    + (text.length() == 0 ? "" : ": " + MediaSupport.cap(text.toString(), 200)));
        }
        MediaSupport.Gather g = gather("image", job);
        List<Media.MediaFile> files = new ArrayList<>();
        for (String u : urls) files.add(g.url(u));
        // §7G.12 D21: usage.prompt_tokens / completion_tokens / cost (USD) as reported.
        Object cost = MediaSupport.readPath(res, "usage.cost");
        Media.MediaUsage usage = Media.MediaUsage.ofNullable(MediaSupport.longOf(MediaSupport.readPath(res, "usage.prompt_tokens")),
                MediaSupport.longOf(MediaSupport.readPath(res, "usage.completion_tokens")),
                cost instanceof Number n ? n.doubleValue() : null, null);
        return new Media.MediaResult(files, text.length() == 0 ? null : text.toString(), job.jobId, "completed", usage);
    }
}
