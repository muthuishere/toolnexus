package io.github.muthuishere.toolnexus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * §7G.10 — the Grok CLI ({@code grok}; image live-verified, video live-verified after opting in).
 * Runs {@code grok -p <prompt> --cwd <cwd> --output-format streaming-json --yolo --no-auto-update}
 * with {@code GROK_CONFIG} enabling the image and video tools. Under Zero Data Retention the video
 * tool fails and the run is {@code unavailable} (see docs/media#grok-video-zdr). The client never
 * edits the user's Grok settings.
 */
public final class GrokMediaClient extends CliMediaClient {

    public GrokMediaClient() {
        this(null);
    }

    public GrokMediaClient(Media.Config config) {
        super("grok", config, List.of("grok"));
    }

    @Override
    protected String template(String verb) {
        return switch (verb) {
            case "image" -> "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the file path.";
            case "video" -> "Generate exactly one short video with your video generation tool, shortest duration available: {prompt}. Do not write code. Reply only with the file path.";
            default -> null;
        };
    }

    @Override
    protected List<String> argv(String verb, String prompt, Path cwd) {
        List<String> a = new ArrayList<>(List.of("-p", prompt, "--cwd", cwd.toString(),
                "--output-format", "streaming-json", "--yolo", "--no-auto-update"));
        if (config.model != null && !config.model.isEmpty()) { a.add("-m"); a.add(config.model); }
        return a;
    }

    @Override
    protected Map<String, String> env() {
        return Map.of("GROK_CONFIG", "{\"features\":{\"image_gen\":true,\"video_gen\":true}}");
    }

    @Override
    protected Event parse(Map<String, Object> e, String line) {
        Object type = e.get("type");
        if (!(type instanceof String t)) return null;
        switch (t) {
            case "text":
                return e.get("data") instanceof String s ? new Event(null, s, List.of(), null, null) : null;
            case "tool_call":
                return new Event(null, null, List.of(), null, "tool " + MediaSupport.str(e.get("toolName")));
            case "tool_call_update": {
                if (!(e.get("status") instanceof String st) || st.isEmpty()) return null;
                List<String> paths = new ArrayList<>();
                String error = null;
                Object raw = e.get("rawOutput");
                if ("completed".equals(st) && MediaSupport.readPath(raw, "path") instanceof String p && !p.isEmpty()) {
                    paths.add(p);
                }
                if ("failed".equals(st)) {
                    error = MediaSupport.readPath(raw, "message") instanceof String m ? m : MediaSupport.sortedJson(raw);
                }
                return new Event(null, null, paths, error, "tool " + st);
            }
            case "end":
                // §7G.12 D21: usage.input_tokens/output_tokens, total_cost_usd -> costUsd,
                // total_cost_usd_ticks kept verbatim in usage.raw; each only when present.
                Object u = e.get("usage");
                Long in = MediaSupport.readPath(u, "input_tokens") instanceof Number a ? a.longValue() : null;
                Long out = MediaSupport.readPath(u, "output_tokens") instanceof Number b ? b.longValue() : null;
                Double cost = e.get("total_cost_usd") instanceof Number c ? c.doubleValue() : null;
                Map<String, Object> raw = e.get("total_cost_usd_ticks") instanceof Number n
                        ? Map.of("total_cost_usd_ticks", n) : null;
                Media.MediaUsage usage = Media.MediaUsage.ofNullable(in, out, cost, raw);
                return new Event(e.get("sessionId") instanceof String s && !s.isEmpty() ? s : null, null, List.of(), null,
                        "end " + MediaSupport.str(e.get("stopReason")), usage);
            case "error":
                return new Event(null, null, List.of(), e.get("message") instanceof String m ? m : Json.stringify(e.get("message")), null);
            default:
                return null;
        }
    }

    @Override
    protected String unavailable(String verb, String error) {
        String l = error.toLowerCase(Locale.ROOT);
        if (l.contains("zero data retention")) {
            return "grok: " + verb + " blocked by Zero Data Retention — run /privacy and opt in, or configure ZDR video storage (docs/media#grok-video-zdr)";
        }
        if (l.contains("usage limit")) return "grok: " + capped(error, 400);
        return null;
    }
}
