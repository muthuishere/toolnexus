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
}
