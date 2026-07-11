using System.Globalization;

namespace Toolnexus;

/// <summary>
/// Semantic observability event (SPEC §8). NOT a counter/histogram primitive — a readable record the
/// host can forward anywhere (statsd, logs, OTel, …). The same events also feed the built-in
/// Prometheus registry behind <see cref="LlmClient.Metrics"/>.
///
/// Field names follow idiomatic C# casing (PascalCase), so these are NOT byte-identical across ports —
/// only the Prometheus text from <see cref="LlmClient.Metrics"/> is. <see cref="Event"/> discriminates:
/// <list type="bullet">
///   <item><c>"llm"</c> — <see cref="Model"/>, <see cref="Status"/>, <see cref="Ms"/>, <see cref="PromptTokens"/>, <see cref="CompletionTokens"/></item>
///   <item><c>"tool"</c> — <see cref="Tool"/>, <see cref="Source"/>, <see cref="IsError"/>, <see cref="Pending"/>, <see cref="Ms"/></item>
///   <item><c>"run"</c> — <see cref="Model"/>, <see cref="Turns"/>, <see cref="ToolCalls"/>, <see cref="TotalTokens"/>, <see cref="Ms"/>, <see cref="Error"/></item>
/// </list>
/// </summary>
public sealed record MetricEvent
{
    /// <summary>Discriminator: <c>"llm"</c>, <c>"tool"</c>, or <c>"run"</c>.</summary>
    public required string Event { get; init; }

    // llm + run
    public string? Model { get; init; }
    // llm
    public string? Status { get; init; } // "ok" | "error"
    public long PromptTokens { get; init; }
    public long CompletionTokens { get; init; }
    // tool
    public string? Tool { get; init; }
    public string? Source { get; init; }
    public bool IsError { get; init; }
    /// <summary>§10: true iff this tool call suspended (a Request awaiting out-of-band resolution).
    /// A suspension is classified <c>pending</c>, never <see cref="IsError"/> — see the <c>pending</c>
    /// label on <c>toolnexus_tool_calls_total</c>.</summary>
    public bool Pending { get; init; }
    // run
    public int Turns { get; init; }
    public int ToolCalls { get; init; }
    public long TotalTokens { get; init; }
    public string? Error { get; init; }
    // llm + tool + run
    public long Ms { get; init; }

    internal static MetricEvent Llm(string model, string status, long ms, long promptTokens, long completionTokens)
        => new() { Event = "llm", Model = model, Status = status, Ms = ms, PromptTokens = promptTokens, CompletionTokens = completionTokens };

    internal static MetricEvent ToolCall(string tool, string source, bool isError, long ms, bool pending = false)
        => new() { Event = "tool", Tool = tool, Source = source, IsError = isError, Ms = ms, Pending = pending };

    internal static MetricEvent Run(string model, int turns, int toolCalls, long totalTokens, long ms, string? error = null)
        => new() { Event = "run", Model = model, Turns = turns, ToolCalls = toolCalls, TotalTokens = totalTokens, Ms = ms, Error = error };
}

/// <summary>
/// In-memory cumulative registry that turns the same semantic events <c>OnMetric</c> sees into the
/// Prometheus counters/histograms of SPEC §8, and renders the text exposition format by hand (no
/// third-party dependency). Thread-safe. The rendered text is byte-identical across all ports.
/// </summary>
internal sealed class MetricsRegistry
{
    /// <summary>FIXED across all ports for byte-parity of <c>..._duration_seconds</c> histograms. Seconds.</summary>
    private static readonly double[] Buckets = { 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60 };

    /// <summary>Bucket <c>le</c> labels, rendered once with InvariantCulture to match JS <c>String(n)</c>.</summary>
    private static readonly string[] BucketLabels = Buckets
        .Select(b => b.ToString(CultureInfo.InvariantCulture)).ToArray();

    private sealed class Hist
    {
        public readonly long[] Counts = new long[Buckets.Length];
        public double Sum;
        public long Count;
    }

    private readonly object _lock = new();
    private readonly Dictionary<string, long> _llmRequests = new(); // labels: model,status
    private readonly Dictionary<string, long> _llmTokens = new();   // labels: type
    private readonly Dictionary<string, Hist> _llmDuration = new(); // labels: model
    private readonly Dictionary<string, long> _toolCalls = new();   // labels: tool,source,is_error,pending
    private readonly Dictionary<string, Hist> _toolDuration = new();// labels: tool
    private readonly Dictionary<string, long> _runErrors = new();   // labels: model

    public void Record(MetricEvent ev)
    {
        lock (_lock)
        {
            switch (ev.Event)
            {
                case "llm":
                    Inc(_llmRequests, LabelStr(("model", ev.Model ?? ""), ("status", ev.Status ?? "")));
                    if (ev.Status == "ok")
                    {
                        Inc(_llmTokens, LabelStr(("type", "prompt")), ev.PromptTokens);
                        Inc(_llmTokens, LabelStr(("type", "completion")), ev.CompletionTokens);
                    }
                    Observe(_llmDuration, LabelStr(("model", ev.Model ?? "")), ev.Ms / 1000.0);
                    break;
                case "tool":
                    Inc(_toolCalls, LabelStr(("tool", ev.Tool ?? ""), ("source", ev.Source ?? ""), ("is_error", ev.IsError ? "true" : "false"), ("pending", ev.Pending ? "true" : "false")));
                    Observe(_toolDuration, LabelStr(("tool", ev.Tool ?? "")), ev.Ms / 1000.0);
                    break;
                case "run":
                    if (!string.IsNullOrEmpty(ev.Error))
                        Inc(_runErrors, LabelStr(("model", ev.Model ?? "")));
                    break;
            }
        }
    }

    public string Render()
    {
        lock (_lock)
        {
            var outp = new List<string>();
            RenderCounter(outp, "toolnexus_llm_requests_total", "Total LLM requests.", _llmRequests);
            RenderCounter(outp, "toolnexus_llm_tokens_total", "Total tokens, by type.", _llmTokens);
            RenderHistogram(outp, "toolnexus_llm_request_duration_seconds", "LLM request duration in seconds.", _llmDuration);
            RenderCounter(outp, "toolnexus_tool_calls_total", "Total tool calls.", _toolCalls);
            RenderHistogram(outp, "toolnexus_tool_duration_seconds", "Tool execution duration in seconds.", _toolDuration);
            RenderCounter(outp, "toolnexus_run_errors_total", "Total run errors.", _runErrors);
            return string.Join("\n", outp) + "\n";
        }
    }

    private static void RenderCounter(List<string> outp, string name, string help, Dictionary<string, long> m)
    {
        outp.Add($"# HELP {name} {help}");
        outp.Add($"# TYPE {name} counter");
        foreach (var key in Sorted(m.Keys))
            outp.Add(key.Length > 0
                ? $"{name}{{{key}}} {m[key].ToString(CultureInfo.InvariantCulture)}"
                : $"{name} {m[key].ToString(CultureInfo.InvariantCulture)}");
    }

    private static void RenderHistogram(List<string> outp, string name, string help, Dictionary<string, Hist> m)
    {
        outp.Add($"# HELP {name} {help}");
        outp.Add($"# TYPE {name} histogram");
        foreach (var key in Sorted(m.Keys))
        {
            var h = m[key];
            for (var i = 0; i < Buckets.Length; i++)
                outp.Add($"{name}_bucket{{{WithLe(key, BucketLabels[i])}}} {h.Counts[i].ToString(CultureInfo.InvariantCulture)}");
            outp.Add($"{name}_bucket{{{WithLe(key, "+Inf")}}} {h.Count.ToString(CultureInfo.InvariantCulture)}");
            var sum = h.Sum.ToString(CultureInfo.InvariantCulture);
            var count = h.Count.ToString(CultureInfo.InvariantCulture);
            outp.Add(key.Length > 0 ? $"{name}_sum{{{key}}} {sum}" : $"{name}_sum {sum}");
            outp.Add(key.Length > 0 ? $"{name}_count{{{key}}} {count}" : $"{name}_count {count}");
        }
    }

    // ---- primitives (mirror the JS helpers exactly) ----

    private static void Inc(Dictionary<string, long> m, string key, long by = 1)
        => m[key] = (m.TryGetValue(key, out var v) ? v : 0) + by;

    private static void Observe(Dictionary<string, Hist> m, string key, double seconds)
    {
        if (!m.TryGetValue(key, out var h)) { h = new Hist(); m[key] = h; }
        h.Sum += seconds;
        h.Count++;
        for (var i = 0; i < Buckets.Length; i++)
            if (seconds <= Buckets[i]) h.Counts[i]++;
    }

    /// <summary>Escape a Prometheus label value: backslash, double-quote, newline (backslash first).</summary>
    private static string EscapeLabel(string v)
        => v.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\n", "\\n");

    /// <summary>Render ordered label pairs to <c>k="v",k="v"</c> (order is load-bearing for parity).</summary>
    private static string LabelStr(params (string K, string V)[] pairs)
        => string.Join(",", pairs.Select(p => $"{p.K}=\"{EscapeLabel(p.V)}\""));

    private static string WithLe(string @base, string le)
        => @base.Length > 0 ? $"{@base},le=\"{le}\"" : $"le=\"{le}\"";

    /// <summary>Lexicographic (UTF-16 code-unit / ordinal) sort — matches JS <c>Array.prototype.sort</c>.</summary>
    private static IEnumerable<string> Sorted(IEnumerable<string> keys)
        => keys.OrderBy(k => k, StringComparer.Ordinal);
}
