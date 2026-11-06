using System.Diagnostics;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using Xunit.Abstractions;

namespace Toolnexus.Tests;

/// <summary>
/// add-host-turn-durability: interrupted stateful turns are saved (SPEC §8 memory), builtin
/// <c>killGraceMs</c>, and edit/write/apply_patch <c>metadata.files</c> + <c>metadata.diff</c>.
/// Hermetic: a mock LLM handler, no network.
/// </summary>
public class InterruptedTurnTests
{
    /// <summary>Turn 1 asks for tools <c>a</c> and <c>b</c>; any later turn answers "done". Optional hard failure.</summary>
    private sealed class Wire : HttpMessageHandler
    {
        public bool Fail;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            if (Fail)
                return new HttpResponseMessage(HttpStatusCode.BadRequest)
                { Content = new StringContent("{\"error\":\"bad\"}", Encoding.UTF8, "application/json") };
            var body = Json.ParseObjectLoose(await request.Content!.ReadAsStringAsync(ct));
            var anthropic = request.RequestUri!.AbsolutePath.EndsWith("/messages");
            var stream = body.Get("stream") is true;
            string text;
            if (anthropic)
            {
                text = stream
                    ? Sse(
                        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"ta\",\"name\":\"a\"}}",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}",
                        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tb\",\"name\":\"b\"}}",
                        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":1}}",
                        "{\"type\":\"message_stop\"}")
                    : "{\"content\":[{\"type\":\"tool_use\",\"id\":\"ta\",\"name\":\"a\",\"input\":{}}," +
                      "{\"type\":\"tool_use\",\"id\":\"tb\",\"name\":\"b\",\"input\":{}}],\"stop_reason\":\"tool_use\"}";
            }
            else
            {
                text = stream
                    ? Sse(
                        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"ta\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]}}]}",
                        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"tb\",\"function\":{\"name\":\"b\",\"arguments\":\"{}\"}}]}}]}",
                        "[DONE]")
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" +
                      "{\"id\":\"ta\",\"type\":\"function\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}," +
                      "{\"id\":\"tb\",\"type\":\"function\",\"function\":{\"name\":\"b\",\"arguments\":\"{}\"}}]}}]}";
            }
            return new HttpResponseMessage(HttpStatusCode.OK)
            { Content = new StringContent(text, Encoding.UTF8, stream ? "text/event-stream" : "application/json") };
        }

        private static string Sse(params string[] data) => string.Concat(data.Select(d => "data: " + d + "\n\n"));
    }

    private static LlmClient Client(Wire w, string style, IConversationStore store)
        => LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://stub.invalid/v1", Style = style, Model = "m", ApiKey = "k",
            HttpClient = new HttpClient(w), Store = store, Retries = 0,
        });

    public static IEnumerable<object[]> Modes()
    {
        foreach (var style in new[] { "openai", "anthropic" })
            foreach (var streaming in new[] { false, true })
                yield return new object[] { style, streaming };
    }

    private static Task<LlmClient.RunResult> Go(LlmClient c, string prompt, Toolkit tk, string id, bool streaming, CancellationToken ct)
        => streaming ? c.StreamAsync(prompt, tk, _ => { }, id, ct) : c.AskAsync(prompt, tk, id, null, ct);

    private static IDictionary<string, object?> M(object? o) => (IDictionary<string, object?>)o!;

    [Theory]
    [MemberData(nameof(Modes))]
    public async Task CancelledMidSecondToolKeepsPromptAndFinishedPairs(string style, bool streaming)
    {
        var aDone = new TaskCompletionSource();
        var bStarted = new TaskCompletionSource();
        var a = NativeTool.Of("a", "first", null, _ => { aDone.TrySetResult(); return "A-OUT"; });
        var b = NativeTool.OfAsync("b", "second", null, async (_, ctx) =>
        {
            await aDone.Task;
            await Task.Delay(100); // let the loop record a's finished result before the cancel lands
            bStarted.TrySetResult();
            await Task.Delay(Timeout.Infinite, ctx?.CancellationToken ?? default);
            return "never";
        });
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = new List<ITool> { a, b } });
        var store = new InMemoryConversationStore();
        var c = Client(new Wire(), style, store);
        using var cts = new CancellationTokenSource();
        _ = bStarted.Task.ContinueWith(_ => cts.Cancel());

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Go(c, "do it", tk, "t1", streaming, cts.Token));

        var saved = (await store.GetAsync("t1"))!;
        Assert.Equal("user", M(saved[0])["role"]);
        Assert.Equal("do it", M(saved[0])["content"]);
        Assert.Equal("assistant", M(saved[1])["role"]);
        if (style == "openai")
        {
            Assert.Equal(4, saved.Count);
            Assert.Equal(2, ((List<object?>)M(saved[1])["tool_calls"]!).Count);
            Assert.Equal(("tool", "ta", "A-OUT"), ((string)M(saved[2])["role"]!, (string)M(saved[2])["tool_call_id"]!, M(saved[2])["content"]));
            Assert.Equal(("tool", "tb", "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first."), ((string)M(saved[3])["role"]!, (string)M(saved[3])["tool_call_id"]!, M(saved[3])["content"]));
        }
        else
        {
            Assert.Equal(3, saved.Count);
            Assert.Equal("user", M(saved[2])["role"]);
            var blocks = ((List<object?>)M(saved[2])["content"]!).Select(M).ToList();
            Assert.Equal(2, blocks.Count);
            Assert.Equal(("tool_result", "ta", false), ((string)blocks[0]["type"]!, (string)blocks[0]["tool_use_id"]!, (bool)blocks[0]["is_error"]!));
            Assert.Equal(("tool_result", "tb", true), ((string)blocks[1]["type"]!, (string)blocks[1]["tool_use_id"]!, (bool)blocks[1]["is_error"]!));
            Assert.Contains("interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.", Json.Stringify(blocks[1]["content"]));
            Assert.Contains("A-OUT", Json.Stringify(blocks[0]["content"]));
        }
    }

    [Theory]
    [MemberData(nameof(Modes))]
    public async Task ProviderErrorSavesPriorPlusPrompt(string style, bool streaming)
    {
        var store = new InMemoryConversationStore();
        var prior = new List<object?>
        {
            new Dictionary<string, object?> { ["role"] = "user", ["content"] = "old" },
            new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = "old reply" },
        };
        await store.SaveAsync("t2", prior);
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var c = Client(new Wire { Fail = true }, style, store);

        await Assert.ThrowsAnyAsync<Exception>(() => Go(c, "new q", tk, "t2", streaming, default));

        var saved = (await store.GetAsync("t2"))!;
        Assert.Equal(3, saved.Count);
        Assert.Equal("old", M(saved[0])["content"]);
        Assert.Equal("old reply", M(saved[1])["content"]);
        Assert.Equal(("user", "new q"), ((string)M(saved[2])["role"]!, M(saved[2])["content"]));
    }

    [Fact]
    public async Task StreamToolResultEventCarriesMetadata()
    {
        // b answers normally here: a single turn of tools, then the mock repeats tool calls up to MaxTurns.
        var a = NativeTool.Of("a", "first", null, _ => ToolResult.Ok("A", new Dictionary<string, object?> { ["k"] = "v" }));
        var b = NativeTool.Of("b", "second", null, _ => "B");
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = new List<ITool> { a, b } });
        var c = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://stub.invalid/v1", Style = "openai", Model = "m", ApiKey = "k",
            HttpClient = new HttpClient(new Wire()), MaxTurns = 1,
        });
        var events = new List<LlmClient.StreamEvent>();
        await c.StreamAsync("go", tk, events.Add);
        var ra = events.First(e => e.Type == LlmClient.StreamKind.ToolResult && e.Name == "a");
        Assert.Equal("v", ra.Metadata!["k"]);
    }
}

public class KillGraceTests
{
    private readonly ITestOutputHelper _out;
    public KillGraceTests(ITestOutputHelper output) => _out = output;

    [DllImport("libc", SetLastError = true)]
    private static extern int kill(int pid, int sig);

    private static bool Alive(int pid) => kill(pid, 0) == 0;

    [Fact]
    public async Task ShortGraceKillsTermIgnoringJobAndDescendantFast()
    {
        if (OperatingSystem.IsWindows()) return; // killGraceMs has no effect on Windows
        var dir = Path.Combine(Path.GetTempPath(), "tn-grace-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(dir);
        var bash = BuiltinTools.Create(new Dictionary<string, object?> { ["killGraceMs"] = 100L }).First(t => t.Name == "bash");
        var timings = new List<double>();
        try
        {
            for (var run = 0; run < 10; run++)
            {
                var pidFile = Path.Combine(dir, $"pids{run}");
                // The shell and both background sleeps ignore TERM (an ignored signal is inherited).
                var cmd = $"trap '' TERM; sleep 30 & echo $! >> {pidFile}; sleep 30 & echo $! >> {pidFile}; echo $$ >> {pidFile}; wait";
                using var cts = new CancellationTokenSource();
                var task = bash.ExecuteAsync(new Dictionary<string, object?> { ["command"] = cmd, ["timeout"] = 60000L },
                    new ToolContext(cancellationToken: cts.Token));
                var deadline = DateTime.UtcNow.AddSeconds(5);
                // Wait for three COMPLETE lines: a read between the shell's write of the digits and
                // of the newline would otherwise parse a truncated pid.
                static bool Complete(string f)
                {
                    if (!File.Exists(f)) return false;
                    var text = File.ReadAllText(f);
                    return text.EndsWith('\n') && text.Split('\n', StringSplitOptions.RemoveEmptyEntries).Length >= 3;
                }
                while (!Complete(pidFile) && DateTime.UtcNow < deadline)
                    await Task.Delay(10);
                var pids = File.ReadAllLines(pidFile).Select(int.Parse).ToList();
                Assert.Equal(3, pids.Count);
                Assert.All(pids, p => Assert.True(Alive(p)));

                var sw = Stopwatch.StartNew();
                cts.Cancel();
                while (pids.Any(Alive) && sw.ElapsedMilliseconds < 5000) await Task.Delay(2);
                var elapsed = sw.Elapsed.TotalMilliseconds;
                var result = await task;
                Assert.True(result.IsError);
                Assert.False(pids.Any(Alive), "a job process survived");
                // The claim is "killGraceMs is honoured, not the 2000 ms default": any time clearly
                // below the default proves it. 1000 ms was tighter than that claim and failed when
                // a loaded machine was slow to reap the reparented sleeps.
                Assert.True(elapsed < 1500, $"job gone after {elapsed:F0} ms (default grace is 2000 ms)");
                timings.Add(elapsed);
            }
        }
        finally
        {
            try { Directory.Delete(dir, true); } catch { }
        }
        timings.Sort();
        _out.WriteLine($"killGraceMs=100 cancel->gone ms: min={timings[0]:F1} median={(timings[4] + timings[5]) / 2:F1} max={timings[^1]:F1} all=[{string.Join(", ", timings.Select(t => t.ToString("F1")))}]");
    }
}

public class EditDiffTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "tn-diff-" + Guid.NewGuid().ToString("N")[..8]);
    public EditDiffTests() => Directory.CreateDirectory(_dir);
    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    private ITool Tool(string name) => BuiltinTools.Create(new Dictionary<string, object?> { ["baseDir"] = _dir }).First(t => t.Name == name);

    private static List<IDictionary<string, object?>> Files(ToolResult r)
        => ((List<object?>)r.Metadata!["files"]!).Select(f => (IDictionary<string, object?>)f!).ToList();

    private static void AssertFile(IDictionary<string, object?> f, string path, string status, long add, long del)
        => Assert.Equal((path, status, add, del), ((string)f["path"]!, (string)f["status"]!, (long)f["additions"]!, (long)f["deletions"]!));

    [Fact]
    public async Task EditOneLineIsModifiedOneOne()
    {
        File.WriteAllText(Path.Combine(_dir, "f.txt"), "one\ntwo\nthree\n");
        var r = await Tool("edit").ExecuteAsync(new Dictionary<string, object?> { ["path"] = "f.txt", ["oldString"] = "two", ["newString"] = "TWO" });
        Assert.False(r.IsError, r.Output);
        Assert.Equal("Edited f.txt (1 replacement)", r.Output);
        var files = Files(r);
        Assert.Single(files);
        AssertFile(files[0], "f.txt", "modified", 1, 1);
        var diff = (string)r.Metadata!["diff"]!;
        Assert.Contains("--- a/f.txt\n+++ b/f.txt\n", diff);
        Assert.Contains("\n-two\n", diff);
        Assert.Contains("\n+TWO\n", diff);
        Assert.Contains("@@ -1,3 +1,3 @@", diff);
    }

    [Fact]
    public async Task WriteNewThreeLineFileIsAdded()
    {
        var r = await Tool("write").ExecuteAsync(new Dictionary<string, object?> { ["path"] = "sub/n.txt", ["content"] = "a\nb\nc\n" });
        Assert.False(r.IsError, r.Output);
        AssertFile(Files(r).Single(), "sub/n.txt", "added", 3, 0);
        var diff = (string)r.Metadata!["diff"]!;
        Assert.Contains("--- /dev/null\n+++ b/sub/n.txt\n@@ -0,0 +1,3 @@\n+a\n+b\n+c\n", diff);
    }

    [Fact]
    public async Task WriteOverExistingIsModified()
    {
        File.WriteAllText(Path.Combine(_dir, "e.txt"), "x\ny\n");
        var r = await Tool("write").ExecuteAsync(new Dictionary<string, object?> { ["path"] = "e.txt", ["content"] = "x\nz\nw\n" });
        AssertFile(Files(r).Single(), "e.txt", "modified", 2, 1);
    }

    [Fact]
    public async Task ApplyPatchAddAndDeleteFiles()
    {
        File.WriteAllText(Path.Combine(_dir, "old.txt"), "gone1\ngone2\n");
        var patch = "*** Begin Patch\n*** Add File: new.txt\n+hello\n+world\n*** Delete File: old.txt\n*** End Patch";
        var r = await Tool("apply_patch").ExecuteAsync(new Dictionary<string, object?> { ["patchText"] = patch });
        Assert.False(r.IsError, r.Output);
        Assert.Equal("Applied patch: 2 file operations", r.Output);
        var files = Files(r);
        Assert.Equal(2, files.Count);
        AssertFile(files[0], "new.txt", "added", 2, 0);
        AssertFile(files[1], "old.txt", "deleted", 0, 2);
        var diff = (string)r.Metadata!["diff"]!;
        Assert.Contains("+++ b/new.txt", diff);
        Assert.Contains("--- a/old.txt\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-gone1\n-gone2\n", diff);
        Assert.False(File.Exists(Path.Combine(_dir, "old.txt")));
    }
}
