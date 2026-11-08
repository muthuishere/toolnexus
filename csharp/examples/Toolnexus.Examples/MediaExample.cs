namespace Toolnexus.Examples;

/// <summary>
/// Media generation (SPEC §7G), the shared example contract E1 — both faces: the typed verbs
/// (<c>Media.ImageAsync</c>, <c>Media.SpeechAsync</c>) and the tool face (<c>Media.Tools()</c>,
/// invoked directly, as an LLM would).
/// <para><c>[ELEVENLABS_API_KEY=...] dotnet run --project examples/Toolnexus.Examples -- media</c></para>
/// Clients, in order, each only when its CLI is on PATH or its key is set: Grok CLI, Codex CLI,
/// ElevenLabs, then the paid fallbacks OpenAI, fal, A2E. Steps: (1) image, (2) ElevenLabs speech
/// (voice from <c>MEDIA_VOICE</c>), (3) tool face <c>generate_image</c>, (4) Grok video only with
/// <c>MEDIA_VIDEO=1</c>, (5) paid video / talk only with <c>MEDIA_PAID=1</c>. Exits non-zero when
/// any attempted step errors; with no client at all it prints a hint and exits 0. Keys are read from
/// the environment at call time and never printed.
/// </summary>
internal static class MediaExample
{
    private const string Prompt = "a red apple on a white table";

    public static async Task<int> Run()
    {
        var loop = new MediaLoop { OnProgress = p => Console.WriteLine($"  ..{p.Client} {p.Verb} {p.Status}") };
        MediaClientConfig Cfg() => new() { Loop = loop };

        var clients = new List<IMediaClient>();
        if (OnPath("grok")) clients.Add(new GrokMediaClient(Cfg()));
        if (OnPath("codex")) clients.Add(new CodexMediaClient(Cfg()));
        var eleven = HasEnv("ELEVENLABS_API_KEY");
        if (eleven) clients.Add(new ElevenLabsMediaClient(Cfg()));
        // Paid fallbacks: smallest size, lowest quality (OpenAI gpt-image-1-mini at 1024x1024 "low",
        // fal flux/schnell at its default size and 4 steps).
        if (HasEnv("OPENAI_API_KEY")) clients.Add(new OpenAIMediaClient(Cfg()));
        if (HasEnv("FAL_API_KEY")) clients.Add(new FalMediaClient(Cfg()));
        if (HasEnv("A2E_API_KEY")) clients.Add(new A2EMediaClient(Cfg()));

        if (clients.Count == 0)
        {
            Console.WriteLine("no media client available: install and log in to the grok or codex CLI, or set "
                + "ELEVENLABS_API_KEY / OPENAI_API_KEY / FAL_API_KEY / A2E_API_KEY (skipping: no call made)");
            return 0;
        }
        Console.WriteLine("clients: " + string.Join(", ", clients.Select(c => c.Name)));
        var media = Media.Create(clients);
        var failed = false;
        string? speechFile = null;

        async Task Step(string label, Func<Task> body)
        {
            Console.WriteLine($"== {label}");
            try { await body(); }
            catch (Exception e)
            {
                failed = true;
                Console.WriteLine($"  ERROR {label}: {e.Message}");
            }
        }

        // 1. image (typed verb)
        if (media.Supports("image"))
            await Step("image", async () => Print("image", await media.ImageAsync(new ImageInput(Prompt)), ClientOf(clients, "image")));

        // 2. speech through ElevenLabs, voice from MEDIA_VOICE, else the ElevenLabs default
        if (eleven)
            await Step("speech", async () =>
            {
                var voice = Environment.GetEnvironmentVariable("MEDIA_VOICE");
                var r = await Media.Create(clients.First(c => c.Name == "elevenlabs"))
                    .SpeechAsync(new SpeechInput("Hello from toolnexus.") { Voice = string.IsNullOrEmpty(voice) ? null : voice });
                Print("speech", r, "elevenlabs");
                speechFile = r.Files.FirstOrDefault()?.Path;
            });

        // 3. tool face: the same router as plain Tools an LLM would call
        var tools = media.Tools();
        Console.WriteLine("tools: " + string.Join(", ", tools.Select(t => t.Name)));
        if (tools.FirstOrDefault(t => t.Name == "generate_image") is { } gen)
            await Step("tool generate_image", async () =>
            {
                var res = await gen.ExecuteAsync(new Dictionary<string, object?> { ["prompt"] = Prompt });
                if (res.IsError) throw new MediaException(res.Output);
                var meta = new Dictionary<string, object?>(res.Metadata ?? new Dictionary<string, object?>());
                Console.WriteLine($"  client={meta.GetValueOrDefault("client")} verb={meta.GetValueOrDefault("verb")} output={res.Output.Replace("\n", " | ")}");
                foreach (var p in res.Parts ?? Array.Empty<ContentPart>())
                    Console.WriteLine($"  part {p.Type} {p.MimeType} {(p.Data != null ? Convert.FromBase64String(p.Data).Length + " bytes" : p.Url)}");
                if (meta.GetValueOrDefault("usage") is { } u) Console.WriteLine("  usage " + Json.Stringify(u));
            });

        // 4. Grok video, opt-in
        if (Environment.GetEnvironmentVariable("MEDIA_VIDEO") == "1" && clients.FirstOrDefault(c => c.Name == "grok") is { } grok)
            await Step("video (grok)", async () =>
                Print("video", await Media.Create(grok).VideoAsync(new VideoInput("an apple slowly rotating on a white table")), "grok"));

        // 5. paid video / talk, opt-in
        if (Environment.GetEnvironmentVariable("MEDIA_PAID") == "1")
        {
            var paid = clients.Where(c => c.Name is "openai" or "fal" && c.Supports("video")).ToList();
            if (paid.Count > 0)
                await Step("video (paid)", async () =>
                    Print("video", await Media.Create(paid).VideoAsync(new VideoInput("an apple slowly rotating") { Seconds = 4 }), ClientOf(paid, "video")));
            if (clients.FirstOrDefault(c => c.Name == "a2e") is { } a2e && speechFile != null)
                await Step("talk (a2e)", async () =>
                {
                    var face = await Media.Create(a2e).ImageAsync(new ImageInput("portrait photo of a friendly person, plain background"));
                    var url = face.Files[0].Url ?? throw new MediaException("talk: no face image");
                    Print("talk", await Media.Create(a2e).TalkAsync(new TalkInput(url) { Audio = speechFile }), "a2e");
                });
        }

        Console.WriteLine(failed ? "media example: FAILED (see errors above)" : "media example: ok");
        return failed ? 1 : 0;
    }

    /// <summary>The verb result's client is not on <see cref="MediaResult"/>; the tool face reports it in
    /// metadata. For the typed face print the first supporting candidate as the expected server.</summary>
    private static string ClientOf(IEnumerable<IMediaClient> clients, string verb)
        => clients.FirstOrDefault(c => c.Supports(verb))?.Name ?? "?";

    private static void Print(string verb, MediaResult r, string client)
    {
        Console.WriteLine($"  client={client} verb={verb} status={r.Status}");
        foreach (var f in r.Files)
        {
            long? size = f.Path != null && File.Exists(f.Path) ? new FileInfo(f.Path).Length
                : f.Data != null ? Convert.FromBase64String(f.Data).Length : null;
            Console.WriteLine($"  file {f.Path ?? f.Url ?? "(inline)"} mime={f.MimeType} size={(size?.ToString() ?? "-")}");
        }
        if (r.Usage?.ToMap() is { } u) Console.WriteLine("  usage " + Json.Stringify(u));
    }

    private static bool HasEnv(string name) => !string.IsNullOrEmpty(Environment.GetEnvironmentVariable(name));

    private static bool OnPath(string exe)
        => (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator)
            .Any(d => d.Length > 0 && File.Exists(Path.Combine(d, exe)));
}
