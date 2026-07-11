using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// A2A (agent-to-agent) — OUTBOUND: call remote A2A agents.
//
// A remote agent publishes an Agent Card at `/.well-known/agent-card.json`
// describing its skills. Each advertised skill becomes a uniform ITool
// (source "a2a") whose Execute performs one JSON-RPC `SendMessage`
// (non-blocking) to get a Task id, then polls `GetTask` until the Task reaches
// a terminal state — mapping the Task's artifact/message text to a ToolResult.
//
// This is a genuine subset of real A2A (JSON-RPC 2.0 over one HTTP POST
// endpoint), so a toolnexus agent interoperates with real A2A peers. See
// ../../SPEC.md §7A and openspec/changes/add-a2a-agents. Reuses the `${ENV}`
// header expansion from McpSource; secrets live in the environment and are
// never logged. Mirrors the JS reference (js/src/a2a.ts) byte-for-byte.
// ---------------------------------------------------------------------------

// ===========================================================================
// Wire types (the A2A subset we speak — verified against a2a-python).
// ===========================================================================

/// <summary>A single content part of a message/artifact (<c>kind:"text"</c> carries <c>text</c>).</summary>
public sealed class A2APart
{
    [JsonPropertyName("kind")] public string? Kind { get; set; }
    [JsonPropertyName("text")] public string? Text { get; set; }
}

/// <summary>An A2A message (a role plus text parts).</summary>
public sealed class A2AMessage
{
    [JsonPropertyName("role")] public string? Role { get; set; }
    [JsonPropertyName("messageId")] public string? MessageId { get; set; }
    [JsonPropertyName("parts")] public List<A2APart>? Parts { get; set; }
}

/// <summary>An A2A artifact produced by a completed task.</summary>
public sealed class A2AArtifact
{
    [JsonPropertyName("artifactId")] public string? ArtifactId { get; set; }
    [JsonPropertyName("parts")] public List<A2APart>? Parts { get; set; }
}

/// <summary>A task's status: its lifecycle <c>state</c> plus an optional message.</summary>
public sealed class A2ATaskStatus
{
    [JsonPropertyName("state")] public string? State { get; set; }
    [JsonPropertyName("message")] public A2AMessage? Message { get; set; }
}

/// <summary>An A2A task — the unit of work returned by <c>SendMessage</c> / <c>GetTask</c>.</summary>
public sealed class A2ATask
{
    [JsonPropertyName("id")] public string? Id { get; set; }

    /// <summary>Groups a peer's turns into one conversation (A2A contextId).</summary>
    [JsonPropertyName("contextId")] public string? ContextId { get; set; }

    [JsonPropertyName("status")] public A2ATaskStatus? Status { get; set; }
    [JsonPropertyName("artifacts")] public List<A2AArtifact>? Artifacts { get; set; }
    [JsonPropertyName("history")] public List<A2AMessage>? History { get; set; }
}

/// <summary>A skill advertised by an Agent Card.</summary>
public sealed class A2ASkill
{
    [JsonPropertyName("id")] public string? Id { get; set; }
    [JsonPropertyName("name")] public string? Name { get; set; }
    [JsonPropertyName("description")] public string? Description { get; set; }
    [JsonPropertyName("tags")] public List<string>? Tags { get; set; }
    [JsonPropertyName("examples")] public List<string>? Examples { get; set; }
}

/// <summary>The Agent Card published at <c>/.well-known/agent-card.json</c>.</summary>
public sealed class AgentCard
{
    [JsonPropertyName("name")] public string? Name { get; set; }
    [JsonPropertyName("description")] public string? Description { get; set; }
    [JsonPropertyName("version")] public string? Version { get; set; }
    [JsonPropertyName("protocolVersion")] public string? ProtocolVersion { get; set; }
    [JsonPropertyName("defaultInputModes")] public List<string>? DefaultInputModes { get; set; }
    [JsonPropertyName("defaultOutputModes")] public List<string>? DefaultOutputModes { get; set; }
    [JsonPropertyName("skills")] public List<A2ASkill>? Skills { get; set; }
    /// <summary>The JSON-RPC endpoint the agent listens on.</summary>
    [JsonPropertyName("url")] public string? Url { get; set; }
}

// ===========================================================================
// Agent descriptor.
// ===========================================================================

/// <summary>
/// Descriptor pointing at a remote Agent Card URL. Construct with an object
/// initializer (the idiomatic .NET factory), e.g.
/// <c>new Agent { Card = url, Timeout = 60_000 }</c>.
/// </summary>
public sealed class Agent
{
    /// <summary>URL of the Agent Card (<c>/.well-known/agent-card.json</c>).</summary>
    public string Card { get; set; } = "";

    /// <summary><c>${ENV_VAR}</c> header values are expanded at call time and never logged.</summary>
    public IDictionary<string, string>? Headers { get; set; }

    /// <summary>Overall poll budget in ms (default 300000).</summary>
    public long? Timeout { get; set; }

    /// <summary>Interval between <c>GetTask</c> polls in ms (default 1000).</summary>
    public long? PollEvery { get; set; }
}

/// <summary>
/// A2A outbound: resolve remote agents to tools and parse an <c>agents</c> config block.
/// </summary>
public static class A2A
{
    private const long DefaultTimeout = 300_000L;
    private const long DefaultPollEvery = 1_000L;

    /// <summary>Terminal A2A task states — polling stops once one of these is reached.</summary>
    private static readonly HashSet<string> Terminal = new() { "completed", "failed", "canceled" };

    private static readonly HttpClient Client = new();

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    // -----------------------------------------------------------------------
    // Config parsing — an `agents` block mirroring `mcpServers` (§2).
    // -----------------------------------------------------------------------

    /// <summary>MCP <c>isEnabled</c> precedence: <c>disabled:true</c> wins, then <c>enabled:false</c>.</summary>
    private static bool IsEnabled(IDictionary<string, object?> cfg)
    {
        if (cfg.Get("disabled") is true) return false;
        if (cfg.Get("enabled") is false) return false;
        return true;
    }

    private static long? LongOf(IDictionary<string, object?> cfg, string key)
        => cfg.Get(key) switch
        {
            long l => l,
            int i => i,
            double d => (long)d,
            _ => (long?)null,
        };

    private static IDictionary<string, string>? HeadersOf(IDictionary<string, object?> cfg)
    {
        if (cfg.Get("headers") is not IDictionary<string, object?> raw) return null;
        var output = new Dictionary<string, string>();
        foreach (var (k, v) in raw) output[k] = v?.ToString() ?? "";
        return output;
    }

    /// <summary>
    /// Parse an <c>agents</c> block (a map of id → agent config) into Agent descriptors,
    /// skipping disabled entries. The config key is just an identifier — a tool's name
    /// prefix comes from the fetched card's <c>name</c>, not the key.
    /// </summary>
    public static List<Agent> ParseAgentsConfig(object? block)
    {
        var output = new List<Agent>();
        if (block is not IDictionary<string, object?> map) return output;
        foreach (var value in map.Values)
        {
            if (value is not IDictionary<string, object?> cfg) continue;
            if (cfg.Get("card") is not string card) continue;
            if (!IsEnabled(cfg)) continue;
            output.Add(new Agent
            {
                Card = card,
                Headers = HeadersOf(cfg),
                Timeout = LongOf(cfg, "timeout"),
                PollEvery = LongOf(cfg, "pollEvery"),
            });
        }
        return output;
    }

    // -----------------------------------------------------------------------
    // JSON-RPC transport (single POST) + card fetch. Reuses McpSource's ${ENV}
    // header expansion, timeout handling, and non-2xx → error mapping.
    // -----------------------------------------------------------------------

    /// <summary>POST one JSON-RPC 2.0 request and return <c>result</c> (throws on error/non-2xx).</summary>
    private static async Task<A2ATask?> JsonRpcAsync(
        string endpoint, string method, object? @params,
        IDictionary<string, string> headers, long timeout, CancellationToken outer)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(outer);
        cts.CancelAfter(TimeSpan.FromMilliseconds(timeout));

        var body = Json.Stringify(new Dictionary<string, object?>
        {
            ["jsonrpc"] = "2.0",
            ["id"] = Guid.NewGuid().ToString(),
            ["method"] = method,
            ["params"] = @params,
        });

        using var req = new HttpRequestMessage(HttpMethod.Post, endpoint)
        {
            Content = new StringContent(body, Encoding.UTF8),
        };
        req.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json") { CharSet = null };
        foreach (var (k, v) in headers)
        {
            if (!req.Headers.TryAddWithoutValidation(k, v))
                req.Content.Headers.TryAddWithoutValidation(k, v);
        }

        using var res = await Client.SendAsync(req, cts.Token).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode)
            throw new Exception($"HTTP {(int)res.StatusCode}: {text}");

        JsonElement root;
        try
        {
            using var doc = JsonDocument.Parse(text);
            root = doc.RootElement.Clone();
        }
        catch
        {
            return null; // safeParse → undefined → result undefined
        }

        if (root.ValueKind == JsonValueKind.Object
            && root.TryGetProperty("error", out var err)
            && err.ValueKind is not JsonValueKind.Null and not JsonValueKind.Undefined)
        {
            string? message = null;
            if (err.ValueKind == JsonValueKind.Object
                && err.TryGetProperty("message", out var m) && m.ValueKind == JsonValueKind.String)
                message = m.GetString();
            if (message == null)
            {
                var code = err.ValueKind == JsonValueKind.Object && err.TryGetProperty("code", out var c)
                    ? c.ToString()
                    : "";
                message = $"JSON-RPC error {code}".Trim();
            }
            throw new Exception(message);
        }

        if (root.ValueKind == JsonValueKind.Object && root.TryGetProperty("result", out var result))
            return result.Deserialize<A2ATask>(JsonOpts);
        return null;
    }

    /// <summary>Fetch and parse the Agent Card at its URL (GET).</summary>
    private static async Task<AgentCard> FetchCardAsync(
        string cardUrl, IDictionary<string, string> headers, long timeout, CancellationToken outer)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(outer);
        cts.CancelAfter(TimeSpan.FromMilliseconds(timeout));

        using var req = new HttpRequestMessage(HttpMethod.Get, cardUrl);
        foreach (var (k, v) in headers) req.Headers.TryAddWithoutValidation(k, v);

        using var res = await Client.SendAsync(req, cts.Token).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode)
            throw new Exception($"HTTP {(int)res.StatusCode}: {text}");
        return JsonSerializer.Deserialize<AgentCard>(text, JsonOpts) ?? new AgentCard();
    }

    /// <summary>Abortable sleep — resolves early (without error) if <paramref name="token"/> fires.</summary>
    private static async Task SleepAsync(long ms, CancellationToken token)
    {
        try { await Task.Delay(TimeSpan.FromMilliseconds(ms), token).ConfigureAwait(false); }
        catch (OperationCanceledException) { /* resolve early, no error */ }
    }

    /// <summary>Concatenate a Task's text output: artifact text parts, else last agent message.</summary>
    private static string ExtractOutput(A2ATask task)
    {
        var parts = new List<string>();
        foreach (var artifact in task.Artifacts ?? new List<A2AArtifact>())
            foreach (var part in artifact.Parts ?? new List<A2APart>())
                if (part.Kind == "text" && part.Text != null) parts.Add(part.Text);
        if (parts.Count > 0) return string.Join("\n", parts);

        // Fallback: the last agent message in history.
        var history = task.History ?? new List<A2AMessage>();
        for (var i = history.Count - 1; i >= 0; i--)
        {
            var msg = history[i];
            if (msg.Role != "agent") continue;
            var text = string.Join("\n", (msg.Parts ?? new List<A2APart>())
                .Where(p => p.Kind == "text" && p.Text != null)
                .Select(p => p.Text));
            if (text.Length > 0) return text;
        }
        return "";
    }

    /// <summary>Text of a Task's status.message (used for failed/canceled error output).</summary>
    private static string StatusMessageText(A2ATask task)
    {
        var msg = task.Status?.Message;
        if (msg == null) return "";
        return string.Join("\n", (msg.Parts ?? new List<A2APart>())
            .Where(p => p.Kind == "text" && p.Text != null)
            .Select(p => p.Text));
    }

    // -----------------------------------------------------------------------
    // Skill → Tool.
    // -----------------------------------------------------------------------

    private static Dictionary<string, object?> TaskSchema() => new()
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?>
        {
            ["task"] = new Dictionary<string, object?>
            {
                ["type"] = "string",
                ["description"] = "The task to send to the agent, in natural language.",
            },
        },
        ["required"] = new List<object?> { "task" },
        ["additionalProperties"] = false,
    };

    /// <summary>
    /// Resolve an Agent to its tools: fetch the card, read <c>skills[]</c>, and produce
    /// one Tool per skill. The agent name prefix comes from the card's <c>name</c>.
    /// </summary>
    public static async Task<List<ITool>> AgentTools(Agent ag)
    {
        var timeout = ag.Timeout ?? DefaultTimeout;
        var headers = McpSource.ExpandEnvHeaders(ag.Headers) ?? new Dictionary<string, string>();
        var card = await FetchCardAsync(ag.Card, headers, timeout, default).ConfigureAwait(false);
        var agentName = card.Name ?? "agent";
        // The card's `url` is the JSON-RPC endpoint; fall back to the card origin.
        var endpoint = card.Url ?? new Uri(ag.Card).GetLeftPart(UriPartial.Authority);
        return (card.Skills ?? new List<A2ASkill>())
            .Select(skill => (ITool)new A2ATool(agentName, endpoint, skill, ag))
            .ToList();
    }

    /// <summary>One <c>source:"a2a"</c> Tool for a single advertised skill of an agent.</summary>
    private sealed class A2ATool : ITool
    {
        private readonly string _agentName;
        private readonly string _endpoint;
        private readonly Agent _ag;
        private readonly long _timeout;
        private readonly long _pollEvery;

        public string Name { get; }
        public string Description { get; }
        public IDictionary<string, object?> InputSchema { get; }
        public string Source => "a2a";

        public A2ATool(string agentName, string endpoint, A2ASkill skill, Agent ag)
        {
            _agentName = agentName;
            _endpoint = endpoint;
            _ag = ag;
            _timeout = ag.Timeout ?? DefaultTimeout;
            _pollEvery = ag.PollEvery ?? DefaultPollEvery;

            var skillId = skill.Id ?? skill.Name ?? "";
            Name = Tools.Sanitize(agentName) + "_" + Tools.Sanitize(skillId);
            Description = skill.Description ?? skill.Name ?? skillId;
            InputSchema = TaskSchema();
        }

        public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            var sw = Stopwatch.StartNew();
            var budget = ctx?.TimeoutMs ?? _timeout;
            var token = ctx?.CancellationToken ?? default;
            var headers = McpSource.ExpandEnvHeaders(_ag.Headers) ?? new Dictionary<string, string>();
            var taskText = (args ?? new Dictionary<string, object?>()).Get("task")?.ToString() ?? "";
            var polls = 0;
            var taskId = "";
            var state = "submitted";

            Dictionary<string, object?> Meta() => new()
            {
                ["agent"] = _agentName,
                ["taskId"] = taskId,
                ["state"] = state,
                ["polls"] = polls,
                ["ms"] = sw.ElapsedMilliseconds,
            };

            try
            {
                // 1. SendMessage (non-blocking) → a submitted Task.
                var task = await JsonRpcAsync(_endpoint, "SendMessage", new Dictionary<string, object?>
                {
                    ["message"] = new Dictionary<string, object?>
                    {
                        ["role"] = "user",
                        ["messageId"] = Guid.NewGuid().ToString(),
                        ["parts"] = new List<object?>
                        {
                            new Dictionary<string, object?> { ["kind"] = "text", ["text"] = taskText },
                        },
                    },
                    ["configuration"] = new Dictionary<string, object?> { ["blocking"] = false },
                }, headers, budget, token).ConfigureAwait(false);

                taskId = task?.Id ?? "";
                state = task?.Status?.State ?? "submitted";

                // 2. Poll GetTask until terminal / timeout / cancel.
                while (!Terminal.Contains(state))
                {
                    if (token.IsCancellationRequested)
                    {
                        state = "canceled";
                        return new ToolResult($"A2A task {taskId} canceled", true, Meta());
                    }
                    if (sw.ElapsedMilliseconds >= budget)
                    {
                        return new ToolResult(
                            $"A2A task {taskId} timed out after {budget}ms (state={state})", true, Meta());
                    }
                    await SleepAsync(_pollEvery, token).ConfigureAwait(false);
                    // Cancelled during the wait → stop before another GetTask.
                    if (token.IsCancellationRequested)
                    {
                        state = "canceled";
                        return new ToolResult($"A2A task {taskId} canceled", true, Meta());
                    }
                    task = await JsonRpcAsync(_endpoint, "GetTask",
                        new Dictionary<string, object?> { ["id"] = taskId }, headers, budget, token)
                        .ConfigureAwait(false);
                    polls++;
                    state = task?.Status?.State ?? state;
                }

                // 3. Map the terminal Task → ToolResult.
                if (state == "completed")
                    return new ToolResult(ExtractOutput(task!), false, Meta());

                var detail = task != null ? StatusMessageText(task) : "";
                return new ToolResult(
                    $"A2A task {taskId} {state}" + (detail.Length > 0 ? $": {detail}" : ""),
                    true, Meta());
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                // Cancellation raced an in-flight SendMessage/GetTask call — the request's
                // own linked CancellationTokenSource threw before our explicit checks ran.
                state = "canceled";
                return new ToolResult($"A2A task {taskId} canceled", true, Meta());
            }
            catch (Exception e)
            {
                return new ToolResult(e.Message ?? e.ToString(), true, Meta());
            }
        }
    }
}
