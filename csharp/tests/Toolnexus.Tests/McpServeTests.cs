using System.IO.Pipelines;
using System.Text.Json;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;
using ModelContextProtocol.Server;

namespace Toolnexus.Tests;

/// <summary>
/// MCP inbound (§7C) — serve a toolkit as an MCP server. Hermetic: an in-process
/// linked-stream transport pair for the low-level server, and an ephemeral localhost
/// /mcp endpoint for the streamable-HTTP round-trips. Mirrors the 9 "mcp inbound:"
/// tests in js/test/unit.test.ts.
/// </summary>
public class McpServeTests
{
    private static readonly Dictionary<string, object?> EchoSchema = new()
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["text"] = new Dictionary<string, object?> { ["type"] = "string" } },
        ["required"] = new List<object?> { "text" },
        ["additionalProperties"] = false,
    };

    private static ITool EchoTool() => NativeTool.Of(
        "echo", "echo back text", EchoSchema,
        args => args.TryGetValue("text", out var v) ? v?.ToString() ?? "" : "");

    private static ITool BoomTool() => NativeTool.Of(
        "boom", "always throws", null,
        (IDictionary<string, object?> _) => throw new Exception("kaboom"));

    // -----------------------------------------------------------------------
    // In-process linked-stream transport pair (the C# analog of InMemoryTransport).
    // -----------------------------------------------------------------------

    private sealed class InProc : IAsyncDisposable
    {
        public required McpClient Client { get; init; }
        public required IClientTransport ClientTransport { get; init; }
        public required McpServer Server { get; init; }
        public required Task Run { get; init; }
        public required CancellationTokenSource Cts { get; init; }

        public async ValueTask DisposeAsync()
        {
            try { await Client.DisposeAsync(); } catch { }
            Cts.Cancel();
            try { await Server.DisposeAsync(); } catch { }
            try { await Run; } catch { }
        }
    }

    private static async Task<InProc> ConnectInProc(IReadOnlyList<ITool> tools, MCPServeConfig? cfg = null, OnCall? onCall = null)
    {
        var clientToServer = new Pipe();
        var serverToClient = new Pipe();

        var serverTransport = new StreamServerTransport(clientToServer.Reader.AsStream(), serverToClient.Writer.AsStream());
        var server = McpServe.BuildMcpServer(serverTransport, tools, cfg, onCall);
        var cts = new CancellationTokenSource();
        var run = server.RunAsync(cts.Token);

        var clientTransport = new StreamClientTransport(clientToServer.Writer.AsStream(), serverToClient.Reader.AsStream());
        var client = await McpClient.CreateAsync(clientTransport);

        return new InProc { Client = client, ClientTransport = clientTransport, Server = server, Run = run, Cts = cts };
    }

    // -----------------------------------------------------------------------
    // Tests.
    // -----------------------------------------------------------------------

    [Fact]
    public void ExposedMcpTools_FiltersByName_OmitMeansAll()
    {
        var tools = new List<ITool> { EchoTool(), BoomTool() };
        Assert.Equal(new[] { "echo", "boom" }, McpServe.ExposedMcpTools(tools, null).Select(t => t.Name));
        Assert.Equal(new[] { "echo" },
            McpServe.ExposedMcpTools(tools, new MCPServeConfig { Tools = new[] { "echo" } }).Select(t => t.Name));
        // unknown names in the filter are ignored, not an error
        Assert.Equal(new[] { "echo" },
            McpServe.ExposedMcpTools(tools, new MCPServeConfig { Tools = new[] { "echo", "nope" } }).Select(t => t.Name));
    }

    [Fact]
    public async Task Initialize_ReportsServerInfoFromProfile()
    {
        await using var ip = await ConnectInProc(new[] { EchoTool() }, new MCPServeConfig { Name = "gateway", Version = "2.0.0" });
        Assert.Equal("gateway", ip.Client.ServerInfo.Name);
        Assert.Equal("2.0.0", ip.Client.ServerInfo.Version);
    }

    [Fact]
    public async Task ToolsList_AdvertisesUnifiedToolsWithInputSchema()
    {
        await using var ip = await ConnectInProc(new[] { EchoTool(), BoomTool() });
        var tools = await ip.Client.ListToolsAsync();
        Assert.Equal(new[] { "boom", "echo" }, tools.Select(t => t.Name).OrderBy(n => n));

        var echo = tools.Single(t => t.Name == "echo");
        // inputSchema == the tool's parameters JSON Schema
        var advertised = Json.FromElement(echo.JsonSchema);
        Assert.Equal(Json.Stringify(EchoSchema), Json.Stringify(advertised));
    }

    [Fact]
    public async Task McpTools_NarrowsAdvertisedSurface()
    {
        var cfg = new MCPServeConfig { Tools = new[] { "echo" } };
        var exposed = McpServe.ExposedMcpTools(new List<ITool> { EchoTool(), BoomTool() }, cfg);
        await using var ip = await ConnectInProc(exposed, cfg);
        var tools = await ip.Client.ListToolsAsync();
        Assert.Equal(new[] { "echo" }, tools.Select(t => t.Name));
    }

    [Fact]
    public async Task ToolsCall_DispatchesToExecute_TextContent_IsErrorFalse()
    {
        var calls = new List<OnCallEvent>();
        await using var ip = await ConnectInProc(new[] { EchoTool() }, null, ev => { calls.Add(ev); return Task.CompletedTask; });

        var res = await ip.Client.CallToolAsync("echo", new Dictionary<string, object?> { ["text"] = "hi" });
        Assert.NotEqual(true, res.IsError);
        var text = res.Content.OfType<TextContentBlock>().First();
        Assert.Equal("hi", text.Text);

        Assert.Single(calls);
        Assert.Equal("echo", calls[0].Name);
        Assert.Equal("native", calls[0].Source);
        Assert.False(calls[0].IsError);
    }

    [Fact]
    public async Task ErroringTool_IsErrorResult_ServerSurvives()
    {
        await using var ip = await ConnectInProc(new[] { EchoTool(), BoomTool() });

        var bad = await ip.Client.CallToolAsync("boom", new Dictionary<string, object?>());
        Assert.True(bad.IsError);
        Assert.Contains("kaboom", bad.Content.OfType<TextContentBlock>().First().Text);

        // server keeps serving
        var ok = await ip.Client.CallToolAsync("echo", new Dictionary<string, object?> { ["text"] = "still up" });
        Assert.NotEqual(true, ok.IsError);
        Assert.Equal("still up", ok.Content.OfType<TextContentBlock>().First().Text);
    }

    [Fact]
    public async Task StreamableHttp_RoundTripViaServe()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            ExtraTools = new List<ITool> { EchoTool() },
        });
        var srv = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions { Mcp = new MCPServeConfig { Name = "http-gw" } });
        var client = await McpClient.CreateAsync(new HttpClientTransport(new HttpClientTransportOptions
        {
            Endpoint = new Uri(srv.Url + "/mcp"),
            TransportMode = HttpTransportMode.StreamableHttp,
        }));
        try
        {
            Assert.Equal("http-gw", client.ServerInfo.Name);
            var tools = await client.ListToolsAsync();
            Assert.Contains(tools, t => t.Name == "echo");
            var res = await client.CallToolAsync("echo", new Dictionary<string, object?> { ["text"] = "over http" });
            Assert.Equal("over http", res.Content.OfType<TextContentBlock>().First().Text);
        }
        finally
        {
            await client.DisposeAsync();
            await srv.StopAsync();
        }
    }

    [Fact]
    public async Task AbsentProfile_NoMcpSurface()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            ExtraTools = new List<ITool> { EchoTool() },
        });
        // A2A-only server: no MCP profile → /mcp is handled by the A2A JSON-RPC handler, which
        // treats an MCP tools/list as an unknown method (a JSON-RPC error), not an MCP result.
        var srv = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions { A2A = new A2AConfig { Name = "only-a2a" } });
        try
        {
            using var http = new HttpClient();
            var req = new HttpRequestMessage(HttpMethod.Post, srv.Url + "/mcp")
            {
                Content = new StringContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
                    System.Text.Encoding.UTF8, "application/json"),
            };
            var res = await http.SendAsync(req);
            var body = Json.ParseObjectLoose(await res.Content.ReadAsStringAsync());
            Assert.True(body.ContainsKey("error"), "expected a JSON-RPC error, not an MCP tools/list result");
            Assert.False(body.ContainsKey("result") && body["result"] != null);
        }
        finally
        {
            await srv.StopAsync();
        }
    }

    [Fact]
    public async Task TopLevelMcpServerConfigBlock_PickedUpByServe()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            McpConfig = new Dictionary<string, object?>
            {
                ["mcpServer"] = new Dictionary<string, object?> { ["name"] = "from-config" },
            },
            Builtins = false,
            ExtraTools = new List<ITool> { EchoTool() },
        });
        // Serve with no inline mcp falls back to the config block.
        var srv = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions());
        var client = await McpClient.CreateAsync(new HttpClientTransport(new HttpClientTransportOptions
        {
            Endpoint = new Uri(srv.Url + "/mcp"),
            TransportMode = HttpTransportMode.StreamableHttp,
        }));
        try
        {
            Assert.Equal("from-config", client.ServerInfo.Name);
        }
        finally
        {
            await client.DisposeAsync();
            await srv.StopAsync();
        }
    }
}
