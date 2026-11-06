using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;

namespace Mcp2026.Tests;

static class Paths
{
    public static readonly string Root = Find();
    static string Find()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null; d = d.Parent)
            if (File.Exists(Path.Combine(d.FullName, "Mcp2026.slnx"))) return d.FullName;
        throw new FileNotFoundException("Mcp2026.slnx");
    }
#if DEBUG
    const string Config = "Debug";
#else
    const string Config = "Release";
#endif
    /// <summary>The built dll of a project dir (e.g. "src/Spike", "examples/01-connect-http").</summary>
    public static string Dll(string projectDir, string name) => Path.Combine(Root, projectDir, "bin", Config, "net10.0", name + ".dll");
}

public class StdioTests
{
    static McpClient Stdio(string mode, TextWriter stderr) =>
        McpClient.StartStdio(new ProcessStartInfo("dotnet", [Paths.Dll("src/Spike", "spike"), mode]), stderr);

    [Fact]
    public async Task SameEightSteps() => Assert.True(await Checks.RunAsync("", () => Stdio("stdio-fixture", TextWriter.Null)));

    [Fact]
    public async Task StderrCapturedNotParsedAndConcurrent()
    {
        var err = new StringWriter();
        var c = Stdio("stdio-fixture", err);
        await Task.WhenAll(Enumerable.Range(0, 20).Select(async i =>
        {
            var r = await c.CallToolAsync("echo", new JsonObject { ["text"] = "msg-" + i });
            Assert.Equal("msg-" + i, Mcp.Text(r));
        }));
        await c.DisposeAsync();
        Assert.Contains("stdio-test-server ready", err.ToString());
    }

    [Fact]
    public async Task ServerExitIsAnError()
    {
        await using var c = Stdio("stdio-exit", TextWriter.Null);
        await Assert.ThrowsAsync<McpException>(() => c.CallAsync("tools/list"));
    }
}

public class SkillsTests
{
    [Theory]
    [InlineData("skill://hello-world/SKILL.md", "hello-world", "SKILL.md")]
    [InlineData("skill://a/refs/doc.md", "a", "refs/doc.md")]
    [InlineData("skill://a/with%20space.md", "a", "with space.md")]
    [InlineData("skill://a/..dots-in-name..x.md", "a", "..dots-in-name..x.md")]
    public void ParseOk(string uri, string name, string rel)
    {
        Assert.True(SkillUri.TryParse(uri, out var n, out var r, out var why), why);
        Assert.Equal((name, rel), (n, r));
    }

    [Theory]
    [InlineData("skill://a/../b")] [InlineData("skill://a/%2e%2e/b")] [InlineData("skill://a/%2E%2E%2Fetc")] [InlineData("skill://%2e%2e/x")]
    [InlineData("skill://a/..%2F..%2Fetc%2Fpasswd")] [InlineData("skill://a/%252e%252e/x")] [InlineData("skill://a/%252F")] [InlineData("skill://a/x%5C..%5Cy")]
    [InlineData("skill://a//etc")] [InlineData("skill:///etc/passwd")] [InlineData("skill://a/%2Fetc")] [InlineData("skill://a/%00")] [InlineData("skill://a/C:/x")]
    [InlineData("skill://a/.")] [InlineData("skill://a")] [InlineData("skill://a/%zz")] [InlineData("file:///etc/passwd")]
    public void ParseRejects(string uri) => Assert.False(SkillUri.TryParse(uri, out _, out _, out _));

    [Fact]
    public async Task ServerRejectsTraversalUris()
    {
        await using var ts = await HttpHost.StartAsync(Server.Fixture());
        var c = new McpClient(ts.Url + "/mcp");
        foreach (var m in new[] { "resources/read", "skills/get" })
        {
            var e = await Assert.ThrowsAsync<McpRpcException>(() => c.CallAsync(m, new JsonObject { ["uri"] = "skill://hello-world/%2e%2e/%2e%2e/fixture.json" }));
            Assert.Equal(Mcp.CodeInvalidParams, e.Code);
        }
    }

    [Fact]
    public async Task DownloadSkill()
    {
        await using var ts = await HttpHost.StartAsync(Server.Fixture());
        var dir = Directory.CreateTempSubdirectory().FullName;
        await new McpClient(ts.Url + "/mcp").DownloadSkillAsync("skill://hello-world/SKILL.md", dir);
        Assert.NotEmpty(File.ReadAllBytes(Path.Combine(dir, "hello-world", "SKILL.md")));
    }

    /// <summary>A hostile server lists a resource that decodes to ../../pwned: nothing may be written.</summary>
    [Theory]
    [InlineData("skill://hello-world/%2e%2e%2f%2e%2e%2fpwned")] [InlineData("skill://hello-world/..%2F..%2Fpwned")]
    [InlineData("skill://hello-world/%252e%252e/pwned")] [InlineData("skill://other-skill/pwned")]
    public async Task DownloadSkillRefusesHostileResource(string evil)
    {
        await using var ts = await HttpHost.StartAsync(async http =>
        {
            var req = JsonNode.Parse(await new StreamReader(http.Request.Body).ReadToEndAsync())!;
            var res = J.Str(req["method"]) == "skills/get"
                ? new JsonObject { ["skill"] = new JsonObject { ["uri"] = "skill://hello-world/SKILL.md", ["resources"] = new JsonArray(new JsonObject { ["uri"] = "skill://hello-world/SKILL.md" }, new JsonObject { ["uri"] = evil }) } }
                : new JsonObject { ["contents"] = new JsonArray(new JsonObject { ["uri"] = evil, ["text"] = "pwned" }) };
            await http.Response.WriteAsync(J.Serialize(new JsonObject { ["jsonrpc"] = "2.0", ["id"] = req["id"]!.DeepClone(), ["result"] = res }));
        });
        var root = Directory.CreateTempSubdirectory().FullName;
        await Assert.ThrowsAsync<McpException>(() => new McpClient(ts.Url + "/mcp").DownloadSkillAsync("skill://hello-world/SKILL.md", Path.Combine(root, "a", "b")));
        Assert.Empty(Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories));
    }
}

public class ToolSearchTests
{
    static async Task<HttpHost> HundredToolServer()
    {
        string[][] services = [["github", "issue"], ["slack", "message"], ["jira", "ticket"], ["gmail", "email"], ["calendar", "event"],
            ["drive", "file"], ["stripe", "invoice"], ["postgres", "row"], ["s3", "object"], ["notion", "page"]];
        string[] verbs = ["create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment"];
        var tools = new JsonArray();
        foreach (var s in services)
            foreach (var v in verbs)
                tools.Add(new JsonObject
                {
                    ["name"] = $"{s[0]}_{v}_{s[1]}",
                    ["description"] = $"{v} a {s[1]} in {s[0]}. Requires the workspace to be connected; returns the {s[1]} as JSON with its id, timestamps and owner.",
                    ["inputSchema"] = new JsonObject
                    {
                        ["type"] = "object",
                        ["properties"] = new JsonObject
                        {
                            ["id"] = new JsonObject { ["type"] = "string", ["description"] = $"The {s[1]} id (omit when creating)." },
                            ["query"] = new JsonObject { ["type"] = "string", ["description"] = "Free-text filter applied server-side." },
                            ["body"] = new JsonObject { ["type"] = "object", ["description"] = $"Fields of the {s[1]} to write." },
                            ["options"] = new JsonObject { ["type"] = "object", ["properties"] = new JsonObject { ["dryRun"] = new JsonObject { ["type"] = "boolean" }, ["limit"] = new JsonObject { ["type"] = "integer" } } },
                        },
                        ["required"] = J.Array(["query"]),
                    },
                });
        var dir = Directory.CreateTempSubdirectory().FullName;
        var f = Path.Combine(dir, "f.json");
        File.WriteAllText(f, J.Serialize(new JsonObject { ["server"] = new JsonObject { ["name"] = "big", ["version"] = "1" }, ["tools"] = tools, ["skillsDir"] = "." }));
        return await HttpHost.StartAsync(Server.Load(f));
    }

    [Fact]
    public async Task RankingAndSize()
    {
        await using var ts = await HundredToolServer();
        var eager = new McpClient(ts.Url + "/mcp");
        var lazy = new McpClient(ts.Url + "/mcp") { ToolSearch = true, Description = "SaaS workspace (GitHub, Slack, Jira, ...)" };
        var all = await eager.ToolsAsync();
        Assert.Equal(100, all.Count);
        var meta = await lazy.ToolsAsync();
        foreach (var (q, want) in new Dictionary<string, string>
        {
            ["create slack message"] = "slack_create_message", ["search stripe invoices"] = "stripe_search_invoice",
            ["archive a notion page"] = "notion_archive_page", ["s3 export"] = "s3_export_object",
        })
        {
            var hits = await lazy.SearchToolsAsync(q, 5);
            Assert.Equal(want, J.Str(hits[0]["name"]));
            Assert.Equal(lazy.Description, J.Str(hits[0]["server"]));
        }
        int Size(IEnumerable<JsonNode> xs) => J.Serialize(new JsonArray(xs.Select(x => x.DeepClone()).ToArray())).Length;
        var r = await lazy.InvokeAsync("search_tools", new JsonObject { ["query"] = "create slack message" });
        var (eb, lb, rb) = (Size(all), Size(meta), J.Serialize(r["content"]).Length);
        Console.WriteLine($"tool JSON bytes: eager(100 tools)={eb}  lazy(2 meta-tools)={lb}  + one search_tools result(5 hits)={rb}");
        Assert.True(lb * 10 < eb, $"lazy not smaller: {lb} vs {eb}");
    }

    [Fact]
    public async Task InvokeCallsFoundTool()
    {
        await using var ts = await HttpHost.StartAsync(Server.Fixture());
        var c = new McpClient(ts.Url + "/mcp") { ToolSearch = true };
        var hits = await c.SearchToolsAsync("echo text back", 3);
        Assert.Equal("echo", J.Str(hits[0]["name"]));
        Assert.Equal("found", Mcp.Text(await c.InvokeAsync("call_tool", new JsonObject { ["name"] = "echo", ["arguments"] = new JsonObject { ["text"] = "found" } })));
        await Assert.ThrowsAsync<McpException>(() => c.InvokeAsync("echo")); // direct tool names are hidden
    }
}

public class ProtocolTests
{
    [Fact]
    public void HeaderEncodingRoundTrips()
    {
        foreach (var v in new[] { "echo", " padded ", "héllo", "=?base64?x?=" })
        {
            var enc = McpClient.EncodeHeader(v);
            Assert.Equal(v, Server.DecodeHeader(enc).Item1);
        }
        Assert.Equal("echo", McpClient.EncodeHeader("echo"));
    }

    [Fact]
    public async Task OversizeBodyIs413AndUnknownMethod404()
    {
        await using var ts = await HttpHost.StartAsync(Server.Fixture());
        using var big = await DevTrust.Shared.PostAsync(ts.Url + "/mcp", new StringContent(new string('x', Server.MaxBodyBytes + 10), Encoding.UTF8, "application/json"));
        Assert.Equal(413, (int)big.StatusCode);
        var e = await Assert.ThrowsAsync<McpRpcException>(() => new McpClient(ts.Url + "/mcp").CallAsync("nope/nope"));
        Assert.Equal(Mcp.CodeMethodNotFound, e.Code);
    }

    [Fact]
    public async Task SseResponseUsesFinalMessage()
    {
        await using var ts = await HttpHost.StartAsync(async http =>
        {
            http.Response.ContentType = "text/event-stream";
            await http.Response.WriteAsync("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n");
        });
        var r = await new McpClient(ts.Url + "/mcp").CallAsync("x");
        Assert.True(J.True(r["ok"]));
        Assert.Equal("complete", J.Str(r["resultType"])); // missing resultType = complete
    }
}
