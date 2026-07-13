using System.Text.RegularExpressions;
using YamlDotNet.Serialization;

namespace Toolnexus;

/// <summary>
/// Dynamic agent-skill source. Mirrors opencode's skill/index.ts + tool/skill.ts:
/// discover <c>**/SKILL.md</c>, parse YAML frontmatter, and expose ONE <c>skill</c>
/// tool that loads a skill's instructions + sampled resources on demand
/// (progressive disclosure).
///
/// Beyond on-disk discovery the source also accepts skills supplied as data
/// (<see cref="SkillDef"/>) — SPEC.md §3. Directory-sourced skills keep the exact
/// <c>file://</c> base + on-disk sibling sampling (byte-identical); data-sourced
/// skills use a logical <c>skill://name/</c> base + a supplied resource list and
/// never touch disk.
/// </summary>
public sealed partial class SkillSource
{
    public const string SkillToolDescription =
        "Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.\n"
        + "\n"
        + "Use this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.\n"
        + "\n"
        + "The skill name must match one of the skills listed in your system prompt.";

    /// <summary>
    /// Instruction preamble prepended to <see cref="Prompt"/> when ≥1 described skill exists.
    /// Byte-identical across all ports — do not reword. See SPEC.md §3.
    /// </summary>
    public const string SkillsPromptPreamble =
        "Skills provide specialized instructions and workflows for specific tasks.\n"
        + "Use the skill tool to load a skill when a task matches its description.";

    [GeneratedRegex(@"^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$")]
    private static partial Regex Frontmatter();

    public sealed record SkillInfo(
        string Name, string? Description, string Location, string Content,
        string Origin = "fs", IReadOnlyList<string>? Resources = null, string? Base = null);

    /// <summary>A skill supplied directly as data, bypassing the filesystem (SPEC.md §3, S1).</summary>
    public sealed record SkillDef(
        string Name, string? Description, string Content,
        IReadOnlyList<string>? Resources = null, string? Base = null);

    /// <summary>Why a candidate SKILL.md did not become a skill (S3).</summary>
    public sealed record SkillSkip(string Location, string Reason)
    {
        public const string MissingName = "missing-name";
        public const string Malformed = "malformed-frontmatter";
        public const string DuplicateName = "duplicate-name";
        public const string Unreadable = "unreadable";
    }

    /// <summary>Result of a list-only validate pass (S3).</summary>
    public sealed record SkillInventory(IReadOnlyList<SkillInfo> Skills, IReadOnlyList<SkillSkip> Skipped);

    /// <summary>Options for <see cref="LoadWith"/> / <see cref="ListSkills"/> (SPEC.md §3, S1/S2/S5).</summary>
    public sealed class LoadOptions
    {
        public IEnumerable<string>? Dirs { get; set; }
        public IReadOnlyList<SkillDef>? Skills { get; set; }
        public IReadOnlyDictionary<string, bool>? Filter { get; set; }
        public int SampleLimit { get; set; } // 0 ⇒ default 10, n>0 ⇒ cap, -1 ⇒ omit <skill_files>
    }

    private readonly Dictionary<string, SkillInfo> _skills;

    public IReadOnlyDictionary<string, SkillInfo> Skills => _skills;
    public ITool Tool { get; }

    private SkillSource(Dictionary<string, SkillInfo> skills, int sampleLimit)
    {
        _skills = skills;
        Tool = new SkillTool(skills, sampleLimit);
    }

    /// <summary>Markdown catalog for the system prompt (mirrors opencode Skill.fmt).</summary>
    public string Prompt()
    {
        var described = _skills.Values
            .Where(s => s.Description != null)
            .OrderBy(s => s.Name, StringComparer.Ordinal)
            .ToList();
        if (described.Count == 0) return "No skills are currently available.";
        var sb = new System.Text.StringBuilder(SkillsPromptPreamble);
        sb.Append("\n\n## Available Skills");
        foreach (var s in described)
            sb.Append("\n- **").Append(s.Name).Append("**: ").Append(s.Description);
        return sb.ToString();
    }

    public static SkillSource Load(params string[] dirs) => Load((IEnumerable<string>)dirs);

    /// <summary>Discover skills under one or more roots and build the <c>skill</c> loader tool.</summary>
    public static SkillSource Load(IEnumerable<string> dirs) => LoadWith(new LoadOptions { Dirs = dirs });

    private readonly record struct RawCandidate(SkillInfo? Info, SkillSkip? Skip);

    private static List<RawCandidate> CandidatesFromDir(string root)
    {
        var output = new List<RawCandidate>();
        if (!Directory.Exists(root))
        {
            Console.Error.WriteLine($"[toolnexus] skills dir not found: {root}");
            return output;
        }
        foreach (var file in WalkSkillFiles(root))
        {
            string text;
            try { text = File.ReadAllText(file); }
            catch
            {
                output.Add(new RawCandidate(null, new SkillSkip(Path.GetFullPath(file), SkillSkip.Unreadable)));
                continue;
            }
            var (data, content, malformed) = ParseFrontmatter(text);
            var abs = Path.GetFullPath(file);
            if (malformed)
            {
                output.Add(new RawCandidate(null, new SkillSkip(abs, SkillSkip.Malformed)));
                continue;
            }
            if (!data.TryGetValue("name", out var name) || string.IsNullOrEmpty(name))
            {
                output.Add(new RawCandidate(null, new SkillSkip(abs, SkillSkip.MissingName)));
                continue;
            }
            data.TryGetValue("description", out var description);
            output.Add(new RawCandidate(new SkillInfo(name, description, abs, content, "fs"), null));
        }
        return output;
    }

    private static List<RawCandidate> CandidatesFromDefs(IReadOnlyList<SkillDef> defs)
    {
        var output = new List<RawCandidate>();
        foreach (var d in defs)
        {
            if (string.IsNullOrEmpty(d.Name))
            {
                output.Add(new RawCandidate(null, new SkillSkip(d.Base ?? "skill://", SkillSkip.MissingName)));
                continue;
            }
            var baseUrl = string.IsNullOrEmpty(d.Base) ? $"skill://{d.Name}/" : d.Base!;
            var res = d.Resources != null ? new List<string>(d.Resources) : new List<string>();
            output.Add(new RawCandidate(
                new SkillInfo(d.Name, d.Description, baseUrl, d.Content ?? "", "logical", res, baseUrl), null));
        }
        return output;
    }

    private static List<RawCandidate> CollectCandidates(LoadOptions opts)
    {
        var cands = new List<RawCandidate>();
        if (opts.Dirs != null)
            foreach (var root in opts.Dirs) cands.AddRange(CandidatesFromDir(root));
        if (opts.Skills != null && opts.Skills.Count > 0)
            cands.AddRange(CandidatesFromDefs(opts.Skills));
        return cands;
    }

    private static (Dictionary<string, SkillInfo> Skills, List<SkillSkip> Skipped) MergeCandidates(
        List<RawCandidate> cands)
    {
        var skills = new Dictionary<string, SkillInfo>();
        var skipped = new List<SkillSkip>();
        foreach (var c in cands)
        {
            if (c.Skip != null)
            {
                skipped.Add(c.Skip);
                continue;
            }
            var info = c.Info!;
            if (skills.ContainsKey(info.Name))
            {
                Console.Error.WriteLine(
                    $"[toolnexus] duplicate skill name \"{info.Name}\" ({info.Location}) — keeping first");
                skipped.Add(new SkillSkip(info.Location, SkillSkip.DuplicateName));
                continue;
            }
            skills[info.Name] = info;
        }
        return (skills, skipped);
    }

    /// <summary>
    /// Per-agent skill allowlist (S2): null/empty ⇒ all; ≥1 true ⇒ allowlist;
    /// only-false ⇒ drop-list over all-on; unknown names ignored + warned once.
    /// </summary>
    private static Dictionary<string, SkillInfo> ApplyFilter(
        Dictionary<string, SkillInfo> skills, IReadOnlyDictionary<string, bool>? filter)
    {
        if (filter == null || filter.Count == 0) return skills;
        var hasTrue = filter.Values.Any(v => v);
        foreach (var k in filter.Keys)
            if (!skills.ContainsKey(k))
                Console.Error.WriteLine($"[toolnexus] skill filter name \"{k}\" matched no skill");
        var output = new Dictionary<string, SkillInfo>();
        foreach (var (name, info) in skills)
        {
            var present = filter.TryGetValue(name, out var v);
            var keep = hasTrue ? (present && v) : !(present && !v);
            if (keep) output[name] = info;
        }
        return output;
    }

    /// <summary>
    /// Discover + validate skills from the same sources <see cref="Load"/> accepts,
    /// returning parsed skills plus typed skip reasons — no toolkit wired
    /// (SPEC.md §3, S3). The inventory is UNFILTERED (it authors the S2 allowlist).
    /// </summary>
    public static SkillInventory ListSkills(LoadOptions opts)
    {
        var (skills, skipped) = MergeCandidates(CollectCandidates(opts));
        return new SkillInventory(skills.Values.ToList(), skipped);
    }

    /// <summary>Discover skills (dirs and/or data) and build the <c>skill</c> loader tool.</summary>
    public static SkillSource LoadWith(LoadOptions opts)
    {
        var (merged, _) = MergeCandidates(CollectCandidates(opts));
        var resolved = ApplyFilter(merged, opts.Filter);
        return new SkillSource(resolved, opts.SampleLimit);
    }

    private sealed class SkillTool : ITool
    {
        private readonly Dictionary<string, SkillInfo> _skills;
        private readonly int _sampleLimit;

        public string Name => "skill";
        public string Description => SkillToolDescription;
        public IDictionary<string, object?> InputSchema { get; }
        public string Source => "skill";

        public SkillTool(Dictionary<string, SkillInfo> skills, int sampleLimit)
        {
            _skills = skills;
            _sampleLimit = sampleLimit;
            InputSchema = new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["name"] = new Dictionary<string, object?>
                    {
                        ["type"] = "string",
                        ["description"] = "The name of the skill to load",
                    },
                },
                ["required"] = new List<object?> { "name" },
                ["additionalProperties"] = false,
            };
        }

        public Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            var name = args != null && args.TryGetValue("name", out var n) && n != null ? n.ToString()! : "";
            if (!_skills.TryGetValue(name, out var info))
            {
                var avail = _skills.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();
                var list = avail.Count == 0 ? "none" : string.Join(", ", avail);
                return Task.FromResult(ToolResult.Error(
                    $"Skill \"{name}\" not found. Available skills: {list}"));
            }

            // effLimit: 0 ⇒ default 10 (byte-identical), n>0 ⇒ cap, -1 ⇒ omit.
            var effLimit = _sampleLimit == 0 ? 10 : _sampleLimit;
            var emitFiles = effLimit != -1;
            string baseUrl;
            string metaDir;
            IReadOnlyList<string> files;
            if (info.Origin == "logical")
            {
                baseUrl = string.IsNullOrEmpty(info.Base) ? $"skill://{info.Name}/" : info.Base!;
                var res = info.Resources ?? Array.Empty<string>();
                if (res.Count == 0) emitFiles = false;
                files = effLimit > 0 && res.Count > effLimit ? res.Take(effLimit).ToList() : res;
                metaDir = baseUrl;
            }
            else
            {
                var dir = Path.GetDirectoryName(info.Location)!;
                baseUrl = PathToFileUrl(dir);
                files = effLimit == -1 ? Array.Empty<string>() : SampleSiblingFiles(dir, effLimit);
                metaDir = dir;
            }

            var filesBlock = new System.Text.StringBuilder();
            for (var i = 0; i < files.Count; i++)
            {
                if (i > 0) filesBlock.Append('\n');
                filesBlock.Append("<file>").Append(files[i]).Append("</file>");
            }

            var lines = new List<string>
            {
                $"<skill_content name=\"{info.Name}\">",
                $"# Skill: {info.Name}",
                "",
                info.Content.Trim(),
                "",
                $"Base directory for this skill: {baseUrl}",
                "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
            };
            if (emitFiles)
            {
                lines.Add("Note: file list is sampled.");
                lines.Add("");
                lines.Add("<skill_files>");
                lines.Add(filesBlock.ToString());
                lines.Add("</skill_files>");
            }
            lines.Add("</skill_content>");

            var meta = new Dictionary<string, object?> { ["name"] = info.Name, ["dir"] = metaDir };
            return Task.FromResult(new ToolResult(string.Join("\n", lines), false, meta));
        }
    }

    /// <summary>
    /// Parse the <c>---</c>-fenced YAML frontmatter with a real YAML parser
    /// (YamlDotNet). <c>Malformed</c> is true only when fences are present but the
    /// YAML fails to parse — distinguishing a malformed header from a body with no
    /// frontmatter, so the inventory (S3) reports the right skip reason. Load's
    /// behavior is unchanged. Mirrors js/src/skill.ts and SPEC.md §3.
    /// </summary>
    internal static (Dictionary<string, string> Data, string Content, bool Malformed) ParseFrontmatter(string text)
    {
        var data = new Dictionary<string, string>();
        var m = Frontmatter().Match(text);
        if (!m.Success)
            return (data, text, false);

        Dictionary<object, object> parsed;
        var malformed = false;
        try
        {
            var deserializer = new DeserializerBuilder().Build();
            parsed = deserializer.Deserialize<Dictionary<object, object>>(m.Groups[1].Value)
                     ?? new Dictionary<object, object>();
        }
        catch
        {
            // Malformed YAML — fall back to an empty mapping, never crash discovery.
            parsed = new Dictionary<object, object>();
            malformed = true;
        }

        foreach (var (rawKey, rawValue) in parsed)
        {
            if (rawKey is null) continue;
            var key = rawKey.ToString();
            if (string.IsNullOrEmpty(key)) continue;
            if (rawValue is string or bool || (rawValue is not null && rawValue.GetType().IsPrimitive))
                data[key] = rawValue!.ToString()!.Trim();
        }
        return (data, m.Groups[2].Value, malformed);
    }

    private static List<string> WalkSkillFiles(string root)
    {
        var output = new List<string>();
        var stack = new Stack<string>();
        stack.Push(root);
        var seen = new HashSet<string>();
        while (stack.Count > 0)
        {
            var dir = stack.Pop();
            IEnumerable<string> entries;
            try { entries = Directory.EnumerateFileSystemEntries(dir); }
            catch { continue; }
            foreach (var entry in entries)
            {
                var fn = Path.GetFileName(entry);
                var (isDir, isFile, real) = Classify(entry);
                if (isDir)
                {
                    if (fn is "node_modules" or ".git") continue;
                    if (real == null || !seen.Add(real)) continue;
                    stack.Push(entry);
                }
                else if (isFile && fn == "SKILL.md")
                {
                    output.Add(entry);
                }
            }
        }
        return output;
    }

    private static List<string> SampleSiblingFiles(string dir, int limit)
    {
        var output = new List<string>();
        var stack = new Stack<string>();
        stack.Push(dir);
        var seen = new HashSet<string>();
        while (stack.Count > 0 && output.Count < limit)
        {
            var cur = stack.Pop();
            IEnumerable<string> entries;
            try { entries = Directory.EnumerateFileSystemEntries(cur); }
            catch { continue; }
            foreach (var entry in entries)
            {
                if (output.Count >= limit) break;
                var fn = Path.GetFileName(entry);
                var (isDir, isFile, real) = Classify(entry);
                if (isDir)
                {
                    if (fn is "node_modules" or ".git") continue;
                    if (real == null || !seen.Add(real)) continue;
                    stack.Push(entry);
                }
                else if (isFile && fn != "SKILL.md")
                {
                    output.Add(Path.GetFullPath(entry));
                }
            }
        }
        return output;
    }

    /// <summary>
    /// Classify a filesystem entry, following symlinks. For a reparse point
    /// (symlink) the final target is resolved and stat'd to decide dir vs file;
    /// broken links return <c>(false, false, null)</c>. <c>Real</c> is the
    /// resolved real path used for symlink-cycle detection.
    /// </summary>
    private static (bool IsDir, bool IsFile, string? Real) Classify(string entry)
    {
        FileAttributes attrs;
        try { attrs = File.GetAttributes(entry); }
        catch { return (false, false, null); }

        if (attrs.HasFlag(FileAttributes.ReparsePoint))
        {
            try
            {
                FileSystemInfo fsi = new DirectoryInfo(entry);
                var target = fsi.ResolveLinkTarget(returnFinalTarget: true);
                if (target == null) return (false, false, null);
                var real = target.FullName;
                if (Directory.Exists(real)) return (true, false, real);
                if (File.Exists(real)) return (false, true, real);
                return (false, false, null); // broken link
            }
            catch { return (false, false, null); }
        }

        if (attrs.HasFlag(FileAttributes.Directory))
            return (true, false, TryFullPath(entry));
        return (false, true, TryFullPath(entry));
    }

    private static string? TryFullPath(string path)
    {
        try { return Path.GetFullPath(path); }
        catch { return null; }
    }

    /// <summary>Match Node's pathToFileURL(dir).href: file://&lt;abs path&gt;, no trailing slash.</summary>
    internal static string PathToFileUrl(string dir)
    {
        var abs = Path.GetFullPath(dir);
        if (!abs.StartsWith('/')) abs = "/" + abs.Replace('\\', '/');
        return "file://" + abs;
    }
}
