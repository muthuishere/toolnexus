package io.github.muthuishere.toolnexus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * §7G.9 — ElevenLabs ({@code elevenlabs}): speech (live-verified, incl. a cloned voice by name),
 * music, transcribe, cloneVoice (implemented per the provider's docs, unverified). Key
 * {@code ELEVENLABS_API_KEY} ({@code xi-api-key}), default url {@code https://api.elevenlabs.io},
 * primary model (speech) {@code eleven_flash_v2_5}.
 */
public final class ElevenLabsMediaClient extends ApiMediaClient {

    static final String DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb";

    private static final Map<String, List<Media.ArgSpec>> ARGS = Map.of(
            "speech", List.of(req("text"), opt("voice")),
            "music", List.of(req("prompt"), opt("seconds"), opt("instrumental")),
            "transcribe", List.of(req("audio"), opt("language")),
            "cloneVoice", List.of(req("name"), req("samples")));

    public ElevenLabsMediaClient() {
        this(null);
    }

    public ElevenLabsMediaClient(Media.Config config) {
        super("elevenlabs", config, "ELEVENLABS_API_KEY", "https://api.elevenlabs.io", 5000L);
    }

    @Override Map<String, String> authHeaders(String key) {
        return Map.of("xi-api-key", key);
    }

    @Override Map<String, List<Media.ArgSpec>> argTable() {
        return ARGS;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Media.MediaResult speech(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("speech", ctx);
        String voice = MediaSupport.strArg(args, "voice");
        String id = DEFAULT_VOICE;
        if (voice != null) {
            Object list = job.json(job.api("GET", url() + "/v2/voices?page_size=100&search="
                    + MediaSupport.pctEncode(voice), null, null));
            id = null;
            if (MediaSupport.readPath(list, "voices") instanceof List<?> voices) {
                for (Object o : voices) {
                    if (!(o instanceof Map<?, ?> v)) continue;
                    Object n = v.get("name");
                    Object vid = v.get("voice_id");
                    if ((n instanceof String s && s.toLowerCase(Locale.ROOT).equals(voice.toLowerCase(Locale.ROOT)))
                            || voice.equals(vid)) {
                        id = vid == null ? null : String.valueOf(vid);
                        if (id != null) break;
                    }
                }
            }
            if (id == null) {
                if (voice.length() == 20 && !voice.contains(" ")) id = voice;
                else throw job.failed("speech: voice \"" + voice + "\" not found");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", args.get("text"));
        body.put("model_id", MediaSupport.modelFor(config, "speech", "speech", "eleven_flash_v2_5"));
        MediaSupport.Resp r = job.api("POST", url() + "/v1/text-to-speech/" + MediaSupport.pctEncode(id)
                + "?output_format=mp3_22050_32", MediaSupport.jsonBody(body, options(ctx)), "application/json");
        job.jobId = r.headers().firstValue("request-id").filter(s -> !s.isEmpty()).orElse(null);
        Media.MediaFile f = gather("speech", job).bytes(r.body(), "audio/mpeg");
        return new Media.MediaResult(List.of(f), null, job.jobId, "completed");
    }

    @Override
    public Media.MediaResult music(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("music", ctx);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", args.get("prompt"));
        Integer seconds = MediaSupport.intArg(args, "seconds");
        body.put("music_length_ms", seconds == null ? 10000 : seconds * 1000);
        if (Boolean.TRUE.equals(args.get("instrumental"))) body.put("force_instrumental", true);
        MediaSupport.Resp r = job.api("POST", url() + "/v1/music?output_format=mp3_44100_128",
                MediaSupport.jsonBody(body, options(ctx)), "application/json");
        Media.MediaFile f = gather("music", job).bytes(r.body(), "audio/mpeg");
        return new Media.MediaResult(List.of(f), null, null, "completed");
    }

    @Override
    public Media.MediaResult transcribe(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("transcribe", ctx);
        MediaSupport.Input in = MediaSupport.readInput(job, "transcribe", MediaSupport.strArg(args, "audio"), maxBytes());
        MediaSupport.Multipart mp = new MediaSupport.Multipart()
                .field("model_id", MediaSupport.modelFor(config, "transcribe", "speech", "scribe_v1"));
        String lang = MediaSupport.strArg(args, "language");
        if (lang != null) mp.field("language_code", lang);
        mp.file("file", in).options(options(ctx));
        Object res = job.json(job.api("POST", url() + "/v1/speech-to-text", mp.body(), mp.contentType()));
        Object text = MediaSupport.readPath(res, "text");
        if (!(text instanceof String s)) throw job.failed("transcribe: no result at text");
        return new Media.MediaResult(List.of(), s, null, "completed");
    }

    @Override
    public Media.MediaResult cloneVoice(Map<String, Object> args, Media.Ctx ctx) {
        MediaSupport.Job job = job("cloneVoice", ctx);
        List<MediaSupport.Input> inputs = new ArrayList<>();
        Object samples = args.get("samples");
        if (samples instanceof List<?> l) {
            for (Object o : l) inputs.add(MediaSupport.readInput(job, "cloneVoice", String.valueOf(o), maxBytes()));
        }
        MediaSupport.Multipart mp = new MediaSupport.Multipart().field("name", MediaSupport.strArg(args, "name"));
        for (MediaSupport.Input in : inputs) mp.file("files", in);
        mp.options(options(ctx));
        Object res = job.json(job.api("POST", url() + "/v1/voices/add", mp.body(), mp.contentType()));
        Object id = MediaSupport.readPath(res, "voice_id");
        if (!(id instanceof String s) || s.isEmpty()) throw job.failed("cloneVoice: no result at voice_id");
        return new Media.MediaResult(List.of(), s, null, "completed");
    }
}
