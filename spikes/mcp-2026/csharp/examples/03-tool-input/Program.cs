// Example 03 — a tool that asks the user for input mid-call (MRTR `input_required`).
//
// Run: dotnet run --project examples/03-tool-input
//
// In MCP 2026-07-28 a server never sends requests to the client. Instead a tools/call can
// RETURN resultType "input_required" with a set of inputRequests (e.g. an elicitation form).
// The client answers them and re-sends the same call with inputResponses (+ the opaque
// requestState). CallToolAsync runs those rounds for you: you only supply a Responder.
using System.Text.Json.Nodes;
using Mcp2026;

await using var ts = await HttpHost.StartAsync(Server.Fixture()); // `greet` asks for the user's name
var c = new McpClient(ts.Url + "/mcp");

// The Responder is called once per input request. key identifies the request inside the
// round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
// In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
Responder askUser = (key, req) =>
{
    Console.WriteLine($"server asks ({req["method"]}): {req["params"]?["message"]}");
    // Answer like a person who filled the form. "decline" / "cancel" are the other actions.
    return Task.FromResult(new JsonObject { ["action"] = "accept", ["content"] = new JsonObject { ["name"] = "Ada" } });
};

var res = await c.CallToolAsync("greet", [], askUser);
Console.WriteLine("result: " + Mcp.Text(res));
