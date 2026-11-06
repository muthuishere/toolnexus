using System.Text;
using System.Text.Json.Nodes;

namespace Mcp2026;

/// <summary>Answers one MRTR input request (stands in for toolnexus SPEC §10 waitFor).</summary>
public delegate Task<JsonObject> Responder(string key, JsonObject request);

/// <summary>A stateless MCP 2026-07-28 client: Streamable HTTP (<c>new McpClient(url)</c>) or stdio
/// (<see cref="StartStdio"/>). The same API either way.</summary>
public sealed partial class McpClient : IAsyncDisposable
{
    /// <summary>Bounds every request made through the default HttpClient (a hung server cannot hang the caller).</summary>
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(30);

    public string Url { get; set; }
    public string Version { get; set; } = Mcp.ProtocolVersion;
    public JsonObject Info { get; set; } = new() { ["name"] = "toolnexus-spike-csharp", ["version"] = "0.0.1" };
    public JsonObject Caps { get; set; } = new() { ["elicitation"] = new JsonObject { ["form"] = new JsonObject() } };
    /// <summary>The HttpClient for MCP requests; default: 30 s timeout, trusts in-process DevAuthServers.</summary>
    public HttpClient Http { get; set; } = DevTrust.Shared;
    /// <summary>null = no authorization (HTTP only; stdio servers take credentials from their env).</summary>
    public OAuth? Auth { get; set; }

    internal StdioConn? Stdio;
    internal Dictionary<string, string>? HeaderOverride; // self-test forges headers (negative cases only)
    private long _nextId;

    public McpClient(string url) => Url = url;

    /// <summary>Sends one request; params gets the per-request _meta merged in.
    /// Retry rule (spec issue #3394): a request is re-sent ONLY after a 401, or a 403 insufficient_scope,
    /// carrying WWW-Authenticate — the server rejected it before dispatch, so the tool never ran. A lost
    /// connection, timeout, 5xx or unreadable response is thrown and NEVER retried.</summary>
    public async Task<JsonObject> CallAsync(string method, JsonObject? @params = null, CancellationToken ct = default)
    {
        @params ??= [];
        @params["_meta"] = new JsonObject
        {
            [Mcp.MetaVersion] = Version, [Mcp.MetaClientCaps] = Caps.DeepClone(), [Mcp.MetaClientInfo] = Info.DeepClone(),
        };
        var id = Interlocked.Increment(ref _nextId);
        var body = J.Serialize(new JsonObject { ["jsonrpc"] = "2.0", ["id"] = id, ["method"] = method, ["params"] = @params.DeepClone() });
        var msg = Stdio != null ? await Stdio.RoundTripAsync(id, body, ct) : await HttpRoundTripAsync(method, @params, body, ct);
        if (msg["error"] is JsonObject e)
            throw new McpRpcException((int)(J.Num(e["code"]) ?? 0), J.Str(e["message"], ""), e["data"]?.DeepClone());
        if (msg["result"] is not JsonObject res) throw new McpException("response has neither result nor error");
        res = (JsonObject)res.DeepClone();
        res["resultType"] ??= "complete"; // earlier-protocol servers
        return res;
    }

    private async Task<JsonObject> HttpRoundTripAsync(string method, JsonObject @params, string body, CancellationToken ct)
    {
        HttpResponseMessage resp;
        for (var attempt = 0; ; attempt++)
        {
            var sent = Auth != null ? await Auth.BearerAsync(Url, ct) : "";
            try { resp = await PostAsync(method, @params, body, sent, ct); }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
            {
                throw new McpException($"{method}: {ex.Message}", ex); // never retried: the server may have executed it
            }
            var www = resp.Headers.NonValidated.TryGetValues("WWW-Authenticate", out var v) ? string.Join(", ", v) : "";
            var st = (int)resp.StatusCode;
            if (Auth == null || (st != 401 && st != 403) || www == "") break;
            resp.Dispose();
            if (attempt >= 3) throw new McpException($"authorization failed: HTTP {st} after {attempt + 1} attempts"); // spec: Step-Up retry limit
            await Auth.ChallengeAsync(Url, st, www, sent, () => CallAsync(method, @params, ct), ct);
        }
        using (resp)
        {
            var status = (int)resp.StatusCode;
            string text;
            try { text = await resp.Content.ReadAsStringAsync(ct); }
            catch (Exception ex) when (ex is HttpRequestException or IOException or TaskCanceledException)
            {
                throw new McpException($"HTTP {status}: {ex.Message}", ex);
            }
            JsonObject? msg = null;
            if (resp.Content.Headers.ContentType?.MediaType == "text/event-stream")
            {
                // Take the last data: payload that carries a result or error (the final response).
                foreach (var line in text.Split('\n'))
                    if (line.StartsWith("data:") && J.ParseObject(line[5..].TrimEnd('\r')) is { } m && (m["result"] != null || m["error"] != null))
                        msg = m;
            }
            else msg = J.ParseObject(text) ?? throw new McpException($"HTTP {status}: response is not a JSON object");
            if (msg == null || (msg["result"] is not JsonObject && msg["error"] is not JsonObject))
                throw new McpException($"HTTP {status}: response has neither result nor error");
            return msg;
        }
    }

    internal static string EncodeHeader(string v)
    {
        var safe = v == v.Trim() && !(v.StartsWith("=?base64?") && v.EndsWith("?=")) && v.All(c => c >= 0x20 && c <= 0x7e);
        return safe ? v : "=?base64?" + Convert.ToBase64String(Encoding.UTF8.GetBytes(v)) + "?=";
    }

    internal Task<HttpResponseMessage> PostAsync(string method, JsonObject @params, string body, string bearer, CancellationToken ct)
    {
        var req = new HttpRequestMessage(HttpMethod.Post, Url) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        req.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream");
        req.Headers.TryAddWithoutValidation("MCP-Protocol-Version", Version);
        req.Headers.TryAddWithoutValidation("Mcp-Method", method);
        if (method is "tools/call" or "prompts/get") req.Headers.TryAddWithoutValidation("Mcp-Name", EncodeHeader(J.Str(@params["name"], "")));
        if (method == "resources/read") req.Headers.TryAddWithoutValidation("Mcp-Name", EncodeHeader(J.Str(@params["uri"], "")));
        foreach (var (k, v) in HeaderOverride ?? [])
        {
            req.Headers.Remove(k);
            req.Headers.TryAddWithoutValidation(k, v);
        }
        if (bearer != "") req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + bearer);
        return Http.SendAsync(req, HttpCompletionOption.ResponseContentRead, ct);
    }

    /// <summary>Runs tools/call, looping over input_required rounds via respond.</summary>
    public Task<JsonObject> CallToolAsync(string name, JsonObject? args = null, Responder? respond = null, CancellationToken ct = default) =>
        CallToolRoundsAsync(name, args ?? [], respond, null, null, 0, ct);

    private async Task<JsonObject> CallToolRoundsAsync(string name, JsonObject args, Responder? respond, JsonObject? responses, JsonNode? state, int round, CancellationToken ct)
    {
        for (; round < 8; round++)
        {
            var p = new JsonObject { ["name"] = name, ["arguments"] = args.DeepClone() };
            if (responses != null) p["inputResponses"] = responses.DeepClone();
            if (state != null) p["requestState"] = state.DeepClone();
            JsonObject res;
            try { res = await CallAsync("tools/call", p, ct); }
            catch (PendingAuthorizationException pe)
            {
                // The paused request got 401/403 before dispatch: the server has NOT run this round.
                // Resume sends exactly this round (same inputResponses/requestState) for the first time —
                // earlier rounds are not replayed and the user is not re-asked.
                var (resp0, st0, r0) = (responses, state, round);
                Auth!.SetRetry(pe.Pending.Id, () => CallToolRoundsAsync(name, args, respond, resp0, st0, r0, CancellationToken.None));
                throw;
            }
            if (J.Str(res["resultType"]) != "input_required") return res;
            state = res["requestState"]?.DeepClone();
            responses = [];
            if (respond == null) throw new McpException($"tools/call {name}: server asked for input but no Responder was given");
            foreach (var (k, v) in res["inputRequests"] as JsonObject ?? [])
            {
                if (v is not JsonObject r) throw new McpException($"tools/call {name}: malformed input request \"{k}\"");
                responses[k] = (await respond(k, (JsonObject)r.DeepClone())).DeepClone();
            }
        }
        throw new McpException($"tools/call {name}: too many input_required rounds");
    }

    /// <summary>Stops a stdio server (close stdin, kill after 2 s); no-op for HTTP clients.</summary>
    public ValueTask DisposeAsync() => Stdio?.DisposeAsync() ?? ValueTask.CompletedTask;
}
