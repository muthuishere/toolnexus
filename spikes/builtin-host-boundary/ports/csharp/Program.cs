// Port-feasibility probe (csharp) for ADR 0034's four obligations.
// Every assertion has a CONTROL arm: the same measurement with the fix absent,
// or with the mechanism deliberately broken. Run with:  bash run.sh
//
// No third-party dependency (BCL only), no network, no LLM key.
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

static class Probe
{
    static string Tmp = "";

    static void H(string s) => Console.WriteLine("\n== " + s);
    static void KV(string k, object? v) => Console.WriteLine("  " + k + "=" + v);

    static int Main(string[] argv)
    {
        // Resolve the temp root through its own links up front (macOS /var -> /private/var),
        // so the CONTROL arms below are not decided by that symlink.
        Tmp = new DirectoryInfo(Directory.CreateTempSubdirectory("tn-cs-probe").FullName)
            .ResolveLinkTarget(true)?.FullName ?? Directory.CreateTempSubdirectory("tn-cs-probe").FullName;
        Tmp = Canonical(Tmp);
        Directory.CreateDirectory(Tmp);
        var only = argv.Length > 0 ? argv[0] : "all";
        if (only is "all" or "o1") O1Shell();
        if (only is "all" or "o2") O2BaseDir();
        if (only is "all" or "o3") O3Confinement();
        if (only is "all" or "o4") O4Kill();
        Console.WriteLine("\nTMPDIR=" + Tmp);
        return 0;
    }

    // ---------------------------------------------------------------- O1 SHELL
    static void O1Shell()
    {
        H("O1 SHELL — argv prefix used verbatim; bare-name PATH resolution; failure shape");

        // (a) does ProcessStartInfo resolve a BARE program name on PATH, with
        //     UseShellExecute=false (which the port must keep, for redirection)?
        KV("bare_name_sh", Run(new[] { "sh", "-c", "echo bare-ok" }));
        // CONTROL: the absolute path both ports hardcode today.
        KV("control_abs_bin_sh", Run(new[] { "/bin/sh", "-c", "echo abs-ok" }));

        // (b) what does it THROW when the name does not resolve? This exception is
        //     what "construction fails naming the candidates" must be built from.
        foreach (var cand in new[] { "pwsh", "powershell", "definitely-not-a-shell-xyz" })
        {
            try
            {
                var psi = new ProcessStartInfo { FileName = cand, UseShellExecute = false };
                psi.ArgumentList.Add("-c"); psi.ArgumentList.Add("echo x");
                using var p = Process.Start(psi)!;
                p.WaitForExit();
                KV("resolve_" + cand, $"RESOLVED exit={p.ExitCode}");
            }
            catch (Exception e)
            {
                KV("resolve_" + cand, $"THROWS {e.GetType().FullName} " +
                    (e is Win32Exception w ? $"NativeErrorCode={w.NativeErrorCode} " : "") +
                    $"msg=\"{e.Message}\"");
            }
        }
        // is a non-executable file distinguishable from a missing one?
        var notExec = Path.Combine(Tmp, "not-executable");
        File.WriteAllText(notExec, "#!/bin/sh\necho nope\n");
        try
        {
            using var p = Process.Start(new ProcessStartInfo { FileName = notExec, UseShellExecute = false })!;
            p.WaitForExit();
            KV("non_executable_file", "RESOLVED (unexpected)");
        }
        catch (Exception e)
        {
            KV("non_executable_file", $"THROWS {e.GetType().FullName} " +
                (e is Win32Exception w2 ? $"NativeErrorCode={w2.NativeErrorCode} " : "") + $"msg=\"{e.Message}\"");
        }

        // is there a lookup WITHOUT starting a process? (construction-time detection
        // wants to name candidates, not spawn them)
        KV("probe_without_spawn_hint", "no BCL API; PATH is walked by hand — " +
            WhichLike("sh") + " | " + WhichLike("pwsh"));

        // (c) a host-set prefix is used VERBATIM
        const string bashism = "[[ -d . ]] && echo bashism-ok";
        KV("prefix_bash_lc", Run(new[] { "bash", "-lc", bashism }));
        KV("prefix_sh_c", Run(new[] { "sh", "-c", bashism }));
        // CONTROL: dash is the Debian /bin/sh
        KV("prefix_dash_c", Run(new[] { "dash", "-c", bashism }));
        // a 4-element prefix (the Windows `cmd /d /s /c` shape) works structurally
        KV("prefix_4elem_env_shape", Run(new[] { "env", "FOO=1", "sh", "-c", "echo $FOO" }));
    }

    static string WhichLike(string name)
    {
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            if (dir.Length == 0) continue;
            var c = Path.Combine(dir, name);
            if (File.Exists(c)) return $"{name}=>{c}";
        }
        return $"{name}=>MISSING";
    }

    static string Run(string[] argv)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = argv[0],
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            for (var i = 1; i < argv.Length; i++) psi.ArgumentList.Add(argv[i]);
            using var p = Process.Start(psi)!;
            var o = (p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd()).Trim().Replace("\n", "\\n");
            p.WaitForExit();
            return $"exit={p.ExitCode} out=\"{o}\"";
        }
        catch (Exception e) { return $"THROWS {e.GetType().Name} \"{e.Message}\""; }
    }

    // -------------------------------------------------------------- O2 BASEDIR
    static string Resolve(string baseDir, string p)
    {
        if (string.IsNullOrEmpty(baseDir)) return p;                 // today's behaviour
        return Path.IsPathRooted(p) ? p : Path.Combine(baseDir, p);
    }

    static void O2BaseDir()
    {
        H("O2 BASEDIR — relative resolution, empty base == today, apply_patch text, bash workdir, §4A separators");

        var bd = Path.Combine(Tmp, "base");
        Directory.CreateDirectory(Path.Combine(bd, "sub"));

        // (a) empty base is byte-identical to today's raw string
        const string rel = "tn-empty-base-probe.txt";
        var viaResolve = Resolve("", rel);
        KV("empty_base_same_string", (viaResolve == rel) + $" (\"{viaResolve}\")");
        KV("empty_base_same_fullpath", Path.GetFullPath(viaResolve) == Path.GetFullPath(rel));
        KV("empty_base_is_process_cwd",
            Path.GetDirectoryName(Path.GetFullPath(viaResolve)) == Directory.GetCurrentDirectory());
        // CONTROL: a non-empty base must NOT equal today's path
        KV("control_nonempty_base_differs",
            (Path.GetFullPath(Resolve(bd, rel)) != Path.GetFullPath(rel)) + $" (\"{Resolve(bd, rel)}\")");

        // (b) relative write lands under the base; nothing lands in the cwd
        var target = Resolve(bd, "sub/x.txt");
        File.WriteAllText(target, "x");
        KV("relative_write_landed_under_base", Path.GetFullPath(target).StartsWith(bd));
        KV("nothing_in_cwd", !File.Exists("sub/x.txt"));
        var abs = Path.Combine(Tmp, "abs.txt");
        KV("absolute_unaffected", Resolve(bd, abs) == abs);
        // Path.Combine's trap, measured: a rooted second argument SILENTLY discards the base
        KV("Path.Combine_discards_rooted_second_arg", "\"" + Path.Combine(bd, "/etc/passwd") + "\"" +
            "  <- why IsPathRooted must be checked explicitly, not left to Combine");

        // (c) apply_patch: the paths are CONTENT, not arguments
        var patch = "*** Begin Patch\n*** Add File: sub/added.txt\n+hello\n*** End Patch\n";
        var added = patch.Split('\n').Where(l => l.StartsWith("*** Add File: "))
            .Select(l => l["*** Add File: ".Length..].Trim()).ToList();
        var patchTarget = Resolve(bd, added[0]);
        File.WriteAllText(patchTarget, "hello");
        KV("patch_text_path_landed_under_base", Path.GetFullPath(patchTarget).StartsWith(bd) + $" ({added[0]})");
        KV("control_patch_unresolved_target", Path.GetFullPath(added[0]));

        // (d) bash workdir defaults to the base; explicit workdir wins; unset == cwd
        KV("bash_workdir_default_base", Pwd(bd));
        var other = Path.Combine(Tmp, "other"); Directory.CreateDirectory(other);
        KV("bash_workdir_explicit_wins", Pwd(other));
        KV("control_bash_workdir_unset", Pwd(null));

        // (e) §4A `/`-separated relative listing paths — does the base change them?
        var file = Path.Combine(bd, "sub", "x.txt");
        KV("dir_sep_char", "\"" + Path.DirectorySeparatorChar + "\"");
        KV("rel_emitted_with_base_root",
            "\"" + Path.GetRelativePath(bd, file).Replace('\\', '/') + "\"");
        KV("rel_emitted_with_relative_root_dot",
            "\"" + Path.GetRelativePath(".", file).Replace('\\', '/') + "\"  <- GetRelativePath resolves '.' against the cwd");
        KV("control_replace_on_backslash",
            "\"" + "sub\\x.txt".Replace('\\', '/') + "\" (POSIX separator is '/', so the Replace is a NO-OP here; "
            + "UNVERIFIED on Windows)");
    }

    static string Pwd(string? dir)
    {
        var psi = new ProcessStartInfo { FileName = "sh", UseShellExecute = false, RedirectStandardOutput = true };
        psi.ArgumentList.Add("-c"); psi.ArgumentList.Add("pwd");
        if (dir != null) psi.WorkingDirectory = dir;
        using var p = Process.Start(psi)!;
        var o = p.StandardOutput.ReadToEnd().Trim();
        p.WaitForExit();
        return o;
    }

    // ---------------------------------------------------------- O3 CONFINEMENT
    /// canonicalise: walk to the deepest EXISTING ancestor, resolve links there, re-attach the tail.
    static string Canonical(string p)
    {
        var abs = Path.GetFullPath(p);
        var tail = new List<string>();
        var probe = abs;
        while (true)
        {
            if (Directory.Exists(probe) || File.Exists(probe))
            {
                var real = RealPath(probe);
                for (var i = tail.Count - 1; i >= 0; i--) real = Path.Combine(real, tail[i]);
                return Path.GetFullPath(real);
            }
            var parent = Path.GetDirectoryName(probe);
            if (string.IsNullOrEmpty(parent) || parent == probe) return abs;
            tail.Add(Path.GetFileName(probe));
            probe = parent;
        }
    }

    /// Fully resolve an EXISTING path, one component at a time — because
    /// ResolveLinkTarget only looks at the FINAL segment (measured below).
    static string RealPath(string existing)
    {
        var cur = Path.GetPathRoot(Path.GetFullPath(existing)) ?? "/";
        var parts = Path.GetFullPath(existing)[cur.Length..]
            .Split(Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries);
        foreach (var part in parts)
        {
            cur = Path.Combine(cur, part);
            for (var hops = 0; hops < 40; hops++)
            {
                FileSystemInfo? t = null;
                try
                {
                    t = Directory.Exists(cur)
                        ? new DirectoryInfo(cur).ResolveLinkTarget(false)
                        : new FileInfo(cur).ResolveLinkTarget(false);
                }
                catch { }
                if (t == null) break;
                cur = Path.IsPathRooted(t.FullName)
                    ? t.FullName
                    : Path.GetFullPath(Path.Combine(Path.GetDirectoryName(cur)!, t.FullName));
            }
        }
        return cur;
    }

    static bool Contained(string bd, string p)
    {
        var cb = Canonical(bd);
        var cp = Canonical(Resolve(bd, p.Length == 0 ? "." : p));
        var cmp = OperatingSystem.IsWindows() || OperatingSystem.IsMacOS()
            ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        if (string.Equals(cb, cp, cmp)) return true;
        var rel = Path.GetRelativePath(cb, cp);
        return !Path.IsPathRooted(rel) && rel != ".." && !rel.StartsWith(".." + Path.DirectorySeparatorChar);
    }

    static void O3Confinement()
    {
        H("O3 CONFINEMENT — canonicalise both sides, refuse escapes; what GetFullPath/ResolveLinkTarget do");

        var bd = Path.Combine(Tmp, "c-base");
        var outside = Path.Combine(Tmp, "c-outside");
        Directory.CreateDirectory(Path.Combine(bd, "sub"));
        Directory.CreateDirectory(outside);
        File.WriteAllText(Path.Combine(bd, "sub", "in.txt"), "in");
        File.WriteAllText(Path.Combine(outside, "secret.txt"), "secret");

        // (d-part) what the APIs do and throw
        KV("GetFullPath_is_purely_lexical",
            "\"" + Path.GetFullPath(Path.Combine(bd, "sub", "..", "..", "c-outside", "secret.txt")) + "\"");
        var link = Path.Combine(bd, "link");
        Directory.CreateSymbolicLink(link, outside);
        KV("GetFullPath_through_symlink_does_NOT_resolve",
            "\"" + Path.GetFullPath(Path.Combine(link, "secret.txt")) + "\"  <- still shows .../c-base/link/...");
        KV("ResolveLinkTarget_on_the_link_itself",
            new DirectoryInfo(link).ResolveLinkTarget(true)?.FullName ?? "null");
        // THE TRAP: ResolveLinkTarget only inspects the FINAL segment.
        KV("ResolveLinkTarget_on_path_THROUGH_a_link",
            (new FileInfo(Path.Combine(link, "secret.txt")).ResolveLinkTarget(true)?.FullName ?? "null")
            + "  <- null: the final segment is not itself a link, so a per-component walk is required");
        try
        {
            var r = new FileInfo(Path.Combine(bd, "sub", "missing.txt")).ResolveLinkTarget(true);
            KV("ResolveLinkTarget_on_missing_path", r?.FullName ?? "null (no throw)");
        }
        catch (Exception e) { KV("ResolveLinkTarget_on_missing_path", "THROWS " + e.GetType().FullName); }
        try
        {
            var r = new DirectoryInfo(Path.Combine(bd, "sub")).ResolveLinkTarget(true);
            KV("ResolveLinkTarget_on_non_link", r?.FullName ?? "null (a non-link is indistinguishable from a broken probe)");
        }
        catch (Exception e) { KV("ResolveLinkTarget_on_non_link", "THROWS " + e.GetType().FullName); }

        // (a) symlink out of the base
        KV("canonical_through_symlink", Canonical(Path.Combine(link, "secret.txt")));
        KV("a_symlink_escape_contained", Contained(bd, "link/secret.txt") + "  <- must be false");
        // CONTROLS
        KV("control_real_inside_contained", Contained(bd, "sub/in.txt") + "  <- must be true");
        KV("control_lexical_would_say_contained",
            Path.GetFullPath(Path.Combine(link, "secret.txt")).StartsWith(bd) + "  <- the bug if you skip canonicalisation");

        // (b) a path that does not exist yet (the `write` case)
        KV("b_new_file_in_base_contained", Contained(bd, "sub/brand-new.txt") + "  <- must be true");
        KV("b_new_file_in_new_dirs_contained", Contained(bd, "a/b/c/brand-new.txt") + "  <- must be true");
        KV("b_new_file_escape_contained", Contained(bd, "../c-outside/brand-new.txt") + "  <- must be false");
        KV("b_new_file_through_symlink_contained", Contained(bd, "link/brand-new.txt") + "  <- must be false");
        // CONTROL: the naive canonicaliser — ResolveLinkTarget on the whole path only
        KV("control_naive_resolvelinktarget_says_contained",
            NaiveContained(bd, "link/brand-new.txt") + "  <- naive says contained: the inverted check");

        // (c) case normalisation
        var upper = bd.ToUpperInvariant();
        KV("fs_case_insensitive_lookup", Directory.Exists(upper));
        KV("GetFullPath_case_folds", "\"" + Path.GetFullPath(upper) + "\"  <- GetFullPath does NOT case-fold");
        KV("ResolveLinkTarget_case_folds", Directory.Exists(upper)
            ? (new DirectoryInfo(upper).ResolveLinkTarget(true)?.FullName ?? "null (not a link => no case folding either)")
            : "n/a");
        KV("c_upper_case_path_contained_with_OrdinalIgnoreCase",
            Contained(bd, Path.Combine(upper, "sub", "in.txt")) + "  <- must be true");
        KV("control_ordinal_compare_would_say",
            string.Equals(Canonical(bd), Canonical(upper), StringComparison.Ordinal)
            + "  <- Ordinal on a case-insensitive fs: two spellings of one directory disagree");

        // string-prefix vs path relation: the sibling false positive
        var pfx = Path.Combine(Tmp, "pfx"); Directory.CreateDirectory(pfx);
        var evil = Path.Combine(Tmp, "pfx-evil"); Directory.CreateDirectory(evil);
        File.WriteAllText(Path.Combine(evil, "f.txt"), "x");
        KV("string_prefix_says_contained",
            Path.Combine(evil, "f.txt").StartsWith(pfx) + "  <- string prefix is WRONG");
        KV("GetRelativePath_says_contained", Contained(pfx, Path.Combine(evil, "f.txt")) + "  <- right");

        KV("WINDOWS_junction", "UNMEASURED on this host (no .NET SDK on the Windows box)");
    }

    static bool NaiveContained(string bd, string p)
    {
        var abs = Path.GetFullPath(Resolve(bd, p));
        string canon;
        try { canon = new FileInfo(abs).ResolveLinkTarget(true)?.FullName ?? abs; }
        catch (FileNotFoundException) { canon = abs; }   // <- measured: it THROWS on the write case,
                                                         //    and falling back to the lexical path inverts the check
        return canon.StartsWith(Path.GetFullPath(bd));
    }

    // ------------------------------------------------------------- O4 KILL JOB
    [DllImport("libc", SetLastError = true)] static extern int kill(int pid, int sig);

    static void O4Kill()
    {
        H("O4 KILL THE JOB — orphan marker (control/naive/tree) and TERM -> grace -> KILL");

        foreach (var mode in new[] { "control", "naive", "tree" }) Orphan(mode);

        // What signal does Kill() actually send on POSIX? Measured with a shell trap.
        SignalOf("Kill_false");
        SignalOf("Kill_true");
        SignalOf("CloseMainWindow");
        SignalOf("libc_SIGTERM");

        // The full sequence, in the only idiom that can express it on POSIX:
        // P/Invoke kill(pid, SIGTERM) -> 2000 ms grace -> Kill(entireProcessTree: true).
        Grace(true);
        Grace(false);

        // Does a graceful TERM reach the whole JOB, or only the direct child?
        // Kill(entireProcessTree:true) is forceful-only, so "TERM the job" has to be
        // built some other way. Three arms, each with the orphan marker.
        GraceTree("kill_child_only");     // kill(pid, SIGTERM) -> only the shell hears it
        GraceTree("pgroup_setm");         // `set -m` wrapper: the job is its own process group
        GraceTree("control_no_kill");     // nothing killed at all
    }

    static Process StartOrphanCmd(string marker)
    {
        // The leading `sleep 0.2` stops the outer shell exec'ing the single command,
        // so there really is a grandchild to orphan.
        var psi = new ProcessStartInfo { FileName = "sh", UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
        psi.ArgumentList.Add("-c");
        psi.ArgumentList.Add($"sleep 0.2; sh -c 'sleep 1; touch {marker}'");
        return Process.Start(psi)!;
    }

    static void Orphan(string mode)
    {
        var marker = Path.Combine(Tmp, $"orphan-{mode}.marker");
        if (File.Exists(marker)) File.Delete(marker);
        var p = StartOrphanCmd(marker);
        if (!p.WaitForExit(300))
        {
            switch (mode)
            {
                case "control": break;                       // no kill at all: the marker MUST appear
                case "naive": p.Kill(); break;               // today's code (BuiltinTools.cs:273)
                case "tree": p.Kill(entireProcessTree: true); break;
            }
        }
        Thread.Sleep(1800);
        KV("orphan_" + mode, File.Exists(marker) ? "ORPHAN_SURVIVED" : "killed_whole_job");
        if (mode == "control") { try { p.Kill(true); } catch { } }

        if (mode == "tree")
        {
            // the reparenting trap: is the tree still reachable once the parent is dead?
            var m2 = Path.Combine(Tmp, "reparent.marker");
            if (File.Exists(m2)) File.Delete(m2);
            var q = StartOrphanCmd(m2);
            q.WaitForExit(400);
            var pid = q.Id;
            q.Kill();                 // parent only
            q.WaitForExit();
            var still = Run(new[] { "sh", "-c", $"pgrep -P {pid} | wc -l" });
            KV("REACHABLE_AFTER_KILL_children_of_dead_parent", still + "  <- a post-hoc walk finds nothing");
            Thread.Sleep(1500);
            KV("reparent_marker_written", File.Exists(m2) + "  <- the work carried on");
            try { Run(new[] { "sh", "-c", "true" }); } catch { }
        }
    }

    static void SignalOf(string how)
    {
        var log = Path.Combine(Tmp, $"term-{how}.log");
        if (File.Exists(log)) File.Delete(log);
        var psi = new ProcessStartInfo { FileName = "sh", UseShellExecute = false, RedirectStandardOutput = true };
        psi.ArgumentList.Add("-c");
        psi.ArgumentList.Add($"trap 'echo GOT_TERM >> {log}; exit 0' TERM; sleep 5 & wait");
        using var p = Process.Start(psi)!;
        Thread.Sleep(300);
        string note = "";
        switch (how)
        {
            case "Kill_false": p.Kill(); break;
            case "Kill_true": p.Kill(entireProcessTree: true); break;
            case "CloseMainWindow":
                try { note = " CloseMainWindow_returned=" + p.CloseMainWindow(); }
                catch (Exception e) { note = " THROWS " + e.GetType().FullName; }
                break;
            case "libc_SIGTERM": note = " kill(2)_rc=" + kill(p.Id, 15); break;
        }
        var exited = p.WaitForExit(2000);
        Thread.Sleep(200);
        var got = File.Exists(log) ? File.ReadAllText(log).Trim() : "(nothing)";
        var exit = exited ? p.ExitCode.ToString() : "STILL_RUNNING";
        if (!exited) { try { p.Kill(true); p.WaitForExit(); } catch { } }
        KV("signal_of_" + how, $"trap_saw={got} exit={exit}{note}  (128+15=143 => TERM, 128+9=137 => KILL)");
    }

    static void GraceTree(string mode)
    {
        var marker = Path.Combine(Tmp, $"gtree-{mode}.marker");
        if (File.Exists(marker)) File.Delete(marker);
        var pgFile = Path.Combine(Tmp, $"gtree-{mode}.pgid");
        if (File.Exists(pgFile)) File.Delete(pgFile);
        var inner = $"sleep 0.2; sh -c 'sleep 1; touch {marker}'";
        var script = mode == "pgroup_setm"
            // `set -m` puts the backgrounded compound command in its OWN process group
            // whose pgid == its pid, and that pid is written out BEFORE anything is killed.
            ? $"set -m; {{ {inner}; }} & echo $! > {pgFile}; wait $!"
            : inner;
        var psi = new ProcessStartInfo { FileName = "sh", UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
        psi.ArgumentList.Add("-c"); psi.ArgumentList.Add(script);
        using var p = Process.Start(psi)!;
        Thread.Sleep(400);
        var note = "";
        if (mode == "kill_child_only")
        {
            note = " kill(pid,TERM)_rc=" + kill(p.Id, 15);
            if (!p.WaitForExit(2000)) p.Kill(entireProcessTree: true);
        }
        else if (mode == "pgroup_setm")
        {
            var pgid = int.Parse(File.ReadAllText(pgFile).Trim());
            note = $" pgid={pgid} kill(-pgid,TERM)_rc=" + kill(-pgid, 15);
            if (!p.WaitForExit(2000)) { kill(-pgid, 9); p.Kill(entireProcessTree: true); }
        }
        p.WaitForExit(2000);
        Thread.Sleep(1600);
        KV("gracetree_" + mode, (File.Exists(marker) ? "ORPHAN_SURVIVED" : "killed_whole_job") + note);
        if (mode == "control_no_kill") { try { p.Kill(true); } catch { } }
    }

    static void Grace(bool childIgnoresTerm)
    {
        var cmd = childIgnoresTerm ? "trap '' TERM; sleep 5" : "trap 'exit 0' TERM; sleep 5 & wait";
        var psi = new ProcessStartInfo { FileName = "sh", UseShellExecute = false, RedirectStandardOutput = true };
        psi.ArgumentList.Add("-c"); psi.ArgumentList.Add(cmd);
        using var p = Process.Start(psi)!;
        Thread.Sleep(200);
        var sw = Stopwatch.StartNew();
        kill(p.Id, 15);                                  // SIGTERM — no BCL API for this
        var exitedInGrace = p.WaitForExit(2000);
        if (!exitedInGrace) { p.Kill(entireProcessTree: true); p.WaitForExit(); }
        sw.Stop();
        KV("grace_child_" + (childIgnoresTerm ? "ignores_TERM" : "honours_TERM"),
            $"exited_in_grace={exitedInGrace} elapsed_ms={sw.ElapsedMilliseconds} exit={p.ExitCode}");
    }
}
