// Test-only helper for CliClientTests (SPEC §8C) — a port of the fakes in golang/cli_test.go.
// Three parts, picked by argv/env:
//   mcp-bridge <url>             the stdio MCP bridge (CliClient.RunMcpBridgeAsync)
//   TN_FAKE_ACP=1 + argv[0]=acp  a fake ACP agent
//   TN_FAKE_CLI=<mode>           a fake one-shot CLI
using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Toolnexus;

var argv = args;
if (argv.Length == 2 && argv[0] == "mcp-bridge")
{
    try
    {
        await CliClient.RunMcpBridgeAsync(argv[1], Environment.GetEnvironmentVariable(CliClient.McpTokenEnv),
            Console.In, Console.Out);
        return 0;
    }
    catch (Exception e)
    {
        Console.Error.WriteLine(e.Message);
        return 1;
    }
}
if (Environment.GetEnvironmentVariable("TN_FAKE_ACP") == "1" && argv.Length >= 1 && argv[0] == "acp")
    return await FakeAcp.RunAsync();
var mode = Environment.GetEnvironmentVariable("TN_FAKE_CLI");
if (!string.IsNullOrEmpty(mode)) return FakeOneShot.Run(mode, argv);
Console.Error.WriteLine("FakeCli: no mode");
return 2;

static class Util
{
    public static string J(object? v) => JsonSerializer.Serialize(v);

    public static void Rec(string text)
    {
        var p = Environment.GetEnvironmentVariable("TN_FAKE_REC");
        if (!string.IsNullOrEmpty(p)) File.AppendAllText(p, text);
    }
}

// ---------------------------------------------------------------------------
// Fake one-shot CLI. Prompt from stdin, or the arg after "--p", or the file
// after "--f". Writes the answer to stdout, or to the file after "--o".
// ---------------------------------------------------------------------------
static class FakeOneShot
{
    public static int Run(string mode, string[] args)
    {
        string prompt = "", outFile = "";
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--p": prompt = args[++i]; break;
                case "--f": prompt = File.ReadAllText(args[++i]); break;
                case "--o": outFile = args[++i]; break;
                case "--argv":
                    Console.Out.Write("{\"content\":" + Util.J(string.Join(" ", args)) + "}");
                    return 0;
            }
        }
        if (prompt == "") prompt = Console.In.ReadToEnd();
        Util.Rec(prompt + "\n=====\n");
        string reply;
        switch (mode)
        {
            case "exit":
                Console.Error.Write("boom: bad login");
                return 3;
            case "empty":
                return 0;
            case "sleep":
                Thread.Sleep(10000);
                reply = "{\"content\":\"late\"}";
                break;
            case "nooutfile":
                Console.Out.Write("progress only");
                return 0;
            case "tool":
                reply = prompt.Contains("BADGE-77Q")
                    ? "{\"content\": " + Util.J("Your badge is BADGE-77Q.") + "}"
                    // fenced and with string arguments: exercises the tolerant parser
                    : "```json\n{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"get_badge_number\",\"arguments\":\"{}\"}}]}\n```\n\n";
                break;
            default:
                reply = "{\"content\":\"hello\"}";
                break;
        }
        if (outFile != "")
        {
            Console.Out.Write("progress on stdout\n");
            File.WriteAllText(outFile, reply);
            return 0;
        }
        Console.Out.Write(reply);
        return 0;
    }
}

// ---------------------------------------------------------------------------
// Fake ACP agent. FAKE_HTTP=1 advertises mcpCapabilities.http. FAKE_FAIL_INIT=1
// exits before answering initialize. FAKE_NO_CONFIG=1 rejects
// session/set_config_option. FAKE_PROJECT=1 reads the MCP server from
// <cwd>/.mcp.json instead of session/new. TN_FAKE_REC records every method.
// ---------------------------------------------------------------------------
static class FakeAcp
{
    static readonly TextReader In = Console.In;
    static int _id = 1000;

    static void Send(object msg)
    {
        Console.Out.Write(JsonSerializer.Serialize(msg) + "\n");
        Console.Out.Flush();
    }

    static void Update(object u) => Send(new Dictionary<string, object?>
    {
        ["jsonrpc"] = "2.0", ["method"] = "session/update",
        ["params"] = new Dictionary<string, object?> { ["sessionId"] = "s1", ["update"] = u },
    });

    static string Ask(string callId, string title)
    {
        var id = ++_id;
        Send(new Dictionary<string, object?>
        {
            ["jsonrpc"] = "2.0", ["id"] = id, ["method"] = "session/request_permission",
            ["params"] = new Dictionary<string, object?>
            {
                ["sessionId"] = "s1",
                ["toolCall"] = new Dictionary<string, object?> { ["toolCallId"] = callId, ["title"] = title },
                ["options"] = new object[]
                {
                    new Dictionary<string, object?> { ["optionId"] = "yes", ["kind"] = "allow_once" },
                    new Dictionary<string, object?> { ["optionId"] = "no", ["kind"] = "reject_once" },
                },
            },
        });
        while (In.ReadLine() is { } line)
        {
            var n = JsonNode.Parse(line);
            if (n?["id"] is JsonValue v && v.TryGetValue<long>(out var got) && got == id)
                return n["result"]?["outcome"]?["optionId"]?.GetValue<string>() ?? "";
        }
        return "";
    }

    public static async Task<int> RunAsync()
    {
        if (Environment.GetEnvironmentVariable("FAKE_FAIL_INIT") == "1")
        {
            Console.Error.Write("unknown flag: acp");
            return 2;
        }
        JsonObject? entry = null;
        var cwd = "";
        while (In.ReadLine() is { } line)
        {
            var m = JsonNode.Parse(line)!.AsObject();
            var method = m["method"]?.GetValue<string>() ?? "";
            var prms = m["params"];
            using (var raw = JsonDocument.Parse(line))
                Util.Rec(method + " " + (raw.RootElement.TryGetProperty("params", out var rp) ? rp.GetRawText() : "null") + "\n");
            var id = m["id"]?.DeepClone();
            object Reply(object result) => new Dictionary<string, object?> { ["jsonrpc"] = "2.0", ["id"] = id, ["result"] = result };
            switch (method)
            {
                case "initialize":
                    Send(Reply(new Dictionary<string, object?>
                    {
                        ["protocolVersion"] = 1,
                        ["agentCapabilities"] = new Dictionary<string, object?>
                        {
                            ["mcpCapabilities"] = new Dictionary<string, object?>
                            { ["http"] = Environment.GetEnvironmentVariable("FAKE_HTTP") == "1" },
                        },
                    }));
                    break;
                case "session/new":
                    cwd = prms?["cwd"]?.GetValue<string>() ?? "";
                    if (prms?["mcpServers"] is JsonArray arr && arr.Count == 1) entry = arr[0]!.AsObject();
                    Send(Reply(new Dictionary<string, object?> { ["sessionId"] = "s1" }));
                    break;
                case "session/set_config_option":
                    if (Environment.GetEnvironmentVariable("FAKE_NO_CONFIG") == "1")
                        Send(new Dictionary<string, object?>
                        {
                            ["jsonrpc"] = "2.0", ["id"] = id,
                            ["error"] = new Dictionary<string, object?> { ["code"] = -32601, ["message"] = "nope" },
                        });
                    else Send(Reply(new Dictionary<string, object?>()));
                    break;
                case "session/set_model":
                    Send(Reply(new Dictionary<string, object?>()));
                    break;
                case "session/prompt":
                    if (Environment.GetEnvironmentVariable("FAKE_PROJECT") == "1")
                    {
                        var pf = JsonNode.Parse(File.ReadAllText(Path.Combine(cwd, ".mcp.json")))!;
                        var e = pf["mcpServers"]!["toolnexus"]!;
                        var hdr = new JsonArray();
                        foreach (var (k, v) in e["headers"]!.AsObject())
                            hdr.Add(new JsonObject { ["name"] = k, ["value"] = v!.GetValue<string>() });
                        entry = new JsonObject { ["type"] = "http", ["url"] = e["url"]!.GetValue<string>(), ["headers"] = hdr };
                    }
                    // devin-like: the name is only on the tool_call update; the permission
                    // request has an empty title.
                    Update(new Dictionary<string, object?> { ["sessionUpdate"] = "tool_call", ["toolCallId"] = "tc1", ["title"] = "Calling get_badge_number from toolnexus" });
                    var ours = Ask("tc1", "");
                    Update(new Dictionary<string, object?> { ["sessionUpdate"] = "tool_call", ["toolCallId"] = "tc2", ["title"] = "Bash" });
                    var own = Ask("tc2", "Bash");
                    // a lookalike title on a new id: substring of ours, must be rejected
                    var fake = Ask("tc3", "my_get_badge_number_wrapper");
                    // the same id again with a different title: the decision is per id
                    var again = Ask("tc1", "Bash");
                    var text = $"perm ours={ours} own={own} fake={fake} again={again}; " + await CallServedAsync(entry);
                    Update(new Dictionary<string, object?>
                    {
                        ["sessionUpdate"] = "agent_message_chunk",
                        ["content"] = new Dictionary<string, object?> { ["type"] = "text", ["text"] = text },
                    });
                    Send(Reply(new Dictionary<string, object?> { ["stopReason"] = "end_turn" }));
                    break;
            }
        }
        return 0;
    }

    /// <summary>Calls get_badge_number and load_skill on the handed MCP server (http with the given
    /// headers, or by spawning the stdio entry), and probes the http endpoint without a token.</summary>
    static async Task<string> CallServedAsync(JsonObject? entry)
    {
        string[] reqs =
        {
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fake\",\"version\":\"0\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_badge_number\",\"arguments\":{}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"load_skill\",\"arguments\":{\"name\":\"hello-world\"}}}",
        };
        var replies = new List<string>();
        var noauth = 0;
        var type = entry?["type"]?.GetValue<string>();
        if (type == "http")
        {
            var url = entry!["url"]!.GetValue<string>();
            using var http = new HttpClient();
            foreach (var r in reqs)
            {
                using var req = new HttpRequestMessage(HttpMethod.Post, url) { Content = new StringContent(r, Encoding.UTF8, "application/json") };
                req.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream");
                foreach (var h in entry["headers"]!.AsArray())
                    req.Headers.TryAddWithoutValidation(h!["name"]!.GetValue<string>(), h["value"]!.GetValue<string>());
                try
                {
                    using var resp = await http.SendAsync(req);
                    replies.Add(await resp.Content.ReadAsStringAsync());
                }
                catch { return "http error"; }
            }
            using var bare = new HttpRequestMessage(HttpMethod.Post, url) { Content = new StringContent(reqs[0], Encoding.UTF8, "application/json") };
            try
            {
                using var resp = await http.SendAsync(bare);
                noauth = (int)resp.StatusCode;
            }
            catch { /* noauth stays 0 */ }
        }
        else if (entry != null)
        {
            var psi = new ProcessStartInfo(entry["command"]!.GetValue<string>())
            {
                RedirectStandardInput = true, RedirectStandardOutput = true, UseShellExecute = false,
            };
            foreach (var a in entry["args"]!.AsArray()) psi.ArgumentList.Add(a!.GetValue<string>());
            foreach (var e in entry["env"]!.AsArray()) psi.Environment[e!["name"]!.GetValue<string>()] = e["value"]!.GetValue<string>();
            using var p = Process.Start(psi)!;
            await p.StandardInput.WriteAsync(string.Join("\n", reqs) + "\n");
            p.StandardInput.Close();
            var output = await p.StandardOutput.ReadToEndAsync();
            await p.WaitForExitAsync();
            if (p.ExitCode != 0) return "bridge error";
            replies = output.Trim().Split('\n').ToList();
        }
        return $"entry={type ?? "<nil>"} noauth={noauth} replies={string.Join(" | ", replies)}";
    }
}
