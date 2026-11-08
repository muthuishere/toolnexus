using System.Text;

namespace Toolnexus.Agents;

/// <summary>
/// Agent home — the persona archetype over the shipped §7D runtime (SPEC §7E). Three things,
/// all riding shipped seams (the <see cref="AgentSpec.OnSpawn"/> injection point, the six verbs,
/// the runtime-wide store + injectable clock) — no runtime behavior added:
/// <list type="number">
///   <item><see cref="FromDir"/> — the directory IS the agent: ordered bootstrap files
///     (<c>AGENTS/SOUL/IDENTITY/USER/TOOLS/HEARTBEAT/MEMORY.md</c>) composed into the soul as
///     <c>## &lt;file&gt;</c> sections, 2 MB/file cap. Convention over configuration.</item>
///   <item><see cref="MemoryTool"/> — one <c>add|replace|remove</c> tool over
///     <c>MEMORY.md</c>/<c>USER.md</c> with FROZEN-SNAPSHOT injection: files are read into the
///     system prompt at session start (via the soul), mid-session writes hit DISK not the live
///     prompt, and refresh at the START of the next run (the cache-stability rule, for free).</item>
///   <item><see cref="StartAgent"/> — heartbeat: an interval posts a tick to the agent's own
///     inbox (the unsolicited rail — ticks coalesce) and, when idle, wakes it; a
///     <see cref="HeartbeatOk"/> reply stays silent so no-op beats never message anyone. All
///     timing goes through the runtime's injectable <see cref="TimeProvider"/> clock (fixtures run
///     on a virtual clock; <c>PeriodicTimer</c> belongs only in a host recipe).</item>
/// </list>
/// </summary>
public static class Home
{
    /// <summary>Canonical bootstrap order (SPEC §7E) — identity first, memory last.</summary>
    public static readonly IReadOnlyList<string> BootstrapOrder = new[]
    {
        "AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md",
    };

    /// <summary>Per-file bootstrap byte cap (SPEC §7E): larger ⇒ truncated with a notice.</summary>
    public const int MaxFileBytes = 2 * 1024 * 1024;

    /// <summary>A heartbeat reply containing this token is SILENT — no report to the host.</summary>
    public const string HeartbeatOk = "HEARTBEAT_OK";

    private const string TruncationNotice = "\n[truncated: exceeds 2 MB bootstrap cap]";

    /// <summary>Read a bootstrap file with the byte cap; null if absent. The cap is measured in
    /// UTF-8 BYTES (SPEC §7E), and truncation is byte-based at a rune boundary.</summary>
    private static string? ReadCapped(string path)
    {
        if (!File.Exists(path)) return null;
        var body = File.ReadAllText(path);
        return Encoding.UTF8.GetByteCount(body) > MaxFileBytes
            ? TruncateToBytes(body, MaxFileBytes) + TruncationNotice
            : body;
    }

    /// <summary>Truncate a string to at most <paramref name="maxBytes"/> UTF-8 bytes without
    /// splitting a rune (back off any dangling continuation bytes).</summary>
    private static string TruncateToBytes(string s, int maxBytes)
    {
        var bytes = Encoding.UTF8.GetBytes(s);
        if (bytes.Length <= maxBytes) return s;
        var len = maxBytes;
        while (len > 0 && (bytes[len] & 0xC0) == 0x80) len--; // don't cut mid-rune
        return Encoding.UTF8.GetString(bytes, 0, len);
    }

    /// <summary>The preset index cap (SPEC §7E "Memory hierarchy"): 200 lines / 25600 bytes.</summary>
    public static readonly MemoryIndexCap MemoryIndexCapPreset = new() { MaxLines = 200, MaxBytes = 25600 };

    /// <summary>
    /// The <c>memoryIndexCap</c> option: a second, smaller injection cap for <c>MEMORY.md</c> only
    /// (whole lines). <see cref="MemoryIndexCapPreset"/> is the "<c>true</c>" of the other ports;
    /// a null field takes the preset's value; a value &lt; 1 is a construction error.
    /// </summary>
    public sealed record MemoryIndexCap
    {
        public int? MaxLines { get; init; }
        public int? MaxBytes { get; init; }
    }

    /// <summary>Options for <see cref="ComposeSoul(string, ComposeSoulOptions?)"/>. Absent ⇒ byte-identical soul.</summary>
    public sealed class ComposeSoulOptions
    {
        /// <summary>Cap the injected <c>MEMORY.md</c>; null (default) ⇒ no cap.</summary>
        public MemoryIndexCap? MemoryIndexCap { get; set; }

        /// <summary>Notes tier on — selects the notice wording that points at <c>note:&lt;slug&gt;</c>.</summary>
        public bool Notes { get; set; }
    }

    private static (int MaxLines, int MaxBytes)? ResolveIndexCap(MemoryIndexCap? c)
    {
        if (c == null) return null;
        var lines = c.MaxLines ?? MemoryIndexCapPreset.MaxLines!.Value;
        var bytes = c.MaxBytes ?? MemoryIndexCapPreset.MaxBytes!.Value;
        if (lines < 1 || bytes < 1)
            throw new ArgumentException("memoryIndexCap: maxLines and maxBytes must be integers >= 1");
        return (lines, bytes);
    }

    /// <summary>The exact notice appended to a capped <c>MEMORY.md</c> (SPEC §7E).</summary>
    public static string MemoryIndexNotice(int maxLines, int maxBytes, bool notes)
    {
        var tail = notes ? "; move detail into notes (memory target \"note:<slug>\")." : " (keep entries short).";
        return $"\n\n[MEMORY.md index truncated at {maxLines} lines / {maxBytes} bytes. Keep MEMORY.md a short index{tail}]";
    }

    /// <summary>
    /// Apply the index cap to <c>MEMORY.md</c>'s (already 2 MB-capped) content (SPEC §7E, exact):
    /// split on "\n" (a trailing "\n" starts no line); within both bounds ⇒ unchanged; else keep the
    /// first <c>maxLines</c> lines, drop trailing lines while over <c>maxBytes</c> (a lone over-long
    /// line is cut at a code-point boundary), then append <see cref="MemoryIndexNotice"/>.
    /// </summary>
    public static string ApplyIndexCap(string content, int maxLines, int maxBytes, bool notes = false)
    {
        var lines = content.Split('\n').ToList();
        if (content.EndsWith('\n')) lines.RemoveAt(lines.Count - 1);
        if (lines.Count <= maxLines && Encoding.UTF8.GetByteCount(string.Join("\n", lines)) <= maxBytes) return content;
        var kept = lines.Take(maxLines).ToList();
        while (kept.Count > 1 && Encoding.UTF8.GetByteCount(string.Join("\n", kept)) > maxBytes) kept.RemoveAt(kept.Count - 1);
        var text = string.Join("\n", kept);
        if (Encoding.UTF8.GetByteCount(text) > maxBytes) text = CutUtf8(text, maxBytes);
        return text + MemoryIndexNotice(maxLines, maxBytes, notes);
    }

    /// <summary>Cut at the last whole code point whose UTF-8 end is &lt;= <paramref name="max"/>.</summary>
    private static string CutUtf8(string s, int max)
    {
        var sb = new StringBuilder();
        var bytes = 0;
        foreach (var r in s.EnumerateRunes())
        {
            var n = r.Utf8SequenceLength;
            if (bytes + n > max) break;
            sb.Append(r.ToString());
            bytes += n;
        }
        return sb.ToString();
    }

    /// <summary>
    /// Compose the discovered bootstrap files into one soul string (the frozen snapshot). Present
    /// files only, in <see cref="BootstrapOrder"/>, each as a <c>## &lt;file&gt;</c> section.
    /// Returns the soul and the ordered list of files that were found.
    /// </summary>
    public static (string Soul, List<string> Found) ComposeSoul(string dir) => ComposeSoul(dir, null);

    /// <summary>
    /// <see cref="ComposeSoul(string)"/> with the memory-hierarchy options: the <c>MEMORY.md</c>
    /// index cap applies after the 2 MB read cap and before the section trim. Disk is never touched.
    /// </summary>
    public static (string Soul, List<string> Found) ComposeSoul(string dir, ComposeSoulOptions? options)
    {
        var cap = ResolveIndexCap(options?.MemoryIndexCap);
        var sections = new List<string>();
        var found = new List<string>();
        foreach (var f in BootstrapOrder)
        {
            var body = ReadCapped(Path.Combine(dir, f));
            if (body == null) continue;
            if (cap is { } c && f == "MEMORY.md") body = ApplyIndexCap(body, c.MaxLines, c.MaxBytes, options!.Notes);
            sections.Add($"## {f}\n\n{body.Trim()}");
            found.Add(f);
        }
        return (string.Join("\n\n", sections), found);
    }

    /// <summary>The notes-off <c>memory</c> description (SPEC §7E, byte-exact).</summary>
    public const string MemoryDescription =
        "Persist durable memory. action=add appends an entry; replace swaps an existing " +
        "substring (with); remove deletes one. target=self (MEMORY.md, default) or user (USER.md). " +
        "Writes persist to disk and load at the START of your NEXT session — they do NOT change " +
        "your current context.";

    /// <summary>The notes-on <c>memory</c> description (SPEC §7E, byte-exact).</summary>
    public const string MemoryNotesDescription =
        "Persist durable memory. MEMORY.md is a short index loaded at the start of every " +
        "session; put detail in notes (target note:<slug>, stored in memory/<slug>.md, never " +
        "loaded automatically) and point to them from the index. Record what will change a " +
        "future decision. action=add appends an entry; replace swaps the first occurrence of " +
        "an existing substring (with); remove deletes it; read returns a file; list lists the " +
        "notes. target=self (MEMORY.md, default), user (USER.md) or note:<slug> (slug: " +
        "lowercase letters, digits, '.', '_', '-'). Writes persist to disk and load at the " +
        "START of your NEXT session — they do NOT change your current context.";

    /// <summary>A valid note slug: lowercase, digits, '.', '_', '-'; no separators; no "..".</summary>
    public static bool IsValidNoteSlug(string slug)
        => System.Text.RegularExpressions.Regex.IsMatch(slug, @"\A[a-z0-9][a-z0-9._-]{0,63}\z")
           && !slug.Contains("..", StringComparison.Ordinal);

    /// <summary>Per resolved absolute path: one lock, so read-modify-write never interleaves in-process.</summary>
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, object> PathLocks = new(StringComparer.Ordinal);

    /// <summary>
    /// The <c>memory</c> builtin (SPEC §7E — file-backed, opt-in). One tool, three actions over
    /// <c>MEMORY.md</c> (the agent's own notes) and <c>USER.md</c> (its model of the user):
    /// <c>add</c>, <c>replace</c>/<c>remove</c> (first occurrence). Writes go to DISK — the frozen
    /// snapshot in the live prompt is intentionally NOT updated (cache-stable; the next session
    /// re-reads). A <c>replace</c>/<c>remove</c> whose substring is absent is a loud <c>isError</c>.
    /// With <paramref name="notes"/> on, it also takes <c>target: "note:&lt;slug&gt;"</c>
    /// (<c>&lt;dir&gt;/memory/&lt;slug&gt;.md</c>, never injected) and the <c>read</c>/<c>list</c> actions.
    /// </summary>
    public static ITool MemoryTool(string dir, bool notes = false)
    {
        if (notes) return NotesMemoryTool(dir);
        var schema = new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["action"] = new Dictionary<string, object?> { ["type"] = "string", ["enum"] = new List<object?> { "add", "replace", "remove" } },
                ["target"] = new Dictionary<string, object?>
                {
                    ["type"] = "string", ["enum"] = new List<object?> { "self", "user" },
                    ["description"] = "self=MEMORY.md (default), user=USER.md",
                },
                ["text"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "For add: the entry. For replace/remove: the existing text." },
                ["with"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "For replace: the replacement text." },
            },
            ["required"] = new List<object?> { "action", "text" },
        };

        return NativeTool.Of("memory", MemoryDescription, schema, (IDictionary<string, object?> args) =>
        {
            var action = args.TryGetValue("action", out var a) ? a?.ToString() ?? "" : "";
            var text = args.TryGetValue("text", out var t) ? t?.ToString() ?? "" : "";
            var file = SelfOrUser(dir, args.TryGetValue("target", out var tg) ? tg?.ToString() : null);
            var err = ReadModifyWrite(file, action, text, args.TryGetValue("with", out var w) ? w?.ToString() : null);
            if (err != null) return err;
            // Plain success string (a miss returns a loud isError above); parity with the JS reference.
            return (object)$"ok ({action} → {Path.GetFileName(file)}); loads next session";
        });
    }

    private static string SelfOrUser(string dir, string? target)
        => Path.Combine(dir, string.Equals(target, "user", StringComparison.Ordinal) ? "USER.md" : "MEMORY.md");

    private static ITool NotesMemoryTool(string dir)
    {
        var notesDir = Path.Combine(dir, "memory");
        var schema = new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["action"] = new Dictionary<string, object?> { ["type"] = "string", ["enum"] = new List<object?> { "add", "replace", "remove", "read", "list" } },
                ["target"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "self=MEMORY.md (default), user=USER.md, note:<slug>=memory/<slug>.md" },
                ["text"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "For add: the entry. For replace/remove: the existing text." },
                ["with"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "For replace: the replacement text." },
            },
            ["required"] = new List<object?> { "action" },
        };

        return NativeTool.Of("memory", MemoryNotesDescription, schema, (IDictionary<string, object?> args) =>
        {
            var action = args.TryGetValue("action", out var a) ? a?.ToString() ?? "" : "";
            if (action == "list") return (object)ListNotes(notesDir);
            var target = args.TryGetValue("target", out var tg) && tg != null ? tg.ToString()! : "self";
            string file, label;
            if (target is "self" or "user")
            {
                file = SelfOrUser(dir, target);
                label = Path.GetFileName(file);
            }
            else if (target.StartsWith("note:", StringComparison.Ordinal))
            {
                var slug = target[5..];
                if (!IsValidNoteSlug(slug)) return ToolResult.Error($"invalid note slug: {slug}");
                file = Path.Combine(notesDir, slug + ".md");
                label = $"memory/{slug}.md";
            }
            else return ToolResult.Error($"unknown target: {target}");

            if (action == "read")
            {
                var body = ReadCapped(file);
                return body == null ? ToolResult.Error($"not found: {target}") : body;
            }
            if (action is not ("add" or "replace" or "remove")) return ToolResult.Error($"unknown action: {action}");
            if (!args.TryGetValue("text", out var t) || t is not string text) return ToolResult.Error("text is required");
            var err = ReadModifyWrite(file, action, text, args.TryGetValue("with", out var w) ? w?.ToString() : null);
            if (err != null) return err;
            return label.StartsWith("memory/", StringComparison.Ordinal)
                ? $"ok ({action} → {label})"
                : $"ok ({action} → {label}); loads next session";
        });
    }

    /// <summary><c>list</c>: <c>&lt;slug&gt;\t&lt;bytes&gt;</c> per valid note, slug-ascending by explicit ordinal sort.</summary>
    private static string ListNotes(string notesDir)
    {
        var rows = new List<(string Slug, long Bytes)>();
        if (Directory.Exists(notesDir))
        {
            foreach (var path in Directory.EnumerateFileSystemEntries(notesDir))
            {
                var name = Path.GetFileName(path);
                if (!name.EndsWith(".md", StringComparison.Ordinal)) continue;
                var slug = name[..^3];
                if (!IsValidNoteSlug(slug)) continue;
                // Only REGULAR files, judged without following symlinks (lstat): a symlink (to a
                // file, or dangling) and a directory are skipped, never an error (§7E).
                try
                {
                    var fi = new FileInfo(path);
                    if (!fi.Exists || fi.LinkTarget != null
                        || (fi.Attributes & (FileAttributes.Directory | FileAttributes.ReparsePoint)) != 0) continue;
                    rows.Add((slug, fi.Length));
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
        rows.Sort((x, y) => string.CompareOrdinal(x.Slug, y.Slug));
        return rows.Count == 0 ? "(no notes)" : string.Join("\n", rows.Select(r => $"{r.Slug}\t{r.Bytes}"));
    }

    /// <summary>
    /// Read-modify-write over the FULL file (never the capped view), serialized per resolved
    /// absolute path within this process, finished by an atomic fsynced replace. Returns an error
    /// result on a miss, else null.
    /// </summary>
    private static ToolResult? ReadModifyWrite(string file, string action, string text, string? with)
    {
        var key = RealPath(file);
        lock (PathLocks.GetOrAdd(key, _ => new object()))
        {
            var cur = File.Exists(file) ? File.ReadAllText(file) : "";
            string next;
            switch (action)
            {
                case "add":
                    next = (cur.TrimEnd() + $"\n- {text}\n").TrimStart();
                    break;
                case "replace":
                    if (!cur.Contains(text, StringComparison.Ordinal)) return ToolResult.Error($"not found: {text}");
                    next = ReplaceFirst(cur, text, with ?? "");
                    break;
                case "remove":
                    if (!cur.Contains(text, StringComparison.Ordinal)) return ToolResult.Error($"not found: {text}");
                    next = ReplaceFirst(cur, text, "");
                    break;
                default:
                    return ToolResult.Error($"unknown action: {action}");
            }
            AtomicWrite(file, next);
            return null;
        }
    }

    /// <summary>
    /// The lock key (§7E): the REAL path — every symlink along it resolved, component by component;
    /// components that do not exist yet (the file, or its directories) are appended as-is to the
    /// nearest existing, resolved ancestor.
    /// </summary>
    internal static string RealPath(string path, int depth = 0)
    {
        var full = Path.GetFullPath(path);
        if (depth > 32) return full;
        var root = Path.GetPathRoot(full) ?? "";
        var cur = root;
        foreach (var part in full[root.Length..].Split(new[] { Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar }, StringSplitOptions.RemoveEmptyEntries))
        {
            var next = Path.Combine(cur, part);
            try
            {
                FileSystemInfo fi = new FileInfo(next);
                if (fi.LinkTarget == null && Directory.Exists(next)) fi = new DirectoryInfo(next);
                if (fi.LinkTarget != null && fi.ResolveLinkTarget(returnFinalTarget: true) is { } target)
                    next = RealPath(target.FullName, depth + 1);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
            cur = next;
        }
        return cur;
    }

    /// <summary>Atomic replace: temp file in the same dir, flushed to disk (fsync), renamed over the
    /// target; an existing file's permission bits are kept.</summary>
    private static void AtomicWrite(string file, string data)
    {
        var dirName = Path.GetDirectoryName(Path.GetFullPath(file))!;
        Directory.CreateDirectory(dirName);
        var tmp = Path.Combine(dirName, $".{Path.GetFileName(file)}.{Environment.ProcessId}.{Guid.NewGuid():N}.tmp");
        try
        {
            using (var fs = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                var bytes = new UTF8Encoding(false).GetBytes(data);
                fs.Write(bytes, 0, bytes.Length);
                fs.Flush(flushToDisk: true);
            }
            if (!OperatingSystem.IsWindows() && File.Exists(file))
                File.SetUnixFileMode(tmp, File.GetUnixFileMode(file));
            File.Move(tmp, file, overwrite: true);
        }
        finally { if (File.Exists(tmp)) File.Delete(tmp); }
    }

    /// <summary>First-occurrence substring replace (parity with JS <c>String.replace(string, …)</c>).</summary>
    private static string ReplaceFirst(string haystack, string needle, string replacement)
    {
        var i = haystack.IndexOf(needle, StringComparison.Ordinal);
        return i < 0 ? haystack : haystack[..i] + replacement + haystack[(i + needle.Length)..];
    }

    /// <summary>Scheduled dreaming for <see cref="FromDir"/> (SPEC §7E "Dreaming").</summary>
    public sealed class DreamOptions
    {
        /// <summary>Dream interval in ms; must be &gt; 0.</summary>
        public double EveryMs { get; set; }

        /// <summary>The transcripts to verify memory against (must be listable).</summary>
        public IConversationStore? Store { get; set; }

        /// <summary>Host prose appended after <see cref="DreamPrompt"/>.</summary>
        public string? Instructions { get; set; }
    }

    /// <summary>Options for <see cref="FromDir"/>.</summary>
    public sealed class FromDirOptions
    {
        /// <summary>Routing description; defaults to <c>persona agent from &lt;dir&gt;</c>.</summary>
        public string? Does { get; set; }

        /// <summary>Agent name; defaults to the directory's base name.</summary>
        public string? Name { get; set; }

        /// <summary>Model id; defaults to <c>inherit</c>.</summary>
        public string? Model { get; set; }

        /// <summary>Extra tools beyond the memory builtin.</summary>
        public List<ITool>? Tools { get; set; }

        /// <summary>Set false to omit the memory tool (a read-only persona).</summary>
        public bool Memory { get; set; } = true;

        /// <summary>Cap the injected <c>MEMORY.md</c> (<see cref="MemoryIndexCapPreset"/> = 200 lines / 25600 bytes). Off by default.</summary>
        public MemoryIndexCap? MemoryIndexCap { get; set; }

        /// <summary>Turn on the notes tier of the memory tool (<c>note:&lt;slug&gt;</c>, <c>read</c>, <c>list</c>).</summary>
        public bool Notes { get; set; }

        /// <summary>Dream on a schedule when started with <see cref="StartAgent"/>. Off by default.</summary>
        public DreamOptions? Dream { get; set; }
    }

    /// <summary>Dream config attached by <c>FromDir(…, {Dream})</c>, read by <see cref="StartAgent"/>.</summary>
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Agent, Tuple<Agent, double>> Dreams = new();

    /// <summary>
    /// The directory IS the agent (SPEC §7E). Discovers bootstrap files → soul (the frozen
    /// snapshot), wires the <c>memory</c> tool over the same dir (unless disabled). The result is a
    /// plain <see cref="Agent"/> — <c>RunAsync</c> like any agent, or hand it to <see cref="StartAgent"/>.
    /// </summary>
    public static Agent FromDir(string dir, FromDirOptions? opts = null)
    {
        opts ??= new FromDirOptions();
        var (soul, _) = ComposeSoul(dir, new ComposeSoulOptions { MemoryIndexCap = opts.MemoryIndexCap, Notes = opts.Notes });
        var name = opts.Name ?? BaseName(dir);
        var tools = new List<ITool>(opts.Tools ?? new List<ITool>());
        if (opts.Memory) tools.Add(MemoryTool(dir, opts.Notes));
        var persona = new Agent(name, new AgentSpec
        {
            Does = opts.Does ?? $"persona agent from {dir}",
            Soul = soul,
            Model = opts.Model ?? "inherit",
            Uses = tools,
        });
        if (opts.Dream is { } d)
        {
            if (!(d.EveryMs > 0) || double.IsInfinity(d.EveryMs))
                throw new ArgumentException("fromDir: dream.everyMs must be > 0");
            var dreamerAgent = Dreamer(dir, new DreamerOptions { Store = d.Store, Instructions = d.Instructions, Name = $"{name}-dreamer" });
            Dreams.AddOrUpdate(persona, Tuple.Create(dreamerAgent, d.EveryMs));
        }
        return persona;
    }

    private static string BaseName(string dir)
        => Path.GetFileName(dir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));

    /// <summary>The wake prompt each heartbeat sends (SPEC §7E).</summary>
    public const string HeartbeatPrompt =
        "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply " +
        HeartbeatOk + ".";

    /// <summary>
    /// What a dream pass is FOR and the order it works in (SPEC §7E "Dreaming"), byte-identical in
    /// every port. It becomes the dreamer's <c>## HEARTBEAT.md</c> section, so the ordinary
    /// <see cref="HeartbeatPrompt"/> wake drives a pass and <see cref="HeartbeatOk"/> keeps an idle pass silent.
    /// </summary>
    public static readonly string DreamPrompt = string.Join("\n",
        "You are dreaming: an offline pass over your own memory. Memory exists so that a future session decides better. MEMORY.md is a short index loaded at the start of every session; notes (memory target \"note:<slug>\") hold the detail; the transcripts (transcript_search) are the ground truth of what actually happened.",
        "Work in this order:",
        "1. Verify: read MEMORY.md and the notes it points to (memory read, memory list) and check each claim against the transcripts. Fix or remove a claim only when it is contradicted by the transcripts; a claim they do not mention stays (transcripts may be pruned).",
        "2. Demote: move detail out of MEMORY.md into a note and leave a one-line [[slug]] pointer, so the index stays short.",
        "3. Merge: fold duplicate or conflicting notes into one.",
        "4. Repair links: point every [[slug]] at an existing note or create it; give every note an index line.",
        "5. Log: if you changed anything, append exactly one entry with memory add, target \"note:log\", text starting \"dream: \" and naming every change and why.",
        "If nothing needs changing, write nothing and reply HEARTBEAT_OK.");

    /// <summary>Options for <see cref="Dreamer"/>.</summary>
    public sealed class DreamerOptions
    {
        /// <summary>The transcripts to verify memory against — must be listable. Required.</summary>
        public IConversationStore? Store { get; set; }

        /// <summary>Host prose appended after <see cref="DreamPrompt"/> (and after <c>DREAM.md</c>).</summary>
        public string? Instructions { get; set; }

        /// <summary>Agent name; default <c>&lt;basename(dir)&gt;-dreamer</c>.</summary>
        public string? Name { get; set; }

        /// <summary>Model id; default <c>inherit</c> (use a cheap model on the client).</summary>
        public string? Model { get; set; }
    }

    /// <summary>
    /// The built-in dreamer preset (SPEC §7E "Dreaming"): a plain <see cref="Agent"/> whose tools
    /// are EXACTLY <c>memory</c> (notes on, over <paramref name="dir"/>) and <c>transcript_search</c>
    /// (over the store). Soul = one <c>## HEARTBEAT.md</c> section: <see cref="DreamPrompt"/>, then
    /// <c>DREAM.md</c> (trimmed, if non-empty), then the instructions (trimmed, if non-empty).
    /// </summary>
    public static Agent Dreamer(string dir, DreamerOptions? opts)
    {
        if (opts?.Store == null) throw new ArgumentException("dreamer: store is required");
        var search = TranscriptSearch.Tool(opts.Store);
        var parts = new List<string> { DreamPrompt };
        var dreamMd = ReadCapped(Path.Combine(dir, "DREAM.md"))?.Trim();
        if (!string.IsNullOrEmpty(dreamMd)) parts.Add(dreamMd);
        var extra = opts.Instructions?.Trim();
        if (!string.IsNullOrEmpty(extra)) parts.Add(extra);
        return new Agent(opts.Name ?? $"{BaseName(dir)}-dreamer", new AgentSpec
        {
            Does = $"dreams over the memory in {dir}",
            Soul = "## HEARTBEAT.md\n\n" + string.Join("\n\n", parts),
            Model = opts.Model ?? "inherit",
            Uses = new List<ITool> { new DreamGuardTool(MemoryTool(dir, notes: true)), search },
        });
    }

    /// <summary>
    /// A long-lived persona started by <see cref="StartAgent"/>: its runtime + handle, the beats it
    /// has surfaced (HEARTBEAT_OK stripped), and a graceful <see cref="StopAsync"/>. Timing runs on
    /// the runtime's injectable clock — advance a virtual clock (or wait on the system clock) to
    /// drive beats.
    /// </summary>
    public sealed class StartedAgent
    {
        private readonly List<ITimer> _timers = new();
        private readonly List<Task> _inflight = new();
        private readonly List<string> _beats = new();
        private readonly object _lock = new();
        private volatile bool _stopped;

        public AgentRuntime Runtime { get; }
        public Handle Handle { get; }

        /// <summary>Non-silent beats surfaced so far (HEARTBEAT_OK replies never appear here).</summary>
        public IReadOnlyList<string> Beats { get { lock (_lock) return _beats.ToArray(); } }

        internal StartedAgent(AgentRuntime runtime, Handle handle, TimeProvider clock, double everyMs, Action<string>? onBeat,
            Handle? dreamHandle = null, double dreamEveryMs = 0)
        {
            Runtime = runtime;
            Handle = handle;
            _timers.Add(Heartbeat(clock, handle, everyMs, text =>
            {
                lock (_lock) _beats.Add(text);
                onBeat?.Invoke(text);
            }));
            // FromDir(…, {Dream}): the dreamer runs on the SAME runtime on its own interval. Its
            // replies are housekeeping — they never reach onBeat; what it did is in memory/log.md.
            if (dreamHandle != null) _timers.Add(Heartbeat(clock, dreamHandle, dreamEveryMs, null));
        }

        /// <summary>One self-rescheduling heartbeat on <paramref name="h"/>.</summary>
        private ITimer Heartbeat(TimeProvider clock, Handle h, double everyMs, Action<string>? onText)
        {
            var every = TimeSpan.FromMilliseconds(everyMs);
            ITimer? timer = null;
            // One-shot + self-reschedule: the callback re-arms the timer, so a virtual clock fires
            // it deterministically (Advance → tick → re-arm) exactly as the system clock does.
            timer = clock.CreateTimer(_ =>
            {
                if (_stopped) return;
                // Unsolicited rail: the tick lands in the inbox (coalesces); the wake drains it as one turn.
                Runtime.Post(h, new InboxItem("clock", "timer", "tick"));
                if (h.State == "idle")
                {
                    var woke = Runtime.Wake(h, HeartbeatPrompt); // idle→running: drains the tick(s)
                    if (woke.Ok)
                    {
                        var beat = Runtime.WaitAsync(h).ContinueWith(tk =>
                        {
                            var r = tk.Result;
                            if (!r.IsError && !r.Text.Contains(HeartbeatOk, StringComparison.Ordinal)) onText?.Invoke(r.Text);
                        }, TaskScheduler.Default);
                        lock (_lock) _inflight.Add(beat);
                    }
                }
                if (!_stopped) timer?.Change(every, Timeout.InfiniteTimeSpan);
            }, null, every, Timeout.InfiniteTimeSpan);
            return timer;
        }

        /// <summary>Await every beat turn kicked off so far (deterministic virtual-clock draining).</summary>
        public async Task SettleAsync()
        {
            Task[] pending;
            lock (_lock) pending = _inflight.ToArray();
            await Task.WhenAll(pending).ConfigureAwait(false);
        }

        /// <summary>Stop the heartbeat and close the persona's subtree (graceful).</summary>
        public async Task StopAsync()
        {
            _stopped = true;
            foreach (var t in _timers) t.Dispose();
            await SettleAsync().ConfigureAwait(false);
            await Runtime.CloseAsync(Runtime.Root).ConfigureAwait(false);
        }
    }

    /// <summary>
    /// Start a long-lived persona (SPEC §7E heartbeat). Builds a runtime from the agent's registry,
    /// spawns it, and arms a heartbeat on the runtime's injectable clock: each interval posts a tick
    /// to the agent's own inbox and, when idle, wakes it to read <c>HEARTBEAT.md</c> and act, else
    /// reply <see cref="HeartbeatOk"/>. HEARTBEAT_OK replies are silent. Inbound channels stay the
    /// HOST's job — deliver external events by calling <c>Post</c>/<c>Wake</c> on the returned
    /// runtime/handle.
    /// </summary>
    public static StartedAgent StartAgent(Agent agent, RuntimeOptions rtOpts, double everyMs, Action<string>? onBeat = null)
    {
        Dreams.TryGetValue(agent, out var dream);
        var registry = agent.Registry();
        // Checked BEFORE anything is spawned or scheduled: a merge would silently shadow one agent.
        if (dream != null && registry.ContainsKey(dream.Item1.Name))
            throw new InvalidOperationException($"startAgent: dreamer name collision: {dream.Item1.Name} is already registered");
        if (dream != null)
        {
            var merged = dream.Item1.Registry();
            foreach (var (k, v) in registry) merged[k] = v;
            registry = merged;
        }
        var runtime = new AgentRuntime(rtOpts.CloneWithRegistry(registry));
        var spawned = runtime.Spawn(runtime.Root, agent.Name);
        if (spawned.Error != null) throw new InvalidOperationException(spawned.Error);
        Handle? dreamHandle = null;
        if (dream != null)
        {
            var ds = runtime.Spawn(runtime.Root, dream.Item1.Name);
            if (ds.Error != null)
            {
                // No heartbeat is armed yet (StartedAgent arms it); close the runtime so nothing leaks.
                runtime.CloseAsync(runtime.Root).GetAwaiter().GetResult();
                throw new InvalidOperationException(ds.Error);
            }
            dreamHandle = ds.Handle;
        }
        var clock = rtOpts.Clock ?? TimeProvider.System;
        return new StartedAgent(runtime, spawned.Handle!, clock, everyMs, onBeat, dreamHandle, dream?.Item2 ?? 0);
    }
}

/// <summary>The dream guard (SPEC §7E "Dreaming"): the dreamer's <c>memory</c> tool refuses every
/// action on <c>USER.md</c> and <c>replace</c>/<c>remove</c> on <c>note:log</c>, before anything is read
/// or written, so a dream never touches the user model and its audit trail is append-only.</summary>
internal sealed class DreamGuardTool(ITool inner) : ITool
{
    public string Name => inner.Name;
    public string Description => inner.Description;
    public IDictionary<string, object?> InputSchema => inner.InputSchema;
    public string Source => inner.Source;
    public ToolHints? Hints => inner.Hints;
    public bool IsConcurrencySafe(IDictionary<string, object?> args) => inner.IsConcurrencySafe(args);

    public Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
    {
        var target = args.TryGetValue("target", out var t) ? t?.ToString() : null;
        var action = args.TryGetValue("action", out var a) ? a?.ToString() : null;
        if (target == "user") return Task.FromResult(ToolResult.Error("dreamer: USER.md is off-limits"));
        if (target == "note:log" && action is "replace" or "remove")
            return Task.FromResult(ToolResult.Error("dreamer: memory/log.md is append-only"));
        return inner.ExecuteAsync(args, ctx);
    }
}
