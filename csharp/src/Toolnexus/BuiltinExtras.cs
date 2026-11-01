using System.Diagnostics;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace Toolnexus;

/// <summary>One hit from a host search provider (SPEC §4C <c>websearch</c>).</summary>
public sealed record WebSearchResult(string Title, string Url, string Snippet = "");

/// <summary>The host's search provider for the opt-in <c>websearch</c> builtin. The library ships none.</summary>
public delegate Task<IReadOnlyList<WebSearchResult>> WebSearchFn(string query, int limit, CancellationToken cancellationToken);

/// <summary>One background command, for a host UI.</summary>
/// <param name="ExitCode">Valid when not running.</param>
public sealed record BackgroundShellInfo(string Id, string Command, DateTimeOffset Started, bool Running, int ExitCode, bool Killed);

/// <summary>
/// (SPEC §4C) The commands <c>bash</c> started with <c>run_in_background</c>. Pass one as the
/// builtins config key <c>background</c> to turn on <c>run_in_background</c> plus the
/// <c>bash_output</c> and <c>kill_shell</c> tools, and call <see cref="CloseAll"/> when the host shuts
/// down — a background command is NOT tied to the turn that started it, so nothing else stops it.
/// </summary>
public sealed class BackgroundShells
{
    private readonly object _gate = new();
    private readonly Dictionary<string, Job> _jobs = new();
    private int _next;

    private sealed class Job
    {
        public required string Id;
        public required string Command;
        public required DateTimeOffset Started;
        public required Process Proc;
        public required int GraceMs;
        public readonly StringBuilder Buf = new();
        public int Read; // chars of Buf already returned by bash_output
        public readonly TaskCompletionSource Done = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public int ExitCode;
        public bool Killed;
        public bool Running => !Done.Task.IsCompleted;
    }

    internal string Start(IReadOnlyList<string> argv, string command, string workdir, int graceMs)
    {
        var psi = new ProcessStartInfo
        {
            FileName = argv[0],
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            WorkingDirectory = workdir,
        };
        for (var i = 1; i < argv.Count; i++) psi.ArgumentList.Add(argv[i]);
        psi.ArgumentList.Add(command);
        var proc = new Process { StartInfo = psi };
        proc.Start();
        Job job;
        lock (_gate)
        {
            _next++;
            job = new Job { Id = "bg" + _next, Command = command, Started = DateTimeOffset.UtcNow, Proc = proc, GraceMs = graceMs };
            _jobs[job.Id] = job;
        }
        var outTask = Pump(proc.StandardOutput, job);
        var errTask = Pump(proc.StandardError, job);
        _ = Task.Run(async () =>
        {
            try
            {
                // Like Go's cmd.Wait: done once the process exited AND its output is drained.
                await Task.WhenAll(outTask, errTask).ConfigureAwait(false);
                await proc.WaitForExitAsync().ConfigureAwait(false);
                // .NET reports a shell killed by signal N as 128 + N; Go reports -1 (killed by a
                // signal). Map 129..159 to -1 so every port prints the same code. Known limit: a
                // command that itself exits 129..159 also reads as -1.
                var code = proc.ExitCode;
                lock (job.Buf) job.ExitCode = code > 128 && code < 160 ? -1 : code;
            }
            catch { lock (job.Buf) job.ExitCode = -1; }
            job.Done.TrySetResult();
        });
        return job.Id;
    }

    private static async Task Pump(StreamReader reader, Job job)
    {
        var buf = new char[4096];
        int n;
        while ((n = await reader.ReadAsync(buf, 0, buf.Length).ConfigureAwait(false)) > 0)
            lock (job.Buf) job.Buf.Append(buf, 0, n);
    }

    private Job? Get(string id)
    {
        lock (_gate) return _jobs.GetValueOrDefault(id);
    }

    private static BackgroundShellInfo Info(Job j)
    {
        lock (j.Buf) return new BackgroundShellInfo(j.Id, j.Command, j.Started, j.Running, j.ExitCode, j.Killed);
    }

    /// <summary>Stop the job's whole process tree: SIGTERM, the grace window, then SIGKILL. Returns
    /// whether it was still running.</summary>
    private static async Task<bool> KillAsync(Job j)
    {
        if (!j.Running) return false;
        lock (j.Buf) j.Killed = true;
        try
        {
            var descendants = JobControl.Descendants(j.Proc.Id);
            JobControl.RequestStop(j.Proc, descendants);
            var done = await Task.WhenAny(j.Done.Task, Task.Delay(j.GraceMs)).ConfigureAwait(false);
            if (done != j.Done.Task)
            {
                JobControl.ForceStop(j.Proc);
                foreach (var pid in descendants)
                {
                    try { Process.GetProcessById(pid).Kill(); } catch { /* already gone */ }
                }
                await j.Done.Task.ConfigureAwait(false);
            }
        }
        catch (InvalidOperationException)
        {
            // exited between the check and the signal
        }
        return true;
    }

    internal (string Fresh, BackgroundShellInfo Info)? ReadNew(string id)
    {
        var j = Get(id);
        if (j == null) return null;
        string fresh;
        lock (j.Buf)
        {
            fresh = j.Buf.ToString(j.Read, j.Buf.Length - j.Read);
            j.Read = j.Buf.Length;
        }
        return (fresh, Info(j));
    }

    internal async Task<(bool Found, bool WasRunning, BackgroundShellInfo? Info)> KillOneAsync(string id)
    {
        var j = Get(id);
        if (j == null) return (false, false, null);
        var was = await KillAsync(j).ConfigureAwait(false);
        return (true, was, Info(j));
    }

    /// <summary>Every background command, oldest first.</summary>
    public List<BackgroundShellInfo> List()
    {
        List<Job> jobs;
        lock (_gate) jobs = _jobs.Values.ToList();
        return jobs.OrderBy(j => j.Started).ThenBy(j => j.Id, StringComparer.Ordinal).Select(Info).ToList();
    }

    /// <summary>Stop one background command's process tree. Unknown id ⇒ <see cref="KeyNotFoundException"/>.</summary>
    public async Task KillAsync(string id)
    {
        var j = Get(id) ?? throw new KeyNotFoundException($"no background shell \"{id}\"");
        await KillAsync(j).ConfigureAwait(false);
    }

    /// <summary>Stop every background command still running, concurrently.</summary>
    public async Task CloseAllAsync()
    {
        List<Job> jobs;
        lock (_gate) jobs = _jobs.Values.ToList();
        await Task.WhenAll(jobs.Select(KillAsync)).ConfigureAwait(false);
    }

    /// <summary>Synchronous <see cref="CloseAllAsync"/>, for a shutdown path that cannot await.</summary>
    public void CloseAll() => CloseAllAsync().GetAwaiter().GetResult();

    internal static string Status(BackgroundShellInfo i)
        => i.Running ? "running" : i.Killed ? "killed" : $"exited with code {i.ExitCode}";
}

public static partial class BuiltinTools
{
    private static ITool BashOutputTool(BackgroundShells bg) => Builtin(
        "bash_output",
        "Read the output a background bash command (started with run_in_background) produced since the last read, and whether it is still running.",
        Schema(new() { ["id"] = StrProp("The background shell id bash returned") }, "id"),
        (args, _) =>
        {
            var id = Str(args, "id");
            if (bg.ReadNew(id) is not { } r)
                return Task.FromResult(ToolResult.Error($"bash_output: no background shell \"{id}\""));
            var status = BackgroundShells.Status(r.Info);
            var meta = new Dictionary<string, object?> { ["id"] = id, ["status"] = status.Split(' ')[0] };
            if (!r.Info.Running && !r.Info.Killed) meta["exitCode"] = (long)r.Info.ExitCode;
            return Task.FromResult(ToolResult.Ok($"{r.Fresh}\n[status: {status}]", meta));
        });

    private static ITool KillShellTool(BackgroundShells bg) => Builtin(
        "kill_shell",
        "Stop a background bash command and everything it started.",
        Schema(new() { ["id"] = StrProp("The background shell id bash returned") }, "id"),
        async (args, _) =>
        {
            var id = Str(args, "id");
            var (found, was, info) = await bg.KillOneAsync(id).ConfigureAwait(false);
            if (!found) return ToolResult.Error($"kill_shell: no background shell \"{id}\"");
            if (!was)
                return ToolResult.Ok($"Background shell {id} had already {BackgroundShells.Status(info!)}",
                    new Dictionary<string, object?> { ["id"] = id, ["killed"] = false });
            return ToolResult.Ok($"Killed background shell {id}", new Dictionary<string, object?> { ["id"] = id, ["killed"] = true });
        });

    private static ITool WebSearchTool(WebSearchFn search) => Builtin(
        "websearch",
        "Search the web. Returns numbered results with title, URL and snippet.",
        Schema(new()
        {
            ["query"] = StrProp("The search query"),
            ["limit"] = NumProp("Maximum number of results (default 5)"),
        }, "query"),
        async (args, ctx) =>
        {
            var q = Str(args, "query");
            if (q.Trim().Length == 0) return ToolResult.Error("websearch: query is required");
            var limit = NumOrNull(args.Get("limit")) is { } l && l >= 1 ? (int)l : 5;
            IReadOnlyList<WebSearchResult> results;
            try { results = await search(q, limit, ctx?.CancellationToken ?? default).ConfigureAwait(false); }
            catch (Exception e) { return ToolResult.Error($"websearch: {e.Message}"); }
            var list = results.Take(limit).ToList();
            if (list.Count == 0) return ToolResult.Ok("No results.", new Dictionary<string, object?> { ["count"] = 0L });
            var sb = new StringBuilder();
            for (var i = 0; i < list.Count; i++)
            {
                if (i > 0) sb.Append("\n\n");
                sb.Append($"{i + 1}. {list[i].Title}\n   {list[i].Url}");
                if (!string.IsNullOrEmpty(list[i].Snippet)) sb.Append($"\n   {list[i].Snippet}");
            }
            return ToolResult.Ok(sb.ToString(), new Dictionary<string, object?>
            {
                ["count"] = (long)list.Count,
                ["results"] = list.Select(r => (object?)new Dictionary<string, object?>
                {
                    ["title"] = r.Title, ["url"] = r.Url, ["snippet"] = r.Snippet,
                }).ToList(),
            });
        });

    /// <summary>nbformat writes one-space indentation; keep it.</summary>
    private static readonly JsonSerializerOptions NotebookJson = new()
    {
        WriteIndented = true,
        IndentSize = 1,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private static ITool NotebookEditTool(BuiltinEnv env) => Builtin(
        "notebook_edit",
        "Edit a Jupyter notebook (.ipynb) cell: replace its source, insert a new cell after it, or delete it. Replacing a code cell clears its outputs.",
        Schema(new()
        {
            ["path"] = StrProp("Path to the .ipynb file"),
            ["cell_id"] = StrProp("The cell's id (or use cell_index)"),
            ["cell_index"] = NumProp("0-based cell position (used when cell_id is absent)"),
            ["new_source"] = StrProp("The new cell source (replace/insert)"),
            ["cell_type"] = new Dictionary<string, object?>
            {
                ["type"] = "string", ["enum"] = new List<object?> { "code", "markdown" },
                ["description"] = "Cell type for insert (default code) or to change on replace",
            },
            ["edit_mode"] = new Dictionary<string, object?>
            {
                ["type"] = "string", ["enum"] = new List<object?> { "replace", "insert", "delete" },
                ["description"] = "Default replace. insert adds the new cell AFTER the given cell, or first when none is given",
            },
        }, "path"),
        (args, _) =>
        {
            var p = Str(args, "path");
            if (p.Length == 0) return Task.FromResult(ToolResult.Error("notebook_edit: path is required"));
            string full, raw;
            try
            {
                full = env.ResolvePath(p);
                raw = File.ReadAllText(full);
            }
            catch (Exception e) { return Task.FromResult(ToolResult.Error($"notebook_edit: {e.Message}")); }
            Dictionary<string, object?> nb;
            try { nb = Json.ToMap(raw); }
            catch (Exception e) { return Task.FromResult(ToolResult.Error($"notebook_edit: {p} is not a notebook: {e.Message}")); }
            var cells = nb.Get("cells") as List<object?> ?? new List<object?>();
            var mode = Str(args, "edit_mode");
            if (mode.Length == 0) mode = "replace";
            var idx = -1;
            if (args.Get("cell_id") is string cid && cid.Length > 0)
            {
                idx = cells.FindIndex(c => (c as IDictionary<string, object?>)?.Get("id") as string == cid);
                if (idx < 0) return Task.FromResult(ToolResult.Error($"notebook_edit: no cell with id \"{cid}\""));
            }
            else if (NumOrNull(args.Get("cell_index")) is { } ci)
            {
                idx = (int)ci;
                if (idx < 0 || idx >= cells.Count)
                    return Task.FromResult(ToolResult.Error($"notebook_edit: cell_index {idx} out of range (0-{cells.Count - 1})"));
            }
            else if (mode != "insert")
            {
                return Task.FromResult(ToolResult.Error("notebook_edit: cell_id or cell_index is required"));
            }
            var src = Str(args, "new_source");
            var cellType = Str(args, "cell_type");
            string summary;
            switch (mode)
            {
                case "replace":
                    var cm = (IDictionary<string, object?>)cells[idx]!;
                    cm["source"] = NotebookSource(src);
                    if (cellType.Length > 0) cm["cell_type"] = cellType;
                    if (cm.Get("cell_type") as string == "code")
                    {
                        cm["outputs"] = new List<object?>();
                        cm["execution_count"] = null;
                    }
                    else
                    {
                        cm.Remove("outputs");
                        cm.Remove("execution_count");
                    }
                    summary = $"Replaced cell {idx} in {p}";
                    break;
                case "insert":
                    if (cellType.Length == 0) cellType = "code";
                    var cell = new Dictionary<string, object?>
                    {
                        ["cell_type"] = cellType, ["metadata"] = new Dictionary<string, object?>(), ["source"] = NotebookSource(src),
                    };
                    if (cellType == "code")
                    {
                        cell["outputs"] = new List<object?>();
                        cell["execution_count"] = null;
                    }
                    cells.Insert(idx + 1, cell);
                    summary = $"Inserted {cellType} cell at {idx + 1} in {p}";
                    break;
                case "delete":
                    cells.RemoveAt(idx);
                    summary = $"Deleted cell {idx} from {p}";
                    break;
                default:
                    return Task.FromResult(ToolResult.Error($"notebook_edit: unknown edit_mode \"{mode}\""));
            }
            nb["cells"] = cells;
            var output = JsonSerializer.Serialize(nb, NotebookJson) + "\n";
            try { File.WriteAllText(full, output); }
            catch (Exception e) { return Task.FromResult(ToolResult.Error($"notebook_edit: {e.Message}")); }
            var meta = new Dictionary<string, object?> { ["cells"] = (long)cells.Count };
            AddDiffMeta(meta, new[] { ComputeChange(DiffPath(env, p, full), raw, output) });
            return Task.FromResult(ToolResult.Ok(summary, meta));
        });

    /// <summary>Split text into nbformat's list of lines, each keeping its "\n".</summary>
    private static List<object?> NotebookSource(string s)
    {
        var output = new List<object?>();
        var start = 0;
        for (var i = 0; i < s.Length; i++)
        {
            if (s[i] != '\n') continue;
            output.Add(s[start..(i + 1)]);
            start = i + 1;
        }
        if (start < s.Length) output.Add(s[start..]);
        return output;
    }

    /// <summary>
    /// (SPEC §4C) ripgrep-style context: match lines <c>path:N:text</c>, context lines
    /// <c>path-N-text</c>, <c>--</c> between groups that do not touch.
    /// </summary>
    private static List<string> GrepWithContext(List<(string Rel, int Line, string Text)> hits,
        Dictionary<string, string[]> fileLines, int before, int after)
    {
        var matchAt = new Dictionary<string, HashSet<int>>();
        foreach (var h in hits)
        {
            if (!matchAt.TryGetValue(h.Rel, out var set)) matchAt[h.Rel] = set = new HashSet<int>();
            set.Add(h.Line);
        }
        var output = new List<string>();
        string lastRel = "";
        var lastLine = 0;
        foreach (var h in hits)
        {
            var lines = fileLines[h.Rel];
            var n = lines.Length > 0 && lines[^1].Length == 0 ? lines.Length - 1 : lines.Length; // a trailing newline is not a line
            var from = Math.Max(1, h.Line - before);
            var to = Math.Min(n, h.Line + after);
            if (h.Rel == lastRel && from <= lastLine + 1) from = lastLine + 1;
            else if (lastRel.Length > 0) output.Add("--");
            for (var k = from; k <= to; k++)
            {
                var sep = matchAt[h.Rel].Contains(k) ? ":" : "-";
                output.Add($"{h.Rel}{sep}{k}{sep}{lines[k - 1]}");
            }
            if (to > lastLine || h.Rel != lastRel) lastLine = to;
            lastRel = h.Rel;
        }
        return output;
    }
}
