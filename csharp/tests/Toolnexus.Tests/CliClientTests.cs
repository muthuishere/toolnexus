using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>
/// Hermetic tests for the CLI client (SPEC §8C): no network, no real agent CLI. Mirrors
/// golang/cli_test.go. The fakes live in tests/FakeCli (a fake one-shot CLI, a fake ACP agent, the
/// stdio MCP bridge), run as <c>dotnet FakeCli.dll …</c>, so <c>Command</c> is <c>dotnet</c> and the
/// dll path is the first style arg.
/// </summary>
public class CliClientTests
{
    private static string FakeDll()
    {
        var p = Path.Combine(AppContext.BaseDirectory, "FakeCli.dll");
        if (!File.Exists(p)) throw new InvalidOperationException("FakeCli.dll not found next to the tests — build tests/FakeCli");
        return p;
    }

    private static string Conformance(params string[] parts)
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir != null; dir = dir.Parent)
        {
            var c = Path.Combine(new[] { dir.FullName, "conformance" }.Concat(parts).ToArray());
            if (File.Exists(c) || Directory.Exists(c)) return c;
        }
        throw new InvalidOperationException("conformance/ not found");
    }

    private static string TempFile() => Path.Combine(Path.GetTempPath(), "tn-cli-test-" + Guid.NewGuid().ToString("N"));

    private static async Task<Toolkit> BadgeToolkit()
    {
        var badge = NativeTool.Of("get_badge_number", "Returns the badge number.",
            new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() },
            _ => "BADGE-77Q");
        return await Toolkit.CreateAsync(new Toolkit.Options
        {
            ExtraTools = new List<ITool> { badge },
            Builtins = false,
            SkillsDir = new List<string> { TestFixtures.SkillsDir() },
        });
    }

    private static CliOptions FakeCliOpts(string mode, params object?[] extra) => new()
    {
        Style = CliStyles.Custom,
        Command = "dotnet",
        Args = new object?[] { FakeDll() }.Concat(extra).ToList(),
        Prompt = "stdin",
        Answer = "stdout",
        Env = new Dictionary<string, string> { ["TN_FAKE_CLI"] = mode },
        Transport = CliTransports.Cli,
    };

    private static async Task<LlmClient.RunResult> RunCli(CliOptions o, Toolkit? tk)
    {
        using var c = CliClient.Create(o);
        return await c.RunAsync("What is my badge number?", tk);
    }

    // -----------------------------------------------------------------------
    // Shared data: styles and preamble are byte-identical to conformance/.
    // -----------------------------------------------------------------------

    [Fact]
    public void StylesMatchConformance()
    {
        var files = Directory.GetFiles(Conformance("cli-styles")).Where(f => Path.GetFileName(f) != "README.md").ToList();
        Assert.Equal(8, files.Count);
        foreach (var f in files)
        {
            var name = Path.GetFileName(f);
            Assert.True(File.ReadAllText(f) == CliClient.EmbeddedFile(name),
                $"csharp/src/Toolnexus/CliStyles/{name} differs from conformance/cli-styles (copy it)");
        }
        Assert.Equal(File.ReadAllText(Conformance("cli-styles", "preamble.txt")), CliClient.Preamble);
        foreach (var s in new[] { CliStyles.Claude, CliStyles.Codex, CliStyles.Copilot, CliStyles.Opencode, CliStyles.Devin, CliStyles.Gemini, CliStyles.Goose })
        {
            var d = CliClient.StyleDefFor(s);
            Assert.NotNull(d);
            Assert.Equal(CliClient.StyleName(s), d!.Name);
            Assert.False(string.IsNullOrEmpty(d.Verified), d.Name);
            // a built-in style constructs for the one-shot transport
            CliClient.Create(new CliOptions { Style = s, Command = "x", Transport = CliTransports.Cli }).Close();
        }
        Assert.Null(CliClient.StyleDefFor(CliStyles.Custom));
        Assert.Null(CliClient.StyleDefFor(default));
        Assert.Null(CliClient.StyleDefFor(CliStyles.Codex)!.Acp);
        Assert.Equal(".mcp.json", CliClient.StyleDefFor(CliStyles.Devin)!.Acp?.ProjectMcpFile);
        Assert.StartsWith("UNVERIFIED", CliClient.StyleDefFor(CliStyles.Goose)!.Verified);
    }

    [Fact]
    public void LoadStyleFromJsonAndPath()
    {
        var json = "{\"name\":\"mine\",\"args\":[\"-q\",[\"--model\",\"{{model}}\"]],\"prompt\":\"stdin\",\"answer\":\"stdout\",\"timeoutMs\":5,\"env\":{\"A\":\"b\"},\"acp\":{\"args\":[\"acp\"],\"systemHint\":\"H\"}}";
        var d = CliClient.LoadStyle(json);
        Assert.Equal("mine", d.Name);
        Assert.Equal(5, d.TimeoutMs);
        Assert.Equal("b", d.Env!["A"]);
        Assert.Equal("H", d.Acp!.SystemHint);
        var path = TempFile();
        File.WriteAllText(path, json);
        try { Assert.Equal("mine", CliClient.LoadStyle(path).Name); }
        finally { File.Delete(path); }
        Assert.StartsWith("cli style: ", Assert.Throws<CliException>(() => CliClient.LoadStyle("/no/such/style.json")).Message);
        Assert.Equal("cli: a group may only hold strings", Assert.Throws<CliException>(() => CliClient.Create(new CliOptions
        { Style = CliStyles.Custom, Command = "x", Args = new List<object?> { new List<object?> { 1L } }, Prompt = "stdin", Answer = "stdout" })).Message);
        Assert.Equal("cli: an arg must be a string or a group of strings", Assert.Throws<CliException>(() => CliClient.Create(new CliOptions
        { Style = CliStyles.Custom, Command = "x", Args = new List<object?> { 1L }, Prompt = "stdin", Answer = "stdout" })).Message);
    }

    // -----------------------------------------------------------------------
    // Construction errors.
    // -----------------------------------------------------------------------

    [Fact]
    public void ConstructionErrors()
    {
        (CliOptions O, string Want)[] cases =
        {
            (new() { Style = CliStyles.Claude }, "cli: Command is required (path or name of the CLI binary)"),
            (new() { Command = "x" }, "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"),
            (new() { Style = CliStyles.Goose, Command = "goose", Model = "m", Transport = CliTransports.Cli }, "cli: style \"goose\" takes no model"),
            (new() { Style = CliStyles.Codex, Command = "codex", Transport = CliTransports.Acp }, "cli: style \"codex\" has no ACP launch; set ACPCommand or use CliTransports.CLI"),
            (new() { Style = CliStyles.Custom, Command = "x", Args = new() { "{{model}}" }, Prompt = "stdin", Answer = "stdout" }, "cli: the style needs a model: {{model}} is outside an optional group"),
            (new() { Style = CliStyles.Custom, Command = "x", Args = new() { "{{nope}}" }, Prompt = "stdin", Answer = "stdout" }, "cli: unknown placeholder {{nope}}"),
            (new() { Style = CliStyles.Custom, Command = "x", Prompt = "arg", Answer = "stdout" }, "cli: prompt \"arg\" needs {{prompt}} in args"),
            (new() { Style = CliStyles.Custom, Command = "x", Prompt = "file", Answer = "stdout" }, "cli: prompt \"file\" needs {{file}} in args"),
            (new() { Style = CliStyles.Custom, Command = "x", Prompt = "stdin", Answer = "out" }, "cli: answer \"out\" needs {{out}} in args"),
            (new() { Style = CliStyles.Custom, Command = "x", Prompt = "pipe", Answer = "stdout" }, "cli: prompt must be stdin, arg or file, not \"pipe\""),
            (new() { Style = CliStyles.Custom, Command = "x", Prompt = "stdin", Answer = "json" }, "cli: answer must be stdout or out, not \"json\""),
        };
        foreach (var (o, want) in cases)
            Assert.Equal(want, Assert.Throws<CliException>(() => CliClient.Create(o)).Message);
    }

    // -----------------------------------------------------------------------
    // The envelope parser.
    // -----------------------------------------------------------------------

    [Fact]
    public void ParseReply()
    {
        (string In, string? Content, string? Tool)[] cases =
        {
            ("{\"content\":\"hi\"}", "hi", null),
            ("```json\n{\"content\":\"fenced\"}\n```\n\n", "fenced", null),
            ("Sure! {\"content\":\"wrapped\"} done", "wrapped", null),
            ("{\"choices\":[{\"message\":{\"content\":\"openai\"}}]}", "openai", null),
            ("{\"message\":{\"content\":\"msg\"}}", "msg", null),
            ("{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"t\",\"arguments\":\"{\\\"x\\\":1}\"}}]}", null, "t"),
            ("{\"tool_calls\":[{\"name\":\"flat\",\"arguments\":{\"x\":1}}]}", null, "flat"),
            ("plain text answer", "plain text answer", null),
            ("{\"content\":\"x\"} trailing {", "x", null),
            ("{\"answer\":1}", "{\"answer\":1}", null),
            ("{\"content\":\"x\"} {\"content\":\"y\"}", "{\"content\":\"x\"} {\"content\":\"y\"}", null),
        };
        foreach (var (input, content, tool) in cases)
        {
            var r = CliClient.ParseReply(input);
            if (tool != null)
            {
                Assert.True(r.ToolCalls is { Count: 1 } && r.ToolCalls[0].Name == tool, input);
                continue;
            }
            Assert.True(r.Content == content && r.ToolCalls is not { Count: > 0 }, $"{input}: {r.Content}");
        }
        Assert.StartsWith(CliClient.Preamble + "\nREQUEST:\n{\"messages\":[],\"tools\":[]}", CliClient.RenderPrompt(null, null));
    }

    // -----------------------------------------------------------------------
    // One-shot transport.
    // -----------------------------------------------------------------------

    [Fact]
    public async Task OneShotToolRoundTrip()
    {
        var rec = TempFile();
        try
        {
            var o = FakeCliOpts("tool");
            o.Env!["TN_FAKE_REC"] = rec;
            o.SystemPrompt = "You are a badge clerk.";
            await using var tk = await BadgeToolkit();
            var res = await RunCli(o, tk);
            Assert.Contains("BADGE-77Q", res.Text);
            Assert.Single(res.ToolCalls);
            Assert.Equal("get_badge_number", res.ToolCalls[0].Name);
            var prompts = File.ReadAllText(rec).Split("\n=====\n");
            Assert.StartsWith(CliClient.Preamble + "\nREQUEST:\n", prompts[0]);
            using var req = JsonDocument.Parse(prompts[0].Split("\nREQUEST:\n", 2)[1]);
            var first = req.RootElement.GetProperty("messages")[0];
            Assert.Equal("system", first.GetProperty("role").GetString());
            Assert.Contains("badge clerk", first.GetProperty("content").GetString());
            Assert.True(req.RootElement.GetProperty("tools").GetArrayLength() > 0);
        }
        finally { File.Delete(rec); }
    }

    [Fact]
    public async Task PromptModesAndOutFile()
    {
        await using var tk = await BadgeToolkit();
        foreach (var (args, prompt, answer) in new (object?[] Args, string Prompt, string Answer)[]
                 {
                     (new object?[] { "--p", "{{prompt}}" }, "arg", "stdout"),
                     (new object?[] { "--f", "{{file}}" }, "file", "stdout"),
                     (new object?[] { "--o", "{{out}}" }, "stdin", "out"),
                 })
        {
            var o = FakeCliOpts("tool", args);
            o.Prompt = prompt;
            o.Answer = answer;
            var before = Directory.GetFiles(Path.GetTempPath(), "toolnexus-cli-*").Length;
            var res = await RunCli(o, tk);
            Assert.True(res.Text.Contains("BADGE-77Q"), $"{prompt}/{answer}: {res.Text}");
            Assert.True(Directory.GetFiles(Path.GetTempPath(), "toolnexus-cli-*").Length <= before, "temp files must be removed");
        }
    }

    [Fact]
    public async Task ModelGroup()
    {
        var o = FakeCliOpts("x", "--argv", "-q", new List<object?> { "--model", "{{model}}" });
        var res = await RunCli(o, null);
        Assert.DoesNotContain("--model", res.Text);
        o.Model = "prov/m-1";
        res = await RunCli(o, null);
        Assert.Contains("--model prov/m-1", res.Text);
    }

    [Fact]
    public async Task PinnedErrors()
    {
        var noOut = FakeCliOpts("nooutfile", "--x", "{{out}}");
        noOut.Answer = "out";
        var slow = FakeCliOpts("sleep");
        slow.CallTimeoutMs = 300;
        var missing = FakeCliOpts("x");
        missing.Command = "definitely-not-a-cli-xyz";
        (CliOptions O, string Want)[] cases =
        {
            (FakeCliOpts("exit"), "cli: exit 3: boom: bad login"),
            (FakeCliOpts("empty"), "cli: empty reply"),
            (noOut, "cli: no output file"),
            (slow, "cli: timed out after 300ms"),
            (missing, "cli: command not found: definitely-not-a-cli-xyz"),
        };
        foreach (var (o, want) in cases)
        {
            var e = await Assert.ThrowsAnyAsync<Exception>(() => RunCli(o, null));
            Assert.Contains(want, e.ToString());
        }
    }

    [Fact]
    public async Task OneShotRefusesStreaming()
    {
        using var c = CliClient.Create(FakeCliOpts("x"));
        var e = await Assert.ThrowsAnyAsync<Exception>(() => c.StreamAsync("hi", null, _ => { }));
        Assert.Contains("cli: streaming is not supported by the one-shot CLI transport; use Run", e.ToString());
    }

    // -----------------------------------------------------------------------
    // ACP transport.
    // -----------------------------------------------------------------------

    private static async Task<(CliOptions O, string Rec, Toolkit Tk)> FakeAcpOpts(Dictionary<string, string> env)
    {
        var rec = TempFile();
        var e = new Dictionary<string, string> { ["TN_FAKE_ACP"] = "1", ["TN_FAKE_REC"] = rec };
        foreach (var (k, v) in env) e[k] = v;
        var d = new CliStyleDef
        {
            Name = "fake", Args = new List<object?> { FakeDll() }, Prompt = "stdin", Answer = "stdout",
            Acp = new CliAcpLaunch { Args = new List<string> { FakeDll(), "acp" } },
        };
        var tk = await BadgeToolkit();
        return (new CliOptions
        {
            StyleDef = d, Command = "dotnet", Env = e, Toolkit = tk,
            BridgeCommand = new List<string> { "dotnet", FakeDll(), "mcp-bridge" },
        }, rec, tk);
    }

    private static void AssertFakeAcpRun(CliClient c, LlmClient.RunResult res, string entry)
    {
        Assert.True(c.Transport == "acp", $"transport {c.Transport} ({c.FallbackReason})");
        foreach (var want in new[] { "perm ours=yes own=no fake=no again=yes", "entry=" + entry, "BADGE-77Q", "Hello" })
            Assert.True(res.Text.Contains(want), $"missing {want} in {res.Text}");
        Assert.Contains("\"name\":\"load_skill\"", res.Text);
        Assert.DoesNotContain("\"name\":\"skill\"", res.Text);
        var info = c.AcpSession();
        Assert.NotNull(info);
        Assert.Equal(2, info!.ServedCalls.Count);
    }

    [Fact]
    public async Task AcpHttpEntryWithBearerToken()
    {
        var (o, rec, tk) = await FakeAcpOpts(new() { ["FAKE_HTTP"] = "1" });
        await using var _tk = tk;
        using var c = CliClient.Create(o);
        var res = await c.RunAsync("badge?", null);
        AssertFakeAcpRun(c, res, "http");
        Assert.Contains("noauth=401", res.Text);
        var log = File.ReadAllText(rec);
        File.Delete(rec);
        Assert.Contains("\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer ", log);
        Assert.Contains("\"terminal\":false", log);
        Assert.DoesNotContain("Bearer ", Json.Stringify(c.AcpSession()!.McpServersSent));
    }

    [Fact]
    public async Task AcpStdioBridgeWhenAgentLacksHttp()
    {
        var (o, rec, tk) = await FakeAcpOpts(new() { ["FAKE_HTTP"] = "0" });
        await using var _tk = tk;
        using var c = CliClient.Create(o);
        var res = await c.RunAsync("badge?", null);
        AssertFakeAcpRun(c, res, "<nil>");
        var log = File.ReadAllText(rec);
        File.Delete(rec);
        Assert.Contains("\"env\":[{\"name\":\"TOOLNEXUS_MCP_TOKEN\"", log);
        Assert.DoesNotContain("\"type\":\"stdio\"", log);
    }

    [Fact]
    public async Task AcpProjectMcpFile()
    {
        var (o, rec, tk) = await FakeAcpOpts(new() { ["FAKE_HTTP"] = "0", ["FAKE_PROJECT"] = "1" });
        await using var _tk = tk;
        o.StyleDef!.Acp!.ProjectMcpFile = ".mcp.json";
        var c = CliClient.Create(o);
        var res = await c.RunAsync("badge?", null);
        File.Delete(rec);
        AssertFakeAcpRun(c, res, "http");
        var info = c.AcpSession()!;
        Assert.Empty(info.McpServersSent);
        Assert.False(string.IsNullOrEmpty(info.SessionCwd));
        var file = Path.Combine(info.SessionCwd!, ".mcp.json");
        Assert.True(File.Exists(file));
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(file));
        c.Close();
        Assert.False(Directory.Exists(info.SessionCwd), "temp session dir must be removed on Close");
        using var http = new HttpClient();
        await Assert.ThrowsAnyAsync<Exception>(() => http.PostAsync(info.Url, new StringContent("{}")));
    }

    [Fact]
    public async Task AcpModelPassThrough()
    {
        var (o, rec, tk) = await FakeAcpOpts(new() { ["FAKE_HTTP"] = "1", ["FAKE_NO_CONFIG"] = "1" });
        await using var _tk = tk;
        o.Model = "anthropic/claude-x";
        o.Transport = CliTransports.Acp; // the fake style's one-shot args take no model
        using var c = CliClient.Create(o);
        await c.RunAsync("badge?", null);
        var log = File.ReadAllText(rec);
        File.Delete(rec);
        Assert.Contains("session/set_config_option {\"configId\":\"model\",\"sessionId\":\"s1\",\"value\":\"anthropic/claude-x\"}", log);
        Assert.Contains("session/set_model {\"modelId\":\"anthropic/claude-x\",\"sessionId\":\"s1\"}", log);
    }

    [Fact]
    public async Task AcpFallsBackToOneShot()
    {
        var (o, _, tk) = await FakeAcpOpts(new() { ["FAKE_FAIL_INIT"] = "1", ["TN_FAKE_CLI"] = "tool" });
        await using var _tk = tk;
        using var c = CliClient.Create(o);
        Assert.Equal("auto", c.Transport);
        var res = await c.RunAsync("badge?", tk);
        Assert.Equal("cli", c.Transport);
        Assert.NotEqual("", c.FallbackReason);
        Assert.StartsWith("cli: acp: initialize: ", c.FallbackReason);
        Assert.Contains("agent exited before replying to initialize", c.FallbackReason);
        Assert.Contains("unknown flag: acp", c.FallbackReason);
        Assert.Contains("BADGE-77Q", res.Text);

        // Forced ACP never falls back.
        var (o2, _, tk2) = await FakeAcpOpts(new() { ["FAKE_FAIL_INIT"] = "1" });
        await using var _tk2 = tk2;
        o2.Transport = CliTransports.Acp;
        using var c2 = CliClient.Create(o2);
        var e = await Assert.ThrowsAnyAsync<Exception>(() => c2.RunAsync("badge?", null));
        Assert.Contains("cli: acp: ", e.ToString());
    }

    [Fact]
    public void IsServedCallExactForms()
    {
        var served = new HashSet<string> { "get_badge_number", "load_skill" };
        var cases = new Dictionary<string, bool>
        {
            ["get_badge_number"] = true,
            ["mcp__toolnexus__get_badge_number"] = true,
            ["toolnexus/get_badge_number"] = true,
            ["get_badge_number (toolnexus MCP Server)"] = true,
            ["Calling get_badge_number from toolnexus"] = true,
            ["load_skill(name: secret-recipe)"] = true,
            ["skill"] = false,
            ["Bash"] = false,
            [""] = false,
            ["mcp__other__get_badge_number"] = false,
            ["run get_badge_number and then rm -rf"] = false,
            ["Calling get_badge_number from toolnexus2"] = false,
            ["get_badge_number (evil MCP Server)"] = false,
            ["mcp__toolnexus__get_badge_number_and_more"] = false,
        };
        foreach (var (title, want) in cases)
            Assert.True(CliClient.IsServedCall(title, served) == want, title);
    }

    [Fact]
    public async Task AcpSystemHintFromStyle()
    {
        Assert.Contains("\"toolnexus\"", CliClient.StyleDefFor(CliStyles.Devin)!.Acp!.SystemHint);
        var (o, rec, tk) = await FakeAcpOpts(new() { ["FAKE_HTTP"] = "1" });
        await using var _tk = tk;
        o.StyleDef!.Acp!.SystemHint = "HINT-USE-TOOLNEXUS";
        o.SystemPrompt = "You are terse.";
        using (var c = CliClient.Create(o)) await c.RunAsync("badge?", null);
        var log = File.ReadAllText(rec);
        File.Delete(rec);
        Assert.Contains("You are terse.\\n\\nHINT-USE-TOOLNEXUS", log);

        var got = CliClient.WithSystemHint(new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = "x" } }, "H");
        Assert.Equal(2, got.Count);
        Assert.Equal("H", ((Dictionary<string, object?>)got[0]!)["content"]);
        var msgs = new List<object?> { new Dictionary<string, object?> { ["role"] = "system", ["content"] = "S" } };
        CliClient.WithSystemHint(msgs, "H");
        Assert.Equal("S", ((Dictionary<string, object?>)msgs[0]!)["content"]);
    }

    [Fact]
    public async Task McpBridgeRefusesWithoutToken()
    {
        // The bridge itself, against the served endpoint, with a wrong token: a loud error.
        var (o, rec, tk) = await FakeAcpOpts(new() { ["FAKE_HTTP"] = "1" });
        await using var _tk = tk;
        using var c = CliClient.Create(o);
        await c.RunAsync("badge?", null);
        File.Delete(rec);
        var url = c.AcpSession()!.Url;
        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => CliClient.RunMcpBridgeAsync(url, "wrong",
            new StringReader("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}\n"), new StringWriter()));
        Assert.Contains("unauthorized", e.Message);
    }

    // -----------------------------------------------------------------------
    // conformance/cli-client/cases.json — the fixture every port replays.
    // -----------------------------------------------------------------------

    private static string Norm(object? v)
    {
        if (v is string s)
        {
            try { v = Json.ParseLoose(s); } catch { /* keep the string */ }
        }
        return Json.Stringify(Json.ParseLoose(Json.Stringify(v)));
    }

    [Fact]
    public void ConformanceCases()
    {
        var fx = Json.ParseObjectLoose(File.ReadAllText(Conformance("cli-client", "cases.json")));
        var byName = Enum.GetValues<CliStyles>().ToDictionary(CliClient.StyleName);

        foreach (var c in ((List<object?>)fx["argv"]!).Cast<Dictionary<string, object?>>())
        {
            var cl = CliClient.Create(new CliOptions
            {
                Style = byName[(string)c["style"]!], Command = (string)c["command"]!,
                Model = (string)c["model"]!, Transport = CliTransports.Cli,
            });
            var tmp = new List<string>();
            var argv = cl.CliArgv((string)c["prompt"]!, tmp, out var outPath);
            foreach (var f in tmp) File.Delete(f);
            for (var i = 0; i < argv.Count; i++)
            {
                if (outPath != null && argv[i] == outPath) argv[i] = "<out>";
                else if (tmp.Count > 0 && argv[i] == tmp[0]) argv[i] = "<file>";
            }
            Assert.Equal(Json.Stringify(c["argv"]), Json.Stringify(argv));
            Assert.Equal((bool)c["stdin"]!, cl.Style.Prompt == "stdin");
        }

        foreach (var c in ((List<object?>)fx["parse"]!).Cast<Dictionary<string, object?>>())
        {
            var input = (string)c["in"]!;
            var r = CliClient.ParseReply(input);
            if (c.TryGetValue("toolCall", out var tcv) && tcv is Dictionary<string, object?> tc)
            {
                Assert.True(r.ToolCalls is { Count: 1 }, input);
                var got = r.ToolCalls![0];
                Assert.Equal((string)tc["name"]!, got.Name);
                Assert.Equal(tc.TryGetValue("id", out var id) ? id as string : null, got.Id);
                Assert.Equal(Norm(tc["arguments"]), Norm(got.Arguments));
                continue;
            }
            Assert.True(r.ToolCalls is not { Count: > 0 }, input);
            Assert.Equal((string)c["content"]!, r.Content ?? "");
        }

        foreach (var c in ((List<object?>)fx["constructionErrors"]!).Cast<Dictionary<string, object?>>())
        {
            var opts = (Dictionary<string, object?>)c["options"]!;
            var o = new CliOptions();
            if (opts.TryGetValue("style", out var s)) o.Style = byName[(string)s!];
            o.Command = opts.TryGetValue("command", out var cmd) ? (string?)cmd : null;
            o.Model = opts.TryGetValue("model", out var m) ? (string)m! : "";
            o.Prompt = opts.TryGetValue("prompt", out var p) ? (string?)p : null;
            o.Answer = opts.TryGetValue("answer", out var a) ? (string?)a : null;
            if (opts.TryGetValue("args", out var args)) o.Args = (List<object?>)args!;
            o.Transport = opts.TryGetValue("transport", out var t) ? t switch
            {
                "cli" => CliTransports.Cli,
                "acp" => CliTransports.Acp,
                _ => CliTransports.Auto,
            } : CliTransports.Auto;
            Assert.Equal((string)c["error"]!, Assert.Throws<CliException>(() => CliClient.Create(o)).Message);
        }

        Assert.Equal(((string)fx["renderEmpty"]!).Replace("<PREAMBLE>", CliClient.Preamble), CliClient.RenderPrompt(null, null));
    }
}
