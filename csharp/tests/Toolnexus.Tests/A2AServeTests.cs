using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// A2A (inbound / serve) tests — hermetic: a mock LLM (HttpListener) + a real
/// localhost served toolkit on an ephemeral port. No external network, no live
/// LLM. Mirrors js/test/serve.test.ts.
/// </summary>
public class A2AServeTests
{
    private static readonly HttpClient Http = new();

    /// <summary>Mock OpenAI-style LLM: one canned assistant completion (no tool calls).</summary>
    private static StubServer MockLlm(string content, int status = 200)
        => new(ctx =>
        {
            if (status != 200)
            {
                StubServer.Respond(ctx, status, "boom");
                return;
            }
            StubServer.Respond(ctx, 200,
                "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]," +
                "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
        });

    private static LlmClient ClientFor(StubServer llm)
        => LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl,
            Style = "openai",
            Model = "mock",
            ApiKey = "k",
        });

    private static Task<Toolkit> ToolkitWithSkills()
        => Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            SkillsDir = new List<string> { TestFixtures.SkillsDir() },
        });

    // ---- the key round-trip: outbound A2A calls the inbound served toolkit ----

    [Fact]
    public async Task InboundServedToolkit_RoundTripsThroughOutboundA2A()
    {
        using var llm = MockLlm("TRANSCRIBED");
        await using var tk = await ToolkitWithSkills();

        var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
        {
            Client = ClientFor(llm),
            A2A = new A2AConfig { Name = "video-desk", Skills = new List<string> { "hello-world" } },
        });
        try
        {
            var tools = await A2A.AgentTools(new Agent
            {
                Card = handle.Url + "/.well-known/agent-card.json",
                PollEvery = 25,
            });
            var tool = tools.Single(t => t.Name == "video-desk_hello-world");

            var r = await tool.ExecuteAsync(new Dictionary<string, object?> { ["task"] = "do it" });
            Assert.False(r.IsError);
            Assert.Equal("TRANSCRIBED", r.Output);
        }
        finally
        {
            await handle.StopAsync();
        }
    }

    // ---- card lists skills (not raw tools); streaming false ----

    [Fact]
    public async Task Card_ListsSkillsNotTools_StreamingFalse()
    {
        using var llm = MockLlm("ok");
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = true, // builtin tools present but must NOT appear on the card
            SkillsDir = new List<string> { TestFixtures.SkillsDir() },
        });

        var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
        {
            Client = ClientFor(llm),
            A2A = new A2AConfig(),
        });
        try
        {
            var body = await Http.GetStringAsync(handle.Url + "/.well-known/agent-card.json");
            var card = Json.ParseObjectLoose(body);

            Assert.Equal("toolnexus-agent", card["name"]);
            Assert.Equal("0.3.0", card["protocolVersion"]);
            var caps = (IDictionary<string, object?>)card["capabilities"]!;
            Assert.Equal(false, caps["streaming"]);
            Assert.Equal(false, caps["pushNotifications"]);
            Assert.Equal(handle.Url + "/", card["url"]);

            var skills = (IEnumerable<object?>)card["skills"]!;
            var names = skills.Select(s => ((IDictionary<string, object?>)s!)["name"]?.ToString()).ToList();
            Assert.Contains("hello-world", names);
            Assert.DoesNotContain("bash", names);
            Assert.DoesNotContain("read", names);
            // ids equal names, never raw tool names
            Assert.All(skills, s =>
            {
                var d = (IDictionary<string, object?>)s!;
                Assert.Equal(d["name"], d["id"]);
            });
        }
        finally
        {
            await handle.StopAsync();
        }
    }

    // ---- a2a absent → 404 ----

    [Fact]
    public async Task NoA2aProfile_Returns404()
    {
        using var llm = MockLlm("ok");
        await using var tk = await ToolkitWithSkills();

        var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
        {
            Client = ClientFor(llm),
            // no A2A profile, no top-level a2a block
        });
        try
        {
            var res = await Http.GetAsync(handle.Url + "/.well-known/agent-card.json");
            Assert.Equal(HttpStatusCode.NotFound, res.StatusCode);

            var post = await Http.PostAsync(handle.Url + "/",
                new StringContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GetTask\",\"params\":{\"id\":\"x\"}}",
                    Encoding.UTF8, "application/json"));
            Assert.Equal(HttpStatusCode.NotFound, post.StatusCode);
        }
        finally
        {
            await handle.StopAsync();
        }
    }

    // ---- fulfilment error → failed task, server survives ----

    [Fact]
    public async Task FulfilmentError_ProducesFailedTask_ServerSurvives()
    {
        using var llm = MockLlm("", status: 500); // every LLM call 500s → client.Run throws
        await using var tk = await ToolkitWithSkills();

        var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
        {
            Client = LlmClient.Create(new LlmClient.Options
            {
                BaseUrl = llm.BaseUrl,
                Style = "openai",
                Model = "mock",
                ApiKey = "k",
                Retries = 0,
            }),
            A2A = new A2AConfig(),
        });
        try
        {
            var taskId = await SendMessage(handle.Url, "will fail");
            var task = await PollUntilTerminal(handle.Url, taskId);
            var status = (IDictionary<string, object?>)task["status"]!;
            Assert.Equal("failed", status["state"]);

            // server still serves: the card is still reachable afterwards
            var card = await Http.GetAsync(handle.Url + "/.well-known/agent-card.json");
            Assert.Equal(HttpStatusCode.OK, card.StatusCode);

            // unknown task id → JSON-RPC error -32001
            var missing = await Rpc(handle.Url, "GetTask", "{\"id\":\"nope\"}");
            var err = (IDictionary<string, object?>)missing["error"]!;
            Assert.Equal(-32001L, err["code"]);
        }
        finally
        {
            await handle.StopAsync();
        }
    }

    // ---- unknown method / parse error ----

    [Fact]
    public async Task UnknownMethodAndParseErrorMapToJsonRpcCodes()
    {
        using var llm = MockLlm("ok");
        await using var tk = await ToolkitWithSkills();
        var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
        {
            Client = ClientFor(llm),
            A2A = new A2AConfig(),
        });
        try
        {
            var unknown = await Rpc(handle.Url, "Nope", "{}");
            Assert.Equal(-32601L, ((IDictionary<string, object?>)unknown["error"]!)["code"]);

            var post = await Http.PostAsync(handle.Url + "/",
                new StringContent("not json", Encoding.UTF8, "application/json"));
            var parsed = Json.ParseObjectLoose(await post.Content.ReadAsStringAsync());
            Assert.Equal(-32700L, ((IDictionary<string, object?>)parsed["error"]!)["code"]);
        }
        finally
        {
            await handle.StopAsync();
        }
    }

    // ---- custom ITaskStore is used ----

    private sealed class RecordingStore : ITaskStore
    {
        public readonly ConcurrentDictionary<string, A2ATask> Tasks = new();
        public int Saves;
        public Task<A2ATask?> GetAsync(string id)
            => Task.FromResult(Tasks.TryGetValue(id, out var t) ? t : null);
        public Task SaveAsync(A2ATask task)
        {
            Interlocked.Increment(ref Saves);
            Tasks[task.Id ?? ""] = task;
            return Task.CompletedTask;
        }
    }

    [Fact]
    public async Task CustomTaskStore_IsUsed()
    {
        using var llm = MockLlm("DONE");
        await using var tk = await ToolkitWithSkills();
        var store = new RecordingStore();

        var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
        {
            Client = ClientFor(llm),
            A2A = new A2AConfig { Store = store },
        });
        try
        {
            var taskId = await SendMessage(handle.Url, "hi");
            var task = await PollUntilTerminal(handle.Url, taskId);
            Assert.Equal("completed", ((IDictionary<string, object?>)task["status"]!)["state"]);
            Assert.True(store.Saves >= 3, "submitted + working + completed all went through the custom store");
            Assert.True(store.Tasks.ContainsKey(taskId));
        }
        finally
        {
            await handle.StopAsync();
        }
    }

    // ---- FileTaskStore round-trips in a temp dir ----

    [Fact]
    public async Task FileTaskStore_RoundTripsInTempDir()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tn-a2a-" + Guid.NewGuid().ToString("N"));
        try
        {
            using var llm = MockLlm("FILED");
            await using var tk = await ToolkitWithSkills();
            var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
            {
                Client = ClientFor(llm),
                A2A = new A2AConfig { Store = "file:" + dir },
            });
            try
            {
                var taskId = await SendMessage(handle.Url, "persist me");
                var task = await PollUntilTerminal(handle.Url, taskId);
                Assert.Equal("completed", ((IDictionary<string, object?>)task["status"]!)["state"]);

                // The task file exists on disk and a fresh FileTaskStore reads it back.
                Assert.True(File.Exists(Path.Combine(dir, taskId + ".json")));
                var reopened = new FileTaskStore(dir);
                var loaded = await reopened.GetAsync(taskId);
                Assert.NotNull(loaded);
                Assert.Equal("completed", loaded!.Status?.State);
                Assert.Equal("FILED", loaded.Artifacts?[0].Parts?[0].Text);
            }
            finally
            {
                await handle.StopAsync();
            }
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch { }
        }
    }

    // ---- helpers ----

    private static async Task<IDictionary<string, object?>> Rpc(string baseUrl, string method, string paramsJson)
    {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        var res = await Http.PostAsync(baseUrl + "/", new StringContent(body, Encoding.UTF8, "application/json"));
        return Json.ParseObjectLoose(await res.Content.ReadAsStringAsync());
    }

    private static async Task<string> SendMessage(string baseUrl, string text)
    {
        var rpc = await Rpc(baseUrl, "SendMessage",
            "{\"message\":{\"parts\":[{\"kind\":\"text\",\"text\":\"" + text + "\"}]}}");
        var result = (IDictionary<string, object?>)rpc["result"]!;
        return result["id"]!.ToString()!;
    }

    private static async Task<IDictionary<string, object?>> PollUntilTerminal(string baseUrl, string taskId)
    {
        for (var i = 0; i < 100; i++)
        {
            var rpc = await Rpc(baseUrl, "GetTask", "{\"id\":\"" + taskId + "\"}");
            var result = (IDictionary<string, object?>)rpc["result"]!;
            var state = ((IDictionary<string, object?>)result["status"]!)["state"]?.ToString();
            if (state is "completed" or "failed" or "canceled") return result;
            await Task.Delay(20);
        }
        throw new Exception("task did not reach a terminal state");
    }
}
