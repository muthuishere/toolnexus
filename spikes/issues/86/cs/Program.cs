// Spike for issue #86 (C#): the issue says a toolkit-less completion is
// "impossible". Test what actually happens: does `null` compile, and does it run?
using Toolnexus;

var baseUrl = Environment.GetEnvironmentVariable("SPIKE86_BASE")!;
var client = LlmClient.Create(new LlmClient.Options
{
    BaseUrl = baseUrl, Style = "openai", Model = "mock", ApiKey = "not-a-real-key",
});

// There is no overload without a Toolkit, so the only way to say "no tools"
// without building one is `null!` — which COMPILES (a nullable-reference
// warning, not an error). The question is whether the loop survives it.
try
{
    var r = await client.RunAsync("write me a haiku", null!);
    Console.WriteLine($"cs: null-toolkit run OK, text={r.Text}");
}
catch (Exception e)
{
    Console.WriteLine($"cs: null-toolkit run FAILED: {e.GetType().Name}: {e.Message}");
}

// The workaround that exists today.
var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));
var r2 = await client.RunAsync("write me a haiku", tk);
Console.WriteLine($"cs: Toolkit.CreateAsync(Builtins=false) OK, tools={tk.Tools().Count} text={r2.Text}");
