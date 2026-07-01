using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// Conversation memory (SPEC §8) — hermetic tests for <c>AskAsync</c> + the
/// <see cref="IConversationStore"/>, and the A2A serve path that keys a peer's
/// turns by <c>contextId</c>. Mirrors js/test/unit.test.ts (ask-remembers-by-id,
/// custom store get/save, a2a serve contextId memory). No network, no live LLM.
/// </summary>
public class ConversationMemoryTests
{
    private static readonly HttpClient Http = new();

    /// <summary>Mock OpenAI-style LLM whose reply is the number of messages it received — proves
    /// that history is loaded (more messages ⇒ higher count).</summary>
    private static StubServer CountingLlm()
        => new(ctx =>
        {
            string body;
            using (var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8))
                body = reader.ReadToEnd();
            var req = Json.ParseObjectLoose(body);
            var count = ((req["messages"] as IEnumerable<object?>) ?? Enumerable.Empty<object?>()).Count();
            StubServer.Respond(ctx, 200,
                "{\"choices\":[{\"message\":{\"content\":\"" + count + "\"}}]," +
                "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
        });

    /// <summary>Mock OpenAI-style LLM: one canned assistant completion (no tool calls).</summary>
    private static StubServer FixedLlm(string content)
        => new(ctx => StubServer.Respond(ctx, 200,
            "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]," +
            "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));

    private static LlmClient ClientFor(StubServer llm, IConversationStore? store = null)
        => LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl,
            Style = "openai",
            Model = "x",
            ApiKey = "k",
            Store = store,
        });

    // ---- ask remembers by id via the conversation store ----

    [Fact]
    public async Task Ask_RemembersById_ViaConversationStore()
    {
        using var llm = CountingLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var client = ClientFor(llm);

        var solo = await client.AskAsync("hi", tk);
        Assert.Equal("1", solo.Text); // no id ⇒ stateless one-shot (just the user turn)

        var a = await client.AskAsync("first", tk, "c1");
        Assert.Equal("1", a.Text); // first turn: 1 message

        var b = await client.AskAsync("second", tk, "c1");
        Assert.Equal("3", b.Text); // same id remembers: user+assistant+user = 3

        var c = await client.AskAsync("other", tk, "c2");
        Assert.Equal("1", c.Text); // a different id is an independent conversation
    }

    // ---- custom IConversationStore provider is used (get then save) ----

    private sealed class RecordingConversationStore : IConversationStore
    {
        public readonly List<string> Calls = new();
        public readonly ConcurrentDictionary<string, List<object?>> Backing = new();

        public Task<List<object?>?> GetAsync(string id)
        {
            lock (Calls) Calls.Add("get:" + id);
            return Task.FromResult(Backing.TryGetValue(id, out var m) ? m : null);
        }

        public Task SaveAsync(string id, List<object?> messages)
        {
            lock (Calls) Calls.Add("save:" + id);
            Backing[id] = messages;
            return Task.CompletedTask;
        }
    }

    [Fact]
    public async Task CustomConversationStore_IsUsed()
    {
        using var llm = FixedLlm("ok");
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var store = new RecordingConversationStore();
        var client = ClientFor(llm, store);

        await client.AskAsync("hi", tk, "u1");

        Assert.Equal(new[] { "get:u1", "save:u1" }, store.Calls); // custom store: get then save
        Assert.True(store.Backing.ContainsKey("u1")); // custom store persisted the transcript
    }

    // ---- a2a serve: remembers a peer's turns by contextId (ask + store) ----

    [Fact]
    public async Task A2aServe_RemembersByContextId()
    {
        using var llm = CountingLlm();
        // no skills ⇒ no system-prompt message, so counts are exactly the turns
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var client = ClientFor(llm);

        var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions
        {
            Client = client,
            A2A = new A2AConfig { Name = "mem-desk" },
        });
        try
        {
            var t1 = await SendAndWait(handle.Url, "first", "ctxA");
            Assert.Equal("1", ArtifactText(t1)); // first served turn: 1 message

            var t2 = await SendAndWait(handle.Url, "second", "ctxA");
            Assert.Equal("3", ArtifactText(t2)); // same contextId remembers: user+assistant+user = 3

            var t3 = await SendAndWait(handle.Url, "other", "ctxB");
            Assert.Equal("1", ArtifactText(t3)); // a different contextId is independent
        }
        finally
        {
            await handle.StopAsync();
        }
    }

    // ---- helpers ----

    private static async Task<IDictionary<string, object?>> Rpc(string baseUrl, string method, string paramsJson)
    {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        var res = await Http.PostAsync(baseUrl + "/", new StringContent(body, Encoding.UTF8, "application/json"));
        return Json.ParseObjectLoose(await res.Content.ReadAsStringAsync());
    }

    private static async Task<IDictionary<string, object?>> SendAndWait(string baseUrl, string text, string contextId)
    {
        var rpc = await Rpc(baseUrl, "SendMessage",
            "{\"message\":{\"role\":\"user\",\"contextId\":\"" + contextId + "\"," +
            "\"parts\":[{\"kind\":\"text\",\"text\":\"" + text + "\"}]}}");
        var taskId = ((IDictionary<string, object?>)rpc["result"]!)["id"]!.ToString()!;
        for (var i = 0; i < 100; i++)
        {
            var g = await Rpc(baseUrl, "GetTask", "{\"id\":\"" + taskId + "\"}");
            var result = (IDictionary<string, object?>)g["result"]!;
            var state = ((IDictionary<string, object?>)result["status"]!)["state"]?.ToString();
            if (state is "completed" or "failed") return result;
            await Task.Delay(10);
        }
        throw new Exception("task did not reach a terminal state");
    }

    private static string ArtifactText(IDictionary<string, object?> task)
    {
        var artifacts = (IEnumerable<object?>)task["artifacts"]!;
        var first = (IDictionary<string, object?>)artifacts.First()!;
        var parts = (IEnumerable<object?>)first["parts"]!;
        return ((IDictionary<string, object?>)parts.First()!)["text"]?.ToString() ?? "";
    }
}
