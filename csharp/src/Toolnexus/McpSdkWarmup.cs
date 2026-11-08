using System.IO.Pipelines;
using System.Text;
using ModelContextProtocol;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;

namespace Toolnexus;

/// <summary>
/// Performance only — no observable behavior. The first <c>McpClient.CreateAsync</c> + <c>ListToolsAsync</c> in a
/// process spends most of its time JIT-compiling and type-loading the SDK (≈50 ms on a laptop), and today that
/// happens strictly AFTER the discover probe has waited for the child process to start (≈30 ms of idle CPU).
/// <see cref="Start"/> runs the same SDK client path once, in the background, against an in-memory fake server,
/// so that work overlaps the child's startup instead of following it. Started at most once per process; every
/// failure is swallowed; nothing waits on it.
/// </summary>
internal static class McpSdkWarmup
{
    private static int _started;

    public static void Start()
    {
        if (Interlocked.Exchange(ref _started, 1) != 0) return;
        _ = Task.Run(async () =>
        {
            try { await RunAsync().ConfigureAwait(false); } catch { /* warm-up only */ }
        });
        // The protocol's JSON metadata is the largest single slice; warm the result types on a second
        // thread so the client flow above finds them ready instead of building them in series.
        _ = Task.Run(() =>
        {
            try { WarmJson(); } catch { /* warm-up only */ }
        });
    }

    private static void WarmJson()
    {
        var o = McpJsonUtilities.DefaultOptions;
        System.Text.Json.JsonSerializer.Deserialize<JsonRpcMessage>(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", o);
        System.Text.Json.JsonSerializer.Deserialize<ListToolsResult>(
            "{\"tools\":[{\"name\":\"t\",\"inputSchema\":{\"type\":\"object\"}}]}", o);
        System.Text.Json.JsonSerializer.Deserialize<CallToolResult>(
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}", o);
    }

    private static async Task RunAsync()
    {
        var toServer = new Pipe();
        var toClient = new Pipe();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var server = FakeServerAsync(toServer.Reader.AsStream(), toClient.Writer.AsStream(), cts.Token);
        var transport = new StreamClientTransport(toServer.Writer.AsStream(), toClient.Reader.AsStream());
        await using (var client = await McpClient.CreateAsync(transport, null, cancellationToken: cts.Token).ConfigureAwait(false))
        {
            var tools = await client.ListToolsAsync(cancellationToken: cts.Token).ConfigureAwait(false);
            if (tools.Count > 0)
                await tools[0].CallAsync(new Dictionary<string, object?> { ["x"] = "y" }, cancellationToken: cts.Token).ConfigureAwait(false);
        }
        await toServer.Writer.CompleteAsync().ConfigureAwait(false);
        try { await server.ConfigureAwait(false); } catch { }
    }

    private static async Task FakeServerAsync(Stream input, Stream output, CancellationToken ct)
    {
        using var reader = new StreamReader(input, new UTF8Encoding(false));
        string? line;
        while ((line = await reader.ReadLineAsync(ct).ConfigureAwait(false)) != null)
        {
            using var doc = System.Text.Json.JsonDocument.Parse(line);
            var root = doc.RootElement;
            if (!root.TryGetProperty("id", out var id) || !root.TryGetProperty("method", out var m)) continue;
            var result = m.GetString() switch
            {
                "initialize" => "{\"protocolVersion\":" + root.GetProperty("params").GetProperty("protocolVersion").GetRawText()
                    + ",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"warmup\",\"version\":\"0\"}}",
                "tools/list" => "{\"tools\":[{\"name\":\"t\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}}]}",
                "tools/call" => "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}",
                _ => "{}",
            };
            var bytes = Encoding.UTF8.GetBytes("{\"jsonrpc\":\"2.0\",\"id\":" + id.GetRawText() + ",\"result\":" + result + "}\n");
            await output.WriteAsync(bytes, ct).ConfigureAwait(false);
            await output.FlushAsync(ct).ConfigureAwait(false);
        }
        output.Dispose();
    }
}
