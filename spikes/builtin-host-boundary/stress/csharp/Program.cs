// Stress harness for the SHIPPED C# builtin host boundary.
//
// It calls the real public API only — Toolnexus.BuiltinTools.Create(cfg) -> List<ITool>,
// tool.ExecuteAsync(args, ctx) — and never reimplements any builtin logic. Concurrency is
// Task.WhenAll, the port's own idiom.
//
// Contract: exactly ONE machine-readable line per scenario, and every scenario carries its
// control arm's result in that line. A control that fails makes the verdict INVALID, not PASS.

using System.Diagnostics;
using Toolnexus;

internal static class Program
{
    private static readonly string Root = AppContext.BaseDirectory;
    private static string _work = "";
    private static string _outside = "";
    private static ITool _bash = null!;

    private static async Task<int> Main()
    {
        var harnessDir = FindHarnessDir();
        _work = Path.Combine(harnessDir, "work");
        if (Directory.Exists(_work)) Directory.Delete(_work, true);
        Directory.CreateDirectory(_work);
        _outside = Path.Combine(_work, "outside");
        Directory.CreateDirectory(_outside);

        _bash = Create(null).First(t => t.Name == "bash");

        var sw = Stopwatch.StartNew();
        var ok = true;

        var before = Sample();
        ok &= await S1("S1");
        ok &= await S2("S2");
        ok &= await S3("S3");
        var after1 = Sample();

        // S4: repeat S1-S3 three more times, sampling between rounds.
        var samples = new List<Sampled> { before, after1 };
        var rounds = int.TryParse(Environment.GetEnvironmentVariable("STRESS_ROUNDS"), out var rr) ? rr : 3;
        for (var round = 0; round < rounds; round++)
        {
            await S1(null);
            await S2(null);
            await S3(null);
            samples.Add(Sample());
        }
        ok &= S4(samples);

        ok &= await S5();

        Console.WriteLine($"TOTAL wall={sw.Elapsed.TotalSeconds:F1}s verdict={(ok ? "PASS" : "FAIL")}");
        return ok ? 0 : 1;
    }

    private static string FindHarnessDir()
    {
        var d = new DirectoryInfo(Root);
        while (d != null && !File.Exists(Path.Combine(d.FullName, "Stress.csproj"))) d = d.Parent;
        return d?.FullName ?? Directory.GetCurrentDirectory();
    }

    private static List<ITool> Create(IDictionary<string, object?>? cfg) => BuiltinTools.Create(cfg);

    private static Dictionary<string, object?> Args(params (string K, object? V)[] kv)
    {
        var d = new Dictionary<string, object?>();
        foreach (var (k, v) in kv) d[k] = v;
        return d;
    }

    private static bool MetaTrue(ToolResult r, string key)
        => r.Metadata != null && r.Metadata.TryGetValue(key, out var v) && v is true;

    // ------------------------------------------------------------------ S1
    // 30 concurrent bash calls whose real work lives in a GRANDCHILD, timeout 300ms.
    // If the kill does not reach the whole job, the grandchild survives the timeout and
    // touches its marker file 5 seconds later.

    private static async Task<bool> S1(string? label)
    {
        var sw = Stopwatch.StartNew();
        var dir = Path.Combine(_work, "s1-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(dir);

        string Cmd(string marker) => $"sleep 0.2; sh -c 'sleep 5; touch {marker}'";

        var tasks = Enumerable.Range(0, 30).Select(i =>
            _bash.ExecuteAsync(Args(("command", Cmd(Path.Combine(dir, $"m{i}"))), ("timeout", 300.0)))).ToArray();
        var results = await Task.WhenAll(tasks);

        // CONTROL: the same command with a generous timeout MUST produce its markers —
        // otherwise a zero marker count proves nothing about the kill.
        var cdir = Path.Combine(dir, "control");
        Directory.CreateDirectory(cdir);
        var controls = await Task.WhenAll(Enumerable.Range(0, 3).Select(i =>
            _bash.ExecuteAsync(Args(("command", Cmd(Path.Combine(cdir, $"c{i}"))), ("timeout", 20000.0)))));

        await Task.Delay(7000);

        var markers = Directory.GetFiles(dir).Length;
        var controlMarkers = Directory.GetFiles(cdir).Length;
        var timedOut = results.Count(r => MetaTrue(r, "timedOut"));
        var killed = results.Count(r => MetaTrue(r, "killedTree"));
        var errors = results.Count(r => r.IsError);
        var controlOk = controls.Count(r => !r.IsError);

        var valid = controlMarkers == 3 && controlOk == 3;
        var pass = markers == 0 && timedOut == 30 && errors == 30;
        var verdict = !valid ? "INVALID" : pass ? "PASS" : "FAIL";
        if (label != null)
            Console.WriteLine($"{label} verdict={verdict} markers={markers} timedOut={timedOut}/30 killedTree={killed}/30 errors={errors}/30 control_markers={controlMarkers}/3 control_ok={controlOk}/3 wall={sw.Elapsed.TotalSeconds:F1}s");
        return verdict == "PASS";
    }

    // ------------------------------------------------------------------ S2
    // 40 interleaved calls, half short successes and half timeouts. Results must not cross.

    private static async Task<bool> S2(string? label)
    {
        var sw = Stopwatch.StartNew();
        var dir = Path.Combine(_work, "s2-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(dir);

        var tasks = new List<Task<(int I, bool Slow, ToolResult R)>>();
        for (var i = 0; i < 40; i++)
        {
            var idx = i;
            var slow = i % 2 == 1;
            var cmd = slow
                ? $"sleep 0.2; sh -c 'sleep 5; touch {Path.Combine(dir, $"m{idx}")}'"
                : $"echo ok-{idx}";
            var timeout = slow ? 300.0 : 20000.0;
            tasks.Add(Task.Run(async () =>
                (idx, slow, await _bash.ExecuteAsync(Args(("command", cmd), ("timeout", timeout))))));
        }
        var results = await Task.WhenAll(tasks);

        var goodSuccess = 0; var crossed = 0; var goodTimeout = 0;
        foreach (var (i, slow, r) in results)
        {
            if (slow)
            {
                if (r.IsError && MetaTrue(r, "timedOut")) goodTimeout++;
                else crossed++;
            }
            else
            {
                var exit0 = r.Metadata != null && r.Metadata.TryGetValue("exitCode", out var c) && c is long l && l == 0;
                if (!r.IsError && exit0 && r.Output.Trim() == $"ok-{i}") goodSuccess++;
                else crossed++;
            }
        }

        // CONTROL: 20 serial `echo ok-<i>` with no load at all must all be right — the
        // baseline that says a crossed result under load is the concurrency, not the tool.
        var controlOk = 0;
        for (var i = 0; i < 20; i++)
        {
            var r = await _bash.ExecuteAsync(Args(("command", $"echo ok-{i}"), ("timeout", 20000.0)));
            if (!r.IsError && r.Output.Trim() == $"ok-{i}") controlOk++;
        }

        var valid = controlOk == 20;
        var pass = goodSuccess == 20 && goodTimeout == 20 && crossed == 0;
        var verdict = !valid ? "INVALID" : pass ? "PASS" : "FAIL";
        if (label != null)
            Console.WriteLine($"{label} verdict={verdict} success={goodSuccess}/20 timeouts={goodTimeout}/20 crossed={crossed} control_serial_ok={controlOk}/20 wall={sw.Elapsed.TotalSeconds:F1}s");
        return verdict == "PASS";
    }

    // ------------------------------------------------------------------ S3
    // 5 MB of output down the pipe, then a grandchild holding the pipe open past the timeout.
    // The shape that used to deadlock: reading to EOF never completes while a surviving
    // grandchild still holds the write end.

    private static async Task<bool> S3(string? label)
    {
        const string big = "head -c 5000000 /dev/zero | tr '\\0' 'x'";
        var sw = Stopwatch.StartNew();
        var r = await _bash.ExecuteAsync(Args(("command", big + "; sleep 10"), ("timeout", 1000.0)));
        var wall = sw.Elapsed.TotalSeconds;

        // CONTROL: the same big output WITHOUT the trailing sleep and a generous timeout —
        // proves the pipe itself carries 5 MB, so a fast error above is a kill, not a break.
        var csw = Stopwatch.StartNew();
        var c = await _bash.ExecuteAsync(Args(("command", big), ("timeout", 30000.0)));
        var cwall = csw.Elapsed.TotalSeconds;

        var valid = !c.IsError && c.Output.Length == 5_000_000;
        var pass = r.IsError && MetaTrue(r, "timedOut") && wall < 5.0;
        var verdict = !valid ? "INVALID" : pass ? "PASS" : "FAIL";
        if (label != null)
            Console.WriteLine($"{label} verdict={verdict} isError={r.IsError} timedOut={MetaTrue(r, "timedOut")} killedTree={MetaTrue(r, "killedTree")} bytes={r.Output.Length} wall={wall:F1}s control_ok={!c.IsError} control_bytes={c.Output.Length} control_wall={cwall:F1}s");
        return verdict == "PASS";
    }

    // ------------------------------------------------------------------ S4 (leaks)

    private readonly record struct Sampled(int Threads, int Children, int Fds);

    private static Sampled Sample()
    {
        var pid = Environment.ProcessId;
        var proc = Process.GetCurrentProcess();
        proc.Refresh();
        int threads;
        try { threads = proc.Threads.Count; } catch { threads = -1; }
        return new Sampled(threads, CountDescendants(pid), CountFds(pid));
    }

    private static int CountDescendants(int pid)
    {
        var text = Sh("ps", "-eo", "pid=,ppid=");
        var pairs = new List<(int P, int Pp)>();
        foreach (var line in text.Split('\n'))
        {
            var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2 && int.TryParse(parts[0], out var a) && int.TryParse(parts[1], out var b)) pairs.Add((a, b));
        }
        var seen = new HashSet<int>();
        var frontier = new List<int> { pid };
        while (frontier.Count > 0)
        {
            var next = pairs.Where(p => frontier.Contains(p.Pp) && p.P != pid && seen.Add(p.P)).Select(p => p.P).ToList();
            if (next.Count == 0) break;
            frontier = next;
        }
        return seen.Count;
    }

    private static int CountFds(int pid)
    {
        var text = Sh("lsof", "-p", pid.ToString());
        var n = text.Split('\n', StringSplitOptions.RemoveEmptyEntries).Length;
        return n > 0 ? n - 1 : -1; // minus the header
    }

    private static string Sh(string exe, params string[] args)
    {
        try
        {
            var psi = new ProcessStartInfo(exe) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false };
            foreach (var a in args) psi.ArgumentList.Add(a);
            using var p = Process.Start(psi);
            if (p is null) return "";
            var o = p.StandardOutput.ReadToEnd();
            p.StandardError.ReadToEnd();
            p.WaitForExit(10000);
            return o;
        }
        catch { return ""; }
    }

    private static bool S4(List<Sampled> s)
    {
        // A leak is growth that does not decelerate. The thread count ALWAYS climbs for the
        // first rounds — that is the .NET ThreadPool warming up, and calling it a leak is a
        // false positive that would make this harness useless. So: ignore the cold sample,
        // and flag only growth that is still monotonic AND whose last step is not markedly
        // smaller than its first (a warm-up converges; a leak does not).
        static bool Leaking(IEnumerable<int> xs)
        {
            var l = xs.Skip(1).ToList();
            if (l.Count < 3) return false;
            for (var i = 1; i < l.Count; i++) if (l[i] <= l[i - 1]) return false;
            var first = l[1] - l[0];
            var last = l[^1] - l[^2];
            return last * 4 >= first;
        }
        var threads = s.Select(x => x.Threads).ToList();
        var children = s.Select(x => x.Children).ToList();
        var fds = s.Select(x => x.Fds).ToList();

        // CONTROL: the sampler must actually be able to see things. A thread count of -1 or an
        // fd count of -1 means lsof/Threads gave nothing and the deltas measure nothing.
        var valid = threads.All(t => t > 0) && fds.All(f => f > 0);
        var leak = Leaking(threads) || Leaking(children) || Leaking(fds) || children.Last() > 2;
        var verdict = !valid ? "INVALID" : leak ? "FAIL" : "PASS";
        Console.WriteLine($"S4 verdict={verdict} rounds={s.Count} threads=[{string.Join(",", threads)}] children=[{string.Join(",", children)}] fds=[{string.Join(",", fds)}] dThreads={threads.Last() - threads[0]} dChildren={children.Last() - children[0]} dFds={fds.Last() - fds[0]} control_sampler_ok={valid}");
        return verdict == "PASS";
    }

    // ------------------------------------------------------------------ S5 (confinement)

    private static async Task<bool> S5()
    {
        var sw = Stopwatch.StartNew();
        var r1 = await ConfinementRun();
        var r2 = await ConfinementRun();
        var stable = r1 == r2;
        var valid = r1.LegalOk > 0 && r1.ControlUnconfinedEscapes > 0; // see below
        var pass = r1.Escapes == 0 && r1.LegalRefused == 0 && stable;
        var verdict = !valid ? "INVALID" : pass ? "PASS" : "FAIL";
        Console.WriteLine($"S5 verdict={verdict} attempts={r1.Attempts} legal_ok={r1.LegalOk} legal_refused={r1.LegalRefused} escapes_allowed={r1.Escapes} escapes_refused={r1.EscapeRefused} rerun_identical={stable} control_unconfined_escapes={r1.ControlUnconfinedEscapes} wall={sw.Elapsed.TotalSeconds:F1}s");
        return verdict == "PASS";
    }

    private readonly record struct Conf(int Attempts, int LegalOk, int LegalRefused, int Escapes, int EscapeRefused, int ControlUnconfinedEscapes);

    private static async Task<Conf> ConfinementRun()
    {
        var baseDir = Path.Combine(_work, "confine-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(Path.Combine(baseDir, "sub"));
        var outside = Path.Combine(_work, "outside");
        Directory.CreateDirectory(outside);
        var secret = Path.Combine(outside, "secret.txt");
        File.WriteAllText(secret, "SECRET");
        File.WriteAllText(Path.Combine(baseDir, "a.txt"), "A");
        File.WriteAllText(Path.Combine(baseDir, "sub", "b.txt"), "B");
        File.WriteAllText(Path.Combine(baseDir, "c.txt"), "C");
        // A link in the MIDDLE of a path — the case ResolveLinkTarget alone gets wrong.
        var link = Path.Combine(baseDir, "link");
        if (!Directory.Exists(link) && !File.Exists(link)) Directory.CreateSymbolicLink(link, outside);

        var cfg = new Dictionary<string, object?> { ["baseDir"] = baseDir, ["confineToBaseDir"] = true };
        var read = Create(cfg).First(t => t.Name == "read");

        var legal = new[] { "a.txt", "sub/b.txt", "./c.txt" };
        var escapes = new[]
        {
            "../x", "sub/../../x", secret, "link/secret.txt", "....//x",
            Path.Combine("sub", "..", "..", "outside", "secret.txt"),
            "a\0.txt",
        };

        var jobs = new List<Task<(bool Legal, bool Refused, bool Leaked)>>();
        for (var i = 0; i < 500; i++)
        {
            var isLegal = i % 3 == 0;
            var p = isLegal ? legal[(i / 3) % legal.Length] : escapes[i % escapes.Length];
            jobs.Add(Task.Run(async () =>
            {
                var r = await read.ExecuteAsync(new Dictionary<string, object?> { ["path"] = p });
                // A leak is an escape path that came back with the OUTSIDE file's content.
                var leaked = !r.IsError && r.Output.Contains("SECRET");
                return (isLegal, r.IsError, leaked);
            }));
        }
        var res = await Task.WhenAll(jobs);

        // CONTROL: the same escape paths with confinement OFF must actually reach outside —
        // otherwise "zero escapes" could just mean the paths were unreachable anyway.
        var loose = Create(new Dictionary<string, object?> { ["baseDir"] = baseDir }).First(t => t.Name == "read");
        var controlEscapes = 0;
        foreach (var p in new[] { secret, "link/secret.txt" })
        {
            var r = await loose.ExecuteAsync(new Dictionary<string, object?> { ["path"] = p });
            if (!r.IsError && r.Output.Contains("SECRET")) controlEscapes++;
        }

        return new Conf(
            res.Length,
            res.Count(x => x.Legal && !x.Refused),
            res.Count(x => x.Legal && x.Refused),
            res.Count(x => !x.Legal && (!x.Refused || x.Leaked)),
            res.Count(x => !x.Legal && x.Refused),
            controlEscapes);
    }
}
