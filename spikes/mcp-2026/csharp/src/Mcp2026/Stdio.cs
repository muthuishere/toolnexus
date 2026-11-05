using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;

namespace Mcp2026;

// stdio client transport (MCP 2026-07-28 basic/transports): spawn the server, one JSON-RPC message per
// line on stdin/stdout. stderr is the server's log stream: passed through (or captured), never parsed.
// No headers, no auth — the _meta carries the version.

internal sealed class StdioConn : IAsyncDisposable
{
    private readonly Process _proc;
    private readonly SemaphoreSlim _write = new(1, 1);
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonObject>> _pending = new();
    private readonly TaskCompletionSource _done = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Task _stderrPump;
    private volatile string? _err;

    public StdioConn(Process proc, TextWriter stderr)
    {
        _proc = proc;
        _ = Task.Run(ReadLoopAsync);
        _stderrPump = Task.Run(async () =>
        {
            string? line;
            while ((line = await proc.StandardError.ReadLineAsync()) != null)
                lock (stderr) { stderr.WriteLine(line); stderr.Flush(); }
        });
    }

    private async Task ReadLoopAsync()
    {
        try
        {
            string? line;
            while ((line = await _proc.StandardOutput.ReadLineAsync()) != null)
            {
                var m = J.ParseObject(line);
                if (m == null || m["method"] != null || m["id"] == null) continue; // notification / server request / noise: ignored
                if (_pending.TryRemove(J.Serialize(m["id"]), out var tcs)) tcs.TrySetResult(m);
            }
            _err = "stdio server closed its stdout";
        }
        catch (Exception e) { _err = "stdio read: " + e.Message; }
        _done.TrySetResult();
        foreach (var k in _pending.Keys)
            if (_pending.TryRemove(k, out var t)) t.TrySetException(new McpException(_err!));
    }

    /// <summary>Writes one line and waits for the response with the same id. Never retried.</summary>
    public async Task<JsonObject> RoundTripAsync(long id, string body, CancellationToken ct)
    {
        if (_err != null) throw new McpException(_err);
        var tcs = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        var key = id.ToString();
        _pending[key] = tcs;
        try
        {
            if (_err != null) throw new McpException(_err);
            await _write.WaitAsync(ct);
            try
            {
                await _proc.StandardInput.WriteAsync(body + "\n"); // the serializer never emits a raw newline
                await _proc.StandardInput.FlushAsync(ct);
            }
            catch (IOException e) { throw new McpException("stdio write: " + e.Message, e); }
            finally { _write.Release(); }
            var msg = await tcs.Task.WaitAsync(McpClient.DefaultTimeout, ct);
            if (msg["result"] == null && msg["error"] == null) throw new McpException("response has neither result nor error");
            return msg;
        }
        catch (TimeoutException) { throw new McpException("stdio request timed out"); }
        finally { _pending.TryRemove(key, out _); }
    }

    public async ValueTask DisposeAsync()
    {
        try { _proc.StandardInput.Close(); } catch { /* already gone */ }
        if (await Task.WhenAny(_done.Task, Task.Delay(2000)) != _done.Task)
            try { _proc.Kill(true); } catch { /* exited */ }
        await _proc.WaitForExitAsync();
        await _stderrPump;
    }
}

public sealed partial class McpClient
{
    /// <summary>Starts the process and returns a client with the same API as <c>new McpClient(url)</c>.
    /// stderr: where the server's log goes (null = this process's stderr). Dispose to stop the process.</summary>
    public static McpClient StartStdio(ProcessStartInfo psi, TextWriter? stderr = null)
    {
        psi.RedirectStandardInput = psi.RedirectStandardOutput = psi.RedirectStandardError = true;
        psi.UseShellExecute = false;
        psi.StandardInputEncoding = new UTF8Encoding(false);
        psi.StandardOutputEncoding = psi.StandardErrorEncoding = Encoding.UTF8;
        var proc = Process.Start(psi) ?? throw new McpException("could not start " + psi.FileName);
        return new McpClient("") { Stdio = new StdioConn(proc, stderr ?? Console.Error) };
    }
}

public static class StdioServer
{
    /// <summary>Serves s as newline-delimited JSON-RPC over input/output until input closes. No auth and no HTTP
    /// headers exist on stdio; only the _meta protocol version is checked. (Tests and examples only.)</summary>
    public static async Task ServeAsync(Server s, TextReader input, TextWriter output)
    {
        string? line;
        while ((line = await input.ReadLineAsync()) != null)
        {
            var req = J.ParseObject(line);
            if (req == null)
            {
                await output.WriteLineAsync(J.Serialize(Server.RpcMessage(null, null, new McpRpcException(-32700, "Parse error"))));
                await output.FlushAsync();
                continue;
            }
            if (req["id"] == null) continue;
            var p = req["params"] as JsonObject ?? [];
            var mv = J.Str((p["_meta"] as JsonObject)?[Mcp.MetaVersion], "");
            var (res, e) = mv != Mcp.ProtocolVersion
                ? (null, Server.Unsupported(mv))
                : await s.DispatchAsync(new ToolContext(), J.Str(req["method"], ""), p);
            await output.WriteLineAsync(J.Serialize(Server.RpcMessage(req["id"], res, e)));
            await output.FlushAsync();
        }
    }
}
