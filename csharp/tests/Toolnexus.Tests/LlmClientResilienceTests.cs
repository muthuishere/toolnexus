namespace Toolnexus.Tests;

public class LlmClientResilienceTests
{
    [Fact]
    public async Task RetriesOn503ThenSucceeds()
    {
        var hits = 0;
        using var server = new StubServer(ctx =>
        {
            var n = Interlocked.Increment(ref hits);
            if (n < 3)
            {
                StubServer.Respond(ctx, 503, "busy");
                return;
            }
            StubServer.Respond(ctx, 200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
        });

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = server.BaseUrl,
            Style = "openai",
            Model = "x",
            ApiKey = "k",
            Retries = 3,
            RetryBaseMs = 5,
        });

        var res = await client.RunAsync("hi", tk);
        Assert.Equal("ok", res.Text);
        Assert.Equal(3, hits);
    }

    [Fact]
    public async Task RunLevelTimeoutAborts()
    {
        using var server = new StubServer(ctx =>
        {
            Thread.Sleep(800); // far longer than the run deadline
            StubServer.Respond(ctx, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
        });

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = server.BaseUrl,
            Style = "openai",
            Model = "x",
            ApiKey = "k",
            Retries = 0,
            TimeoutMs = 60,
        });

        var ex = await Assert.ThrowsAnyAsync<Exception>(() => client.RunAsync("hi", tk));
        Assert.Matches("(?i).*(timeout|abort).*", ex.Message);
    }

    // ---- §8 OnError resilience classifier ----

    [Fact]
    public async Task OnErrorFailOn429SurfacesImmediately()
    {
        var hits = 0;
        using var server = new StubServer(ctx =>
        {
            Interlocked.Increment(ref hits);
            StubServer.Respond(ctx, 429, "rate limited");
        });

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        var seen = new List<LlmClient.ErrorInfo>();
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = server.BaseUrl,
            Style = "openai",
            Model = "x",
            ApiKey = "k",
            Retries = 3,
            RetryBaseMs = 5,
            OnError = info => { seen.Add(info); return LlmClient.Tier.Fail; },
        });

        // 429 is retryable by default, but OnError => Fail surfaces it now (no retries).
        await Assert.ThrowsAnyAsync<Exception>(() => client.RunAsync("hi", tk));
        Assert.Equal(1, hits);
        Assert.Single(seen);
        Assert.Equal(429, seen[0].Status);
        Assert.Equal(0, seen[0].Attempt);
        Assert.True(seen[0].Retryable);
    }

    [Fact]
    public async Task OnErrorRetryOn400RetriesToBudget()
    {
        var hits = 0;
        using var server = new StubServer(ctx =>
        {
            Interlocked.Increment(ref hits);
            StubServer.Respond(ctx, 400, "bad request");
        });

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        const int retries = 2;
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = server.BaseUrl,
            Style = "openai",
            Model = "x",
            ApiKey = "k",
            Retries = retries,
            RetryBaseMs = 5,
            // 400 is NOT retryable by default, but OnError => Retry forces retries (bounded by budget).
            OnError = _ => LlmClient.Tier.Retry,
        });

        await Assert.ThrowsAnyAsync<Exception>(() => client.RunAsync("hi", tk));
        Assert.Equal(retries + 1, hits); // 1 initial + Retries
    }

    [Fact]
    public async Task DefaultNoOnError429ThenSucceeds()
    {
        var hits = 0;
        using var server = new StubServer(ctx =>
        {
            var n = Interlocked.Increment(ref hits);
            if (n < 2)
            {
                StubServer.Respond(ctx, 429, "slow down");
                return;
            }
            StubServer.Respond(ctx, 200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
        });

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = server.BaseUrl,
            Style = "openai",
            Model = "x",
            ApiKey = "k",
            Retries = 3,
            RetryBaseMs = 5,
        });

        var res = await client.RunAsync("hi", tk);
        Assert.Equal("ok", res.Text);
        Assert.Equal(2, hits);
    }

    [Fact]
    public async Task DefaultNoOnError400Fails()
    {
        var hits = 0;
        using var server = new StubServer(ctx =>
        {
            Interlocked.Increment(ref hits);
            StubServer.Respond(ctx, 400, "bad request");
        });

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = server.BaseUrl,
            Style = "openai",
            Model = "x",
            ApiKey = "k",
            Retries = 3,
            RetryBaseMs = 5,
        });

        // 400 not retryable by default => surfaced on first attempt.
        await Assert.ThrowsAnyAsync<Exception>(() => client.RunAsync("hi", tk));
        Assert.Equal(1, hits);
    }
}
