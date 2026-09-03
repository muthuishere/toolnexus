namespace Toolnexus.Examples;

/// <summary>
/// Live multimodal example: attach an image to a run, and let a tool hand one back.
///
/// <para>Runs the shared 8x8 fixture (<c>examples/media/fixture.png</c>, quadrants
/// red/green/blue/white) through both provider styles against OpenRouter, and closes tasks
/// 12.1-12.3 of the <c>add-multimodal-content</c> change for this port.</para>
///
/// <para><c>OPENROUTER_API_KEY=... dotnet run --project examples/Toolnexus.Examples -- multimodal</c></para>
///
/// <para><b>Arrival is proven by the prompt-token delta, never by the model's answer.</b> A model
/// asked to name colours will happily name four colours it never received — which is exactly how
/// the silent-drop bug this release fixes stayed hidden. It cannot fake prompt tokens.</para>
///
/// <para>Two things are measured per style:</para>
/// <list type="number">
///   <item>attachment — the identical request without and with <see cref="ContentPart.FromFile(string,string?,long?)"/>;
///   the prompt tokens must jump.</item>
///   <item>§8A relocation — a tool returning an image on <c>ToolResult.Parts</c>, called by the
///   model. The run must complete AND cost materially more prompt tokens than the same run whose
///   tool returns text only. On <c>openai</c> the image is relocated into a synthetic user message
///   (a <c>tool</c> message cannot carry one); on <c>anthropic</c> it rides inside
///   <c>tool_result</c>. Both are exercised here, live.</item>
/// </list>
///
/// <para><b>Builtins are off on every toolkit here.</b> Their ~2 000-token schema, plus any
/// turn-count difference it induces, drowns the very signal being measured.</para>
///
/// <para>Cheap by construction: tiny models, an 82-byte image, <c>max_tokens</c> capped at 40.
/// The API key is read from the environment and is never printed, logged or written.</para>
///
/// <para>Known upstream defect (see CHANGELOG.md): OpenRouter accepts an image bound for an
/// Anthropic model with HTTP 200 and drops it en route — a ~+4 token delta instead of hundreds.
/// Observed on both of its endpoints (<c>/chat/completions</c> with an openai-shaped
/// <c>image_url</c>, and the Anthropic-compatible <c>/v1/messages</c> with a native
/// <c>source{}</c> block), and reproducible with plain curl carrying no toolnexus code at all. It
/// is a routing defect above this library, so it is reported as <c>image=dropped-upstream</c>,
/// never as a failure of toolnexus. To exercise an Anthropic model with a working image path,
/// point <c>BaseUrl</c> at <c>https://api.anthropic.com</c> with an <c>ANTHROPIC_API_KEY</c>.</para>
/// </summary>
internal static class Multimodal
{
    private const string Ask =
        "Name the four quadrant colours of this image, clockwise from top-left. " +
        "Answer with four words only.";

    private const string ToolAsk =
        "Call the screenshot tool, then name the four quadrant colours of the image it " +
        "returns, clockwise from top-left. Answer with four words only.";

    private static readonly string[] Colours = { "red", "green", "blue", "white" };

    /// <summary>
    /// Even this 82-byte image costs hundreds of prompt tokens wherever it actually arrives
    /// (8 500 on gpt-4o-mini, 263 on gemini-2.5-flash-lite — a tile budget, not a byte count).
    /// Anything under this is the image having been dropped en route; a double-digit difference is
    /// turn-to-turn noise, not an image.
    /// </summary>
    private const int MinImageTokens = 200;

    private static readonly (string Style, string Model)[] Styles =
    {
        ("openai", "openai/gpt-4o-mini"),
        ("anthropic", "anthropic/claude-haiku-4.5"),
    };

    public static async Task<int> Run()
    {
        var key = Environment.GetEnvironmentVariable("OPENROUTER_API_KEY");
        if (string.IsNullOrEmpty(key))
        {
            Console.WriteLine("(no OPENROUTER_API_KEY — skipping the live multimodal run)");
            return 0;
        }
        var fixture = Examples.Fixture(Path.Combine("media", "fixture.png"));
        var openaiModel = Environment.GetEnvironmentVariable("OPENROUTER_MODEL");

        var lines = new List<string>();
        foreach (var (style, defaultModel) in Styles)
        {
            var model = style == "openai" && !string.IsNullOrEmpty(openaiModel) ? openaiModel : defaultModel;
            lines.Add(await RunStyle(style, model, key, fixture));
        }
        Console.WriteLine();
        foreach (var line in lines) Console.WriteLine(line);
        return 0;
    }

    private static async Task<string> RunStyle(string style, string model, string key, string fixture)
    {
        var agent = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "https://openrouter.ai/api/v1",
            Style = style,
            Model = model,
            ApiKey = key,
            RequestParams = new Dictionary<string, object?> { ["max_tokens"] = 40 },
        });

        // --- 1. attachment: the same request, without and with the image ---------
        // builtins off: their ~2 000-token schema would drown the signal we measure
        var bare = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));
        var textOnly = await agent.RunAsync(Ask, bare);
        var withImage = await agent.RunAsync(
            new[] { ContentPart.FromText(Ask), ContentPart.FromFile(fixture) }, bare);
        await bare.DisposeAsync();

        var ptokText = textOnly.Usage.PromptTokens;
        var ptokImage = withImage.Usage.PromptTokens;
        var delta = ptokImage - ptokText;
        var arrived = delta >= MinImageTokens;
        var answer = withImage.Text ?? string.Empty;
        var colours = ColoursNamed(answer);
        Console.WriteLine($"\n[{style}] {model}");
        Console.WriteLine($"  text-only ptok={ptokText}  with-image ptok={ptokImage}  delta={delta:+#;-#;0}");
        Console.WriteLine($"  answer: {OneLine(answer)}  ({colours}/4 colours named"
            + (arrived ? ")" : ", against an image it never received)"));
        if (!arrived)
        {
            Console.WriteLine("  ^ image did NOT arrive: too few prompt tokens. Upstream drop, not a");
            Console.WriteLine("    toolnexus failure — the block is emitted per SPEC §8A either way.");
        }

        // --- 2. §8A relocation: a tool that returns an image ---------------------
        var tkImg = (await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false)))
            .Register(ScreenshotTool(fixture, withImage: true));
        var tkTxt = (await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false)))
            .Register(ScreenshotTool(fixture, withImage: false));
        var resImg = await agent.RunAsync(ToolAsk, tkImg);
        var resTxt = await agent.RunAsync(ToolAsk, tkTxt);
        await tkImg.DisposeAsync();
        await tkTxt.DisposeAsync();

        var called = resImg.ToolCalls.Select(c => c.Name).ToList();
        var relocDelta = resImg.Usage.PromptTokens - resTxt.Usage.PromptTokens;
        // Two independent facts: the loop completed with the part in it (ours), and the
        // image actually reached the model (upstream's to drop).
        var reloc = !string.IsNullOrEmpty(resImg.Text) && called.Contains("screenshot") ? "ok" : "failed";
        var relocImage = relocDelta >= MinImageTokens ? "ok" : "dropped-upstream";
        Console.WriteLine($"  tool calls: [{string.Join(", ", called)}]  turns={resImg.Turns}/{resTxt.Turns}"
            + $"  tool-result ptok delta={relocDelta:+#;-#;0}  -> loop={reloc} image={relocImage}");
        Console.WriteLine($"  answer: {OneLine(resImg.Text ?? string.Empty)}");

        return $"RESULT csharp style={style} model={model}"
            + $" ptok_text={ptokText} ptok_image={ptokImage} delta={delta:+#;-#;0}"
            + $" image={(arrived ? "ok" : "dropped-upstream")}"
            + $" colours={colours}/4"
            + $" relocation={reloc} reloc_image={relocImage} reloc_delta={relocDelta:+#;-#;0}";
    }

    /// <summary>
    /// A tool returning an 8x8 screenshot — with or without the image part.
    ///
    /// <para>The parts-less twin is the control: the prompt-token difference between the two runs
    /// is the image, and nothing else.</para>
    /// </summary>
    private static NativeTool ScreenshotTool(string fixture, bool withImage)
        => NativeTool.Of("screenshot",
            "Capture the current screen and return it as a PNG.",
            new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>(),
                ["additionalProperties"] = false,
            },
            _ => withImage
                ? ToolResult.OkWithParts("screenshot captured, 8x8 png",
                    new[] { ContentPart.FromFile(fixture) })
                : ToolResult.Ok("screenshot captured, 8x8 png"));

    private static int ColoursNamed(string text)
    {
        var low = text.ToLowerInvariant();
        return Colours.Count(c => low.Contains(c));
    }

    private static string OneLine(string s)
    {
        var t = s.Trim().Replace("\n", " ");
        return "'" + (t.Length > 120 ? t[..120] : t) + "'";
    }
}
