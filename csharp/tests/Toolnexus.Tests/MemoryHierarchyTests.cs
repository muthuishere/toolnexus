using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Toolnexus;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>A stub conversation store over fixture conversations: list() in fixture order, never written.</summary>
internal sealed class StubStore : IListableConversationStore
{
    private readonly List<(string Id, List<object?> Messages)> _convs;
    public int Reads;

    public StubStore(JsonNode conversations)
    {
        _convs = conversations.AsArray()
            .Select(c => (c!["id"]!.GetValue<string>(),
                (List<object?>)Json.FromElement(JsonDocument.Parse(c["messages"]!.ToJsonString()).RootElement)!))
            .ToList();
    }

    public Task<List<object?>?> GetAsync(string id)
    {
        Interlocked.Increment(ref Reads);
        return Task.FromResult(_convs.Where(c => c.Id == id).Select(c => c.Messages).FirstOrDefault());
    }

    public Task SaveAsync(string id, List<object?> messages) => throw new InvalidOperationException("transcript search must never write");

    public Task<List<ConversationInfo>> ListAsync()
    {
        Interlocked.Increment(ref Reads);
        return Task.FromResult(_convs.Select(c => new ConversationInfo(c.Id, DateTimeOffset.UnixEpoch)).ToList());
    }
}

/// <summary>A store without list() — transcript search must refuse it.</summary>
internal sealed class NoListStore : IConversationStore
{
    public Task<List<object?>?> GetAsync(string id) => Task.FromResult<List<object?>?>(null);
    public Task SaveAsync(string id, List<object?> messages) => Task.CompletedTask;
}

/// <summary>
/// The dreaming fixture's scripted LLM (<c>examples/dreaming/fixture.json → mockLLM</c>): the
/// dreamer is recognised by its system prompt ("You are dreaming:"), everything else by model id.
/// </summary>
internal sealed class DreamMockHandler : HttpMessageHandler
{
    public string DreamScript = "dreamer-fix";
    private readonly JsonNode _scripts;

    public DreamMockHandler(JsonNode scripts) => _scripts = scripts;

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var body = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct).ConfigureAwait(false))!;
        var msgs = body["messages"]!.AsArray();
        var sys = msgs.FirstOrDefault(m => m!["role"]?.GetValue<string>() == "system")?["content"]?.ToString() ?? "";
        var tool = msgs.Where(m => m!["role"]?.GetValue<string>() == "tool").Select(m => m!["content"]?.ToString() ?? "").ToList();
        if (sys.Contains("You are dreaming:"))
        {
            var steps = _scripts[DreamScript]!.AsArray();
            if (DreamScript == "dreamer-idle") return MockLlm.Text(steps[0]!["text"]!.GetValue<string>());
            HttpResponseMessage Call(int n) => MockLlm.Call(
                steps[n]!["toolCalls"]![0]!["name"]!.GetValue<string>(),
                Json.ToMap(steps[n]!["toolCalls"]![0]!["args"]!.ToJsonString()), $"d{n}");
            var k = tool.Count;
            if (k is 0 or 1 or 3) return Call(k);
            if (k == 2)
                return tool[0].Contains("Mt. Moon exit is south") && tool[1].Contains("exit is north")
                    ? Call(2)
                    : MockLlm.Text("HEARTBEAT_OK");
            return MockLlm.Text(steps[4]!["text"]!.GetValue<string>());
        }
        return body["model"]!.GetValue<string>() switch
        {
            "m-navigator" => MockLlm.Text(sys.Contains("Mt. Moon exit is north") ? "Head north out of Mt. Moon." : "Head south out of Mt. Moon."),
            _ => MockLlm.Text("HEARTBEAT_OK"),
        };
    }
}

/// <summary>
/// SPEC §7E "Memory hierarchy" + "Dreaming", driven by the shared fixtures
/// <c>examples/memory-hierarchy/fixture.json</c> and <c>examples/dreaming/fixture.json</c>.
/// Hermetic: scripted in-process LLM, virtual clock, temp dirs.
/// </summary>
public class MemoryHierarchyTests
{
    private static readonly JsonNode MH = JsonNode.Parse(File.ReadAllText(TestFixtures.Fixture("memory-hierarchy/fixture.json")))!;
    private static readonly JsonNode DR = JsonNode.Parse(File.ReadAllText(TestFixtures.Fixture("dreaming/fixture.json")))!;

    private static string Sha256(string s) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s))).ToLowerInvariant();

    private static string MkHome(IEnumerable<KeyValuePair<string, string>> files)
    {
        var dir = Directory.CreateTempSubdirectory("memh-").FullName;
        foreach (var (f, body) in files)
        {
            var p = Path.Combine(dir, f);
            Directory.CreateDirectory(Path.GetDirectoryName(p)!);
            File.WriteAllBytes(p, Encoding.UTF8.GetBytes(body));
        }
        return dir;
    }

    private static Dictionary<string, string> Files(JsonNode? obj)
        => obj?.AsObject().ToDictionary(kv => kv.Key, kv => kv.Value!.GetValue<string>()) ?? new();

    /// <summary>Every file under <paramref name="dir"/>, keyed by '/'-separated relative path.</summary>
    private static SortedDictionary<string, string> Snapshot(string dir)
    {
        var outp = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var f in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories))
            outp[Path.GetRelativePath(dir, f).Replace('\\', '/')] = File.ReadAllText(f);
        return outp;
    }

    private static Dictionary<string, object?> Args(JsonNode n) => Json.ToMap(n.ToJsonString());

    // ---------------- G1 — index cap ----------------

    private static string CapMemory(JsonNode c)
    {
        if (c["memory"] is { } m) return m.GetValue<string>();
        var sb = new StringBuilder();
        var count = c["generate"]!["count"]!.GetValue<int>();
        var line = c["generate"]!["line"]!.GetValue<string>();
        for (var i = 1; i <= count; i++) sb.Append(line.Replace("<i>", i.ToString())).Append('\n');
        return sb.ToString();
    }

    private static Home.ComposeSoulOptions SoulOptions(JsonNode? o)
    {
        var opts = new Home.ComposeSoulOptions();
        if (o?["notes"] is { } n) opts.Notes = n.GetValue<bool>();
        switch (o?["memoryIndexCap"])
        {
            case JsonValue v when v.GetValue<bool>():
                opts.MemoryIndexCap = Home.MemoryIndexCapPreset;
                break;
            case JsonObject cap:
                opts.MemoryIndexCap = new Home.MemoryIndexCap
                {
                    MaxLines = cap["maxLines"]?.GetValue<int>(),
                    MaxBytes = cap["maxBytes"]?.GetValue<int>(),
                };
                break;
        }
        return opts;
    }

    public static IEnumerable<object[]> IndexCapCases() => MH["indexCap"]!["cases"]!.AsArray().Select(c => new object[] { c!["name"]!.GetValue<string>() });

    [Theory]
    [MemberData(nameof(IndexCapCases))]
    public void G1_IndexCap(string name)
    {
        var c = MH["indexCap"]!["cases"]!.AsArray().First(x => x!["name"]!.GetValue<string>() == name)!;
        var mem = CapMemory(c);
        var files = Files(c["files"]);
        files["MEMORY.md"] = mem;
        var dir = MkHome(files);
        try
        {
            var (soul, _) = Home.ComposeSoul(dir, SoulOptions(c["options"]));
            var e = c["expect"]!;
            Assert.Equal(e["soulSha256"]!.GetValue<string>(), Sha256(soul));
            Assert.Equal(e["soulBytes"]!.GetValue<int>(), Encoding.UTF8.GetByteCount(soul));
            Assert.EndsWith(e["memorySectionTail"]!.GetValue<string>(), soul, StringComparison.Ordinal);
            Assert.Equal(e["equalsUncappedSoul"]!.GetValue<bool>(), soul == Home.ComposeSoul(dir).Soul);
            Assert.Equal(mem, File.ReadAllText(Path.Combine(dir, "MEMORY.md")));
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void G1_FromDirThreadsCap_And_PresetMatchesFixture()
    {
        var preset = MH["indexCap"]!["preset"]!;
        Assert.Equal(preset["maxLines"]!.GetValue<int>(), Home.MemoryIndexCapPreset.MaxLines);
        Assert.Equal(preset["maxBytes"]!.GetValue<int>(), Home.MemoryIndexCapPreset.MaxBytes);
        var c = MH["indexCap"]!["cases"]!.AsArray().First(x => x!["name"]!.GetValue<string>() == "lines-cap-preset-notes-off")!;
        var files = Files(c["files"]);
        files["MEMORY.md"] = CapMemory(c);
        var dir = MkHome(files);
        try
        {
            var soul = Home.FromDir(dir, new Home.FromDirOptions { MemoryIndexCap = Home.MemoryIndexCapPreset }).Spec.Soul!;
            Assert.Equal(c["expect"]!["soulSha256"]!.GetValue<string>(), Sha256(soul));
            Assert.Contains("- fact 200\n\n[MEMORY.md index truncated", soul);
            Assert.DoesNotContain("- fact 201", soul);
            // a field left null takes the preset's value
            var (half, _) = Home.ComposeSoul(dir, new Home.ComposeSoulOptions { MemoryIndexCap = new Home.MemoryIndexCap { MaxBytes = 25600 } });
            Assert.Equal(soul, half);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void G1_InvalidCapThrows()
    {
        var dir = MkHome(new Dictionary<string, string> { ["MEMORY.md"] = "- a\n" });
        try
        {
            Assert.Throws<ArgumentException>(() => Home.ComposeSoul(dir, new Home.ComposeSoulOptions { MemoryIndexCap = new Home.MemoryIndexCap { MaxLines = 0 } }));
            Assert.Throws<ArgumentException>(() => Home.FromDir(dir, new Home.FromDirOptions { MemoryIndexCap = new Home.MemoryIndexCap { MaxBytes = -1 } }));
        }
        finally { Directory.Delete(dir, true); }
    }

    // ---------------- G2 — notes tier ----------------

    private static string SchemaJson(ITool t) => Json.Stringify(t.InputSchema);

    [Fact]
    public void G2_ToolDescriptionsAndSchemasPinned()
    {
        var off = Home.MemoryTool("/x");
        Assert.Equal(MH["memoryTool"]!["notesOff"]!["description"]!.GetValue<string>(), off.Description);
        Assert.Equal(MH["memoryTool"]!["notesOff"]!["inputSchema"]!.ToJsonString(new JsonSerializerOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }), SchemaJson(off));
        var on = Home.MemoryTool("/x", notes: true);
        Assert.Equal(MH["memoryTool"]!["notesOn"]!["description"]!.GetValue<string>(), on.Description);
        Assert.Equal(MH["memoryTool"]!["notesOn"]!["inputSchema"]!.ToJsonString(new JsonSerializerOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }), SchemaJson(on));
    }

    public static IEnumerable<object[]> MemoryToolCases() => MH["memoryTool"]!["cases"]!.AsArray().Select(c => new object[] { c!["name"]!.GetValue<string>() });

    [Theory]
    [MemberData(nameof(MemoryToolCases))]
    public async Task G2_MemoryTool(string name)
    {
        var c = MH["memoryTool"]!["cases"]!.AsArray().First(x => x!["name"]!.GetValue<string>() == name)!;
        var dir = MkHome(Files(c["seed"]));
        try
        {
            var tool = Home.MemoryTool(dir, c["notes"]!.GetValue<bool>());
            foreach (var op in c["ops"]!.AsArray())
            {
                var r = await tool.ExecuteAsync(Args(op!["args"]!));
                Assert.Equal((op["expect"]!["output"]!.GetValue<string>(), op["expect"]!["isError"]!.GetValue<bool>()), (r.Output, r.IsError));
            }
            var expect = new SortedDictionary<string, string>(Files(c["expectFiles"]), StringComparer.Ordinal);
            Assert.Equal(expect, Snapshot(dir));
            if (c["soulMustNotContain"] is { } must)
                Assert.DoesNotContain(must.GetValue<string>(), Home.ComposeSoul(dir, new Home.ComposeSoulOptions { Notes = true }).Soul);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void G2_FromDirNotesWiresNotesTool()
    {
        var dir = MkHome(new Dictionary<string, string> { ["SOUL.md"] = "S" });
        try
        {
            var mem = Home.FromDir(dir, new Home.FromDirOptions { Notes = true }).Spec.Uses!.First(t => t.Name == "memory");
            Assert.Equal(Home.MemoryNotesDescription, mem.Description);
        }
        finally { Directory.Delete(dir, true); }
    }

    // ---------------- G3 — transcript search ----------------

    private static StubStore MhStore() => new(MH["transcriptSearch"]!["store"]!["conversations"]!);

    [Fact]
    public void G3_SchemaAndDescriptionPinned()
    {
        var t = TranscriptSearch.Tool(MhStore());
        var ts = MH["transcriptSearch"]!;
        Assert.Equal(ts["defaults"]!["name"]!.GetValue<string>(), t.Name);
        Assert.Equal(ts["description"]!.GetValue<string>(), t.Description);
        Assert.Equal(ts["inputSchema"]!.ToJsonString(new JsonSerializerOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }), SchemaJson(t));
        var d = new TranscriptSearch.Options();
        Assert.Equal(ts["defaults"]!["maxResults"]!.GetValue<int>(), d.MaxResults);
        Assert.Equal(ts["defaults"]!["snippetChars"]!.GetValue<int>(), d.SnippetChars);
    }

    public static IEnumerable<object[]> SearchCases() => MH["transcriptSearch"]!["cases"]!.AsArray().Select(c => new object[] { c!["name"]!.GetValue<string>() });

    [Theory]
    [MemberData(nameof(SearchCases))]
    public async Task G3_TranscriptSearch(string name)
    {
        var c = MH["transcriptSearch"]!["cases"]!.AsArray().First(x => x!["name"]!.GetValue<string>() == name)!;
        var opts = new TranscriptSearch.Options();
        if (c["options"]?["snippetChars"] is { } sc) opts.SnippetChars = sc.GetValue<int>();
        if (c["options"]?["maxResults"] is { } mr) opts.MaxResults = mr.GetValue<int>();
        var r = await TranscriptSearch.Tool(MhStore(), opts).ExecuteAsync(Args(c["args"]!));
        Assert.Equal((c["expect"]!["output"]!.GetValue<string>(), c["expect"]!["isError"]!.GetValue<bool>()), (r.Output, r.IsError));
    }

    [Fact]
    public async Task G3_EmptyQueryReadsNothing_StoreWithoutListRejected()
    {
        var store = MhStore();
        var r = await TranscriptSearch.Tool(store).ExecuteAsync(new Dictionary<string, object?> { ["query"] = "" });
        Assert.True(r.IsError);
        Assert.Equal(0, store.Reads);
        var ex = Assert.Throws<ArgumentException>(() => TranscriptSearch.Tool(new NoListStore()));
        Assert.Equal(MH["transcriptSearch"]!["storeWithoutList"]!["error"]!.GetValue<string>(), ex.Message);
        Assert.Throws<ArgumentException>(() => TranscriptSearch.Tool(store, new TranscriptSearch.Options { MaxResults = 0 }));
        Assert.Throws<ArgumentException>(() => TranscriptSearch.Tool(store, new TranscriptSearch.Options { SnippetChars = 0 }));
        var bad = await TranscriptSearch.Tool(store).ExecuteAsync(new Dictionary<string, object?> { ["query"] = "moon", ["limit"] = 1.5 });
        Assert.Equal("limit must be an integer >= 1", bad.Output);
    }

    [Fact]
    public async Task G3_WorksOverShippedStores()
    {
        var store = new InMemoryConversationStore();
        await store.SaveAsync("only", new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = "The vault code is 4417" } });
        var r = await TranscriptSearch.Tool(store).ExecuteAsync(new Dictionary<string, object?> { ["query"] = "VAULT" });
        Assert.Equal("only#0 user: The vault code is 4417", r.Output);
    }

    // ---------------- G4 — write safety ----------------

    [Fact]
    public async Task G4_ConcurrentAddsFromTwoToolsKeepEveryEntry()
    {
        var w = MH["writeSafety"]!["concurrentAdds"]!;
        var writers = w["writers"]!.GetValue<int>();
        var addsEach = w["addsEach"]!.GetValue<int>();
        var dir = MkHome(new Dictionary<string, string>());
        try
        {
            var tools = Enumerable.Range(0, writers).Select(_ => Home.MemoryTool(dir)).ToList();
            await Task.WhenAll(tools.SelectMany((t, wi) => Enumerable.Range(0, addsEach)
                .Select(n => Task.Run(() => t.ExecuteAsync(new Dictionary<string, object?> { ["action"] = "add", ["text"] = $"w{wi}-{n}" })))));
            var lines = File.ReadAllText(Path.Combine(dir, "MEMORY.md")).TrimEnd().Split('\n');
            Assert.Equal(w["expectEntries"]!.GetValue<int>(), lines.Length);
            Assert.Equal(w["expectEntries"]!.GetValue<int>(), lines.Distinct().Count());
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public async Task G4_LargeFileAddGrows_NoTempLeft()
    {
        var l = MH["writeSafety"]!["largeFileAdd"]!;
        var original = new string(l["fill"]!.GetValue<string>()[0], l["bytes"]!.GetValue<int>());
        var addText = l["addText"]!.GetValue<string>();
        var dir = MkHome(new Dictionary<string, string> { ["MEMORY.md"] = original });
        try
        {
            var r = await Home.MemoryTool(dir, notes: true).ExecuteAsync(new Dictionary<string, object?> { ["action"] = "add", ["text"] = addText });
            Assert.False(r.IsError, r.Output);
            Assert.Equal(original + $"\n- {addText}\n", File.ReadAllText(Path.Combine(dir, "MEMORY.md")));
            Assert.Equal(new[] { "MEMORY.md" }, Directory.EnumerateFileSystemEntries(dir).Select(Path.GetFileName).ToArray());
        }
        finally { Directory.Delete(dir, true); }
    }

    // ---------------- Dreaming ----------------

    private static StubStore DrStore() => new(DR["store"]!["conversations"]!);

    private static RuntimeOptions DrOpts(DreamMockHandler h, TimeProvider? clock = null)
        => new() { Handler = h, ApiKey = "test", Clock = clock };

    [Fact]
    public void D0_DreamPromptIsFixtureText()
        => Assert.Equal(DR["dreamPrompt"]!.GetValue<string>(), Home.DreamPrompt);

    [Fact]
    public async Task D1_MtMoonBuildGate_And_D2_SecondPassIsSilent()
    {
        var g = DR["expect"]!["D1_mtMoonBuildGate"]!;
        var h = new DreamMockHandler(DR["mockLLM"]!["scripts"]!);
        var home = MkHome(Files(DR["home"]));
        try
        {
            var before = await Home.FromDir(home, new Home.FromDirOptions { Model = g["before"]!["model"]!.GetValue<string>() })
                .RunAsync(DrOpts(h), g["before"]!["prompt"]!.GetValue<string>());
            Assert.Equal(g["before"]!["text"]!.GetValue<string>(), before.Text);

            var pass = await Home.Dreamer(home, new Home.DreamerOptions { Store = DrStore() }).RunAsync(DrOpts(h), Home.HeartbeatPrompt);
            Assert.Equal(g["pass"]!["replyText"]!.GetValue<string>(), pass.Text);
            var snap = Snapshot(home);
            foreach (var (f, body) in g["after"]!["files"]!.AsObject()) Assert.Equal(body!.GetValue<string>(), snap[f]);
            Assert.Single(snap["memory/log.md"].Split('\n'), x => x.StartsWith("- ", StringComparison.Ordinal));

            var next = g["after"]!["nextSession"]!;
            var after = await Home.FromDir(home, new Home.FromDirOptions { Model = next["model"]!.GetValue<string>() })
                .RunAsync(DrOpts(h), next["prompt"]!.GetValue<string>());
            Assert.Equal(next["text"]!.GetValue<string>(), after.Text);

            // D2 (second case): a second pass finds nothing to do — silent, nothing written.
            var frozen = Snapshot(home);
            var again = await Home.Dreamer(home, new Home.DreamerOptions { Store = DrStore() }).RunAsync(DrOpts(h), Home.HeartbeatPrompt);
            Assert.Contains(Home.HeartbeatOk, again.Text);
            Assert.Equal(frozen, Snapshot(home));
        }
        finally { Directory.Delete(home, true); }
    }

    [Fact]
    public async Task D2_IdleDreamerWritesNothing()
    {
        var h = new DreamMockHandler(DR["mockLLM"]!["scripts"]!) { DreamScript = "dreamer-idle" };
        var home = MkHome(Files(DR["home"]));
        try
        {
            var frozen = Snapshot(home);
            var r = await Home.Dreamer(home, new Home.DreamerOptions { Store = DrStore() }).RunAsync(DrOpts(h), Home.HeartbeatPrompt);
            Assert.Contains(Home.HeartbeatOk, r.Text);
            Assert.Equal(frozen, Snapshot(home));
        }
        finally { Directory.Delete(home, true); }
    }

    [Fact]
    public void D3_DreamerToolsExactly()
    {
        var e = DR["expect"]!["D3_dreamerToolsExactly"]!;
        var home = MkHome(Files(DR["home"]));
        try
        {
            var personaTools = e["personaTools"]!.AsArray().Select(n => n!.GetValue<string>()).ToList();
            var persona = Home.FromDir(home, new Home.FromDirOptions
            {
                Tools = personaTools.Select(n => (ITool)NativeTool.Of(n, n, null, (IDictionary<string, object?> _) => "x")).ToList(),
            });
            Assert.Equal(personaTools.Append("memory"), persona.Spec.Uses!.Select(t => t.Name));
            var tools = Home.Dreamer(home, new Home.DreamerOptions { Store = DrStore() }).Spec.Uses!;
            Assert.Equal(e["dreamerTools"]!.AsArray().Select(n => n!.GetValue<string>()), tools.Select(t => t.Name));
            Assert.Equal(e["dreamerMemoryHasNotes"]!.GetValue<bool>(), tools[0].Description == Home.MemoryNotesDescription);
        }
        finally { Directory.Delete(home, true); }
    }

    [Fact]
    public void D3b_DreamerSoulNameAndStoreChecks()
    {
        var e = DR["expect"]!["D3b_dreamerSoul"]!;
        var plain = MkHome(Files(DR["home"]));
        var files = Files(DR["home"]);
        files["DREAM.md"] = e["DREAM.md"]!.GetValue<string>();
        var withExtras = MkHome(files);
        try
        {
            var d = Home.Dreamer(plain, new Home.DreamerOptions { Store = DrStore() });
            Assert.Equal(e["soulWithoutExtras"]!.GetValue<string>(), d.Spec.Soul);
            Assert.Equal($"{Path.GetFileName(plain)}-dreamer", d.Name);
            Assert.Equal("inherit", d.Spec.Model);
            Assert.Equal($"dreams over the memory in {plain}", d.Spec.Does);
            var x = Home.Dreamer(withExtras, new Home.DreamerOptions { Store = DrStore(), Instructions = e["instructions"]!.GetValue<string>() });
            Assert.Equal(e["soul"]!.GetValue<string>(), x.Spec.Soul);
            Assert.Contains("list()", Assert.Throws<ArgumentException>(() => Home.Dreamer(plain, new Home.DreamerOptions { Store = new NoListStore() })).Message);
            Assert.Contains("store is required", Assert.Throws<ArgumentException>(() => Home.Dreamer(plain, new Home.DreamerOptions())).Message);
            Assert.Throws<ArgumentException>(() => Home.FromDir(plain, new Home.FromDirOptions { Dream = new Home.DreamOptions { EveryMs = 0, Store = DrStore() } }));
        }
        finally { Directory.Delete(plain, true); Directory.Delete(withExtras, true); }
    }

    [Fact]
    public async Task D4_FromDirDreamRunsOnSameRuntime_NeverReachesOnBeat()
    {
        var e = DR["expect"]!["D4_fromDirDream"]!;
        var h = new DreamMockHandler(DR["mockLLM"]!["scripts"]!);
        var home = MkHome(Files(DR["home"]));
        try
        {
            var clock = new VirtualClock();
            var onBeat = new List<string>();
            var persona = Home.FromDir(home, new Home.FromDirOptions { Model = "m-quiet", Dream = new Home.DreamOptions { EveryMs = 50, Store = DrStore() } });
            var started = Home.StartAgent(persona, DrOpts(h, clock), everyMs: 1000, onBeat: t => { lock (onBeat) onBeat.Add(t); });
            clock.Advance(TimeSpan.FromMilliseconds(50));
            await started.SettleAsync();
            var snap = Snapshot(home);
            foreach (var (f, body) in e["after"]!["files"]!.AsObject()) Assert.Equal(body!.GetValue<string>(), snap[f]);
            Assert.Contains(started.Runtime.TraceLines(), l => l.Contains($"{Path.GetFileName(home)}-dreamer"));
            await started.StopAsync();
            lock (onBeat) Assert.Empty(onBeat);
            Assert.Empty(started.Beats);
        }
        finally { Directory.Delete(home, true); }
    }

    [Fact]
    public async Task D5_AbsentDreamIsUnchanged()
    {
        var h = new DreamMockHandler(DR["mockLLM"]!["scripts"]!);
        var home = MkHome(Files(DR["home"]));
        try
        {
            var frozen = Snapshot(home);
            var clock = new VirtualClock();
            var persona = Home.FromDir(home, new Home.FromDirOptions { Model = "m-quiet" });
            Assert.Single(persona.Registry());
            var started = Home.StartAgent(persona, DrOpts(h, clock), everyMs: 50);
            for (var i = 0; i < 4; i++)
            {
                clock.Advance(TimeSpan.FromMilliseconds(50));
                await started.SettleAsync();
            }
            await started.StopAsync();
            Assert.Equal(frozen, Snapshot(home));
            Assert.DoesNotContain(started.Runtime.TraceLines(), l => l.Contains("dreamer"));
        }
        finally { Directory.Delete(home, true); }
    }
}
