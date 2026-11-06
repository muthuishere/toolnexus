using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>Replays conformance/serve_mcp/cases.json (SPEC §7C): the toolkit.serve MCP wire, all 8 ports.</summary>
public class ServeMcpConformanceTests
{
    private static string CasesFile()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null; d = d.Parent)
        {
            var p = Path.Combine(d.FullName, "conformance", "serve_mcp", "cases.json");
            if (File.Exists(p)) return p;
        }
        throw new FileNotFoundException("conformance/serve_mcp/cases.json");
    }

    [Fact]
    public async Task Cases()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(CasesFile()));
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            ExtraTools = new List<ITool> { NativeTool.Of("echo", "echo", null, _ => "x") },
        });
        var srv = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions { Mcp = new MCPServeConfig() });
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
        try
        {
            foreach (var c in doc.RootElement.GetProperty("cases").EnumerateArray())
            {
                var name = c.GetProperty("name").GetString();
                var rq = c.GetProperty("request");
                var msg = new HttpRequestMessage(new HttpMethod(rq.GetProperty("method").GetString()!),
                    srv.Url + rq.GetProperty("path").GetString());
                if (rq.TryGetProperty("headers", out var hs))
                    foreach (var h in hs.EnumerateObject()) msg.Headers.TryAddWithoutValidation(h.Name, h.Value.GetString());
                using var res = await http.SendAsync(msg, HttpCompletionOption.ResponseHeadersRead);
                var expect = c.GetProperty("expect");
                Assert.True(expect.GetProperty("status").GetInt32() == (int)res.StatusCode, $"{name}: status {(int)res.StatusCode}");
                if (expect.TryGetProperty("headers", out var eh))
                    foreach (var h in eh.EnumerateObject())
                    {
                        string? got = res.Headers.TryGetValues(h.Name, out var v1) ? string.Join(", ", v1)
                            : res.Content.Headers.TryGetValues(h.Name, out var v2) ? string.Join(", ", v2) : null;
                        Assert.True(h.Value.GetString() == got, $"{name}: header {h.Name} = {got}");
                    }
            }
        }
        finally
        {
            await srv.StopAsync();
        }
    }
}
