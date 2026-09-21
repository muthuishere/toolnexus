using System.Net.Http.Headers;
using System.Text;

namespace Toolnexus.Spike;

/// <summary>
/// The configure-once decision client. Options mirror <c>LlmClient.Options</c> field-for-field
/// where a field makes sense.
/// </summary>
public sealed class Classifier
{
    public sealed class Options
    {
        public string Style { get; set; } = "systemone";   // "systemone" | "static"
        public string BaseUrl { get; set; } = "https://api.typesafe.ai/v1";
        public string Model { get; set; } = "jev-latest";
        public string ApiKeyEnv { get; set; } = "TYPESAFE_API_KEY";
        public IDictionary<string, string>? Headers { get; set; }
        public HttpClient? HttpClient { get; set; }
        public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(10);

        /// <summary>"static" backend: canonical request bytes -&gt; recorded response JSON.</summary>
        public Func<string, string>? Fixture { get; set; }
    }

    private static readonly HttpClient DefaultHttp = new();
    private readonly Options _opts;

    private Classifier(Options opts) => _opts = opts;
    public static Classifier Create(Options opts) => new(opts);

    /// <summary>The canonical request body for these questions — pure, no network.</summary>
    public byte[] RequestBody(object state, IReadOnlyDictionary<string, Question> questions)
        => CanonicalJson.Bytes(new Dictionary<string, object?>
        {
            ["model"] = _opts.Model,
            ["state"] = state,
            ["questions"] = questions.ToDictionary(kv => kv.Key, kv => (object?)kv.Value.ToWire()),
        });

    public async Task<Decision> EvaluateAsync(
        object state, IReadOnlyDictionary<string, Question> questions, CancellationToken ct = default)
    {
        var body = RequestBody(state, questions);
        if (_opts.Style == "static")
            return DecisionParser.Parse(_opts.Fixture!(Encoding.UTF8.GetString(body)));

        var req = new HttpRequestMessage(HttpMethod.Post, _opts.BaseUrl.TrimEnd('/') + "/systemone")
        {
            Content = new ByteArrayContent(body),
        };
        req.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        // Secret is read by name at call time and never logged.
        var key = Environment.GetEnvironmentVariable(_opts.ApiKeyEnv);
        if (!string.IsNullOrEmpty(key)) req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        if (_opts.Headers != null)
            foreach (var (k, v) in _opts.Headers) req.Headers.TryAddWithoutValidation(k, v);

        var http = _opts.HttpClient ?? DefaultHttp;
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(_opts.Timeout);
        using var res = await http.SendAsync(req, cts.Token).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode)
            throw new HttpRequestException($"classifier HTTP {(int)res.StatusCode}: {text}");
        return DecisionParser.Parse(text);
    }
}
