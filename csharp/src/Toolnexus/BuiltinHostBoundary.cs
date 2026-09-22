using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace Toolnexus;

/// <summary>
/// The host boundary the builtins are allowed to know about (SPEC §4A, ADR 0034):
/// which interpreter <c>bash</c> runs, which directory relative paths mean, and
/// whether paths leaving it are refused. Resolved once, at toolkit construction —
/// a missing interpreter is a configuration fact, and turn fourteen of a paid run
/// is the expensive place to learn it.
/// </summary>
internal sealed class BuiltinEnv
{
    /// <summary>
    /// How long a job gets between "please stop" and "stop". Fixed, and identical
    /// in every port, so a timeout means the same thing everywhere.
    /// </summary>
    internal const int KillGraceMs = 2000;

    private static readonly HashSet<string> WinReserved = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    };

    internal IReadOnlyList<string> Shell { get; }
    internal Exception? ShellError { get; }
    internal string BaseDir { get; }
    internal bool Confine { get; }

    internal BuiltinEnv(object? cfg)
    {
        var map = cfg as IDictionary<string, object?>;
        BaseDir = map is not null && map.TryGetValue("baseDir", out var b) && b is string bs ? bs : "";
        Confine = map is not null && map.TryGetValue("confineToBaseDir", out var c) && c is true;

        if (map is not null && map.TryGetValue("shell", out var given) && given is IEnumerable<object?> parts)
        {
            var argv = parts.Select(x => x?.ToString() ?? "").Where(x => x.Length > 0).ToList();
            if (argv.Count > 0)
            {
                Shell = argv;
                return;
            }
        }
        if (map is not null && map.TryGetValue("shell", out var givenStrings) && givenStrings is IEnumerable<string> ss)
        {
            var argv = ss.ToList();
            if (argv.Count > 0)
            {
                Shell = argv;
                return;
            }
        }

        var tried = new List<string>();
        foreach (var argv in ShellCandidates())
        {
            tried.Add(argv[0]);
            if (ResolvesOnPath(argv[0]))
            {
                Shell = argv;
                return;
            }
        }
        Shell = Array.Empty<string>();
        ShellError = new InvalidOperationException(
            $"no shell interpreter found (tried: {string.Join(", ", tried)}); set builtins.shell, "
            + "or disable the bash builtin with builtins.tools.bash = false");
    }

    /// <summary>
    /// The interpreters tried when the host names none. <c>%COMSPEC%</c> leads on
    /// Windows because PowerShell is routinely blocked by execution or
    /// application-control policy, while <c>%COMSPEC%</c> is always present.
    /// <c>/bin/sh</c> leads on POSIX because that is the binary this port has
    /// always run; a bare <c>sh</c> is a PATH lookup, not necessarily the same file.
    /// </summary>
    private static List<List<string>> ShellCandidates()
    {
        if (!OperatingSystem.IsWindows())
            return new() { new() { "/bin/sh", "-c" }, new() { "sh", "-c" } };

        var outList = new List<List<string>>();
        var comspec = Environment.GetEnvironmentVariable("COMSPEC");
        if (!string.IsNullOrEmpty(comspec)) outList.Add(new() { comspec, "/d", "/s", "/c" });
        outList.Add(new() { "cmd.exe", "/d", "/s", "/c" });
        outList.Add(new() { "pwsh", "-NoProfile", "-Command" });
        outList.Add(new() { "powershell", "-NoProfile", "-Command" });
        outList.Add(new() { "bash", "-lc" });
        return outList;
    }

    /// <summary>
    /// Does <paramref name="name"/> resolve as an executable? .NET has no
    /// <c>which</c>, and <c>Process.Start</c> reports a missing program as an
    /// untyped <c>Win32Exception</c>, so detection is a PATH walk rather than
    /// spawn-and-catch.
    /// </summary>
    private static bool ResolvesOnPath(string name)
    {
        if (Path.IsPathRooted(name) || name.Contains('/') || name.Contains('\\'))
            return File.Exists(name);

        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        var exts = OperatingSystem.IsWindows()
            ? (Environment.GetEnvironmentVariable("PATHEXT") ?? ".COM;.EXE;.BAT;.CMD").Split(';', StringSplitOptions.RemoveEmptyEntries)
            : new[] { "" };
        foreach (var dir in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            foreach (var ext in exts)
            {
                if (File.Exists(Path.Combine(dir, name + ext))) return true;
            }
        }
        return false;
    }

    internal IReadOnlyList<string> ShellArgv()
        => ShellError is not null ? throw ShellError : Shell;

    internal string ShellLabel => string.Join(" ", Shell);

    internal string Dir() => BaseDir.Length > 0 ? BaseDir : Directory.GetCurrentDirectory();

    /// <summary>
    /// Map a tool-supplied path onto the filesystem: relative to <c>BaseDir</c>
    /// (or, with none, exactly as before), and refused when confinement is on and
    /// the canonical target lies outside the base.
    /// </summary>
    internal string ResolvePath(string p)
    {
        if (Confine && BaseDir.Length == 0)
            throw new ArgumentException("confineToBaseDir is set but baseDir is empty");

        // Path.Combine SILENTLY DISCARDS the base when the right operand is
        // rooted, so the rooted case is an explicit branch rather than a
        // coincidence that happens to behave.
        var full = BaseDir.Length > 0 && !Path.IsPathRooted(p) ? Path.Combine(BaseDir, p) : p;
        if (!Confine) return full;

        if (OperatingSystem.IsWindows())
        {
            var stem = Path.GetFileName(full);
            var dot = stem.IndexOf('.');
            if (dot >= 0) stem = stem[..dot];
            if (WinReserved.Contains(stem.Trim()))
                throw new ArgumentException($"{p} names a reserved device, which is not a file inside {BaseDir}");
        }

        var baseCanon = Canonical(BaseDir);
        var targetCanon = Canonical(full);
        var comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        var prefix = baseCanon.EndsWith(Path.DirectorySeparatorChar) ? baseCanon : baseCanon + Path.DirectorySeparatorChar;
        if (!string.Equals(targetCanon, baseCanon, comparison) && !targetCanon.StartsWith(prefix, comparison))
            throw new ArgumentException($"{p} resolves outside baseDir {BaseDir}");
        return full;
    }

    /// <summary>
    /// Resolve a path for comparison. .NET has no <c>realpath</c>:
    /// <c>Path.GetFullPath</c> is purely lexical, and
    /// <c>FileSystemInfo.ResolveLinkTarget</c> inspects ONLY the final segment —
    /// measured, a link in the MIDDLE of a path returns null and a naive check
    /// then reports an escape as contained. So every component is walked, and the
    /// deepest EXISTING ancestor is resolved with the remaining segments
    /// re-attached, because a file <c>write</c> is about to create has no real
    /// path and a check that only works on existing files is not a check for
    /// <c>write</c>.
    /// </summary>
    private static string Canonical(string p)
    {
        var absolute = Path.GetFullPath(p);
        var tail = new List<string>();
        var cur = absolute;
        while (true)
        {
            if (Directory.Exists(cur) || File.Exists(cur))
            {
                var resolved = ResolveLinksFully(cur);
                for (var i = tail.Count - 1; i >= 0; i--) resolved = Path.Combine(resolved, tail[i]);
                return resolved.TrimEnd(Path.DirectorySeparatorChar);
            }
            var parent = Path.GetDirectoryName(cur);
            if (string.IsNullOrEmpty(parent) || parent == cur) return absolute.TrimEnd(Path.DirectorySeparatorChar);
            tail.Add(Path.GetFileName(cur));
            cur = parent;
        }
    }

    /// <summary>Follow links component by component, since ResolveLinkTarget only looks at the last one.</summary>
    private static string ResolveLinksFully(string existing)
    {
        var cur = existing;
        for (var hops = 0; hops < 40; hops++)
        {
            FileSystemInfo? info = Directory.Exists(cur) ? new DirectoryInfo(cur) : new FileInfo(cur);
            var target = info.ResolveLinkTarget(returnFinalTarget: true);
            if (target is null) break;
            cur = target.FullName;
        }
        var parent = Path.GetDirectoryName(cur);
        if (string.IsNullOrEmpty(parent) || parent == cur) return cur;
        if (!Directory.Exists(parent) && !File.Exists(parent)) return cur;
        var resolvedParent = ResolveLinksFully(parent);
        return Path.Combine(resolvedParent, Path.GetFileName(cur));
    }
}

/// <summary>
/// Stopping the whole job on this runtime, which the BCL cannot express alone.
/// Measured (spikes/builtin-host-boundary/ports/csharp): <c>Process.Kill()</c> AND
/// <c>Process.Kill(entireProcessTree: true)</c> both send SIGKILL, and
/// <c>CloseMainWindow()</c> returns false and does nothing — so the graceful step
/// needs <c>kill(2)</c>. And it must reach the JOB: TERMing the direct child kills
/// the interpreter instantly, leaving the tree reparented and the "graceful" step
/// self-defeating.
/// </summary>
internal static class JobControl
{
    [DllImport("libc", SetLastError = true)]
    private static extern int kill(int pid, int sig);

    private const int SIGTERM = 15;

    /// <summary>
    /// Ask the whole job to stop. The descendant list must be captured while the
    /// parent is still alive — once it dies its children are reparented and are
    /// unreachable from its pid (measured: REACHABLE_AFTER_KILL=0).
    /// </summary>
    internal static bool RequestStop(Process proc, IReadOnlyList<int> descendants)
    {
        if (OperatingSystem.IsWindows())
        {
            // Windows has NO graceful termination for a console process, and asking
            // anyway is worse than not asking: measured on a native Windows box,
            // `taskkill /T` without `/F` refuses every console process in the tree
            // while still being able to take the PARENT down — which reparents the
            // grandchild and leaves it running. Terminate at once there; the grace
            // window is a POSIX effect.
            return RunTaskkill(proc.Id, force: true);
        }
        var ok = false;
        foreach (var pid in descendants)
        {
            try { ok |= kill(pid, SIGTERM) == 0; } catch (DllNotFoundException) { }
        }
        try { ok |= kill(proc.Id, SIGTERM) == 0; } catch (DllNotFoundException) { }
        return ok;
    }

    /// <summary>Insist. Kill(entireProcessTree) is the one tree-aware BCL call.</summary>
    internal static bool ForceStop(Process proc)
    {
        try
        {
            proc.Kill(entireProcessTree: true);
            return true;
        }
        catch (Exception)
        {
            return OperatingSystem.IsWindows() && RunTaskkill(proc.Id, force: true);
        }
    }

    /// <summary>
    /// Every live descendant of <paramref name="pid"/>, captured BEFORE the parent
    /// is signalled. On POSIX this is a `ps` walk, because .NET exposes no parent
    /// pid; on Windows `taskkill /T` walks the tree itself, so the list is empty.
    /// </summary>
    internal static List<int> Descendants(int pid)
    {
        var result = new List<int>();
        if (OperatingSystem.IsWindows()) return result;
        try
        {
            var psi = new ProcessStartInfo("ps")
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
            };
            psi.ArgumentList.Add("-eo");
            psi.ArgumentList.Add("pid=,ppid=");
            using var ps = Process.Start(psi);
            if (ps is null) return result;
            var text = ps.StandardOutput.ReadToEnd();
            ps.WaitForExit(2000);

            var pairs = new List<(int Pid, int Ppid)>();
            foreach (var line in text.Split('\n'))
            {
                var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 2 && int.TryParse(parts[0], out var a) && int.TryParse(parts[1], out var b))
                    pairs.Add((a, b));
            }
            var frontier = new List<int> { pid };
            while (frontier.Count > 0)
            {
                var next = pairs.Where(p => frontier.Contains(p.Ppid) && !result.Contains(p.Pid) && p.Pid != pid)
                                .Select(p => p.Pid).ToList();
                if (next.Count == 0) break;
                result.AddRange(next);
                frontier = next;
            }
        }
        catch (Exception)
        {
            // A missing `ps` costs the graceful step, never the forceful one.
        }
        return result;
    }

    private static bool RunTaskkill(int pid, bool force)
    {
        try
        {
            var psi = new ProcessStartInfo("taskkill") { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
            psi.ArgumentList.Add("/T");
            if (force) psi.ArgumentList.Add("/F");
            psi.ArgumentList.Add("/PID");
            psi.ArgumentList.Add(pid.ToString());
            using var p = Process.Start(psi);
            if (p is null) return false;
            p.WaitForExit(5000);
            return p.ExitCode == 0;
        }
        catch (Exception)
        {
            return false;
        }
    }
}
