package io.github.muthuishere.toolnexus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The commands {@code bash} started with {@code run_in_background} (SPEC §4C). Create one, pass
 * it as the {@code background} key of the {@code builtins} config map, and call
 * {@link #closeAll()} when the host shuts down — a background command is NOT tied to the turn that
 * started it, so nothing else stops it. With it set, {@code bash} gains {@code run_in_background}
 * and the {@code bash_output} / {@code kill_shell} tools exist.
 */
public final class BackgroundShells implements AutoCloseable {
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private int next;

    /** One background command, for a host UI. {@code exitCode} is valid when not running. */
    public record Info(String id, String command, Instant started, boolean running, int exitCode, boolean killed) {}

    static final class Job {
        final String id;
        final String command;
        final Instant started = Instant.now();
        final Process proc;
        final long graceMs;
        private final StringBuilder buf = new StringBuilder();
        private int read; // chars of buf already returned by bash_output
        volatile boolean killed;
        private final Thread reader;

        Job(String id, String command, Process proc, long graceMs) {
            this.id = id;
            this.command = command;
            this.proc = proc;
            this.graceMs = graceMs;
            this.reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) != -1) {
                        synchronized (buf) {
                            buf.append(new String(b, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "toolnexus-bg-" + id);
            reader.setDaemon(true);
            reader.start();
        }

        /** Output produced since the last call. Once the process exited, waits for the reader to drain. */
        String fresh() {
            if (!proc.isAlive()) {
                try {
                    reader.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (buf) {
                String out = buf.substring(read);
                read = buf.length();
                return out;
            }
        }

        Info info() {
            boolean running = proc.isAlive();
            return new Info(id, command, started, running, running ? 0 : signalCode(proc.exitValue()), killed);
        }

        /** Stop the whole process tree: SIGTERM, the grace window, SIGKILL. Returns whether it was running. */
        boolean kill() {
            if (!proc.isAlive()) return false;
            killed = true;
            List<ProcessHandle> descendants = proc.descendants().toList();
            for (ProcessHandle h : descendants) h.destroy();
            proc.destroy();
            try {
                if (!proc.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                    for (ProcessHandle h : descendants) h.destroyForcibly();
                    proc.destroyForcibly();
                    proc.waitFor();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (ProcessHandle h : descendants) {
                if (h.isAlive()) h.destroyForcibly();
            }
            return true;
        }

        String status() {
            Info i = info();
            if (i.running()) return "running";
            if (i.killed()) return "killed";
            return "exited with code " + i.exitCode();
        }
    }

    String start(List<String> argv, String command, File workdir, long graceMs) throws IOException {
        List<String> full = new ArrayList<>(argv);
        full.add(command);
        ProcessBuilder pb = new ProcessBuilder(full).redirectErrorStream(true).directory(workdir);
        Process proc = pb.start();
        synchronized (jobs) {
            next++;
            String id = "bg" + next;
            jobs.put(id, new Job(id, command, proc, graceMs));
            return id;
        }
    }

    Job get(String id) {
        synchronized (jobs) {
            return jobs.get(id);
        }
    }

    /** Every background command, oldest first. */
    public List<Info> list() {
        List<Job> all;
        synchronized (jobs) {
            all = new ArrayList<>(jobs.values());
        }
        List<Info> out = new ArrayList<>();
        for (Job j : all) out.add(j.info());
        return out;
    }

    /** Stop one background command's process tree. Unknown id ⇒ {@link IllegalArgumentException}. */
    public void kill(String id) {
        Job j = get(id);
        if (j == null) throw new IllegalArgumentException("no background shell \"" + id + "\"");
        j.kill();
    }

    /** Stop every background command still running, concurrently. */
    public void closeAll() {
        List<Job> all;
        synchronized (jobs) {
            all = new ArrayList<>(jobs.values());
        }
        List<Thread> ts = new ArrayList<>();
        for (Job j : all) ts.add(Thread.ofVirtual().start(j::kill));
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        closeAll();
    }

    /**
     * The JVM reports a shell killed by signal N as 128 + N; Go reports -1 (killed by a signal).
     * Map 129..159 to -1 so every port prints the same code. Known limit: a command that itself
     * exits 129..159 also reads as -1.
     */
    static int signalCode(int code) {
        return code > 128 && code < 160 ? -1 : code;
    }
}
