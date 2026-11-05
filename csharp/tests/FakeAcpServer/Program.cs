// FakeAcpServer — a minimal, scripted ACP (Agent Client Protocol) server.
//
// It speaks JSON-RPC 2.0, one object per line, over its own stdin/stdout — exactly the shape a
// real ACP agent (devin acp, gemini, zed agents) uses. It is NOT a general ACP implementation:
// it is a deterministic script, selected with `--scenario=<name>`, built to exercise one failure
// mode each, mirroring spikes/acp/fakeagent/main.go (the Go reference this was ported from) so
// AcpClientTests can drive Toolnexus.Acp.AcpClient over real OS pipes with no real ACP agent.
//
//   stale      - stateful session; answers a near-duplicate prompt with a STALE answer unless the
//                new prompt carries the "SUPERSEDES-ALL-PRIOR:" marker, in which case it answers
//                fresh.
//   hang       - sends session/request_permission and then never replies to session/prompt.
//   permission - like hang, but DOES wait for the client's reply to session/request_permission
//                before finishing the turn.
//   noisy      - emits agent_thought_chunk + tool-call narration interleaved with the real
//                agent_message_chunk, to prove naive accumulation corrupts structured output.
//   warm       - trivial echo. Every reply is prefixed with `sessionNewCalls=<n>;promptN=<n>;`
//                so a test can prove a session was opened once and the session/prompt count keeps
//                climbing across calls made on the SAME AcpClient (warm session reuse).
//
// Conformance flags (conformance/acp-client/cases.json, SPEC §8C), read from the environment
// and applied on top of any scenario:
//   TN_FAKE_REC=<file>         append one JSON line {method, params} per received request
//   TN_FAKE_NO_CONFIG_OPTION=1 reject session/set_config_option (-32601) so set_model is used
//   TN_FAKE_DRIP=1             on session/prompt: a thought, then three chunks 200ms apart
//   TN_FAKE_EXIT_ON_PROMPT=1   on session/prompt: exit without replying
//   TN_FAKE_FS_REQUEST=1       on session/prompt: send fs/read_text_file and wait for the answer;
//                              reply "ok" iff it was a -32601 error
//
// Protocol subset implemented: initialize, session/new, session/set_mode, session/prompt
// (request/response) and session/update, session/request_permission (server->client
// notifications/requests interleaved on the SAME stream).

using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;

var scenario = "warm";
foreach (var a in args)
{
    if (a.StartsWith("--scenario=", StringComparison.Ordinal))
        scenario = a["--scenario=".Length..];
    else if (a.StartsWith("-scenario=", StringComparison.Ordinal))
        scenario = a["-scenario=".Length..];
}

var stdout = Console.OpenStandardOutput();
var outWriter = new StreamWriter(stdout, new UTF8Encoding(false)) { AutoFlush = false };
var outLock = new object();
long nextServerReqId = 0;
long sessionNewCalls = 0;
long promptN = 0;
var historyLock = new object();
var history = new List<string>();
var permReplies = new ConcurrentDictionary<string, TaskCompletionSource<JsonElement>>();
var recPath = Environment.GetEnvironmentVariable("TN_FAKE_REC");
bool Flag(string name) => Environment.GetEnvironmentVariable(name) == "1";

void Send(object value)
{
    var json = JsonSerializer.Serialize(value);
    lock (outLock)
    {
        outWriter.Write(json);
        outWriter.Write('\n');
        outWriter.Flush();
    }
}

void SendNotification(string method, object? @params) =>
    Send(new Dictionary<string, object?> { ["jsonrpc"] = "2.0", ["method"] = method, ["params"] = @params });

string SendRequest(string method, object? @params)
{
    var id = "srv-" + Interlocked.Increment(ref nextServerReqId);
    Send(new Dictionary<string, object?> { ["jsonrpc"] = "2.0", ["id"] = id, ["method"] = method, ["params"] = @params });
    return id;
}

void Reply(object idValue, object result) =>
    Send(new Dictionary<string, object?> { ["jsonrpc"] = "2.0", ["id"] = idValue, ["result"] = result });

object IdValue(JsonElement idEl) => idEl.ValueKind switch
{
    JsonValueKind.String => idEl.GetString() ?? "",
    JsonValueKind.Number => idEl.GetInt64(),
    _ => idEl.GetRawText(),
};

void HandleStale(object id, string userText, List<string> hist)
{
    const string marker = "SUPERSEDES-ALL-PRIOR:";
    string answer;
    var idx = userText.IndexOf(marker, StringComparison.Ordinal);
    if (idx >= 0)
    {
        var fresh = userText[(idx + marker.Length)..].Trim();
        answer = "FRESH-ANSWER-TO:" + fresh;
    }
    else
    {
        // Naive stateful agent: scan history oldest-first, answer the FIRST one whose question
        // text appears (as a substring) in the current full-request prompt — the classic symptom
        // of a session that never forgets and pattern-matches against its whole memory.
        var matched = hist.Count > 0 ? hist[0] : "";
        foreach (var h in hist)
        {
            if (userText.Contains(h, StringComparison.Ordinal))
            {
                matched = h;
                break;
            }
        }
        answer = "STALE-ANSWER-TO:" + matched;
    }
    SendNotification("session/update", new Dictionary<string, object?>
    {
        ["sessionId"] = "sess-1",
        ["update"] = new Dictionary<string, object?>
        {
            ["sessionUpdate"] = "agent_message_chunk",
            ["content"] = new Dictionary<string, object?> { ["type"] = "text", ["text"] = answer },
        },
    });
    Reply(id, new Dictionary<string, object?> { ["stopReason"] = "end_turn" });
}

async Task HandlePermissionAsync(object id, string sessionId, string userText)
{
    var reqId = SendRequest("session/request_permission", new Dictionary<string, object?>
    {
        ["sessionId"] = sessionId,
        ["options"] = new List<object?>
        {
            new Dictionary<string, object?> { ["optionId"] = "reject", ["kind"] = "reject_once", ["name"] = "Reject" },
            new Dictionary<string, object?> { ["optionId"] = "allow-once", ["kind"] = "allow_once", ["name"] = "Allow" },
        },
    });
    var tcs = permReplies.GetOrAdd(reqId, _ => new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously));
    await tcs.Task.ConfigureAwait(false); // blocks until the client answers, mirroring a real agent

    SendNotification("session/update", new Dictionary<string, object?>
    {
        ["sessionId"] = sessionId,
        ["update"] = new Dictionary<string, object?>
        {
            ["sessionUpdate"] = "agent_message_chunk",
            ["content"] = new Dictionary<string, object?> { ["type"] = "text", ["text"] = "PERMITTED:" + userText },
        },
    });
    Reply(id, new Dictionary<string, object?> { ["stopReason"] = "end_turn" });
}

void HandleNoisy(object id, string sessionId, string userText)
{
    var chunks = new (string SessionUpdate, Dictionary<string, object?>? Extra)[]
    {
        ("agent_thought_chunk", Text("Let me think about this... ")),
        ("tool_call", null),
        ("agent_message_chunk", Text("{\"answer\":")),
        ("tool_call_update", null),
        ("agent_thought_chunk", Text("now double-checking the number... ")),
        ("agent_message_chunk", Text("\"" + userText + "\"}")),
    };
    foreach (var (kind, extra) in chunks)
    {
        var update = new Dictionary<string, object?> { ["sessionUpdate"] = kind };
        if (kind is "tool_call")
        {
            update["toolCallId"] = "t1";
            update["title"] = "reading files";
            update["status"] = "in_progress";
        }
        else if (kind is "tool_call_update")
        {
            update["toolCallId"] = "t1";
            update["status"] = "completed";
        }
        else if (extra is not null)
        {
            update["content"] = extra;
        }
        SendNotification("session/update", new Dictionary<string, object?> { ["sessionId"] = sessionId, ["update"] = update });
    }
    Reply(id, new Dictionary<string, object?> { ["stopReason"] = "end_turn" });

    static Dictionary<string, object?> Text(string t) => new() { ["type"] = "text", ["text"] = t };
}

void Chunk(string sessionId, string kind, string text) =>
    SendNotification("session/update", new Dictionary<string, object?>
    {
        ["sessionId"] = sessionId,
        ["update"] = new Dictionary<string, object?>
        {
            ["sessionUpdate"] = kind,
            ["content"] = new Dictionary<string, object?> { ["type"] = "text", ["text"] = text },
        },
    });

var stdin = Console.OpenStandardInput();
using var reader = new StreamReader(stdin, Encoding.UTF8);

string? line;
while ((line = await reader.ReadLineAsync().ConfigureAwait(false)) is not null)
{
    if (string.IsNullOrWhiteSpace(line)) continue;

    JsonDocument doc;
    try { doc = JsonDocument.Parse(line); }
    catch { continue; }
    using (doc)
    {
        var root = doc.RootElement;
        var hasId = root.TryGetProperty("id", out var idEl) && idEl.ValueKind != JsonValueKind.Null;
        var hasMethod = root.TryGetProperty("method", out var methodEl) && methodEl.ValueKind == JsonValueKind.String;

        if (!hasMethod && hasId)
        {
            // A reply to one of OUR server-initiated requests (session/request_permission).
            var key = idEl.ValueKind == JsonValueKind.String ? idEl.GetString() ?? "" : idEl.GetRawText();
            // GetOrAdd, not TryRemove: a fast client can answer before the scenario
            // registers its waiter, and a dropped reply hangs the turn.
            permReplies.GetOrAdd(key, _ => new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously))
                .TrySetResult(root.Clone());
            continue;
        }

        if (!hasMethod) continue;
        var method = methodEl.GetString()!;
        if (!string.IsNullOrEmpty(recPath))
            File.AppendAllText(recPath, JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["method"] = method,
                ["params"] = root.TryGetProperty("params", out var recP) ? recP.Clone() : null,
            }) + "\n");

        switch (method)
        {
            case "initialize":
                Reply(IdValue(idEl), new Dictionary<string, object?>
                {
                    ["protocolVersion"] = 1,
                    ["agentCapabilities"] = new Dictionary<string, object?> { ["loadSession"] = false },
                });
                break;

            case "session/new":
                Interlocked.Increment(ref sessionNewCalls);
                Reply(IdValue(idEl), new Dictionary<string, object?> { ["sessionId"] = "sess-1" });
                break;

            case "session/set_mode":
                Reply(IdValue(idEl), new Dictionary<string, object?>());
                break;

            case "session/set_config_option":
                if (Flag("TN_FAKE_NO_CONFIG_OPTION"))
                    Send(new Dictionary<string, object?>
                    {
                        ["jsonrpc"] = "2.0", ["id"] = IdValue(idEl),
                        ["error"] = new Dictionary<string, object?> { ["code"] = -32601, ["message"] = "method not found" },
                    });
                else Reply(IdValue(idEl), new Dictionary<string, object?>());
                break;

            case "session/set_model":
                Reply(IdValue(idEl), new Dictionary<string, object?>());
                break;

            case "session/cancel":
                Reply(IdValue(idEl), new Dictionary<string, object?>());
                break;

            case "session/prompt":
            {
                var sessionId = root.TryGetProperty("params", out var pEl) &&
                                 pEl.TryGetProperty("sessionId", out var sidEl) && sidEl.ValueKind == JsonValueKind.String
                    ? sidEl.GetString()! : "sess-1";
                var textBuilder = new StringBuilder();
                if (pEl.ValueKind == JsonValueKind.Object &&
                    pEl.TryGetProperty("prompt", out var promptEl) && promptEl.ValueKind == JsonValueKind.Array)
                {
                    foreach (var part in promptEl.EnumerateArray())
                        if (part.TryGetProperty("text", out var t) && t.ValueKind == JsonValueKind.String)
                            textBuilder.Append(t.GetString());
                }
                var userText = textBuilder.ToString();
                var n = Interlocked.Increment(ref promptN);
                List<string> histSnapshot;
                lock (historyLock)
                {
                    history.Add(userText);
                    histSnapshot = new List<string>(history);
                }

                var id = IdValue(idEl);
                if (Flag("TN_FAKE_EXIT_ON_PROMPT"))
                {
                    outWriter.Flush();
                    Environment.Exit(0);
                }
                if (Flag("TN_FAKE_DRIP"))
                {
                    _ = Task.Run(async () =>
                    {
                        Chunk(sessionId, "agent_thought_chunk", "pondering");
                        var drips = new[] { "drip-1 ", "drip-2 ", "drip-3" };
                        for (var i = 0; i < drips.Length; i++)
                        {
                            if (i > 0) await Task.Delay(200).ConfigureAwait(false);
                            Chunk(sessionId, "agent_message_chunk", drips[i]);
                        }
                        Reply(id, new Dictionary<string, object?> { ["stopReason"] = "end_turn" });
                    });
                    break;
                }
                if (Flag("TN_FAKE_FS_REQUEST"))
                {
                    _ = Task.Run(async () =>
                    {
                        var reqId = SendRequest("fs/read_text_file", new Dictionary<string, object?>
                        {
                            ["sessionId"] = sessionId, ["path"] = "/etc/hosts",
                        });
                        var answer = await permReplies.GetOrAdd(reqId,
                            _ => new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously)).Task.ConfigureAwait(false);
                        var ok = answer.TryGetProperty("error", out var err) &&
                                 err.TryGetProperty("code", out var code) && code.GetInt32() == -32601;
                        Chunk(sessionId, "agent_message_chunk", ok ? "ok" : "bad:" + answer.GetRawText());
                        Reply(id, new Dictionary<string, object?> { ["stopReason"] = "end_turn" });
                    });
                    break;
                }
                switch (scenario)
                {
                    case "stale":
                        _ = Task.Run(() => HandleStale(id, userText, histSnapshot));
                        break;
                    case "hang":
                        SendRequest("session/request_permission", new Dictionary<string, object?>
                        {
                            ["sessionId"] = sessionId,
                            ["options"] = new List<object?>
                            {
                                new Dictionary<string, object?> { ["optionId"] = "allow-once", ["kind"] = "allow_once", ["name"] = "Allow" },
                            },
                        });
                        // Deliberately never reply to session/prompt.
                        break;
                    case "permission":
                        _ = Task.Run(() => HandlePermissionAsync(id, sessionId, userText));
                        break;
                    case "noisy":
                        _ = Task.Run(() => HandleNoisy(id, sessionId, userText));
                        break;
                    default: // warm
                        var prefix = $"sessionNewCalls={Volatile.Read(ref sessionNewCalls)};promptN={n};";
                        SendNotification("session/update", new Dictionary<string, object?>
                        {
                            ["sessionId"] = sessionId,
                            ["update"] = new Dictionary<string, object?>
                            {
                                ["sessionUpdate"] = "agent_message_chunk",
                                ["content"] = new Dictionary<string, object?> { ["type"] = "text", ["text"] = prefix + "echo:" + userText },
                            },
                        });
                        Reply(id, new Dictionary<string, object?> { ["stopReason"] = "end_turn" });
                        break;
                }
                break;
            }

            default:
                if (hasId) Reply(IdValue(idEl), new Dictionary<string, object?>());
                break;
        }
    }
}
