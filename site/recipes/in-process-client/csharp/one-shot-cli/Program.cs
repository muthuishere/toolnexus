// usage: dotnet run -- <cli that reads a prompt on stdin>   (dotnet add package Toolnexus; OneShot.cs alongside)
using System.Text.Json;
using Toolnexus;

var add = NativeTool.Of("add", "Add two numbers.",
    new Dictionary<string, object?>
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["a"] = new { type = "number" }, ["b"] = new { type = "number" } },
        ["required"] = new[] { "a", "b" },
    },
    args => $"{Convert.ToDouble(args["a"]?.ToString()) + Convert.ToDouble(args["b"]?.ToString())}");
await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = [add] });
var r = await OneShot.Client(args[0], args[1..]).RunAsync("Use the add tool to add 19 and 23, then tell me the sum.", tk);
Console.WriteLine($"text: {JsonSerializer.Serialize(r.Text)} toolCalls: {JsonSerializer.Serialize(r.ToolCalls.Select(c => new { name = c.Name, args = c.Args, output = c.Output }))}");
