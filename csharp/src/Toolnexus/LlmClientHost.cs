using System.Runtime.CompilerServices;

namespace Toolnexus;

/// <summary>
/// (SPEC §4C prompt caching) Configures <see cref="LlmClient.Options.PromptCache"/>.
/// </summary>
public sealed class PromptCacheOptions
{
    /// <summary>Groups requests that share a prefix (one per session). Sent as openai
    /// <c>prompt_cache_key</c>; ignored by anthropic, which caches by prefix.</summary>
    public string? Key { get; set; }

    /// <summary>Ask for the provider's longer cache lifetime where one exists (anthropic
    /// <c>ttl:"1h"</c>; default is the provider's short lifetime).</summary>
    public bool LongTtl { get; set; }
}

/// <summary>Where a tool sits in a <see cref="ToolTierOptions"/> plan.</summary>
public enum ToolTier
{
    /// <summary>Sent with its full schema on every request.</summary>
    Core,

    /// <summary>Named with its first description line inside <c>tool_search</c>'s description.</summary>
    Listed,

    /// <summary>Named only inside <c>tool_search</c>'s description (or not at all when registered late).</summary>
    Deferred,
}

/// <summary>
/// (SPEC §4C tool tiers) Configures <see cref="LlmClient.Options.ToolTiers"/>. Only CORE tools are
/// sent with full schemas; the rest are loaded by the model through a <c>tool_search</c> tool whose
/// result carries the schemas as a message, so the tools array (and the cached prefix) never
/// changes mid-session.
/// </summary>
public sealed class ToolTierOptions
{
    /// <summary>Tools always sent with their full schema.</summary>
    public IReadOnlyList<string>? Core { get; set; }

    /// <summary>Tools named only. Every other tool is listed (name + one line). A tool registered
    /// after the plan is frozen is deferred and is found by search.</summary>
    public IReadOnlyList<string>? Deferred { get; set; }

    /// <summary>Caps the tokens sent up front: the core schemas plus the <c>tool_search</c> and
    /// <c>call_tool</c> definitions. 0 ⇒ 5% of <see cref="ContextWindow"/>; both 0 ⇒ no budget.
    /// Over budget, the least-used core tools move to listed, then the least-used listed tools to
    /// deferred, until it fits.</summary>
    public int BudgetTokens { get; set; }

    /// <summary>The model's context window in tokens (for the default 5% budget).</summary>
    public int ContextWindow { get; set; }

    /// <summary>The host's per-tool usage stat (higher = more used), read only when the plan is
    /// built. Null ⇒ all zero (ties break by larger schema first, then name).</summary>
    public Func<string, int>? UsageCount { get; set; }

    /// <summary>Token estimate of a JSON-encodable value; default <c>ceil(bytes/4)</c>.</summary>
    public Func<object?, int>? CountTokens { get; set; }
}

/// <summary>(SPEC §4C) One model a provider lists, for model switching.</summary>
/// <param name="Id">The model id.</param>
/// <param name="Name">The provider's display name, when it gives one.</param>
/// <param name="ContextLength">The context window in tokens when the provider reports it
/// (OpenRouter does; OpenAI and Anthropic do not) — 0 = unknown.</param>
public sealed record ModelInfo(string Id, string? Name = null, int ContextLength = 0);

public sealed partial class LlmClient
{
    // ---------------------------------------------------------------- API key by host (security)

    /// <summary>
    /// Pick the API key. An explicit <c>ApiKey</c> is used as-is. Otherwise the key comes ONLY from
    /// <c>ApiKeyEnv</c>, or else from the env var that belongs to the BaseUrl's host, so a key is
    /// never sent to another provider's host (SPEC §4C). The value is never logged.
    /// </summary>
    internal string ResolveKey() => ResolveKey(_opts);

    internal static string ResolveKey(Options opts)
    {
        if (!string.IsNullOrEmpty(opts.ApiKey)) return opts.ApiKey!;
        var envName = !string.IsNullOrEmpty(opts.ApiKeyEnv) ? opts.ApiKeyEnv! : KeyEnvForBaseUrl(opts.BaseUrl);
        if (envName == null)
            throw new InvalidOperationException(
                "No API key: set ApiKey or ApiKeyEnv (provider env vars are only used for https://api.openai.com, "
                + $"https://api.anthropic.com and https://openrouter.ai, never for \"{opts.BaseUrl}\")");
        var key = Environment.GetEnvironmentVariable(envName);
        if (string.IsNullOrEmpty(key))
            throw new InvalidOperationException($"No API key: set ApiKey or {envName}");
        return key;
    }

    /// <summary>The env var whose key may be sent to <paramref name="baseUrl"/>'s host, or null when none may.</summary>
    internal static string? KeyEnvForBaseUrl(string? baseUrl)
    {
        var h = HostOf(baseUrl);
        if (h == "api.openai.com") return "OPENAI_API_KEY";
        if (h == "api.anthropic.com") return "ANTHROPIC_API_KEY";
        if (h == "openrouter.ai" || h.EndsWith(".openrouter.ai", StringComparison.Ordinal)) return "OPENROUTER_API_KEY";
        return null;
    }

    /// <summary>The lower-cased host of an https URL; "" otherwise (a provider key never goes over plain http).</summary>
    private static string HostOf(string? baseUrl)
    {
        if (!Uri.TryCreate(baseUrl ?? "", UriKind.Absolute, out var u) || u.Scheme != "https") return "";
        return u.Host.ToLowerInvariant();
    }

    // ---------------------------------------------------------------- prompt caching

    /// <summary>
    /// Mark the cacheable prefix of an assembled body, in place.
    /// anthropic: <c>cache_control</c> on the last tool, the system prompt (a string becomes one
    /// text block) and the last content block of the latest message (3 of the 4 allowed). The
    /// transcript's own dictionaries are never mutated: touched entries are copied.
    /// openai: <c>prompt_cache_key = Key</c> when set. gemini: nothing yet.
    /// </summary>
    internal static void ApplyPromptCache(Dictionary<string, object?> body, string style, PromptCacheOptions o)
    {
        if (style == "anthropic")
        {
            var mark = new Dictionary<string, object?> { ["type"] = "ephemeral" };
            if (o.LongTtl) mark["ttl"] = "1h";
            if (body.Get("tools") is System.Collections.IEnumerable tools and not string)
            {
                var list = tools.Cast<object?>().ToList();
                if (list.Count > 0)
                {
                    list[^1] = WithCacheControl(list[^1], mark);
                    body["tools"] = list;
                }
            }
            switch (body.Get("system"))
            {
                case string sys when sys.Length > 0:
                    body["system"] = new List<object?>
                    {
                        new Dictionary<string, object?> { ["type"] = "text", ["text"] = sys, ["cache_control"] = mark },
                    };
                    break;
                case List<object?> { Count: > 0 } sysBlocks:
                    var sb = new List<object?>(sysBlocks);
                    sb[^1] = WithCacheControl(sb[^1], mark);
                    body["system"] = sb;
                    break;
            }
            if (body.Get("messages") is List<object?> { Count: > 0 } msgs)
            {
                var outMsgs = new List<object?>(msgs);
                if (outMsgs[^1] is IDictionary<string, object?> last)
                {
                    var m = new Dictionary<string, object?>(last);
                    switch (m.Get("content"))
                    {
                        case string content:
                            m["content"] = new List<object?>
                            {
                                new Dictionary<string, object?> { ["type"] = "text", ["text"] = content, ["cache_control"] = mark },
                            };
                            break;
                        case List<object?> { Count: > 0 } blocks:
                            var nb = new List<object?>(blocks);
                            nb[^1] = WithCacheControl(nb[^1], mark);
                            m["content"] = nb;
                            break;
                    }
                    outMsgs[^1] = m;
                }
                body["messages"] = outMsgs;
            }
        }
        else if (style == "openai" && !string.IsNullOrEmpty(o.Key))
        {
            body["prompt_cache_key"] = o.Key;
        }
    }

    private static object? WithCacheControl(object? v, Dictionary<string, object?> mark)
    {
        if (v is not IDictionary<string, object?> d) return v;
        return new Dictionary<string, object?>(d) { ["cache_control"] = mark };
    }

    // ---------------------------------------------------------------- tool tiers

    internal sealed class ToolPlan
    {
        public Dictionary<string, ToolTier> Tier = new();
        public List<string> Core = new();
        public List<string> Listed = new();
        public List<string> Deferred = new();
        public ITool Search = null!;
        public ITool Call = null!;
    }

    /// <summary>The frozen plan per toolkit (weakly keyed, so a dropped toolkit takes its plan with it).</summary>
    private readonly ConditionalWeakTable<Toolkit, ToolPlan> _plans = new();
    private readonly object _plansGate = new();

    /// <summary>The frozen tier plan for <paramref name="toolkit"/> (built on first use), for a host
    /// UI or log: tool name → tier. Null when <see cref="Options.ToolTiers"/> is not set.</summary>
    public IReadOnlyDictionary<string, ToolTier>? ToolTierPlan(Toolkit toolkit)
    {
        var p = Plan(toolkit);
        return p == null ? null : new Dictionary<string, ToolTier>(p.Tier);
    }

    /// <summary>Forget <paramref name="toolkit"/>'s plan so the next request rebuilds it — call it at a
    /// new session start (e.g. after updating usage stats). Mid-session it breaks the cached prefix.</summary>
    public void ResetToolTiers(Toolkit toolkit)
    {
        lock (_plansGate) _plans.Remove(toolkit);
    }

    private ToolPlan? Plan(Toolkit? toolkit)
    {
        var o = _opts.ToolTiers;
        if (o == null || toolkit == null) return null;
        lock (_plansGate)
        {
            if (_plans.TryGetValue(toolkit, out var p)) return p;
            p = BuildPlan(o, toolkit.Tools());
            _plans.Add(toolkit, p);
            return p;
        }
    }

    internal static int DefaultCountTokens(object? v) => (global::System.Text.Encoding.UTF8.GetByteCount(Json.Stringify(v)) + 3) / 4;

    internal static Dictionary<string, object?> ToolDef(ITool t) => new()
    {
        // Sorted keys: the same bytes the Go reference (a sorted map) produces.
        ["description"] = t.Description,
        ["inputSchema"] = t.InputSchema,
        ["name"] = t.Name,
    };

    /// <summary>Build a tier plan over <paramref name="tools"/> (internal for tests).</summary>
    internal static ToolPlan BuildPlan(ToolTierOptions o, List<ITool> tools)
    {
        var count = o.CountTokens ?? DefaultCountTokens;
        int Usage(string n) => o.UsageCount?.Invoke(n) ?? 0;
        var byName = new Dictionary<string, ITool>();
        foreach (var t in tools) byName.TryAdd(t.Name, t);
        var p = new ToolPlan();
        foreach (var t in tools) p.Tier[t.Name] = ToolTier.Listed;
        foreach (var n in o.Deferred ?? Array.Empty<string>())
            if (p.Tier.ContainsKey(n)) p.Tier[n] = ToolTier.Deferred;
        foreach (var n in o.Core ?? Array.Empty<string>())
            if (p.Tier.ContainsKey(n)) p.Tier[n] = ToolTier.Core;
        Rebuild(p, byName);

        var budget = o.BudgetTokens;
        if (budget == 0 && o.ContextWindow > 0) budget = o.ContextWindow / 20;
        if (budget > 0)
        {
            int Cost()
            {
                var n = count(ToolDef(p.Search)) + count(ToolDef(p.Call));
                foreach (var name in p.Core) n += count(ToolDef(byName[name]));
                return n;
            }
            // least used first; ties: bigger schema first, then name
            List<string> Order(List<string> names) => names
                .OrderBy(Usage)
                .ThenByDescending(n => count(ToolDef(byName[n])))
                .ThenBy(n => n, StringComparer.Ordinal)
                .ToList();
            foreach (var n in Order(p.Core))
            {
                if (Cost() <= budget) break;
                p.Tier[n] = ToolTier.Listed;
                Rebuild(p, byName);
            }
            foreach (var n in Order(p.Listed))
            {
                if (Cost() <= budget) break;
                p.Tier[n] = ToolTier.Deferred;
                Rebuild(p, byName);
            }
        }
        return p;
    }

    internal static string FirstLine(string s)
    {
        var i = s.IndexOf('\n');
        if (i >= 0) s = s[..i];
        // Longest valid UTF-8 prefix of at most 120 BYTES (as Go): never split a character.
        var bytes = global::System.Text.Encoding.UTF8.GetBytes(s);
        if (bytes.Length <= 120) return s;
        var n = 120;
        while (n > 0 && (bytes[n] & 0xC0) == 0x80) n--;
        return global::System.Text.Encoding.UTF8.GetString(bytes, 0, n) + "…";
    }

    /// <summary>Recompute the sorted tier lists and the tool_search description.</summary>
    private static void Rebuild(ToolPlan p, Dictionary<string, ITool> byName)
    {
        List<string> Of(ToolTier t) => p.Tier.Where(kv => kv.Value == t).Select(kv => kv.Key)
            .OrderBy(n => n, StringComparer.Ordinal).ToList();
        p.Core = Of(ToolTier.Core);
        p.Listed = Of(ToolTier.Listed);
        p.Deferred = Of(ToolTier.Deferred);
        var sb = new global::System.Text.StringBuilder(
            "Load the full schema of tools that are not shown in full, then call them by name (or with call_tool). "
            + "Query by keywords, or \"select:a,b\" for exact names.");
        if (p.Listed.Count > 0)
        {
            sb.Append("\n\nAvailable tools:");
            foreach (var n in p.Listed)
            {
                sb.Append("\n- ").Append(n);
                var d = FirstLine(byName[n].Description ?? "");
                if (d.Length > 0) sb.Append(" — ").Append(d);
            }
        }
        if (p.Deferred.Count > 0) sb.Append("\n\nMore tools (name only): ").Append(string.Join(", ", p.Deferred));
        p.Search = new MetaTool("tool_search", sb.ToString(), new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["query"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "Keywords, or select:name1,name2" },
                ["limit"] = new Dictionary<string, object?> { ["type"] = "number", ["description"] = "Maximum results for a keyword query (default 5)" },
            },
            ["required"] = new List<object?> { "query" },
            ["additionalProperties"] = false,
        }, new ToolHints(ReadOnly: true));
        p.Call = new MetaTool("call_tool", "Call a tool loaded with tool_search, by name, with its arguments.", new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["name"] = new Dictionary<string, object?> { ["type"] = "string" },
                ["arguments"] = new Dictionary<string, object?> { ["type"] = "object" },
            },
            ["required"] = new List<object?> { "name" },
            ["additionalProperties"] = false,
        }, null);
    }

    /// <summary>The engine-internal tool_search / call_tool definitions. The loop intercepts both;
    /// executing one directly is an error.</summary>
    private sealed class MetaTool(string name, string description, IDictionary<string, object?> schema, ToolHints? hints) : ITool
    {
        public string Name => name;
        public string Description => description;
        public IDictionary<string, object?> InputSchema => schema;
        public string Source => "builtin";
        public ToolHints? Hints => hints;

        public Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
            => Task.FromResult(ToolResult.Error($"{name} is handled by the client loop"));
    }

    /// <summary>The tools array for a request: core tools (sorted) then tool_search and call_tool.
    /// Without tiers it is every tool, as before.</summary>
    internal List<ITool> RequestTools(Toolkit toolkit)
    {
        var p = Plan(toolkit);
        if (p == null) return toolkit.Tools();
        var outList = new List<ITool>(p.Core.Count + 2);
        foreach (var n in p.Core)
            if (toolkit.Get(n) is { } t) outList.Add(t);
        outList.Add(p.Search);
        outList.Add(p.Call);
        return outList;
    }

    /// <summary>Map <c>call_tool{name, arguments}</c> to the real call. Wrapped = false when tiers are
    /// off or the name is not call_tool.</summary>
    private (string Name, IDictionary<string, object?> Args, bool Wrapped) UnwrapCall(string name, IDictionary<string, object?> args)
    {
        if (_opts.ToolTiers == null || name != "call_tool") return (name, args, false);
        var real = args.Get("name") as string ?? "";
        var inner = args.Get("arguments") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
        return (real, inner, true);
    }

    /// <summary>Run tool_search against the toolkit (every tool, any tier).</summary>
    internal ToolResult ToolSearch(Toolkit toolkit, IDictionary<string, object?> args)
    {
        var q = (args.Get("query") as string ?? "").Trim();
        if (q.Length == 0) return ToolResult.Error("tool_search: query is required");
        var picked = new List<ITool>();
        if (q.StartsWith("select:", StringComparison.Ordinal))
        {
            var missing = new List<string>();
            foreach (var raw in q["select:".Length..].Split(','))
            {
                var n = raw.Trim();
                if (n.Length == 0) continue;
                if (toolkit.Get(n) is { } t) picked.Add(t);
                else missing.Add(n);
            }
            if (missing.Count > 0 && picked.Count == 0)
                return ToolResult.Error("tool_search: no such tool: " + string.Join(", ", missing));
        }
        else
        {
            var limit = 5;
            if (args.Get("limit") is { } lv and not bool and not string)
            {
                var l = Convert.ToDouble(lv, global::System.Globalization.CultureInfo.InvariantCulture);
                if (l >= 1) limit = (int)l;
            }
            var terms = q.ToLowerInvariant().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            var hits = new List<(ITool T, int Score)>();
            foreach (var t in toolkit.Tools())
            {
                var name = t.Name.ToLowerInvariant();
                var desc = (t.Description ?? "").ToLowerInvariant();
                var score = 0;
                foreach (var term in terms)
                {
                    if (name.Contains(term, StringComparison.Ordinal)) score += 3;
                    if (desc.Contains(term, StringComparison.Ordinal)) score++;
                }
                if (score > 0) hits.Add((t, score));
            }
            picked.AddRange(hits.OrderByDescending(h => h.Score).ThenBy(h => h.T.Name, StringComparer.Ordinal)
                .Take(limit).Select(h => h.T));
        }
        if (picked.Count == 0)
            return ToolResult.Ok("No matching tools.", new Dictionary<string, object?> { ["count"] = 0L });
        var defs = picked.Select(t => (object?)ToolDef(t)).ToList();
        return ToolResult.Ok("Loaded tools (call by name, or with call_tool):\n" + Json.GoIndent(defs),
            new Dictionary<string, object?>
            {
                ["count"] = (long)picked.Count,
                ["tools"] = picked.Select(t => (object?)t.Name).ToList(),
            });
    }

    /// <summary>The stream's tool_call event, naming the REAL tool for a call_tool call (<c>Via = "call_tool"</c>).</summary>
    private StreamEvent CallEvent(string? id, string name, IDictionary<string, object?> args)
    {
        var (real, inner, wrapped) = UnwrapCall(name, args);
        return new StreamEvent(StreamKind.ToolCall, Id: id, Name: real, Args: inner, Via: wrapped ? "call_tool" : null);
    }

    /// <summary>The stream's tool_result event, naming the REAL tool for a call_tool call.</summary>
    private StreamEvent ResultEvent(string? id, string name, IDictionary<string, object?> args, ToolResult result)
    {
        var (real, _, wrapped) = UnwrapCall(name, args);
        return new StreamEvent(StreamKind.ToolResult, Id: id, Name: real, Output: result.Output, IsError: result.IsError,
            Metadata: result.Metadata, Via: wrapped ? "call_tool" : null);
    }

    // ---------------------------------------------------------------- listModels

    /// <summary>
    /// (SPEC §4C) The models a provider offers, for model switching. Same key rules as the client
    /// (<c>ApiKey</c>, <c>ApiKeyEnv</c>, or the host's own env var) and the same HTTP seam
    /// (<c>HttpClient</c>/<c>HttpHandler</c>). openai style: <c>GET {base}/models</c> (OpenRouter's
    /// <c>context_length</c> read when present). anthropic style: <c>GET {base}/v1/models</c>,
    /// following <c>has_more</c>/<c>last_id</c>. One GET per page, no retries. Sorted by id.
    /// </summary>
    public static async Task<List<ModelInfo>> ListModelsAsync(Options opts, CancellationToken cancellationToken = default)
    {
        var c = new LlmClient(opts);
        var key = c.ResolveKey();
        var b = StripTrailingSlash(opts.BaseUrl);
        var output = new List<ModelInfo>();
        if (opts.Style == "anthropic")
        {
            var endpoint = b.EndsWith("/v1", StringComparison.Ordinal) ? b + "/models" : b + "/v1/models";
            var after = "";
            for (var page = 0; page < 100; page++)
            {
                var u = endpoint + "?limit=1000";
                if (after.Length > 0) u += "&after_id=" + Uri.EscapeDataString(after);
                var body = await c.GetJsonAsync(u, new Dictionary<string, string> { ["x-api-key"] = key, ["anthropic-version"] = "2023-06-01" }, cancellationToken).ConfigureAwait(false);
                foreach (var m in (body.Get("data") as List<object?> ?? new()).OfType<IDictionary<string, object?>>())
                    output.Add(new ModelInfo(m.Get("id")?.ToString() ?? "", m.Get("display_name") as string));
                var last = body.Get("last_id") as string ?? "";
                if (body.Get("has_more") is not true || last.Length == 0) break;
                after = last;
            }
        }
        else
        {
            var body = await c.GetJsonAsync(b + "/models", new Dictionary<string, string> { ["Authorization"] = "Bearer " + key }, cancellationToken).ConfigureAwait(false);
            foreach (var m in (body.Get("data") as List<object?> ?? new()).OfType<IDictionary<string, object?>>())
                output.Add(new ModelInfo(m.Get("id")?.ToString() ?? "", m.Get("name") as string, (int)AsLong(m.Get("context_length"))));
        }
        output.Sort((x, y) => string.CompareOrdinal(x.Id, y.Id));
        return output;
    }

    private async Task<Dictionary<string, object?>> GetJsonAsync(string url, Dictionary<string, string> headers, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        foreach (var (k, v) in headers) req.Headers.TryAddWithoutValidation(k, v);
        if (_opts.Headers != null)
            foreach (var (k, v) in _opts.Headers) req.Headers.TryAddWithoutValidation(k, v);
        using var res = await _http.SendAsync(req, ct).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        var status = (int)res.StatusCode;
        if (status is < 200 or >= 300)
            throw new ProviderException(status, RedactBody(status, text, cap: false), RawRetryAfter(res),
                "list models: " + ProviderMessage(status, text));
        return Json.ToMap(text);
    }
}
