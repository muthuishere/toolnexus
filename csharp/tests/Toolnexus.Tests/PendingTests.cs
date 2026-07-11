using System.Net;
using System.Text;
using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>
/// §10 Suspension — Pending / WaitFor. Mirrors <c>js/examples/pending.ts</c>. Drives the REAL client
/// loop against a tiny stubbed "LLM" (a localhost <see cref="StubServer"/> — no network, no key) with a
/// <c>get_balance</c> tool that returns <see cref="ToolResult.AuthRequired"/> until authed. The
/// <c>authorization</c> kind is the OAuth2/OIDC login case; the kernel stays OIDC-agnostic — all login
/// behavior lives in the host's <c>WaitFor</c>. Shows both postures:
///   A) WaitFor provided → the engine resolves + retries transparently; the model gets the balance.
///   B) no WaitFor       → run halts with { Status = "pending", Pending = request } to resume later.
/// </summary>
public class PendingTests
{
    // ---- Wire-key parity: Request/Answer serialize to the pinned keys, byte-identical across ports ----

    [Fact]
    public void Request_And_Answer_SerializeToPinnedWireKeys()
    {
        var req = new Request { Id = "r1", Kind = "authorization", Prompt = "Log in", Url = "https://x/login" };
        Assert.Equal(
            "{\"id\":\"r1\",\"kind\":\"authorization\",\"prompt\":\"Log in\",\"url\":\"https://x/login\"}",
            JsonSerializer.Serialize(req));

        var full = new Request
        {
            Id = "r2", Kind = "input", Prompt = "Pick", Url = "u",
            Data = new Dictionary<string, object?> { ["k"] = "v" }, ExpiresAt = "2026-01-01T00:00:00Z",
        };
        // camelCase expiresAt is preserved; unset optionals are omitted.
        Assert.Equal(
            "{\"id\":\"r2\",\"kind\":\"input\",\"prompt\":\"Pick\",\"url\":\"u\",\"data\":{\"k\":\"v\"},\"expiresAt\":\"2026-01-01T00:00:00Z\"}",
            JsonSerializer.Serialize(full));

        var ans = new Answer { Id = "r1", Ok = true };
        Assert.Equal("{\"id\":\"r1\",\"ok\":true}", JsonSerializer.Serialize(ans));
    }

    // ---- A) WaitFor provided → resolve + retry, transparently → Status "done", balance in output ----

    [Fact]
    public async Task WaitFor_ResolvesAndRetries_RunCompletes()
    {
        var authed = false; // flipped out-of-band by WaitFor (the "world changed")
        using var llm = StubLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(BalanceTool(() => authed));

        var seen = new List<string>();
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl, Style = "openai", Model = "stub", ApiKey = "k",
            WaitFor = request =>
            {
                seen.Add(request.Url!);   // in real life: open a browser / text the link / forward over A2A
                authed = true;            // simulate the human completing OIDC login out-of-band
                return Task.FromResult(new Answer { Id = request.Id, Ok = true });
            },
        });

        var a = await client.RunAsync("what is my balance?", tk);

        Assert.Equal("done", a.Status);
        Assert.Contains("67,417", a.ToolCalls[0].Output);   // the retried tool produced the balance
        Assert.Contains("example.com/login", seen[0]);       // the link was delivered to the host
        Assert.Contains("67,417", a.Text);                   // model's final answer carries it through
    }

    // ---- B) no WaitFor → durable halt: Status "pending", Pending is the authorization request ----

    [Fact]
    public async Task NoWaitFor_Halts_WithPendingRequest()
    {
        var authed = false;
        using var llm = StubLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(BalanceTool(() => authed));

        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl, Style = "openai", Model = "stub", ApiKey = "k",
        });

        var b = await client.RunAsync("what is my balance?", tk);

        Assert.Equal("pending", b.Status);                       // did not hang, did not error out
        Assert.NotNull(b.Pending);
        Assert.Equal("authorization", b.Pending!.Kind);
        Assert.Contains("example.com/login", b.Pending.Url!);
    }

    // ---- B') no WaitFor → a `question` builtin suspension halts the run the same way ----

    [Fact]
    public async Task NoWaitFor_QuestionBuiltin_Halts_WithQuestionRequest()
    {
        using var llm = StubQuestionLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = true }); // `question` exists

        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl, Style = "openai", Model = "stub", ApiKey = "k", // no WaitFor
        });

        var res = await client.RunAsync("ask me", tk);

        Assert.Equal("pending", res.Status);          // no WaitFor ⇒ halts pending, does not loop forever
        Assert.NotNull(res.Pending);
        Assert.Equal("question", res.Pending!.Kind);
        Assert.Equal("Pick a color? (options: red, green)", res.Pending.Prompt); // byte-exact
    }

    // ---- helpers ----

    /// <summary>A fake LLM whose first turn calls the `question` builtin (never reached again — the run halts).</summary>
    private static StubServer StubQuestionLlm()
        => new(ctx =>
        {
            ReadBody(ctx); // drain
            const string args = "{\\\"questions\\\":[{\\\"question\\\":\\\"Pick a color?\\\",\\\"options\\\":[\\\"red\\\",\\\"green\\\"]}]}";
            var json = "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[" +
                       "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"question\",\"arguments\":\"" + args + "\"}}]}}]," +
                       "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            StubServer.Respond(ctx, 200, json);
        });

    /// <summary>A tool that needs authorization the first time, then returns the balance.</summary>
    private static NativeTool BalanceTool(Func<bool> authed)
        => NativeTool.Of("get_balance", "Return the account balance. Requires login first.", null,
            (IDictionary<string, object?> _, ToolContext? ctx) =>
                authed()
                    ? (object)ToolResult.Ok($"balance: ₹67,417 (resolved via answer {ctx?.Answer?.Id})")
                    : ToolResult.AuthRequired("https://example.com/login?token=abc", "Log in to view your balance"));

    /// <summary>
    /// A tiny fake OpenAI endpoint: turn 1 calls get_balance; turn 2 (once it has seen a tool result)
    /// returns a final answer. Lets us exercise the real client loop with no network.
    /// </summary>
    private static StubServer StubLlm()
        => new(ctx =>
        {
            var body = ReadBody(ctx);
            var req = Json.ParseObjectLoose(body);
            var messages = (req.Get("messages") as IEnumerable<object?>) ?? Enumerable.Empty<object?>();
            var sawToolResult = messages
                .OfType<IDictionary<string, object?>>()
                .Any(m => (m.Get("role") as string) == "tool");
            var json = sawToolResult
                ? "{\"choices\":[{\"message\":{\"content\":\"Done — your balance is ₹67,417.\"}}]," +
                  "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"
                : "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[" +
                  "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"get_balance\",\"arguments\":\"{}\"}}]}}]," +
                  "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            StubServer.Respond(ctx, 200, json);
        });

    private static string ReadBody(HttpListenerContext ctx)
    {
        using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
        return reader.ReadToEnd();
    }
}
