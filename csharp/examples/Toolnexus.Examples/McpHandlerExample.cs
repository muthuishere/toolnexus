using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;

namespace Toolnexus.Examples;

/// <summary>
/// Embeddable MCP server (SPEC §7C.1): mount <c>tk.McpHandler</c> on your own HTTP server,
/// authenticate with a bearer token, give each caller its own tool list, and gate a destructive
/// tool behind a confirmation (elicitation when the client supports it, a host-shaped "re-call
/// with a nonce" result otherwise).
/// <para><c>dotnet run --project examples/Toolnexus.Examples -- mcphandler</c></para>
/// Self-contained: it starts the server on an ephemeral port and drives it with three MCP clients
/// (alice with elicitation, alice without, bob). Mirrors golang/examples/mcphandler.
/// </summary>
internal static class McpHandlerExample
{
    private sealed record User(string Name, string Role);

    // Demo tokens only. A real host verifies a JWT/OAuth token here.
    private static readonly Dictionary<string, User> Tokens = new()
    {
        ["token-alice"] = new("alice", "admin"),
        ["token-bob"] = new("bob", "viewer"),
    };

    private static Task<IReadOnlyList<ITool>> Tools(McpContext ctx)
    {
        var u = (User)ctx.Identity!;
        var list = new List<ITool>
        {
            new NativeToolBuilder("list_records", "List records", _ =>
                "records for " + ((User)McpContext.Current!.Identity!).Name + ": [1, 2]")
            { Annotations = new ToolAnnotations { ReadOnlyHint = true } }.Build(),
        };
        if (u.Role == "admin")
            list.Add(new NativeToolBuilder("delete_record", "Delete a record", a => $"deleted {a["id"]}")
            {
                Schema = new Dictionary<string, object?>
                {
                    ["type"] = "object",
                    ["properties"] = new Dictionary<string, object?>
                    {
                        ["id"] = new Dictionary<string, object?> { ["type"] = "string" },
                        ["nonce"] = new Dictionary<string, object?> { ["type"] = "string" },
                    },
                },
                Annotations = new ToolAnnotations { Title = "Delete record", DestructiveHint = true },
            }.Build());
        return Task.FromResult<IReadOnlyList<ITool>>(list);
    }

    private sealed class NativeToolBuilder(string name, string description, Func<IDictionary<string, object?>, object?> fn)
    {
        public IDictionary<string, object?> Schema { get; init; } = new Dictionary<string, object?> { ["type"] = "object" };
        public ToolAnnotations? Annotations { get; init; }

        public ITool Build()
        {
            var t = NativeTool.Of(name, description, Schema, fn);
            t.Annotations = Annotations;
            return t;
        }
    }

    private static Task<McpDecision> BeforeCall(McpContext _, ITool t, JsonElement args)
    {
        if (t.Annotations?.DestructiveHint != true) return Task.FromResult(McpDecision.Allow());
        if (args.GetRawText().Contains("\"nonce\":\"n-123\"")) return Task.FromResult(McpDecision.Allow()); // the host's own nonce check
        return Task.FromResult(McpDecision.Confirm("Really delete this record?",
            new ToolResult("Confirmation needed: call delete_record again with \"nonce\":\"n-123\".", false)));
    }

    public static async Task<int> Run()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var handler = tk.McpHandler(new McpHandlerConfig
        {
            Name = "records",
            Version = "1.0.0",
            Authenticate = ex =>
            {
                var tok = (ex.Header("Authorization") ?? "").Replace("Bearer ", "");
                if (Tokens.TryGetValue(tok, out var u)) return Task.FromResult<object?>(u);
                throw new UnauthorizedAccessException("invalid token");
            },
            WwwAuthenticate = (_, _) => "Bearer resource_metadata=\"https://example.test/.well-known/oauth-protected-resource\"",
            ListTools = Tools,
            BeforeCall = BeforeCall,
            OnCall = ev => Console.WriteLine(
                $"  [onCall] {ev.Name} by {((User)ev.Identity!).Name} decision={ev.Decision.ToString().ToLowerInvariant()} isError={ev.IsError} {ev.ResultBytes}B"),
        });

        // Mount on the host's own HttpListener at /mcp (an ASP.NET host subclasses McpHttpExchange instead).
        var probe = new TcpListener(IPAddress.Loopback, 0);
        probe.Start();
        var port = ((IPEndPoint)probe.LocalEndpoint).Port;
        probe.Stop();
        var listener = new HttpListener();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/mcp/");
        listener.Start();
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); }
                catch { break; }
                _ = Task.Run(() => handler.HandleAsync(ctx));
            }
        });
        var url = $"http://127.0.0.1:{port}/mcp/";

        try
        {
            using var http = new HttpClient();
            var resp = await http.PostAsync(url, new StringContent("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", Encoding.UTF8, "application/json"));
            Console.WriteLine($"no token -> {(int)resp.StatusCode}, WWW-Authenticate: {string.Join(",", resp.Headers.WwwAuthenticate)}");

            Console.WriteLine("alice (admin, client supports elicitation):");
            await using (var alice = await Connect(url, "token-alice", elicit: true))
            {
                await Show(alice);
                Console.WriteLine("  delete_record -> " + await CallText(alice, "delete_record", new() { ["id"] = "7" }));
            }

            Console.WriteLine("alice (admin, client WITHOUT elicitation):");
            await using (var alice2 = await Connect(url, "token-alice", elicit: false))
            {
                Console.WriteLine("  delete_record -> " + await CallText(alice2, "delete_record", new() { ["id"] = "7" }));
                Console.WriteLine("  delete_record+nonce -> " + await CallText(alice2, "delete_record", new() { ["id"] = "7", ["nonce"] = "n-123" }));
            }

            Console.WriteLine("bob (viewer):");
            await using (var bob = await Connect(url, "token-bob", elicit: false))
            {
                await Show(bob);
                Console.WriteLine("  delete_record -> " + await CallText(bob, "delete_record", new() { ["id"] = "7" }));
            }
        }
        finally
        {
            listener.Stop();
        }
        return 0;
    }

    private static async Task<McpClient> Connect(string url, string token, bool elicit)
    {
        var opts = new McpClientOptions();
        if (elicit)
            opts.Handlers = new McpClientHandlers
            {
                ElicitationHandler = (p, _) =>
                {
                    Console.WriteLine($"  [elicitation] {p?.Message} -> accept");
                    return ValueTask.FromResult(new ElicitResult
                    {
                        Action = "accept",
                        Content = new Dictionary<string, JsonElement> { ["confirm"] = JsonSerializer.SerializeToElement(true) },
                    });
                },
            };
        return await McpClient.CreateAsync(new HttpClientTransport(new HttpClientTransportOptions
        {
            Endpoint = new Uri(url),
            TransportMode = HttpTransportMode.StreamableHttp,
            AdditionalHeaders = new Dictionary<string, string> { ["Authorization"] = "Bearer " + token },
        }), opts);
    }

    private static async Task Show(McpClient c)
        => Console.WriteLine("  tools: " + string.Join(", ", (await c.ListToolsAsync()).Select(t => t.Name)));

    private static async Task<string> CallText(McpClient c, string name, Dictionary<string, object?> args)
    {
        try
        {
            var r = await c.CallToolAsync(name, args);
            var text = r.Content.OfType<TextContentBlock>().FirstOrDefault()?.Text ?? "";
            return r.IsError == true ? "error: " + text : text;
        }
        catch (Exception e)
        {
            return "rpc error: " + e.Message;
        }
    }
}
