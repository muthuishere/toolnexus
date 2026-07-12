using System.Collections.Concurrent;
using System.Net;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// A2A (outbound) tests — a hermetic in-process fake A2A agent (HttpListener),
/// no external network, no live LLM. Mirrors js/test/unit.test.ts.
/// </summary>
public class A2ATests
{
    private sealed class Seen
    {
        public string? Auth;
        public int SendMessageCalls;
        public int GetTaskCalls;
    }

    private sealed record Stub(StubServer Server, string CardUrl, Seen Seen) : IDisposable
    {
        public void Dispose() => Server.Dispose();
    }

    /// <summary>
    /// Fake A2A agent:
    ///   GET /.well-known/agent-card.json → card { name:"reviewer", url→self, skills:[review, plan, fail] }
    ///   POST / (JSON-RPC) SendMessage → Task{state:"submitted"}; GetTask → "working" once, then terminal.
    ///   Behavior keyed on message text: "fail" → failed; "slow" → stays "working"; else → completed(REVIEWED).
    /// </summary>
    private static Stub StartStub()
    {
        var seen = new Seen();
        var tasks = new ConcurrentDictionary<string, (int Polls, string Mode)>();
        var counter = 0;
        StubServer? server = null;

        void Handler(HttpListenerContext ctx)
        {
            var req = ctx.Request;
            var port = server!.Port;

            if (req.HttpMethod == "GET" && req.Url!.AbsolutePath == "/.well-known/agent-card.json")
            {
                StubServer.Respond(ctx, 200,
                    "{\"name\":\"reviewer\",\"description\":\"a code reviewer\",\"version\":\"1.0.0\"," +
                    "\"protocolVersion\":\"0.3.0\"," +
                    "\"capabilities\":{\"streaming\":false,\"pushNotifications\":false}," +
                    "\"defaultInputModes\":[\"text/plain\"],\"defaultOutputModes\":[\"text/plain\"]," +
                    "\"url\":\"http://127.0.0.1:" + port + "/\"," +
                    "\"skills\":[" +
                    "{\"id\":\"review\",\"name\":\"Review\",\"description\":\"Review some code\"}," +
                    "{\"id\":\"plan\",\"name\":\"Plan\",\"description\":\"Plan a task\"}," +
                    "{\"id\":\"fail\",\"name\":\"Fail\",\"description\":\"Always fails\"}]}");
                return;
            }

            if (req.HttpMethod == "POST")
            {
                seen.Auth = req.Headers["Authorization"];
                string body;
                using (var reader = new StreamReader(req.InputStream, Encoding.UTF8))
                    body = reader.ReadToEnd();
                var rpc = Json.ParseObjectLoose(body);
                var id = G(rpc, "id");
                var method = G(rpc, "method") as string;
                var @params = G(rpc, "params") as IDictionary<string, object?> ?? new Dictionary<string, object?>();

                if (method == "SendMessage")
                {
                    seen.SendMessageCalls++;
                    var message = G(@params, "message") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
                    var parts = G(message, "parts") as IEnumerable<object?> ?? Enumerable.Empty<object?>();
                    var text = string.Concat(parts.Select(p =>
                        G(p as IDictionary<string, object?>, "text")?.ToString() ?? ""));
                    var mode = text.Contains("fail") ? "failed" : text.Contains("slow") ? "slow" : "completed";
                    var taskId = "t" + (++counter);
                    tasks[taskId] = (0, mode);
                    Rpc(ctx, id, "{\"id\":\"" + taskId + "\",\"status\":{\"state\":\"submitted\"}}");
                }
                else if (method == "GetTask")
                {
                    seen.GetTaskCalls++;
                    var taskId = G(@params, "id")?.ToString() ?? "";
                    var t = tasks[taskId];
                    t = (t.Polls + 1, t.Mode);
                    tasks[taskId] = t;
                    if (t.Mode == "slow" || t.Polls < 2)
                        Rpc(ctx, id, "{\"id\":\"" + taskId + "\",\"status\":{\"state\":\"working\"}}");
                    else if (t.Mode == "failed")
                        Rpc(ctx, id, "{\"id\":\"" + taskId + "\",\"status\":{\"state\":\"failed\"," +
                            "\"message\":{\"role\":\"agent\",\"parts\":[{\"kind\":\"text\",\"text\":\"boom\"}]}}}");
                    else
                        Rpc(ctx, id, "{\"id\":\"" + taskId + "\",\"status\":{\"state\":\"completed\"}," +
                            "\"artifacts\":[{\"parts\":[{\"kind\":\"text\",\"text\":\"REVIEWED\"}]}]}");
                }
                else
                {
                    var idJson = id is string s ? "\"" + s + "\"" : Json.Stringify(id);
                    StubServer.Respond(ctx, 200,
                        "{\"jsonrpc\":\"2.0\",\"id\":" + idJson +
                        ",\"error\":{\"code\":-32601,\"message\":\"unknown method\"}}");
                }
                return;
            }

            StubServer.Respond(ctx, 404, "nope");
        }

        server = new StubServer(Handler);
        var cardUrl = $"{server.BaseUrl}/.well-known/agent-card.json";
        return new Stub(server, cardUrl, seen);
    }

    private static object? G(IDictionary<string, object?>? d, string k)
        => d != null && d.TryGetValue(k, out var v) ? v : null;

    private static void Rpc(HttpListenerContext ctx, object? id, string resultJson)
    {
        var idJson = id is string s ? "\"" + s + "\"" : Json.Stringify(id);
        StubServer.Respond(ctx, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":" + resultJson + "}");
    }

    [Fact]
    public async Task SkillsBecomeA2aTools_SuccessRoundTrip_EnvHeader()
    {
        using var stub = StartStub();
        Environment.SetEnvironmentVariable("TN_A2A_TOKEN", "a2a-secret");
        try
        {
            await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
            {
                Builtins = false,
                Agents = new List<Agent>
                {
                    new()
                    {
                        Card = stub.CardUrl,
                        PollEvery = 5,
                        Headers = new Dictionary<string, string> { ["Authorization"] = "Bearer ${TN_A2A_TOKEN}" },
                    },
                },
            });

            // skills → tools, source:"a2a"
            var review = tk.Get("reviewer_review");
            Assert.NotNull(review);
            Assert.Equal("a2a", review!.Source);
            Assert.NotNull(tk.Get("reviewer_plan"));

            // in the provider schema
            var names = tk.ToOpenAI().Select(f => ((IDictionary<string, object?>)f["function"]!)["name"]).ToList();
            Assert.Contains("reviewer_review", names);
            Assert.Contains("reviewer_plan", names);

            // execute → SendMessage once → poll GetTask → completed → "REVIEWED"
            var r = await tk.ExecuteAsync("reviewer_review", new Dictionary<string, object?> { ["task"] = "please review" });
            Assert.False(r.IsError);
            Assert.Equal("REVIEWED", r.Output);
            Assert.Equal(1, stub.Seen.SendMessageCalls);
            Assert.True(stub.Seen.GetTaskCalls >= 2, "polled GetTask until completed");
            Assert.Equal("Bearer a2a-secret", stub.Seen.Auth);
            Assert.Equal("completed", r.Metadata!["state"]);
            Assert.Equal("reviewer", r.Metadata!["agent"]);
            Assert.False(string.IsNullOrEmpty(r.Metadata!["taskId"]?.ToString()));
        }
        finally
        {
            Environment.SetEnvironmentVariable("TN_A2A_TOKEN", null);
        }
    }

    [Fact]
    public async Task FailedTaskMapsToIsError()
    {
        using var stub = StartStub();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            Agents = new List<Agent> { new() { Card = stub.CardUrl, PollEvery = 5 } },
        });

        var r = await tk.ExecuteAsync("reviewer_fail", new Dictionary<string, object?> { ["task"] = "please fail" });
        Assert.True(r.IsError);
        Assert.Contains("failed", r.Output);
        Assert.Contains("boom", r.Output);
        Assert.Equal("failed", r.Metadata!["state"]);
    }

    [Fact]
    public async Task CancelMidPollStopsFurtherGetTask()
    {
        using var stub = StartStub();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            Agents = new List<Agent> { new() { Card = stub.CardUrl, PollEvery = 10 } },
        });

        using var cts = new CancellationTokenSource();
        var ctx = new ToolContext(cancellationToken: cts.Token);
        var p = tk.ExecuteAsync("reviewer_review", new Dictionary<string, object?> { ["task"] = "slow one" }, ctx);
        cts.CancelAfter(35);
        var r = await p;

        Assert.True(r.IsError);
        Assert.Equal("canceled", r.Metadata!["state"]);

        // A GetTask already on the wire when cancel fired is abandoned by the client but
        // still counted asynchronously by the stub's listener thread — let that straggler
        // land first, THEN prove no *new* poll starts after cancellation (the real intent).
        await Task.Delay(50);
        var afterAbort = stub.Seen.GetTaskCalls;
        await Task.Delay(80);
        Assert.Equal(afterAbort, stub.Seen.GetTaskCalls);
    }

    [Fact]
    public async Task ConfigAgentsBlock_DisabledSkipped_EnabledResolves()
    {
        using var stub = StartStub();

        await using var off = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            McpConfig = new Dictionary<string, object?>
            {
                ["agents"] = new Dictionary<string, object?>
                {
                    ["rev"] = new Dictionary<string, object?> { ["card"] = stub.CardUrl, ["disabled"] = true },
                },
            },
        });
        Assert.Null(off.Get("reviewer_review"));

        await using var on = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            McpConfig = new Dictionary<string, object?>
            {
                ["agents"] = new Dictionary<string, object?>
                {
                    ["rev"] = new Dictionary<string, object?> { ["card"] = stub.CardUrl, ["pollEvery"] = 5 },
                },
            },
        });
        Assert.NotNull(on.Get("reviewer_review"));
    }

    [Fact]
    public async Task AddAgentRegistersToolsAtRuntime()
    {
        using var stub = StartStub();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });

        Assert.Null(tk.Get("reviewer_review"));
        await tk.AddAgentAsync(new Agent { Card = stub.CardUrl, PollEvery = 5 });
        Assert.NotNull(tk.Get("reviewer_review"));
        Assert.NotNull(tk.Get("reviewer_plan"));

        // bare card URL form also works (dedupe keeps first — no throw).
        await tk.AddAgentAsync(stub.CardUrl);
    }

    [Fact]
    public void ParseAgentsConfigHonorsEnabledDisabledPrecedence()
    {
        var parsed = A2A.ParseAgentsConfig(new Dictionary<string, object?>
        {
            ["a"] = new Dictionary<string, object?> { ["card"] = "http://x/1" },
            ["b"] = new Dictionary<string, object?> { ["card"] = "http://x/2", ["disabled"] = true },
            ["c"] = new Dictionary<string, object?> { ["card"] = "http://x/3", ["enabled"] = false },
            ["d"] = new Dictionary<string, object?> { ["card"] = "http://x/4", ["enabled"] = true, ["disabled"] = true },
        });
        Assert.Single(parsed);
        Assert.Equal("http://x/1", parsed[0].Card);
    }

    [Fact]
    public void ParseMcpConfigStripsReservedAgentsAndBuiltins()
    {
        var config = McpSource.ParseConfig(new Dictionary<string, object?>
        {
            ["builtins"] = false,
            ["agents"] = new Dictionary<string, object?> { ["rev"] = new Dictionary<string, object?> { ["card"] = "http://x/1" } },
            ["a2a"] = new Dictionary<string, object?> { ["name"] = "me" },
            ["realServer"] = new Dictionary<string, object?> { ["url"] = "http://x/mcp" },
        });
        Assert.False(config.ContainsKey("builtins"));
        Assert.False(config.ContainsKey("agents"));
        Assert.False(config.ContainsKey("a2a"));
        Assert.True(config.ContainsKey("realServer"));
    }
}
