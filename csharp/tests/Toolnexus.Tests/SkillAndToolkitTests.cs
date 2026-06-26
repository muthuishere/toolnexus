namespace Toolnexus.Tests;

public class SkillSourceTests
{
    [Fact]
    public async Task DiscoveryPromptAndSkillBlock()
    {
        var src = SkillSource.Load(TestFixtures.SkillsDir());

        Assert.True(src.Skills.ContainsKey("hello-world"));

        var prompt = src.Prompt();
        Assert.Contains("## Available Skills", prompt);
        Assert.Contains("hello-world", prompt);

        var res = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "hello-world" });
        Assert.False(res.IsError);
        var output = res.Output;
        Assert.StartsWith("<skill_content name=\"hello-world\">", output);
        Assert.Contains("# Skill: hello-world", output);
        Assert.Contains("Base directory for this skill: file://", output);
        Assert.Contains("<skill_files>", output);
        Assert.EndsWith("</skill_content>", output);
    }

    [Fact]
    public async Task UnknownSkillIsError()
    {
        var src = SkillSource.Load(TestFixtures.SkillsDir());
        var miss = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "nope" });
        Assert.True(miss.IsError);
        Assert.Contains("not found", miss.Output);
    }
}

public class ToolkitTests
{
    private static Task<Toolkit> Build()
        => Toolkit.CreateAsync(new Toolkit.Options().WithSkillsDir(TestFixtures.SkillsDir()));

    [Fact]
    public async Task RegisterGetExecuteAndDuplicateKeepsFirst()
    {
        await using var tk = await Build();
        tk.Register(NativeTool.Of("add", "", null, args =>
        {
            var a = Convert.ToInt32(args["a"]);
            var b = Convert.ToInt32(args["b"]);
            return (a + b).ToString();
        }));
        // duplicate name — must be ignored (first wins).
        tk.Register(NativeTool.Of("add", "dup", null, _ => "SHOULD_NOT_WIN"));

        Assert.NotNull(tk.Get("add"));
        Assert.NotNull(tk.Get("skill"));

        var r = await tk.ExecuteAsync("add", new Dictionary<string, object?> { ["a"] = 2, ["b"] = 3 });
        Assert.Equal("5", r.Output);
    }

    [Fact]
    public async Task UnknownToolIsError()
    {
        await using var tk = await Build();
        var miss = await tk.ExecuteAsync("ghost", new Dictionary<string, object?>());
        Assert.True(miss.IsError);
        Assert.Contains("Unknown tool", miss.Output);
    }

    [Fact]
    public async Task NoMcpMeansEmptyStatus()
    {
        await using var tk = await Build();
        Assert.Empty(tk.McpStatus());
    }
}
