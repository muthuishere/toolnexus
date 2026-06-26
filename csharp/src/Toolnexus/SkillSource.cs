using System.Text.RegularExpressions;

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
        var sb = new System.Text.StringBuilder("## Available Skills");
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

    /// <summary>Minimal flat <c>key: value</c> frontmatter parser (mirrors the JS reference).</summary>
    internal static (Dictionary<string, string> Data, string Content) ParseFrontmatter(string text)
    {
        var data = new Dictionary<string, string>();
        var m = Frontmatter().Match(text);
        if (!m.Success)
            return (data, text);

        foreach (var line in Regex.Split(m.Groups[1].Value, "\r?\n"))
        {
            var idx = line.IndexOf(':');
            if (idx == -1) continue;
            var key = line[..idx].Trim();
            var value = line[(idx + 1)..].Trim();
            if ((value.StartsWith('"') && value.EndsWith('"') && value.Length >= 2)
                || (value.StartsWith('\'') && value.EndsWith('\'') && value.Length >= 2))
                value = value[1..^1];
            if (key.Length > 0) data[key] = value;
        }
        return (data, m.Groups[2].Value);
    }

    private static List<string> WalkSkillFiles(string root)
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
                var fn = Path.GetFileName(entry);
                if (Directory.Exists(entry))
                {
                    if (fn is "node_modules" or ".git") continue;
                    stack.Push(entry);
                }
                else if (fn == "SKILL.md")
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
                if (Directory.Exists(entry))
                {
                    if (fn is "node_modules" or ".git") continue;
                    stack.Push(entry);
                }
                else if (fn != "SKILL.md")
                {
                    output.Add(Path.GetFullPath(entry));
                }
            }
        }
        return output;
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
