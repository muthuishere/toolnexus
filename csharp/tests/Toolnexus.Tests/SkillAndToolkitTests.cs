namespace Toolnexus.Tests;

public class SkillSourceTests
{
    [Fact]
    public async Task DiscoveryPromptAndSkillBlock()
    {
        var src = SkillSource.Load(TestFixtures.SkillsDir());

        Assert.True(src.Skills.ContainsKey("hello-world"));

        var prompt = src.Prompt();
        Assert.Contains("## Available Skills", prompt);
        Assert.Contains("hello-world", prompt);

        var res = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "hello-world" });
        Assert.False(res.IsError);
        var output = res.Output;
        Assert.StartsWith("<skill_content name=\"hello-world\">", output);
        Assert.Contains("# Skill: hello-world", output);
        Assert.Contains("Base directory for this skill: file://", output);
        Assert.Contains("<skill_files>", output);
        Assert.EndsWith("</skill_content>", output);
    }

    [Fact]
    public async Task UnknownSkillIsError()
    {
        var src = SkillSource.Load(TestFixtures.SkillsDir());
        var miss = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "nope" });
        Assert.True(miss.IsError);
        Assert.Contains("not found", miss.Output);
    }

    [Fact]
    public void DiscoveryFollowsSymlinkedSkillDirectories()
    {
        // Layout: root/ has a real skill (direct/) and a symlink (linked/) → an
        // out-of-tree skill dir. The walker must discover both (opencode parity).
        var root = TempDir();
        var direct = Path.Combine(root, "direct");
        Directory.CreateDirectory(direct);
        File.WriteAllText(Path.Combine(direct, "SKILL.md"),
            "---\nname: direct-skill\ndescription: d\n---\nbody\n");

        var external = TempDir();
        var target = Path.Combine(external, "linked-target");
        Directory.CreateDirectory(target);
        File.WriteAllText(Path.Combine(target, "SKILL.md"),
            "---\nname: linked-skill\ndescription: l\n---\nbody\n");

        try
        {
            Directory.CreateSymbolicLink(Path.Combine(root, "linked"), target);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or PlatformNotSupportedException)
        {
            // Symlink creation not permitted on this runner (e.g. Windows
            // without privilege) — treat as skipped (pass) rather than fail.
            // xUnit v2 has no dynamic Assert.Skip, so we return early.
            return;
        }

        var src = SkillSource.Load(root);
        Assert.True(src.Skills.ContainsKey("direct-skill"), "real skill discovered");
        Assert.True(src.Skills.ContainsKey("linked-skill"), "symlinked skill directory discovered");
    }

    // --- Frontmatter YAML block-scalar consensus matrix (SPEC.md §3) ---------
    // The parser must use a real YAML parser (YamlDotNet), so folded/literal
    // block scalars resolve correctly and byte-identically with the JS reference.

    private static string? DescOf(string skillMd)
    {
        var root = TempDir();
        Directory.CreateDirectory(Path.Combine(root, "s"));
        File.WriteAllText(Path.Combine(root, "s", "SKILL.md"), skillMd);
        var src = SkillSource.Load(root);
        return src.Skills.TryGetValue("t", out var info) ? info.Description : null;
    }

    [Fact]
    public void SingleLineDescription()
    {
        // No regression: a plain single-line description resolves verbatim.
        var desc = DescOf("---\nname: t\ndescription: hello world\n---\nbody\n");
        Assert.Equal("hello world", desc);
    }

    [Fact]
    public void FoldedBlockScalarJoinsWithSpaces()
    {
        // `description: >` folds newlines into spaces — must NOT capture ">".
        var desc = DescOf("---\nname: t\ndescription: >\n  first line\n  second line\n---\nbody\n");
        Assert.Equal("first line second line", desc);
        Assert.DoesNotContain(">", desc);
    }

    [Fact]
    public void LiteralBlockScalarPreservesNewlines()
    {
        // `description: |` keeps newlines (trailing chomp trimmed by .Trim()).
        var desc = DescOf("---\nname: t\ndescription: |\n  first line\n  second line\n---\nbody\n");
        Assert.Equal("first line\nsecond line", desc);
        Assert.DoesNotContain("|", desc);
    }

    [Fact]
    public void EmptyDescriptionDoesNotCrash()
    {
        var desc = DescOf("---\nname: t\ndescription:\n---\nbody\n");
        // Empty scalar → YAML null; not a scalar string, so no description entry.
        Assert.Null(desc);
    }

    [Fact]
    public void MalformedYamlDoesNotCrashDiscovery()
    {
        // Broken YAML must fail gracefully: empty frontmatter → skill has no name
        // → skipped, discovery keeps running (no throw).
        var root = TempDir();
        Directory.CreateDirectory(Path.Combine(root, "bad"));
        File.WriteAllText(Path.Combine(root, "bad", "SKILL.md"),
            "---\nname: t\ndescription: \"unterminated\n  : : : broken\n\t- nope\n---\nbody\n");
        // Also drop a valid skill alongside so we can assert discovery survived.
        Directory.CreateDirectory(Path.Combine(root, "good"));
        File.WriteAllText(Path.Combine(root, "good", "SKILL.md"),
            "---\nname: good\ndescription: ok\n---\nbody\n");

        var src = SkillSource.Load(root); // must not throw
        Assert.True(src.Skills.ContainsKey("good"), "discovery survived the malformed skill");
    }

    [Fact]
    public void RealHuddleStyleFoldedDescription()
    {
        // A real-world `name: huddle` + `description: >` skill (huddle, reqsume
        // kernel use this) — the folded text must resolve, not break.
        var skillMd =
            "---\n" +
            "name: huddle\n" +
            "description: >\n" +
            "  Runs a repo-aware expert huddle for engineering decisions,\n" +
            "  planning, research, verification, and spec capture.\n" +
            "---\n" +
            "body\n";
        var root = TempDir();
        Directory.CreateDirectory(Path.Combine(root, "huddle"));
        File.WriteAllText(Path.Combine(root, "huddle", "SKILL.md"), skillMd);
        var src = SkillSource.Load(root);

        Assert.True(src.Skills.ContainsKey("huddle"));
        Assert.Equal(
            "Runs a repo-aware expert huddle for engineering decisions, "
            + "planning, research, verification, and spec capture.",
            src.Skills["huddle"].Description);
    }

    // --- extend-skill-source: S1–S5 (SPEC.md §3) ---

    private static List<string> Keys(SkillSource s) => s.Skills.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();

    [Fact]
    public async Task S4_OnDiskBlockByteIdentical()
    {
        var src = SkillSource.Load(TestFixtures.SkillsDir());
        var res = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "hello-world" });
        var dir = Path.GetFullPath(Path.Combine(TestFixtures.SkillsDir(), "hello-world"));
        var content = src.Skills["hello-world"].Content.Trim();
        var want = string.Join("\n",
            "<skill_content name=\"hello-world\">",
            "# Skill: hello-world",
            "",
            content,
            "",
            "Base directory for this skill: " + SkillSource.PathToFileUrl(dir),
            "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
            "Note: file list is sampled.",
            "",
            "<skill_files>",
            "<file>" + Path.Combine(dir, "scripts", "greet.sh") + "</file>",
            "</skill_files>",
            "</skill_content>");
        Assert.Equal(want, res.Output);
    }

    [Fact]
    public async Task S1_DataSkillLogicalBaseAndResources()
    {
        var src = SkillSource.LoadWith(new SkillSource.LoadOptions
        {
            Skills = new List<SkillSource.SkillDef>
            {
                new("x", "d", "body", new List<string> { "scripts/foo.sh" }),
            },
        });
        Assert.True(src.Skills.ContainsKey("x"));
        Assert.Contains("**x**: d", src.Prompt());
        var res = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "x" });
        Assert.Contains("Base directory for this skill: skill://x/", res.Output);
        Assert.Contains("<file>scripts/foo.sh</file>", res.Output);
        Assert.DoesNotContain("file://", res.Output);
    }

    [Fact]
    public async Task S1_InstructionOnlyHasNoFilesBlock()
    {
        var src = SkillSource.LoadWith(new SkillSource.LoadOptions
        {
            Skills = new List<SkillSource.SkillDef> { new("y", "d", "just text") },
        });
        var res = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "y" });
        Assert.DoesNotContain("<skill_files>", res.Output);
        Assert.Contains("just text", res.Output);
    }

    [Fact]
    public async Task S1_DirDataFirstWins()
    {
        var src = SkillSource.LoadWith(new SkillSource.LoadOptions
        {
            Dirs = new List<string> { TestFixtures.SkillsDir() },
            Skills = new List<SkillSource.SkillDef> { new("hello-world", "shadow", "shadow body") },
        });
        var res = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "hello-world" });
        Assert.Contains("file://", res.Output);
        Assert.DoesNotContain("shadow body", res.Output);
    }

    [Fact]
    public void S2_FilterSemantics()
    {
        var defs = new List<SkillSource.SkillDef> { new("a", "A", "a"), new("b", "B", "b"), new("c", "C", "c") };
        SkillSource With(IReadOnlyDictionary<string, bool>? f) =>
            SkillSource.LoadWith(new SkillSource.LoadOptions { Skills = defs, Filter = f });
        Assert.Equal(new List<string> { "a", "b" }, Keys(With(new Dictionary<string, bool> { ["a"] = true, ["b"] = true })));
        Assert.Equal(new List<string> { "a", "b" }, Keys(With(new Dictionary<string, bool> { ["c"] = false })));
        Assert.Equal(new List<string> { "a" }, Keys(With(new Dictionary<string, bool> { ["a"] = true, ["nope"] = true })));
        Assert.Equal(new List<string> { "a", "b", "c" }, Keys(With(new Dictionary<string, bool>())));
    }

    [Fact]
    public void S3_ListSkillsTypedSkips()
    {
        var root = TempDir();
        void Mk(string name, string body)
        {
            var d = Path.Combine(root, name);
            Directory.CreateDirectory(d);
            File.WriteAllText(Path.Combine(d, "SKILL.md"), body);
        }
        Mk("good", "---\nname: good\ndescription: ok\n---\nbody");
        Mk("noname", "---\ndescription: no name\n---\nbody");
        Mk("bad", "---\nname: [unclosed\n---\nbody");
        var inv = SkillSource.ListSkills(new SkillSource.LoadOptions
        {
            Dirs = new List<string> { root },
            Skills = new List<SkillSource.SkillDef> { new("good", "dup", "dup") },
        });
        Assert.Single(inv.Skills);
        Assert.Equal("good", inv.Skills[0].Name);
        var reasons = inv.Skipped.Select(s => s.Reason).OrderBy(r => r, StringComparer.Ordinal).ToList();
        Assert.Equal(new List<string> { "duplicate-name", "malformed-frontmatter", "missing-name" }, reasons);
    }

    [Fact]
    public async Task S5_SampleCapAndDisable()
    {
        var defs = new List<SkillSource.SkillDef>
        {
            new("z", null, "z", new List<string> { "one", "two", "three" }),
        };
        var capped = await SkillSource.LoadWith(new SkillSource.LoadOptions { Skills = defs, SampleLimit = 2 })
            .Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "z" });
        Assert.Contains("<file>one</file>\n<file>two</file>", capped.Output);
        Assert.DoesNotContain("three", capped.Output);
        var off = await SkillSource.LoadWith(new SkillSource.LoadOptions { Skills = defs, SampleLimit = -1 })
            .Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "z" });
        Assert.DoesNotContain("<skill_files>", off.Output);
        Assert.DoesNotContain("Note: file list is sampled", off.Output);
    }

    private static string TempDir()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tnx-skill-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        return dir;
    }
}

public class ToolkitTests
{
    private static Task<Toolkit> Build()
        => Toolkit.CreateAsync(new Toolkit.Options().WithSkillsDir(TestFixtures.SkillsDir()));

    [Fact]
    public async Task RegisterGetExecuteAndDuplicateKeepsFirst()
    {
        await using var tk = await Build();
        tk.Register(NativeTool.Of("add", "", null, args =>
        {
            var a = Convert.ToInt32(args["a"]);
            var b = Convert.ToInt32(args["b"]);
            return (a + b).ToString();
        }));
        // duplicate name — must be ignored (first wins).
        tk.Register(NativeTool.Of("add", "dup", null, _ => "SHOULD_NOT_WIN"));

        Assert.NotNull(tk.Get("add"));
        Assert.NotNull(tk.Get("skill"));

        var r = await tk.ExecuteAsync("add", new Dictionary<string, object?> { ["a"] = 2, ["b"] = 3 });
        Assert.Equal("5", r.Output);
    }

    [Fact]
    public async Task UnknownToolIsError()
    {
        await using var tk = await Build();
        var miss = await tk.ExecuteAsync("ghost", new Dictionary<string, object?>());
        Assert.True(miss.IsError);
        Assert.Contains("Unknown tool", miss.Output);
    }

    [Fact]
    public async Task NoMcpMeansEmptyStatus()
    {
        await using var tk = await Build();
        Assert.Empty(tk.McpStatus());
    }

    // extend-skill-source (§3, S1/S2): toolkit wires data skills, a lazy provider
    // (failure isolated), and the per-agent filter.
    [Fact]
    public async Task SkillProviderFailureIsolatedAndDataSkillsWired()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            SkillsDir = new List<string> { TestFixtures.SkillsDir() },
            Skills = new List<SkillSource.SkillDef> { new("extra", "e", "e-body") },
            SkillProvider = () => throw new InvalidOperationException("boom"),
            Builtins = false,
        });
        Assert.Contains("hello-world", tk.SkillsPrompt());
        Assert.Contains("extra", tk.SkillsPrompt());
    }

    [Fact]
    public async Task SkillsFilterAppliedThroughToolkit()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Skills = new List<SkillSource.SkillDef> { new("a", "A", "a"), new("b", "B", "b") },
            SkillsFilter = new Dictionary<string, bool> { ["a"] = true },
            Builtins = false,
        });
        Assert.Contains("**a**", tk.SkillsPrompt());
        Assert.DoesNotContain("**b**", tk.SkillsPrompt());
    }
}
