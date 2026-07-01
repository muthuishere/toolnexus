using System.Text.RegularExpressions;
using YamlDotNet.Serialization;

namespace Toolnexus;

/// <summary>
/// Dynamic agent-skill source. Mirrors opencode's skill/index.ts + tool/skill.ts:
/// discover <c>**/SKILL.md</c>, parse YAML frontmatter, and expose ONE <c>skill</c>
/// tool that loads a skill's instructions + sampled resources on demand
/// (progressive disclosure).
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

    public sealed record SkillInfo(string Name, string? Description, string Location, string Content);

    private readonly Dictionary<string, SkillInfo> _skills;

    public IReadOnlyDictionary<string, SkillInfo> Skills => _skills;
    public ITool Tool { get; }

    private SkillSource(Dictionary<string, SkillInfo> skills)
    {
        _skills = skills;
        Tool = new SkillTool(skills);
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
    public static SkillSource Load(IEnumerable<string> dirs)
    {
        var skills = new Dictionary<string, SkillInfo>();

        foreach (var root in dirs)
        {
            if (!Directory.Exists(root))
            {
                Console.Error.WriteLine($"[toolnexus] skills dir not found: {root}");
                continue;
            }
            foreach (var file in WalkSkillFiles(root))
            {
                string text;
                try { text = File.ReadAllText(file); }
                catch { continue; }

                var (data, content) = ParseFrontmatter(text);
                if (!data.TryGetValue("name", out var name) || string.IsNullOrEmpty(name))
                    continue;
                if (skills.ContainsKey(name))
                {
                    Console.Error.WriteLine(
                        $"[toolnexus] duplicate skill name \"{name}\" ({Path.GetFullPath(file)}) — keeping first");
                    continue;
                }
                data.TryGetValue("description", out var description);
                skills[name] = new SkillInfo(name, description, Path.GetFullPath(file), content);
            }
        }

        return new SkillSource(skills);
    }

    private sealed class SkillTool : ITool
    {
        private readonly Dictionary<string, SkillInfo> _skills;

        public string Name => "skill";
        public string Description => SkillToolDescription;
        public IDictionary<string, object?> InputSchema { get; }
        public string Source => "skill";

        public SkillTool(Dictionary<string, SkillInfo> skills)
        {
            _skills = skills;
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

            var dir = Path.GetDirectoryName(info.Location)!;
            var baseUrl = PathToFileUrl(dir);
            var files = SampleSiblingFiles(dir, 10);

            var filesBlock = new System.Text.StringBuilder();
            for (var i = 0; i < files.Count; i++)
            {
                if (i > 0) filesBlock.Append('\n');
                filesBlock.Append("<file>").Append(files[i]).Append("</file>");
            }

            var output = string.Join("\n",
                $"<skill_content name=\"{info.Name}\">",
                $"# Skill: {info.Name}",
                "",
                info.Content.Trim(),
                "",
                $"Base directory for this skill: {baseUrl}",
                "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
                "Note: file list is sampled.",
                "",
                "<skill_files>",
                filesBlock.ToString(),
                "</skill_files>",
                "</skill_content>");

            var meta = new Dictionary<string, object?> { ["name"] = info.Name, ["dir"] = dir };
            return Task.FromResult(new ToolResult(output, false, meta));
        }
    }

    /// <summary>
    /// Parse the <c>---</c>-fenced YAML frontmatter with a real YAML parser
    /// (YamlDotNet), so folded (<c>&gt;</c>)/literal (<c>|</c>) block scalars,
    /// chomping, quoting, and multi-line values all resolve correctly. Scalar
    /// values (string/number/bool) are coerced to string and <c>.Trim()</c>'d,
    /// keeping the five ports byte-identical. Malformed YAML fails gracefully to
    /// an empty mapping, never crashing discovery. Mirrors js/src/skill.ts and
    /// SPEC.md §3.
    /// </summary>
    internal static (Dictionary<string, string> Data, string Content) ParseFrontmatter(string text)
    {
        var data = new Dictionary<string, string>();
        var m = Frontmatter().Match(text);
        if (!m.Success)
            return (data, text);

        Dictionary<object, object> parsed;
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
        }

        foreach (var (rawKey, rawValue) in parsed)
        {
            if (rawKey is null) continue;
            var key = rawKey.ToString();
            if (string.IsNullOrEmpty(key)) continue;
            // Take only scalar values (YamlDotNet yields strings for scalars);
            // skip nested mappings/sequences. Trim so block-scalar trailing
            // newlines (chomping differs subtly per lib) don't leak.
            if (rawValue is string or bool || (rawValue is not null && rawValue.GetType().IsPrimitive))
                data[key] = rawValue!.ToString()!.Trim();
        }
        return (data, m.Groups[2].Value);
    }

    private static List<string> WalkSkillFiles(string root)
    {
        var output = new List<string>();
        var stack = new Stack<string>();
        stack.Push(root);
        // Follow symlinked directories (like opencode's `symlink: true` glob);
        // guard against symlink cycles by tracking resolved real paths already
        // visited. See SPEC.md §3.
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
            // Symlink: resolve the final target, then stat it to decide.
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
        // On POSIX the absolute path already starts with '/'.
        if (!abs.StartsWith('/')) abs = "/" + abs.Replace('\\', '/');
        return "file://" + abs;
    }
}
