namespace Toolnexus.Examples;

/// <summary>
/// Media generation (SPEC §7G) against the shared a2e.ai preset — both faces: the typed verb
/// (<c>Media.ImageAsync</c>) and the tool face (<c>Media.Tools()</c>, invoked directly).
/// <para><c>A2E_API_KEY=... dotnet run --project examples/Toolnexus.Examples -- media</c></para>
/// Without <c>A2E_API_KEY</c> it prints a hint and exits 0, so hermetic runs never touch the
/// network. The key is read by the preset's <c>${A2E_API_KEY}</c> header and is never printed.
/// </summary>
internal static class MediaExample
{
    public static async Task<int> Run()
    {
        if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("A2E_API_KEY")))
        {
            Console.WriteLine("set A2E_API_KEY to run the media example (skipping: no network call made)");
            return 0;
        }

        var media = Media.Create(
            MediaStyle.Load(Examples.Fixture("media-styles/a2e.json")),
            new MediaOptions { OnProgress = p => Console.WriteLine($"  ..{p.Verb} {p.Status}") });

        // 1. Typed verb.
        var img = await media.ImageAsync(new ImageInput("a red fox in fresh snow, watercolor") { Width = 768, Height = 768 });
        Console.WriteLine($"image status: {img.Status}");
        foreach (var f in img.Files) Console.WriteLine($"  file: {f.Type} {f.MimeType} {f.Url}");

        // 2. Tool face — the same engine as plain Tools an LLM would call.
        var tools = media.Tools();
        foreach (var t in tools) Console.WriteLine($"tool: {t.Name}");
        var speech = tools.First(t => t.Name == "generate_speech");
        var res = await speech.ExecuteAsync(new Dictionary<string, object?> { ["text"] = "Hello from toolnexus." });
        Console.WriteLine($"speech output: {res.Output} isError: {res.IsError}");
        foreach (var p in res.Parts ?? []) Console.WriteLine($"  part: {p.Type} {p.MimeType} {p.Url}");
        return 0;
    }
}
