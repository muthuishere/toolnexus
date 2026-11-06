using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Toolnexus;

namespace Mcp2026Interop;

/// <summary>
/// The SERVER half of the interop matrix: a toolkit behind McpHttpHandler on the 2026-07-28 wire, protected by
/// McpServerAuth + the dev authorization server, plus an unprotected mount and a legacy-only mount. Prints one
/// JSON line {"base","issuer"} and serves until stdin closes. Mirrors golang/internal/interop/mcp2026server.
/// </summary>
internal static class InteropServer
{
    // Live mode (--keycloak): interop/mcp2026/keycloak/docker-compose.yml.
    private const string KeycloakIssuer = "http://localhost:8180/realms/toolnexus";
    private const int KeycloakPort = 7199;

    private static Dictionary<string, object?> Obj(Dictionary<string, object?>? props = null, params string[] required)
    {
        var s = new Dictionary<string, object?> { ["type"] = "object" };
        if (props != null) s["properties"] = props;
        if (required.Length > 0) s["required"] = required.ToList<object?>();
        return s;
    }

    internal static IReadOnlyList<ITool> Tools() =>
    [
        NativeTool.Of("echo", "Echo the given text back.",
            Obj(new() { ["text"] = new Dictionary<string, object?> { ["type"] = "string" } }, "text"),
            args =>
            {
                var s = args.TryGetValue("text", out var t) ? t?.ToString() ?? "" : "";
                return new ToolResult(s, false) { Structured = new Dictionary<string, object?> { ["text"] = s } };
            }),
        NativeTool.Of("greet", "Greet the user; asks for their name.", Obj(), (_, ctx) =>
        {
            if (ctx?.Answer is { Ok: true } a)
            {
                var n = a.Data != null && a.Data.TryGetValue("name", out var v) ? v?.ToString() ?? "" : "";
                return "Hello, " + n + "!";
            }
            return ToolResult.Pending(new Request
            {
                Id = "name", Kind = "input", Prompt = "What is your name?",
                Data = new Dictionary<string, object?>
                {
                    ["schema"] = Obj(new() { ["name"] = new Dictionary<string, object?> { ["type"] = "string" } }, "name"),
                },
            });
        }),
        NativeTool.Of("wipe", "Destructive; asks for confirmation first.", Obj(), _ => "wiped"),
        NativeTool.Of("whoami", "Who is calling.", Obj(), _ => (McpContext.Current?.Identity as McpPrincipal)?.Subject ?? ""),
    ];

    public static async Task<int> RunAsync(string[] args)
    {
        var keycloak = args.Length > 0 && args[0] == "--keycloak";
        if (keycloak) args = args[1..];
        var skillsDir = args.Length > 0 ? args[0] : "../examples/skills";

        Mcp2026Fixture.DevAuthServer? dev = null;
        var issuer = KeycloakIssuer;
        if (!keycloak)
        {
            dev = await Mcp2026Fixture.DevAuthServer.StartAsync();
            dev.Clients["interop-client"] = ["http://127.0.0.1/callback"];
            issuer = dev.Issuer;
        }

        var tools = Tools();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var mu = new object();
        var seen = new List<string>(); // "<mount> <MCP-Protocol-Version> <Mcp-Method>"
        var executions = new Dictionary<string, int>();

        McpHandlerConfig Cfg() => new()
        {
            Name = "interop", Version = "1.0.0",
            ProtocolVersions = ["2026-07-28"],
            ListTools = _ => Task.FromResult(tools),
            ListSkills = _ => Task.FromResult(McpHttpHandler.LoadMcpSkills(skillsDir)),
            BeforeCall = (_, t, _) => Task.FromResult(t.Name == "wipe" ? McpDecision.Confirm("Really wipe?") : McpDecision.Allow()),
            OnCall = ev => { lock (mu) executions[ev.Name] = executions.GetValueOrDefault(ev.Name) + 1; },
        };
        var auth = new McpServerAuth
        {
            Issuer = issuer,
            Scopes = new Dictionary<string, IReadOnlyList<string>> { ["*"] = ["mcp"], ["whoami"] = ["mcp:whoami"] },
        };
        if (keycloak)
        {
            // The realm's audience mapper pins aud = http://127.0.0.1:7199/mcp.
            auth.Audience = $"http://127.0.0.1:{KeycloakPort}/mcp";
            auth.Scopes = new Dictionary<string, IReadOnlyList<string>> { ["*"] = ["mcp:tools"], ["whoami"] = ["mcp:greet"] };
        }
        var protectedCfg = Cfg();
        protectedCfg.Authenticate = auth.AuthenticateAsync;

        var open = tk.McpHandler(Cfg());
        var authed = tk.McpHandler(protectedCfg);
        var legacy = tk.McpHandler(new McpHandlerConfig { Stateless = true, ListTools = _ => Task.FromResult(tools) });

        async Task Logged(string mount, McpHttpHandler h, HttpListenerContext ctx)
        {
            lock (mu) seen.Add($"{mount} {ctx.Request.Headers["MCP-Protocol-Version"]} {ctx.Request.Headers["Mcp-Method"]}");
            await h.HandleAsync(ctx);
        }

        async Task Dispatch(HttpListenerContext ctx)
        {
            try
            {
                switch (ctx.Request.Url!.AbsolutePath)
                {
                    case "/open/mcp": await Logged("open", open, ctx); break;
                    case "/mcp": await Logged("auth", authed, ctx); break;
                    case "/legacy/mcp": await Logged("legacy", legacy, ctx); break;
                    case "/.well-known/oauth-protected-resource/mcp":
                    case "/.well-known/oauth-protected-resource":
                        await auth.MetadataAsync(ctx); break;
                    case "/log":
                        string body;
                        lock (mu) body = JsonSerializer.Serialize(new { seen, calls = executions });
                        var b = Encoding.UTF8.GetBytes(body + "\n");
                        ctx.Response.ContentType = "application/json";
                        await ctx.Response.OutputStream.WriteAsync(b);
                        ctx.Response.Close();
                        break;
                    default:
                        ctx.Response.StatusCode = 404;
                        ctx.Response.Close();
                        break;
                }
            }
            catch (Exception e)
            {
                Console.Error.WriteLine($"dispatch: {e.Message}");
                try { ctx.Response.Abort(); } catch { /* gone */ }
            }
        }

        var listener = Listen(keycloak ? KeycloakPort : 0, out var port);
        _ = Task.Run(async () =>
        {
            while (true)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); }
                catch { break; }
                _ = Task.Run(() => Dispatch(ctx));
            }
        });
        Console.WriteLine(JsonSerializer.Serialize(new { @base = $"http://127.0.0.1:{port}", issuer }));
        Console.Out.Flush();
        // Serve until stdin closes (the runner owns our lifetime).
        await Console.In.ReadToEndAsync();
        listener.Stop();
        if (dev != null) await dev.DisposeAsync();
        return 0;
    }

    private static HttpListener Listen(int fixedPort, out int port)
    {
        for (var attempt = 0; ; attempt++)
        {
            port = fixedPort;
            if (port == 0)
            {
                var l = new TcpListener(IPAddress.Loopback, 0);
                l.Start();
                port = ((IPEndPoint)l.LocalEndpoint).Port;
                l.Stop();
            }
            var listener = new HttpListener();
            listener.Prefixes.Add($"http://127.0.0.1:{port}/");
            try { listener.Start(); return listener; }
            catch (HttpListenerException) when (fixedPort == 0 && attempt < 20) { }
        }
    }
}
