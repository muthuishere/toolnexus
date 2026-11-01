using System.Diagnostics;

namespace Toolnexus.Tests;

/// <summary>add-host-engine-needs: MCP `command` + `args` parsing (SPEC §2) and FileConversationStore.</summary>
public class McpCommandArgsTests
{
    private static List<string> Argv(string json, string name = "x")
        => McpSource.ResolveCommand(name, (IDictionary<string, object?>)McpSource.ParseConfig(json)[name]!);

    [Fact]
    public void CommandStringPlusArgsMcpServersWrapper()
        => Assert.Equal(new[] { "node", "server.mjs", "a b" },
            Argv("{\"mcpServers\":{\"x\":{\"command\":\"node\",\"args\":[\"server.mjs\",\"a b\"]}}}"));

    [Fact]
    public void CommandStringPlusArgsMcpWrapper()
        => Assert.Equal(new[] { "node", "server.mjs" },
            Argv("{\"mcp\":{\"x\":{\"command\":\"node\",\"args\":[\"server.mjs\"]}}}"));

    [Fact]
    public void CommandStringPlusArgsFlatForm()
        => Assert.Equal(new[] { "node", "s.mjs" }, Argv("{\"x\":{\"command\":\"node\",\"args\":[\"s.mjs\"]}}"));

    [Fact]
    public void ArgvArrayUnchanged()
        => Assert.Equal(new[] { "node", "server.mjs" }, Argv("{\"mcp\":{\"x\":{\"command\":[\"node\",\"server.mjs\"]}}}"));

    [Fact]
    public void ArgvArrayPlusArgsAppends()
        => Assert.Equal(new[] { "node", "a", "b" }, Argv("{\"mcp\":{\"x\":{\"command\":[\"node\",\"a\"],\"args\":[\"b\"]}}}"));

    [Fact]
    public void StringWithoutArgs()
        => Assert.Equal(new[] { "node" }, Argv("{\"mcp\":{\"x\":{\"command\":\"node\"}}}"));

    [Theory]
    [InlineData("{\"mcp\":{\"bad\":{\"command\":\"\"}}}")]
    [InlineData("{\"mcp\":{\"bad\":{\"command\":[]}}}")]
    [InlineData("{\"mcp\":{\"bad\":{\"command\":[\"node\",1]}}}")]
    [InlineData("{\"mcp\":{\"bad\":{\"command\":\"node\",\"args\":\"x\"}}}")]
    [InlineData("{\"mcp\":{\"bad\":{\"command\":\"node\",\"args\":[\"ok\",2]}}}")]
    [InlineData("{\"mcp\":{\"bad\":{\"command\":42}}}")]
    public void InvalidCommandIsNamedError(string json)
    {
        var e = Assert.Throws<ArgumentException>(() => McpSource.ParseConfig(json));
        Assert.Contains("\"bad\"", e.Message);
    }

    [Fact]
    public void RemoteServerIgnoresArgs()
        => Assert.True(McpSource.ParseConfig("{\"mcp\":{\"r\":{\"url\":\"http://x\",\"args\":\"junk\"}}}").ContainsKey("r"));
}

public class FileConversationStoreTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "tn-fcs-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch { }
    }

    private static List<object?> Msgs(string text) => new()
    {
        new Dictionary<string, object?> { ["role"] = "user", ["content"] = text },
    };

    [Fact]
    public void RootIsRequired()
        => Assert.Throws<ArgumentException>(() => new FileConversationStore(""));

    [Fact]
    public async Task FileStoreSyncModes()
    {
        Assert.Throws<ArgumentException>(() => new FileConversationStore(_root, "bogus"));
        Assert.Throws<ArgumentException>(() => new FileConversationStore(_root, (FileStoreSync)42));
        var stores = new List<Func<string, FileConversationStore>>
        {
            r => new FileConversationStore(r),
            r => new FileConversationStore(r, "full"),
            r => new FileConversationStore(r, "fsync"),
            r => new FileConversationStore(r, "none"),
            r => new FileConversationStore(r, FileStoreSync.Full),
            r => new FileConversationStore(r, FileStoreSync.Fsync),
            r => new FileConversationStore(r, FileStoreSync.None),
        };
        for (var i = 0; i < stores.Count; i++)
        {
            var root = Path.Combine(_root, "m" + i);
            await stores[i](root).SaveAsync("c1", Msgs("hi"));
            var got = await new FileConversationStore(root).GetAsync("c1");
            Assert.Equal(Json.Stringify(Msgs("hi")), Json.Stringify(got));
            Assert.Equal(new[] { "c1.json" }, Directory.GetFileSystemEntries(root).Select(Path.GetFileName).ToArray());
        }
    }

    [Fact]
    public async Task RoundTripAcrossInstances()
    {
        await new FileConversationStore(_root).SaveAsync("c1", Msgs("hi ünïcode"));
        var got = await new FileConversationStore(_root).GetAsync("c1");
        Assert.Equal(Json.Stringify(Msgs("hi ünïcode")), Json.Stringify(got));
        var body = Json.ToMap(File.ReadAllText(Path.Combine(_root, "c1.json")));
        Assert.Equal(1L, body["version"]);
        Assert.Null(await new FileConversationStore(_root).GetAsync("missing"));
    }

    [Theory]
    [InlineData("../x")]
    [InlineData("a/b")]
    [InlineData(".")]
    [InlineData("..")]
    [InlineData("")]
    [InlineData("a\\b")]
    public async Task TraversalIdsRejected(string id)
    {
        var s = new FileConversationStore(_root);
        await Assert.ThrowsAsync<ArgumentException>(() => s.SaveAsync(id, Msgs("x")));
        await Assert.ThrowsAsync<ArgumentException>(() => s.GetAsync(id));
        await Assert.ThrowsAsync<ArgumentException>(() => s.DeleteAsync(id));
        Assert.False(File.Exists(Path.Combine(Path.GetDirectoryName(_root)!, "x.json")));
    }

    [Fact]
    public async Task OverlongIdRejected()
        => await Assert.ThrowsAsync<ArgumentException>(() => new FileConversationStore(_root).GetAsync(new string('a', 129)));

    [Fact]
    public async Task CorruptFileIsError()
    {
        Directory.CreateDirectory(_root);
        File.WriteAllText(Path.Combine(_root, "c.json"), "{not json");
        await Assert.ThrowsAsync<InvalidDataException>(() => new FileConversationStore(_root).GetAsync("c"));
    }

    [Fact]
    public async Task UnknownVersionIsError()
    {
        Directory.CreateDirectory(_root);
        File.WriteAllText(Path.Combine(_root, "c.json"), "{\"version\":2,\"messages\":[]}");
        await Assert.ThrowsAsync<InvalidDataException>(() => new FileConversationStore(_root).GetAsync("c"));
    }

    [Fact]
    public async Task ListNewestFirstTiesById()
    {
        var s = new FileConversationStore(_root);
        await s.SaveAsync("a", Msgs("1"));
        await Task.Delay(30);
        await s.SaveAsync("b", Msgs("2"));
        Assert.Equal(new[] { "b", "a" }, (await s.ListAsync()).Select(i => i.Id));

        var t = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        await s.SaveAsync("z", Msgs("3"));
        await s.SaveAsync("y", Msgs("4"));
        File.SetLastWriteTimeUtc(Path.Combine(_root, "z.json"), t);
        File.SetLastWriteTimeUtc(Path.Combine(_root, "y.json"), t);
        File.WriteAllText(Path.Combine(_root, ".a.123.tmp"), "x");
        File.WriteAllText(Path.Combine(_root, "bad name.json"), "x");
        var ids = (await s.ListAsync()).Select(i => i.Id).ToList();
        Assert.Equal(new[] { "b", "a", "y", "z" }, ids);
    }

    [Fact]
    public async Task DeleteIsIdempotent()
    {
        var s = new FileConversationStore(_root);
        await s.DeleteAsync("never"); // no root, no file
        await s.SaveAsync("d", Msgs("x"));
        await s.DeleteAsync("d");
        Assert.Null(await s.GetAsync("d"));
        Assert.DoesNotContain(await s.ListAsync(), i => i.Id == "d");
        await s.DeleteAsync("d");
    }

    [Fact]
    public async Task ConcurrentSavesLeaveValidFileAndNoTemps()
    {
        var s = new FileConversationStore(_root);
        await Task.WhenAll(Enumerable.Range(0, 40).Select(i => Task.Run(() => s.SaveAsync("same", Msgs("m" + i)))));
        var got = await new FileConversationStore(_root).GetAsync("same");
        Assert.Single(got!);
        Assert.Empty(Directory.GetFiles(_root, "*.tmp"));
        Assert.Single(Directory.GetFiles(_root));
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(Path.Combine(_root, "same.json")));
    }

    [Fact]
    public async Task InMemoryStoreListAndDelete()
    {
        var s = new InMemoryConversationStore();
        await s.SaveAsync("a", Msgs("1"));
        await s.SaveAsync("b", Msgs("2"));
        Assert.Equal(new[] { "b", "a" }, (await s.ListAsync()).Select(i => i.Id));
        await s.DeleteAsync("a");
        await s.DeleteAsync("a");
        Assert.Null(await s.GetAsync("a"));
        Assert.Equal(new[] { "b" }, (await s.ListAsync()).Select(i => i.Id));
    }
}

/// <summary>add-mcp-progress-timeout: the server `timeout` is an IDLE timeout, against the shared fixture.</summary>
public class McpIdleTimeoutTests
{
    private static bool NodeAvailable()
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("node", "--version")
                { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false });
            p!.WaitForExit(5000);
            return p.ExitCode == 0;
        }
        catch { return false; }
    }

    private static async Task<ToolResult?> CallSlow(Dictionary<string, object?> args)
    {
        if (!NodeAvailable()) return null; // skip: fixture needs node
        var fixture = TestFixtures.Fixture("mcp-progress-server.mjs");
        // The server `timeout` also bounds connect+list (SPEC §2), and a cold `node` spawn plus the
        // SDK handshake can exceed a few hundred ms on a CI runner, so the idle budget is 1500ms and
        // each call runs well past it (4000ms) to keep proving "idle, not total".
        var cfg = "{\"mcp\":{\"p\":{\"command\":\"node\",\"args\":[" + Json.Stringify(fixture) + "],\"timeout\":1500}}}";
        await using var src = await McpSource.LoadAsync(cfg);
        Assert.Equal("connected", src.Status["p"]);
        var tool = Assert.Single(src.Tools);
        return await tool.ExecuteAsync(args);
    }

    [Fact]
    public async Task ProgressKeepsCallAlivePastTimeout()
    {
        var r = await CallSlow(new() { ["totalMs"] = 4000, ["intervalMs"] = 100 });
        if (r == null) return;
        Assert.False(r.IsError, r.Output);
        Assert.Equal(true, Json.ToMap(r.Output)["progressTokenReceived"]);
    }

    [Fact]
    public async Task SilenceTimesOut()
    {
        var r = await CallSlow(new() { ["totalMs"] = 4000, ["intervalMs"] = 0 });
        if (r == null) return;
        Assert.True(r.IsError);
        Assert.Contains("timed out", r.Output);
    }

    [Fact]
    public async Task WrongTokenDoesNotReset()
    {
        var r = await CallSlow(new() { ["totalMs"] = 4000, ["intervalMs"] = 100, ["wrongToken"] = true });
        if (r == null) return;
        Assert.True(r.IsError);
        Assert.Contains("timed out", r.Output);
    }

    [Fact]
    public async Task CallerCancellationWins()
    {
        if (!NodeAvailable()) return;
        var fixture = TestFixtures.Fixture("mcp-progress-server.mjs");
        var cfg = "{\"mcp\":{\"p\":{\"command\":\"node\",\"args\":[" + Json.Stringify(fixture) + "],\"timeout\":5000}}}";
        await using var src = await McpSource.LoadAsync(cfg);
        using var cts = new CancellationTokenSource(150);
        var sw = Stopwatch.StartNew();
        var r = await src.Tools.Single().ExecuteAsync(new Dictionary<string, object?> { ["totalMs"] = 3000, ["intervalMs"] = 50 },
            new ToolContext(cancellationToken: cts.Token));
        Assert.True(r.IsError);
        Assert.DoesNotContain("timed out", r.Output);
        Assert.True(sw.ElapsedMilliseconds < 2500);
    }
}
