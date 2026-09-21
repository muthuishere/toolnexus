// Gate item 1, C#: does `int? Retries` (csharp/src/Toolnexus/LlmClient.cs:184)
// already distinguish "unset" from "explicit 0"? Defaulting is
//   :1554 `private int Retries() => _opts.Retries ?? 2;`
// `??` only falls through on null, so 0 survives. Prove it against a fake
// HttpMessageHandler that always answers 500, counting SendAsync calls.

using System.Net;
using Toolnexus;

int calls = 0;

var handler = new FakeHandler(() =>
{
    calls++;
    return new HttpResponseMessage(HttpStatusCode.InternalServerError)
    {
        Content = new StringContent("boom"),
    };
});

var toolkit = await Toolkit.CreateAsync(new Toolkit.Options());

// explicit zero
var client0 = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = "http://example.invalid",
    Style = "openai",
    Model = "test-model",
    ApiKey = "x",
    Retries = 0,
    HttpHandler = handler,
});
try
{
    await client0.RunAsync("hi", toolkit);
}
catch
{
    // expected
}
Console.WriteLine($"calls with Retries=0 -> {calls}");
if (calls != 1)
{
    Console.WriteLine($"FAIL: expected 1 call, got {calls}");
    return 1;
}

// unset -> documented default of 2 retries = 3 total attempts
calls = 0;
var client1 = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = "http://example.invalid",
    Style = "openai",
    Model = "test-model",
    ApiKey = "x",
    // Retries left null
    HttpHandler = handler,
});
try
{
    await client1.RunAsync("hi", toolkit);
}
catch
{
    // expected
}
Console.WriteLine($"calls with Retries UNSET -> {calls}");
if (calls != 3)
{
    Console.WriteLine($"FAIL: expected 3 calls, got {calls}");
    return 1;
}

Console.WriteLine("CSHARP VERDICT: Retries=0 != unset. Nullable int `?? 2` already distinguishes them. No -1 sentinel needed.");
return 0;

sealed class FakeHandler : HttpMessageHandler
{
    private readonly Func<HttpResponseMessage> _respond;
    public FakeHandler(Func<HttpResponseMessage> respond) => _respond = respond;
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        => Task.FromResult(_respond());
}
