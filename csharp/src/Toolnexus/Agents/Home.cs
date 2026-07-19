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

    /// <summary>
    /// Compose the discovered bootstrap files into one soul string (the frozen snapshot). Present
    /// files only, in <see cref="BootstrapOrder"/>, each as a <c>## &lt;file&gt;</c> section.
    /// Returns the soul and the ordered list of files that were found.
    /// </summary>
    public static (string Soul, List<string> Found) ComposeSoul(string dir)
    {
        var sections = new List<string>();
        var found = new List<string>();
        foreach (var f in BootstrapOrder)
        {
            var body = ReadCapped(Path.Combine(dir, f));
            if (body == null) continue;
            sections.Add($"## {f}\n\n{body.Trim()}");
            found.Add(f);
        }
        return (string.Join("\n\n", sections), found);
    }

    /// <summary>
    /// The <c>memory</c> builtin (SPEC §7E — file-backed, opt-in). One tool, three actions over
    /// <c>MEMORY.md</c> (the agent's own notes) and <c>USER.md</c> (its model of the user). Writes
    /// go to DISK — the frozen snapshot in the live prompt is intentionally NOT updated
    /// (cache-stable; the next session re-reads). A <c>replace</c>/<c>remove</c> whose substring is
    /// absent is a loud <c>isError</c>.
    /// </summary>
    public static ITool MemoryTool(string dir)
    {
        string FileFor(object? target)
            => Path.Combine(dir, string.Equals(target?.ToString(), "user", StringComparison.Ordinal) ? "USER.md" : "MEMORY.md");

        var schema = new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["action"] = new Dictionary<string, object?> { ["type"] = "string", ["enum"] = new List<object?> { "add", "replace", "remove" } },
                ["target"] = new Dictionary<string, object?> { ["type"] = "string", ["enum"] = new List<object?> { "self", "user" } },
                ["text"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "For add: the entry. For replace/remove: the existing text." },
                ["with"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "For replace: the replacement text." },
            },
            ["required"] = new List<object?> { "action", "text" },
        };

        const string description =
            "Persist durable memory. action=add appends an entry; replace swaps an existing " +
            "substring; remove deletes one. target=self (MEMORY.md, default) or user (USER.md). " +
            "Writes persist to disk and load at the START of your next session — they do NOT change " +
            "your current context.";

        return NativeTool.Of("memory", description, schema, (IDictionary<string, object?> args) =>
        {
            var action = args.TryGetValue("action", out var a) ? a?.ToString() ?? "" : "";
            var text = args.TryGetValue("text", out var t) ? t?.ToString() ?? "" : "";
            var file = FileFor(args.TryGetValue("target", out var tg) ? tg : "self");
            var cur = File.Exists(file) ? File.ReadAllText(file) : "";
            string next;
            switch (action)
            {
                case "add":
                    next = (cur.TrimEnd() + $"\n- {text}\n").TrimStart();
                    break;
                case "replace":
                    if (!cur.Contains(text, StringComparison.Ordinal)) return (object)ToolResult.Error($"not found: {text}");
                    next = ReplaceFirst(cur, text, args.TryGetValue("with", out var w) ? w?.ToString() ?? "" : "");
                    break;
                case "remove":
                    if (!cur.Contains(text, StringComparison.Ordinal)) return (object)ToolResult.Error($"not found: {text}");
                    next = ReplaceFirst(cur, text, "");
                    break;
                default:
                    return (object)ToolResult.Error($"unknown action: {action}");
            }
            Directory.CreateDirectory(Path.GetDirectoryName(file)!);
            File.WriteAllText(file, next);
            // Plain success string (a miss returns a loud isError above); parity with the JS reference.
            return (object)$"ok ({action} → {Path.GetFileName(file)}); loads next session";
        });
    }

    /// <summary>First-occurrence substring replace (parity with JS <c>String.replace(string, …)</c>).</summary>
    private static string ReplaceFirst(string haystack, string needle, string replacement)
    {
        var i = haystack.IndexOf(needle, StringComparison.Ordinal);
        return i < 0 ? haystack : haystack[..i] + replacement + haystack[(i + needle.Length)..];
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
    }

    /// <summary>
    /// The directory IS the agent (SPEC §7E). Discovers bootstrap files → soul (the frozen
    /// snapshot), wires the <c>memory</c> tool over the same dir (unless disabled). The result is a
    /// plain <see cref="Agent"/> — <c>RunAsync</c> like any agent, or hand it to <see cref="StartAgent"/>.
    /// </summary>
    public static Agent FromDir(string dir, FromDirOptions? opts = null)
    {
        opts ??= new FromDirOptions();
        var (soul, _) = ComposeSoul(dir);
        var name = opts.Name ?? Path.GetFileName(dir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        var tools = new List<ITool>(opts.Tools ?? new List<ITool>());
        if (opts.Memory) tools.Add(MemoryTool(dir));
        return new Agent(name, new AgentSpec
        {
            Does = opts.Does ?? $"persona agent from {dir}",
            Soul = soul,
            Model = opts.Model ?? "inherit",
            Uses = tools,
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
        private readonly ITimer _timer;
        private readonly List<Task> _inflight = new();
        private readonly List<string> _beats = new();
        private readonly object _lock = new();
        private volatile bool _stopped;

        public AgentRuntime Runtime { get; }
        public Handle Handle { get; }

        /// <summary>Non-silent beats surfaced so far (HEARTBEAT_OK replies never appear here).</summary>
        public IReadOnlyList<string> Beats { get { lock (_lock) return _beats.ToArray(); } }

        internal StartedAgent(AgentRuntime runtime, Handle handle, TimeProvider clock, double everyMs, Action<string>? onBeat)
        {
            Runtime = runtime;
            Handle = handle;
            const string prompt =
                "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply " +
                HeartbeatOk + ".";
            var every = TimeSpan.FromMilliseconds(everyMs);
            // One-shot + self-reschedule: the callback re-arms the timer, so a virtual clock fires
            // it deterministically (Advance → tick → re-arm) exactly as the system clock does.
            _timer = clock.CreateTimer(_ =>
            {
                if (_stopped) return;
                // Unsolicited rail: the tick lands in the inbox (coalesces); the wake drains it as one turn.
                Runtime.Post(Handle, new InboxItem("clock", "timer", "tick"));
                if (Handle.State == "idle")
                {
                    var woke = Runtime.Wake(Handle, prompt); // idle→running: drains the tick(s)
                    if (woke.Ok)
                    {
                        var beat = Runtime.WaitAsync(Handle).ContinueWith(tk =>
                        {
                            var r = tk.Result;
                            if (!r.IsError && !r.Text.Contains(HeartbeatOk, StringComparison.Ordinal))
                            {
                                lock (_lock) _beats.Add(r.Text);
                                onBeat?.Invoke(r.Text);
                            }
                        }, TaskScheduler.Default);
                        lock (_lock) _inflight.Add(beat);
                    }
                }
                if (!_stopped) _timer?.Change(every, Timeout.InfiniteTimeSpan);
            }, null, every, Timeout.InfiniteTimeSpan);
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
            _timer.Dispose();
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
        var runtime = new AgentRuntime(rtOpts.CloneWithRegistry(agent.Registry()));
        var spawned = runtime.Spawn(runtime.Root, agent.Name);
        if (spawned.Error != null) throw new InvalidOperationException(spawned.Error);
        var clock = rtOpts.Clock ?? TimeProvider.System;
        return new StartedAgent(runtime, spawned.Handle!, clock, everyMs, onBeat);
    }
}
