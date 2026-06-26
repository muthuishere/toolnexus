using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// Shared test helpers: resolves the shared <c>agentskillsmcp/examples</c> fixtures
/// the same way the examples do, and a tiny <see cref="HttpListener"/> stub for the
/// hermetic HTTP / LLM-client tests (no network, no live LLM).
/// </summary>
internal static class TestFixtures
{
    public static string Fixture(string relative)
    {
        // Walk up from the test binary (and cwd) until we find the shared examples dir.
        foreach (var start in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            var dir = new DirectoryInfo(start);
            while (dir != null)
            {
                var candidate = Path.Combine(dir.FullName, "examples", relative);
                if (File.Exists(candidate) || Directory.Exists(candidate))
                    return Path.GetFullPath(candidate);
                dir = dir.Parent;
            }
        }
        return Path.GetFullPath(Path.Combine("..", "examples", relative));
    }

    public static string SkillsDir() => Fixture("skills");
}

/// <summary>An ephemeral localhost HTTP stub driven by a handler delegate.</summary>
internal sealed class StubServer : IDisposable
{
    private readonly HttpListener _listener = new();
    private readonly CancellationTokenSource _cts = new();

    public int Port { get; }
    public string BaseUrl => $"http://127.0.0.1:{Port}";

    public StubServer(Action<HttpListenerContext> handler)
    {
        Port = FreePort();
        _listener.Prefixes.Add($"http://127.0.0.1:{Port}/");
        _listener.Start();
        _ = Task.Run(async () =>
        {
            while (!_cts.IsCancellationRequested)
            {
                HttpListenerContext ctx;
                try { ctx = await _listener.GetContextAsync().ConfigureAwait(false); }
                catch { break; }
                try { handler(ctx); }
                catch { /* ignore handler errors */ }
            }
        });
    }

    public static void Respond(HttpListenerContext ctx, int status, string body)
    {
        var bytes = Encoding.UTF8.GetBytes(body);
        ctx.Response.StatusCode = status;
        ctx.Response.ContentType = "application/json";
        ctx.Response.ContentLength64 = bytes.Length;
        ctx.Response.OutputStream.Write(bytes, 0, bytes.Length);
        ctx.Response.OutputStream.Close();
    }

    private static int FreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _listener.Stop(); } catch { }
        try { _listener.Close(); } catch { }
    }
}
