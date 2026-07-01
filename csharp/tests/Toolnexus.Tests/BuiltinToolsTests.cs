using System.Net;

namespace Toolnexus.Tests;

public class BuiltinSelectTests
{
    private static readonly string[] AllNames =
    {
        "bash", "read", "write", "edit", "grep", "glob",
        "webfetch", "question", "apply_patch", "todowrite",
    };

    [Fact]
    public void DefaultSelectsAllTenEachSourceBuiltin()
    {
        var tools = BuiltinTools.Select(null);
        Assert.Equal(10, tools.Count);
        Assert.Equal(AllNames, tools.Select(t => t.Name).ToArray());
        Assert.All(tools, t => Assert.Equal("builtin", t.Source));
    }

    [Theory]
    [InlineData(false)]
    public void BoolFalseDisablesAll(bool cfg)
        => Assert.Empty(BuiltinTools.Select(cfg));

    [Fact]
    public void DisabledTrueRemovesAll()
    {
        var cfg = new Dictionary<string, object?> { ["disabled"] = true };
        Assert.Empty(BuiltinTools.Select(cfg));
    }

    [Fact]
    public void EnabledFalseRemovesAll()
    {
        var cfg = new Dictionary<string, object?> { ["enabled"] = false };
        Assert.Empty(BuiltinTools.Select(cfg));
    }

    [Fact]
    public void DisabledWinsOverEnabled()
    {
        var cfg = new Dictionary<string, object?> { ["disabled"] = true, ["enabled"] = true };
        Assert.False(BuiltinTools.Enabled(cfg));
    }

    [Fact]
    public void PerToolMapDropsNamedKeepsEight()
    {
        var cfg = new Dictionary<string, object?>
        {
            ["tools"] = new Dictionary<string, object?> { ["bash"] = false, ["write"] = false, ["unknown"] = false },
        };
        var names = BuiltinTools.Select(cfg).Select(t => t.Name).ToList();
        Assert.Equal(8, names.Count);
        Assert.DoesNotContain("bash", names);
        Assert.DoesNotContain("write", names);
        Assert.Contains("read", names);
    }

    [Fact]
    public void WholeSourceOffOverridesToolMap()
    {
        var cfg = new Dictionary<string, object?>
        {
            ["disabled"] = true,
            ["tools"] = new Dictionary<string, object?> { ["bash"] = true },
        };
        Assert.Empty(BuiltinTools.Select(cfg));
    }

    [Fact]
    public void ToolMapTrueKeepsTool()
    {
        var cfg = new Dictionary<string, object?>
        {
            ["tools"] = new Dictionary<string, object?> { ["bash"] = true },
        };
        Assert.Equal(10, BuiltinTools.Select(cfg).Count);
    }
}

public class BuiltinToolkitAssemblyTests
{
    private static Task<Toolkit> Build(object? builtins = null, ITool[]? extras = null)
    {
        var opts = new Toolkit.Options().WithSkillsDir(TestFixtures.SkillsDir());
        if (builtins != null) opts.WithBuiltins(builtins);
        if (extras != null) opts.WithExtraTools(extras);
        return Toolkit.CreateAsync(opts);
    }

    [Fact]
    public async Task DefaultToolkitHasAllTenBuiltinsPlusSkill()
    {
        await using var tk = await Build();
        var builtins = tk.Tools().Where(t => t.Source == "builtin").ToList();
        Assert.Equal(10, builtins.Count);
        Assert.NotNull(tk.Get("bash"));
        Assert.NotNull(tk.Get("skill"));
    }

    [Fact]
    public async Task ToggleOffRemovesAllTenKeepsSkillAndExtras()
    {
        var extra = NativeTool.Of("mytool", "", null, _ => "hi");
        await using var tk = await Build(builtins: false, extras: new[] { extra });
        Assert.Equal(0, tk.Tools().Count(t => t.Source == "builtin"));
        Assert.NotNull(tk.Get("skill"));
        Assert.NotNull(tk.Get("mytool"));
        Assert.Null(tk.Get("bash"));
    }

    [Fact]
    public async Task DisabledTrueMapRemovesAll()
    {
        var cfg = new Dictionary<string, object?>
        {
            ["disabled"] = true,
            ["tools"] = new Dictionary<string, object?> { ["bash"] = true },
        };
        await using var tk = await Build(builtins: cfg);
        Assert.Equal(0, tk.Tools().Count(t => t.Source == "builtin"));
    }

    [Fact]
    public async Task ExtraToolShadowsBuiltin()
    {
        var fakeRead = NativeTool.Of("read", "sandboxed", null, _ => "SHADOW");
        await using var tk = await Build(extras: new[] { fakeRead });
        var read = tk.Get("read");
        Assert.NotNull(read);
        Assert.Equal("native", read!.Source);
        // still 9 builtins present
        Assert.Equal(9, tk.Tools().Count(t => t.Source == "builtin"));
    }

    [Fact]
    public async Task PerToolMapDropsTwoKeepsEight()
    {
        var cfg = new Dictionary<string, object?>
        {
            ["tools"] = new Dictionary<string, object?> { ["bash"] = false, ["write"] = false },
        };
        await using var tk = await Build(builtins: cfg);
        Assert.Equal(8, tk.Tools().Count(t => t.Source == "builtin"));
        Assert.Null(tk.Get("bash"));
        Assert.Null(tk.Get("write"));
        Assert.NotNull(tk.Get("edit"));
    }

    [Fact]
    public async Task TopLevelBuiltinsKeyOnConfigObjectDisables()
    {
        // A parsed config object carrying a top-level `builtins:false` turns the source off,
        // even though McpConfig also lists (zero) servers.
        var cfg = new Dictionary<string, object?>
        {
            ["mcpServers"] = new Dictionary<string, object?>(),
            ["builtins"] = false,
        };
        await using var tk = await Toolkit.CreateAsync(
            new Toolkit.Options().WithMcpConfig(cfg).WithSkillsDir(TestFixtures.SkillsDir()));
        Assert.Equal(0, tk.Tools().Count(t => t.Source == "builtin"));
    }
}

public class BuiltinFileToolsTests
{
    private static IDictionary<string, object?> Args(params (string, object?)[] pairs)
    {
        var d = new Dictionary<string, object?>();
        foreach (var (k, v) in pairs) d[k] = v;
        return d;
    }

    private static ITool Tool(string name) => BuiltinTools.Create().Single(t => t.Name == name);

    private static string TempDir()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tnx-builtin-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        return dir;
    }

    [Fact]
    public async Task WriteReadEditRoundTrip()
    {
        var dir = TempDir();
        var file = Path.Combine(dir, "a.txt");

        var w = await Tool("write").ExecuteAsync(Args(("path", file), ("content", "hello world")));
        Assert.False(w.IsError);
        Assert.Contains("Wrote", w.Output);
        Assert.Equal("hello world", File.ReadAllText(file));

        var r = await Tool("read").ExecuteAsync(Args(("path", file)));
        Assert.False(r.IsError);
        Assert.Equal("hello world", r.Output);

        var e = await Tool("edit").ExecuteAsync(Args(("path", file), ("oldString", "world"), ("newString", "there")));
        Assert.False(e.IsError);
        Assert.Equal("hello there", File.ReadAllText(file));

        Directory.Delete(dir, true);
    }

    [Fact]
    public async Task EditReplaceAll()
    {
        var dir = TempDir();
        var file = Path.Combine(dir, "a.txt");
        File.WriteAllText(file, "x x x");
        var e = await Tool("edit").ExecuteAsync(Args(("path", file), ("oldString", "x"), ("newString", "y"), ("replaceAll", true)));
        Assert.False(e.IsError);
        Assert.Equal("y y y", File.ReadAllText(file));
        Assert.Equal(3L, e.Metadata!["replacements"]);
        Directory.Delete(dir, true);
    }

    [Fact]
    public async Task EditNonUniqueIsError()
    {
        var dir = TempDir();
        var file = Path.Combine(dir, "a.txt");
        File.WriteAllText(file, "x x");
        var e = await Tool("edit").ExecuteAsync(Args(("path", file), ("oldString", "x"), ("newString", "y")));
        Assert.True(e.IsError);
        Assert.Contains("not unique", e.Output);
        Directory.Delete(dir, true);
    }

    [Fact]
    public async Task EditNotFoundIsError()
    {
        var dir = TempDir();
        var file = Path.Combine(dir, "a.txt");
        File.WriteAllText(file, "abc");
        var e = await Tool("edit").ExecuteAsync(Args(("path", file), ("oldString", "zzz"), ("newString", "y")));
        Assert.True(e.IsError);
        Assert.Contains("not found", e.Output);
        Directory.Delete(dir, true);
    }

    [Fact]
    public async Task ReadMissingIsError()
    {
        var r = await Tool("read").ExecuteAsync(Args(("path", Path.Combine(TempDir(), "nope.txt"))));
        Assert.True(r.IsError);
        Assert.StartsWith("read:", r.Output);
    }

    [Fact]
    public async Task ReadOffsetLimitWindow()
    {
        var dir = TempDir();
        var file = Path.Combine(dir, "a.txt");
        File.WriteAllText(file, "l1\nl2\nl3\nl4\nl5");
        var r = await Tool("read").ExecuteAsync(Args(("path", file), ("offset", 2), ("limit", 2)));
        Assert.False(r.IsError);
        Assert.Equal("l2\nl3", r.Output);
        Directory.Delete(dir, true);
    }

    [Fact]
    public async Task GrepCountsMatches()
    {
        var dir = TempDir();
        File.WriteAllText(Path.Combine(dir, "a.txt"), "foo\nbar\nfoo");
        File.WriteAllText(Path.Combine(dir, "b.txt"), "baz");
        var g = await Tool("grep").ExecuteAsync(Args(("pattern", "foo"), ("path", dir)));
        Assert.False(g.IsError);
        Assert.Equal(2L, g.Metadata!["count"]);
        Directory.Delete(dir, true);
    }

    [Fact]
    public async Task GlobCounts()
    {
        var dir = TempDir();
        File.WriteAllText(Path.Combine(dir, "a.txt"), "");
        File.WriteAllText(Path.Combine(dir, "b.txt"), "");
        File.WriteAllText(Path.Combine(dir, "c.md"), "");
        var g = await Tool("glob").ExecuteAsync(Args(("pattern", "*.txt"), ("path", dir)));
        Assert.False(g.IsError);
        Assert.Equal(2L, g.Metadata!["count"]);
        Directory.Delete(dir, true);
    }

    [Fact]
    public async Task ApplyPatchAddUpdateDelete()
    {
        var dir = TempDir();
        var add = Path.Combine(dir, "new.txt");
        var upd = Path.Combine(dir, "upd.txt");
        var del = Path.Combine(dir, "del.txt");
        File.WriteAllText(upd, "hello world");
        File.WriteAllText(del, "bye");

        var patch = string.Join("\n",
            "*** Begin Patch",
            $"*** Add File: {add}",
            "+created line",
            $"*** Update File: {upd}",
            "@@",
            "-hello world",
            "+hello there",
            $"*** Delete File: {del}",
            "*** End Patch");

        var r = await Tool("apply_patch").ExecuteAsync(Args(("patchText", patch)));
        Assert.False(r.IsError);
        Assert.Equal("created line", File.ReadAllText(add));
        Assert.Equal("hello there", File.ReadAllText(upd));
        Assert.False(File.Exists(del));
        Assert.Equal(1L, r.Metadata!["added"]);
        Assert.Equal(1L, r.Metadata!["updated"]);
        Assert.Equal(1L, r.Metadata!["deleted"]);
        Directory.Delete(dir, true);
    }

    [Fact]
    public async Task ApplyPatchNonMatchAbortsWithNoWrite()
    {
        var dir = TempDir();
        var add = Path.Combine(dir, "new.txt");
        var upd = Path.Combine(dir, "upd.txt");
        File.WriteAllText(upd, "actual content");

        var patch = string.Join("\n",
            "*** Begin Patch",
            $"*** Add File: {add}",
            "+should not be created",
            $"*** Update File: {upd}",
            "@@",
            "-nonexistent line",
            "+replacement",
            "*** End Patch");

        var r = await Tool("apply_patch").ExecuteAsync(Args(("patchText", patch)));
        Assert.True(r.IsError);
        Assert.Contains("does not match", r.Output);
        // atomic: neither the add nor the update happened
        Assert.False(File.Exists(add));
        Assert.Equal("actual content", File.ReadAllText(upd));
        Directory.Delete(dir, true);
    }
}

public class BuiltinMiscToolsTests
{
    private static IDictionary<string, object?> Args(params (string, object?)[] pairs)
    {
        var d = new Dictionary<string, object?>();
        foreach (var (k, v) in pairs) d[k] = v;
        return d;
    }

    private static ITool Tool(string name) => BuiltinTools.Create().Single(t => t.Name == name);

    [Fact]
    public async Task BashSuccess()
    {
        var r = await Tool("bash").ExecuteAsync(Args(("command", "echo hello")));
        Assert.False(r.IsError);
        Assert.Contains("hello", r.Output);
        Assert.Equal(0L, r.Metadata!["exitCode"]);
    }

    [Fact]
    public async Task BashNonZeroExitIsError()
    {
        var r = await Tool("bash").ExecuteAsync(Args(("command", "exit 3")));
        Assert.True(r.IsError);
        Assert.Contains("exited with code 3", r.Output);
    }

    [Fact]
    public async Task QuestionRoundTrip()
    {
        var questions = new List<object?>
        {
            new Dictionary<string, object?> { ["question"] = "Proceed?" },
        };
        var r = await Tool("question").ExecuteAsync(Args(("questions", questions)));
        Assert.False(r.IsError);
        Assert.Contains("Proceed?", r.Output);
        Assert.NotNull(r.Metadata!["questions"]);
    }

    [Fact]
    public async Task TodowriteRoundTrip()
    {
        var todos = new List<object?>
        {
            new Dictionary<string, object?> { ["id"] = "1", ["text"] = "task one", ["completed"] = false },
            new Dictionary<string, object?> { ["id"] = "2", ["text"] = "task two", ["completed"] = true },
        };
        var r = await Tool("todowrite").ExecuteAsync(Args(("todos", todos)));
        Assert.False(r.IsError);
        Assert.Equal("[ ] task one\n[x] task two", r.Output);
    }

    [Fact]
    public async Task WebfetchAgainstLocalStub()
    {
        using var server = new StubServer(ctx =>
        {
            StubServer.Respond(ctx, 200, "<html><body><p>Hi there</p></body></html>");
        });
        var r = await Tool("webfetch").ExecuteAsync(Args(("url", server.BaseUrl + "/"), ("format", "text")));
        Assert.False(r.IsError);
        Assert.Contains("Hi there", r.Output);
        Assert.Equal(200L, r.Metadata!["status"]);
    }

    [Fact]
    public async Task WebfetchNon2xxIsError()
    {
        using var server = new StubServer(ctx => StubServer.Respond(ctx, 404, "nope"));
        var r = await Tool("webfetch").ExecuteAsync(Args(("url", server.BaseUrl + "/")));
        Assert.True(r.IsError);
        Assert.Contains("HTTP 404", r.Output);
    }
}

public class SkillsPreambleTests
{
    [Fact]
    public void PreamblePrecedesSkillsListWhenDescribed()
    {
        var src = SkillSource.Load(TestFixtures.SkillsDir());
        var prompt = src.Prompt();
        Assert.StartsWith(SkillSource.SkillsPromptPreamble, prompt);
        Assert.Contains(
            SkillSource.SkillsPromptPreamble + "\n\n## Available Skills", prompt);
    }

    [Fact]
    public void PreambleStringIsByteIdentical()
    {
        Assert.Equal(
            "Skills provide specialized instructions and workflows for specific tasks.\n"
            + "Use the skill tool to load a skill when a task matches its description.",
            SkillSource.SkillsPromptPreamble);
    }

    [Fact]
    public void NoSkillsMeansNoPreamble()
    {
        var empty = Path.Combine(Path.GetTempPath(), "tnx-noskills-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(empty);
        try
        {
            var src = SkillSource.Load(empty);
            var prompt = src.Prompt();
            Assert.Equal("No skills are currently available.", prompt);
            Assert.DoesNotContain(SkillSource.SkillsPromptPreamble, prompt);
        }
        finally
        {
            Directory.Delete(empty, true);
        }
    }
}
