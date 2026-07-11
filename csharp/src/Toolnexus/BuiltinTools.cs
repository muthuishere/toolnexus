using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace Toolnexus;

/// <summary>
/// Built-in tool source (source <c>"builtin"</c>). The default toolset toolnexus ships
/// so an agent can act with zero custom wiring — opencode's built-ins, ported with
/// identical tool names + input schemas across all ports. See <c>../../SPEC.md §4A</c>.
///
/// <para>Every tool obeys the uniform <see cref="ITool"/>/<see cref="ToolResult"/> contract:
/// a failure is a <see cref="ToolResult"/> with <c>IsError:true</c>, never a thrown exception
/// across the boundary. Paths resolve relative to the process working directory unless
/// absolute.</para>
///
/// <para><b>Config</b> — the single global toggle mirrors MCP's <c>isEnabled</c> precedence.
/// A config value is one of: <c>null</c> (default on), a <see cref="bool"/>, or an
/// <c>IDictionary&lt;string, object?&gt;</c> with keys <c>enabled</c>/<c>disabled</c>/<c>tools</c>
/// (a name→bool map on the all-on baseline).</para>
/// </summary>
public static partial class BuiltinTools
{
    private static readonly HashSet<string> IgnoreDirs = new() { "node_modules", ".git" };

    [GeneratedRegex(@"^\*\*\* (Add|Update|Delete) File: (.+)$")]
    private static partial Regex FileMarker();

    /// <summary>
    /// Whether the builtin source is on. Default ON. Same precedence as MCP:
    /// <c>disabled:true</c> wins, else <c>enabled:false</c> disables, otherwise enabled.
    /// </summary>
    public static bool Enabled(object? cfg)
    {
        if (cfg == null) return true;
        if (cfg is bool b) return b;
        if (cfg is IDictionary<string, object?> d)
        {
            if (d.Get("disabled") is true) return false;
            if (d.Get("enabled") is false) return false;
        }
        return true;
    }

    /// <summary>
    /// Resolve the active builtin tools for a config. Whole-source-off wins and returns an
    /// empty list. Otherwise all ten are on; a <c>tools</c> name→bool map drops any tool
    /// mapped to <c>false</c> (all-on baseline; <c>true</c>/absent stay on; unknown names are
    /// ignored). SPEC §4A.
    /// </summary>
    public static List<ITool> Select(object? cfg)
    {
        if (!Enabled(cfg)) return new List<ITool>();
        var all = Create();
        if (cfg is IDictionary<string, object?> d && d.Get("tools") is IDictionary<string, object?> map)
            return all.Where(t => map.Get(t.Name) is not false).ToList();
        return all;
    }

    /// <summary>
    /// Build the ten built-in tools (each <c>source:"builtin"</c>). The order is fixed for
    /// parity: bash, read, write, edit, grep, glob, webfetch, question, apply_patch,
    /// todowrite.
    /// </summary>
    public static List<ITool> Create() => new()
    {
        BashTool(),
        ReadTool(),
        WriteTool(),
        EditTool(),
        GrepTool(),
        GlobTool(),
        WebfetchTool(),
        QuestionTool(),
        ApplyPatchTool(),
        TodowriteTool(),
    };

    // -----------------------------------------------------------------------
    // schema helpers
    // -----------------------------------------------------------------------

    private static Dictionary<string, object?> StrProp(string desc) => new() { ["type"] = "string", ["description"] = desc };
    private static Dictionary<string, object?> NumProp(string desc) => new() { ["type"] = "number", ["description"] = desc };
    private static Dictionary<string, object?> BoolProp(string desc) => new() { ["type"] = "boolean", ["description"] = desc };

    private static Dictionary<string, object?> Schema(Dictionary<string, object?> properties, params string[] required) => new()
    {
        ["type"] = "object",
        ["properties"] = properties,
        ["required"] = required.Cast<object?>().ToList(),
        ["additionalProperties"] = false,
    };

    // -----------------------------------------------------------------------
    // arg helpers
    // -----------------------------------------------------------------------

    private static string Str(IDictionary<string, object?> args, string key)
        => args.Get(key)?.ToString() ?? "";

    private static double? NumOrNull(object? v) => v switch
    {
        null => null,
        double d => d,
        float f => f,
        long l => l,
        int i => i,
        short sh => sh,
        decimal m => (double)m,
        bool => null,
        string s => double.TryParse(s, System.Globalization.CultureInfo.InvariantCulture, out var r) ? r : null,
        _ => null,
    };

    // -----------------------------------------------------------------------
    // the wrapper — turns a run delegate into a ToolResult-safe ITool
    // -----------------------------------------------------------------------

    private static ITool Builtin(
        string name, string description, Dictionary<string, object?> inputSchema,
        Func<IDictionary<string, object?>, ToolContext?, Task<ToolResult>> run)
        => new BuiltinTool(name, description, inputSchema, run);

    private sealed class BuiltinTool : ITool
    {
        private readonly Func<IDictionary<string, object?>, ToolContext?, Task<ToolResult>> _run;

        public string Name { get; }
        public string Description { get; }
        public IDictionary<string, object?> InputSchema { get; }
        public string Source => "builtin";

        public BuiltinTool(string name, string description, Dictionary<string, object?> inputSchema,
            Func<IDictionary<string, object?>, ToolContext?, Task<ToolResult>> run)
        {
            Name = name;
            Description = description;
            InputSchema = inputSchema;
            _run = run;
        }

        public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            try
            {
                return await _run(args ?? new Dictionary<string, object?>(), ctx).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                var cause = e.InnerException ?? e;
                return ToolResult.Error($"{Name}: {cause.Message}");
            }
        }
    }

    // -----------------------------------------------------------------------
    // glob helpers (shared by grep + glob)
    // -----------------------------------------------------------------------

    /// <summary>Convert a glob (<c>*</c>, <c>**</c>, <c>?</c>) to an anchored regex.</summary>
    private static Regex GlobToRegex(string glob)
    {
        var re = new StringBuilder("^");
        for (var i = 0; i < glob.Length; i++)
        {
            var c = glob[i];
            if (c == '*')
            {
                if (i + 1 < glob.Length && glob[i + 1] == '*')
                {
                    re.Append(".*");
                    i++;
                    if (i + 1 < glob.Length && glob[i + 1] == '/') i++;
                }
                else
                {
                    re.Append("[^/]*");
                }
            }
            else if (c == '?')
            {
                re.Append("[^/]");
            }
            else if ("\\^$.|+()[]{}".IndexOf(c) >= 0)
            {
                re.Append('\\').Append(c);
            }
            else
            {
                re.Append(c);
            }
        }
        re.Append('$');
        return new Regex(re.ToString());
    }

    /// <summary>Match a relative path against a glob; slash-less globs test the basename.</summary>
    private static bool MatchGlob(string rel, string glob)
    {
        var re = GlobToRegex(glob);
        if (!glob.Contains('/')) return re.IsMatch(Path.GetFileName(rel));
        return re.IsMatch(rel);
    }

    /// <summary>Recursively list files under <paramref name="root"/> (skips node_modules/.git).</summary>
    private static List<string> WalkFiles(string root)
    {
        var output = new List<string>();
        var stack = new Stack<string>();
        stack.Push(root);
        while (stack.Count > 0)
        {
            var dir = stack.Pop();
            IEnumerable<string> entries;
            try { entries = Directory.EnumerateFileSystemEntries(dir); }
            catch { continue; }
            foreach (var entry in entries)
            {
                if (Directory.Exists(entry))
                {
                    if (IgnoreDirs.Contains(Path.GetFileName(entry))) continue;
                    stack.Push(entry);
                }
                else
                {
                    output.Add(entry);
                }
            }
        }
        return output;
    }

    private static string RelPath(string root, string file)
        => Path.GetRelativePath(root, file).Replace('\\', '/');

    // -----------------------------------------------------------------------
    // individual tools
    // -----------------------------------------------------------------------

    private static ITool BashTool() => Builtin(
        "bash",
        "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
        Schema(new()
        {
            ["command"] = StrProp("The shell command to run"),
            ["workdir"] = StrProp("Working directory (default: process cwd)"),
            ["timeout"] = NumProp("Timeout in milliseconds (default 60000)"),
            ["description"] = StrProp("Human-readable description of the command"),
        }, "command"),
        async (args, _) =>
        {
            var command = Str(args, "command");
            if (command.Length == 0) return ToolResult.Error("bash: command is required");
            var workdir = args.Get("workdir") is string w && w.Length > 0 ? w : Directory.GetCurrentDirectory();
            var timeout = NumOrNull(args.Get("timeout")) ?? 60_000;

            var psi = new ProcessStartInfo
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                WorkingDirectory = workdir,
            };
            if (OperatingSystem.IsWindows())
            {
                psi.FileName = "cmd.exe";
                psi.ArgumentList.Add("/c");
                psi.ArgumentList.Add(command);
            }
            else
            {
                psi.FileName = "/bin/sh";
                psi.ArgumentList.Add("-c");
                psi.ArgumentList.Add(command);
            }

            using var proc = new Process { StartInfo = psi };
            try { proc.Start(); }
            catch (Exception e) { return ToolResult.Error($"bash: {e.Message}"); }

            var stdoutTask = proc.StandardOutput.ReadToEndAsync();
            var stderrTask = proc.StandardError.ReadToEndAsync();

            var timedOut = false;
            using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(timeout));
            try
            {
                await proc.WaitForExitAsync(cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                timedOut = true;
                try { proc.Kill(true); } catch { /* best effort */ }
            }

            var combined = (await stdoutTask.ConfigureAwait(false)) + (await stderrTask.ConfigureAwait(false));
            if (timedOut)
                return ToolResult.Error($"bash: command timed out after {(long)timeout}ms\n{combined}");

            var code = proc.ExitCode;
            var meta = new Dictionary<string, object?> { ["exitCode"] = (long)code };
            if (code != 0)
                return ToolResult.Error($"{combined}\nbash: command exited with code {code}", meta);
            return ToolResult.Ok(combined, meta);
        });

    private static ITool ReadTool() => Builtin(
        "read",
        "Read a UTF-8 text file. With offset/limit, return only that line window.",
        Schema(new()
        {
            ["path"] = StrProp("Path to the file to read"),
            ["offset"] = NumProp("1-based line to start from"),
            ["limit"] = NumProp("Maximum number of lines to read"),
        }, "path"),
        (args, _) =>
        {
            var p = Str(args, "path");
            if (p.Length == 0) return Task.FromResult(ToolResult.Error("read: path is required"));
            string content;
            try { content = File.ReadAllText(p); }
            catch (Exception e) { return Task.FromResult(ToolResult.Error($"read: {e.Message}")); }

            var offsetArg = args.Get("offset");
            var limitArg = args.Get("limit");
            if (offsetArg == null && limitArg == null) return Task.FromResult(ToolResult.Ok(content));

            var lines = content.Split('\n');
            var offset = NumOrNull(offsetArg) is { } o ? Math.Max(1, (int)Math.Truncate(o)) : 1;
            var start = offset - 1;
            var limit = NumOrNull(limitArg) is { } l ? Math.Max(0, (int)Math.Truncate(l)) : lines.Length - start;
            if (start < 0) start = 0;
            if (start > lines.Length) start = lines.Length;
            var count = Math.Min(limit, lines.Length - start);
            if (count < 0) count = 0;
            return Task.FromResult(ToolResult.Ok(string.Join("\n", lines.Skip(start).Take(count))));
        });

    private static ITool WriteTool() => Builtin(
        "write",
        "Write content to a file (create/overwrite), creating parent directories.",
        Schema(new()
        {
            ["path"] = StrProp("Path to write to"),
            ["content"] = StrProp("Content to write"),
        }, "path", "content"),
        (args, _) =>
        {
            var p = Str(args, "path");
            if (p.Length == 0) return Task.FromResult(ToolResult.Error("write: path is required"));
            var content = args.Get("content") is string s ? s : args.Get("content")?.ToString() ?? "";
            var dir = Path.GetDirectoryName(Path.GetFullPath(p));
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            File.WriteAllText(p, content);
            var bytes = Encoding.UTF8.GetByteCount(content);
            var meta = new Dictionary<string, object?> { ["bytes"] = (long)bytes };
            return Task.FromResult(ToolResult.Ok($"Wrote {bytes} bytes to {p}", meta));
        });

    private static ITool EditTool() => Builtin(
        "edit",
        "Exact-string replace in a file. Default replaces a single unique occurrence; replaceAll replaces all.",
        Schema(new()
        {
            ["path"] = StrProp("Path to the file to edit"),
            ["oldString"] = StrProp("Exact string to replace"),
            ["newString"] = StrProp("Replacement string"),
            ["replaceAll"] = BoolProp("Replace all occurrences"),
        }, "path", "oldString", "newString"),
        (args, _) =>
        {
            var p = Str(args, "path");
            if (p.Length == 0) return Task.FromResult(ToolResult.Error("edit: path is required"));
            if (args.Get("oldString") is not string oldString || oldString.Length == 0)
                return Task.FromResult(ToolResult.Error("edit: oldString is required"));
            var newString = args.Get("newString") is string ns ? ns : args.Get("newString")?.ToString() ?? "";

            string content;
            try { content = File.ReadAllText(p); }
            catch (Exception e) { return Task.FromResult(ToolResult.Error($"edit: {e.Message}")); }

            var count = CountOccurrences(content, oldString);
            if (count == 0) return Task.FromResult(ToolResult.Error($"edit: oldString not found in {p}"));

            var replaceAll = args.Get("replaceAll") is true;
            string next;
            int replacements;
            if (replaceAll)
            {
                next = content.Replace(oldString, newString);
                replacements = count;
            }
            else
            {
                if (count > 1)
                    return Task.FromResult(ToolResult.Error(
                        $"edit: oldString is not unique in {p} ({count} occurrences); use replaceAll"));
                var idx = content.IndexOf(oldString, StringComparison.Ordinal);
                next = content[..idx] + newString + content[(idx + oldString.Length)..];
                replacements = 1;
            }
            File.WriteAllText(p, next);
            var meta = new Dictionary<string, object?> { ["replacements"] = (long)replacements };
            return Task.FromResult(ToolResult.Ok(
                $"Edited {p} ({replacements} replacement{(replacements == 1 ? "" : "s")})", meta));
        });

    private static int CountOccurrences(string haystack, string needle)
    {
        var count = 0;
        var i = 0;
        while ((i = haystack.IndexOf(needle, i, StringComparison.Ordinal)) >= 0)
        {
            count++;
            i += needle.Length;
        }
        return count;
    }

    private static ITool GrepTool() => Builtin(
        "grep",
        "Search file contents by regex under a directory. Output is file:line:text matches.",
        Schema(new()
        {
            ["pattern"] = StrProp("Regular expression to search for"),
            ["path"] = StrProp("Directory to search (default: process cwd)"),
            ["include"] = StrProp("Glob filter for file names"),
            ["limit"] = NumProp("Maximum number of matches (default 100)"),
        }, "pattern"),
        (args, _) =>
        {
            var pattern = Str(args, "pattern");
            if (pattern.Length == 0) return Task.FromResult(ToolResult.Error("grep: pattern is required"));
            Regex re;
            try { re = new Regex(pattern); }
            catch (Exception e) { return Task.FromResult(ToolResult.Error($"grep: invalid regex: {e.Message}")); }

            var root = args.Get("path") is string rp && rp.Length > 0 ? rp : Directory.GetCurrentDirectory();
            var include = args.Get("include") is string inc && inc.Length > 0 ? inc : null;
            var limit = (int)(NumOrNull(args.Get("limit")) ?? 100);

            var matches = new List<string>();
            foreach (var file in WalkFiles(root))
            {
                if (matches.Count >= limit) break;
                var rel = RelPath(root, file);
                if (include != null && !MatchGlob(rel, include)) continue;
                string text;
                try { text = File.ReadAllText(file); }
                catch { continue; }
                var lines = text.Split('\n');
                for (var i = 0; i < lines.Length; i++)
                {
                    if (matches.Count >= limit) break;
                    if (re.IsMatch(lines[i])) matches.Add($"{file}:{i + 1}:{lines[i]}");
                }
            }
            var meta = new Dictionary<string, object?> { ["count"] = (long)matches.Count };
            return Task.FromResult(ToolResult.Ok(string.Join("\n", matches), meta));
        });

    private static ITool GlobTool() => Builtin(
        "glob",
        "List files matching a glob under a directory. Output is newline-joined relative paths.",
        Schema(new()
        {
            ["pattern"] = StrProp("Glob pattern to match"),
            ["path"] = StrProp("Directory to search (default: process cwd)"),
            ["limit"] = NumProp("Maximum number of results (default 100)"),
        }, "pattern"),
        (args, _) =>
        {
            var pattern = Str(args, "pattern");
            if (pattern.Length == 0) return Task.FromResult(ToolResult.Error("glob: pattern is required"));
            var root = args.Get("path") is string rp && rp.Length > 0 ? rp : Directory.GetCurrentDirectory();
            var limit = (int)(NumOrNull(args.Get("limit")) ?? 100);

            var found = new List<string>();
            foreach (var file in WalkFiles(root))
            {
                if (found.Count >= limit) break;
                var rel = RelPath(root, file);
                if (MatchGlob(rel, pattern)) found.Add(rel);
            }
            found.Sort(StringComparer.Ordinal);
            var capped = found.Take(limit).ToList();
            var meta = new Dictionary<string, object?> { ["count"] = (long)capped.Count };
            return Task.FromResult(ToolResult.Ok(string.Join("\n", capped), meta));
        });

    /// <summary>Very light HTML → text: drop scripts/styles + tags, collapse whitespace.</summary>
    private static string StripHtml(string html)
    {
        html = Regex.Replace(html, @"<script[\s\S]*?</script>", "", RegexOptions.IgnoreCase);
        html = Regex.Replace(html, @"<style[\s\S]*?</style>", "", RegexOptions.IgnoreCase);
        html = Regex.Replace(html, @"<[^>]+>", "");
        html = Regex.Replace(html, @"[ \t]+\n", "\n");
        html = Regex.Replace(html, @"\n{3,}", "\n\n");
        return html.Trim();
    }

    private static ITool WebfetchTool() => Builtin(
        "webfetch",
        "HTTP GET a URL and return its body as text, markdown, or html.",
        Schema(new()
        {
            ["url"] = StrProp("URL to fetch"),
            ["format"] = new Dictionary<string, object?>
            {
                ["type"] = "string",
                ["enum"] = new List<object?> { "text", "markdown", "html" },
                ["description"] = "Response format (default markdown)",
            },
            ["timeout"] = NumProp("Timeout in seconds (default 30)"),
        }, "url"),
        async (args, ctx) =>
        {
            var url = Str(args, "url");
            if (url.Length == 0) return ToolResult.Error("webfetch: url is required");
            var fmt = args.Get("format")?.ToString();
            var format = fmt is "text" or "html" ? fmt : "markdown";
            var timeoutMs = (NumOrNull(args.Get("timeout")) ?? 30) * 1000;

            using var cts = ctx?.CancellationToken is { } ct
                ? CancellationTokenSource.CreateLinkedTokenSource(ct)
                : new CancellationTokenSource();
            cts.CancelAfter(TimeSpan.FromMilliseconds(timeoutMs));
            try
            {
                using var client = new HttpClient();
                using var res = await client.GetAsync(url, cts.Token).ConfigureAwait(false);
                var body = await res.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
                var status = (int)res.StatusCode;
                if (!res.IsSuccessStatusCode)
                    return ToolResult.Error($"HTTP {status}", new Dictionary<string, object?> { ["status"] = (long)status });
                var output = format == "html" ? body : StripHtml(body);
                var meta = new Dictionary<string, object?> { ["status"] = (long)status, ["format"] = format };
                return ToolResult.Ok(output, meta);
            }
            catch (Exception e)
            {
                return ToolResult.Error($"webfetch: {e.Message}");
            }
        });

    /// <summary>
    /// Render the questions into a human-readable <c>Request.Prompt</c> (§10). Byte-identical across
    /// ports: each question's text in order, <c>" (options: a, b, c)"</c> appended when it has non-empty
    /// options, joined by "\n" (no trailing newline). <c>header</c> is not rendered — it survives in
    /// <c>data.questions</c>.
    /// </summary>
    private static string RenderQuestionPrompt(IEnumerable<object?> questions)
        => string.Join("\n", questions.Select(q =>
        {
            var item = q as IDictionary<string, object?>;
            var line = item?.Get("question") is string s ? s : "";
            var opts = (item?.Get("options") as IEnumerable<object?>)?.ToList() ?? new List<object?>();
            if (opts.Count > 0) line += $" (options: {string.Join(", ", opts)})";
            return line;
        }));

    private static ITool QuestionTool() => Builtin(
        "question",
        "Ask the host one or more questions. Suspends via a kind:\"question\" Request (§10); the host's WaitFor resolves it and the answer is returned to the model.",
        Schema(new()
        {
            ["questions"] = new Dictionary<string, object?>
            {
                ["type"] = "array",
                ["description"] = "Questions to ask",
                ["items"] = new Dictionary<string, object?>
                {
                    ["type"] = "object",
                    ["properties"] = new Dictionary<string, object?>
                    {
                        ["question"] = new Dictionary<string, object?> { ["type"] = "string" },
                        ["header"] = new Dictionary<string, object?> { ["type"] = "string" },
                        ["options"] = new Dictionary<string, object?>
                        {
                            ["type"] = "array",
                            ["items"] = new Dictionary<string, object?> { ["type"] = "string" },
                        },
                        ["multiple"] = new Dictionary<string, object?> { ["type"] = "boolean" },
                    },
                    ["required"] = new List<object?> { "question" },
                },
            },
        }, "questions"),
        (args, ctx) =>
        {
            var questions = args.Get("questions") as IEnumerable<object?> ?? new List<object?>();
            var list = questions.ToList();
            // Re-executed after the host's WaitFor resolved (§10 loop rule): the resolution IS the
            // answer, as with kind:"input" — forward it verbatim to the model.
            if (ctx?.Answer is not null)
                return Task.FromResult(ToolResult.Ok(Json.Stringify(ctx.Answer.Data ?? new Dictionary<string, object?>())));
            // First call: suspend. A question is just a §10 Request with kind:"question".
            return Task.FromResult(ToolResult.Pending(new Request
            {
                Kind = "question",
                Prompt = RenderQuestionPrompt(list),
                Data = new Dictionary<string, object?> { ["questions"] = list },
            }));
        });

    private static ITool TodowriteTool() => Builtin(
        "todowrite",
        "Replace the session todo list. Returns the rendered list.",
        Schema(new()
        {
            ["todos"] = new Dictionary<string, object?>
            {
                ["type"] = "array",
                ["description"] = "The full todo list to store",
                ["items"] = new Dictionary<string, object?>
                {
                    ["type"] = "object",
                    ["properties"] = new Dictionary<string, object?>
                    {
                        ["id"] = new Dictionary<string, object?> { ["type"] = "string" },
                        ["text"] = new Dictionary<string, object?> { ["type"] = "string" },
                        ["completed"] = new Dictionary<string, object?> { ["type"] = "boolean" },
                    },
                    ["required"] = new List<object?> { "id", "text", "completed" },
                },
            },
        }, "todos"),
        (args, _) =>
        {
            var todos = (args.Get("todos") as IEnumerable<object?> ?? new List<object?>()).ToList();
            var rendered = string.Join("\n", todos.Select(t =>
            {
                var d = t as IDictionary<string, object?>;
                var done = d?.Get("completed") is true;
                var text = d?.Get("text")?.ToString() ?? "";
                return $"[{(done ? "x" : " ")}] {text}";
            }));
            var meta = new Dictionary<string, object?> { ["todos"] = todos };
            return Task.FromResult(ToolResult.Ok(rendered.Length > 0 ? rendered : "(no todos)", meta));
        });

    // -----------------------------------------------------------------------
    // apply_patch (opencode Begin/End Patch grammar)
    // -----------------------------------------------------------------------

    private abstract record PatchOp;
    private sealed record AddOp(string Path, string Content) : PatchOp;
    private sealed record DeleteOp(string Path) : PatchOp;
    private sealed record UpdateOp(string Path, List<string> Body) : PatchOp;

    private static List<PatchOp> ParsePatch(string patchText)
    {
        var lines = patchText.Split('\n');
        var i = 0;
        while (i < lines.Length && lines[i].Trim().Length == 0) i++;
        if (i >= lines.Length || lines[i].Trim() != "*** Begin Patch")
            throw new Exception("missing '*** Begin Patch'");
        i++;
        var ops = new List<PatchOp>();
        while (i < lines.Length)
        {
            var line = lines[i];
            if (line.Trim() == "*** End Patch") return ops;
            if (line.Trim().Length == 0) { i++; continue; }
            var m = FileMarker().Match(line);
            if (!m.Success) throw new Exception($"unexpected line: {line}");
            var kind = m.Groups[1].Value;
            var p = m.Groups[2].Value.Trim();
            i++;
            var body = new List<string>();
            while (i < lines.Length && lines[i].Trim() != "*** End Patch" && !FileMarker().IsMatch(lines[i]))
            {
                body.Add(lines[i]);
                i++;
            }
            if (kind == "Add")
            {
                var content = string.Join("\n", body.Select(l => l.StartsWith('+') ? l[1..] : l));
                ops.Add(new AddOp(p, content));
            }
            else if (kind == "Delete")
            {
                ops.Add(new DeleteOp(p));
            }
            else
            {
                ops.Add(new UpdateOp(p, body));
            }
        }
        throw new Exception("missing '*** End Patch'");
    }

    /// <summary>Apply an Update hunk-body to file content, throwing on a non-match.</summary>
    private static string ApplyUpdate(string content, List<string> body)
    {
        var hunks = new List<List<string>>();
        var cur = new List<string>();
        foreach (var l in body)
        {
            if (l.StartsWith("@@"))
            {
                if (cur.Count > 0) hunks.Add(cur);
                cur = new List<string>();
            }
            else
            {
                cur.Add(l);
            }
        }
        if (cur.Count > 0) hunks.Add(cur);

        var result = content;
        foreach (var hunk in hunks)
        {
            var oldLines = new List<string>();
            var newLines = new List<string>();
            foreach (var l in hunk)
            {
                if (l.StartsWith('-')) oldLines.Add(l[1..]);
                else if (l.StartsWith('+')) newLines.Add(l[1..]);
                else if (l.StartsWith(' ')) { oldLines.Add(l[1..]); newLines.Add(l[1..]); }
                else { oldLines.Add(l); newLines.Add(l); }
            }
            var oldBlock = string.Join("\n", oldLines);
            var newBlock = string.Join("\n", newLines);
            if (oldBlock.Length > 0)
            {
                var idx = result.IndexOf(oldBlock, StringComparison.Ordinal);
                if (idx < 0) throw new Exception("hunk does not match file contents");
                result = result[..idx] + newBlock + result[(idx + oldBlock.Length)..];
            }
            else
            {
                result = result + (result.EndsWith('\n') || result.Length == 0 ? "" : "\n") + newBlock;
            }
        }
        return result;
    }

    private static ITool ApplyPatchTool() => Builtin(
        "apply_patch",
        "Apply a patch (Begin/End Patch grammar: Add/Update/Delete File). Atomic — a non-matching hunk aborts with no writes.",
        Schema(new()
        {
            ["patchText"] = StrProp("The patch text in Begin/End Patch format"),
        }, "patchText"),
        (args, _) =>
        {
            var patchText = Str(args, "patchText");
            if (patchText.Length == 0) return Task.FromResult(ToolResult.Error("apply_patch: patchText is required"));
            List<PatchOp> ops;
            try { ops = ParsePatch(patchText); }
            catch (Exception e) { return Task.FromResult(ToolResult.Error($"apply_patch: {e.Message}")); }

            // Stage every write/delete first; only touch the filesystem once all hunks apply.
            var writes = new List<(string Path, string Content)>();
            var deletes = new List<string>();
            try
            {
                foreach (var op in ops)
                {
                    switch (op)
                    {
                        case AddOp a:
                            if (File.Exists(a.Path)) throw new Exception($"file already exists: {a.Path}");
                            writes.Add((a.Path, a.Content));
                            break;
                        case DeleteOp d:
                            if (!File.Exists(d.Path)) throw new Exception($"file not found: {d.Path}");
                            deletes.Add(d.Path);
                            break;
                        case UpdateOp u:
                            var content = File.ReadAllText(u.Path);
                            writes.Add((u.Path, ApplyUpdate(content, u.Body)));
                            break;
                    }
                }
            }
            catch (Exception e)
            {
                return Task.FromResult(ToolResult.Error($"apply_patch: {e.Message}"));
            }

            foreach (var w in writes)
            {
                var dir = Path.GetDirectoryName(Path.GetFullPath(w.Path));
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                File.WriteAllText(w.Path, w.Content);
            }
            foreach (var d in deletes)
            {
                try { File.Delete(d); } catch { /* best effort */ }
            }

            var meta = new Dictionary<string, object?>
            {
                ["added"] = (long)ops.Count(o => o is AddOp),
                ["updated"] = (long)ops.Count(o => o is UpdateOp),
                ["deleted"] = (long)ops.Count(o => o is DeleteOp),
            };
            return Task.FromResult(ToolResult.Ok(
                $"Applied patch: {ops.Count} file operation{(ops.Count == 1 ? "" : "s")}", meta));
        });
}
