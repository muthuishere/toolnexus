using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;
using Toolnexus;

public static class OneShot
{
    // JSON.stringify-style escaping, so the prompt text matches the other languages byte for byte.
    static readonly JsonSerializerOptions Js = new() { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    // Render the whole request as one prompt: tools, transcript, and the reply contract.
    public static string Render(InProcess.Request req)
    {
        var lines = new List<string> { "You are the model inside a tool-calling loop. Tools you may call:" };
        foreach (var t in req.Tools.Select(o => JsonSerializer.SerializeToNode(o)!["function"]!))
            lines.Add($"- {t["name"]}: {t["description"]} args={t["parameters"]?.ToJsonString(Js)}");
        lines.AddRange(["", "Conversation:"]);
        var called = new Dictionary<string, string>(); // tool_call_id -> tool name, so results read "[tool add] 5"
        foreach (var m in req.Messages.Select(o => JsonSerializer.SerializeToNode(o)!))
        {
            var role = (string)m["role"]!;
            if (role == "tool")
            {
                var id = (string)m["tool_call_id"]!;
                lines.Add($"[tool {called.GetValueOrDefault(id, id)}] {m["content"]}");
            }
            else if (m["tool_calls"] is JsonArray calls)
            {
                foreach (var c in calls) called[(string)c!["id"]!] = (string)c["function"]!["name"]!;
                lines.Add($"[assistant called] {new JsonArray(calls.Select(c => c!["function"]!.DeepClone()).ToArray()).ToJsonString(Js)}");
            }
            else lines.Add($"[{role}] {(m["content"] is JsonValue v && v.TryGetValue<string>(out var s) ? s : m["content"]?.ToJsonString(Js))}");
        }
        lines.AddRange(["", """Reply with ONLY one JSON object: {"tool_calls":[{"name":"...","arguments":{...}}]} to call tools,""",
            """or {"content":"..."} with the final answer. No prose, no code fences."""]);
        return string.Join("\n", lines);
    }

    // Pull the first {...} out of the reply, tolerating fences or chatter around it.
    public static InProcess.Response Parse(string output)
    {
        try
        {
            int start = output.IndexOf('{'), end = output.LastIndexOf('}');
            var r = JsonNode.Parse(output[start..(end + 1)])!;
            if (r["tool_calls"] is JsonArray { Count: > 0 } calls)
                return new InProcess.Response { ToolCalls = calls.Select(c => new InProcess.ToolCall
                    { Name = (string)c!["name"]!, Arguments = c["arguments"]?.ToJsonString() ?? "{}" }).ToList() };
            if (r["content"] is JsonValue v && v.TryGetValue<string>(out var content)) return InProcess.Response.FromContent(content);
        }
        catch (Exception) { }
        return InProcess.Response.FromContent(output.Trim()); // not our contract: treat it as the answer
    }

    // One process per turn. The prompt goes in on stdin; the reply comes back on stdout.
    public static LlmClient Client(string command, IReadOnlyList<string> args, string? model = null, int timeoutMs = 300_000) =>
        InProcess.CreateClient(new InProcess.Options
        {
            Model = model ?? command,
            Generate = req =>
            {
                var psi = new ProcessStartInfo(command) { RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
                foreach (var a in args) psi.ArgumentList.Add(a);
                using var p = Process.Start(psi)!;
                var stdout = p.StandardOutput.ReadToEndAsync();
                var stderr = p.StandardError.ReadToEndAsync();
                p.StandardInput.Write(Render(req));
                p.StandardInput.Close();
                if (!p.WaitForExit(timeoutMs)) { p.Kill(true); throw new TimeoutException($"{command} timed out"); }
                if (p.ExitCode != 0) throw new InvalidOperationException($"{command} exited {p.ExitCode}: {stderr.Result}");
                return Parse(stdout.Result);
            },
        });
}
