namespace Toolnexus.Tests;

public class HttpToolTests
{
    private static readonly string EnvName =
        Environment.GetEnvironmentVariable("PATH") != null ? "PATH" : "HOME";
    private static readonly string? EnvValue = Environment.GetEnvironmentVariable(EnvName);

    private static Dictionary<string, object?> Schema(bool withQ)
    {
        var props = new Dictionary<string, object?>
        {
            ["id"] = new Dictionary<string, object?> { ["type"] = "number" },
        };
        if (withQ) props["q"] = new Dictionary<string, object?> { ["type"] = "string" };
        return new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = props,
            ["required"] = new List<object?> { "id" },
            ["additionalProperties"] = false,
        };
    }

    [Fact]
    public async Task PlaceholderEnvHeaderAndGetQuery()
    {
        Assert.False(string.IsNullOrEmpty(EnvValue), "need a real env var to prove header expansion");

        string? seenUrl = null;
        string? seenAuth = null;
        using var server = new StubServer(ctx =>
        {
            seenUrl = ctx.Request.Url!.PathAndQuery;
            seenAuth = ctx.Request.Headers["Authorization"];
            if (seenUrl.StartsWith("/posts/5"))
                StubServer.Respond(ctx, 200, "{\"id\":5,\"title\":\"hello\"}");
            else
                StubServer.Respond(ctx, 404, "nope");
        });

        var tool = HttpTool.Of(new HttpTool.Options
        {
            Name = "get_post",
            Method = "GET",
            Url = server.BaseUrl + "/posts/{id}",
            Headers = new Dictionary<string, string> { ["Authorization"] = "Bearer ${" + EnvName + "}" },
            InputSchema = Schema(true),
        });

        var r1 = await tool.ExecuteAsync(new Dictionary<string, object?> { ["id"] = 5, ["q"] = "x" });

        Assert.False(r1.IsError);
        Assert.Contains("hello", r1.Output);
        Assert.Equal("Bearer " + EnvValue, seenAuth);
        Assert.Equal("/posts/5?q=x", seenUrl);
        Assert.Equal("http", tool.Source);
        Assert.Equal(200, r1.Metadata!["status"]);
    }

    [Fact]
    public async Task Non2xxIsError()
    {
        using var server = new StubServer(ctx =>
        {
            var path = ctx.Request.Url!.PathAndQuery;
            if (path.StartsWith("/posts/5"))
                StubServer.Respond(ctx, 200, "ok");
            else
                StubServer.Respond(ctx, 404, "nope");
        });

        var tool = HttpTool.Of(new HttpTool.Options
        {
            Name = "b",
            Method = "GET",
            Url = server.BaseUrl + "/posts/{id}",
            InputSchema = Schema(false),
        });

        var r2 = await tool.ExecuteAsync(new Dictionary<string, object?> { ["id"] = 99 });
        Assert.True(r2.IsError);
        Assert.StartsWith("HTTP 404:", r2.Output);
    }
}
