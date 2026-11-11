using System.IO;
using System.Text.Json.Nodes;
using Toolnexus.Acp;

namespace Toolnexus.Tests;

/// <summary>
/// SPEC §8C "ACP content parts": media travels as native ACP blocks in both directions —
/// outbound image parts become <c>image</c> blocks when the agent advertised
/// <c>promptCapabilities.image</c> (else only their description in the text), and inbound image
/// blocks (tool results, message chunks) become parts and <c>RunResult.Files</c>.
/// </summary>
public class AcpImageTests
{
    // A 1x1 transparent PNG (67 bytes).
    private static readonly byte[] Png = Convert.FromHexString(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
        + "890000000d49444154789c63000100000500010d0a2db40000000049454e44ae"
        + "426082");

    private static readonly string B64 = Convert.ToBase64String(Png);

    private static string FakeDll()
    {
        var p = Path.Combine(AppContext.BaseDirectory, "FakeAcpServer.dll");
        if (!File.Exists(p)) throw new InvalidOperationException("FakeAcpServer.dll not next to the tests");
        return p;
    }

    private static Task<AcpClient> Start(string scenario, Dictionary<string, string>? env = null) =>
        AcpClient.StartAsync(new AcpStartOptions
        {
            Command = "dotnet",
            Arguments = new[] { FakeDll(), $"--scenario={scenario}" },
            Cwd = Directory.GetCurrentDirectory(),
            Env = env,
        });

    /// <summary>Runs one turn whose user message carries a text part and an image part (OpenAI
    /// chat wire shape) and returns the session/prompt blocks the fake agent recorded.</summary>
    private static async Task<JsonArray> ImagePrompt(string scenario)
    {
        var rec = Path.Combine(Path.GetTempPath(), $"tn-acp-img-{Guid.NewGuid():N}.ndjson");
        try
        {
            using (var acp = await Start(scenario, new() { ["TN_FAKE_REC"] = rec }))
            {
                acp.Generate(new InProcess.Request
                {
                    Model = "acp",
                    Messages = new List<object?>
                    {
                        new Dictionary<string, object?>
                        {
                            ["role"] = "user",
                            ["content"] = new List<object?>
                            {
                                new Dictionary<string, object?> { ["type"] = "text", ["text"] = "what is in this picture?" },
                                new Dictionary<string, object?>
                                {
                                    ["type"] = "image_url",
                                    ["image_url"] = new Dictionary<string, object?> { ["url"] = "data:image/png;base64," + B64 },
                                },
                            },
                        },
                    },
                });
            }
            foreach (var line in File.ReadAllLines(rec))
            {
                if (line.Length == 0) continue;
                var r = JsonNode.Parse(line)!.AsObject();
                if (r["method"]!.GetValue<string>() == "session/prompt")
                    return r["params"]!["prompt"]!.AsArray();
            }
            throw new InvalidOperationException("fake agent recorded no session/prompt");
        }
        finally
        {
            File.Delete(rec);
        }
    }

    private static (string Text, List<JsonObject> Images) Split(JsonArray blocks)
    {
        var text = "";
        var images = new List<JsonObject>();
        foreach (var b in blocks)
        {
            var o = b!.AsObject();
            switch (o["type"]!.GetValue<string>())
            {
                case "text": text += o["text"]!.GetValue<string>(); break;
                case "image": images.Add(o); break;
            }
        }
        return (text, images);
    }

    [Fact]
    public async Task ImagePart_SentAsImageBlock_WhenAdvertised()
    {
        var (text, images) = Split(await ImagePrompt("image"));
        var img = Assert.Single(images);
        Assert.Equal("image/png", img["mimeType"]!.GetValue<string>());
        Assert.Equal(B64, img["data"]!.GetValue<string>());
        Assert.DoesNotContain(B64, text);
        Assert.Contains("what is in this picture?", text);
    }

    [Fact]
    public async Task ImagePart_PlaceholderOnly_WhenNotAdvertised()
    {
        var (text, images) = Split(await ImagePrompt("warm"));
        Assert.Empty(images);
        Assert.DoesNotContain(B64, text);
        Assert.Contains($"image (image/png, {Png.Length} bytes)", text);
    }

    private static void AssertFiles(IReadOnlyList<LlmClient.RunFile> files)
    {
        Assert.Equal(2, files.Count);
        Assert.Equal(("image", "image/png", B64, "screenshot", "t1"),
            (files[0].Type, files[0].MimeType, files[0].Data, files[0].From, files[0].CallId));
        Assert.Equal(("image", "image/png", B64, "acp", ""),
            (files[1].Type, files[1].MimeType, files[1].Data, files[1].From, files[1].CallId));
    }

    [Fact]
    public async Task InboundImages_BecomePartsAndFiles_Direct()
    {
        using var acp = await Start("image_out");
        var results = new List<LlmClient.StreamEvent>();
        var resp = await acp.GenerateStreamAsync(new InProcess.Request
        {
            Model = "acp",
            Messages = new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = "shoot" } },
        }, ev => { lock (results) if (ev.Type == LlmClient.StreamKind.ToolResult) results.Add(ev); });

        var tr = Assert.Single(results);
        Assert.Equal("image (image/png, 67 bytes)", tr.Output);
        var part = Assert.Single(tr.Parts!);
        Assert.Equal(("image", "image/png", B64), (part.Type, part.MimeType, part.Data));
        Assert.Equal("here", resp.Content);
        AssertFiles(resp.Files!);
    }

    [Fact]
    public async Task InboundImages_ReachRunResultFiles_ThroughInProcessClient()
    {
        using var acp = await Start("image_out");
        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "acp",
            GenerateStream = (req, emit, ct) => acp.GenerateStreamAsync(req, emit, ct),
        });

        var res = await client.RunAsync("shoot", null);
        Assert.Equal("here", res.Text);
        AssertFiles(res.Files);

        LlmClient.RunResult? done = null;
        await client.StreamAsync("shoot", null, ev => { if (ev.Type == LlmClient.StreamKind.Done) done = ev.Result; });
        Assert.NotNull(done);
        AssertFiles(done!.Files);
        Assert.Equal("t1", done.Files[0].CallId);
    }
}
