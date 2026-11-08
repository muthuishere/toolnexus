using System.Diagnostics;
using System.Net.Http;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// Starts (or reuses, via <c>TN_MOCK_LLM_URL</c>) the shared mock model server
/// <c>tools/mock-llm/mock_llm.py</c> once for the class. Null <see cref="BaseUrl"/> = python3 missing.
/// </summary>
public sealed class MockLlmServer : IDisposable
{
    private readonly Process? _proc;
    public string? BaseUrl { get; }
    public string? SkipReason { get; }

    public MockLlmServer()
    {
        var env = Environment.GetEnvironmentVariable("TN_MOCK_LLM_URL");
        if (!string.IsNullOrEmpty(env)) { BaseUrl = env.TrimEnd('/'); return; }
        var script = FindScript();
        if (script is null) { SkipReason = "tools/mock-llm/mock_llm.py not found"; return; }
        try
        {
            _proc = Process.Start(new ProcessStartInfo("python3")
            {
                ArgumentList = { script, "--port", "0", "--quiet" },
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            });
        }
        catch (System.ComponentModel.Win32Exception)
        {
            SkipReason = "python3 is not available; skipping the mock-LLM integration suite";
            return;
        }
        var line = _proc!.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(15)).GetAwaiter().GetResult();
        const string prefix = "toolnexus mock model at ";
        if (line is null || !line.StartsWith(prefix))
            throw new InvalidOperationException("mock did not announce its URL: " + line);
        BaseUrl = line[prefix.Length..].Trim().TrimEnd('/');
    }

    private static string? FindScript()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var p = Path.Combine(dir.FullName, "tools", "mock-llm", "mock_llm.py");
            if (File.Exists(p)) return p;
            dir = dir.Parent;
        }
        return null;
    }

    public void Dispose()
    {
        if (_proc is { HasExited: false }) { try { _proc.Kill(true); _proc.WaitForExit(5000); } catch { } }
        _proc?.Dispose();
    }
}

/// <summary>
/// End-to-end against the real mock model server over real HTTP: client loop (OpenAI + Anthropic),
/// streaming, §1B attachments, §8 resilience, §8B classifier (custom + cloudflare) and §7G media.
/// Skips (passes with a message) when python3 is unavailable.
/// </summary>
public class MockLlmIntegrationTests : IClassFixture<MockLlmServer>
{
    private const string Reply = "This is a reply from the toolnexus mock model.";
    private readonly MockLlmServer _mock;
    private static readonly HttpClient Http = new();

    public MockLlmIntegrationTests(MockLlmServer mock) { _mock = mock; }

    private bool Skip()
    {
        if (_mock.BaseUrl != null) return false;
        Console.WriteLine("SKIP mock-LLM suite: " + _mock.SkipReason);
        return true;
    }

    private string Base => _mock.BaseUrl!;
    private string Origin => new Uri(Base).GetLeftPart(UriPartial.Authority);

    private LlmClient Client(string style) => LlmClient.Create(new LlmClient.Options
    {
        BaseUrl = Base, Style = style, Model = "mock", ApiKey = "mock-key", RetryBaseMs = 1,
    });

    private static async Task<(Toolkit, List<(double, double)>)> AddToolkit()
    {
        var calls = new List<(double, double)>();
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var schema = new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["a"] = new Dictionary<string, object?> { ["type"] = "integer" },
                ["b"] = new Dictionary<string, object?> { ["type"] = "integer" },
            },
            ["required"] = new List<object?> { "a", "b" },
        };
        tk.Register(NativeTool.Of("add", "add two integers", schema, args =>
        {
            var a = Convert.ToDouble(args["a"]?.ToString(), System.Globalization.CultureInfo.InvariantCulture);
            var b = Convert.ToDouble(args["b"]?.ToString(), System.Globalization.CultureInfo.InvariantCulture);
            lock (calls) calls.Add((a, b));
            return ((long)(a + b)).ToString();
        }));
        return (tk, calls);
    }

    private async Task<string> Get(string path) => await Http.GetStringAsync(Base + path);
    private async Task Post(string path, string json)
        => (await Http.PostAsync(Base + path, new StringContent(json, Encoding.UTF8, "application/json"))).EnsureSuccessStatusCode();

    [Theory]
    [InlineData("openai")]
    [InlineData("anthropic")]
    public async Task ClientRun_ExecutesTheMockToolCall(string style)
    {
        if (Skip()) return;
        var (tk, calls) = await AddToolkit();
        var r = await Client(style).RunAsync("add one and one", tk);
        Assert.Equal(new[] { (1.0, 1.0) }, calls);
        Assert.Equal("Tool result: 2", r.Text);
    }

    [Theory]
    [InlineData("openai")]
    [InlineData("anthropic")]
    public async Task Stream_ConcatenatedDeltasEqualTheReply(string style)
    {
        if (Skip()) return;
        var sb = new StringBuilder();
        var r = await Client(style).StreamAsync("hello", null, e =>
        {
            if (e.Type == LlmClient.StreamKind.Text) sb.Append(e.Delta);
        });
        Assert.Equal(Reply, sb.ToString());
        Assert.Equal(Reply, r.Text);
    }

    [Fact]
    public async Task Attachment_ImageBytesArriveIntact()
    {
        if (Skip()) return;
        var png = TestFixtures.Fixture("media/fixture.png");
        var sha = File.ReadAllText(TestFixtures.Fixture("media/fixture.png.sha256")).Trim()[..12];
        var r = await Client("openai").RunAsync(
            new List<ContentPart> { ContentPart.FromText("what is this?"), ContentPart.FromFile(png) }, null);
        Assert.Contains("image/png 82 bytes sha256=" + sha, r.Text);
    }

    [Fact]
    public async Task Resilience_RetriesOne429ThenSucceeds()
    {
        if (Skip()) return;
        await Post("/_mock/reset", "{}");
        await Post("/_mock/faults", """{"path":"/chat/completions","status":429,"times":1,"retryAfter":"0"}""");
        var r = await Client("openai").RunAsync("hello", null);
        Assert.Equal(Reply, r.Text);
        var counts = Json.ToMap(await Get("/_mock/requests"));
        var flat = Json.Stringify(counts);
        Assert.True(FindCount(counts, "/chat/completions") == 2, "expected 2 chat calls, got " + flat);
    }

    private static long? FindCount(object? node, string route)
    {
        if (node is Dictionary<string, object?> m)
        {
            foreach (var (k, v) in m)
            {
                if (k.EndsWith(route) && v is not Dictionary<string, object?> && v is not List<object?>)
                    return Convert.ToInt64(v);
                if (FindCount(v, route) is { } n) return n;
            }
        }
        return null;
    }

    private static readonly Dictionary<string, string> Options = new()
    {
        ["billing"] = "a payment or invoice problem",
        ["bug"] = "something is broken",
        ["other"] = "anything else",
    };

    private static async Task AssertClassifier(Classifier cl)
    {
        var asks = new[]
        {
            Judge.Noul("urgent", "Is this urgent?"),
            Judge.Choice("team", "Which team owns this?", Options),
            Judge.Score("severity", "How severe is it?", "low", "medium", "high"),
        };
        var d = await cl.State("my invoice was charged twice").EvaluateAsync(asks);
        Assert.Equal(0.95, d.Noul("urgent").Noul, 6);
        var c = d.Choice("team");
        Assert.Contains(c.Choice, Options.Keys);
        Assert.Equal(Options.Keys.OrderBy(k => k, StringComparer.Ordinal), c.Probabilities.Keys.OrderBy(k => k, StringComparer.Ordinal));
        Assert.Equal(1.0, c.Probabilities.Values.Sum(), 6);
        Assert.Equal(0.7, c.Probabilities[c.Choice], 6);
        var s = d.Score("severity");
        Assert.InRange(s.Score, 0.0, 2.0);
        Assert.Equal(1.0, s.Probabilities.Values.Sum(), 6);

        var again = await cl.State("my invoice was charged twice").EvaluateAsync(asks);
        Assert.Equal(c.Choice, again.Choice("team").Choice);
        Assert.Equal(s.Score, again.Score("severity").Score, 9);
    }

    [Fact]
    public async Task Classifier_CustomBackend_SystemOneWire()
    {
        if (Skip()) return;
        await AssertClassifier(new Classifier(new ClassifierOptions
        {
            Backend = ClassifierBackend.Custom, BaseUrl = Base, Supports = new ClassifierSupports(false),
            Model = "jev-mock", ApiKeyEnv = "TN_MOCK_UNSET_KEY", OnError = _ => LlmClient.Tier.Fail,
        }));
    }

    [Fact]
    public async Task Classifier_CloudflareBackend()
    {
        if (Skip()) return;
        await AssertClassifier(new Classifier(new ClassifierOptions
        {
            Backend = ClassifierBackend.Cloudflare, BaseUrl = Origin + "/v1/accounts/acc/ai/run",
            ApiKeyEnv = "TN_MOCK_UNSET_KEY", OnError = _ => LlmClient.Tier.Fail,
        }));
    }

    [Fact]
    public async Task Media_EveryVerbAndAnAsyncFailure()
    {
        if (Skip()) return;
        var tmp = Path.Combine(Path.GetTempPath(), "tn-mock-media-" + Guid.NewGuid().ToString("N") + ".json");
        await File.WriteAllTextAsync(tmp, await Get("/_mock/media-style.json"));
        try
        {
            var media = Media.Create(MediaStyle.Load(tmp));

            var img = await media.ImageAsync(new ImageInput("a red fox"));
            Assert.Equal("completed", img.Status);
            var f = Assert.Single(img.Files);
            Assert.EndsWith("/files/image.png", f.Url);
            Assert.Equal("image/png", f.MimeType);

            var vid = await media.VideoAsync(new VideoInput("a fox running"));
            Assert.EndsWith("/video.mp4", Assert.Single(vid.Files).Url);
            Assert.Equal("video/mp4", vid.Files[0].MimeType);

            var talk = await media.TalkingAsync(new TalkingInput(Base + "/files/image.png", Base + "/files/speech.wav"));
            Assert.Matches("/(video|talking)\\.mp4$", Assert.Single(talk.Files).Url);
            Assert.Equal("video/mp4", talk.Files[0].MimeType);

            var sp = await media.SpeechAsync(new SpeechInput("hello there"));
            Assert.EndsWith("/speech.wav", Assert.Single(sp.Files).Url);
            Assert.Equal("audio/wav", sp.Files[0].MimeType);

            var audio = Base + "/files/speech.wav";
            var tr = await media.TranscribeAsync(new TranscribeInput(audio));
            Assert.Equal("Mock transcript of " + audio + ".", tr.Text);

            var cv = await media.CloneVoiceAsync(new CloneVoiceInput("narrator", new[] { audio }));
            var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes("narrator"))).ToLowerInvariant();
            Assert.Equal("voice-" + hash[..8], cv.Text);

            var e = await Assert.ThrowsAsync<MediaException>(() => media.ImageAsync(new ImageInput("a fox [fail]")));
            Assert.Contains("mock: job asked to fail", e.Message);
        }
        finally { File.Delete(tmp); }
    }
}
