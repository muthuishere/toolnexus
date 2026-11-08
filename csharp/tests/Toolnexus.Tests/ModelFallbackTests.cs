using System.Text.RegularExpressions;

namespace Toolnexus.Tests;

/// <summary>§8 Resilience <c>FallbackModels</c> (issue #190): advance on an exhausted 503/529 only.</summary>
[Collection("env")]
public class ModelFallbackTests
{
    private const string Ok = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    private static (StubServer, List<string>) Server(Func<string, (int, string)> byModel)
    {
        var models = new List<string>();
        var server = new StubServer(ctx =>
        {
            var body = new StreamReader(ctx.Request.InputStream).ReadToEnd();
            var m = Regex.Match(body, "\"model\"\\s*:\\s*\"([^\"]*)\"").Groups[1].Value;
            lock (models) models.Add(m);
            var (st, text) = byModel(m);
            StubServer.Respond(ctx, st, text);
        });
        return (server, models);
    }

    private static LlmClient Client(string url, IReadOnlyList<string>? fb, Func<LlmClient.ErrorInfo, LlmClient.Tier>? onError = null,
        LlmClient.Hooks? hooks = null) => LlmClient.Create(new LlmClient.Options
    {
        BaseUrl = url, Style = "openai", Model = "m1", ApiKey = "k", Retries = 0, RetryBaseMs = 1,
        FallbackModels = fb, OnError = onError, Hooks = hooks,
    });

    [Fact]
    public async Task Overload529AdvancesToNextModel()
    {
        Environment.SetEnvironmentVariable("TOOLNEXUS_DISABLE_MODEL_FALLBACK", null);
        var (server, models) = Server(m => m == "m1" ? (529, "overloaded") : (200, Ok));
        using var _ = server;
        string? after = null;
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        var res = await Client(server.BaseUrl, new[] { "", "m1", "m2" },
            hooks: new LlmClient.Hooks { AfterLLM = e => after = e.Model }).RunAsync("hi", tk);
        Assert.Equal("ok", res.Text);
        Assert.Equal("m2", res.Model);
        Assert.Equal("m2", after);
        Assert.Equal(new[] { "m1", "m2" }, models);
    }

    [Fact]
    public async Task RateLimit429NeverSwitches()
    {
        var (server, models) = Server(m => m == "m1" ? (429, "slow down") : (200, Ok));
        using var _ = server;
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        await Assert.ThrowsAnyAsync<Exception>(() => Client(server.BaseUrl, new[] { "m2" }).RunAsync("hi", tk));
        Assert.Equal(new[] { "m1" }, models);
    }

    [Fact]
    public async Task OnErrorFailOn503DoesNotSwitch()
    {
        var (server, models) = Server(m => m == "m1" ? (503, "busy") : (200, Ok));
        using var _ = server;
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        await Assert.ThrowsAnyAsync<Exception>(() =>
            Client(server.BaseUrl, new[] { "m2" }, _ => LlmClient.Tier.Fail).RunAsync("hi", tk));
        Assert.Equal(new[] { "m1" }, models);
    }

    [Theory]
    [InlineData("1")]
    [InlineData("TRUE")]
    public async Task KillSwitchDisablesChain(string v)
    {
        var (server, models) = Server(m => m == "m1" ? (503, "busy") : (200, Ok));
        using var _ = server;
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        Environment.SetEnvironmentVariable("TOOLNEXUS_DISABLE_MODEL_FALLBACK", v);
        try
        {
            await Assert.ThrowsAnyAsync<Exception>(() => Client(server.BaseUrl, new[] { "m2" }).RunAsync("hi", tk));
        }
        finally { Environment.SetEnvironmentVariable("TOOLNEXUS_DISABLE_MODEL_FALLBACK", null); }
        Assert.Equal(new[] { "m1" }, models);
    }

    [Fact]
    public async Task StreamEmitsFallbackBeforeText()
    {
        const string sse = "data: {\"choices\":[{\"delta\":{\"content\":\"hey\"}}]}\n\ndata: [DONE]\n\n";
        var (server, models) = Server(m => m == "m1" ? (529, "overloaded") : (200, sse));
        using var _ = server;
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        var events = new List<LlmClient.StreamEvent>();
        var res = await Client(server.BaseUrl, new[] { "m2" }).StreamAsync("hi", tk, e => events.Add(e));
        Assert.Equal("m2", res.Model);
        var fi = events.FindIndex(e => e.Type == LlmClient.StreamKind.Fallback);
        var ti = events.FindIndex(e => e.Type == LlmClient.StreamKind.Text);
        Assert.True(fi >= 0 && fi < ti);
        Assert.Equal("m1", events[fi].From);
        Assert.Equal("m2", events[fi].To);
        Assert.Equal(new[] { "m1", "m2" }, models);
    }

    [Fact]
    public async Task AbsentOptionUnchanged()
    {
        var (server, models) = Server(m => m == "m1" ? (529, "overloaded") : (200, Ok));
        using var _ = server;
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        await Assert.ThrowsAnyAsync<Exception>(() => Client(server.BaseUrl, null).RunAsync("hi", tk));
        Assert.Equal(new[] { "m1" }, models);
    }
}
