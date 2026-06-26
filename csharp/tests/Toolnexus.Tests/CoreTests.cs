namespace Toolnexus.Tests;

public class ToolSanitizeTests
{
    [Fact]
    public void SanitizeReplacesNonAllowedChars()
    {
        Assert.Equal("a_b_c_d_e", Tools.Sanitize("a b/c.d:e"));
        Assert.Equal("keep-_OK1", Tools.Sanitize("keep-_OK1"));
    }

    [Fact]
    public void SanitizeNullIsEmpty() => Assert.Equal("", Tools.Sanitize(null));
}

public class AdaptersTests
{
    private static ITool Sample()
    {
        var schema = new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() };
        return NativeTool.Of("t", "d", schema, _ => "");
    }

    [Fact]
    public void ToOpenAIShape()
    {
        var first = Adapters.ToOpenAI(new[] { Sample() })[0];
        Assert.Equal("function", first["type"]);
        var fn = (IDictionary<string, object?>)first["function"]!;
        Assert.Equal("t", fn["name"]);
        Assert.Equal("d", fn["description"]);
        Assert.Equal("object", ((IDictionary<string, object?>)fn["parameters"]!)["type"]);
    }

    [Fact]
    public void ToAnthropicShape()
    {
        var first = Adapters.ToAnthropic(new[] { Sample() })[0];
        Assert.Equal("t", first["name"]);
        Assert.Equal("d", first["description"]);
        Assert.True(first.ContainsKey("input_schema"));
        Assert.Equal("object", ((IDictionary<string, object?>)first["input_schema"]!)["type"]);
    }

    [Fact]
    public void ToGeminiShape()
    {
        var first = Adapters.ToGemini(new[] { Sample() })[0];
        var decls = (List<object?>)first["functionDeclarations"]!;
        Assert.Single(decls);
        Assert.Equal("t", ((IDictionary<string, object?>)decls[0]!)["name"]);
    }
}

public class NativeToolTests
{
    [Fact]
    public async Task StringResultOk()
    {
        var s = NativeTool.Of("s", "", null, _ => "hi");
        var r = await s.ExecuteAsync(new Dictionary<string, object?>());
        Assert.Equal("hi", r.Output);
        Assert.False(r.IsError);
        Assert.Equal("native", s.Source);
    }

    [Fact]
    public async Task ToolResultPassthrough()
    {
        var t = NativeTool.Of("r", "", null, _ => ToolResult.Error("x"));
        var r = await t.ExecuteAsync(new Dictionary<string, object?>());
        Assert.Equal("x", r.Output);
        Assert.True(r.IsError);
    }

    [Fact]
    public async Task ThrownErrorBecomesErrorResult()
    {
        var e = NativeTool.Of("e", "", null, _ => throw new InvalidOperationException("boom"));
        var r = await e.ExecuteAsync(new Dictionary<string, object?>());
        Assert.True(r.IsError);
        Assert.Contains("boom", r.Output);
    }

    private sealed class Calc
    {
        [ToolMethod("add", "Add two numbers")]
        public string Add([Param("a")] int a, [Param("b")] int b) => (a + b).ToString();
    }

    [Fact]
    public async Task FromObjectDiscoversAnnotatedToolWithInferredSchema()
    {
        var tools = Tools.FromObject(new Calc());
        Assert.Single(tools);
        var add = tools[0];
        Assert.Equal("add", add.Name);
        Assert.Equal("Add two numbers", add.Description);
        Assert.Equal("native", add.Source);

        var schema = add.InputSchema;
        Assert.Equal("object", schema["type"]);
        var props = (IDictionary<string, object?>)schema["properties"]!;
        Assert.True(props.ContainsKey("a"));
        Assert.True(props.ContainsKey("b"));
        Assert.Equal("number", ((IDictionary<string, object?>)props["a"]!)["type"]);
        var required = ((IEnumerable<object?>)schema["required"]!).Select(x => x?.ToString()).ToList();
        Assert.Contains("a", required);
        Assert.Contains("b", required);

        var res = await add.ExecuteAsync(new Dictionary<string, object?> { ["a"] = 2, ["b"] = 3 });
        Assert.False(res.IsError);
        Assert.Equal("5", res.Output);
    }
}
