using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Toolnexus;

// Classifier backends, images, cache and validation (SPEC.md §8B "Backends, images, cache and
// validation", OpenSpec change add-classifier-backends, ADR 0038). Every default here is a
// PROPOSED default, owner to confirm (openspec/changes/add-classifier-backends/design.md
// PD-1..PD-15).

// ---------------------------------------------------------------- backends

/// <summary>What a backend+model can take. It is DATA: a fail-fast for what the table says,
/// never a claim about the vendor. Override per classifier with
/// <see cref="ClassifierOptions.Supports"/>.</summary>
public sealed record ClassifierSupports(bool Images = false);

/// <summary>One row of the backend table (<see cref="ClassifierBackends.Table"/>).</summary>
/// <param name="BaseUrl">Null on <see cref="ClassifierBackend.Custom"/>, which requires one.</param>
/// <param name="Wire"><see cref="ClassifierBackends.WireSystemOne"/> or <see cref="ClassifierBackends.WireCloudflare"/>.</param>
/// <param name="ImagesHeader">Send <c>TypeSafe-Danger-Zone-Images: true</c> when a request carries images.</param>
/// <param name="Supports">Default support for a model on this backend. Null on
/// <see cref="ClassifierBackend.Custom"/>, which requires <see cref="ClassifierOptions.Supports"/>.</param>
public sealed record ClassifierBackendPreset(
    string? BaseUrl, string Model, string ApiKeyEnv, string Wire, bool ImagesHeader,
    Func<string, ClassifierSupports>? Supports);

/// <summary>Thrown BEFORE any network call when the backend's support table says it cannot take
/// what the request carries. Message, exact: <c>classifier: &lt;backend&gt; (&lt;model&gt;) does not
/// support &lt;feature&gt;</c>.</summary>
public sealed class ClassifierUnsupportedException : ClassifierException
{
    public string Backend { get; }
    public string Model { get; }
    public string Feature { get; }

    public ClassifierUnsupportedException(string backend, string model, string feature)
        : base($"classifier: {backend} ({model}) does not support {feature}")
    {
        Backend = backend;
        Model = model;
        Feature = feature;
    }
}

/// <summary>The backend table and its constants (§8B).</summary>
public static class ClassifierBackends
{
    /// <summary>The two request shapes a backend row can use.</summary>
    public const string WireSystemOne = "systemone";
    public const string WireCloudflare = "cloudflare";

    /// <summary>TypeSafe's required opt-in for image ("danger zone") input.</summary>
    public const string ImagesHeader = "TypeSafe-Danger-Zone-Images";

    /// <summary>Image limits (docs.typesafe.ai/images). Outside them the API rejects, never resizes,
    /// so they are checked client-side before any request.</summary>
    public const int ImageMinSide = 280;
    public const int ImageMaxSide = 1568;
    public const int ImageMaxBytes = 16 * 1024 * 1024;
    public const int RequestImageBytes = 32 * 1024 * 1024;

    /// <summary>The backend table (researched and live-probed 2026-11-04, ADR 0038). Vercel and
    /// Cloudflare-Jev images stay false until a live probe proves them.</summary>
    public static readonly IReadOnlyDictionary<ClassifierBackend, ClassifierBackendPreset> Table =
        new Dictionary<ClassifierBackend, ClassifierBackendPreset>
        {
            [ClassifierBackend.TypeSafe] = new(Classifier.DefaultBaseUrl, Classifier.DefaultModel,
                Classifier.DefaultApiKeyEnv, WireSystemOne, true, _ => new ClassifierSupports(true)),
            [ClassifierBackend.OpenRouter] = new(Classifier.OpenRouterBaseUrl, Classifier.OpenRouterModel,
                Classifier.OpenRouterApiKeyEnv, WireSystemOne, true, _ => new ClassifierSupports(true)),
            [ClassifierBackend.Cloudflare] = new(
                "https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run", "typesafe/jev",
                "CLOUDFLARE_JEV_API_KEY", WireCloudflare, false,
                m => new ClassifierSupports(m.Contains("clef", StringComparison.Ordinal))),
            [ClassifierBackend.Vercel] = new("https://ai-gateway.vercel.sh/typesafe/v1", "typesafe-ai/jev",
                "AI_GATEWAY_API_KEY", WireSystemOne, true, _ => new ClassifierSupports(false)),
            [ClassifierBackend.Custom] = new(null, Classifier.DefaultModel, "",
                WireSystemOne, true, null),
        };

    private static string? HttpsHost(string? url)
        => Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == Uri.UriSchemeHttps
            ? u.IdnHost.ToLowerInvariant() : null;

    /// <summary>The env var whose key may be sent to <paramref name="expandedBaseUrl"/> (already
    /// ${NAME}-expanded): the backend's own variable, and only when the URL is that backend's own
    /// host over https (OpenRouter also its subdomains). "" when none may — custom has no preset
    /// key at all. A key is never sent to a host the caller did not name (#155, SPEC §8B).</summary>
    internal static string KeyEnv(ClassifierBackend backend, string? expandedBaseUrl)
    {
        if (!Table.TryGetValue(backend, out var p) || string.IsNullOrEmpty(p.ApiKeyEnv)) return "";
        var own = HttpsHost(p.BaseUrl);
        var h = HttpsHost(expandedBaseUrl);
        if (own == null || h == null) return "";
        return h == own || (backend == ClassifierBackend.OpenRouter && h.EndsWith(".openrouter.ai", StringComparison.Ordinal))
            ? p.ApiKeyEnv : "";
    }

    private static readonly (string Name, ClassifierBackend Value)[] Names =
    {
        ("typesafe", ClassifierBackend.TypeSafe), ("openrouter", ClassifierBackend.OpenRouter),
        ("cloudflare", ClassifierBackend.Cloudflare), ("vercel", ClassifierBackend.Vercel),
        ("custom", ClassifierBackend.Custom),
    };

    /// <summary>The wire name of a backend (<c>typesafe</c> when unset).</summary>
    public static string Name(ClassifierBackend b)
    {
        if (b == ClassifierBackend.Unset) return "typesafe";
        foreach (var (n, v) in Names) if (v == b) return n;
        return b.ToString();
    }

    /// <summary>Parses a wire name. An unknown name throws, listing all five.</summary>
    public static ClassifierBackend Parse(string name)
    {
        foreach (var (n, v) in Names) if (n == name) return v;
        throw new ClassifierException(UnknownBackendMessage(name));
    }

    internal static string UnknownBackendMessage(string name)
        => $"classifier: unknown backend \"{name}\" (want \"typesafe\", \"openrouter\", \"cloudflare\", \"vercel\" or \"custom\")";

    private static readonly Regex EnvRef = new(@"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", RegexOptions.CultureInvariant);

    /// <summary>Expands <c>${NAME}</c> only (not <c>$NAME</c>), at call time; unset ⇒ empty.</summary>
    internal static string ExpandEnvRefs(string s)
        => !s.Contains("${", StringComparison.Ordinal)
            ? s
            : EnvRef.Replace(s, m => Environment.GetEnvironmentVariable(m.Groups[1].Value) ?? "");
}

// ---------------------------------------------------------------- open union

/// <summary>The open end of the <see cref="Question"/> union: a System One question type the
/// library does not model (e.g. Jev 1.14 <c>box</c>), sent as written. No client-side limits.</summary>
public sealed record RawQuestion : Question
{
    /// <summary>The System One question type. Must be non-empty.</summary>
    public required string Type { get; init; }

    /// <summary>Sent verbatim when non-null, omitted when null.</summary>
    public object? Criteria { get; init; }

    internal override IReadOnlyDictionary<string, object?> Wire()
    {
        var m = new Dictionary<string, object?> { ["type"] = Type, ["instructions"] = Instructions };
        if (Criteria != null) m["criteria"] = Criteria;
        return m;
    }

    internal override void Validate(string key)
    {
        if (string.IsNullOrEmpty(Type))
            throw new ClassifierException($"classifier: question \"{key}\": a raw question needs a type");
    }
}

/// <summary>An answer of a type the library does not model. <see cref="Value"/> is the whole
/// answer object as received (including <c>type</c>).</summary>
public sealed record RawAnswer : DecisionAnswer
{
    public string Type { get; init; } = "";
    public JsonElement Value { get; init; }
    public override string AnswerType => Type;
}

// ---------------------------------------------------------------- cache

/// <summary>An injected answer cache (§8B). Values are opaque strings: the served model under a
/// model key, the System One reply body under a decision key. Implementations must be safe for
/// concurrent use. Async so a host can back it with a file, Redis, etc.</summary>
public interface IClassifierCache
{
    /// <summary>The value under <paramref name="key"/>, or null when absent.</summary>
    ValueTask<string?> GetAsync(string key, CancellationToken cancellationToken = default);

    ValueTask SetAsync(string key, string value, CancellationToken cancellationToken = default);
}

/// <summary>An in-process TTL cache with a size cap (oldest-inserted out first). TTL zero ⇒ no
/// expiry; max 0 ⇒ unbounded. toolnexus persists nothing itself.</summary>
public sealed class MemoryCache : IClassifierCache
{
    private readonly object _lock = new();
    private readonly Dictionary<string, (string Value, DateTimeOffset At, long Seq)> _m = new(StringComparer.Ordinal);
    private long _seq;

    public TimeSpan Ttl { get; }
    public int Max { get; }

    /// <summary>The clock; replaceable for tests.</summary>
    public Func<DateTimeOffset> Now { get; set; } = () => DateTimeOffset.UtcNow;

    public MemoryCache(TimeSpan ttl = default, int max = 0)
    {
        Ttl = ttl;
        Max = max;
    }

    public ValueTask<string?> GetAsync(string key, CancellationToken cancellationToken = default)
    {
        lock (_lock)
        {
            if (!_m.TryGetValue(key, out var e)) return ValueTask.FromResult<string?>(null);
            if (Ttl > TimeSpan.Zero && Now() - e.At > Ttl)
            {
                _m.Remove(key);
                return ValueTask.FromResult<string?>(null);
            }
            return ValueTask.FromResult<string?>(e.Value);
        }
    }

    public ValueTask SetAsync(string key, string value, CancellationToken cancellationToken = default)
    {
        lock (_lock)
        {
            _m[key] = (value, Now(), ++_seq);
            if (Max > 0 && _m.Count > Max)
                foreach (var k in _m.OrderBy(kv => kv.Value.Seq).Take(_m.Count - Max).Select(kv => kv.Key).ToList())
                    _m.Remove(k);
        }
        return ValueTask.CompletedTask;
    }
}

// ---------------------------------------------------------------- T1

/// <summary>jevx's INCLUSIVE cut-points (ADR 0038 T1), default 0.8 / 0.2 / 0.6. They sit alongside
/// <see cref="Bands"/> (exclusive), which stays the default of <c>Ask</c>/<c>Gate</c>.</summary>
public sealed record Thresholds(double Yes = 0.8, double No = 0.2, double MinConfidence = 0.6)
{
    public static readonly Thresholds Default = new();

    /// <summary>noul <c>p ≥ Yes</c> ⇒ yes, <c>p ≤ No</c> ⇒ no, else uncertain; choice/score
    /// <c>confidence ≥ MinConfidence</c> ⇒ yes, else uncertain. No near-uniform demotion. A raw
    /// answer is uncertain.</summary>
    public string Band(DecisionAnswer a) => a switch
    {
        NoulAnswer n when n.Noul >= Yes => "yes",
        NoulAnswer n when n.Noul <= No => "no",
        ChoiceAnswer c when c.Confidence >= MinConfidence => "yes",
        ScoreAnswer s when s.Confidence >= MinConfidence => "yes",
        _ => "uncertain",
    };
}

// ---------------------------------------------------------------- builders

/// <summary>A bound state; nothing is sent until <see cref="EvaluateAsync(Ask[])"/>. A value, not a session.</summary>
public sealed class ClassifierState
{
    private readonly Classifier _c;
    private readonly object? _v;
    internal ClassifierState(Classifier c, object? v) { _c = c; _v = v; }

    /// <summary>One request; the reply is validated fail-closed (<see cref="Classifier.ValidateDecision"/>).
    /// A duplicate question name throws before any request.</summary>
    public Task<Decision> EvaluateAsync(IEnumerable<Ask> questions, CancellationToken cancellationToken = default)
        => _c.EvaluateCoreAsync(_v, Judge.Questions(questions), validate: true, cancellationToken);

    public Task<Decision> EvaluateAsync(params Ask[] questions) => EvaluateAsync(questions, default);
}

/// <summary>One state's outcome in <see cref="ClassifierStates.EvaluateAsync(Ask[])"/>: exactly one
/// of <see cref="Decision"/> / <see cref="Error"/> is set.</summary>
public sealed record BatchResult(Decision? Decision, Exception? Error);

/// <summary>A batch of bound states.</summary>
public sealed class ClassifierStates
{
    private readonly Classifier _c;
    private readonly IReadOnlyList<object?> _vs;
    internal ClassifierStates(Classifier c, IReadOnlyList<object?> vs) { _c = c; _vs = vs; }

    /// <summary>Runs <c>State(v).EvaluateAsync</c> per state (at most
    /// <see cref="Classifier.BatchConcurrency"/> in flight) and returns one result per state, in
    /// order. One state's failure never fails the batch; only a question-set error (duplicate name)
    /// throws, before any request. No states ⇒ an empty list.</summary>
    public async Task<IReadOnlyList<BatchResult>> EvaluateAsync(IEnumerable<Ask> questions,
        CancellationToken cancellationToken = default)
    {
        var qs = Judge.Questions(questions);
        if (_vs.Count == 0) return Array.Empty<BatchResult>();
        using var gate = new SemaphoreSlim(Classifier.BatchConcurrency);
        var tasks = _vs.Select(async v =>
        {
            try
            {
                await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
                try { return new BatchResult(await _c.EvaluateCoreAsync(v, qs, true, cancellationToken).ConfigureAwait(false), null); }
                finally { gate.Release(); }
            }
            catch (Exception e) { return new BatchResult(null, e); }
        }).ToArray();
        return await Task.WhenAll(tasks).ConfigureAwait(false);
    }

    public Task<IReadOnlyList<BatchResult>> EvaluateAsync(params Ask[] questions) => EvaluateAsync(questions, default);
}

// ---------------------------------------------------------------- wire path

internal sealed record LiftedImage(string Ref, string MimeType, string Data)
{
    public string Format { get; set; } = "";
}

internal sealed record PreparedRequest(object? State, IReadOnlyList<LiftedImage> Images,
    Dictionary<string, object?>? Assets);

internal static class ClassifierImages
{
    /// <summary>The §8B lifting walk. A state with no content part is returned VERBATIM (the same
    /// object), so a text-only state is byte-identical to before.</summary>
    internal static (object? State, List<LiftedImage> Images) Lift(object? state)
    {
        var imgs = new List<LiftedImage>();
        if (!ContainsPart(state)) return (state, imgs);
        var used = new HashSet<string>(StringComparer.Ordinal);
        string Next()
        {
            for (var n = 1; ; n++)
            {
                var name = "image" + n.ToString(CultureInfo.InvariantCulture);
                if (!used.Contains(name)) return name;
            }
        }
        object? Walk(object? v, string key)
        {
            switch (v)
            {
                case ContentPart p:
                    p.Validate();
                    switch (p.Type)
                    {
                        case "text":
                            return p.Text;
                        case "image":
                            if (string.IsNullOrEmpty(p.Data))
                                throw new ClassifierException("classifier: state: an image part needs inline data (a URL image cannot be sent)");
                            var r = key.Length == 0 || used.Contains(key) ? Next() : key;
                            used.Add(r);
                            imgs.Add(new LiftedImage(r, p.MimeType ?? "", p.Data!));
                            return r;
                        default:
                            throw new ClassifierException($"classifier: state: {p.Type} parts are not supported");
                    }
                case string or JsonElement:
                    return v;
                case System.Collections.IDictionary d:
                {
                    var keys = new List<string>();
                    foreach (var k in d.Keys) keys.Add(k?.ToString() ?? "");
                    keys.Sort(StringComparer.Ordinal);
                    var o = new Dictionary<string, object?>();
                    foreach (var k in keys) o[k] = Walk(d[k], k);
                    return o;
                }
                case System.Collections.IEnumerable e:
                {
                    var l = new List<object?>();
                    foreach (var x in e) l.Add(Walk(x, ""));
                    return l;
                }
                default:
                    return v;
            }
        }
        var outState = Walk(state, "");
        return (outState, imgs);
    }

    private static bool ContainsPart(object? v)
    {
        switch (v)
        {
            case ContentPart:
                return true;
            case null or string or JsonElement:
                return false;
            case System.Collections.IDictionary d:
                foreach (var x in d.Values) if (ContainsPart(x)) return true;
                return false;
            case System.Collections.IEnumerable e:
                foreach (var x in e) if (ContainsPart(x)) return true;
                return false;
            default:
                return false;
        }
    }

    /// <summary>Format, per-image and per-request limits, in collection order. Errors name the
    /// reference. Normalises <see cref="LiftedImage.Format"/>.</summary>
    internal static void Check(IReadOnlyList<LiftedImage> imgs)
    {
        long total = 0;
        foreach (var im in imgs)
        {
            var format = im.MimeType switch
            {
                "image/png" => "png",
                "image/jpeg" or "image/jpg" => "jpeg",
                _ => "",
            };
            string Err(string s) => $"classifier: image \"{im.Ref}\": {s}";
            if (format.Length == 0)
                throw new ClassifierException(Err($"type \"{im.MimeType}\" is not supported (PNG or JPEG only)"));
            im.Format = format;
            byte[] raw;
            try { raw = Convert.FromBase64String(im.Data); }
            catch (FormatException) { throw new ClassifierException(Err("data is not base64")); }
            if (raw.Length > ClassifierBackends.ImageMaxBytes)
                throw new ClassifierException(Err($"{raw.Length} bytes, over 16 MiB"));
            if (Size(format, raw) is not var (w, h))
                throw new ClassifierException(Err($"not a readable {format.ToUpperInvariant()}"));
            if (w < ClassifierBackends.ImageMinSide || w > ClassifierBackends.ImageMaxSide
                || h < ClassifierBackends.ImageMinSide || h > ClassifierBackends.ImageMaxSide)
                throw new ClassifierException(Err(
                    $"{w}x{h}; each side must be {ClassifierBackends.ImageMinSide}..{ClassifierBackends.ImageMaxSide} px"));
            total += raw.Length;
        }
        if (total > ClassifierBackends.RequestImageBytes)
            throw new ClassifierException(
                $"classifier: image \"{imgs[^1].Ref}\": request images total {total} bytes, over 32 MiB");
    }

    /// <summary>Width/height from a PNG IHDR or the first JPEG SOF marker; null when unreadable.</summary>
    internal static (long W, long H)? Size(string format, byte[] b)
    {
        int Be16(int i) => (b[i] << 8) | b[i + 1];
        if (format == "png")
        {
            if (b.Length < 24 || !b.AsSpan(0, 8).SequenceEqual(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A })
                || Encoding.ASCII.GetString(b, 12, 4) != "IHDR")
                return null;
            long Be32(int i) => ((long)b[i] << 24) | ((long)b[i + 1] << 16) | ((long)b[i + 2] << 8) | b[i + 3];
            return (Be32(16), Be32(20));
        }
        if (b.Length < 4 || b[0] != 0xFF || b[1] != 0xD8) return null;
        var p = 2;
        while (p + 3 < b.Length)
        {
            if (b[p] != 0xFF) return null;
            var m = b[p + 1];
            if (m == 0xFF) { p++; continue; } // fill byte
            if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC)
            {
                if (p + 8 >= b.Length) return null;
                return (Be16(p + 7), Be16(p + 5));
            }
            p += 2 + Be16(p + 2);
        }
        return null;
    }
}

// ---------------------------------------------------------------- Classifier additions

public sealed partial class Classifier
{
    /// <summary>Binds <paramref name="state"/>; see <see cref="ClassifierState"/>.</summary>
    public ClassifierState State(object? state) => new(this, state);

    /// <summary>Binds a batch; see <see cref="ClassifierStates"/>.</summary>
    public ClassifierStates States(params object?[] states) => new(this, states);

    /// <summary>Binds a batch; see <see cref="ClassifierStates"/>.</summary>
    public ClassifierStates States(IEnumerable<object?> states) => new(this, states.ToList());

    private static string Sha256HexOf(byte[] b) => Convert.ToHexString(SHA256.HashData(b)).ToLowerInvariant();

    /// <summary>§8B model key: which model an endpoint+alias served last.</summary>
    public static string ModelCacheKey(string endpoint, string model)
        => Sha256HexOf(CanonicalJsonBytes(new Dictionary<string, object?>
        {
            ["endpoint"] = endpoint, ["kind"] = "model", ["model"] = model,
        }));

    /// <summary>§8B decision key, on the SERVED model. Null/empty <paramref name="assets"/> ⇒ the
    /// <c>assets</c> member is omitted.</summary>
    public static string DecisionCacheKey(string endpoint, string servedModel, object? state,
        IReadOnlyDictionary<string, object?>? assets, IReadOnlyDictionary<string, Question> questions)
    {
        var qs = new Dictionary<string, object?>();
        foreach (var (k, q) in questions) qs[k] = q.Wire();
        var m = new Dictionary<string, object?>
        {
            ["endpoint"] = endpoint, ["kind"] = "decision", ["model"] = servedModel, ["questions"] = qs, ["state"] = state,
        };
        if (assets is { Count: > 0 }) m["assets"] = assets;
        return Sha256HexOf(CanonicalJsonBytes(m));
    }

    /// <summary>
    /// V1: fails closed on a reply that does not answer exactly the questions asked within their
    /// schema (ADR 0038). First failure wins, in the §8B order. Returns null when valid, else the
    /// exception (message starts <c>classifier: invalid decision: </c>), not thrown.
    /// </summary>
    public static ClassifierException? ValidateDecision(IReadOnlyDictionary<string, Question> questions, Decision decision)
    {
        static ClassifierException Bad(string s) => new("classifier: invalid decision: " + s);
        static string F(double v) => v.ToString("R", CultureInfo.InvariantCulture);
        static bool In01(double p) => !double.IsNaN(p) && p >= 0 && p <= 1;
        var keys = questions.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();
        foreach (var k in keys)
            if (!decision.Answers.ContainsKey(k)) return Bad($"no answer \"{k}\"");
        var extra = decision.Answers.Keys.Where(k => !questions.ContainsKey(k)).OrderBy(k => k, StringComparer.Ordinal).ToList();
        if (extra.Count > 0) return Bad($"unexpected answer \"{extra[0]}\"");

        ClassifierException? Dist(string k, IReadOnlyDictionary<string, double> probs, Func<string, bool> offered)
        {
            var sum = 0.0;
            foreach (var o in probs.Keys.OrderBy(x => x, StringComparer.Ordinal))
            {
                if (!offered(o)) return Bad($"answer \"{k}\": probability for \"{o}\", which was not offered");
                if (!In01(probs[o])) return Bad($"answer \"{k}\": probability {F(probs[o])} outside [0,1]");
                sum += probs[o];
            }
            if (probs.Count > 0 && Math.Abs(sum - 1) > 0.02)
                return Bad($"answer \"{k}\": probabilities sum to {sum.ToString("F4", CultureInfo.InvariantCulture)}");
            return null;
        }

        foreach (var k in keys)
        {
            var q = questions[k];
            var a = decision.Answers[k];
            var qType = q switch
            {
                NoulQuestion => "noul",
                ChoiceQuestion => "choice",
                ScoreQuestion => "score",
                RawQuestion r => r.Type,
                _ => "",
            };
            if (a.AnswerType != qType) return Bad($"answer \"{k}\" is a {a.AnswerType} answer, not {qType}");
            switch (a)
            {
                case NoulAnswer n when !In01(n.Noul):
                    return Bad($"answer \"{k}\": noul {F(n.Noul)} outside [0,1]");
                case ChoiceAnswer c:
                {
                    var crit = (q as ChoiceQuestion)?.Criteria ?? new Dictionary<string, string>();
                    if (!crit.ContainsKey(c.Choice)) return Bad($"answer \"{k}\": choice \"{c.Choice}\" was not offered");
                    if (Dist(k, c.Probabilities, crit.ContainsKey) is { } e) return e;
                    if (!In01(c.Confidence)) return Bad($"answer \"{k}\": confidence {F(c.Confidence)} outside [0,1]");
                    break;
                }
                case ScoreAnswer s:
                {
                    var n = (q as ScoreQuestion)?.Criteria.Count ?? 0;
                    if (double.IsNaN(s.Score) || s.Score < 0 || s.Score > n - 1)
                        return Bad($"answer \"{k}\": score {F(s.Score)} outside [0,{n - 1}]");
                    bool Offered(string o)
                    {
                        for (var i = 0; i < n; i++) if (o == i.ToString(CultureInfo.InvariantCulture)) return true;
                        return false;
                    }
                    if (Dist(k, s.Probabilities, Offered) is { } e) return e;
                    if (!In01(s.Confidence)) return Bad($"answer \"{k}\": confidence {F(s.Confidence)} outside [0,1]");
                    break;
                }
            }
        }
        return null;
    }

    /// <summary>C1: splits a question set into chunks of <paramref name="size"/>, keys in ASCII
    /// order. A helper, not automatic: joint calibration across a split is unproven.</summary>
    public static IReadOnlyList<IReadOnlyDictionary<string, Question>> ChunkQuestions(
        IReadOnlyDictionary<string, Question> questions, int size)
    {
        if (size < 1) throw new ClassifierException($"classifier: chunk size must be at least 1, got {size}");
        return questions.Keys.OrderBy(k => k, StringComparer.Ordinal).Chunk(size)
            .Select(ks => (IReadOnlyDictionary<string, Question>)ks.ToDictionary(k => k, k => questions[k]))
            .ToList();
    }

    private PreparedRequest Prepare(object? state)
    {
        var (sent, imgs) = ClassifierImages.Lift(state);
        if (imgs.Count > 0 && !SupportsRow.Images)
            throw new ClassifierUnsupportedException(ClassifierBackends.Name(BackendName), Model, "images");
        ClassifierImages.Check(imgs);
        Dictionary<string, object?>? assets = null;
        if (imgs.Count > 0)
        {
            assets = new Dictionary<string, object?>();
            foreach (var im in imgs)
                assets[im.Ref] = new Dictionary<string, object?>
                {
                    ["type"] = "image", ["format"] = im.Format, ["data_base64"] = im.Data,
                };
        }
        return new PreparedRequest(sent, imgs, assets);
    }

    private byte[] WireBody(PreparedRequest p, IReadOnlyDictionary<string, Question> questions)
    {
        var qs = new Dictionary<string, object?>();
        foreach (var (k, q) in questions) qs[k] = q.Wire();
        IDictionary<string, object?> body;
        if (Preset.Wire == ClassifierBackends.WireCloudflare)
        {
            var input = new Dictionary<string, object?> { ["questions"] = qs, ["state"] = p.State };
            if (p.Images.Count > 0)
                input["images"] = p.Images.Select(im => (object?)$"data:image/{im.Format};base64,{im.Data}").ToList();
            body = new Dictionary<string, object?> { ["model"] = Model, ["input"] = input };
        }
        else
        {
            body = new Dictionary<string, object?> { ["model"] = Model, ["questions"] = qs, ["state"] = p.State };
            if (p.Assets != null) body["assets"] = p.Assets;
        }
        ApplyBodyPipeline(ref body);
        return CanonicalJsonBytes(body);
    }

    /// <summary>": &lt;code&gt; &lt;message&gt;" from errors[0] of a failed envelope (SPEC §8B), through
    /// the same redaction and 200-char cap as any provider cause; "" when there is nothing to show.</summary>
    private static string CloudflareCause(JsonElement root)
    {
        if (!root.TryGetProperty("errors", out var errs) || errs.ValueKind != JsonValueKind.Array
            || errs.GetArrayLength() == 0 || errs[0].ValueKind != JsonValueKind.Object) return "";
        var e0 = errs[0];
        var parts = new List<string>();
        if (e0.TryGetProperty("code", out var code))
        {
            var c = code.ValueKind switch
            {
                JsonValueKind.String => code.GetString() ?? "",
                JsonValueKind.Number => code.GetRawText(),
                _ => "",
            };
            if (c.Length > 0) parts.Add(c);
        }
        if (e0.TryGetProperty("message", out var msg) && msg.ValueKind == JsonValueKind.String
            && !string.IsNullOrEmpty(msg.GetString())) parts.Add(msg.GetString()!);
        var s = LlmClient.RedactBody(200, string.Join(" ", parts));
        return s.Length == 0 ? "" : ": " + s;
    }

    /// <summary>The System One reply inside a Cloudflare envelope. A failed envelope surfaces only
    /// errors[0] (redacted, capped) — Cloudflare reports a missing balance, a bad model id and a
    /// malformed request alike as HTTP 200 with success:false.</summary>
    internal static string UnwrapCloudflare(string text)
    {
        const string notOk = "classifier: cloudflare: envelope not successful";
        JsonDocument doc;
        try { doc = JsonDocument.Parse(text); }
        catch (JsonException) { throw new ClassifierException(notOk); }
        using (doc)
        {
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
                throw new ClassifierException(notOk);
            if (!root.TryGetProperty("success", out var ok) || ok.ValueKind != JsonValueKind.True)
                throw new ClassifierException(notOk + CloudflareCause(root));
            if (!root.TryGetProperty("result", out var res))
                throw new ClassifierException("classifier: cloudflare: no result");
            if (res.ValueKind == JsonValueKind.Object && res.TryGetProperty("state", out var st)
                && st.ValueKind == JsonValueKind.String)
            {
                if (st.GetString() != "Completed")
                    throw new ClassifierException($"classifier: cloudflare: run state \"{st.GetString()}\"");
                if (!res.TryGetProperty("result", out res))
                    throw new ClassifierException("classifier: cloudflare: no result");
            }
            return CanonicalJsonString(res);
        }
    }

    /// <summary>The systemone style: lift, check, cache, post, decode.</summary>
    private async Task<Decision> EvaluateWireAsync(object? state, IReadOnlyDictionary<string, Question> questions,
        bool validate, CancellationToken ct)
    {
        var p = Prepare(state);
        var cache = CacheOpt;
        string? modelKey = null;
        if (cache != null)
        {
            modelKey = ModelCacheKey(BaseUrl, Model);
            var served = await cache.GetAsync(modelKey, ct).ConfigureAwait(false);
            if (served != null)
            {
                var raw = await cache.GetAsync(DecisionCacheKey(BaseUrl, served, p.State, p.Assets, questions), ct)
                    .ConfigureAwait(false);
                if (raw != null)
                {
                    Decision? hit = null;
                    try { hit = Decision.FromJson(raw); }
                    catch (Exception) { /* a corrupt entry is a miss */ }
                    if (hit != null && ValidateDecision(questions, hit) == null) return hit with { Cached = true };
                }
            }
        }
        var payload = WireBody(p, questions);
        var extra = p.Images.Count > 0 && Preset.ImagesHeader
            ? new Dictionary<string, string> { [ClassifierBackends.ImagesHeader] = "true" }
            : null;
        var text = await PostAsync(payload, extra, ct).ConfigureAwait(false);
        if (Preset.Wire == ClassifierBackends.WireCloudflare) text = UnwrapCloudflare(text);
        var d = Decision.FromJson(text);
        var verr = ValidateDecision(questions, d);
        if (validate && verr != null) throw verr;
        if (cache != null && verr == null)
        {
            var served = string.IsNullOrEmpty(d.Model) ? Model : d.Model;
            await cache.SetAsync(modelKey!, served, ct).ConfigureAwait(false);
            await cache.SetAsync(DecisionCacheKey(BaseUrl, served, p.State, p.Assets, questions), text, ct)
                .ConfigureAwait(false);
        }
        return d;
    }
}
