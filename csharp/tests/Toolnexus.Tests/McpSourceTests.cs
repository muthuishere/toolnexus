namespace Toolnexus.Tests;

public class McpSourceTests
{
    private static Dictionary<string, object?> ServersBlock() => new()
    {
        ["foo"] = new Dictionary<string, object?> { ["command"] = new List<object?> { "x" } },
    };

    [Fact]
    public void ParseConfigAcceptsMcpServersServersMcpAndRaw()
    {
        var s = ServersBlock();

        Assert.True(McpSource.ParseConfig(new Dictionary<string, object?> { ["mcpServers"] = s }).ContainsKey("foo"));
        Assert.True(McpSource.ParseConfig(new Dictionary<string, object?> { ["servers"] = s }).ContainsKey("foo"));
        Assert.True(McpSource.ParseConfig(new Dictionary<string, object?> { ["mcp"] = s }).ContainsKey("foo"));
        // raw (no wrapper) — the dictionary IS the servers map.
        Assert.True(McpSource.ParseConfig(s).ContainsKey("foo"));
    }

    [Fact]
    public void ParseConfigAcceptsRawJsonString()
    {
        const string json = "{\"mcpServers\":{\"foo\":{\"command\":[\"x\"]}}}";
        var parsed = McpSource.ParseConfig(json);
        Assert.True(parsed.ContainsKey("foo"));
    }

    [Fact]
    public void ExpandEnvHeadersExpandsViaLookupStub()
    {
        var headers = new Dictionary<string, string>
        {
            ["Authorization"] = "Bearer ${TN_TEST_TOKEN}",
            ["X"] = "plain",
        };
        var env = new Dictionary<string, string> { ["TN_TEST_TOKEN"] = "secret123" };
        var output = McpSource.ExpandEnvHeaders(headers, k => env.GetValueOrDefault(k))!;

        Assert.Equal("Bearer secret123", output["Authorization"]);
        Assert.Equal("plain", output["X"]);
    }

    [Fact]
    public void ExpandEnvHeadersMissingVarBecomesEmpty()
    {
        var headers = new Dictionary<string, string> { ["Authorization"] = "Bearer ${NOT_SET_VAR}" };
        var output = McpSource.ExpandEnvHeaders(headers, _ => null)!;
        Assert.Equal("Bearer ", output["Authorization"]);
    }

    [Fact]
    public void ExpandEnvHeadersNullInNullOut()
    {
        Assert.Null(McpSource.ExpandEnvHeaders(null));
        Assert.Null(McpSource.ExpandEnvHeaders(null, _ => "x"));
    }

    [Fact]
    public void ExpandEnvHeadersDefaultUsesEnvironment()
    {
        var path = Environment.GetEnvironmentVariable("PATH");
        Assert.False(string.IsNullOrEmpty(path));
        var headers = new Dictionary<string, string> { ["X-Path"] = "p=${PATH}" };
        var output = McpSource.ExpandEnvHeaders(headers)!;
        Assert.Equal("p=" + path, output["X-Path"]);
    }
}
