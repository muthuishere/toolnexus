using System.Runtime.InteropServices;
using Toolnexus;
using Xunit;

namespace Toolnexus.Tests;

/// <summary>
/// The host boundary (ADR 0034, issues #100/#101/#102): which interpreter runs,
/// what a relative path means, and what a timeout kills.
///
/// Every assertion carries its control. The spike that produced these fixes twice
/// reported a clean kill from a broken probe, so "no orphan" is evidence only next
/// to a run proving the command can write the marker at all
/// (spikes/builtin-host-boundary/SPIKE.md §1).
/// </summary>
public class BuiltinHostBoundaryTests
{
    private static bool IsWindows => OperatingSystem.IsWindows();

    private static ITool Tool(object? cfg, string name)
        => BuiltinTools.Create(cfg).First(t => t.Name == name);

    private static Task<ToolResult> Run(ITool tool, Dictionary<string, object?> args, ToolContext? ctx = null)
        => tool.ExecuteAsync(args, ctx);

    private static string TempDir()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tn-boundary-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(dir);
        return dir;
    }

    /// <summary>
    /// The GRANDCHILD writes the marker, and `sleep 0.2` in front stops the shell
    /// exec-optimising the single command away — the difference between measuring
    /// an orphan and measuring nothing.
    /// </summary>
    private static string OrphanCommand(string marker) => $"sleep 0.2; sh -c 'sleep 1; touch {marker}'";

    // -----------------------------------------------------------------------
    // #102 — a timeout kills the job, not the shell
    // -----------------------------------------------------------------------

    [Fact]
    public async Task TimeoutKillsTheWholeJob()
    {
        if (IsWindows) return; // POSIX command shape; the Windows arm is in the spike
        var dir = TempDir();
        var marker = Path.Combine(dir, "orphan.marker");

        var res = await Run(Tool(null, "bash"), new() { ["command"] = OrphanCommand(marker), ["timeout"] = 300.0 });

        Assert.True(res.IsError);
        Assert.Contains("timed out", res.Output);
        Assert.Equal(true, res.Metadata?["timedOut"]);
        Assert.Equal(true, res.Metadata?["killedTree"]);

        await Task.Delay(2500);
        Assert.False(File.Exists(marker), "the grandchild outlived the kill");
    }

    [Fact]
    public async Task ControlTheProbeCommandCanWriteTheMarker()
    {
        if (IsWindows) return;
        var dir = TempDir();
        var marker = Path.Combine(dir, "control.marker");

        var res = await Run(Tool(null, "bash"), new() { ["command"] = OrphanCommand(marker), ["timeout"] = 20000.0 });
        Assert.False(res.IsError, res.Output);
        Assert.True(File.Exists(marker),
            "the command cannot write the marker at all — the orphan test proves nothing");
    }

    [Fact]
    public async Task CancellingTheCallStopsTheWork()
    {
        if (IsWindows) return;
        var dir = TempDir();
        var marker = Path.Combine(dir, "cancelled.marker");
        using var cts = new CancellationTokenSource(300);

        var res = await Run(Tool(null, "bash"),
            new() { ["command"] = OrphanCommand(marker), ["timeout"] = 60000.0 },
            new ToolContext(cancellationToken: cts.Token));

        Assert.True(res.IsError);
        Assert.Contains("cancelled", res.Output);
        Assert.Equal(false, res.Metadata?["timedOut"]);

        await Task.Delay(2500);
        Assert.False(File.Exists(marker), "cancellation left the job running");
    }

    // -----------------------------------------------------------------------
    // #100 — the interpreter is chosen, and reported
    // -----------------------------------------------------------------------

    [Fact]
    public async Task TheResolvedInterpreterIsReported()
    {
        var res = await Run(Tool(null, "bash"), new() { ["command"] = "echo hi" });
        Assert.NotNull(res.Metadata?["shell"]);
        if (!IsWindows) Assert.Equal("/bin/sh -c", res.Metadata?["shell"]);
    }

    [Fact]
    public async Task AHostSuppliedShellIsUsedVerbatim()
    {
        if (IsWindows) return;
        var cfg = new Dictionary<string, object?> { ["shell"] = new List<object?> { "/bin/sh", "-c" } };
        var res = await Run(Tool(cfg, "bash"), new() { ["command"] = "echo verbatim" });
        Assert.False(res.IsError, res.Output);
        Assert.Contains("verbatim", res.Output);
        Assert.Equal("/bin/sh -c", res.Metadata?["shell"]);
    }

    [Fact]
    public void DisablingBashMakesAnInterpreterUnnecessary()
    {
        var cfg = new Dictionary<string, object?>
        {
            ["tools"] = new Dictionary<string, object?> { ["bash"] = false },
        };
        Assert.DoesNotContain(BuiltinTools.Select(cfg), t => t.Name == "bash");
        Assert.NotEmpty(BuiltinTools.Shell(null));
    }

    // -----------------------------------------------------------------------
    // #101 — one base directory, and optional confinement
    // -----------------------------------------------------------------------

    [Fact]
    public async Task BaseDirScopesRelativePaths()
    {
        var baseDir = TempDir();
        var cfg = new Dictionary<string, object?> { ["baseDir"] = baseDir };

        var res = await Run(Tool(cfg, "write"), new() { ["path"] = "sub/nested.txt", ["content"] = "landed" });
        Assert.False(res.IsError, res.Output);
        Assert.True(File.Exists(Path.Combine(baseDir, "sub", "nested.txt")));
        Assert.False(File.Exists(Path.Combine(Directory.GetCurrentDirectory(), "sub", "nested.txt")),
            "relative write leaked into the process working directory");

        var read = await Run(Tool(cfg, "read"), new() { ["path"] = "sub/nested.txt" });
        Assert.Equal("landed", read.Output);
    }

    [Fact]
    public async Task AnAbsolutePathWithNoBaseDirBehavesAsBefore()
    {
        var dir = TempDir();
        var target = Path.Combine(dir, "absolute.txt");
        var res = await Run(Tool(null, "write"), new() { ["path"] = target, ["content"] = "x" });
        Assert.False(res.IsError, res.Output);
        Assert.True(File.Exists(target));
    }

    [Fact]
    public async Task ApplyPatchResolvesPathsInsideThePatchText()
    {
        var baseDir = TempDir();
        var patch = "*** Begin Patch\n*** Add File: pkg/new.txt\n+hello\n*** End Patch";
        var cfg = new Dictionary<string, object?> { ["baseDir"] = baseDir };

        var res = await Run(Tool(cfg, "apply_patch"), new() { ["patchText"] = patch });
        Assert.False(res.IsError, res.Output);
        Assert.True(File.Exists(Path.Combine(baseDir, "pkg", "new.txt")),
            "the path inside the patch text was not resolved");
    }

    [Fact]
    public async Task BashDefaultsItsWorkdirToBaseDir()
    {
        if (IsWindows) return;
        var baseDir = TempDir();
        File.WriteAllText(Path.Combine(baseDir, "marker.txt"), "x");
        var cfg = new Dictionary<string, object?> { ["baseDir"] = baseDir };

        var res = await Run(Tool(cfg, "bash"), new() { ["command"] = "ls marker.txt" });
        Assert.False(res.IsError, res.Output);
        Assert.Contains("marker.txt", res.Output);
    }

    [Fact]
    public async Task ConfinementRefusesEscapesAndStillServesWhatIsInside()
    {
        var root = TempDir();
        var baseDir = Path.Combine(root, "base");
        var outside = Path.Combine(root, "outside");
        Directory.CreateDirectory(baseDir);
        Directory.CreateDirectory(outside);
        File.WriteAllText(Path.Combine(outside, "secret.txt"), "secret");

        var cfg = new Dictionary<string, object?> { ["baseDir"] = baseDir, ["confineToBaseDir"] = true };
        var read = Tool(cfg, "read");

        foreach (var p in new[] { "../outside/secret.txt", Path.Combine(outside, "secret.txt") })
        {
            var res = await Run(read, new() { ["path"] = p });
            Assert.True(res.IsError, $"{p} should be refused");
            Assert.Contains("outside baseDir", res.Output);
        }

        File.WriteAllText(Path.Combine(baseDir, "ok.txt"), "fine");
        var inside = await Run(read, new() { ["path"] = "ok.txt" });
        Assert.False(inside.IsError, "a path inside baseDir must still be read");
    }

    [Fact]
    public async Task ConfinementFollowsSymlinksBeforeDeciding()
    {
        if (IsWindows) return; // symlinks need privilege there; junctions are covered in the spike
        var root = TempDir();
        var baseDir = Path.Combine(root, "base");
        var outside = Path.Combine(root, "outside");
        Directory.CreateDirectory(baseDir);
        Directory.CreateDirectory(outside);
        File.WriteAllText(Path.Combine(outside, "secret.txt"), "secret");
        Directory.CreateSymbolicLink(Path.Combine(baseDir, "link"), outside);

        var cfg = new Dictionary<string, object?> { ["baseDir"] = baseDir, ["confineToBaseDir"] = true };
        var res = await Run(Tool(cfg, "read"), new() { ["path"] = "link/secret.txt" });
        Assert.True(res.IsError, "a symlink out of baseDir must be refused");
    }

    [Fact]
    public async Task ConfinementCoversPathsThatDoNotExistYet()
    {
        var baseDir = TempDir();
        var cfg = new Dictionary<string, object?> { ["baseDir"] = baseDir, ["confineToBaseDir"] = true };
        var write = Tool(cfg, "write");

        var refused = await Run(write, new() { ["path"] = "../escape.txt", ["content"] = "x" });
        Assert.True(refused.IsError);

        var allowed = await Run(write, new() { ["path"] = "deep/new/file.txt", ["content"] = "x" });
        Assert.False(allowed.IsError, allowed.Output);
    }

    // -----------------------------------------------------------------------
    // §4A — grep emits the string it sorted by
    // -----------------------------------------------------------------------

    [Fact]
    public async Task GrepEmitsWalkRootRelativeForwardSlashedPaths()
    {
        var baseDir = TempDir();
        Directory.CreateDirectory(Path.Combine(baseDir, "tree", "sub"));
        File.WriteAllText(Path.Combine(baseDir, "tree", "sub", "a.txt"), "needle\n");

        var cfg = new Dictionary<string, object?> { ["baseDir"] = baseDir };
        var res = await Run(Tool(cfg, "grep"), new() { ["pattern"] = "needle", ["path"] = "tree" });
        Assert.False(res.IsError, res.Output);
        Assert.Equal("sub/a.txt:1:needle", res.Output);
        Assert.DoesNotContain("\\", res.Output);
    }
}
