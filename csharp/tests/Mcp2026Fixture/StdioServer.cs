using System.Text.Json.Nodes;

namespace Mcp2026Fixture;

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
