using System.IO.Pipelines;
using Toolnexus.Agents;
using System.Text;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;
using ModelContextProtocol.Server;

namespace Toolnexus.Tests;

/// <summary>
/// §1B multimodal content parts — the model, the edge constructors, MCP passthrough, loop input,
/// §8A emission (allowlist + provenance + the tool-result relocation rule), §11 translation, the
/// <c>read</c> media table and MCP-inbound blocks.
///
/// <para>Hermetic: a recording localhost stub stands in for the provider, and an in-process
/// linked-stream pair for MCP. The base64 golden is READ from
/// <c>examples/media/fixture.png.base64</c> — never hardcoded, and never re-encoded from the PNG
/// by the test itself, because regeneration is not byte-stable across zlib versions.</para>
/// </summary>
public class MultimodalTests
{
    private static string FixturePng() => TestFixtures.Fixture("media/fixture.png");
    private static string Golden() => File.ReadAllText(TestFixtures.Fixture("media/fixture.png.base64")).Trim();

    /// <summary>A provider stub that records every request body and replays a canned script.</summary>
    private sealed class Upstream : IDisposable
    {
        private readonly StubServer _server;
        private readonly string[] _script;
        private int _n;

        public List<Dictionary<string, object?>> Sent { get; } = new();

        public Upstream(params string[] script)
        {
            _script = script;
            _server = new StubServer(ctx =>
            {
                using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
                lock (Sent) Sent.Add(Json.ParseObjectLoose(reader.ReadToEnd()));
                var i = Interlocked.Increment(ref _n) - 1;
                StubServer.Respond(ctx, 200, _script[Math.Min(i, _script.Length - 1)]);
            });
        }

        public string BaseUrl => _server.BaseUrl;
        public List<object?> Messages(int request) => Sent[request].Get("messages") as List<object?> ?? new List<object?>();
        public void Dispose() => _server.Dispose();
    }

    private static LlmClient Client(string baseUrl, string style, Action<LlmClient.Options>? tweak = null)
    {
        var opts = new LlmClient.Options { BaseUrl = baseUrl, Style = style, Model = "stub", ApiKey = "k" };
        tweak?.Invoke(opts);
        return LlmClient.Create(opts);
    }

    private static async Task<Toolkit> EmptyToolkit() =>
        await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });

    /// <summary>A toolkit with one tool whose result carries the given parts.</summary>
    private static async Task<Toolkit> ToolkitReturning(string name, string output, params ContentPart[] parts)
    {
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(NativeTool.Of(name, "returns parts", null,
            (IDictionary<string, object?> _) => ToolResult.OkWithParts(output, parts)));
        return tk;
    }

    private static Dictionary<string, object?> M(object? m) => (Dictionary<string, object?>)m!;
    private static List<object?> L(object? v) => (List<object?>)v!;

    // =====================================================================
    // The model + the edge constructors (§1B)
    // =====================================================================

    [Fact]
    public void Base64_MatchesTheCommittedGolden()
    {
        var part = ContentPart.FromFile(FixturePng());
        Assert.Equal("image", part.Type);
        Assert.Equal("image/png", part.MimeType);
        Assert.Equal(Golden(), part.Data);
    }

    [Fact]
    public async Task Base64_MatchesTheGolden_OnTheAsyncEdgeToo()
    {
        var part = await ContentPart.FromFileAsync(FixturePng());
        Assert.Equal(Golden(), part.Data);
    }

    [Fact]
    public void APathIsReadAtTheEdgeAndNeverStored()
    {
        var part = ContentPart.FromFile(FixturePng());
        Assert.Null(part.Url);
        Assert.NotNull(part.Data);
        // A persisted transcript replays without the file: the JSON carries no path field.
        var json = Json.Stringify(part);
        Assert.DoesNotContain("fixture.png\"", json.Replace("\"name\":\"fixture.png\"", ""));
        Assert.Contains("\"mimeType\":\"image/png\"", json);
    }

    [Fact]
    public void AFileInfoIsAcceptedAndReadAtTheEdge()
    {
        var part = ContentPart.FromFile(new FileInfo(FixturePng()));
        Assert.Equal("image", part.Type);
        Assert.Equal("image/png", part.MimeType);       // from .Extension, via the §6 table
        Assert.Equal(Golden(), part.Data);
        Assert.Equal("fixture.png", part.Name);
        Assert.Null(part.Url);
    }

    [Fact]
    public async Task AFileInfoWorksOnTheAsyncEdgeToo()
    {
        var part = await ContentPart.FromFileAsync(new FileInfo(FixturePng()));
        Assert.Equal(Golden(), part.Data);
    }

    [Fact]
    public void AFileStreamIsReadEagerlyAndTheMimeComesFromTheName()
    {
        using var fs = File.OpenRead(FixturePng());
        var part = ContentPart.FromStream(fs, name: "fixture.png");
        Assert.Equal("image/png", part.MimeType);
        Assert.Equal(Golden(), part.Data);
    }

    [Fact]
    public async Task AFileStreamWorksOnTheAsyncEdgeToo()
    {
        using var fs = File.OpenRead(FixturePng());
        var part = await ContentPart.FromStreamAsync(fs, "image/png");
        Assert.Equal(Golden(), part.Data);
    }

    [Fact]
    public void AMemoryStreamIsAcceptedWithAnExplicitMimeType()
    {
        using var ms = new MemoryStream(File.ReadAllBytes(FixturePng()));
        var part = ContentPart.FromStream(ms, "image/png");
        Assert.Equal(Golden(), part.Data);
    }

    /// <summary>A caller-supplied stream belongs to the caller; the edge reads it, never closes it.</summary>
    [Fact]
    public void ACallerSuppliedStreamIsNotDisposed()
    {
        var ms = new MemoryStream(File.ReadAllBytes(FixturePng()));
        var part = ContentPart.FromStream(ms, "image/png");
        Assert.Equal(Golden(), part.Data);
        Assert.True(ms.CanRead);                 // still open
        ms.Position = 0;
        Assert.Equal(82, ms.Read(new byte[82], 0, 82)); // and still usable
        ms.Dispose();                            // disposal stayed the owner's job
    }

    /// <summary>A pipe cannot be seeked or measured — the edge must read it forward.</summary>
    [Fact]
    public void ANonSeekableStreamStillWorks()
    {
        using var pipe = new ForwardOnlyStream(File.ReadAllBytes(FixturePng()));
        Assert.False(pipe.CanSeek);
        var part = ContentPart.FromStream(pipe, "image/png");
        Assert.Equal(Golden(), part.Data);
        Assert.Equal(0, pipe.SeekCalls);
        Assert.False(pipe.Disposed);
    }

    [Fact]
    public void AStreamPartCarriesNoHandleOrPath()
    {
        using var fs = File.OpenRead(FixturePng());
        var part = ContentPart.FromStream(fs, name: "fixture.png");
        var json = Json.Stringify(part);
        // Only mimeType + base64 data (+ the optional display name) survive the transcript.
        Assert.DoesNotContain(FixturePng(), json);
        Assert.DoesNotContain("Stream", json);
        Assert.Contains("\"mimeType\":\"image/png\"", json);
        Assert.Null(part.Url);
    }

    [Fact]
    public void AStreamWithNeitherMimeTypeNorKnownNameIsATypedError()
    {
        using var ms = new MemoryStream(new byte[] { 1, 2, 3 });
        Assert.Throws<ContentPart.InvalidPartException>(() => ContentPart.FromStream(ms));
        ms.Position = 0;
        var e = Assert.Throws<ContentPart.InvalidPartException>(() => ContentPart.FromStream(ms, name: "notes.xyz"));
        Assert.Contains(".xyz", e.Message);
    }

    [Fact]
    public void SpanAndMemoryBytesAgreeWithTheArrayEdge()
    {
        var bytes = File.ReadAllBytes(FixturePng());
        Assert.Equal(Golden(), ContentPart.FromBytes(bytes.AsSpan(), "image/png").Data);
        Assert.Equal(Golden(), ContentPart.FromBytes(new ReadOnlyMemory<byte>(bytes), "image/png").Data);
    }

    [Fact]
    public void AnOversizeStreamFailsFastAtTheEdge()
    {
        using var ms = new MemoryStream(new byte[2048]);
        Assert.Throws<ContentPart.InvalidPartException>(
            () => ContentPart.FromStream(ms, "image/png", maxPartBytes: 1024));
        using var pipe = new ForwardOnlyStream(new byte[2048]);
        Assert.Throws<ContentPart.InvalidPartException>(
            () => ContentPart.FromStream(pipe, "image/png", maxPartBytes: 1024));
    }

    /// <summary>A read-forward-only stream: no Length, no Seek, and it records both.</summary>
    private sealed class ForwardOnlyStream : Stream
    {
        private readonly MemoryStream _inner;
        public ForwardOnlyStream(byte[] bytes) => _inner = new MemoryStream(bytes);
        public int SeekCalls { get; private set; }
        public bool Disposed { get; private set; }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int offset, int count) => _inner.Read(buffer, offset, count);
        public override long Seek(long offset, SeekOrigin origin) { SeekCalls++; throw new NotSupportedException(); }
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() { }
        protected override void Dispose(bool disposing) { Disposed = true; base.Dispose(disposing); }
    }

    [Fact]
    public void ADataUrlIsNormalisedAtConstruction()
    {
        var b64 = Golden();
        var part = ContentPart.FromUrl($"data:image/png;base64,{b64}");
        Assert.Equal("image/png", part.MimeType);
        Assert.Equal(b64, part.Data);
        Assert.Null(part.Url); // never stored as a url
    }

    [Fact]
    public void AnHttpsUrlIsKeptAsUrl()
    {
        var part = ContentPart.FromUrl("https://example.com/shot.png");
        Assert.Equal("https://example.com/shot.png", part.Url);
        Assert.Null(part.Data);
        Assert.Equal("image/png", part.MimeType);
    }

    [Fact]
    public void AUrlPartRendersZeroBytesInTheCanonicalStrings()
    {
        // A part carrying `url` instead of `data` renders <bytes> as 0 — not empty, not the
        // URL's length — in both the "described in text" and unsupported-placeholder forms.
        var part = ContentPart.FromUrl("https://example.com/shot.png");
        Assert.Equal(0, part.ByteLength);
        Assert.Equal("image (image/png, 0 bytes)", part.DescribeInText());
        Assert.Equal("[unsupported image part (image/png, 0 bytes)]", part.UnsupportedPlaceholderText());
    }

    [Fact]
    public void APartWithBothDataAndUrlIsRejected()
    {
        var bad = new ContentPart { Type = "image", MimeType = "image/png", Data = "AA==", Url = "https://x/y.png" };
        var e = Assert.Throws<ContentPart.InvalidPartException>(() => bad.Validate());
        Assert.Contains("both data and url", e.Message);
    }

    [Fact]
    public void APartWithNeitherDataNorUrlIsRejected()
    {
        var bad = new ContentPart { Type = "image", MimeType = "image/png" };
        Assert.Contains("neither data nor url",
            Assert.Throws<ContentPart.InvalidPartException>(() => bad.Validate()).Message);
    }

    [Fact]
    public void AnUnknownExtensionIsRefusedByName()
    {
        var path = Path.Combine(Path.GetTempPath(), $"tn-{Guid.NewGuid():N}.xyz");
        File.WriteAllText(path, "hi");
        try
        {
            var e = Assert.Throws<ContentPart.InvalidPartException>(() => ContentPart.FromFile(path));
            Assert.Contains(".xyz", e.Message);
        }
        finally { File.Delete(path); }
    }

    [Fact]
    public void AnOversizedPartIsRejectedAtTheEdge()
    {
        var bytes = new byte[2 * 1024 * 1024];
        var e = Assert.Throws<ContentPart.InvalidPartException>(
            () => ContentPart.FromBytes(bytes, "image/png", maxPartBytes: 1048576));
        Assert.Contains("1048576", e.Message);          // the limit
        Assert.Contains("2097152", e.Message);          // the actual size
    }

    [Fact]
    public void APartRendersWithoutItsBytes()
    {
        var part = ContentPart.FromFile(FixturePng());
        var described = part.Describe();
        Assert.Contains("type:image", described);
        Assert.Contains("mimeType:image/png", described);
        Assert.Contains($"bytes:{part.ByteLength}", described);
        Assert.DoesNotContain(part.Data!, described);
        Assert.DoesNotContain(part.Data!, part.ToString());
    }

    [Fact]
    public void APartIsNotFreeToTheCompactor()
    {
        // A 2 MB image charged from its BYTE LENGTH, not from the 9-char mimeType string, which
        // would score it at ~3 tokens and make it uncompactable.
        var big = ContentPart.FromBytes(new byte[2 * 1024 * 1024], "image/png");
        Assert.Equal(2097152, big.ByteLength);
        Assert.True(big.EstimatedTokens > 2000, $"charged {big.EstimatedTokens} tokens");
        Assert.True(big.EstimatedTokens > ContentPart.FromBytes(new byte[64], "image/png").EstimatedTokens);
    }

    [Fact]
    public void TokenEstimatePinsBothBoundaries()
    {
        // max(85, floor(decodedBytes / 750)) — pinned at both edges so a regression to the old
        // floor of 8, or a drop of the max() altogether, fails loudly.
        var floorWins = ContentPart.FromBytes(new byte[82], "image/png");
        Assert.Equal(85, floorWins.EstimatedTokens); // floor(82/750) == 0, so the 85 floor wins

        var formulaWins = ContentPart.FromBytes(new byte[750_000], "image/png");
        Assert.Equal(1000, formulaWins.EstimatedTokens); // floor(750000/750) == 1000, above the floor
    }

    [Fact]
    public void ATranscriptHoldingAnImageIsChargedForIt()
    {
        var part = ContentPart.FromBytes(new byte[512 * 1024], "image/png");
        var withText = new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = "hi" } };
        var withImage = new List<object?>
        {
            new Dictionary<string, object?> { ["role"] = "user", ["content"] = new List<object?> { part } },
        };
        Assert.True(Compaction.EstimateTokens(withImage) > Compaction.EstimateTokens(withText) * 100);
    }

    // =====================================================================
    // ToolResult.Parts (§1B / D1)
    // =====================================================================

    [Fact]
    public void ToolResultWithoutParts_IsUnchanged()
    {
        var r = ToolResult.Ok("plain");
        Assert.Null(r.Parts);
        Assert.Equal("plain", r.Output);
        Assert.False(r.IsError);
    }

    [Fact]
    public void PartsDoNotCollideWithSuspension()
    {
        var pending = ToolResult.Pending(new Request { Kind = "input", Prompt = "who?" });
        var withParts = pending with { Parts = new[] { ContentPart.FromFile(FixturePng()) } };
        Assert.NotNull(ToolResult.PendingOf(withParts)); // §10 still sees the suspension
        Assert.Single(withParts.Parts!);
    }

    // =====================================================================
    // Loop input (§7)
    // =====================================================================

    private const string OpenAIDone = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";

    private const string AnthropicDone = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""";

    [Fact]
    public async Task TheStringPathIsUnchanged()
    {
        using var up = new Upstream(OpenAIDone);
        await using var tk = await EmptyToolkit();
        await Client(up.BaseUrl, "openai").RunAsync("hello", tk);
        var user = M(up.Messages(0)[0]);
        Assert.Equal("user", user.Get("role"));
        Assert.Equal("hello", user.Get("content")); // a bare string, exactly as before
    }

    [Fact]
    public async Task ATextOnlyPartsListIsByteIdenticalToTheStringPath()
    {
        using var up = new Upstream(OpenAIDone);
        await using var tk = await EmptyToolkit();
        await Client(up.BaseUrl, "openai").RunAsync(["hel", "lo"], tk);
        Assert.Equal("hello", M(up.Messages(0)[0]).Get("content"));
    }

    [Fact]
    public async Task OrderingIsPreserved()
    {
        using var up = new Upstream(OpenAIDone);
        await using var tk = await EmptyToolkit();
        await Client(up.BaseUrl, "openai")
            .RunAsync(["before", ContentPart.FromFile(FixturePng()), "after"], tk);

        var blocks = L(M(up.Messages(0)[0]).Get("content"));
        Assert.Equal(3, blocks.Count);
        Assert.Equal("text", M(blocks[0]).Get("type"));
        Assert.Equal("image_url", M(blocks[1]).Get("type"));
        Assert.Equal("text", M(blocks[2]).Get("type"));
        Assert.Equal($"data:image/png;base64,{Golden()}",
            M(M(blocks[1]).Get("image_url")).Get("url"));
    }

    [Fact]
    public async Task AnthropicGetsItsNativeImageBlock()
    {
        using var up = new Upstream(AnthropicDone);
        await using var tk = await EmptyToolkit();
        await Client(up.BaseUrl, "anthropic").RunAsync(["look", ContentPart.FromFile(FixturePng())], tk);

        var blocks = L(M(up.Messages(0)[0]).Get("content"));
        var img = M(blocks[1]);
        Assert.Equal("image", img.Get("type"));
        var src = M(img.Get("source"));
        Assert.Equal("base64", src.Get("type"));
        Assert.Equal("image/png", src.Get("media_type"));
        Assert.Equal(Golden(), src.Get("data"));
    }

    [Fact]
    public async Task StreamAndAskAcceptPartsToo()
    {
        // Both overloads exist, so parts are not run-only.
        using var up = new Upstream(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        await using var tk = await EmptyToolkit();
        var c = Client(up.BaseUrl, "openai");
        await c.StreamAsync([ContentPart.FromText("hi")], tk, _ => { });
        Assert.Equal("hi", M(up.Messages(0)[0]).Get("content"));

        using var up2 = new Upstream(OpenAIDone);
        await Client(up2.BaseUrl, "openai").AskAsync([ContentPart.FromText("hi")], tk, "conv-1");
        Assert.Equal("hi", M(up2.Messages(0)[0]).Get("content"));
    }

    // =====================================================================
    // §8A emission: allowlist, provenance, relocation
    // =====================================================================

    [Fact]
    public async Task AnAttachedAudioPartToAnthropicErrorsBeforeAnyHttpCall()
    {
        using var up = new Upstream(AnthropicDone);
        await using var tk = await EmptyToolkit();
        var audio = ContentPart.FromBytes(new byte[] { 1, 2, 3 }, "audio/mpeg");
        var e = await Assert.ThrowsAsync<ContentPart.InvalidPartException>(
            () => Client(up.BaseUrl, "anthropic").RunAsync(["listen", audio], tk));
        Assert.Contains("audio", e.Message);
        Assert.Contains("anthropic", e.Message);
        Assert.Empty(up.Sent); // no request was made
    }

    [Fact]
    public async Task AnAttachedFileUrlToOpenAIErrors()
    {
        // Chat Completions has no URL form for `file` — an explicit refusal, not a guess.
        using var up = new Upstream(OpenAIDone);
        await using var tk = await EmptyToolkit();
        var doc = ContentPart.FromUrl("https://example.com/report.pdf");
        await Assert.ThrowsAsync<ContentPart.InvalidPartException>(
            () => Client(up.BaseUrl, "openai").RunAsync(["read this", doc], tk));
        Assert.Empty(up.Sent);
    }

    [Fact]
    public async Task ToolDerivedAudioDegradesInsteadOfFailingTheRun()
    {
        const string call = """
        {"content":[{"type":"tool_use","id":"t1","name":"listen","input":{}}],"stop_reason":"tool_use"}
        """;
        using var up = new Upstream(call, AnthropicDone);
        await using var tk = await ToolkitReturning("listen", "a clip",
            ContentPart.FromBytes(new byte[] { 1, 2, 3 }, "audio/mpeg"));

        var r = await Client(up.BaseUrl, "anthropic").RunAsync("hear it", tk);
        Assert.Equal("done", r.Status); // the run is NOT failed by a volunteered part

        var toolTurn = M(up.Messages(1)[2]);
        var blocks = L(L(toolTurn.Get("content"))[0] is Dictionary<string, object?> d ? d.Get("content") : null);
        Assert.Equal("a clip", M(blocks[0]).Get("text"));
        var placeholder = M(blocks[1]).Get("text") as string ?? "";
        Assert.Equal("[unsupported audio part (audio/mpeg, 3 bytes)]", placeholder); // §SPEC §1B exact form
    }

    [Fact]
    public async Task TheOverrideForcesUniformStrictness()
    {
        const string call = """
        {"content":[{"type":"tool_use","id":"t1","name":"listen","input":{}}],"stop_reason":"tool_use"}
        """;
        using var up = new Upstream(call, AnthropicDone);
        await using var tk = await ToolkitReturning("listen", "a clip",
            ContentPart.FromBytes(new byte[] { 1, 2, 3 }, "audio/mpeg"));

        await Assert.ThrowsAsync<ContentPart.InvalidPartException>(
            () => Client(up.BaseUrl, "anthropic", o => o.OnUnsupportedPart = "error").RunAsync("hear it", tk));
    }

    [Fact]
    public async Task MaxPartBytesIsEnforcedOnTheWirePathToo()
    {
        using var up = new Upstream(OpenAIDone);
        await using var tk = await EmptyToolkit();
        var big = ContentPart.FromBytes(new byte[2 * 1024 * 1024], "image/png");
        var e = await Assert.ThrowsAsync<ContentPart.InvalidPartException>(
            () => Client(up.BaseUrl, "openai", o => o.MaxPartBytes = 1048576).RunAsync(["x", big], tk));
        Assert.Contains("1048576", e.Message);
        Assert.Empty(up.Sent);
    }

    [Fact]
    public async Task AnOversizeToolDerivedPartDegradesInsteadOfFailingTheRun()
    {
        // maxPartBytes is enforced at ASSEMBLY, over every part regardless of provenance — a
        // part an MCP server volunteered never passed through an edge constructor, so a limit
        // only checked there is not a limit (§1B). A tool-derived part that is over the limit
        // degrades to the canonical placeholder, exactly like an unrepresentable part, and the
        // run completes rather than failing.
        const string call = """
        {"content":[{"type":"tool_use","id":"t1","name":"shot","input":{}}],"stop_reason":"tool_use"}
        """;
        using var up = new Upstream(call, AnthropicDone);
        var big = ContentPart.FromBytes(new byte[2 * 1024 * 1024], "image/png");
        await using var tk = await ToolkitReturning("shot", "screenshot, 8x8 png", big);

        var r = await Client(up.BaseUrl, "anthropic", o => o.MaxPartBytes = 1048576).RunAsync("grab it", tk);
        Assert.Equal("done", r.Status); // the oversize part did NOT fail the run

        var toolTurn = M(up.Messages(1)[2]);
        var blocks = L(M(L(toolTurn.Get("content"))[0]).Get("content"));
        Assert.Equal("screenshot, 8x8 png", M(blocks[0]).Get("text"));
        Assert.Equal($"[unsupported image part (image/png, {big.ByteLength} bytes)]", M(blocks[1]).Get("text"));
    }

    [Fact]
    public async Task AnOversizeAttachedPartStillErrorsBeforeAnyHttpCall()
    {
        // The provenance split: ATTACHED is still a hard error, even though tool-DERIVED now
        // degrades — the caller can fix an attached part before sending; a remote MCP server
        // cannot be trusted to.
        using var up = new Upstream(AnthropicDone);
        await using var tk = await EmptyToolkit();
        var big = ContentPart.FromBytes(new byte[2 * 1024 * 1024], "image/png");
        var e = await Assert.ThrowsAsync<ContentPart.InvalidPartException>(
            () => Client(up.BaseUrl, "anthropic", o => o.MaxPartBytes = 1048576).RunAsync(["x", big], tk));
        Assert.Contains("1048576", e.Message);
        Assert.Empty(up.Sent);
    }

    [Fact]
    public async Task TheOversizeWarnFiresOnlyOnceAcrossMultipleDegradedParts()
    {
        var original = Console.Error;
        var captured = new StringWriter();
        Console.SetError(captured);
        try
        {
            const string call = """
            {"content":[{"type":"tool_use","id":"t1","name":"shot","input":{}}],"stop_reason":"tool_use"}
            """;
            using var up = new Upstream(call, AnthropicDone);
            var big1 = ContentPart.FromBytes(new byte[2 * 1024 * 1024], "image/png");
            var big2 = ContentPart.FromBytes(new byte[3 * 1024 * 1024], "image/png");
            await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
            tk.Register(NativeTool.Of("shot", "returns parts", null,
                (IDictionary<string, object?> _) => ToolResult.OkWithParts("two shots", new[] { big1, big2 })));

            var r = await Client(up.BaseUrl, "anthropic", o => o.MaxPartBytes = 1048576).RunAsync("grab both", tk);
            Assert.Equal("done", r.Status);

            var warnCount = captured.ToString()
                .Split('\n', StringSplitOptions.RemoveEmptyEntries)
                .Count(line => line.Contains("maxPartBytes"));
            Assert.Equal(1, warnCount); // latched — the second oversize part warns silently
        }
        finally
        {
            Console.SetError(original);
        }
    }

    [Fact]
    public async Task AnthropicReceivesTheImageInsideTheToolResult()
    {
        const string call = """
        {"content":[{"type":"tool_use","id":"t1","name":"shot","input":{}}],"stop_reason":"tool_use"}
        """;
        using var up = new Upstream(call, AnthropicDone);
        await using var tk = await ToolkitReturning("shot", "screenshot, 8x8 png", ContentPart.FromFile(FixturePng()));

        await Client(up.BaseUrl, "anthropic").RunAsync("grab it", tk);

        var toolTurn = M(up.Messages(1)[2]);
        Assert.Equal("user", toolTurn.Get("role"));
        var result = M(L(toolTurn.Get("content"))[0]);
        Assert.Equal("tool_result", result.Get("type"));
        Assert.Equal("t1", result.Get("tool_use_id")); // keyed to the tool_use_id
        var blocks = L(result.Get("content"));
        Assert.Equal("screenshot, 8x8 png", M(blocks[0]).Get("text"));
        Assert.Equal("image", M(blocks[1]).Get("type"));
        // and no synthetic user message was emitted
        Assert.Equal(3, up.Messages(1).Count);
    }

    [Fact]
    public async Task OpenAIReceivesOneSyntheticUserMessage()
    {
        const string call = """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"c1","type":"function","function":{"name":"shot","arguments":"{}"}},
          {"id":"c2","type":"function","function":{"name":"shot","arguments":"{}"}}
        ]},"finish_reason":"tool_calls"}]}
        """;
        using var up = new Upstream(call, OpenAIDone);
        await using var tk = await ToolkitReturning("shot", "screenshot, 8x8 png", ContentPart.FromFile(FixturePng()));

        var r = await Client(up.BaseUrl, "openai").RunAsync("grab two", tk);

        var wire = up.Messages(1);
        // user, assistant(tool_calls), tool, tool, synthetic user
        Assert.Equal(5, wire.Count);
        Assert.Equal("tool", M(wire[2]).Get("role"));
        Assert.Equal("screenshot, 8x8 png", M(wire[2]).Get("content")); // output text only
        Assert.Equal("tool", M(wire[3]).Get("role"));

        var synthetic = M(wire[4]);
        Assert.Equal("user", synthetic.Get("role"));
        var blocks = L(synthetic.Get("content"));
        Assert.Equal(4, blocks.Count); // label, image, label, image — in tool-call order
        Assert.Equal("Output of tool shot (c1):", M(blocks[0]).Get("text"));
        Assert.Equal("image_url", M(blocks[1]).Get("type"));
        Assert.Equal("Output of tool shot (c2):", M(blocks[2]).Get("text"));
        Assert.Equal("image_url", M(blocks[3]).Get("type"));

        // The synthetic message is an adapter artifact: it never enters the transcript.
        // user, assistant(tool_calls), tool, tool, assistant("ok") — and nothing else.
        Assert.Equal(5, r.Messages.Count);
        Assert.DoesNotContain(r.Messages, m => m is IDictionary<string, object?> d
            && (d.Get("role") as string) == "user"
            && d.Get("content") is List<object?>);
    }

    [Fact]
    public async Task ATextOnlyToolResultIsByteIdenticalOnTheOpenAIPath()
    {
        const string call = """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"c1","type":"function","function":{"name":"plain","arguments":"{}"}}
        ]},"finish_reason":"tool_calls"}]}
        """;
        using var up = new Upstream(call, OpenAIDone);
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(NativeTool.Of("plain", "text only", null, (IDictionary<string, object?> _) => "just text"));

        await Client(up.BaseUrl, "openai").RunAsync("go", tk);

        var wire = up.Messages(1);
        Assert.Equal(3, wire.Count);                       // no synthetic message at all
        Assert.Equal("just text", M(wire[2]).Get("content")); // a bare string, as before
    }

    // =====================================================================
    // MCP passthrough (§0.4 / §2)
    // =====================================================================

    private static List<ContentBlock> Blocks(params ContentBlock[] b) => b.ToList();

    [Fact]
    public void AScreenshotToolsImageSurvives()
    {
        var parts = McpSource.MapParts(Blocks(
            new TextContentBlock { Text = "here" },
            new ImageContentBlock { Data = Encoding.UTF8.GetBytes(Golden()), MimeType = "image/png" }), out var extra);
        Assert.Empty(extra);
        var part = Assert.Single(parts!);
        Assert.Equal("image", part.Type);
        Assert.Equal("image/png", part.MimeType);
        Assert.Equal(Golden(), part.Data);
    }

    [Fact]
    public void AResourceLinkBecomesAFilePart()
    {
        var parts = McpSource.MapParts(Blocks(
            new ResourceLinkBlock { Uri = "https://example.com/r.pdf", Name = "r.pdf", MimeType = "application/pdf" }),
            out _);
        var part = Assert.Single(parts!);
        Assert.Equal("file", part.Type);
        Assert.Equal("https://example.com/r.pdf", part.Url);
    }

    [Fact]
    public void AnEmbeddedBlobBecomesAFilePartAndEmbeddedTextGoesToOutput()
    {
        var parts = McpSource.MapParts(Blocks(
            new EmbeddedResourceBlock
            {
                Resource = new BlobResourceContents { Uri = "file://x.pdf", MimeType = "application/pdf", Blob = Encoding.UTF8.GetBytes("QUJD") },
            },
            new EmbeddedResourceBlock
            {
                Resource = new TextResourceContents { Uri = "file://n.txt", MimeType = "text/plain", Text = "a note" },
            }), out var extra);
        var part = Assert.Single(parts!);
        Assert.Equal("file", part.Type);
        Assert.Equal("QUJD", part.Data);
        Assert.Equal(new[] { "a note" }, extra);
    }

    [Fact]
    public void AudioBecomesAnAudioPart()
    {
        var parts = McpSource.MapParts(Blocks(
            new AudioContentBlock { Data = Encoding.UTF8.GetBytes("QUJD"), MimeType = "audio/mpeg" }), out _);
        Assert.Equal("audio", Assert.Single(parts!).Type);
    }

    [Fact]
    public void ATextOnlyMcpResultHasNoParts()
    {
        var parts = McpSource.MapParts(Blocks(new TextContentBlock { Text = "a" }, new TextContentBlock { Text = "b" }), out var extra);
        Assert.Null(parts);   // absent, so the ToolResult is byte-identical to today
        Assert.Empty(extra);
    }

    // =====================================================================
    // MCP inbound (§7C): a tool's parts become MCP content blocks
    // =====================================================================

    [Fact]
    public async Task AServedToolsImagePartBecomesAnMcpImageBlock()
    {
        var tool = NativeTool.Of("shot", "a screenshot", null,
            (IDictionary<string, object?> _) => ToolResult.OkWithParts("screenshot", new[] { ContentPart.FromFile(FixturePng()) }));

        var clientToServer = new Pipe();
        var serverToClient = new Pipe();
        var server = McpServe.BuildMcpServer(
            new StreamServerTransport(clientToServer.Reader.AsStream(), serverToClient.Writer.AsStream()),
            new[] { tool }, null, null);
        using var cts = new CancellationTokenSource();
        var run = server.RunAsync(cts.Token);
        var client = await McpClient.CreateAsync(
            new StreamClientTransport(clientToServer.Writer.AsStream(), serverToClient.Reader.AsStream()));
        try
        {
            var result = await client.CallToolAsync("shot", new Dictionary<string, object?>());
            Assert.False(result.IsError ?? false);
            Assert.Equal(2, result.Content.Count);
            Assert.Equal("screenshot", ((TextContentBlock)result.Content[0]).Text); // text first
            var img = Assert.IsType<ImageContentBlock>(result.Content[1]);          // then the image
            Assert.Equal("image/png", img.MimeType);
            Assert.Equal(Golden(), Encoding.UTF8.GetString(img.Data.Span));

            // And the source side maps it straight back to a ContentPart — a full round trip.
            var back = Assert.Single(McpSource.MapParts(result.Content, out _)!);
            Assert.Equal(Golden(), back.Data);
        }
        finally
        {
            await client.DisposeAsync();
            cts.Cancel();
            await server.DisposeAsync();
            try { await run; } catch { }
        }
    }

    // =====================================================================
    // §11 translate
    // =====================================================================

    [Fact]
    public void TranslateConcatenatesTextParts()
    {
        var content = new List<object?>
        {
            new Dictionary<string, object?> { ["type"] = "text", ["text"] = "part one " },
            new Dictionary<string, object?> { ["type"] = "text", ["text"] = "part two" },
        };
        Assert.Equal("part one part two", Translate.ContentToAnthropic(content));
    }

    [Fact]
    public void TranslatePreservesAnImagePartInOrder()
    {
        var content = new List<object?>
        {
            new Dictionary<string, object?> { ["type"] = "text", ["text"] = "look" },
            ContentPart.FromFile(FixturePng()),
        };
        var blocks = Assert.IsType<List<object?>>(Translate.ContentToAnthropic(content));
        Assert.Equal(2, blocks.Count);
        Assert.Equal("text", M(blocks[0]).Get("type"));
        var img = M(blocks[1]);
        Assert.Equal("image", img.Get("type"));
        Assert.Equal(Golden(), M(img.Get("source")).Get("data"));
    }

    [Fact]
    public void TranslateMapsAnOpenAIImageUrlBlock()
    {
        var content = new List<object?>
        {
            new Dictionary<string, object?>
            {
                ["type"] = "image_url",
                ["image_url"] = new Dictionary<string, object?> { ["url"] = $"data:image/png;base64,{Golden()}" },
            },
        };
        var blocks = Assert.IsType<List<object?>>(Translate.ContentToAnthropic(content));
        Assert.Equal("image", M(blocks[0]).Get("type"));
        Assert.Equal(Golden(), M(M(blocks[0]).Get("source")).Get("data"));
    }

    [Fact]
    public void TranslateNamesAnUnrepresentablePartRatherThanDroppingIt()
    {
        var content = new List<object?>
        {
            ContentPart.FromBytes(new byte[] { 1, 2, 3 }, "audio/mpeg"),
        };
        var blocks = Assert.IsType<List<object?>>(Translate.ContentToAnthropic(content));
        Assert.Contains("audio/mpeg", M(blocks[0]).Get("text") as string ?? "");
    }

    // =====================================================================
    // read (§6 media table)
    // =====================================================================

    private static async Task<ITool> ReadTool()
    {
        var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        return tk.Get("read")!;
    }

    [Fact]
    public async Task ReadingAPngYieldsAnImagePart()
    {
        var r = await (await ReadTool()).ExecuteAsync(new Dictionary<string, object?> { ["path"] = FixturePng() });
        Assert.False(r.IsError);
        var part = Assert.Single(r.Parts!);
        Assert.Equal("image", part.Type);
        Assert.Equal(Golden(), part.Data);
        Assert.Equal($"{FixturePng()} (image/png, {part.ByteLength} bytes)", r.Output); // §SPEC §1B exact form
    }

    [Fact]
    public async Task ReadingATextFileIsUnchanged()
    {
        var path = Path.Combine(Path.GetTempPath(), $"tn-{Guid.NewGuid():N}.md");
        await File.WriteAllTextAsync(path, "one\ntwo\nthree\nfour");
        try
        {
            var r = await (await ReadTool()).ExecuteAsync(new Dictionary<string, object?>
            {
                ["path"] = path, ["offset"] = 2L, ["limit"] = 2L,
            });
            Assert.Equal("two\nthree", r.Output);
            Assert.Null(r.Parts);
        }
        finally { File.Delete(path); }
    }

    [Fact]
    public async Task AnUnrecognisedBinaryYieldsAnErrorResultNotAnException()
    {
        var path = Path.Combine(Path.GetTempPath(), $"tn-{Guid.NewGuid():N}.bin");
        await File.WriteAllBytesAsync(path, new byte[] { 0xFF, 0xFE, 0xFD, 0x00, 0x80 });
        try
        {
            var r = await (await ReadTool()).ExecuteAsync(new Dictionary<string, object?> { ["path"] = path });
            Assert.True(r.IsError);
            Assert.Contains(path, r.Output);
            Assert.Null(r.Parts);
        }
        finally { File.Delete(path); }
    }
}
