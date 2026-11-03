package io.github.muthuishere.toolnexus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * §7G.10 — the CLI base: runs a local subscription CLI <b>once, headless</b>, reads its JSON-lines
 * stdout, and collects files. Subclasses ({@link GrokMediaClient}, {@link CodexMediaClient})
 * supply the argv, the prompt templates, the line parser, an optional session folder and the
 * unavailable matcher. CLI clients accept {@code prompt} only.
 *
 * <p>A command that cannot be started is {@code unavailable}; cancellation or the deadline
 * ({@code loop.timeoutMs}) kills the process (and its descendants).
 */
public abstract class CliMediaClient implements MediaClient {

    /** What one stdout JSON object contributed. Every field is optional. */
    public record Event(String session, String text, List<String> paths, String error, String status,
                        Media.MediaUsage usage) {
        public Event(String session, String text, List<String> paths, String error, String status) {
            this(session, text, paths, error, status, null);
        }

        public static Event of() {
            return new Event(null, null, List.of(), null, null);
        }
    }

    private static final Pattern PATH = Pattern.compile(
            "/[^\\s\"'()\\[\\]]+\\.(?i:png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav)");

    private final String name;
    protected final Media.Config config;
    private final List<String> defaultCommand;

    protected CliMediaClient(String name, Media.Config config, List<String> defaultCommand) {
        this.name = name;
        this.config = Media.Config.orEmpty(config);
        this.defaultCommand = List.copyOf(defaultCommand);
    }

    @Override public String name() { return name; }

    /** The prompt template for {@code verb} ({@code {prompt}} is replaced), or null when unsupported. */
    protected abstract String template(String verb);

    /** The client argv after the command. */
    protected abstract List<String> argv(String verb, String prompt, Path cwd);

    /** Parse one stdout JSON object ({@code line} is its raw text). */
    protected abstract Event parse(Map<String, Object> event, String line);

    /** Fixed env added to the process env. */
    protected Map<String, String> env() {
        return Map.of();
    }

    /** The CLI's own output folder for a session, or null. */
    protected Path folder(String session) {
        return null;
    }

    /** The unavailable message for the last error, or null when it is an ordinary failure. */
    protected String unavailable(String verb, String error) {
        return null;
    }

    @Override public boolean supports(String verb) {
        return template(verb) != null;
    }

    @Override public List<Media.ArgSpec> args(String verb) {
        return List.of(new Media.ArgSpec("prompt", true));
    }

    @Override public Media.MediaResult image(Map<String, Object> a, Media.Ctx c) { return call("image", a, c); }
    @Override public Media.MediaResult video(Map<String, Object> a, Media.Ctx c) { return call("video", a, c); }

    @Override
    public Media.MediaResult call(String verb, Map<String, Object> args, Media.Ctx ctx) {
        if (!supports(verb)) return MediaClient.super.call(verb, args, ctx);
        return run(verb, args, ctx == null ? new Media.Ctx() : ctx);
    }

    /** Cap at {@code n} code points plus "…". */
    protected static String capped(String s, int n) {
        return MediaSupport.cap(s, n);
    }

    private final class State {
        String session;
        final StringBuilder text = new StringBuilder();
        final List<String> paths = new ArrayList<>();
        String error;
        Media.MediaUsage usage;
    }

    private Media.MediaResult run(String verb, Map<String, Object> args, Media.Ctx ctx) {
        String prompt = template(verb).replace("{prompt}", MediaSupport.strArg(args, "prompt") == null
                ? "" : MediaSupport.strArg(args, "prompt"));
        long timeoutMs = config.loop != null && config.loop.timeoutMs != null && config.loop.timeoutMs > 0
                ? config.loop.timeoutMs : MediaSupport.DEFAULT_TIMEOUT_MS;
        long start = System.nanoTime();
        long deadline = start + timeoutMs * 1_000_000L;
        if (ctx.deadline != null) {
            deadline = Math.min(deadline, start
                    + java.time.Duration.between(java.time.Instant.now(), ctx.deadline).toNanos());
        }
        Consumer<Media.Progress> progress = ctx.progress != null ? ctx.progress
                : config.loop != null ? config.loop.onProgress : null;
        List<String> command = config.command != null && !config.command.isEmpty() ? config.command : defaultCommand;

        Path cwd;
        try {
            cwd = Files.createTempDirectory("toolnexus-cli-").toRealPath(); // §7G.12 D17
        } catch (IOException e) {
            throw new Media.MediaException(name + ": cannot create a working dir: " + e.getMessage());
        }
        try {
            List<String> full = new ArrayList<>(command);
            full.addAll(argv(verb, prompt, cwd));
            ProcessBuilder pb = new ProcessBuilder(full).directory(cwd.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.environment().putAll(env());
            Process p;
            try {
                p = pb.start();
            } catch (IOException | RuntimeException e) {
                throw new Media.MediaUnavailableException(name + ": cannot start \"" + command.get(0) + "\"");
            }
            try {
                p.getOutputStream().close(); // stdin closed/empty
            } catch (IOException ignored) {
            }

            State st = new State();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Map<String, Object> obj;
                        String t = line.trim();
                        if (!t.startsWith("{")) continue;
                        try {
                            obj = Json.toMap(t);
                        } catch (RuntimeException e) {
                            continue;
                        }
                        Event ev = parse(obj, line);
                        if (ev == null) continue;
                        String status;
                        synchronized (st) {
                            if (ev.session() != null) st.session = ev.session();
                            if (ev.text() != null) st.text.append(ev.text());
                            if (ev.paths() != null) st.paths.addAll(ev.paths());
                            if (ev.error() != null) st.error = ev.error();
                            if (ev.usage() != null) st.usage = ev.usage();
                            status = ev.status();
                        }
                        if (status != null && progress != null) {
                            progress.accept(new Media.Progress(verb, name, st.session, status,
                                    (System.nanoTime() - start) / 1_000_000L));
                        }
                    }
                } catch (IOException ignored) {
                    // process killed / stream closed
                }
            }, "toolnexus-cli-" + name);
            reader.setDaemon(true);
            reader.start();

            try {
                while (!p.waitFor(20, TimeUnit.MILLISECONDS)) {
                    if ((ctx.cancelled != null && ctx.cancelled.getAsBoolean())) {
                        kill(p);
                        throw new LlmClient.CancelledException("media cancelled");
                    }
                    if (System.nanoTime() - deadline >= 0) {
                        kill(p);
                        throw new Media.MediaException(name + ": timed out after " + timeoutMs + "ms", "timeout", st.session);
                    }
                }
                reader.join(5000);
            } catch (InterruptedException e) {
                kill(p);
                Thread.currentThread().interrupt();
                throw new LlmClient.CancelledException("media cancelled");
            }
            int exit = p.exitValue();

            String session, text, error;
            List<String> paths;
            Media.MediaUsage usage;
            synchronized (st) {
                usage = st.usage;
                session = st.session;
                text = st.text.toString();
                error = st.error;
                paths = new ArrayList<>(st.paths);
            }

            LinkedHashSet<String> cands = new LinkedHashSet<>(paths);
            Matcher m = PATH.matcher(text);
            while (m.find()) cands.add(m.group());
            if (session != null) {
                Path dir = folder(session);
                if (dir != null && Files.isDirectory(dir)) {
                    try (Stream<Path> s = Files.list(dir)) {
                        s.sorted(Comparator.comparing(x -> x.getFileName().toString()))
                                .forEach(x -> cands.add(x.toAbsolutePath().toString()));
                    } catch (IOException ignored) {
                    }
                }
            }
            String want = "image".equals(verb) ? "image/" : "video/";
            List<String> kept = new ArrayList<>();
            for (String c : cands) {
                if (c.isEmpty()) continue;
                Path f;
                try {
                    f = Path.of(c);
                } catch (RuntimeException e) {
                    continue;
                }
                if (!Files.isRegularFile(f)) continue;
                String mime = MediaSupport.mimeOfName(c);
                if (mime == null || !mime.startsWith(want)) continue;
                kept.add(c);
            }

            if (!kept.isEmpty()) {
                MediaSupport.Gather g = new MediaSupport.Gather(name, verb, MediaSupport.kindOf(verb), config,
                        "download", null);
                List<Media.MediaFile> files = new ArrayList<>();
                for (String k : kept) files.add(g.local(k));
                return new Media.MediaResult(files, text.isEmpty() ? null : text, session, "completed",
                        usage); // §7G.12 D21
            }
            if (error != null) {
                String u = unavailable(verb, error);
                if (u != null) throw new Media.MediaUnavailableException(u, session);
                throw new Media.MediaException(name + ": no " + verb + ": " + capped(error, 400), "failed", session);
            }
            throw new Media.MediaException(name + ": no " + verb + " file in the run (exit code " + exit + ")"
                    + (text.isEmpty() ? "" : ": " + capped(text, 300)), "failed", session);
        } finally {
            deleteTree(cwd);
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }
}
