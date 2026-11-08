package io.github.muthuishere.toolnexus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Google Antigravity CLI ({@code agy}; image). Runs
 * {@code agy -p <prompt> --output-format stream-json --dangerously-skip-permissions [--model <m>]}.
 * The path is in the final result's response text, else the newest image in the conversation's
 * brain folder. Reached through the {@link MediaStyle#GOOGLE_AGY_CLI} preset.
 */
final class AgyMediaClient extends CliMediaClient {

    AgyMediaClient(Media.Config config) {
        super("agy", config, List.of("agy"));
    }

    @Override
    protected String template(String verb) {
        return "image".equals(verb)
                ? "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the absolute path where the image was saved."
                : null;
    }

    @Override
    protected List<String> argv(String verb, String prompt, Path cwd) {
        List<String> a = new ArrayList<>(List.of("-p", prompt, "--output-format", "stream-json",
                "--dangerously-skip-permissions"));
        if (config.model != null && !config.model.isEmpty()) { a.add("--model"); a.add(config.model); }
        return a;
    }

    @Override
    protected Event parse(Map<String, Object> e, String line) {
        if (!(e.get("event") instanceof String t)) return null;
        switch (t) {
            case "init":
                return new Event(e.get("conversation_id") instanceof String s && !s.isEmpty() ? s : null,
                        null, List.of(), null, "init");
            case "step_update": {
                if (MediaSupport.readPath(e, "step_update.tool_name") instanceof String tool && !tool.isEmpty()
                        && MediaSupport.readPath(e, "step_update.state") instanceof String st && !st.isEmpty()) {
                    return new Event(null, null, List.of(), null, "tool " + tool + " " + st);
                }
                return null;
            }
            case "result": {
                String session = MediaSupport.readPath(e, "result.conversation_id") instanceof String s && !s.isEmpty() ? s : null;
                String text = MediaSupport.readPath(e, "result.response") instanceof String s ? s : null;
                String st = MediaSupport.readPath(e, "result.status") instanceof String s ? s : "";
                String error = null;
                if (!"SUCCESS".equals(st)) {
                    if (MediaSupport.readPath(e, "result.error") instanceof String m && !m.isEmpty()) error = m;
                    else if (MediaSupport.readPath(e, "result.error.message") instanceof String m && !m.isEmpty()) error = m;
                    else if (text != null && !text.isEmpty()) error = text;
                    else error = MediaSupport.sortedJson(e.get("result"));
                }
                Long in = MediaSupport.readPath(e, "result.usage.input_tokens") instanceof Number a ? a.longValue() : null;
                Long out = MediaSupport.readPath(e, "result.usage.output_tokens") instanceof Number b ? b.longValue() : null;
                return new Event(session, text == null || text.isEmpty() ? null : text, List.of(), error,
                        "result " + st, Media.MediaUsage.ofNullable(in, out, null, null));
            }
            case "error":
                return new Event(null, null, List.of(), e.get("message") instanceof String m && !m.isEmpty() ? m : null, null);
            default:
                return null;
        }
    }

    @Override
    protected Path folder(String session) {
        String home = Media.env.apply("HOME");
        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
        return Path.of(home, ".gemini", "antigravity-cli", "brain", session);
    }

    @Override
    protected String unavailable(String verb, String error) {
        String l = error.toLowerCase(Locale.ROOT);
        if (l.contains("quota") || l.contains("usage limit")) return "agy: " + capped(error, 400);
        return null;
    }
}
