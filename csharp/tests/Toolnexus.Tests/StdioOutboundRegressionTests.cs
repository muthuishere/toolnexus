namespace Toolnexus.Tests;

/// <summary>
/// ADR-0007 regression guard for the v0.9.0 <b>stdio</b> (local subprocess) MCP bug: a
/// ctx-aware connect that bound the child's lifetime to a connect-timeout token killed the
/// subprocess the moment connect returned, so every call AFTER connect (tools/list,
/// tools/call) hit a dead transport. Only Go was confirmed affected; C# was UNVERIFIED
/// because the MCP tests only ever used HTTP servers. This drives a genuine OUTBOUND stdio
/// MCP server (a real child process, <see cref="McpSource"/> as the client) and asserts it
/// connects, lists tools, and one tool EXECUTES — i.e. the child stays alive past connect.
/// Hermetic: no network, no python — the server is the C#-native StdioMcpServerFixture,
/// launched as <c>dotnet &lt;fixture.dll&gt;</c>.
/// </summary>
public class StdioOutboundRegressionTests
{
    private static Dictionary<string, object?> StdioConfig()
    {
        var cfg = new Dictionary<string, object?>
        {
            ["srv"] = new Dictionary<string, object?>
            {
                ["command"] = new List<object?> { "dotnet", FixtureDll() },
            },
        };
        return cfg;
    }

    /// <summary>
    /// Locate the built StdioMcpServerFixture.dll next to the test's own build output
    /// (same Configuration/TFM tail, sibling project under <c>tests/</c>).
    /// </summary>
    private static string FixtureDll()
    {
        var baseDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        var tfm = Path.GetFileName(baseDir);                                   // e.g. net10.0
        var config = Path.GetFileName(Path.GetDirectoryName(baseDir)!);        // e.g. Debug

        var dir = new DirectoryInfo(baseDir);
        while (dir != null && !string.Equals(dir.Name, "tests", StringComparison.Ordinal))
            dir = dir.Parent;
        Assert.NotNull(dir); // must run from within the tests/ tree

        var dll = Path.Combine(dir!.FullName, "StdioMcpServerFixture", "bin", config, tfm, "StdioMcpServerFixture.dll");
        Assert.True(File.Exists(dll), $"stdio fixture not built at {dll}");
        return dll;
    }

    [Fact]
    public async Task OutboundStdioChildStaysAliveAfterConnect()
    {
        await using var src = await McpSource.LoadAsync(StdioConfig());

        // Connect must succeed (the regression's first half — connect itself was fine).
        Assert.Equal("connected", src.Status["srv"]);

        // tools/list must succeed AFTER connect (the regression's failure point — a dead child
        // here surfaces as status "failed" with no tools).
        var names = src.Tools.Select(t => t.Name).ToHashSet();
        Assert.Contains("srv_a", names);
        Assert.Contains("srv_b", names);
        Assert.Contains("srv_c", names);

        // tools/call must succeed on the still-live child — the strongest signal the subprocess
        // outlived connect and is not tied to the connect-timeout token.
        var toolA = src.Tools.First(t => t.Name == "srv_a");
        var result = await toolA.ExecuteAsync(new Dictionary<string, object?>());
        Assert.False(result.IsError, $"execute srv_a returned error: {result.Output}");
        Assert.Equal("a", result.Output);
    }
}
