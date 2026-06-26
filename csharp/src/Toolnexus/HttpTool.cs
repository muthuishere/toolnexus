using System.Text;
using System.Text.RegularExpressions;

namespace Toolnexus;

/// <summary>
/// HTTP / REST tool (source <c>"http"</c>). Declares a remote endpoint as a uniform
/// <see cref="ITool"/>. Mirrors the JS reference (<c>js/src/http.ts</c>).
/// <list type="bullet">
///   <item><c>{placeholder}</c> URL substitution (consumed from args).</item>
///   <item><c>Query</c> arg names → querystring (GET → all args to query).</item>
///   <item>body json|form|raw for non-GET requests.</item>
///   <item><c>${ENV}</c> header expansion from the environment (never logged).</item>
///   <item>non-2xx ⇒ <c>"HTTP &lt;status&gt;: &lt;body&gt;"</c> error; 2xx ⇒ body + metadata{status}.</item>
/// </list>
/// </summary>
public sealed partial class HttpTool : ITool
{
    private const long DefaultTimeout = 30_000L;
    private static readonly HttpClient Client = new();

    [GeneratedRegex(@"\{(\w+)\}")]
    private static partial Regex Placeholder();

    public sealed class Options
    {
        public string Name { get; set; } = "";
        public string Description { get; set; } = "";
        public string Method { get; set; } = "GET";
        public string Url { get; set; } = "";
        public IDictionary<string, string>? Headers { get; set; }
        public List<string>? Query { get; set; }
        public string? Body { get; set; } // "json" | "form" | "raw"
        public IDictionary<string, object?>? InputSchema { get; set; }
        public long? Timeout { get; set; }
        public string? ResultMode { get; set; } // "text" | "json" | "status+text"
    }

    private readonly Options _opts;
    private readonly string _method;
    private readonly HashSet<string> _querySet;

    public string Name => _opts.Name;
    public string Description => _opts.Description;
    public IDictionary<string, object?> InputSchema { get; }
    public string Source => "http";

    private HttpTool(Options opts)
    {
        _opts = opts;
        _method = opts.Method.ToUpperInvariant();
        _querySet = new HashSet<string>(opts.Query ?? new List<string>());
        InputSchema = opts.InputSchema ?? NativeTool.EmptySchema();
    }

    public static HttpTool Of(Options opts) => new(opts);

    public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
    {
        var a = new Dictionary<string, object?>(args ?? new Dictionary<string, object?>());

        // 1. substitute {placeholders} in the URL from args (consumed afterwards)
        var url = Placeholder().Replace(_opts.Url, m =>
        {
            var key = m.Groups[1].Value;
            a.Remove(key, out var val);
            return Uri.EscapeDataString(val?.ToString() ?? "");
        });

        // 2. querystring args
        var qsParts = new List<string>();
        foreach (var key in a.Keys.ToList())
        {
            if (_querySet.Contains(key) || _method == "GET")
            {
                a.Remove(key, out var v);
                qsParts.Add(Uri.EscapeDataString(key) + "=" + Uri.EscapeDataString(v?.ToString() ?? ""));
            }
        }
        if (qsParts.Count > 0)
            url += (url.Contains('?') ? "&" : "?") + string.Join("&", qsParts);

        // 3. body
        var headers = McpSource.ExpandEnvHeaders(_opts.Headers) ?? new Dictionary<string, string>();
        string? bodyInit = null;
        string? contentType = null;
        if (_method != "GET" && _method != "HEAD" && a.Count > 0)
        {
            var mode = _opts.Body ?? "json";
            if (mode == "json")
            {
                contentType = "application/json";
                bodyInit = Json.Stringify(a);
            }
            else if (mode == "form")
            {
                contentType = "application/x-www-form-urlencoded";
                bodyInit = string.Join("&", a.Select(e =>
                    Uri.EscapeDataString(e.Key) + "=" + Uri.EscapeDataString(e.Value?.ToString() ?? "")));
            }
            else
            {
                bodyInit = a.TryGetValue("body", out var b) ? b?.ToString() ?? "" : "";
            }
        }

        var timeoutMs = ctx?.TimeoutMs ?? _opts.Timeout ?? DefaultTimeout;

        try
        {
            using var req = new HttpRequestMessage(new HttpMethod(_method), url);
            if (bodyInit != null)
            {
                req.Content = new StringContent(bodyInit, Encoding.UTF8);
                if (contentType != null)
                    req.Content.Headers.ContentType =
                        new System.Net.Http.Headers.MediaTypeHeaderValue(contentType) { CharSet = null };
            }
            foreach (var (k, v) in headers)
            {
                if (!req.Headers.TryAddWithoutValidation(k, v) && req.Content != null)
                    req.Content.Headers.TryAddWithoutValidation(k, v);
            }

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ctx?.CancellationToken ?? default);
            cts.CancelAfter(TimeSpan.FromMilliseconds(timeoutMs));

            using var res = await Client.SendAsync(req, cts.Token).ConfigureAwait(false);
            var status = (int)res.StatusCode;
            var text = await res.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
            var meta = new Dictionary<string, object?> { ["status"] = status };

            if (status < 200 || status >= 300)
                return new ToolResult($"HTTP {status}: {text}", true, meta);

            string output;
            if (_opts.ResultMode == "status+text")
                output = status + "\n" + text;
            else if (_opts.ResultMode == "json")
                output = Json.Stringify(Json.ParseLoose(text));
            else
                output = text;

            return new ToolResult(output, false, meta);
        }
        catch (Exception e)
        {
            return ToolResult.Error(e.Message ?? e.ToString());
        }
    }
}
