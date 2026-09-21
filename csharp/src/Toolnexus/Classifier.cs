using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace Toolnexus;

// Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as ITool is the contract
// for an ACTION. A System One model takes a state plus pre-declared, typed
// questions and returns calibrated answers with no free text. It has no messages,
// no tool calling and no streaming, so it never enters the client loop.
//
// A classifier INTERPRETS; it never AUTHORISES. Schema validity is not
// correctness: a decision can be confidently wrong, and "cannot hallucinate"
// means only that the returned value is in the declared schema. Numeric limits,
// permission checks and allowlists stay in code. Nothing here is a security
// control.

/// <summary>Thrown by every classifier failure path. Never carries a credential value.</summary>
public sealed class ClassifierException : Exception
{
    public ClassifierException(string message) : base(message) { }
    public ClassifierException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>Which backend answers (§8B). Default <see cref="SystemOne"/>.</summary>
public enum ClassifierStyle
{
    /// <summary>One POST of the canonical body to <c>{BaseUrl}/systemone</c>.</summary>
    SystemOne,

    /// <summary>The questions rendered as ONE structured-output call on a §8 <see cref="LlmClient"/>.
    /// Reports <c>Calibrated = false</c> unless the backend read token probabilities.</summary>
    Llm,

    /// <summary>The host's own <see cref="ClassifierOptions.Evaluate"/>.</summary>
    Custom,

    /// <summary>A recorded corpus. This is what CI runs: no network, no credential.</summary>
    Static,
}

// ---------------------------------------------------------------- questions

/// <summary>
/// One pre-declared question. The set is CLOSED — the only subclasses are
/// <see cref="NoulQuestion"/>, <see cref="ChoiceQuestion"/> and <see cref="ScoreQuestion"/>,
/// enforced by the internal constructor. The three shapes differ only in what <c>criteria</c>
/// is on the wire (absent, an object, or an ordered array), so the abstraction is "give me
/// your wire form", not a field set.
/// </summary>
public abstract record Question
{
    internal Question() { }

    /// <summary>What the model is being asked.</summary>
    public required string Instructions { get; init; }

    /// <summary>The wire projection. A plain dictionary, never reflection over properties:
    /// an <c>omitempty</c>-style rule cannot tell an ABSENT field from an EMPTY one, and the
    /// wire carries that distinction (§8B).</summary>
    internal abstract IReadOnlyDictionary<string, object?> Wire();

    /// <summary>Enforce the client-side limits, naming the offending key.</summary>
    internal virtual void Validate(string key) { }
}

/// <summary>
/// Labels for the true and false cases of a <see cref="NoulQuestion"/>. Absent and empty are
/// different values and both are preserved on the wire, which is why
/// <see cref="NoulQuestion.Criteria"/> is nullable: null omits <c>criteria</c> entirely, while
/// a <c>new NoulCriteria()</c> emits <c>{"false":"","true":""}</c>.
/// </summary>
public sealed record NoulCriteria
{
    public string True { get; init; } = "";
    public string False { get; init; } = "";
}

/// <summary>Asks for the probability that a statement holds, one number in 0..1. It reports
/// NO confidence: the number IS the answer.</summary>
public sealed record NoulQuestion : Question
{
    /// <summary>Optional. Null ⇒ the field is absent from the request.</summary>
    public NoulCriteria? Criteria { get; init; }

    internal override IReadOnlyDictionary<string, object?> Wire()
    {
        var m = new Dictionary<string, object?> { ["type"] = "noul", ["instructions"] = Instructions };
        if (Criteria != null)
            m["criteria"] = new Dictionary<string, object?> { ["true"] = Criteria.True, ["false"] = Criteria.False };
        return m;
    }
}

/// <summary>
/// Asks for one option from a named set, 1..255 options.
///
/// THE ENCODING OBLIGATION IS THE CALLER'S (§8B, docs/adr/0021 D1): <c>Criteria[id]</c> is the
/// only thing that differentiates one option from another to the model. Passing the id itself,
/// an empty string, or one value repeated is schema-valid, returns HTTP 200 and a well-formed
/// distribution — and ranks at chance (measured: 17 apples described by consequence, 0/1/0
/// described by id).
/// </summary>
public sealed record ChoiceQuestion : Question
{
    /// <summary>option id ⇒ what picking it would MEAN.</summary>
    public IReadOnlyDictionary<string, string> Criteria { get; init; } = new Dictionary<string, string>();

    internal override IReadOnlyDictionary<string, object?> Wire()
    {
        var c = new Dictionary<string, object?>();
        foreach (var (k, v) in Criteria) c[k] = v;
        return new Dictionary<string, object?>
        {
            ["type"] = "choice",
            ["instructions"] = Instructions,
            ["criteria"] = c,
        };
    }

    internal override void Validate(string key)
    {
        var n = Criteria.Count;
        if (n < 1 || n > Classifier.MaxChoiceOptions)
            throw new ClassifierException(
                $"classifier: question \"{key}\": a choice needs 1..{Classifier.MaxChoiceOptions} options, got {n}");
    }
}

/// <summary>
/// Asks for a rating against an ORDERED rubric of 2..10 levels. The list order IS the level
/// numbering, so it is never sorted — a "sort everything" canonicaliser silently renumbers
/// the rubric.
/// </summary>
public sealed record ScoreQuestion : Question
{
    public IReadOnlyList<string> Criteria { get; init; } = Array.Empty<string>();

    internal override IReadOnlyDictionary<string, object?> Wire()
        => new Dictionary<string, object?>
        {
            ["type"] = "score",
            ["instructions"] = Instructions,
            ["criteria"] = Criteria.ToList(),
        };

    internal override void Validate(string key)
    {
        var n = Criteria.Count;
        if (n < Classifier.MinScoreLevels || n > Classifier.MaxScoreLevels)
            throw new ClassifierException(
                $"classifier: question \"{key}\": a score needs {Classifier.MinScoreLevels}..{Classifier.MaxScoreLevels} ordered levels, got {n}");
    }
}

// ---------------------------------------------------------------- answers

/// <summary>
/// One typed answer. The set is CLOSED and discriminated by the wire's <c>type</c> field.
/// Named <c>DecisionAnswer</c> rather than <c>Answer</c> because §10 already owns
/// <see cref="Answer"/> (the suspension resolution) — same idea, different seam.
/// </summary>
public abstract record DecisionAnswer
{
    internal DecisionAnswer() { }

    /// <summary>The wire discriminator: <c>"noul"</c> | <c>"choice"</c> | <c>"score"</c>.</summary>
    public abstract string AnswerType { get; }
}

/// <summary>The probability that a statement holds. Carries NO confidence: the number is the answer.</summary>
public sealed record NoulAnswer : DecisionAnswer
{
    public double Noul { get; init; }
    public override string AnswerType => "noul";
}

/// <summary>One option from the offered set, with a probability for every offered option.</summary>
public sealed record ChoiceAnswer : DecisionAnswer
{
    public string Choice { get; init; } = "";
    public IReadOnlyDictionary<string, double> Probabilities { get; init; } = new Dictionary<string, double>();
    public double Confidence { get; init; }

    /// <summary>
    /// DERIVED from <see cref="Probabilities"/> on decode and never read from the wire (§8B):
    /// <c>max|p - 1/n| &lt;= 0.05</c>, inclusive, <c>n</c> = the number of ENTRIES in the map.
    ///
    /// ADVISORY, NOT a correctness signal. It detects an encoding that gave the model nothing to
    /// rank on — it cannot distinguish a good encoding from a subtly wrong one.
    /// <see cref="Decision.Calibrated"/> carries the same caveat.
    /// </summary>
    public bool NearUniform { get; init; }

    public override string AnswerType => "choice";
}

/// <summary>A rating against the ordered rubric. <see cref="Score"/> MAY fall between levels
/// (1.21 is a real answer) and is always within the rubric's bounds.</summary>
public sealed record ScoreAnswer : DecisionAnswer
{
    public double Score { get; init; }
    public IReadOnlyDictionary<string, string> Legend { get; init; } = new Dictionary<string, string>();
    public IReadOnlyDictionary<string, double> Probabilities { get; init; } = new Dictionary<string, double>();
    public double Confidence { get; init; }

    public override string AnswerType => "score";

    /// <summary>The legend in LEVEL order, which the map itself loses.</summary>
    public IReadOnlyList<string> Levels()
        => Legend.Keys
            // Numeric-by-length-then-ordinal: "2" &lt; "10" as levels, unlike as strings.
            .OrderBy(k => k.Length)
            .ThenBy(k => k, StringComparer.Ordinal)
            .Select(k => Legend[k])
            .ToList();
}

/// <summary>Mirrors the wire's <c>usage</c> block. Named apart from
/// <see cref="LlmClient.Usage"/>, which counts a client run.</summary>
public sealed record ClassifierUsage
{
    public long InputTokens { get; init; }
    public long OutputTokens { get; init; }

    /// <summary>What the call cost, when the backend says. <c>null</c> means it did NOT say —
    /// cost is a gateway field, and TypeSafe's own API never returns one. A non-null <c>0</c> is a
    /// backend genuinely reporting a free call, which is why this is nullable rather than 0.</summary>
    public double? Cost { get; init; }
}

/// <summary>
/// One answer per question, keyed by the CALLER's keys. The keys are addressing, not content:
/// they are never transmitted, so a key may be a tool, skill or agent name verbatim.
/// </summary>
public sealed record Decision
{
    /// <summary>Echoes what actually answered, which may be more specific than the model asked for.</summary>
    public string Model { get; init; } = "";

    public IReadOnlyDictionary<string, DecisionAnswer> Answers { get; init; } = new Dictionary<string, DecisionAnswer>();

    public ClassifierUsage Usage { get; init; } = new();

    /// <summary>
    /// Whether the probabilities are calibrated. <c>systemone</c> reports true; <c>llm</c> reports
    /// false unless it derived them from provider token probabilities. A THRESHOLD TUNED AGAINST
    /// ONE BACKEND DOES NOT TRANSFER TO ANOTHER.
    /// </summary>
    public bool Calibrated { get; init; } = true;

    /// <summary>Typed read of a <c>noul</c> answer. Throws on a wrong-type or absent key, so a
    /// caller never casts by hand and a mistake is never a silent default.</summary>
    public NoulAnswer Noul(string key) => Typed<NoulAnswer>(key, "noul");

    /// <summary>Typed read of a <c>choice</c> answer.</summary>
    public ChoiceAnswer Choice(string key) => Typed<ChoiceAnswer>(key, "choice");

    /// <summary>Typed read of a <c>score</c> answer.</summary>
    public ScoreAnswer Score(string key) => Typed<ScoreAnswer>(key, "score");

    private T Typed<T>(string key, string want) where T : DecisionAnswer
    {
        if (!Answers.TryGetValue(key, out var a))
            throw new ClassifierException($"classifier: no answer \"{key}\" in this decision");
        if (a is T typed) return typed;
        throw new ClassifierException($"classifier: answer \"{key}\" is a {a.AnswerType} answer, not {want}");
    }

    /// <summary>Decode a backend response body. This is the single place
    /// <see cref="ChoiceAnswer.NearUniform"/> is derived.</summary>
    internal static Decision FromJson(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        var answers = new Dictionary<string, DecisionAnswer>();
        if (root.TryGetProperty("answers", out var answersEl) && answersEl.ValueKind == JsonValueKind.Object)
        {
            foreach (var prop in answersEl.EnumerateObject())
                answers[prop.Name] = ParseAnswer(prop.Name, prop.Value);
        }
        var usage = new ClassifierUsage();
        if (root.TryGetProperty("usage", out var u) && u.ValueKind == JsonValueKind.Object)
        {
            usage = new ClassifierUsage
            {
                InputTokens = Num(u, "input_tokens") is { } i ? (long)i : 0,
                OutputTokens = Num(u, "output_tokens") is { } o ? (long)o : 0,
                Cost = Num(u, "cost"), // absent stays absent; a real 0 stays a real 0
            };
        }
        // Absent ⇒ true: the systemone wire reports calibration by being itself. A backend that is
        // not calibrated says so explicitly.
        var calibrated = !root.TryGetProperty("calibrated", out var cal) || cal.ValueKind != JsonValueKind.False;
        return new Decision
        {
            Model = root.TryGetProperty("model", out var m) && m.ValueKind == JsonValueKind.String ? m.GetString()! : "",
            Answers = answers,
            Usage = usage,
            Calibrated = calibrated,
        };
    }

    private static double? Num(JsonElement obj, string name)
        => obj.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.Number ? el.GetDouble() : null;

    private static DecisionAnswer ParseAnswer(string key, JsonElement el)
    {
        var type = el.TryGetProperty("type", out var t) && t.ValueKind == JsonValueKind.String ? t.GetString() : null;
        switch (type)
        {
            case "noul":
                return new NoulAnswer { Noul = Num(el, "noul") ?? 0 };
            case "choice":
            {
                var probs = Doubles(el, "probabilities");
                return new ChoiceAnswer
                {
                    Choice = el.TryGetProperty("choice", out var c) && c.ValueKind == JsonValueKind.String ? c.GetString()! : "",
                    Probabilities = probs,
                    Confidence = Num(el, "confidence") ?? 0,
                    NearUniform = Classifier.NearUniform(probs),
                };
            }
            case "score":
                return new ScoreAnswer
                {
                    Score = Num(el, "score") ?? 0,
                    Legend = Strings(el, "legend"),
                    Probabilities = Doubles(el, "probabilities"),
                    Confidence = Num(el, "confidence") ?? 0,
                };
            default:
                throw new ClassifierException($"classifier: answer \"{key}\": unknown type \"{type}\"");
        }
    }

    private static Dictionary<string, double> Doubles(JsonElement obj, string name)
    {
        var map = new Dictionary<string, double>();
        if (obj.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.Object)
            foreach (var p in el.EnumerateObject())
                if (p.Value.ValueKind == JsonValueKind.Number)
                    map[p.Name] = p.Value.GetDouble();
        return map;
    }

    private static Dictionary<string, string> Strings(JsonElement obj, string name)
    {
        var map = new Dictionary<string, string>();
        if (obj.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.Object)
            foreach (var p in el.EnumerateObject())
                map[p.Name] = p.Value.ValueKind == JsonValueKind.String ? p.Value.GetString()! : p.Value.ToString();
        return map;
    }
}

// ---------------------------------------------------------------- options

/// <summary>
/// One entry of the <c>static</c> corpus. Keyed on the canonical request AND the state: several
/// recorded entries legitimately share one <c>questions</c> payload and differ only in state (the
/// three guard bands in <c>examples/judge/decisions.json</c> do exactly that), so a corpus keyed
/// on the canonical request alone cannot tell them apart.
/// </summary>
public sealed record RecordedDecision
{
    public object? State { get; init; }
    public required IReadOnlyDictionary<string, Question> Questions { get; init; }

    /// <summary>The recorded backend response body, verbatim.</summary>
    public required string Response { get; init; }
}

/// <summary>
/// Mirrors <see cref="LlmClient.Options"/> field-for-field wherever a field makes sense, so a host
/// that has configured one has configured the other (§8B).
/// </summary>
public sealed class ClassifierOptions
{
    /// <summary>Which backend answers. Default <see cref="ClassifierStyle.SystemOne"/>.</summary>
    public ClassifierStyle Style { get; set; } = ClassifierStyle.SystemOne;

    /// <summary>The API base. Null/empty ⇒ <see cref="Classifier.DefaultBaseUrl"/>. OpenRouter
    /// (<c>https://openrouter.ai/api/v1</c>) serves this wire today.</summary>
    public string? BaseUrl { get; set; }

    /// <summary>Null/empty ⇒ <see cref="Classifier.DefaultModel"/>. Pin it once thresholds are tuned.</summary>
    public string? Model { get; set; }

    /// <summary>The NAME of the env var holding the credential, never the value — read at call time
    /// and never logged. Null/empty ⇒ <see cref="Classifier.DefaultApiKeyEnv"/>.
    /// §8's <c>ApiKey</c> takes a value; this option deliberately does not.</summary>
    public string? ApiKeyEnv { get; set; }

    /// <summary>Extra request headers. Values expand <c>${ENV_VAR}</c> from the environment AT CALL
    /// TIME and are NEVER logged, identically to remote-MCP headers (§2).</summary>
    public IDictionary<string, string>? Headers { get; set; }

    /// <summary>Bounds ONE request (a classifier has no loop to bound). Null ⇒ 10 s.</summary>
    public TimeSpan? Timeout { get; set; }

    /// <summary>(§8 Gap 2) Overrides the transport. Scope is the classifier path only.</summary>
    public HttpClient? HttpClient { get; set; }

    /// <summary>(§8 Gap 2) A handler to build the classifier's HTTP client from. Ignored when
    /// <see cref="HttpClient"/> is set.</summary>
    public HttpMessageHandler? HttpHandler { get; set; }

    /// <summary>Retries on transient errors (408/429/500/502/503/504/529 + network). Null ⇒ 2.
    /// Widen the status set with <see cref="RetryableStatuses"/>.</summary>
    public int? Retries { get; set; }

    /// <summary>
    /// Extra HTTP statuses to treat as retryable, ADDED to the default set
    /// (<c>429</c>/<c>500</c>/<c>502</c>/<c>503</c>/<c>504</c>/<c>529</c>, plus <c>408</c> here). It
    /// can only widen: a host cannot remove <c>429</c> and lose <c>Retry-After</c> handling with it.
    /// This sets the DEFAULT classification; <c>OnError</c> still runs per attempt and has the final
    /// say, so <c>OnError</c> returning <c>Fail</c> overrides a status listed here.
    /// Example: a Cloudflare-fronted origin that answers <c>520</c>–<c>527</c>.
    /// <c>Retry-After</c> handling is untouched. Null ⇒ the defaults alone.
    /// </summary>
    public IReadOnlyCollection<int>? RetryableStatuses { get; set; }

    /// <summary>(§8 Resilience) Classifies a failed attempt into <see cref="LlmClient.Tier.Retry"/>
    /// or <see cref="LlmClient.Tier.Fail"/>. Null ⇒ the default classifier. REUSES the client's
    /// <see cref="LlmClient.ErrorInfo"/>/<see cref="LlmClient.Tier"/> and the <c>Retry-After</c>
    /// delay-seconds rule verbatim — there is no second retry policy, and no <c>"suspend"</c>
    /// tier here either.</summary>
    public Func<LlmClient.ErrorInfo, LlmClient.Tier>? OnError { get; set; }

    /// <summary>(§8 Gap 1) Extra top-level keys shallow-merged into the request body after the
    /// classifier builds its own; a <see cref="RequestParams"/> key WINS on collision. Null ⇒ the
    /// body is byte-identical.</summary>
    public IReadOnlyDictionary<string, object?>? RequestParams { get; set; }

    /// <summary>(§8 Gap 1) Receives the assembled body after the <see cref="RequestParams"/> merge
    /// and returns the body to send. Returning null ⇒ unchanged. Order is: base body →
    /// RequestParams merge → BodyTransform → marshal, exactly as §8.</summary>
    public Func<IDictionary<string, object?>, IDictionary<string, object?>?>? BodyTransform { get; set; }

    /// <summary>Emits <c>classifier.evaluate</c> events into the SAME §8 sink, and carries the
    /// degenerate-criteria warning as <c>classifier.warning</c>. Neither event is folded into the
    /// Prometheus registry, so <see cref="LlmClient.Metrics"/> text is unchanged.</summary>
    public Action<MetricEvent>? OnMetric { get; set; }

    /// <summary>The §8 client to emulate over. <see cref="ClassifierStyle.Llm"/> only.</summary>
    public LlmClient? Client { get; set; }

    /// <summary>The host's own function. <see cref="ClassifierStyle.Custom"/> only; every wire
    /// option is ignored.</summary>
    public Func<object?, IReadOnlyDictionary<string, Question>, CancellationToken, Task<Decision>>? Evaluate { get; set; }

    /// <summary>The recorded corpus. <see cref="ClassifierStyle.Static"/> only.</summary>
    public IReadOnlyList<RecordedDecision>? Decisions { get; set; }

    public ClassifierOptions WithStyle(ClassifierStyle v) { Style = v; return this; }
    public ClassifierOptions WithBaseUrl(string v) { BaseUrl = v; return this; }
    public ClassifierOptions WithModel(string v) { Model = v; return this; }
    public ClassifierOptions WithApiKeyEnv(string v) { ApiKeyEnv = v; return this; }
    public ClassifierOptions WithHeaders(IDictionary<string, string> v) { Headers = v; return this; }
    public ClassifierOptions WithTimeout(TimeSpan v) { Timeout = v; return this; }
    public ClassifierOptions WithHttpClient(HttpClient v) { HttpClient = v; return this; }
    public ClassifierOptions WithRetries(int v) { Retries = v; return this; }
    public ClassifierOptions WithRetryableStatuses(IReadOnlyCollection<int> v) { RetryableStatuses = v; return this; }
    public ClassifierOptions WithOnError(Func<LlmClient.ErrorInfo, LlmClient.Tier> v) { OnError = v; return this; }
    public ClassifierOptions WithRequestParams(IReadOnlyDictionary<string, object?> v) { RequestParams = v; return this; }
    public ClassifierOptions WithBodyTransform(Func<IDictionary<string, object?>, IDictionary<string, object?>?> v) { BodyTransform = v; return this; }
    public ClassifierOptions WithOnMetric(Action<MetricEvent> v) { OnMetric = v; return this; }
    public ClassifierOptions WithClient(LlmClient v) { Client = v; return this; }
    public ClassifierOptions WithEvaluate(Func<object?, IReadOnlyDictionary<string, Question>, CancellationToken, Task<Decision>> v) { Evaluate = v; return this; }
    public ClassifierOptions WithDecisions(IReadOnlyList<RecordedDecision> v) { Decisions = v; return this; }
}

// ---------------------------------------------------------------- classifier

/// <summary>The whole seam: one verb, <see cref="EvaluateAsync"/>.</summary>
public sealed class Classifier
{
    /// <summary>The System One endpoint base.</summary>
    public const string DefaultBaseUrl = "https://api.typesafe.ai/v1";

    /// <summary>The floating model alias. Pin it once thresholds are tuned.</summary>
    public const string DefaultModel = "jev-latest";

    /// <summary>The NAME of the env var holding the credential.</summary>
    public const string DefaultApiKeyEnv = "TYPESAFE_API_KEY";

    /// <summary>Bounds ONE request (a classifier has no loop to bound).</summary>
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(10);

    /// <summary>The client-side cap on a choice's named options (§8B).</summary>
    public const int MaxChoiceOptions = 255;

    /// <summary>Bounds on a score rubric (§8B).</summary>
    public const int MinScoreLevels = 2;
    public const int MaxScoreLevels = 10;

    /// <summary>The ABSOLUTE tolerance on <c>max|p - 1/n|</c>, compared INCLUSIVELY (§8B). Pinned
    /// across every port; the shared fixtures pin both sides.</summary>
    public const double NearUniformTolerance = 0.05;

    /// <summary>The two <see cref="MetricEvent.Event"/> values this file emits. Neither is folded
    /// into the Prometheus registry, so the rendered metrics text is unchanged.</summary>
    public const string MetricEvaluate = "classifier.evaluate";
    public const string MetricWarning = "classifier.warning";

    private readonly ClassifierOptions _opts;
    private readonly string _baseUrl;
    private readonly string _model;
    private readonly string _apiKeyEnv;
    private readonly TimeSpan _timeout;
    private readonly int _retries;
    private readonly HttpClient _http;
    private readonly Dictionary<string, string> _static = new(StringComparer.Ordinal);

    // The once-per-question-key set for the degenerate-criteria warning, so a per-turn judge does
    // not flood the sink.
    private readonly object _warnLock = new();
    private readonly HashSet<string> _warned = new(StringComparer.Ordinal);

    private static readonly HttpClient SharedHttp = new() { Timeout = System.Threading.Timeout.InfiniteTimeSpan };

    private Toolkit? _emptyToolkit;

    /// <summary>
    /// Builds a classifier, applying the §8B defaults and rejecting a style whose required option
    /// is missing BEFORE any call is made.
    /// </summary>
    public Classifier(ClassifierOptions? options = null)
    {
        _opts = options ?? new ClassifierOptions();
        _baseUrl = string.IsNullOrEmpty(_opts.BaseUrl) ? DefaultBaseUrl : _opts.BaseUrl!;
        _model = string.IsNullOrEmpty(_opts.Model) ? DefaultModel : _opts.Model!;
        _apiKeyEnv = string.IsNullOrEmpty(_opts.ApiKeyEnv) ? DefaultApiKeyEnv : _opts.ApiKeyEnv!;
        _timeout = _opts.Timeout is { } t && t > TimeSpan.Zero ? t : DefaultTimeout;
        _retries = _opts.Retries is { } r && r > 0 ? r : 2;
        _http = _opts.HttpClient
                ?? (_opts.HttpHandler != null
                    ? new HttpClient(_opts.HttpHandler, disposeHandler: false) { Timeout = System.Threading.Timeout.InfiniteTimeSpan }
                    : SharedHttp);

        switch (_opts.Style)
        {
            case ClassifierStyle.SystemOne:
                break;
            case ClassifierStyle.Llm:
                if (_opts.Client == null)
                    throw new ClassifierException("classifier: style \"llm\" requires Client");
                break;
            case ClassifierStyle.Custom:
                if (_opts.Evaluate == null)
                    throw new ClassifierException("classifier: style \"custom\" requires Evaluate");
                break;
            case ClassifierStyle.Static:
                foreach (var (rec, i) in (_opts.Decisions ?? Array.Empty<RecordedDecision>()).Select((r, i) => (r, i)))
                {
                    try { _static[StaticKey(_model, rec.State, rec.Questions)] = rec.Response; }
                    catch (Exception e) { throw new ClassifierException($"classifier: recorded decision {i}: {e.Message}", e); }
                }
                break;
            default:
                throw new ClassifierException($"classifier: unknown style \"{_opts.Style}\"");
        }
    }

    /// <summary>Mirrors the other ports' <c>createClassifier</c> factory.</summary>
    public static Classifier Create(ClassifierOptions? options = null) => new(options);

    /// <summary>The applied style (after defaults).</summary>
    public ClassifierStyle Style => _opts.Style;

    /// <summary>The applied base URL (after defaults).</summary>
    public string BaseUrl => _baseUrl;

    /// <summary>The applied model (after defaults).</summary>
    public string Model => _model;

    /// <summary>The applied env-var NAME (after defaults). Never the value.</summary>
    public string ApiKeyEnv => _apiKeyEnv;

    /// <summary>The applied per-request timeout (after defaults).</summary>
    public TimeSpan Timeout => _timeout;

    /// <summary>
    /// The whole contract: a state plus typed questions in, a <see cref="Decision"/> out.
    /// Questions are INDEPENDENT — one answer is never context for another.
    /// </summary>
    public async Task<Decision> EvaluateAsync(object? state, IReadOnlyDictionary<string, Question> questions,
        CancellationToken cancellationToken = default)
    {
        var started = DateTimeOffset.UtcNow;
        if (questions == null || questions.Count == 0)
            throw Fail(started, new ClassifierException("classifier: no questions to evaluate"));

        // Limits are enforced CLIENT-SIDE, before the request: the caller finds out faster and more
        // legibly than from the backend's own 400. Keys are walked in sorted order so the same
        // malformed set always names the same key first.
        foreach (var key in SortedKeys(questions))
        {
            var q = questions[key];
            if (q == null) throw Fail(started, new ClassifierException($"classifier: question \"{key}\" is null"));
            try { q.Validate(key); }
            catch (ClassifierException e) { throw Fail(started, e); }
        }

        // Detection, never repair (ADR 0020/0021): the request goes out BYTE-UNCHANGED and the
        // warning is the entire observable effect.
        ReportDegenerate(questions);

        Decision d;
        try
        {
            d = _opts.Style switch
            {
                ClassifierStyle.Custom => await _opts.Evaluate!(state, questions, cancellationToken).ConfigureAwait(false),
                ClassifierStyle.Static => EvaluateStatic(state, questions),
                ClassifierStyle.Llm => await EvaluateLlmAsync(state, questions, cancellationToken).ConfigureAwait(false),
                _ => await EvaluateSystemOneAsync(state, questions, cancellationToken).ConfigureAwait(false),
            };
        }
        catch (Exception e)
        {
            throw Fail(started, e);
        }

        Emit(new MetricEvent
        {
            Event = MetricEvaluate,
            Model = d.Model,
            Status = "ok",
            Ms = (long)(DateTimeOffset.UtcNow - started).TotalMilliseconds,
            PromptTokens = d.Usage.InputTokens,
            CompletionTokens = d.Usage.OutputTokens,
        });
        return d;
    }

    private static IEnumerable<string> SortedKeys<T>(IReadOnlyDictionary<string, T> map)
        => map.Keys.OrderBy(k => k, StringComparer.Ordinal);

    // ---------------------------------------------------------------- the wire

    /// <summary>
    /// The bytes the byte-identity claim covers: <c>model</c> + <c>questions</c>, keys sorted
    /// recursively in ASCII (ordinal) order, arrays NEVER reordered, compact separators, and
    /// <c>&lt;&gt;&amp;'"</c> plus non-ASCII transmitted raw.
    ///
    /// <c>state</c> is deliberately NOT here. It is transmitted verbatim as the host supplied it
    /// and is outside the claim, because numbers do not canonicalise across languages (-0.0 renders
    /// four ways across our own seven runtimes). Do not re-widen this: a caller who needs their
    /// state pinned canonicalises it themselves before handing it over.
    /// </summary>
    public static byte[] CanonicalRequest(string model, IReadOnlyDictionary<string, Question> questions)
    {
        var qs = new Dictionary<string, object?>();
        foreach (var (k, q) in questions)
        {
            if (q == null) throw new ClassifierException($"classifier: question \"{k}\" is null");
            qs[k] = q.Wire();
        }
        return CanonicalJsonBytes(new Dictionary<string, object?> { ["model"] = model, ["questions"] = qs });
    }

    /// <summary>The same bytes as UTF-8 text.</summary>
    public static string CanonicalRequestString(string model, IReadOnlyDictionary<string, Question> questions)
        => Encoding.UTF8.GetString(CanonicalRequest(model, questions));

    private static readonly JsonWriterOptions CanonicalWriterOptions = new()
    {
        Indented = false,
        // System.Text.Json's DEFAULT encoder turns ' into &#x27; and <>& into entities. §8B says
        // they travel raw, so the relaxed encoder is mandatory, not a preference.
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        SkipValidation = false,
    };

    /// <summary>
    /// Canonical JSON. System.Text.Json CANNOT sort object keys, so the sort is ours, and it is
    /// <see cref="StringComparer.Ordinal"/> — the culture-sensitive default gets <c>10_alpha</c> /
    /// <c>Alpha</c> / <c>beta</c> / <c>café</c> wrong. Arrays are never reordered: a score rubric's
    /// order IS its level numbering.
    /// </summary>
    internal static byte[] CanonicalJsonBytes(object? value)
    {
        using var buf = new MemoryStream();
        using (var w = new Utf8JsonWriter(buf, CanonicalWriterOptions)) WriteCanonical(w, value);
        return buf.ToArray();
    }

    internal static string CanonicalJsonString(object? value) => Encoding.UTF8.GetString(CanonicalJsonBytes(value));

    private static void WriteCanonical(Utf8JsonWriter w, object? v)
    {
        switch (v)
        {
            case null:
                w.WriteNullValue();
                break;
            case string s:
                w.WriteStringValue(s);
                break;
            case bool b:
                w.WriteBooleanValue(b);
                break;
            case int or long or short or byte:
                w.WriteNumberValue(Convert.ToInt64(v, CultureInfo.InvariantCulture));
                break;
            case float or double:
                w.WriteNumberValue(Convert.ToDouble(v, CultureInfo.InvariantCulture));
                break;
            case decimal d:
                w.WriteNumberValue(d);
                break;
            case JsonElement el:
                WriteCanonicalElement(w, el);
                break;
            case System.Collections.IDictionary dict:
            {
                var keys = new List<string>();
                foreach (var k in dict.Keys) keys.Add(k?.ToString() ?? "");
                keys.Sort(StringComparer.Ordinal);
                w.WriteStartObject();
                foreach (var k in keys)
                {
                    w.WritePropertyName(k);
                    WriteCanonical(w, dict[k]);
                }
                w.WriteEndObject();
                break;
            }
            case System.Collections.IEnumerable list:
                w.WriteStartArray();
                foreach (var item in list) WriteCanonical(w, item);
                w.WriteEndArray();
                break;
            default:
                throw new ClassifierException($"classifier: value of type {v.GetType()} is not canonicalisable");
        }
    }

    private static void WriteCanonicalElement(Utf8JsonWriter w, JsonElement el)
    {
        switch (el.ValueKind)
        {
            case JsonValueKind.Object:
            {
                var props = el.EnumerateObject().ToList();
                props.Sort((a, b) => string.CompareOrdinal(a.Name, b.Name));
                w.WriteStartObject();
                foreach (var p in props)
                {
                    w.WritePropertyName(p.Name);
                    WriteCanonicalElement(w, p.Value);
                }
                w.WriteEndObject();
                break;
            }
            case JsonValueKind.Array:
                w.WriteStartArray();
                foreach (var item in el.EnumerateArray()) WriteCanonicalElement(w, item);
                w.WriteEndArray();
                break;
            case JsonValueKind.String:
                w.WriteStringValue(el.GetString());
                break;
            case JsonValueKind.Number:
                // Verbatim: the caller's state is outside the byte claim precisely because numbers
                // do not canonicalise, so the source text is the least-lossy thing to forward.
                w.WriteRawValue(el.GetRawText(), skipInputValidation: true);
                break;
            case JsonValueKind.True:
            case JsonValueKind.False:
                w.WriteBooleanValue(el.GetBoolean());
                break;
            default:
                w.WriteNullValue();
                break;
        }
    }

    // ---------------------------------------------------------------- nearUniform

    /// <summary>
    /// Whether a choice answer's probability map is indistinguishable from flat (§8B):
    /// <c>nearUniform ⇔ max over i of |p_i - 1/n| &lt;= 0.05</c>.
    ///
    /// <c>n</c> is the number of ENTRIES IN THE MAP and the values are taken AS RETURNED — not
    /// renormalised, not sorted, not rounded; an offered option absent from the map counts as 0 by
    /// not being an entry. The tolerance is ABSOLUTE (a relative band collapses below the wire's
    /// two-decimal rounding on a 255-option roster) and the comparison is INCLUSIVE.
    /// <c>n == 1</c> is trivially uniform. An EMPTY map has no distribution at all ⇒ false.
    /// </summary>
    public static bool NearUniform(IReadOnlyDictionary<string, double> probabilities)
    {
        if (probabilities == null) return false;
        var n = probabilities.Count;
        if (n == 0) return false;
        if (n == 1) return true;
        var target = 1.0 / n;
        foreach (var p in probabilities.Values)
            if (Math.Abs(p - target) > NearUniformTolerance)
                return false;
        return true;
    }

    // ---------------------------------------------------------------- degenerate

    /// <summary>
    /// Emits ONE warning per degenerate question key per classifier, naming the key, and changes
    /// nothing about the request. Repairing would invent option descriptions the caller did not
    /// write, and the library has no way to know what the options mean (ADR 0020).
    /// </summary>
    private void ReportDegenerate(IReadOnlyDictionary<string, Question> questions)
    {
        foreach (var key in SortedKeys(questions))
        {
            if (questions[key] is not ChoiceQuestion q) continue;
            var reason = DegenerateReason(q.Criteria);
            if (reason == null) continue;
            bool first;
            lock (_warnLock) first = _warned.Add(key);
            if (!first) continue;
            Emit(new MetricEvent
            {
                Event = MetricWarning,
                Question = key,
                Warning = $"classifier: question \"{key}\" has degenerate criteria ({reason}) — every option "
                        + "reads the same to the model and the answer ranks at chance; describe what picking "
                        + "each option would MEAN (SPEC.md §8B)",
            });
        }
    }

    /// <summary>
    /// The §8B predicate. Degenerate ⇔ ANY of: (1) every value is empty, (2) every value equals its
    /// own key, (3) every value is identical to every other value. A single-option choice
    /// (<c>n == 1</c>) is NEVER reported: with one option there is nothing to differentiate, and
    /// rules 1 and 2 can still hold there, so the gate comes first for all three.
    /// Returns null when the criteria are fine.
    /// </summary>
    internal static string? DegenerateReason(IReadOnlyDictionary<string, string> criteria)
    {
        if (criteria == null || criteria.Count < 2) return null;
        bool allEmpty = true, allEqualKey = true, allIdentical = true;
        string? first = null;
        foreach (var (k, raw) in criteria)
        {
            var v = raw ?? "";
            if (v.Length != 0) allEmpty = false;
            if (!string.Equals(v, k, StringComparison.Ordinal)) allEqualKey = false;
            if (first == null) first = v;
            else if (!string.Equals(v, first, StringComparison.Ordinal)) allIdentical = false;
        }
        if (allEmpty) return "every description is empty";
        if (allEqualKey) return "every description is just its own option id";
        if (allIdentical) return "every description is identical";
        return null;
    }

    // ---------------------------------------------------------------- backends

    /// <summary>
    /// Assembles the request: the canonical model + questions, plus state VERBATIM as the host
    /// supplied it, then the §8 Gap 1 pipeline in §8 order
    /// (base → RequestParams merge → BodyTransform → marshal).
    /// </summary>
    private byte[] Body(object? state, IReadOnlyDictionary<string, Question> questions)
    {
        var qs = new Dictionary<string, object?>();
        foreach (var (k, q) in questions) qs[k] = q.Wire();
        IDictionary<string, object?> body = new Dictionary<string, object?>
        {
            ["model"] = _model,
            ["questions"] = qs,
            ["state"] = state,
        };
        if (_opts.RequestParams != null)
            foreach (var (k, v) in _opts.RequestParams) body[k] = v; // a RequestParams key WINS
        if (_opts.BodyTransform != null)
        {
            var output = _opts.BodyTransform(body);
            if (output != null) body = output;
        }
        return CanonicalJsonBytes(body);
    }

    /// <summary>Identifies a recorded decision by the canonical request AND the state. See
    /// <see cref="RecordedDecision"/> for why the state is load-bearing here.</summary>
    private static string StaticKey(string model, object? state, IReadOnlyDictionary<string, Question> questions)
        => CanonicalRequestString(model, questions) + "\0" + CanonicalJsonString(state);

    private Decision EvaluateStatic(object? state, IReadOnlyDictionary<string, Question> questions)
    {
        var key = StaticKey(_model, state, questions);
        if (!_static.TryGetValue(key, out var raw))
            throw new ClassifierException("classifier: static: no recorded decision for this request+state");
        return Decision.FromJson(raw);
    }

    private async Task<Decision> EvaluateSystemOneAsync(object? state, IReadOnlyDictionary<string, Question> questions,
        CancellationToken cancellationToken)
        => Decision.FromJson(await PostAsync(Body(state, questions), cancellationToken).ConfigureAwait(false));

    /// <summary>
    /// The one POST, with the §8 retry budget — reusing the client's
    /// <see cref="LlmClient.ErrorInfo"/>/<see cref="LlmClient.Tier"/> classifier and the
    /// <c>Retry-After</c> delay-seconds rule verbatim.
    ///
    /// NO CREDENTIAL VALUE AND NO EXPANDED HEADER VALUE LEAVES HERE ON ANY PATH: an authentication
    /// failure names the status and the endpoint and nothing else, and a 401/403 body is never
    /// echoed back (a gateway happily reflects a bad Authorization header into its own 401 text).
    /// </summary>
    private async Task<string> PostAsync(byte[] payload, CancellationToken cancellationToken)
    {
        var endpoint = _baseUrl.TrimEnd('/') + "/systemone";
        var classify = _opts.OnError ?? (info => info.Retryable ? LlmClient.Tier.Retry : LlmClient.Tier.Fail);

        for (var attempt = 0; ; attempt++)
        {
            string? lastError = null;
            long? retryAfterMs = null;
            bool retryable;
            int? status = null;
            Exception? transport = null;

            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeoutCts.CancelAfter(_timeout);
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Post, endpoint)
                {
                    Content = new ByteArrayContent(payload),
                };
                req.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
                // Read at call time. Never logged, never returned, never in an exception.
                var key = Environment.GetEnvironmentVariable(_apiKeyEnv);
                if (!string.IsNullOrEmpty(key))
                    req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + key);
                foreach (var (hk, hv) in McpSource.ExpandEnvHeaders(_opts.Headers) ?? new Dictionary<string, string>())
                    req.Headers.TryAddWithoutValidation(hk, hv); // ${ENV_VAR} expanded at call time

                using var res = await _http.SendAsync(req, timeoutCts.Token).ConfigureAwait(false);
                var text = await res.Content.ReadAsStringAsync(timeoutCts.Token).ConfigureAwait(false);
                if (res.IsSuccessStatusCode) return text;
                status = (int)res.StatusCode;
                retryable = status == 408 || LlmClient.IsRetryableStatus(status.Value, _opts.RetryableStatuses);
                retryAfterMs = LlmClient.RetryAfterMs(res);
                // The backend's own cause is surfaced INTACT so a caller can tell a limit error from
                // a transport fault — but never for an auth status.
                lastError = $"classifier: POST {endpoint}: HTTP {status}{Cause(status.Value, text)}";
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw; // caller cancellation is never retried
            }
            catch (Exception e)
            {
                transport = e;
                retryable = true;
                // The message of a transport exception never contains a credential (the header value
                // is not part of the URI), but it is wrapped rather than rethrown so the endpoint is
                // always named.
                lastError = $"classifier: POST {endpoint}: {e.GetType().Name}";
            }

            if (attempt >= _retries) throw new ClassifierException(lastError!);
            if (classify(new LlmClient.ErrorInfo(transport, status, attempt, retryable)) != LlmClient.Tier.Retry)
                throw new ClassifierException(lastError!);

            var delay = retryAfterMs ?? (long)(500 * Math.Pow(2, attempt));
            if (delay > 0) await Task.Delay((int)Math.Min(delay, int.MaxValue), cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>Surfaces a backend's reported cause intact, EXCEPT on an authentication status: a
    /// 401/403 body routinely reflects the credential or the header that was sent, so it never
    /// reaches a log, a metric, an exception or a return value.</summary>
    private static string Cause(int status, string body)
    {
        if (status == 401 || status == 403) return "";
        var s = (body ?? "").Trim();
        if (s.Length == 0) return "";
        if (s.Length > 200) s = s[..200] + "…";
        return ": " + s;
    }

    /// <summary>
    /// Renders the three question types as ONE structured-output call on any §8 client — the
    /// vendor-neutral fallback, so a host with no System One credential runs the same questions on
    /// a cheap chat model. <c>Calibrated</c> is FALSE: the numbers are the model's self-report,
    /// not token probabilities.
    /// </summary>
    private async Task<Decision> EvaluateLlmAsync(object? state, IReadOnlyDictionary<string, Question> questions,
        CancellationToken cancellationToken)
    {
        var qs = new Dictionary<string, object?>();
        foreach (var (k, q) in questions) qs[k] = q.Wire();
        var prompt =
            "Answer every question about the state below. Questions are INDEPENDENT: "
            + "one answer is never context for another.\n\n"
            + "STATE:\n" + CanonicalJsonString(state) + "\n\nQUESTIONS:\n" + CanonicalJsonString(qs) + "\n\n"
            + "Reply with JSON only, no prose and no code fence, shaped exactly:\n"
            + "{\"answers\":{\"<key>\":{\"type\":\"noul\",\"noul\":0.0}}}\n"
            + "A \"noul\" answer is {\"type\":\"noul\",\"noul\":<0..1>}. A \"choice\" answer is "
            + "{\"type\":\"choice\",\"choice\":\"<one offered option id>\",\"probabilities\":{\"<every offered option id>\":<0..1>},\"confidence\":<0..1>}. "
            + "A \"score\" answer is {\"type\":\"score\",\"score\":<a number within the rubric bounds, fractional allowed>,"
            + "\"legend\":{\"0\":\"<level 0>\",…},\"probabilities\":{\"0\":<0..1>,…},\"confidence\":<0..1>}.";

        _emptyToolkit ??= await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false }, cancellationToken).ConfigureAwait(false);
        var run = await _opts.Client!.RunAsync(prompt, _emptyToolkit, null, cancellationToken).ConfigureAwait(false);
        var payload = FirstJsonObject(run.Text);
        var d = Decision.FromJson(payload);
        // The model reports no calibration and none is derived here. Never repaired, never asserted
        // as calibrated (ADR 0020).
        return d with
        {
            Calibrated = false,
            Model = string.IsNullOrEmpty(d.Model) ? _model : d.Model,
            Usage = new ClassifierUsage { InputTokens = run.Usage.PromptTokens, OutputTokens = run.Usage.CompletionTokens },
        };
    }

    /// <summary>Extracts the outermost JSON object from a model reply, which may arrive wrapped in
    /// a code fence or prose. It does NOT repair malformed JSON — an unparseable answer is no
    /// answer (ADR 0020).</summary>
    internal static string FirstJsonObject(string s)
    {
        var start = s?.IndexOf('{') ?? -1;
        var end = s?.LastIndexOf('}') ?? -1;
        if (start < 0 || end <= start) throw new ClassifierException("classifier: llm: no JSON object in the reply");
        return s!.Substring(start, end - start + 1);
    }

    // ---------------------------------------------------------------- metrics

    private void Emit(MetricEvent ev) => _opts.OnMetric?.Invoke(ev);

    /// <summary>Emits the terminal error event and returns the exception to throw, so every failure
    /// path is one statement and none can forget the metric.</summary>
    private Exception Fail(DateTimeOffset started, Exception e)
    {
        Emit(new MetricEvent
        {
            Event = MetricEvaluate,
            Model = _model,
            Status = "error",
            Ms = (long)(DateTimeOffset.UtcNow - started).TotalMilliseconds,
            Error = e.Message,
        });
        return e;
    }
}
