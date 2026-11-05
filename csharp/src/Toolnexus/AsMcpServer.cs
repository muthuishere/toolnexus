using System.Net;
using System.Security.Cryptography;
using System.Text;

namespace Toolnexus;

/// <summary>
/// The toolkit served as a streamable-HTTP MCP server for a local agent (SPEC §8C,
/// <see cref="Toolkit.AsMcpServer"/>). Listens on <c>127.0.0.1:&lt;ephemeral&gt;</c>; only
/// <c>/mcp</c> exists, every request must carry <c>Authorization: Bearer &lt;Token&gt;</c>.
/// The caller builds whatever <c>mcpServers</c> entry its agent accepts from <see cref="Url"/>
/// and <see cref="Token"/>.
/// </summary>
public sealed class ToolkitMcpServer : IDisposable, IAsyncDisposable
{
    private readonly HttpListener _listener;
    private readonly CancellationTokenSource _cts = new();
    private int _closed;

    /// <summary><c>http://127.0.0.1:&lt;port&gt;/mcp</c>.</summary>
    public string Url { get; }

    /// <summary>32 random bytes, lowercase hex (64 chars), fresh per server. Never logged.</summary>
    public string Token { get; }

    internal ToolkitMcpServer(IReadOnlyList<ITool> tools, MCPServeConfig cfg)
    {
        Token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        var port = A2AServer.FreePort();
        Url = $"http://127.0.0.1:{port}/mcp";
        _listener = new HttpListener();
        _listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        _listener.Start();

        var want = Encoding.UTF8.GetBytes("Bearer " + Token);
        var listener = _listener;
        var cts = _cts;
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync().ConfigureAwait(false); }
                catch { break; }
                _ = Task.Run(async () =>
                {
                    try
                    {
                        if (ctx.Request.Url?.AbsolutePath != "/mcp")
                        {
                            Status(ctx, 404, "not found");
                            return;
                        }
                        var got = Encoding.UTF8.GetBytes(ctx.Request.Headers["Authorization"] ?? "");
                        // Constant-time: a length mismatch is a fixed-time reject too.
                        if (got.Length != want.Length || !CryptographicOperations.FixedTimeEquals(got, want))
                        {
                            ctx.Response.Headers["WWW-Authenticate"] = "Bearer";
                            Status(ctx, 401, "unauthorized");
                            return;
                        }
                        // POST ⇒ the stateless §7C endpoint; any other method ⇒ 405.
                        await A2AServer.HandleMcpAsync(ctx, tools, cfg, null).ConfigureAwait(false);
                    }
                    catch { /* isolated — the server keeps serving */ }
                });
            }
        });
    }

    private static void Status(HttpListenerContext ctx, int code, string text)
    {
        try
        {
            var b = Encoding.UTF8.GetBytes(text);
            ctx.Response.StatusCode = code;
            ctx.Response.ContentType = "text/plain";
            ctx.Response.ContentLength64 = b.Length;
            ctx.Response.OutputStream.Write(b);
            ctx.Response.OutputStream.Close();
        }
        catch { /* client hung up */ }
    }

    /// <summary>Stops the listener. Idempotent.</summary>
    public void Close()
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;
        try { _cts.Cancel(); } catch { /* best effort */ }
        try { _listener.Stop(); _listener.Close(); } catch { /* best effort */ }
        _cts.Dispose();
    }

    public void Dispose() => Close();

    public ValueTask DisposeAsync()
    {
        Close();
        return ValueTask.CompletedTask;
    }
}
