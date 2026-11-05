using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;

namespace Toolnexus.Tests;

// McpHandler (SPEC §7C.1, ADR 0039): the embeddable MCP server, exercised with the official
// ModelContextProtocol CLIENT over real streamable HTTP (HttpListener), plus raw HTTP checks for
// the protocol 2025-06-18 transport rules. Mirrors golang/mcphandler_test.go test for test.

/// <summary>A test ITool over a delegate, with settable §1 metadata; a throw propagates (native failure).</summary>
internal sealed class FnTool(string name, Func<IDictionary<string, object?>, ToolResult> fn) : ITool
{
    public string Name => name;
    public string Description { get; init; } = name + " tool";
    public IDictionary<string, object?> InputSchema { get; init; } = new Dictionary<string, object?>
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["id"] = new Dictionary<string, object?> { ["type"] = "string" } },
    };
    public string Source => "native";
    public ToolHints? Hints { get; init; }
    public IDictionary<string, object?>? OutputSchema { get; init; }
    public ToolAnnotations? Annotations { get; init; }
    public Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null) => Task.FromResult(fn(args));
}

/// <summary>Hosts an <see cref="McpHttpHandler"/> on an ephemeral HttpListener, one task per request.</summary>
internal sealed class HandlerHost : IDisposable
{
    private readonly HttpListener _listener = new();
    public string Url { get; }

    public HandlerHost(McpHttpHandler handler)
    {
        for (var attempt = 0; ; attempt++)
        {
            var l = new TcpListener(IPAddress.Loopback, 0);
            l.Start();
            var port = ((IPEndPoint)l.LocalEndpoint).Port;
            l.Stop();
            _listener.Prefixes.Clear();
            _listener.Prefixes.Add($"http://127.0.0.1:{port}/");
            try { _listener.Start(); Url = $"http://127.0.0.1:{port}/"; break; }
            catch (HttpListenerException) when (attempt < 20) { }
        }
        _ = Task.Run(async () =>
        {
            while (true)
            {
                HttpListenerContext ctx;
                try { ctx = await _listener.GetContextAsync(); }
                catch { break; }
                _ = Task.Run(() => handler.HandleAsync(ctx));
            }
        });
    }

    public void Dispose()
    {
        try { _listener.Stop(); } catch { }
        try { _listener.Close(); } catch { }
    }
}

public class McpHandlerTests
{
    internal sealed record User(string Name);

    internal static readonly HttpClient Http = new();

    internal static FnTool TestTool(string name, Action? ran = null, ToolAnnotations? ann = null) =>
        new(name, _ =>
        {
            ran?.Invoke();
            var u = McpContext.Current?.Identity as User;
            return new ToolResult(name + ":" + (u?.Name ?? ""), false);
        })
        { Annotations = ann };

    private static async Task<Toolkit> NewTk() => await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });

    private static Task<object?> BearerAuth(McpHttpExchange ex)
    {
        var tok = (ex.Header("Authorization") ?? "").Replace("Bearer ", "");
        if (tok is "alice" or "bob") return Task.FromResult<object?>(new User(tok));
        throw new UnauthorizedAccessException("bad token");
    }

    private static async Task<McpClient> Dial(string url, string token, Func<ElicitRequestParams?, ElicitResult>? elicit = null)
    {
        var opts = new McpClientOptions { ProtocolVersion = "2025-06-18" };
        if (elicit != null)
            opts.Handlers = new McpClientHandlers { ElicitationHandler = (p, _) => ValueTask.FromResult(elicit(p)) };
        return await McpClient.CreateAsync(new HttpClientTransport(new HttpClientTransportOptions
        {
            Endpoint = new Uri(url),
            TransportMode = HttpTransportMode.StreamableHttp,
            AdditionalHeaders = new Dictionary<string, string> { ["Authorization"] = "Bearer " + token },
        }), opts);
    }

    private static Task<CallToolResult> Call(McpClient c, string name, Dictionary<string, object?>? args = null)
        => c.CallToolAsync(name, args).AsTask();

    private static string Text(CallToolResult r) => r.Content.OfType<TextContentBlock>().FirstOrDefault()?.Text ?? "";

    private static async Task<string> Names(McpClient c) => string.Join(",", (await c.ListToolsAsync()).Select(t => t.Name));

    internal static async Task<HttpResponseMessage> RawPost(string url, string? sid, string body, Dictionary<string, string>? hdr = null)
    {
        var req = new HttpRequestMessage(HttpMethod.Post, url) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        req.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
        req.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream");
        if (!string.IsNullOrEmpty(sid)) req.Headers.TryAddWithoutValidation("Mcp-Session-Id", sid);
        foreach (var (k, v) in hdr ?? new()) req.Headers.TryAddWithoutValidation(k, v);
        return await Http.SendAsync(req);
    }

    private static async Task<string> RawBody(string url, string? sid, string body, Dictionary<string, string>? hdr = null)
        => await (await RawPost(url, sid, body, hdr)).Content.ReadAsStringAsync();

    private static string? Sid(HttpResponseMessage r) => r.Headers.TryGetValues("Mcp-Session-Id", out var v) ? v.First() : null;

    [Fact]
    public async Task AuthRejectsWith401AndWwwAuthenticate()
    {
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            Authenticate = BearerAuth,
            WwwAuthenticate = (_, _) => "Bearer resource_metadata=\"https://example.test/.well-known/oauth-protected-resource\"",
        }));
        var resp = await RawPost(host.Url, null, """{"jsonrpc":"2.0","id":1,"method":"ping"}""");
        Assert.Equal(401, (int)resp.StatusCode);
        Assert.Contains("resource_metadata=", string.Join(",", resp.Headers.WwwAuthenticate.Select(h => h.ToString())));
    }

    [Fact]
    public async Task PerIdentityToolsAndNotFoundNeverExecutes()
    {
        var ranAdmin = 0;
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            Authenticate = BearerAuth,
            ListTools = ctx =>
            {
                // runtime-synthesized: none of these are registered in the toolkit
                var tools = new List<ITool> { TestTool("search") };
                if (((User)ctx.Identity!).Name == "alice") tools.Add(TestTool("admin_reset", () => Interlocked.Increment(ref ranAdmin)));
                return Task.FromResult<IReadOnlyList<ITool>>(tools);
            },
        }));
        await using var alice = await Dial(host.Url, "alice");
        await using var bob = await Dial(host.Url, "bob");
        Assert.Equal("search,admin_reset", await Names(alice));
        Assert.Equal("search", await Names(bob));
        Assert.Equal("search:bob", Text(await Call(bob, "search")));
        var ex = await Assert.ThrowsAnyAsync<Exception>(() => Call(bob, "admin_reset"));
        Assert.Contains("tool not found", ex.Message);
        Assert.Equal(0, ranAdmin);
        Assert.Equal("admin_reset:alice", Text(await Call(alice, "admin_reset")));
    }

    private static McpHandlerConfig Gated(Action ran, ToolResult? fallback)
    {
        var del = TestTool("delete_record", ran, new ToolAnnotations
        {
            Title = "Delete", DestructiveHint = true, ReadOnlyHint = false, IdempotentHint = true, OpenWorldHint = false,
        });
        return new McpHandlerConfig
        {
            Authenticate = BearerAuth,
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(new List<ITool> { del, TestTool("read") }),
            BeforeCall = (_, tool, args) =>
            {
                var raw = args.GetRawText();
                if (tool.Annotations?.DestructiveHint == true)
                    return Task.FromResult(raw.Contains("\"nonce-ok\"")
                        ? McpDecision.Allow()
                        : McpDecision.Confirm("Delete the record?", fallback));
                if (raw.Contains("\"forbidden\"")) return Task.FromResult(McpDecision.Deny("not allowed"));
                return Task.FromResult(McpDecision.Allow());
            },
        };
    }

    private static ElicitResult Answer(string action) => new()
    {
        Action = action,
        Content = new Dictionary<string, JsonElement> { ["confirm"] = JsonSerializer.SerializeToElement(action == "accept") },
    };

    [Fact]
    public async Task ConfirmViaElicitation()
    {
        var ran = 0;
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(Gated(() => Interlocked.Increment(ref ran), null)));
        var asked = 0;
        await using var c = await Dial(host.Url, "alice", _ => { asked++; return Answer("accept"); });
        var r = await Call(c, "delete_record", new() { ["id"] = "1" });
        Assert.False(r.IsError ?? false);
        Assert.Equal("delete_record:alice", Text(r));
        Assert.Equal(1, asked);
        Assert.Equal(1, ran);

        await using var c2 = await Dial(host.Url, "alice", _ => Answer("decline"));
        r = await Call(c2, "delete_record", new() { ["id"] = "1" });
        Assert.True(r.IsError);
        Assert.Equal("declined", Text(r));
        Assert.Equal(1, ran);
    }

    [Fact]
    public async Task ConfirmRawElicitationRoundTrip()
    {
        // The elicitation/create rides the SAME POST's SSE response; the answer arrives on a later POST.
        var ran = 0;
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(Gated(() => Interlocked.Increment(ref ran), null)));
        var auth = new Dictionary<string, string> { ["Authorization"] = "Bearer alice" };
        var init = await RawPost(host.Url, null,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"elicitation":{}}}}""", auth);
        var sid = Sid(init)!;
        var req = new HttpRequestMessage(HttpMethod.Post, host.Url)
        {
            Content = new StringContent("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"delete_record","arguments":{"id":"1"}}}""", Encoding.UTF8, "application/json"),
        };
        req.Headers.TryAddWithoutValidation("Authorization", "Bearer alice");
        req.Headers.TryAddWithoutValidation("Mcp-Session-Id", sid);
        req.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream");
        var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
        Assert.Equal("text/event-stream", resp.Content.Headers.ContentType?.MediaType);
        using var reader = new StreamReader(await resp.Content.ReadAsStreamAsync());
        async Task<JsonElement> NextEvent()
        {
            while (true)
            {
                var line = await reader.ReadLineAsync();
                if (line == null) throw new Exception("stream ended");
                if (line.StartsWith("data: ")) return JsonDocument.Parse(line[6..]).RootElement.Clone();
            }
        }
        var ask = await NextEvent();
        Assert.Equal("elicitation/create", ask.GetProperty("method").GetString());
        Assert.Equal("Delete the record?", ask.GetProperty("params").GetProperty("message").GetString());
        var id = ask.GetProperty("id").GetString();
        var ack = await RawPost(host.Url, sid,
            "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"action\":\"accept\",\"content\":{\"confirm\":true}}}", auth);
        Assert.Equal(202, (int)ack.StatusCode);
        var final = await NextEvent();
        Assert.Equal(2, final.GetProperty("id").GetInt32());
        Assert.Equal("delete_record:alice", final.GetProperty("result").GetProperty("content")[0].GetProperty("text").GetString());
        Assert.Equal(1, ran);
    }

    [Fact]
    public async Task ConfirmTimeoutFails()
    {
        await using var tk = await NewTk();
        var cfg = Gated(() => { }, null);
        cfg.ConfirmTimeout = TimeSpan.FromMilliseconds(100);
        using var host = new HandlerHost(tk.McpHandler(cfg));
        await using var c = await Dial(host.Url, "alice", _ => { Thread.Sleep(1500); return Answer("accept"); });
        var r = await Call(c, "delete_record", new() { ["id"] = "1" });
        Assert.True(r.IsError);
        Assert.StartsWith("confirmation failed: ", Text(r));
    }

    [Fact]
    public async Task ConfirmWithoutElicitation()
    {
        var ran = 0;
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(Gated(() => Interlocked.Increment(ref ran), null)));
        await using var c = await Dial(host.Url, "alice");
        var r = await Call(c, "delete_record", new() { ["id"] = "1" });
        Assert.True(r.IsError);
        Assert.Equal("confirmation required: Delete the record?", Text(r));
        Assert.Equal(0, ran);

        var ran2 = 0;
        await using var tk2 = await NewTk();
        using var host2 = new HandlerHost(tk2.McpHandler(Gated(() => Interlocked.Increment(ref ran2),
            new ToolResult("re-call with {\"nonce\":\"nonce-ok\"}", false))));
        await using var c2 = await Dial(host2.Url, "alice");
        r = await Call(c2, "delete_record", new() { ["id"] = "1" });
        Assert.False(r.IsError ?? false);
        Assert.Contains("nonce-ok", Text(r));
        Assert.Equal(0, ran2);
        r = await Call(c2, "delete_record", new() { ["id"] = "1", ["nonce"] = "nonce-ok" });
        Assert.Equal("delete_record:alice", Text(r));
        Assert.Equal(1, ran2);
    }

    [Fact]
    public async Task DenyAndAnnotations()
    {
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(Gated(() => { }, null)));
        await using var c = await Dial(host.Url, "alice");
        var r = await Call(c, "read", new() { ["id"] = "forbidden" });
        Assert.True(r.IsError);
        Assert.Equal("not allowed", Text(r));
        var a = (await c.ListToolsAsync())[0].ProtocolTool.Annotations!;
        Assert.Equal("Delete", a.Title);
        Assert.True(a.DestructiveHint);
        Assert.False(a.ReadOnlyHint);
        Assert.True(a.IdempotentHint);
        Assert.False(a.OpenWorldHint);
    }

    [Fact]
    public async Task OnCallEvent()
    {
        var evs = new List<McpCallEvent>();
        var boom = new FnTool("boom", _ => { Thread.Sleep(5); throw new InvalidOperationException("kaput"); });
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            Authenticate = BearerAuth,
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(new List<ITool> { boom }),
            OnCall = ev => { lock (evs) evs.Add(ev); throw new Exception("isolated"); },
        }));
        await using var c = await Dial(host.Url, "bob");
        var r = await Call(c, "boom");
        Assert.True(r.IsError);
        Assert.Equal("kaput", Text(r));
        var ev = Assert.Single(evs);
        Assert.Equal("boom", ev.Name);
        Assert.True(ev.IsError);
        Assert.NotNull(ev.Err);
        Assert.True(ev.ResultBytes > 0);
        Assert.True(ev.Ms >= 5);
        Assert.Equal("bob", ((User)ev.Identity!).Name);
        Assert.NotEqual("", ev.SessionId);
        Assert.Equal(McpDecisionKind.Allow, ev.Decision);
    }

    [Fact]
    public async Task ListChangedNotification()
    {
        await using var tk = await NewTk();
        var h = tk.McpHandler(new McpHandlerConfig
        {
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(new List<ITool> { TestTool("a") }),
        });
        using var host = new HandlerHost(h);
        var got = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        await using var c = await Dial(host.Url, "");
        await using var reg = c.RegisterNotificationHandler("notifications/tools/list_changed", (n, _) =>
        {
            got.TrySetResult(n.Method);
            return ValueTask.CompletedTask;
        });
        var deadline = DateTime.UtcNow.AddSeconds(5);
        while (!got.Task.IsCompleted)
        {
            Assert.True(DateTime.UtcNow < deadline, "no list_changed received");
            h.NotifyToolsChanged();
            await Task.WhenAny(got.Task, Task.Delay(50));
        }
        Assert.Equal("notifications/tools/list_changed", await got.Task);
    }

    [Fact]
    public async Task ServerInfoResourcesPrompts()
    {
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            Authenticate = BearerAuth,
            ServerInfo = ctx => new McpServerInfo(
                "srv-" + ctx.Request.Header("Host")![..9] + "-" + ((User)ctx.Identity!).Name, "9.9.9", "be careful"),
            ListResources = _ => Task.FromResult<IReadOnlyList<McpResource>>(new[] { new McpResource("docs://api", "api", MimeType: "text/markdown") }),
            ReadResource = (ctx, uri) => Task.FromResult<IReadOnlyList<McpResourceContent>>(new[]
            {
                new McpResourceContent(uri, "text/markdown", "# API for " + ((User)ctx.Identity!).Name),
            }),
            ListPrompts = _ => Task.FromResult<IReadOnlyList<McpPrompt>>(new[] { new McpPrompt("greet", Arguments: new[] { new McpPromptArgument("who", Required: true) }) }),
            GetPrompt = (_, _, args) => Task.FromResult(new McpPromptResult(new[] { new McpPromptMessage("user", "hello " + args["who"]) })),
        }));
        await using var c = await Dial(host.Url, "alice");
        Assert.Equal("srv-127.0.0.1-alice", c.ServerInfo.Name);
        Assert.Equal("9.9.9", c.ServerInfo.Version);
        Assert.Equal("be careful", c.ServerInstructions);
        Assert.Equal("2025-06-18", c.NegotiatedProtocolVersion);
        Assert.NotNull(c.ServerCapabilities.Resources);
        Assert.NotNull(c.ServerCapabilities.Prompts);
        var rl = await c.ListResourcesAsync();
        Assert.Equal("docs://api", Assert.Single(rl).Uri);
        var rd = await c.ReadResourceAsync("docs://api");
        Assert.Equal("# API for alice", ((TextResourceContents)rd.Contents[0]).Text);
        var pr = await c.GetPromptAsync("greet", new Dictionary<string, object?> { ["who"] = "world" });
        Assert.Equal("hello world", ((TextContentBlock)pr.Messages[0].Content).Text);
        var pl = await c.ListPromptsAsync();
        Assert.Equal("who", pl[0].ProtocolPrompt.Arguments![0].Name);
        Assert.True(pl[0].ProtocolPrompt.Arguments![0].Required);
    }

    [Fact]
    public async Task ProtocolConformance()
    {
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(Array.Empty<ITool>()),
        }));
        var url = host.Url;
        var resp = await RawPost(url, null, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}""");
        var sid = Sid(resp);
        var body = await resp.Content.ReadAsStringAsync();
        Assert.Equal(200, (int)resp.StatusCode);
        Assert.False(string.IsNullOrEmpty(sid));
        Assert.Contains("\"protocolVersion\":\"2025-06-18\"", body);
        Assert.Contains("\"listChanged\":true", body);
        // unknown version ⇒ our latest
        Assert.Contains("\"protocolVersion\":\"2025-06-18\"", await RawBody(url, null, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}"""));
        var v = new Dictionary<string, string> { ["MCP-Protocol-Version"] = "2025-06-18" };
        Assert.Equal(202, (int)(await RawPost(url, sid, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", v)).StatusCode);
        Assert.Equal(400, (int)(await RawPost(url, null, """{"jsonrpc":"2.0","id":2,"method":"ping"}""", v)).StatusCode);
        Assert.Equal(404, (int)(await RawPost(url, "nope", """{"jsonrpc":"2.0","id":2,"method":"ping"}""", v)).StatusCode);
        Assert.Equal(400, (int)(await RawPost(url, sid, """{"jsonrpc":"2.0","id":2,"method":"ping"}""",
            new() { ["MCP-Protocol-Version"] = "2099-01-01" })).StatusCode);
        Assert.Contains("-32600", await RawBody(url, sid, """[{"jsonrpc":"2.0","id":3,"method":"ping"}]""", v));
        Assert.Contains("\"result\":{}", await RawBody(url, sid, """{"jsonrpc":"2.0","id":4,"method":"ping"}""", v));
        Assert.Contains("-32601", await RawBody(url, sid, """{"jsonrpc":"2.0","id":5,"method":"resources/list"}""", v));
        // wrong content type ⇒ 415; GET without SSE accept ⇒ 405
        var bad = new HttpRequestMessage(HttpMethod.Post, url) { Content = new StringContent("{}", Encoding.UTF8, "text/plain") };
        Assert.Equal(415, (int)(await Http.SendAsync(bad)).StatusCode);
        Assert.Equal(405, (int)(await Http.GetAsync(url)).StatusCode);
        // oversized body ⇒ 413
        await using var tkSmall = await NewTk();
        using var small = new HandlerHost(tkSmall.McpHandler(new McpHandlerConfig { Stateless = true, MaxBodyBytes = 64 }));
        Assert.Equal(413, (int)(await RawPost(small.Url, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\"" + new string('x', 200) + "\"}}")).StatusCode);
        // DELETE ends the session; it is then 404
        var del = new HttpRequestMessage(HttpMethod.Delete, url);
        del.Headers.TryAddWithoutValidation("Mcp-Session-Id", sid);
        Assert.Equal(200, (int)(await Http.SendAsync(del)).StatusCode);
        Assert.Equal(404, (int)(await RawPost(url, sid, """{"jsonrpc":"2.0","id":6,"method":"ping"}""", v)).StatusCode);
    }

    private sealed class TestIds : IMcpSessionIds
    {
        public int Gen, Val, Term;
        public Task<string> GenerateAsync(McpContext ctx) { Interlocked.Increment(ref Gen); return Task.FromResult("host-session-1"); }
        public Task<bool> ValidateAsync(McpContext ctx, string id) { Interlocked.Increment(ref Val); return Task.FromResult(id == "host-session-1"); }
        public Task TerminateAsync(McpContext ctx, string id) { Interlocked.Increment(ref Term); return Task.CompletedTask; }
    }

    [Fact]
    public async Task CustomSessionIdsAndStateless()
    {
        var ids = new TestIds();
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            SessionIds = ids,
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(new List<ITool> { TestTool("a") }),
        }));
        await using (var c = await Dial(host.Url, ""))
            Assert.Equal("a:", Text(await Call(c, "a")));
        Assert.Equal(1, ids.Gen);
        Assert.True(ids.Val > 0);

        await using var tk2 = await NewTk();
        using var st = new HandlerHost(tk2.McpHandler(new McpHandlerConfig
        {
            Stateless = true,
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(new List<ITool> { TestTool("a") }),
        }));
        var resp = await RawPost(st.Url, null, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""");
        Assert.Null(Sid(resp));
        Assert.Contains("a:", await RawBody(st.Url, null, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"a"}}"""));
    }

    [Fact]
    public async Task DefaultsToToolkitTools()
    {
        await using var tk = await NewTk();
        tk.Register(TestTool("registered"));
        using var host = new HandlerHost(tk.McpHandler());
        await using var c = await Dial(host.Url, "");
        Assert.Contains("registered", await Names(c));
    }

    [Fact]
    public async Task StructuredContentAndOutputSchema()
    {
        var st = new FnTool("stats", _ => new ToolResult("", false) { Structured = new Dictionary<string, object?> { ["count"] = 3 } })
        {
            InputSchema = new Dictionary<string, object?> { ["type"] = "object" },
            OutputSchema = new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?> { ["count"] = new Dictionary<string, object?> { ["type"] = "integer" } },
            },
        };
        var plain = TestTool("plain");
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(new List<ITool> { st, plain }),
        }));
        await using var c = await Dial(host.Url, "");
        var lt = await c.ListToolsAsync();
        Assert.Equal("object", lt[0].ProtocolTool.OutputSchema!.Value.GetProperty("type").GetString());
        var r = await Call(c, "stats");
        Assert.Equal("{\"count\":3}", r.StructuredContent!.Value.GetRawText());
        Assert.Equal("{\"count\":3}", Text(r));
        Assert.False(McpHttpHandler.WireTool(plain).ContainsKey("outputSchema"));
    }

    [Fact]
    public async Task MetaPassthrough()
    {
        IReadOnlyDictionary<string, object?>? seen = null, seenHook = null;
        var tool = new FnTool("m", _ =>
        {
            seen = McpContext.Current?.Meta;
            return new ToolResult("ok", false)
            {
                Meta = new Dictionary<string, object?> { ["usage"] = new Dictionary<string, object?> { ["units"] = 2 } },
            };
        });
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            Stateless = true,
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(new List<ITool> { tool }),
            BeforeCall = (ctx, _, _) => { seenHook = ctx.Meta; return Task.FromResult(McpDecision.Allow()); },
        }));
        var b = await RawBody(host.Url, null, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"m","_meta":{"clientCaps":{"elicitation":{}}}}}""");
        Assert.Contains("\"_meta\":{\"usage\":{\"units\":2}}", b);
        Assert.True(seen!.ContainsKey("clientCaps"));
        Assert.True(seenHook!.ContainsKey("clientCaps"));
    }

    [Fact]
    public async Task ProtocolVersionsPerHandler()
    {
        static string Init(string v) => "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + v + "\"}}";
        await using var tk = await NewTk();
        using var def = new HandlerHost(tk.McpHandler(new McpHandlerConfig { Stateless = true }));
        await using var tk2 = await NewTk();
        using var newer = new HandlerHost(tk2.McpHandler(new McpHandlerConfig
        {
            Stateless = true,
            ProtocolVersions = new[] { "2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26" },
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>(new List<ITool> { TestTool("a") }),
        }));
        Assert.Contains("\"protocolVersion\":\"2025-06-18\"", await RawBody(def.Url, null, Init("2025-11-25")));
        Assert.Equal(400, (int)(await RawPost(def.Url, null, """{"jsonrpc":"2.0","id":2,"method":"ping"}""", new() { ["MCP-Protocol-Version"] = "2025-11-25" })).StatusCode);
        Assert.Contains("\"protocolVersion\":\"2025-11-25\"", await RawBody(newer.Url, null, Init("2025-11-25")));
        Assert.Contains("\"protocolVersion\":\"2025-11-25\"", await RawBody(newer.Url, null, Init("2026-07-28")));
        Assert.Equal(400, (int)(await RawPost(newer.Url, null, """{"jsonrpc":"2.0","id":2,"method":"ping"}""", new() { ["MCP-Protocol-Version"] = "2025-03-26" })).StatusCode);
        const string badArgs = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"a","arguments":[1]}}""";
        var b = await RawBody(newer.Url, null, badArgs, new() { ["MCP-Protocol-Version"] = "2025-11-25" });
        Assert.Contains("\"isError\":true", b);
        Assert.DoesNotContain("-32602", b);
        Assert.Contains("-32602", await RawBody(newer.Url, null, badArgs, new() { ["MCP-Protocol-Version"] = "2025-06-18" }));
    }

    [Fact]
    public async Task StatelessSessionIdAndTitle()
    {
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig { Stateless = true, StatelessSessionId = "stateless", Title = "Records API" }));
        var r = await RawPost(host.Url, null, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""");
        Assert.Equal("stateless", Sid(r));
        Assert.Contains("\"title\":\"Records API\"", await r.Content.ReadAsStringAsync());
        Assert.Contains("\"result\":{}", await RawBody(host.Url, "whatever", """{"jsonrpc":"2.0","id":2,"method":"ping"}"""));
        await using var tk2 = await NewTk();
        using var h2 = new HandlerHost(tk2.McpHandler(new McpHandlerConfig { Stateless = true }));
        Assert.DoesNotContain("\"title\"", await RawBody(h2.Url, null, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""));
    }

    [Fact]
    public void Serve_StructuredResultViaMcpServer()
    {
        // The §7C Serve path maps Structured/Meta/OutputSchema too, and stays byte-identical when absent.
        var opts = ModelContextProtocol.McpJsonUtilities.DefaultOptions;
        var schema = new Dictionary<string, object?> { ["type"] = "object" };
        var plainTool = JsonSerializer.Serialize(McpServe.ToMcpTool(new FnTool("x", _ => ToolResult.Ok("")) { InputSchema = schema }), opts);
        Assert.DoesNotContain("outputSchema", plainTool);
        var withOut = JsonSerializer.Serialize(McpServe.ToMcpTool(new FnTool("x", _ => ToolResult.Ok("")) { InputSchema = schema, OutputSchema = schema }), opts);
        Assert.Contains("\"outputSchema\":{\"type\":\"object\"}", withOut);

        // Absent ⇒ exactly the pre-§1 wire.
        var absent = JsonSerializer.Serialize(McpServe.ToCallToolResult(new ToolResult("x", false)), opts);
        var before = JsonSerializer.Serialize(new CallToolResult
        {
            Content = new List<ContentBlock> { new TextContentBlock { Text = "x" } },
            IsError = false,
        }, opts);
        Assert.Equal(before, absent);

        var b = JsonSerializer.Serialize(McpServe.ToCallToolResult(new ToolResult("hi", false)
        {
            Structured = new Dictionary<string, object?> { ["a"] = 1 },
            Meta = new Dictionary<string, object?> { ["k"] = "v" },
        }), opts);
        Assert.Contains("\"structuredContent\":{\"a\":1}", b);
        Assert.Contains("\"_meta\":{\"k\":\"v\"}", b);
        Assert.Contains("\"text\":\"hi\"", b);
    }
}
