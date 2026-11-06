// dotnet new console && dotnet add package Toolnexus && dotnet run
using Toolnexus;
using static Toolnexus.LlmClient;

var add = NativeTool.Of("add", "Add two numbers.",
    new Dictionary<string, object?>
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["a"] = new { type = "number" }, ["b"] = new { type = "number" } },
        ["required"] = new[] { "a", "b" },
    },
    args => $"{Convert.ToDouble(args["a"]?.ToString()) + Convert.ToDouble(args["b"]?.ToString())}");

// Your "model": first turn asks for a tool, second turn streams the answer.
var client = InProcess.CreateClient(new InProcess.Options
{
    Model = "my-model",
    GenerateStream = async (req, emit, ct) =>
    {
        var last = (System.Text.Json.JsonElement)req.Messages[^1]!;
        if (last.GetProperty("role").GetString() != "tool")
            return InProcess.Response.FromToolCalls(new InProcess.ToolCall { Name = "add", Arguments = new { a = 2, b = 3 } });
        string[] words = ["The ", "sum ", "is ", last.GetProperty("content").GetString()!, "."];
        foreach (var w in words) emit(new StreamEvent(StreamKind.Text, Delta: w)); // deltas must add up to content
        return InProcess.Response.FromContent(string.Concat(words));
    },
});

await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = [add] });
await client.StreamAsync("What is 2 + 3?", toolkit, ev =>
{
    if (ev.Type == StreamKind.Text) Console.Write(ev.Delta);
    else if (ev.Type == StreamKind.ToolCall) Console.WriteLine($"[calling {ev.Name}]");
});
Console.WriteLine(); // The sum is 5.
