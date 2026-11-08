package io.github.muthuishere.toolnexus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * §7G.10 — the Codex CLI ({@code codex}; image live-verified). Runs
 * {@code codex exec --json --skip-git-repo-check -s workspace-write [-m <model>] <prompt>}. No event
 * carries the file: it comes from the text scan and {@code $CODEX_HOME/generated_images/<thread>}
 * ({@code CODEX_HOME} defaults to {@code ~/.codex}).
 */
public final class CodexMediaClient extends CliMediaClient {

    public CodexMediaClient() {
        this(null);
    }

    public CodexMediaClient(Media.Config config) {
        super("codex", config, List.of("codex"));
    }

    @Override
    protected String template(String verb) {
        return "image".equals(verb)
                ? "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write code or use scripts. Reply only with the absolute path where the image was saved."
                : null;
    }

    @Override
    protected List<String> argv(String verb, String prompt, Path cwd) {
        List<String> a = new ArrayList<>(List.of("exec", "--json", "--skip-git-repo-check", "-s", "workspace-write"));
        if (config.model != null && !config.model.isEmpty()) { a.add("-m"); a.add(config.model); }
        a.add(prompt);
        return a;
    }

    @Override
    protected Event parse(Map<String, Object> e, String line) {
        if (!(e.get("type") instanceof String t)) return null;
        String session = null, text = null, error = null;
        switch (t) {
            case "thread.started" -> session = e.get("thread_id") instanceof String s && !s.isEmpty() ? s : null;
            case "item.completed" -> {
                if ("agent_message".equals(MediaSupport.readPath(e, "item.type"))
                        && MediaSupport.readPath(e, "item.text") instanceof String s) text = s;
            }
            case "error", "turn.failed" -> {
                if (e.get("message") instanceof String m) error = m;
                else if (MediaSupport.readPath(e, "error.message") instanceof String m) error = m;
                else error = line;
            }
            default -> { }
        }
        return new Event(session, text, List.of(), error, t);
    }

    @Override
    protected Path folder(String session) {
        String home = Media.env.apply("CODEX_HOME");
        Path root = home == null || home.isEmpty() ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(home);
        return root.resolve("generated_images").resolve(session);
    }

    @Override
    protected String unavailable(String verb, String error) {
        String l = error.toLowerCase(Locale.ROOT);
        if (l.contains("usage limit") || l.contains("quota")) return "codex: " + capped(error, 400);
        return null;
    }
}
