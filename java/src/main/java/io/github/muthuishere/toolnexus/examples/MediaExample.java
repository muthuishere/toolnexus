package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.A2EMediaClient;
import io.github.muthuishere.toolnexus.CodexMediaClient;
import io.github.muthuishere.toolnexus.ElevenLabsMediaClient;
import io.github.muthuishere.toolnexus.FalMediaClient;
import io.github.muthuishere.toolnexus.GrokMediaClient;
import io.github.muthuishere.toolnexus.Media;
import io.github.muthuishere.toolnexus.MediaClient;
import io.github.muthuishere.toolnexus.OpenAIMediaClient;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Media generation (SPEC.md §7G) — the E1 example contract, identical in every port.
 *
 * <pre>[ELEVENLABS_API_KEY=...] [OPENAI_API_KEY=...] [FAL_API_KEY=...] [A2E_API_KEY=...] ./gradlew runMedia</pre>
 *
 * <p>Clients, each only when its CLI is on {@code PATH} or its key is set: {@code grok},
 * {@code codex}, {@code elevenlabs}, then the paid fallbacks {@code openai}, {@code fal},
 * {@code a2e}. Steps: (1) an image, (2) speech through ElevenLabs (voice from {@code MEDIA_VOICE},
 * else the default), (3) the tool face — list {@code media.tools()} and call
 * {@code generate_image}, (4) a Grok video only with {@code MEDIA_VIDEO=1}, (5) paid video and
 * talk only with {@code MEDIA_PAID=1}. Paid image clients run on their smallest, lowest-quality
 * defaults. Exits non-zero if any attempted step fails; exits 0 with a hint when no client is
 * available. Keys are read from the environment at call time and never printed.
 */
public final class MediaExample {

    private static int failures = 0;

    public static void main(String[] args) {
        Media.Config config = new Media.Config().gather(new Media.Gather().mode("download"));

        List<Served> all = new ArrayList<>();
        Served grok = null, elevenlabs = null;
        List<Served> paid = new ArrayList<>();
        if (onPath("grok")) all.add(grok = new Served(new GrokMediaClient(config)));
        if (onPath("codex")) all.add(new Served(new CodexMediaClient(config)));
        if (hasEnv("ELEVENLABS_API_KEY")) all.add(elevenlabs = new Served(new ElevenLabsMediaClient(config)));
        if (hasEnv("OPENAI_API_KEY")) paid.add(new Served(new OpenAIMediaClient(config)));
        if (hasEnv("FAL_API_KEY")) paid.add(new Served(new FalMediaClient(config)));
        if (hasEnv("A2E_API_KEY")) paid.add(new Served(new A2EMediaClient(config)));
        all.addAll(paid);

        if (all.isEmpty()) {
            System.out.println("no media client available: install and log in to the grok or codex CLI, or set "
                    + "ELEVENLABS_API_KEY, OPENAI_API_KEY, FAL_API_KEY or A2E_API_KEY (no network call made)");
            return;
        }
        System.out.println("clients: " + String.join(", ", all.stream().map(Served::name).toList()));
        Media media = Media.create(new ArrayList<MediaClient>(all));

        // 1. image (function face)
        Media.MediaResult img = step("image", all, m -> m.image(new Media.ImageInput("a red apple on a white table")), media);

        // 2. speech through ElevenLabs
        Media.MediaResult speech = null;
        if (elevenlabs != null) {
            String voice = System.getenv("MEDIA_VOICE");
            Media.SpeechInput in = new Media.SpeechInput("Hello from toolnexus.",
                    voice == null || voice.isEmpty() ? null : voice, null);
            speech = step("speech", List.of(elevenlabs), m -> m.speech(in), Media.create(elevenlabs));
        } else {
            System.out.println("speech: skipped (set ELEVENLABS_API_KEY)");
        }

        // 3. tool face: the same router as tools an LLM would call
        List<Tool> tools = media.tools();
        System.out.println("tools: " + String.join(", ", tools.stream().map(Tool::name).toList()));
        tools.stream().filter(t -> t.name().equals("generate_image")).findFirst().ifPresent(t -> {
            ToolResult r = t.execute(Map.of("prompt", "a red apple on a white table"), null);
            if (r.isError()) {
                failures++;
                System.out.println("tool generate_image: ERROR " + r.output());
            } else {
                Map<String, Object> meta = r.metadata();
                System.out.println("tool generate_image: client=" + meta.get("client") + " output=" + r.output()
                        + (meta.containsKey("usage") ? " usage=" + meta.get("usage") : ""));
            }
        });

        // 4. Grok video, opt-in
        if ("1".equals(System.getenv("MEDIA_VIDEO")) && grok != null) {
            step("video", List.of(grok), m -> m.video(new Media.VideoInput("the apple slowly rotates")), Media.create(grok));
        }

        // 5. paid video and talk, opt-in
        if ("1".equals(System.getenv("MEDIA_PAID")) && !paid.isEmpty()) {
            Media paidMedia = Media.create(new ArrayList<MediaClient>(paid));
            if (paidMedia.supports("video")) {
                step("video", paid, m -> m.video(new Media.VideoInput("the apple slowly rotates")), paidMedia);
            }
            if (paidMedia.supports("talk") && img != null && speech != null
                    && !img.files().isEmpty() && !speech.files().isEmpty()) {
                String face = ref(img.files().get(0)), audio = ref(speech.files().get(0));
                step("talk", paid, m -> m.talk(new Media.TalkInput(face, audio)), paidMedia);
            }
        }

        if (failures > 0) {
            System.out.println(failures + " step(s) failed");
            System.exit(1);
        }
    }

    /** Run one verb, print each file (client, verb, path or url, mime, size, usage); count a failure. */
    private static Media.MediaResult step(String verb, List<Served> clients, Function<Media, Media.MediaResult> call,
                                          Media media) {
        clients.forEach(s -> s.last = null);
        try {
            Media.MediaResult r = call.apply(media);
            String client = clients.stream().filter(s -> s.last != null).map(Served::name).findFirst().orElse("?");
            for (Media.MediaFile f : r.files()) {
                System.out.println(verb + ": client=" + client + " " + ref(f) + " mime=" + f.mimeType()
                        + " size=" + size(f) + (r.usage() == null ? "" : " usage=" + r.usage().toMap()));
            }
            return r;
        } catch (Media.MediaException e) {
            failures++;
            System.out.println(verb + ": ERROR (" + e.status() + ") " + e.getMessage());
            return null;
        }
    }

    private static String ref(Media.MediaFile f) {
        return f.path() != null ? f.path() : f.url() != null ? f.url() : "inline";
    }

    private static long size(Media.MediaFile f) {
        try {
            if (f.path() != null) return Files.size(Path.of(f.path()));
            if (f.data() != null) return Base64.getDecoder().decode(f.data()).length;
        } catch (Exception ignored) {
            // fall through
        }
        return -1;
    }

    private static boolean hasEnv(String name) {
        String v = System.getenv(name);
        return v != null && !v.isEmpty();
    }

    private static boolean onPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String d : path.split(File.pathSeparator)) {
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, cmd))) return true;
        }
        return false;
    }

    /** Delegates to a client and remembers when it served a call (to print which client answered). */
    private static final class Served implements MediaClient {
        final MediaClient inner;
        volatile String last;

        Served(MediaClient inner) { this.inner = inner; }

        @Override public String name() { return inner.name(); }
        @Override public boolean supports(String verb) { return inner.supports(verb); }
        @Override public List<Media.ArgSpec> args(String verb) { return inner.args(verb); }

        @Override
        public Media.MediaResult call(String verb, Map<String, Object> args, Media.Ctx ctx) {
            Media.MediaResult r = inner.call(verb, new LinkedHashMap<>(args), ctx);
            last = verb;
            return r;
        }
    }
}
