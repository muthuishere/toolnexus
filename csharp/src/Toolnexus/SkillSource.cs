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

    /// <summary>
    /// Why a candidate SKILL.md did not become a skill (S3).
    /// </summary>
    /// <param name="Detail">(ADR 0028) The NATIVE parser error behind <paramref name="Reason"/>,
    /// verbatim — the line and column YamlDotNet refused at. <paramref name="Reason"/> stays one of
    /// the four byte-identical strings below; this is the field that lets a host fix the file
    /// instead of guessing. Empty when there is nothing more to say.</param>
    public sealed record SkillSkip(string Location, string Reason, string Detail = "")
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

    private SkillSource(Dictionary<string, SkillInfo> skills, int sampleLimit,
        IReadOnlyList<SkillSkip>? skipped = null)
    {
        _skills = skills;
        Skipped = skipped ?? Array.Empty<SkillSkip>();
        Tool = new SkillTool(skills, sampleLimit);
    }

    /// <summary>Markdown catalog for the system prompt (mirrors opencode Skill.fmt).</summary>
    public string Prompt()
    {
        var described = _skills.Values
            .Where(s => s.Description != null)
            // (A22) CODE-POINT order, not StringComparer.Ordinal. SPEC §0.10 pins this prompt as
            // BYTE-IDENTICAL across ports, and Ordinal is UTF-16 CODE-UNIT order — above U+FFFF it
            // disagrees with the code-point order golang/python/elixir produce, so one astral-plane
            // skill name would reorder the prompt in this port alone.
            .OrderBy(s => s.Name, CodePointComparer.Instance)
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
            var (data, content, malformed, detail) = ParseFrontmatterDetailed(text);
            var abs = Path.GetFullPath(file);
            if (malformed)
            {
                output.Add(new RawCandidate(null, new SkillSkip(abs, SkillSkip.Malformed, detail)));
                continue;
            }
            if (!data.TryGetValue("name", out var name) || string.IsNullOrEmpty(name))
            {
                output.Add(new RawCandidate(null, new SkillSkip(abs, SkillSkip.MissingName, detail)));
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
        var (merged, skipped) = MergeCandidates(CollectCandidates(opts));
        var resolved = ApplyFilter(merged, opts.Filter);
        return new SkillSource(resolved, opts.SampleLimit, skipped);
    }

    /// <summary>
    /// (ADR 0028, A2) The candidates this load REFUSED, in discovery order — RETURNED DATA on the
    /// result, not a hook: a hook shape differs per port and cannot be a parity gate. Empty on a
    /// clean load. Without this a host had to run <see cref="ListSkills"/> a second time to learn
    /// that 6 of 87 skills vanished, and a load that silently drops files is indistinguishable
    /// from a directory that never had them.
    /// </summary>
    public IReadOnlyList<SkillSkip> Skipped { get; }

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
                // (A22) Same rule as the §0.10 prompt: this list is user-visible, byte-identical
                // output too, so it orders by code point rather than by UTF-16 code unit.
                var avail = _skills.Keys.OrderBy(k => k, CodePointComparer.Instance).ToList();
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
        var (data, content, malformed, _) = ParseFrontmatterDetailed(text);
        return (data, content, malformed);
    }

    /// <summary>The keys the line-wise rescue will take. Only the two §3 scalars — anything else a
    /// broken header claims is not worth guessing at.</summary>
    private static readonly string[] LenientKeys = { "name", "description" };

    /// <summary>A value opening with one of these opens a YAML construct the rescue does not
    /// implement (block scalar, anchor, alias, flow seq/map, tag). Taking the rest of the line
    /// there would produce garbage, so it takes NOTHING instead.</summary>
    private const string RefusedValueOpeners = "|>&*[{!";

    /// <summary>Column 0, ordinary key characters, one <c>:</c>; the value is the rest of the line.</summary>
    [GeneratedRegex(@"^([A-Za-z0-9_][A-Za-z0-9_.-]*):[ \t]*(.*)$")]
    private static partial Regex LenientLine();

    /// <summary>
    /// Parse the <c>---</c>-fenced frontmatter, with the lenient rescue (ADR 0028), and report the
    /// native parser error.
    ///
    /// <para><b>ORDER IS THE WHOLE DESIGN: STRICT YAML FIRST.</b> The line-wise
    /// <c>key: rest-of-line</c> read runs ONLY on a block a real YAML parser has ALREADY REFUSED.
    /// Line-wise-first is forbidden: it misparses legitimate YAML the ports handle today —
    /// <c>description: |</c>, <c>description: &gt;</c>, a plain scalar continued on the next line,
    /// <c>name: !!str x</c> — because rest-of-line is empty or a block marker there. Running it
    /// only on already-refused frontmatter means it can never see a file whose YAML semantics
    /// matter: by construction those files have none left.</para>
    ///
    /// <para>Guards on the rescue: column 0 only (an indented line is a continuation, not a key),
    /// first-wins, only <see cref="LenientKeys"/>, and a value opening a construct it does not
    /// implement is REFUSED rather than taken — a half-broken block scalar degrades to "no
    /// description", never to garbage. No description is ever invented.</para>
    /// </summary>
    internal static (Dictionary<string, string> Data, string Content, bool Malformed, string Detail)
        ParseFrontmatterDetailed(string text)
    {
        var data = new Dictionary<string, string>();
        var m = Frontmatter().Match(text);
        if (!m.Success)
            return (data, text, false, "");

        var block = m.Groups[1].Value;
        var content = m.Groups[2].Value;

        object? node = null;
        var detail = "";
        try
        {
            var deserializer = new DeserializerBuilder().Build();
            node = deserializer.Deserialize<object>(block);
        }
        catch (Exception e)
        {
            detail = e.Message; // the native parser error, verbatim (line/column included)
        }

        // (A10) The rescue triggers on THREE conditions, not one, because YAML libraries disagree
        // about the same broken file: YamlDotNet THROWS on `description: [unterminated` while JS's
        // `yaml` RECOVERS it into a sequence. Both paths must reach the same observable outcome, so
        // the rescue also runs when the parse yields a NON-MAPPING, or a mapping whose `name` or
        // `description` is PRESENT BUT NOT A STRING. The invariant that decides every case: a file
        // never gains an INVENTED description, and never silently keeps a STRUCTURALLY-WRONG one.
        if (node is not null and not IDictionary<object, object>)
        {
            node = null;
            if (detail.Length == 0) detail = "frontmatter is not a mapping";
        }
        if (node is IDictionary<object, object> map)
        {
            foreach (var key in LenientKeys)
            {
                if (!map.TryGetValue(key, out var v) || v is null) continue;
                if (v is string or bool || v.GetType().IsPrimitive) continue;
                node = null;
                if (detail.Length == 0) detail = $"frontmatter key \"{key}\" is not a scalar";
                break;
            }
        }

        if (node is IDictionary<object, object> parsed)
        {
            foreach (var (rawKey, rawValue) in parsed)
            {
                if (rawKey is null) continue;
                var key = rawKey.ToString();
                if (string.IsNullOrEmpty(key)) continue;
                if (rawValue is string or bool || (rawValue is not null && rawValue.GetType().IsPrimitive))
                    data[key] = rawValue!.ToString()!.Trim();
            }
            return (data, content, false, "");
        }
        // An EMPTY frontmatter block parses cleanly to nothing. That is not malformed — it is a
        // header with no `name`, which keeps its existing `missing-name` reason.
        if (node is null && detail.Length == 0) return (data, content, false, "");

        // YAML refused. Rescue `name`/`description` line-wise; still malformed if `name` is absent.
        foreach (var kv in LineWise(block)) data[kv.Key] = kv.Value;
        return (data, content, !data.ContainsKey("name"), detail);
    }

    /// <summary>Rescue the two known scalars from a frontmatter block YAML has already refused.</summary>
    private static Dictionary<string, string> LineWise(string block)
    {
        var data = new Dictionary<string, string>();
        foreach (var raw in block.Split('\n'))
        {
            var line = raw.TrimEnd('\r');
            if (line.Length == 0) continue;
            var c0 = line[0];
            if (c0 is ' ' or '\t' or '#') continue; // indented continuation, comment, blank
            var m = LenientLine().Match(line);
            if (!m.Success) continue;
            var key = m.Groups[1].Value;
            if (Array.IndexOf(LenientKeys, key) < 0 || data.ContainsKey(key)) continue; // first wins
            var value = m.Groups[2].Value.Trim();
            if (value.Length == 0 || RefusedValueOpeners.Contains(value[0])) continue;
            data[key] = Unquote(value);
        }
        return data;
    }

    private static string Unquote(string v)
    {
        if (v.Length > 1 && v[0] == v[^1] && (v[0] == '"' || v[0] == '\'')) return v[1..^1];
        return v;
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
            try { entries = SortedEntries(dir); }
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
        // (A1/A15) DISCOVERY ORDER is part of the contract, because first-wins decides which of
        // two same-named skills survives. Dirs apply in the order the CALLER passed them; within a
        // dir: DEPTH ASCENDING, then Unicode code point, over the path RELATIVE TO THAT DIR.
        // Never the filesystem's own order — EnumerateFileSystemEntries makes no ordering promise,
        // which is how `docx`/`pdf`/`pptx` came to resolve to DIFFERENT FILES per port.
        output.Sort((a, b) => CompareDiscovery(RelativeTo(root, a), RelativeTo(root, b)));
        return output;
    }

    private static string RelativeTo(string root, string path)
    {
        try { return Path.GetRelativePath(root, path).Replace('\\', '/'); }
        catch { return path; }
    }

    /// <summary>
    /// Directory entries in code-point order by file name, for the DISCOVERY walk only.
    /// <c>Directory.EnumerateFileSystemEntries</c> makes no ordering promise, so this keeps the
    /// walk deterministic — belt-and-braces, since A15 re-sorts the candidates explicitly afterwards.
    ///
    /// <para>The <c>&lt;skill_files&gt;</c> SAMPLE deliberately does NOT use this (A25): its order
    /// must be a function of the file set and the cap, never of the traversal.</para>
    /// </summary>
    private static List<string> SortedEntries(string dir)
    {
        var entries = new List<string>(Directory.EnumerateFileSystemEntries(dir));
        entries.Sort((a, b) => CompareCodePoints(Path.GetFileName(a), Path.GetFileName(b)));
        return entries;
    }

    /// <summary><see cref="CompareCodePoints"/> as an <see cref="IComparer{T}"/>, for the LINQ
    /// orderings that emit user-visible, byte-identical-pinned text (A22).</summary>
    internal sealed class CodePointComparer : IComparer<string>
    {
        public static readonly CodePointComparer Instance = new();
        public int Compare(string? x, string? y) => CompareCodePoints(x ?? "", y ?? "");
    }

    /// <summary>
    /// (A15) DEPTH FIRST, then code point. Depth is the number of path segments, so a top-level
    /// skill beats a nested copy of the same name UNIFORMLY, for every name.
    ///
    /// <para>Code point alone does NOT deliver that rule, which is the defect this replaces:
    /// <c>docx</c>, <c>pdf</c> and <c>pptx</c> begin with letters before <c>s</c>, so they beat
    /// <c>synced/&lt;uuid&gt;/…</c> — but <c>xlsx</c> begins with <c>x</c>, so it LOSES to
    /// <c>synced/&lt;uuid&gt;/xlsx</c>. The winner depended on the skill's first letter relative
    /// to a sibling directory's name. A <c>docx</c>-only fixture passes under both rules, which is
    /// exactly why this survived six ports and a spec review.</para>
    /// </summary>
    internal static int CompareDiscovery(string a, string b)
    {
        var da = Depth(a);
        var db = Depth(b);
        if (da != db) return da < db ? -1 : 1;
        return CompareCodePoints(a, b);
    }

    /// <summary>Path segments, over the already-normalised <c>/</c>-joined relative path.</summary>
    private static int Depth(string relative)
    {
        var n = 1;
        foreach (var c in relative) if (c == '/') n++;
        return n;
    }

    /// <summary>
    /// (A1c) CODE-POINT order — the TIE-BREAK WITHIN a depth (A15), not the whole order, which is what the other six ports compare and what the contract
    /// says. <c>string.CompareOrdinal</c> is NOT sufficient: it compares UTF-16 CODE UNITS, and
    /// above U+FFFF a surrogate pair (0xD800–0xDBFF) sorts BELOW the unpaired BMP characters
    /// U+E000–U+FFFF — so an astral-plane filename would order differently here than in Go, Python
    /// or Elixir. This is still ordinal and culture-invariant; the fix is surrogate handling, not
    /// culture. <c>string.CompareTo</c> / <c>Comparer&lt;string&gt;.Default</c> are wrong on BOTH
    /// counts and must not be used here.
    /// </summary>
    internal static int CompareCodePoints(string a, string b)
    {
        int i = 0, j = 0;
        while (i < a.Length && j < b.Length)
        {
            var ca = char.ConvertToUtf32(a, i);
            var cb = char.ConvertToUtf32(b, j);
            if (ca != cb) return ca < cb ? -1 : 1;
            i += char.IsHighSurrogate(a[i]) ? 2 : 1;
            j += char.IsHighSurrogate(b[j]) ? 2 : 1;
        }
        return (a.Length - i).CompareTo(b.Length - j);
    }

    /// <summary>
    /// The <c>&lt;skill_files&gt;</c> sample: COLLECT every candidate, SORT by the path RELATIVE to
    /// the skill directory in plain Unicode code-point order, THEN truncate to the cap (A25).
    ///
    /// <para><b>Order before cap, and the order must not be a function of the traversal.</b> Five
    /// ports fixed A24 five different ways — sorting per-directory entries during the walk, as this
    /// method used to, makes the result depend on each port reproducing the same stack discipline,
    /// which is a byte-identity break introduced by the fix for a byte-identity break. A global
    /// sort over relative paths is a function of the FILE SET and the cap alone, so there is
    /// nothing left for six other languages to reproduce.</para>
    ///
    /// <para>The cap is applied LAST for the same reason it made this a CONTENT bug rather than a
    /// cosmetic one: truncating an unordered collection changes WHICH files appear, not merely
    /// their order.</para>
    /// </summary>
    private static List<string> SampleSiblingFiles(string dir, int limit)
    {
        // 1. COLLECT — the whole candidate set. No cap here: a cap during traversal would make the
        //    contents depend on visit order, which is the defect.
        var candidates = new List<string>();
        var stack = new Stack<string>();
        stack.Push(dir);
        var seen = new HashSet<string>();
        while (stack.Count > 0)
        {
            var cur = stack.Pop();
            IEnumerable<string> entries;
            try { entries = Directory.EnumerateFileSystemEntries(cur); }
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
                else if (isFile && fn != "SKILL.md")
                {
                    candidates.Add(Path.GetFullPath(entry));
                }
            }
        }

        // 2. SORT — by the path RELATIVE to the skill directory, plain code point. Relative, so the
        //    absolute prefix (a temp dir, a home dir) can never influence the order.
        candidates.Sort((a, b) => CompareCodePoints(RelativeTo(dir, a), RelativeTo(dir, b)));

        // 3. TRUNCATE.
        if (candidates.Count > limit) candidates.RemoveRange(limit, candidates.Count - limit);
        return candidates;
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
