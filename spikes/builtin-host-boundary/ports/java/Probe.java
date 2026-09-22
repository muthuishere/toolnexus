// Port-feasibility probe (java) for ADR 0034's four obligations.
// Every assertion has a CONTROL arm: the same measurement with the fix absent,
// or with the mechanism deliberately broken, so a passing "fixed" arm means
// something. Run with:  bash run.sh
//
// No third-party dependency, no network, no LLM key. JDK only.
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Probe {
    static Path tmp;

    public static void main(String[] args) throws Exception {
        tmp = Files.createTempDirectory("tn-java-probe").toRealPath();  // toRealPath: macOS /var -> /private/var, so the CONTROL arms below are not decided by that symlink
        String only = args.length > 0 ? args[0] : "all";
        if (only.equals("all") || only.equals("o1")) o1Shell();
        if (only.equals("all") || only.equals("o2")) o2BaseDir();
        if (only.equals("all") || only.equals("o3")) o3Confinement();
        if (only.equals("all") || only.equals("o4")) o4Kill();
        System.out.println("\nTMPDIR=" + tmp);
    }

    static void h(String s) { System.out.println("\n== " + s); }
    static void kv(String k, Object v) { System.out.println("  " + k + "=" + v); }

    // ---------------------------------------------------------------- O1 SHELL
    static void o1Shell() throws Exception {
        h("O1 SHELL — argv prefix used verbatim; bare-name PATH resolution; failure shape");

        // (a) does ProcessBuilder resolve a BARE program name on PATH?
        //     Today both ports hardcode "/bin/sh"; "sh" is the portable spelling.
        kv("bare_name_sh", run(List.of("sh", "-c", "echo bare-ok")));
        // CONTROL: absolute path, the thing the ports do today. If this differs,
        // the bare-name result is not about PATH at all.
        kv("control_abs_bin_sh", run(List.of("/bin/sh", "-c", "echo abs-ok")));

        // (b) what does it THROW when the name does not resolve? This exception is
        //     what "construction fails naming the candidates" must be built from.
        for (String cand : List.of("pwsh", "powershell", "definitely-not-a-shell-xyz")) {
            try {
                Process p = new ProcessBuilder(cand, "-c", "echo x").start();
                p.waitFor();
                kv("resolve_" + cand, "RESOLVED exit=" + p.exitValue());
            } catch (IOException e) {
                kv("resolve_" + cand, "THROWS " + e.getClass().getName() + " msg=\"" + e.getMessage() + "\"");
            }
        }
        // Is the failure distinguishable from a permission error / other IOException?
        Path notExec = tmp.resolve("not-executable");
        Files.writeString(notExec, "#!/bin/sh\necho nope\n");
        try {
            new ProcessBuilder(notExec.toString(), "-c", "x").start();
            kv("non_executable_file", "RESOLVED (unexpected)");
        } catch (IOException e) {
            kv("non_executable_file", "THROWS " + e.getClass().getName() + " msg=\"" + e.getMessage() + "\"");
        }

        // (c) a host-set prefix is used VERBATIM — measured by a bashism that only
        //     one of the two candidate interpreters accepts.
        String bashism = "[[ -d . ]] && echo bashism-ok";
        kv("prefix_bash_lc", run(concat(List.of("bash", "-lc"), bashism)));
        kv("prefix_sh_c", run(concat(List.of("sh", "-c"), bashism)));
        // CONTROL: dash is the Debian /bin/sh. If sh -c above had silently been
        // bash, this arm shows what a real POSIX-only sh does with the same input.
        kv("prefix_dash_c", run(concat(List.of("dash", "-c"), bashism)));
        // a 4-element prefix (the Windows cmd shape) still works structurally
        kv("prefix_4elem_env_shape", run(List.of("env", "FOO=1", "sh", "-c", "echo $FOO")));
    }

    static List<String> concat(List<String> prefix, String cmd) {
        List<String> l = new ArrayList<>(prefix); l.add(cmd); return l;
    }

    static String run(List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim().replace("\n", "\\n");
            p.waitFor();
            return "exit=" + p.exitValue() + " out=\"" + out + "\"";
        } catch (Exception e) {
            return "THROWS " + e.getClass().getSimpleName() + " \"" + e.getMessage() + "\"";
        }
    }

    // -------------------------------------------------------------- O2 BASEDIR
    // The candidate resolver that would land in the port.
    static Path resolve(String baseDir, String p) {
        if (baseDir == null || baseDir.isEmpty()) return Path.of(p);       // today's behaviour
        Path pp = Path.of(p);
        return pp.isAbsolute() ? pp : Path.of(baseDir).resolve(pp);
    }

    static void o2BaseDir() throws Exception {
        h("O2 BASEDIR — relative resolution, empty base == today, apply_patch text, bash workdir, §4A separators");

        Path base = Files.createDirectories(tmp.resolve("base"));
        Files.createDirectories(base.resolve("sub"));

        // (a) empty base is byte-identical to today's `Path.of(p)`.
        //     Measured as the STRING the port would hand the filesystem, plus a
        //     real write that must land in the process cwd exactly as today.
        String rel = "tn-empty-base-probe.txt";
        Path today = Path.of(rel);                 // literally what BuiltinTools.java does
        Path viaResolve = resolve("", rel);
        kv("empty_base_same_string", today.toString().equals(viaResolve.toString()) + " (\"" + viaResolve + "\")");
        kv("empty_base_same_absolute", today.toAbsolutePath().equals(viaResolve.toAbsolutePath()));
        kv("empty_base_is_process_cwd", viaResolve.toAbsolutePath().getParent()
                .equals(Path.of(System.getProperty("user.dir"))));
        // CONTROL: a non-empty base must NOT equal today's path, else (a) proves nothing.
        kv("control_nonempty_base_differs", !resolve(base.toString(), rel).toAbsolutePath()
                .equals(today.toAbsolutePath()) + " (\"" + resolve(base.toString(), rel) + "\")");

        // (b) relative write lands under the base; nothing lands in the cwd
        Path target = resolve(base.toString(), "sub/x.txt");
        Files.writeString(target, "x");
        kv("relative_write_landed_under_base", target.toAbsolutePath().startsWith(base));
        kv("nothing_in_cwd", !Files.exists(Path.of("sub/x.txt")));
        // absolute path unaffected by the base
        Path abs = tmp.resolve("abs.txt").toAbsolutePath();
        kv("absolute_unaffected", resolve(base.toString(), abs.toString()).equals(abs));

        // (c) apply_patch: the paths are CONTENT, not arguments. Parse + resolve.
        String patch = """
                *** Begin Patch
                *** Add File: sub/added.txt
                +hello
                *** End Patch
                """;
        List<String> added = new ArrayList<>();
        for (String line : patch.split("\n")) {
            if (line.startsWith("*** Add File: ")) added.add(line.substring(14).trim());
        }
        Path patchTarget = resolve(base.toString(), added.get(0));
        Files.writeString(patchTarget, "hello");
        kv("patch_text_path_landed_under_base", patchTarget.toAbsolutePath().startsWith(base) + " (" + added + ")");
        // CONTROL: the unresolved path would have landed in the process cwd
        kv("control_patch_unresolved_target", Path.of(added.get(0)).toAbsolutePath());

        // (d) bash workdir defaults to the base; an explicit workdir still wins
        kv("bash_workdir_default_base", pwdWith(base.toFile()));
        Path other = Files.createDirectories(tmp.resolve("other"));
        kv("bash_workdir_explicit_wins", pwdWith(other.toFile()));
        // CONTROL: no directory() set -> process cwd, i.e. today's behaviour
        kv("control_bash_workdir_unset", pwdWith(null));

        // (e) §4A: `/`-separated relative listing paths. Does a base change them?
        Path root = base;
        Path file = base.resolve("sub").resolve("x.txt");
        String relEmitted = root.toAbsolutePath().relativize(file.toAbsolutePath()).toString()
                .replace(File.separatorChar, '/');
        kv("sep_char", "\"" + File.separator + "\"");
        kv("rel_emitted_with_base_root", "\"" + relEmitted + "\"");
        // the same relativize with the walk root passed as a RELATIVE path (today's
        // `Path.of(".")` default) — the emitted string must not change
        String relFromDot = Path.of(".").toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath()).toString().replace(File.separatorChar, '/');
        kv("rel_emitted_from_dot_root_is_cwd_relative", "\"" + relFromDot + "\"");
        kv("base_changes_emitted_separator", false + " (separator is a platform fact, not a base fact)");
        // CONTROL that the replace() is doing work at all: a synthetic backslash path
        kv("control_replace_on_backslash",
                "\"" + "sub\\x.txt".replace('\\', '/') + "\" (POSIX File.separator is '/', so the "
                + "replace is a NO-OP here and this obligation is UNVERIFIED on Windows)");
    }

    static String pwdWith(File dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "pwd").redirectErrorStream(true);
            if (dir != null) pb.directory(dir);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out;
        } catch (Exception e) { return "ERR " + e; }
    }

    // ---------------------------------------------------------- O3 CONFINEMENT
    /** canonicalise: resolve symlinks on the deepest EXISTING ancestor, re-attach the tail. */
    static Path canonical(Path p) {
        Path abs = p.toAbsolutePath();
        Path probe = abs;
        List<String> tail = new ArrayList<>();
        while (true) {
            try {
                Path real = probe.toRealPath();
                for (int i = tail.size() - 1; i >= 0; i--) real = real.resolve(tail.get(i));
                return real.normalize();
            } catch (IOException e) {
                Path parent = probe.getParent();
                if (parent == null) return abs.normalize();      // nothing exists: lexical
                tail.add(probe.getFileName().toString());
                probe = parent;
            }
        }
    }

    static boolean contained(Path base, String p) {
        Path cb = canonical(base);
        Path cp = canonical(p.isEmpty() ? base : resolve(base.toString(), p));
        return cp.equals(cb) || cp.startsWith(cb);
    }

    static void o3Confinement() throws Exception {
        h("O3 CONFINEMENT — canonicalise both sides, refuse escapes; what toRealPath does and throws");

        Path base = Files.createDirectories(tmp.resolve("c-base"));
        Path outside = Files.createDirectories(tmp.resolve("c-outside"));
        Files.createDirectories(base.resolve("sub"));
        Files.writeString(base.resolve("sub/in.txt"), "in");
        Files.writeString(outside.resolve("secret.txt"), "secret");

        // (d-part) what toRealPath does and THROWS
        try { kv("toRealPath_existing", base.resolve("sub/in.txt").toRealPath()); }
        catch (IOException e) { kv("toRealPath_existing", "THROWS " + e); }
        try {
            Path r = base.resolve("sub/does-not-exist.txt").toRealPath();
            kv("toRealPath_missing", "RETURNED " + r);
        } catch (IOException e) {
            kv("toRealPath_missing", "THROWS " + e.getClass().getName() + " msg=\"" + e.getMessage() + "\"");
        }
        try {
            Path r = base.resolve("sub/does-not-exist.txt").toRealPath(LinkOption.NOFOLLOW_LINKS);
            kv("toRealPath_missing_NOFOLLOW", "RETURNED " + r);
        } catch (IOException e) {
            kv("toRealPath_missing_NOFOLLOW", "THROWS " + e.getClass().getSimpleName());
        }

        // (a) symlink out of the base
        Path link = base.resolve("link");
        Files.createSymbolicLink(link, outside);
        kv("symlink_target", Files.readSymbolicLink(link));
        kv("canonical_through_symlink", canonical(base.resolve("link/secret.txt")));
        kv("a_symlink_escape_contained", contained(base, "link/secret.txt") + "  <- must be false");
        // CONTROL: the same shape WITHOUT the symlink must be contained, else the
        // probe is just refusing everything.
        kv("control_real_inside_contained", contained(base, "sub/in.txt") + "  <- must be true");
        // CONTROL 2: canonicalisation is what decides it — the LEXICAL answer inverts
        kv("control_lexical_would_say_contained",
                base.resolve("link/secret.txt").normalize().startsWith(base) + "  <- the bug if you skip canonicalisation");

        // (b) a path that does not exist yet (the `write` case)
        kv("b_new_file_in_base_contained", contained(base, "sub/brand-new.txt") + "  <- must be true");
        kv("b_new_file_in_new_dirs_contained", contained(base, "a/b/c/brand-new.txt") + "  <- must be true");
        kv("b_new_file_escape_contained", contained(base, "../c-outside/brand-new.txt") + "  <- must be false");
        kv("b_new_file_through_symlink_contained",
                contained(base, "link/brand-new.txt") + "  <- must be false (tail re-attached to the RESOLVED ancestor)");
        // CONTROL: the naive canonicaliser (toRealPath on the whole path, fall back to
        // lexical on failure) — the exact inversion ADR 0034 warns about.
        kv("control_naive_canon_new_file_escape", naiveContained(base, "link/brand-new.txt")
                + "  <- naive says contained; that is the inverted check");

        // (c) case normalisation
        String upper = base.toString().toUpperCase(Locale.ROOT);
        kv("fs_case_insensitive_lookup", Files.exists(Path.of(upper)));
        kv("toRealPath_case_folds", Files.exists(Path.of(upper))
                ? ("\"" + Path.of(upper).toRealPath() + "\"") : "n/a (case-sensitive fs)");
        Path upperFile = Path.of(base.toString().toUpperCase(Locale.ROOT)).resolve("SUB/IN.TXT");
        kv("c_upper_case_path_contained", tryContained(base, upperFile));
        kv("c_equals_is_case_sensitive_string_compare",
                Path.of("/tmp/A").equals(Path.of("/tmp/a")) + " (Path.equals on this fs)");

        // string-prefix vs path-relation: the sibling-prefix false positive
        Path bx = Files.createDirectories(tmp.resolve("pfx"));
        Path sibling = Files.createDirectories(tmp.resolve("pfx-evil"));
        Files.writeString(sibling.resolve("f.txt"), "x");
        kv("string_prefix_says_contained",
                sibling.resolve("f.txt").toString().startsWith(bx.toString()) + "  <- string prefix is WRONG");
        kv("startsWith_says_contained",
                canonical(sibling.resolve("f.txt")).startsWith(canonical(bx)) + "  <- Path.startsWith is right");

        // Windows: NOT MEASURABLE HERE
        kv("WINDOWS_junction", "UNMEASURED on this host (no JDK on the Windows box)");
    }

    static String tryContained(Path base, Path p) {
        try { return String.valueOf(canonical(p).startsWith(canonical(base))); }
        catch (Exception e) { return "THROWS " + e; }
    }

    static boolean naiveContained(Path base, String p) {
        Path abs = resolve(base.toString(), p).toAbsolutePath();
        Path canon;
        try { canon = abs.toRealPath(); } catch (IOException e) { canon = abs.normalize(); }
        Path cb;
        try { cb = base.toRealPath(); } catch (IOException e) { cb = base.toAbsolutePath().normalize(); }
        return canon.equals(cb) || canon.startsWith(cb);
    }

    // ------------------------------------------------------------- O4 KILL JOB
    static void o4Kill() throws Exception {
        h("O4 KILL THE JOB — orphan marker (control/naive/tree) and TERM -> grace -> KILL");

        for (String mode : List.of("control", "naive", "tree")) orphan(mode);

        // Does destroy() actually deliver SIGTERM, and destroyForcibly() SIGKILL?
        // Measured with a shell trap, not asserted from the javadoc.
        termTrap("destroy");
        termTrap("destroyForcibly");

        // The full sequence against a child that IGNORES TERM: does the grace
        // window work in this idiom?
        graceSequence(true);
        graceSequence(false);
    }

    static void orphan(String mode) throws Exception {
        Path marker = tmp.resolve("orphan-" + mode + ".marker");
        Files.deleteIfExists(marker);
        // The command shape matters: the leading `sleep 0.2` stops the outer shell
        // exec'ing the single command, so there really is a grandchild to orphan.
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "sleep 0.2; sh -c 'sleep 1; touch " + marker + "'");
        pb.redirectErrorStream(true).redirectOutput(new File("/dev/null"));
        Process p = pb.start();
        int descendants = -1;
        if (!p.waitFor(300, TimeUnit.MILLISECONDS)) {
            switch (mode) {
                case "control" -> { /* no kill at all: the marker MUST appear */ }
                case "naive" -> p.destroyForcibly();                 // today's code
                case "tree" -> {
                    List<ProcessHandle> kids = p.descendants().toList();   // BEFORE the kill
                    descendants = kids.size();
                    kids.forEach(ProcessHandle::destroy);
                    p.destroy();
                    if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) {
                        kids.forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                    }
                }
            }
        }
        Thread.sleep(1800);
        boolean survived = Files.exists(marker);
        kv("orphan_" + mode, (survived ? "ORPHAN_SURVIVED" : "killed_whole_job")
                + (descendants >= 0 ? " DESCENDANTS=" + descendants : ""));
        if (mode.equals("control")) p.destroyForcibly();

        // the reparenting trap, measured: after the parent dies, is the tree still
        // reachable from its pid?
        if (mode.equals("tree")) {
            Path m2 = tmp.resolve("reparent.marker");
            Files.deleteIfExists(m2);
            ProcessBuilder pb2 = new ProcessBuilder("sh", "-c",
                    "sleep 0.2; sh -c 'sleep 1; touch " + m2 + "'");
            pb2.redirectErrorStream(true).redirectOutput(new File("/dev/null"));
            Process q = pb2.start();
            q.waitFor(400, TimeUnit.MILLISECONDS);
            q.destroyForcibly(); q.waitFor();
            long after = q.toHandle().descendants().count();
            kv("REACHABLE_AFTER_KILL", after + "  <- why the snapshot must be taken first");
            Thread.sleep(1500);
            kv("reparent_marker_written", Files.exists(m2));
        }
    }

    static void termTrap(String how) throws Exception {
        Path log = tmp.resolve("term-" + how + ".log");
        Files.deleteIfExists(log);
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "trap 'echo GOT_TERM >> " + log + "; exit 0' TERM; sleep 5 & wait");
        pb.redirectErrorStream(true).redirectOutput(new File("/dev/null"));
        Process p = pb.start();
        Thread.sleep(300);
        if (how.equals("destroy")) p.destroy(); else p.destroyForcibly();
        boolean exited = p.waitFor(2, TimeUnit.SECONDS);
        Thread.sleep(200);
        String got = Files.exists(log) ? Files.readString(log).trim() : "(nothing)";
        String exit = exited ? String.valueOf(p.exitValue()) : "STILL_RUNNING";
        if (!exited) { p.destroyForcibly(); p.waitFor(); }
        kv("signal_of_" + how, "trap_saw=" + got + " exit=" + exit
                + "  (128+15=143 => TERM, 128+9=137 => KILL)");
    }

    static void graceSequence(boolean childIgnoresTerm) throws Exception {
        String cmd = childIgnoresTerm
                ? "trap '' TERM; sleep 5"
                : "trap 'exit 0' TERM; sleep 5 & wait";
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.redirectErrorStream(true).redirectOutput(new File("/dev/null"));
        Process p = pb.start();
        Thread.sleep(200);
        long t0 = System.nanoTime();
        p.destroy();
        boolean exitedInGrace = p.waitFor(2000, TimeUnit.MILLISECONDS);
        if (!exitedInGrace) { p.destroyForcibly(); p.waitFor(); }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        kv("grace_child_" + (childIgnoresTerm ? "ignores_TERM" : "honours_TERM"),
                "exited_in_grace=" + exitedInGrace + " elapsed_ms=" + ms + " exit=" + p.exitValue());
    }
}
